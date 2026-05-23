package com.stardima

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import com.fasterxml.jackson.annotation.JsonProperty
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

data class StardimaSeasonEpisodesResponse(
    @JsonProperty("episodes") val episodes: List<StardimaEpisode>?,
    @JsonProperty("series_id") val seriesId: String?
)

data class StardimaEpisode(
    @JsonProperty("id") val id: Int?,
    @JsonProperty("title") val title: String?,
    @JsonProperty("is_exclusive") val isExclusive: Boolean?
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
    @JsonProperty("id") val id: Int?,
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

    // ==================== SEARCH ====================

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=$query", headers = headers).document

        return doc.select("div.embla__slide").mapNotNull { slide ->
            slide.toSearchResponse()
        }.ifEmpty {
            doc.select("div.group/item, a[href*=/movie/], a[href*=/tvshow/]").mapNotNull {
                it.toSearchResponseFromLink()
            }
        }
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

    // FIX 1: Added "suspend" keyword
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

        // Strategy 1: Parse season items from the page HTML
        val seasonItems = doc.select(".season-item")

        if (seasonItems.isNotEmpty()) {
            for (seasonEl in seasonItems) {
                val seasonId = seasonEl.attr("data-season-id")
                val seasonNumberStr = seasonEl.attr("data-season-number")
                val seasonNumber = seasonNumberStr.filter { it.isDigit() }.toIntOrNull() ?: 1

                if (seasonId.isNotBlank()) {
                    try {
                        val apiHeaders = headers + mapOf(
                            "X-Requested-With" to "XMLHttpRequest",
                            "Accept" to "application/json"
                        )
                        val apiResponse = app.get(
                            "$mainUrl/series/season/$seasonId",
                            headers = apiHeaders
                        )
                        val seasonData = apiResponse.parsedSafe<StardimaSeasonEpisodesResponse>()

                        if (seasonData?.episodes != null) {
                            for (ep in seasonData.episodes) {
                                val epId = ep.id ?: continue
                                val epTitle = ep.title ?: "حلقة $epId"
                                val slug = seasonData.seriesId ?: seriesSlug
                                val playUrl = "$mainUrl/tvshow/$slug/play/$epId"

                                episodes.add(
                                    newEpisode(playUrl) {
                                        name = epTitle
                                        season = seasonNumber
                                    }
                                )
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
        }

        // Strategy 2: Parse episode list items directly from the HTML
        if (episodes.isEmpty()) {
            val episodeItems = doc.select("li.episode-list-item")
            for (epEl in episodeItems) {
                val epId = epEl.attr("data-episode-id")
                val epTitle = epEl.selectFirst("a")?.text()?.trim() ?: "حلقة $epId"

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

        // Strategy 3: Load the play page to find season/episode data
        if (episodes.isEmpty()) {
            val firstPlayUrl = doc.selectFirst("meta[property=og:video]")?.attr("content")
                ?: doc.selectFirst("a[href*=/play/]")?.attr("href")?.let {
                    if (it.startsWith("http")) it else "$mainUrl$it"
                }

            if (!firstPlayUrl.isNullOrBlank()) {
                try {
                    val playDoc = app.get(firstPlayUrl, headers = headers).document

                    val playSeasonItems = playDoc.select(".season-item")
                    for (seasonEl in playSeasonItems) {
                        val seasonId = seasonEl.attr("data-season-id")
                        val seasonNumberStr = seasonEl.attr("data-season-number")
                        val seasonNumber = seasonNumberStr.filter { it.isDigit() }.toIntOrNull() ?: 1

                        if (seasonId.isNotBlank()) {
                            try {
                                val apiHeaders = headers + mapOf(
                                    "X-Requested-With" to "XMLHttpRequest",
                                    "Accept" to "application/json"
                                )
                                val apiResponse = app.get(
                                    "$mainUrl/series/season/$seasonId",
                                    headers = apiHeaders
                                )
                                val seasonData = apiResponse.parsedSafe<StardimaSeasonEpisodesResponse>()

                                if (seasonData?.episodes != null) {
                                    for (ep in seasonData.episodes) {
                                        val epId = ep.id ?: continue
                                        val epTitle = ep.title ?: "حلقة $epId"
                                        // FIX 2: Use seriesSlug instead of undefined "slug"
                                        val slug = seasonData.seriesId ?: seriesSlug
                                        val playUrl = "$mainUrl/tvshow/$slug/play/$epId"

                                        episodes.add(
                                            newEpisode(playUrl) {
                                                name = epTitle
                                                season = seasonNumber
                                            }
                                        )
                                    }
                                }
                            } catch (_: Exception) {}
                        }
                    }

                    if (episodes.isEmpty()) {
                        val playEpItems = playDoc.select("li.episode-list-item")
                        for (epEl in playEpItems) {
                            val epId = epEl.attr("data-episode-id")
                            val epTitle = epEl.selectFirst("a")?.text()?.trim() ?: "حلقة $epId"
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
                } catch (_: Exception) {}
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
        val doc = app.get(data, headers = headers).document

        // Strategy 1: Look for iframe in the video player container
        val iframeUrl = doc.selectFirst("#video-player-container iframe")?.attr("src")
            ?.let { if (it.startsWith("http")) it else "$mainUrl$it" }

        if (!iframeUrl.isNullOrBlank()) {
            loadExtractor(iframeUrl, data, subtitleCallback, callback)
        }

        // Strategy 2: Try the episode API to get watch_url
        val episodeId = data.trimEnd('/').substringAfterLast("/")
        if (episodeId.all { it.isDigit() }) {
            try {
                val apiHeaders = headers + mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Accept" to "application/json"
                )
                val apiResponse = app.get(
                    "$mainUrl/series/episode/$episodeId",
                    headers = apiHeaders
                )
                val epData = apiResponse.parsedSafe<StardimaEpisodeDetailResponse>()
                val watchUrl = epData?.episode?.watchUrl
                if (!watchUrl.isNullOrBlank()) {
                    loadExtractor(watchUrl, data, subtitleCallback, callback)
                }
            } catch (_: Exception) {}
        }

        // Strategy 3: Search for any other iframes in the page
        coroutineScope {
            doc.select("iframe[src]").map { iframe ->
                async {
                    val src = iframe.attr("src")
                    if (src.isNotBlank() && src.startsWith("http") &&
                        !src.contains("about:blank") &&
                        iframe.attr("style")?.contains("display: none") != true &&
                        iframe.attr("width") != "1"
                    ) {
                        loadExtractor(src, data, subtitleCallback, callback)
                    }
                }
            }.awaitAll()
        }

        // Strategy 4: Check for JSON-LD embedUrl
        doc.select("script[type=application/ld+json]").forEach { script ->
            try {
                val jsonStr = script.html()
                val embedUrl = Regex("""embedUrl"\s*:\s*"([^"]+)"""").find(jsonStr)?.groupValues?.get(1)
                if (!embedUrl.isNullOrBlank() && embedUrl.startsWith("http")) {
                    loadExtractor(embedUrl, data, subtitleCallback, callback)
                }
            } catch (_: Exception) {}
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

    private fun Element.toSearchResponseFromLink(): SearchResponse? {
        val href = attr("href")
        if (!href.contains("/movie/") && !href.contains("/tvshow/")) return null
        val fullUrl = if (href.startsWith("http")) href else "$mainUrl$href"

        val title = attr("title").ifBlank { text().trim() }.ifBlank { return null }
        val isTvSeries = fullUrl.contains("/tvshow/")

        return if (isTvSeries) {
            newTvSeriesSearchResponse(title, fullUrl) { this.posterUrl = null }
        } else {
            newMovieSearchResponse(title, fullUrl) { this.posterUrl = null }
        }
    }
}