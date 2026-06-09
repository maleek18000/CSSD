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
    @JsonProperty("playlist_id") val playlistId: Any? = null
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

// Data stored in episode/movie items - fetched fresh in loadLinks
data class EpisodeLinkData(
    val playlistId: String,
    val episodeId: String,
    val episodeTitle: String = ""
)

data class MovieLinkData(
    val cartoonId: String,
    val playlistId: String,
    val episodeId: String? = null
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
            "1" to "2", // series subbed
            "1" to "1", // series dubbed
            "2" to "2", // film subbed
            "2" to "1"  // film dubbed
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

        // Fetch playlists - gives us the cartoon structure (seasons/parts)
        val playlistsText = app.post(
            "$apiUrl/playlist/read.php",
            headers = authHeaders + mapOf("Content-Type" to "application/x-www-form-urlencoded"),
            data = mapOf("cartoon_id" to cartoonId)
        ).text
        val playlists = parseList<Playlist>(playlistsText)

        if (playlists.isEmpty()) return null

        // Get cartoon info - we need type to distinguish movies from series
        val cartoonInfo = fetchCartoonInfo(cartoonId, playlists)
        val title = cartoonInfo.title ?: return null
        val poster = cartoonInfo.thumb
        val scoreValue = Score.from10(cartoonInfo.worldRate)
        val categories = cartoonInfo.category?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

        // type="2" = film, type="1" = series
        // Also check if ALL playlist titles contain "فيلم" (movie) as secondary indicator
        val isMovie = cartoonInfo.type == "2" ||
            playlists.all { it.title?.contains("فيلم") == true }

        if (isMovie) {
            val playlistId = playlists.firstOrNull()?.id ?: return null
            val linkData = MovieLinkData(
                cartoonId = cartoonId,
                playlistId = playlistId
            )
            return newMovieLoadResponse(title, url, TvType.Movie, linkData.toJson()) {
                this.posterUrl = poster
                this.score = scoreValue
                this.tags = categories
                plot = buildPlot(cartoonInfo)
            }
        }

        // TV Series - fetch episodes for each playlist (season)
        val tvEpisodes = mutableListOf<Episode>()

        for (playlist in playlists) {
            val playlistId = playlist.id ?: continue
            try {
                val epsText = app.post(
                    "$apiUrl/episodeWithInfo/readPaging.php",
                    headers = authHeaders + mapOf("Content-Type" to "application/x-www-form-urlencoded"),
                    data = mapOf("playlist_id" to playlistId)
                ).text
                val eps = parseList<Episode>(epsText)
                tvEpisodes.addAll(eps)
            } catch (_: Exception) {}
        }

        if (tvEpisodes.isEmpty()) return null

        val episodes = tvEpisodes.mapIndexed { idx, ep ->
            val epId = ep.getIdString()
            val pId = ep.getPlaylistIdString().ifEmpty { playlists.firstOrNull()?.id ?: "" }
            val linkData = EpisodeLinkData(
                playlistId = pId,
                episodeId = epId,
                episodeTitle = ep.title ?: "الحلقة ${idx + 1}"
            )
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

        val classificationTag = when (cartoonInfo.classification) {
            "1" -> "مدبلج"
            "2" -> "مترجم"
            else -> ""
        }
        val displayTitle = if (classificationTag.isNotEmpty()) "$title ($classificationTag)" else title

        return newTvSeriesLoadResponse(displayTitle, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.score = scoreValue
            this.tags = categories
            plot = buildPlot(cartoonInfo)
        }
    }

    private fun buildPlot(info: CartoonWithInfo): String {
        val parts = mutableListOf<String>()
        info.classification?.let {
            parts.add(if (it == "1") "مدبلج" else "مترجم")
        }
        info.viewDate?.let { parts.add(it) }
        info.status?.let {
            parts.add(if (it == "1") "مكتمل" else "مستمر")
        }
        info.worldRate?.let { parts.add("التقييم: $it") }
        info.watchedUsers?.let { parts.add("المشاهدات: $it") }
        info.category?.let { parts.add("التصنيفات: $it") }
        return parts.joinToString(" | ")
    }

    private fun extractEpisodeNumber(title: String): Int? {
        Regex("الحلقة\\s*(\\d+)").find(title)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        Regex("(?:Episode|Ep\\.?)\\s*(\\d+)", RegexOption.IGNORE_CASE).find(title)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        Regex("^(\\d+)$").find(title.trim())?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        return null
    }

    private suspend fun fetchCartoonInfo(cartoonId: String, playlists: List<Playlist>): CartoonWithInfo {
        // Try to get info from the most viewed list
        try {
            val text = app.get(
                "$apiUrl/cartoon_with_info/getMostViewedCartoons.php",
                headers = authHeaders
            ).text
            val items = parseList<CartoonWithInfo>(text)
            items.find { it.id == cartoonId }?.let { return it }
        } catch (_: Exception) {}

        // Try searching for the cartoon using playlist title
        val searchTitle = playlists.firstOrNull()?.title
            ?.replace(Regex("الجزء\\s*\\d+"), "")
            ?.replace(Regex("الموسم\\s*\\d+"), "")
            ?.trim()
        if (!searchTitle.isNullOrBlank()) {
            try {
                for (classification in listOf("1", "2")) {
                    for (type in listOf("1", "2")) {
                        try {
                            val text = app.get(
                                "$apiUrl/cartoon_with_info/searchCartoon.php",
                                params = mapOf("search" to searchTitle, "type" to type, "classification" to classification),
                                headers = authHeaders
                            ).text
                            val items = parseList<CartoonWithInfo>(text)
                            items.find { it.id == cartoonId }?.let { return it }
                        } catch (_: Exception) {}
                    }
                }
            } catch (_: Exception) {}
        }

        // Fallback: infer type from playlist info
        val isInferredMovie = playlists.all { it.title?.contains("فيلم") == true }

        return CartoonWithInfo(
            id = cartoonId,
            title = playlists.firstOrNull()?.title
                ?.replace(Regex("الجزء\\s*\\d+"), "")
                ?.replace(Regex("الموسم\\s*\\d+"), "")
                ?.trim() ?: "Anime #$cartoonId",
            type = if (isInferredMovie) "2" else "1",
            thumb = playlists.firstOrNull()?.thumb
        )
    }

    // ==================== Video Extraction ====================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Try parsing as episode data first
        val epData = tryParseJson<EpisodeLinkData>(data)
        if (epData != null) {
            return loadEpisodeLinks(epData, subtitleCallback, callback)
        }

        // Try parsing as movie data
        val movieData = tryParseJson<MovieLinkData>(data)
        if (movieData != null) {
            return loadMovieLinks(movieData, subtitleCallback, callback)
        }

        return false
    }

    private suspend fun loadEpisodeLinks(
        epData: EpisodeLinkData,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episode = fetchFreshEpisode(epData.playlistId, epData.episodeId)
            ?: return false

        val videoUrls = collectVideoUrls(episode)
        if (videoUrls.isEmpty()) return false

        extractAllVideos(videoUrls, subtitleCallback, callback)
        return true
    }

    private suspend fun loadMovieLinks(
        movieData: MovieLinkData,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episode = fetchFreshEpisode(movieData.playlistId, movieData.episodeId)
            ?: return false

        val videoUrls = collectVideoUrls(episode)
        if (videoUrls.isEmpty()) return false

        extractAllVideos(videoUrls, subtitleCallback, callback)
        return true
    }

    private suspend fun fetchFreshEpisode(playlistId: String, episodeId: String?): Episode? {
        // Use episodeWithInfo which returns ALL episodes without pagination
        try {
            val text = app.post(
                "$apiUrl/episodeWithInfo/readPaging.php",
                headers = authHeaders + mapOf("Content-Type" to "application/x-www-form-urlencoded"),
                data = mapOf("playlist_id" to playlistId)
            ).text
            val episodes = parseList<Episode>(text)

            if (episodeId != null) {
                episodes.find { it.getIdString() == episodeId }?.let { return it }
            }
            return episodes.firstOrNull()
        } catch (_: Exception) {}

        // Fallback: try the paginated endpoint
        try {
            val text = app.post(
                "$apiUrl/episode/readPaging.php?page=1",
                headers = authHeaders + mapOf("Content-Type" to "application/x-www-form-urlencoded"),
                data = mapOf("playlist_id" to playlistId)
            ).text
            val episodes = parseList<Episode>(text)

            if (episodeId != null) {
                episodes.find { it.getIdString() == episodeId }?.let { return it }
            }
            return episodes.firstOrNull()
        } catch (_: Exception) {}

        return null
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

    private suspend fun extractAllVideos(
        videos: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        coroutineScope {
            videos.entries.map { (serverName, url) ->
                async {
                    try {
                        extractFromUrl(url, serverName, subtitleCallback, callback)
                    } catch (_: Exception) {}
                }
            }.forEach { it.await() }
        }
    }

    private suspend fun extractFromUrl(
        url: String,
        serverName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        when {
            // test-stream worker - returns JSON with availableQualities
            // Each quality entry has a okcdn.ru URL which contains the ok.ru video ID
            url.contains("test-stream") -> {
                extractTestStream(url, serverName, subtitleCallback, callback)
            }
            // cdnlink worker - returns 302 redirect to okcdn.ru
            // Extract ok.ru video ID from the redirect URL
            url.contains("cdnlink.developer-pro.workers.dev") -> {
                extractCdnLink(url, serverName, subtitleCallback, callback)
            }
            // Other developer-pro workers
            url.contains("link.developer-pro.workers.dev") ||
            url.contains("linkcdn.developer-pro.workers.dev") -> {
                extractWorkerLink(url, serverName, subtitleCallback, callback)
            }
            // apps-anime workers
            url.contains("apps-anime.workers.dev") -> {
                extractWorkerLink(url, serverName, subtitleCallback, callback)
            }
            // Google Photos
            url.contains("photos.google.com") -> {
                extractGooglePhotos(url, serverName, callback)
            }
            // Direct video URLs
            url.contains(".mp4") || url.contains(".m3u8") -> {
                val quality = getQualityFromUrl(url)
                callback(
                    newExtractorLink(
                        source = serverName,
                        name = "$serverName - $quality",
                        url = url,
                        type = if (url.contains(".m3u8")) INFER_TYPE else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = mainUrl
                        this.headers = headers
                        this.quality = getQualityValue(quality)
                    }
                )
            }
            // ok.ru page - use CloudStream built-in extractor
            url.contains("ok.ru") -> {
                try {
                    loadExtractor(url, mainUrl, subtitleCallback, callback)
                } catch (_: Exception) {}
            }
            // Other video hosts - use CloudStream built-in extractors
            else -> {
                try {
                    loadExtractor(url, mainUrl, subtitleCallback, callback)
                } catch (_: Exception) {}
            }
        }
    }

    /**
     * Extract ok.ru video ID from an okcdn.ru CDN URL.
     * okcdn.ru URLs have an "id" parameter which is the ok.ru video ID.
     * Example: https://vd436.okcdn.ru/?...&id=7455078484594
     * This ID can be used with the ok.ru page URL: https://ok.ru/video/7455078484594
     */
    private fun extractOkVideoId(okcdnUrl: String): String? {
        val idParam = okcdnUrl.substringAfter("id=", "")
            .substringBefore("&")
            .substringBefore(" ")
            .trim()
        return idParam.takeIf { it.isNotEmpty() && it.all { c -> c.isDigit() } }
    }

    /**
     * test-stream worker returns JSON:
     * {"availableQualities": [{"quality":"1080p","url":"https://vd436.okcdn.ru/...&id=XXXX"}, ...]}
     *
     * Strategy: Extract the ok.ru video ID from the first quality URL,
     * then use CloudStream's built-in ok.ru extractor with the page URL.
     */
    private suspend fun extractTestStream(
        url: String,
        serverName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val responseText = app.get(url, headers = headers).text
            val res = parseObject<StreamResponse>(responseText) ?: return
            val qualities = res.availableQualities ?: return

            if (qualities.isEmpty()) return

            // Extract ok.ru video ID from the first available URL
            val firstUrl = qualities.firstOrNull()?.url ?: return
            val okVideoId = extractOkVideoId(firstUrl)

            if (okVideoId != null) {
                // Use CloudStream's built-in ok.ru extractor
                val okUrl = "https://ok.ru/video/$okVideoId"
                try {
                    loadExtractor(okUrl, mainUrl, subtitleCallback, callback)
                    return
                } catch (_: Exception) {}
            }

            // Fallback: try each quality URL as a direct link
            // (okcdn.ru URLs are IP-restricted but may work on the user's device)
            qualities.forEach { q ->
                val qualityStr = q.quality ?: return@forEach
                val streamUrl = q.url ?: return@forEach

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
        } catch (_: Exception) {}
    }

    /**
     * cdnlink worker returns 302 redirect to okcdn.ru.
     * Strategy: Follow the redirect, extract the ok.ru video ID from the final URL,
     * then use CloudStream's built-in ok.ru extractor.
     */
    private suspend fun extractCdnLink(
        url: String,
        serverName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            // Follow the redirect - cdnlink returns 302 to okcdn.ru
            val response = app.get(url, headers = headers)
            val finalUrl = response.url

            if (finalUrl != url && finalUrl.isNotEmpty()) {
                // Got redirected
                if (finalUrl.contains("okcdn.ru")) {
                    // Extract ok.ru video ID and use the extractor
                    val okVideoId = extractOkVideoId(finalUrl)
                    if (okVideoId != null) {
                        val okUrl = "https://ok.ru/video/$okVideoId"
                        try {
                            loadExtractor(okUrl, mainUrl, subtitleCallback, callback)
                            return
                        } catch (_: Exception) {}
                    }
                    // Fallback: pass as direct link
                    callback(
                        newExtractorLink(
                            source = serverName,
                            name = "$serverName - Auto",
                            url = finalUrl,
                            type = INFER_TYPE
                        ) {
                            this.referer = ""
                        }
                    )
                    return
                } else {
                    // Redirected to something other than okcdn.ru
                    extractFromUrl(finalUrl, serverName, subtitleCallback, callback)
                    return
                }
            }

            // No redirect detected - try parsing as JSON
            val streamRes = parseObject<StreamResponse>(response.text)
            if (streamRes?.availableQualities?.isNotEmpty() == true) {
                val firstUrl = streamRes.availableQualities.firstOrNull()?.url ?: return
                val okVideoId = extractOkVideoId(firstUrl)
                if (okVideoId != null) {
                    val okUrl = "https://ok.ru/video/$okVideoId"
                    try {
                        loadExtractor(okUrl, mainUrl, subtitleCallback, callback)
                        return
                    } catch (_: Exception) {}
                }
                // Fallback: direct links
                streamRes.availableQualities.forEach { q ->
                    val qualityStr = q.quality ?: return@forEach
                    val streamUrl = q.url ?: return@forEach
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
            }
        } catch (_: Exception) {}
    }

    /**
     * Generic worker link handler for link/linkcdn/apps-anime workers.
     */
    private suspend fun extractWorkerLink(
        url: String,
        serverName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val response = app.get(url, headers = headers)

            // Try parsing as JSON with availableQualities
            val streamRes = parseObject<StreamResponse>(response.text)
            if (streamRes?.availableQualities?.isNotEmpty() == true) {
                val firstUrl = streamRes.availableQualities.firstOrNull()?.url ?: return
                val okVideoId = extractOkVideoId(firstUrl)
                if (okVideoId != null) {
                    val okUrl = "https://ok.ru/video/$okVideoId"
                    try {
                        loadExtractor(okUrl, mainUrl, subtitleCallback, callback)
                        return
                    } catch (_: Exception) {}
                }
                // Fallback
                streamRes.availableQualities.forEach { q ->
                    val qualityStr = q.quality ?: return@forEach
                    val streamUrl = q.url ?: return@forEach
                    callback(
                        newExtractorLink(
                            source = serverName,
                            name = "$serverName - $qualityStr",
                            url = streamUrl,
                            type = INFER_TYPE
                        ) {
                            this.referer = mainUrl
                            this.quality = getQualityValue(qualityStr)
                        }
                    )
                }
                return
            }

            // Check if redirected
            val finalUrl = response.url
            if (finalUrl != url && finalUrl.isNotEmpty()) {
                extractFromUrl(finalUrl, serverName, subtitleCallback, callback)
                return
            }

            // Try to find direct video URL in the response text
            val text = response.text
            val m3u8Regex = Regex("https?://[^\"'\\s]+\\.m3u8[^\"'\\s]*")
            val mp4Regex = Regex("https?://[^\"'\\s]+\\.mp4[^\"'\\s]*")

            m3u8Regex.find(text)?.value?.let { streamUrl ->
                callback(
                    newExtractorLink(source = serverName, name = "$serverName - Auto", url = streamUrl, type = INFER_TYPE) {
                        this.referer = mainUrl
                    }
                )
                return
            }

            mp4Regex.find(text)?.value?.let { streamUrl ->
                callback(
                    newExtractorLink(source = serverName, name = "$serverName - Auto", url = streamUrl, type = ExtractorLinkType.VIDEO) {
                        this.referer = mainUrl
                    }
                )
                return
            }
        } catch (_: Exception) {}
    }

    /**
     * Google Photos - extract video URL from the shared photo page.
     */
    private suspend fun extractGooglePhotos(
        url: String,
        serverName: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val doc = app.get(url, headers = headers).document
            val html = doc.toString()

            val patterns = listOf(
                Regex("https?://[^\"'\\s]+?googleusercontent\\.com/[^\"'\\s]+?(?:mp4|m3u8)[^\"'\\s]*"),
                Regex("https?://[^\"'\\s]+?googlevideo\\.com/[^\"'\\s]+?(?:mp4|m3u8)[^\"'\\s]*"),
                Regex("\"(https://lh3\\.[^\"']+)\""),
                Regex("\"(https://video\\.google\\.com/[^\"']+)\"")
            )

            for (pattern in patterns) {
                pattern.findAll(html).map { it.groupValues.last() }.distinct().forEach { videoUrl ->
                    callback(
                        newExtractorLink(
                            source = "$serverName (GP)",
                            name = "$serverName - Google Photos",
                            url = videoUrl,
                            type = INFER_TYPE
                        ) {
                            this.referer = "https://photos.google.com/"
                        }
                    )
                }
            }
        } catch (_: Exception) {}
    }

    private fun getQualityFromUrl(url: String): String {
        return when {
            url.contains("1080") -> "1080p"
            url.contains("720") -> "720p"
            url.contains("480") -> "480p"
            url.contains("360") -> "360p"
            url.contains("240") -> "240p"
            else -> "Auto"
        }
    }

    private fun getQualityValue(quality: String): Int {
        return when {
            quality.contains("1080") -> Qualities.P1080.value
            quality.contains("720") -> Qualities.P720.value
            quality.contains("480") -> Qualities.P480.value
            quality.contains("360") -> Qualities.P360.value
            quality.contains("240") -> Qualities.P240.value
            else -> Qualities.Unknown.value
        }
    }
}
