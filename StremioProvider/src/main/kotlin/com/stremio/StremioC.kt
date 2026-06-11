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

        // For standard movie/series with IMDB/TMDB ids, use cinemeta for metadata
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

        val loadData = try {
            mapper.readValue(data, LoadData::class.java)
        } catch (e: Exception) {
            Log.e("StremioC", "Failed to parse loadLinks data: $data", e)
            return false
        }

        Log.d("StremioC", "loadLinks called: type=${loadData.type}, id=${loadData.id}, season=${loadData.season}, episode=${loadData.episode}, imdbId=${loadData.imdbId}, mainUrl=$mainUrl")

        // Set subtitle auto-select language
        try {
            val context = CloudStreamApp.context
            val prefs = context?.getSharedPreferences("stremio_prefs", 0)
            val subsAutoSelect = prefs?.getString("app_locale", null)
            if (subsAutoSelect != null) {
                AcraApplication.setKey("subs_auto_select", subsAutoSelect)
            }
        } catch (e: Exception) {}

        // Construct stream URL using the type and id from the catalog
        // The id for episodes is typically "tt0903747:1:1" format (imdbId:season:episode)
        val streamType = loadData.type ?: if (loadData.season != null) "series" else "movie"
        val streamId = loadData.id ?: loadData.imdbId ?: ""

        val streamUrl = if (streamId.contains(":")) {
            // ID already contains season:episode info (e.g., "tt0903747:1:1")
            "$mainUrl/stream/$streamType/$streamId.json"
        } else if (loadData.season != null && loadData.episode != null) {
            // Separate season/episode fields - construct compound ID
            "$mainUrl/stream/$streamType/$streamId:${loadData.season}:${loadData.episode}.json"
        } else {
            // Movie or no episode info
            "$mainUrl/stream/$streamType/$streamId.json"
        }

        Log.d("StremioC", "Stream URL: $streamUrl")

        var foundLinks = false

        try {
            val response = app.get(streamUrl, timeout = 30)
            Log.d("StremioC", "Stream response code: ${response.code}, length: ${response.text.length}")

            if (response.isSuccessful) {
                val streamsResponse = tryParseJson<StreamsResponse>(response.text)
                val streams = streamsResponse?.streams
                Log.d("StremioC", "Parsed ${streams?.size ?: 0} streams")

                if (!streams.isNullOrEmpty()) {
                    for (stream in streams) {
                        try {
                            processStream(stream, subtitleCallback, callback)
                            foundLinks = true
                        } catch (e: Exception) {
                            Log.e("StremioC", "Error processing stream: ${e.message}", e)
                        }
                    }
                }
            } else {
                Log.w("StremioC", "Stream URL returned ${response.code}")
            }
        } catch (e: Exception) {
            Log.e("StremioC", "Stream request failed: ${e.message}", e)
        }

        // If no links found from addon, try torrentio as fallback
        if (!foundLinks) {
            Log.d("StremioC", "No links from addon, trying torrentio fallback")
            try {
                invokeMainSource(
                    "https://torrentio.strem.fun",
                    name,
                    loadData.imdbId ?: loadData.id ?: "",
                    loadData.season,
                    loadData.episode,
                    subtitleCallback,
                    callback
                )
            } catch (e: Exception) {
                Log.e("StremioC", "Torrentio fallback failed: ${e.message}", e)
            }
        }

        // Load subtitles with timeout protection (each gets 10 seconds max)
        loadSubtitles(loadData, subtitleCallback)

        return true
    }

    /**
     * Load subtitles from external sources with individual timeouts.
     * Each extractor gets 10 seconds; one failure won't block the others.
     */
    private suspend fun loadSubtitles(
        loadData: LoadData,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        coroutineScope {
            listOf(
                async(Dispatchers.IO) {
                    try {
                        withTimeoutOrNull(10_000L) {
                            invokeStremio(loadData.imdbId, loadData.season, loadData.episode, subtitleCallback)
                        }
                    } catch (_: Exception) {}
                },
                async(Dispatchers.IO) {
                    try {
                        withTimeoutOrNull(10_000L) {
                            invokeOpensub(loadData.imdbId, loadData.season, loadData.episode, subtitleCallback)
                        }
                    } catch (_: Exception) {}
                },
                async(Dispatchers.IO) {
                    try {
                        withTimeoutOrNull(10_000L) {
                            invokeSubsource(loadData.title, loadData.imdbId, loadData.season, loadData.episode, subtitleCallback)
                        }
                    } catch (_: Exception) {}
                },
                async(Dispatchers.IO) {
                    try {
                        withTimeoutOrNull(10_000L) {
                            invokeWatchsomuch(loadData.imdbId, loadData.season, loadData.episode, subtitleCallback)
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
                } catch (e: Exception) {
                    Log.e("StremioC", Log.getStackTraceString(e))
                }

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
                subtitleCallback.invoke(SubtitleFile(sub.lang, sub.url))
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
                // Movie - pass LoadData as JSON string for loadLinks()
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
                // TV Series - each episode's data must be a JSON string
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
            // IMPORTANT: Pass LoadData as JSON string so loadLinks() can parse it
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
