package com.stardima

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import com.fasterxml.jackson.annotation.JsonProperty
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

// ==================== STARDIMA API MODELS ====================

data class StardimaSearchResponse(
    @JsonProperty("videos") val videos: List<StardimaSearchVideo>?,
    @JsonProperty("pagination") val pagination: StardimaPagination?
)

data class StardimaSearchVideo(
    @JsonProperty("id") val id: Int?,
    @JsonProperty("title") val title: String?,
    @JsonProperty("url") val url: String?,
    @JsonProperty("poster_url") val posterUrl: String?,
    @JsonProperty("is_series") val isSeries: Boolean?,
    @JsonProperty("seasons_count") val seasonsCount: String?,
    @JsonProperty("video_quality") val videoQuality: String?,
    @JsonProperty("status_text") val statusText: String?,
    @JsonProperty("year") val year: String?
)

data class StardimaPagination(
    @JsonProperty("current_page") val currentPage: Int?,
    @JsonProperty("last_page") val lastPage: Int?
)

data class StardimaSeasonEpisodesResponse(
    @JsonProperty("episodes") val episodes: List<StardimaSeasonEpisode>?,
    @JsonProperty("series_id") val seriesId: String?
)

data class StardimaSeasonEpisode(
    @JsonProperty("id") val id: Int?,
    @JsonProperty("episode_number") val episodeNumber: Int?,
    @JsonProperty("title") val title: String?,
    @JsonProperty("watch_url") val watchUrl: String?
)

data class StardimaEpisodeDetailResponse(
    @JsonProperty("episode") val episode: StardimaEpisodeDetail?,
    @JsonProperty("series") val series: StardimaSeriesDetail?,
    @JsonProperty("season") val season: StardimaSeasonDetail?
)

data class StardimaEpisodeDetail(
    @JsonProperty("id") val id: Int?,
    @JsonProperty("episode_number") val episodeNumber: Int?,
    @JsonProperty("title") val title: String?,
    @JsonProperty("watch_url") val watchUrl: String?,
    @JsonProperty("can_watch") val canWatch: Boolean?
)

data class StardimaSeriesDetail(
    @JsonProperty("slug") val slug: String?,
    @JsonProperty("title") val title: String?,
    @JsonProperty("poster_url") val posterUrl: String?
)

data class StardimaSeasonDetail(
    @JsonProperty("number") val number: Int?
)

// ==================== HYPERWATCHING API MODELS ====================

data class HyperwatchingLinkResponse(
    @JsonProperty("success") val success: Boolean?,
    @JsonProperty("status") val status: String?,
    @JsonProperty("watch_url") val watchUrl: String?,
    @JsonProperty("server_name") val serverName: String?
)

data class HyperwatchingTokenResponse(
    @JsonProperty("token") val token: String?
)

data class HyperwatchingExtractResponse(
    @JsonProperty("success") val success: Boolean?,
    @JsonProperty("data") val data: HyperwatchingExtractData?
)

data class HyperwatchingExtractData(
    @JsonProperty("file") val file: String?,
    @JsonProperty("headers") val headers: Map<String, String>?
)

// ==================== PROVIDER ====================

class StardimaProvider : MainAPI() {
    override var mainUrl = "https://www.stardima.com"
    override var name = "ستارديما"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
    override var lang = "ar"
    override val hasMainPage = true

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
    )

    private val apiHeaders = headers + mapOf(
        "X-Requested-With" to "XMLHttpRequest",
        "Accept" to "application/json"
    )

    // ==================== HOMEPAGE ====================

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(mainUrl, headers = headers).document
        val sections = mutableListOf<HomePageList>()

        val sectionHeadings = doc.select("h2.text-white")

        for (heading in sectionHeadings) {
            val sectionTitle = heading.text().trim()
            if (sectionTitle.isBlank()) continue

            val emblaContainer = heading.parent()?.parent()?.selectFirst("div.embla")
                ?: heading.parent()?.parent()?.nextElementSiblings()?.select("div.embla")?.first()
                ?: continue

            val items = emblaContainer.select("div.embla__slide").mapNotNull { slide ->
                slide.toSearchResponse()
            }

            if (items.isNotEmpty()) {
                sections.add(HomePageList(sectionTitle, items))
            }
        }

        if (sections.isEmpty()) {
            doc.select("div.embla").forEach { carousel ->
                val title = carousel.parent()?.selectFirst("h2")?.text()?.trim() ?: "الرئيسية"
                val items = carousel.select("div.embla__slide").mapNotNull { slide ->
                    slide.toSearchResponse()
                }
                if (items.isNotEmpty()) {
                    sections.add(HomePageList(title, items))
                }
            }
        }

        return newHomePageResponse(sections)
    }

    // ==================== SEARCH (JSON API) ====================

    override suspend fun search(query: String): List<SearchResponse> {
        val response = app.get(
            "$mainUrl/search?query=${java.net.URLEncoder.encode(query, "UTF-8")}",
            headers = apiHeaders
        )

        val searchResult = response.parsedSafe<StardimaSearchResponse>() ?: return emptyList()

        return searchResult.videos?.mapNotNull { video ->
            val url = video.url ?: return@mapNotNull null
            val fullUrl = if (url.startsWith("http")) url else "$mainUrl$url"
            val title = video.title ?: return@mapNotNull null

            val posterUrl = video.posterUrl?.let { poster ->
                if (poster.startsWith("http")) poster else "$mainUrl/storage/$poster"
            }

            if (video.isSeries == true) {
                newTvSeriesSearchResponse(title, fullUrl) {
                    this.posterUrl = posterUrl
                }
            } else {
                newMovieSearchResponse(title, fullUrl) {
                    this.posterUrl = posterUrl
                }
            }
        } ?: emptyList()
    }

    // ==================== LOAD ====================

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, headers = headers).document

        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: doc.selectFirst("h1")?.text()?.trim()
            ?: return null

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim()

        val description = doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
            ?: doc.selectFirst("meta[name=description]")?.attr("content")?.trim()

        val tags = doc.select("meta[name=keywords]")?.attr("content")?.split(",")?.map { it.trim() }
            ?: emptyList()

        val isTvSeries = url.contains("/tvshow/")

        return if (isTvSeries) {
            loadTvSeries(url, doc, title, poster, description, tags)
        } else {
            loadMovie(url, doc, title, poster, description, tags)
        }
    }

    private suspend fun loadMovie(
        url: String,
        doc: org.jsoup.nodes.Document,
        title: String,
        poster: String?,
        description: String?,
        tags: List<String>?
    ): LoadResponse {
        val playUrl = doc.selectFirst("a[href*=/play/]")?.attr("href")
            ?.let { if (it.startsWith("http")) it else "$mainUrl$it" }
            ?: url

        return newMovieLoadResponse(title, url, TvType.Movie, playUrl) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
        }
    }

    private suspend fun loadTvSeries(
        url: String,
        doc: org.jsoup.nodes.Document,
        title: String,
        poster: String?,
        description: String?,
        tags: List<String>?
    ): LoadResponse {
        val episodes = mutableListOf<Episode>()
        val seriesSlug = url.trimEnd('/').substringAfterLast("/")

        val firstPlayHref = doc.selectFirst("a[href*='/play/']")?.attr("href")
            ?.let { if (it.startsWith("http")) it else "$mainUrl$it" }

        if (firstPlayHref == null) {
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
            }
        }

        val playDoc = app.get(firstPlayHref, headers = headers).document

        val seasonItems = playDoc.select(".season-item")

        if (seasonItems.isNotEmpty()) {
            for (seasonEl in seasonItems) {
                val seasonId = seasonEl.attr("data-season-id")
                val seasonNumberStr = seasonEl.attr("data-season-number")
                val seasonNumber = Regex("""(\d+)""").find(seasonNumberStr)?.groupValues?.get(1)?.toIntOrNull() ?: 1

                if (seasonId.isNotBlank()) {
                    try {
                        val apiResponse = app.get(
                            "$mainUrl/series/season/$seasonId",
                            headers = apiHeaders
                        )
                        val seasonData = apiResponse.parsedSafe<StardimaSeasonEpisodesResponse>()

                        seasonData?.episodes?.forEach { ep ->
                            val epId = ep.id ?: return@forEach
                            val epTitle = ep.title ?: "حلقة ${ep.episodeNumber ?: epId}"
                            val slug = seasonData.seriesId ?: seriesSlug

                            // Encode both watch_url and play URL in episode data
                            // Format: watch_url|play_url  (if watch_url exists)
                            // Or just: play_url (if no watch_url)
                            val playUrl = "$mainUrl/tvshow/$slug/play/$epId"
                            val epData = if (!ep.watchUrl.isNullOrBlank()) {
                                "${ep.watchUrl}|$playUrl"
                            } else {
                                playUrl
                            }

                            episodes.add(
                                newEpisode(epData) {
                                    name = epTitle
                                    season = seasonNumber
                                    episode = ep.episodeNumber ?: 0
                                }
                            )
                        }
                    } catch (_: Exception) {}
                }
            }
        }

        // Fallback: parse episode list items from play page HTML
        if (episodes.isEmpty()) {
            val episodeItems = playDoc.select(".episode-list-item")
            for (epEl in episodeItems) {
                val epId = epEl.attr("data-episode-id")
                val epLink = epEl.selectFirst("a[data-episode-id]")
                val epTitle = epLink?.text()?.trim() ?: "حلقة $epId"

                if (epId.isNotBlank()) {
                    val playUrl = "$mainUrl/tvshow/$seriesSlug/play/$epId"
                    episodes.add(
                        newEpisode(playUrl) {
                            name = epTitle
                            season = 1
                        }
                    )
                }
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
        }
    }

    // ==================== LOAD LINKS ====================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var foundAny = false

        // Parse episode data - could be "watch_url|play_url" or just a URL
        val parts = data.split("|")
        val watchUrl = if (parts.size == 2) parts[0] else null
        val playUrl = if (parts.size == 2) parts[1] else data

        val isTvEpisode = playUrl.contains("/tvshow/") && playUrl.contains("/play/")

        // Strategy 1: If we have a direct watch_url, try extracting from it
        if (!watchUrl.isNullOrBlank()) {
            foundAny = tryExtractFromUrl(watchUrl, subtitleCallback, callback) || foundAny
        }

        // Strategy 2: For TV episodes, call the stardima episode API
        if (isTvEpisode && !foundAny) {
            val episodeId = playUrl.trimEnd('/').substringAfterLast("/")
            if (episodeId.all { it.isDigit() }) {
                try {
                    val apiResponse = app.get(
                        "$mainUrl/series/episode/$episodeId",
                        headers = apiHeaders
                    )
                    val epData = apiResponse.parsedSafe<StardimaEpisodeDetailResponse>()
                    val apiWatchUrl = epData?.episode?.watchUrl
                    if (!apiWatchUrl.isNullOrBlank()) {
                        foundAny = tryExtractFromUrl(apiWatchUrl, subtitleCallback, callback) || foundAny
                    }
                } catch (_: Exception) {}
            }
        }

        // Strategy 3: Scrape the play page for iframes
        if (!foundAny) {
            try {
                val doc = app.get(playUrl, headers = headers).document

                // Check main video player iframe
                val iframeUrl = doc.selectFirst("#video-player-container iframe")?.attr("src")
                    ?.let { if (it.startsWith("http")) it else "$mainUrl$it" }

                if (!iframeUrl.isNullOrBlank()) {
                    foundAny = tryExtractFromUrl(iframeUrl, subtitleCallback, callback) || foundAny
                }

                // Check all other iframes
                doc.select("iframe[src]").forEach { iframe ->
                    val src = iframe.attr("src")
                    if (src.isNotBlank() && src.startsWith("http") && !src.contains("about:blank")) {
                        foundAny = tryExtractFromUrl(src, subtitleCallback, callback) || foundAny
                    }
                }
            } catch (_: Exception) {}
        }

        // Strategy 4: For movies, try the movie play page
        if (!isTvEpisode && !foundAny) {
            try {
                val doc = app.get(playUrl, headers = headers).document
                doc.select("iframe[src]").forEach { iframe ->
                    val src = iframe.attr("src")
                    if (src.isNotBlank() && src.startsWith("http") && !src.contains("about:blank")) {
                        foundAny = tryExtractFromUrl(src, subtitleCallback, callback) || foundAny
                    }
                }
            } catch (_: Exception) {}
        }

        return foundAny
    }

    /**
     * Try all extraction methods for a given URL:
     * 1. If it's a hyperwatching URL, use our custom extractor
     * 2. Try CloudStream's built-in extractors (loadExtractor)
     */
    private suspend fun tryExtractFromUrl(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false

        // If it's a hyperwatching URL, use our custom extractor
        if (url.contains("hyperwatching.com")) {
            found = extractFromHyperwatching(url, subtitleCallback, callback) || found
        }

        // Always also try CloudStream's built-in extractors
        // This covers uqload, streamhg, darkibox, lulustream, etc.
        try {
            loadExtractor(url, url, subtitleCallback, callback)
            found = true
        } catch (_: Exception) {}

        return found
    }

    // ==================== HYPERWATCHING EXTRACTOR ====================

    private suspend fun extractFromHyperwatching(
        iframeUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val pageResponse = app.get(iframeUrl, headers = headers)
            val html = pageResponse.text

            // Parse CSRF token
            val csrf = Regex("""csrf:\s*['"]([^'"]+)['"]""").find(html)?.groupValues?.get(1) ?: return false

            // Parse server IDs and names - try multiple patterns
            val servers = mutableListOf<Pair<String, String>>()

            // Pattern 1: {id: "123", name: "Uqload"}
            val serverPattern1 = Regex("""\{id:\s*['"](\d+)['"],\s*name:\s*['"]([^'"]+)['"]""")
            serverPattern1.findAll(html).forEach {
                servers.add(it.groupValues[1] to it.groupValues[2])
            }

            // Pattern 2: just id: "123"
            if (servers.isEmpty()) {
                val serverPattern2 = Regex("""id:\s*['"](\d+)['"]""")
                serverPattern2.findAll(html).forEach { match ->
                    // Try to find the name near this id
                    val nearbyName = Regex("""name:\s*['"]([^'"]+)['"]""").find(
                        html.substring(
                            maxOf(0, match.range.first - 100),
                            minOf(html.length, match.range.last + 100)
                        )
                    )?.groupValues?.get(1) ?: "Server"
                    servers.add(match.groupValues[1] to nearbyName)
                }
            }

            if (servers.isEmpty()) return false

            // Parse API routes
            val linkRoute = Regex("""link:\s*['"]([^'"]+)['"]""").find(html)?.groupValues?.get(1) ?: return false
            val tokenRoute = Regex("""token:\s*['"]([^'"]+)['"]""").find(html)?.groupValues?.get(1)
            val extractRoute = Regex("""extract:\s*['"]([^'"]+)['"]""").find(html)?.groupValues?.get(1)

            val hyperApiHeaders = mapOf(
                "X-CSRF-TOKEN" to csrf,
                "Content-Type" to "application/x-www-form-urlencoded",
                "Accept" to "application/json",
                "Referer" to iframeUrl,
                "User-Agent" to headers["User-Agent"]!!
            )

            var foundAny = false

            for ((serverId, serverName) in servers) {
                try {
                    // Get the third-party embed URL from this server
                    val linkResponse = app.post(
                        linkRoute,
                        headers = hyperApiHeaders,
                        data = mapOf("server_link_id" to serverId)
                    )
                    val linkData = linkResponse.parsedSafe<HyperwatchingLinkResponse>()

                    if (linkData?.watchUrl.isNullOrBlank()) continue
                    val watchUrl = linkData!!.watchUrl!!

                    // Try secure extract chain for direct m3u8/mp4 URL
                    if (!tokenRoute.isNullOrBlank() && !extractRoute.isNullOrBlank()) {
                        try {
                            val tokenResponse = app.post(
                                tokenRoute,
                                headers = hyperApiHeaders,
                                data = mapOf("url" to watchUrl)
                            )
                            val tokenData = tokenResponse.parsedSafe<HyperwatchingTokenResponse>()
                            val token = tokenData?.token

                            if (!token.isNullOrBlank()) {
                                val extractResponse = app.post(
                                    extractRoute,
                                    headers = hyperApiHeaders,
                                    data = mapOf("token" to token)
                                )
                                val extractData = extractResponse.parsedSafe<HyperwatchingExtractResponse>()

                                if (extractData?.success == true && !extractData.data?.file.isNullOrBlank()) {
                                    val directUrl = extractData.data!!.file!!
                                    val extraHeaders = extractData.data.headers ?: emptyMap()

                                    callback.invoke(
                                        newExtractorLink(
                                            source = serverName,
                                            name = "$name - $serverName",
                                            url = directUrl,
                                            type = INFER_TYPE
                                        ) {
                                            this.referer = extraHeaders["Referer"] ?: iframeUrl
                                            this.quality = Qualities.Unknown.value
                                        }
                                    )
                                    foundAny = true
                                    continue // This server worked, try next server too
                                }
                            }
                        } catch (_: Exception) {}
                    }

                    // Fallback: try loadExtractor on the third-party embed URL
                    // CloudStream has extractors for uqload, streamhg, etc.
                    try {
                        loadExtractor(watchUrl, iframeUrl, subtitleCallback, callback)
                        foundAny = true
                    } catch (_: Exception) {}

                } catch (_: Exception) {}
            }

            return foundAny

        } catch (_: Exception) {
            return false
        }
    }

    // ==================== HELPERS ====================

    private fun Element.toSearchResponse(): SearchResponse? {
        val linkEl = selectFirst("a[href*=/movie/], a[href*=/tvshow/]")
        val href = linkEl?.attr("href") ?: return null
        val fullUrl = if (href.startsWith("http")) href else "$mainUrl$href"

        val title = linkEl.selectFirst("h3")?.text()?.trim()
            ?: selectFirst("h4 a")?.text()?.trim()
            ?: selectFirst("h4")?.text()?.trim()
            ?: linkEl.attr("title").ifBlank { null }
            ?: return null

        val posterUrl = selectFirst("img")?.let { img ->
            img.attr("data-src").ifBlank { img.attr("src") }
        }?.let { if (it.startsWith("http")) it else "$mainUrl$it" }

        val isTvSeries = fullUrl.contains("/tvshow/")

        return if (isTvSeries) {
            newTvSeriesSearchResponse(title, fullUrl) {
                this.posterUrl = posterUrl
            }
        } else {
            newMovieSearchResponse(title, fullUrl) {
                this.posterUrl = posterUrl
            }
        }
    }
}