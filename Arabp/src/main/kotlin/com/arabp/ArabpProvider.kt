package com.arabp

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.SubtitleFile
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
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

        // TorrServe Matrix API — default host:port
        // Change this if your TorrServe runs on a different address
        private const val TORRSERVE_HOST = "http://127.0.0.1:8090"
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

    // ==================== HOME PAGE ====================

    override val mainPage = mainPageOf(
        "$mainUrl/index.php?page=anime-listing" to "قائمة الانمي"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = if (page > 1) "${request.data}&pages=$page" else request.data
        val doc = app.get(url).document
        val homeSets = mutableListOf<HomePageList>()

        try {
            val items = doc.select("div.listing_div1").mapNotNull { toSearchResult(it) }
            if (items.isNotEmpty()) {
                homeSets.add(HomePageList(request.name, items))
            }
            return newHomePageResponse(homeSets, items.isNotEmpty())
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
                .replace("<br>", " ").replace("<br/>", " ").replace("<br />", " ").trim()
            val title = cleanTitleText(rawTitle)

            val posterUrl = element.selectFirst("img.listing_poster")?.attr("src")
                ?: element.selectFirst("img")?.attr("src") ?: ""

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
        val results = mutableListOf<SearchResponse>()

        // Source 1: PUBLIC anime listing
        try {
            val animeUrl = "$mainUrl/index.php?page=anime-listing&search=$encoded"
            val animeDoc = app.get(animeUrl).document
            val animeResults = animeDoc.select("div.listing_div1").mapNotNull { toSearchResult(it) }
            Log.d(TAG, "Anime listing search: found ${animeResults.size} results")
            results.addAll(animeResults)
        } catch (e: Exception) {
            Log.e(TAG, "Anime listing search error: ${e.message}")
        }

        // Source 2: PRIVATE torrents page
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

            val epData = "$torrentId|$detailHref|${toAbsoluteUrl(downloadHref)}|$magnetHref|${if (isFree) "1" else "0"}|${if (isExternal) "1" else "0"}"
            newAnimeSearchResponse(displayName, epData, tvType) { this.posterUrl = "" }
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

            val epData = "$torrentId|$detailHref|${toAbsoluteUrl(downloadHref)}|$magnetHref|${if (isFree) "1" else "0"}|${if (isExternal) "1" else "0"}"
            newAnimeSearchResponse(displayName, epData, tvType) { this.posterUrl = "" }
        } catch (e: Exception) {
            Log.e(TAG, "modernTorrentRowToSearchResult Error: ${e.message}")
            null
        }
    }

    // ==================== LOAD (Detail Page) ====================

    override suspend fun load(url: String): LoadResponse? {
        if (url.contains("|")) return loadFromTorrentData(url)

        val fullUrl = toAbsoluteUrl(url)
        val doc = app.get(fullUrl).document

        return try {
            val h1 = doc.selectFirst("div.listing_div_id h1")
            val rawTitle = h1?.html()
                ?.replace("<br>", " ")?.replace("<br/>", " ")?.replace("<br />", " ")?.trim() ?: ""
            val title = cleanTitleText(rawTitle)

            val posterUrl = doc.selectFirst("div.listing_div_id img.listing_poster")?.attr("src")
                ?: doc.selectFirst("img.listing_poster")?.attr("src") ?: ""

            val rows = doc.select("table#listing_table tr")
            val episodes = rows.mapNotNull { row ->
                val nameLink = row.selectFirst("a[href*=torrent-details]") ?: return@mapNotNull null
                val epName = cleanTitleText(nameLink.text())
                val detailHref = nameLink.attr("href")
                val torrentId = DIGITS.find(detailHref.substringAfter("id="))?.value ?: return@mapNotNull null
                val downloadHref = row.selectFirst("a[href*=download.php]")?.attr("href") ?: ""
                val isFree = isFreeTorrent(row)

                val epData = "$torrentId|${toAbsoluteUrl(detailHref)}|${toAbsoluteUrl(downloadHref)}||${if (isFree) "1" else "0"}|0"

                val tds = row.select("td")
                val size = tds.getOrNull(3)?.text()?.trim() ?: ""
                val seeders = tds.getOrNull(4)?.text()?.trim() ?: ""

                val displayName = buildString {
                    append(epName)
                    if (isFree) append(" ✅مجاني")
                    if (size.isNotEmpty()) append(" | $size")
                    if (seeders.isNotEmpty()) append(" | ▲$seeders")
                }

                newEpisode(epData) {
                    name = displayName
                    this.posterUrl = toAbsoluteUrl(posterUrl)
                }
            }

            if (episodes.isEmpty()) {
                newMovieLoadResponse(title, fullUrl, TvType.Anime, "0|$fullUrl|||0|0") {
                    this.posterUrl = toAbsoluteUrl(posterUrl)
                    this.posterHeaders = imageHeaders
                }
            } else {
                newTvSeriesLoadResponse(title, fullUrl, TvType.Anime, episodes) {
                    this.posterUrl = toAbsoluteUrl(posterUrl)
                    this.posterHeaders = imageHeaders
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Load Error: ${e.message}")
            null
        }
    }

    private suspend fun loadFromTorrentData(data: String): LoadResponse? {
        val parts = data.split("|", limit = 6)
        val torrentId = parts.getOrNull(0) ?: "0"
        val detailUrl = parts.getOrNull(1) ?: ""

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
    // STRATEGY:
    // 1. External torrents → pass magnet link directly
    // 2. Internal torrents → download .torrent → upload to TorrServe → get file list
    //
    // MULTI-FILE SUPPORT:
    // TorrServe returns file_stats with all files in the torrent.
    // We create one ExtractorLink per VIDEO file, each with its own
    // stream URL pointing to the correct file index.
    // This fixes the issue where only the first episode plays.

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

            // === INTERNAL TORRENTS ===
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

                        // Check for magnet link on detail page
                        val magnetEl = detailDoc.selectFirst("a[href^=magnet:]")
                        if (magnetEl != null) {
                            callback(
                                newExtractorLink(
                                    source = this.name,
                                    name = "${this.name} (Magnet)",
                                    url = magnetEl.attr("href"),
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

            // Step 2: Download .torrent → upload to TorrServe → get ALL video files
            if (resolvedDownloadUrl.isNotBlank() && resolvedDownloadUrl.contains("&f=")) {
                try {
                    val result = downloadTorrentFile(resolvedDownloadUrl)
                    when (result) {
                        is TorrentDownloadResult.Success -> {
                            Log.d(TAG, "Downloaded .torrent: ${result.bytes.size} bytes, uploading to TorrServe...")
                            val streamEntries = uploadToTorrServe(result.bytes, torrentId)
                            if (streamEntries != null && streamEntries.isNotEmpty()) {
                                for ((index, entry) in streamEntries.withIndex()) {
                                    val linkName = if (streamEntries.size == 1) {
                                        "${this.name} (TorrServe)"
                                    } else {
                                        "${this.name} — ${entry.fileName}"
                                    }
                                    Log.d(TAG, "TorrServe link [$index]: ${entry.fileName} → ${entry.streamUrl}")
                                    callback(
                                        newExtractorLink(
                                            source = this.name,
                                            name = linkName,
                                            url = entry.streamUrl,
                                            type = ExtractorLinkType.VIDEO
                                        )
                                    )
                                }
                                foundLink = true
                            } else {
                                Log.e(TAG, "TorrServe upload failed — is TorrServe running on $TORRSERVE_HOST?")
                            }
                        }
                        is TorrentDownloadResult.DailyLimitExceeded -> {
                            Log.e(TAG, "DAILY DOWNLOAD LIMIT EXCEEDED for torrent id=$torrentId")
                            callback(
                                newExtractorLink(
                                    source = this.name,
                                    name = "❌ تجاوزت الحد اليومي للتحميل",
                                    url = "error://daily-limit-exceeded",
                                    type = ExtractorLinkType.VIDEO
                                )
                            )
                            foundLink = true
                        }
                        is TorrentDownloadResult.NotLoggedIn -> {
                            Log.e(TAG, "Session expired — resetting login state")
                            isLoggedIn = false
                        }
                        is TorrentDownloadResult.Error -> {
                            Log.e(TAG, "Failed to download .torrent: ${result.message}")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "TorrServe integration error: ${e.message}")
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

    // ==================== TORRENT DOWNLOAD ====================

    sealed class TorrentDownloadResult {
        data class Success(val bytes: ByteArray) : TorrentDownloadResult()
        data object DailyLimitExceeded : TorrentDownloadResult()
        data object NotLoggedIn : TorrentDownloadResult()
        data class Error(val message: String) : TorrentDownloadResult()
    }

    /**
     * Download .torrent file. Checks Content-Type to detect error pages
     * (daily limit exceeded) vs actual .torrent files.
     */
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

    // ==================== TORRSERVE INTEGRATION ====================

    /**
     * Represents one streamable file from a TorrServe torrent.
     */
    data class TorrServeStreamEntry(
        val fileName: String,
        val fileIndex: Int,
        val streamUrl: String
    )

    /**
     * Upload .torrent to TorrServe, parse file_stats from the response,
     * and return a list of stream entries — one per VIDEO file.
     *
     * For single-file torrents: returns 1 entry with index=1.
     * For multi-file torrents (season packs): returns one entry per video file,
     * each with the correct file index for TorrServe's stream URL.
     */
    private fun uploadToTorrServe(torrentBytes: ByteArray, torrentId: String): List<TorrServeStreamEntry>? {
        return try {
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file", "arabp_$torrentId.torrent",
                    torrentBytes.toRequestBody("application/x-bittorrent".toMediaType())
                )
                .addFormDataPart("save", "1")
                .build()

            val request = Request.Builder()
                .url("$TORRSERVE_HOST/torrent/upload")
                .post(requestBody)
                .build()

            torrServeClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "TorrServe upload HTTP error: ${response.code}")
                    return null
                }

                val responseBody = response.body?.string() ?: return null
                Log.d(TAG, "TorrServe upload response: $responseBody")

                val hash = parseHashFromJson(responseBody)
                if (hash == null) {
                    Log.e(TAG, "Could not parse hash from TorrServe response")
                    return null
                }

                Log.d(TAG, "TorrServe hash: $hash")

                // Wait for TorrServe to start processing the torrent
                Thread.sleep(500)

                // Parse file_stats from the response to get all files
                val fileStats = parseFileStatsFromJson(responseBody)
                Log.d(TAG, "Parsed ${fileStats.size} files from TorrServe response")

                if (fileStats.isEmpty()) {
                    // Fallback: single file torrent, use index=1
                    return listOf(
                        TorrServeStreamEntry(
                            fileName = "Video",
                            fileIndex = 1,
                            streamUrl = "$TORRSERVE_HOST/stream/fname?link=$hash&index=1&play"
                        )
                    )
                }

                // Filter to only video files and create stream entries
                val videoEntries = fileStats
                    .filter { (path, _) -> isVideoFile(path) }
                    .map { (path, id) ->
                        // Extract just the filename from the path
                        val fileName = path.substringAfterLast("/")
                        TorrServeStreamEntry(
                            fileName = fileName,
                            fileIndex = id,
                            streamUrl = "$TORRSERVE_HOST/stream/fname?link=$hash&index=$id&play"
                        )
                    }

                Log.d(TAG, "Created ${videoEntries.size} video stream entries (from ${fileStats.size} total files)")

                if (videoEntries.isEmpty()) {
                    // If no video files detected, use first file as fallback
                    val first = fileStats.first()
                    listOf(
                        TorrServeStreamEntry(
                            fileName = first.first.substringAfterLast("/"),
                            fileIndex = first.second,
                            streamUrl = "$TORRSERVE_HOST/stream/fname?link=$hash&index=${first.second}&play"
                        )
                    )
                } else {
                    videoEntries
                }
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
     * Parse file_stats from TorrServe's JSON response.
     * Returns list of (path, id) pairs.
     *
     * Format: "file_stats":[{"id":1,"path":"Episode01.mkv","length":...}, ...]
     */
    private fun parseFileStatsFromJson(json: String): List<Pair<String, Int>> {
        val results = mutableListOf<Pair<String, Int>>()
        try {
            // Find the file_stats array
            val fsStart = json.indexOf("\"file_stats\"")
            if (fsStart < 0) return results

            val arrayStart = json.indexOf('[', fsStart)
            if (arrayStart < 0) return results
            val arrayEnd = json.indexOf(']', arrayStart)
            if (arrayEnd < 0) return results

            val fileStatsContent = json.substring(arrayStart, arrayEnd + 1)

            // Extract each {"id":N,"path":"..."} or {"path":"...","id":N} entry
            val entryPattern = """"path"\s*:\s*"([^"]*?)"[^}]*?"id"\s*:\s*(\d+)""".toRegex()
            val entryPattern2 = """"id"\s*:\s*(\d+)[^}]*?"path"\s*:\s*"([^"]*?)"""".toRegex()

            for (match in entryPattern.findAll(fileStatsContent)) {
                val path = match.groupValues[1]
                val id = match.groupValues[2].toIntOrNull() ?: continue
                results.add(path to id)
            }

            // If first pattern didn't find anything, try the reverse order
            if (results.isEmpty()) {
                for (match in entryPattern2.findAll(fileStatsContent)) {
                    val id = match.groupValues[1].toIntOrNull() ?: continue
                    val path = match.groupValues[2]
                    results.add(path to id)
                }
            }

            // Generic fallback: find all "id":N and "path":"..." pairs
            if (results.isEmpty()) {
                val idPattern = """"id"\s*:\s*(\d+)""".toRegex()
                val pathPattern = """"path"\s*:\s*"([^"]*)"""".toRegex()
                val ids = idPattern.findAll(fileStatsContent).mapNotNull { it.groupValues[1].toIntOrNull() }.toList()
                val paths = pathPattern.findAll(fileStatsContent).map { it.groupValues[1] }.toList()
                for (i in ids.indices) {
                    if (i < paths.size) results.add(paths[i] to ids[i])
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "parseFileStatsFromJson error: ${e.message}")
        }
        return results
    }

    /**
     * Check if a filename looks like a video file.
     */
    private fun isVideoFile(fileName: String): Boolean {
        val videoExtensions = listOf(
            ".mkv", ".mp4", ".avi", ".wmv", ".flv", ".mov", ".webm",
            ".m4v", ".ts", ".mpg", ".mpeg", ".ogv", ".rmvb", ".rm",
            ".vob", ".m2ts", ".mts", ".3gp", ".divx"
        )
        val lower = fileName.lowercase()
        return videoExtensions.any { lower.endsWith(it) }
    }

    private fun parseHashFromJson(json: String): String? {
        val hashPattern = """"hash"\s*:\s*"([a-fA-F0-9]{40})"""".toRegex()
        return hashPattern.find(json)?.groupValues?.getOrNull(1)?.lowercase()
    }

    // ==================== HELPERS ====================

    private fun Map<String, String>.toOkHttpHeaders(): okhttp3.Headers {
        val builder = okhttp3.Headers.Builder()
        this.forEach { (key, value) -> builder.add(key, value) }
        return builder.build()
    }

    private fun cleanTitleText(text: String): String {
        return text.replace("\\n", " ").replace("\n", " ").replace(Regex("\\s+"), " ").trim()
    }
}
