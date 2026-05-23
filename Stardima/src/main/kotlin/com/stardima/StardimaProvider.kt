package com.stardima

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import com.fasterxml.jackson.annotation.JsonProperty
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

data class SeasonEpisodesResponse(
    @JsonProperty("status") val status: Boolean?,
    @JsonProperty("html") val html: String?
)

data class EpisodeData(
    @JsonProperty("id") val id: Int?,
    @JsonProperty("title") val title: String?,
    @JsonProperty("watch_url") val watchUrl: String?
)

data class EpisodeDetailResponse(
    @JsonProperty("status") val status: Boolean?,
    @JsonProperty("data") val data: EpisodeData?
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

        // Parse homepage sections from Embla carousels
        doc.select("div.embla").forEach { carousel ->
            val sectionTitle = carousel.selectFirst("h2, h3, .section-title, .embla__title")
                ?.text()?.trim() ?: return@forEach

            val items = carousel.select("div.embla__slide").mapNotNull { slide ->
                slide.toSearchResponse()
            }

            if (items.isNotEmpty()) {
                sections.add(HomePageList(sectionTitle, items))
            }
        }

        // If no Embla sections found, try generic card containers
        if (sections.isEmpty()) {
            val allCards = doc.select("div.card, article.post, div.post-item, div.movie-card, div.series-card").mapNotNull {
                it.toSearchResponse()
            }
            if (allCards.isNotEmpty()) {
                sections.add(HomePageList("الرئيسية", allCards))
            }
        }

        return newHomePageResponse(sections)
    }

    // ==================== SEARCH ====================

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=$query", headers = headers).document

        return doc.select("div.embla__slide, div.card, article.post, div.post-item, div.movie-card, div.series-card, div.search-result")
            .mapNotNull { it.toSearchResponse() }
    }

    // ==================== LOAD ====================

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, headers = headers).document

        val title = doc.selectFirst("h1, .post-title, .entry-title, .hero-title")
            ?.text()?.trim() ?: return null

        val poster = doc.selectFirst(".post-thumbnail img, .hero-image img, .poster img, meta[property=og:image]")
            ?.let { img ->
                img.attr("data-src").ifBlank { img.attr("src") }.ifBlank { img.attr("content") }
            }

        val description = doc.selectFirst(".post-content, .entry-content, .description, .synopsis, meta[property=og:description]")
            ?.let { el ->
                el.text()?.trim()?.ifBlank { el.attr("content") }
            }

        val year = doc.selectFirst(".year, .release-year, meta[property=og:release_date]")
            ?.text()?.trim()?.toIntOrNull()

        val tags = doc.select(".genres a, .category a, .tags a").map { it.text().trim() }

        val isTvSeries = url.contains("/tvshow/") || url.contains("/series/")

        return if (isTvSeries) {
            loadTvSeries(url, doc, title, poster, description, year, tags)
        } else {
            loadMovie(url, doc, title, poster, description, year, tags)
        }
    }

    private suspend fun loadMovie(
        url: String,
        doc: org.jsoup.nodes.Document,
        title: String,
        poster: String?,
        description: String?,
        year: Int?,
        tags: List<String>
    ): LoadResponse {
        // Find the play link for the movie
        val playUrl = doc.selectFirst("a[href*=/play/]")
            ?.attr("href")
            ?.let { if (it.startsWith("http")) it else "$mainUrl$it" }
            ?: url

        return newMovieLoadResponse(title, url, TvType.Movie, playUrl) {
            this.posterUrl = poster
            this.plot = description
            this.year = year
            this.tags = tags
        }
    }

    private suspend fun loadTvSeries(
        url: String,
        doc: org.jsoup.nodes.Document,
        title: String,
        poster: String?,
        description: String?,
        year: Int?,
        tags: List<String>
    ): LoadResponse {
        val episodes = mutableListOf<Episode>()

        // Strategy 1: Parse seasons from the page HTML
        val seasonElements = doc.select("div.season, div#seasons li, ul.seasons-list li, [data-season]")

        if (seasonElements.isNotEmpty()) {
            seasonElements.forEachIndexed { seasonIndex, seasonEl ->
                val seasonName = seasonEl.text().trim()
                val seasonNumber = seasonName.filter { it.isDigit() }.toIntOrNull() ?: (seasonIndex + 1)

                // Try to get season data-id for the API call
                val seasonDataId = seasonEl.attr("data-id")
                    .ifBlank { seasonEl.selectFirst("a")?.attr("data-id") }
                    ?.ifBlank { seasonEl.selectFirst("a")?.attr("href")?.substringAfterLast("/") }

                if (!seasonDataId.isNullOrBlank()) {
                    try {
                        val apiHeaders = headers + mapOf(
                            "X-Requested-With" to "XMLHttpRequest",
                            "Accept" to "application/json, text/html, */*"
                        )
                        val apiResponse = app.get(
                            "$mainUrl/series/season/$seasonDataId",
                            headers = apiHeaders
                        )

                        // Try parsing as JSON first
                        try {
                            val seasonData = apiResponse.parsedSafe<SeasonEpisodesResponse>()
                            if (seasonData?.html != null) {
                                val epDoc = org.jsoup.Jsoup.parse(seasonData.html)
                                epDoc.select("a, li, div.episode").forEach { epEl ->
                                    val epTitle = epEl.text().trim()
                                    val epHref = epEl.attr("href")
                                        .ifBlank { epEl.selectFirst("a")?.attr("href") ?: "" }

                                    if (epHref.isNotBlank()) {
                                        val fullEpUrl = if (epHref.startsWith("http")) epHref else "$mainUrl$epHref"
                                        episodes.add(
                                            newEpisode(fullEpUrl) {
                                                name = epTitle
                                                season = seasonNumber
                                            }
                                        )
                                    }
                                }
                            }
                        } catch (_: Exception) {}

                        // Try parsing the response body directly as HTML
                        val epDoc2 = org.jsoup.Jsoup.parse(apiResponse.text)
                        epDoc2.select("a, li, div.episode").forEach { epEl ->
                            val epTitle = epEl.text().trim()
                            val epHref = epEl.attr("href")
                                .ifBlank { epEl.selectFirst("a")?.attr("href") ?: "" }

                            if (epHref.isNotBlank()) {
                                val fullEpUrl = if (epHref.startsWith("http")) epHref else "$mainUrl$epHref"
                                // Avoid duplicates
                                if (episodes.none { it.data == fullEpUrl }) {
                                    episodes.add(
                                        newEpisode(fullEpUrl) {
                                            name = epTitle
                                            season = seasonNumber
                                        }
                                    )
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }

                // Fallback: parse episodes directly from the season element on the page
                seasonEl.select("a[href*=/play/], a[href*=/episode/], li.episode a").forEach { epLink ->
                    val epTitle = epLink.text().trim()
                    val epHref = epLink.attr("href")
                    if (epHref.isNotBlank()) {
                        val fullEpUrl = if (epHref.startsWith("http")) epHref else "$mainUrl$epHref"
                        if (episodes.none { it.data == fullEpUrl }) {
                            episodes.add(
                                newEpisode(fullEpUrl) {
                                    name = epTitle
                                    season = seasonNumber
                                }
                            )
                        }
                    }
                }
            }
        }

        // Strategy 2: If no season elements found, try direct episode links on the page
        if (episodes.isEmpty()) {
            doc.select("a[href*=/play/], a[href*=/episode/]").forEach { epLink ->
                val epTitle = epLink.text().trim()
                val epHref = epLink.attr("href")
                if (epHref.isNotBlank()) {
                    val fullEpUrl = if (epHref.startsWith("http")) epHref else "$mainUrl$epHref"
                    episodes.add(
                        newEpisode(fullEpUrl) {
                            name = epTitle
                            season = 1
                        }
                    )
                }
            }
        }

        // Strategy 3: Try the episode API directly
        if (episodes.isEmpty()) {
            val seriesId = url.trimEnd('/').substringAfterLast("/")
            try {
                val apiHeaders = headers + mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Accept" to "application/json, text/html, */*"
                )
                val apiResponse = app.get(
                    "$mainUrl/series/season/$seriesId",
                    headers = apiHeaders
                )
                val epDoc = org.jsoup.Jsoup.parse(apiResponse.text)
                epDoc.select("a, li, div.episode").forEach { epEl ->
                    val epTitle = epEl.text().trim()
                    val epHref = epEl.attr("href")
                    if (epHref.isNotBlank()) {
                        val fullEpUrl = if (epHref.startsWith("http")) epHref else "$mainUrl$epHref"
                        episodes.add(
                            newEpisode(fullEpUrl) {
                                name = epTitle
                                season = 1
                            }
                        )
                    }
                }
            } catch (_: Exception) {}
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = description
            this.year = year
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
        val iframeUrl = doc.selectFirst("#video-player-container iframe, .video-container iframe, .player iframe")
            ?.attr("src")
            ?.let { if (it.startsWith("http")) it else "$mainUrl$it" }

        if (!iframeUrl.isNullOrBlank()) {
            loadExtractor(iframeUrl, data, subtitleCallback, callback)
        }

        // Strategy 2: Try the episode API to get watch_url
        if (data.contains("/play/") || data.contains("/episode/")) {
            val episodeId = data.trimEnd('/').substringAfterLast("/")
            try {
                val apiHeaders = headers + mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Accept" to "application/json"
                )
                val apiResponse = app.get(
                    "$mainUrl/series/episode/$episodeId",
                    headers = apiHeaders
                )
                val epData = apiResponse.parsedSafe<EpisodeDetailResponse>()
                val watchUrl = epData?.data?.watchUrl
                if (!watchUrl.isNullOrBlank()) {
                    loadExtractor(watchUrl, data, subtitleCallback, callback)
                }
            } catch (_: Exception) {}
        }

        // Strategy 3: Search for any iframe in the page
        coroutineScope {
            doc.select("iframe").map { iframe ->
                async {
                    val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
                    if (src.isNotBlank() && src.startsWith("http")) {
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
                if (!embedUrl.isNullOrBlank()) {
                    loadExtractor(embedUrl, data, subtitleCallback, callback)
                }
            } catch (_: Exception) {}
        }

        // Strategy 5: Look for direct video source tags
        doc.select("video source, video[src], .player video").forEach { videoEl ->
            val src = videoEl.attr("src").ifBlank { videoEl.selectFirst("source")?.attr("src") ?: "" }
            if (src.isNotBlank() && (src.endsWith(".mp4") || src.endsWith(".m3u8"))) {
                val fullSrc = if (src.startsWith("http")) src else "$mainUrl$src"
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = fullSrc,
                    ) {
                        referer = mainUrl
                        quality = Qualities.Unknown.value
                    }
                )
            }
        }

        return true
    }

    // ==================== HELPER ====================

    private fun Element.toSearchResponse(): SearchResponse? {
        val titleEl = selectFirst("a, h2, h3, .title, .card-title")
        val title = titleEl?.text()?.trim() ?: return null

        val href = titleEl.attr("href")
            .ifBlank { selectFirst("a")?.attr("href") ?: "" }
        if (href.isBlank()) return null

        val fullUrl = if (href.startsWith("http")) href else "$mainUrl$href"

        val posterUrl = selectFirst("img")?.let { img ->
            img.attr("data-src").ifBlank { img.attr("src") }
        }

        val isTvSeries = fullUrl.contains("/tvshow/") || fullUrl.contains("/series/")

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