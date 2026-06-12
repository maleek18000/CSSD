package com.stremio

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Interceptor

class StremioC(
    override var mainUrl: String,
    override var name: String
) : MainAPI() {

    override val hasMainPage = true
    override var lang = "vi"
    override val supportedTypes = setOf(TvType.Others)

    init {
        try {
            val context = CloudStreamApp.context
            if (context != null) {
                val prefs = context.getSharedPreferences("stremio_prefs", 0)
                val localeStr = prefs.getString("app_locale", null)
                if (localeStr != null) {
                    lang = localeStr
                }
            }
        } catch (e: Exception) {
        }
        if (lang.isEmpty()) lang = "vi"
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        mainUrl = fixSourceUrl(mainUrl)
        val manifest = tryParseJson<Manifest>(app.get("$mainUrl/manifest.json", timeout = 15).text)
        val catalogs = manifest?.catalogs ?: return newHomePageResponse(emptyList())
        val homePageLists = coroutineScope {
            catalogs.map { catalog ->
                async(Dispatchers.IO) {
                    catalog.toHomePageList(this@StremioC)
                }
            }.awaitAll().filterNotNull()
        }
        return newHomePageResponse(homePageLists, true)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        mainUrl = fixSourceUrl(mainUrl)
        val manifest = tryParseJson<Manifest>(app.get("$mainUrl/manifest.json", timeout = 15).text)
        val catalogs = manifest?.catalogs ?: return null
        return catalogs.flatMap { it.search(query, this) }
    }

    override suspend fun load(url: String): LoadResponse? {
        val entry = tryParseJson<CatalogEntry>(url) ?: return null

        // Use a LOCAL variable for the metadata URL.
        // For standard IMDB movie/series entries, cinemeta provides richer metadata.
        // We do NOT change mainUrl because loadLinks() needs the ADDON URL for streams.
        val metaBaseUrl = if ((entry.type == "movie" || entry.type == "series") && isImdborTmdb(entry.id)) {
            "https://v3-cinemeta.strem.io"
        } else {
            fixSourceUrl(mainUrl)
        }

        val metaUrl = "$metaBaseUrl/meta/${entry.type}/${entry.id}.json"
        val metaResponse = tryParseJson<CatalogResponse>(app.get(metaUrl, timeout = 15).text)
        val detailedEntry = metaResponse?.meta ?: entry

        val imdbId = if (detailedEntry.id.startsWith("tt")) detailedEntry.id else null

        return if (detailedEntry.videos.isNullOrEmpty()) {
            newMovieLoadResponse(
                detailedEntry.name,
                "$metaBaseUrl/meta/${detailedEntry.type}/${detailedEntry.id}.json",
                TvType.Movie,
                LoadData(detailedEntry.type, detailedEntry.id, imdbId = imdbId).toJson()
            ) {
                posterUrl = detailedEntry.poster
                backgroundPosterUrl = detailedEntry.background
                plot = detailedEntry.description
                year = detailedEntry.yearNum?.toIntOrNull()
                tags = detailedEntry.genre ?: detailedEntry.genres
                score = Score.from10(detailedEntry.imdbRating)
                addImdbId(imdbId)
                addActors(detailedEntry.cast)
            }
        } else {
            val episodes = detailedEntry.videos.map { it.toEpisode(this, detailedEntry.type, imdbId) }
            newTvSeriesLoadResponse(
                detailedEntry.name,
                "$metaBaseUrl/meta/${detailedEntry.type}/${detailedEntry.id}.json",
                TvType.TvSeries,
                episodes
            ) {
                posterUrl = detailedEntry.poster
                backgroundPosterUrl = detailedEntry.background
                plot = detailedEntry.description
                year = detailedEntry.yearNum?.toIntOrNull()
                tags = detailedEntry.genre ?: detailedEntry.genres
                score = Score.from10(detailedEntry.imdbRating)
                addImdbId(imdbId)
                addActors(detailedEntry.cast)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("StremioC", "=== loadLinks START ===")

        // Ensure mainUrl is clean (remove /manifest.json, fix stremio:// protocol)
        // This should already be the ADDON URL since load() no longer overrides it to cinemeta
        mainUrl = fixSourceUrl(mainUrl)

        // Parse LoadData
        val loadData = try {
            mapper.readValue(data, LoadData::class.java)
        } catch (e: Exception) {
            Log.e("StremioC", "Failed to parse LoadData: ${e.message}")
            return false
        }

        Log.d("StremioC", "LoadData: type=${loadData.type}, id=${loadData.id}, season=${loadData.season}, episode=${loadData.episode}, imdbId=${loadData.imdbId}")

        // Set subtitle auto-select language
        try {
            val context = CloudStreamApp.context
            val prefs = context?.getSharedPreferences("stremio_prefs", 0)
            val subsAutoSelect = prefs?.getString("app_locale", null)
            if (subsAutoSelect != null) {
                AcraApplication.setKey("subs_auto_select", subsAutoSelect)
            }
        } catch (_: Exception) {}

        // Construct stream URL — EXACTLY matching original
        // Original uses: $mainUrl/stream/${type}/${id}.json
        // where id comes from Video.id (already contains :season:episode for series)
        val streamType = loadData.type ?: "movie"
        val streamId = loadData.id ?: ""
        val streamUrl = "$mainUrl/stream/$streamType/$streamId.json"

        Log.d("StremioC", "Stream URL: $streamUrl")

        var linksFound = 0

        // Fetch streams — use tryParseJson<StreamsResponse> like the original
        try {
            val response = app.get(streamUrl, timeout = 15)
            Log.d("StremioC", "Response: code=${response.code}, length=${response.text.length}")

            if (response.isSuccessful && response.text.isNotEmpty()) {
                val streamsResponse = tryParseJson<StreamsResponse>(response.text)
                val streams = streamsResponse?.streams

                if (streams != null) {
                    Log.d("StremioC", "Found ${streams.size} streams")
                    for (stream in streams) {
                        try {
                            processStream(stream, subtitleCallback, callback)
                            linksFound++
                        } catch (e: Exception) {
                            Log.e("StremioC", "Failed to process stream: ${e.message}")
                        }
                    }
                } else {
                    Log.w("StremioC", "No streams array in response")
                    // Fallback to tree model if tryParseJson fails (unknown fields issue)
                    val treeCount = parseStreamsFromTree(response.text, subtitleCallback, callback)
                    linksFound += treeCount
                    Log.d("StremioC", "Tree model fallback found $treeCount links")
                }
            } else {
                Log.w("StremioC", "Stream URL failed: HTTP ${response.code}")
            }
        } catch (e: Exception) {
            Log.e("StremioC", "Stream fetch exception: ${e.message}")
        }

        // Torrentio fallback
        val imdbForFallback = loadData.imdbId ?: if (streamId.startsWith("tt")) streamId else null
        if (imdbForFallback != null) {
            try {
                val fallbackUrl = if (loadData.season != null) {
                    "https://torrentio.strem.fun/stream/series/$imdbForFallback:${loadData.season}:${loadData.episode ?: 1}.json"
                } else {
                    "https://torrentio.strem.fun/stream/movie/$imdbForFallback.json"
                }
                Log.d("StremioC", "Torrentio URL: $fallbackUrl")
                val fallbackResponse = app.get(fallbackUrl, timeout = 10)
                if (fallbackResponse.isSuccessful && fallbackResponse.text.isNotEmpty()) {
                    val fallbackStreams = tryParseJson<StreamsResponse>(fallbackResponse.text)?.streams
                    if (fallbackStreams != null) {
                        for (stream in fallbackStreams) {
                            try {
                                processStream(stream, subtitleCallback, callback)
                                linksFound++
                            } catch (_: Exception) {}
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("StremioC", "Torrentio fallback failed: ${e.message}")
            }
        }

        // Subtitles
        try {
            loadSubtitles(loadData.imdbId, loadData.season, loadData.episode, loadData.title, subtitleCallback)
        } catch (e: Exception) {
            Log.e("StremioC", "Subtitle loading failed: ${e.message}")
        }

        Log.d("StremioC", "=== loadLinks END: linksFound=$linksFound ===")
        return linksFound > 0
    }

    /**
     * Process a single Stream object — matches original logic.
     * Handles url, ytId, externalUrl, infoHash, and embedded subtitles.
     */
    private suspend fun processStream(
        stream: Stream,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val url = stream.url
        val ytId = stream.ytId
        val externalUrl = stream.externalUrl
        val infoHash = stream.infoHash
        val streamName = stream.name ?: ""
        val streamTitle = stream.title ?: ""
        val streamDesc = stream.description ?: ""

        // Process URL streams
        if (url != null) {
            val fixedName = fixSourceName(streamName, streamTitle, streamDesc)
            val qualityTitle = buildExtractedTitle(extractSpecs(fixedName))

            // Extract headers from behaviorHints
            val headers = extractHeadersFromStream(stream)

            callback.invoke(
                newExtractorLink(
                    source = streamName.ifBlank { qualityTitle },
                    name = qualityTitle.ifBlank { streamTitle },
                    url = url,
                    type = null  // auto-detect
                ) {
                    this.referer = ""
                    this.quality = getQuality(listOf(streamDesc, streamTitle, streamName))
                    this.headers = headers
                }
            )
        }

        // Process YouTube streams
        if (ytId != null) {
            loadExtractor("https://www.youtube.com/watch?v=$ytId", subtitleCallback, callback)
        }

        // Process external URL streams
        if (externalUrl != null) {
            loadExtractor(externalUrl, subtitleCallback, callback)
        }

        // Process torrent/infoHash streams
        if (infoHash != null) {
            val magnet = generateMagnetLink(infoHash)
            val displayName = streamTitle.ifBlank { streamName }
            val extractedTitle = buildExtractedTitle(extractSpecs(displayName))
            val fullTitle = "$extractedTitle$displayName"

            val sizeInfo = when {
                fullTitle.contains("\uD83D\uDCBE") && fullTitle.contains("\uD83D\uDC64") -> {
                    val sizeIdx = fullTitle.indexOf("\uD83D\uDCBE")
                    val userIdx = fullTitle.indexOf("\uD83D\uDC64")
                    if (sizeIdx >= userIdx) fullTitle.substringAfter("\uD83D\uDC64")
                    else fullTitle.substringAfter("\uD83D\uDCBE")
                }
                fullTitle.contains("\uD83D\uDCBE") -> fullTitle.substringAfter("\uD83D\uDCBE")
                fullTitle.contains("\uD83D\uDC64") -> fullTitle.substringAfter("\uD83D\uDC64")
                fullTitle.contains("Name: ") -> fullTitle.substringBefore("Size")
                else -> extractedTitle
            }

            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = sizeInfo.ifBlank { extractedTitle },
                    url = magnet,
                    type = ExtractorLinkType.TORRENT
                ) {
                    this.referer = ""
                    this.quality = getQuality(listOf(streamDesc, streamTitle, streamName))
                }
            )
        }

        // Process embedded subtitles
        val subtitles = stream.subtitles
        if (subtitles != null) {
            for (sub in subtitles) {
                if (sub.url != null && sub.lang != null) {
                    try {
                        subtitleCallback.invoke(SubtitleFile(sub.lang, sub.url))
                    } catch (_: Exception) {}
                }
            }
        }
    }

    /**
     * Extract headers from Stream.behaviorHints — matches original.
     */
    private fun extractHeadersFromStream(stream: Stream): Map<String, String> {
        val bh = stream.behaviorHints ?: return emptyMap()
        try {
            // Try proxyHeaders.request first
            val proxyReq = bh.proxyHeaders?.request
            if (proxyReq != null && proxyReq.isNotEmpty()) {
                return proxyReq.mapValues { (_, v) ->
                    if (v is List<*>) v.firstOrNull()?.toString() ?: "" else v.toString()
                }
            }
            // Fallback to behaviorHints.headers
            val headers = bh.headers
            if (headers != null && headers.isNotEmpty()) {
                return headers.mapValues { (_, v) ->
                    if (v is List<*>) v.firstOrNull()?.toString() ?: "" else v.toString()
                }
            }
        } catch (_: Exception) {}
        return emptyMap()
    }

    /**
     * Fallback: Parse streams using Jackson tree model.
     * Used when tryParseJson<StreamsResponse> fails (e.g., unknown JSON fields).
     */
    private suspend fun parseStreamsFromTree(
        jsonText: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Int {
        var count = 0
        try {
            val rootNode = mapper.readTree(jsonText)
            val streamsNode = rootNode?.get("streams") ?: return 0
            if (!streamsNode.isArray) return 0

            for (streamNode in streamsNode) {
                try {
                    val url = streamNode.get("url")?.asText()
                    val ytId = streamNode.get("ytId")?.asText()
                    val externalUrl = streamNode.get("externalUrl")?.asText()
                    val infoHash = streamNode.get("infoHash")?.asText()
                    val streamName = streamNode.get("name")?.asText() ?: ""
                    val streamTitle = streamNode.get("title")?.asText() ?: ""
                    val streamDesc = streamNode.get("description")?.asText() ?: ""

                    if (url != null) {
                        val fixedName = fixSourceName(streamName, streamTitle, streamDesc)
                        val qualityTitle = buildExtractedTitle(extractSpecs(fixedName))
                        val headers = extractHeadersFromNode(streamNode)

                        callback.invoke(
                            newExtractorLink(
                                source = streamName.ifBlank { qualityTitle },
                                name = qualityTitle.ifBlank { streamTitle },
                                url = url,
                                type = null
                            ) {
                                this.referer = ""
                                this.quality = getQuality(listOf(streamDesc, streamTitle, streamName))
                                this.headers = headers
                            }
                        )
                        count++
                    }

                    if (ytId != null) {
                        loadExtractor("https://www.youtube.com/watch?v=$ytId", subtitleCallback, callback)
                        count++
                    }

                    if (externalUrl != null) {
                        loadExtractor(externalUrl, subtitleCallback, callback)
                        count++
                    }

                    if (infoHash != null) {
                        val magnet = generateMagnetLink(infoHash)
                        val displayName = streamTitle.ifBlank { streamName }
                        val extractedTitle = buildExtractedTitle(extractSpecs(displayName))

                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = extractedTitle.ifBlank { displayName },
                                url = magnet,
                                type = ExtractorLinkType.TORRENT
                            ) {
                                this.referer = ""
                                this.quality = getQuality(listOf(streamDesc, streamTitle, streamName))
                            }
                        )
                        count++
                    }

                    val subtitlesNode = streamNode.get("subtitles")
                    if (subtitlesNode != null && subtitlesNode.isArray) {
                        for (subNode in subtitlesNode) {
                            val subUrl = subNode.get("url")?.asText()
                            val subLang = subNode.get("lang")?.asText()
                            if (subUrl != null && subLang != null) {
                                try {
                                    subtitleCallback.invoke(SubtitleFile(subLang, subUrl))
                                } catch (_: Exception) {}
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("StremioC", "Failed to process one stream: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e("StremioC", "Failed to parse streams JSON: ${e.message}")
        }
        return count
    }

    private fun extractHeadersFromNode(streamNode: com.fasterxml.jackson.databind.JsonNode): Map<String, String> {
        try {
            val bh = streamNode.get("behaviorHints") ?: return emptyMap()
            val proxyReq = bh.get("proxyHeaders")?.get("request")
            if (proxyReq != null && proxyReq.isObject) {
                val headers = mutableMapOf<String, String>()
                proxyReq.fields().forEach { entry ->
                    val value = entry.value
                    headers[entry.key] = if (value.isArray) {
                        value.firstOrNull()?.asText() ?: ""
                    } else {
                        value.asText()
                    }
                }
                return headers
            }
            val headersNode = bh.get("headers")
            if (headersNode != null && headersNode.isObject) {
                val headers = mutableMapOf<String, String>()
                headersNode.fields().forEach { entry ->
                    val value = entry.value
                    headers[entry.key] = if (value.isArray) {
                        value.firstOrNull()?.asText() ?: ""
                    } else {
                        value.asText()
                    }
                }
                return headers
            }
        } catch (_: Exception) {}
        return emptyMap()
    }

    private suspend fun loadSubtitles(
        imdbId: String?,
        season: Int?,
        episode: Int?,
        title: String?,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        coroutineScope {
            listOf(
                async(Dispatchers.IO) {
                    try {
                        withTimeoutOrNull(5_000L) {
                            invokeStremio(imdbId, season, episode, subtitleCallback)
                        }
                    } catch (_: Exception) {}
                },
                async(Dispatchers.IO) {
                    try {
                        withTimeoutOrNull(5_000L) {
                            invokeOpensub(imdbId, season, episode, subtitleCallback)
                        }
                    } catch (_: Exception) {}
                },
                async(Dispatchers.IO) {
                    try {
                        withTimeoutOrNull(5_000L) {
                            invokeSubsource(title, imdbId, season, episode, subtitleCallback)
                        }
                    } catch (_: Exception) {}
                },
                async(Dispatchers.IO) {
                    try {
                        withTimeoutOrNull(5_000L) {
                            invokeWatchsomuch(imdbId, season, episode, subtitleCallback)
                        }
                    } catch (_: Exception) {}
                }
            ).awaitAll()
        }
    }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? = null

    // ==================== Data Classes ====================
    // @JsonProperty annotations match the original decompiled code exactly

    data class LoadData(
        val type: String? = null,
        val id: String? = null,
        val season: Int? = null,
        val episode: Int? = null,
        val title: String? = null,
        val imdbId: String? = null
    )

    data class Manifest(
        val catalogs: List<Catalog>? = null
    )

    data class Catalog(
        var name: String? = null,
        val id: String,
        val type: String? = null,
        val types: MutableList<String> = mutableListOf()
    ) {
        init {
            if (type != null) types.add(type)
        }

        suspend fun search(query: String, provider: StremioC): List<SearchResponse> {
            val entries = mutableListOf<SearchResponse>()
            types.forEach { type ->
                try {
                    var skip = 0
                    var pagesFetched = 0
                    var hasMore = true
                    while (hasMore && pagesFetched < 2) {
                        val url = if (skip == 0) {
                            "${provider.mainUrl}/catalog/$type/${id}/search=${query.encodeUri()}.json"
                        } else {
                            "${provider.mainUrl}/catalog/$type/${id}/search=${query.encodeUri()}/skip=$skip.json"
                        }
                        val res = tryParseJson<CatalogResponse>(
                            app.get(url, timeout = 15).text
                        ) ?: break
                        if (res.metas.isNullOrEmpty()) {
                            hasMore = false
                        } else {
                            res.metas.forEach { entry ->
                                entries.add(entry.toSearchResponse(provider))
                            }
                            skip += res.metas.size
                            pagesFetched++
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            return entries
        }

        suspend fun toHomePageList(provider: StremioC): HomePageList? {
            val entries = mutableListOf<SearchResponse>()
            types.forEach { type ->
                try {
                    val url = "${provider.mainUrl}/catalog/$type/${id.encodeUri()}.json"
                    val res = tryParseJson<CatalogResponse>(
                        app.get(url, timeout = 15).text
                    )
                    if (res?.metas != null) {
                        res.metas.forEach { entry ->
                            entries.add(entry.toSearchResponse(provider))
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            if (entries.isEmpty()) return null
            return HomePageList(
                "${types.joinToString { it }} - ${name ?: id}",
                entries
            )
        }
    }

    data class CatalogResponse(
        val metas: List<CatalogEntry>? = null,
        val meta: CatalogEntry? = null
    )

    data class CatalogEntry(
        val name: String,
        val id: String,
        val poster: String? = null,
        val background: String? = null,
        val description: String? = null,
        val imdbRating: String? = null,
        val type: String? = null,
        val videos: List<Video>? = null,
        val genre: List<String>? = null,
        val genres: List<String>? = null,
        val cast: List<String>? = null,
        @JsonProperty("year")
        val yearNum: String? = null,
        @JsonProperty("trailers")
        val trailersSources: ArrayList<Trailer>? = ArrayList()
    ) {
        fun toSearchResponse(provider: StremioC): SearchResponse {
            return provider.newMovieSearchResponse(
                fixTitle(name),
                this.toJson(),
                TvType.Others
            ) {
                posterUrl = poster
            }
        }
    }

    data class Video(
        val id: String? = null,
        val title: String? = null,
        val name: String? = null,
        @JsonProperty("season")
        val seasonNumber: Int? = null,
        val number: Int? = null,
        val episode: Int? = null,
        val thumbnail: String? = null,
        val overview: String? = null,
        val description: String? = null
    ) {
        fun toEpisode(provider: StremioC, type: String?, imdbId: String?): Episode {
            val epNum = episode ?: number
            val epName = name ?: title
            val loadData = LoadData(type, id, seasonNumber, epNum, epName, imdbId)
            return provider.newEpisode(loadData.toJson()) {
                this.name = title
                this.posterUrl = thumbnail
                this.description = overview ?: this.description
                this.season = seasonNumber
                this.episode = number
            }
        }
    }

    data class Trailer(
        val source: String? = null,
        val type: String? = null
    )

    // StreamsResponse — matches original exactly with @JsonIgnoreProperties(ignoreUnknown = true)
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    data class StreamsResponse(
        val streams: List<Stream>? = null
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    data class Stream(
        val name: String? = null,
        val title: String? = null,
        val url: String? = null,
        val description: String? = null,
        val ytId: String? = null,
        val externalUrl: String? = null,
        val behaviorHints: BehaviorHints? = null,
        val infoHash: String? = null,
        val sources: List<String> = emptyList(),
        val subtitles: List<Subtitle>? = emptyList()
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    data class BehaviorHints(
        val headers: Map<String, Any>? = null,
        val proxyHeaders: ProxyHeaders? = null
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    data class ProxyHeaders(
        val request: Map<String, Any>? = null
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    data class Subtitle(
        val lang: String? = null,
        val url: String? = null
    )

    data class CustomSite(
        val parentJavaClass: String,
        val name: String,
        val url: String,
        val lang: String
    )
}
