package com.arabicsource

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.*
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

// Simple in-memory cookie jar (no JavaNetCookieJar available)
private class SimpleCookieJar : okhttp3.CookieJar {
    private val store = ConcurrentHashMap<String, MutableList<Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        store.getOrPut(url.host) { mutableListOf() }.addAll(cookies)
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        return store[url.host] ?: emptyList()
    }
}

class ArabicSource : MainAPI() {
    companion object {
        private const val TAG = "ArabicSource"
        private const val MAIN_URL = "https://arabicsource.net"
        private const val LOGIN_USERNAME = "armano"
        private const val LOGIN_PASSWORD = "ARmano01**"

        private val USER_AGENTS = listOf(
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36",
            "Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Mobile Safari/537.36",
            "Mozilla/5.0 (Linux; Android 14; Galaxy S24 Ultra) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36",
            "Mozilla/5.0 (Linux; Android 13; OnePlus 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        )

        private fun categoryToTvType(catId: String): TvType = when (catId) {
            "1", "2", "21" -> TvType.AnimeMovie
            "3", "4", "8", "9", "10", "13" -> TvType.Movie
            "5", "6", "14" -> TvType.Anime
            "7", "11", "12", "15", "19", "20", "22" -> TvType.TvSeries
            else -> TvType.Movie
        }

        private fun resolutionIdToName(resId: String): String = when (resId) {
            "1" -> "4320p"; "2" -> "2160p"; "3" -> "1080p"; "5" -> "720p"; "8" -> "480p"
            else -> ""
        }

        private fun typeIdToName(typeId: String): String = when (typeId) {
            "1" -> "Full Disc"; "2" -> "Remux"; "3" -> "Encode"; "4" -> "WEB-DL"
            "5" -> "WEBRip"; "6" -> "HDTV"; "7" -> "HDR"; "8" -> "Dolby Vision"; "9" -> "3D"
            else -> ""
        }

        /** Find a byte in a ByteArray starting from an offset (Kotlin lacks this overload) */
        private fun ByteArray.indexOfByte(byte: Byte, fromIndex: Int): Int {
            for (i in fromIndex until this.size) {
                if (this[i] == byte) return i
            }
            return -1
        }
    }

    // ─── CloudStream required overrides ───
    override var mainUrl = MAIN_URL
    override var name = "المصدر العربي"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(
        TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AnimeMovie, TvType.OVA
    )

    // ─── HTTP clients ───
    private val cookieJar = SimpleCookieJar()

    private val authClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .cookieJar(cookieJar)
            .followRedirects(false)
            .followSslRedirects(true)
            .build()
    }

    private val browseClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .cookieJar(cookieJar)
            .build()
    }

    // ─── Background scope ───
    private val prefetchScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ─── Caches ───
    private val torrentBytesCache = ConcurrentHashMap<String, ByteArray>()

    // ─── Login state ───
    @Volatile private var loggedIn = false
    private val loginLock = Any()

    // ─── Eager login ───
    init {
        try {
            prefetchScope.launch { ensureLogin() }
        } catch (_: Exception) {}
    }

    // ═══════════════════════════════════════════════════════════════════
    // AUTHENTICATION
    // ═══════════════════════════════════════════════════════════════════

    private fun isSessionAlive(): Boolean {
        return cookieJar.loadForRequest(MAIN_URL.toHttpUrl())
            .any { it.name == "arabicsource_session" }
    }

    private suspend fun ensureLogin() {
        if (isSessionAlive()) { loggedIn = true; return }
        synchronized(loginLock) {
            if (isSessionAlive()) { loggedIn = true; return }
            runBlocking(Dispatchers.IO) { performLogin() }
        }
    }

    private fun performLogin() {
        try {
            // Use a redirect-following client for login flow
            val loginClient = browseClient.newBuilder()
                .followRedirects(true)
                .followSslRedirects(true)
                    .cookieJar(cookieJar)
                    .build()

            val getResponse = loginClient.newCall(
                okhttp3.Request.Builder()
                    .url("$mainUrl/login")
                    .header("User-Agent", USER_AGENTS[0])
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")
                    .build()
            ).execute()

            val loginPageBody = getResponse.body?.string() ?: return
            getResponse.close()
            val loginDoc = Jsoup.parse(loginPageBody, mainUrl)

            // Already logged in if redirected away from /login
            if (!loginPageBody.contains("name=\"password\"") && !loginPageBody.contains("name='password'")) {
                loggedIn = true
                Log.d(TAG, "Already logged in")
                return
            }

            val csrfToken = loginDoc.selectFirst("meta[name=csrf-token]")?.attr("content")
                ?: loginDoc.selectFirst("input[name=_token]")?.attr("value")
                ?: return

            // Build URL-encoded form (UNIT3D login expects application/x-www-form-urlencoded)
            val formBuilder = FormBody.Builder()
                .add("_token", csrfToken)
                .add("username", LOGIN_USERNAME)
                .add("password", LOGIN_PASSWORD)
                .add("remember", "on")
                .add("_username", "") // honeypot field (type=text, hidden off-screen) — MUST be present but empty
            // Add all other hidden inputs (_captcha, dynamic timestamp, etc.)
            loginDoc.select("input[type=hidden]").forEach { input ->
                val inputName = input.attr("name")
                val inputValue = input.attr("value")
                if (inputName == "_token") return@forEach
                formBuilder.add(inputName, inputValue)
            }
            val formBody = formBuilder.build()

            val postResponse = loginClient.newCall(
                okhttp3.Request.Builder()
                    .url("$mainUrl/login")
                    .header("User-Agent", USER_AGENTS[0])
                    .header("Referer", "$mainUrl/login")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")
                    .header("Origin", mainUrl)
                    .post(formBody)
                    .build()
            ).execute()

            val postBody = postResponse.body?.string()
            postResponse.close()

            loggedIn = isSessionAlive()
            if (loggedIn) {
                Log.d(TAG, "Login successful")
            } else {
                Log.w(TAG, "Login failed. Response snippet: ${postBody?.take(200)}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Login error", e)
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // BROWSE / FETCH HELPERS
    // ═══════════════════════════════════════════════════════════════════

    private suspend fun fetchDoc(url: String, useAuth: Boolean = false): Document? {
        return withContext(Dispatchers.IO) {
            try {
                val client = if (useAuth) authClient else browseClient
                val request = okhttp3.Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENTS.random())
                    .header("Accept", "text/html,application/xhtml+xml")
                    .header("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")
                    .build()

                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: return@withContext null

                if (body.contains("تسجيل الدخول") && body.contains("password") && url != "$mainUrl/login") {
                    Log.w(TAG, "Redirected to login, re-authenticating...")
                    loggedIn = false
                    withContext(Dispatchers.IO) { performLogin() }
                    if (loggedIn) {
                        val retryResponse = client.newCall(request).execute()
                        val retryBody = retryResponse.body?.string()
                        retryResponse.close()
                        if (retryBody != null) return@withContext Jsoup.parse(retryBody, url)
                    }
                    return@withContext null
                }

                response.close()
                Jsoup.parse(body, url)
            } catch (e: Exception) {
                Log.e(TAG, "fetchDoc error for $url", e)
                null
            }
        }
    }

    private suspend fun fetchBytes(url: String): ByteArray? {
        return withContext(Dispatchers.IO) {
            try {
                val request = okhttp3.Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENTS.random())
                    .build()
                val response = browseClient.newCall(request).execute()
                val bytes = response.body?.bytes()
                response.close()
                bytes
            } catch (e: Exception) {
                Log.e(TAG, "fetchBytes error for $url", e)
                null
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // MAIN PAGE
    // ═══════════════════════════════════════════════════════════════════

    override val mainPage = mainPageOf(
        "$mainUrl/torrents?categoryIds%5B0%5D=1" to "أفلام رسوم مدبلجة",
        "$mainUrl/torrents?categoryIds%5B0%5D=2" to "أفلام رسوم مترجمة",
        "$mainUrl/torrents?categoryIds%5B0%5D=3" to "أفلام عربية",
        "$mainUrl/torrents?categoryIds%5B0%5D=4" to "أفلام أجنبية",
        "$mainUrl/torrents?categoryIds%5B0%5D=5" to "مسلسلات رسوم مدبلجة",
        "$mainUrl/torrents?categoryIds%5B0%5D=6" to "مسلسلات رسوم مترجمة",
        "$mainUrl/torrents?categoryIds%5B0%5D=7" to "مسلسلات عربية",
        "$mainUrl/torrents?categoryIds%5B0%5D=22" to "مسلسلات أجنبية",
        "$mainUrl/torrents?categoryIds%5B0%5D=9" to "أفلام وثائقية",
        "$mainUrl/torrents?categoryIds%5B0%5D=20" to "مسلسلات وثائقية"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {
        ensureLogin()
        val url = if (page == 1) request.data else "${request.data}&page=$page"
        val doc = fetchDoc(url) ?: return newHomePageResponse(request.name, emptyList(), false)

        val items = parseTorrentList(doc)
        val hasNext = doc.selectFirst(".pagination__next a[href]") != null
        return newHomePageResponse(request.name, items, hasNext)
    }

    private fun parseTorrentList(doc: Document): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        val rows = doc.select("tr.torrent-search--list__no-poster-row, tr.torrent-search--list__poster-row")

        for (row in rows) {
            try {
                val categoryId = row.attr("data-category-id") ?: "4"
                val typeId = row.attr("data-type-id") ?: ""
                val resolutionId = row.attr("data-resolution-id") ?: ""

                val nameEl = row.selectFirst("a.torrent-search--list__name")
                val name = nameEl?.text()?.trim() ?: continue
                val href = nameEl?.attr("abs:href")
                if (href.isNullOrBlank()) continue

                val sizeText = row.selectFirst(".torrent-search--list__size span")?.text()?.trim() ?: ""
                val seeders = row.selectFirst(".torrent-search--list__seeders a")?.text()?.trim()?.toIntOrNull() ?: 0

                val tvType = categoryToTvType(categoryId)
                val resolution = resolutionIdToName(resolutionId)
                val encodeType = typeIdToName(typeId)

                // No posters in list view on this site

                val response: SearchResponse = when (tvType) {
                    TvType.Anime, TvType.AnimeMovie, TvType.OVA -> {
                        newAnimeSearchResponse(name, href, tvType)
                    }
                    TvType.TvSeries -> {
                        newTvSeriesSearchResponse(name, href, tvType)
                    }
                    else -> {
                        newMovieSearchResponse(name, href, tvType)
                    }
                }

                // Add quality tag using the helper
                if (resolution.isNotEmpty()) response.addQuality(resolution)
                if (encodeType.isNotEmpty()) response.addQuality(encodeType)

                results.add(response)
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing torrent row", e)
            }
        }
        return results
    }

    // ═══════════════════════════════════════════════════════════════════
    // SEARCH
    // ═══════════════════════════════════════════════════════════════════

    override suspend fun search(query: String): List<SearchResponse> {
        ensureLogin()
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/torrents?name=$encoded"
        val doc = fetchDoc(url) ?: return emptyList()
        return parseTorrentList(doc)
    }

    // ═══════════════════════════════════════════════════════════════════
    // LOAD (DETAIL PAGE)
    // ═══════════════════════════════════════════════════════════════════

    override suspend fun load(url: String): LoadResponse {
        ensureLogin()
        val doc = fetchDoc(url, useAuth = true)
            ?: throw ErrorLoadingException("فشل تحميل الصفحة")
        return parseDetailPage(doc, url)
    }

    private suspend fun parseDetailPage(doc: Document, pageUrl: String): LoadResponse {
        val torrentId = pageUrl.trimEnd('/').split("/").last()

        // Work metadata
        val title = doc.selectFirst("h1.meta__title")?.text()?.trim()
            ?: doc.selectFirst("h1")?.text()?.trim()
            ?: "بدون عنوان"

        val posterUrl = doc.selectFirst("img.meta__poster")?.attr("abs:src")
        val backdropUrl = doc.selectFirst("img.meta__backdrop")?.attr("abs:src")
        val description = doc.selectFirst("p.meta__description")?.text()?.trim() ?: ""

        // Torrent-specific info
        val torrentTitle = doc.select("article h1").lastOrNull()?.text()?.trim() ?: title
        val resolutionText = doc.selectFirst(".torrent__resolution a")?.text()?.trim() ?: ""
        val typeText = doc.selectFirst(".torrent__type a")?.text()?.trim() ?: ""
        val sizeText = doc.selectFirst(".torrent__size-link")?.text()?.trim() ?: ""
        val seeders = doc.selectFirst(".torrent__seeders-link")?.text()?.trim()?.toIntOrNull() ?: 0

        // Tags (genres)
        val tags = doc.select(".meta__chips div:has(h3:contains(الأنواع)) a, .meta__chips div:has(h3:contains(Genres)) a")
            .map { it.text().trim() }

        // Year
        val year = doc.select(".work__tags li:has(.tag__icon--year) span, .meta__tags .tag--year")
            .text()?.trim()?.toIntOrNull()

        // Rating (site uses 0-10 scale)
        val ratingValue = doc.selectFirst(".meta__rating-value")?.text()?.trim()?.toDoubleOrNull()

        // Determine TvType from category tag on detail page
        val categoryLink = doc.selectFirst(".work__media-type a")?.attr("abs:href") ?: ""
        val categoryId = Regex("categoryIds%5B\\d+%5D=(\\d+)").find(categoryLink)?.groupValues?.get(1)
            ?: Regex("categoryIds\\[\\d+]=(\\d+)").find(categoryLink)?.groupValues?.get(1)
            ?: "4"
        val tvType = categoryToTvType(categoryId)

        // Download URL
        val downloadUrl = "$mainUrl/torrents/download/$torrentId"

        // Try to download .torrent and parse files
        val torrentBytes = fetchBytes(downloadUrl)
        val fileListing = if (torrentBytes != null) {
            torrentBytesCache[torrentId] = torrentBytes
            parseTorrentFiles(torrentBytes)
        } else emptyList()

        // Quality tag
        val qualityTag = buildString {
            if (resolutionText.isNotEmpty()) append(resolutionText)
            if (typeText.isNotEmpty()) { if (isNotEmpty()) append(" "); append(typeText) }
        }

        // Build episodes from torrent files
        val dataUrlBase = "$torrentId|$downloadUrl"

        return when (tvType) {
            TvType.Anime, TvType.AnimeMovie, TvType.OVA -> {
                if (fileListing.size > 1) {
                    val episodes = fileListing.mapIndexed { index, file ->
                        newEpisode("$dataUrlBase|$index") {
                            this.name = file.name.substringAfterLast("/")
                            this.episode = index + 1
                            this.season = 1
                            this.description = "${file.sizeInBytes / (1024 * 1024)} MB"
                        }
                    }
                    newAnimeLoadResponse(title, pageUrl, tvType) {
                        this.posterUrl = posterUrl
                        this.backgroundPosterUrl = backdropUrl
                        this.plot = description
                        this.year = year
                        this.tags = tags
                        ratingValue?.let { this.score = Score.from10(it) }
                        addEpisodes(DubStatus.Subbed, episodes)
                        }
                } else {
                    newAnimeLoadResponse(title, pageUrl, tvType) {
                        this.posterUrl = posterUrl
                        this.backgroundPosterUrl = backdropUrl
                        this.plot = description
                        this.year = year
                        this.tags = tags
                        ratingValue?.let { this.score = Score.from10(it) }
                        addEpisodes(DubStatus.Subbed, listOf(
                            newEpisode("$dataUrlBase|-1") {
                                this.name = torrentTitle
                                this.episode = 1
                                this.description = sizeText
                            }
                        ))
                        }
                }
            }
            TvType.TvSeries -> {
                val episodes = if (fileListing.size > 1) {
                    fileListing.mapIndexed { index, file ->
                        newEpisode("$dataUrlBase|$index") {
                            this.name = file.name.substringAfterLast("/")
                            this.episode = index + 1
                            this.season = 1
                            this.description = "${file.sizeInBytes / (1024 * 1024)} MB"
                        }
                    }
                } else {
                    listOf(
                        newEpisode("$dataUrlBase|-1") {
                            this.name = torrentTitle
                            this.episode = 1
                            this.description = sizeText
                        }
                    )
                }
                newTvSeriesLoadResponse(title, pageUrl, tvType, episodes) {
                    this.posterUrl = posterUrl
                    this.backgroundPosterUrl = backdropUrl
                    this.plot = description
                    this.year = year
                    this.tags = tags
                    ratingValue?.let { this.score = Score.from10(it) }
                }
            }
            else -> {
                // Movie
                newMovieLoadResponse(title, pageUrl, tvType, "$dataUrlBase|-1") {
                    this.posterUrl = posterUrl
                    this.backgroundPosterUrl = backdropUrl
                    this.plot = description
                    this.year = year
                    this.tags = tags
                    ratingValue?.let { this.score = Score.from10(it) }
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // LOAD LINKS (VIDEO EXTRACTION)
    // ═══════════════════════════════════════════════════════════════════

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        ensureLogin()
        val parts = data.split("|")
        if (parts.size < 2) return false

        val torrentId = parts[0]
        val downloadUrl = parts[1]
        val fileIndex = parts.getOrNull(2)?.toIntOrNull() ?: -1

        // Get torrent bytes (from cache or download)
        var torrentBytes = torrentBytesCache[torrentId]
        if (torrentBytes == null) {
            torrentBytes = fetchBytes(downloadUrl)
            if (torrentBytes != null) torrentBytesCache[torrentId] = torrentBytes
        }

        if (torrentBytes != null) {
            // Parse torrent and create magnet link
            try {
                val magnet = torrentToMagnet(torrentBytes, fileIndex)
                callback(
                    newExtractorLink(
                        source = name,
                        name = "Torrent",
                        url = magnet,
                        type = ExtractorLinkType.MAGNET
                    ) {
                        this.quality = getQualityFromName(
                            parseResolutionFromTorrent(torrentBytes)
                        )
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error creating magnet", e)
            }

            // Also serve .torrent file via local server
            try {
                val serverUrl = serveTorrentLocally(torrentBytes)
                if (serverUrl != null) {
                    callback(
                        newExtractorLink(
                            source = name,
                            name = "Torrent File",
                            url = if (fileIndex >= 0) "$serverUrl?file_index=$fileIndex" else serverUrl,
                            type = ExtractorLinkType.TORRENT
                        )
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error serving torrent locally", e)
            }
        } else {
            // Fallback: direct download URL
            callback(
                newExtractorLink(
                    source = name,
                    name = "Direct Download",
                    url = downloadUrl,
                    type = ExtractorLinkType.TORRENT
                )
            )
        }
        return true
    }

    // ═══════════════════════════════════════════════════════════════════
    // TORRENT PARSING (BENCODE)
    // ═══════════════════════════════════════════════════════════════════

    private sealed class BencodeValue {
        data class BString(val bytes: ByteArray) : BencodeValue() {
            override fun equals(other: Any?): Boolean =
                other is BString && bytes.contentEquals(other.bytes)
            override fun hashCode(): Int = bytes.contentHashCode()
            fun string(): String = String(bytes, Charsets.UTF_8)
        }
        data class BInt(val value: Long) : BencodeValue()
        data class BList(val items: List<BencodeValue>) : BencodeValue()
        data class BDict(val entries: MutableList<Pair<BencodeValue.BString, BencodeValue>>) :
            BencodeValue() {
            operator fun get(key: String): BencodeValue? =
                entries.firstOrNull { it.first.string() == key }?.second
        }
    }

    private data class TorrentFile(val name: String, val sizeInBytes: Long)

    private class Ref(var value: Int)

    private fun decodeBencode(data: ByteArray, offset: Int = 0): Pair<BencodeValue, Int> {
        val pos = Ref(offset)
        val result = decodeBencodeRecursive(data, pos)
        return Pair(result, pos.value)
    }

    private fun decodeBencodeRecursive(data: ByteArray, pos: Ref): BencodeValue {
        val ch = data[pos.value].toInt().toChar()
        return when {
            ch == 'i' -> {
                pos.value++
                val end = data.indexOfByte('e'.code.toByte(), pos.value)
                val num = String(data, pos.value, end - pos.value).toLong()
                pos.value = end + 1
                BencodeValue.BInt(num)
            }
            ch in '0'..'9' -> {
                val colon = data.indexOfByte(':'.code.toByte(), pos.value)
                val len = String(data, pos.value, colon - pos.value).toInt()
                pos.value = colon + 1
                val str = data.copyOfRange(pos.value, pos.value + len)
                pos.value += len
                BencodeValue.BString(str)
            }
            ch == 'l' -> {
                pos.value++
                val items = mutableListOf<BencodeValue>()
                while (data[pos.value].toInt().toChar() != 'e') {
                    items.add(decodeBencodeRecursive(data, pos))
                }
                pos.value++
                BencodeValue.BList(items)
            }
            ch == 'd' -> {
                pos.value++
                val entries = mutableListOf<Pair<BencodeValue.BString, BencodeValue>>()
                while (data[pos.value].toInt().toChar() != 'e') {
                    val key = decodeBencodeRecursive(data, pos) as BencodeValue.BString
                    val value = decodeBencodeRecursive(data, pos)
                    entries.add(Pair(key, value))
                }
                pos.value++
                BencodeValue.BDict(entries)
            }
            else -> throw IllegalArgumentException("Invalid bencode at position ${pos.value}: '$ch'")
        }
    }

    private fun parseTorrentFiles(torrentBytes: ByteArray): List<TorrentFile> {
        try {
            val (root, _) = decodeBencode(torrentBytes)
            val info = (root as? BencodeValue.BDict)?.get("info") as? BencodeValue.BDict ?: return emptyList()

            // Single file torrent
            if (info["length"] != null) {
                val length = (info["length"] as? BencodeValue.BInt)?.value ?: 0L
                val fName = (info["name"] as? BencodeValue.BString)?.string() ?: "unknown"
                return listOf(TorrentFile(fName, length))
            }

            // Multi-file torrent
            val files = (info["files"] as? BencodeValue.BList)?.items ?: return emptyList()
            return files.mapNotNull { fileDict ->
                val dict = fileDict as? BencodeValue.BDict ?: return@mapNotNull null
                val pathParts = (dict["path"] as? BencodeValue.BList)?.items?.map {
                    (it as? BencodeValue.BString)?.string() ?: ""
                } ?: return@mapNotNull null
                val length = (dict["length"] as? BencodeValue.BInt)?.value ?: 0L
                val fullName = if (pathParts.size > 1) {
                    pathParts.drop(1).joinToString("/")
                } else {
                    pathParts.firstOrNull() ?: "unknown"
                }
                TorrentFile(fullName, length)
            }.filter {
                val n = it.name.lowercase()
                n.endsWith(".mkv") || n.endsWith(".mp4") || n.endsWith(".avi")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing torrent files", e)
            return emptyList()
        }
    }

    private fun parseResolutionFromTorrent(torrentBytes: ByteArray): String {
        val files = parseTorrentFiles(torrentBytes)
        val fileName = files.firstOrNull()?.name?.lowercase() ?: return ""
        return when {
            "2160" in fileName || "4k" in fileName -> "2160p"
            "1080" in fileName -> "1080p"
            "720" in fileName -> "720p"
            "480" in fileName -> "480p"
            else -> ""
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // TORRENT → MAGNET CONVERSION
    // ═══════════════════════════════════════════════════════════════════

    private fun torrentToMagnet(torrentBytes: ByteArray, fileIndex: Int = -1): String {
        val (root, _) = decodeBencode(torrentBytes)
        val rootDict = root as? BencodeValue.BDict
            ?: throw IllegalArgumentException("Not a valid torrent")

        val infoStart = findBencodeKeyPosition(torrentBytes, "info")
        if (infoStart < 0) throw IllegalArgumentException("No info dict found")

        val infoEnd = skipBencodeValue(torrentBytes, infoStart)
        val infoBytes = torrentBytes.copyOfRange(infoStart, infoEnd)
        val sha1 = java.security.MessageDigest.getInstance("SHA-1").digest(infoBytes)

        val info = rootDict["info"] as? BencodeValue.BDict
        val torrentName = (info?.get("name") as? BencodeValue.BString)?.string() ?: ""
        val base32Hash = base32Encode(sha1)

        val magnet = StringBuilder("magnet:?xt=urn:btih:$base32Hash")
        if (torrentName.isNotEmpty()) {
            magnet.append("&dn=").append(URLEncoder.encode(torrentName, "UTF-8"))
        }

        val trackers = mutableListOf<String>()
        (rootDict["announce"] as? BencodeValue.BString)?.string()?.let { trackers.add(it) }
        (rootDict["announce-list"] as? BencodeValue.BList)?.items?.forEach { item ->
            (item as? BencodeValue.BList)?.items?.firstOrNull()?.let {
                trackers.add((it as? BencodeValue.BString)?.string() ?: return@forEach)
            }
        }

        trackers.distinct().take(10).forEach { tracker ->
            magnet.append("&tr=").append(URLEncoder.encode(tracker, "UTF-8"))
        }

        if (fileIndex >= 0) {
            magnet.append("&so=").append(fileIndex)
        }

        return magnet.toString()
    }

    private fun findBencodeKeyPosition(data: ByteArray, key: String): Int {
        val keyBytes = key.toByteArray(Charsets.UTF_8)
        outer@ for (i in data.indices) {
            if (data[i].toInt().toChar() == 'd') {
                var pos = i + 1
                while (pos < data.size && data[pos].toInt().toChar() != 'e') {
                    val colon = data.indexOfByte(':'.code.toByte(), pos)
                    if (colon < 0) break
                    val keyLen = String(data, pos, colon - pos).toIntOrNull() ?: break
                    val keyStart = colon + 1
                    if (keyStart + keyLen > data.size) break
                    val foundKey = String(data, keyStart, keyLen)
                    if (foundKey == key) return keyStart + keyLen
                    pos = skipBencodeValue(data, keyStart + keyLen)
                }
            }
        }
        return -1
    }

    private fun skipBencodeValue(data: ByteArray, offset: Int): Int {
        val pos = Ref(offset)
        val ch = data[pos.value].toInt().toChar()
        when {
            ch == 'i' -> {
                pos.value++
                val end = data.indexOfByte('e'.code.toByte(), pos.value)
                return end + 1
            }
            ch in '0'..'9' -> {
                val colon = data.indexOfByte(':'.code.toByte(), pos.value)
                val len = String(data, pos.value, colon - pos.value).toInt()
                return colon + 1 + len
            }
            ch == 'l' -> {
                pos.value++
                while (data[pos.value].toInt().toChar() != 'e') {
                    val nextEnd = skipBencodeValue(data, pos.value)
                    pos.value = nextEnd
                }
                return pos.value + 1
            }
            ch == 'd' -> {
                pos.value++
                while (data[pos.value].toInt().toChar() != 'e') {
                    val colon = data.indexOfByte(':'.code.toByte(), pos.value)
                    val len = String(data, pos.value, colon - pos.value).toInt()
                    var next = colon + 1 + len
                    next = skipBencodeValue(data, next)
                    pos.value = next
                }
                return pos.value + 1
            }
        }
        return offset
    }

    private fun base32Encode(data: ByteArray): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val sb = StringBuilder()
        var buffer = 0
        var bitsLeft = 0
        for (byte in data) {
            buffer = (buffer shl 8) or (byte.toInt() and 0xFF)
            bitsLeft += 8
            while (bitsLeft >= 5) {
                bitsLeft -= 5
                val index = (buffer ushr bitsLeft) and 0x1F
                sb.append(alphabet[index])
            }
        }
        if (bitsLeft > 0) {
            sb.append(alphabet[(buffer shl (5 - bitsLeft)) and 0x1F])
        }
        return sb.toString()
    }

    // ═══════════════════════════════════════════════════════════════════
    // LOCAL TORRENT SERVER
    // ═══════════════════════════════════════════════════════════════════

    private var localServer: ServerSocket? = null
    private var localServerData: ByteArray? = null

    private fun serveTorrentLocally(torrentBytes: ByteArray): String? {
        try {
            localServer?.close()
            localServer = ServerSocket(0)
            val port = localServer!!.localPort
            localServerData = torrentBytes

            Thread {
                try {
                    localServer?.soTimeout = 90000
                    while (!localServer!!.isClosed) {
                        try {
                            val client: Socket = localServer!!.accept() ?: break
                            Thread {
                                try {
                                    client.soTimeout = 30000
                                    val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                                    reader.readLine() // consume request line

                                    val outputStream = client.getOutputStream()
                                    val data = localServerData ?: return@Thread

                                    val header = "HTTP/1.1 200 OK\r\n" +
                                        "Content-Type: application/x-bittorrent\r\n" +
                                        "Connection: close\r\n" +
                                        "Content-Length: ${data.size}\r\n" +
                                        "\r\n"

                                    outputStream.write(header.toByteArray())
                                    outputStream.write(data)
                                    outputStream.flush()
                                } catch (_: Exception) {} finally {
                                    try { client.close() } catch (_: Exception) {}
                                }
                            }.start()
                        } catch (_: Exception) { break }
                    }
                } catch (_: Exception) {}
            }.start()

            return "http://127.0.0.1:$port/torrent.torrent"
        } catch (e: Exception) {
            Log.e(TAG, "Error starting local server", e)
            return null
        }
    }
}