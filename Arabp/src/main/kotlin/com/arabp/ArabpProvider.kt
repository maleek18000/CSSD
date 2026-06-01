package com.arabp

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.SubtitleFile
import okhttp3.FormBody
import okhttp3.Headers.Companion.toHeaders
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpCookie
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLEncoder
import java.io.ByteArrayOutputStream
import java.io.OutputStream
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
        private val PASSKEY_REGEX = Regex("/([0-9a-f]{32})/announce")

        // Base32 alphabet for magnet info hash encoding
        private const val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    }

    // Cached passkey extracted from .torrent announce URL or website
    @Volatile
    private var cachedPasskey: String? = null

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
                .headers(getAuthHeaders(referer = "$mainUrl/").toHeaders())
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
                    .headers(getAuthHeaders(referer = "$mainUrl/").toHeaders())
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
                    .headers(getAuthHeaders(referer = "$mainUrl/").toHeaders())
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

    // ==================== LOCAL TORRENT SERVER ====================
    //
    // CloudStream3's built-in torrent player uses a local Go server (anacrolix/torrent)
    // that fetches .torrent files directly via HTTP. It does NOT forward the
    // ExtractorLink headers (cookies/auth) — they are discarded with emptyMap().
    // This means private tracker .torrent URLs requiring auth will always fail.
    //
    // Solution: Download the .torrent file ourselves (with auth), then serve it
    // from a local HTTP server on 127.0.0.1. CS3's Go server can fetch from
    // localhost without auth, and the .torrent file already contains the passkey
    // tracker URL inside it.
    //
    // The server auto-stops after 90 seconds of inactivity.

    private var localServerSocket: ServerSocket? = null
    private var localServerPort: Int = 0
    private var localServerThread: Thread? = null
    @Volatile
    private var servedTorrentBytes: ByteArray? = null
    @Volatile
    private var lastServerActivity: Long = 0

    // Tracker proxy state: intercepts announce requests from CS's torrent engine
    // and only forwards the FIRST one to the real tracker. All subsequent
    // announces get a minimal empty-peers response, so the real tracker
    // never sees download progress.
    @Volatile
    private var realAnnounceUrl: String? = null
    @Volatile
    private var announceProxyForwarded = false
    @Volatile
    private var cachedAnnounceResponse: ByteArray? = null

    /**
     * Starts (or reuses) a local HTTP server that:
     * 1. Serves a MODIFIED .torrent file (announce URL → our /announce proxy)
     * 2. Acts as tracker proxy: forwards only FIRST announce to real tracker,
     *    then returns empty/cached responses for all subsequent announces.
     * Returns the local URL (e.g. http://127.0.0.1:PORT/torrent.torrent) or null on error.
     */
    private fun startLocalTorrentServer(originalTorrentBytes: ByteArray): String? {
        lastServerActivity = System.currentTimeMillis()

        // If server is already running, update bytes and proxy state, return the URL
        if (localServerSocket != null && localServerPort > 0 && localServerThread?.isAlive == true) {
            Log.d(TAG, "Local torrent server: reusing existing server on port $localServerPort")
            val proxyAnnounce = "http://127.0.0.1:$localServerPort/announce"
            realAnnounceUrl = extractAnnounceUrl(originalTorrentBytes)
            servedTorrentBytes = if (realAnnounceUrl != null) {
                modifyTorrentAnnounce(originalTorrentBytes, proxyAnnounce)
            } else {
                originalTorrentBytes
            }
            announceProxyForwarded = false
            cachedAnnounceResponse = null
            return "http://127.0.0.1:$localServerPort/torrent.torrent"
        }

        // Stop any stale server
        stopLocalTorrentServer()

        // Reset proxy state
        announceProxyForwarded = false
        cachedAnnounceResponse = null
        realAnnounceUrl = null

        try {
            val socket = ServerSocket(0) // random available port
            socket.reuseAddress = true
            socket.soTimeout = 5000 // accept() timeout — check for shutdown every 5s
            localServerSocket = socket
            localServerPort = socket.localPort

            // Now that we know the port, modify .torrent and extract real announce URL
            val proxyAnnounce = "http://127.0.0.1:$localServerPort/announce"
            realAnnounceUrl = extractAnnounceUrl(originalTorrentBytes)
            servedTorrentBytes = if (realAnnounceUrl != null) {
                Log.d(TAG, "Tracker proxy: real announce = $realAnnounceUrl, replacing with $proxyAnnounce")
                modifyTorrentAnnounce(originalTorrentBytes, proxyAnnounce)
            } else {
                Log.d(TAG, "No arabp2p tracker found in .torrent, serving unmodified")
                originalTorrentBytes
            }

            localServerThread = Thread({
                try {
                    while (!Thread.currentThread().isInterrupted) {
                        // Auto-stop after 90 seconds of inactivity
                        if (System.currentTimeMillis() - lastServerActivity > 90_000) {
                            Log.d(TAG, "Local torrent server: shutting down after 90s inactivity")
                            break
                        }
                        try {
                            val client = socket.accept()
                            lastServerActivity = System.currentTimeMillis()
                            handleLocalServerRequest(client)
                        } catch (_: java.net.SocketTimeoutException) {
                            // Normal — loop back and check inactivity / interrupt
                            continue
                        }
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Local torrent server stopped: ${e.message}")
                } finally {
                    stopLocalTorrentServer()
                }
            }, "ArabpLocalServer")
            localServerThread?.start()

            Log.d(TAG, "Local torrent server started on port $localServerPort")
            return "http://127.0.0.1:$localServerPort/torrent.torrent"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start local torrent server: ${e.message}")
            return null
        }
    }

    private fun handleLocalServerRequest(socket: Socket) {
        try {
            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            // Read the HTTP request
            val buffer = ByteArray(8192)
            val bytesRead = input.read(buffer)
            if (bytesRead <= 0) return

            val requestStr = String(buffer, 0, bytesRead, Charsets.ISO_8859_1)
            val requestLine = requestStr.substringBefore("\r\n")
            Log.d(TAG, "Local server request: $requestLine")

            // Parse path from request line: "GET /path HTTP/1.1"
            val path = requestLine.split(" ").getOrNull(1) ?: "/"

            when {
                path.startsWith("/announce") -> {
                    handleAnnounceProxy(path, output)
                }
                else -> {
                    // Serve .torrent file
                    val bytes = servedTorrentBytes
                    if (bytes == null) {
                        val response = "HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n"
                        output.write(response.toByteArray())
                    } else {
                        val response = "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: application/x-bittorrent\r\n" +
                                "Content-Length: ${bytes.size}\r\n" +
                                "Connection: close\r\n\r\n"
                        output.write(response.toByteArray())
                        output.write(bytes)
                        Log.d(TAG, "Local server: served .torrent file (${bytes.size} bytes)")
                    }
                }
            }
            output.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Local server request error: ${e.message}")
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun stopLocalTorrentServer() {
        try { localServerSocket?.close() } catch (_: Exception) {}
        try { localServerThread?.interrupt() } catch (_: Exception) {}
        localServerSocket = null
        localServerPort = 0
        localServerThread = null
        realAnnounceUrl = null
        announceProxyForwarded = false
        cachedAnnounceResponse = null
    }

    // ==================== TRACKER ANNOUNCE PROXY ====================
    //
    // Intercepts announce requests from CS's torrent engine.
    // Only the FIRST announce is forwarded to the real arabp2p tracker.
    // All subsequent announces get an empty-peers response.
    // This way the real tracker only ever sees ONE announce (event=started, downloaded=0)
    // and never tracks download progress.

    private fun handleAnnounceProxy(path: String, output: OutputStream) {
        val announceUrl = realAnnounceUrl

        if (announceUrl == null) {
            // No real tracker to proxy to, return empty response
            writeHttpResponse(output, buildEmptyAnnounceResponse())
            return
        }

        if (!announceProxyForwarded) {
            // Haven't successfully forwarded yet — try to forward to real tracker
            try {
                val queryString = if (path.contains("?")) path.substringAfter("?") else ""
                val fullUrl = if (queryString.isNotEmpty()) "$announceUrl?$queryString" else announceUrl

                Log.d(TAG, "Tracker proxy: forwarding FIRST announce to real tracker")

                val request = Request.Builder()
                    .url(fullUrl)
                    .header("User-Agent", "Transmission/3.00")
                    .build()

                authClient.newCall(request).execute().use { response ->
                    val bodyBytes = response.body?.bytes()
                    if (bodyBytes != null && response.isSuccessful) {
                        cachedAnnounceResponse = bodyBytes
                        announceProxyForwarded = true
                        Log.d(TAG, "Tracker proxy: got response from real tracker (${bodyBytes.size} bytes)")
                        writeHttpResponse(output, bodyBytes)
                        return
                    } else {
                        Log.w(TAG, "Tracker proxy: real tracker returned HTTP ${response.code}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Tracker proxy: error forwarding announce: ${e.message}")
            }
            // Forwarding failed — return empty response but DON'T mark as forwarded
            // so next announce will try again
            writeHttpResponse(output, buildEmptyAnnounceResponse())
            return
        }

        // Already forwarded successfully — return cached/empty response
        val response = cachedAnnounceResponse ?: buildEmptyAnnounceResponse()
        writeHttpResponse(output, response)
        Log.d(TAG, "Tracker proxy: returning cached/empty response (not forwarding to real tracker)")
    }

    private fun buildEmptyAnnounceResponse(): ByteArray {
        // Minimal valid bencoded tracker response with empty peer list (compact format)
        // d8:intervali7200e12:min intervali3600e5:peers0:e
        return "d8:intervali7200e12:min intervali3600e5:peers0:e".toByteArray(Charsets.ISO_8859_1)
    }

    private fun writeHttpResponse(output: OutputStream, body: ByteArray) {
        val header = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/plain\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Connection: close\r\n\r\n"
        output.write(header.toByteArray(Charsets.ISO_8859_1))
        output.write(body)
    }

    // ==================== TORRENT MODIFICATION ====================
    //
    // Extracts the real announce URL from a .torrent file and modifies it
    // to point to our local tracker proxy. The info dict bytes are preserved
    // exactly to ensure the info hash stays the same.

    private fun extractAnnounceUrl(torrentBytes: ByteArray): String? {
        return try {
            val (root, _) = decodeBencode(torrentBytes, 0)
            val dict = root as? BencodeValue.BDict ?: return null

            // Look for announce URL with arabp2p passkey
            val announceEntry = dict.entries.find { (k, _) ->
                k.bytes.contentEquals("announce".toByteArray())
            }
            if (announceEntry != null) {
                val url = String((announceEntry.second as BencodeValue.BString).bytes)
                if (url.contains("arabp2p.net") || PASSKEY_REGEX.containsMatchIn(url)) {
                    return url
                }
            }

            // Check announce-list for passkey URLs
            val announceListEntry = dict.entries.find { (k, _) ->
                k.bytes.contentEquals("announce-list".toByteArray())
            }
            if (announceListEntry != null) {
                val announceList = announceListEntry.second as? BencodeValue.BList
                announceList?.items?.forEach { tier ->
                    val tierList = tier as? BencodeValue.BList
                    tierList?.items?.forEach { tracker ->
                        val url = String((tracker as BencodeValue.BString).bytes)
                        if (url.contains("arabp2p.net") || PASSKEY_REGEX.containsMatchIn(url)) {
                            return url
                        }
                    }
                }
            }

            null
        } catch (e: Exception) {
            Log.e(TAG, "extractAnnounceUrl error: ${e.message}")
            null
        }
    }

    private fun modifyTorrentAnnounce(torrentBytes: ByteArray, newAnnounceUrl: String): ByteArray {
        // RAW BYTE REPLACEMENT — no bencode re-encoding!
        // We find the announce URL in the raw bytes and swap it.
        // We also remove the announce-list entry if present.
        // Everything else (including the info dict) stays exactly as-is.
        return try {
            val (root, _) = decodeBencode(torrentBytes, 0)
            val dict = root as? BencodeValue.BDict ?: return torrentBytes

            // Find the real announce URL from parsed dict
            val announceEntry = dict.entries.find { (k, _) ->
                k.bytes.contentEquals("announce".toByteArray())
            } ?: return torrentBytes
            val oldUrl = String((announceEntry.second as BencodeValue.BString).bytes)
            val oldUrlBytes = oldUrl.toByteArray(Charsets.ISO_8859_1)
            val newUrlBytes = newAnnounceUrl.toByteArray(Charsets.ISO_8859_1)

            // Build old and new bencode string entries: "length:url"
            val oldEntry = "${oldUrlBytes.size}:".toByteArray(Charsets.ISO_8859_1) + oldUrlBytes
            val newEntry = "${newUrlBytes.size}:".toByteArray(Charsets.ISO_8859_1) + newUrlBytes

            // Find the old announce value in raw bytes (after the "8:announce" key)
            val announceKey = "8:announce".toByteArray(Charsets.ISO_8859_1)
            var result = torrentBytes

            val keyIdx = findBytes(result, announceKey)
            if (keyIdx >= 0) {
                val valueStart = keyIdx + announceKey.size
                // Verify the value matches
                if (valueStart + oldEntry.size <= result.size &&
                    result.copyOfRange(valueStart, valueStart + oldEntry.size).contentEquals(oldEntry)) {
                    // Replace: keep bytes before, insert new entry, keep bytes after
                    val output = ByteArrayOutputStream()
                    output.write(result, 0, valueStart)
                    output.write(newEntry)
                    output.write(result, valueStart + oldEntry.size, result.size - valueStart - oldEntry.size)
                    result = output.toByteArray()
                    Log.d(TAG, "Raw byte replace: announce URL swapped (${oldUrlBytes.size} → ${newUrlBytes.size} bytes)")
                } else {
                    Log.w(TAG, "Announce value mismatch in raw bytes, skipping modification")
                }
            }

            // Remove announce-list if present (raw byte removal)
            val announceListKey = "13:announce-list".toByteArray(Charsets.ISO_8859_1)
            val alIdx = findBytes(result, announceListKey)
            if (alIdx >= 0) {
                // Find the end of the announce-list value using the parser
                val valueStart = alIdx + announceListKey.size
                val (_, valueEnd) = decodeBencode(result, valueStart)
                // Remove everything from alIdx to valueEnd
                val output = ByteArrayOutputStream()
                output.write(result, 0, alIdx)
                output.write(result, valueEnd, result.size - valueEnd)
                result = output.toByteArray()
                Log.d(TAG, "Raw byte remove: announce-list removed (bytes $alIdx..$valueEnd)")
            }

            result
        } catch (e: Exception) {
            Log.e(TAG, "modifyTorrentAnnounce error: ${e.message}, serving original .torrent")
            torrentBytes
        }
    }

    /** Finds the first occurrence of pattern in data, returns index or -1 */
    private fun findBytes(data: ByteArray, pattern: ByteArray): Int {
        if (pattern.isEmpty() || pattern.size > data.size) return -1
        for (i in 0..data.size - pattern.size) {
            var match = true
            for (j in pattern.indices) {
                if (data[i + j] != pattern[j]) {
                    match = false
                    break
                }
            }
            if (match) return i
        }
        return -1
    }

    // ==================== LOAD LINKS ====================
    //
    // Downloads the .torrent file with auth → modifies announce URL to use our proxy →
    // serves via local HTTP server → returns source.
    //
    // The local server also acts as a TRACKER PROXY:
    // - The .torrent file is modified to point to our local /announce endpoint
    // - CS's torrent engine announces to our proxy instead of the real tracker
    // - The proxy forwards ONLY the first announce to the real tracker (to get peers)
    // - All subsequent announces get an empty-peers response
    // - Result: the real tracker only sees one announce (downloaded=0), never tracks progress
    //
    // Only ONE source is returned (the .torrent) so CloudStream auto-selects it
    // and shows the file picker for multi-file torrents.

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

            // === INTERNAL TORRENTS: download .torrent → serve via local server ===
            var resolvedDownloadUrl = downloadUrl

            // Step 1: Resolve download URL with &f= parameter
            if (resolvedDownloadUrl.isBlank() || !resolvedDownloadUrl.contains("&f=")) {
                val detailPageUrl = if (detailUrl.isNotBlank()) detailUrl
                    else "$mainUrl/index.php?page=torrent-details&id=$torrentId"

                try {
                    val request = Request.Builder()
                        .url(toAbsoluteUrl(detailPageUrl))
                        .headers(getAuthHeaders(referer = "$mainUrl/").toHeaders())
                        .build()

                    authClient.newCall(request).execute().use { response ->
                        val body = response.body?.string() ?: ""
                        val detailDoc = Jsoup.parse(body, toAbsoluteUrl(detailPageUrl))

                        val dlLink = detailDoc.selectFirst("a[href*=download.php]")
                            ?: detailDoc.selectFirst("td#Title h1 a[href*=download.php]")
                        if (dlLink != null) {
                            resolvedDownloadUrl = toAbsoluteUrl(dlLink.attr("href"))
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

            // Step 2: Download .torrent → serve via local server
            if (resolvedDownloadUrl.isNotBlank() && resolvedDownloadUrl.contains("&f=")) {
                try {
                    val result = downloadTorrentFile(resolvedDownloadUrl)
                    foundLink = handleTorrentDownloadResult(result, resolvedDownloadUrl, callback) || foundLink
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

    /**
     * Processes a TorrentDownloadResult: serves .torrent via local server.
     * Returns true if a source was added.
     */
    private suspend fun handleTorrentDownloadResult(
        result: TorrentDownloadResult,
        downloadUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var foundLink = false

        when (result) {
            is TorrentDownloadResult.Success -> {
                Log.d(TAG, "Downloaded .torrent: ${result.bytes.size} bytes")

                // Serve .torrent via local HTTP server (with original tracker intact)
                val localUrl = startLocalTorrentServer(result.bytes)
                if (localUrl != null) {
                    Log.d(TAG, "Serving .torrent via local server: $localUrl")
                    callback(
                        newExtractorLink(
                            source = this.name,
                            name = this.name,
                            url = localUrl,
                            type = ExtractorLinkType.TORRENT
                        )
                    )
                    foundLink = true
                }
            }
            is TorrentDownloadResult.DailyLimitExceeded -> {
                Log.w(TAG, "DAILY DOWNLOAD LIMIT EXCEEDED, thanking and retrying...")
                val retryResult = downloadTorrentFile(downloadUrl)
                when (retryResult) {
                    is TorrentDownloadResult.Success -> {
                        Log.d(TAG, "Retry succeeded!")

                        val localUrl = startLocalTorrentServer(retryResult.bytes)
                        if (localUrl != null) {
                            callback(
                                newExtractorLink(
                                    source = this.name,
                                    name = this.name,
                                    url = localUrl,
                                    type = ExtractorLinkType.TORRENT
                                )
                            )
                            foundLink = true
                        }
                    }
                    else -> {
                        Log.e(TAG, "Download still failed after thank + retry")
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
                Log.e(TAG, "Session expired")
            }
            is TorrentDownloadResult.Error -> {
                Log.e(TAG, "Download error: ${result.message}")
            }
        }

        return foundLink
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
                .headers(getAuthHeaders(referer = pageUrl).toHeaders())
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
                .headers(getAuthHeaders(referer = "$mainUrl/").toHeaders())
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
    // Matches fosstorrents.com/t2m/ conversion:
    //   1. Find the raw "info" dict bytes in the .torrent file
    //   2. SHA1 hash those exact raw bytes → info hash (NO re-encoding!)
    //   3. Base32-encode the hash → magnet xt parameter
    //   4. Build magnet URI with xt, dn, and standard &tr= parameters
    //
    // CRITICAL: We hash the RAW bytes of the info dict from the original file,
    // NOT a re-encoded version. Re-encoding can produce different bytes → wrong hash.
    //
    // Tracker URLs always include BOTH:
    //   &tr= non-passkey announce (e.g. http://www.arabp2p.net:2052/announce)
    //   &tr= passkey announce (e.g. http://www.arabp2p.net:2052/3d365.../announce)
    // Using standard &tr= format (not tr.N) for maximum client compatibility.

    private fun torrentToMagnet(torrentBytes: ByteArray): String? {
        return try {
            // === Step 1: Find the raw info dict bytes ===
            val infoBytesResult = findInfoDictBytes(torrentBytes)
            if (infoBytesResult == null) {
                Log.e(TAG, "torrentToMagnet: could not find info dict in .torrent file")
                return null
            }

            // === Step 2: SHA1 hash the raw info dict bytes ===
            val sha1 = java.security.MessageDigest.getInstance("SHA-1").digest(infoBytesResult.bytes)

            // === Step 3: Convert to Base32 ===
            val infoHashBase32 = base32Encode(sha1)

            Log.d(TAG, "torrentToMagnet: info hash (hex) = ${sha1.joinToString("") { "%02x".format(it) }}")
            Log.d(TAG, "torrentToMagnet: info hash (base32) = $infoHashBase32")

            // === Step 4: Parse the torrent for metadata (name, trackers) ===
            val (root, _) = decodeBencode(torrentBytes, 0)
            val dict = root as? BencodeValue.BDict ?: return null

            val infoEntry = dict.entries.find { (k, _) ->
                k.bytes.contentEquals("info".toByteArray())
            } ?: return null

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

            // === Collect all tracker URLs from the .torrent file ===
            val trackerUrls = mutableListOf<String>()

            // Single announce URL
            val announceEntry = dict.entries.find { (k, _) ->
                k.bytes.contentEquals("announce".toByteArray())
            }
            if (announceEntry != null) {
                trackerUrls.add(String((announceEntry.second as BencodeValue.BString).bytes))
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
                        if (trackerUrl !in trackerUrls) {
                            trackerUrls.add(trackerUrl)
                        }
                    }
                }
            }

            // === Extract passkey from tracker URLs and cache it ===
            for (url in trackerUrls) {
                val match = PASSKEY_REGEX.find(url)
                if (match != null) {
                    cachedPasskey = match.groupValues[1]
                    Log.d(TAG, "torrentToMagnet: extracted passkey from announce URL")
                    break
                }
            }

            // === Build tracker parameters ===
            // Always include BOTH non-passkey and passkey announce URLs.
            // Use standard &tr= format (not tr.N) for maximum client compatibility.
            val seenUrls = mutableSetOf<String>()

            for (url in trackerUrls) {
                if (!url.contains("arabp2p.net")) {
                    // Non-arabp2p tracker (e.g. public tracker) — add as-is
                    if (seenUrls.add(url)) {
                        magnet.append("&tr=").append(URLEncoder.encode(url, "UTF-8"))
                    }
                    continue
                }

                val passkeyMatch = PASSKEY_REGEX.find(url)
                if (passkeyMatch != null) {
                    // URL HAS a passkey (e.g. http://www.arabp2p.net:2052/3d365.../announce)
                    val pk = passkeyMatch.groupValues[1]
                    val nonPasskeyUrl = url.replace("/$pk/announce", "/announce")

                    // Add non-passkey first
                    if (seenUrls.add(nonPasskeyUrl)) {
                        magnet.append("&tr=").append(URLEncoder.encode(nonPasskeyUrl, "UTF-8"))
                    }
                    // Add passkey version
                    if (seenUrls.add(url)) {
                        magnet.append("&tr=").append(URLEncoder.encode(url, "UTF-8"))
                    }
                } else {
                    // URL does NOT have a passkey
                    if (seenUrls.add(url)) {
                        magnet.append("&tr=").append(URLEncoder.encode(url, "UTF-8"))
                    }
                    // Also add passkey version if we have a cached passkey
                    val pk = cachedPasskey
                    if (pk != null) {
                        val passkeyUrl = url.replace("/announce", "/$pk/announce")
                        if (seenUrls.add(passkeyUrl)) {
                            magnet.append("&tr=").append(URLEncoder.encode(passkeyUrl, "UTF-8"))
                        }
                    }
                }
            }

            // If no tracker URLs were found, construct from cached passkey
            if (trackerUrls.isEmpty() && cachedPasskey != null) {
                val nonPasskeyUrl = "http://www.arabp2p.net:2052/announce"
                val passkeyUrl = "http://www.arabp2p.net:2052/${cachedPasskey}/announce"
                magnet.append("&tr=").append(URLEncoder.encode(nonPasskeyUrl, "UTF-8"))
                magnet.append("&tr=").append(URLEncoder.encode(passkeyUrl, "UTF-8"))
            }

            Log.d(TAG, "torrentToMagnet: → ${magnet.toString().take(150)}...")
            magnet.toString()
        } catch (e: Exception) {
            Log.e(TAG, "torrentToMagnet error: ${e.message}")
            null
        }
    }

    // ==================== FIND INFO DICT RAW BYTES ====================
    //
    // Extracts the exact raw bytes of the "info" dictionary from the .torrent file.
    // This is critical: we must hash the ORIGINAL bytes, not a re-encoded version.
    // Re-encoding can produce slightly different bytes → completely different SHA1 hash.

    private data class InfoBytesResult(val bytes: ByteArray, val startOffset: Int, val endOffset: Int)

    private fun findInfoDictBytes(data: ByteArray): InfoBytesResult? {
        return try {
            if (data.isEmpty() || data[0] != 'd'.code.toByte()) return null

            var pos = 1 // skip opening 'd'

            while (pos < data.size && data[pos] != 'e'.code.toByte()) {
                // Parse the key (must be a bencode string)
                val (key, afterKey) = decodeBencodeString(data, pos)
                val keyStr = String((key as BencodeValue.BString).bytes)

                if (keyStr == "info") {
                    // Found the "info" key! The value starts at afterKey.
                    val valueStart = afterKey
                    // Parse the value to find where it ends
                    val (_, valueEnd) = decodeBencode(data, valueStart)

                    // Extract the exact raw bytes of the info dict
                    val infoBytes = data.sliceArray(valueStart until valueEnd)
                    Log.d(TAG, "findInfoDictBytes: found info dict at offset $valueStart..$valueEnd (${infoBytes.size} bytes)")
                    return InfoBytesResult(infoBytes, valueStart, valueEnd)
                }

                // Not the "info" key — skip over this key's value
                val (_, afterValue) = decodeBencode(data, afterKey)
                pos = afterValue
            }

            Log.e(TAG, "findInfoDictBytes: 'info' key not found in .torrent root dict")
            null
        } catch (e: Exception) {
            Log.e(TAG, "findInfoDictBytes error: ${e.message}")
            null
        }
    }

    // ==================== PASSKEY FETCH FROM WEBSITE ====================
    //
    // Fallback: if the .torrent file doesn't contain the passkey,
    // try to extract it from the user's profile page on arabp2p.
    // The passkey is usually shown in the user control panel or
    // can be derived from the tracker URL shown on torrent detail pages.

    private fun fetchPasskeyFromWebsite(): String? {
        if (cachedPasskey != null) return cachedPasskey
        if (!ensureLogin()) return null

        return try {
            // Try fetching user profile page to find passkey
            val profileRequest = Request.Builder()
                .url("$mainUrl/index.php?page=usercp")
                .headers(getAuthHeaders(referer = "$mainUrl/").toHeaders())
                .build()

            authClient.newCall(profileRequest).execute().use { response ->
                val body = response.body?.string() ?: return null
                val doc = Jsoup.parse(body, "$mainUrl/index.php?page=usercp")

                // Look for passkey in the page (usually shown as a 32-char hex string)
                // Try various selectors where xbtit-based trackers show passkey
                val passkeyElements = doc.select("td:contains(passkey) + td, td:contains(Passkey) + td, " +
                    "td:contains(مفتاح) + td, span.passkey, #passkey")
                for (el in passkeyElements) {
                    val match = Regex("[0-9a-f]{32}").find(el.text())
                    if (match != null) {
                        cachedPasskey = match.groupValues[0]
                        Log.d(TAG, "fetchPasskeyFromWebsite: found passkey on profile page")
                        return cachedPasskey
                    }
                }

                // Fallback: search entire page for 32-char hex string that looks like a passkey
                val bodyMatch = Regex("[0-9a-f]{32}").find(body)
                if (bodyMatch != null) {
                    cachedPasskey = bodyMatch.groupValues[0]
                    Log.d(TAG, "fetchPasskeyFromWebsite: found passkey in page body")
                    return cachedPasskey
                }

                Log.w(TAG, "fetchPasskeyFromWebsite: passkey not found on profile page")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchPasskeyFromWebsite error: ${e.message}")
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

        // Note: For SHA1 (20 bytes), we get exactly 32 Base32 chars (160 bits / 5 = 32),
        // which is already a multiple of 8, so no padding is needed.
        // We skip padding because some torrent clients reject '=' in magnet URIs.

        return result.toString()
    }

    // ==================== BENCODE PARSER ====================
    //
    // Minimal bencode parser/encoder for .torrent files.
    // Handles: strings (<n>:<bytes>), integers (i<n>e), lists (l...e), dicts (d...e)

    sealed class BencodeValue {
        data class BString(val bytes: ByteArray) : BencodeValue()
        data class BInt(val value: Long) : BencodeValue()
        data class BList(val items: List<BencodeValue>) : BencodeValue()
        data class BDict(val entries: MutableList<Pair<BString, BencodeValue>>) : BencodeValue()
    }

    private data class BencodeParseResult(val value: BencodeValue, val nextPos: Int)

    private fun decodeBencode(data: ByteArray, startPos: Int): BencodeParseResult {
        if (startPos >= data.size) throw IllegalArgumentException("Unexpected end of data at $startPos")

        val firstByte = data[startPos].toInt().toChar()

        return when {
            firstByte == 'd' -> { // Dictionary
                var pos = startPos + 1
                val entries = mutableListOf<Pair<BencodeValue.BString, BencodeValue>>()

                while (pos < data.size && data[pos].toInt().toChar() != 'e') {
                    val (key, afterKey) = decodeBencode(data, pos)
                    val keyStr = key as? BencodeValue.BString
                        ?: throw IllegalArgumentException("Dict key must be string at $pos")
                    val (value, afterValue) = decodeBencode(data, afterKey)
                    entries.add(keyStr to value)
                    pos = afterValue
                }

                BencodeParseResult(BencodeValue.BDict(entries), pos + 1) // skip 'e'
            }

            firstByte == 'l' -> { // List
                var pos = startPos + 1
                val items = mutableListOf<BencodeValue>()

                while (pos < data.size && data[pos].toInt().toChar() != 'e') {
                    val (item, afterItem) = decodeBencode(data, pos)
                    items.add(item)
                    pos = afterItem
                }

                BencodeParseResult(BencodeValue.BList(items), pos + 1) // skip 'e'
            }

            firstByte == 'i' -> { // Integer
                val endPos = indexOfByte(data, 'e'.code.toByte(), startPos + 1)
                val numStr = String(data, startPos + 1, endPos - startPos - 1)
                BencodeParseResult(BencodeValue.BInt(numStr.toLong()), endPos + 1)
            }

            firstByte in '0'..'9' -> { // String
                decodeBencodeString(data, startPos)
            }

            else -> throw IllegalArgumentException("Unexpected byte '${firstByte}' at $startPos")
        }
    }

    private fun decodeBencodeString(data: ByteArray, startPos: Int): BencodeParseResult {
        // Format: <length>:<bytes>
        val colonPos = indexOfByte(data, ':'.code.toByte(), startPos)
        val lengthStr = String(data, startPos, colonPos - startPos)
        val length = lengthStr.toInt()
        val stringStart = colonPos + 1
        val bytes = data.sliceArray(stringStart until stringStart + length)
        return BencodeParseResult(BencodeValue.BString(bytes), stringStart + length)
    }

    private fun indexOfByte(data: ByteArray, target: Byte, startPos: Int): Int {
        for (i in startPos until data.size) {
            if (data[i] == target) return i
        }
        throw IllegalArgumentException("Byte '${target.toInt().toChar()}' not found after $startPos")
    }

    // ==================== UTILITY ====================

    private fun cleanTitleText(text: String): String {
        return text
            .replace(Regex("<[^>]*>"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
