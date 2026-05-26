package com.arabp

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.SubtitleFile
import okhttp3.FormBody
import okhttp3.Request
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.security.MessageDigest

class Arabp : MainAPI() {
    override var mainUrl = "https://www.arabp2p.net"
    override var name = "Arabp"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    companion object {
        private const val TAG = "Arabp_Log"
        // Login credentials — change these if needed
        private const val LOGIN_USERNAME = "armano"
        private const val LOGIN_PASSWORD = "ARmano01**"
        private val DIGITS = Regex("\\d+")
    }

    // Images require Referer header to avoid 403
    private val imageHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
        "Referer" to "$mainUrl/"
    )

    // In-memory cookie cache
    @Volatile
    private var cachedCookies: String? = null

    private fun toAbsoluteUrl(url: String): String {
        return when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            else -> "$mainUrl/$url"
        }
    }

    // ==================== LOGIN ====================

    private suspend fun ensureLogin(): String? {
        // Try cached cookies first
        val cached = cachedCookies
        if (!cached.isNullOrBlank()) {
            try {
                val testResp = app.get(
                    "$mainUrl/index.php?page=torrents",
                    headers = mapOf("Cookie" to cached)
                )
                if (testResp.code == 200 && !testResp.text.contains("name=\"uid\"")) {
                    return cached
                }
            } catch (_: Exception) {
            }
            // Cookies expired
            cachedCookies = null
        }

        return try {
            // Perform login via POST
            val loginResp = app.post(
                "$mainUrl/index.php?page=login",
                data = mapOf("uid" to LOGIN_USERNAME, "pwd" to LOGIN_PASSWORD),
                allowRedirects = true
            )

            // Extract cookies from response headers
            val cookies = StringBuilder()
            loginResp.headers.forEach { (key, value) ->
                if (key.equals("set-cookie", ignoreCase = true)) {
                    if (cookies.isNotEmpty()) cookies.append("; ")
                    cookies.append(value.substringBefore(";"))
                }
            }

            if (cookies.isNotBlank()) {
                cachedCookies = cookies.toString()
                return cachedCookies
            }

            // Fallback: use OkHttp directly for better cookie handling
            loginWithOkHttp()
        } catch (e: Exception) {
            Log.e(TAG, "Login Error: ${e.message}")
            null
        }
    }

    /**
     * Fallback login using OkHttp directly for proper cookie capture
     */
    private fun loginWithOkHttp(): String? {
        return try {
            val client = app.baseClient
            val formBody = FormBody.Builder()
                .add("uid", LOGIN_USERNAME)
                .add("pwd", LOGIN_PASSWORD)
                .build()

            val request = Request.Builder()
                .url("$mainUrl/index.php?page=login")
                .post(formBody)
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                )
                .build()

            val response = client.newCall(request).execute()
            val cookieHeaders = response.headers("Set-Cookie")

            if (cookieHeaders.isNotEmpty()) {
                val cookies = cookieHeaders.joinToString("; ") { it.substringBefore(";") }
                cachedCookies = cookies
                cookies
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "OkHttp Login Error: ${e.message}")
            null
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

            // Extract description from the anime listing page
            val desc = ""

            // Extract torrent entries from the listing table
            val rows = doc.select("table#listing_table tr")
            val episodes = rows.mapNotNull { row ->
                val nameLink = row.selectFirst("a[href*=torrent-details]")
                    ?: return@mapNotNull null

                val epName = cleanTitleText(nameLink.text())
                val epHref = toAbsoluteUrl(nameLink.attr("href"))

                // Extract download ID from the download link
                val downloadLink = row.selectFirst("a[href*=download.php]")
                val downloadId = downloadLink?.attr("href")
                    ?.let { DIGITS.find(it)?.value }
                    ?: nameLink.attr("href")
                        .let { DIGITS.find(it.substringAfter("id="))?.value }

                // Build episode data as: torrent_id|detail_url
                val epData = if (downloadId != null) {
                    "$downloadId|$epHref"
                } else {
                    "0|$epHref"
                }

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
                newMovieLoadResponse(title, fullUrl, TvType.Anime, "0|$fullUrl") {
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
        // Data format: "torrentId|detailUrl"
        val parts = data.split("|", limit = 2)
        val torrentId = parts.getOrNull(0) ?: "0"
        val detailUrl = parts.getOrNull(1) ?: data

        return try {
            // Login to get cookies
            val cookies = ensureLogin()
            if (cookies.isNullOrBlank()) {
                Log.w(TAG, "Cannot load links: not logged in. Check credentials in source code.")
                return false
            }

            val headers = mapOf(
                "Cookie" to cookies,
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
                "Referer" to toAbsoluteUrl(detailUrl)
            )

            // Step 1: Try to download the .torrent file and extract magnet
            if (torrentId != "0") {
                val torrentUrl = "$mainUrl/download.php?id=$torrentId"
                try {
                    val torrentResp = app.get(torrentUrl, headers = headers, allowRedirects = true)
                    val torrentBytes = torrentResp.body?.bytes()

                    if (torrentBytes != null && torrentBytes.size > 10 && torrentBytes[0] == 'd'.code.toByte()) {
                        // Valid bittorrent file (starts with 'd' for dictionary)
                        val magnet = extractMagnetFromTorrent(torrentBytes)
                        if (magnet != null) {
                            callback(
                                newExtractorLink(
                                    source = this.name,
                                    name = "${this.name} Magnet",
                                    url = magnet,
                                    type = ExtractorLinkType.MAGNET
                                )
                            )
                            return true
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to download .torrent: ${e.message}")
                }
            }

            // Step 2: Fallback - check torrent detail page for magnet/download links
            try {
                val doc = app.get(toAbsoluteUrl(detailUrl), headers = headers).document

                // Check for magnet links
                val magnetLinks = doc.select("a[href^=magnet:]")
                if (magnetLinks.isNotEmpty()) {
                    magnetLinks.forEach { magnetEl ->
                        val magnetUrl = magnetEl.attr("href")
                        callback(
                            newExtractorLink(
                                source = this.name,
                                name = "${this.name} Magnet",
                                url = magnetUrl,
                                type = ExtractorLinkType.MAGNET
                            )
                        )
                    }
                    return true
                }

                // Check for download link on the detail page
                val dlLink = doc.selectFirst("a[href*=download.php]")
                if (dlLink != null) {
                    val dlUrl = toAbsoluteUrl(dlLink.attr("href"))
                    val torrentResp = app.get(dlUrl, headers = headers, allowRedirects = true)
                    val torrentBytes = torrentResp.body?.bytes()

                    if (torrentBytes != null && torrentBytes.size > 10 && torrentBytes[0] == 'd'.code.toByte()) {
                        val magnet = extractMagnetFromTorrent(torrentBytes)
                        if (magnet != null) {
                            callback(
                                newExtractorLink(
                                    source = this.name,
                                    name = "${this.name} Magnet",
                                    url = magnet,
                                    type = ExtractorLinkType.MAGNET
                                )
                            )
                            return true
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch torrent detail page: ${e.message}")
            }

            false
        } catch (e: Exception) {
            Log.e(TAG, "loadLinks Error: ${e.message}")
            false
        }
    }

    // ==================== TORRENT → MAGNET EXTRACTION ====================

    /**
     * Parse a .torrent file and extract the magnet URI from its info dictionary.
     * The magnet link is constructed using: info_hash + name + trackers
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

            magnet.toString()
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
