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

    // ==================== LOAD LINKS ====================
    //
    // CRITICAL: download.php REQUIRES the &f=filename.torrent parameter!
    //   - download.php?id=83836 → 404 (error "E1")
    //   - download.php?id=83836&f=Name.torrent → 200 OK (valid .torrent)
    //
    // So we ALWAYS fetch the torrent detail page first to get the proper
    // download URL, then download the .torrent file, extract info hash
    // and trackers, and build a magnet link.
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

            // Step 1: If we don't have the download URL with &f= param,
            // fetch the torrent detail page to find it
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

                        // Also check for direct magnet links (external torrents)
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

            // Step 2: Download .torrent and build magnet
            if (!foundLink && resolvedDownloadUrl.isNotBlank() && resolvedDownloadUrl.contains("&f=")) {
                foundLink = torrentToMagnet(resolvedDownloadUrl, callback)
            }

            // Step 3: Last resort - try without &f= (probably won't work but worth trying)
            if (!foundLink && torrentId != "0") {
                Log.w(TAG, "Last resort: trying download without &f= param")
                foundLink = torrentToMagnet("$mainUrl/download.php?id=$torrentId", callback)
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
     * Download a .torrent file, extract info hash and trackers from raw bytes,
     * and build a magnet link.
     */
    private suspend fun torrentToMagnet(url: String, callback: (ExtractorLink) -> Unit): Boolean {
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
                if (torrentBytes == null || torrentBytes.size < 20) {
                    Log.w(TAG, "Empty or too small .torrent response (${torrentBytes?.size ?: 0} bytes)")
                    return false
                }

                // Verify it's a valid bittorrent file (starts with 'd')
                if (torrentBytes[0] != 'd'.code.toByte()) {
                    val preview = String(torrentBytes, 0, minOf(50, torrentBytes.size), Charsets.US_ASCII)
                    Log.w(TAG, "Not a valid .torrent file. First 50 chars: $preview")
                    return false
                }

                Log.d(TAG, "Downloaded .torrent: ${torrentBytes.size} bytes")

                // Compute info hash from RAW bytes
                val infoHash = computeInfoHash(torrentBytes)
                if (infoHash == null) {
                    Log.e(TAG, "Failed to compute info hash")
                    return false
                }

                // Extract announce URL(s) from .torrent
                val trackers = extractTrackers(torrentBytes)

                // Extract torrent name
                val torrentName = extractTorrentName(torrentBytes)

                // Build magnet link
                val magnet = StringBuilder("magnet:?xt=urn:btih:$infoHash")
                if (torrentName.isNotEmpty()) {
                    magnet.append("&dn=").append(URLEncoder.encode(torrentName, "UTF-8"))
                }

                // Add trackers: private tracker first, then public
                for (tracker in trackers) {
                    magnet.append("&tr=").append(URLEncoder.encode(tracker, "UTF-8"))
                }
                for (tracker in PUBLIC_TRACKERS) {
                    magnet.append("&tr=").append(URLEncoder.encode(tracker, "UTF-8"))
                }

                val magnetStr = magnet.toString()
                Log.d(TAG, "Built magnet: hash=$infoHash, name=$torrentName, trackers=${trackers.size}+${PUBLIC_TRACKERS.size}")

                callback(
                    newExtractorLink(
                        source = this.name,
                        name = "${this.name} Torrent",
                        url = magnetStr,
                        type = ExtractorLinkType.MAGNET
                    )
                )
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "torrentToMagnet Error: ${e.message}")
            false
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
