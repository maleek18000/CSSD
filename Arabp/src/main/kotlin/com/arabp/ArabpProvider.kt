package com.arabp

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.SubtitleFile
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpCookie
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class Arabp : MainAPI() {
    override var mainUrl = "https://www.arabp2p.net"
    override var name = "Arabp"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA, TvType.TvSeries, TvType.Movie)

    companion object {
        private const val TAG = "Arabp_Log"
        private const val LOGIN_USERNAME = "armano"
        private const val LOGIN_PASSWORD = "ARmano01**"
        private val DIGITS = Regex("\\d+")

        // Base32 alphabet for magnet info hash encoding
        private const val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    }

    // Images require Referer header to avoid 403
    private val imageHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
        "Referer" to "$mainUrl/"
    )

    // ==================== SESSION / COOKIE MANAGEMENT ====================

    private val cookieManager = CookieManager().apply {
        setCookiePolicy(CookiePolicy.ACCEPT_ALL)
    }

    private val authClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(object : okhttp3.CookieJar {
                override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<okhttp3.Cookie>) {
                    for (cookie in cookies) {
                        val uri = URI(url.toString())
                        val httpCookie = HttpCookie(cookie.name, cookie.value)
                        httpCookie.domain = cookie.domain
                        httpCookie.path = cookie.path ?: "/"
                        httpCookie.secure = cookie.secure
                        if (cookie.expiresAt != Long.MAX_VALUE) {
                            httpCookie.maxAge = (cookie.expiresAt - System.currentTimeMillis()) / 1000
                        }
                        cookieManager.cookieStore.add(uri, httpCookie)
                    }
                }

                override fun loadForRequest(url: okhttp3.HttpUrl): List<okhttp3.Cookie> {
                    val uri = URI(url.toString())
                    return cookieManager.cookieStore.get(uri).map { hc ->
                        val builder = okhttp3.Cookie.Builder()
                            .name(hc.name)
                            .value(hc.value)
                            .domain(hc.domain ?: url.host)
                            .path(hc.path ?: "/")
                        if (hc.secure) builder.secure()
                        if (hc.isHttpOnly) builder.httpOnly()
                        builder.build()
                    }
                }
            })
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private fun getSessionCookies(): String {
        val uri = URI(mainUrl)
        return cookieManager.cookieStore.get(uri)
            .joinToString("; ") { "${it.name}=${it.value}" }
    }

    private fun getAuthHeaders(referer: String? = null): Map<String, String> {
        val headers = mutableMapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        )
        val cookies = getSessionCookies()
        if (cookies.isNotBlank()) {
            headers["Cookie"] = cookies
        }
        if (referer != null) {
            headers["Referer"] = referer
        }
        return headers
    }

    // ==================== LOGIN ====================

    @Volatile
    private var isLoggedIn = false

    private fun ensureLogin(): Boolean {
        if (isLoggedIn) return true

        return try {
            val initRequest = Request.Builder()
                .url("$mainUrl/index.php?page=login")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
                .build()
            authClient.newCall(initRequest).execute().use { response ->
                Log.d(TAG, "Init login page: ${response.code}")
            }

            val formBody = FormBody.Builder()
                .add("uid", LOGIN_USERNAME)
                .add("pwd", LOGIN_PASSWORD)
                .build()

            val loginRequest = Request.Builder()
                .url("$mainUrl/index.php?page=login&returnto=index.php")
                .post(formBody)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
                .header("Referer", "$mainUrl/index.php?page=login")
                .build()

            authClient.newCall(loginRequest).execute().use { response ->
                val body = response.body?.string() ?: ""
                Log.d(TAG, "Login response: code=${response.code}, cookies=${getSessionCookies()}")

                val loginSuccess = response.code == 302 ||
                        body.contains("logout.php") ||
                        body.contains("page=logout") ||
                        !body.contains("name=\"uid\"") ||
                        body.contains(LOGIN_USERNAME)

                if (loginSuccess) {
                    isLoggedIn = true
                    Log.d(TAG, "Login SUCCESS!")
                } else {
                    Log.e(TAG, "Login FAILED. Snippet: ${body.take(300)}")
                }
                loginSuccess
            }
        } catch (e: Exception) {
            Log.e(TAG, "Login Error: ${e.message}")
            false
        }
    }

    private fun toAbsoluteUrl(url: String): String {
        return when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            else -> "$mainUrl/$url"
        }
    }

    // ==================== AUTH-AWARE DOCUMENT FETCHING ====================

    private fun requiresAuth(url: String): Boolean {
        return url.contains("tv-listing") || url.contains("movies-listing")
    }

    private fun fetchDocWithAuth(url: String): Document? {
        if (!ensureLogin()) return null
        return try {
            val request = Request.Builder()
                .url(url)
                .headers(getAuthHeaders(referer = "$mainUrl/").toOkHttpHeaders())
                .build()
            authClient.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return null
                Jsoup.parse(body, url)
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchDocWithAuth error for $url: ${e.message}")
            null
        }
    }

    private suspend fun fetchDoc(url: String): Document? {
        return if (requiresAuth(url)) {
            fetchDocWithAuth(url)
        } else {
            app.get(url).document
        }
    }

    // ==================== HOME PAGE ====================

    override val mainPage = mainPageOf(
        "$mainUrl/index.php?page=anime-listing" to "قائمة الانمي",
        "$mainUrl/index.php?page=tv-listing" to "مسلسلات عربية",
        "$mainUrl/index.php?page=movies-listing" to "أفلام عربية"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = if (page > 1) "${request.data}&pages=$page" else request.data
        val doc = fetchDoc(url) ?: return newHomePageResponse(mutableListOf())
        val homeSets = mutableListOf<HomePageList>()

        try {
            val tvType = tvTypeFromPage(request.data)
            val items = doc.select("div.listing_div1").mapNotNull { toSearchResult(it, tvType) }
            if (items.isNotEmpty()) {
                homeSets.add(HomePageList(request.name, items))
            }
            return newHomePageResponse(homeSets, items.isNotEmpty())
        } catch (e: Exception) {
            Log.e(TAG, "MainPage Error: ${e.message}")
        }
        return newHomePageResponse(homeSets)
    }

    private fun tvTypeFromPage(pageUrl: String): TvType {
        return when {
            pageUrl.contains("movies-listing") -> TvType.Movie
            pageUrl.contains("tv-listing") -> TvType.TvSeries
            else -> TvType.Anime
        }
    }

    private fun tvTypeFromTitle(title: String): TvType {
        return when {
            title.contains("فيلم", ignoreCase = true) || title.contains("Movie", ignoreCase = true) -> TvType.Movie
            else -> TvType.TvSeries
        }
    }

    private fun TvType.toMovieType(): TvType = when (this) {
        TvType.Movie -> TvType.Movie
        TvType.Anime -> TvType.AnimeMovie
        else -> TvType.Movie
    }

    private fun TvType.toSeriesType(): TvType = when (this) {
        TvType.TvSeries -> TvType.TvSeries
        TvType.Anime -> TvType.Anime
        else -> TvType.TvSeries
    }

    private fun toSearchResult(element: Element, fallbackTvType: TvType = TvType.Anime): SearchResponse? {
        return try {
            val linkEl = element.selectFirst("div.listing_div2 a")
                ?: element.selectFirst("a[href*=anime-listing]")
                ?: element.selectFirst("a[href*=tv-listing]")
                ?: element.selectFirst("a[href*=movies-listing]")
                ?: return null

            val href = toAbsoluteUrl(linkEl.attr("href"))
            val rawTitle = linkEl.html()
                .replace("<br>", " ").replace("<br/>", " ").replace("<br />", " ").trim()
            val title = cleanTitleText(rawTitle)

            val posterUrl = element.selectFirst("img.listing_poster")?.attr("src")
                ?: element.selectFirst("img")?.attr("src") ?: ""

            val tvType = when {
                title.contains("فيلم", ignoreCase = true) || title.contains("Movie", ignoreCase = true) -> {
                    if (fallbackTvType == TvType.Anime) TvType.AnimeMovie else TvType.Movie
                }
                else -> fallbackTvType
            }

            when (tvType) {
                TvType.Movie, TvType.AnimeMovie -> newMovieSearchResponse(title, href, tvType) {
                    this.posterUrl = toAbsoluteUrl(posterUrl)
                    this.posterHeaders = imageHeaders
                }
                TvType.TvSeries -> newTvSeriesSearchResponse(title, href, tvType) {
                    this.posterUrl = toAbsoluteUrl(posterUrl)
                    this.posterHeaders = imageHeaders
                }
                else -> newAnimeSearchResponse(title, href, tvType) {
                    this.posterUrl = toAbsoluteUrl(posterUrl)
                    this.posterHeaders = imageHeaders
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "toSearchResult Error: ${e.message}")
            null
        }
    }

    // ==================== SEARCH ====================

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val results = mutableListOf<SearchResponse>()
        val queryLower = query.lowercase().trim()

        // Source 1: LISTINGS (anime=public, tv/movies=require auth)
        val listingPages = listOf(
            Triple("$mainUrl/index.php?page=anime-listing&search=$encoded", "anime", TvType.Anime),
            Triple("$mainUrl/index.php?page=tv-listing&search=$encoded", "tv", TvType.TvSeries),
            Triple("$mainUrl/index.php?page=movies-listing&search=$encoded", "movies", TvType.Movie)
        )

        for ((listingUrl, label, tvType) in listingPages) {
            try {
                val doc = fetchDoc(listingUrl)
                val listingResults = doc?.select("div.listing_div1")
                    ?.mapNotNull { toSearchResult(it, tvType) }
                    ?.filter { matchesQuery(it.name, queryLower) }
                    ?: emptyList()
                Log.d(TAG, "$label listing search: found ${listingResults.size} results")
                results.addAll(listingResults)
            } catch (e: Exception) {
                Log.e(TAG, "$label listing search error: ${e.message}")
            }
        }

        // Source 2: PRIVATE torrents page (supports search natively)
        if (ensureLogin()) {
            try {
                val torrentsUrl = "$mainUrl/index.php?page=torrents&search=$encoded&category=0&active=0"
                val request = Request.Builder()
                    .url(torrentsUrl)
                    .headers(getAuthHeaders(referer = "$mainUrl/").toOkHttpHeaders())
                    .build()

                authClient.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: ""
                    val torrentsDoc = Jsoup.parse(body, torrentsUrl)

                    val tableResults = torrentsDoc.select("table.lista2t tr.lista2, table tr:has(a[href*=torrent-details])")
                        .mapNotNull { torrentRowToSearchResult(it) }
                    val modernResults = torrentsDoc.select("div.file-header")
                        .mapNotNull { modernTorrentRowToSearchResult(it) }

                    val allTorrentResults = tableResults + modernResults
                    Log.d(TAG, "Torrents search: found ${allTorrentResults.size} results")
                    results.addAll(allTorrentResults)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Torrents search error: ${e.message}")
            }
        }

        Log.d(TAG, "Total search results: ${results.size}")
        return results
    }

    private fun matchesQuery(title: String?, queryLower: String): Boolean {
        if (title.isNullOrBlank() || queryLower.isBlank()) return true
        val titleLower = title.lowercase()
        return titleLower.contains(queryLower)
    }

    private fun isFreeTorrent(row: Element): Boolean {
        if (row.selectFirst("span.free") != null) return true
        if (row.selectFirst("span.tor_free_link") != null) return true
        if (row.text().contains("مجاني")) return true
        return false
    }

    private fun isExternalTorrent(row: Element): Boolean {
        if (row.selectFirst("a[href^=magnet:]") != null) return true
        if (row.text().contains("خارجي")) return true
        return false
    }

    private fun torrentRowToSearchResult(row: Element): SearchResponse? {
        return try {
            val nameLink = row.selectFirst("a[href*=torrent-details]") ?: return null
            val name = cleanTitleText(nameLink.text())
            val detailHref = toAbsoluteUrl(nameLink.attr("href"))
            val torrentId = DIGITS.find(detailHref.substringAfter("id="))?.value ?: return null

            val downloadHref = row.selectFirst("a[href*=download.php]")?.attr("href") ?: ""
            val magnetHref = row.selectFirst("a[href^=magnet:]")?.attr("href") ?: ""

            val tds = row.select("td")
            val size = tds.getOrNull(3)?.text()?.trim() ?: ""
            val seeders = tds.getOrNull(4)?.text()?.trim() ?: ""
            val categoryName = row.selectFirst("a[href*=category=]")?.text()?.trim() ?: ""

            val isFree = isFreeTorrent(row)
            val isExternal = isExternalTorrent(row)
            val tvType = when {
                categoryName.contains("فيلم", ignoreCase = true) || categoryName.contains("Movie", ignoreCase = true) ||
                        name.contains("فيلم", ignoreCase = true) -> TvType.AnimeMovie
                else -> TvType.Anime
            }

            val displayName = buildString {
                append(name)
                if (isFree) append(" ✅مجاني")
                if (size.isNotEmpty()) append(" | $size")
                if (seeders.isNotEmpty()) append(" | ▲$seeders")
            }

            val posterUrl = row.selectFirst("a.screenshot")?.attr("rel")
                ?: nameLink.attr("rel")
                ?: ""

            val epData = "$torrentId|${toAbsoluteUrl(detailHref)}|${toAbsoluteUrl(downloadHref)}|$magnetHref|${if (isFree) "1" else "0"}|${if (isExternal) "1" else "0"}"
            newAnimeSearchResponse(displayName, epData, tvType) {
                this.posterUrl = toAbsoluteUrl(posterUrl)
                this.posterHeaders = imageHeaders
            }
        } catch (e: Exception) {
            Log.e(TAG, "torrentRowToSearchResult Error: ${e.message}")
            null
        }
    }

    private fun modernTorrentRowToSearchResult(row: Element): SearchResponse? {
        return try {
            val nameLink = row.selectFirst("a[name=t_url], a[href*=torrent-details]") ?: return null
            val name = cleanTitleText(nameLink.text())
            val detailHref = toAbsoluteUrl(nameLink.attr("href"))
            val torrentId = DIGITS.find(detailHref.substringAfter("id="))?.value ?: return null

            val downloadHref = row.selectFirst("a[href*=download.php]")?.attr("href") ?: ""
            val magnetHref = row.selectFirst("a[href^=magnet:]")?.attr("href") ?: ""

            val isFree = isFreeTorrent(row)
            val isExternal = isExternalTorrent(row)
            val tvType = when {
                name.contains("فيلم", ignoreCase = true) || name.contains("Movie", ignoreCase = true) -> TvType.AnimeMovie
                else -> TvType.Anime
            }

            val displayName = buildString {
                append(name)
                if (isFree) append(" ✅مجاني")
            }

            val posterUrl = row.selectFirst("a.screenshot")?.attr("rel")
                ?: nameLink.attr("rel")
                ?: ""

            val epData = "$torrentId|${toAbsoluteUrl(detailHref)}|${toAbsoluteUrl(downloadHref)}|$magnetHref|${if (isFree) "1" else "0"}|${if (isExternal) "1" else "0"}"
            newAnimeSearchResponse(displayName, epData, tvType) {
                this.posterUrl = toAbsoluteUrl(posterUrl)
                this.posterHeaders = imageHeaders
            }
        } catch (e: Exception) {
            Log.e(TAG, "modernTorrentRowToSearchResult Error: ${e.message}")
            null
        }
    }

    // ==================== LOAD (Detail Page) ====================

    override suspend fun load(url: String): LoadResponse? {
        if (url.contains("|")) return loadFromTorrentData(url)

        val fullUrl = toAbsoluteUrl(url)
        val doc = fetchDoc(fullUrl) ?: return null

        return try {
            val h1 = doc.selectFirst("div.listing_div_id h1")
            val rawTitle = h1?.html()
                ?.replace("<br>", " ")?.replace("<br/>", " ")?.replace("<br />", " ")?.trim() ?: ""
            val title = cleanTitleText(rawTitle)

            val posterUrl = doc.selectFirst("div.listing_div_id img.listing_poster")?.attr("src")
                ?: doc.selectFirst("img.listing_poster")?.attr("src") ?: ""

            val absPosterUrl = toAbsoluteUrl(posterUrl)
            val rows = doc.select("table#listing_table tr")
            val episodes = mutableListOf<Episode>()
            val seasonNamesList = mutableListOf<SeasonData>()
            var globalSeasonNum = 1

            for (row in rows) {
                val nameLink = row.selectFirst("a[href*=torrent-details]") ?: continue
                val epName = cleanTitleText(nameLink.text())
                val detailHref = nameLink.attr("href")
                val torrentId = DIGITS.find(detailHref.substringAfter("id="))?.value ?: continue
                val downloadHref = row.selectFirst("a[href*=download.php]")?.attr("href") ?: ""
                val magnetHref = row.selectFirst("a[href^=magnet:]")?.attr("href") ?: ""
                val isFree = isFreeTorrent(row)
                val isExternal = isExternalTorrent(row)

                val tds = row.select("td")
                val size = tds.getOrNull(3)?.text()?.trim() ?: ""
                val seeders = tds.getOrNull(4)?.text()?.trim() ?: ""

                val displayName = buildString {
                    append(epName)
                    if (isFree) append(" ✅مجاني")
                    if (size.isNotEmpty()) append(" | $size")
                    if (seeders.isNotEmpty()) append(" | ▲$seeders")
                }

                // Each torrent row becomes one episode with pipe-delimited data.
                // loadLinks() will convert .torrent → magnet when user clicks play.
                val epData = "$torrentId|${toAbsoluteUrl(detailHref)}|${toAbsoluteUrl(downloadHref)}|$magnetHref|${if (isFree) "1" else "0"}|${if (isExternal) "1" else "0"}"
                seasonNamesList.add(SeasonData(season = globalSeasonNum, name = displayName))
                episodes.add(
                    newEpisode(epData, fix = false, initializer = {
                        name = displayName
                        season = globalSeasonNum
                        episode = 1
                        this.posterUrl = absPosterUrl
                    })
                )
                globalSeasonNum++
            }

            val pageTvType = tvTypeFromPage(fullUrl)

            if (episodes.isEmpty()) {
                newMovieLoadResponse(title, fullUrl, pageTvType.toMovieType(), "0|$fullUrl|||0|0") {
                    this.posterUrl = absPosterUrl
                    this.posterHeaders = imageHeaders
                }
            } else {
                newTvSeriesLoadResponse(title, fullUrl, pageTvType.toSeriesType(), episodes) {
                    this.posterUrl = absPosterUrl
                    this.posterHeaders = imageHeaders
                    this.seasonNames = seasonNamesList
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Load Error: ${e.message}")
            null
        }
    }

    // ==================== LOAD FROM TORRENT DATA ====================

    private suspend fun loadFromTorrentData(data: String): LoadResponse? {
        val parts = data.split("|", limit = 6)
        val torrentId = parts.getOrNull(0) ?: "0"
        val detailUrl = parts.getOrNull(1) ?: ""
        val downloadUrl = parts.getOrNull(2) ?: ""
        val magnetUrl = parts.getOrNull(3) ?: ""
        val isFree = parts.getOrNull(4) == "1"
        val isExternal = parts.getOrNull(5) == "1"

        var title = "Torrent #$torrentId"
        var posterUrl = ""

        // Fetch title and poster from detail page
        try {
            if (ensureLogin() && detailUrl.isNotBlank()) {
                val request = Request.Builder()
                    .url(toAbsoluteUrl(detailUrl))
                    .headers(getAuthHeaders(referer = "$mainUrl/").toOkHttpHeaders())
                    .build()

                authClient.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: ""
                    val detailDoc = Jsoup.parse(body, toAbsoluteUrl(detailUrl))
                    title = detailDoc.selectFirst("td#Title h1")?.text()?.trim() ?: title
                    posterUrl = detailDoc.selectFirst("img.listing_poster")?.attr("src") ?: ""
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "loadFromTorrentData detail fetch error: ${e.message}")
        }

        val absPosterUrl = toAbsoluteUrl(posterUrl)

        val pageTvType = if (detailUrl.contains("movies-listing")) TvType.Movie
            else if (detailUrl.contains("tv-listing")) TvType.TvSeries
            else if (detailUrl.contains("anime-listing")) TvType.Anime
            else tvTypeFromTitle(title)

        // External torrents with magnet: return as movie with magnet data
        if (isExternal && magnetUrl.startsWith("magnet:")) {
            return newMovieLoadResponse(title, data, pageTvType.toMovieType(), data) {
                this.posterUrl = absPosterUrl
                this.posterHeaders = imageHeaders
            }
        }

        // All other torrents: return as single-episode series.
        // loadLinks() will convert .torrent → magnet when user clicks play.
        return newTvSeriesLoadResponse(title, data, pageTvType.toSeriesType(), listOf(
            newEpisode(data, fix = false, initializer = {
                name = title
                season = 1
                episode = 1
                this.posterUrl = absPosterUrl
            })
        )) {
            this.posterUrl = absPosterUrl
            this.posterHeaders = imageHeaders
        }
    }

    // ==================== LOAD LINKS ====================
    //
    // Downloads the .torrent file → converts to magnet link → returns MAGNET source.
    // No TorrServe needed! CloudStream handles magnet links natively.
    //
    // Two sources provided:
    // 1. "Arabp (Magnet)" — magnet WITH trackers (works but may count)
    // 2. "Arabp (Magnet - No Trackers)" — magnet WITHOUT trackers (safe from counting,
    //    but needs a debrid service or DHT to find peers)

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("|", limit = 6)
        val torrentId = parts.getOrNull(0) ?: "0"
        val detailUrl = parts.getOrNull(1) ?: ""
        val downloadUrl = parts.getOrNull(2) ?: ""
        val magnetUrl = parts.getOrNull(3) ?: ""
        val isFree = parts.getOrNull(4) == "1"
        val isExternal = parts.getOrNull(5) == "1"

        Log.d(TAG, "loadLinks: id=$torrentId, free=$isFree, external=$isExternal")

        return try {
            if (!ensureLogin()) {
                Log.e(TAG, "Cannot load links: login failed")
                return false
            }

            var foundLink = false

            // === EXTERNAL TORRENTS: pass magnet link directly ===
            if (isExternal && magnetUrl.startsWith("magnet:")) {
                Log.d(TAG, "External torrent — passing magnet link")
                callback(
                    newExtractorLink(
                        source = this.name,
                        name = "${this.name} (Magnet)",
                        url = magnetUrl,
                        type = ExtractorLinkType.MAGNET
                    )
                )
                return true
            }

            // === INTERNAL TORRENTS: download .torrent → convert to magnet ===
            var resolvedDownloadUrl = downloadUrl

            // Step 1: Resolve download URL with &f= parameter
            if (resolvedDownloadUrl.isBlank() || !resolvedDownloadUrl.contains("&f=")) {
                val detailPageUrl = if (detailUrl.isNotBlank()) detailUrl
                    else "$mainUrl/index.php?page=torrent-details&id=$torrentId"

                try {
                    val request = Request.Builder()
                        .url(toAbsoluteUrl(detailPageUrl))
                        .headers(getAuthHeaders(referer = "$mainUrl/").toOkHttpHeaders())
                        .build()

                    authClient.newCall(request).execute().use { response ->
                        val body = response.body?.string() ?: ""
                        val detailDoc = Jsoup.parse(body, toAbsoluteUrl(detailPageUrl))

                        val dlLink = detailDoc.selectFirst("a[href*=download.php]")
                            ?: detailDoc.selectFirst("td#Title h1 a[href*=download.php]")
                        if (dlLink != null) {
                            resolvedDownloadUrl = toAbsoluteUrl(dlLink.attr("href"))
                        }

                        // Also check for magnet link on detail page as fallback
                        val magnetEl = detailDoc.selectFirst("a[href^=magnet:]")
                        if (magnetEl != null) {
                            val pageMagnet = magnetEl.attr("href")
                            if (pageMagnet.startsWith("magnet:")) {
                                callback(
                                    newExtractorLink(
                                        source = this.name,
                                        name = "${this.name} (Magnet)",
                                        url = pageMagnet,
                                        type = ExtractorLinkType.MAGNET
                                    )
                                )
                                foundLink = true
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to fetch detail page: ${e.message}")
                }
            }

            // Step 1.5: Thank the uploader for non-free torrents
            if (!isFree) {
                val thankDetailUrl = if (detailUrl.isNotBlank()) detailUrl
                    else "$mainUrl/index.php?page=torrent-details&id=$torrentId"
                val thanked = thankUploader(torrentId, thankDetailUrl)
                Log.d(TAG, "loadLinks: thank uploader result = $thanked for torrent $torrentId")
            }

            // Step 2: Download .torrent → convert to magnet
            if (resolvedDownloadUrl.isNotBlank() && resolvedDownloadUrl.contains("&f=")) {
                try {
                    val result = downloadTorrentFile(resolvedDownloadUrl)
                    when (result) {
                        is TorrentDownloadResult.Success -> {
                            Log.d(TAG, "Downloaded .torrent: ${result.bytes.size} bytes, converting to magnet...")

                            // Convert .torrent to magnet link
                            val magnetWithTrackers = torrentToMagnet(result.bytes, includeTrackers = true)
                            val magnetNoTrackers = torrentToMagnet(result.bytes, includeTrackers = false)

                            if (magnetWithTrackers != null) {
                                // Source 1: Magnet WITH trackers (may count on tracker)
                                callback(
                                    newExtractorLink(
                                        source = this.name,
                                        name = "${this.name} (Magnet)",
                                        url = magnetWithTrackers,
                                        type = ExtractorLinkType.MAGNET
                                    )
                                )
                                foundLink = true
                            }

                            if (magnetNoTrackers != null && magnetNoTrackers != magnetWithTrackers) {
                                // Source 2: Magnet WITHOUT trackers (safe from counting,
                                // but needs debrid service or DHT to find peers)
                                callback(
                                    newExtractorLink(
                                        source = "$name-NT",
                                        name = "${this.name} (لا متتبع)", // "No Tracker"
                                        url = magnetNoTrackers,
                                        type = ExtractorLinkType.MAGNET
                                    )
                                )
                                foundLink = true
                            }

                            if (magnetWithTrackers == null && magnetNoTrackers == null) {
                                Log.e(TAG, "Failed to convert .torrent to magnet link")
                            }
                        }
                        is TorrentDownloadResult.DailyLimitExceeded -> {
                            Log.w(TAG, "DAILY DOWNLOAD LIMIT EXCEEDED for torrent id=$torrentId, thanking and retrying...")
                            val thankDetailUrl = if (detailUrl.isNotBlank()) detailUrl
                                else "$mainUrl/index.php?page=torrent-details&id=$torrentId"
                            thankUploader(torrentId, thankDetailUrl)

                            val retryResult = downloadTorrentFile(resolvedDownloadUrl)
                            when (retryResult) {
                                is TorrentDownloadResult.Success -> {
                                    Log.d(TAG, "Retry succeeded! Converting to magnet...")
                                    val magnet = torrentToMagnet(retryResult.bytes, includeTrackers = true)
                                    val magnetNT = torrentToMagnet(retryResult.bytes, includeTrackers = false)
                                    if (magnet != null) {
                                        callback(
                                            newExtractorLink(
                                                source = this.name,
                                                name = "${this.name} (Magnet)",
                                                url = magnet,
                                                type = ExtractorLinkType.MAGNET
                                            )
                                        )
                                        foundLink = true
                                    }
                                    if (magnetNT != null && magnetNT != magnet) {
                                        callback(
                                            newExtractorLink(
                                                source = "$name-NT",
                                                name = "${this.name} (لا متتبع)",
                                                url = magnetNT,
                                                type = ExtractorLinkType.MAGNET
                                            )
                                        )
                                        foundLink = true
                                    }
                                }
                                else -> {
                                    Log.e(TAG, "Download still failed after thank + retry for torrent id=$torrentId")
                                    callback(
                                        newExtractorLink(
                                            source = this.name,
                                            name = "\u274C تجاوزت الحد اليومي للتحميل",
                                            url = "$mainUrl/",
                                            type = ExtractorLinkType.VIDEO
                                        )
                                    )
                                    foundLink = true
                                }
                            }
                        }
                        is TorrentDownloadResult.NotLoggedIn -> {
                            isLoggedIn = false
                            Log.e(TAG, "Session expired for torrent id=$torrentId")
                        }
                        is TorrentDownloadResult.Error -> {
                            Log.e(TAG, "Download error for torrent id=$torrentId: ${result.message}")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Magnet conversion error: ${e.message}")
                }
            }

            foundLink
        } catch (e: Exception) {
            Log.e(TAG, "loadLinks Error: ${e.message}")
            false
        }
    }

    // ==================== THANK UPLOADER ====================

    private fun thankUploader(torrentId: String, detailUrl: String): Boolean {
        if (!ensureLogin()) return false

        return try {
            val thankFormBody = FormBody.Builder()
                .add("tid", torrentId)
                .add("thanks", "1")
                .build()

            val pageUrl = if (detailUrl.isNotBlank()) toAbsoluteUrl(detailUrl)
                else "$mainUrl/index.php?page=torrent-details&id=$torrentId"

            val thankRequest = Request.Builder()
                .url("$mainUrl/thanks.php")
                .post(thankFormBody)
                .headers(getAuthHeaders(referer = pageUrl).toOkHttpHeaders())
                .header("X-Requested-With", "XMLHttpRequest")
                .build()

            authClient.newCall(thankRequest).execute().use { response ->
                val responseBody = response.body?.string() ?: ""
                Log.d(TAG, "Thank uploader: HTTP ${response.code}, response: ${responseBody.take(200)}")
                response.isSuccessful
            }
        } catch (e: Exception) {
            Log.e(TAG, "thankUploader error: ${e.message}")
            false
        }
    }

    // ==================== TORRENT DOWNLOAD ====================

    sealed class TorrentDownloadResult {
        data class Success(val bytes: ByteArray) : TorrentDownloadResult()
        data object DailyLimitExceeded : TorrentDownloadResult()
        data object NotLoggedIn : TorrentDownloadResult()
        data class Error(val message: String) : TorrentDownloadResult()
    }

    private fun downloadTorrentFile(url: String): TorrentDownloadResult {
        return try {
            val request = Request.Builder()
                .url(toAbsoluteUrl(url))
                .headers(getAuthHeaders(referer = "$mainUrl/").toOkHttpHeaders())
                .build()

            authClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return TorrentDownloadResult.Error("HTTP ${response.code}")

                val contentType = response.header("Content-Type", "") ?: ""
                val bodyBytes = response.body?.bytes() ?: return TorrentDownloadResult.Error("Empty response")

                if (contentType.contains("text/html", ignoreCase = true)) {
                    val htmlBody = String(bodyBytes, Charsets.UTF_8)
                    Log.e(TAG, "download.php returned HTML: ${htmlBody.take(200)}")
                    return when {
                        htmlBody.contains("تجاوزت الحد") || htmlBody.contains("الحد المسموح") ->
                            TorrentDownloadResult.DailyLimitExceeded
                        htmlBody.contains("E3") || htmlBody.length < 10 ->
                            TorrentDownloadResult.NotLoggedIn
                        else -> TorrentDownloadResult.Error("Server returned HTML: ${htmlBody.take(100)}")
                    }
                }

                if (bodyBytes.size < 20 || bodyBytes[0] != 'd'.code.toByte()) {
                    return TorrentDownloadResult.Error("Invalid .torrent data (${bodyBytes.size} bytes)")
                }

                Log.d(TAG, "Downloaded .torrent: ${bodyBytes.size} bytes")
                TorrentDownloadResult.Success(bodyBytes)
            }
        } catch (e: Exception) {
            TorrentDownloadResult.Error(e.message ?: "Unknown error")
        }
    }

    // ==================== TORRENT → MAGNET CONVERSION ====================
    //
    // Converts a .torrent file to a magnet URI link.
    // This is the same algorithm as fosstorrents.com/t2m/ but done natively:
    //   1. Bencode-decode the .torrent file
    //   2. Extract the "info" dictionary
    //   3. Re-bencode just the info dict
    //   4. SHA1 hash the bencoded info → this is the info hash
    //   5. Base32-encode the hash → magnet xt parameter
    //   6. Build magnet URI with xt, dn, xl, and optionally tr parameters
    //
    // By omitting tracker URLs (includeTrackers=false), the torrent client
    // cannot announce to the tracker, so arabp2p won't count the download.
    // This requires a debrid service or DHT to find peers.

    private fun torrentToMagnet(torrentBytes: ByteArray, includeTrackers: Boolean = true): String? {
        return try {
            val (root, _) = decodeBencode(torrentBytes, 0)
            val dict = root as? BencodeValue.BDict ?: return null

            // Find the "info" dictionary
            val infoEntry = dict.entries.find { (k, _) ->
                k.bytes.contentEquals("info".toByteArray())
            } ?: return null

            // Re-bencode just the info dict and SHA1 hash it
            val infoBencoded = encodeBencode(infoEntry.second)
            val sha1 = java.security.MessageDigest.getInstance("SHA-1").digest(infoBencoded)

            // Convert SHA1 hash to Base32 (standard for magnet URIs)
            val infoHashBase32 = base32Encode(sha1)

            // Build magnet URI
            val magnet = StringBuilder("magnet:?xt=urn:btih:$infoHashBase32")

            // Add display name (dn)
            val nameEntry = (infoEntry.second as? BencodeValue.BDict)?.entries?.find { (k, _) ->
                k.bytes.contentEquals("name".toByteArray())
            }
            if (nameEntry != null) {
                val name = String((nameEntry.second as BencodeValue.BString).bytes)
                magnet.append("&dn=").append(URLEncoder.encode(name, "UTF-8"))
            }

            // Add file length (xl) — for single-file torrents
            val lengthEntry = (infoEntry.second as? BencodeValue.BDict)?.entries?.find { (k, _) ->
                k.bytes.contentEquals("length".toByteArray())
            }
            if (lengthEntry != null) {
                val length = (lengthEntry.second as BencodeValue.BInt).value
                magnet.append("&xl=$length")
            }

            // Add tracker URLs (tr) — only if requested
            if (includeTrackers) {
                // Single announce URL
                val announceEntry = dict.entries.find { (k, _) ->
                    k.bytes.contentEquals("announce".toByteArray())
                }
                if (announceEntry != null) {
                    val announceUrl = String((announceEntry.second as BencodeValue.BString).bytes)
                    magnet.append("&tr=").append(URLEncoder.encode(announceUrl, "UTF-8"))
                }

                // Announce-list (multiple trackers)
                val announceListEntry = dict.entries.find { (k, _) ->
                    k.bytes.contentEquals("announce-list".toByteArray())
                }
                if (announceListEntry != null) {
                    val announceList = announceListEntry.second as? BencodeValue.BList
                    announceList?.items?.forEach { tier ->
                        val tierList = tier as? BencodeValue.BList
                        tierList?.items?.forEach { tracker ->
                            val trackerUrl = String((tracker as BencodeValue.BString).bytes)
                            magnet.append("&tr=").append(URLEncoder.encode(trackerUrl, "UTF-8"))
                        }
                    }
                }
            }

            Log.d(TAG, "torrentToMagnet: ${if (includeTrackers) "with" else "without"} trackers → ${magnet.toString().take(100)}...")
            magnet.toString()
        } catch (e: Exception) {
            Log.e(TAG, "torrentToMagnet error: ${e.message}")
            null
        }
    }

    // ==================== BASE32 ENCODING ====================
    //
    // Standard Base32 encoding (RFC 4648) used for magnet URI info hashes.
    // Converts binary data to a string using the alphabet A-Z2-7.

    private fun base32Encode(data: ByteArray): String {
        var result = StringBuilder()
        var buffer = 0L
        var bitsInBuffer = 0

        for (byte in data) {
            buffer = (buffer shl 8) or (byte.toInt() and 0xFF).toLong()
            bitsInBuffer += 8

            while (bitsInBuffer >= 5) {
                bitsInBuffer -= 5
                val index = ((buffer shr bitsInBuffer) and 0x1F).toInt()
                result.append(BASE32_ALPHABET[index])
            }
        }

        if (bitsInBuffer > 0) {
            val index = ((buffer shl (5 - bitsInBuffer)) and 0x1F).toInt()
            result.append(BASE32_ALPHABET[index])
        }

        // Pad to multiple of 8
        while (result.length % 8 != 0) {
            result.append('=')
        }

        return result.toString()
    }

    // ==================== BENCODE PARSER ====================
    //
    // Minimal bencode parser/encoder for .torrent files.
    // Handles: strings (d<n>:<bytes>), integers (i<n>e), lists (l...e), dicts (d...e)

    sealed class BencodeValue {
        data class BString(val bytes: ByteArray) : BencodeValue()
        data class BInt(val value: Long) : BencodeValue()
        data class BList(val items: List<BencodeValue>) : BencodeValue()
        data class BDict(val entries: MutableList<Pair<BString, BencodeValue>>) : BencodeValue()
    }

    private fun decodeBencode(data: ByteArray, offset: Int): Pair<BencodeValue, Int> {
        val byte = data[offset].toInt().toChar()
        return when {
            byte in '0'..'9' -> decodeBencodeString(data, offset)
            byte == 'i' -> decodeBencodeInt(data, offset)
            byte == 'l' -> decodeBencodeList(data, offset)
            byte == 'd' -> decodeBencodeDict(data, offset)
            else -> throw IllegalArgumentException("Invalid bencode at offset $offset: '$byte'")
        }
    }

    private fun decodeBencodeString(data: ByteArray, offset: Int): Pair<BencodeValue.BString, Int> {
        var colon = -1
        for (i in offset until data.size) { if (data[i] == ':'.code.toByte()) { colon = i; break } }
        if (colon < 0) throw IllegalArgumentException("No colon found")
        val len = String(data, offset, colon - offset).toInt()
        val strStart = colon + 1
        return Pair(BencodeValue.BString(data.sliceArray(strStart until strStart + len)), strStart + len)
    }

    private fun decodeBencodeInt(data: ByteArray, offset: Int): Pair<BencodeValue.BInt, Int> {
        var end = -1
        for (i in offset until data.size) { if (data[i] == 'e'.code.toByte()) { end = i; break } }
        val value = String(data, offset + 1, end - offset - 1).toLong()
        return Pair(BencodeValue.BInt(value), end + 1)
    }

    private fun decodeBencodeList(data: ByteArray, offset: Int): Pair<BencodeValue.BList, Int> {
        var pos = offset + 1
        val items = mutableListOf<BencodeValue>()
        while (data[pos].toInt().toChar() != 'e') {
            val (item, newPos) = decodeBencode(data, pos)
            items.add(item)
            pos = newPos
        }
        return Pair(BencodeValue.BList(items), pos + 1)
    }

    private fun decodeBencodeDict(data: ByteArray, offset: Int): Pair<BencodeValue.BDict, Int> {
        var pos = offset + 1
        val entries = mutableListOf<Pair<BencodeValue.BString, BencodeValue>>()
        while (data[pos].toInt().toChar() != 'e') {
            val (key, newPos1) = decodeBencodeString(data, pos)
            val (value, newPos2) = decodeBencode(data, newPos1)
            entries.add(Pair(key, value))
            pos = newPos2
        }
        return Pair(BencodeValue.BDict(entries), pos + 1)
    }

    private fun encodeBencode(value: BencodeValue): ByteArray {
        return when (value) {
            is BencodeValue.BString -> {
                val len = value.bytes.size.toString().toByteArray()
                len + ":".toByteArray() + value.bytes
            }
            is BencodeValue.BInt -> "i${value.value}e".toByteArray()
            is BencodeValue.BList -> {
                val parts = mutableListOf<ByteArray>("l".toByteArray())
                for (item in value.items) parts.add(encodeBencode(item))
                parts.add("e".toByteArray())
                parts.reduce { acc, bytes -> acc + bytes }
            }
            is BencodeValue.BDict -> {
                val parts = mutableListOf<ByteArray>("d".toByteArray())
                for ((key, val_) in value.entries) {
                    parts.add(encodeBencode(key))
                    parts.add(encodeBencode(val_))
                }
                parts.add("e".toByteArray())
                parts.reduce { acc, bytes -> acc + bytes }
            }
        }
    }

    // ==================== UTILITY FUNCTIONS ====================

    private fun cleanTitleText(text: String): String {
        return text
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&nbsp;", " ")
            .trim()
    }

    // ==================== OKHTTP HELPERS ====================

    private fun Map<String, String>.toOkHttpHeaders(): okhttp3.Headers {
        val builder = okhttp3.Headers.Builder()
        for ((key, value) in this) {
            builder.add(key, value)
        }
        return builder.build()
    }
}
