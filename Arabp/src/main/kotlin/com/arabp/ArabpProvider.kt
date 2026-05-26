package com.arabp

import android.util.Log
import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.SubtitleFile
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
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
            .readTimeout(30, TimeUnit.SECONDS)
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

    private suspend fun ensureLogin(): Boolean {
        if (isLoggedIn) {
            return true
        }

        return try {
            // Step 1: GET the login page first to establish PHPSESSID
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
                Log.d(TAG, "Login response code: ${response.code}, body length: ${body.length}")

                // Check for login success: look for logout link or absence of login form
                val loginSuccess = body.contains("logout.php") ||
                        body.contains("page=logout") ||
                        !body.contains("name=\"uid\"") ||
                        body.contains("مرحبا") ||
                        body.contains(LOGIN_USERNAME)

                if (loginSuccess) {
                    isLoggedIn = true
                    Log.d(TAG, "Login SUCCESS! Cookies: ${getSessionCookies()}")
                } else {
                    Log.e(TAG, "Login FAILED. Response snippet: ${body.take(500)}")
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

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/index.php?page=anime-listing&search=$encoded"
        val doc = app.get(url).document

        return doc.select("div.listing_div1").mapNotNull { element ->
            toSearchResult(element)
        }
    }

    // ==================== LOAD (Detail Page) ====================

    override suspend fun load(url: String): LoadResponse? {
        val fullUrl = toAbsoluteUrl(url)
        val doc = app.get(fullUrl).document

        return try {
            // Extract title
            val h1 = doc.selectFirst("div.listing_div_id h1")
            val rawTitle = h1?.html()
                ?.replace("<br>", " ")
                ?.replace("<br/>", " ")
                ?.replace("<br />", " ")
                ?.trim()
                ?: ""
            val title = cleanTitleText(rawTitle)

            // Extract poster
            val posterUrl = doc.selectFirst("div.listing_div_id img.listing_poster")?.attr("src")
                ?: doc.selectFirst("img.listing_poster")?.attr("src")
                ?: ""

            val desc = ""

            // Extract torrent entries from the listing table
            val rows = doc.select("table#listing_table tr")
            val episodes = rows.mapNotNull { row ->
                // Look for torrent detail link
                val nameLink = row.selectFirst("a[href*=torrent-details]")
                    ?: return@mapNotNull null

                val epName = cleanTitleText(nameLink.text())
                val detailHref = nameLink.attr("href")

                // Extract the torrent ID from the detail link
                val torrentId = DIGITS.find(detailHref.substringAfter("id="))?.value ?: return@mapNotNull null

                // Extract the FULL download URL including the &f= parameter
                val downloadLink = row.selectFirst("a[href*=download.php]")
                val downloadHref = downloadLink?.attr("href") ?: ""

                // Build episode data: torrent_id|detail_url|download_url
                val epData = "$torrentId|${toAbsoluteUrl(detailHref)}|${toAbsoluteUrl(downloadHref)}"

                // Extract additional info from the row
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

    // ==================== LOAD LINKS ====================
    //
    // KEY INSIGHT: arabp2p.net is a PRIVATE tracker. 98% of torrents are internal.
    // - Converting .torrent → magnet FAILS because private trackers disable DHT/PEX
    // - Public trackers don't know about private tracker torrents
    // - The .torrent file from arabp2p.net contains a private announce URL with a passkey
    //   that authenticates with the tracker and returns a peer list
    //
    // SOLUTION: Download the .torrent file ourselves (with auth cookies), then pass
    // the .torrent data directly to CloudStream using a data: URI with TORRENT type.
    // CloudStream's libtorrent engine will:
    //   1. Parse the .torrent file
    //   2. Connect to the private tracker's announce URL (with embedded passkey)
    //   3. Get the peer list from the tracker
    //   4. Stream from those peers
    //

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Data format: "torrentId|detailUrl|downloadUrl"
        val parts = data.split("|", limit = 3)
        val torrentId = parts.getOrNull(0) ?: "0"
        val detailUrl = parts.getOrNull(1) ?: ""
        val downloadUrl = parts.getOrNull(2) ?: ""

        Log.d(TAG, "loadLinks called: id=$torrentId, detail=$detailUrl, download=$downloadUrl")

        return try {
            // Login first
            if (!ensureLogin()) {
                Log.e(TAG, "Cannot load links: login failed")
                return false
            }

            var foundLink = false

            // ===== PRIMARY: Download .torrent and pass as data: URI =====
            // Try the captured download URL first
            if (downloadUrl.isNotBlank()) {
                foundLink = passTorrentFile(downloadUrl, callback)
            }

            // Try constructing download URL from torrent ID
            if (!foundLink && torrentId != "0") {
                val constructedUrl = "$mainUrl/download.php?id=$torrentId"
                foundLink = passTorrentFile(constructedUrl, callback)
            }

            // ===== FALLBACK: Check detail page for magnet/download links =====
            if (!foundLink && detailUrl.isNotBlank()) {
                try {
                    val request = Request.Builder()
                        .url(toAbsoluteUrl(detailUrl))
                        .headers(getAuthHeaders(referer = "$mainUrl/").toOkHttpHeaders())
                        .build()

                    authClient.newCall(request).execute().use { response ->
                        val body = response.body?.string() ?: ""
                        val detailDoc = org.jsoup.Jsoup.parse(body, toAbsoluteUrl(detailUrl))

                        // Check for magnet links (external torrents)
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

                        // Check for download link on the detail page
                        if (!foundLink) {
                            val dlLink = detailDoc.selectFirst("a[href*=download.php]")
                                ?: detailDoc.selectFirst("td#Title h1 a[href*=download.php]")
                            if (dlLink != null) {
                                val dlHref = toAbsoluteUrl(dlLink.attr("href"))
                                foundLink = passTorrentFile(dlHref, callback)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to fetch torrent detail page: ${e.message}")
                }
            }

            if (!foundLink) {
                Log.w(TAG, "No links found for torrent id=$torrentId")
            }

            foundLink
        } catch (e: Exception) {
            Log.e(TAG, "loadLinks Error: ${e.message}")
            false
        }
    }

    /**
     * Download a .torrent file using the authenticated OkHttp client,
     * then pass it to CloudStream as a data: URI with TORRENT type.
     *
     * This preserves the private tracker announce URL (with passkey) inside
     * the .torrent file, so CloudStream's libtorrent can connect to the
     * private tracker and get the peer list.
     */
    private suspend fun passTorrentFile(url: String, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            val request = Request.Builder()
                .url(toAbsoluteUrl(url))
                .headers(getAuthHeaders(referer = "$mainUrl/").toOkHttpHeaders())
                .build()

            authClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Download .torrent failed: HTTP ${response.code} for $url")
                    return false
                }

                val torrentBytes = response.body?.bytes()
                if (torrentBytes == null || torrentBytes.size < 10) {
                    Log.w(TAG, "Empty or too small .torrent response")
                    return false
                }

                // Verify it's a valid bittorrent file (starts with 'd' for dictionary)
                if (torrentBytes[0] != 'd'.code.toByte()) {
                    Log.w(TAG, "Not a valid .torrent file (first byte: ${torrentBytes[0]})")
                    return false
                }

                // Encode the .torrent file as base64 data URI
                val base64 = Base64.encodeToString(torrentBytes, Base64.NO_WRAP)
                val dataUri = "data:application/x-bittorrent;base64,$base64"

                Log.d(TAG, "Passing .torrent file (${torrentBytes.size} bytes) as data URI to CloudStream")

                callback(
                    newExtractorLink(
                        source = this.name,
                        name = "${this.name} Torrent",
                        url = dataUri,
                        type = ExtractorLinkType.TORRENT
                    )
                )
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "passTorrentFile Error: ${e.message}")
            false
        }
    }

    // Helper to convert Map<String, String> to OkHttp Headers
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
