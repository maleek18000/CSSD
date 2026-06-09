package com.appsanime

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

// Link data stores raw video URLs from the episode - resolved in loadLinks()
data class LinkData(
    val videoUrls: Map<String, String>  // server name -> raw URL from API
)

// ==================== Provider ====================

class AppsAnimeProvider : MainAPI() {
    override var mainUrl = "https://appsanime.com"
    override var name = "AppsAnime"
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

    private fun extractOkVideoId(okcdnUrl: String): String? {
        val idParam = okcdnUrl.substringAfter("id=", "")
            .substringBefore("&")
            .substringBefore(" ")
            .substringBefore("\r")
            .substringBefore("\n")
            .trim()
        return idParam.takeIf { it.isNotEmpty() && it.all { c -> c.isDigit() } }
    }

    private fun collectVideoUrls(episode: Episode): Map<String, String> {
        val videos = mutableMapOf<String, String>()
        episode.video?.trim()?.takeIf { it.isNotEmpty() }?.let { videos["سيرفر 1"] = it }
        episode.video1?.trim()?.takeIf { it.isNotEmpty() }?.let { videos["سيرفر 2"] = it }
        episode.video2?.trim()?.takeIf { it.isNotEmpty() }?.let { videos["سيرفر 3"] = it }
        episode.video3?.trim()?.takeIf { it.isNotEmpty() }?.let { videos["سيرفر 4"] = it }
        episode.video4?.trim()?.takeIf { it.isNotEmpty() }?.let { videos["سيرفر 5"] = it }
        episode.video5?.trim()?.takeIf { it.isNotEmpty() }?.let { videos["سيرفر 6"] = it }
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

    // ==================== Video Extraction ====================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val linkData = tryParseJson<LinkData>(data) ?: return false
        val videoUrls = linkData.videoUrls
        if (videoUrls.isEmpty()) return false

        // Try to resolve ok.ru video ID from the available URLs
        // and use CloudStream's built-in ok.ru extractor
        val okVideoId = resolveOkVideoIdFromUrls(videoUrls)

        if (okVideoId != null) {
            val okUrl = "https://ok.ru/video/$okVideoId"
            try {
                loadExtractor(okUrl, mainUrl, subtitleCallback, callback)
                return true
            } catch (_: Exception) {}
        }

        // Fallback: try each URL individually
        var foundAny = false
        coroutineScope {
            videoUrls.entries.map { (serverName, url) ->
                async {
                    try {
                        if (extractFromUrl(url, serverName, subtitleCallback, callback)) {
                            foundAny = true
                        }
                    } catch (_: Exception) {}
                }
            }.forEach { it.await() }
        }

        return foundAny
    }

    /**
     * Try to resolve the ok.ru video ID from a map of video URLs.
     * Tries test-stream first (reliable), then cdnlink.
     */
    private suspend fun resolveOkVideoIdFromUrls(videoUrls: Map<String, String>): String? {
        // Try test-stream URLs first (they return JSON reliably)
        for ((_, url) in videoUrls) {
            if (url.contains("test-stream")) {
                try {
                    val responseText = app.get(url, headers = headers).text
                    val res = parseObject<StreamResponse>(responseText) ?: continue
                    val firstUrl = res.availableQualities?.firstOrNull()?.url ?: continue
                    extractOkVideoId(firstUrl)?.let { return it }
                } catch (_: Exception) {}
            }
        }

        // Try cdnlink URLs (they redirect to okcdn.ru)
        for ((_, url) in videoUrls) {
            if (url.contains("cdnlink.developer-pro.workers.dev")) {
                try {
                    val response = app.get(url, headers = headers)
                    // Check final URL after redirect (even on 400, URL might be accessible)
                    val finalUrl = response.url
                    if (finalUrl != url && finalUrl.contains("okcdn.ru")) {
                        extractOkVideoId(finalUrl)?.let { return it }
                    }
                    // Check Location header for redirect
                    val location = response.headers?.get("location")
                    if (!location.isNullOrBlank() && location.contains("okcdn.ru")) {
                        extractOkVideoId(location)?.let { return it }
                    }
                } catch (_: Exception) {}
            }
        }

        return null
    }

    /**
     * Extract video from a single URL. Returns true if links were found.
     */
    private suspend fun extractFromUrl(
        url: String,
        serverName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        when {
            // test-stream worker - returns JSON with qualities
            url.contains("test-stream") -> {
                try {
                    val responseText = app.get(url, headers = headers).text
                    val res = parseObject<StreamResponse>(responseText) ?: return false
                    val qualities = res.availableQualities ?: return false
                    if (qualities.isEmpty()) return false

                    // Try ok.ru extractor first
                    val firstUrl = qualities.firstOrNull()?.url ?: return false
                    val okVideoId = extractOkVideoId(firstUrl)
                    if (okVideoId != null) {
                        try {
                            loadExtractor("https://ok.ru/video/$okVideoId", mainUrl, subtitleCallback, callback)
                            return true
                        } catch (_: Exception) {}
                    }

                    // Fallback: direct links
                    qualities.forEach { q ->
                        val qualityStr = q.quality ?: return@forEach
                        val streamUrl = q.url ?: return@forEach
                        callback(
                            newExtractorLink(source = serverName, name = "$serverName - $qualityStr", url = streamUrl, type = INFER_TYPE) {
                                this.referer = ""
                                this.quality = getQualityValue(qualityStr)
                            }
                        )
                    }
                    return true
                } catch (_: Exception) { return false }
            }

            // cdnlink worker - 302 redirect to okcdn.ru
            url.contains("cdnlink.developer-pro.workers.dev") -> {
                try {
                    val response = app.get(url, headers = headers)
                    val finalUrl = response.url

                    if (finalUrl != url && finalUrl.contains("okcdn.ru")) {
                        val okVideoId = extractOkVideoId(finalUrl)
                        if (okVideoId != null) {
                            try {
                                loadExtractor("https://ok.ru/video/$okVideoId", mainUrl, subtitleCallback, callback)
                                return true
                            } catch (_: Exception) {}
                        }
                        // Direct link fallback
                        callback(
                            newExtractorLink(source = serverName, name = "$serverName - Auto", url = finalUrl, type = INFER_TYPE) {
                                this.referer = ""
                            }
                        )
                        return true
                    }

                    // Check Location header
                    val location = response.headers?.get("location")
                    if (!location.isNullOrBlank() && location.contains("okcdn.ru")) {
                        val okVideoId = extractOkVideoId(location)
                        if (okVideoId != null) {
                            try {
                                loadExtractor("https://ok.ru/video/$okVideoId", mainUrl, subtitleCallback, callback)
                                return true
                            } catch (_: Exception) {}
                        }
                    }
                } catch (_: Exception) {}
                return false
            }

            // Google Photos - use CloudStream's built-in extractor
            url.contains("photos.google.com") -> {
                try {
                    loadExtractor(url, mainUrl, subtitleCallback, callback)
                    return true
                } catch (_: Exception) { return false }
            }

            // Direct video URLs
            url.contains(".mp4") || url.contains(".m3u8") -> {
                val quality = getQualityFromUrl(url)
                callback(
                    newExtractorLink(source = serverName, name = "$serverName - $quality", url = url,
                        type = if (url.contains(".m3u8")) INFER_TYPE else ExtractorLinkType.VIDEO) {
                        this.referer = mainUrl
                        this.quality = getQualityValue(quality)
                    }
                )
                return true
            }

            // ok.ru or other known hosts - use built-in extractor
            else -> {
                try {
                    loadExtractor(url, mainUrl, subtitleCallback, callback)
                    return true
                } catch (_: Exception) { return false }
            }
        }
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
