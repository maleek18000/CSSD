package com.appsanime

import com.fasterxml.jackson.annotation.JsonProperty
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
    @JsonProperty("type") val type: String? = null,           // 1=series, 2=film
    @JsonProperty("world_rate") val worldRate: String? = null,
    @JsonProperty("view_date") val viewDate: String? = null,
    @JsonProperty("status") val status: String? = null,       // 1=completed, 2=ongoing
    @JsonProperty("Watched_users") val watchedUsers: String? = null,
    @JsonProperty("classification") val classification: String? = null, // 1=dubbed, 2=subbed
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
    @JsonProperty("jResolver") val jResolver: String? = null,
    @JsonProperty("jResolver1") val jResolver1: String? = null,
    @JsonProperty("jResolver2") val jResolver2: String? = null,
    @JsonProperty("jResolver3") val jResolver3: String? = null,
    @JsonProperty("jResolver4") val jResolver4: String? = null,
    @JsonProperty("jResolver5") val jResolver5: String? = null,
    @JsonProperty("playlist_id") val playlistId: Any? = null
)

data class EpisodeWithInfoLatest(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("thumb") val thumb: String? = null,
    @JsonProperty("video") val video: String? = null,
    @JsonProperty("video1") val video1: String? = null,
    @JsonProperty("video2") val video2: String? = null,
    @JsonProperty("video3") val video3: String? = null,
    @JsonProperty("video4") val video4: String? = null,
    @JsonProperty("video5") val video5: String? = null,
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

// Data class to pass video URLs to loadLinks
data class EpisodeVideoData(
    val videos: Map<String, String>,  // serverName -> url
    val episodeTitle: String = ""
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
            val res = app.get(url, headers = authHeaders).parsedSafe<List<EpisodeWithInfoLatest>>() ?: emptyList()
            val items = res.mapNotNull { ep ->
                val cartoon = ep.cartoon ?: return@mapNotNull null
                cartoon.id?.let { id ->
                    toSearchResult(cartoon)
                }
            }.distinctBy { it.url }
            return newHomePageResponse(request.name, items, false)
        }

        val res = app.get(url, headers = authHeaders).parsedSafe<List<CartoonWithInfo>>() ?: emptyList()
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

        // Search subbed series
        val subbedSeries = app.get(
            "$apiUrl/cartoon_with_info/searchCartoon.php",
            params = mapOf("search" to query, "type" to "1", "classification" to "2"),
            headers = authHeaders
        ).parsedSafe<List<CartoonWithInfo>>() ?: emptyList()

        // Search dubbed series
        val dubbedSeries = app.get(
            "$apiUrl/cartoon_with_info/searchCartoon.php",
            params = mapOf("search" to query, "type" to "1", "classification" to "1"),
            headers = authHeaders
        ).parsedSafe<List<CartoonWithInfo>>() ?: emptyList()

        // Search subbed films
        val subbedFilms = app.get(
            "$apiUrl/cartoon_with_info/searchCartoon.php",
            params = mapOf("search" to query, "type" to "2", "classification" to "2"),
            headers = authHeaders
        ).parsedSafe<List<CartoonWithInfo>>() ?: emptyList()

        // Search dubbed films
        val dubbedFilms = app.get(
            "$apiUrl/cartoon_with_info/searchCartoon.php",
            params = mapOf("search" to query, "type" to "2", "classification" to "1"),
            headers = authHeaders
        ).parsedSafe<List<CartoonWithInfo>>() ?: emptyList()

        (subbedSeries + dubbedSeries + subbedFilms + dubbedFilms).forEach {
            toSearchResult(it)?.let { r -> results.add(r) }
        }

        return results.distinctBy { it.url }
    }

    // ==================== Load Detail ====================

    override suspend fun load(url: String): LoadResponse? {
        val cartoonId = url.substringAfterLast("/")

        // Get cartoon info from most viewed or search (since readOne.php is 404)
        // We fetch a quick search or use the most viewed list
        val cartoonInfo = fetchCartoonInfo(cartoonId) ?: return null

        val title = cartoonInfo.title ?: return null
        val poster = cartoonInfo.thumb
        val scoreValue = Score.from10(cartoonInfo.worldRate)
        val categories = cartoonInfo.category?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

        // Get playlists (seasons)
        val playlists = app.post(
            "$apiUrl/playlist/read.php",
            headers = authHeaders + mapOf("Content-Type" to "application/x-www-form-urlencoded"),
            data = mapOf("cartoon_id" to cartoonId)
        ).parsedSafe<List<Playlist>>() ?: emptyList()

        if (cartoonInfo.type == "2" || playlists.isEmpty()) {
            // It's a movie or single-list show
            if (playlists.isEmpty()) {
                // No playlists at all - create a dummy one
                val videoData = EpisodeVideoData(
                    videos = emptyMap(),
                    episodeTitle = title
                )
                return newMovieLoadResponse(title, url, TvType.Movie, videoData.toJson()) {
                    this.posterUrl = poster
                    this.score = scoreValue
                    this.tags = categories
                    plot = buildPlot(cartoonInfo)
                }
            }

            // Movie with playlists - use first playlist
            val episodes = fetchEpisodes(playlists.first().id ?: return null)
            if (episodes.isEmpty()) {
                val videoData = EpisodeVideoData(videos = emptyMap(), episodeTitle = title)
                return newMovieLoadResponse(title, url, TvType.Movie, videoData.toJson()) {
                    this.posterUrl = poster
                    this.score = scoreValue
                    this.tags = categories
                    plot = buildPlot(cartoonInfo)
                }
            }

            val ep = episodes.first()
            val videoData = EpisodeVideoData(
                videos = collectVideoUrls(ep),
                episodeTitle = ep.title ?: title
            )
            return newMovieLoadResponse(title, url, TvType.Movie, videoData.toJson()) {
                this.posterUrl = poster
                this.score = scoreValue
                this.tags = categories
                plot = buildPlot(cartoonInfo)
            }
        }

        // TV Series with seasons
        val allEpisodes = mutableListOf<Episode>()
        playlists.forEachIndexed { index, playlist ->
            val playlistId = playlist.id ?: return@forEachIndexed
            val eps = fetchEpisodes(playlistId)
            eps.forEach { ep ->
                allEpisodes.add(ep)
            }
        }

        val tvEpisodes = allEpisodes.mapIndexed { idx, ep ->
            val videoData = EpisodeVideoData(
                videos = collectVideoUrls(ep),
                episodeTitle = ep.title ?: "الحلقة ${idx + 1}"
            )
            newEpisode(videoData.toJson()) {
                this.name = ep.title ?: "الحلقة ${idx + 1}"
                // Try to extract season and episode number from title
                val seasonPlaylist = playlists.find { it.id == ep.playlistId?.toString() }
                val seasonIndex = playlists.indexOf(seasonPlaylist)
                if (seasonIndex >= 0) {
                    this.season = seasonIndex + 1
                }
                // Extract episode number from Arabic title like "الحلقة 1"
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

        return newTvSeriesLoadResponse(displayTitle, url, TvType.TvSeries, tvEpisodes) {
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
        // Arabic pattern: "الحلقة 1" or "الحلقة 15"
        val arabicPattern = Regex("الحلقة\\s*(\\d+)")
        arabicPattern.find(title)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }

        // English pattern: "Episode 1" or "E15"
        val engPattern = Regex("(?:Episode|Ep\\.?)\\s*(\\d+)", RegexOption.IGNORE_CASE)
        engPattern.find(title)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }

        // Just a number
        val numPattern = Regex("^(\\d+)$")
        numPattern.find(title.trim())?.groupValues?.get(1)?.toIntOrNull()?.let { return it }

        return null
    }

    private suspend fun fetchCartoonInfo(cartoonId: String): CartoonWithInfo? {
        // Try most viewed list first
        val mostViewed = app.get(
            "$apiUrl/cartoon_with_info/getMostViewedCartoons.php",
            headers = authHeaders
        ).parsedSafe<List<CartoonWithInfo>>() ?: emptyList()

        mostViewed.find { it.id == cartoonId }?.let { return it }

        // Try page 1 of each listing
        val listings = listOf(
            "$apiUrl/cartoon_with_info/readPagingTranslatedSeriesAnime.php?page=1",
            "$apiUrl/cartoon_with_info/readPagingDUBBEDSeriesAnime.php?page=1",
            "$apiUrl/cartoon_with_info/readPagingTranslatedFilms.php?page=1",
            "$apiUrl/cartoon_with_info/readPagingDUBBEDFilms.php?page=1"
        )

        for (listingUrl in listings) {
            val items = app.get(listingUrl, headers = authHeaders).parsedSafe<List<CartoonWithInfo>>() ?: emptyList()
            items.find { it.id == cartoonId }?.let { return it }
        }

        // If still not found, create a minimal info from the cartoon ID
        // We'll still try to load playlists
        return CartoonWithInfo(id = cartoonId, title = "Anime #$cartoonId", type = "1")
    }

    private suspend fun fetchEpisodes(playlistId: String): List<Episode> {
        val allEpisodes = mutableListOf<Episode>()
        var page = 1

        while (true) {
            val eps = app.post(
                "$apiUrl/episode/readPaging.php?page=$page",
                headers = authHeaders + mapOf("Content-Type" to "application/x-www-form-urlencoded"),
                data = mapOf("playlist_id" to playlistId)
            ).parsedSafe<List<Episode>>() ?: emptyList()

            if (eps.isEmpty()) break
            allEpisodes.addAll(eps)

            // If we got less than 50, there are no more pages
            if (eps.size < 50) break
            page++
        }

        return allEpisodes
    }

    private fun collectVideoUrls(episode: Episode): Map<String, String> {
        val videos = mutableMapOf<String, String>()

        episode.video?.takeIf { it.isNotEmpty() }?.let {
            videos["سيرفر 1"] = it
        }
        episode.video1?.takeIf { it.isNotEmpty() }?.let {
            videos["سيرفر 2"] = it
        }
        episode.video2?.takeIf { it.isNotEmpty() }?.let {
            videos["سيرفر 3"] = it
        }
        episode.video3?.takeIf { it.isNotEmpty() }?.let {
            videos["سيرفر 4"] = it
        }
        episode.video4?.takeIf { it.isNotEmpty() }?.let {
            videos["سيرفر 5"] = it
        }
        episode.video5?.takeIf { it.isNotEmpty() }?.let {
            videos["سيرفر 6"] = it
        }

        return videos
    }

    // ==================== Video Extraction ====================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val videoData = tryParseJson<EpisodeVideoData>(data) ?: return false

        if (videoData.videos.isEmpty()) return false

        coroutineScope {
            videoData.videos.entries.map { (serverName, url) ->
                async {
                    try {
                        extractFromUrl(url, serverName, subtitleCallback, callback)
                    } catch (e: Exception) {
                        // Silently skip failed extractions
                    }
                }
            }.forEach { it.await() }
        }

        return true
    }

    private suspend fun extractFromUrl(
        url: String,
        serverName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        when {
            // test-stream worker - returns JSON with availableQualities
            url.contains("test-stream.developer-pro.workers.dev") -> {
                extractTestStream(url, serverName, callback)
            }
            // cdnlink worker - may return JSON with qualities or redirect
            url.contains("cdnlink.developer-pro.workers.dev") -> {
                extractWorkerLink(url, serverName, callback)
            }
            // link worker - may return JSON with qualities or redirect
            url.contains("link.developer-pro.workers.dev") -> {
                extractWorkerLink(url, serverName, callback)
            }
            // linkcdn worker
            url.contains("linkcdn.developer-pro.workers.dev") -> {
                extractWorkerLink(url, serverName, callback)
            }
            // multiplecdnqualities worker
            url.contains("multiplecdnqualities.apps-anime.workers.dev") -> {
                extractWorkerLink(url, serverName, callback)
            }
            // Google Photos
            url.contains("photos.google.com") -> {
                extractGooglePhotos(url, serverName, callback)
            }
            // ok.ru
            url.contains("ok.ru") -> {
                loadExtractor(url, mainUrl, subtitleCallback, callback)
            }
            // vudeo.io / vudeo.net
            url.contains("vudeo") -> {
                loadExtractor(url, mainUrl, subtitleCallback, callback)
            }
            // mixdrop
            url.contains("mixdrop") -> {
                loadExtractor(url, mainUrl, subtitleCallback, callback)
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
            // Unknown - try CloudStream's built-in extractor
            else -> {
                try {
                    loadExtractor(url, mainUrl, subtitleCallback, callback)
                } catch (e: Exception) {
                    // Fallback: try fetching as direct stream
                    tryDirectFetch(url, serverName, callback)
                }
            }
        }
    }

    private suspend fun extractTestStream(
        url: String,
        serverName: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val res = app.get(url, headers = authHeaders).parsedSafe<StreamResponse>()
            val qualities = res?.availableQualities ?: return

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
        } catch (e: Exception) {
            // Stream may be unavailable
        }
    }

    private suspend fun extractWorkerLink(
        url: String,
        serverName: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val response = app.get(url, headers = authHeaders)

            // Try parsing as JSON with availableQualities
            val streamRes = response.parsedSafe<StreamResponse>()
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

            // Try as redirect (follow and extract from destination)
            val finalUrl = response.url
            if (finalUrl != url) {
                extractFromUrl(finalUrl, serverName, callback = callback, subtitleCallback = {})
                return
            }

            // Try direct fetch
            tryDirectFetch(url, serverName, callback)
        } catch (e: Exception) {
            // Worker link may be expired
        }
    }

    private suspend fun extractGooglePhotos(
        url: String,
        serverName: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val doc = app.get(url, headers = headers).document

            // Extract video URLs from Google Photos page
            // Pattern: https://...googleusercontent.com/...(mp4|m3u8)
            val videoRegex = Regex("https?://[^\"'\\s]+?googleusercontent\\.com/[^\"'\\s]+?(?:mp4|m3u8)[^\"'\\s]*")
            val videoUrls = videoRegex.findAll(doc.toString()).map { it.value }.distinct().toList()

            videoUrls.forEach { videoUrl ->
                callback(
                    newExtractorLink(
                        source = "$serverName (Google Photos)",
                        name = "$serverName - Google Photos",
                        url = videoUrl,
                        type = INFER_TYPE
                    ) {
                        this.referer = "https://photos.google.com/"
                        this.quality = Qualities.Unknown.value
                    }
                )
            }

            // Also try extracting from script tags with video data
            if (videoUrls.isEmpty()) {
                val scripts = doc.select("script")
                for (script in scripts) {
                    val content = script.data()
                    if (content.contains("googleusercontent") && content.contains("video")) {
                        val urls = videoRegex.findAll(content).map { it.value }.distinct()
                        urls.forEach { videoUrl ->
                            callback(
                                newExtractorLink(
                                    source = "$serverName (Google Photos)",
                                    name = "$serverName - Google Photos",
                                    url = videoUrl,
                                    type = INFER_TYPE
                                ) {
                                    this.referer = "https://photos.google.com/"
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Google Photos may require auth or be unavailable
        }
    }

    private suspend fun tryDirectFetch(
        url: String,
        serverName: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val response = app.get(url, headers = authHeaders, allowRedirects = true)
            val finalUrl = response.url

            // Check if it's a direct video URL
            if (finalUrl.contains(".mp4") || finalUrl.contains(".m3u8")) {
                val quality = getQualityFromUrl(finalUrl)
                callback(
                    newExtractorLink(
                        source = serverName,
                        name = "$serverName - $quality",
                        url = finalUrl,
                        type = if (finalUrl.contains(".m3u8")) INFER_TYPE else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = mainUrl
                        this.headers = headers
                        this.quality = getQualityValue(quality)
                    }
                )
            }
        } catch (e: Exception) {
            // URL may not be a direct video
        }
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
