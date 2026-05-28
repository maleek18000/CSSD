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
import java.util.concurrent.TimeUnit

class Arabp : MainAPI() {
    override var mainUrl = "https://www.arabp2p.net"
    override var name = "Arabp"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(
        TvType.Anime, TvType.AnimeMovie, TvType.OVA,
        TvType.TvSeries, TvType.Movie
    )

    companion object {
        private const val TAG = "Arabp_Log"
        private const val LOGIN_USERNAME = "armano"
        private const val LOGIN_PASSWORD = "ARmano01**"
        private val DIGITS = Regex("\\d+")

        // TorrServe Matrix API — default host:port
        // Change this if your TorrServe runs on a different address
        private const val TORRSERVE_HOST = "http://127.0.0.1:8090"

        // How many times to poll TorrServe for file list after upload
        private const val TORRSERVE_POLL_ATTEMPTS = 6
        // Delay between polls (ms)
        private const val TORRSERVE_POLL_DELAY = 1000L
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

    // ==================== TV TYPE HELPERS ====================

    /**
     * Determine the appropriate TvType from a listing page URL.
     * - anime-listing → Anime
     * - tv-listing → TvSeries
     * - movies-listing → Movie
     */
    private fun tvTypeFromPage(pageUrl: String): TvType {
        return when {
            pageUrl.contains("movies-listing") -> TvType.Movie
            pageUrl.contains("tv-listing") -> TvType.TvSeries
            else -> TvType.Anime
        }
    }

    /**
     * Determine TvType from a title string.
     * Checks for Arabic/English movie keywords.
     */
    private fun tvTypeFromTitle(title: String): TvType {
        return when {
            title.contains("فيلم", ignoreCase = true) || title.contains("Movie", ignoreCase = true) -> TvType.Movie
            else -> TvType.TvSeries
        }
    }

    /**
     * Get the search response constructor for a given TvType.
     * CloudStream requires specific search response types for each TvType.
     */
    private fun buildSearchResponse(
        title: String,
        url: String,
        tvType: TvType,
        posterUrl: String = "",
        posterHeaders: Map<String, String> = imageHeaders
    ): SearchResponse {
        return when (tvType) {
            TvType.Movie, TvType.AnimeMovie -> newMovieSearchResponse(title, url, tvType) {
                this.posterUrl = posterUrl
                this.posterHeaders = posterHeaders
            }
            TvType.TvSeries -> newTvSeriesSearchResponse(title, url, tvType) {
                this.posterUrl = posterUrl
                this.posterHeaders = posterHeaders
            }
            else -> newAnimeSearchResponse(title, url, tvType) {
                this.posterUrl = posterUrl
                this.posterHeaders = posterHeaders
            }
        }
    }

    // ==================== AUTH-AWARE DOCUMENT FETCHING ====================

    /**
     * Whether a page URL requires authentication to view its content.
     * Anime listing is public; tv-listing and movies-listing require login.
     */
    private fun requiresAuth(url: String): Boolean {
        return url.contains("tv-listing") || url.contains("movies-listing")
    }

    /**
     * Fetch an HTML document using authenticated OkHttp + Jsoup.
     * Used for pages that require login (tv-listing, movies-listing).
     */
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

    /**
     * Fetch a document with auth if needed, otherwise use CloudStream's app.get().
     */
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
        val tvType = tvTypeFromPage(request.data)

        // Determine how many pages to fetch for each section.
        // tv-listing has 2 pages, movies-listing has 3 pages, anime has many (use CloudStream pagination).
        val maxPages = when {
            request.data.contains("tv-listing") -> 2
            request.data.contains("movies-listing") -> 3
            else -> 0 // anime: use CloudStream's built-in pagination (0 = let it paginate naturally)
        }

        val allItems = mutableListOf<SearchResponse>()

        try {
            if (maxPages > 0) {
                // Fetch ALL pages at once for tv-listing and movies-listing
                // so the entire catalog appears on the home page.
                for (pageNum in 1..maxPages) {
                    val url = if (pageNum > 1) "${request.data}&pages=$pageNum" else request.data
                    val doc = fetchDoc(url) ?: continue
                    val items = doc.select("div.listing_div1").mapNotNull { toSearchResult(it, tvType) }
                    allItems.addAll(items)
                    Log.d(TAG, "MainPage ${request.name} page $pageNum: found ${items.size} items")
                }
                // Return all items at once, no more pages needed
                val homeSets = mutableListOf<HomePageList>()
                if (allItems.isNotEmpty()) {
                    homeSets.add(HomePageList(request.name, allItems))
                }
                return newHomePageResponse(homeSets, false)
            } else {
                // Anime listing: use CloudStream's normal pagination
                val url = if (page > 1) "${request.data}&pages=$page" else request.data
                val doc = fetchDoc(url) ?: return newHomePageResponse(mutableListOf())
                val items = doc.select("div.listing_div1").mapNotNull { toSearchResult(it, tvType) }
                val homeSets = mutableListOf<HomePageList>()
                if (items.isNotEmpty()) {
                    homeSets.add(HomePageList(request.name, items))
                }
                return newHomePageResponse(homeSets, items.isNotEmpty())
            }
        } catch (e: Exception) {
            Log.e(TAG, "MainPage Error: ${e.message}")
        }
        return newHomePageResponse(mutableListOf())
    }

    private fun toSearchResult(element: Element, fallbackTvType: TvType = TvType.Anime): SearchResponse? {
        return try {
            // Find the listing link - could be anime-listing, tv-listing, or movies-listing
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

            buildSearchResponse(title, href, tvType, toAbsoluteUrl(posterUrl), imageHeaders)
        } catch (e: Exception) {
            Log.e(TAG, "toSearchResult Error: ${e.message}")
            null
        }
    }

    // ==================== SEARCH ====================

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val results = mutableListOf<SearchResponse>()

        // Source 1: LISTINGS (anime=public, tv/movies=require auth)
        val listingPages = listOf(
            Triple("$mainUrl/index.php?page=anime-listing&search=$encoded", "anime", TvType.Anime),
            Triple("$mainUrl/index.php?page=tv-listing&search=$encoded", "tv", TvType.TvSeries),
            Triple("$mainUrl/index.php?page=movies-listing&search=$encoded", "movies", TvType.Movie)
        )

        for ((listingUrl, label, tvType) in listingPages) {
            try {
                val doc = fetchDoc(listingUrl)
                val listingResults = doc?.select("div.listing_div1")?.mapNotNull { toSearchResult(it, tvType) } ?: emptyList()
                Log.d(TAG, "$label listing search: found ${listingResults.size} results")
                results.addAll(listingResults)
            } catch (e: Exception) {
                Log.e(TAG, "$label listing search error: ${e.message}")
            }
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
                        name.contains("فيلم", ignoreCase = true) -> TvType.Movie
                categoryName.contains("مسلسل", ignoreCase = true) || categoryName.contains("Series", ignoreCase = true) -> TvType.TvSeries
                categoryName.contains("انمي", ignoreCase = true) || categoryName.contains("Anime", ignoreCase = true) -> TvType.Anime
                else -> tvTypeFromTitle(name)
            }

            val displayName = buildString {
                append(name)
                if (isFree) append(" ✅مجاني")
                if (size.isNotEmpty()) append(" | $size")
                if (seeders.isNotEmpty()) append(" | ▲$seeders")
            }

            val epData = "$torrentId|$detailHref|${toAbsoluteUrl(downloadHref)}|$magnetHref|${if (isFree) "1" else "0"}|${if (isExternal) "1" else "0"}"
            buildSearchResponse(displayName, epData, tvType)
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
            val tvType = tvTypeFromTitle(name)

            val displayName = buildString {
                append(name)
                if (isFree) append(" ✅مجاني")
            }

            val epData = "$torrentId|$detailHref|${toAbsoluteUrl(downloadHref)}|$magnetHref|${if (isFree) "1" else "0"}|${if (isExternal) "1" else "0"}"
            buildSearchResponse(displayName, epData, tvType)
        } catch (e: Exception) {
            Log.e(TAG, "modernTorrentRowToSearchResult Error: ${e.message}")
            null
        }
    }

    // ==================== NATURAL SORT FOR EPISODE ORDERING ====================

    /**
     * Natural sort key: pads digit sequences so that "E01" < "E02" < "E10"
     * (not lexicographic "E01" < "E10" < "E02").
     * Ensures Episode 1 appears at the top (ascending order).
     */
    private fun naturalSortKey(str: String): String {
        return str.replace(Regex("\\d+")) { match ->
            match.value.padStart(6, '0')
        }.lowercase()
    }

    // ==================== BUILD EPISODES FROM STREAM ENTRIES ====================

    /**
     * Build episodes and season data from a list of TorrServe stream entries.
     *
     * For multi-show torrents (multiple folders), each folder becomes a season
     * with the folder name as the season name. Episodes are naturally sorted
     * within each season so Episode 1 appears first.
     *
     * Returns Pair<episodes, seasonNames> where seasonNames is a List<SeasonData>.
     */
    private fun buildEpisodesFromEntries(
        entries: List<TorrServeStreamEntry>
    ): Pair<List<Episode>, List<SeasonData>> {
        val sortedEntries = entries.sortedBy { naturalSortKey(it.fileName) }
        val folderGroups = sortedEntries.groupBy { it.folderName }
        val distinctFolders = folderGroups.keys.filter { it.isNotEmpty() }.sortedBy { naturalSortKey(it) }

        val episodes = mutableListOf<Episode>()
        val seasonNamesList = mutableListOf<SeasonData>()
        val addedSeasons = mutableSetOf<Int>()

        if (distinctFolders.size > 1) {
            // MULTI-SHOW: each folder = one season
            for ((folderIndex, folder) in distinctFolders.withIndex()) {
                val seasonNum = folderIndex + 1
                val folderEntries = folderGroups[folder] ?: continue

                // Add season name (only once per season)
                if (!addedSeasons.contains(seasonNum)) {
                    seasonNamesList.add(SeasonData(seasonNum, folder))
                    addedSeasons.add(seasonNum)
                }

                // Create one episode per file in this folder
                for ((epIndex, entry) in folderEntries.withIndex()) {
                    val epData = "ts://${entry.streamUrl}|${entry.fileName}"
                    episodes.add(
                        newEpisode(epData) {
                            name = entry.fileName
                            season = seasonNum
                            episode = epIndex + 1
                        }
                    )
                }
            }

            // Files without a folder → "Other" season
            val otherEntries = folderGroups[""]
            if (otherEntries != null && otherEntries.isNotEmpty()) {
                val otherSeasonNum = distinctFolders.size + 1
                if (!addedSeasons.contains(otherSeasonNum)) {
                    seasonNamesList.add(SeasonData(otherSeasonNum, "أخرى"))
                    addedSeasons.add(otherSeasonNum)
                }
                for ((epIndex, entry) in otherEntries.withIndex()) {
                    val epData = "ts://${entry.streamUrl}|${entry.fileName}"
                    episodes.add(
                        newEpisode(epData) {
                            name = entry.fileName
                            season = otherSeasonNum
                            episode = epIndex + 1
                        }
                    )
                }
            }
        } else if (distinctFolders.size == 1) {
            // SINGLE folder: one season named after the folder
            val folder = distinctFolders.first()
            val folderEntries = folderGroups[folder] ?: emptyList()
            seasonNamesList.add(SeasonData(1, folder))

            for ((epIndex, entry) in folderEntries.withIndex()) {
                val epData = "ts://${entry.streamUrl}|${entry.fileName}"
                episodes.add(
                    newEpisode(epData) {
                        name = entry.fileName
                        season = 1
                        episode = epIndex + 1
                    }
                )
            }

            // Also handle root-level files if any
            val rootEntries = folderGroups[""]
            if (rootEntries != null && rootEntries.isNotEmpty()) {
                for ((epIndex, entry) in rootEntries.withIndex()) {
                    val epData = "ts://${entry.streamUrl}|${entry.fileName}"
                    episodes.add(
                        newEpisode(epData) {
                            name = entry.fileName
                            season = 1
                            episode = folderEntries.size + epIndex + 1
                        }
                    )
                }
            }
        } else {
            // NO folders: all files in root, single season
            seasonNamesList.add(SeasonData(1, "الموسم 1"))
            for ((epIndex, entry) in sortedEntries.withIndex()) {
                val epData = "ts://${entry.streamUrl}|${entry.fileName}"
                episodes.add(
                    newEpisode(epData) {
                        name = entry.fileName
                        season = 1
                        episode = epIndex + 1
                    }
                )
            }
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

            // Determine TvType from the URL
            val pageTvType = tvTypeFromPage(fullUrl)

            val rows = doc.select("table#listing_table tr")
            val allEpisodes = mutableListOf<Episode>()
            val allSeasonNames = mutableListOf<SeasonData>()
            val addedSeasons = mutableSetOf<Int>()

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

                // Try to pre-resolve via TorrServe to detect multi-show torrents
                val streamEntries = tryResolveTorrent(
                    torrentId, toAbsoluteUrl(detailHref), toAbsoluteUrl(downloadHref), isFree
                )

                if (streamEntries == null || streamEntries.isEmpty()) {
                    // Pre-resolve failed — fall back to legacy data
                    val epData = "$torrentId|${toAbsoluteUrl(detailHref)}|${toAbsoluteUrl(downloadHref)}||${if (isFree) "1" else "0"}|0"
                    allEpisodes.add(
                        newEpisode(epData) {
                            name = displayName
                        }
                    )
                    continue
                }

                if (streamEntries.size == 1) {
                    // Single file → one episode
                    val entry = streamEntries.first()
                    val epData = "ts://${entry.streamUrl}|${entry.fileName}"
                    allEpisodes.add(
                        newEpisode(epData) {
                            name = displayName
                        }
                    )
                } else {
                    // Multiple files → use buildEpisodesFromEntries with seasons
                    val (eps, seasonNames) = buildEpisodesFromEntries(streamEntries)

                    // Offset season numbers so they don't collide with previous torrents
                    val seasonOffset = allSeasonNames.size
                    for (ep in eps) {
                        val origSeason = ep.season ?: 1
                        val newSeason = seasonOffset + origSeason
                        allEpisodes.add(
                            newEpisode(ep.data ?: "") {
                                name = ep.name
                                season = newSeason
                                episode = ep.episode
                            }
                        )
                    }
                    // Add season names with offset
                    for (sd in seasonNames) {
                        val newSeasonNum = seasonOffset + sd.season
                        if (!addedSeasons.contains(newSeasonNum)) {
                            allSeasonNames.add(SeasonData(newSeasonNum, sd.name))
                            addedSeasons.add(newSeasonNum)
                        }
                    }
                }
            }

            if (allEpisodes.isEmpty()) {
                newMovieLoadResponse(title, fullUrl, pageTvType.toMovieType(), "0|$fullUrl|||0|0") {
                    this.posterUrl = absPosterUrl
                    this.posterHeaders = imageHeaders
                }
            } else {
                val response = newTvSeriesLoadResponse(title, fullUrl, pageTvType.toSeriesType(), allEpisodes) {
                    this.posterUrl = absPosterUrl
                    this.posterHeaders = imageHeaders
                    seasonNames = allSeasonNames.ifEmpty { null }
                }
                Log.d(TAG, "load: returning TvSeriesLoadResponse with ${allEpisodes.size} episodes, seasonNames=${allSeasonNames.map { "S${it.season}=${it.name}" }}")
                response
            }
        } catch (e: Exception) {
            Log.e(TAG, "Load Error: ${e.message}")
            null
        }
    }

    // ==================== LOAD FROM TORRENT DATA ====================
    //
    // PRE-RESOLVE STRATEGY:
    // Instead of waiting for loadLinks() to download/upload the torrent,
    // we do it right here in load() so we can create proper episode entries.
    //
    // For MULTI-SHOW torrents (multiple folders), each show/folder
    // becomes its own season in CloudStream's UI, with episodes nested
    // under each season.
    //
    // Episode data format:
    //   ts://STREAM_URL|FILENAME
    //   (loadLinks detects the ts:// prefix and creates ExtractorLink)

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

        // Determine TvType from title and detail URL
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
        val streamEntries = tryResolveTorrent(torrentId, detailUrl, downloadUrl, isFree)

        if (streamEntries.isNullOrEmpty()) {
            // Failed to pre-resolve — fall back to movie format.
            // loadLinks() will try again when the user clicks play.
            Log.w(TAG, "Pre-resolve failed for torrent $torrentId, falling back to movie format")
            return newMovieLoadResponse(title, data, pageTvType.toMovieType(), data) {
                this.posterUrl = absPosterUrl
                this.posterHeaders = imageHeaders
            }
        }

        // Sort entries naturally (Episode 1 first)
        val sortedEntries = streamEntries.sortedBy { naturalSortKey(it.fileName) }

        // Single video file → Movie
        if (sortedEntries.size == 1) {
            val entry = sortedEntries.first()
            val epData = "ts://${entry.streamUrl}|${entry.fileName}"
            val tvType = if (title.contains("فيلم", ignoreCase = true) || title.contains("Movie", ignoreCase = true))
                pageTvType.toMovieType() else pageTvType.toSeriesType()
            return newMovieLoadResponse(title, data, tvType, epData) {
                this.posterUrl = absPosterUrl
                this.posterHeaders = imageHeaders
            }
        }

        // Multiple video files → TV Series with seasons (using buildEpisodesFromEntries)
        val (episodes, seasonData) = buildEpisodesFromEntries(sortedEntries)

        Log.d(TAG, "loadFromTorrentData: ${episodes.size} episodes, ${seasonData.size} seasons")
        for (sd in seasonData) {
            Log.d(TAG, "  Season ${sd.season}: ${sd.name}")
        }
        for (ep in episodes) {
            Log.d(TAG, "  S${ep.season}E${ep.episode}: ${ep.name}")
        }

        val response = newTvSeriesLoadResponse(title, data, pageTvType.toSeriesType(), episodes) {
            this.posterUrl = absPosterUrl
            this.posterHeaders = imageHeaders
            seasonNames = seasonData.ifEmpty { null }
        }
        return response
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
                Log.e(TAG, "tryResolveTorrent: daily download limit exceeded")
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
    // Data formats handled:
    //
    // 1. Pre-resolved stream (ts://...):
    //    Format: ts://STREAM_URL|FILENAME
    //    The torrent was already uploaded to TorrServe in load().
    //    We create one ExtractorLink directly.
    //    This is now used for ALL pre-resolved episodes (including multi-show).
    //
    // 2. Legacy folder (tsfolder://...) — DEPRECATED, kept for cached data:
    //    Format: tsfolder://FOLDER_NAME|FILE_COUNT|ts://URL1|FILE1;;ts://URL2|FILE2;;...
    //
    // 3. Legacy (pipe-delimited):
    //    Format: torrentId|detailUrl|downloadUrl|magnetUrl|isFree|isExternal
    //    The full download/upload cycle runs here.
    //    Used as fallback when pre-resolve failed.

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // === PRE-RESOLVED FOLDER: show with multiple episodes ===
        // Handle both clean "tsfolder://..." and fixUrl-corrupted versions
        val folderData = if (data.startsWith("tsfolder://")) {
            data
        } else if (data.contains("tsfolder://")) {
            data.substring(data.indexOf("tsfolder://"))
        } else {
            null
        }

        if (folderData != null) {
            val afterPrefix = folderData.substringAfter("tsfolder://")
            // Format: FOLDER_NAME|FILE_COUNT|ts://URL1|FILE1;;ts://URL2|FILE2;;...
            val firstPipe = afterPrefix.indexOf('|')
            if (firstPipe < 0) return false
            val folderName = afterPrefix.substring(0, firstPipe)

            val afterFolder = afterPrefix.substring(firstPipe + 1)
            val secondPipe = afterFolder.indexOf('|')
            if (secondPipe < 0) return false
            val fileCountStr = afterFolder.substring(0, secondPipe)
            val filesPart = afterFolder.substring(secondPipe + 1)

            // Parse each ts:// entry separated by ;;
            val fileEntries = filesPart.split(";;")
            Log.d(TAG, "loadLinks: folder '$folderName' with $fileCountStr files, parsed ${fileEntries.size} entries")

            for (entry in fileEntries) {
                if (!entry.startsWith("ts://")) continue
                val afterTs = entry.substringAfter("ts://")
                val pipeIdx = afterTs.indexOf('|')
                val streamUrl = if (pipeIdx >= 0) afterTs.substring(0, pipeIdx) else afterTs
                val fileName = if (pipeIdx >= 0) afterTs.substring(pipeIdx + 1) else "Video"

                callback(
                    newExtractorLink(
                        source = this.name,
                        name = fileName,
                        url = streamUrl,
                        type = ExtractorLinkType.VIDEO
                    )
                )
            }
            return fileEntries.isNotEmpty()
        }

        // === PRE-RESOLVED STREAM: single direct link from load() ===
        // Handle both clean "ts://..." and fixUrl-corrupted "https://www.arabp2p.net/ts://..."
        val tsData = if (data.startsWith("ts://")) {
            data
        } else if (data.contains("ts://")) {
            // fixUrl may have prepended mainUrl, extract the ts:// part
            data.substring(data.indexOf("ts://"))
        } else {
            null
        }

        if (tsData != null) {
            val afterPrefix = tsData.substringAfter("ts://")
            val pipeIndex = afterPrefix.indexOf('|')
            val streamUrl = if (pipeIndex >= 0) afterPrefix.substring(0, pipeIndex) else afterPrefix
            val fileName = if (pipeIndex >= 0) afterPrefix.substring(pipeIndex + 1) else "Video"

            Log.d(TAG, "loadLinks: pre-resolved stream → $fileName")
            callback(
                newExtractorLink(
                    source = this.name,
                    name = fileName,
                    url = streamUrl,
                    type = ExtractorLinkType.VIDEO
                )
            )
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

            // Step 2: Download .torrent → upload to TorrServe → get ALL video files
            if (resolvedDownloadUrl.isNotBlank() && resolvedDownloadUrl.contains("&f=")) {
                try {
                    val result = downloadTorrentFile(resolvedDownloadUrl)
                    when (result) {
                        is TorrentDownloadResult.Success -> {
                            Log.d(TAG, "Downloaded .torrent: ${result.bytes.size} bytes, uploading to TorrServe...")
                            val streamEntries = uploadToTorrServe(result.bytes, torrentId)
                            if (streamEntries != null && streamEntries.isNotEmpty()) {
                                // Sort entries naturally (Episode 1 first)
                                val sortedEntries = streamEntries.sortedBy { naturalSortKey(it.fileName) }
                                for ((index, entry) in sortedEntries.withIndex()) {
                                    val linkName = if (sortedEntries.size == 1) {
                                        "${this.name} (TorrServe)"
                                    } else {
                                        entry.fileName
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
                                    name = "\u274C تجاوزت الحد اليومي للتحميل",
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
     * Includes folder structure info for multi-show torrents.
     */
    data class TorrServeStreamEntry(
        val fileName: String,      // Just the filename (e.g. "Episode01.mkv")
        val filePath: String,      // Full path within torrent (e.g. "ShowName/Episode01.mkv")
        val folderName: String,    // Parent folder / show name (e.g. "ShowName"), empty if in root
        val fileIndex: Int,        // TorrServe file index (1-based)
        val streamUrl: String      // Full stream URL with correct index
    )

    /**
     * Upload .torrent to TorrServe, then poll for file list.
     *
     * Flow:
     * 1. POST /torrent/upload with .torrent file → get hash
     * 2. Try to parse file_stats from upload response
     * 3. If empty, poll POST /torrents {"action":"get","hash":...} with retries
     * 4. Filter to video files, group by folder, return stream entries
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

            // Filter to only video files and create stream entries with folder info
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
                // Sort naturally by filename (Episode 1 before Episode 10)
                videoEntries.sortedBy { naturalSortKey(it.fileName) }
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
     * The torrent may need time to resolve its metadata (stat=1 → stat=3).
     *
     * Returns list of (path, id) pairs, or empty list if polling fails.
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
                    .build()

                torrServeClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "TorrServe poll attempt $attempt: HTTP ${response.code}")
                        return@use
                    }

                    val responseBody = response.body?.string() ?: return@use
                    Log.d(TAG, "TorrServe poll attempt $attempt: ${responseBody.take(300)}")

                    val fileStats = parseFileStatsFromResponse(responseBody)
                    if (fileStats.isNotEmpty()) {
                        Log.d(TAG, "TorrServe poll SUCCESS on attempt $attempt: ${fileStats.size} files")
                        return fileStats
                    }

                    // Check torrent status
                    try {
                        val json = JSONObject(responseBody)
                        val stat = json.optInt("stat", -1)
                        val statString = json.optString("stat_string", "")
                        Log.d(TAG, "TorrServe torrent stat=$stat ($statString)")

                        // stat=4 means closed/errored — no point polling further
                        if (stat == 4) {
                            Log.e(TAG, "TorrServe torrent closed/errored, stopping polls")
                            return emptyList()
                        }
                    } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                Log.w(TAG, "TorrServe poll attempt $attempt error: ${e.message}")
            }
        }

        Log.w(TAG, "TorrServe polling exhausted after $TORRSERVE_POLL_ATTEMPTS attempts")
        return emptyList()
    }

    // ==================== JSON PARSING (using JSONObject for reliability) ====================

    /**
     * Parse hash from TorrServe JSON response using JSONObject.
     */
    private fun parseHashFromResponse(json: String): String? {
        return try {
            val obj = JSONObject(json)
            val hashValue = obj.optString("hash", "")
            if (hashValue.isNotEmpty()) hashValue.lowercase() else null
        } catch (e: Exception) {
            // Fallback to regex if JSONObject fails
            Log.w(TAG, "JSONObject hash parsing failed, trying regex: ${e.message}")
            val hashPattern = """"hash"\s*:\s*"([a-fA-F0-9]{40})"""".toRegex()
            hashPattern.find(json)?.groupValues?.getOrNull(1)?.lowercase()
        }
    }

    /**
     * Parse file_stats from TorrServe JSON response using JSONObject/JSONArray.
     * Returns list of (path, id) pairs.
     */
    private fun parseFileStatsFromResponse(json: String): List<Pair<String, Int>> {
        val results = mutableListOf<Pair<String, Int>>()

        try {
            val jsonObj = JSONObject(json)
            parseFileStatsFromObject(jsonObj, results)
        } catch (_: Exception) {
            try {
                val jsonArr = JSONArray(json)
                for (i in 0 until jsonArr.length()) {
                    parseFileStatsFromObject(jsonArr.getJSONObject(i), results)
                }
            } catch (e: Exception) {
                Log.w(TAG, "parseFileStatsFromResponse: both object and array parsing failed: ${e.message}")
            }
        }

        return results
    }

    private fun parseFileStatsFromObject(obj: JSONObject, results: MutableList<Pair<String, Int>>) {
        val fileStats = obj.optJSONArray("file_stats") ?: return

        for (i in 0 until fileStats.length()) {
            try {
                val fileObj = fileStats.getJSONObject(i)
                val id = fileObj.optInt("id", -1)
                val path = fileObj.optString("path", "")
                if (id > 0 && path.isNotEmpty()) {
                    results.add(path to id)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error parsing file_stats entry $i: ${e.message}")
            }
        }
    }

    // ==================== PATH HELPERS ====================

    private fun normalizePath(path: String): String {
        return path.replace("\\", "/")
    }

    private fun extractFolderName(normalizedPath: String): String {
        val slashIndex = normalizedPath.indexOf('/')
        return if (slashIndex > 0) normalizedPath.substring(0, slashIndex) else ""
    }

    private fun isVideoFile(fileName: String): Boolean {
        val videoExtensions = listOf(
            ".mkv", ".mp4", ".avi", ".wmv", ".flv", ".mov", ".webm",
            ".m4v", ".ts", ".mpg", ".mpeg", ".ogv", ".rmvb", ".rm",
            ".vob", ".m2ts", ".mts", ".3gp", ".divx"
        )
        val lower = fileName.lowercase()
        return videoExtensions.any { lower.endsWith(it) }
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

    /**
     * Convert TvType to its series equivalent for TV series responses.
     * Anime → Anime, TvSeries → TvSeries, Movie → TvSeries
     */
    private fun TvType.toSeriesType(): TvType {
        return when (this) {
            TvType.Anime, TvType.AnimeMovie, TvType.OVA -> TvType.Anime
            TvType.Movie -> TvType.TvSeries
            else -> this
        }
    }

    /**
     * Convert TvType to its movie equivalent for movie responses.
     * Anime → AnimeMovie, TvSeries → Movie, Movie → Movie
     */
    private fun TvType.toMovieType(): TvType {
        return when (this) {
            TvType.Anime, TvType.AnimeMovie, TvType.OVA -> TvType.AnimeMovie
            TvType.TvSeries -> TvType.Movie
            else -> this
        }
    }
}
