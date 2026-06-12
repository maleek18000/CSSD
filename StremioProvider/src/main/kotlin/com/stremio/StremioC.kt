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
        val manifest = tryParseJson<Manifest>(app.get("$mainUrl/manifest.json", timeout = 15).text)
        val catalogs = manifest?.catalogs ?: return newHomePageResponse(emptyList())
        // Fetch all catalogs in parallel (matching original which uses amap)
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

        // Fetch detailed meta data from the addon
        val metaUrl = "${mainUrl}/meta/${entry.type}/${entry.id}.json"
        val metaResponse = tryParseJson<CatalogResponse>(app.get(metaUrl).text)
        val detailedEntry = metaResponse?.meta ?: entry

        // Extract imdbId from the entry ID (if it's an IMDB ID)
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
        Log.d("StremioC", "mainUrl=$mainUrl")
        Log.d("StremioC", "raw data=$data")

        mainUrl = fixSourceUrl(mainUrl)

        val loadData = try {
            mapper.readValue(data, LoadData::class.java)
        } catch (e: Exception) {
            Log.e("StremioC", "Failed to parse LoadData: $data", e)
            return false
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

        // === Strategy 1: Direct stream URL (matching original implementation) ===
        // The original uses simply: $mainUrl/stream/${type}/${id}.json
        // The Stremio ID may already contain :season:episode
        val streamType = loadData.type ?: if (loadData.season != null) "series" else "movie"
        val streamId = loadData.id ?: loadData.imdbId ?: ""
        val streamUrl = "$mainUrl/stream/$streamType/$streamId.json"

        Log.d("StremioC", "Trying stream URL: $streamUrl")

        try {
            val response = app.get(streamUrl, timeout = 15)
            Log.d("StremioC", "Response code: ${response.code}, body length: ${response.text.length}")

            if (response.isSuccessful && response.text.isNotEmpty()) {
                val streamsResponse = tryParseJson<StreamsResponse>(response.text)
                val streams = streamsResponse?.streams
                Log.d("StremioC", "Parsed ${streams?.size ?: 0} streams from response")

                if (!streams.isNullOrEmpty()) {
                    for (stream in streams) {
                        try {
                            processStream(stream, subtitleCallback, callback)
                        } catch (e: Exception) {
                            Log.e("StremioC", "Error processing stream: ${e.message}")
                        }
                    }
                } else {
                    // Try manual JSON parsing if tryParseJson returns null/empty
                    Log.d("StremioC", "tryParseJson returned no streams, trying manual parse")
                    tryManualStreamParse(response.text, subtitleCallback, callback)
                }
            }
        } catch (e: Exception) {
            Log.e("StremioC", "Stream URL failed: $streamUrl - ${e.message}")
        }

        // === Strategy 2: Try invokeMainSource with imdbId (like original uses torrentio) ===
        val imdbIdForFallback = loadData.imdbId ?: if (streamId.startsWith("tt")) streamId else null
        if (imdbIdForFallback != null) {
            try {
                invokeMainSource(
                    mainUrl, name,
                    imdbIdForFallback,
                    loadData.season, loadData.episode,
                    subtitleCallback, callback
                )
            } catch (e: Exception) {
                Log.e("StremioC", "invokeMainSource fallback failed: ${e.message}")
            }

            // Also try torrentio as backup (matching original)
            try {
                invokeMainSource(
                    "https://torrentio.strem.fun", name,
                    imdbIdForFallback,
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

        Log.d("StremioC", "=== loadLinks END ===")
        return true
    }

    /**
     * Process a single Stream object into extractor links.
     * This matches the original working implementation's logic.
     */
    private suspend fun processStream(
        stream: Stream,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        when {
            stream.url != null -> {
                val fixedName = fixSourceName(stream.name, stream.title, stream.description)
                val qualityTitle = buildExtractedTitle(extractSpecs(fixedName))

                // Get headers from proxyHeaders or behaviorHints.headers
                val headers = stream.behaviorHints?.proxyHeaders?.request
                    ?: stream.behaviorHints?.headers
                    ?: emptyMap()

                callback.invoke(
                    newExtractorLink(
                        source = stream.name ?: "",
                        name = qualityTitle,
                        url = stream.url,
                        type = if (stream.url.endsWith(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = ""
                        this.quality = getQuality(listOf(stream.description, stream.title, stream.name))
                        this.headers = headers
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
                    else -> extractedTitle
                }

                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = sizeInfo.ifBlank { extractedTitle },
                        url = magnet,
                        type = ExtractorLinkType.MAGNET
                    ) {
                        this.quality = getQuality(listOf(displayName))
                    }
                )
            }

            else -> {
                Log.w("StremioC", "Stream has no url/ytId/externalUrl/infoHash: ${stream.title}")
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

    /**
     * Manual JSON parsing fallback if tryParseJson fails.
     * Uses Jackson's JsonNode tree model for more forgiving parsing.
     */
    private suspend fun tryManualStreamParse(
        responseText: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val jsonObj = mapper.readTree(responseText)
            val streamsNode = jsonObj.get("streams") ?: return
            if (!streamsNode.isArray || streamsNode.size() == 0) return

            for (streamNode in streamsNode) {
                try {
                    val url = streamNode.get("url")?.asText()
                    val ytId = streamNode.get("ytId")?.asText()
                    val externalUrl = streamNode.get("externalUrl")?.asText()
                    val infoHash = streamNode.get("infoHash")?.asText()
                    val streamTitle = streamNode.get("title")?.asText() ?: streamNode.get("name")?.asText() ?: ""
                    val streamName = streamNode.get("name")?.asText() ?: ""
                    val streamDesc = streamNode.get("description")?.asText() ?: ""

                    when {
                        url != null -> {
                            val fixedName = fixSourceName(streamName, streamTitle, streamDesc)
                            val qualityTitle = buildExtractedTitle(extractSpecs(fixedName))

                            // Get headers from proxyHeaders or behaviorHints.headers
                            val headers = try {
                                streamNode.get("behaviorHints")
                                    ?.get("proxyHeaders")?.get("request")
                                    ?.fields()?.asSequence()
                                    ?.associate { it.key to it.value.asText() }
                                    ?: streamNode.get("behaviorHints")
                                        ?.get("headers")
                                        ?.fields()?.asSequence()
                                        ?.associate { it.key to it.value.asText() }
                                    ?: emptyMap()
                            } catch (_: Exception) {
                                emptyMap()
                            }

                            callback.invoke(
                                newExtractorLink(
                                    source = streamName.ifBlank { streamTitle },
                                    name = qualityTitle.ifBlank { streamTitle },
                                    url = url,
                                    type = if (url.endsWith(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = ""
                                    this.quality = getQuality(listOf(streamDesc, streamTitle, streamName))
                                    this.headers = headers
                                }
                            )
                        }
                        ytId != null -> {
                            loadExtractor("https://www.youtube.com/watch?v=$ytId", subtitleCallback, callback)
                        }
                        externalUrl != null -> {
                            loadExtractor(externalUrl, subtitleCallback, callback)
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
                        }
                    }

                    // Handle subtitles
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
                    Log.e("StremioC", "Manual parse failed for one stream: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e("StremioC", "Manual JSON parse failed: ${e.message}")
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
                    // Fetch first 2 pages of search results max
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
                            app.get(url, timeout = 30).text
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
                    // Only fetch the FIRST page for home screen (no pagination)
                    val url = "${provider.mainUrl}/catalog/$type/${id.encodeUri()}.json"
                    val res = tryParseJson<CatalogResponse>(
                        app.get(url, timeout = 30).text
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

        suspend fun toLoadResponse(provider: StremioC, entryId: String): LoadResponse {
            return if (videos.isNullOrEmpty()) {
                provider.newMovieLoadResponse(
                    name,
                    "${provider.mainUrl}/meta/${type}/${id}.json",
                    TvType.Movie,
                    LoadData(type, id, imdbId = entryId).toJson()
                ) {
                    posterUrl = poster
                    backgroundPosterUrl = background
                    plot = description
                    year = yearNum?.toIntOrNull()
                    tags = genres ?: genre
                    score = Score.from10(imdbRating)
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
                    backgroundPosterUrl = background
                    plot = description
                    year = yearNum?.toIntOrNull()
                    tags = genres ?: genre
                    score = Score.from10(imdbRating)
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
