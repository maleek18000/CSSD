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

        // Ensure mainUrl is clean
        mainUrl = fixSourceUrl(mainUrl)

        // Parse LoadData — try data class first, fall back to tree model
        val loadData = tryParseJson<LoadData>(data)
            ?: try {
                // Tree model fallback: parse just the fields we need
                val node = mapper.readTree(data)
                LoadData(
                    type = node?.get("type")?.asText(),
                    id = node?.get("id")?.asText(),
                    season = node?.get("season")?.asInt(),
                    episode = node?.get("episode")?.asInt(),
                    title = node?.get("title")?.asText(),
                    imdbId = node?.get("imdbId")?.asText()
                )
            } catch (e: Exception) {
                Log.e("StremioC", "Failed to parse LoadData: ${e.message}")
                null
            }

        if (loadData == null) {
            Log.e("StremioC", "Failed to parse LoadData from: ${data.take(200)}")
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

        // Extract clean IMDB ID (strip :season:episode suffix from loadData.id)
        val cleanImdbId = loadData.imdbId
            ?: loadData.id?.substringBefore(":")?.takeIf { it.startsWith("tt") }

        // Construct the IPTV stream URL from the addon
        val streamType = loadData.type ?: "movie"
        val streamId = loadData.id ?: ""
        val streamUrl = "$mainUrl/stream/$streamType/$streamId.json"
        Log.d("StremioC", "IPTV stream URL: $streamUrl")

        // === Fetch IPTV streams using tree model (most robust, never fails on unknown fields) ===
        var linksFound = false

        try {
            val response = app.get(streamUrl, timeout = 30)
            Log.d("StremioC", "Response: code=${response.code}, length=${response.text.length}")

            if (response.isSuccessful && response.text.isNotEmpty()) {
                val count = parseStreamsFromTree(response.text, subtitleCallback, callback)
                if (count > 0) linksFound = true
                Log.d("StremioC", "Parsed $count IPTV streams")
            } else {
                Log.w("StremioC", "Stream URL returned HTTP ${response.code}")
            }
        } catch (e: Exception) {
            Log.e("StremioC", "IPTV stream fetch failed: ${e.message}")
        }

        // Also try Torrentio as extra source (parallel, non-blocking)
        if (cleanImdbId != null) {
            try {
                invokeMainSource(
                    "https://torrentio.strem.fun",
                    name,
                    cleanImdbId,
                    loadData.season,
                    loadData.episode,
                    subtitleCallback,
                    callback
                )
                linksFound = true
            } catch (_: Exception) {}
        }

        // Load subtitles
        try {
            loadSubtitles(cleanImdbId, loadData.season, loadData.episode, loadData.title, subtitleCallback)
        } catch (_: Exception) {}

        Log.d("StremioC", "=== loadLinks END: linksFound=$linksFound ===")
        return linksFound
    }

    /**
     * Parse IPTV streams from JSON using Jackson tree model.
     * This is the PRIMARY parsing method — it never fails on unknown JSON fields.
     * Direct stream URLs (IPTV), YouTube, external URLs, and subtitles are all handled.
     */
    private suspend fun parseStreamsFromTree(
        jsonText: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Int {
        var count = 0
        try {
            val rootNode = mapper.readTree(jsonText)
            val streamsNode = rootNode?.get("streams")
            if (streamsNode == null || !streamsNode.isArray) {
                Log.w("StremioC", "No 'streams' array in JSON response. Keys: ${rootNode?.fieldNames()?.asSequence()?.take(10)?.joinToString()}")
                return 0
            }

            val totalStreams = streamsNode.size()
            Log.d("StremioC", "Parsing $totalStreams streams from response")

            for (streamNode in streamsNode) {
                try {
                    val url = streamNode.get("url")?.asText()
                    val ytId = streamNode.get("ytId")?.asText()
                    val externalUrl = streamNode.get("externalUrl")?.asText()
                    val streamName = streamNode.get("name")?.asText() ?: ""
                    val streamTitle = streamNode.get("title")?.asText() ?: ""
                    val streamDesc = streamNode.get("description")?.asText() ?: ""

                    // IPTV direct URL — this is the main stream type
                    if (url != null) {
                        val fixedName = fixSourceName(streamName, streamTitle, streamDesc)
                        val qualityTitle = buildExtractedTitle(extractSpecs(fixedName))
                        val headers = extractHeadersFromNode(streamNode)

                        Log.d("StremioC", "Found IPTV URL: name=$streamName, title=$streamTitle, url=${url.take(80)}...")

                        callback.invoke(
                            newExtractorLink(
                                source = streamName.ifBlank { qualityTitle },
                                name = qualityTitle.ifBlank { streamTitle },
                                url = url,
                                type = null  // auto-detect M3U8 vs VIDEO
                            ) {
                                this.referer = ""
                                this.quality = getQuality(listOf(streamDesc, streamTitle, streamName))
                                this.headers = headers
                            }
                        )
                        count++
                    }

                    // YouTube stream
                    if (ytId != null) {
                        loadExtractor("https://www.youtube.com/watch?v=$ytId", subtitleCallback, callback)
                        count++
                    }

                    // External URL (e.g. web player)
                    if (externalUrl != null) {
                        loadExtractor(externalUrl, subtitleCallback, callback)
                        count++
                    }

                    // Embedded subtitles
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
