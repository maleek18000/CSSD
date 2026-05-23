package com.stardima

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import com.fasterxml.jackson.annotation.JsonProperty
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

// ==================== JSON API MODELS ====================

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

        // Each section: h2 heading + embla carousel in the same parent container
        val sectionHeadings = doc.select("h2.text-white")

        for (heading in sectionHeadings) {
            val sectionTitle = heading.text().trim()
            if (sectionTitle.isBlank()) continue

            // The embla carousel is a sibling after the heading's parent
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

        // Fallback: try finding all embla carousels
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

            // Poster URL can be relative or absolute
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
        // Movie play URL is at /play/{slug}
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

        // Step 1: Find the first play link to get the initial episode ID
        val firstPlayHref = doc.selectFirst("a[href*='/play/']")?.attr("href")
            ?.let { if (it.startsWith("http")) it else "$mainUrl$it" }

        if (firstPlayHref == null) {
            // No play link found, return with empty episodes
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
            }
        }

        // Step 2: Fetch the play page to get season data
        val playDoc = app.get(firstPlayHref, headers = headers).document

        // Step 3: Find all season items on the play page
        val seasonItems = playDoc.select(".season-item")

        if (seasonItems.isNotEmpty()) {
            // Fetch episodes for each season via the API
            for (seasonEl in seasonItems) {
                val seasonId = seasonEl.attr("data-season-id")
                val seasonNumberStr = seasonEl.attr("data-season-number")
                // Extract number from "Season 1", "one piece S01", etc.
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
                            val playUrl = "$mainUrl/tvshow/$slug/play/$epId"

                            episodes.add(
                                newEpisode(playUrl) {
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
        // For TV show episodes: data is /tvshow/{slug}/play/{episodeId}
        // For movies: data is /play/{slug}

        val isTvEpisode = data.contains("/tvshow/") && data.contains("/play/")

        if (isTvEpisode) {
            // Extract episode ID from URL
            val episodeId = data.trimEnd('/').substringAfterLast("/")
            if (episodeId.all { it.isDigit() }) {
                // Use the episode API to get watch_url directly
                try {
                    val apiResponse = app.get(
                        "$mainUrl/series/episode/$episodeId",
                        headers = apiHeaders
                    )
                    val epData = apiResponse.parsedSafe<StardimaEpisodeDetailResponse>()
                    val watchUrl = epData?.episode?.watchUrl
                    if (!watchUrl.isNullOrBlank()) {
                        loadExtractor(watchUrl, data, subtitleCallback, callback)
                        return true
                    }
                } catch (_: Exception) {}
            }

            // Fallback: try scraping the play page for iframe
            val doc = app.get(data, headers = headers).document
            val iframeUrl = doc.selectFirst("#video-player-container iframe")?.attr("src")
                ?.let { if (it.startsWith("http")) it else "$mainUrl$it" }

            if (!iframeUrl.isNullOrBlank()) {
                loadExtractor(iframeUrl, data, subtitleCallback, callback)
                return true
            }
        } else {
            // Movie: /play/{slug} — the iframe src is directly in the HTML
            val doc = app.get(data, headers = headers).document
            val iframeUrl = doc.selectFirst("#video-player-container iframe")?.attr("src")
                ?.let { if (it.startsWith("http")) it else "$mainUrl$it" }

            if (!iframeUrl.isNullOrBlank()) {
                loadExtractor(iframeUrl, data, subtitleCallback, callback)
                return true
            }

            // Fallback: search all iframes
            doc.select("iframe[src]").forEach { iframe ->
                val src = iframe.attr("src")
                if (src.isNotBlank() && src.startsWith("http") && !src.contains("about:blank")) {
                    loadExtractor(src, data, subtitleCallback, callback)
                }
            }
        }

        return true
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