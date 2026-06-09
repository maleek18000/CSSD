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
    @JsonProperty("id") val id: String? = null,
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
)

data class EpisodeWithInfoLatest(
    @JsonProperty("id") val id: String? = null,
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

// Episode data stored in newEpisode() - fetched fresh in loadLinks
data class EpisodeLinkData(
    val playlistId: String,
    val episodeId: String,
    val episodeTitle: String = ""
)

// Movie data stored in newMovieLoadResponse()
data class MovieLinkData(
    val cartoonId: String,
    val playlistId: String? = null,
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

        // Fetch playlists first - this gives us the cartoon structure
        val playlistsText = app.post(
            "$apiUrl/playlist/read.php",
            headers = authHeaders + mapOf("Content-Type" to "application/x-www-form-urlencoded"),
            data = mapOf("cartoon_id" to cartoonId)
        ).text
        val playlists = parseList<Playlist>(playlistsText)

        // Get cartoon info from the first playlist's episodes or search
        val cartoonInfo = fetchCartoonInfo(cartoonId, playlists)
        val title = cartoonInfo.title ?: return null
        val poster = cartoonInfo.thumb
        val scoreValue = Score.from10(cartoonInfo.worldRate)
        val categories = cartoonInfo.category?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        val isMovie = cartoonInfo.type == "2" || (playlists.size == 1 && playlists.first().title?.contains("فيلم") == true)

        if (isMovie || playlists.isEmpty()) {
            // Movie - store cartoonId and playlistId for fresh fetch in loadLinks
            val playlistId = playlists.firstOrNull()?.id
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

        // TV Series - fetch episodes for each playlist using episodeWithInfo (no pagination)
        val tvEpisodes = mutableListOf<Episode>()

        for ((seasonIndex, playlist) in playlists.withIndex()) {
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

        val episodes = tvEpisodes.mapIndexed { idx, ep ->
            val epId = ep.id ?: idx.toString()
            val pId = ep.playlistId?.toString() ?: playlists.firstOrNull()?.id ?: ""
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
                this.posterUrl = ep.thumb?.ifEmpty { poster }
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
        // Try to get info from most viewed list
        try {
            val text = app.get(
                "$apiUrl/cartoon_with_info/getMostViewedCartoons.php",
                headers = authHeaders
            ).text
            val items = parseList<CartoonWithInfo>(text)
            items.find { it.id == cartoonId }?.let { return it }
        } catch (_: Exception) {}

        // Try search with the cartoon ID (won't work but worth trying title from playlist)
        // Use playlist info to infer type
        val isInferredMovie = playlists.size == 1

        return CartoonWithInfo(
            id = cartoonId,
            title = playlists.firstOrNull()?.title?.substringBefore(" الجزء")?.substringBefore(" الموسم")?.trim() ?: "Anime #$cartoonId",
            type = if (isInferredMovie) "2" else "1",
            thumb = playlists.firstOrNull()?.thumb
        )
    }

    // ==================== Video Extraction (FRESH FETCH) ====================

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
        // Fetch fresh episode data from the API at play time
        val videos = fetchFreshEpisodeVideos(epData.playlistId, epData.episodeId)
        if (videos.isEmpty()) return false

        extractAllVideos(videos, subtitleCallback, callback)
        return true
    }

    private suspend fun loadMovieLinks(
        movieData: MovieLinkData,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val playlistId = movieData.playlistId ?: return false

        // Fetch fresh episode data for the movie
        val videos = fetchFreshEpisodeVideos(playlistId, movieData.episodeId)
        if (videos.isEmpty()) return false

        extractAllVideos(videos, subtitleCallback, callback)
        return true
    }

    private suspend fun fetchFreshEpisodeVideos(playlistId: String, episodeId: String?): Map<String, String> {
        val videos = mutableMapOf<String, String>()

        try {
            // Use episodeWithInfo which returns ALL episodes without pagination
            val text = app.post(
                "$apiUrl/episodeWithInfo/readPaging.php",
                headers = authHeaders + mapOf("Content-Type" to "application/x-www-form-urlencoded"),
                data = mapOf("playlist_id" to playlistId)
            ).text
            val episodes = parseList<Episode>(text)

            val ep = if (episodeId != null) {
                episodes.find { it.id == episodeId }
            } else {
                episodes.firstOrNull()
            } ?: return emptyMap()

            collectVideoUrls(ep, videos)
        } catch (_: Exception) {}

        // If no videos from episodeWithInfo, try the paginated endpoint
        if (videos.isEmpty()) {
            try {
                val text = app.post(
                    "$apiUrl/episode/readPaging.php?page=1",
                    headers = authHeaders + mapOf("Content-Type" to "application/x-www-form-urlencoded"),
                    data = mapOf("playlist_id" to playlistId)
                ).text
                val episodes = parseList<Episode>(text)

                val ep = if (episodeId != null) {
                    episodes.find { it.id == episodeId }
                } else {
                    episodes.firstOrNull()
                } ?: return videos

                collectVideoUrls(ep, videos)
            } catch (_: Exception) {}
        }

        return videos
    }

    private fun collectVideoUrls(episode: Episode, videos: MutableMap<String, String>) {
        episode.video?.takeIf { it.isNotEmpty() }?.let { videos["سيرفر 1"] = it }
        episode.video1?.takeIf { it.isNotEmpty() }?.let { videos["سيرفر 2"] = it }
        episode.video2?.takeIf { it.isNotEmpty() }?.let { videos["سيرفر 3"] = it }
        episode.video3?.takeIf { it.isNotEmpty() }?.let { videos["سيرفر 4"] = it }
        episode.video4?.takeIf { it.isNotEmpty() }?.let { videos["سيرفر 5"] = it }
        episode.video5?.takeIf { it.isNotEmpty() }?.let { videos["سيرفر 6"] = it }
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
            url.contains("test-stream") -> {
                extractTestStream(url, serverName, callback)
            }
            // Any developer-pro.workers.dev worker
            url.contains("developer-pro.workers.dev") -> {
                extractWorkerLink(url, serverName, subtitleCallback, callback)
            }
            // Any apps-anime.workers.dev worker
            url.contains("apps-anime.workers.dev") -> {
                extractWorkerLink(url, serverName, subtitleCallback, callback)
            }
            // Google Photos
            url.contains("photos.google.com") -> {
                extractGooglePhotos(url, serverName, callback)
            }
            // Direct video URLs (.mp4, .m3u8)
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
            // Known video hosts - use CloudStream built-in extractors
            else -> {
                try {
                    loadExtractor(url, mainUrl, subtitleCallback, callback)
                } catch (_: Exception) {}
            }
        }
    }

    private suspend fun extractTestStream(
        url: String,
        serverName: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val responseText = app.get(url, headers = authHeaders).text
            val res = parseObject<StreamResponse>(responseText) ?: return
            val qualities = res.availableQualities ?: return

            if (qualities.isEmpty()) return

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
                        this.referer = mainUrl
                        this.quality = getQualityValue(qualityStr)
                    }
                )
            }
        } catch (_: Exception) {}
    }

    private suspend fun extractWorkerLink(
        url: String,
        serverName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val response = app.get(url, headers = authHeaders)

            // Try parsing as JSON with availableQualities
            val streamRes = parseObject<StreamResponse>(response.text)
            if (streamRes?.availableQualities?.isNotEmpty() == true) {
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

            // Check if the response is a redirect to another URL
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

    private suspend fun extractGooglePhotos(
        url: String,
        serverName: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val doc = app.get(url, headers = headers).document
            val html = doc.toString()

            // Multiple patterns for Google Photos video URLs
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
