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
import org.jsoup.nodes.Element
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpCookie
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class Arabp : MainAPI() {
    override var mainUrl = "https://www.arabp2p.net"
    override var name = "Arabp"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    companion object {
        private const val TAG = "Arabp_Log"
        private const val LOGIN_USERNAME = "armano"
        private const val LOGIN_PASSWORD = "ARmano01**"
        private val DIGITS = Regex("\\d+")
    }

    // Images require Referer header to avoid 403
    private val imageHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
        "Referer" to "$mainUrl/"
    )

    /**
     * Public trackers injected into magnet links as fallback.
     */
    private val PUBLIC_TRACKERS = listOf(
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://open.tracker.cl:1337/announce",
        "udp://tracker.openbittorrent.com:6969/announce",
        "udp://open.stealth.si:80/announce",
        "udp://tracker.torrent.eu.org:451/announce",
        "udp://exodus.desync.com:6969/announce",
        "udp://tracker.tiny-vps.com:6969/announce",
        "udp://tracker.moeking.me:6969/announce",
        "udp://explodie.org:6969/announce",
        "udp://tracker.pomf.se:80/announce",
        "udp://tracker.dler.org:6969/announce",
        "udp://p4p.arenabg.com:1337/announce",
        "udp://movies.zsw.ca:6969/announce",
        "udp://retracker.lanta-net.ru:2710/announce",
        "http://tracker.openbittorrent.com:80/announce"
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
        if (isLoggedIn) {
            return true
        }

        return try {
            // Step 1: GET the login page to establish PHPSESSID
            val initRequest = Request.Builder()
                .url("$mainUrl/index.php?page=login")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
                .build()
            authClient.newCall(initRequest).execute().use { response ->
                Log.d(TAG, "Init login page: ${response.code}")
            }

            // Step 2: POST login credentials
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

                // Login success = 302 redirect OR page contains logout link OR no login form
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

    // ==================== HOME PAGE ====================

    override val mainPage = mainPageOf(
        "$mainUrl/index.php?page=anime-listing" to "قائمة الانمي"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = if (page > 1) "${request.data}&pages=$page" else request.data
        val doc = app.get(url).document
        val homeSets = mutableListOf<HomePageList>()

        try {
            val items = doc.select("div.listing_div1").mapNotNull { element ->
                toSearchResult(element)
            }

            if (items.isNotEmpty()) {
                homeSets.add(HomePageList(request.name, items))
            }

            val hasNextPage = items.isNotEmpty()
            return newHomePageResponse(homeSets, hasNextPage)
        } catch (e: Exception) {
            Log.e(TAG, "MainPage Error: ${e.message}")
        }
        return newHomePageResponse(homeSets)
    }

    private fun toSearchResult(element: Element): SearchResponse? {
        return try {
            val linkEl = element.selectFirst("div.listing_div2 a")
                ?: element.selectFirst("a[href*=anime-listing]")
                ?: return null

            val href = toAbsoluteUrl(linkEl.attr("href"))

            val rawTitle = linkEl.html()
                .replace("<br>", " ")
                .replace("<br/>", " ")
                .replace("<br />", " ")
                .trim()
            val title = cleanTitleText(rawTitle)

            val posterUrl = element.selectFirst("img.listing_poster")?.attr("src")
                ?: element.selectFirst("img")?.attr("src")
                ?: ""

            val tvType = when {
                title.contains("فيلم", ignoreCase = true) || title.contains("Movie", ignoreCase = true) -> TvType.AnimeMovie
                else -> TvType.Anime
            }

            newAnimeSearchResponse(title, href, tvType) {
                this.addPoster(toAbsoluteUrl(posterUrl), headers = imageHeaders)
            }
        } catch (e: Exception) {
            Log.e(TAG, "toSearchResult Error: ${e.message}")
            null
        }
    }

    // ==================== SEARCH ====================
    //
    // Searches TWO sources:
    // 1. Anime listing (PUBLIC - no login needed) - shows anime entries
    // 2. Torrents page (PRIVATE - requires login) - shows individual torrents
    //
    // If login works, you'll see results from BOTH sources.
    // If login fails, you'll only see anime listing results.

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val results = mutableListOf<SearchResponse>()

        // Source 1: PUBLIC anime listing (no login needed)
        try {
            val animeUrl = "$mainUrl/index.php?page=anime-listing&search=$encoded"
            val animeDoc = app.get(animeUrl).document
            val animeResults = animeDoc.select("div.listing_div1").mapNotNull { toSearchResult(it) }
            Log.d(TAG, "Anime listing search: found ${animeResults.size} results")
            results.addAll(animeResults)
        } catch (e: Exception) {
            Log.e(TAG, "Anime listing search error: ${e.message}")
        }

        // Source 2: PRIVATE torrents page (requires login)
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

                    val torrentResults = torrentsDoc.select("table.lista2t tr.lista2, table tr:has(a[href*=torrent-details])").mapNotNull { row ->
                        torrentRowToSearchResult(row)
                    }
                    Log.d(TAG, "Torrents search (LOGGED IN): found ${torrentResults.size} results")
                    results.addAll(torrentResults)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Torrents search error: ${e.message}")
            }
        } else {
            Log.w(TAG, "Torrents search SKIPPED: login failed")
        }

        Log.d(TAG, "Total search results: ${results.size}")
        return results
    }

    /**
     * Convert a torrent row from the torrents listing page to a SearchResponse.
     * Format is different from the anime listing page.
     */
    private fun torrentRowToSearchResult(row: Element): SearchResponse? {
        return try {
            val nameLink = row.selectFirst("a[href*=torrent-details]") ?: return null
            val name = cleanTitleText(nameLink.text())
            val detailHref = toAbsoluteUrl(nameLink.attr("href"))

            // Extract torrent ID from detail link
            val torrentId = DIGITS.find(detailHref.substringAfter("id="))?.value ?: return null

            // Find download URL with &f= parameter
            val downloadLink = row.selectFirst("a[href*=download.php]")
            val downloadHref = downloadLink?.attr("href") ?: ""

            // Extract size and seeders
            val tds = row.select("td")
            val size = tds.getOrNull(3)?.text()?.trim() ?: ""
            val seeders = tds.getOrNull(4)?.text()?.trim() ?: ""

            // Check category for TvType
            val categoryLink = row.selectFirst("a[href*=category=]")
            val categoryName = categoryLink?.text()?.trim() ?: ""
            val tvType = when {
                categoryName.contains("فيلم", ignoreCase = true) ||
                        categoryName.contains("Movie", ignoreCase = true) ||
                        name.contains("فيلم", ignoreCase = true) -> TvType.AnimeMovie
                else -> TvType.Anime
            }

            val displayName = buildString {
                append(name)
                if (size.isNotEmpty()) append(" | $size")
                if (seeders.isNotEmpty()) append(" | ▲$seeders")
            }

            // Store data as: torrent_id|detail_url|download_url
            val epData = "$torrentId|$detailHref|${toAbsoluteUrl(downloadHref)}"

            newAnimeSearchResponse(displayName, epData, tvType) {
                this.posterUrl = ""
            }
        } catch (e: Exception) {
            Log.e(TAG, "torrentRowToSearchResult Error: ${e.message}")
            null
        }
    }

    // ==================== LOAD (Detail Page) ====================

    override suspend fun load(url: String): LoadResponse? {
        // Check if URL is encoded torrent data from search (format: torrentId|detailUrl|downloadUrl)
        if (url.contains("|")) {
            return loadFromTorrentData(url)
        }

        val fullUrl = toAbsoluteUrl(url)
        val doc = app.get(fullUrl).document

        return try {
            val h1 = doc.selectFirst("div.listing_div_id h1")
            val rawTitle = h1?.html()
                ?.replace("<br>", " ")
                ?.replace("<br/>", " ")
                ?.replace("<br />", " ")
                ?.trim()
                ?: ""
            val title = cleanTitleText(rawTitle)

            val posterUrl = doc.selectFirst("div.listing_div_id img.listing_poster")?.attr("src")
                ?: doc.selectFirst("img.listing_poster")?.attr("src")
                ?: ""

            val desc = ""

            val rows = doc.select("table#listing_table tr")
            val episodes = rows.mapNotNull { row ->
                val nameLink = row.selectFirst("a[href*=torrent-details]")
                    ?: return@mapNotNull null

                val epName = cleanTitleText(nameLink.text())
                val detailHref = nameLink.attr("href")
                val torrentId = DIGITS.find(detailHref.substringAfter("id="))?.value ?: return@mapNotNull null

                // The download URL WITH &f= parameter is critical!
                // Without &f=, download.php returns 404
                val downloadLink = row.selectFirst("a[href*=download.php]")
                val downloadHref = downloadLink?.attr("href") ?: ""

                val epData = "$torrentId|${toAbsoluteUrl(detailHref)}|${toAbsoluteUrl(downloadHref)}"

                val tds = row.select("td")
                val size = tds.getOrNull(3)?.text()?.trim() ?: ""
                val seeders = tds.getOrNull(4)?.text()?.trim() ?: ""

                val displayName = buildString {
                    append(epName)
                    if (size.isNotEmpty()) append(" | $size")
                    if (seeders.isNotEmpty()) append(" | ▲$seeders")
                }

                newEpisode(epData) {
                    name = displayName
                    this.posterUrl = toAbsoluteUrl(posterUrl)
                }
            }

            if (episodes.isEmpty()) {
                newMovieLoadResponse(title, fullUrl, TvType.Anime, "0|$fullUrl|") {
                    this.posterUrl = toAbsoluteUrl(posterUrl)
                    this.posterHeaders = imageHeaders
                    this.plot = desc
                }
            } else {
                newTvSeriesLoadResponse(title, fullUrl, TvType.Anime, episodes) {
                    this.posterUrl = toAbsoluteUrl(posterUrl)
                    this.posterHeaders = imageHeaders
                    this.plot = desc
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Load Error: ${e.message}")
            null
        }
    }

    /**
     * Handle load() when the URL is encoded torrent data from search results.
     * Format: "torrentId|detailUrl|downloadUrl"
     * Creates a single movie/episode that will call loadLinks() with this data.
     */
    private suspend fun loadFromTorrentData(data: String): LoadResponse? {
        val parts = data.split("|", limit = 3)
        val torrentId = parts.getOrNull(0) ?: "0"
        val detailUrl = parts.getOrNull(1) ?: ""
        val downloadUrl = parts.getOrNull(2) ?: ""

        // Try to get more info from the detail page
        var title = "Torrent #$torrentId"
        var posterUrl = ""

        try {
            if (ensureLogin() && detailUrl.isNotBlank()) {
                val request = Request.Builder()
                    .url(toAbsoluteUrl(detailUrl))
                    .headers(getAuthHeaders(referer = "$mainUrl/").toOkHttpHeaders())
                    .build()

                authClient.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: ""
                    val detailDoc = Jsoup.parse(body, toAbsoluteUrl(detailUrl))

                    // Get title from detail page
                    title = detailDoc.selectFirst("td#Title h1")?.text()?.trim() ?: title
                    posterUrl = detailDoc.selectFirst("img.listing_poster")?.attr("src") ?: ""
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "loadFromTorrentData detail fetch error: ${e.message}")
        }

        return newMovieLoadResponse(title, data, TvType.Anime, data) {
            this.posterUrl = toAbsoluteUrl(posterUrl)
            this.posterHeaders = imageHeaders
        }
    }

    // ==================== LOAD LINKS ====================
    //
    // STRATEGY: We download the .torrent file OURSELVES (with auth cookies),
    // then build a magnet link from its contents.
    //
    // Why not ExtractorLinkType.TORRENT?
    //   CloudStream's internal downloader does NOT send our auth cookies,
    //   so the server returns empty/403, causing "no content to map" error.
    //
    // Why magnet instead of .torrent file?
    //   CloudStream cannot open .torrent files directly, and data: URIs
    //   are not supported. Magnet links are the only working approach.
    //
    // For private trackers: the magnet includes the tracker's announce URL
    // with passkey as the FIRST tracker, so CloudStream's torrent engine
    // can connect to the private tracker to get peers.
    //

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("|", limit = 3)
        val torrentId = parts.getOrNull(0) ?: "0"
        val detailUrl = parts.getOrNull(1) ?: ""
        val downloadUrl = parts.getOrNull(2) ?: ""

        Log.d(TAG, "loadLinks: id=$torrentId, detail=$detailUrl, download=$downloadUrl")

        return try {
            if (!ensureLogin()) {
                Log.e(TAG, "Cannot load links: login failed")
                return false
            }

            var foundLink = false
            var resolvedDownloadUrl = downloadUrl

            // Step 1: Resolve the download URL with &f= parameter
            if (resolvedDownloadUrl.isBlank() || !resolvedDownloadUrl.contains("&f=")) {
                Log.d(TAG, "Download URL missing &f= param, fetching detail page...")
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

                        // Look for the download link with &f= parameter
                        val dlLink = detailDoc.selectFirst("a[href*=download.php]")
                            ?: detailDoc.selectFirst("td#Title h1 a[href*=download.php]")

                        if (dlLink != null) {
                            resolvedDownloadUrl = toAbsoluteUrl(dlLink.attr("href"))
                            Log.d(TAG, "Found download URL from detail page: $resolvedDownloadUrl")
                        }

                        // Also check for direct magnet links on the detail page (external torrents)
                        val magnetLinks = detailDoc.select("a[href^=magnet:]")
                        for (magnetEl in magnetLinks) {
                            val magnetUrl = magnetEl.attr("href")
                            callback(
                                newExtractorLink(
                                    source = this.name,
                                    name = "${this.name} Magnet",
                                    url = magnetUrl,
                                    type = ExtractorLinkType.MAGNET
                                )
                            )
                            foundLink = true
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to fetch detail page: ${e.message}")
                }
            }

            // Step 2: Download the .torrent file OURSELVES (with auth cookies)
            // and build a magnet link from it.
            if (resolvedDownloadUrl.isNotBlank() && resolvedDownloadUrl.contains("&f=")) {
                Log.d(TAG, "Downloading .torrent file to build magnet...")
                try {
                    val magnet = downloadAndBuildMagnet(resolvedDownloadUrl)
                    if (magnet != null) {
                        callback(
                            newExtractorLink(
                                source = this.name,
                                name = "${this.name}",
                                url = magnet,
                                type = ExtractorLinkType.MAGNET
                            )
                        )
                        foundLink = true
                    } else {
                        Log.e(TAG, "Failed to build magnet from .torrent")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Magnet build error: ${e.message}")
                }
            }

            if (!foundLink) {
                Log.e(TAG, "No links found for torrent id=$torrentId")
            }

            foundLink
        } catch (e: Exception) {
            Log.e(TAG, "loadLinks Error: ${e.message}")
            false
        }
    }

    /**
     * Download a .torrent file using our authenticated client,
     * extract info hash and trackers, and build a magnet link.
     *
     * IMPORTANT: The private tracker announce URL is placed FIRST
     * so CloudStream's torrent engine tries it before public trackers.
     */
    private fun downloadAndBuildMagnet(url: String): String? {
        return try {
            val request = Request.Builder()
                .url(toAbsoluteUrl(url))
                .headers(getAuthHeaders(referer = "$mainUrl/").toOkHttpHeaders())
                .build()

            authClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Torrent download failed: HTTP ${response.code}")
                    return null
                }

                val torrentBytes = response.body?.bytes() ?: return null
                if (torrentBytes.size < 20 || torrentBytes[0] != 'd'.code.toByte()) {
                    Log.e(TAG, "Invalid .torrent data (${torrentBytes.size} bytes)")
                    return null
                }

                Log.d(TAG, "Downloaded .torrent: ${torrentBytes.size} bytes")

                val infoHash = computeInfoHash(torrentBytes) ?: return null
                val privateTrackers = extractTrackers(torrentBytes)
                val torrentName = extractTorrentName(torrentBytes)

                val magnet = StringBuilder("magnet:?xt=urn:btih:$infoHash")
                if (torrentName.isNotEmpty()) {
                    magnet.append("&dn=").append(URLEncoder.encode(torrentName, "UTF-8"))
                }

                // Private tracker announce goes FIRST — it has the passkey and
                // will respond with a peer list for this specific torrent.
                for (tracker in privateTrackers) {
                    magnet.append("&tr=").append(URLEncoder.encode(tracker, "UTF-8"))
                }

                // Public trackers as fallback (for non-private torrents or if
                // the private tracker is unreachable)
                for (tracker in PUBLIC_TRACKERS) {
                    magnet.append("&tr=").append(URLEncoder.encode(tracker, "UTF-8"))
                }

                Log.d(TAG, "Built magnet: hash=$infoHash, private_trackers=${privateTrackers.size}, public_trackers=${PUBLIC_TRACKERS.size}")
                Log.d(TAG, "Magnet URL length: ${magnet.length}")
                magnet.toString()
            }
        } catch (e: Exception) {
            Log.e(TAG, "downloadAndBuildMagnet Error: ${e.message}")
            null
        }
    }

    // ==================== TORRENT PARSING ====================

    /**
     * Compute the info hash by finding the raw "info" dictionary in the .torrent
     * and SHA-1 hashing its bytes directly.
     */
    private fun computeInfoHash(data: ByteArray): String? {
        return try {
            // Find "4:info" in the raw data
            val infoKey = "4:info".toByteArray(Charsets.US_ASCII)
            val infoKeyOffset = findBytes(data, infoKey, 0)
            if (infoKeyOffset < 0) {
                Log.e(TAG, "Could not find '4:info' in .torrent")
                return null
            }

            val infoStart = infoKeyOffset + infoKey.size
            val infoEnd = findBencodeEnd(data, infoStart)
            if (infoEnd < 0) {
                Log.e(TAG, "Could not find end of info dictionary")
                return null
            }

            val infoBytes = data.copyOfRange(infoStart, infoEnd)
            val md = MessageDigest.getInstance("SHA-1")
            val hash = md.digest(infoBytes).joinToString("") { "%02x".format(it) }

            Log.d(TAG, "Info hash: $hash (${infoBytes.size} bytes)")
            hash
        } catch (e: Exception) {
            Log.e(TAG, "computeInfoHash Error: ${e.message}")
            null
        }
    }

    /**
     * Extract the announce URL and announce-list from the .torrent file.
     */
    private fun extractTrackers(data: ByteArray): List<String> {
        val trackers = mutableListOf<String>()
        try {
            val decoded = parseBencode(data) as? Map<*, *> ?: return trackers

            when (val announce = decoded["announce"]) {
                is ByteArray -> trackers.add(String(announce))
                is String -> trackers.add(announce)
            }

            val announceList = decoded["announce-list"] as? List<*>
            if (announceList != null) {
                for (tier in announceList) {
                    when (tier) {
                        is List<*> -> {
                            for (tracker in tier) {
                                when (tracker) {
                                    is ByteArray -> trackers.add(String(tracker))
                                    is String -> trackers.add(tracker)
                                }
                            }
                        }
                        is ByteArray -> trackers.add(String(tier))
                        is String -> trackers.add(tier)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "extractTrackers Error: ${e.message}")
        }

        Log.d(TAG, "Extracted ${trackers.size} tracker(s)")
        return trackers
    }

    private fun extractTorrentName(data: ByteArray): String {
        return try {
            val decoded = parseBencode(data) as? Map<*, *> ?: return ""
            val info = decoded["info"] as? Map<*, *> ?: return ""
            when (val name = info["name"]) {
                is ByteArray -> String(name)
                is String -> name
                else -> ""
            }
        } catch (e: Exception) {
            ""
        }
    }

    // ==================== BENCODE PARSER ====================

    private fun parseBencode(data: ByteArray): Any {
        val result = parseBencodeAt(data, 0)
        return result.first
    }

    private fun parseBencodeAt(data: ByteArray, offset: Int): Pair<Any, Int> {
        val first = data[offset].toInt().toChar()
        return when {
            first == 'd' -> {
                val result = mutableMapOf<String, Any>()
                var pos = offset + 1
                while (pos < data.size && data[pos].toInt().toChar() != 'e') {
                    val key = parseBencodeAt(data, pos)
                    pos = key.second
                    val value = parseBencodeAt(data, pos)
                    pos = value.second
                    val keyStr = when (key.first) {
                        is ByteArray -> String(key.first as ByteArray)
                        is String -> key.first as String
                        else -> key.first.toString()
                    }
                    result[keyStr] = value.first
                }
                result to pos + 1
            }
            first == 'l' -> {
                val result = mutableListOf<Any>()
                var pos = offset + 1
                while (pos < data.size && data[pos].toInt().toChar() != 'e') {
                    val item = parseBencodeAt(data, pos)
                    pos = item.second
                    result.add(item.first)
                }
                result to pos + 1
            }
            first == 'i' -> {
                val end = indexOfByte(data, 'e'.code.toByte(), offset + 1)
                val num = String(data, offset + 1, end - offset - 1).toLong()
                num to end + 1
            }
            first in '0'..'9' -> {
                val colon = indexOfByte(data, ':'.code.toByte(), offset)
                val length = String(data, offset, colon - offset).toInt()
                val start = colon + 1
                data.copyOfRange(start, start + length) to start + length
            }
            else -> throw IllegalArgumentException("Invalid bencode at offset $offset: $first")
        }
    }

    // ==================== RAW BYTE HELPERS ====================

    private fun findBencodeEnd(data: ByteArray, offset: Int): Int {
        val first = data[offset].toInt().toChar()
        return when {
            first == 'd' -> {
                var pos = offset + 1
                while (pos < data.size && data[pos].toInt().toChar() != 'e') {
                    pos = findBencodeEnd(data, pos)
                    pos = findBencodeEnd(data, pos)
                }
                pos + 1
            }
            first == 'l' -> {
                var pos = offset + 1
                while (pos < data.size && data[pos].toInt().toChar() != 'e') {
                    pos = findBencodeEnd(data, pos)
                }
                pos + 1
            }
            first == 'i' -> {
                val end = indexOfByte(data, 'e'.code.toByte(), offset + 1)
                end + 1
            }
            first in '0'..'9' -> {
                val colon = indexOfByte(data, ':'.code.toByte(), offset)
                val length = String(data, offset, colon - offset).toInt()
                colon + 1 + length
            }
            else -> throw IllegalArgumentException("Invalid bencode at offset $offset: $first")
        }
    }

    private fun findBytes(data: ByteArray, pattern: ByteArray, fromIndex: Int): Int {
        if (pattern.isEmpty()) return fromIndex
        val maxStart = data.size - pattern.size
        for (i in fromIndex..maxStart) {
            var found = true
            for (j in pattern.indices) {
                if (data[i + j] != pattern[j]) {
                    found = false
                    break
                }
            }
            if (found) return i
        }
        return -1
    }

    private fun indexOfByte(data: ByteArray, b: Byte, fromIndex: Int = 0): Int {
        for (i in fromIndex until data.size) {
            if (data[i] == b) return i
        }
        return -1
    }

    private fun Map<String, String>.toOkHttpHeaders(): okhttp3.Headers {
        val builder = okhttp3.Headers.Builder()
        this.forEach { (key, value) -> builder.add(key, value) }
        return builder.build()
    }

    // ==================== HELPERS ====================

    private fun cleanTitleText(text: String): String {
        return text.replace("\\n", " ")
            .replace("\n", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
