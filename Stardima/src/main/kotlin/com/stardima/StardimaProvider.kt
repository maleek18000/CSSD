package com.stardima

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class StardimaProvider : MainAPI() {
    override var mainUrl = "https://www.stardima.com"
    override var name = "StarDima"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Cartoon,
        TvType.Anime
    )

    // ==================== Helpers ====================

    private fun String.toAbsoluteUrl(): String {
        if (this.isBlank()) return ""
        return when {
            this.startsWith("http") -> this
            this.startsWith("//") -> "https:$this"
            this.startsWith("/") -> "$mainUrl$this"
            else -> "$mainUrl/$this"
        }
    }

    /**
     * Parse a card element into a SearchResponse.
     */
    private fun Element.toSearchResponse(): SearchResponse? {
        val href = this.selectFirst("a[href]")?.attr("href")?.toAbsoluteUrl() ?: return null
        val isMovie = href.contains("/movie/")
        val title = this.selectFirst("h3")?.text()?.trim() ?: return null
        val posterUrl = this.selectFirst("img")?.let { img ->
            (img.attr("data-src").ifBlank { img.attr("src") }).toAbsoluteUrl()
        }

        val typeBadge = this.select("span").firstOrNull {
            val t = it.text().trim()
            t == "\u0641\u064A\u0644\u0645" || t == "\u0645\u0633\u0644\u0633\u0644"
        }?.text()?.trim()
        val tvType = when (typeBadge) {
            "\u0641\u064A\u0644\u0645" -> TvType.Movie
            else -> if (isMovie) TvType.Movie else TvType.TvSeries
        }

        return if (tvType == TvType.Movie) {
            newMovieSearchResponse(title, href, tvType) {
                this.posterUrl = posterUrl
            }
        } else {
            newTvSeriesSearchResponse(title, href, tvType) {
                this.posterUrl = posterUrl
            }
        }
    }

    /**
     * Parse all cards from a document.
     */
    private fun parseCards(document: org.jsoup.nodes.Document): List<SearchResponse> {
        val slides = document.select("div.embla__slide")
        if (slides.isNotEmpty()) {
            return slides.mapNotNull { slide ->
                val card = slide.selectFirst("div[class*=\"group/item\"]") ?: slide
                card.toSearchResponse()
            }.distinctBy { it.url }
        }

        val cards = document.select("div[class*=\"aspect-\"][class*=\"2/3\"]")
        if (cards.isNotEmpty()) {
            return cards.mapNotNull { card ->
                card.toSearchResponse()
            }.distinctBy { it.url }
        }

        val groupItems = document.select("div[class*=\"group/item\"]")
        if (groupItems.isNotEmpty()) {
            return groupItems.mapNotNull { card ->
                card.toSearchResponse()
            }.distinctBy { it.url }
        }

        return emptyList()
    }

    /**
     * Decode Dean Edwards packed JavaScript and extract video URL.
     * The packed format is: eval(function(p,a,c,k,e,d){...}('encoded',base,count,'dict'.split('|')))
     * After decoding, we look for m3u8/mp4 URLs in sources:[{file:"URL"}] or file:"URL" patterns.
     */
    private fun decodePackedJs(html: String): String? {
        try {
            // Find the packed JS script
            val packedMatch = Regex(
                """eval\(function\(p,a,c,k,e,d\)\{.*?\}""",
                RegexOption.DOT_MATCHES_ALL
            ).find(html) ?: return null

            val packedBlock = packedMatch.value

            // Extract the dictionary string (last single-quoted string before .split('|'))
            val splitIdx = packedBlock.indexOf(".split('|')".also {
                if (it.isEmpty()) return null
            })
            if (splitIdx < 0) return null

            // Walk backwards to find the dictionary string boundaries
            var endOfDict = splitIdx - 1
            while (endOfDict >= 0 && packedBlock[endOfDict] != '\'') endOfDict--
            val dictEnd = endOfDict
            var j = dictEnd - 1
            while (j >= 0 && packedBlock[j] != '\'') j--
            val dictStart = j
            if (dictStart < 0 || dictEnd <= dictStart) return null
            val dictStr = packedBlock.substring(dictStart + 1, dictEnd)

            // Extract encoded string (first quoted string after function body)
            val funcEndMatch = Regex("""return\s+p\s*\}""").find(packedBlock) ?: return null
            val afterFunc = packedBlock.substring(funcEndMatch.range.last + 1).trim().trimStart('(')

            // Find the first single-quoted string
            val firstQuote = afterFunc.indexOf('\'')
            if (firstQuote < 0) return null
            var endQuote = firstQuote + 1
            while (endQuote < afterFunc.length) {
                if (afterFunc[endQuote] == '\\' && endQuote + 1 < afterFunc.length) {
                    endQuote += 2
                } else if (afterFunc[endQuote] == '\'') {
                    break
                } else {
                    endQuote++
                }
            }
            val encodedStr = afterFunc.substring(firstQuote + 1, endQuote)

            // Extract base and count from the remaining text
            val afterEncoded = afterFunc.substring(endQuote + 1).trim().trimStart(',')
            val nums = Regex("""(\d+)\s*,\s*(\d+)""").find(afterEncoded) ?: return null
            val base = nums.groupValues[1].toInt()
            val count = nums.groupValues[2].toInt()

            if (base < 2 || base > 36 || count <= 0) return null

            // Build dictionary
            val dictionary = dictStr.split('|').toMutableList()
            while (dictionary.size < count) dictionary.add("")

            // Base conversion function
            fun baseConvert(num: Int, base: Int): String {
                val chars = "0123456789abcdefghijklmnopqrstuvwxyz"
                if (num == 0) return "0"
                var n = num
                var result = ""
                while (n > 0) {
                    result = chars[n % base] + result
                    n /= base
                }
                return result
            }

            // Decode: replace each base-encoded token with dictionary value
            var decoded = encodedStr
            for (idx in count - 1 downTo 0) {
                val value = dictionary[idx]
                if (value.isNotEmpty()) {
                    val token = baseConvert(idx, base)
                    if (token.isNotEmpty()) {
                        decoded = decoded.replace(
                            Regex("""\b${Regex.escape(token)}\b"""),
                            value
                        )
                    }
                }
            }

            return decoded
        } catch (_: Exception) {
            return null
        }
    }

    /**
     * Extract video URL from decoded JavaScript.
     * Looks for m3u8 or mp4 URLs in the decoded player setup.
     */
    private fun extractVideoUrlFromJs(decodedJs: String): String? {
        // Pattern 1: sources:[{file:"URL"}]
        val sourceFilePattern = Regex("""file\s*:\s*["']([^"']+\.(?:m3u8|mp4)[^"']*)["']""")
        sourceFilePattern.find(decodedJs)?.let {
            return it.groupValues[1]
        }

        // Pattern 2: Direct URL in decoded JS
        val urlPattern = Regex("""https?://[^\s"'<>,]+\.(?:m3u8|mp4)[^\s"'<>,]*""")
        urlPattern.find(decodedJs)?.let {
            return it.groupValues[0]
        }

        return null
    }

    // ==================== Main Page ====================

    override val mainPage = mainPageOf(
        "$mainUrl/" to "\u0627\u0644\u0631\u0626\u064A\u0633\u064A\u0629",
        "$mainUrl/newrelases" to "\u0627\u0644\u0645\u0636\u0627\u0641 \u062D\u062F\u064A\u062B\u0627\u064B",
        "$mainUrl/mosalsalat" to "\u0645\u0633\u0644\u0633\u0644\u0627\u062A",
        "$mainUrl/aflam" to "\u0623\u0641\u0644\u0627\u0645",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) {
            "${request.data}?page=$page"
        } else {
            request.data
        }

        val document = app.get(url).document
        val items = parseCards(document)

        val hasNext = document.select("a[href*=\"page=${page + 1}\"]").isNotEmpty() ||
                document.select("nav ul.pagination li:last-child a").isNotEmpty()

        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    // ==================== Search ====================

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${query}"
        val document = app.get(url).document
        return parseCards(document)
    }

    // ==================== Data Classes for API Responses ====================

    data class SeasonEpisodesResponse(
        @JsonProperty("episodes") val episodes: List<EpisodeData>?,
        @JsonProperty("series_id") val seriesId: String?
    )

    data class EpisodeData(
        @JsonProperty("id") val id: Int,
        @JsonProperty("episode_number") val episodeNumber: Int?,
        @JsonProperty("title") val title: String?,
        @JsonProperty("watch_url") val watchUrl: String?,
        @JsonProperty("is_exclusive") val isExclusive: Int?
    )

    data class EpisodeDetailResponse(
        @JsonProperty("episode") val episode: EpisodeDetail?,
        @JsonProperty("season") val season: SeasonDetail?,
        @JsonProperty("series") val series: SeriesDetail?
    )

    data class EpisodeDetail(
        @JsonProperty("id") val id: Int,
        @JsonProperty("title") val title: String?,
        @JsonProperty("episode_number") val episodeNumber: Int?,
        @JsonProperty("watch_url") val watchUrl: String?,
        @JsonProperty("can_watch") val canWatch: Boolean?,
        @JsonProperty("reason") val reason: String?
    )

    data class SeasonDetail(
        @JsonProperty("number") val number: Int?
    )

    data class SeriesDetail(
        @JsonProperty("title") val title: String?,
        @JsonProperty("slug") val slug: String?
    )

    // ==================== Load (Detail Page) ====================

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val isMovie = url.contains("/movie/")

        val heroSection = document.selectFirst("section[class*=\"relative\"]")
            ?: document

        val title = heroSection.selectFirst("h1")?.text()?.trim()
            ?.takeIf { it != "STARDIMA" && !it.contains("\u062A\u0633\u062C\u064A\u0644 \u0627\u0644\u062F\u062E\u0648\u0644") }
            ?: return null

        val poster = heroSection.selectFirst("img[src*=\"tmdb\"]")?.attr("src")
            ?: heroSection.selectFirst("img[alt*=\"Poster\"]")?.attr("src")?.toAbsoluteUrl()
        val description = heroSection.selectFirst("p[class*=\"line-clamp\"]")?.text()?.trim()
        val year = heroSection.selectFirst("div.info-item")?.text()?.trim()?.toIntOrNull()

        if (isMovie) {
            val playUrl = document.selectFirst("a[href*=\"/play/\"]")?.attr("href")?.toAbsoluteUrl()
                ?: return null

            return newMovieLoadResponse(title, url, TvType.Movie, playUrl) {
                this.posterUrl = poster?.takeIf { it.isNotBlank() }
                this.plot = description
                this.year = year
            }
        } else {
            val episodes = mutableListOf<Episode>()

            val episodesContainer = document.selectFirst("#episodes-list-container")
            if (episodesContainer != null) {
                loadAllSeasons(document, episodesContainer, episodes)
            }

            if (episodes.isEmpty()) {
                val playLink = document.selectFirst("a[href*=\"/play/\"]")?.attr("href")?.toAbsoluteUrl()
                if (playLink != null) {
                    val playDoc = app.get(playLink).document
                    val playContainer = playDoc.selectFirst("#episodes-list-container")
                    if (playContainer != null) {
                        loadAllSeasons(playDoc, playContainer, episodes)
                    }
                }
            }

            if (episodes.isEmpty()) {
                val seasonElements = document.select("[data-season-id]")
                if (seasonElements.isNotEmpty()) {
                    for (seasonEl in seasonElements) {
                        val seasonId = seasonEl.attr("data-season-id")
                        val seasonNumber = seasonEl.attr("data-season-number")
                            .replace("S", "").toIntOrNull() ?: continue
                        val seasonEpisodes = loadSeasonEpisodes(seasonId, seasonNumber)
                        episodes.addAll(seasonEpisodes)
                    }
                }
            }

            if (episodes.isNotEmpty()) {
                return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = poster?.takeIf { it.isNotBlank() }
                    this.plot = description
                    this.year = year
                }
            } else {
                val playLink = document.selectFirst("a[href*=\"/play/\"]")?.attr("href")?.toAbsoluteUrl()
                    ?: url
                return newMovieLoadResponse(title, url, TvType.Movie, playLink) {
                    this.posterUrl = poster?.takeIf { it.isNotBlank() }
                    this.plot = description
                    this.year = year
                }
            }
        }
    }

    private suspend fun loadAllSeasons(
        document: org.jsoup.nodes.Document,
        episodesContainer: Element,
        episodes: MutableList<Episode>
    ) {
        val seasonItems = document.select(".season-item")

        if (seasonItems.isNotEmpty()) {
            for (seasonItem in seasonItems) {
                val seasonId = seasonItem.attr("data-season-id")
                val seasonNumber = seasonItem.attr("data-season-number")
                    .replace("S", "").toIntOrNull() ?: continue
                val seasonEpisodes = loadSeasonEpisodes(seasonId, seasonNumber)
                episodes.addAll(seasonEpisodes)
            }
        } else {
            val initialSeasonId = episodesContainer.attr("data-initial-season-id")
            if (initialSeasonId.isNotBlank()) {
                val seasonEpisodes = loadSeasonEpisodes(initialSeasonId, 1)
                episodes.addAll(seasonEpisodes)
            }
        }
    }

    private suspend fun loadSeasonEpisodes(seasonId: String, seasonNumber: Int): List<Episode> {
        val episodes = mutableListOf<Episode>()
        try {
            val response = app.get(
                "$mainUrl/series/season/$seasonId",
                headers = mapOf("X-Requested-With" to "XMLHttpRequest")
            ).parsedSafe<SeasonEpisodesResponse>()

            response?.episodes?.forEach { ep ->
                val episodeId = ep.id.toString()
                val epTitle = ep.title?.trim()
                    ?: "\u0627\u0644\u062D\u0644\u0642\u0629 ${ep.episodeNumber ?: "?"}"
                val epNum = ep.episodeNumber

                val seriesId = response.seriesId ?: ""
                val playUrl = "$mainUrl/tvshow/$seriesId/play/$episodeId"

                episodes.add(newEpisode(playUrl) {
                    name = epTitle
                    episode = epNum
                    season = seasonNumber
                })
            }
        } catch (_: Exception) { }
        return episodes
    }

    // ==================== Load Links (Video Extraction) ====================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val csrfToken = document.selectFirst("meta[name=csrf-token]")?.attr("content") ?: ""

        var hyperwatchingUrl: String? = null

        // Strategy 1: Find iframe in the player container (initial page load)
        val iframeSrc = document.selectFirst("#video-player-container iframe")?.attr("src")
            ?.toAbsoluteUrl()
        if (!iframeSrc.isNullOrBlank() && iframeSrc.contains("hyperwatching")) {
            hyperwatchingUrl = iframeSrc
        }

        // Strategy 2: For TV episodes, use the API to get the watch_url
        if (hyperwatchingUrl.isNullOrBlank() && data.contains("/tvshow/") && data.contains("/play/")) {
            val episodeId = data.substringAfterLast("/")
            try {
                val response = app.get(
                    "$mainUrl/series/episode/$episodeId",
                    headers = mapOf(
                        "X-Requested-With" to "XMLHttpRequest",
                        "X-CSRF-TOKEN" to csrfToken
                    )
                ).parsedSafe<EpisodeDetailResponse>()

                val watchUrl = response?.episode?.watchUrl
                if (!watchUrl.isNullOrBlank() && watchUrl.contains("hyperwatching")) {
                    hyperwatchingUrl = watchUrl
                } else if (!watchUrl.isNullOrBlank()) {
                    // Direct non-hyperwatching URL - route it directly
                    routeWatchUrl(watchUrl, "\u0633\u064A\u0631\u0641\u0631", subtitleCallback, callback)
                }
            } catch (_: Exception) { }
        }

        // Strategy 3: Search for any iframe with hyperwatching
        if (hyperwatchingUrl.isNullOrBlank()) {
            hyperwatchingUrl = document.select("iframe[src]")
                .mapNotNull { it.attr("src").toAbsoluteUrl() }
                .firstOrNull { it.contains("hyperwatching") }
        }

        // Strategy 4: Check JSON-LD for embedUrl
        if (hyperwatchingUrl.isNullOrBlank()) {
            val jsonLdScript = document.selectFirst("script[type=\"application/ld+json\"]")?.data()
            if (!jsonLdScript.isNullOrBlank()) {
                val embedUrlMatch = Regex("\"embedUrl\"\\s*:\\s*\"([^\"]+)\"").find(jsonLdScript)
                val embedUrl = embedUrlMatch?.groupValues?.get(1)
                if (!embedUrl.isNullOrBlank() && embedUrl.contains("hyperwatching")) {
                    hyperwatchingUrl = embedUrl
                }
            }
        }

        // If we found a hyperwatching URL, extract all servers from it
        if (!hyperwatchingUrl.isNullOrBlank()) {
            extractFromHyperwatching(hyperwatchingUrl, callback)
        }

        return true
    }

    /**
     * Extract video links from a hyperwatching.com iframe page.
     *
     * The hyperwatching page contains a config object with:
     * - servers: [{id: "123", name: "Lulustream"}, ...]
     * - routes: {link: "https://hyperwatching.com/api/videos/ID/link", ...}
     * - csrf: "token"
     *
     * For each server, we POST to the link route to get a watch_url,
     * then route that URL to the appropriate extractor.
     */
    private suspend fun extractFromHyperwatching(
        iframeUrl: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val response = app.get(iframeUrl, referer = mainUrl).text

            // Parse CSRF token from config
            val csrf = Regex("""csrf\s*:\s*"([^"]+)"""").find(response)?.groupValues?.get(1)
                ?: return

            // Parse link route from config
            val linkRoute = Regex("""link\s*:\s*"([^"]+)"""").find(response)?.groupValues?.get(1)
                ?: return

            // Parse all servers from config
            val servers = Regex("""\{\s*id\s*:\s*"(\d+)"\s*,\s*name\s*:\s*"([^"]+)"""")
                .findAll(response)
                .map { Pair(it.groupValues[1], it.groupValues[2]) }
                .toList()

            if (servers.isEmpty()) return

            // Try each server
            for ((serverId, serverName) in servers) {
                try {
                    val linkResponse = app.post(
                        linkRoute,
                        data = mapOf("server_link_id" to serverId),
                        headers = mapOf(
                            "X-CSRF-TOKEN" to csrf,
                            "Content-Type" to "application/json"
                        )
                    ).text

                    // Check if link request was successful
                    val status = Regex(""""status"\s*:\s*"([^"]+)"""").find(linkResponse)?.groupValues?.get(1)
                    if (status != "completed") continue

                    // Extract the watch_url (may contain escaped slashes)
                    val watchUrl = Regex(""""watch_url"\s*:\s*"([^"]+)"""").find(linkResponse)
                        ?.groupValues?.get(1)?.replace("\\/", "/")
                        ?: continue

                    // Route the watch URL to the appropriate extractor
                    routeWatchUrl(watchUrl, serverName, subtitleCallback = { }, callback)
                } catch (_: Exception) {
                    // Skip failed servers, try next one
                }
            }
        } catch (_: Exception) { }
    }

    /**
     * Route a watch_url to the appropriate extractor based on the domain.
     */
    private suspend fun routeWatchUrl(
        watchUrl: String,
        serverName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        when {
            // strema.top wraps lulustream - extract the inner URL
            watchUrl.contains("strema.top") -> extractViaStremaTop(watchUrl, serverName, callback)

            // Direct lulustream embed
            watchUrl.contains("lulustream.com") || watchUrl.contains("luluvdo.com") ->
                extractLulustream(watchUrl, serverName, callback)

            // Uqload embed
            watchUrl.contains("uqload") -> extractUqload(watchUrl, serverName, callback)

            // Try CloudStream's built-in extractors as fallback
            else -> {
                try {
                    loadExtractor(watchUrl, mainUrl, subtitleCallback, callback)
                } catch (_: Exception) { }
            }
        }
    }

    /**
     * Extract video from strema.top redirect wrapper.
     * strema.top/embed2/?id=ENCODED_URL → the id parameter contains the actual embed URL.
     * We can either follow the redirect or just extract the id parameter directly.
     */
    private suspend fun extractViaStremaTop(
        stremaUrl: String,
        serverName: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            // Method 1: Extract the id parameter directly (it's the actual embed URL)
            val idParam = Regex("""[?&]id=([^&]+)""").find(stremaUrl)?.groupValues?.get(1)
            if (!idParam.isNullOrBlank()) {
                val actualUrl = java.net.URLDecoder.decode(idParam, "UTF-8")
                if (actualUrl.isNotBlank()) {
                    routeWatchUrl(actualUrl, serverName, subtitleCallback = { }, callback)
                    return
                }
            }

            // Method 2: Fetch strema.top page and follow the form redirect
            val stremaResponse = app.get(stremaUrl, referer = mainUrl).text
            val formAction = Regex("""<form[^>]*action="([^"]+)"""").find(stremaResponse)?.groupValues?.get(1)
            if (!formAction.isNullOrBlank()) {
                val redirectUrl = formAction.replace("&amp;", "&")
                val embedResponse = app.post(redirectUrl, referer = stremaUrl).text

                // Try to find video URL in the embed page
                val decoded = decodePackedJs(embedResponse)
                if (decoded != null) {
                    val videoUrl = extractVideoUrlFromJs(decoded)
                    if (videoUrl != null) {
                        callback.invoke(
                            newExtractorLink(
                                source = "$name - $serverName",
                                name = "$name - $serverName",
                                url = videoUrl,
                                type = INFER_TYPE
                            ) {
                                this.referer = "https://strema.top/"
                                this.quality = Qualities.Unknown.value
                            }
                        )
                    }
                }
            }
        } catch (_: Exception) { }
    }

    /**
     * Extract video URL from lulustream.com embed page.
     * Lulustream uses Dean Edwards packed JavaScript containing the m3u8 URL.
     * The m3u8 URL requires a Referer header to play.
     */
    private suspend fun extractLulustream(
        embedUrl: String,
        serverName: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val response = app.get(embedUrl, referer = "https://strema.top/").text

            // Decode the packed JavaScript
            val decoded = decodePackedJs(response) ?: return

            // Extract the m3u8 URL from the decoded JavaScript
            val videoUrl = extractVideoUrlFromJs(decoded) ?: return

            // Determine the appropriate referer based on the video URL domain
            val referer = when {
                videoUrl.contains("tnmr.org") || videoUrl.contains("lulucdn") -> "https://lulustream.com/"
                else -> "https://lulustream.com/"
            }

            callback.invoke(
                newExtractorLink(
                    source = "$name - $serverName",
                    name = "$name - $serverName",
                    url = videoUrl,
                    type = INFER_TYPE
                ) {
                    this.referer = referer
                    this.quality = Qualities.Unknown.value
                }
            )
        } catch (_: Exception) { }
    }

    /**
     * Extract video URL from uqload embed page.
     * Uqload uses Dean Edwards packed JavaScript containing the m3u8 URL.
     */
    private suspend fun extractUqload(
        embedUrl: String,
        serverName: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val response = app.get(embedUrl, referer = mainUrl).text

            // Decode the packed JavaScript
            val decoded = decodePackedJs(response) ?: return

            // Extract the m3u8 URL from the decoded JavaScript
            val videoUrl = extractVideoUrlFromJs(decoded) ?: return

            callback.invoke(
                newExtractorLink(
                    source = "$name - $serverName",
                    name = "$name - $serverName",
                    url = videoUrl,
                    type = INFER_TYPE
                ) {
                    this.referer = "https://uqload.is/"
                    this.quality = Qualities.Unknown.value
                }
            )
        } catch (_: Exception) { }
    }
}
