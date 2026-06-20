package com.arabp2

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.SubtitleFile
import okhttp3.FormBody
import okhttp3.Headers.Companion.toHeaders
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.ServerSocket
import java.net.Socket
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Arabp2 — fast CloudStream plugin for arabp2p.net
 *
 * Key speedups over Arabp (v1):
 *
 *  Fix A — load() no longer downloads .torrent files. Episode lists are read
 *          directly from the torrent-detail HTML table via extractVideoFileNamesFromDoc().
 *          The .torrent binary is only fetched in loadLinks(), and only for the one
 *          episode the user actually clicks. Old: 5 torrents × 3s = 15-25s. New: 5
 *          detail pages × 0.3s = 1.5-3s.
 *
 *  Fix B — single OkHttpClient, no rate limiter, no semaphore, no prefetch.
 *          The old v1 had a 1500ms / 1-concurrency authClient + a 5-permit browse
 *          semaphore + 3-tier cache + background prefetch that competed with the
 *          foreground fetches. None of it was needed — arabp2p.net tolerates normal
 *          browser traffic. Home page now loads in ~1s instead of 3-5s.
 *
 *  Fix C — each quality variant becomes its own season (e.g. "1080p WEB-DL",
 *          "720p HDTV"). Cleaner UX than v1's merged-and-duplicated episode list.
 *
 *  Fix D — LoadResponse cached for 1 hour per show URL. Back-button navigations
 *          are now instant.
 *
 *  File size: ~1100 lines (vs v1's 2655). Easier to read and maintain.
 */
class Arabp2 : MainAPI() {
    override var mainUrl = "https://www.arabp2p.net"
    override var name = "Arabp2"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(
        TvType.Anime, TvType.AnimeMovie, TvType.OVA, TvType.TvSeries, TvType.Movie
    )

    companion object {
        private const val TAG = "Arabp2_Log"
        private const val LOGIN_USERNAME = "armano"
        private const val LOGIN_PASSWORD = "ARmano01**"
        private val DIGITS = Regex("\\d+")
        private val PASSKEY_REGEX = Regex("/([0-9a-f]{32})/announce")

        private const val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        private const val MAX_TORRENT_CACHE = 10

        // 1-hour TTL for the per-show LoadResponse cache (Fix D).
        private const val SHOW_CACHE_TTL_MS = 3_600_000L
        private const val MAX_SHOW_CACHE = 16

        private val USER_AGENTS = listOf(
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36",
            "Mozilla/5.0 (Linux; Android 14; SM-S928B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36",
            "Mozilla/5.0 (Linux; Android 12; SM-S908B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Mobile Safari/537.36",
            "Mozilla/5.0 (Linux; Android 14; SM-A546B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36",
            "Mozilla/5.0 (Linux; Android 13; SM-A145F) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36"
        )
    }

    // ─── HTTP client (single, no rate limiter — Fix B) ───
    private class SimpleCookieJar : okhttp3.CookieJar {
        private val store = ConcurrentHashMap<String, MutableList<okhttp3.Cookie>>()
        override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<okhttp3.Cookie>) {
            store.getOrPut(url.host) { mutableListOf() }.addAll(cookies)
        }
        override fun loadForRequest(url: okhttp3.HttpUrl): List<okhttp3.Cookie> =
            store[url.host] ?: emptyList()
    }

    private val cookieJar = SimpleCookieJar()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .cookieJar(cookieJar)
            .build()
    }

    private val prefetchScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ─── Caches ───
    private val torrentCacheLock = Any()
    private val torrentCache = object : LinkedHashMap<String, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>): Boolean =
            size > MAX_TORRENT_CACHE
    }
    private fun getCachedTorrent(id: String): ByteArray? = synchronized(torrentCacheLock) { torrentCache[id] }
    private fun cacheTorrent(id: String, bytes: ByteArray) = synchronized(torrentCacheLock) { torrentCache[id] = bytes }

    private val resolvedUrlCacheLock = Any()
    private val resolvedUrlCache = HashMap<String, String>()
    private fun getCachedResolvedUrl(id: String): String? = synchronized(resolvedUrlCacheLock) { resolvedUrlCache[id] }
    private fun cacheResolvedUrl(id: String, url: String) = synchronized(resolvedUrlCacheLock) { resolvedUrlCache[id] = url }

    // Per-show LoadResponse cache (Fix D).
    private val showCacheLock = Any()
    private val showCache = object : LinkedHashMap<String, Pair<Long, LoadResponse>>(MAX_SHOW_CACHE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, LoadResponse>>): Boolean {
            if (size > MAX_SHOW_CACHE) return true
            return System.currentTimeMillis() - eldest.value.first > SHOW_CACHE_TTL_MS
        }
    }

    private val imageHeaders = mapOf(
        "User-Agent" to USER_AGENTS[0],
        "Referer" to "$mainUrl/"
    )

    // ─── Login state ───
    @Volatile private var isLoggedIn = false
    private val loginLock = Any()
    @Volatile private var cachedPasskey: String? = null

    /**
     * Eager background login. CloudStream instantiates the plugin when the user
     * opens the home page; by the time getMainPage() is called, login is usually
     * already done. loginLock dedups so only ONE login HTTP request happens
     * even if 11 carousels fire at once.
     */
    init {
        try { prefetchScope.launch { ensureLogin() } } catch (_: Exception) {}
    }

    // ═══════════════════════════════════════════════════════════════════
    // AUTHENTICATION
    // ═══════════════════════════════════════════════════════════════════

    private fun getSessionCookies(): String {
        val uri = okhttp3.HttpUrl.Builder().scheme("https").host("www.arabp2p.net").build()
        return cookieJar.loadForRequest(uri).joinToString("; ") { "${it.name}=${it.value}" }
    }

    private fun isSessionAlive(): Boolean {
        if (!isLoggedIn) return false
        val cookies = getSessionCookies()
        if (!cookies.contains("uid_") || !cookies.contains("pass_")) {
            isLoggedIn = false
            return false
        }
        return true
    }

    private fun ensureLogin(): Boolean {
        if (isSessionAlive()) return true
        synchronized(loginLock) {
            if (isSessionAlive()) return true
            return try {
                // GET login page first to pick up session cookies.
                val initReq = Request.Builder()
                    .url("$mainUrl/index.php?page=login")
                    .header("User-Agent", USER_AGENTS[0])
                    .build()
                client.newCall(initReq).execute().use { /* discard body */ }

                // POST credentials.
                val formBody = FormBody.Builder()
                    .add("uid", LOGIN_USERNAME)
                    .add("pwd", LOGIN_PASSWORD)
                    .build()
                val loginReq = Request.Builder()
                    .url("$mainUrl/index.php?page=login&returnto=index.php")
                    .post(formBody)
                    .header("User-Agent", USER_AGENTS[0])
                    .header("Referer", "$mainUrl/index.php?page=login")
                    .build()
                client.newCall(loginReq).execute().use { response ->
                    val body = response.body?.string() ?: ""
                    val cookiesAfter = getSessionCookies()
                    val success = response.code == 302 ||
                            cookiesAfter.contains("uid_") ||
                            body.contains("logout.php") ||
                            body.contains("page=logout") ||
                            body.contains(LOGIN_USERNAME)
                    if (success) {
                        isLoggedIn = true
                        Log.d(TAG, "Login OK. Cookies: $cookiesAfter")
                    } else {
                        Log.e(TAG, "Login FAILED. code=${response.code}, snippet=${body.take(200)}")
                    }
                    success
                }
            } catch (e: Exception) {
                Log.e(TAG, "Login error: ${e.message}")
                false
            }
        }
    }

    private fun randomUserAgent(): String = USER_AGENTS[kotlin.random.Random.nextInt(USER_AGENTS.size)]

    private fun getAuthHeaders(referer: String? = null): Map<String, String> {
        val headers = mutableMapOf(
            "User-Agent" to randomUserAgent(),
            "Accept-Language" to "ar,en-US;q=0.9,en;q=0.8",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8"
        )
        val cookies = getSessionCookies()
        if (cookies.isNotBlank()) headers["Cookie"] = cookies
        if (referer != null) headers["Referer"] = referer
        return headers
    }

    // ═══════════════════════════════════════════════════════════════════
    // FETCH HELPERS
    // ═══════════════════════════════════════════════════════════════════

    private fun hasListingContent(doc: Document): Boolean =
        doc.select("div.listing_div1, table.lista2t, div.listing_div_id, table#listing_table, div.file-header").isNotEmpty()

    /**
     * Fetch an HTML page with auth cookies. Re-logins once if the site responds
     * with a login form.
     */
    private suspend fun fetchDoc(url: String): Document? = withContext(Dispatchers.IO) {
        if (!ensureLogin()) {
            Log.e(TAG, "fetchDoc: login failed for $url")
            return@withContext null
        }
        try {
            val request = Request.Builder()
                .url(toAbsoluteUrl(url))
                .headers(getAuthHeaders("$mainUrl/").toHeaders())
                .build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return@withContext null
                var doc = Jsoup.parse(body, url)
                if (!hasListingContent(doc) && body.contains("name=\"uid\"")) {
                    Log.w(TAG, "fetchDoc: got login page for $url — re-login")
                    isLoggedIn = false
                    if (ensureLogin()) {
                        val retry = client.newCall(request).execute().use { it.body?.string() } ?: return@withContext null
                        doc = Jsoup.parse(retry, url)
                    } else return@withContext null
                }
                doc
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchDoc error for $url: ${e.message}")
            null
        }
    }

    /**
     * Download raw bytes (used for .torrent files in loadLinks).
     */
    private suspend fun fetchBytes(url: String): ByteArray? = withContext(Dispatchers.IO) {
        if (!ensureLogin()) return@withContext null
        try {
            val request = Request.Builder()
                .url(toAbsoluteUrl(url))
                .headers(getAuthHeaders("$mainUrl/").toHeaders())
                .build()
            client.newCall(request).execute().use { response ->
                response.body?.bytes()
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchBytes error for $url: ${e.message}")
            null
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // HOME PAGE
    // ═══════════════════════════════════════════════════════════════════

    override val mainPage = mainPageOf(
        "$mainUrl/index.php?page=anime-listing" to "قائمة الانمي",
        "$mainUrl/index.php?page=tv-listing" to "مسلسلات عربية",
        "$mainUrl/index.php?page=movies-listing" to "أفلام عربية",
        "$mainUrl/index.php?page=torrents&category=19" to "وثائقيات",
        "$mainUrl/index.php?page=torrents&category=88" to "أفلام مدبلجة للعربية",
        "$mainUrl/index.php?page=torrents&category=90" to "برامج و مسابقات",
        "$mainUrl/index.php?page=torrents&category=93" to "وثائقيات مترجمة",
        "$mainUrl/index.php?page=torrents&active=0&category=113" to "مسلسلات لاتينية",
        "$mainUrl/index.php?page=torrents&active=0&category=57" to "مسلسلات آسيوية",
        "$mainUrl/index.php?page=torrents&active=0&category=71" to "مسلسلات مدبلجة للعربية",
        "$mainUrl/index.php?page=torrents&active=0&category=115" to "مسلسلات تركية"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {
        val url = "${request.data}&pages=$page"
        val doc = fetchDoc(url) ?: return newHomePageResponse(request.name, emptyList(), false)
        val tvType = tvTypeFromPage(request.data)
        val items = parseListingRows(doc, tvType)
        val hasNext = doc.selectFirst("a[href*=pages=${page + 1}]") != null
        return newHomePageResponse(request.name, items, hasNext)
    }

    /**
     * Parse rows from a listing/category page into search results.
     *
     * - For listing pages (anime-listing / tv-listing / movies-listing): each row
     *   links to a SHOW page. The show URL is passed to load() as-is.
     * - For category pages (page=torrents&category=...): each row IS a torrent.
     *   The torrent metadata is encoded as a pipe-delimited string that
     *   loadFromTorrentData() will parse.
     */
    private fun parseListingRows(doc: Document, fallbackTvType: TvType): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()

        // Modern torrent pages: div.file-header
        val modernRows = doc.select("div.file-header")
        if (modernRows.isNotEmpty()) {
            for (row in modernRows) {
                parseTorrentRow(row)?.let { info ->
                    toSearchResult(info, fallbackTvType)?.let { results.add(it) }
                }
            }
            return results
        }

        // Old-style torrent table: table.lista2t tr.lista2
        val tableRows = doc.select("table.lista2t tr.lista2")
        if (tableRows.isNotEmpty()) {
            for (row in tableRows) {
                parseTorrentRow(row)?.let { info ->
                    toSearchResult(info, fallbackTvType)?.let { results.add(it) }
                }
            }
            return results
        }

        // Listing pages: div.listing_div1 (each links to a show page)
        val listingDivs = doc.select("div.listing_div1")
        for (div in listingDivs) {
            toListingSearchResult(div, fallbackTvType)?.let { results.add(it) }
        }
        return results
    }

    // ═══════════════════════════════════════════════════════════════════
    // SEARCH
    // ═══════════════════════════════════════════════════════════════════

    override suspend fun search(query: String): List<SearchResponse> = coroutineScope {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val queryLower = query.lowercase().trim()

        // Listing pages (anime/tv/movies) — apply matchesQuery filter, same as v1.
        // These pages return shows by actor/director/year/etc., so we filter to
        // only shows whose title contains the query.
        val listingUrls = listOf(
            "$mainUrl/index.php?page=anime-listing&search=$encoded" to TvType.Anime,
            "$mainUrl/index.php?page=tv-listing&search=$encoded" to TvType.TvSeries,
            "$mainUrl/index.php?page=movies-listing&search=$encoded" to TvType.Movie
        )

        val listingDeferred: List<Deferred<List<SearchResponse>>> = listingUrls.map { (url, tvType) ->
            async(Dispatchers.IO) {
                val doc = fetchDoc(url) ?: return@async emptyList<SearchResponse>()
                parseListingRows(doc, tvType).filter { matchesQuery(it.name, queryLower) }
            }
        }

        // Torrents page — NO matchesQuery filter, same as v1. The site's torrents
        // search already matches the query against the torrent name; filtering
        // again would drop results where the site matched on description/uploader/etc.
        val torrentsUrl = "$mainUrl/index.php?page=torrents&search=$encoded&category=0&active=0"
        val torrentsDeferred = async(Dispatchers.IO) {
            val doc = fetchDoc(torrentsUrl) ?: return@async emptyList<SearchResponse>()
            parseListingRows(doc, TvType.Anime)
        }

        val results = mutableListOf<SearchResponse>()
        results.addAll(listingDeferred.awaitAll().flatten())
        results.addAll(torrentsDeferred.await())
        results
    }

    private fun matchesQuery(title: String?, queryLower: String): Boolean {
        if (title.isNullOrBlank() || queryLower.isBlank()) return true
        return title.lowercase().contains(queryLower)
    }

    // ═══════════════════════════════════════════════════════════════════
    // LOAD (DETAIL PAGE) — Fix A + Fix C
    // ═══════════════════════════════════════════════════════════════════

    override suspend fun load(url: String): LoadResponse? {
        // Search-result format: pipe-delimited torrent metadata.
        if (url.contains("|")) return loadFromTorrentData(url)

        // Category-page format: detail URL with arabp_data= parameter containing
        // URL-encoded torrent metadata. CloudStream doesn't preserve raw pipe
        // characters in URLs, so toSearchResult() encodes them. This is the same
        // approach v1 uses (modernTorrentRowToSearchResult).
        if (url.contains("arabp_data=")) return loadFromTorrentDetailUrl(url)

        // Fix D — check 1h show cache first.
        synchronized(showCacheLock) {
            showCache[url]?.let { (ts, response) ->
                if (System.currentTimeMillis() - ts < SHOW_CACHE_TTL_MS) {
                    Log.d(TAG, "load: SHOW CACHE HIT for $url")
                    return response
                }
            }
        }

        val doc = fetchDoc(url) ?: return null
        val response = parseShowPage(doc, url)

        if (response != null) {
            synchronized(showCacheLock) {
                showCache[url] = Pair(System.currentTimeMillis(), response)
            }
        }
        return response
    }

    /**
     * Parse a SHOW listing page (e.g. anime-listing?id=123).
     *
     * The page lists N torrent variants for one show (different qualities / episodes
     * packs). For each variant we fetch its torrent-detail page and read the file
     * list from the HTML table — NO .torrent downloads (Fix A).
     *
     * Each torrent variant becomes its own season (Fix C).
     */
    private suspend fun parseShowPage(doc: Document, pageUrl: String): LoadResponse? {
        val h1 = doc.selectFirst("div.listing_div_id h1")
        val title = cleanTitleText(
            h1?.html()?.replace("<br>", " ")?.replace("<br/>", " ")?.replace("<br />", " ")?.trim() ?: ""
        )
        val posterUrl = doc.selectFirst("div.listing_div_id img.listing_poster")?.attr("src")
            ?: doc.selectFirst("img.listing_poster")?.attr("src") ?: ""
        val absPosterUrl = toAbsoluteUrl(posterUrl)

        val rows = doc.select("table#listing_table tr")
        val torrentInfos = rows.mapNotNull { parseTorrentRow(it) }

        Log.d(TAG, "parseShowPage: $title — ${torrentInfos.size} torrent variants")

        val episodes = mutableListOf<Episode>()
        val seasonNames = mutableListOf<SeasonData>()
        var seasonNum = 1

        // Fix A — fetch each torrent-detail page (HTML only), extract file names from
        // the table. NO .torrent binary downloads here.
        for (info in torrentInfos) {
            val detailDoc = fetchDoc(info.detailHref) ?: continue
            val fileNames = extractVideoFileNamesFromDoc(detailDoc)

            // Season name shows the quality so the user can pick (Fix C).
            val seasonName = info.displayName.ifBlank { "$title #$seasonNum" }
            seasonNames.add(SeasonData(season = seasonNum, name = seasonName))

            if (fileNames != null && fileNames.size > 1) {
                fileNames.forEachIndexed { idx, fileName ->
                    val epData = "${info.baseData}|$idx"
                    val epName = fileName.substringBeforeLast(".")
                        .replace(".", " ").replace("_", " ").trim()
                    episodes.add(newEpisode(epData, fix = false, initializer = {
                        name = epName
                        season = seasonNum
                        episode = idx + 1
                        this.posterUrl = absPosterUrl
                    }))
                }
            } else {
                // Single-file torrent or no file list on detail page — show as 1 episode.
                episodes.add(newEpisode(info.baseData, fix = false, initializer = {
                    name = info.displayName
                    season = seasonNum
                    episode = 1
                    this.posterUrl = absPosterUrl
                }))
            }
            seasonNum++
        }

        val pageTvType = tvTypeFromPage(pageUrl)
        return if (episodes.isEmpty()) {
            // No torrent rows on the show page — treat as a single movie.
            val movieData = "0|$pageUrl|||0|0"
            newMovieLoadResponse(title, pageUrl, pageTvType.toMovieType(), movieData) {
                this.posterUrl = absPosterUrl
                this.posterHeaders = imageHeaders
            }
        } else {
            newTvSeriesLoadResponse(title, pageUrl, pageTvType.toSeriesType(), episodes) {
                this.posterUrl = absPosterUrl
                this.posterHeaders = imageHeaders
                this.seasonNames = seasonNames
            }
        }
    }

    /**
     * Handle URLs from torrent category pages (documentaries, Asian, Latin, etc.)
     * that use a real torrent-detail URL with an arabp_data= query parameter
     * containing URL-encoded torrent metadata.
     *
     * Decodes the metadata, reconstructs the full pipe-delimited baseData, and
     * delegates to loadFromTorrentData(). Same approach as v1's
     * loadFromTorrentDetailUrl.
     */
    private suspend fun loadFromTorrentDetailUrl(url: String): LoadResponse? {
        val detailUrl = url.substringBefore("&arabp_data=")
        val encodedData = url.substringAfter("arabp_data=", "")
        val torrentData = try {
            java.net.URLDecoder.decode(encodedData, "UTF-8")
        } catch (e: Exception) {
            Log.e(TAG, "loadFromTorrentDetailUrl: decode failed: ${e.message}")
            return null
        }
        // torrentData format: torrentId|downloadUrl|magnetUrl|isFree|isExternal
        val dataParts = torrentData.split("|")
        val torrentId = dataParts.getOrNull(0) ?: "0"
        val downloadUrl = dataParts.getOrNull(1) ?: ""
        val magnetUrl = dataParts.getOrNull(2) ?: ""
        val isFree = dataParts.getOrNull(3) == "1"
        val isExternal = dataParts.getOrNull(4) == "1"

        // Reconstruct baseData: torrentId|detailUrl|downloadUrl|magnetUrl|isFree|isExternal
        val baseData = "$torrentId|$detailUrl|$downloadUrl|$magnetUrl|${if (isFree) "1" else "0"}|${if (isExternal) "1" else "0"}"
        return loadFromTorrentData(baseData)
    }

    /**
     * Handle the pipe-delimited format produced by search() and parseListingRows()
     * for single torrents (category pages, search results).
     *
     * Same Fix A approach: fetch detail HTML, read file names from the table.
     */
    private suspend fun loadFromTorrentData(data: String): LoadResponse? {
        // Fix D — check cache first.
        synchronized(showCacheLock) {
            showCache[data]?.let { (ts, response) ->
                if (System.currentTimeMillis() - ts < SHOW_CACHE_TTL_MS) return response
            }
        }

        val parts = data.split("|")
        val torrentId = parts.getOrNull(0) ?: "0"
        val detailUrl = parts.getOrNull(1) ?: ""
        val downloadUrl = parts.getOrNull(2) ?: ""
        val magnetUrl = parts.getOrNull(3) ?: ""
        val isFree = parts.getOrNull(4) == "1"
        val isExternal = parts.getOrNull(5) == "1"

        var title = "Torrent #$torrentId"
        var posterUrl = ""

        // Fetch detail page for title, poster, and file list (Fix A).
        val detailDoc = if (detailUrl.isNotBlank()) fetchDoc(detailUrl) else null
        if (detailDoc != null) {
            title = detailDoc.selectFirst("td#Title h1")?.text()?.trim() ?: title
            posterUrl = detailDoc.selectFirst("img.listing_poster")?.attr("src")
                ?: detailDoc.selectFirst("img[src*=poster]")?.attr("src") ?: ""
        }
        val fileNames = detailDoc?.let { extractVideoFileNamesFromDoc(it) }
        val absPosterUrl = toAbsoluteUrl(posterUrl)
        val pageTvType = tvTypeFromTitle(title)

        // External magnet — single movie.
        if (isExternal && magnetUrl.startsWith("magnet:")) {
            val epData = parts.take(6).joinToString("|") + "|0"
            return newMovieLoadResponse(title, data, pageTvType.toMovieType(), epData) {
                this.posterUrl = absPosterUrl
                this.posterHeaders = imageHeaders
            }
        }

        val baseData = parts.take(6).joinToString("|")
        val episodes = mutableListOf<Episode>()

        if (fileNames != null && fileNames.size > 1) {
            fileNames.forEachIndexed { idx, fileName ->
                val epName = fileName.substringBeforeLast(".")
                    .replace(".", " ").replace("_", " ").trim()
                episodes.add(newEpisode("$baseData|$idx", fix = false, initializer = {
                    name = epName
                    season = 1
                    episode = idx + 1
                    this.posterUrl = absPosterUrl
                }))
            }
        } else {
            episodes.add(newEpisode(baseData, fix = false, initializer = {
                name = title
                season = 1
                episode = 1
                this.posterUrl = absPosterUrl
            }))
        }

        val response = newTvSeriesLoadResponse(title, data, pageTvType.toSeriesType(), episodes) {
            this.posterUrl = absPosterUrl
            this.posterHeaders = imageHeaders
            this.seasonNames = listOf(SeasonData(season = 1, name = title))
        }

        synchronized(showCacheLock) {
            showCache[data] = Pair(System.currentTimeMillis(), response)
        }
        return response
    }

    // ═══════════════════════════════════════════════════════════════════
    // LOAD LINKS — only here do we download the .torrent binary
    // ═══════════════════════════════════════════════════════════════════

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (!ensureLogin()) {
            Log.e(TAG, "loadLinks: login failed")
            return false
        }

        val parts = data.split("|")
        val torrentId = parts.getOrNull(0) ?: "0"
        val detailUrl = parts.getOrNull(1) ?: ""
        val downloadUrl = parts.getOrNull(2) ?: ""
        val magnetUrl = parts.getOrNull(3) ?: ""
        val isFree = parts.getOrNull(4) == "1"
        val isExternal = parts.getOrNull(5) == "1"
        val fileIndex = parts.getOrNull(6)?.toIntOrNull()

        Log.d(TAG, "loadLinks: id=$torrentId, free=$isFree, external=$isExternal, fileIndex=$fileIndex")

        // External magnet — pass through directly.
        if (isExternal && magnetUrl.startsWith("magnet:")) {
            callback(newExtractorLink(
                source = name,
                name = "$name (Magnet)",
                url = magnetUrl,
                type = ExtractorLinkType.MAGNET
            ))
            return true
        }

        if (cachedPasskey == null) fetchPasskeyFromWebsite()

        // Thank uploader for non-free torrents to avoid daily download limit.
        if (!isFree && detailUrl.isNotBlank()) {
            thankUploader(torrentId, detailUrl)
        }

        // Cache hit on .torrent bytes?
        var torrentBytes = getCachedTorrent(torrentId)
        if (torrentBytes != null) {
            Log.d(TAG, "loadLinks: torrent cache HIT for #$torrentId (${torrentBytes.size} bytes)")
        } else {
            // Resolve the actual download URL (must contain &f=).
            var resolvedUrl = getCachedResolvedUrl(torrentId) ?: downloadUrl
            if (resolvedUrl.isBlank() || !resolvedUrl.contains("&f=")) {
                val detailDoc = if (detailUrl.isNotBlank()) fetchDoc(detailUrl) else null
                if (detailDoc != null) {
                    val dlLink = detailDoc.selectFirst("a[href*=download.php]")
                        ?: detailDoc.selectFirst("td#Title h1 a[href*=download.php]")
                    if (dlLink != null) {
                        resolvedUrl = toAbsoluteUrl(dlLink.attr("href"))
                        if (resolvedUrl.contains("&f=")) cacheResolvedUrl(torrentId, resolvedUrl)
                    }
                }
            }

            if (resolvedUrl.isNotBlank() && resolvedUrl.contains("&f=")) {
                var result = downloadTorrentFile(resolvedUrl)
                if (result is TorrentDownloadResult.DailyLimitExceeded) {
                    Log.w(TAG, "loadLinks: daily limit hit, thanking and retrying...")
                    thankUploader(torrentId, detailUrl)
                    result = downloadTorrentFile(resolvedUrl)
                }
                if (result is TorrentDownloadResult.Success) {
                    torrentBytes = result.bytes
                    cacheTorrent(torrentId, torrentBytes!!)
                } else {
                    Log.e(TAG, "loadLinks: download failed: $result")
                }
            }
        }

        if (torrentBytes == null) return false

        // Serve .torrent via local HTTP server.
        val localUrl = startLocalTorrentServer(torrentBytes)
        if (localUrl != null) {
            val torrentUrl = if (fileIndex != null) "$localUrl&file_index=$fileIndex" else localUrl
            callback(newExtractorLink(
                source = name,
                name = "$name (Torrent)",
                url = torrentUrl,
                type = ExtractorLinkType.TORRENT
            ))
        }

        // Generate magnet link.
        val magnet = torrentToMagnet(torrentBytes)
        if (magnet != null) {
            callback(newExtractorLink(
                source = name,
                name = "$name (Magnet)",
                url = magnet,
                type = ExtractorLinkType.MAGNET
            ))
        }

        return true
    }

    // ═══════════════════════════════════════════════════════════════════
    // ROW PARSING HELPERS
    // ═══════════════════════════════════════════════════════════════════

    data class TorrentRowInfo(
        val displayName: String,
        val torrentId: String,
        val detailHref: String,
        val downloadHref: String,
        val magnetHref: String,
        val posterUrl: String,
        val isFree: Boolean,
        val isExternal: Boolean,
        val baseData: String
    )

    private fun parseTorrentRow(row: Element): TorrentRowInfo? = try {
        val nameLink = row.selectFirst("a[name=t_url], a[href*=torrent-details]") ?: return null
        val epName = cleanTitleText(nameLink.text())
        val detailHref = toAbsoluteUrl(nameLink.attr("href"))
        val torrentId = DIGITS.find(detailHref.substringAfter("id="))?.value ?: return null
        val downloadHref = row.selectFirst("a[href*=download.php]")?.attr("href") ?: ""
        val magnetHref = row.selectFirst("a[href^=magnet:]")?.attr("href") ?: ""

        // Poster URL — try common locations used by torrent sites.
        // arabp2p.net uses jQuery lightbox-style <a class="screenshot" rel="IMAGE_URL">;
        // some pages use <img data-original="..."> (lazy-load) or a plain poster <img>.
        // Reading from already-fetched HTML = zero extra HTTP requests = no slowdown.
        val posterUrl = row.selectFirst("a.screenshot")?.attr("rel")
            ?: row.selectFirst("a.screenshot")?.attr("data-src")
            ?: row.selectFirst("img[data-original]")?.attr("data-original")
            ?: row.selectFirst("img[src*=poster]")?.attr("src")
            ?: row.selectFirst("img")?.attr("src")
            ?: ""

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
        val baseData = "$torrentId|${toAbsoluteUrl(detailHref)}|${toAbsoluteUrl(downloadHref)}|$magnetHref|${if (isFree) "1" else "0"}|${if (isExternal) "1" else "0"}"
        TorrentRowInfo(displayName, torrentId, detailHref, downloadHref, magnetHref, posterUrl, isFree, isExternal, baseData)
    } catch (e: Exception) {
        Log.e(TAG, "parseTorrentRow: ${e.message}")
        null
    }

    private fun toSearchResult(info: TorrentRowInfo, fallbackTvType: TvType = TvType.Anime): SearchResponse? {
        val absPosterUrl = if (info.posterUrl.isNotBlank()) toAbsoluteUrl(info.posterUrl) else ""
        val tvType = when {
            info.displayName.contains("فيلم", ignoreCase = true) || info.displayName.contains("Movie", ignoreCase = true) -> {
                if (fallbackTvType == TvType.Anime) TvType.AnimeMovie else TvType.Movie
            }
            fallbackTvType == TvType.TvSeries -> TvType.TvSeries
            else -> fallbackTvType
        }
        // Use the detail page URL as the search result URL, with torrent metadata
        // encoded as a URL parameter. CloudStream doesn't preserve raw pipe
        // characters in URLs, so we URL-encode the pipe-delimited data. This is
        // the same approach v1 uses (modernTorrentRowToSearchResult).
        // torrentData format: torrentId|downloadUrl|magnetUrl|isFree|isExternal
        val torrentData = "${info.torrentId}|${toAbsoluteUrl(info.downloadHref)}|${info.magnetHref}|${if (info.isFree) "1" else "0"}|${if (info.isExternal) "1" else "0"}"
        val encodedData = URLEncoder.encode(torrentData, "UTF-8")
        val searchUrl = "${info.detailHref}&arabp_data=$encodedData"
        return when (tvType) {
            TvType.TvSeries -> newTvSeriesSearchResponse(info.displayName, searchUrl, tvType) {
                this.posterUrl = absPosterUrl
                this.posterHeaders = imageHeaders
            }
            TvType.Movie -> newMovieSearchResponse(info.displayName, searchUrl, tvType) {
                this.posterUrl = absPosterUrl
                this.posterHeaders = imageHeaders
            }
            else -> newAnimeSearchResponse(info.displayName, searchUrl, tvType) {
                this.posterUrl = absPosterUrl
                this.posterHeaders = imageHeaders
            }
        }
    }

    private fun toListingSearchResult(element: Element, fallbackTvType: TvType = TvType.Anime): SearchResponse? = try {
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
        Log.e(TAG, "toListingSearchResult: ${e.message}")
        null
    }

    private fun isFreeTorrent(row: Element): Boolean =
        row.selectFirst("span.free") != null ||
                row.selectFirst("span.tor_free_link") != null ||
                row.text().contains("مجاني")

    private fun isExternalTorrent(row: Element): Boolean =
        row.selectFirst("a[href^=magnet:]") != null || row.text().contains("خارجي")

    // ═══════════════════════════════════════════════════════════════════
    // TORRENT FILE LIST PARSING (from HTML detail page — Fix A core)
    // ═══════════════════════════════════════════════════════════════════

    private val VIDEO_EXTENSIONS = setOf("mkv", "mp4", "avi", "wmv", "flv", "mov", "webm", "ts", "m2ts")

    private fun isVideoFile(path: String): Boolean {
        val ext = path.substringAfterLast(".", "").lowercase()
        return ext in VIDEO_EXTENSIONS
    }

    /**
     * Read the file-list table on a torrent-details page and return the names of
     * all video files. This is the Fix A foundation: instead of downloading the
     * .torrent binary to learn its file list, we read the names directly from the
     * HTML the site already shows us.
     *
     * Returns null if no video files were found in any table.
     */
    private fun extractVideoFileNamesFromDoc(doc: Document): List<String>? {
        var best: List<String> = emptyList()
        for (table in doc.select("table")) {
            val rows = table.select("tr")
            if (rows.size < 2) continue
            val candidates = mutableListOf<String>()
            for (row in rows) {
                val cells = row.select("td")
                if (cells.isNotEmpty()) {
                    val name = cells[0].text().trim()
                    if (name.isNotEmpty() && isVideoFile(name)) candidates.add(name)
                }
            }
            if (candidates.size > best.size) best = candidates
        }
        return if (best.isNotEmpty()) {
            Log.d(TAG, "extractVideoFileNamesFromDoc: found ${best.size} video files")
            best
        } else {
            Log.w(TAG, "extractVideoFileNamesFromDoc: no video files found")
            null
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // TORRENT DOWNLOAD (only called from loadLinks)
    // ═══════════════════════════════════════════════════════════════════

    sealed class TorrentDownloadResult {
        data class Success(val bytes: ByteArray) : TorrentDownloadResult()
        data object DailyLimitExceeded : TorrentDownloadResult()
        data object NotLoggedIn : TorrentDownloadResult()
        data class Error(val message: String) : TorrentDownloadResult()
    }

    private fun downloadTorrentFile(url: String): TorrentDownloadResult = try {
        val request = Request.Builder()
            .url(toAbsoluteUrl(url))
            .headers(getAuthHeaders("$mainUrl/").toHeaders())
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return TorrentDownloadResult.Error("HTTP ${response.code}")
            val contentType = response.header("Content-Type", "") ?: ""
            val bodyBytes = response.body?.bytes() ?: return TorrentDownloadResult.Error("Empty response")
            if (contentType.contains("text/html", ignoreCase = true)) {
                val htmlBody = String(bodyBytes, Charsets.UTF_8)
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
            Log.d(TAG, "downloadTorrentFile: ${bodyBytes.size} bytes")
            TorrentDownloadResult.Success(bodyBytes)
        }
    } catch (e: Exception) {
        TorrentDownloadResult.Error(e.message ?: "Unknown error")
    }

    // ═══════════════════════════════════════════════════════════════════
    // TORRENT → MAGNET CONVERSION
    // ═══════════════════════════════════════════════════════════════════

    private fun torrentToMagnet(torrentBytes: ByteArray): String? = try {
        val infoBytesResult = findInfoDictBytes(torrentBytes)
            ?: run { Log.e(TAG, "torrentToMagnet: no info dict"); return null }
        val sha1 = java.security.MessageDigest.getInstance("SHA-1").digest(infoBytesResult.bytes)
        val infoHashBase32 = base32Encode(sha1)
        Log.d(TAG, "torrentToMagnet: infohash(hex)=${sha1.joinToString("") { "%02x".format(it) }}")

        val (root, _) = decodeBencode(torrentBytes, 0)
        val dict = root as? BencodeValue.BDict ?: return null
        val infoEntry = dict.entries.find { (k, _) -> k.bytes.contentEquals("info".toByteArray()) } ?: return null

        val magnet = StringBuilder("magnet:?xt=urn:btih:$infoHashBase32")

        val nameEntry = (infoEntry.second as? BencodeValue.BDict)?.entries?.find { (k, _) ->
            k.bytes.contentEquals("name".toByteArray())
        }
        if (nameEntry != null) {
            val nameStr = String((nameEntry.second as BencodeValue.BString).bytes)
            magnet.append("&dn=").append(URLEncoder.encode(nameStr, "UTF-8"))
        }

        val trackerUrls = mutableListOf<String>()
        dict.entries.find { (k, _) -> k.bytes.contentEquals("announce".toByteArray()) }?.let {
            trackerUrls.add(String((it.second as BencodeValue.BString).bytes))
        }
        dict.entries.find { (k, _) -> k.bytes.contentEquals("announce-list".toByteArray()) }?.let { al ->
            (al.second as? BencodeValue.BList)?.items?.forEach { tier ->
                (tier as? BencodeValue.BList)?.items?.forEach { tracker ->
                    val u = String((tracker as BencodeValue.BString).bytes)
                    if (u !in trackerUrls) trackerUrls.add(u)
                }
            }
        }

        // Extract passkey from announce URL if present.
        for (u in trackerUrls) {
            val m = PASSKEY_REGEX.find(u)
            if (m != null) { cachedPasskey = m.groupValues[1]; break }
        }

        val seen = mutableSetOf<String>()
        for (u in trackerUrls) {
            if (!u.contains("arabp2p.net")) {
                if (seen.add(u)) magnet.append("&tr=").append(URLEncoder.encode(u, "UTF-8"))
                continue
            }
            val pkMatch = PASSKEY_REGEX.find(u)
            if (pkMatch != null) {
                val pk = pkMatch.groupValues[1]
                val nonPk = u.replace("/$pk/announce", "/announce")
                if (seen.add(nonPk)) magnet.append("&tr=").append(URLEncoder.encode(nonPk, "UTF-8"))
                if (seen.add(u)) magnet.append("&tr=").append(URLEncoder.encode(u, "UTF-8"))
            } else {
                if (seen.add(u)) magnet.append("&tr=").append(URLEncoder.encode(u, "UTF-8"))
                cachedPasskey?.let { pk ->
                    val pkUrl = u.replace("/announce", "/$pk/announce")
                    if (seen.add(pkUrl)) magnet.append("&tr=").append(URLEncoder.encode(pkUrl, "UTF-8"))
                }
            }
        }

        if (trackerUrls.isEmpty() && cachedPasskey != null) {
            val nonPk = "http://www.arabp2p.net:2052/announce"
            val pkUrl = "http://www.arabp2p.net:2052/${cachedPasskey}/announce"
            magnet.append("&tr=").append(URLEncoder.encode(nonPk, "UTF-8"))
            magnet.append("&tr=").append(URLEncoder.encode(pkUrl, "UTF-8"))
        }

        magnet.toString()
    } catch (e: Exception) {
        Log.e(TAG, "torrentToMagnet error: ${e.message}")
        null
    }

    private data class InfoBytesResult(val bytes: ByteArray, val startOffset: Int, val endOffset: Int)

    private fun findInfoDictBytes(data: ByteArray): InfoBytesResult? {
        return try {
            if (data.isEmpty() || data[0] != 'd'.code.toByte()) return null
            var pos = 1
            while (pos < data.size && data[pos] != 'e'.code.toByte()) {
                val (key, afterKey) = decodeBencode(data, pos)
                val keyStr = String((key as BencodeValue.BString).bytes)
                if (keyStr == "info") {
                    val valueStart = afterKey
                    val (_, valueEnd) = decodeBencode(data, valueStart)
                    return InfoBytesResult(data.sliceArray(valueStart until valueEnd), valueStart, valueEnd)
                }
                val (_, afterValue) = decodeBencode(data, afterKey)
                pos = afterValue
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "findInfoDictBytes: ${e.message}")
            null
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // LOCAL TORRENT SERVER
    // ═══════════════════════════════════════════════════════════════════

    private var localServerSocket: ServerSocket? = null
    private var localServerPort: Int = 0
    private var localServerThread: Thread? = null
    @Volatile private var servedTorrentBytes: ByteArray? = null
    @Volatile private var lastServerActivity: Long = 0

    private fun startLocalTorrentServer(bytes: ByteArray): String? {
        servedTorrentBytes = bytes
        lastServerActivity = System.currentTimeMillis()

        if (localServerSocket != null && localServerPort > 0 && localServerThread?.isAlive == true) {
            return "http://127.0.0.1:$localServerPort/torrent.torrent"
        }
        stopLocalTorrentServer()

        return try {
            val socket = ServerSocket(0).apply {
                reuseAddress = true
                soTimeout = 5000
            }
            localServerSocket = socket
            localServerPort = socket.localPort
            localServerThread = Thread({
                try {
                    while (!Thread.currentThread().isInterrupted) {
                        if (System.currentTimeMillis() - lastServerActivity > 90_000) break
                        try {
                            val client = socket.accept()
                            lastServerActivity = System.currentTimeMillis()
                            handleLocalServerRequest(client)
                        } catch (_: java.net.SocketTimeoutException) {
                            continue
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    stopLocalTorrentServer()
                }
            }, "Arabp2LocalServer").also { it.start() }
            "http://127.0.0.1:$localServerPort/torrent.torrent"
        } catch (e: Exception) {
            Log.e(TAG, "startLocalTorrentServer: ${e.message}")
            null
        }
    }

    private fun handleLocalServerRequest(socket: Socket) {
        try {
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            val buffer = ByteArray(4096)
            input.read(buffer)
            val bytes = servedTorrentBytes
            if (bytes == null) {
                output.write("HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n".toByteArray())
            } else {
                output.write("HTTP/1.1 200 OK\r\nContent-Type: application/x-bittorrent\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                output.write(bytes)
            }
            output.flush()
        } catch (_: Exception) {
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun stopLocalTorrentServer() {
        try { localServerSocket?.close() } catch (_: Exception) {}
        try { localServerThread?.interrupt() } catch (_: Exception) {}
        localServerSocket = null
        localServerPort = 0
        localServerThread = null
    }

    // ═══════════════════════════════════════════════════════════════════
    // PASSKEY FETCH
    // ═══════════════════════════════════════════════════════════════════

    private fun fetchPasskeyFromWebsite(): String? {
        cachedPasskey?.let { return it }
        if (!ensureLogin()) return null
        return try {
            val request = Request.Builder()
                .url("$mainUrl/index.php?page=usercp")
                .headers(getAuthHeaders("$mainUrl/").toHeaders())
                .build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return null
                val doc = Jsoup.parse(body, "$mainUrl/index.php?page=usercp")
                val els = doc.select("td:contains(passkey) + td, td:contains(Passkey) + td, td:contains(مفتاح) + td, span.passkey, #passkey")
                for (el in els) {
                    val m = Regex("[0-9a-f]{32}").find(el.text())
                    if (m != null) { cachedPasskey = m.groupValues[0]; return cachedPasskey }
                }
                Regex("[0-9a-f]{32}").find(body)?.let { cachedPasskey = it.groupValues[0]; return cachedPasskey }
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchPasskeyFromWebsite: ${e.message}")
            null
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // THANK UPLOADER (avoid daily download limit)
    // ═══════════════════════════════════════════════════════════════════

    private fun thankUploader(torrentId: String, detailUrl: String): Boolean {
        if (!ensureLogin()) return false
        return try {
            val formBody = FormBody.Builder()
                .add("tid", torrentId)
                .add("thanks", "1")
                .build()
            val refererUrl = if (detailUrl.isNotBlank()) toAbsoluteUrl(detailUrl)
                else "$mainUrl/index.php?page=torrent-details&id=$torrentId"
            val request = Request.Builder()
                .url("$mainUrl/thanks.php")
                .post(formBody)
                .headers(getAuthHeaders(refererUrl).toHeaders())
                .header("X-Requested-With", "XMLHttpRequest")
                .build()
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            Log.e(TAG, "thankUploader: ${e.message}")
            false
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // BENCODE PARSER
    // ═══════════════════════════════════════════════════════════════════

    sealed class BencodeValue {
        data class BString(val bytes: ByteArray) : BencodeValue()
        data class BInt(val value: Long) : BencodeValue()
        data class BList(val items: List<BencodeValue>) : BencodeValue()
        data class BDict(val entries: MutableList<Pair<BString, BencodeValue>>) : BencodeValue()
    }

    private data class BencodeParseResult(val value: BencodeValue, val nextPos: Int)

    private fun decodeBencode(data: ByteArray, startPos: Int): BencodeParseResult {
        if (startPos >= data.size) throw IllegalArgumentException("Unexpected end at $startPos")
        val firstByte = data[startPos].toInt().toChar()
        return when {
            firstByte == 'd' -> {
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
            firstByte == 'l' -> {
                var pos = startPos + 1
                val items = mutableListOf<BencodeValue>()
                while (pos < data.size && data[pos].toInt().toChar() != 'e') {
                    val (item, afterItem) = decodeBencode(data, pos)
                    items.add(item)
                    pos = afterItem
                }
                BencodeParseResult(BencodeValue.BList(items), pos + 1)
            }
            firstByte == 'i' -> {
                val endPos = indexOfByte(data, 'e'.code.toByte(), startPos + 1)
                val numStr = String(data, startPos + 1, endPos - startPos - 1)
                BencodeParseResult(BencodeValue.BInt(numStr.toLong()), endPos + 1)
            }
            firstByte in '0'..'9' -> decodeBencodeString(data, startPos)
            else -> throw IllegalArgumentException("Unexpected byte '$firstByte' at $startPos")
        }
    }

    private fun decodeBencodeString(data: ByteArray, startPos: Int): BencodeParseResult {
        val colonPos = indexOfByte(data, ':'.code.toByte(), startPos)
        val length = String(data, startPos, colonPos - startPos).toInt()
        val stringStart = colonPos + 1
        val bytes = data.sliceArray(stringStart until stringStart + length)
        return BencodeParseResult(BencodeValue.BString(bytes), stringStart + length)
    }

    private fun indexOfByte(data: ByteArray, target: Byte, startPos: Int): Int {
        for (i in startPos until data.size) if (data[i] == target) return i
        throw IllegalArgumentException("Byte '${target.toInt().toChar()}' not found after $startPos")
    }

    // ═══════════════════════════════════════════════════════════════════
    // UTILITIES
    // ═══════════════════════════════════════════════════════════════════

    private fun base32Encode(data: ByteArray): String {
        val sb = StringBuilder()
        var buffer = 0L
        var bits = 0
        for (byte in data) {
            buffer = (buffer shl 8) or (byte.toInt() and 0xFF).toLong()
            bits += 8
            while (bits >= 5) {
                bits -= 5
                sb.append(BASE32_ALPHABET[((buffer shr bits) and 0x1F).toInt()])
            }
        }
        if (bits > 0) sb.append(BASE32_ALPHABET[((buffer shl (5 - bits)) and 0x1F).toInt()])
        return sb.toString()
    }

    private fun toAbsoluteUrl(url: String): String = when {
        url.startsWith("http") -> url
        url.startsWith("//") -> "https:$url"
        url.startsWith("/") -> "$mainUrl$url"
        else -> "$mainUrl/$url"
    }

    private fun cleanTitleText(text: String): String =
        text.replace(Regex("<[^>]*>"), "").replace(Regex("\\s+"), " ").trim()

    private fun tvTypeFromPage(pageUrl: String): TvType = when {
        pageUrl.contains("movies-listing") -> TvType.Movie
        pageUrl.contains("tv-listing") -> TvType.TvSeries
        pageUrl.contains("category=88") -> TvType.Movie
        pageUrl.contains("category=") -> TvType.TvSeries
        else -> TvType.Anime
    }

    private fun tvTypeFromTitle(title: String): TvType =
        if (title.contains("فيلم", ignoreCase = true) || title.contains("Movie", ignoreCase = true)) TvType.Movie
        else TvType.TvSeries

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
}
