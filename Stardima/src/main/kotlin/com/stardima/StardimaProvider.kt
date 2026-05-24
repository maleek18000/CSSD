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
                        if (mapped.isEmpty()) break
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
        val actualUrl = if (url.contains("strema.top")) {
            val innerUrlRaw = Regex("[?&]id=([^&]+)").find(url)?.groupValues?.get(1)
            val innerUrl = innerUrlRaw?.let { java.net.URLDecoder.decode(it, "UTF-8") }
            if (!innerUrl.isNullOrBlank() && innerUrl.startsWith("http")) {
                innerUrl
            } else {
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
            url.contains("hgplaycdn.com") || url.contains("streamhg") -> {
                extractPackedEmbed(url, serverName, callback); true
            }
            url.contains("darkibox.com") -> {
                extractPackedEmbed(url, serverName, callback); true
            }
            url.contains("krakenfiles.com") -> {
                extractPackedEmbed(url, serverName, callback); true
            }
            url.contains("goodstream") -> {
                extractPackedEmbed(url, serverName, callback); true
            }
            url.contains("earnvids") || url.contains("earnvidsapi") -> {
                extractPackedEmbed(url, serverName, callback); true
            }
            else -> false
        }
    }

    // ==================== PACKED EMBED EXTRACTOR ====================

    private suspend fun extractPackedEmbed(
        url: String, serverName: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val html = app.get(url, headers = headers).text

            val decoded = decodePackedFromHtml(html)

            val videoUrl = decoded?.let { extractVideoUrlFromDecoded(it) }
                ?: Regex("""(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)""").find(html)?.groupValues?.get(1)
                ?: Regex("""(https?://[^\s"'<>]+\.mp4[^\s"'<>]*)""").find(html)?.groupValues?.get(1)

            if (videoUrl != null) {
                decoded?.let { callViewTracking(it, url) }

                val isM3u8 = videoUrl.contains(".m3u8")
                if (isM3u8) {
                    try {
                        M3u8Helper.generateM3u8(
                            source = serverName,
                            streamUrl = videoUrl,
                            referer = url,
                            headers = mapOf(
                                "Referer" to url,
                                "User-Agent" to headers["User-Agent"]!!
                            ),
                            name = serverName
                        ).forEach { link ->
                            callback.invoke(link)
                        }
                    } catch (_: Exception) {
                        callback.invoke(
                            newExtractorLink(
                                source = serverName,
                                name = serverName,
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
                            name = serverName,
                            url = videoUrl,
                            type = INFER_TYPE
                        ) {
                            this.referer = url
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }
            }
        } catch (_: Exception) {}
    }

    private suspend fun callViewTracking(decoded: String, embedUrl: String) {
        try {
            val viewPattern = Regex("""/dl\?op=view[^"'\s]+""")
            val viewMatch = viewPattern.find(decoded) ?: return
            var viewPath = viewMatch.groupValues[0]

            viewPath = viewPath.trimEnd('&', '\'', '"', ',', ')')
            viewPath = viewPath.replace("adb=", "adb=0")

            val baseUrl = Regex("(https?://[^/]+)").find(embedUrl)?.groupValues?.get(1) ?: return
            val fullViewUrl = baseUrl + viewPath

            app.get(fullViewUrl, headers = headers + mapOf("Referer" to embedUrl))
        } catch (_: Exception) {}
    }

    private fun extractVideoUrlFromDecoded(decoded: String): String? {
        val patterns = listOf(
            Regex("file:\\s*[\"'](https?://[^\"']+\\.m3u8[^\"']*)"),
            Regex("file:\\s*[\"'](https?://[^\"']+)[\"']"),
            Regex("sources:\\s*\\[\\{file:\\s*[\"'](https?://[^\"']+)[\"']"),
            Regex("[\"']src[\"']:\\s*[\"'](https?://[^\"']+\\.m3u8[^\"']*)")
        )
        for (pattern in patterns) {
            val match = pattern.find(decoded)
            if (match != null) return match.groupValues[1]
        }
        return null
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

            val argsPattern = Regex(",(\\d+),(\\d+),")
            val argsMatch = argsPattern.find(afterBody) ?: return null

            val payloadEnd = argsMatch.range.first
            val payloadRaw = afterBody.substring(0, payloadEnd)
            val base = argsMatch.groupValues[1].toIntOrNull() ?: return null
            val count = argsMatch.groupValues[2].toIntOrNull() ?: return null

            val payload = payloadRaw
                .removeSurrounding("'")
                .replace("\\'", "'")
                .replace("\\\\", "\\")

            val afterCount = afterBody.substring(argsMatch.range.last + 1)

            val dictStart = afterCount.indexOf("'")
            if (dictStart < 0) return null

            val afterDictStart = afterCount.substring(dictStart + 1)

            val splitMarker = "'.split('"
            val dictEnd = afterDictStart.indexOf(splitMarker)
            val dictStr = if (dictEnd >= 0) {
                afterDictStart.substring(0, dictEnd)
            } else {
                val standardEnd = afterDictStart.indexOf("')")
                if (standardEnd < 0) return null
                afterDictStart.substring(0, standardEnd)
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