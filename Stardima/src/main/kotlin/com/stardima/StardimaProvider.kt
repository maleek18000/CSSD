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

    // ==================== MAIN PAGE ====================

    override val mainPage = mainPageOf(
        "$mainUrl/mosalsalat" to "مسلسلات",
        "$mainUrl/aflam" to "أفلام",
        "$mainUrl/mosalsalat?category=anmy" to "أنمي",
        "$mainUrl/mosalsalat?category=krton" to "كرتون",
        "$mainUrl/mosalsalat?category=krton-ntork" to "كرتون نتورك",
        "$mainUrl/mosalsalat?category=sbyston" to "سبيستون",
        "$mainUrl/mosalsalat?status=continu" to "مسلسلات مستمرة",
        "$mainUrl/aflam?category=aflam-konan" to "أفلام كونان",
        "$mainUrl/aflam?language=dub" to "أفلام مدبلجة",
        "$mainUrl/mosalsalat?language=dub" to "مسلسلات مدبلجة",
        "$mainUrl/aflam?category=aflam-barby" to "أفلام باربي",
        "$mainUrl/mosalsalat?category=abtal-alakshn" to "أبطال الأكشن",
        "$mainUrl/mosalsalat?category=nynga" to "نينجا"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val separator = if (request.data.contains("?")) "&" else "?"
        val url = "${request.data}${separator}page=$page"
        val response = app.get(url, headers = apiHeaders)
        val result = response.parsedSafe<StardimaSearchResponse>()
            ?: return newHomePageResponse(emptyList(), false)

        val items = result.videos?.mapNotNull { it.toSearchResult() } ?: emptyList()
        val hasNextPage = (result.pagination?.currentPage ?: 1) < (result.pagination?.lastPage ?: 1)

        return newHomePageResponse(
            listOf(HomePageList(request.name, items)),
            hasNext = hasNextPage
        )
    }

    // ==================== SEARCH ====================

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val allResults = mutableListOf<SearchResponse>()

        // Fetch page 1 first to get pagination info
        val firstResponse = app.get(
            "$mainUrl/search?query=$encodedQuery&page=1",
            headers = apiHeaders
        )
        val firstResult = firstResponse.parsedSafe<StardimaSearchResponse>() ?: return emptyList()

        firstResult.videos?.let { videos ->
            allResults.addAll(videos.mapNotNull { it.toSearchResult() })
        }

        // Check if there are more pages and fetch them
        val lastPage = firstResult.pagination?.lastPage ?: 1
        if (lastPage > 1) {
            for (page in 2..lastPage) {
                try {
                    val pageResponse = app.get(
                        "$mainUrl/search?query=$encodedQuery&page=$page",
                        headers = apiHeaders
                    )
                    val pageResult = pageResponse.parsedSafe<StardimaSearchResponse>()
                    pageResult?.videos?.let { videos ->
                        val mapped = videos.mapNotNull { it.toSearchResult() }
                        if (mapped.isEmpty()) break // No more results
                        allResults.addAll(mapped)
                    } ?: break
                } catch (_: Exception) { break }
            }
        }

        return allResults
    }

    private fun StardimaSearchVideo.toSearchResult(): SearchResponse? {
        val url = this.url ?: return null
        val fullUrl = if (url.startsWith("http")) url else "$mainUrl$url"
        val title = this.title ?: return null
        val posterUrl = posterUrl?.let { p ->
            if (p.startsWith("http")) p else "$mainUrl/storage/$p"
        }

        return if (isSeries == true) {
            newTvSeriesSearchResponse(title, fullUrl) { this.posterUrl = posterUrl }
        } else {
            newMovieSearchResponse(title, fullUrl) { this.posterUrl = posterUrl }
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

    // ==================== LOAD LINKS ====================

    override suspend fun loadLinks(
        data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val isTvEpisode = data.contains("/tvshow/") && data.contains("/play/")

        if (isTvEpisode) {
            val episodeId = data.trimEnd('/').substringAfterLast("/")
            if (episodeId.all { it.isDigit() }) {
                try {
                    val apiResp = app.get("$mainUrl/series/episode/$episodeId", headers = apiHeaders)
                    val epData = apiResp.parsedSafe<StardimaEpisodeDetailResponse>()
                    val hyperUrl = epData?.episode?.watchUrl
                    if (!hyperUrl.isNullOrBlank()) {
                        extractAllHyperServers(hyperUrl, subtitleCallback, callback)
                    }
                } catch (_: Exception) {}
            }
            // Note: The play page HTML has the iframe src="" (empty, populated via JS),
            // so doc.select("iframe[src]") finds nothing useful. We rely on the API only.
        } else {
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

    // ==================== HYPERWATCHING EXTRACTOR ====================

    private suspend fun extractAllHyperServers(
        hyperUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val cleanUrl = hyperUrl.split("?").first()
            val pageResponse = app.get(cleanUrl, headers = headers)
            val html = pageResponse.text

            val csrf = Regex("csrf:\\s*[\"']([^\"']+)[\"']").find(html)?.groupValues?.get(1)

            val servers = mutableListOf<Pair<String, String>>()
            val serverRegex = Regex("id:\\s*[\"'](\\d+)[\"'],\\s*name:\\s*[\"']([^\"']+)[\"']")
            serverRegex.findAll(html).forEach { match ->
                servers.add(Pair(match.groupValues[1], match.groupValues[2]))
            }

            if (servers.isEmpty()) {
                // Not a hyperwatching page — try built-in extractors
                safeLoadExtractor(cleanUrl, subtitleCallback, callback)
                return
            }

            val linkRoute = Regex("link:\\s*[\"']([^\"']+)[\"']").find(html)?.groupValues?.get(1)
            if (linkRoute.isNullOrBlank()) {
                safeLoadExtractor(cleanUrl, subtitleCallback, callback)
                return
            }

            coroutineScope {
                servers.map { server ->
                    async {
                        tryHyperServer(
                            server.first, server.second, linkRoute, csrf,
                            subtitleCallback, callback
                        )
                    }
                }.awaitAll()
            }
        } catch (_: Exception) {
            safeLoadExtractor(hyperUrl, subtitleCallback, callback)
        }
    }

    private suspend fun tryHyperServer(
        serverId: String, serverName: String,
        linkRoute: String, csrf: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val postHeaders = mutableMapOf(
                "Accept" to "application/json, text/plain, */*",
                "User-Agent" to headers["User-Agent"]!!,
                "X-Requested-With" to "XMLHttpRequest"
            )
            if (!csrf.isNullOrBlank()) {
                postHeaders["X-CSRF-TOKEN"] = csrf
            }

            var linkData: HyperLinkResponse? = null

            try {
                val resp = app.post(
                    linkRoute,
                    headers = postHeaders + mapOf("Content-Type" to "application/json"),
                    data = mapOf("server_link_id" to serverId)
                )
                linkData = resp.parsedSafe<HyperLinkResponse>()
            } catch (_: Exception) {}

            if (linkData?.success != true) {
                try {
                    val resp = app.post(
                        linkRoute,
                        headers = postHeaders + mapOf("Content-Type" to "application/x-www-form-urlencoded"),
                        data = mapOf("server_link_id" to serverId)
                    )
                    linkData = resp.parsedSafe<HyperLinkResponse>()
                } catch (_: Exception) {}
            }

            if (linkData?.success != true) return
            if (linkData.watchUrl.isNullOrBlank()) return

            routeAndExtract(linkData.watchUrl, serverName, subtitleCallback, callback)
        } catch (_: Exception) {}
    }

    // ==================== URL ROUTER ====================

    private suspend fun routeAndExtract(
        url: String, serverName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // For strema.top, decode the inner URL from the ?id= parameter
        val actualUrl = if (url.contains("strema.top")) {
            val innerUrlRaw = Regex("[?&]id=([^&]+)").find(url)?.groupValues?.get(1)
            val innerUrl = innerUrlRaw?.let { java.net.URLDecoder.decode(it, "UTF-8") }
            if (!innerUrl.isNullOrBlank() && innerUrl.startsWith("http")) {
                innerUrl
            } else {
                // Fallback: try to follow strema.top redirect
                try {
                    val stremaDoc = app.get(url, headers = headers).document
                    val formAction = stremaDoc.selectFirst("form")?.attr("action")
                    if (!formAction.isNullOrBlank()) {
                        if (formAction.startsWith("http")) formAction else "https://strema.top$formAction"
                    } else {
                        stremaDoc.selectFirst("iframe[src]")?.attr("src")?.takeIf {
                            it.isNotBlank() && it.startsWith("http")
                        } ?: url
                    }
                } catch (_: Exception) { url }
            }
        } else {
            url
        }

        // Extract video from the embed page using our packed JS decoder.
        // We do NOT use CloudStream's built-in loadExtractor for known hosts because:
        // - Built-in Lulustream uses script:containsData(vplayer) which fails on packed JS
        // - Built-in Uqload only handles .com/.co/.cx/.bz, NOT .is
        // For known hosts we use our custom extractors only.
        // For unknown hosts we fall through to the built-in extractor.
        val handled = extractFromHost(actualUrl, serverName, subtitleCallback, callback)
        if (!handled) {
            safeLoadExtractor(actualUrl, subtitleCallback, callback)
        }
    }

    // ==================== HOST ROUTER ====================

    private suspend fun extractFromHost(
        url: String, serverName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return when {
            url.contains("lulustream.com") || url.contains("luluvdo.com") -> {
                extractPackedEmbed(url, serverName, callback); true
            }
            url.contains("uqload") -> {
                extractPackedEmbed(url, serverName, callback); true
            }
            url.contains("hgplaycdn.com") || url.contains("streamhg") || url.contains("huntrexus") -> {
                extractPackedEmbed(url, serverName, callback); true
            }
            url.contains("darkibox.com") -> {
                extractPackedEmbed(url, serverName, callback); true
            }
            url.contains("krakenfiles.com") -> {
                extractKrakenfiles(url, serverName, callback); true
            }
            url.contains("goodstream") -> {
                extractPackedEmbed(url, serverName, callback); true
            }
            url.contains("earnvids") || url.contains("earnvidsapi") || url.contains("minochinos") || url.contains("vidhide") -> {
                extractPackedEmbed(url, serverName, callback); true
            }
            else -> false
        }
    }

    // ==================== PACKED EMBED EXTRACTOR ====================
    // Universal extractor for embed pages that use Dean Edwards packed JS.
    // Works for lulustream, uqload, hgplaycdn, darkibox, earnvids/minochinos, etc.
    //
    // IMPORTANT: Many video CDNs (lulustream/tnmr.org, uqload) use anti-hotlinking
    // protection. The embed page JS calls a "view tracking" URL (/dl?op=view&...)
    // which registers the client's IP with the CDN. Without this call, the CDN
    // returns 403 for encryption keys and .ts segments, causing ExoPlayer error
    // "io bad http status (2004)". We must extract and call this tracking URL
    // BEFORE returning the ExtractorLink.
    //
    // Some hosts (Earnvids/VidHide/Minochinos) provide multiple CDN URLs in
    // a var links={...} object. We extract ALL URLs and add them as separate
    // ExtractorLinks so the user can try alternatives if one fails.
    // Example: hls2 may return 403 while hls3 works — we include both.

    private suspend fun extractPackedEmbed(
        url: String, serverName: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val html = app.get(url, headers = headers).text

            // Decode packed JS to get both video URL and view tracking URL
            val decoded = decodePackedFromHtml(html)

            // CRITICAL: Call the view tracking URL to register this IP with the CDN.
            // Without this, the CDN returns 403 for segments/keys.
            decoded?.let { callViewTracking(it, url) }

            // Extract ALL video URLs from decoded JS (may be multiple for VidHide hosts)
            val videoUrls = decoded?.let { extractAllVideoUrlsFromDecoded(it) } ?: emptyList()

            // If no URLs from packed JS, try regex on raw HTML
            val allUrls = if (videoUrls.isNotEmpty()) {
                videoUrls
            } else {
                val fallbackUrl = Regex("""(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)""").find(html)?.groupValues?.get(1)
                    ?: Regex("""(https?://[^\s"'<>]+\.mp4[^\s"'<>]*)""").find(html)?.groupValues?.get(1)
                if (fallbackUrl != null) listOf(Pair(serverName, fallbackUrl)) else emptyList()
            }

            for ((label, videoUrl) in allUrls) {
                // Detect HLS streams: .m3u8 extension OR .txt from var links (disguised m3u8)
                val isM3u8 = videoUrl.contains(".m3u8") ||
                    (videoUrl.contains(".txt") && label.startsWith("hls"))

                if (isM3u8) {
                    // Use M3u8Helper.generateM3u8 for proper m3u8 parsing — it resolves
                    // relative URLs, handles variant playlists, and passes headers to ALL
                    // segment/key requests. Direct ExtractorLink with type=M3U8 can fail
                    // because ExoPlayer may not send Referer on key/segment requests,
                    // causing 403 (error 2004).
                    try {
                        M3u8Helper.generateM3u8(
                            source = serverName,
                            streamUrl = videoUrl,
                            referer = url,
                            headers = mapOf(
                                "Referer" to url,
                                "User-Agent" to headers["User-Agent"]!!
                            ),
                            name = "$serverName ($label)"
                        ).forEach { link ->
                            callback.invoke(link)
                        }
                    } catch (_: Exception) {
                        // Fallback: direct link if M3u8Helper fails
                        callback.invoke(
                            newExtractorLink(
                                source = serverName,
                                name = "$serverName ($label)",
                                url = videoUrl,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = url
                                this.quality = Qualities.Unknown.value
                            }
                        )
                    }
                } else {
                    callback.invoke(
                        newExtractorLink(
                            source = serverName,
                            name = "$serverName ($label)",
                            url = videoUrl,
                            type = INFER_TYPE
                        ) {
                            this.referer = url
                            this.headers = mapOf(
                                "Referer" to url,
                                "User-Agent" to headers["User-Agent"]!!
                            )
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }
            }
        } catch (_: Exception) {}
    }

    /**
     * Call the view tracking URL found in the decoded JS.
     * This registers the client IP with the CDN so segments can be fetched.
     * Pattern: /dl?op=view&file_code=...&hash=...&embed=1&referer=&adb=0
     *          /dl?op=view&view_id=...&hash=...&embed=1&adb=0
     */
    private suspend fun callViewTracking(decoded: String, embedUrl: String) {
        try {
            // Extract the view tracking relative URL from decoded JS
            val viewPattern = Regex("""/dl\?op=view[^"'\s]+""")
            val viewMatch = viewPattern.find(decoded) ?: return
            var viewPath = viewMatch.groupValues[0]

            // Clean up trailing characters that aren't part of the URL
            viewPath = viewPath.trimEnd('&', '\'', '"', ',', ')')

            // Replace adb= (empty) with adb=0 (tell server no adblock)
            viewPath = viewPath.replace("adb=", "adb=0")

            // Build the full URL using the same host as the embed page
            val baseUrl = Regex("(https?://[^/]+)").find(embedUrl)?.groupValues?.get(1) ?: return
            val fullViewUrl = baseUrl + viewPath

            // Call the view tracking URL — this registers our IP with the CDN
            app.get(fullViewUrl, headers = headers + mapOf("Referer" to embedUrl))
        } catch (_: Exception) {}
    }

    /**
     * Extract ALL video URLs from already-decoded packed JS.
     * Returns a list of (label, url) pairs.
     *
     * Formats supported:
     * 1. var links={"hls2":"https://...","hls3":"https://..."};
     *    sources:[{file:links.hls4||links.hls3||links.hls2,type:"hls"}]
     *    → Returns ALL URLs from the links object as separate entries.
     *    This is important because some CDN mirrors (hls2) may return 403
     *    while others (hls3) work fine. By including all URLs, the user can
     *    try alternatives.
     * 2. Direct literal: file:"https://..." or sources:[{file:"https://..."...}]
     * 3. Fallback: any .m3u8 URL found in the decoded text
     */
    private fun extractAllVideoUrlsFromDecoded(decoded: String): List<Pair<String, String>> {
        val results = mutableListOf<Pair<String, String>>()

        // 1. Try var links pattern (VidHide/Earnvids/Minochinos/Streamhg style)
        //    This pattern provides multiple CDN URLs — we include ALL of them
        //    because some mirrors may be 403 while others work.
        //    Order: hls4 > hls3 > hls2 (higher number = usually better/preferred by player)
        val linksMatch = Regex("var\\s+links\\s*=\\s*\\{([^}]+)\\}").find(decoded)
        if (linksMatch != null) {
            val linksBody = linksMatch.groupValues[1]
            val urlEntries = mutableListOf<Pair<String, String>>()
            Regex("\"(\\w+)\"\\s*:\\s*\"(https?://[^\"]+)\"").findAll(linksBody).forEach { m ->
                urlEntries.add(Pair(m.groupValues[1], m.groupValues[2]))
            }
            // Sort: hls4 first (best), then hls3, then hls2, then others
            val sorted = urlEntries.sortedByDescending { (key, _) ->
                when {
                    key == "hls4" -> 4
                    key == "hls3" -> 3
                    key == "hls2" -> 2
                    key.startsWith("hls") -> 1
                    else -> 0
                }
            }
            for (entry in sorted) {
                results.add(Pair(entry.first, entry.second))
            }
        }

        // 2. Try direct literal patterns (Lulustream, Uqload style)
        if (results.isEmpty()) {
            val literalPatterns = listOf(
                Regex("file:\\s*[\"'](https?://[^\"']+\\.m3u8[^\"']*)"),
                Regex("file:\\s*[\"'](https?://[^\"']+)[\"']"),
                Regex("sources:\\s*\\[\\{file:\\s*[\"'](https?://[^\"']+)[\"']"),
                Regex("[\"']src[\"']:\\s*[\"'](https?://[^\"']+\\.m3u8[^\"']*)")
            )
            for (pattern in literalPatterns) {
                val match = pattern.find(decoded)
                if (match != null) {
                    results.add(Pair("Video", match.groupValues[1]))
                    break
                }
            }
        }

        // 3. Last resort: scan for any .m3u8 URL in the decoded text
        if (results.isEmpty()) {
            val m3u8Match = Regex("(https?://[^\\s\"'<>]+\\.m3u8[^\\s\"'<>]*)").find(decoded)
            if (m3u8Match != null) {
                results.add(Pair("Video", m3u8Match.groupValues[1]))
            }
        }

        return results
    }

    // ==================== KRAKENFILES EXTRACTOR ====================
    // Krakenfiles uses a simple HTML5 <video> with <source> tag instead of
    // packed JS. The video URL is in the src attribute of <source>.
    // No packed JS, no jwplayer.
    //
    // The CDN (krakencloud.net) serves MP4 with content-type: application/octet-stream.
    // The URL has no .mp4 extension so INFER_TYPE can't auto-detect the format.
    // We append ".mp4" as a fragment (#) so CloudStream recognizes the file type,
    // but it's never sent to the server (fragments stay in the browser/client).
    //
    // CRITICAL: The CDN blocks ExoPlayer's default User-Agent. We must pass
    // a browser User-Agent via the headers map so ExoPlayer uses it instead,
    // otherwise the CDN returns 403 → "io bad http status (2004)".

    private suspend fun extractKrakenfiles(
        url: String, serverName: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val html = app.get(url, headers = headers).text

            // Try <source src="..." type="video/mp4"> first
            val sourceMatch = Regex("""<source\s+src="(https?://[^"]+)"\s+type="video/[^"]+"""").find(html)
            val videoUrl = sourceMatch?.groupValues?.get(1)
                // Fallback: look for any URL that looks like a krakencloud video path
                ?: Regex("""(https?://[^\s"'<>]+krakencloud[^\s"'<>]+)""").find(html)?.groupValues?.get(1)
                // Fallback: look for any video-like source tag
                ?: Regex("""<source\s+src="(https?://[^"]+)"""").find(html)?.groupValues?.get(1)

            if (videoUrl != null) {
                // Append #.mp4 fragment as a type hint for CloudStream/ExoPlayer.
                // Fragment identifiers are never sent to the server, so the CDN
                // sees the original URL, while CloudStream detects it as MP4.
                val hintUrl = if (videoUrl.contains(".mp4")) videoUrl else "$videoUrl#.mp4"

                callback.invoke(
                    newExtractorLink(
                        source = serverName,
                        name = serverName,
                        url = hintUrl,
                        type = INFER_TYPE
                    ) {
                        this.referer = url
                        this.headers = mapOf(
                            "User-Agent" to headers["User-Agent"]!!
                        )
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        } catch (_: Exception) {}
    }

    // ==================== SAFE EXTRACTOR FALLBACK ====================

    private suspend fun safeLoadExtractor(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try { loadExtractor(url, url, subtitleCallback, callback) } catch (_: Exception) {}
    }

    // ==================== PACKED JS DECODER ====================

    /**
     * Decode the Dean Edwards packed JS from HTML and return the full decoded string.
     * Returns null if no packed JS is found or decoding fails.
     */
    private fun decodePackedFromHtml(html: String): String? {
        try {
            val startMarker = "eval(function(p,a,c,k,e,d)"
            val startIdx = html.indexOf(startMarker)
            if (startIdx < 0) return null

            val scriptEnd = html.indexOf("</script>", startIdx)
            val searchEnd = if (scriptEnd > startIdx) scriptEnd else html.length
            val snippet = html.substring(startIdx, searchEnd)

            val bodyEndMarker = "}('"
            val bodyEndIdx = snippet.indexOf(bodyEndMarker)
            if (bodyEndIdx < 0) return null

            val afterBody = snippet.substring(bodyEndIdx + bodyEndMarker.length)

            // Use specific pattern that includes surrounding quotes to avoid matching
            // digit sequences inside the payload (e.g. SVG path data like ",0,0,").
            // The packed JS format is: }('PAYLOAD',BASE,COUNT,'DICT'.split('|'))
            // So the args are always between the closing quote of the payload and the
            // opening quote of the dictionary: ',BASE,COUNT,'
            val argsPattern = Regex("',(\\d+),(\\d+),'")
            val argsMatch = argsPattern.find(afterBody) ?: return null

            // The payload ends right before the quote+comma:  ...PAYLOAD',BASE,COUNT,'DICT...
            // argsMatch starts at the ', so payload ends at argsMatch.range.first
            val payloadEnd = argsMatch.range.first
            val payloadRaw = afterBody.substring(0, payloadEnd)
            val base = argsMatch.groupValues[1].toIntOrNull() ?: return null
            val count = argsMatch.groupValues[2].toIntOrNull() ?: return null

            val payload = payloadRaw
                .removeSurrounding("'")
                .replace("\\'", "'")
                .replace("\\\\", "\\")

            // After the args pattern ',BASE,COUNT,' the dictionary content follows
            // immediately (the trailing ' of the pattern is the opening quote of DICT).
            // Format: ...',BASE,COUNT,'DICT'.split('|'))
            val afterArgs = afterBody.substring(argsMatch.range.last + 1)

            // Find the closing quote of the dictionary string, then verify '.split('
            val splitMarker = "'.split('"
            val splitIdx = afterArgs.indexOf(splitMarker)
            val dictStr = if (splitIdx >= 0) {
                afterArgs.substring(0, splitIdx)
            } else {
                // Fallback: look for closing ')'
                val closeIdx = afterArgs.indexOf("')")
                if (closeIdx < 0) return null
                // Back up to find the closing quote before ')'
                val dictCloseQuote = afterArgs.lastIndexOf("'", closeIdx)
                if (dictCloseQuote < 0) return null
                afterArgs.substring(0, dictCloseQuote)
            }

            val cleanDict = dictStr
                .replace("\\'", "'")
                .replace("\\\\", "\\")

            return decodePackedJs(payload, base, count, cleanDict)
        } catch (_: Exception) { return null }
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
