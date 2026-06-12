package com.stremio

import android.util.Log
import android.widget.Toast
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
        // Fetch all catalogs in parallel
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
            // Movie: pass the DIRECT stream URL as data (matching reference implementation)
            val streamUrl = "${mainUrl}/stream/${detailedEntry.type}/${detailedEntry.id}.json"
            newMovieLoadResponse(
                detailedEntry.name,
                metaUrl,
                TvType.Movie,
                EpisodeData(streamUrl, imdbId).toJson()
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
            // Series: pass the DIRECT stream URL for each episode as data
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

        // Parse the EpisodeData which contains the stream URL and imdbId
        val episodeData = try {
            mapper.readValue(data, EpisodeData::class.java)
        } catch (e: Exception) {
            Log.e("StremioC", "Failed to parse EpisodeData: $data", e)
            // Show debug toast so user can see the error
            showDebugToast("PARSE FAIL: ${e.message?.take(80)}\ndata=${data.take(100)}")
            return false
        }

        val streamUrl = episodeData.streamUrl
        val imdbId = episodeData.imdbId
        val season = episodeData.season
        val episode = episodeData.episode

        Log.d("StremioC", "Stream URL: $streamUrl")
        Log.d("StremioC", "imdbId=$imdbId, season=$season, episode=$episode")

        // Set subtitle auto-select language
        try {
            val context = CloudStreamApp.context
            val prefs = context?.getSharedPreferences("stremio_prefs", 0)
            val subsAutoSelect = prefs?.getString("app_locale", null)
            if (subsAutoSelect != null) {
                AcraApplication.setKey("subs_auto_select", subsAutoSelect)
            }
        } catch (_: Exception) {}

        var linksFound = 0

        // === Step 1: Fetch from the DIRECT stream URL ===
        // This matches the reference implementation: just app.get(data)
        try {
            Log.d("StremioC", "Fetching: $streamUrl")
            val response = app.get(streamUrl, timeout = 30)
            Log.d("StremioC", "Response: code=${response.code}, length=${response.text.length}")
            Log.d("StremioC", "Response preview: ${response.text.take(500)}")

            if (response.isSuccessful && response.text.isNotEmpty()) {
                val streamsResponse = tryParseJson<StreamsResponse>(response.text)
                val streams = streamsResponse?.streams
                Log.d("StremioC", "Parsed ${streams?.size ?: 0} streams via tryParseJson")

                if (!streams.isNullOrEmpty()) {
                    for (stream in streams) {
                        try {
                            val count = processStream(stream, subtitleCallback, callback)
                            linksFound += count
                        } catch (e: Exception) {
                            Log.e("StremioC", "Error processing stream: ${e.message}")
                        }
                    }
                } else {
                    // tryParseJson failed - try manual JSON parsing
                    Log.d("StremioC", "tryParseJson returned no streams, trying manual parse")
                    val manualCount = tryManualStreamParse(response.text, subtitleCallback, callback)
                    linksFound += manualCount
                    Log.d("StremioC", "Manual parse found $manualCount links")
                }
            } else {
                Log.w("StremioC", "Stream URL returned non-success or empty: code=${response.code}")
            }
        } catch (e: Exception) {
            Log.e("StremioC", "Stream URL fetch failed: $streamUrl - ${e.message}")
        }

        // === Step 2: Torrentio fallback (if we have an imdbId) ===
        if (imdbId != null) {
            try {
                Log.d("StremioC", "Trying Torrentio fallback with imdbId=$imdbId")
                invokeMainSource(
                    "https://torrentio.strem.fun", name,
                    imdbId, season, episode,
                    subtitleCallback, callback
                )
                linksFound++  // invokeMainSource doesn't return count, assume it found something
            } catch (e: Exception) {
                Log.e("StremioC", "Torrentio fallback failed: ${e.message}")
            }
        }

        // === Step 3: Load subtitles ===
        try {
            loadSubtitles(imdbId, season, episode, episodeData.title, subtitleCallback)
        } catch (e: Exception) {
            Log.e("StremioC", "Subtitle loading failed: ${e.message}")
        }

        // Show debug toast with results
        val debugMsg = "URL: ${streamUrl.take(60)}\nLinks found: $linksFound"
        showDebugToast(debugMsg)

        Log.d("StremioC", "=== loadLinks END: linksFound=$linksFound ===")
        return linksFound > 0
    }

    /**
     * Show a debug toast on the UI thread.
     * Uses Handler to post to the main thread since we're in a suspend function.
     */
    private fun showDebugToast(message: String) {
        try {
            val context = CloudStreamApp.context ?: return
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            handler.post {
                try {
                    Toast.makeText(context, "[Stremio Debug]\n$message", Toast.LENGTH_LONG).show()
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    /**
     * Process a single Stream object into extractor links.
     * Returns the number of links added.
     */
    private suspend fun processStream(
        stream: Stream,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Int {
        var count = 0

        // Handle direct URL streams
        if (stream.url != null) {
            val fixedName = fixSourceName(stream.name, stream.title, stream.description)
            val qualityTitle = buildExtractedTitle(extractSpecs(fixedName))

            val headers = stream.behaviorHints?.proxyHeaders?.request
                ?: stream.behaviorHints?.headers
                ?: emptyMap()

            callback.invoke(
                newExtractorLink(
                    source = stream.name ?: "",
                    name = qualityTitle,
                    url = stream.url,
                    type = null  // null = auto-detect (INFER_TYPE)
                ) {
                    this.referer = ""
                    this.quality = getQuality(listOf(stream.description, stream.title, stream.name))
                    this.headers = headers
                }
            )
            count++
        }

        if (stream.ytId != null) {
            loadExtractor("https://www.youtube.com/watch?v=${stream.ytId}", subtitleCallback, callback)
            count++
        }

        if (stream.externalUrl != null) {
            loadExtractor(stream.externalUrl, subtitleCallback, callback)
            count++
        }

        if (stream.infoHash != null) {
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
                    type = ExtractorLinkType.TORRENT
                ) {
                    this.referer = ""
                    this.quality = getQuality(listOf(stream.description, stream.title, stream.name))
                }
            )
            count++
        }

        // Handle subtitles in streams
        stream.subtitles?.forEach { sub ->
            if (sub.url != null && sub.lang != null) {
                try {
                    subtitleCallback.invoke(SubtitleFile(sub.lang, sub.url))
                } catch (e: Exception) {
                    Log.e("StremioC", "SubtitleFile failed: ${e.message}")
                }
            }
        }

        return count
    }

    /**
     * Manual JSON parsing fallback using Jackson's JsonNode tree model.
     * Returns the number of links found.
     */
    private suspend fun tryManualStreamParse(
        responseText: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Int {
        var found = 0
        try {
            val jsonObj = mapper.readTree(responseText)
            val streamsNode = jsonObj.get("streams") ?: return 0
            if (!streamsNode.isArray || streamsNode.size() == 0) return 0

            for (streamNode in streamsNode) {
                try {
                    val url = streamNode.get("url")?.asText()
                    val ytId = streamNode.get("ytId")?.asText()
                    val externalUrl = streamNode.get("externalUrl")?.asText()
                    val infoHash = streamNode.get("infoHash")?.asText()
                    val streamTitle = streamNode.get("title")?.asText() ?: streamNode.get("name")?.asText() ?: ""
                    val streamName = streamNode.get("name")?.asText() ?: ""
                    val streamDesc = streamNode.get("description")?.asText() ?: ""

                    // Use if-statements to process ALL matching types
                    if (url != null) {
                        val fixedName = fixSourceName(streamName, streamTitle, streamDesc)
                        val qualityTitle = buildExtractedTitle(extractSpecs(fixedName))

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
                                type = null
                            ) {
                                this.referer = ""
                                this.quality = getQuality(listOf(streamDesc, streamTitle, streamName))
                                this.headers = headers
                            }
                        )
                        found++
                    }
                    if (ytId != null) {
                        loadExtractor("https://www.youtube.com/watch?v=$ytId", subtitleCallback, callback)
                        found++
                    }
                    if (externalUrl != null) {
                        loadExtractor(externalUrl, subtitleCallback, callback)
                        found++
                    }
                    if (infoHash != null) {
                        val magnet = generateMagnetLink(infoHash)
                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = streamTitle.ifBlank { streamName },
                                url = magnet,
                                type = ExtractorLinkType.TORRENT
                            ) {
                                this.referer = ""
                                this.quality = getQualityFromName(streamTitle)
                            }
                        )
                        found++
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
        return found
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

    // ==================== Data Classes ====================

    /**
     * EpisodeData: contains the pre-built stream URL plus metadata for fallbacks.
     * This replaces the old LoadData approach. The stream URL is constructed
     * in load() and passed directly, matching the reference StremioProvider pattern.
     */
    data class EpisodeData(
        val streamUrl: String,
        val imdbId: String? = null,
        val season: Int? = null,
        val episode: Int? = null,
        val title: String? = null
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
         * KEY CHANGE: Now passes the DIRECT stream URL as episode data,
         * matching the reference StremioProvider implementation.
         * The stream URL is constructed here and stored as the episode data.
         */
        fun toEpisode(provider: StremioC, type: String?, entryId: String?): Episode {
            val epNum = episode ?: number
            val epName = name ?: title

            // Construct the stream URL directly (matching reference implementation)
            val streamUrl = "${provider.mainUrl}/stream/$type/$id.json"

            val episodeData = EpisodeData(
                streamUrl = streamUrl,
                imdbId = entryId,
                season = seasonNumber,
                episode = epNum,
                title = epName
            )

            return provider.newEpisode(episodeData.toJson()) {
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
