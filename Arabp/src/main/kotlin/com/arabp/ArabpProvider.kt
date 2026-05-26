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

    // ==================== SESSION / COOKIE MANAGEMENT ====================

    // Use java.net.CookieManager to properly handle PHPSESSID
    private val cookieManager = CookieManager().apply {
        setCookiePolicy(CookiePolicy.ACCEPT_ALL)
    }

    // Dedicated OkHttpClient with cookie jar for authenticated requests
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
                // This sets PHPSESSID in the cookie manager
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

                // Check for login success: look for logout link
                val loginSuccess = body.contains("logout.php") ||
                        body.contains("page=logout") ||
                        !body.contains("name=\"uid\"") ||
                        body.contains("مرحبا") || // "Welcome" in Arabic
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

    // ==================== LOAD LINKS (Torrent/Magnet Extraction) ====================

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

            val headers = getAuthHeaders(referer = detailUrl)
            var foundLink = false

            // Strategy 1: Try downloading .torrent file directly and extract magnet
            if (downloadUrl.isNotBlank()) {
                try {
                    val magnet = downloadTorrentAndExtractMagnet(downloadUrl)
                    if (magnet != null) {
                        callback(
                            newExtractorLink(
                                source = this.name,
                                name = "${this.name} Magnet",
                                url = magnet,
                                type = ExtractorLinkType.MAGNET
                            )
                        )
                        foundLink = true
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to download .torrent from downloadUrl: ${e.message}")
                }
            }

            // Strategy 2: Try constructing download URL if we have the torrent ID
            if (!foundLink && torrentId != "0") {
                try {
                    val constructedUrl = "$mainUrl/download.php?id=$torrentId"
                    val magnet = downloadTorrentAndExtractMagnet(constructedUrl)
                    if (magnet != null) {
                        callback(
                            newExtractorLink(
                                source = this.name,
                                name = "${this.name} Magnet",
                                url = magnet,
                                type = ExtractorLinkType.MAGNET
                            )
                        )
                        foundLink = true
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to download .torrent by ID: ${e.message}")
                }
            }

            // Strategy 3: Fetch the torrent detail page and look for magnet/download links
            if (!foundLink && detailUrl.isNotBlank()) {
                try {
                    val request = Request.Builder()
                        .url(toAbsoluteUrl(detailUrl))
                        .headers(headers.toOkHttpHeaders())
                        .build()

                    authClient.newCall(request).execute().use { response ->
                        val body = response.body?.string() ?: ""
                        val detailDoc = org.jsoup.Jsoup.parse(body, toAbsoluteUrl(detailUrl))

                        // Check for magnet links on the page
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

                        // Check for download link on detail page
                        if (!foundLink) {
                            val dlLink = detailDoc.selectFirst("a[href*=download.php]")
                            if (dlLink != null) {
                                val dlHref = toAbsoluteUrl(dlLink.attr("href"))
                                val magnet = downloadTorrentAndExtractMagnet(dlHref)
                                if (magnet != null) {
                                    callback(
                                        newExtractorLink(
                                            source = this.name,
                                            name = "${this.name} Magnet",
                                            url = magnet,
                                            type = ExtractorLinkType.MAGNET
                                        )
                                    )
                                    foundLink = true
                                }
                            }
                        }

                        // Also check the title link (it's often a download link on detail pages)
                        if (!foundLink) {
                            val titleLink = detailDoc.selectFirst("td#Title h1 a[href*=download.php]")
                            if (titleLink != null) {
                                val dlHref = toAbsoluteUrl(titleLink.attr("href"))
                                val magnet = downloadTorrentAndExtractMagnet(dlHref)
                                if (magnet != null) {
                                    callback(
                                        newExtractorLink(
                                            source = this.name,
                                            name = "${this.name} Magnet",
                                            url = magnet,
                                            type = ExtractorLinkType.MAGNET
                                        )
                                    )
                                    foundLink = true
                                }
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
     * Download a .torrent file using the authenticated client and extract a magnet URI
     */
    private fun downloadTorrentAndExtractMagnet(url: String): String? {
        return try {
            val request = Request.Builder()
                .url(toAbsoluteUrl(url))
                .headers(getAuthHeaders(referer = "$mainUrl/").toOkHttpHeaders())
                .build()

            authClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Download .torrent failed: HTTP ${response.code}")
                    return null
                }

                val torrentBytes = response.body?.bytes() ?: return null
                Log.d(TAG, "Downloaded .torrent: ${torrentBytes.size} bytes, first byte: ${if (torrentBytes.isNotEmpty()) torrentBytes[0] else 'N'}")

                // Valid bittorrent file starts with 'd' (dictionary)
                if (torrentBytes.size > 10 && torrentBytes[0] == 'd'.code.toByte()) {
                    extractMagnetFromTorrent(torrentBytes)
                } else {
                    Log.w(TAG, "Not a valid .torrent file. First 20 bytes: ${torrentBytes.take(20).map { it.toInt().toChar() }.joinToString("")}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "downloadTorrentAndExtractMagnet Error: ${e.message}")
            null
        }
    }

    // Helper to convert Map<String, String> to OkHttp Headers
    private fun Map<String, String>.toOkHttpHeaders(): okhttp3.Headers {
        val builder = okhttp3.Headers.Builder()
        this.forEach { (key, value) -> builder.add(key, value) }
        return builder.build()
    }

    // ==================== TORRENT → MAGNET EXTRACTION ====================

    /**
     * Parse a .torrent file and extract the magnet URI from its info dictionary.
     */
    private fun extractMagnetFromTorrent(data: ByteArray): String? {
        return try {
            val decoded = decodeBencode(data, 0).first as? Map<*, *> ?: return null
            val info = decoded["info"] as? Map<*, *> ?: return null

            // Re-encode info dictionary to compute SHA1 hash
            val infoEncoded = encodeBencode(info)
            val infoHash = sha1Hex(infoEncoded)

            // Get torrent name
            val name = when (val nameObj = info["name"]) {
                is ByteArray -> String(nameObj)
                is String -> nameObj
                else -> ""
            }

            // Build magnet link
            val magnet = StringBuilder("magnet:?xt=urn:btih:$infoHash&dn=")
                .append(URLEncoder.encode(name, "UTF-8"))

            // Add trackers from announce-list
            val announceList = decoded["announce-list"] as? List<*>
            if (announceList != null) {
                for (tier in announceList) {
                    when (tier) {
                        is List<*> -> {
                            for (tracker in tier) {
                                val trackerStr = when (tracker) {
                                    is ByteArray -> String(tracker)
                                    is String -> tracker
                                    else -> null
                                }
                                if (trackerStr != null) {
                                    magnet.append("&tr=").append(URLEncoder.encode(trackerStr, "UTF-8"))
                                }
                            }
                        }
                        is ByteArray -> magnet.append("&tr=").append(URLEncoder.encode(String(tier), "UTF-8"))
                        is String -> magnet.append("&tr=").append(URLEncoder.encode(tier, "UTF-8"))
                    }
                }
            }

            // Add single announce tracker if no announce-list
            if (announceList == null) {
                when (val announce = decoded["announce"]) {
                    is ByteArray -> magnet.append("&tr=").append(URLEncoder.encode(String(announce), "UTF-8"))
                    is String -> magnet.append("&tr=").append(URLEncoder.encode(announce, "UTF-8"))
                }
            }

            val magnetStr = magnet.toString()
            Log.d(TAG, "Extracted magnet: $magnetStr")
            magnetStr
        } catch (e: Exception) {
            Log.e(TAG, "extractMagnetFromTorrent Error: ${e.message}")
            null
        }
    }

    // ==================== BENCODE PARSER ====================

    private fun decodeBencode(data: ByteArray, offset: Int): Pair<Any, Int> {
        val first = data[offset].toInt().toChar()
        return when {
            first == 'd' -> {
                val result = mutableMapOf<String, Any>()
                var pos = offset + 1
                while (data[pos].toInt().toChar() != 'e') {
                    val key = decodeBencode(data, pos)
                    pos = key.second
                    val value = decodeBencode(data, pos)
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
                while (data[pos].toInt().toChar() != 'e') {
                    val item = decodeBencode(data, pos)
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

    private fun encodeBencode(obj: Any): ByteArray {
        return when (obj) {
            is Map<*, *> -> {
                val sorted = obj.entries.sortedBy { it.key.toString() }
                val parts = mutableListOf<ByteArray>()
                parts.add(byteArrayOf('d'.code.toByte()))
                for ((k, v) in sorted) {
                    parts.add(encodeBencode(k!!))
                    parts.add(encodeBencode(v!!))
                }
                parts.add(byteArrayOf('e'.code.toByte()))
                parts.reduce { acc, bytes -> acc + bytes }
            }
            is List<*> -> {
                val parts = mutableListOf<ByteArray>()
                parts.add(byteArrayOf('l'.code.toByte()))
                for (item in obj) {
                    parts.add(encodeBencode(item!!))
                }
                parts.add(byteArrayOf('e'.code.toByte()))
                parts.reduce { acc, bytes -> acc + bytes }
            }
            is Long -> "i${obj}e".toByteArray()
            is Int -> "i${obj}e".toByteArray()
            is ByteArray -> "${obj.size}:".toByteArray() + obj
            is String -> "${obj.length}:".toByteArray() + obj.toByteArray()
            else -> throw IllegalArgumentException("Cannot bencode: $obj")
        }
    }

    private fun sha1Hex(data: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-1")
        return md.digest(data).joinToString("") { "%02x".format(it) }
    }

    private fun indexOfByte(data: ByteArray, b: Byte, fromIndex: Int = 0): Int {
        for (i in fromIndex until data.size) {
            if (data[i] == b) return i
        }
        return -1
    }

    // ==================== HELPERS ====================

    private fun cleanTitleText(text: String): String {
        return text.replace("\\n", " ")
            .replace("\n", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
