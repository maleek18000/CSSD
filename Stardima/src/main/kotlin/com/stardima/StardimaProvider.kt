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
        val isTvEpisode = data.contains("/tvshow/") && data.contains("/play/")
        var hyperwatchingUrl: String? = null

        if (isTvEpisode) {
            val episodeId = data.trimEnd('/').substringAfterLast("/")
            if (episodeId.all { it.isDigit() }) {
                try {
                    val apiResponse = app.get(
                        "$mainUrl/series/episode/$episodeId",
                        headers = apiHeaders
                    )
                    val epData = apiResponse.parsedSafe<StardimaEpisodeDetailResponse>()
                    hyperwatchingUrl = epData?.episode?.watchUrl
                } catch (_: Exception) {}
            }
        } else {
            val doc = app.get(data, headers = headers).document
            hyperwatchingUrl = doc.selectFirst("#video-player-container iframe")?.attr("src")
                ?.let { if (it.startsWith("http")) it else "$mainUrl$it" }

            if (hyperwatchingUrl.isNullOrBlank()) {
                doc.select("iframe[src]").forEach { iframe ->
                    val src = iframe.attr("src")
                    if (src.isNotBlank() && src.startsWith("http") && !src.contains("about:blank")) {
                        loadExtractor(src, data, subtitleCallback, callback)
                    }
                }
            }
        }

        if (!hyperwatchingUrl.isNullOrBlank()) {
            extractFromHyperwatching(hyperwatchingUrl!!, subtitleCallback, callback)
        }

        return true
    }

    // ==================== HYPERWATCHING EXTRACTOR ====================

    private suspend fun extractFromHyperwatching(
        iframeUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val pageResponse = app.get(iframeUrl, headers = headers)
            val html = pageResponse.text

            val csrf = Regex("""csrf:\s*['"]([^'"]+)['"]""").find(html)?.groupValues?.get(1) ?: return

            val serverPattern = Regex("""\{id:\s*['"](\d+)['"],\s*name:\s*['"]([^'"]+)['"]""")
            val servers = serverPattern.findAll(html).map {
                it.groupValues[1] to it.groupValues[2]
            }.toList()

            if (servers.isEmpty()) {
                val simpleServers = Regex("""id:\s*['"](\d+)['"]""").findAll(html).map {
                    it.groupValues[1] to "Server"
                }.toList()
                if (simpleServers.isEmpty()) return
                extractFromServers(simpleServers, csrf, iframeUrl, html, subtitleCallback, callback)
            } else {
                extractFromServers(servers, csrf, iframeUrl, html, subtitleCallback, callback)
            }
        } catch (_: Exception) {}
    }

    private suspend fun extractFromServers(
        servers: List<Pair<String, String>>,
        csrf: String,
        iframeUrl: String,
        html: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val linkRoute = Regex("""link:\s*['"]([^'"]+)['"]""").find(html)?.groupValues?.get(1) ?: return
        val tokenRoute = Regex("""token:\s*['"]([^'"]+)['"]""").find(html)?.groupValues?.get(1)
        val extractRoute = Regex("""extract:\s*['"]([^'"]+)['"]""").find(html)?.groupValues?.get(1)

        val hyperApiHeaders = mapOf(
            "X-CSRF-TOKEN" to csrf,
            "Content-Type" to "application/x-www-form-urlencoded",
            "Accept" to "application/json",
            "Referer" to iframeUrl,
            "User-Agent" to headers["User-Agent"]!!
        )

        for ((serverId, serverName) in servers) {
            try {
                val linkResponse = app.post(
                    linkRoute,
                    headers = hyperApiHeaders,
                    data = mapOf("server_link_id" to serverId)
                )
                val linkData = linkResponse.parsedSafe<HyperwatchingLinkResponse>()

                if (linkData?.watchUrl.isNullOrBlank()) continue
                val watchUrl = linkData!!.watchUrl!!

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
                                continue
                            }
                        }
                    } catch (_: Exception) {}
                }

                try {
                    loadExtractor(watchUrl, iframeUrl, subtitleCallback, callback)
                } catch (_: Exception) {}

            } catch (_: Exception) {}
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