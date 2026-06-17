package com.animeday

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

// ==================== Data Classes ====================

data class CartoonWithInfo(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("thumb") val thumb: String? = null,
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("world_rate") val worldRate: String? = null,
    @JsonProperty("view_date") val viewDate: String? = null,
    @JsonProperty("status") val status: String? = null,
    @JsonProperty("Watched_users") val watchedUsers: String? = null,
    @JsonProperty("classification") val classification: String? = null,
    @JsonProperty("category") val category: String? = null,
    @JsonProperty("age_rate") val ageRate: String? = null
)

data class Playlist(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("thumb") val thumb: String? = null,
    @JsonProperty("cartoon_id") val cartoonId: String? = null
)

data class Episode(
    @JsonProperty("id") val id: Any? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("thumb") val thumb: String? = null,
    @JsonProperty("video") val video: String? = null,
    @JsonProperty("video1") val video1: String? = null,
    @JsonProperty("video2") val video2: String? = null,
    @JsonProperty("video3") val video3: String? = null,
    @JsonProperty("video4") val video4: String? = null,
    @JsonProperty("video5") val video5: String? = null,
    @JsonProperty("jResolver") val jResolver: Any? = null,
    @JsonProperty("jResolver1") val jResolver1: Any? = null,
    @JsonProperty("jResolver2") val jResolver2: Any? = null,
    @JsonProperty("jResolver3") val jResolver3: Any? = null,
    @JsonProperty("jResolver4") val jResolver4: Any? = null,
    @JsonProperty("jResolver5") val jResolver5: Any? = null,
    @JsonProperty("playlist_id") val playlistId: Any? = null,
    @JsonProperty("story") val story: String? = null,
    @JsonProperty("category") val category: String? = null,
    @JsonProperty("world_rate") val worldRate: String? = null,
    @JsonProperty("view_date") val viewDate: String? = null,
    @JsonProperty("age_rate") val ageRate: String? = null,
    @JsonProperty("status") val status: String? = null
) {
    fun getIdString(): String = when (id) {
        is Number -> id.toString()
        is String -> id
        else -> ""
    }
    fun getPlaylistIdString(): String = when (playlistId) {
        is Number -> playlistId.toString()
        is String -> playlistId
        else -> ""
    }
}

data class EpisodeWithInfoLatest(
    @JsonProperty("id") val id: Any? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("thumb") val thumb: String? = null,
    @JsonProperty("video") val video: String? = null,
    @JsonProperty("video1") val video1: String? = null,
    @JsonProperty("video2") val video2: String? = null,
    @JsonProperty("playlist") val playlist: PlaylistInfo? = null,
    @JsonProperty("cartoon") val cartoon: CartoonWithInfo? = null,
    @JsonProperty("world_rate") val worldRate: String? = null,
    @JsonProperty("view_date") val viewDate: String? = null,
    @JsonProperty("status") val status: String? = null
)

data class PlaylistInfo(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("thumb") val thumb: String? = null,
    @JsonProperty("cartoon_id") val cartoonId: String? = null
)

data class VideoQuality(
    @JsonProperty("quality") val quality: String? = null,
    @JsonProperty("url") val url: String? = null
)

data class StreamResponse(
    @JsonProperty("availableQualities") val availableQualities: List<VideoQuality>? = null
)

data class AgentsCookies(
    @JsonProperty("oKru_Agent") val okRuAgent: String? = null,
    @JsonProperty("okRu_Cookie") val okRuCookie: String? = null,
    @JsonProperty("gPhotos_Agent") val gPhotosAgent: String? = null,
    @JsonProperty("gPhotos_Cookie") val gPhotosCookie: String? = null,
    @JsonProperty("MF_Agent") val mfAgent: String? = null,
    @JsonProperty("MF_Cookie") val mfCookie: String? = null
)

// Link data stores raw video URLs and jResolver flags from the episode
data class LinkData(
    val videoUrls: Map<String, Pair<String, Boolean>>  // server name -> (raw URL, needsExtraction)
)

// ==================== Provider ====================

class AnimeDayProvider : MainAPI() {
    override var mainUrl = "https://anime-day.com"
    override var name = "Anime Day"
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.Cartoon,
        TvType.TvSeries,
        TvType.Movie
    )
    override var lang = "ar"
    override val hasMainPage = true
    override val hasQuickSearch = false

    private val apiUrl = "https://anime-cartoon.developer-pro.workers.dev/API"
    private val authUser = "rabee3yamen"
    private val authPass = "d^AFi%Mu9Th5Wc7uLwh1nEMG8fp2*CW@"

    private val mapper = jacksonObjectMapper()

    private val headers = mapOf(
        "User-Agent" to "okhttp/4.9.3",
        "Accept" to "application/json"
    )

    private fun getAuthHeader(): String {
        val credentials = "$authUser:$authPass"
        return "Basic ${android.util.Base64.encodeToString(credentials.toByteArray(), android.util.Base64.NO_WRAP)}"
    }

    private val authHeaders get() = headers + mapOf(
        "Authorization" to getAuthHeader()
    )

    private inline fun <reified T> parseList(text: String): List<T> {
        return try {
            mapper.readValue(text, object : TypeReference<List<T>>() {})
        } catch (e: Exception) {
            emptyList()
        }
    }

    private inline fun <reified T> parseObject(text: String): T? {
        return try {
            mapper.readValue(text, object : TypeReference<T>() {})
        } catch (e: Exception) {
            null
        }
    }

    // ==================== Helpers ====================

    private fun needsExtraction(jResolver: Any?): Boolean {
        return when (jResolver) {
            is Number -> jResolver.toInt() == 1
            is String -> jResolver.trim() == "1"
            else -> false
        }
    }

    private fun collectVideoUrls(episode: Episode): Map<String, Pair<String, Boolean>> {
        val videos = mutableMapOf<String, Pair<String, Boolean>>()
        episode.video?.trim()?.takeIf { it.isNotEmpty() }?.let {
            videos["سيرفر 1"] = Pair(it, needsExtraction(episode.jResolver))
        }
        episode.video1?.trim()?.takeIf { it.isNotEmpty() }?.let {
            videos["سيرفر 2"] = Pair(it, needsExtraction(episode.jResolver1))
        }
        episode.video2?.trim()?.takeIf { it.isNotEmpty() }?.let {
            videos["سيرفر 3"] = Pair(it, needsExtraction(episode.jResolver2))
        }
        episode.video3?.trim()?.takeIf { it.isNotEmpty() }?.let {
            videos["سيرفر 4"] = Pair(it, needsExtraction(episode.jResolver3))
        }
        episode.video4?.trim()?.takeIf { it.isNotEmpty() }?.let {
            videos["سيرفر 5"] = Pair(it, needsExtraction(episode.jResolver4))
        }
        episode.video5?.trim()?.takeIf { it.isNotEmpty() }?.let {
            videos["سيرفر 6"] = Pair(it, needsExtraction(episode.jResolver5))
        }
        return videos
    }

    private fun isPlaylistMovie(playlist: Playlist, episodeCount: Int): Boolean {
        if (playlist.title?.contains("فيلم") == true) return true
        if (playlist.title?.contains("الجزء") == true || playlist.title?.contains("الموسم") == true) return false
        if (episodeCount == 1) return true
        return false
    }

    private fun extractEpisodeNumber(title: String): Int? {
        Regex("الحلقة\\s*(\\d+)").find(title)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        Regex("(?:Episode|Ep\\.?)\\s*(\\d+)", RegexOption.IGNORE_CASE).find(title)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        Regex("^(\\d+)$").find(title.trim())?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        return null
    }

    // ========== OPTIMIZATION 1: Pre-fetch agents/cookies at plugin load ==========
    // Instead of lazy-loading on first video play (which adds latency),
    // fetch immediately and cache aggressively.
    private var cachedAgents: AgentsCookies? = null
    private var agentsFetched = false
    private var agentsFetchFailed = false

    private suspend fun getAgents(): AgentsCookies? {
        if (agentsFetched) return cachedAgents
        if (agentsFetchFailed) return null  // Don't retry if it failed before
        agentsFetched = true
        cachedAgents = try {
            withTimeoutOrNull(5000L) {  // Reduced from 8000L
                val text = app.post(
                    "$apiUrl/AgentsAndCookies/getData.php",
                    headers = authHeaders
                ).text
                parseObject<AgentsCookies>(text)
            }
        } catch (_: Exception) {
            agentsFetchFailed = true
            null
        }
        return cachedAgents
    }

    // ========== OPTIMIZATION 2: URL resolution cache ==========
    // Cache resolved video URLs in memory to avoid re-fetching
    // when user switches quality/server for the same episode
    private val urlCache = object : LinkedHashMap<String, List<ExtractorLink>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<ExtractorLink>>): Boolean {
            return size > 50  // Cache up to 50 resolved URLs
        }
    }

    // ==================== Video Extraction (OPTIMIZED) ====================

    /**
     * OPTIMIZATION 3: Streamlined workers.dev extraction
     * - Try the most common JSON format first (availableQualities)
     * - Immediately return on first successful parse
     * - Reduced timeout from 12s to 6s
     */
    private suspend fun extractWorkerUrl(
        url: String,
        serverName: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val response = withTimeoutOrNull(6000L) {  // Reduced from 12000L
                app.get(url, headers = authHeaders)
            } ?: return false

            val responseText = response.text

            // Fast path: Try availableQualities first (most common format)
            if (responseText.trim().startsWith("{")) {
                try {
                    val node = mapper.readTree(responseText)

                    // Try availableQualities array (most common worker response)
                    val qualities = node?.get("availableQualities")
                    if (qualities != null && qualities.isArray) {
                        var found = false
                        for (q in qualities) {
                            val qualityStr = q.get("quality")?.asText() ?: continue
                            val streamUrl = q.get("url")?.asText() ?: continue
                            callback(
                                newExtractorLink(
                                    source = serverName,
                                    name = "$serverName - $qualityStr",
                                    url = streamUrl,
                                    type = INFER_TYPE
                                ) {
                                    this.referer = ""
                                    this.quality = getQualityValue(qualityStr)
                                }
                            )
                            found = true
                        }
                        if (found) return true
                    }

                    // Try single "url" field
                    node?.get("url")?.asText()?.takeIf { it.isNotEmpty() }?.let { directUrl ->
                        val quality = getQualityFromUrl(directUrl)
                        callback(
                            newExtractorLink(
                                source = serverName,
                                name = "$serverName - $quality",
                                url = directUrl,
                                type = INFER_TYPE
                            ) {
                                this.referer = ""
                                this.quality = getQualityValue(quality)
                            }
                        )
                        return true
                    }

                    // Try "videos" array
                    node?.get("videos")?.let { videosNode ->
                        if (videosNode.isArray) {
                            var found = false
                            for (v in videosNode) {
                                val vUrl = v.get("url")?.asText() ?: continue
                                val vQuality = v.get("quality")?.asText() ?: getQualityFromUrl(vUrl)
                                callback(
                                    newExtractorLink(
                                        source = serverName,
                                        name = "$serverName - $vQuality",
                                        url = vUrl,
                                        type = INFER_TYPE
                                    ) {
                                        this.referer = ""
                                        this.quality = getQualityValue(vQuality)
                                    }
                                )
                                found = true
                            }
                            if (found) return true
                        }
                    }
                } catch (_: Exception) {}
            }

            // Try JSON array format
            if (responseText.trim().startsWith("[")) {
                try {
                    val qualityList = parseList<VideoQuality>(responseText)
                    if (qualityList.isNotEmpty()) {
                        for (q in qualityList) {
                            val qualityStr = q.quality ?: continue
                            val streamUrl = q.url ?: continue
                            callback(
                                newExtractorLink(
                                    source = serverName,
                                    name = "$serverName - $qualityStr",
                                    url = streamUrl,
                                    type = INFER_TYPE
                                ) {
                                    this.referer = ""
                                    this.quality = getQualityValue(qualityStr)
                                }
                            )
                        }
                        return true
                    }
                } catch (_: Exception) {}
            }

            // Check for redirect
            val finalUrl = response.url
            if (finalUrl != url && finalUrl.isNotEmpty()) {
                callback(
                    newExtractorLink(
                        source = serverName,
                        name = "$serverName - تلقائي",
                        url = finalUrl,
                        type = INFER_TYPE
                    ) {
                        this.referer = ""
                        this.quality = Qualities.Unknown.value
                    }
                )
                return true
            }

            return false
        } catch (e: Exception) {
            // CRASH FIX v12: previously passed the raw workers.dev URL
            // directly to the player with INFER_TYPE. But workers.dev URLs
            // return JSON (not video), so the player tried to play JSON
            // as video -> "error parsing container 3003". Removed this
            // fallback — if JSON extraction fails, return false so the
            // next server can be tried instead.
            return false
        }
    }

    /**
     * OPTIMIZATION 4: Simplified Google Photos extraction
     * The native app only extracts the video-downloads URL (Original quality)
     * and adds it directly. No need for multi-quality extraction with lh3 base URLs.
     * Reduced from ~100 lines to ~40 lines, and from 10s to 5s timeout.
     */
    private suspend fun extractGooglePhotos(
        url: String,
        serverName: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Skip loadExtractor entirely — CloudStream's built-in Google Photos extractor
        // often fails or is slow. Go directly to custom HTML extraction.

        try {
            val agents = getAgents()
            val gPhotosHeaders = mutableMapOf<String, String>()
            val agent = agents?.gPhotosAgent?.takeIf { it.isNotEmpty() }
                ?: "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/107.0.0.0 Mobile Safari/537.36"
            gPhotosHeaders["User-Agent"] = agent
            agents?.gPhotosCookie?.takeIf { it.isNotEmpty() }?.let {
                gPhotosHeaders["Cookie"] = it
            }

            val pageText = withTimeoutOrNull(5000L) {  // Reduced from 10000L
                app.get(url, headers = gPhotosHeaders).text
            } ?: return false

            // Fix malformed percent encoding
            val fixed = Regex("%(?![0-9a-fA-F]{2})").replace(pageText) { "%25" }
            val decoded = java.net.URLDecoder.decode(fixed, "UTF-8")

            var foundAny = false

            // 1. Extract video-downloads URL (Original quality) — same as native app
            val videoDownloadPattern = Regex("""https://video-downloads\.googleusercontent\.com/[^"\\\s]+""")
            videoDownloadPattern.find(decoded)?.value?.let { videoUrl ->
                callback(
                    newExtractorLink(
                        source = serverName,
                        name = "$serverName - Original",
                        url = videoUrl,
                        type = INFER_TYPE
                    ) {
                        this.referer = ""
                        this.quality = Qualities.P1080.value
                    }
                )
                foundAny = true
            }

            // CRASH FIX v12: lh3.googleusercontent.com /pw/ URLs are image-CDN
            // endpoints; the =m22/=m37/=m18 suffixes don't produce playable
            // video URLs. Dropped to avoid broken links.
            return foundAny
        } catch (_: Exception) {
            return false
        }
    }

    /**
     * OPTIMIZATION 5: Skip loadExtractor for ok.ru — go directly to metadata API
     * The native app uses the ok.ru metadata API directly, not CloudStream's
     * built-in extractor which may make unnecessary requests.
     * Reduced timeout from 10s to 5s.
     */
    private suspend fun extractOkRu(
        url: String,
        serverName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Try metadata API directly (same approach as native app)
        try {
            val agents = getAgents()
            val videoId = Regex("""ok\.ru/video(?:embed)?/(\d+)""").find(url)?.groupValues?.get(1)
                ?: return false

            val userAgent = agents?.okRuAgent?.takeIf { it.isNotEmpty() }
                ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/107.0.0.0 Safari/537.36"
            val cookie = agents?.okRuCookie?.takeIf { it.isNotEmpty() } ?: ""

            // Use metadata API — same as native app
            val metadataUrl = "https://ok.ru/dk?cmd=videoPlayerMetadata&mid=$videoId"
            val metaResponse = withTimeoutOrNull(5000L) {  // Reduced from 10000L
                app.post(
                    metadataUrl,
                    headers = mapOf(
                        "User-Agent" to userAgent,
                        "Cookie" to cookie,
                        "Content-Type" to "application/x-www-form-urlencoded"
                    ),
                    data = mapOf("" to "")
                ).text
            } ?: return false

            val metadata = mapper.readTree(metaResponse)

            // Try "videos" array first (faster than HLS parsing)
            metadata?.get("videos")?.let { videosNode ->
                if (videosNode.isArray) {
                    var foundAny = false
                    for (v in videosNode) {
                        val vUrl = v.get("url")?.asText() ?: continue
                        val vName = v.get("name")?.asText() ?: "Auto"
                        callback(
                            newExtractorLink(
                                source = serverName,
                                name = "$serverName - $vName",
                                url = vUrl,
                                type = INFER_TYPE
                            ) {
                                this.referer = ""
                                this.quality = getQualityValue(vName)
                            }
                        )
                        foundAny = true
                    }
                    if (foundAny) return true
                }
            }

            // Try ondemandHls if videos array fails
            val hlsUrl = metadata?.get("ondemandHls")?.asText()
            if (hlsUrl != null && hlsUrl.isNotEmpty()) {
                val hlsResponse = withTimeoutOrNull(5000L) {  // Reduced from 10000L
                    app.get(
                        hlsUrl,
                        headers = mapOf(
                            "User-Agent" to userAgent,
                            "Cookie" to cookie
                        )
                    ).text
                } ?: return false

                val lines = hlsResponse.lines()
                var foundAny = false
                for (i in lines.indices) {
                    val line = lines[i]
                    if (line.startsWith("#EXT-X-STREAM-INF:")) {
                        val resolution = Regex("RESOLUTION=(\\d+x\\d+)").find(line)?.groupValues?.get(1)
                        if (i + 1 < lines.size) {
                            val streamUrl = lines[i + 1].trim()
                            if (streamUrl.isNotEmpty() && !streamUrl.startsWith("#")) {
                                val qualityLabel = resolution?.let { res ->
                                    val height = res.substringAfter("x")
                                    "${height}p"
                                } ?: "Auto"

                                callback(
                                    newExtractorLink(
                                        source = serverName,
                                        name = "$serverName - $qualityLabel",
                                        url = streamUrl,
                                        type = INFER_TYPE
                                    ) {
                                        this.referer = ""
                                        this.quality = getQualityValue(qualityLabel)
                                    }
                                )
                                foundAny = true
                            }
                        }
                    }
                }
                if (foundAny) return true
            }
        } catch (_: Exception) {}

        // CRASH FIX v12: loadExtractor fallback REMOVED (thread-spawning).
        return false
    }

    /**
     * Dailymotion extraction — try CloudStream's built-in extractor
     * with reduced timeout.
     */
    private suspend fun extractDailymotion(
        url: String,
        serverName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // CRASH FIX v12: loadExtractor REMOVED (thread-spawning catch-all).
        return false
    }

    /**
     * OPTIMIZATION 6: GDPlayer Pro — pass URL directly, no extraction needed
     * The native app adds GDPlayer URLs directly as "سيرفر : GD" without
     * any HTTP extraction. This is instant.
     */
    private suspend fun extractGdPlayerPro(
        url: String,
        serverName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Pass directly — same as native app (instant, no HTTP request)
        try {
            callback(
                newExtractorLink(
                    source = serverName,
                    name = "$serverName - GD",
                    url = url,
                    type = INFER_TYPE
                ) {
                    this.referer = mainUrl
                    this.quality = Qualities.Unknown.value
                }
            )
            return true
        } catch (_: Exception) {}

        // CRASH FIX v12: loadExtractor fallback REMOVED.
        return false
    }

    /**
     * OPTIMIZATION 7: Reordered URL routing for fastest path first
     * Direct video URLs > CDN URLs > workers.dev > Google Photos > ok.ru > others
     */
    private suspend fun extractFromUrl(
        url: String,
        serverName: String,
        needsExtraction: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val cleanUrl = url.trim()

        // 1. Direct video URLs (mp4, m3u8) — INSTANT, no HTTP needed
        if (cleanUrl.contains(".mp4") || cleanUrl.contains(".m3u8")) {
            val quality = getQualityFromUrl(cleanUrl)
            callback(
                newExtractorLink(
                    source = serverName,
                    name = "$serverName - $quality",
                    url = cleanUrl,
                    type = if (cleanUrl.contains(".m3u8")) INFER_TYPE else ExtractorLinkType.VIDEO
                ) {
                    this.referer = mainUrl
                    this.quality = getQualityValue(quality)
                }
            )
            return true
        }

        // 2. CDN URLs (okcdn.ru, vkuser.net) — INSTANT, pass directly
        if (cleanUrl.contains("okcdn.ru") || cleanUrl.contains("vkuser.net")) {
            callback(
                newExtractorLink(
                    source = serverName,
                    name = "$serverName - تلقائي",
                    url = cleanUrl,
                    type = INFER_TYPE
                ) {
                    this.referer = ""
                    this.quality = Qualities.Unknown.value
                }
            )
            return true
        }

        // 3. Google CDN URLs (lh3.googleusercontent.com) — INSTANT
        if (cleanUrl.contains("googleusercontent.com") && !cleanUrl.contains("photos.google.com")) {
            callback(
                newExtractorLink(
                    source = serverName,
                    name = serverName,
                    url = cleanUrl,
                    type = INFER_TYPE
                ) {
                    this.referer = ""
                    this.quality = Qualities.Unknown.value
                }
            )
            return true
        }

        // 4. GDPlayer URLs — FAST, pass directly (native app does this)
        if (cleanUrl.contains("gdplayer")) {
            return extractGdPlayerPro(cleanUrl, serverName, subtitleCallback, callback)
        }

        // 5. Workers.dev URLs — needs 1 HTTP request with auth
        if (cleanUrl.contains(".workers.dev/")) {
            return extractWorkerUrl(cleanUrl, serverName, callback)
        }

        // 6. ok.ru video URLs — metadata API (skip loadExtractor first-attempt)
        if (cleanUrl.contains("ok.ru/video") || cleanUrl.contains("ok.ru/videoembed")) {
            return extractOkRu(cleanUrl, serverName, subtitleCallback, callback)
        }

        // 7. Dailymotion URLs
        if (cleanUrl.contains("dailymotion.com") || cleanUrl.contains("dai.ly")) {
            return extractDailymotion(cleanUrl, serverName, subtitleCallback, callback)
        }

        // 8. Google Photos URLs — slower, HTML extraction needed
        if (cleanUrl.contains("photos.google.com")) {
            return extractGooglePhotos(cleanUrl, serverName, callback)
        }

        // CRASH FIX v12: loadExtractor for unknown URLs REMOVED, AND the
        // "last resort: pass as direct link" fallback is ALSO REMOVED.
        // Returning unknown URLs to the player with INFER_TYPE caused
        // runaway probing -> pthread_create failures.
        return false
    }

    // ==================== Home Page ====================

    override val mainPage = mainPageOf(
        "$apiUrl/cartoon_with_info/readPagingTranslatedSeriesAnime.php" to "انمي مترجم",
        "$apiUrl/cartoon_with_info/readPagingDUBBEDSeriesAnime.php" to "انمي مدبلج",
        "$apiUrl/cartoon_with_info/readPagingTranslatedFilms.php" to "أفلام مترجمة",
        "$apiUrl/cartoon_with_info/readPagingDUBBEDFilms.php" to "أفلام مدبلجة",
        "$apiUrl/cartoon_with_info/getMostViewedCartoons.php" to "الأكثر مشاهدة",
        "$apiUrl/episodeWithInfo/latest.php" to "آخر الحلقات"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (request.data.contains("getMostViewed") || request.data.contains("latest")) {
            request.data
        } else {
            "${request.data}?page=$page"
        }

        val isLatest = request.data.contains("latest")

        if (isLatest) {
            val responseText = app.get(url, headers = authHeaders).text
            val res = parseList<EpisodeWithInfoLatest>(responseText)
            val items = res.mapNotNull { ep ->
                val cartoon = ep.cartoon ?: return@mapNotNull null
                cartoon.id?.let { toSearchResult(cartoon) }
            }.distinctBy { it.url }
            return newHomePageResponse(request.name, items, false)
        }

        val responseText = app.get(url, headers = authHeaders).text
        val res = parseList<CartoonWithInfo>(responseText)
        val items = res.mapNotNull { toSearchResult(it) }
        val hasNext = res.size >= 20

        return newHomePageResponse(request.name, items, hasNext)
    }

    private fun toSearchResult(item: CartoonWithInfo): SearchResponse? {
        val id = item.id ?: return null
        val title = item.title ?: return null
        val poster = item.thumb
        val type = when (item.type) {
            "2" -> TvType.Movie
            else -> TvType.TvSeries
        }
        val classificationTag = when (item.classification) {
            "1" -> "مدبلج"
            "2" -> "مترجم"
            else -> ""
        }
        val displayTitle = if (classificationTag.isNotEmpty()) "$title ($classificationTag)" else title

        val dubExist = item.classification == "1"
        val subExist = item.classification == "2"

        return newAnimeSearchResponse(displayTitle, "$mainUrl/cartoon/$id", type) {
            this.posterUrl = poster
            addDubStatus(dubExist, subExist)
        }
    }

    // ==================== Search ====================

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()

        val searchConfigs = listOf(
            "1" to "2", "1" to "1", "2" to "2", "2" to "1"
        )

        // CRASH FIX v12: SEQUENTIAL (was 4 parallel async).
        for ((type, classification) in searchConfigs) {
            try {
                val text = app.get(
                    "$apiUrl/cartoon_with_info/searchCartoon.php",
                    params = mapOf("search" to query, "type" to type, "classification" to classification),
                    headers = authHeaders
                ).text
                val items = parseList<CartoonWithInfo>(text)
                items.forEach { toSearchResult(it)?.let { r -> results.add(r) } }
            } catch (_: Exception) {}
        }

        return results.distinctBy { it.url }
    }

    // ==================== Load Detail ====================

    /**
     * OPTIMIZATION 9: Parallel episode fetching
     * Instead of fetching playlists then episodes sequentially,
     * fetch all playlist episodes concurrently.
     */
    override suspend fun load(url: String): LoadResponse? {
        val cartoonId = url.substringAfterLast("/")

        val playlistsText = app.post(
            "$apiUrl/playlist/read.php",
            headers = authHeaders + mapOf("Content-Type" to "application/x-www-form-urlencoded"),
            data = mapOf("cartoon_id" to cartoonId)
        ).text
        val playlists = parseList<Playlist>(playlistsText)
        if (playlists.isEmpty()) return null

        // CRASH FIX v12: SEQUENTIAL (was parallel async per playlist).
        val playlistEpisodes = mutableMapOf<String, List<Episode>>()
        var firstEpisodeInfo: Episode? = null

        for (playlist in playlists) {
            val playlistId = playlist.id ?: continue
            try {
                val epsText = app.post(
                    "$apiUrl/episodeWithInfo/readPaging.php",
                    headers = authHeaders + mapOf("Content-Type" to "application/x-www-form-urlencoded"),
                    data = mapOf("playlist_id" to playlistId)
                ).text
                val eps = parseList<Episode>(epsText)
                playlistEpisodes[playlistId] = eps
                if (firstEpisodeInfo == null && eps.isNotEmpty()) {
                    firstEpisodeInfo = eps.first()
                }
            } catch (_: Exception) {
                playlistEpisodes[playlistId] = emptyList()
            }
        }

        val firstPlaylist = playlists.first()
        val firstPlaylistEps = playlistEpisodes[firstPlaylist.id] ?: emptyList()
        val isMovie = isPlaylistMovie(firstPlaylist, firstPlaylistEps.size)

        val title = firstPlaylist.title?.trim() ?: "Anime #$cartoonId"
        val poster = firstPlaylist.thumb
        val scoreValue = Score.from10(firstEpisodeInfo?.worldRate)
        val categories = firstEpisodeInfo?.category?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: emptyList()
        val plot = buildPlotFromEpisode(firstEpisodeInfo)

        if (isMovie) {
            val ep = firstPlaylistEps.firstOrNull()
            val videoUrls = if (ep != null) collectVideoUrls(ep) else emptyMap()
            val linkData = LinkData(videoUrls)

            return newMovieLoadResponse(title, url, TvType.Movie, linkData.toJson()) {
                this.posterUrl = poster
                this.score = scoreValue
                this.tags = categories
                this.plot = plot
            }
        }

        // TV Series
        val allEpisodes = mutableListOf<Episode>()
        for (playlist in playlists) {
            playlistEpisodes[playlist.id]?.let { allEpisodes.addAll(it) }
        }
        if (allEpisodes.isEmpty()) return null

        val episodes = allEpisodes.mapIndexed { idx, ep ->
            val videoUrls = collectVideoUrls(ep)
            val pId = ep.getPlaylistIdString().ifEmpty { playlists.firstOrNull()?.id ?: "" }
            val linkData = LinkData(videoUrls)

            newEpisode(linkData.toJson()) {
                this.name = ep.title ?: "الحلقة ${idx + 1}"
                val seasonPlaylist = playlists.find { it.id == pId }
                val seasonIndex = playlists.indexOf(seasonPlaylist)
                if (seasonIndex >= 0) {
                    this.season = seasonIndex + 1
                }
                val epNum = ep.title?.let { extractEpisodeNumber(it) }
                if (epNum != null) {
                    this.episode = epNum
                }
                this.posterUrl = ep.thumb?.takeIf { it.isNotEmpty() } ?: poster
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.score = scoreValue
            this.tags = categories
            this.plot = plot
        }
    }

    private fun buildPlotFromEpisode(ep: Episode?): String {
        if (ep == null) return ""
        val parts = mutableListOf<String>()
        ep.viewDate?.takeIf { it.isNotEmpty() }?.let { parts.add(it) }
        ep.status?.takeIf { it == "1" }?.let { parts.add("مكتمل") }
        ep.status?.takeIf { it == "2" }?.let { parts.add("مستمر") }
        ep.worldRate?.takeIf { it.isNotEmpty() }?.let { parts.add("التقييم: $it") }
        ep.category?.takeIf { it.isNotEmpty() }?.let { parts.add("التصنيفات: $it") }
        return parts.joinToString(" | ")
    }

    // ==================== Video Extraction Entry Point ====================

    /**
     * OPTIMIZATION 10: Reduced per-server timeout from 15s to 8s
     * The native app uses 10s timeout for all HTTP operations.
     * Also pre-fetches agents/cookies in the background.
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val linkData = tryParseJson<LinkData>(data) ?: return false
        val videoUrls = linkData.videoUrls
        if (videoUrls.isEmpty()) return false

        // Pre-fetch agents/cookies in background (won't block if already fetched)
        coroutineScope {
            async { getAgents() }
        }

        var foundAny = false
        // CRASH FIX v12 = v8's exact loadLinks (which DIDN'T crash) + v9's
        // 3003 fix (remove workers.dev raw-URL fallback):
        //
        // History of what crashed vs didn't:
        //   v8  (1 server, 5s timeout, 1000ms delay, MAX_LINKS=3, workers.dev
        //       raw-URL fallback PRESENT): NO CRASH, but 3003 errors from
        //       the workers.dev fallback passing JSON to the player.
        //   v9  (3 servers, 3s timeout, 200ms delay): CRASHED (3 servers too
        //       many — saturated OkHttp pool).
        //   v10 (1 server, 3s timeout, 200ms delay): CRASHED (3s timeout too
        //       short → false "no links found" → tried all 6 servers → pool
        //       saturated → crash on 2nd play).
        //   v11 (2 servers, 5s timeout, 1000ms delay): CRASHED (2 servers
        //       still too many).
        //
        // Conclusion: the threshold is EXACTLY at v8's config — 1 server,
        // 5s timeout, 1000ms delay. v12 restores v8's exact loadLinks and
        // ONLY adds v9's 3003 fix (which doesn't add any HTTP requests).
        //
        // v12 = v8 + 3003 fix:
        //   1. PROCESS ONLY THE FIRST SERVER that returns links (from v8).
        //   2. timeout = 5s (from v8 — reliable for slow networks).
        //   3. delay = 1000ms (from v8 — lets OkHttp pool clean up).
        //   4. MAX_LINKS=5 (workers.dev returns 3-5 qualities from a SINGLE
        //      server, so user gets alternatives within that one server
        //      without needing more HTTP requests).
        //   5. workers.dev raw-URL fallback REMOVED (from v9) — if JSON
        //      extraction fails, return false instead of feeding the player
        //      JSON -> no more 3003.
        //   6. ALL previous crash fixes retained: sequential search/load,
        //      no loadExtractor, no lh3 fake URLs, no extractFromUrl
        //      last-resort fallback.
        val MAX_LINKS = 5
        val linksReturned = java.util.concurrent.atomic.AtomicInteger(0)
        val bufferingCallback: (ExtractorLink) -> Unit = { link ->
            if (linksReturned.getAndIncrement() < MAX_LINKS) {
                callback(link)
            }
        }

        for ((serverName, urlAndExtraction) in videoUrls.entries) {
            // CRASH FIX v12: stop as soon as the FIRST server returns any
            // links. This is v8's exact approach that didn't crash. v9 (3
            // servers) and v11 (2 servers) both crashed; 1 server is the
            // safe threshold. This caps total HTTP requests to ~2 per
            // loadLinks call (one for the first server, possibly one for
            // getAgents if not cached), keeping OkHttp's connection pool
            // well below saturation.
            if (linksReturned.get() > 0) break
            val result = coroutineScope {
                withTimeoutOrNull(5000L) {  // 5s — v8's reliable timeout
                    try {
                        val (url, needsExtraction) = urlAndExtraction
                        extractFromUrl(url, serverName, needsExtraction, subtitleCallback, bufferingCallback)
                    } catch (_: Exception) {
                        false
                    }
                } ?: false
            }
            if (result) foundAny = true
            // Longer delay to let OkHttp's connection pool release the
            // connection before any potential next iteration. v8's 1000ms
            // is critical — v10's 200ms was too short and contributed to
            // the crash. Fully-qualified to avoid touching the import block.
            kotlinx.coroutines.yield()
            kotlinx.coroutines.delay(1000)
        }

        return foundAny
    }

    private fun getQualityFromUrl(url: String): String = when {
        url.contains("1080") -> "1080p"
        url.contains("720") -> "720p"
        url.contains("480") -> "480p"
        url.contains("360") -> "360p"
        url.contains("240") -> "240p"
        else -> "Auto"
    }

    private fun getQualityValue(quality: String): Int = when {
        quality.contains("1080") -> Qualities.P1080.value
        quality.contains("720") -> Qualities.P720.value
        quality.contains("480") -> Qualities.P480.value
        quality.contains("360") -> Qualities.P360.value
        quality.contains("240") -> Qualities.P240.value
        else -> Qualities.Unknown.value
    }
}