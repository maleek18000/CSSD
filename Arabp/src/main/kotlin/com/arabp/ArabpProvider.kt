package com.arabp

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.SubtitleFile
import okhttp3.FormBody
import okhttp3.Headers.Companion.toHeaders
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.json.JSONArray
import org.json.JSONObject
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

        // Set to true to enable tracker proxy (modify .torrent announce URL → local proxy)
        // Set to false to serve original .torrent as-is (more reliable, but tracker counts downloads)
        private const val ENABLE_TRACKER_PROXY = false

        // ==================== TORRSERVE CONFIGURATION ====================
        // TorrServe Matrix API — default host:port
        // Change this if your TorrServe runs on a different address
        private const val TORRSERVE_HOST = "http://127.0.0.1:8090"

        // How many times to poll TorrServe for file list after upload
        private const val TORRSERVE_POLL_ATTEMPTS = 20
        // Delay between polls (ms)
        private const val TORRSERVE_POLL_DELAY = 2000L
    }

    // Cached passkey extracted from .torrent announce URL or website
    @Volatile
    private var cachedPasskey: String? = null

    // Torrent cache: stores downloaded .torrent bytes by torrent ID
    // so loadLinks() doesn't need to re-download (avoids wasting daily download count)
    private val torrentCache = java.util.LinkedHashMap<String, ByteArray>(8, 0.75f, true)
    private val MAX_TORRENT_CACHE = 5

    // File info from a parsed .torrent
    private data class TorrentFileInfo(val path: String, val length: Long)

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

    private val torrServeClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
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

    // ==================== TORRSERVE INTEGRATION ====================
    //
    // TorrServe is a separate torrent server app that runs on the device.
    // When available, we upload .torrent files to it and get individual
    // direct stream URLs per file — this enables:
    //   1. Multi-show torrent splitting (each show = separate season)
    //   2. Per-file playback (TorrServe streams exactly ONE file, not all)
    //   3. File priority management (pause all, resume only the watched one)
    //
    // When TorrServe is NOT available, we fall back to CS's built-in
    // torrent engine (TORRENT/MAGNET links) which works but can't
    // split multi-show torrents.

    /**
     * Represents one streamable file from a TorrServe torrent.
     * Includes folder structure info for multi-show detection.
     */
    data class TorrServeStreamEntry(
        val fileName: String,      // Just the filename (e.g. "Episode01.mkv")
        val filePath: String,      // Full path within torrent (e.g. "ShowName/Episode01.mkv")
        val folderName: String,    // Legacy: first folder component (for backward compat)
        val fileIndex: Int,        // TorrServe file index (1-based)
        val streamUrl: String      // Full stream URL with correct index
    )

    /**
     * Check if TorrServe is running on the device.
     * Sends a lightweight GET request to the TorrServe API.
     */
    private fun isTorrServeAvailable(): Boolean {
        return try {
            val request = Request.Builder()
                .url("$TORRSERVE_HOST/torrents")
                .get()
                .build()

            torrServeClient.newCall(request).execute().use { response ->
                val available = response.isSuccessful
                Log.d(TAG, "TorrServe availability check: $available")
                available
            }
        } catch (e: Exception) {
            Log.d(TAG, "TorrServe not available: ${e.message}")
            false
        }
    }

    /**
     * Upload .torrent bytes to TorrServe, poll for file list, and return stream entries.
     * Returns null if TorrServe is not running or upload fails.
     */
    private fun uploadToTorrServe(torrentBytes: ByteArray, torrentId: String): List<TorrServeStreamEntry>? {
        return try {
            // Step 1: Upload .torrent file
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file", "arabp_$torrentId.torrent",
                    torrentBytes.toRequestBody("application/x-bittorrent".toMediaType())
                )
                .addFormDataPart("save", "1")
                .build()

            val uploadRequest = Request.Builder()
                .url("$TORRSERVE_HOST/torrent/upload")
                .post(requestBody)
                .build()

            val hash: String
            var fileStats: List<Pair<String, Int>>

            torrServeClient.newCall(uploadRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "TorrServe upload HTTP error: ${response.code}")
                    return null
                }

                val responseBody = response.body?.string() ?: return null
                Log.d(TAG, "TorrServe upload response (${responseBody.length} chars): ${responseBody.take(500)}")

                hash = parseHashFromResponse(responseBody)
                    ?: run {
                        Log.e(TAG, "Could not parse hash from TorrServe response")
                        return null
                    }

                Log.d(TAG, "TorrServe hash: $hash")

                // Try to parse file_stats from the upload response
                fileStats = parseFileStatsFromResponse(responseBody)
                Log.d(TAG, "Parsed ${fileStats.size} files from upload response")
            }

            // Step 2: If file_stats was empty, poll TorrServe until the torrent is ready
            if (fileStats.isEmpty()) {
                Log.d(TAG, "file_stats empty in upload response, polling TorrServe API...")
                fileStats = pollTorrServeFileList(hash)
            }

            // Step 3: Build stream entries from file stats
            if (fileStats.isEmpty()) {
                // Final fallback: single file torrent, use index=1
                Log.w(TAG, "No file_stats found after polling — falling back to index=1")
                return listOf(
                    TorrServeStreamEntry(
                        fileName = "Video",
                        filePath = "Video",
                        folderName = "",
                        fileIndex = 1,
                        streamUrl = "$TORRSERVE_HOST/stream/fname?link=$hash&index=1&play"
                    )
                )
            }

            // Filter to only video files and create stream entries
            val videoEntries = fileStats
                .filter { (path, _) -> isVideoFile(path) }
                .map { (path, id) ->
                    val normalizedPath = normalizePath(path)
                    val fileName = normalizedPath.substringAfterLast("/")
                    val folderName = extractFolderName(normalizedPath)

                    TorrServeStreamEntry(
                        fileName = fileName,
                        filePath = normalizedPath,
                        folderName = folderName,
                        fileIndex = id,
                        streamUrl = "$TORRSERVE_HOST/stream/fname?link=$hash&index=$id&play"
                    )
                }

            Log.d(TAG, "Created ${videoEntries.size} video stream entries (from ${fileStats.size} total files)")

            // Pause all files after upload so only the episode the user watches gets downloaded.
            // TorrServe will auto-resume a paused file when it's accessed via the stream URL,
            // and loadLinks() explicitly resumes the active file for ts:// URLs.
            try {
                if (videoEntries.size > 1) {
                    val allIndices = videoEntries.map { it.fileIndex }
                    pauseAllFiles(hash, allIndices)
                }
            } catch (e: Exception) {
                Log.w(TAG, "TorrServe pauseAllFiles failed (non-fatal): ${e.message}")
            }

            if (videoEntries.isEmpty()) {
                // If no video files detected, use first file as fallback
                val first = fileStats.first()
                val normalizedPath = normalizePath(first.first)
                listOf(
                    TorrServeStreamEntry(
                        fileName = normalizedPath.substringAfterLast("/"),
                        filePath = normalizedPath,
                        folderName = extractFolderName(normalizedPath),
                        fileIndex = first.second,
                        streamUrl = "$TORRSERVE_HOST/stream/fname?link=$hash&index=${first.second}&play"
                    )
                )
            } else {
                videoEntries.sortedBy { it.filePath }
            }
        } catch (e: java.net.ConnectException) {
            Log.e(TAG, "TorrServe connection refused — is TorrServe running on $TORRSERVE_HOST?")
            null
        } catch (e: Exception) {
            Log.e(TAG, "uploadToTorrServe Error: ${e.message}")
            null
        }
    }

    /**
     * Poll TorrServe's POST /torrents API to get file_stats.
     */
    private fun pollTorrServeFileList(hash: String): List<Pair<String, Int>> {
        for (attempt in 1..TORRSERVE_POLL_ATTEMPTS) {
            try {
                Thread.sleep(TORRSERVE_POLL_DELAY)

                val jsonBody = JSONObject().apply {
                    put("action", "get")
                    put("hash", hash)
                }

                val request = Request.Builder()
                    .url("$TORRSERVE_HOST/torrents")
                    .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                    .header("Content-Type", "application/json")
                    .build()

                torrServeClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "TorrServe poll attempt $attempt: HTTP ${response.code}")
                        return@use
                    }

                    val responseBody = response.body?.string() ?: return@use
                    val resultJson = JSONObject(responseBody)

                    // Check if torrent is ready (stat=3 means metadata resolved)
                    val stat = resultJson.optInt("stat", 0)
                    Log.d(TAG, "TorrServe poll attempt $attempt: stat=$stat")

                    if (stat == 3 || stat == 4) {
                        val stats = parseFileStatsFromJsonObj(resultJson)
                        if (stats.isNotEmpty()) {
                            Log.d(TAG, "TorrServe poll SUCCESS: ${stats.size} files after $attempt attempts")
                            return stats
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "TorrServe poll attempt $attempt error: ${e.message}")
            }
        }

        Log.e(TAG, "TorrServe polling exhausted after $TORRSERVE_POLL_ATTEMPTS attempts")
        return emptyList()
    }

    // ==================== TORRSERVE FILE PRIORITY (PAUSE/RESUME) ====================
    //
    // TorrServe downloads ALL files in a torrent by default. To save bandwidth
    // and storage, we pause all files except the one being watched.
    //
    // TorrServe Matrix API: POST /torrents with action=set-file-priority
    // Priority values: 0 = Don't download (paused), 1 = Normal

    /**
     * Set download priority for a single file in TorrServe.
     */
    private fun setTorrServeFilePriority(hash: String, fileIndex: Int, priority: Int): Boolean {
        return try {
            val jsonBody = JSONObject().apply {
                put("action", "set-file-priority")
                put("hash", hash)
                put("id", fileIndex)
                put("priority", priority)
            }

            val request = Request.Builder()
                .url("$TORRSERVE_HOST/torrents")
                .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                .header("Content-Type", "application/json")
                .build()

            torrServeClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Log.d(TAG, "TorrServe: set file $fileIndex priority=$priority for $hash")
                    true
                } else {
                    Log.w(TAG, "TorrServe: set priority failed, HTTP ${response.code}")
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "TorrServe setFilePriority error: ${e.message}")
            false
        }
    }

    private fun pauseAllFiles(hash: String, indices: List<Int>, priority: Int = 0) {
        for (idx in indices) {
            if (setTorrServeFilePriority(hash, idx, priority)) {
                // small delay to not hammer TorrServe
            }
        }
    }

    /**
     * Extract hash and file index from a TorrServe stream URL.
     */
    private fun parseTorrServeUrl(streamUrl: String): Pair<String, Int>? {
        return try {
            // URL format: http://127.0.0.1:8090/stream/fname?link=HASH&index=ID&play
            val uri = java.net.URI(streamUrl)
            val query = uri.query ?: ""
            var hash = ""
            var index = -1
            for (param in query.split("&")) {
                val kv = param.split("=", limit = 2)
                if (kv.size == 2) {
                    when (kv[0]) {
                        "link" -> hash = kv[1]
                        "index" -> index = kv[1].toIntOrNull() ?: -1
                    }
                }
            }
            if (hash.isNotEmpty() && index >= 0) Pair(hash, index) else null
        } catch (e: Exception) {
            Log.w(TAG, "parseTorrServeUrl error: ${e.message}")
            null
        }
    }

    /**
     * Get all file indices for a torrent hash from TorrServe.
     */
    private fun getTorrServeFileIndices(hash: String): List<Int> {
        return try {
            val jsonBody = JSONObject().apply {
                put("action", "get")
                put("hash", hash)
            }

            val request = Request.Builder()
                .url("$TORRSERVE_HOST/torrents")
                .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                .header("Content-Type", "application/json")
                .build()

            torrServeClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val body = response.body?.string() ?: return emptyList()
                val json = JSONObject(body)
                val fileStats = parseFileStatsFromJsonObj(json)
                fileStats.map { it.second }
            }
        } catch (e: Exception) {
            Log.e(TAG, "getTorrServeFileIndices error: ${e.message}")
            emptyList()
        }
    }

    // ==================== TORRSERVE RESPONSE PARSING ====================

    private fun parseHashFromResponse(body: String): String? {
        return try {
            val json = JSONObject(body)
            json.optString("hash", null)
                ?: json.optJSONObject("data")?.optString("hash", null)
                ?: run {
                    val dataArr = json.optJSONArray("data")
                    if (dataArr != null && dataArr.length() > 0) {
                        dataArr.getJSONObject(0).optString("hash", null)
                    } else null
                }
        } catch (e: Exception) {
            Log.w(TAG, "parseHashFromResponse error: ${e.message}")
            null
        }
    }

    private fun parseFileStatsFromResponse(body: String): List<Pair<String, Int>> {
        val stats = mutableListOf<Pair<String, Int>>()
        try {
            val json = JSONObject(body)
            val fileStatsArr = json.optJSONArray("file_stats")
            if (fileStatsArr != null) {
                parseFileStatsArray(fileStatsArr, stats)
                if (stats.isNotEmpty()) return stats
            }
            val dataObj = json.optJSONObject("data")
            if (dataObj != null) {
                val innerStats = dataObj.optJSONArray("file_stats")
                if (innerStats != null) {
                    parseFileStatsArray(innerStats, stats)
                    if (stats.isNotEmpty()) return stats
                }
            }
            val dataArr = json.optJSONArray("data")
            if (dataArr != null && dataArr.length() > 0) {
                val firstObj = dataArr.getJSONObject(0)
                val innerStats = firstObj.optJSONArray("file_stats")
                if (innerStats != null) {
                    parseFileStatsArray(innerStats, stats)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "parseFileStatsFromResponse error: ${e.message}")
        }
        return stats
    }

    private fun parseFileStatsFromJsonObj(json: JSONObject): List<Pair<String, Int>> {
        val stats = mutableListOf<Pair<String, Int>>()
        try {
            val fileStatsArr = json.optJSONArray("file_stats")
            if (fileStatsArr != null) {
                parseFileStatsArray(fileStatsArr, stats)
            }
        } catch (e: Exception) {
            Log.w(TAG, "parseFileStatsFromJsonObj error: ${e.message}")
        }
        return stats
    }

    private fun parseFileStatsArray(arr: JSONArray, stats: MutableList<Pair<String, Int>>) {
        for (i in 0 until arr.length()) {
            try {
                val item = arr.getJSONObject(i)
                val id = item.optInt("id", i + 1)
                val path = item.optString("path", "")
                    .ifEmpty { item.optString("name", "") }
                    .ifEmpty { item.optString("file", "") }
                if (path.isNotEmpty()) {
                    stats.add(Pair(path, id))
                }
            } catch (e: Exception) {
                // Skip malformed entries
            }
        }
    }

    // ==================== SMART FOLDER GROUPING ====================
    //
    // The key problem: simple extractFolderName() only takes the FIRST path
    // component, but many torrents have a wrapper folder like:
    //   "RootFolder/Show1/E01.mkv"
    //   "RootFolder/Show2/E01.mkv"
    // extractFolderName returns "RootFolder" for both → hasMultipleShows=false!
    //
    // Solution: analyze ALL paths together and find the grouping level
    // where there are MULTIPLE distinct values.

    /**
     * Represents a file's path split into components, with the computed
     * "show name" (the grouping key at the diversity level).
     */
    data class PathGroup(
        val path: String,           // Full normalized path
        val fileName: String,       // Just the filename
        val showName: String,       // Computed show/group name
        val showDepth: Int          // How deep the show level is (0=root, 1=first folder, etc.)
    )

    /**
     * Analyze all file paths together to find the correct grouping level.
     *
     * Algorithm:
     * 1. Split every path into components
     * 2. Find the shallowest level where there are 2+ distinct values
     * 3. Group files by their value at that level
     *
     * Examples:
     *   ["Show1/E01.mkv", "Show2/E01.mkv"]
     *     → Level 0: {"Show1","Show2"} → 2 values → shows at level 0
     *
     *   ["Root/Show1/E01.mkv", "Root/Show2/E01.mkv"]
     *     → Level 0: {"Root","Root"} → 1 value → skip
     *     → Level 1: {"Show1","Show2"} → 2 values → shows at level 1
     *
     *   ["Show1/E01.mkv", "Show1/E02.mkv"]
     *     → Level 0: {"Show1","Show1"} → 1 value → single show
     */
    private fun computeShowGroups(entries: List<TorrServeStreamEntry>): List<PathGroup> {
        if (entries.isEmpty()) return emptyList()

        // Split each entry's filePath into components
        val pathParts = entries.map { entry ->
            val parts = entry.filePath.split("/").filter { it.isNotEmpty() }
            // Last component is the filename, rest are folders
            Pair(entry, if (parts.isNotEmpty()) parts.dropLast(1) else emptyList())
        }

        // Find the maximum folder depth
        val maxDepth = pathParts.maxOf { it.second.size }

        // Find the shallowest level with 2+ distinct values
        var showLevel = -1
        for (level in 0 until maxDepth) {
            val valuesAtLevel = pathParts.mapNotNull { it.second.getOrNull(level) }.toSet()
            if (valuesAtLevel.size >= 2) {
                showLevel = level
                break
            }
        }

        // Build PathGroup for each entry
        return pathParts.map { (entry, parts) ->
            val showName = if (showLevel >= 0 && showLevel < parts.size) {
                parts[showLevel]
            } else if (showLevel < 0 && parts.size > 1) {
                parts.first()
            } else {
                "" // No folder at all
            }

            PathGroup(
                path = entry.filePath,
                fileName = entry.fileName,
                showName = showName,
                showDepth = showLevel
            )
        }
    }

    /**
     * Check if the entries represent multiple shows based on smart grouping.
     */
    private fun hasMultipleShowsSmart(entries: List<TorrServeStreamEntry>): Boolean {
        val groups = computeShowGroups(entries)
        val distinctShows = groups.map { it.showName }.filter { it.isNotEmpty() }.toSet()
        return distinctShows.size > 1
    }

    // ==================== BUILD EPISODES FROM STREAM ENTRIES ====================
    //
    // Shared logic for both load() and loadFromTorrentData().
    // Creates per-file episodes with ts:// data, grouped by show/season.
    //
    // Episode data format: ts://STREAM_URL|FILENAME
    // When loadLinks() sees ts://, it returns exactly ONE ExtractorLink
    // (the direct TorrServe stream URL) — not a flat list of all files.

    /**
     * Build a list of Episodes from pre-resolved stream entries.
     * Each video file becomes its own Episode with a ts:// data URL,
     * so loadLinks() returns exactly ONE source (not a flat list of all files).
     *
     * For multi-show torrents, each show becomes a SEASON with a readable name.
     */
    private fun buildEpisodesFromEntries(
        streamEntries: List<TorrServeStreamEntry>,
        absPosterUrl: String
    ): Pair<List<Episode>, List<SeasonData>> {
        val groups = computeShowGroups(streamEntries)
        val distinctShows = groups.map { it.showName }.filter { it.isNotEmpty() }.distinct()
        val isMultiShow = distinctShows.size > 1

        val episodes = mutableListOf<Episode>()
        val seasonNamesList = mutableListOf<SeasonData>()

        if (isMultiShow) {
            // MULTI-SHOW: each show = one season
            var seasonNum = 1

            for (showName in distinctShows) {
                seasonNamesList.add(SeasonData(season = seasonNum, name = showName))

                // Find all entries belonging to this show
                val showEntries = groups.filter { it.showName == showName }
                for ((epIndex, group) in showEntries.withIndex()) {
                    // Find the original TorrServeStreamEntry for this file
                    val entry = streamEntries.find { it.filePath == group.path } ?: continue
                    val epData = "ts://${entry.streamUrl}|${entry.fileName}"

                    episodes.add(
                        newEpisode(epData, fix = false, initializer = {
                            name = entry.fileName
                            season = seasonNum
                            episode = epIndex + 1
                            this.posterUrl = absPosterUrl
                        })
                    )
                }
                seasonNum++
            }

            // Files without a show name → put in next season
            val noShowEntries = groups.filter { it.showName.isEmpty() }
            if (noShowEntries.isNotEmpty()) {
                seasonNamesList.add(SeasonData(season = seasonNum, name = "أخرى"))
                for ((epIndex, group) in noShowEntries.withIndex()) {
                    val entry = streamEntries.find { it.filePath == group.path } ?: continue
                    val epData = "ts://${entry.streamUrl}|${entry.fileName}"

                    episodes.add(
                        newEpisode(epData, fix = false, initializer = {
                            name = entry.fileName
                            season = seasonNum
                            episode = epIndex + 1
                            this.posterUrl = absPosterUrl
                        })
                    )
                }
            }

            Log.d(TAG, "buildEpisodes: MULTI-SHOW → ${episodes.size} episodes in ${seasonNamesList.size} seasons")
        } else {
            // SINGLE-SHOW: all files in season 1
            val showLabel = distinctShows.firstOrNull() ?: ""
            val sName = if (showLabel.isNotEmpty()) showLabel else "مواسم"
            seasonNamesList.add(SeasonData(season = 1, name = sName))

            for ((epIndex, entry) in streamEntries.withIndex()) {
                val epData = "ts://${entry.streamUrl}|${entry.fileName}"
                episodes.add(
                    newEpisode(epData, fix = false, initializer = {
                        name = entry.fileName
                        season = 1
                        episode = epIndex + 1
                        this.posterUrl = absPosterUrl
                    })
                )
            }

            Log.d(TAG, "buildEpisodes: SINGLE-SHOW → ${episodes.size} episodes")
        }

        return Pair(episodes, seasonNamesList)
    }

    /**
     * Try to download and upload the .torrent to TorrServe, returning
     * the list of stream entries. Returns null on failure.
     */
    private fun tryResolveTorrent(
        torrentId: String,
        detailUrl: String,
        downloadUrl: String,
        isFree: Boolean
    ): List<TorrServeStreamEntry>? {
        if (!ensureLogin()) return null

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
                Log.w(TAG, "tryResolveTorrent: failed to fetch detail page: ${e.message}")
            }
        }

        // Step 2: Download .torrent file
        if (resolvedDownloadUrl.isBlank() || !resolvedDownloadUrl.contains("&f=")) {
            Log.w(TAG, "tryResolveTorrent: no valid download URL for torrent $torrentId")
            return null
        }

        val result = downloadTorrentFile(resolvedDownloadUrl)
        when (result) {
            is TorrentDownloadResult.Success -> {
                Log.d(TAG, "tryResolveTorrent: downloaded .torrent (${result.bytes.size} bytes), uploading to TorrServe...")
                val entries = uploadToTorrServe(result.bytes, torrentId)
                if (entries != null && entries.isNotEmpty()) {
                    return entries
                }
                Log.w(TAG, "tryResolveTorrent: TorrServe upload returned no entries")
                return null
            }
            is TorrentDownloadResult.DailyLimitExceeded -> {
                Log.e(TAG, "tryResolveTorrent: daily download limit exceeded - will retry in loadLinks()")
                return null
            }
            is TorrentDownloadResult.NotLoggedIn -> {
                isLoggedIn = false
                Log.e(TAG, "tryResolveTorrent: session expired")
                return null
            }
            is TorrentDownloadResult.Error -> {
                Log.e(TAG, "tryResolveTorrent: download error: ${result.message}")
                return null
            }
        }
    }

    // ==================== RESOLVE DOWNLOAD URL ====================

    private fun resolveDownloadUrl(torrentId: String, detailUrl: String): String? {
        if (!ensureLogin()) return null

        val detailPageUrl = if (detailUrl.isNotBlank()) toAbsoluteUrl(detailUrl)
            else "$mainUrl/index.php?page=torrent-details&id=$torrentId"

        return try {
            val request = Request.Builder()
                .url(detailPageUrl)
                .headers(getAuthHeaders(referer = "$mainUrl/").toHeaders())
                .build()

            authClient.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return null
                val detailDoc = Jsoup.parse(body, detailPageUrl)

                val dlLink = detailDoc.selectFirst("a[href*=download.php]")
                    ?: detailDoc.selectFirst("td#Title h1 a[href*=download.php]")

                if (dlLink != null) {
                    toAbsoluteUrl(dlLink.attr("href"))
                } else {
                    Log.w(TAG, "resolveDownloadUrl: no download link found on detail page for torrent $torrentId")
                    null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "resolveDownloadUrl error: ${e.message}")
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

            // Check if TorrServe is available for multi-show splitting
            val torrServeAvailable = isTorrServeAvailable()

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

                // Try TorrServe pre-resolve for multi-show detection (only free torrents to avoid daily limit)
                val streamEntries = if (torrServeAvailable && isFree) {
                    tryResolveTorrent(
                        torrentId, toAbsoluteUrl(detailHref), toAbsoluteUrl(downloadHref), isFree
                    )
                } else {
                    null
                }

                if (streamEntries == null || streamEntries.isEmpty()) {
                    // Pre-resolve failed or not attempted — fall back to legacy data (one episode = one torrent)
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
                    continue
                }

                // Pre-resolve succeeded — build per-file episodes
                if (streamEntries.size == 1) {
                    // Single file → one episode in its own season
                    val entry = streamEntries.first()
                    val tsEpData = "ts://${entry.streamUrl}|${entry.fileName}"
                    seasonNamesList.add(SeasonData(season = globalSeasonNum, name = displayName))
                    episodes.add(
                        newEpisode(tsEpData, fix = false, initializer = {
                            name = displayName
                            season = globalSeasonNum
                            episode = 1
                            this.posterUrl = absPosterUrl
                        })
                    )
                    globalSeasonNum++
                } else {
                    // Multiple files — use smart grouping for seasons
                    val (fileEpisodes, fileSeasonNames) = buildEpisodesFromEntries(streamEntries, absPosterUrl)

                    // Build a lookup map from the SeasonData list
                    val fileSeasonMap = fileSeasonNames.associate { it.season to (it.name ?: "") }

                    // Add one SeasonData per unique remapped season
                    for ((localSeason, showName) in fileSeasonMap) {
                        val remappedSeason = globalSeasonNum + localSeason - 1
                        val seasonDisplay = if (fileSeasonMap.size > 1) {
                            "$displayName — $showName"
                        } else {
                            displayName
                        }
                        seasonNamesList.add(SeasonData(season = remappedSeason, name = seasonDisplay))
                    }

                    // Remap season numbers in episodes to our global numbering
                    for (ep in fileEpisodes) {
                        val localSeason = ep.season ?: 1
                        val remappedSeason = globalSeasonNum + localSeason - 1

                        episodes.add(
                            newEpisode(ep.data ?: "", fix = false, initializer = {
                                name = ep.name ?: ""
                                season = remappedSeason
                                episode = ep.episode
                                this.posterUrl = absPosterUrl
                            })
                        )
                    }
                    globalSeasonNum += fileSeasonMap.size
                }
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
    //
    // Called when the URL contains "|" (pipe-delimited torrent data
    // from search results). This is a SINGLE torrent.
    //
    // Strategy:
    // - If TorrServe is available, pre-resolve to detect multi-show structure
    // - If TorrServe not available, fall back to legacy single-episode format
    // - loadLinks() handles both ts:// and legacy pipe-delimited data

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

        // Try TorrServe pre-resolve (only for free torrents to avoid daily limit)
        val torrServeAvailable = isTorrServeAvailable()
        val streamEntries = if (torrServeAvailable && isFree) {
            tryResolveTorrent(torrentId, detailUrl, downloadUrl, isFree)
        } else {
            null
        }

        if (streamEntries.isNullOrEmpty()) {
            // Pre-resolve failed or not available — fall back to legacy format.
            // loadLinks() will try again when the user clicks play.
            Log.w(TAG, "Pre-resolve failed for torrent $torrentId, falling back to single-episode format")
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

        // Single video file → Movie
        if (streamEntries.size == 1) {
            val entry = streamEntries.first()
            val tsEpData = "ts://${entry.streamUrl}|${entry.fileName}"
            val tvType = if (title.contains("فيلم", ignoreCase = true) || title.contains("Movie", ignoreCase = true))
                pageTvType.toMovieType() else pageTvType.toSeriesType()

            if (tvType == TvType.Movie || tvType == TvType.AnimeMovie) {
                return newMovieLoadResponse(title, data, tvType, tsEpData) {
                    this.posterUrl = absPosterUrl
                    this.posterHeaders = imageHeaders
                }
            }
            // Series with single file → return as series with one episode
            return newTvSeriesLoadResponse(title, data, tvType, listOf(
                newEpisode(tsEpData, fix = false, initializer = {
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

        // Multiple video files → TV Series with smart show grouping
        val (episodes, seasonNames) = buildEpisodesFromEntries(streamEntries, absPosterUrl)

        return newTvSeriesLoadResponse(title, data, pageTvType.toSeriesType(), episodes) {
            this.posterUrl = absPosterUrl
            this.posterHeaders = imageHeaders
            this.seasonNames = seasonNames
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
    private var lastServerActivity: Long = 0

    // Tracker proxy state
    @Volatile
    private var realAnnounceUrl: String? = null
    @Volatile
    private var announceProxyForwarded = false
    @Volatile
    private var cachedAnnounceResponse: ByteArray? = null

    /**
     * Starts (or reuses) a local HTTP server that serves .torrent files from torrentCache.
     * The URL path includes the torrent ID (e.g. /12345.torrent) so the server
     * can serve the correct bytes even when multiple torrents are active.
     * Also acts as tracker proxy when ENABLE_TRACKER_PROXY is true.
     * Returns the local URL (e.g. http://127.0.0.1:PORT/12345.torrent) or null on error.
     */
    private fun startLocalTorrentServer(torrentId: String, originalTorrentBytes: ByteArray): String? {
        // Cache the bytes BEFORE starting the server, so they're available when CS fetches
        cacheTorrentBytes(torrentId, originalTorrentBytes)

        lastServerActivity = System.currentTimeMillis()

        // If server is already running, just return the URL — bytes are in cache
        if (localServerSocket != null && localServerPort > 0 && localServerThread?.isAlive == true) {
            Log.d(TAG, "Local torrent server: reusing existing server on port $localServerPort")
            if (ENABLE_TRACKER_PROXY) {
                val proxyAnnounce = "http://127.0.0.1:$localServerPort/announce"
                val realUrl = extractAnnounceUrl(originalTorrentBytes)
                if (realUrl != null) {
                    val modified = modifyTorrentAnnounce(originalTorrentBytes, proxyAnnounce)
                    // Store modified bytes in cache for serving
                    cacheTorrentBytes(torrentId, modified)
                    realAnnounceUrl = realUrl
                    announceProxyForwarded = false
                    cachedAnnounceResponse = null
                }
            }
            return "http://127.0.0.1:$localServerPort/$torrentId.torrent"
        }

        // Stop any stale server
        stopLocalTorrentServer()

        return try {
            val serverSocket = ServerSocket(0) // random available port
            localServerSocket = serverSocket
            localServerPort = serverSocket.localPort
            Log.d(TAG, "Local torrent server: starting on port $localServerPort")

            if (ENABLE_TRACKER_PROXY) {
                val proxyAnnounce = "http://127.0.0.1:$localServerPort/announce"
                val realUrl = extractAnnounceUrl(originalTorrentBytes)
                if (realUrl != null) {
                    val modified = modifyTorrentAnnounce(originalTorrentBytes, proxyAnnounce)
                    cacheTorrentBytes(torrentId, modified)
                    realAnnounceUrl = realUrl
                    announceProxyForwarded = false
                    cachedAnnounceResponse = null
                }
            }

            localServerThread = Thread {
                while (!Thread.currentThread().isInterrupted) {
                    try {
                        // Auto-stop after 90 seconds of inactivity
                        if (System.currentTimeMillis() - lastServerActivity > 90_000) {
                            Log.d(TAG, "Local torrent server: shutting down after inactivity")
                            break
                        }

                        serverSocket.soTimeout = 5000
                        val client = try { serverSocket.accept() } catch (_: java.net.SocketTimeoutException) { continue }

                        lastServerActivity = System.currentTimeMillis()
                        Thread {
                            try { handleServerRequest(client) } catch (e: Exception) {
                                Log.e(TAG, "Error handling server request: ${e.message}")
                            }
                        }.start()
                    } catch (e: Exception) {
                        if (!Thread.currentThread().isInterrupted) {
                            Log.e(TAG, "Server accept error: ${e.message}")
                        }
                        break
                    }
                }
                try { serverSocket.close() } catch (_: Exception) {}
            }.also { it.start() }

            "http://127.0.0.1:$localServerPort/$torrentId.torrent"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start local torrent server: ${e.message}")
            null
        }
    }

    private fun handleServerRequest(client: Socket) {
        client.use {
            val input = client.getInputStream()
            val output = client.getOutputStream()

            // Read HTTP request line
            val requestLine = readLine(input) ?: return
            Log.d(TAG, "Local server request: $requestLine")

            val requestParts = requestLine.split(" ")
            if (requestParts.size < 2) return

            val method = requestParts[0]
            val path = requestParts[1]

            // Read headers
            val headers = mutableMapOf<String, String>()
            while (true) {
                val headerLine = readLine(input) ?: break
                if (headerLine.isEmpty()) break
                val colonIdx = headerLine.indexOf(':')
                if (colonIdx > 0) {
                    headers[headerLine.substring(0, colonIdx).trim().lowercase()] =
                        headerLine.substring(colonIdx + 1).trim()
                }
            }

            when {
                // Tracker proxy: intercept announce requests
                ENABLE_TRACKER_PROXY && path.startsWith("/announce") -> {
                    handleAnnounceProxy(method, path, headers, input, output)
                }

                // Serve .torrent file by torrent ID
                path.endsWith(".torrent") -> {
                    val torrentId = path.substringAfter("/").substringBefore(".torrent")
                    val bytes = torrentCache[torrentId]

                    if (bytes != null) {
                        Log.d(TAG, "Serving .torrent for ID $torrentId (${bytes.size} bytes)")
                        val response = "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: application/x-bittorrent\r\n" +
                                "Content-Length: ${bytes.size}\r\n" +
                                "Connection: close\r\n\r\n"
                        output.write(response.toByteArray())
                        output.write(bytes)
                        output.flush()
                    } else {
                        Log.e(TAG, "No cached .torrent for ID $torrentId")
                        val response = "HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n"
                        output.write(response.toByteArray())
                        output.flush()
                    }
                }

                else -> {
                    val response = "HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n"
                    output.write(response.toByteArray())
                    output.flush()
                }
            }
        }
    }

    private fun handleAnnounceProxy(
        method: String,
        path: String,
        headers: Map<String, String>,
        input: java.io.InputStream,
        output: OutputStream
    ) {
        val targetUrl = realAnnounceUrl ?: run {
            Log.e(TAG, "Tracker proxy: no real announce URL set")
            sendTrackerResponse(output, emptyList())
            return
        }

        if (announceProxyForwarded && cachedAnnounceResponse != null) {
            Log.d(TAG, "Tracker proxy: returning cached announce response (not forwarding)")
            val response = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nConnection: close\r\n\r\n"
            output.write(response.toByteArray())
            output.write(cachedAnnounceResponse!!)
            output.flush()
            return
        }

        try {
            // Build the real tracker URL with query params from the client request
            val queryStr = if (path.contains("?")) path.substringAfter("?") else ""
            val realUrl = if (queryStr.isNotEmpty()) "$targetUrl?$queryStr" else targetUrl

            Log.d(TAG, "Tracker proxy: forwarding to real tracker: ${realUrl.take(100)}...")

            val request = Request.Builder()
                .url(realUrl)
                .header("User-Agent", headers["user-agent"] ?: "Transmission/3.00")
                .build()

            authClient.newCall(request).execute().use { response ->
                val bodyBytes = response.body?.bytes() ?: ByteArray(0)

                if (response.isSuccessful && bodyBytes.isNotEmpty()) {
                    announceProxyForwarded = true
                    cachedAnnounceResponse = bodyBytes
                    Log.d(TAG, "Tracker proxy: got real response (${bodyBytes.size} bytes), caching")

                    val httpResponse = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nConnection: close\r\n\r\n"
                    output.write(httpResponse.toByteArray())
                    output.write(bodyBytes)
                    output.flush()
                } else {
                    Log.w(TAG, "Tracker proxy: real tracker returned ${response.code}")
                    sendTrackerResponse(output, emptyList())
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Tracker proxy error: ${e.message}")
            sendTrackerResponse(output, emptyList())
        }
    }

    private fun sendTrackerResponse(output: OutputStream, peers: List<String>) {
        // Minimal bencoded response: d8:intervali1800e12:min intervali300e5:peers0:e
        val response = "d8:intervali1800e12:min intervali300e5:peers0:e"
        val httpHeader = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nConnection: close\r\n\r\n"
        output.write(httpHeader.toByteArray())
        output.write(response.toByteArray())
        output.flush()
    }

    private fun readLine(input: java.io.InputStream): String? {
        val baos = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) return null
            if (b == '\n'.code) break
            if (b != '\r'.code) baos.write(b)
        }
        return baos.toString("UTF-8")
    }

    private fun stopLocalTorrentServer() {
        try {
            localServerThread?.interrupt()
            localServerSocket?.close()
        } catch (_: Exception) {}
        localServerSocket = null
        localServerPort = 0
        localServerThread = null
    }

    private fun extractAnnounceUrl(torrentBytes: ByteArray): String? {
        return try {
            val (root, _) = decodeBencode(torrentBytes, 0)
            val dict = root as? BencodeValue.BDict ?: return null
            val announceEntry = dict.entries.find { (k, _) -> k.bytes.contentEquals("announce".toByteArray()) }
                ?: return null
            String((announceEntry.second as BencodeValue.BString).bytes)
        } catch (e: Exception) {
            null
        }
    }

    private fun modifyTorrentAnnounce(torrentBytes: ByteArray, newAnnounce: String): ByteArray {
        return try {
            val oldAnnounce = extractAnnounceUrl(torrentBytes) ?: return torrentBytes
            val oldAnnounceStr = String(oldAnnounce.toByteArray())
            val torrentStr = String(torrentBytes, Charsets.ISO_8859_1)

            // Simple string replacement — works for most .torrent files
            // because announce URLs are stored as bencode strings
            if (torrentStr.contains(oldAnnounceStr)) {
                val modified = torrentStr.replace(oldAnnounceStr, newAnnounce)
                return modified.toByteArray(Charsets.ISO_8859_1)
            }

            // Fallback: rebuild the bencode dict with modified announce
            val (root, _) = decodeBencode(torrentBytes, 0)
            val dict = root as? BencodeValue.BDict ?: return torrentBytes

            val entries = dict.entries.toMutableList()
            val announceIdx = entries.indexOfFirst { (k, _) -> k.bytes.contentEquals("announce".toByteArray()) }
            if (announceIdx >= 0) {
                entries[announceIdx] = entries[announceIdx].copy(
                    second = BencodeValue.BString(newAnnounce.toByteArray())
                )
            }

            // Re-encode the dict
            val encoded = encodeBencode(BencodeValue.BDict(entries))
            encoded
        } catch (e: Exception) {
            Log.e(TAG, "modifyTorrentAnnounce error: ${e.message}")
            torrentBytes
        }
    }

    private fun encodeBencode(value: BencodeValue): ByteArray {
        return when (value) {
            is BencodeValue.BString -> {
                val lenBytes = "${value.bytes.size}:".toByteArray()
                lenBytes + value.bytes
            }
            is BencodeValue.BInt -> "i${value.value}e".toByteArray()
            is BencodeValue.BList -> {
                val items = value.items.map { encodeBencode(it) }
                "l".toByteArray() + items.fold(ByteArray(0)) { acc, b -> acc + b } + "e".toByteArray()
            }
            is BencodeValue.BDict -> {
                val entries = value.entries.map { (k, v) ->
                    encodeBencode(k) + encodeBencode(v)
                }
                "d".toByteArray() + entries.fold(ByteArray(0)) { acc, b -> acc + b } + "e".toByteArray()
            }
        }
    }

    // ==================== TORRENT FILE PARSING ====================

    private fun parseTorrentFileList(torrentBytes: ByteArray): List<TorrentFileInfo>? {
        return try {
            val (root, _) = decodeBencode(torrentBytes, 0)
            val dict = root as? BencodeValue.BDict ?: return null

            val infoEntry = dict.entries.find { (k, _) -> k.bytes.contentEquals("info".toByteArray()) }
                ?: return null
            val infoDict = infoEntry.second as? BencodeValue.BDict ?: return null

            // Check for multi-file torrent (has "files" key in info dict)
            val filesEntry = infoDict.entries.find { (k, _) -> k.bytes.contentEquals("files".toByteArray()) }

            if (filesEntry != null) {
                // Multi-file torrent
                val filesList = (filesEntry.second as? BencodeValue.BList)?.items ?: return null
                val result = mutableListOf<TorrentFileInfo>()

                // Get the torrent name for the root folder
                val nameEntry = infoDict.entries.find { (k, _) -> k.bytes.contentEquals("name".toByteArray()) }
                val torrentName = nameEntry?.let { String((it.second as BencodeValue.BString).bytes) } ?: ""

                for (fileItem in filesList) {
                    val fileDict = fileItem as? BencodeValue.BDict ?: continue
                    val lengthEntry = fileDict.entries.find { (k, _) -> k.bytes.contentEquals("length".toByteArray()) }
                    val pathEntry = fileDict.entries.find { (k, _) -> k.bytes.contentEquals("path".toByteArray()) }

                    val length = (lengthEntry?.second as? BencodeValue.BInt)?.value ?: 0L

                    if (pathEntry != null) {
                        val pathList = (pathEntry.second as? BencodeValue.BList)?.items ?: continue
                        val pathParts = pathList.mapNotNull {
                            String((it as? BencodeValue.BString)?.bytes ?: ByteArray(0))
                        }
                        val fullPath = if (torrentName.isNotEmpty()) {
                            "$torrentName/${pathParts.joinToString("/")}"
                        } else {
                            pathParts.joinToString("/")
                        }
                        result.add(TorrentFileInfo(fullPath, length))
                    }
                }
                result
            } else {
                // Single-file torrent
                val nameEntry = infoDict.entries.find { (k, _) -> k.bytes.contentEquals("name".toByteArray()) }
                val lengthEntry = infoDict.entries.find { (k, _) -> k.bytes.contentEquals("length".toByteArray()) }

                val name = nameEntry?.let { String((it.second as BencodeValue.BString).bytes) } ?: "video"
                val length = (lengthEntry?.second as? BencodeValue.BInt)?.value ?: 0L

                listOf(TorrentFileInfo(name, length))
            }
        } catch (e: Exception) {
            Log.e(TAG, "parseTorrentFileList error: ${e.message}")
            null
        }
    }

    private fun tryParseTorrentFiles(torrentId: String, downloadUrl: String, isFree: Boolean): List<TorrentFileInfo>? {
        if (!ensureLogin()) return null

        var resolvedUrl = downloadUrl
        if (resolvedUrl.isBlank() || !resolvedUrl.contains("&f=")) {
            resolvedUrl = resolveDownloadUrl(torrentId, "") ?: return null
        }

        val result = downloadTorrentFile(resolvedUrl)
        return when (result) {
            is TorrentDownloadResult.Success -> {
                cacheTorrentBytes(torrentId, result.bytes)
                parseTorrentFileList(result.bytes)
            }
            is TorrentDownloadResult.DailyLimitExceeded -> {
                Log.w(TAG, "DailyLimitExceeded in tryParseTorrentFiles, thanking and retrying...")
                thankUploader(torrentId, "")
                val retryResult = downloadTorrentFile(resolvedUrl)
                if (retryResult is TorrentDownloadResult.Success) {
                    cacheTorrentBytes(torrentId, retryResult.bytes)
                    parseTorrentFileList(retryResult.bytes)
                } else null
            }
            else -> null
        }
    }

    private fun cacheTorrentBytes(torrentId: String, bytes: ByteArray) {
        if (torrentCache.size >= MAX_TORRENT_CACHE) {
            val oldestKey = torrentCache.keys.first()
            torrentCache.remove(oldestKey)
            Log.d(TAG, "Torrent cache evicted: $oldestKey")
        }
        torrentCache[torrentId] = bytes
        Log.d(TAG, "Cached .torrent for $torrentId (${bytes.size} bytes), cache size: ${torrentCache.size}")
    }

    // ==================== LOAD LINKS ====================
    //
    // Three data formats handled:
    //
    // 1. Pre-resolved TorrServe stream (ts://...):
    //    Format: ts://STREAM_URL|FILENAME
    //    The torrent was already uploaded to TorrServe in load().
    //    We create ONE ExtractorLink directly — the user sees exactly
    //    one source, not a flat list of all episodes.
    //    Also adds legacy TORRENT/MAGNET fallback sources.
    //
    // 2. Legacy (pipe-delimited):
    //    Format: torrentId|detailUrl|downloadUrl|magnetUrl|isFree|isExternal
    //    The full download/upload cycle runs here.
    //    Used as fallback when TorrServe pre-resolve failed.
    //    Offers both TORRENT source (CS built-in player) and MAGNET source.

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // === PRE-RESOLVED TORRSERVE STREAM: direct link from load() ===
        // Each episode has its own ts:// URL, so the user sees ONE source.
        if (data.startsWith("ts://")) {
            val afterPrefix = data.substringAfter("ts://")
            val pipeIndex = afterPrefix.indexOf('|')
            val streamUrl = if (pipeIndex >= 0) afterPrefix.substring(0, pipeIndex) else afterPrefix
            val fileName = if (pipeIndex >= 0) afterPrefix.substring(pipeIndex + 1) else "Video"

            // Return the TorrServe link FIRST so playback starts immediately,
            // then adjust priorities in the background (don't block playback).
            Log.d(TAG, "loadLinks: pre-resolved TorrServe stream → $fileName")
            callback(
                newExtractorLink(
                    source = "${this.name} (TorrServe)",
                    name = fileName,
                    url = streamUrl,
                    type = ExtractorLinkType.VIDEO
                )
            )

            // Resume only this file, pause all others to save bandwidth.
            // Best-effort — if it fails, playback still works.
            try {
                val parsed = parseTorrServeUrl(streamUrl)
                if (parsed != null) {
                    val (hash, activeIndex) = parsed
                    // First, resume the active file
                    setTorrServeFilePriority(hash, activeIndex, 1)
                    // Then pause all other files
                    val allIndices = getTorrServeFileIndices(hash)
                    if (allIndices.isNotEmpty()) {
                        for (idx in allIndices) {
                            if (idx != activeIndex) {
                                setTorrServeFilePriority(hash, idx, 0)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "TorrServe priority adjustment failed (non-fatal): ${e.message}")
            }

            return true
        }

        // === LEGACY PATH: pipe-delimited torrent data ===
        val parts = data.split("|", limit = 6)
        val torrentId = parts.getOrNull(0) ?: "0"
        val detailUrl = parts.getOrNull(1) ?: ""
        val downloadUrl = parts.getOrNull(2) ?: ""
        val magnetUrl = parts.getOrNull(3) ?: ""
        val isFree = parts.getOrNull(4) == "1"
        val isExternal = parts.getOrNull(5) == "1"

        Log.d(TAG, "loadLinks [legacy]: id=$torrentId, free=$isFree, external=$isExternal")

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

            // === INTERNAL TORRENTS ===
            // We offer multiple source options:
            // 1. "Arabp (TorrServe)" — if TorrServe is running, upload and get direct stream URLs
            // 2. "Arabp" — CS player with .torrent file (fallback)
            // 3. "Arabp Magnet" — magnet link (fallback / copy to Webbie)

            var generatedMagnet: String? = null

            // First, try using the page's magnet link if available
            if (magnetUrl.startsWith("magnet:")) {
                generatedMagnet = magnetUrl
            }

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

                        // Also grab magnet link from detail page if we don't have one yet
                        if (generatedMagnet == null) {
                            val magnetLink = detailDoc.selectFirst("a[href^=magnet:]")?.attr("href")
                            if (magnetLink != null) {
                                generatedMagnet = magnetLink
                                Log.d(TAG, "Found magnet link on detail page")
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

            // Step 2: Try TorrServe first, then fall back to local server + magnet
            if (resolvedDownloadUrl.isNotBlank() && resolvedDownloadUrl.contains("&f=")) {
                try {
                    // Check cache first — avoid re-downloading if we already have it
                    val cachedBytes = torrentCache[torrentId]
                    val torrentBytes: ByteArray? = if (cachedBytes != null) {
                        Log.d(TAG, "loadLinks: using cached .torrent for $torrentId (${cachedBytes.size} bytes)")
                        cachedBytes
                    } else {
                        val result = downloadTorrentFile(resolvedDownloadUrl)
                        when (result) {
                            is TorrentDownloadResult.Success -> {
                                cacheTorrentBytes(torrentId, result.bytes)
                                result.bytes
                            }
                            is TorrentDownloadResult.DailyLimitExceeded -> {
                                Log.w(TAG, "DAILY DOWNLOAD LIMIT EXCEEDED, thanking uploader and retrying...")
                                thankUploader(torrentId, "")
                                val retryResult = downloadTorrentFile(resolvedDownloadUrl)
                                when (retryResult) {
                                    is TorrentDownloadResult.Success -> {
                                        cacheTorrentBytes(torrentId, retryResult.bytes)
                                        retryResult.bytes
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
                                        null
                                    }
                                }
                            }
                            is TorrentDownloadResult.NotLoggedIn -> {
                                isLoggedIn = false
                                Log.e(TAG, "Session expired")
                                null
                            }
                            is TorrentDownloadResult.Error -> {
                                Log.e(TAG, "Download error: ${result.message}")
                                null
                            }
                        }
                    }

                    if (torrentBytes != null) {
                        // Generate magnet from downloaded .torrent if we still don't have one
                        if (generatedMagnet == null) {
                            generatedMagnet = torrentToMagnet(torrentBytes)
                        }

                        // === SOURCE 1: Try TorrServe ===
                        val torrServeEntries = uploadToTorrServe(torrentBytes, torrentId)
                        if (torrServeEntries != null && torrServeEntries.isNotEmpty()) {
                            Log.d(TAG, "TorrServe: ${torrServeEntries.size} files available")

                            if (torrServeEntries.size == 1) {
                                // Single file — just add one TorrServe source
                                val entry = torrServeEntries.first()
                                callback(
                                    newExtractorLink(
                                        source = "${this.name} (TorrServe)",
                                        name = "${this.name} (TorrServe)",
                                        url = entry.streamUrl,
                                        type = ExtractorLinkType.VIDEO
                                    )
                                )
                            } else {
                                // Multi-file — add all video files as separate sources
                                val groups = computeShowGroups(torrServeEntries)
                                val distinctShows = groups.map { it.showName }.filter { it.isNotEmpty() }.distinct()
                                val isMultiShow = distinctShows.size > 1

                                for (entry in torrServeEntries) {
                                    val group = groups.find { it.path == entry.filePath }
                                    val linkName = when {
                                        isMultiShow && group != null && group.showName.isNotEmpty() ->
                                            "[${group.showName}] ${entry.fileName}"
                                        else -> "${this.name} — ${entry.fileName}"
                                    }
                                    Log.d(TAG, "TorrServe link: ${entry.fileName} → ${entry.streamUrl}")
                                    callback(
                                        newExtractorLink(
                                            source = "${this.name} (TorrServe)",
                                            name = linkName,
                                            url = entry.streamUrl,
                                            type = ExtractorLinkType.VIDEO
                                        )
                                    )
                                }
                            }
                            foundLink = true
                        } else {
                            Log.w(TAG, "TorrServe not available or upload failed, falling back to CS built-in torrent")
                        }

                        // === SOURCE 2: CS built-in torrent player (fallback) ===
                        if (!foundLink || true) { // Always add as alternative
                            val localUrl = startLocalTorrentServer(torrentId, torrentBytes)
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
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Torrent download/serve error: ${e.message}")
                }
            }

            // === SOURCE 3: Magnet link ===
            // CS's engine respects private=1 flag → still counts on tracker.
            // But you can long-press this source in CS → "Copy Link" → paste into Webbie.
            // Webbie ignores private=1 and keeps DHT enabled → download NOT counted.
            //
            // IMPORTANT: Always add magnet as fallback, even if TORRENT source succeeded.
            // If CS's torrent engine fails to parse the .torrent file (error 3003),
            // the magnet source provides a working alternative.
            if (generatedMagnet != null) {
                Log.d(TAG, "Adding magnet source")
                callback(
                    newExtractorLink(
                        source = "${this.name} Magnet",
                        name = "${this.name} (Magnet)",
                        url = generatedMagnet,
                        type = ExtractorLinkType.MAGNET
                    )
                )
                foundLink = true

            }

            if (!foundLink) {
                Log.e(TAG, "loadLinks: NO sources found for torrent $torrentId! downloadUrl=$resolvedDownloadUrl, magnet=$generatedMagnet")
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

    private fun torrentToMagnet(torrentBytes: ByteArray): String? {
        return try {
            val infoBytesResult = findInfoDictBytes(torrentBytes)
            if (infoBytesResult == null) {
                Log.e(TAG, "torrentToMagnet: could not find info dict in .torrent file")
                return null
            }

            val sha1 = java.security.MessageDigest.getInstance("SHA-1").digest(infoBytesResult.bytes)
            val infoHashBase32 = base32Encode(sha1)

            Log.d(TAG, "torrentToMagnet: info hash (hex) = ${sha1.joinToString("") { "%02x".format(it) }}")
            Log.d(TAG, "torrentToMagnet: info hash (base32) = $infoHashBase32")

            val (root, _) = decodeBencode(torrentBytes, 0)
            val dict = root as? BencodeValue.BDict ?: return null

            val infoEntry = dict.entries.find { (k, _) ->
                k.bytes.contentEquals("info".toByteArray())
            } ?: return null

            val magnet = StringBuilder("magnet:?xt=urn:btih:$infoHashBase32")

            val nameEntry = (infoEntry.second as? BencodeValue.BDict)?.entries?.find { (k, _) ->
                k.bytes.contentEquals("name".toByteArray())
            }
            if (nameEntry != null) {
                val name = String((nameEntry.second as BencodeValue.BString).bytes)
                magnet.append("&dn=").append(URLEncoder.encode(name, "UTF-8"))
            }

            val trackerUrls = mutableListOf<String>()

            val announceEntry = dict.entries.find { (k, _) ->
                k.bytes.contentEquals("announce".toByteArray())
            }
            if (announceEntry != null) {
                trackerUrls.add(String((announceEntry.second as BencodeValue.BString).bytes))
            }

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

            // Extract passkey from tracker URLs and cache it
            for (url in trackerUrls) {
                val match = PASSKEY_REGEX.find(url)
                if (match != null) {
                    cachedPasskey = match.groupValues[1]
                    Log.d(TAG, "torrentToMagnet: extracted passkey from announce URL")
                    break
                }
            }

            // Build tracker parameters
            val seenUrls = mutableSetOf<String>()

            for (url in trackerUrls) {
                if (!url.contains("arabp2p.net")) {
                    if (seenUrls.add(url)) {
                        magnet.append("&tr=").append(URLEncoder.encode(url, "UTF-8"))
                    }
                    continue
                }

                val passkeyMatch = PASSKEY_REGEX.find(url)
                if (passkeyMatch != null) {
                    val pk = passkeyMatch.groupValues[1]
                    val nonPasskeyUrl = url.replace("/$pk/announce", "/announce")

                    if (seenUrls.add(nonPasskeyUrl)) {
                        magnet.append("&tr=").append(URLEncoder.encode(nonPasskeyUrl, "UTF-8"))
                    }
                    if (seenUrls.add(url)) {
                        magnet.append("&tr=").append(URLEncoder.encode(url, "UTF-8"))
                    }
                } else {
                    if (seenUrls.add(url)) {
                        magnet.append("&tr=").append(URLEncoder.encode(url, "UTF-8"))
                    }
                    val pk = cachedPasskey
                    if (pk != null) {
                        val passkeyUrl = url.replace("/announce", "/$pk/announce")
                        if (seenUrls.add(passkeyUrl)) {
                            magnet.append("&tr=").append(URLEncoder.encode(passkeyUrl, "UTF-8"))
                        }
                    }
                }
            }

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

    private data class InfoBytesResult(val bytes: ByteArray, val startOffset: Int, val endOffset: Int)

    private fun findInfoDictBytes(data: ByteArray): InfoBytesResult? {
        return try {
            if (data.isEmpty() || data[0] != 'd'.code.toByte()) return null

            var pos = 1 // skip opening 'd'

            while (pos < data.size && data[pos] != 'e'.code.toByte()) {
                val (key, afterKey) = decodeBencodeString(data, pos)
                val keyStr = String((key as BencodeValue.BString).bytes)

                if (keyStr == "info") {
                    val valueStart = afterKey
                    val (_, valueEnd) = decodeBencode(data, valueStart)

                    val infoBytes = data.sliceArray(valueStart until valueEnd)
                    Log.d(TAG, "findInfoDictBytes: found info dict at offset $valueStart..$valueEnd (${infoBytes.size} bytes)")
                    return InfoBytesResult(infoBytes, valueStart, valueEnd)
                }

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

    private fun fetchPasskeyFromWebsite(): String? {
        if (cachedPasskey != null) return cachedPasskey
        if (!ensureLogin()) return null

        return try {
            val profileRequest = Request.Builder()
                .url("$mainUrl/index.php?page=usercp")
                .headers(getAuthHeaders(referer = "$mainUrl/").toHeaders())
                .build()

            authClient.newCall(profileRequest).execute().use { response ->
                val body = response.body?.string() ?: return null
                val doc = Jsoup.parse(body, "$mainUrl/index.php?page=usercp")

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

        return result.toString()
    }

    // ==================== BENCODE PARSER ====================

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

                BencodeParseResult(BencodeValue.BDict(entries), pos + 1)
            }

            firstByte == 'l' -> { // List
                var pos = startPos + 1
                val items = mutableListOf<BencodeValue>()

                while (pos < data.size && data[pos].toInt().toChar() != 'e') {
                    val (item, afterItem) = decodeBencode(data, pos)
                    items.add(item)
                    pos = afterItem
                }

                BencodeParseResult(BencodeValue.BList(items), pos + 1)
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

    private fun normalizePath(path: String): String {
        return path.replace("\\", "/").trim('/')
    }

    private fun extractFolderName(normalizedPath: String): String {
        val parts = normalizedPath.split("/")
        return if (parts.size > 1) parts.first() else ""
    }

    private fun isVideoFile(fileName: String): Boolean {
        val lower = fileName.lowercase()
        return lower.endsWith(".mkv") || lower.endsWith(".mp4") || lower.endsWith(".avi") ||
                lower.endsWith(".mov") || lower.endsWith(".wmv") || lower.endsWith(".flv") ||
                lower.endsWith(".webm") || lower.endsWith(".ts") || lower.endsWith(".m4v")
    }

    private fun cleanTitleText(text: String): String {
        return text
            .replace(Regex("<[^>]*>"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
