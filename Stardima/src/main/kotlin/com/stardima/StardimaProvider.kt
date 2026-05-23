package com.stardima

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import com.fasterxml.jackson.annotation.JsonProperty
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

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
    @JsonProperty("title") val title: String?
)

data class StardimaSeasonDetail(
    @JsonProperty("number") val number: Int?
)

data class HyperLinkResponse(
    @JsonProperty("success") val success: Boolean?,
    @JsonProperty("status") val status: String?,
    @JsonProperty("watch_url") val watchUrl: String?,
    @JsonProperty("server_name") val serverName: String?,
    @JsonProperty("message") val message: String?
)

class StardimaProvider : MainAPI() {
    override var mainUrl = "https://www.stardima.com"
    override var name = "ستارديما"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
    override var lang = "ar"
    override val hasMainPage = true

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
    )

    private val apiHeaders = headers + mapOf(
        "X-Requested-With" to "XMLHttpRequest",
        "Accept" to "application/json"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(mainUrl, headers = headers).document
        val sections = mutableListOf<HomePageList>()

        val headings = doc.select("h2.text-white")
        for (heading in headings) {
            val sectionTitle = heading.text().trim()
            if (sectionTitle.isBlank()) continue

            val embla = heading.parent()?.parent()?.selectFirst("div.embla")
                ?: heading.parent()?.parent()?.nextElementSiblings()?.select("div.embla")?.first()
                ?: continue

            val items = embla.select("div.embla__slide").mapNotNull { slide ->
                slide.toSearchResult()
            }
            if (items.isNotEmpty()) {
                sections.add(HomePageList(sectionTitle, items))
            }
        }

        if (sections.isEmpty()) {
            doc.select("div.embla").forEach { carousel ->
                val title = carousel.parent()?.selectFirst("h2")?.text()?.trim() ?: "الرئيسية"
                val items = carousel.select("div.embla__slide").mapNotNull { slide ->
                    slide.toSearchResult()
                }
                if (items.isNotEmpty()) {
                    sections.add(HomePageList(title, items))
                }
            }
        }

        return newHomePageResponse(sections)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val response = app.get(
            "$mainUrl/search?query=${java.net.URLEncoder.encode(query, "UTF-8")}",
            headers = apiHeaders
        )
        val result = response.parsedSafe<StardimaSearchResponse>() ?: return emptyList()

        return result.videos?.mapNotNull { video ->
            val url = video.url ?: return@mapNotNull null
            val fullUrl = if (url.startsWith("http")) url else "$mainUrl$url"
            val title = video.title ?: return@mapNotNull null
            val posterUrl = video.posterUrl?.let { p ->
                if (p.startsWith("http")) p else "$mainUrl/storage/$p"
            }

            if (video.isSeries == true) {
                newTvSeriesSearchResponse(title, fullUrl) { this.posterUrl = posterUrl }
            } else {
                newMovieSearchResponse(title, fullUrl) { this.posterUrl = posterUrl }
            }
        } ?: emptyList()
    }

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

        return if (url.contains("/tvshow/")) {
            loadTvSeries(url, doc, title, poster, description, tags)
        } else {
            loadMovie(url, doc, title, poster, description, tags)
        }
    }

    private suspend fun loadMovie(
        url: String, doc: org.jsoup.nodes.Document, title: String,
        poster: String?, description: String?, tags: List<String>?
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
        url: String, doc: org.jsoup.nodes.Document, title: String,
        poster: String?, description: String?, tags: List<String>?
    ): LoadResponse {
        val episodes = mutableListOf<Episode>()
        val seriesSlug = url.trimEnd('/').substringAfterLast("/")

        val firstPlayHref = doc.selectFirst("a[href*='/play/']")?.attr("href")
            ?.let { if (it.startsWith("http")) it else "$mainUrl$it" }

        if (firstPlayHref == null) {
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster; this.plot = description; this.tags = tags
            }
        }

        val playDoc = app.get(firstPlayHref, headers = headers).document
        val seasonItems = playDoc.select(".season-item")

        if (seasonItems.isNotEmpty()) {
            for (seasonEl in seasonItems) {
                val seasonId = seasonEl.attr("data-season-id")
                val seasonNumberStr = seasonEl.attr("data-season-number")
                val seasonNumber = Regex("""(\d+)""").find(seasonNumberStr)
                    ?.groupValues?.get(1)?.toIntOrNull() ?: 1

                if (seasonId.isNotBlank()) {
                    try {
                        val apiResp = app.get("$mainUrl/series/season/$seasonId", headers = apiHeaders)
                        val seasonData = apiResp.parsedSafe<StardimaSeasonEpisodesResponse>()
                        seasonData?.episodes?.forEach { ep ->
                            val epId = ep.id ?: return@forEach
                            val epTitle = ep.title ?: "حلقة ${ep.episodeNumber ?: epId}"
                            val slug = seasonData.seriesId ?: seriesSlug
                            val playUrl = "$mainUrl/tvshow/$slug/play/$epId"

                            // Store only the play URL - we'll get watch_url at loadLinks time
                            // This avoids stale watch_url data
                            episodes.add(newEpisode(playUrl) {
                                name = epTitle
                                season = seasonNumber
                                episode = ep.episodeNumber ?: 0
                            })
                        }
                    } catch (_: Exception) {}
                }
            }
        }

        if (episodes.isEmpty()) {
            val epItems = playDoc.select(".episode-list-item")
            for (epEl in epItems) {
                val epId = epEl.attr("data-episode-id")
                val epTitle = epEl.selectFirst("a[data-episode-id]")?.text()?.trim() ?: "حلقة $epId"
                if (epId.isNotBlank()) {
                    val playUrl = "$mainUrl/tvshow/$seriesSlug/play/$epId"
                    episodes.add(newEpisode(playUrl) { name = epTitle; season = 1 })
                }
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster; this.plot = description; this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val isTvEpisode = data.contains("/tvshow/") && data.contains("/play/")

        if (isTvEpisode) {
            val episodeId = data.trimEnd('/').substringAfterLast("/")
            if (episodeId.all { it.isDigit() }) {
                // Step 1: Get hyperwatching iframe URL from stardima API
                try {
                    val apiResp = app.get("$mainUrl/series/episode/$episodeId", headers = apiHeaders)
                    val epData = apiResp.parsedSafe<StardimaEpisodeDetailResponse>()
                    val hyperUrl = epData?.episode?.watchUrl
                    if (!hyperUrl.isNullOrBlank()) {
                        // Step 2: Extract ALL servers from the hyperwatching iframe page
                        // This is critical - server IDs are per-episode!
                        extractAllHyperServers(hyperUrl, subtitleCallback, callback)
                    }
                } catch (_: Exception) {}
            }
        } else {
            // Movie: scrape the play page for iframe
            try {
                val doc = app.get(data, headers = headers).document
                doc.select("iframe[src]").forEach { iframe ->
                    val src = iframe.attr("src")
                    if (src.isNotBlank() && src.startsWith("http") && !src.contains("about:blank")) {
                        extractAllHyperServers(src, subtitleCallback, callback)
                    }
                }
            } catch (_: Exception) {}
        }

        return true
    }

    /**
     * Fetch the hyperwatching iframe page for THIS specific episode,
     * parse the per-episode server list, then try each server in parallel.
     */
    private suspend fun extractAllHyperServers(
        hyperUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            // Clean URL - remove query params that may cause issues
            val cleanUrl = hyperUrl.split("?").first()

            val pageResponse = app.get(cleanUrl, headers = headers)
            val html = pageResponse.text

            // Parse server list from config.servers
            // Pattern: { id: "123456", name: "Lulustream", icon: "..." }
            val servers = mutableListOf<Pair<String, String>>()
            val serverRegex = Regex("""id:\s*["'](\d+)["'],\s*name:\s*["']([^"']+)["']""")
            serverRegex.findAll(html).forEach { match ->
                servers.add(Pair(match.groupValues[1], match.groupValues[2]))
            }

            if (servers.isEmpty()) {
                // Try CloudStream built-in extractors as fallback
                safeLoadExtractor(cleanUrl, subtitleCallback, callback)
                return
            }

            // Parse the link API route
            val linkRoute = Regex("""link:\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
            if (linkRoute.isNullOrBlank()) {
                safeLoadExtractor(cleanUrl, subtitleCallback, callback)
                return
            }

            // Try all servers in parallel
            coroutineScope {
                servers.map { server ->
                    async {
                        tryHyperServer(
                            server.first, server.second, linkRoute,
                            subtitleCallback, callback
                        )
                    }
                }.awaitAll()
            }
        } catch (_: Exception) {
            // Fallback to CloudStream's built-in extractors
            safeLoadExtractor(hyperUrl, subtitleCallback, callback)
        }
    }

    /**
     * Try a single hyperwatching server:
     * 1. POST to link API to get the third-party embed URL
     * 2. Route that URL to the appropriate extractor (lulustream, uqload, etc.)
     */
    private suspend fun tryHyperServer(
        serverId: String, serverName: String,
        linkRoute: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val linkResp = app.post(
                linkRoute,
                headers = mapOf(
                    "Content-Type" to "application/x-www-form-urlencoded",
                    "Accept" to "application/json",
                    "User-Agent" to headers["User-Agent"]!!
                ),
                data = mapOf("server_link_id" to serverId)
            )
            val linkData = linkResp.parsedSafe<HyperLinkResponse>()

            // Skip failed servers
            if (linkData?.success != true) return
            if (linkData.watchUrl.isNullOrBlank()) return

            val watchUrl = linkData.watchUrl

            // Route the URL to the right extractor
            routeUrl(watchUrl, serverName, subtitleCallback, callback)
        } catch (_: Exception) {}
    }

    /**
     * Route a URL to the appropriate extractor based on its domain
     */
    private suspend fun routeUrl(
        url: String, serverName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        when {
            url.contains("lulustream.com") || url.contains("luluvdo.com") -> {
                extractLulustream(url, callback)
            }
            url.contains("strema.top") -> {
                // strema.top wraps other hosts - extract the inner URL from id parameter
                val innerUrl = Regex("""[?&]id=(https?://[^&]+)""").find(url)?.groupValues?.get(1)
                if (!innerUrl.isNullOrBlank()) {
                    val decoded = java.net.URLDecoder.decode(innerUrl, "UTF-8")
                    routeUrl(decoded, serverName, subtitleCallback, callback)
                } else {
                    safeLoadExtractor(url, subtitleCallback, callback)
                }
            }
            url.contains("uqload") -> {
                extractUqload(url, callback)
            }
            url.contains("hgplaycdn.com") || url.contains("streamhg") -> {
                extractStreamhg(url, callback)
            }
            url.contains("darkibox.com") -> {
                extractDarkibox(url, callback)
            }
            url.contains("krakenfiles.com") -> {
                safeLoadExtractor(url, subtitleCallback, callback)
            }
            url.contains("goodstream") -> {
                // goodstream is also wrapped in strema.top
                val innerUrl = Regex("""[?&]id=(https?://[^&]+)""").find(url)?.groupValues?.get(1)
                if (!innerUrl.isNullOrBlank()) {
                    val decoded = java.net.URLDecoder.decode(innerUrl, "UTF-8")
                    safeLoadExtractor(decoded, subtitleCallback, callback)
                } else {
                    safeLoadExtractor(url, subtitleCallback, callback)
                }
            }
            else -> {
                safeLoadExtractor(url, subtitleCallback, callback)
            }
        }
    }

    private suspend fun safeLoadExtractor(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try { loadExtractor(url, url, subtitleCallback, callback) } catch (_: Exception) {}
    }

    // ==================== LULUSTREAM EXTRACTOR ====================

    private suspend fun extractLulustream(url: String, callback: (ExtractorLink) -> Unit) {
        try {
            val response = app.get(url, headers = headers)
            val html = response.text

            // Find Dean Edwards packed eval JS
            val packedRegex = Regex(
                """eval\(function\(p,a,c,k,e,d\)\{.*?\}\('(.+?)',(\d+),(\d+),'(.+?)'\)""",
                RegexOption.DOT_MATCHES_ALL
            )
            val packedMatch = packedRegex.find(html) ?: return

            val payload = packedMatch.groupValues[1]
            val base = packedMatch.groupValues[2].toInt()
            val count = packedMatch.groupValues[3].toInt()
            val dictStr = packedMatch.groupValues[4]

            val decoded = decodePackedJs(payload, base, count, dictStr)

            // Extract m3u8 URL from decoded JS
            val m3u8Regex = Regex("""file:\s*["'](https?://[^"']+\.m3u8[^"']*)""")
            val m3u8Match = m3u8Regex.find(decoded) ?: return
            val m3u8Url = m3u8Match.groupValues[1]

            val referer = if (url.contains("luluvdo.com")) "https://luluvdo.com/" else "https://lulustream.com/"

            callback.invoke(
                newExtractorLink(
                    source = "Lulustream",
                    name = "$name - Lulustream",
                    url = m3u8Url,
                    type = INFER_TYPE
                ) {
                    this.referer = referer
                    this.quality = Qualities.Unknown.value
                }
            )
        } catch (_: Exception) {}
    }

    private fun decodePackedJs(payload: String, base: Int, count: Int, dictStr: String): String {
        val dictionary = dictStr.split("|").toMutableList()
        while (dictionary.size < count) dictionary.add("")

        var result = payload
        for (i in count - 1 downTo 0) {
            if (dictionary[i].isNotBlank()) {
                val baseStr = i.toString(base)
                result = result.replace("\\b$baseStr\\b".toRegex(), dictionary[i])
            }
        }
        return result
    }

    // ==================== UQLOAD EXTRACTOR ====================

    private suspend fun extractUqload(url: String, callback: (ExtractorLink) -> Unit) {
        try {
            val html = app.get(url, headers = headers).text

            // Try sources:[{file:"URL"}]
            val mp4Regex = Regex("""sources:\s*\[\{file:\s*["'](https?://[^"']+)["']""")
            val mp4Match = mp4Regex.find(html)

            if (mp4Match != null) {
                callback.invoke(
                    newExtractorLink(
                        source = "Uqload",
                        name = "$name - Uqload",
                        url = mp4Match.groupValues[1],
                        type = INFER_TYPE
                    ) {
                        this.referer = url
                        this.quality = Qualities.Unknown.value
                    }
                )
                return
            }

            // Fallback: look for .mp4 URL in playerjs
            val altRegex = Regex("""["'](https?://[^"']*\.mp4[^"']*)""")
            val altMatch = altRegex.find(html)
            if (altMatch != null) {
                callback.invoke(
                    newExtractorLink(
                        source = "Uqload",
                        name = "$name - Uqload",
                        url = altMatch.groupValues[1],
                        type = INFER_TYPE
                    ) {
                        this.referer = url
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        } catch (_: Exception) {}
    }

    // ==================== STREAMHG EXTRACTOR ====================

    private suspend fun extractStreamhg(url: String, callback: (ExtractorLink) -> Unit) {
        try {
            val html = app.get(url, headers = headers).text

            // Look for m3u8 URL in the page source
            val m3u8Regex = Regex("""["'](https?://[^"']+\.m3u8[^"']*)""")
            val m3u8Match = m3u8Regex.find(html)
            if (m3u8Match != null) {
                callback.invoke(
                    newExtractorLink(
                        source = "Streamhg",
                        name = "$name - Streamhg",
                        url = m3u8Match.groupValues[1],
                        type = INFER_TYPE
                    ) {
                        this.referer = url
                        this.quality = Qualities.Unknown.value
                    }
                )
                return
            }

            // Try CloudStream's built-in as fallback
            safeLoadExtractor(url, { _ -> }, callback)
        } catch (_: Exception) {}
    }

    // ==================== DARKIBOX EXTRACTOR ====================

    private suspend fun extractDarkibox(url: String, callback: (ExtractorLink) -> Unit) {
        try {
            val html = app.get(url, headers = headers).text

            // Look for video URL in the page
            val videoRegex = Regex("""["'](https?://[^"']+\.(?:mp4|m3u8)[^"']*)""")
            val videoMatch = videoRegex.find(html)
            if (videoMatch != null) {
                callback.invoke(
                    newExtractorLink(
                        source = "Darkibox",
                        name = "$name - Darkibox",
                        url = videoMatch.groupValues[1],
                        type = INFER_TYPE
                    ) {
                        this.referer = url
                        this.quality = Qualities.Unknown.value
                    }
                )
                return
            }

            safeLoadExtractor(url, { _ -> }, callback)
        } catch (_: Exception) {}
    }

    // ==================== HELPERS ====================

    private fun Element.toSearchResult(): SearchResponse? {
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

        return if (fullUrl.contains("/tvshow/")) {
            newTvSeriesSearchResponse(title, fullUrl) { this.posterUrl = posterUrl }
        } else {
            newMovieSearchResponse(title, fullUrl) { this.posterUrl = posterUrl }
        }
    }
}