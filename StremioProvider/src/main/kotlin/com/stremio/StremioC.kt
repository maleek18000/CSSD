package com.stremio

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
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
        val manifest = tryParseJson<Manifest>(app.get("$mainUrl/manifest.json").text)
            ?: return newHomePageResponse(emptyList(), false)
        val lists = mutableListOf<HomePageList>()
        manifest.catalogs?.amap { catalog ->
            catalog.toHomePageList(this)?.let { lists.add(it) }
        }
        return newHomePageResponse(lists, true)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        mainUrl = fixSourceUrl(mainUrl)
        val manifest = tryParseJson<Manifest>(app.get("$mainUrl/manifest.json").text) ?: return null
        val list = mutableListOf<SearchResponse>()
        manifest.catalogs?.amap { catalog ->
            list.addAll(catalog.search(query, this))
        }
        return list.distinct()
    }

    override suspend fun load(url: String): LoadResponse {
        val entry = mapper.readValue(url, CatalogEntry::class.java)

        val baseUrl = if ((entry.type == "movie" || entry.type == "series") && isImdborTmdb(entry.id)) {
            "https://v3-cinemeta.strem.io"
        } else {
            mainUrl
        }

        val metaUrl = "$baseUrl/meta/${entry.type}/${entry.id}.json"
        val response = tryParseJson<CatalogResponse>(app.get(metaUrl).text)
        val meta = response?.meta ?: throw RuntimeException(url)

        return meta.toLoadResponse(this, entry.id)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        mainUrl = fixSourceUrl(mainUrl)
        Log.d("StremioC", "=== loadLinks START ===")
        Log.d("StremioC", "mainUrl=$mainUrl")
        Log.d("StremioC", "raw data=$data")

        // Parse the episode data
        val loadData = try {
            mapper.readValue(data, LoadData::class.java)
        } catch (e: Exception) {
            Log.e("StremioC", "Failed to parse LoadData from: $data", e)
            // Try fallback: parse as raw string to extract what we can
            return tryFallbackLoadLinks(data, subtitleCallback, callback)
        }

        Log.d("StremioC", "Parsed LoadData: type=${loadData.type}, id=${loadData.id}, season=${loadData.season}, episode=${loadData.episode}, imdbId=${loadData.imdbId}")

        // Set subtitle auto-select language
        try {
            val context = CloudStreamApp.context
            val prefs = context?.getSharedPreferences("stremio_prefs", 0)
            val subsAutoSelect = prefs?.getString("app_locale", null)
            if (subsAutoSelect != null) {
                AcraApplication.setKey("subs_auto_select", subsAutoSelect)
            }
        } catch (_: Exception) {}

        var foundLinks = false

        // Strategy 1: Try the stream URL based on type and id from catalog
        val streamType = loadData.type ?: if (loadData.season != null) "series" else "movie"
        val streamId = loadData.id ?: loadData.imdbId ?: ""
        val streamUrls = buildStreamUrls(mainUrl, streamType, streamId, loadData.season, loadData.episode)

        for (url in streamUrls) {
            try {
                Log.d("StremioC", "Trying stream URL: $url")
                val response = app.get(url, timeout = 15)
                Log.d("StremioC", "Response code: ${response.code}, body length: ${response.text.length}")

                if (response.isSuccessful && response.text.isNotEmpty()) {
                    val streamsResponse = tryParseJson<StreamsResponse>(response.text)
                    val streams = streamsResponse?.streams
                    Log.d("StremioC", "Parsed ${streams?.size ?: 0} streams from response")

                    if (!streams.isNullOrEmpty()) {
                        for (stream in streams) {
                            try {
                                processStream(stream, subtitleCallback, callback)
                                foundLinks = true
                            } catch (e: Exception) {
                                Log.e("StremioC", "Error processing stream: ${e.message}")
                            }
                        }
                        if (foundLinks) break // Don't try more URLs if we found links
                    } else {
                        // Try manual JSON parsing if tryParseJson returns null/empty
                        Log.d("StremioC", "tryParseJson returned no streams, trying manual parse")
                        val manualResult = tryManualStreamParse(response.text, subtitleCallback, callback)
                        if (manualResult) {
                            foundLinks = true
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("StremioC", "Stream URL failed: $url - ${e.message}")
            }
        }

        // Strategy 2: Try invokeMainSource as fallback
        if (!foundLinks) {
            Log.d("StremioC", "No links from direct URLs, trying invokeMainSource fallback")
            try {
                invokeMainSource(
                    mainUrl, name,
                    loadData.imdbId ?: streamId,
                    loadData.season, loadData.episode,
                    subtitleCallback, callback
                )
                // invokeMainSource doesn't return a boolean, so we assume it might have found links
            } catch (e: Exception) {
                Log.e("StremioC", "invokeMainSource failed: ${e.message}")
            }
        }

        // Strategy 3: Try torrentio as last resort
        if (!foundLinks) {
            Log.d("StremioC", "Trying torrentio fallback")
            try {
                invokeMainSource(
                    "https://torrentio.strem.fun", name,
                    loadData.imdbId ?: streamId,
                    loadData.season, loadData.episode,
                    subtitleCallback, callback
                )
            } catch (e: Exception) {
                Log.e("StremioC", "Torrentio fallback failed: ${e.message}")
            }
        }

        // Load subtitles with timeout protection
        try {
            loadSubtitles(loadData.imdbId, loadData.season, loadData.episode, loadData.title, subtitleCallback)
        } catch (e: Exception) {
            Log.e("StremioC", "Subtitle loading failed: ${e.message}")
        }

        Log.d("StremioC", "=== loadLinks END, foundLinks=$foundLinks ===")
        return true
    }

    /**
     * Build multiple candidate stream URLs to try.
     * Different addons may use different ID formats.
     */
    private fun buildStreamUrls(
        baseUrl: String,
        type: String?,
        id: String,
        season: Int?,
        episode: Int?
    ): List<String> {
        val urls = mutableListOf<String>()
        val cleanId = id.split(":").firstOrNull() ?: id

        // URL 1: Use id as-is (might already contain :season:episode)
        if (id.contains(":")) {
            urls.add("$baseUrl/stream/$type/$id.json")
        }

        // URL 2: Construct with separate season/episode
        if (season != null && episode != null) {
            urls.add("$baseUrl/stream/series/$cleanId:$season:$episode.json")
        } else if (season != null) {
            urls.add("$baseUrl/stream/series/$cleanId:$season:1.json")
        }

        // URL 3: Simple format without compound ID
        if (!id.contains(":")) {
            urls.add("$baseUrl/stream/$type/$id.json")
        }

        // URL 4: Try "movie" type if series fails (some addons use different type names)
        if (type == "series" && season != null && episode != null) {
            urls.add("$baseUrl/stream/movie/$cleanId.json")
        }

        return urls.distinct()
    }

    /**
     * Manual JSON parsing fallback if tryParseJson fails.
     * Handles cases where the JSON structure is slightly different.
     */
    private suspend fun tryManualStreamParse(
        responseText: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val jsonObj = mapper.readTree(responseText)
            val streamsNode = jsonObj.get("streams") ?: return false
            if (!streamsNode.isArray || streamsNode.size() == 0) return false

            var found = false
            for (streamNode in streamsNode) {
                try {
                    val url = streamNode.get("url")?.asText()
                    val ytId = streamNode.get("ytId")?.asText()
                    val externalUrl = streamNode.get("externalUrl")?.asText()
                    val infoHash = streamNode.get("infoHash")?.asText()
                    val streamTitle = streamNode.get("title")?.asText() ?: streamNode.get("name")?.asText() ?: ""
                    val streamName = streamNode.get("name")?.asText() ?: ""

                    when {
                        url != null -> {
                            val isM3u8 = url.endsWith(".m3u8")
                            val linkType = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            var referer = ""
                            try {
                                val headers = streamNode.get("behaviorHints")
                                    ?.get("proxyHeaders")?.get("request")
                                referer = headers?.get("referer")?.asText()
                                    ?: headers?.get("origin")?.asText() ?: ""
                            } catch (_: Exception) {}

                            callback.invoke(
                                newExtractorLink(
                                    source = streamName.ifBlank { streamTitle },
                                    name = streamTitle.ifBlank { streamName },
                                    url = url,
                                    type = linkType
                                ) {
                                    this.referer = referer
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                            found = true
                        }
                        ytId != null -> {
                            loadExtractor("https://www.youtube.com/watch?v=$ytId", subtitleCallback, callback)
                            found = true
                        }
                        externalUrl != null -> {
                            loadExtractor(externalUrl, subtitleCallback, callback)
                            found = true
                        }
                        infoHash != null -> {
                            val magnet = generateMagnetLink(infoHash)
                            callback.invoke(
                                newExtractorLink(
                                    source = name,
                                    name = streamTitle.ifBlank { streamName },
                                    url = magnet,
                                    type = ExtractorLinkType.MAGNET
                                ) {
                                    this.quality = getQualityFromName(streamTitle)
                                }
                            )
                            found = true
                        }
                    }

                    // Handle subtitles in streams
                    val subtitlesNode = streamNode.get("subtitles")
                    if (subtitlesNode != null && subtitlesNode.isArray) {
                        for (subNode in subtitlesNode) {
                            val subUrl = subNode.get("url")?.asText()
                            val subLang = subNode.get("lang")?.asText()
                            if (subUrl != null && subLang != null) {
                                subtitleCallback.invoke(SubtitleFile(subLang, subUrl))
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("StremioC", "Manual parse failed for one stream: ${e.message}")
                }
            }
            return found
        } catch (e: Exception) {
            Log.e("StremioC", "Manual JSON parse failed: ${e.message}")
            return false
        }
    }

    /**
     * Fallback when LoadData parsing fails completely.
     * Tries to use the raw data string to construct a stream URL.
     */
    private suspend fun tryFallbackLoadLinks(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("StremioC", "Trying fallback loadLinks with raw data")

        // Try to extract an ID from the data string
        val imdbMatch = Regex("tt\\d+").find(data)
        val imdbId = imdbMatch?.value

        if (imdbId != null) {
            try {
                invokeMainSource(mainUrl, name, imdbId, null, null, subtitleCallback, callback)
            } catch (e: Exception) {
                Log.e("StremioC", "Fallback invokeMainSource failed: ${e.message}")
            }
            try {
                invokeMainSource("https://torrentio.strem.fun", name, imdbId, null, null, subtitleCallback, callback)
            } catch (e: Exception) {
                Log.e("StremioC", "Fallback torrentio failed: ${e.message}")
            }
        }

        return true
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
                        withTimeoutOrNull(8_000L) {
                            invokeStremio(imdbId, season, episode, subtitleCallback)
                        }
                    } catch (_: Exception) {}
                },
                async(Dispatchers.IO) {
                    try {
                        withTimeoutOrNull(8_000L) {
                            invokeOpensub(imdbId, season, episode, subtitleCallback)
                        }
                    } catch (_: Exception) {}
                },
                async(Dispatchers.IO) {
                    try {
                        withTimeoutOrNull(8_000L) {
                            invokeSubsource(title, imdbId, season, episode, subtitleCallback)
                        }
                    } catch (_: Exception) {}
                },
                async(Dispatchers.IO) {
                    try {
                        withTimeoutOrNull(8_000L) {
                            invokeWatchsomuch(imdbId, season, episode, subtitleCallback)
                        }
                    } catch (_: Exception) {}
                }
            ).awaitAll()
        }
    }

    private suspend fun processStream(
        stream: Stream,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val streamName = stream.title ?: stream.name ?: ""

        when {
            stream.url != null -> {
                var referer: String? = null
                try {
                    val headers = stream.behaviorHints?.proxyHeaders?.request
                    referer = headers?.get("referer") ?: headers?.get("origin")
                } catch (_: Exception) {}

                val isM3u8 = stream.url.endsWith(".m3u8")
                val linkType = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                callback.invoke(
                    newExtractorLink(
                        source = streamName,
                        name = streamName,
                        url = stream.url,
                        type = linkType
                    ) {
                        this.referer = referer ?: ""
                        this.quality = Qualities.Unknown.value
                    }
                )
            }

            stream.ytId != null -> {
                loadExtractor("https://www.youtube.com/watch?v=${stream.ytId}", subtitleCallback, callback)
            }

            stream.externalUrl != null -> {
                loadExtractor(stream.externalUrl, subtitleCallback, callback)
            }

            stream.infoHash != null -> {
                val magnet = generateMagnetLink(stream.infoHash)
                val displayName = stream.title ?: stream.name ?: ""
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
                    else -> ""
                }

                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = sizeInfo.ifBlank { extractedTitle },
                        url = magnet,
                        type = ExtractorLinkType.MAGNET
                    ) {
                        this.quality = getQualityFromName(displayName)
                    }
                )
            }

            else -> {
                Log.w("StremioC", "Stream has no url/ytId/externalUrl/infoHash: $streamName")
            }
        }

        // Handle subtitles embedded in streams
        stream.subtitles?.forEach { sub ->
            if (sub.url != null && sub.lang != null) {
                try {
                    subtitleCallback.invoke(SubtitleFile(sub.lang, sub.url))
                } catch (e: Exception) {
                    Log.e("StremioC", "SubtitleFile creation failed: ${e.message}")
                }
            }
        }
    }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? = null

    // ==================== StremioC-specific Data Classes ====================

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
                    var hasMore = true
                    while (hasMore) {
                        val url = if (skip == 0) {
                            "${provider.mainUrl}/catalog/$type/${id}/search=${query.encodeUri()}.json"
                        } else {
                            "${provider.mainUrl}/catalog/$type/${id}/search=${query.encodeUri()}/skip=$skip.json"
                        }
                        val res = tryParseJson<CatalogResponse>(
                            app.get(url, timeout = 120).text
                        ) ?: break
                        if (res.metas.isNullOrEmpty()) {
                            hasMore = false
                        } else {
                            res.metas.forEach { entry ->
                                entries.add(entry.toSearchResponse(provider))
                            }
                            skip += res.metas.size
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
                    var skip = 0
                    var hasMore = true
                    while (hasMore) {
                        val url = if (skip == 0) {
                            "${provider.mainUrl}/catalog/$type/${id.encodeUri()}.json"
                        } else {
                            "${provider.mainUrl}/catalog/$type/${id.encodeUri()}/skip=$skip.json"
                        }
                        val res = tryParseJson<CatalogResponse>(
                            app.get(url, timeout = 120).text
                        ) ?: break
                        if (res.metas.isNullOrEmpty()) {
                            hasMore = false
                        } else {
                            res.metas.forEach { entry ->
                                entries.add(entry.toSearchResponse(provider))
                            }
                            skip += res.metas.size
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

        suspend fun toLoadResponse(provider: StremioC, entryId: String): LoadResponse {
            return if (videos.isNullOrEmpty()) {
                provider.newMovieLoadResponse(
                    name,
                    "${provider.mainUrl}/meta/${type}/${id}.json",
                    TvType.Movie,
                    LoadData(type, id, imdbId = entryId).toJson()
                ) {
                    posterUrl = poster
                    plot = description
                    tags = genres ?: genre
                }
            } else {
                val episodes = videos.map { it.toEpisode(provider, type, entryId) }
                provider.newTvSeriesLoadResponse(
                    name,
                    "${provider.mainUrl}/meta/${type}/${id}.json",
                    TvType.TvSeries,
                    episodes
                ) {
                    posterUrl = poster
                    plot = description
                    tags = genres ?: genre
                }
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
        fun toEpisode(provider: StremioC, type: String?, entryId: String): Episode {
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

    data class LoadData(
        val type: String? = null,
        val id: String? = null,
        val season: Int? = null,
        val episode: Int? = null,
        val title: String? = null,
        val imdbId: String? = null
    )

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
