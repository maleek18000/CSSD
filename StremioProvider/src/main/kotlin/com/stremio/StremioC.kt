package com.stremio

import android.util.Log
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
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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

        val metaUrl = "${mainUrl}/meta/${entry.type}/${entry.id}.json"
        val metaResponse = tryParseJson<CatalogResponse>(app.get(metaUrl, timeout = 15).text)
        val detailedEntry = metaResponse?.meta ?: entry

        val imdbId = if (detailedEntry.id.startsWith("tt")) detailedEntry.id else null

        return if (detailedEntry.videos.isNullOrEmpty()) {
            newMovieLoadResponse(
                detailedEntry.name,
                metaUrl,
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
                metaUrl,
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
        Log.d("StremioC", "raw data=$data")

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

        // Construct stream URL
        val streamType = loadData.type ?: "movie"
        val streamId = loadData.id ?: ""
        val streamUrl = "$mainUrl/stream/$streamType/$streamId.json"

        Log.d("StremioC", "Stream URL: $streamUrl")

        // === FAST PATH: Fetch primary IPTV streams and return IMMEDIATELY ===
        // For IPTV addons, the primary /stream/ endpoint returns direct URLs.
        // We fetch ONLY that and return as fast as possible — no Torrentio, no subtitles blocking.
        // Torrentio and subtitles run in background (fire-and-forget) so they don't slow down playback.
        var linksFound = 0

        // 1) Primary stream fetch — this is the ONLY thing we wait for
        try {
            val response = app.get(streamUrl, timeout = 10)
            Log.d("StremioC", "Primary response: code=${response.code}, length=${response.text.length}")

            if (response.isSuccessful && response.text.isNotEmpty()) {
                linksFound = parseStreamsFromJson(response.text, subtitleCallback, callback)
                Log.d("StremioC", "Primary: $linksFound links")
            } else {
                Log.w("StremioC", "Primary failed: HTTP ${response.code}")
            }
        } catch (e: Exception) {
            Log.e("StremioC", "Primary fetch exception: ${e.message}")
        }

        // 2) Fire-and-forget: Torrentio fallback + subtitles — DON'T block playback
        //    These run in background and add links/subs as they arrive.
        //    CloudStream already shows links as callbacks fire, so users can start
        //    watching IPTV immediately while extras load in background.
        try {
            val imdbForFallback = loadData.imdbId ?: if (streamId.startsWith("tt")) streamId else null
            if (imdbForFallback != null) {
                GlobalScope.launch(Dispatchers.IO) {
                    try {
                        fetchFromAddon(
                            "https://torrentio.strem.fun",
                            name,
                            imdbForFallback,
                            loadData.season,
                            loadData.episode,
                            subtitleCallback,
                            callback
                        )
                    } catch (_: Exception) {}
                }
            }

            // Subtitles — fire and forget
            GlobalScope.launch(Dispatchers.IO) {
                try {
                    loadSubtitles(loadData.imdbId, loadData.season, loadData.episode, loadData.title, subtitleCallback)
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}

        Log.d("StremioC", "=== loadLinks END: linksFound=$linksFound ===")
        return linksFound > 0
    }

    /**
     * Parse streams from JSON using Jackson tree model.
     * This NEVER fails on unknown fields like cacheMaxAge, fileIdx, bingeGroup, etc.
     * Returns the number of links found.
     */
    private suspend fun parseStreamsFromJson(
        jsonText: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Int {
        var count = 0
        try {
            val rootNode = mapper.readTree(jsonText)
            val streamsNode = rootNode?.get("streams") ?: return 0
            if (!streamsNode.isArray) return 0

            Log.d("StremioC", "Found ${streamsNode.size()} stream entries in JSON")

            for (streamNode in streamsNode) {
                try {
                    val url = streamNode.get("url")?.asText()
                    val ytId = streamNode.get("ytId")?.asText()
                    val externalUrl = streamNode.get("externalUrl")?.asText()
                    val infoHash = streamNode.get("infoHash")?.asText()
                    val streamName = streamNode.get("name")?.asText() ?: ""
                    val streamTitle = streamNode.get("title")?.asText() ?: ""
                    val streamDesc = streamNode.get("description")?.asText() ?: ""

                    // Process URL streams
                    if (url != null) {
                        val fixedName = fixSourceName(streamName, streamTitle, streamDesc)
                        val qualityTitle = buildExtractedTitle(extractSpecs(fixedName))

                        // Extract headers from behaviorHints (tree model - very forgiving)
                        val headers = extractHeadersFromNode(streamNode)

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
                        count++
                    }

                    // Process YouTube streams
                    if (ytId != null) {
                        loadExtractor("https://www.youtube.com/watch?v=$ytId", subtitleCallback, callback)
                        count++
                    }

                    // Process external URL streams
                    if (externalUrl != null) {
                        loadExtractor(externalUrl, subtitleCallback, callback)
                        count++
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
                        count++
                    }

                    // Process subtitles embedded in streams
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

    /**
     * Extract headers from behaviorHints in a stream JsonNode.
     * Tries proxyHeaders.request first, then behaviorHints.headers.
     * Uses tree model so it works with ANY JSON structure.
     */
    private fun extractHeadersFromNode(streamNode: com.fasterxml.jackson.databind.JsonNode): Map<String, String> {
        try {
            val bh = streamNode.get("behaviorHints") ?: return emptyMap()

            // Try proxyHeaders.request first
            val proxyReq = bh.get("proxyHeaders")?.get("request")
            if (proxyReq != null && proxyReq.isObject) {
                val headers = mutableMapOf<String, String>()
                proxyReq.fields().forEach { entry ->
                    val value = entry.value
                    // Handle both string values and array values (take first element)
                    headers[entry.key] = if (value.isArray) {
                        value.firstOrNull()?.asText() ?: ""
                    } else {
                        value.asText()
                    }
                }
                return headers
            }

            // Fallback to behaviorHints.headers
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

    /**
     * Fetch streams from a Stremio addon by constructing the stream URL.
     * Uses the correct /stream/series/ or /stream/movie/ path.
     * Returns the number of links found.
     */
    private suspend fun fetchFromAddon(
        baseUrl: String,
        providerName: String,
        imdbId: String,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Int {
        if (imdbId.isBlank()) return 0

        val fixedUrl = fixSourceUrl(baseUrl)

        // Construct stream URL - use correct type (series vs movie)
        val streamUrl = if (season != null) {
            "$fixedUrl/stream/series/$imdbId:$season:${episode ?: 1}.json"
        } else {
            "$fixedUrl/stream/movie/$imdbId.json"
        }

        Log.d("StremioC", "fetchFromAddon URL: $streamUrl")

        try {
            val response = app.get(streamUrl, timeout = 10)
            if (!response.isSuccessful || response.text.isEmpty()) return 0

            // Use tree model for robust parsing
            return parseStreamsFromJson(response.text, subtitleCallback, callback)
        } catch (e: Exception) {
            Log.e("StremioC", "fetchFromAddon failed: ${e.message}")
            return 0
        }
    }

    /**
     * Load subtitles with timeout protection.
     */
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

    /**
     * LoadData: passed from load() to loadLinks() via newEpisode/newMovieLoadResponse.
     * The 'id' field comes from Video.id in the Stremio meta response.
     * For series, Video.id typically contains :season:episode suffix (e.g., "tt1234567:1:1")
     * The stream URL is: $mainUrl/stream/${type}/${id}.json
     */
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
        val yearNum: String? = null,
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
        val seasonNumber: Int? = null,
        val number: Int? = null,
        val episode: Int? = null,
        val thumbnail: String? = null,
        val overview: String? = null,
        val description: String? = null
    ) {
        /**
         * Create an Episode with LoadData containing the stream info.
         * Video.id from the Stremio meta response already contains :season:episode
         * for series (e.g., "tt1234567:1:1"), so the stream URL will be correct.
         */
        fun toEpisode(provider: StremioC, type: String?, entryId: String?): Episode {
            val epNum = episode ?: number
            val epName = name ?: title
            val loadData = LoadData(type, id, seasonNumber, epNum, epName, entryId)
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

    data class CustomSite(
        val parentJavaClass: String,
        val name: String,
        val url: String,
        val lang: String
    )
}
