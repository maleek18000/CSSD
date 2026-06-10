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

    // Cached agents/cookies — fetched lazily only when needed (Google Photos / ok.ru)
    private var cachedAgents: AgentsCookies? = null
    private var agentsFetched = false

    private suspend fun getAgents(): AgentsCookies? {
        if (agentsFetched) return cachedAgents
        agentsFetched = true
        cachedAgents = try {
            withTimeoutOrNull(8000L) {
                val text = app.post(
                    "$apiUrl/AgentsAndCookies/getData.php",
                    headers = authHeaders
                ).text
                parseObject<AgentsCookies>(text)
            }
        } catch (_: Exception) { null }
        return cachedAgents
    }

    // ==================== Video Extraction ====================

    private suspend fun extractWorkerUrl(
        url: String,
        serverName: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val response = withTimeoutOrNull(12000L) {
                app.get(url, headers = authHeaders)
            } ?: return false

            val responseText = response.text

            // Try parsing as JSON object with availableQualities (test-stream format)
            if (responseText.trim().startsWith("{")) {
                val streamRes = parseObject<StreamResponse>(responseText)
                val qualities = streamRes?.availableQualities
                if (qualities != null && qualities.isNotEmpty()) {
                    for (q in qualities) {
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

                // Try JSON object with "url" or "videos" field
                try {
                    val node = mapper.readTree(responseText)
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

            // Try parsing as JSON array [{url, quality}, ...]
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

            // If the response URL is different from the request URL, it means a redirect occurred
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
            // If app.get() fails, pass the worker URL directly with auth headers
            try {
                callback(
                    newExtractorLink(
                        source = serverName,
                        name = serverName,
                        url = url,
                        type = INFER_TYPE
                    ) {
                        this.referer = ""
                        this.headers = mapOf("Authorization" to getAuthHeader())
                    }
                )
                return true
            } catch (_: Exception) {
                return false
            }
        }
    }

    private suspend fun extractGooglePhotos(
        url: String,
        serverName: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Try CloudStream's built-in extractor first (fast path)
        try {
            withTimeoutOrNull(10000L) {
                loadExtractor(url, mainUrl, subtitleCallback = { }, callback = callback)
            }?.let { return true }
        } catch (_: Exception) {}

        // Fallback: Custom HTML extraction with timeout
        try {
            val agents = getAgents()
            val gPhotosHeaders = mutableMapOf<String, String>()
            val agent = agents?.gPhotosAgent?.takeIf { it.isNotEmpty() }
                ?: "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/107.0.0.0 Mobile Safari/537.36"
            gPhotosHeaders["User-Agent"] = agent
            agents?.gPhotosCookie?.takeIf { it.isNotEmpty() }?.let {
                gPhotosHeaders["Cookie"] = it
            }

            val pageText = withTimeoutOrNull(10000L) {
                app.get(url, headers = gPhotosHeaders).text
            } ?: return false

            // Fix malformed percent encoding (from APK's bo0.d pattern)
            val fixed = Regex("%(?![0-9a-fA-F]{2})").replace(pageText) { "%25" }
            val decoded = java.net.URLDecoder.decode(fixed, "UTF-8")

            val links = mutableListOf<ExtractorLink>()

            // 1. Extract video-downloads URL (Original quality)
            val videoDownloadPattern = Regex("""https://video-downloads\.googleusercontent\.com/[^"\\\s]+""")
            videoDownloadPattern.find(decoded)?.value?.let { videoUrl ->
                links.add(
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
            }

            // 2. Extract lh3.googleusercontent.com/pw/ base URL
            val lh3Pattern = Regex("""https://lh3\.googleusercontent\.com/pw/[^"\\\s]+""")
            var lh3BaseUrl: String? = null
            for (match in lh3Pattern.findAll(decoded)) {
                val foundUrl = match.value
                if (!foundUrl.contains("=s") && !foundUrl.contains("=w") &&
                    !foundUrl.contains("=d") && !foundUrl.contains("=m") && !foundUrl.contains("=h")) {
                    lh3BaseUrl = foundUrl
                    break
                }
            }
            if (lh3BaseUrl == null) {
                lh3Pattern.find(decoded)?.value?.let { rawUrl ->
                    val lastEqIndex = rawUrl.lastIndexOf('=')
                    lh3BaseUrl = if (lastEqIndex > 0) rawUrl.substring(0, lastEqIndex) else rawUrl
                }
            }

            // 3. Check for quality arrays in the page [itag,width,height]
            val qualityArrayPattern = Regex("""\[(\d+),(\d+),(\d+)\]""")
            val availableQualities = mutableSetOf<Int>()
            qualityArrayPattern.findAll(decoded).forEach { match ->
                val itag = match.groupValues[1].toIntOrNull() ?: return@forEach
                if (itag == 18 || itag == 22 || itag == 37) {
                    availableQualities.add(itag)
                }
            }

            // 4. Generate quality URLs from lh3 base URL
            if (lh3BaseUrl != null && availableQualities.isNotEmpty()) {
                data class QualityInfo(val itag: Int, val suffix: String, val qualityName: String, val qualityValue: Int)
                val qualityMap = listOf(
                    QualityInfo(18, "=m18", "480p", Qualities.P480.value),
                    QualityInfo(22, "=m22", "720p", Qualities.P720.value),
                    QualityInfo(37, "=m37", "1080p", Qualities.P1080.value)
                )
                for (qi in qualityMap) {
                    if (availableQualities.contains(qi.itag)) {
                        val qualityUrl = lh3BaseUrl!! + qi.suffix
                        links.add(
                            newExtractorLink(
                                source = serverName,
                                name = "$serverName - ${qi.qualityName}",
                                url = qualityUrl,
                                type = INFER_TYPE
                            ) {
                                this.referer = ""
                                this.quality = qi.qualityValue
                            }
                        )
                    }
                }
            }

            // 5. If no quality arrays found but we have lh3 base URL, try common qualities
            if (lh3BaseUrl != null && availableQualities.isEmpty() && links.isEmpty()) {
                data class SimpleQuality(val suffix: String, val qualityName: String, val qualityValue: Int)
                for (sq in listOf(
                    SimpleQuality("=m22", "720p", Qualities.P720.value),
                    SimpleQuality("=m37", "1080p", Qualities.P1080.value),
                    SimpleQuality("=m18", "480p", Qualities.P480.value)
                )) {
                    links.add(
                        newExtractorLink(
                            source = serverName,
                            name = "$serverName - ${sq.qualityName}",
                            url = lh3BaseUrl!! + sq.suffix,
                            type = INFER_TYPE
                        ) {
                            this.referer = ""
                            this.quality = sq.qualityValue
                        }
                    )
                }
            }

            for (link in links) {
                callback(link)
            }
            return links.isNotEmpty()
        } catch (_: Exception) {
            return false
        }
    }

    private suspend fun extractOkRu(
        url: String,
        serverName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Try CloudStream's built-in ok.ru extractor (fast path)
        try {
            withTimeoutOrNull(10000L) {
                loadExtractor(url, mainUrl, subtitleCallback, callback)
            }?.let { return true }
        } catch (_: Exception) {}

        // Fallback: Use ok.ru metadata API with timeout
        try {
            val agents = getAgents()
            val videoId = Regex("""ok\.ru/video(?:embed)?/(\d+)""").find(url)?.groupValues?.get(1)
                ?: return false

            val userAgent = agents?.okRuAgent?.takeIf { it.isNotEmpty() }
                ?: System.getProperty("http.agent")
                ?: "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/107.0.0.0 Mobile Safari/537.36"

            val cookie = agents?.okRuCookie?.takeIf { it.isNotEmpty() } ?: ""

            val metadataUrl = "https://ok.ru/dk?cmd=videoPlayerMetadata&mid=$videoId"
            val metaResponse = withTimeoutOrNull(10000L) {
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

            // Try ondemandHls first
            val hlsUrl = metadata?.get("ondemandHls")?.asText()
            if (hlsUrl != null && hlsUrl.isNotEmpty()) {
                val hlsResponse = withTimeoutOrNull(10000L) {
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

            // Try "videos" array from metadata
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
        } catch (_: Exception) {}

        return false
    }

    /**
     * Extract video from Dailymotion URLs.
     * The PO server in Anime Day supports dailymotion as a video host.
     */
    private suspend fun extractDailymotion(
        url: String,
        serverName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Try CloudStream's built-in extractor
        try {
            withTimeoutOrNull(10000L) {
                loadExtractor(url, mainUrl, subtitleCallback, callback)
            }?.let { return true }
        } catch (_: Exception) {}
        return false
    }

    /**
     * Extract video from GDPlayerPro URLs.
     * GDPlayerPro typically hosts video streams that can be resolved via their embed page.
     */
    private suspend fun extractGdPlayerPro(
        url: String,
        serverName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Try CloudStream's built-in extractor
        try {
            withTimeoutOrNull(10000L) {
                loadExtractor(url, mainUrl, subtitleCallback, callback)
            }?.let { return true }
        } catch (_: Exception) {}
        return false
    }

    private suspend fun extractFromUrl(
        url: String,
        serverName: String,
        needsExtraction: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val cleanUrl = url.trim()

        // 1. Workers.dev URLs - fastest path, only needs Basic Auth
        if (cleanUrl.contains(".workers.dev/")) {
            return extractWorkerUrl(cleanUrl, serverName, callback)
        }

        // 2. Direct video URLs (mp4, m3u8) — instant, no HTTP needed
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

        // 3. CDN URLs (okcdn.ru, vkuser.net) - pass directly
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

        // 4. ok.ru video URLs
        if (cleanUrl.contains("ok.ru/video") || cleanUrl.contains("ok.ru/videoembed")) {
            return extractOkRu(cleanUrl, serverName, subtitleCallback, callback)
        }

        // 5. Dailymotion URLs
        if (cleanUrl.contains("dailymotion.com") || cleanUrl.contains("dai.ly")) {
            return extractDailymotion(cleanUrl, serverName, subtitleCallback, callback)
        }

        // 6. GDPlayerPro URLs
        if (cleanUrl.contains("gdplayer")) {
            return extractGdPlayerPro(cleanUrl, serverName, subtitleCallback, callback)
        }

        // 7. Google Photos URLs — slower, try later
        if (cleanUrl.contains("photos.google.com")) {
            return extractGooglePhotos(cleanUrl, serverName, callback)
        }

        // 8. Google CDN URLs (lh3.googleusercontent.com)
        if (cleanUrl.contains("googleusercontent.com")) {
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

        // 9. Unknown URL - try CloudStream's built-in extractor
        try {
            withTimeoutOrNull(8000L) {
                loadExtractor(cleanUrl, mainUrl, subtitleCallback, callback)
            }?.let { return true }
        } catch (_: Exception) {}

        // 10. Last resort: pass as direct link
        try {
            callback(
                newExtractorLink(
                    source = serverName,
                    name = serverName,
                    url = cleanUrl,
                    type = INFER_TYPE
                ) {
                    this.referer = ""
                }
            )
            return true
        } catch (_: Exception) {
            return false
        }
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

    override suspend fun load(url: String): LoadResponse? {
        val cartoonId = url.substringAfterLast("/")

        val playlistsText = app.post(
            "$apiUrl/playlist/read.php",
            headers = authHeaders + mapOf("Content-Type" to "application/x-www-form-urlencoded"),
            data = mapOf("cartoon_id" to cartoonId)
        ).text
        val playlists = parseList<Playlist>(playlistsText)
        if (playlists.isEmpty()) return null

        // Fetch episodes for all playlists
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
            } catch (_: Exception) {}
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val linkData = tryParseJson<LinkData>(data) ?: return false
        val videoUrls = linkData.videoUrls
        if (videoUrls.isEmpty()) return false

        var foundAny = false
        coroutineScope {
            videoUrls.entries.map { (serverName, urlAndExtraction) ->
                async {
                    // Each server gets max 15s to resolve
                    val result = withTimeoutOrNull(15000L) {
                        try {
                            val (url, needsExtraction) = urlAndExtraction
                            extractFromUrl(url, serverName, needsExtraction, subtitleCallback, callback)
                        } catch (_: Exception) {
                            false
                        }
                    } ?: false
                    if (result) foundAny = true
                }
            }.forEach { it.await() }
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
