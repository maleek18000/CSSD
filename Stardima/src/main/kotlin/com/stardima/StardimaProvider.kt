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

    // ==================== SEARCH ====================

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

            // Fallback: try scraping the play page directly for iframes
            try {
                val doc = app.get(data, headers = headers).document
                doc.select("iframe[src]").forEach { iframe ->
                    val src = iframe.attr("src")
                    if (src.isNotBlank() && src.startsWith("http") && !src.contains("about:blank")) {
                        safeLoadExtractor(src, subtitleCallback, callback)
                    }
                }
            } catch (_: Exception) {}
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

    /**
     * Fetch the hyperwatching iframe page, extract servers and CSRF,
     * then try each server's link API to get third-party embed URLs.
     */
    private suspend fun extractAllHyperServers(
        hyperUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val cleanUrl = hyperUrl.split("?").first()
            val pageResponse = app.get(cleanUrl, headers = headers)
            val html = pageResponse.text

            // Extract CSRF token
            val csrf = Regex("csrf:\\s*[\"']([^\"']+)[\"']").find(html)?.groupValues?.get(1)

            // Extract server IDs and names
            val servers = mutableListOf<Pair<String, String>>()
            val serverRegex = Regex("id:\\s*[\"'](\\d+)[\"'],\\s*name:\\s*[\"']([^\"']+)[\"']")
            serverRegex.findAll(html).forEach { match ->
                servers.add(Pair(match.groupValues[1], match.groupValues[2]))
            }

            if (servers.isEmpty()) {
                safeLoadExtractor(cleanUrl, subtitleCallback, callback)
                return
            }

            // Extract the link API route
            val linkRoute = Regex("link:\\s*[\"']([^\"']+)[\"']").find(html)?.groupValues?.get(1)
            if (linkRoute.isNullOrBlank()) {
                safeLoadExtractor(cleanUrl, subtitleCallback, callback)
                return
            }

            // Try ALL servers in parallel
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

            // Try JSON body first
            var linkData: HyperLinkResponse? = null

            try {
                val resp = app.post(
                    linkRoute,
                    headers = postHeaders + mapOf("Content-Type" to "application/json"),
                    data = mapOf("server_link_id" to serverId)
                )
                linkData = resp.parsedSafe<HyperLinkResponse>()
            } catch (_: Exception) {}

            // If JSON failed, try form-encoded
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

            val watchUrl = linkData.watchUrl

            // Route the URL to the right extractor
            routeAndExtract(watchUrl, serverName, subtitleCallback, callback)
        } catch (_: Exception) {}
    }

    // ==================== URL ROUTER ====================

    /**
     * Route a watch URL to the correct extractor, handling wrappers like strema.top
     */
    private suspend fun routeAndExtract(
        url: String, serverName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // If strema.top wrapper, extract the inner URL first
        if (url.contains("strema.top")) {
            val innerUrlEncoded = Regex("[?&]id=(https?://[^&]+)").find(url)?.groupValues?.get(1)
            if (!innerUrlEncoded.isNullOrBlank()) {
                val innerUrl = java.net.URLDecoder.decode(innerUrlEncoded, "UTF-8")
                extractFromHost(innerUrl, serverName, subtitleCallback, callback)
                safeLoadExtractor(innerUrl, subtitleCallback, callback)
            } else {
                try {
                    val stremaDoc = app.get(url, headers = headers).document
                    val formAction = stremaDoc.selectFirst("form")?.attr("action")
                    if (!formAction.isNullOrBlank()) {
                        val redirectUrl = if (formAction.startsWith("http")) formAction else "https://strema.top$formAction"
                        extractFromHost(redirectUrl, serverName, subtitleCallback, callback)
                        safeLoadExtractor(redirectUrl, subtitleCallback, callback)
                    }
                    stremaDoc.select("iframe[src]").forEach { iframe ->
                        val src = iframe.attr("src")
                        if (src.isNotBlank() && src.startsWith("http")) {
                            extractFromHost(src, serverName, subtitleCallback, callback)
                            safeLoadExtractor(src, subtitleCallback, callback)
                        }
                    }
                } catch (_: Exception) {}
            }
            return
        }

        // Direct URL: try custom extractor then CloudStream built-in
        extractFromHost(url, serverName, subtitleCallback, callback)
        safeLoadExtractor(url, subtitleCallback, callback)
    }

    // ==================== HOST ROUTER ====================

    /**
     * Route URL to custom extractors based on host
     */
    private suspend fun extractFromHost(
        url: String, serverName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        when {
            url.contains("lulustream.com") || url.contains("luluvdo.com") -> {
                extractLulustream(url, serverName, callback)
            }
            url.contains("uqload") -> {
                extractUqload(url, serverName, callback)
            }
            url.contains("hgplaycdn.com") || url.contains("streamhg") -> {
                extractGenericEmbed(url, serverName, callback)
            }
            url.contains("darkibox.com") -> {
                extractGenericEmbed(url, serverName, callback)
            }
            url.contains("krakenfiles.com") -> {
                extractGenericEmbed(url, serverName, callback)
            }
            url.contains("goodstream") -> {
                extractGenericEmbed(url, serverName, callback)
            }
            url.contains("earnvids") || url.contains("earnvidsapi") -> {
                extractGenericEmbed(url, serverName, callback)
            }
        }
    }

    // ==================== GENERIC EMBED EXTRACTOR ====================

    /**
     * Generic embed extractor - looks for any video URL (m3u8, mp4) in the page HTML
     */
    private suspend fun extractGenericEmbed(
        url: String, serverName: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val html = app.get(url, headers = headers).text

            val packedUrl = extractPackedVideoUrl(html)
            if (packedUrl != null) {
                callback.invoke(
                    newExtractorLink(
                        source = serverName,
                        name = "$name - $serverName",
                        url = packedUrl,
                        type = INFER_TYPE
                    ) {
                        this.referer = url
                        this.quality = Qualities.Unknown.value
                    }
                )
                return
            }

            val m3u8Match = Regex("(https?://[^\\s\"'<>]+\\.m3u8[^\\s\"'<>]*)").find(html)
            if (m3u8Match != null) {
                callback.invoke(
                    newExtractorLink(
                        source = serverName,
                        name = "$name - $serverName",
                        url = m3u8Match.groupValues[1],
                        type = INFER_TYPE
                    ) {
                        this.referer = url
                        this.quality = Qualities.Unknown.value
                    }
                )
                return
            }

            val mp4Match = Regex("(https?://[^\\s\"'<>]+\\.mp4[^\\s\"'<>]*)").find(html)
            if (mp4Match != null) {
                callback.invoke(
                    newExtractorLink(
                        source = serverName,
                        name = "$name - $serverName",
                        url = mp4Match.groupValues[1],
                        type = INFER_TYPE
                    ) {
                        this.referer = url
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

    // ==================== LULUSTREAM EXTRACTOR ====================

    private suspend fun extractLulustream(url: String, serverName: String, callback: (ExtractorLink) -> Unit) {
        try {
            val response = app.get(url, headers = headers)
            val html = response.text

            val packedUrl = extractPackedVideoUrl(html)
            if (packedUrl != null) {
                callback.invoke(
                    newExtractorLink(
                        source = serverName,
                        name = "$name - $serverName",
                        url = packedUrl,
                        type = INFER_TYPE
                    ) {
                        this.referer = url
                        this.quality = Qualities.Unknown.value
                    }
                )
                return
            }

            val directM3u8 = Regex("(https?://[^\\s\"'<>]+\\.m3u8[^\\s\"'<>]*)").find(html)
            if (directM3u8 != null) {
                callback.invoke(
                    newExtractorLink(
                        source = serverName,
                        name = "$name - $serverName",
                        url = directM3u8.groupValues[1],
                        type = INFER_TYPE
                    ) {
                        this.referer = url
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        } catch (_: Exception) {}
    }

    // ==================== PACKED JS DECODER ====================

    /**
     * Shared helper: try to find and decode packed JS in HTML, returning a video URL.
     * Uses step-by-step extraction instead of one giant regex, which fails on long payloads.
     * Handles both standard Dean Edwards format and the .split('|') variant.
     */
    private fun extractPackedVideoUrl(html: String): String? {
        // Find the start of packed JS
        val startMarker = "eval(function(p,a,c,k,e,d)"
        val startIdx = html.indexOf(startMarker)
        if (startIdx < 0) return null

        // Find the script block end
        val scriptEnd = html.indexOf("</script>", startIdx)
        val searchEnd = if (scriptEnd > startIdx) scriptEnd else html.length
        val snippet = html.substring(startIdx, searchEnd)

        // Find the function body closing: }('
        val bodyEndMarker = "}('"
        val bodyEndIdx = snippet.indexOf(bodyEndMarker)
        if (bodyEndIdx < 0) return null

        val afterBody = snippet.substring(bodyEndIdx + bodyEndMarker.length)

        // Extract: PAYLOAD',BASE,COUNT,'DICT'.split('|')))
        // Strategy: find the numbers after the payload
        val argsPattern = Regex(",(\\d+),(\\d+),")
        val argsMatch = argsPattern.find(afterBody) ?: return null

        val payloadEnd = argsMatch.range.first
        val payloadRaw = afterBody.substring(0, payloadEnd)
        val base = argsMatch.groupValues[1].toIntOrNull() ?: return null
        val count = argsMatch.groupValues[2].toIntOrNull() ?: return null

        // Extract payload string (strip surrounding quotes if present)
        val payload = payloadRaw
            .removeSurrounding("'")
            .replace("\\'", "'")
            .replace("\\\\", "\\")

        // Now extract the dict string after the count number
        val afterCount = afterBody.substring(argsMatch.range.last + 1)

        // Dict is in single quotes: 'DICT' followed by .split('|') or )
        val dictStart = afterCount.indexOf("'")
        if (dictStart < 0) return null

        val afterDictStart = afterCount.substring(dictStart + 1)

        // Find closing quote - look for .split pattern first (more common)
        val splitMarker = "'.split('"
        val dictEnd = afterDictStart.indexOf(splitMarker)
        val dictStr = if (dictEnd >= 0) {
            afterDictStart.substring(0, dictEnd)
        } else {
            // Try standard Dean Edwards without .split: ')
            val standardEnd = afterDictStart.indexOf("')")
            if (standardEnd < 0) return null
            afterDictStart.substring(0, standardEnd)
        }

        val cleanDict = dictStr
            .replace("\\'", "'")
            .replace("\\\\", "\\")

        return tryDecodePacked(payload, base, count, cleanDict)
    }

    private fun tryDecodePacked(payload: String, base: Int, count: Int, dictStr: String): String? {
        return try {
            val decoded = decodePackedJs(payload, base, count, dictStr)

            // Try multiple video URL patterns in the decoded content
            // Using regular strings with escaping to avoid copy-paste encoding issues
            val p1 = Regex("file:\\s*[\"'](https?://[^\"']+\\.m3u8[^\"']*)")
            val p2 = Regex("file:\\s*[\"'](https?://[^\"']+)[\"']")
            val p3 = Regex("sources:\\s*\\[\\{file:\\s*[\"'](https?://[^\"']+)[\"']")
            val p4 = Regex("[\"']src[\"']:\\s*[\"'](https?://[^\"']+\\.m3u8[^\"']*)")

            val videoPatterns = listOf(p1, p2, p3, p4)

            for (pattern in videoPatterns) {
                val match = pattern.find(decoded)
                if (match != null) {
                    return match.groupValues[1]
                }
            }
            null
        } catch (_: Exception) { null }
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

    private suspend fun extractUqload(url: String, serverName: String, callback: (ExtractorLink) -> Unit) {
        try {
            val html = app.get(url, headers = headers).text

            // Pattern: sources:[{file:"URL"}]  or  sources:[{file:'URL'}
            val mp4Match = Regex("sources:\\s*\\[\\{file:\\s*[\"'](https?://[^\"']+)[\"']").find(html)

            if (mp4Match != null) {
                callback.invoke(
                    newExtractorLink(
                        source = serverName,
                        name = "$name - $serverName",
                        url = mp4Match.groupValues[1],
                        type = INFER_TYPE
                    ) {
                        this.referer = url
                        this.quality = Qualities.Unknown.value
                    }
                )
                return
            }

            val packedUrl = extractPackedVideoUrl(html)
            if (packedUrl != null) {
                callback.invoke(
                    newExtractorLink(
                        source = serverName,
                        name = "$name - $serverName",
                        url = packedUrl,
                        type = INFER_TYPE
                    ) {
                        this.referer = url
                        this.quality = Qualities.Unknown.value
                    }
                )
                return
            }

            val altMatch = Regex("[\"'](https?://[^\"']*\\.mp4[^\"']*)").find(html)
            if (altMatch != null) {
                callback.invoke(
                    newExtractorLink(
                        source = serverName,
                        name = "$name - $serverName",
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