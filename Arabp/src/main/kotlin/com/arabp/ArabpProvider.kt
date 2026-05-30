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
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.json.JSONArray
import org.json.JSONObject
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
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA, TvType.TvSeries, TvType.Movie)

    companion object {
        private const val TAG = "Arabp_Log"
        private const val LOGIN_USERNAME = "armano"
        private const val LOGIN_PASSWORD = "ARmano01**"
        private val DIGITS = Regex("\\d+")

        // TorrServe Matrix API — default host:port
        // Change this if your TorrServe runs on a different address
        private const val TORRSERVE_HOST = "http://127.0.0.1:8090"

        // How many times to poll TorrServe for file list after upload
        private const val TORRSERVE_POLL_ATTEMPTS = 20
        // Delay between polls (ms)
        private const val TORRSERVE_POLL_DELAY = 2000L

        // How long to wait before stripping trackers (ms)
        // The tracker needs time to connect and find peers first.
        private const val TRACKER_STRIP_DELAY = 10000L
    }

    // Cache of original .torrent bytes keyed by hash,
    // used to re-upload stripped versions after peers connect.
    private val torrentBytesCache = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

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
        // NOTE: Some listing pages ignore the search parameter and return ALL items.
        // We filter results client-side to only keep relevant ones.
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

    /**
     * Check if a title matches the search query.
     * Handles both Arabic and English, and partial word matching.
     */
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

            // Poster is in the rel attribute of a.screenshot (tooltip/hover image)
            val posterUrl = row.selectFirst("a.screenshot")?.attr("rel")
                ?: nameLink.attr("rel")
                ?: ""

            val epData = "$torrentId|$detailHref|${toAbsoluteUrl(downloadHref)}|$magnetHref|${if (isFree) "1" else "0"}|${if (isExternal) "1" else "0"}"
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

            // Poster is in the rel attribute of a.screenshot (tooltip/hover image)
            val posterUrl = row.selectFirst("a.screenshot")?.attr("rel")
                ?: nameLink.attr("rel")
                ?: ""

            val epData = "$torrentId|$detailHref|${toAbsoluteUrl(downloadHref)}|$magnetHref|${if (isFree) "1" else "0"}|${if (isExternal) "1" else "0"}"
            newAnimeSearchResponse(displayName, epData, tvType) {
                this.posterUrl = toAbsoluteUrl(posterUrl)
                this.posterHeaders = imageHeaders
            }
        } catch (e: Exception) {
            Log.e(TAG, "modernTorrentRowToSearchResult Error: ${e.message}")
            null
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
            } else if (parts.isNotEmpty()) {
                // All files share the same path prefix — single show
                // Use the last folder component as the show name (or empty if only root)
                if (parts.size > 1) parts.last() else parts.first()
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
            val sName = if (showLabel.isNotEmpty()) showLabel else "مواسم" // "Seasons"
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
                val isFree = isFreeTorrent(row)

                val tds = row.select("td")
                val size = tds.getOrNull(3)?.text()?.trim() ?: ""
                val seeders = tds.getOrNull(4)?.text()?.trim() ?: ""

                val displayName = buildString {
                    append(epName)
                    if (isFree) append(" ✅مجاني")
                    if (size.isNotEmpty()) append(" | $size")
                    if (seeders.isNotEmpty()) append(" | ▲$seeders")
                }

                // For non-free torrents, skip pre-resolve (it would hit the daily limit).
                // The thank + download will happen in loadLinks() when user clicks play.
                // For free torrents, pre-resolve to detect multi-show structure.
                val streamEntries = if (isFree) {
                    tryResolveTorrent(
                        torrentId, toAbsoluteUrl(detailHref), toAbsoluteUrl(downloadHref), isFree
                    )
                } else {
                    null
                }

                if (streamEntries == null || streamEntries.isEmpty()) {
                    // Pre-resolve failed — fall back to legacy data (one episode = one torrent)
                    val epData = "$torrentId|${toAbsoluteUrl(detailHref)}|${toAbsoluteUrl(downloadHref)}||${if (isFree) "1" else "0"}|0"
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
                    val epData = "ts://${entry.streamUrl}|${entry.fileName}"
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
                } else {
                    // Multiple files — use smart grouping for seasons
                    val (fileEpisodes, fileSeasonNames) = buildEpisodesFromEntries(streamEntries, absPosterUrl)

                    // Build a lookup map from the SeasonData list for easy access
                    val fileSeasonMap = fileSeasonNames.associate { it.season to (it.name ?: "") }

                    // Add one SeasonData per unique remapped season (not per episode!)
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
    // from search results). This is a SINGLE torrent, so we CAN
    // pre-resolve it via TorrServe to detect multi-show structure.
    //
    // Strategy:
    // - Pre-resolve the torrent (download .torrent → upload to TorrServe)
    // - If torrent has MULTIPLE shows (detected via smart grouping):
    //   Each show becomes a SEASON with a readable name. User picks a
    //   season (show) first, then sees only that show's episodes.
    // - If torrent has ONE show:
    //   Each video file becomes one Episode. All in season 1.
    // - If pre-resolve fails:
    //   Fall back to one episode with legacy data; loadLinks() will
    //   try again and show all files with folder-based naming.
    //
    // Episode data formats:
    //   ts://STREAM_URL|FILENAME  — pre-resolved direct stream (one source)
    //   pipe-delimited            — legacy fallback (all files as sources)

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

        // Determine TvType from detail URL
        val pageTvType = if (detailUrl.contains("movies-listing")) TvType.Movie
            else if (detailUrl.contains("tv-listing")) TvType.TvSeries
            else if (detailUrl.contains("anime-listing")) TvType.Anime
            else tvTypeFromTitle(title)

        // === EXTERNAL TORRENTS (magnet links): can't pre-resolve, fall back to movie ===
        if (isExternal && magnetUrl.startsWith("magnet:")) {
            return newMovieLoadResponse(title, data, pageTvType.toMovieType(), data) {
                this.posterUrl = absPosterUrl
                this.posterHeaders = imageHeaders
            }
        }

        // === INTERNAL TORRENTS: try to pre-resolve via TorrServe ===
        // For non-free torrents, skip pre-resolve (would hit daily limit).
        // The thank + download will happen in loadLinks() when user clicks play.
        val streamEntries = if (isFree) {
            tryResolveTorrent(torrentId, detailUrl, downloadUrl, isFree)
        } else {
            null
        }

        if (streamEntries.isNullOrEmpty()) {
            // Failed to pre-resolve — fall back to TV series with one episode.
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
            val epData = "ts://${entry.streamUrl}|${entry.fileName}"
            val tvType = if (title.contains("فيلم", ignoreCase = true) || title.contains("Movie", ignoreCase = true))
                pageTvType.toMovieType() else pageTvType.toSeriesType()
            return newMovieLoadResponse(title, data, tvType, epData) {
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

    // ==================== LOAD LINKS ====================
    //
    // Two data formats handled:
    //
    // 1. Pre-resolved stream (ts://...):
    //    Format: ts://STREAM_URL|FILENAME
    //    The torrent was already uploaded to TorrServe in load().
    //    We create ONE ExtractorLink directly — the user sees exactly
    //    one source, not a flat list of all episodes.
    //
    // 2. Legacy (pipe-delimited):
    //    Format: torrentId|detailUrl|downloadUrl|magnetUrl|isFree|isExternal
    //    The full download/upload cycle runs here.
    //    Used as fallback when pre-resolve failed.
    //    For multi-folder torrents, folder names are added to link names
    //    so the user can identify which show each file belongs to.

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // === PRE-RESOLVED STREAM: direct link from load() ===
        // Each episode has its own ts:// URL, so the user sees ONE source.
        if (data.startsWith("ts://")) {
            val afterPrefix = data.substringAfter("ts://")
            val pipeIndex = afterPrefix.indexOf('|')
            val streamUrl = if (pipeIndex >= 0) afterPrefix.substring(0, pipeIndex) else afterPrefix
            val fileName = if (pipeIndex >= 0) afterPrefix.substring(pipeIndex + 1) else "Video"

            // Return the link FIRST so playback starts immediately,
            // then adjust priorities in the background (don't block playback).
            Log.d(TAG, "loadLinks: pre-resolved stream → $fileName")
            callback(
                newExtractorLink(
                    source = this.name,
                    name = fileName,
                    url = streamUrl,
                    type = ExtractorLinkType.VIDEO
                )
            )

            // Resume only this file, pause all others to save bandwidth.
            // Then strip trackers AFTER the tracker has connected and found peers.
            // This is best-effort — if it fails, playback still works.
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
                    // ===== STOP TRACKER COUNTING =====
                    // Multi-pronged strategy to stop arabp2p from counting:
                    //
                    // A) PRIMARY: Delete torrent + re-add via magnet WITHOUT trackers
                    //    This completely removes the tracker URL from TorrServe.
                    //    No tracker = no announce = no counting.
                    //    The magnet link enables DHT/PEX since the .torrent's
                    //    private flag isn't loaded when adding via magnet.
                    //
                    // B) BACKUP: Send event=stopped directly to the tracker.
                    //    Uses TorrServe's actual peer_id and port (queried from API).
                    //
                    // C) BACKUP: Replace trackers with dummy URL via API.
                    //
                    // D) REAPER: Background thread that re-sends event=stopped
                    //    every 30 seconds for 30 minutes to counter re-announces.
                    Thread.sleep(TRACKER_STRIP_DELAY)

                    val originalBytes = torrentBytesCache[hash]

                    // First, query TorrServe for the torrent's session properties
                    // (peer_id, port, tracker URLs, etc.)
                    val torrentProps = getTorrServeTorrentProps(hash)

                    // === APPROACH B: Send event=stopped directly to the tracker ===
                    // Do this BEFORE deleting, while the session is still active
                    if (originalBytes != null) {
                        try {
                            val infoHash = computeInfoHash(originalBytes)
                            val announceUrl = extractAnnounceUrl(originalBytes)
                            if (infoHash != null && announceUrl != null) {
                                // Use TorrServe's actual peer_id and port if available
                                val peerId = torrentProps?.peerId
                                val port = torrentProps?.listenPort ?: 6881
                                Log.d(TAG, "Tracker removal: sending event=stopped (peerId=$peerId, port=$port)")
                                val stopped = sendTrackerStopped(announceUrl, infoHash, peerId, port)
                                Log.d(TAG, "Tracker removal: event=stopped result=$stopped")
                            } else {
                                Log.w(TAG, "Tracker removal: could not extract info_hash or announce URL")
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Tracker removal: direct stopped event failed: ${e.message}")
                        }
                    }

                    // === APPROACH A: Delete + re-add via magnet without trackers ===
                    // This is the most reliable method — completely removes the tracker
                    try {
                        Log.d(TAG, "Tracker removal: attempting delete + magnet re-add for hash=$hash")
                        val readded = deleteAndReaddWithoutTrackers(hash, activeIndex)
                        Log.d(TAG, "Tracker removal: delete + magnet re-add result=$readded")

                        if (readded) {
                            // Verify the new torrent has no trackers
                            try { Thread.sleep(2000) } catch (_: InterruptedException) {}
                            val newProps = getTorrServeTorrentProps(hash)
                            if (newProps != null) {
                                Log.d(TAG, "Tracker removal: after re-add, trackers=${newProps.trackerUrls.size}, urls=${newProps.trackerUrls}")
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Tracker removal: delete + magnet re-add failed: ${e.message}")
                    }

                    // === APPROACH C: Replace trackers with dummy URL (backup) ===
                    try {
                        val replaced = replaceTrackersWithDummy(hash)
                        Log.d(TAG, "Tracker removal: replaceTrackersWithDummy result=$replaced")
                    } catch (e: Exception) {
                        Log.w(TAG, "Tracker removal: replaceTrackersWithDummy failed: ${e.message}")
                    }

                    // === APPROACH B again: Send event=stopped after all changes ===
                    // In case TorrServe re-announced during our operations
                    if (originalBytes != null) {
                        try {
                            val infoHash = computeInfoHash(originalBytes)
                            val announceUrl = extractAnnounceUrl(originalBytes)
                            if (infoHash != null && announceUrl != null) {
                                val peerId = torrentProps?.peerId
                                val port = torrentProps?.listenPort ?: 6881
                                Thread.sleep(2000)
                                val stopped = sendTrackerStopped(announceUrl, infoHash, peerId, port)
                                Log.d(TAG, "Tracker removal: second event=stopped result=$stopped")
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Tracker removal: second stopped event failed: ${e.message}")
                        }
                        torrentBytesCache.remove(hash)
                    }

                    // === APPROACH D: Background reaper ===
                    // Periodically re-sends event=stopped to counter any re-announces.
                    // Runs for 30 minutes (60 iterations × 30 seconds).
                    if (originalBytes != null) {
                        try {
                            val infoHash = computeInfoHash(originalBytes)
                            val announceUrl = extractAnnounceUrl(originalBytes)
                            if (infoHash != null && announceUrl != null) {
                                val reaperPeerId = torrentProps?.peerId
                                val reaperPort = torrentProps?.listenPort ?: 6881
                                Thread {
                                    for (i in 1..60) {
                                        try {
                                            Thread.sleep(30000)
                                            Log.d(TAG, "Tracker reaper: sending event=stopped (iteration $i/60)")
                                            sendTrackerStopped(announceUrl, infoHash, reaperPeerId, reaperPort)
                                        } catch (e: InterruptedException) {
                                            break
                                        } catch (e: Exception) {
                                            Log.w(TAG, "Tracker reaper error: ${e.message}")
                                        }
                                    }
                                    Log.d(TAG, "Tracker reaper: finished for hash=$hash")
                                }.start()
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Tracker reaper: failed to start: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "TorrServe priority/tracker adjustment failed (non-fatal): ${e.message}")
            }

            return true
        }

        // === LEGACY PATH: full download/upload cycle ===
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

            // Step 1.5: Thank the uploader for non-free torrents to bypass daily download limit
            if (!isFree) {
                val thankDetailUrl = if (detailUrl.isNotBlank()) detailUrl
                    else "$mainUrl/index.php?page=torrent-details&id=$torrentId"
                val thanked = thankUploader(torrentId, thankDetailUrl)
                Log.d(TAG, "loadLinks: thank uploader result = $thanked for torrent $torrentId")
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
                                // Use smart grouping for better naming
                                val groups = computeShowGroups(streamEntries)
                                val distinctShows = groups.map { it.showName }.filter { it.isNotEmpty() }.distinct()
                                val isMultiShow = distinctShows.size > 1

                                for (entry in streamEntries) {
                                    val group = groups.find { it.path == entry.filePath }
                                    val linkName = when {
                                        streamEntries.size == 1 -> "${this.name} (TorrServe)"
                                        isMultiShow && group != null && group.showName.isNotEmpty() -> "[${group.showName}] ${entry.fileName}"
                                        else -> entry.fileName
                                    }
                                    Log.d(TAG, "TorrServe link: ${entry.fileName} → ${entry.streamUrl} (show: ${group?.showName})")
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
                            // Retry: thank the uploader and try downloading again
                            Log.w(TAG, "DAILY DOWNLOAD LIMIT EXCEEDED for torrent id=$torrentId, thanking and retrying...")
                            val thankDetailUrl = if (detailUrl.isNotBlank()) detailUrl
                                else "$mainUrl/index.php?page=torrent-details&id=$torrentId"
                            thankUploader(torrentId, thankDetailUrl)
                            val retryResult = downloadTorrentFile(resolvedDownloadUrl)
                            when (retryResult) {
                                is TorrentDownloadResult.Success -> {
                                    Log.d(TAG, "Retry succeeded! Downloaded .torrent: ${retryResult.bytes.size} bytes")
                                    val retryEntries = uploadToTorrServe(retryResult.bytes, torrentId)
                                    if (retryEntries != null && retryEntries.isNotEmpty()) {
                                        val groups = computeShowGroups(retryEntries)
                                        val distinctShows = groups.map { it.showName }.filter { it.isNotEmpty() }.distinct()
                                        val isMultiShow = distinctShows.size > 1
                                        for (entry in retryEntries) {
                                            val group = groups.find { it.path == entry.filePath }
                                            val linkName = when {
                                                retryEntries.size == 1 -> "${this.name} (TorrServe)"
                                                isMultiShow && group != null && group.showName.isNotEmpty() -> "[${group.showName}] ${entry.fileName}"
                                                else -> entry.fileName
                                            }
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
                    Log.e(TAG, "TorrServe processing error: ${e.message}")
                }
            }

            foundLink
        } catch (e: Exception) {
            Log.e(TAG, "loadLinks Error: ${e.message}")
            false
        }
    }

    // ==================== THANK UPLOADER ====================

    /**
     * Click "اشكر الرافع" (thank the uploader) on the torrent detail page.
     * This must be done before downloading non-free torrents to avoid the
     * daily download limit ("لقد تجاوزت الحد المسموح به من التحميلات بيوم واحد").
     *
     * The arabp2p website uses an AJAX mechanism (sack library) that sends a
     * POST request to thanks.php with form data tid=TORRENT_ID&thanks=1 when
     * the "اشكر الرافع" button (id="ty", class="btn thanks_btn",
     * onclick="thank_you(ID)") is clicked. We replicate this by:
     * 1. Sending a POST request to thanks.php with tid and thanks parameters
     *
     * Returns true if the thank was sent or already done, false on error.
     */
    private fun thankUploader(torrentId: String, detailUrl: String): Boolean {
        if (!ensureLogin()) return false

        return try {
            // Send the thank POST request — same as the sack AJAX call:
            // at.requestFile='thanks.php'; at.setVar('tid',ia); at.setVar('thanks',1);
            // sack library uses POST method by default with form-encoded body
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
     * Includes folder structure info for multi-show torrents.
     */
    data class TorrServeStreamEntry(
        val fileName: String,      // Just the filename (e.g. "Episode01.mkv")
        val filePath: String,      // Full path within torrent (e.g. "ShowName/Episode01.mkv")
        val folderName: String,    // Legacy: first folder component (for backward compat)
        val fileIndex: Int,        // TorrServe file index (1-based)
        val streamUrl: String      // Full stream URL with correct index
    )

    // ==================== BENCODE PARSER (for .torrent tracker stripping) ====================
    //
    // Minimal bencode parser/encoder to strip tracker URLs from .torrent files
    // before uploading to TorrServe. This prevents the private tracker from
    // counting your download/upload.

    private sealed class BencodeValue {
        data class BInt(val value: Long) : BencodeValue()
        data class BString(val bytes: ByteArray) : BencodeValue() {
            override fun equals(other: Any?) = other is BString && bytes.contentEquals(other.bytes)
            override fun hashCode() = bytes.contentHashCode()
        }
        data class BList(val items: List<BencodeValue>) : BencodeValue()
        data class BDict(val entries: MutableList<Pair<BString, BencodeValue>>) : BencodeValue()
    }

    private fun decodeBencode(data: ByteArray, offset: Int): Pair<BencodeValue, Int> {
        val byte = data[offset]
        return when {
            byte == 'i'.code.toByte() -> {
                // Integer: i<number>e
                val end = indexOf(data, 'e'.code.toByte(), offset + 1)
                val num = String(data, offset + 1, end - offset - 1).toLong()
                Pair(BencodeValue.BInt(num), end + 1)
            }
            byte in '0'.code.toByte()..'9'.code.toByte() -> {
                // String: <length>:<bytes>
                val colon = indexOf(data, ':'.code.toByte(), offset)
                val len = String(data, offset, colon - offset).toInt()
                val strStart = colon + 1
                val strBytes = data.sliceArray(strStart until strStart + len)
                Pair(BencodeValue.BString(strBytes), strStart + len)
            }
            byte == 'l'.code.toByte() -> {
                // List: l<items>e
                val items = mutableListOf<BencodeValue>()
                var pos = offset + 1
                while (data[pos] != 'e'.code.toByte()) {
                    val (item, newPos) = decodeBencode(data, pos)
                    items.add(item)
                    pos = newPos
                }
                Pair(BencodeValue.BList(items), pos + 1)
            }
            byte == 'd'.code.toByte() -> {
                // Dictionary: d<key><value>...e
                val entries = mutableListOf<Pair<BencodeValue.BString, BencodeValue>>()
                var pos = offset + 1
                while (data[pos] != 'e'.code.toByte()) {
                    val (key, keyEnd) = decodeBencode(data, pos)
                    val (value, valueEnd) = decodeBencode(data, keyEnd)
                    entries.add(key as BencodeValue.BString to value)
                    pos = valueEnd
                }
                Pair(BencodeValue.BDict(entries), pos + 1)
            }
            else -> throw IllegalArgumentException("Invalid bencode byte at $offset: ${byte.toInt().toChar()}")
        }
    }

    private fun encodeBencode(value: BencodeValue): ByteArray {
        return when (value) {
            is BencodeValue.BInt -> "i${value.value}e".toByteArray()
            is BencodeValue.BString -> {
                val prefix = "${value.bytes.size}:".toByteArray()
                prefix + value.bytes
            }
            is BencodeValue.BList -> {
                val parts = value.items.map { encodeBencode(it) }
                byteArrayOf('l'.code.toByte()) + parts.reduce { acc, bytes -> acc + bytes } + byteArrayOf('e'.code.toByte())
            }
            is BencodeValue.BDict -> {
                val parts = value.entries.flatMap { (k, v) ->
                    listOf(encodeBencode(k), encodeBencode(v))
                }
                val body = if (parts.isEmpty()) ByteArray(0) else parts.reduce { acc, bytes -> acc + bytes }
                byteArrayOf('d'.code.toByte()) + body + byteArrayOf('e'.code.toByte())
            }
        }
    }

    private fun indexOf(data: ByteArray, b: Byte, from: Int): Int {
        for (i in from until data.size) {
            if (data[i] == b) return i
        }
        throw IllegalArgumentException("Byte not found in bencode data")
    }

    /**
     * Strip tracker URLs from a .torrent file.
     * Removes `announce` and `announce-list` keys from the top-level dictionary.
     * The `info` dict is kept UNCHANGED (same info hash → TorrServe recognizes
     * it as the same torrent and updates it in place).
     *
     * IMPORTANT: We do NOT remove the `private` flag from the info dict because
     * that would CHANGE the info hash, making it a completely different torrent.
     * The existing peer connections persist regardless of the private flag.
     *
     * Without tracker URLs, no announce happens → the private tracker
     * cannot count your download/upload. Existing peer connections continue.
     */
    private fun stripTrackersFromTorrent(torrentBytes: ByteArray): ByteArray {
        return try {
            val (root, _) = decodeBencode(torrentBytes, 0)
            val dict = root as? BencodeValue.BDict ?: return torrentBytes

            // Remove announce and announce-list from top-level dict ONLY.
            // Do NOT touch the info dict — that would change the info hash!
            dict.entries.removeAll { (k, _) ->
                k.bytes.contentEquals("announce".toByteArray()) ||
                k.bytes.contentEquals("announce-list".toByteArray())
            }

            val result = encodeBencode(dict)
            Log.d(TAG, "stripTrackersFromTorrent: ${torrentBytes.size} → ${result.size} bytes (trackers removed, info hash preserved)")
            result
        } catch (e: Exception) {
            Log.w(TAG, "stripTrackersFromTorrent failed, using original: ${e.message}")
            torrentBytes
        }
    }

    /**
     * Upload .torrent to TorrServe, then poll for file list.
     */
    private fun uploadToTorrServe(torrentBytes: ByteArray, torrentId: String): List<TorrServeStreamEntry>? {
        return try {
            // Upload the ORIGINAL .torrent with trackers/passkey so TorrServe
            // can connect to the tracker and find peers. Trackers will be removed
            // AFTER the download starts (once peers are connected via DHT/PEX).

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

                // Cache original torrent bytes so we can strip and re-upload later
                torrentBytesCache[hash] = torrentBytes

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

            // Post-upload: pause files.
            // Tracker counting is stopped in loadLinks() after the video starts playing.
            // We send event=stopped directly to the tracker + replace trackers with dummy URL.
            // Best-effort: don't let these fail the upload.
            try {
                if (videoEntries.size > 1) {
                    val allIndices = videoEntries.map { it.fileIndex }
                    pauseAllFiles(hash, allIndices)
                }
            } catch (e: Exception) {
                Log.w(TAG, "TorrServe post-upload setup failed (non-fatal): ${e.message}")
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
                // Sort: by full path for consistent ordering
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
    // and storage, we pause all files except the one being watched. When the user
    // clicks another episode, that file is resumed and the rest are paused.
    //
    // TorrServe Matrix API: POST /torrents with action=set-file-priority
    // Priority values: 0 = Don't download (paused), 1 = Normal, 2 = High, 3 = Maximum

    /**
     * Set download priority for a single file in TorrServe.
     * @param hash Torrent hash
     * @param fileIndex 1-based file index
     * @param priority 0=paused, 1=normal, 2=high, 3=max
     */
    private fun setTorrServeFilePriority(hash: String, fileIndex: Int, priority: Int): Boolean {
        return try {
            val jsonBody = JSONObject().apply {
                put("action", "set-file-priority")
                put("hash", hash)
                put("file_id", fileIndex)
                put("priority", priority)
            }

            val request = Request.Builder()
                .url("$TORRSERVE_HOST/torrents")
                .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                .header("Content-Type", "application/json")
                .build()

            torrServeClient.newCall(request).execute().use { response ->
                val success = response.isSuccessful
                if (success) {
                    Log.d(TAG, "TorrServe: set file $fileIndex priority=$priority for $hash")
                } else {
                    Log.w(TAG, "TorrServe: set priority failed, HTTP ${response.code}")
                }
                success
            }
        } catch (e: Exception) {
            Log.e(TAG, "TorrServe setFilePriority error: ${e.message}")
            false
        }
    }

    /**
     * Pause all files in a torrent except the one being watched.
     * The active file gets normal priority (1), all others get paused (0).
     *
     * @param hash Torrent hash
     * @param activeFileIndex 1-based index of the file to resume
     * @param totalFileIndices All file indices in the torrent
     */
    private fun pauseAllExceptOne(hash: String, activeFileIndex: Int, totalFileIndices: List<Int>) {
        var successCount = 0
        for (idx in totalFileIndices) {
            val priority = if (idx == activeFileIndex) 1 else 0
            if (setTorrServeFilePriority(hash, idx, priority)) {
                successCount++
            }
        }
        Log.d(TAG, "pauseAllExceptOne: set priorities for $successCount/${totalFileIndices.size} files (active=$activeFileIndex)")
    }

    /**
     * Pause all files in a torrent after upload (before user selects any episode).
     */
    private fun pauseAllFiles(hash: String, totalFileIndices: List<Int>) {
        for (idx in totalFileIndices) {
            setTorrServeFilePriority(hash, idx, 0)
        }
        Log.d(TAG, "pauseAllFiles: paused ${totalFileIndices.size} files for $hash")
    }

    /**
     * Extract hash and file index from a TorrServe stream URL.
     * URL format: http://127.0.0.1:8090/stream/fname?link=HASH&index=INDEX&play
     * @return Pair(hash, index) or null if parsing fails
     */
    private fun parseTorrServeUrl(streamUrl: String): Pair<String, Int>? {
        return try {
            val hash = streamUrl.substringAfter("link=").substringBefore("&")
            val indexStr = streamUrl.substringAfter("index=").substringBefore("&")
            val index = indexStr.toIntOrNull() ?: return null
            if (hash.isNotBlank()) Pair(hash, index) else null
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

    // ==================== TORRSERVE TRACKER REMOVAL ====================
    //
    // Strategy to stop arabp2p from counting downloads:
    //
    // APPROACH A (primary): Delete torrent from TorrServe, then re-add via
    // magnet link WITHOUT tracker URLs. This is the most reliable method
    // because it completely removes the tracker from the equation.
    // - We delete with save=true (keep downloaded data on disk)
    // - We re-add via magnet:?xt=urn:btih:HASH (no &tr= parameter)
    // - TorrServe checks existing data and can stream from it immediately
    // - No tracker URL = no announce = no counting
    // - The torrent uses DHT/PEX to find peers (magnet links enable DHT
    //   even for private torrents, since the .torrent's private flag isn't loaded)
    //
    // APPROACH B (backup): Send event=stopped directly to the tracker.
    // This uses TorrServe's actual peer_id and port (queried from the API)
    // so the tracker recognizes it as the same session and closes it.
    //
    // APPROACH C (backup): Replace trackers with a dummy URL via API.
    //
    // Background reaper: Periodically re-sends event=stopped for 30 minutes
    // to counter any re-announces from TorrServe.

    /**
     * Data class for TorrServe torrent properties.
     * Populated by querying the TorrServe API.
     */
    data class TorrServeTorrentProps(
        val peerId: String?,
        val listenPort: Int,
        val trackerUrls: List<String>,
        val totalDownloaded: Long,
        val totalUploaded: Long,
        val stat: Int,
        val rawJson: String
    )

    /**
     * Query TorrServe for detailed torrent properties.
     * Returns peer_id, listening port, tracker URLs, and other session data.
     * We need this to send a proper event=stopped to the tracker.
     */
    private fun getTorrServeTorrentProps(hash: String): TorrServeTorrentProps? {
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
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null

                // Log the FULL response for debugging — this is critical
                // to understand what TorrServe actually returns
                Log.d(TAG, "TorrServe torrent props FULL response: ${body.take(2000)}")

                val json = JSONObject(body)
                val stat = json.optInt("stat", -1)

                // Try to extract peer_id — it might be under different keys
                var peerId: String? = null
                // Try common keys
                for (key in listOf("peer_id", "peer-id", "peerId", "connection_id")) {
                    val v = json.optString(key, null)
                    if (v != null && v.isNotEmpty()) {
                        peerId = v
                        Log.d(TAG, "Found peer_id under key '$key': $peerId")
                        break
                    }
                }

                // Try to get it from the torrent object if it's nested
                if (peerId == null) {
                    val dataObj = json.optJSONObject("data")
                    if (dataObj != null) {
                        for (key in listOf("peer_id", "peer-id", "peerId")) {
                            val v = dataObj.optString(key, null)
                            if (v != null && v.isNotEmpty()) {
                                peerId = v
                                Log.d(TAG, "Found peer_id under data.$key: $peerId")
                                break
                            }
                        }
                    }
                }

                // Extract listening port
                var listenPort = json.optInt("listen_port",
                    json.optInt("port",
                        json.optInt("lp", 0)))

                // Try from data object
                if (listenPort == 0) {
                    val dataObj = json.optJSONObject("data")
                    if (dataObj != null) {
                        listenPort = dataObj.optInt("listen_port",
                            dataObj.optInt("port", 0))
                    }
                }
                if (listenPort == 0) listenPort = 6881  // default BitTorrent port

                // Extract tracker URLs
                val trackerUrls = mutableListOf<String>()
                val trackersArr = json.optJSONArray("trackers")
                if (trackersArr != null) {
                    for (i in 0 until trackersArr.length()) {
                        when (val item = trackersArr.get(i)) {
                            is String -> trackerUrls.add(item)
                            is JSONArray -> {
                                for (j in 0 until item.length()) {
                                    val url = item.optString(j)
                                    if (url.isNotEmpty()) trackerUrls.add(url)
                                }
                            }
                        }
                    }
                }

                // Extract download/upload stats
                val totalDownloaded = json.optLong("downloaded",
                    json.optLong("total_download",
                        json.optLong("total_downloaded", 0L)))
                val totalUploaded = json.optLong("uploaded",
                    json.optLong("total_upload",
                        json.optLong("total_uploaded", 0L)))

                val props = TorrServeTorrentProps(
                    peerId = peerId,
                    listenPort = listenPort,
                    trackerUrls = trackerUrls,
                    totalDownloaded = totalDownloaded,
                    totalUploaded = totalUploaded,
                    stat = stat,
                    rawJson = body.take(500)
                )
                Log.d(TAG, "TorrServe torrent props: peerId=$peerId, port=$listenPort, trackers=${trackerUrls.size}, dl=$totalDownloaded, ul=$totalUploaded, stat=$stat")
                props
            }
        } catch (e: Exception) {
            Log.e(TAG, "getTorrServeTorrentProps error: ${e.message}")
            null
        }
    }

    /**
     * Compute the info_hash (SHA-1 of bencoded info dict) from a .torrent file.
     * This is the same hash that identifies the torrent on the tracker.
     */
    private fun computeInfoHash(torrentBytes: ByteArray): String? {
        return try {
            val (root, _) = decodeBencode(torrentBytes, 0)
            val dict = root as? BencodeValue.BDict ?: return null

            // Find the "info" entry
            val infoEntry = dict.entries.find { (k, _) ->
                k.bytes.contentEquals("info".toByteArray())
            } ?: return null

            // Re-encode just the info dict
            val infoEncoded = encodeBencode(infoEntry.second)

            // SHA-1 hash
            val sha1 = MessageDigest.getInstance("SHA-1").digest(infoEncoded)

            // Convert to hex string
            val hexHash = sha1.joinToString("") { "%02x".format(it) }
            Log.d(TAG, "computeInfoHash: $hexHash")
            hexHash
        } catch (e: Exception) {
            Log.e(TAG, "computeInfoHash error: ${e.message}")
            null
        }
    }

    /**
     * Extract the tracker announce URL from a .torrent file.
     * Returns the first announce URL (which includes the passkey for private trackers).
     */
    private fun extractAnnounceUrl(torrentBytes: ByteArray): String? {
        return try {
            val (root, _) = decodeBencode(torrentBytes, 0)
            val dict = root as? BencodeValue.BDict ?: return null

            // Try "announce" key first
            val announceEntry = dict.entries.find { (k, _) ->
                k.bytes.contentEquals("announce".toByteArray())
            }
            if (announceEntry != null) {
                val url = String((announceEntry.second as BencodeValue.BString).bytes)
                Log.d(TAG, "extractAnnounceUrl: $url")
                return url
            }

            // Try first URL in "announce-list"
            val announceListEntry = dict.entries.find { (k, _) ->
                k.bytes.contentEquals("announce-list".toByteArray())
            }
            if (announceListEntry != null) {
                val list = announceListEntry.second as? BencodeValue.BList ?: return null
                if (list.items.isNotEmpty()) {
                    val tier = list.items[0] as? BencodeValue.BList ?: return null
                    if (tier.items.isNotEmpty()) {
                        val url = String((tier.items[0] as BencodeValue.BString).bytes)
                        Log.d(TAG, "extractAnnounceUrl (from announce-list): $url")
                        return url
                    }
                }
            }

            null
        } catch (e: Exception) {
            Log.e(TAG, "extractAnnounceUrl error: ${e.message}")
            null
        }
    }

    /**
     * Send `event=stopped` directly to the tracker's HTTP announce URL.
     * This bypasses TorrServe entirely and tells the tracker we're stopping.
     *
     * For private trackers, the announce URL includes a passkey that identifies
     * the user. When the tracker receives event=stopped, it closes the user's
     * session and stops counting download/upload for that torrent.
     *
     * @param announceUrl The tracker announce URL (with passkey)
     * @param infoHashHex The info_hash as hex string
     * @param peerId The peer_id to use (TorrServe's actual peer_id if available)
     * @param port The port to report (TorrServe's actual listening port if available)
     * @return true if the tracker accepted the stopped event
     */
    private fun sendTrackerStopped(
        announceUrl: String,
        infoHashHex: String,
        peerId: String? = null,
        port: Int = 6881
    ): Boolean {
        return try {
            // Convert hex info_hash to URL-encoded binary format
            val infoHashBytes = ByteArray(20)
            for (i in 0 until 20) {
                infoHashBytes[i] = infoHashHex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }

            // URL-encode each byte of the info_hash
            val encodedInfoHash = infoHashBytes.joinToString("") { byte ->
                "%${String.format("%02x", byte.toInt() and 0xFF)}"
            }

            // Use TorrServe's actual peer_id if available, otherwise generate one
            val effectivePeerId = peerId ?: run {
                // Generate a libtorrent-style peer_id: -LT1000- + 12 random hex
                "-LT1000-" + (1..12).map {
                    "0123456789abcdef".random()
                }.joinToString("")
            }

            // Build the announce URL with event=stopped
            val separator = if ("?" in announceUrl) "&" else "?"
            val stoppedUrl = buildString {
                append(announceUrl)
                append(separator)
                append("info_hash=").append(encodedInfoHash)
                append("&peer_id=").append(URLEncoder.encode(effectivePeerId, "UTF-8"))
                append("&port=").append(port)
                append("&uploaded=0")
                append("&downloaded=0")
                append("&left=0")
                append("&compact=1")
                append("&event=stopped")
                append("&numwant=0")
            }

            Log.d(TAG, "sendTrackerStopped: sending event=stopped to $announceUrl (peer_id=$effectivePeerId, port=$port)")

            val request = Request.Builder()
                .url(stoppedUrl)
                .header("User-Agent", "libtorrent/1.2.19.0")
                .build()

            torrServeClient.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                Log.d(TAG, "sendTrackerStopped: HTTP ${response.code}, body=${body.take(300)}")

                if (response.isSuccessful) {
                    // Check if the response is a valid bencoded tracker response
                    if (body.startsWith("d") || body.contains("interval")) {
                        Log.d(TAG, "sendTrackerStopped: SUCCESS — tracker accepted event=stopped")
                        true
                    } else {
                        // Even non-bencoded 200 might mean success
                        Log.w(TAG, "sendTrackerStopped: tracker returned unexpected response: ${body.take(100)}")
                        true
                    }
                } else {
                    Log.w(TAG, "sendTrackerStopped: FAILED — HTTP ${response.code}")
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendTrackerStopped error: ${e.message}")
            false
        }
    }

    /**
     * Replace the trackers in a TorrServe torrent with a dummy URL.
     */
    private fun replaceTrackersWithDummy(hash: String): Boolean {
        val dummyTracker = "http://127.0.0.1:1/announce"
        var success = false

        // Method 1: set-trackers with dummy URL as string
        try {
            val jsonBody = JSONObject().apply {
                put("action", "set-trackers")
                put("hash", hash)
                put("trackers", dummyTracker)
            }

            val request = Request.Builder()
                .url("$TORRSERVE_HOST/torrents")
                .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                .header("Content-Type", "application/json")
                .build()

            torrServeClient.newCall(request).execute().use { response ->
                Log.d(TAG, "replaceTrackersWithDummy method 1: HTTP ${response.code}")
                if (response.isSuccessful) success = true
            }
        } catch (e: Exception) {
            Log.w(TAG, "replaceTrackersWithDummy method 1 error: ${e.message}")
        }

        // Method 2: set-trackers with dummy URL as array
        if (!success) {
            try {
                val jsonBody = JSONObject().apply {
                    put("action", "set-trackers")
                    put("hash", hash)
                    put("trackers", JSONArray().put(dummyTracker))
                }

                val request = Request.Builder()
                    .url("$TORRSERVE_HOST/torrents")
                    .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                    .header("Content-Type", "application/json")
                    .build()

                torrServeClient.newCall(request).execute().use { response ->
                    Log.d(TAG, "replaceTrackersWithDummy method 2: HTTP ${response.code}")
                    if (response.isSuccessful) success = true
                }
            } catch (e: Exception) {
                Log.w(TAG, "replaceTrackersWithDummy method 2 error: ${e.message}")
            }
        }

        // Method 3: set-trackers with nested array (announce-list format)
        if (!success) {
            try {
                val jsonBody = JSONObject().apply {
                    put("action", "set-trackers")
                    put("hash", hash)
                    put("trackers", JSONArray().put(JSONArray().put(dummyTracker)))
                }

                val request = Request.Builder()
                    .url("$TORRSERVE_HOST/torrents")
                    .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                    .header("Content-Type", "application/json")
                    .build()

                torrServeClient.newCall(request).execute().use { response ->
                    Log.d(TAG, "replaceTrackersWithDummy method 3: HTTP ${response.code}")
                    if (response.isSuccessful) success = true
                }
            } catch (e: Exception) {
                Log.w(TAG, "replaceTrackersWithDummy method 3 error: ${e.message}")
            }
        }

        if (!success) {
            Log.w(TAG, "replaceTrackersWithDummy: all methods failed for $hash")
        }
        return success
    }

    /**
     * Delete a torrent from TorrServe and re-add it via magnet link
     * WITHOUT tracker URLs. This completely removes the tracker from
     * the equation — no tracker URL means no announce.
     *
     * The magnet link is: magnet:?xt=urn:btih:HASH (no &tr=)
     * TorrServe will check existing data on disk and can stream from it.
     * Without trackers, the torrent uses DHT/PEX to find peers.
     * Since we add via magnet (not .torrent), the private flag is NOT
     * enforced, so DHT/PEX are enabled.
     *
     * @param hash The torrent info hash
     * @param activeFileIndex The file index to resume after re-adding
     * @return true if the operation succeeded
     */
    private fun deleteAndReaddWithoutTrackers(hash: String, activeFileIndex: Int): Boolean {
        // Step 1: Delete torrent from TorrServe (keep downloaded data on disk)
        try {
            val deleteBody = JSONObject().apply {
                put("action", "delete")
                put("hash", hash)
                put("save", true)  // keep downloaded data
            }
            val deleteRequest = okhttp3.Request.Builder()
                .url("$TORRSERVE_HOST/torrents")
                .post(deleteBody.toString().toRequestBody("application/json".toMediaType()))
                .header("Content-Type", "application/json")
                .build()
            torrServeClient.newCall(deleteRequest).execute().use { delResp ->
                Log.d(TAG, "TorrServe: delete torrent $hash → HTTP ${delResp.code}")
                if (!delResp.isSuccessful) {
                    Log.w(TAG, "TorrServe: delete failed, aborting re-add")
                    return false
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "TorrServe: delete torrent failed: ${e.message}")
            return false
        }

        // Step 2: Re-add via magnet link WITHOUT trackers
        // magnet:?xt=urn:btih:HASH — no &tr= parameter
        val magnetUri = "magnet:?xt=urn:btih:$hash"
        Log.d(TAG, "TorrServe: re-adding via magnet (no trackers): $magnetUri")

        try {
            val addBody = JSONObject().apply {
                put("action", "add")
                put("link", magnetUri)
                put("save", true)  // save to disk
            }
            val addRequest = okhttp3.Request.Builder()
                .url("$TORRSERVE_HOST/torrents")
                .post(addBody.toString().toRequestBody("application/json".toMediaType()))
                .header("Content-Type", "application/json")
                .build()
            torrServeClient.newCall(addRequest).execute().use { addResp ->
                Log.d(TAG, "TorrServe: add magnet → HTTP ${addResp.code}")
                if (!addResp.isSuccessful) {
                    Log.w(TAG, "TorrServe: add magnet failed, trying upload endpoint")
                    // Try the upload endpoint as a fallback
                    return tryAddMagnetViaUpload(magnetUri)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "TorrServe: add magnet failed: ${e.message}")
            return tryAddMagnetViaUpload(magnetUri)
        }

        // Step 3: Wait a moment for TorrServe to process the magnet
        try { Thread.sleep(3000) } catch (_: InterruptedException) {}

        // Step 4: Resume the active file, pause all others
        try {
            val allIndices = getTorrServeFileIndices(hash)
            if (allIndices.isNotEmpty()) {
                for (idx in allIndices) {
                    setTorrServeFilePriority(hash, idx, if (idx == activeFileIndex) 1 else 0)
                }
                Log.d(TAG, "TorrServe: re-applied file priorities after magnet re-add")
            }
        } catch (e: Exception) {
            Log.w(TAG, "TorrServe: re-apply priorities failed: ${e.message}")
        }

        return true
    }

    /**
     * Try adding a magnet link via the torrent/upload endpoint.
     * Some TorrServe versions accept magnet links through this endpoint.
     */
    private fun tryAddMagnetViaUpload(magnetUri: String): Boolean {
        return try {
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", magnetUri)
                .addFormDataPart("save", "1")
                .build()

            val request = okhttp3.Request.Builder()
                .url("$TORRSERVE_HOST/torrent/upload")
                .post(requestBody)
                .build()

            torrServeClient.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                Log.d(TAG, "tryAddMagnetViaUpload: HTTP ${response.code}, body=${body.take(300)}")
                response.isSuccessful
            }
        } catch (e: Exception) {
            Log.w(TAG, "tryAddMagnetViaUpload error: ${e.message}")
            false
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

    // ==================== UTILITY FUNCTIONS ====================

    private fun normalizePath(path: String): String {
        return path.replace("\\", "/").trim('/')
    }

    /**
     * Extract the first folder name from a path.
     * NOTE: This is used for the TorrServeStreamEntry.folderName field
     * for backward compatibility, but the ACTUAL multi-show detection
     * uses computeShowGroups() which handles nested folders correctly.
     */
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
