package com.arabp

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
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpCookie
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

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
        private const val MAX_TORRENT_CACHE = 10

        // Rate limiting: minimum milliseconds between consecutive HTTP requests
        // to avoid IP blocking from arabp2p.net anti-scraping protection.
        // Increased from 800ms to 1500ms with 0-1500ms jitter for human-like pacing.
        private const val MIN_REQUEST_INTERVAL_MS = 1500L

        // Maximum concurrent HTTP requests allowed at any time.
        // Prevents parallel async() from overwhelming the server.
        private const val MAX_CONCURRENT_REQUESTS = 1

        // Rotating User-Agent pool to reduce bot fingerprint detection.
        // Expanded to 10 diverse Android Chrome UAs across different devices and versions.
        private val USER_AGENTS = listOf(
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36",
            "Mozilla/5.0 (Linux; Android 12; SM-S908B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Mobile Safari/537.36",
            "Mozilla/5.0 (Linux; Android 11; M2101K6G) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36",
            "Mozilla/5.0 (Linux; Android 14; SM-A546B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36",
            "Mozilla/5.0 (Linux; Android 13; SM-A145F) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
            "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
            "Mozilla/5.0 (Linux; Android 14; SM-S928B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36",
            "Mozilla/5.0 (Linux; Android 11; Redmi Note 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36",
            "Mozilla/5.0 (Linux; Android 13; OPPO CPH2461) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"
        )
    }

    // Thread-safe rate-limiting state: uses AtomicLong for correct multi-thread behavior.
    // The previous @Volatile Long was NOT thread-safe — parallel async() coroutines could
    // read the same timestamp, all sleep the same amount, then all fire at once.
    private val lastRequestTimeMs = AtomicLong(0)

    // Semaphore to enforce MAX_CONCURRENT_REQUESTS. Prevents parallel async() in load()
    // from firing many HTTP requests simultaneously.
    private val requestSemaphore = Semaphore(MAX_CONCURRENT_REQUESTS)

    // ---- Speed-up state (search + home page only) ----
    // Limits concurrent in-flight BROWSE requests (home page carousels + search pages).
    // 5 permits = browser-like parallelism (Chrome uses 6 per host). With 11 home-page
    // carousels this means 11/5 = 2.2 batches instead of 11/3 = 3.7 batches, cutting
    // home-page fetch time by ~40% while staying IP-ban-safe.
    private val browseSemaphore = Semaphore(5)

    // Lock to prevent thundering-herd login. When 11 home-page carousels fire at once,
    // without this lock all 11 would enter ensureLogin() simultaneously and serialize
    // through authClient's semaphore (1 permit) — taking 11 * ~3s = ~33s on first load.
    // With this lock, only the first coroutine logs in; the rest wait, then see
    // isLoggedIn=true via the double-check and skip immediately.
    private val loginLock = Any()

    // ---- Speed-up: shared OkHttp ConnectionPool ----
    // Both authClient and browseClient connect to the same host (arabp2p.net).
    // Without sharing, each client does its own TLS handshake (~300-500ms each).
    // Sharing one pool means the second client reuses the first's warm connection.
    // Thread-safe by design (OkHttp ConnectionPool is internally synchronized).
    private val sharedConnectionPool = okhttp3.ConnectionPool(5, 5, TimeUnit.MINUTES)

    // ---- Speed-up: short-TTL cache for browse Documents ----
    // Caches parsed HTML Documents for home-page carousel URLs and search URLs.
    // TTL = 60s: short enough to stay fresh, long enough that navigating from home
    // → detail → back-to-home is instant instead of re-fetching all 11 carousels.
    // Also REDUCES total request count → IP-ban safer.
    private val browseCacheLock = Any()
    private val browseCache = object : LinkedHashMap<String, Pair<Long, Document>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, Document>>): Boolean {
            return size > 32
        }
    }
    private val BROWSE_CACHE_TTL_MS = 60_000L

    private fun getCachedBrowseDoc(url: String): Document? = synchronized(browseCacheLock) {
        val entry = browseCache[url] ?: return null
        if (System.currentTimeMillis() - entry.first > BROWSE_CACHE_TTL_MS) {
            browseCache.remove(url)
            return null
        }
        entry.second
    }

    private fun cacheBrowseDoc(url: String, doc: Document) = synchronized(browseCacheLock) {
        browseCache[url] = Pair(System.currentTimeMillis(), doc)
    }

    // ---- Speed-up: stale-while-revalidate cache ----
    // SEPARATE from the 60s fresh cache above. This stale cache has NO TTL — it
    // returns the last-known Document regardless of age. Used by fetchDocForBrowseCached:
    //   1. Fresh cache hit (≤60s)  → return immediately, no refresh
    //   2. Stale cache hit (>60s)  → return immediately, trigger background refresh
    //   3. No cache at all         → fetch synchronously (first ever load)
    // This makes EVERY home-page load after the first one feel INSTANT — the user
    // sees stale content immediately while fresh data loads in the background.
    private val staleBrowseCacheLock = Any()
    private val staleBrowseCache = object : LinkedHashMap<String, Document>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Document>): Boolean {
            return size > 32
        }
    }

    private fun getCachedStaleBrowseDoc(url: String): Document? = synchronized(staleBrowseCacheLock) {
        staleBrowseCache[url]
    }

    private fun cacheStaleBrowseDoc(url: String, doc: Document) = synchronized(staleBrowseCacheLock) {
        staleBrowseCache[url] = doc
    }

    // ---- Speed-up: in-flight request deduplication ----
    // When CloudStream calls getMainPage() for 11 carousels AND the background prefetch
    // fires for 10 of those same URLs, we'd get 21 concurrent requests for 11 URLs.
    // This map holds per-URL locks: only ONE HTTP request per URL is made. The rest
    // wait on the lock and get the cached result. Saves ~10 duplicate requests.
    private val urlFetchLocks = ConcurrentHashMap<String, Any>()

    // ---- Speed-up: home-page background prefetch ----
    // Background scope for fire-and-forget prefetch of home-page carousels.
    // Uses SupervisorJob so one failed prefetch doesn't cancel others.
    private val prefetchScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Cooldown to prevent repeated prefetch bursts. Set to 60s (same as cache TTL):
    // - First home-page load: prefetch fires, caches all 11 carousels for 60s.
    // - Within 60s: repeat loads are instant (cache hit). Prefetch on cooldown.
    // - After 60s: cache expired, cooldown expired → next load triggers new prefetch.
    @Volatile
    private var lastPrefetchTimeMs = 0L
    private val homePrefetchLock = Any()
    private val PREFETCH_COOLDOWN_MS = 60_000L

    private fun randomUserAgent(): String =
        USER_AGENTS[kotlin.random.Random.nextInt(USER_AGENTS.size)]

    // LRU cache for downloaded .torrent bytes (key = torrent ID, thread-safe)
    private val torrentCacheLock = Any()
    private val torrentCache = object : LinkedHashMap<String, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>): Boolean {
            return size > MAX_TORRENT_CACHE
        }
    }

    private fun getCachedTorrent(id: String): ByteArray? = synchronized(torrentCacheLock) {
        torrentCache[id]
    }

    private fun cacheTorrent(id: String, bytes: ByteArray) = synchronized(torrentCacheLock) {
        torrentCache[id] = bytes
    }

    // Cached passkey extracted from .torrent announce URL or website
    @Volatile
    private var cachedPasskey: String? = null

    // Cache of resolved download URLs (torrentId -> URL with &f=).
    // Avoids re-fetching detail pages in loadLinks() that were already fetched in load().
    private val resolvedUrlCacheLock = Any()
    private val resolvedUrlCache = HashMap<String, String>()

    private fun getCachedResolvedUrl(torrentId: String): String? = synchronized(resolvedUrlCacheLock) {
        resolvedUrlCache[torrentId]
    }

    private fun cacheResolvedUrl(torrentId: String, url: String) = synchronized(resolvedUrlCacheLock) {
        resolvedUrlCache[torrentId] = url
    }

    // Images require Referer header to avoid 403
    private val imageHeaders = mapOf(
        "User-Agent" to USER_AGENTS[0],
        "Referer" to "$mainUrl/"
    )

    // ==================== SESSION / COOKIE MANAGEMENT ====================

    private val cookieManager = CookieManager().apply {
        setCookiePolicy(CookiePolicy.ACCEPT_ALL)
    }

    private val authClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            // Thread-safe rate-limiting interceptor: enforces minimum delay between
            // requests using AtomicLong compareAndSet to prevent race conditions.
            // Also acquires a semaphore to limit concurrent in-flight requests.
            .addInterceptor { chain ->
                requestSemaphore.acquire()
                try {
                    while (true) {
                        val now = System.currentTimeMillis()
                        val last = lastRequestTimeMs.get()
                        val elapsed = now - last
                        if (elapsed < MIN_REQUEST_INTERVAL_MS) {
                            val delay = MIN_REQUEST_INTERVAL_MS - elapsed +
                                kotlin.random.Random.nextLong(0, 1500) // 0-1500ms jitter for human-like pacing
                            Log.d(TAG, "Rate limit: sleeping ${delay}ms between requests")
                            try { Thread.sleep(delay) } catch (_: InterruptedException) {}
                        }
                        val newNow = System.currentTimeMillis()
                        // Atomically update only if no other thread updated it since we read it
                        if (lastRequestTimeMs.compareAndSet(last, newNow)) break
                    }
                    chain.proceed(chain.request())
                } finally {
                    requestSemaphore.release()
                }
            }
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
            .connectionPool(sharedConnectionPool)
            .build()
    }

    // Separate client for HOME PAGE BROWSING only.
    // Uses a MUCH lighter rate limiter (300ms vs 1500ms) and NO Semaphore,
    // allowing concurrent category fetches so 11 carousels load fast.
    // Shares the same cookieManager for auth, but has shorter timeouts
    // so a slow/failing category page doesn't hold up the entire home page.
    // Anti-blocking for torrent downloads still goes through authClient.
    private val browseClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                // Speed-up: use browseSemaphore (3 permits) instead of the SHARED
                // lastRequestTimeMs CAS loop. The previous implementation shared
                // lastRequestTimeMs with authClient's 1500ms limiter, which caused
                // browse requests to be throttled to 1500-1800ms intervals even
                // though the intent was 300ms. The semaphore gives TRUE parallelism
                // (up to 3 in-flight HTTP requests at once, no artificial sleep)
                // while keeping the server load browser-like and IP-ban-safe.
                browseSemaphore.acquire()
                try {
                    chain.proceed(chain.request())
                } finally {
                    browseSemaphore.release()
                }
            }
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
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .connectionPool(sharedConnectionPool)
            .build()
    }

    private fun getSessionCookies(): String {
        val uri = URI(mainUrl)
        return cookieManager.cookieStore.get(uri)
            .joinToString("; ") { "${it.name}=${it.value}" }
    }

    private fun getAuthHeaders(referer: String? = null): Map<String, String> {
        val headers = mutableMapOf(
            "User-Agent" to randomUserAgent(),
            // Accept-Language header with Arabic makes requests look more like a real browser
            "Accept-Language" to "ar,en-US;q=0.9,en;q=0.8",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8"
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

    /**
     * Validate that our session is actually alive by checking if we have
     * auth cookies and they still work. If session expired, reset and re-login.
     */
    private fun isSessionAlive(): Boolean {
        if (!isLoggedIn) return false
        val cookies = getSessionCookies()
        // We need at least uid_ and pass_ cookies to be considered logged in
        if (!cookies.contains("uid_") || !cookies.contains("pass_")) {
            Log.w(TAG, "Session cookies missing, resetting isLoggedIn")
            isLoggedIn = false
            return false
        }
        return true
    }

    private fun ensureLogin(): Boolean {
        // Fast path — no lock needed if already logged in.
        if (isSessionAlive()) return true

        // Slow path — acquire loginLock to prevent thundering-herd login attempts.
        // Without this, when 11 home-page carousels fire at once, all 11 would enter
        // ensureLogin() simultaneously and serialize through authClient's semaphore
        // (1 permit, 1500ms rate limit), taking 11 * ~3s = ~33s on first home-page load.
        synchronized(loginLock) {
            // Double-check inside the lock — another coroutine may have logged in
            // while we were waiting.
            if (isSessionAlive()) return true

            return try {
                // Speed-up: fetch the login PAGE via browseClient (fast, no 1500ms rate
                // limit) instead of authClient. The login page is a public page — it
                // doesn't need the strict anti-scraping limiter. Only the credential
                // POST below needs authClient. Cookies are shared via cookieManager so
                // the POST picks up any session cookie set by this GET.
                // Saves ~1.5s on every first-load login (the 1500ms rate-limit gap
                // between init GET and POST is eliminated).
                val initRequest = Request.Builder()
                    .url("$mainUrl/index.php?page=login")
                    .header("User-Agent", randomUserAgent())
                    .build()
                browseClient.newCall(initRequest).execute().use { response ->
                    Log.d(TAG, "Init login page: ${response.code}")
                }

                val formBody = FormBody.Builder()
                    .add("uid", LOGIN_USERNAME)
                    .add("pwd", LOGIN_PASSWORD)
                    .build()

                val loginRequest = Request.Builder()
                    .url("$mainUrl/index.php?page=login&returnto=index.php")
                    .post(formBody)
                    .header("User-Agent", randomUserAgent())
                    .header("Referer", "$mainUrl/index.php?page=login")
                    .build()

                authClient.newCall(loginRequest).execute().use { response ->
                    val body = response.body?.string() ?: ""
                    val cookiesAfterLogin = getSessionCookies()
                    Log.d(TAG, "Login response: code=${response.code}, cookies=$cookiesAfterLogin")

                    val loginSuccess = response.code == 302 ||
                            cookiesAfterLogin.contains("uid_") ||
                            body.contains("logout.php") ||
                            body.contains("page=logout") ||
                            body.contains(LOGIN_USERNAME)

                    if (loginSuccess) {
                        isLoggedIn = true
                        Log.d(TAG, "Login SUCCESS! Cookies: $cookiesAfterLogin")
                    } else {
                        Log.e(TAG, "Login FAILED. Response code=${response.code}, snippet: ${body.take(300)}")
                    }
                    loginSuccess
                }
            } catch (e: Exception) {
                Log.e(TAG, "Login Error: ${e.message}")
                false
            }
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

    private fun fetchDocWithAuth(url: String): Document? {
        if (!ensureLogin()) {
            Log.e(TAG, "fetchDocWithAuth: login failed, cannot fetch $url")
            return null
        }
        return try {
            val request = Request.Builder()
                .url(url)
                .headers(getAuthHeaders(referer = "$mainUrl/").toHeaders())
                .build()
            authClient.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return null
                val doc = Jsoup.parse(body, url)
                // Check if we got a login page instead of real content
                val hasListingContent = doc.select("div.listing_div1, table.lista2t, div.listing_div_id, table#listing_table, div.file-header").isNotEmpty()
                if (!hasListingContent && body.contains("name=\"uid\"")) {
                    Log.w(TAG, "fetchDocWithAuth: got login page for $url — session may have expired, retrying login")
                    isLoggedIn = false
                    if (ensureLogin()) {
                        // Retry the request after re-login
                        val retryRequest = Request.Builder()
                            .url(url)
                            .headers(getAuthHeaders(referer = "$mainUrl/").toHeaders())
                            .build()
                        authClient.newCall(retryRequest).execute().use { retryResponse ->
                            val retryBody = retryResponse.body?.string() ?: return null
                            return Jsoup.parse(retryBody, url)
                        }
                    }
                }
                doc
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchDocWithAuth error for $url: ${e.message}")
            null
        }
    }

    /**
     * Always use the authenticated client for ALL requests.
     * arabp2p.net requires session cookies for ALL listing pages (anime, TV, movies).
     * CloudStream's app.get() does not share our authClient cookies, so it will always
     * get the login page instead of listing content.
     */
    private suspend fun fetchDoc(url: String): Document? {
        return fetchDocWithAuth(url)
    }

    /**
     * Fast document fetch for HOME PAGE BROWSING only.
     * Uses browseClient (light rate limiter, no Semaphore, shorter timeouts)
     * so that 11 category pages can load concurrently without hitting the
     * 120s CloudStream timeout.
     * Still calls ensureLogin() via authClient to get auth cookies, then
     * uses browseClient for the actual page fetch.
     */
    private fun fetchDocForBrowse(url: String): Document? {
        if (!ensureLogin()) {
            Log.e(TAG, "fetchDocForBrowse: login failed, cannot fetch $url")
            return null
        }
        return try {
            val request = Request.Builder()
                .url(url)
                .headers(getAuthHeaders(referer = "$mainUrl/").toHeaders())
                .build()
            browseClient.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return null
                val doc = Jsoup.parse(body, url)
                // Same session-expired check as fetchDocWithAuth
                val hasListingContent = doc.select("div.listing_div1, table.lista2t, div.listing_div_id, table#listing_table, div.file-header").isNotEmpty()
                if (!hasListingContent && body.contains("name=\"uid\"")) {
                    Log.w(TAG, "fetchDocForBrowse: got login page for $url — session may have expired, retrying login")
                    isLoggedIn = false
                    if (ensureLogin()) {
                        val retryRequest = Request.Builder()
                            .url(url)
                            .headers(getAuthHeaders(referer = "$mainUrl/").toHeaders())
                            .build()
                        browseClient.newCall(retryRequest).execute().use { retryResponse ->
                            val retryBody = retryResponse.body?.string() ?: return null
                            return Jsoup.parse(retryBody, url)
                        }
                    }
                }
                doc
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchDocForBrowse error for $url: ${e.message}")
            null
        }
    }

    /**
     * Speed-up: stale-while-revalidate + in-flight deduplication wrapper.
     *
     * Three-tier strategy for INSTANT home page:
     *
     *   1. FRESH cache hit (≤60s old) → return immediately, no HTTP request at all.
     *      Navigating home → detail → back-to-home within 60s is instant.
     *
     *   2. STALE cache hit (>60s old) → return stale Document IMMEDIATELY, then
     *      trigger a fire-and-forget background refresh. The user sees content
     *      instantly; fresh data is cached for next time. This makes EVERY
     *      home-page load after the first one feel instant — no waiting at all.
     *
     *   3. NO cache (first ever load) → fetch synchronously with in-flight dedup.
     *      Per-URL locks ensure that if 21 coroutines request 11 URLs simultaneously
     *      (11 getMainPage calls + 10 background prefetch), only 11 HTTP requests
     *      are made — not 21. The duplicate callers wait on the lock and get the
     *      cached result.
     *
     * IP-ban safety: stale-while-revalidate REDUCES total request count vs the
     * old approach. In-flight dedup eliminates duplicate requests. Net effect:
     * fewer HTTP requests to the server, not more.
     */
    private fun fetchDocForBrowseCached(url: String): Document? {
        // Tier 1: Fresh cache hit (≤60s) — return immediately.
        getCachedBrowseDoc(url)?.let {
            Log.d(TAG, "fetchDocForBrowseCached: FRESH HIT for $url")
            return it
        }

        // Tier 2: Stale cache hit (>60s) — return immediately, refresh in background.
        // This is what makes the home page feel "almost instant" on repeat visits.
        val staleDoc = getCachedStaleBrowseDoc(url)
        if (staleDoc != null) {
            Log.d(TAG, "fetchDocForBrowseCached: STALE HIT for $url — returning stale, refreshing in background")
            // Fire-and-forget background refresh. Uses prefetchScope (SupervisorJob)
            // so failures don't cascade. browseSemaphore(5) limits concurrency.
            prefetchScope.launch {
                try {
                    val freshDoc = fetchDocForBrowse(url)
                    if (freshDoc != null) {
                        cacheBrowseDoc(url, freshDoc)
                        cacheStaleBrowseDoc(url, freshDoc)
                        Log.d(TAG, "fetchDocForBrowseCached: background refresh OK for $url")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "fetchDocForBrowseCached: background refresh failed for $url: ${e.message}")
                }
            }
            return staleDoc
        }

        // Tier 3: No cache at all (first ever load) — fetch synchronously.
        // In-flight dedup: per-URL lock ensures only ONE HTTP request per URL.
        // If 3 coroutines request the same URL simultaneously, only 1 fetches;
        // the other 2 wait on the lock and get the cached result.
        val lock = urlFetchLocks.computeIfAbsent(url) { Any() }
        synchronized(lock) {
            // Double-check caches after acquiring lock — another coroutine may have
            // fetched and cached this URL while we were waiting.
            getCachedBrowseDoc(url)?.let { return it }
            getCachedStaleBrowseDoc(url)?.let { return it }

            Log.d(TAG, "fetchDocForBrowseCached: FETCH (no cache) for $url")
            val doc = fetchDocForBrowse(url)
            if (doc != null) {
                cacheBrowseDoc(url, doc)
                cacheStaleBrowseDoc(url, doc)
            }
            return doc
        }
    }

    /**
     * Speed-up: fire-and-forget background prefetch of ALL home-page carousels.
     * Called from getMainPage() on the first call (per 60s cooldown period).
     * Prefetches all carousels EXCEPT the current one (which the caller fetches
     * directly so it returns ASAP). Results land in the 60s TTL cache, so
     * subsequent getMainPage() calls for those carousels return instantly.
     *
     * This makes the home page load in ~1.5s (login + first fetch) instead of
     * ~5-8s (sequential fetch of all 11), with progressive display: the first
     * carousel appears ASAP, then the rest are instant (cache hits).
     *
     * IP-ban safety: 11 requests in ~1.5s = ~7 req/s (Chrome does 20+ on a
     * complex page). 60s cooldown prevents repeat bursts. browseSemaphore(5)
     * caps concurrent in-flight requests at 5.
     */
    private fun triggerHomePrefetch(excludeUrl: String) {
        val now = System.currentTimeMillis()
        if (now - lastPrefetchTimeMs < PREFETCH_COOLDOWN_MS) return
        synchronized(homePrefetchLock) {
            val now2 = System.currentTimeMillis()
            if (now2 - lastPrefetchTimeMs < PREFETCH_COOLDOWN_MS) return
            lastPrefetchTimeMs = now2
        }
        Log.d(TAG, "triggerHomePrefetch: starting background prefetch (excluding $excludeUrl)")
        prefetchScope.launch {
            mainPage.mapNotNull { mp ->
                val url = "${mp.data}&pages=1"
                if (url == excludeUrl) null  // caller handles this one
                else async {  // inherits Dispatchers.IO from prefetchScope
                    try {
                        fetchDocForBrowseCached(url)
                        Log.d(TAG, "triggerHomePrefetch: OK $url")
                    } catch (e: Exception) {
                        Log.w(TAG, "triggerHomePrefetch: failed $url: ${e.message}")
                    }
                }
            }.awaitAll()
            Log.d(TAG, "triggerHomePrefetch: background prefetch complete")
        }
    }

    // ==================== HOME PAGE ====================

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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        // Always use &pages=$page to ensure the site returns only one page of results.
        // Without this, page 1 loads ALL items at once, causing too many HTTP requests
        // (poster images etc.) which triggers IP blocking.
        val url = "${request.data}&pages=$page"
        Log.d(TAG, "getMainPage: fetching $url for '${request.name}' (page=$page)")

        // Speed-up: trigger background prefetch of all OTHER carousels.
        // Fire-and-forget — doesn't block this call. The current carousel is
        // fetched below; the others are prefetched in the background and land
        // in the 60s TTL cache for instant access when CloudStream calls them.
        // This makes the home page feel instant: first carousel loads in ~1.5s
        // (login + fetch), then the remaining 10 are cache hits.
        triggerHomePrefetch(url)

        // Use fetchDocForBrowseCached (browseClient + 60s TTL cache) instead of
        // fetchDoc (authClient). browseClient has 5 concurrent permits and shorter
        // timeouts, so 11 categories load fast without hitting 120s timeout.
        // The cache makes "home → detail → back-to-home" instant.
        // Also wrap in withTimeoutOrNull(20s) as safety net — if one category page
        // is slow/failing, return empty instead of blocking the entire home page.
        val doc = withTimeoutOrNull(20_000L) {
            fetchDocForBrowseCached(url)
        }
        if (doc == null) {
            Log.w(TAG, "getMainPage: fetchDocForBrowse timed out or failed for $url")
            return newHomePageResponse(mutableListOf())
        }
        val homeSets = mutableListOf<HomePageList>()

        try {
            val tvType = tvTypeFromPage(request.data)
            val listingDivs = doc.select("div.listing_div1")
            Log.d(TAG, "getMainPage: found ${listingDivs.size} listing_div1 elements for '${request.name}'")
            val items = listingDivs.mapNotNull { toSearchResult(it, tvType) }

            // For torrent category pages (e.g. documentaries, Asian, Latin, Turkish),
            // try torrent table/modern parsing since they don't use listing_div1
            val allItems = if (items.isEmpty() && request.data.contains("category=")) {
                // Modern torrent pages use div.file-header — prefer these as they give
                // one result per torrent. Avoid table tr selectors here because the
                // new page layout has a single tr containing ALL file-headers, which
                // would produce duplicate/wrong results.
                val modernResults = doc.select("div.file-header")
                    .mapNotNull { modernTorrentRowToSearchResult(it, tvType) }
                // Fallback: old-style table rows (only if no file-header found)
                val tableResults = if (modernResults.isEmpty()) {
                    doc.select("table.lista2t tr.lista2")
                        .mapNotNull { torrentRowToSearchResult(it, tvType) }
                } else emptyList()
                Log.d(TAG, "getMainPage: torrent page found ${modernResults.size} modern + ${tableResults.size} table results for '${request.name}'")
                modernResults + tableResults
            } else {
                items
            }

            Log.d(TAG, "getMainPage: ${allItems.size} search results for '${request.name}'")
            if (allItems.isNotEmpty()) {
                homeSets.add(HomePageList(request.name, allItems))
            }
            return newHomePageResponse(homeSets, allItems.isNotEmpty())
        } catch (e: Exception) {
            Log.e(TAG, "MainPage Error: ${e.message}")
        }
        return newHomePageResponse(homeSets)
    }

    private fun tvTypeFromPage(pageUrl: String): TvType {
        return when {
            pageUrl.contains("movies-listing") -> TvType.Movie
            pageUrl.contains("tv-listing") -> TvType.TvSeries
            pageUrl.contains("category=19") -> TvType.TvSeries  // وثائقيات
            pageUrl.contains("category=88") -> TvType.Movie      // أفلام مدبلجة للعربية
            pageUrl.contains("category=90") -> TvType.TvSeries  // برامج و مسابقات
            pageUrl.contains("category=93") -> TvType.TvSeries  // وثائقيات مترجمة
            pageUrl.contains("category=113") -> TvType.TvSeries // مسلسلات لاتينية
            pageUrl.contains("category=57") -> TvType.TvSeries  // مسلسلات آسيوية
            pageUrl.contains("category=71") -> TvType.TvSeries  // مسلسلات مدبلجة للعربية
            pageUrl.contains("category=115") -> TvType.TvSeries // مسلسلات تركية
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
        val queryLower = query.lowercase().trim()

        val listingPages = listOf(
            Triple("$mainUrl/index.php?page=anime-listing&search=$encoded", "anime", TvType.Anime),
            Triple("$mainUrl/index.php?page=tv-listing&search=$encoded", "tv", TvType.TvSeries),
            Triple("$mainUrl/index.php?page=movies-listing&search=$encoded", "movies", TvType.Movie)
        )
        val torrentsUrl = "$mainUrl/index.php?page=torrents&search=$encoded&category=0&active=0"

        // SPEED-UP: Fetch all 4 search pages IN PARALLEL using browseClient (5 concurrent
        // permits, no per-request sleep) instead of the OLD sequential for-loop through
        // authClient (1500ms rate limit + semaphore=1).
        //
        // Old: 3 listing pages sequential * ~1.5s + 1 torrents page * ~1.5s = ~6-9s
        // New: 4 pages / 5 concurrent * ~500ms HTTP = ~0.5s (all 4 fit in one batch)
        //
        // IP-ban safety: browseSemaphore(5) caps in-flight requests at 5 — well within
        // browser behavior (Chrome uses 6 per host). Search pages are listing pages
        // (not torrent downloads), so they don't need the strict authClient limiter.
        // Also benefits from the 60s TTL cache — repeat searches are instant.
        return coroutineScope {
            val listingDeferred = listingPages.map { (listingUrl, label, tvType) ->
                async(Dispatchers.IO) {
                    try {
                        // Use fetchDocForBrowseCached (browseClient + 60s TTL cache) —
                        // listing pages don't need the strict authClient limiter that's
                        // reserved for torrent downloads.
                        val doc = fetchDocForBrowseCached(listingUrl)
                        val listingResults = doc?.select("div.listing_div1")
                            ?.mapNotNull { toSearchResult(it, tvType) }
                            ?.filter { matchesQuery(it.name, queryLower) }
                            ?: emptyList()
                        Log.d(TAG, "$label listing search: found ${listingResults.size} results")
                        listingResults
                    } catch (e: Exception) {
                        Log.e(TAG, "$label listing search error: ${e.message}")
                        emptyList()
                    }
                }
            }

            val torrentsDeferred = async(Dispatchers.IO) {
                if (!ensureLogin()) return@async emptyList<SearchResponse>()
                try {
                    // Check 60s TTL cache first — repeat searches for the same query
                    // are instant and don't hit the server at all.
                    val cachedDoc = getCachedBrowseDoc(torrentsUrl)
                    if (cachedDoc != null) {
                        Log.d(TAG, "Torrents search: cache HIT for $torrentsUrl")
                        val modernResults = cachedDoc.select("div.file-header")
                            .mapNotNull { modernTorrentRowToSearchResult(it) }
                        val tableResults = if (modernResults.isEmpty()) {
                            cachedDoc.select("table.lista2t tr.lista2")
                                .mapNotNull { torrentRowToSearchResult(it) }
                        } else emptyList()
                        val allTorrentResults = modernResults + tableResults
                        Log.d(TAG, "Torrents search: found ${allTorrentResults.size} results (cached)")
                        return@async allTorrentResults
                    }

                    val request = Request.Builder()
                        .url(torrentsUrl)
                        .headers(getAuthHeaders(referer = "$mainUrl/").toHeaders())
                        .build()

                    // Use browseClient here too — the torrents search page is just another
                    // listing page, not the actual .torrent download endpoint.
                    browseClient.newCall(request).execute().use { response ->
                        val body = response.body?.string() ?: ""
                        val torrentsDoc = Jsoup.parse(body, torrentsUrl)
                        // Cache for future repeat searches
                        cacheBrowseDoc(torrentsUrl, torrentsDoc)

                        // Prefer modern file-header format; fall back to old table format
                        val modernResults = torrentsDoc.select("div.file-header")
                            .mapNotNull { modernTorrentRowToSearchResult(it) }
                        val tableResults = if (modernResults.isEmpty()) {
                            torrentsDoc.select("table.lista2t tr.lista2")
                                .mapNotNull { torrentRowToSearchResult(it) }
                        } else emptyList()

                        val allTorrentResults = modernResults + tableResults
                        Log.d(TAG, "Torrents search: found ${allTorrentResults.size} results")
                        allTorrentResults
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Torrents search error: ${e.message}")
                    emptyList()
                }
            }

            val results = mutableListOf<SearchResponse>()
            results.addAll(listingDeferred.awaitAll().flatten())
            results.addAll(torrentsDeferred.await())
            Log.d(TAG, "Total search results: ${results.size}")
            results
        }
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

    private fun torrentRowToSearchResult(row: Element, fallbackTvType: TvType = TvType.Anime): SearchResponse? {
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
                        name.contains("فيلم", ignoreCase = true) -> {
                    if (fallbackTvType == TvType.Anime) TvType.AnimeMovie else TvType.Movie
                }
                categoryName.contains("وثائق", ignoreCase = true) || categoryName.contains("Documentary", ignoreCase = true) -> TvType.TvSeries
                fallbackTvType == TvType.TvSeries -> TvType.TvSeries
                else -> fallbackTvType
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

            // Use detailHref as the search result URL (a clean, valid HTTP URL)
            // instead of pipe-delimited data which CloudStream may not handle properly
            // in search result URLs. The torrent metadata is encoded as a URL parameter.
            val torrentData = "$torrentId|${toAbsoluteUrl(downloadHref)}|$magnetHref|${if (isFree) "1" else "0"}|${if (isExternal) "1" else "0"}"
            val encodedData = URLEncoder.encode(torrentData, "UTF-8")
            val searchUrl = "${detailHref}&arabp_data=$encodedData"
            when (tvType) {
                TvType.TvSeries -> newTvSeriesSearchResponse(displayName, searchUrl, tvType) {
                    this.posterUrl = toAbsoluteUrl(posterUrl)
                    this.posterHeaders = imageHeaders
                }
                TvType.Movie -> newMovieSearchResponse(displayName, searchUrl, tvType) {
                    this.posterUrl = toAbsoluteUrl(posterUrl)
                    this.posterHeaders = imageHeaders
                }
                else -> newAnimeSearchResponse(displayName, searchUrl, tvType) {
                    this.posterUrl = toAbsoluteUrl(posterUrl)
                    this.posterHeaders = imageHeaders
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "torrentRowToSearchResult Error: ${e.message}")
            null
        }
    }

    private fun modernTorrentRowToSearchResult(row: Element, fallbackTvType: TvType = TvType.Anime): SearchResponse? {
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
                name.contains("فيلم", ignoreCase = true) || name.contains("Movie", ignoreCase = true) -> {
                    if (fallbackTvType == TvType.Anime) TvType.AnimeMovie else TvType.Movie
                }
                fallbackTvType == TvType.TvSeries -> TvType.TvSeries
                else -> fallbackTvType
            }

            val displayName = buildString {
                append(name)
                if (isFree) append(" ✅مجاني")
            }

            val posterUrl = row.selectFirst("a.screenshot")?.attr("rel")
                ?: nameLink.attr("rel")
                ?: ""

            // Use detailHref as the search result URL (a clean, valid HTTP URL)
            // instead of pipe-delimited data which CloudStream may not handle properly
            // in search result URLs. The torrent metadata is encoded as a URL parameter.
            val torrentData = "$torrentId|${toAbsoluteUrl(downloadHref)}|$magnetHref|${if (isFree) "1" else "0"}|${if (isExternal) "1" else "0"}"
            val encodedData = URLEncoder.encode(torrentData, "UTF-8")
            val searchUrl = "${detailHref}&arabp_data=$encodedData"
            when (tvType) {
                TvType.TvSeries -> newTvSeriesSearchResponse(displayName, searchUrl, tvType) {
                    this.posterUrl = toAbsoluteUrl(posterUrl)
                    this.posterHeaders = imageHeaders
                }
                TvType.Movie -> newMovieSearchResponse(displayName, searchUrl, tvType) {
                    this.posterUrl = toAbsoluteUrl(posterUrl)
                    this.posterHeaders = imageHeaders
                }
                else -> newAnimeSearchResponse(displayName, searchUrl, tvType) {
                    this.posterUrl = toAbsoluteUrl(posterUrl)
                    this.posterHeaders = imageHeaders
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "modernTorrentRowToSearchResult Error: ${e.message}")
            null
        }
    }

    // ==================== TORRENT FILE PARSING ====================

    private val VIDEO_EXTENSIONS = setOf("mkv", "mp4", "avi", "wmv", "flv", "mov", "webm", "ts", "m2ts")

    private fun isVideoFile(path: String): Boolean {
        val ext = path.substringAfterLast(".", "").lowercase()
        return ext in VIDEO_EXTENSIONS
    }

    /** Represents a folder within a torrent containing video files */
    data class TorrentFolder(
        val folderName: String,
        val videoFiles: List<String>
    )

    /**
     * Parse the file list table from a torrent details page.
     * This is used as a fallback when the .torrent file can't be downloaded
     * (e.g., daily download limit exceeded).
     * Returns a list of video file names found in the details page table.
     * Also accepts an already-fetched Document to avoid redundant HTTP requests.
     */
    private fun parseFileListFromDetailPage(detailUrl: String, existingDoc: Document? = null): List<String>? {
        val doc = existingDoc ?: try {
            val request = Request.Builder()
                .url(toAbsoluteUrl(detailUrl))
                .headers(getAuthHeaders(referer = "$mainUrl/").toHeaders())
                .build()
            authClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                Jsoup.parse(body, detailUrl)
            }
        } catch (e: Exception) {
            Log.w(TAG, "parseFileListFromDetailPage error for $detailUrl: ${e.message}")
            return null
        }
        return extractVideoFileNamesFromDoc(doc)
    }

    /**
     * Extract video file names from an already-parsed detail page Document.
     * This avoids re-fetching the page when we already have the parsed HTML.
     */
    private fun extractVideoFileNamesFromDoc(doc: Document): List<String>? {
        val videoFiles = mutableListOf<String>()
        for (table in doc.select("table")) {
            val rows = table.select("tr")
            if (rows.size < 2) continue

            val candidates = mutableListOf<String>()
            for (row in rows) {
                val cells = row.select("td")
                if (cells.isNotEmpty()) {
                    val name = cells[0].text().trim()
                    if (name.isNotEmpty() && isVideoFile(name)) {
                        candidates.add(name)
                    }
                }
            }
            if (candidates.size > videoFiles.size) {
                videoFiles.clear()
                videoFiles.addAll(candidates)
            }
        }

        return if (videoFiles.isNotEmpty()) {
            Log.d(TAG, "extractVideoFileNamesFromDoc: found ${videoFiles.size} video files")
            videoFiles
        } else {
            Log.w(TAG, "extractVideoFileNamesFromDoc: no video files found")
            null
        }
    }

    private fun parseTorrentFileList(torrentBytes: ByteArray): List<String> {
        return parseTorrentFolders(torrentBytes).flatMap { it.videoFiles }
    }

    /**
     * Parse a .torrent file and group video files by their top-level folder.
     * Each folder becomes a TorrentFolder. Files in the root (no folder) go into
     * a folder named "" (empty string). This allows torrents that contain multiple
     * shows (each in its own subfolder) to be split into separate seasons.
     */
    private fun parseTorrentFolders(torrentBytes: ByteArray): List<TorrentFolder> {
        return try {
            val (root, _) = decodeBencode(torrentBytes, 0)
            val dict = root as? BencodeValue.BDict ?: return emptyList()

            val infoEntry = dict.entries.find { (k, _) ->
                k.bytes.contentEquals("info".toByteArray())
            } ?: return emptyList()

            val infoDict = infoEntry.second as? BencodeValue.BDict ?: return emptyList()

            val filesEntry = infoDict.entries.find { (k, _) ->
                k.bytes.contentEquals("files".toByteArray())
            }

            if (filesEntry != null) {
                val filesList = filesEntry.second as? BencodeValue.BList ?: return emptyList()
                // Collect all video files with their top-level folder
                val folderMap = linkedMapOf<String, MutableList<String>>()
                for (item in filesList.items) {
                    val fileDict = item as? BencodeValue.BDict ?: continue
                    val pathEntry = fileDict.entries.find { (k, _) ->
                        k.bytes.contentEquals("path".toByteArray())
                            || k.bytes.contentEquals("path.utf-8".toByteArray())
                    }
                    val pathParts = pathEntry?.second as? BencodeValue.BList ?: continue
                    val pathSegments = pathParts.items.mapNotNull {
                        (it as? BencodeValue.BString)?.let { s -> String(s.bytes) }
                    }
                    val fullPath = pathSegments.joinToString("/")
                    if (fullPath.isNotEmpty() && isVideoFile(fullPath)) {
                        // Top-level folder is the first path segment if there are multiple segments
                        val topFolder = if (pathSegments.size > 1) pathSegments[0] else ""
                        folderMap.getOrPut(topFolder) { mutableListOf() }.add(fullPath)
                    }
                }
                folderMap.map { (folder, files) -> TorrentFolder(folder, files) }
            } else {
                // Single-file torrent (no "files" key)
                val nameEntry = infoDict.entries.find { (k, _) ->
                    k.bytes.contentEquals("name".toByteArray())
                        || k.bytes.contentEquals("name.utf-8".toByteArray())
                }
                val name = nameEntry?.second as? BencodeValue.BString
                    ?: return emptyList()
                val nameStr = String(name.bytes)
                if (nameStr.isNotEmpty() && isVideoFile(nameStr)) {
                    listOf(TorrentFolder("", listOf(nameStr)))
                } else {
                    emptyList()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "parseTorrentFolders error: ${e.message}")
            emptyList()
        }
    }

    // ==================== LOAD (Detail Page) ====================

    override suspend fun load(url: String): LoadResponse? {
        if (url.contains("|")) return loadFromTorrentData(url)

        // Handle torrent detail pages (e.g. from documentary/category listings)
        // These use arabp_data URL parameter instead of pipe-delimited data
        if (url.contains("arabp_data=")) return loadFromTorrentDetailUrl(url)

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

            data class TorrentRowInfo(
                val epName: String,
                val displayName: String,
                val torrentId: String,
                val detailHref: String,
                val downloadHref: String,
                val magnetHref: String,
                val isFree: Boolean,
                val isExternal: Boolean,
                val baseData: String
            )

            val torrentInfos = rows.mapNotNull { row ->
                val nameLink = row.selectFirst("a[href*=torrent-details]") ?: return@mapNotNull null
                val epName = cleanTitleText(nameLink.text())
                val detailHref = nameLink.attr("href")
                val torrentId = DIGITS.find(detailHref.substringAfter("id="))?.value ?: return@mapNotNull null
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

                val baseData = "$torrentId|${toAbsoluteUrl(detailHref)}|${toAbsoluteUrl(downloadHref)}|$magnetHref|${if (isFree) "1" else "0"}|${if (isExternal) "1" else "0"}"

                TorrentRowInfo(epName, displayName, torrentId, detailHref, downloadHref, magnetHref, isFree, isExternal, baseData)
            }

            ensureLogin()

            data class SplitResult(
                val info: TorrentRowInfo,
                val folders: List<TorrentFolder>?,
                val fallbackFileNames: List<String>? = null  // From details page when .torrent download fails
            )

            // Process torrents SEQUENTIALLY instead of in parallel.
            // Previously used async(Dispatchers.IO) for all torrents at once, which caused
            // dozens of simultaneous HTTP requests that triggered IP blocking.
            // Sequential processing ensures the rate limiter actually controls pacing.
            val splitResults = if (torrentInfos.isNotEmpty()) {
                try {
                    torrentInfos.map { info ->
                        var folders: List<TorrentFolder>? = null
                        var fallbackFileNames: List<String>? = null
                        var detailDocCache: Document? = null
                        try {
                            if (!info.isExternal && info.downloadHref.isNotBlank()) {
                                // Check if we already resolved this download URL
                                var resolvedUrl = getCachedResolvedUrl(info.torrentId)
                                    ?: toAbsoluteUrl(info.downloadHref)
                                if (!resolvedUrl.contains("&f=")) {
                                    val detailPageUrl = toAbsoluteUrl(info.detailHref)
                                    val detailRequest = Request.Builder()
                                        .url(detailPageUrl)
                                        .headers(getAuthHeaders(referer = "$mainUrl/").toHeaders())
                                        .build()
                                    authClient.newCall(detailRequest).execute().use { resp ->
                                        val body = resp.body?.string() ?: ""
                                        detailDocCache = Jsoup.parse(body, detailPageUrl)
                                        val dlLink = detailDocCache!!.selectFirst("a[href*=download.php]")
                                            ?: detailDocCache!!.selectFirst("td#Title h1 a[href*=download.php]")
                                        if (dlLink != null) {
                                            resolvedUrl = toAbsoluteUrl(dlLink.attr("href"))
                                        }
                                    }
                                }
                                // Cache the resolved URL so loadLinks() doesn't need to re-fetch
                                if (resolvedUrl.contains("&f=")) {
                                    cacheResolvedUrl(info.torrentId, resolvedUrl)
                                }
                                if (resolvedUrl.contains("&f=")) {
                                    var dlResult = downloadTorrentFile(resolvedUrl)

                                    // Handle daily limit: thank uploader and retry once
                                    if (dlResult is TorrentDownloadResult.DailyLimitExceeded) {
                                        Log.w(TAG, "Daily limit hit for #${info.torrentId}, thanking and retrying...")
                                        thankUploader(info.torrentId, info.detailHref)
                                        dlResult = downloadTorrentFile(resolvedUrl)
                                    }

                                    if (dlResult is TorrentDownloadResult.Success) {
                                        cacheTorrent(info.torrentId, dlResult.bytes)
                                        val parsedFolders = parseTorrentFolders(dlResult.bytes)
                                        val totalVideos = parsedFolders.sumOf { it.videoFiles.size }
                                        Log.d(TAG, "Torrent #${info.torrentId} has ${totalVideos} video files in ${parsedFolders.size} folders")
                                        if (totalVideos > 1) folders = parsedFolders
                                    } else {
                                        // .torrent download failed — reuse cached detail doc to avoid extra request
                                        Log.w(TAG, "Torrent #${info.torrentId} download failed, trying details page fallback...")
                                        fallbackFileNames = if (detailDocCache != null)
                                            extractVideoFileNamesFromDoc(detailDocCache!!)
                                        else parseFileListFromDetailPage(info.detailHref)
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Torrent split failed for #${info.torrentId}: ${e.message}")
                            // Try details page fallback — reuse cached doc if available
                            try {
                                fallbackFileNames = if (detailDocCache != null)
                                    extractVideoFileNamesFromDoc(detailDocCache!!)
                                else parseFileListFromDetailPage(info.detailHref)
                            } catch (_: Exception) {}
                        }
                        SplitResult(info, folders, fallbackFileNames)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Sequential download failed, using fallback: ${e.message}")
                    torrentInfos.map { SplitResult(it, null) }
                }
            } else {
                emptyList()
            }

            val episodes = mutableListOf<Episode>()
            val seasonNamesList = mutableListOf<SeasonData>()
            var seasonNum = 1

            for (result in splitResults) {
                val info = result.info

                if (result.folders != null) {
                    val folders = result.folders
                    // Only treat folders as separate seasons if there are 2+ named folders
                    val hasMultipleFolders = folders.size > 1

                    if (hasMultipleFolders) {
                        // Each folder becomes its own season (each folder is a different show)
                        for (folder in folders) {
                            val seasonDisplayName = if (folder.folderName.isNotEmpty())
                                folder.folderName else info.epName
                            seasonNamesList.add(SeasonData(season = seasonNum, name = seasonDisplayName))

                            // Build a global file index map so loadLinks can find the right file
                            // We need the flat index across all video files for this torrent
                            val globalOffset = folders.takeWhile { it != folder }
                                .sumOf { it.videoFiles.size }

                            for ((localIdx, filePath) in folder.videoFiles.withIndex()) {
                                val fileName = filePath.substringAfterLast("/")
                                val epNameFromFile = fileName.substringBeforeLast(".")
                                    .replace(".", " ").replace("_", " ").trim()
                                val globalIdx = globalOffset + localIdx
                                val epData = "${info.baseData}|${globalIdx}"
                                episodes.add(newEpisode(epData, fix = false, initializer = {
                                    name = epNameFromFile
                                    season = seasonNum
                                    episode = localIdx + 1
                                    this.posterUrl = absPosterUrl
                                }))
                            }
                            seasonNum++
                        }
                    } else {
                        // Single folder with videos (or all in root) — treat as one season
                        seasonNamesList.add(SeasonData(season = seasonNum, name = info.epName))
                        val allFiles = folders.flatMap { it.videoFiles }
                        for ((idx, filePath) in allFiles.withIndex()) {
                            val fileName = filePath.substringAfterLast("/")
                            val epNameFromFile = fileName.substringBeforeLast(".")
                                .replace(".", " ").replace("_", " ").trim()
                            val epData = "${info.baseData}|${idx}"
                            episodes.add(newEpisode(epData, fix = false, initializer = {
                                name = epNameFromFile
                                season = seasonNum
                                episode = idx + 1
                                this.posterUrl = absPosterUrl
                            }))
                        }
                        seasonNum++
                    }
                } else if (result.fallbackFileNames != null && result.fallbackFileNames.size > 1) {
                    // .torrent download failed but we got file names from details page
                    val fileNames = result.fallbackFileNames
                    seasonNamesList.add(SeasonData(season = seasonNum, name = info.epName))
                    for ((idx, fileName) in fileNames.withIndex()) {
                        val epNameFromFile = fileName.substringBeforeLast(".")
                            .replace(".", " ").replace("_", " ").trim()
                        val epData = "${info.baseData}|${idx}"
                        episodes.add(newEpisode(epData, fix = false, initializer = {
                            name = epNameFromFile
                            season = seasonNum
                            episode = idx + 1
                            this.posterUrl = absPosterUrl
                        }))
                    }
                    seasonNum++
                } else {
                    seasonNamesList.add(SeasonData(season = seasonNum, name = info.epName))
                    episodes.add(newEpisode(info.baseData, fix = false, initializer = {
                        name = info.displayName
                        season = seasonNum
                        episode = 1
                        this.posterUrl = absPosterUrl
                    }))
                    seasonNum++
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

    // ==================== LOAD FROM TORRENT DETAIL URL ====================

    /**
     * Handle URLs from torrent category pages (documentaries, Asian, Latin, Turkish, etc.)
     * that use a real torrent-detail URL with an arabp_data query parameter containing
     * encoded torrent metadata. This follows the same anti-blocking pattern as the anime
     * listing pages: sequential processing, resolved URL caching, detail doc reuse.
     */
    private suspend fun loadFromTorrentDetailUrl(url: String): LoadResponse? {
        // Extract the actual detail page URL (strip the arabp_data parameter)
        val detailUrl = url.substringBefore("&arabp_data=")
        // Decode the torrent metadata
        val encodedData = url.substringAfter("arabp_data=", "")
        val torrentData = try {
            java.net.URLDecoder.decode(encodedData, "UTF-8")
        } catch (e: Exception) {
            Log.e(TAG, "loadFromTorrentDetailUrl: failed to decode arabp_data: ${e.message}")
            return null
        }

        // torrentData format: torrentId|downloadUrl|magnetUrl|isFree|isExternal
        val dataParts = torrentData.split("|")
        val torrentId = dataParts.getOrNull(0) ?: "0"
        val downloadUrl = dataParts.getOrNull(1) ?: ""
        val magnetUrl = dataParts.getOrNull(2) ?: ""
        val isFree = dataParts.getOrNull(3) == "1"
        val isExternal = dataParts.getOrNull(4) == "1"

        Log.d(TAG, "loadFromTorrentDetailUrl: id=$torrentId, free=$isFree, external=$isExternal, detailUrl=$detailUrl")

        // Build the full baseData in the same format as anime episodes use:
        // torrentId|detailUrl|downloadUrl|magnetUrl|isFree|isExternal
        val baseData = "$torrentId|$detailUrl|$downloadUrl|$magnetUrl|${if (isFree) "1" else "0"}|${if (isExternal) "1" else "0"}"

        var title = "Torrent #$torrentId"
        var posterUrl = ""
        var detailDocCache: Document? = null

        // Fetch the detail page for title and poster
        try {
            if (ensureLogin() && detailUrl.isNotBlank()) {
                val request = Request.Builder()
                    .url(toAbsoluteUrl(detailUrl))
                    .headers(getAuthHeaders(referer = "$mainUrl/").toHeaders())
                    .build()
                authClient.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: ""
                    detailDocCache = Jsoup.parse(body, toAbsoluteUrl(detailUrl))
                    title = detailDocCache!!.selectFirst("td#Title h1")?.text()?.trim() ?: title
                    posterUrl = detailDocCache!!.selectFirst("img.listing_poster")?.attr("src")
                        ?: detailDocCache!!.selectFirst("img[src*=poster]")?.attr("src")
                        ?: ""
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "loadFromTorrentDetailUrl detail fetch error: ${e.message}")
        }

        val absPosterUrl = toAbsoluteUrl(posterUrl)
        val pageTvType = tvTypeFromTitle(title)

        // Handle external torrents with magnet links
        if (isExternal && magnetUrl.startsWith("magnet:")) {
            val epData = "$baseData|0"
            return newMovieLoadResponse(title, url, pageTvType.toMovieType(), epData) {
                this.posterUrl = absPosterUrl
                this.posterHeaders = imageHeaders
            }
        }

        // Download and parse the .torrent file — sequential with anti-blocking
        val folderResult = try {
            if (!isExternal && ensureLogin()) {
                // Check resolved URL cache first to avoid re-fetching detail page
                var resolvedUrl = getCachedResolvedUrl(torrentId)
                    ?: toAbsoluteUrl(downloadUrl)

                // If we don't have a valid download URL with &f=, try to find one
                if (resolvedUrl.isBlank() || !resolvedUrl.contains("&f=")) {
                    // Reuse cached detail doc if we already fetched it above
                    if (detailDocCache != null) {
                        val dlLink = detailDocCache!!.selectFirst("a[href*=download.php]")
                            ?: detailDocCache!!.selectFirst("td#Title h1 a[href*=download.php]")
                        if (dlLink != null) {
                            resolvedUrl = toAbsoluteUrl(dlLink.attr("href"))
                        }
                    } else {
                        val detailPageUrl = toAbsoluteUrl(detailUrl)
                        val detailRequest = Request.Builder()
                            .url(detailPageUrl)
                            .headers(getAuthHeaders(referer = "$mainUrl/").toHeaders())
                            .build()
                        authClient.newCall(detailRequest).execute().use { resp ->
                            val body = resp.body?.string() ?: ""
                            detailDocCache = Jsoup.parse(body, detailPageUrl)
                            val dlLink = detailDocCache!!.selectFirst("a[href*=download.php]")
                                ?: detailDocCache!!.selectFirst("td#Title h1 a[href*=download.php]")
                            if (dlLink != null) {
                                resolvedUrl = toAbsoluteUrl(dlLink.attr("href"))
                            }
                        }
                    }
                }
                // Cache the resolved download URL
                if (resolvedUrl.contains("&f=")) {
                    cacheResolvedUrl(torrentId, resolvedUrl)
                }
                if (resolvedUrl.contains("&f=")) {
                    var dlResult = downloadTorrentFile(resolvedUrl)

                    // Handle daily limit: thank uploader and retry once
                    if (dlResult is TorrentDownloadResult.DailyLimitExceeded) {
                        Log.w(TAG, "loadFromTorrentDetailUrl: Daily limit hit for #$torrentId, thanking and retrying...")
                        thankUploader(torrentId, detailUrl)
                        dlResult = downloadTorrentFile(resolvedUrl)
                    }

                    if (dlResult is TorrentDownloadResult.Success) {
                        cacheTorrent(torrentId, dlResult.bytes)
                        val folders = parseTorrentFolders(dlResult.bytes)
                        val totalVideos = folders.sumOf { it.videoFiles.size }
                        Log.d(TAG, "loadFromTorrentDetailUrl: Torrent #$torrentId has ${totalVideos} video files in ${folders.size} folders")
                        if (totalVideos >= 1) {
                            Pair(folders, baseData)
                        } else null
                    } else null
                } else null
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "loadFromTorrentDetailUrl download failed: ${e.message}")
            null
        }

        // Fallback: reuse cached detail doc to avoid extra HTTP request
        val fallbackFileNames = if (folderResult == null && !isExternal) {
            if (detailDocCache != null) extractVideoFileNamesFromDoc(detailDocCache!!)
            else parseFileListFromDetailPage(detailUrl)
        } else null

        if (folderResult != null) {
            val (folders, baseDataStr) = folderResult
            val episodes = mutableListOf<Episode>()
            val seasonNamesList = mutableListOf<SeasonData>()
            val hasMultipleFolders = folders.size > 1

            var seasonNum = 1
            if (hasMultipleFolders) {
                for (folder in folders) {
                    val seasonDisplayName = if (folder.folderName.isNotEmpty())
                        folder.folderName else title
                    seasonNamesList.add(SeasonData(season = seasonNum, name = seasonDisplayName))
                    val globalOffset = folders.takeWhile { it != folder }
                        .sumOf { it.videoFiles.size }
                    for ((localIdx, filePath) in folder.videoFiles.withIndex()) {
                        val fileName = filePath.substringAfterLast("/")
                        val epNameFromFile = fileName.substringBeforeLast(".")
                            .replace(".", " ").replace("_", " ").trim()
                        val globalIdx = globalOffset + localIdx
                        val epData = "$baseDataStr|${globalIdx}"
                        episodes.add(newEpisode(epData, fix = false, initializer = {
                            name = epNameFromFile
                            season = seasonNum
                            episode = localIdx + 1
                            this.posterUrl = absPosterUrl
                        }))
                    }
                    seasonNum++
                }
            } else {
                seasonNamesList.add(SeasonData(season = 1, name = title))
                val allFiles = folders.flatMap { it.videoFiles }
                for ((idx, filePath) in allFiles.withIndex()) {
                    val fileName = filePath.substringAfterLast("/")
                    val epNameFromFile = fileName.substringBeforeLast(".")
                        .replace(".", " ").replace("_", " ").trim()
                    val epData = "$baseDataStr|${idx}"
                    episodes.add(newEpisode(epData, fix = false, initializer = {
                        name = epNameFromFile
                        season = 1
                        episode = idx + 1
                        this.posterUrl = absPosterUrl
                    }))
                }
            }

            return newTvSeriesLoadResponse(title, url, pageTvType.toSeriesType(), episodes) {
                this.posterUrl = absPosterUrl
                this.posterHeaders = imageHeaders
                this.seasonNames = seasonNamesList
            }
        }

        // Fallback: use file names from details page
        if (fallbackFileNames != null && fallbackFileNames.size > 1) {
            val episodes = mutableListOf<Episode>()
            for ((idx, fileName) in fallbackFileNames.withIndex()) {
                val epNameFromFile = fileName.substringBeforeLast(".")
                    .replace(".", " ").replace("_", " ").trim()
                val epData = "$baseData|${idx}"
                episodes.add(newEpisode(epData, fix = false, initializer = {
                    name = epNameFromFile
                    season = 1
                    episode = idx + 1
                    this.posterUrl = absPosterUrl
                }))
            }
            return newTvSeriesLoadResponse(title, url, pageTvType.toSeriesType(), episodes) {
                this.posterUrl = absPosterUrl
                this.posterHeaders = imageHeaders
                this.seasonNames = listOf(SeasonData(season = 1, name = title))
            }
        }

        // Single episode fallback
        val epData = "$baseData|0"
        return newTvSeriesLoadResponse(title, url, pageTvType.toSeriesType(), listOf(
            newEpisode(epData, fix = false, initializer = {
                name = title
                season = 1
                episode = 1
                this.posterUrl = absPosterUrl
            })
        )) {
            this.posterUrl = absPosterUrl
            this.posterHeaders = imageHeaders
            this.seasonNames = listOf(SeasonData(season = 1, name = title))
        }
    }

    // ==================== LOAD FROM TORRENT DATA ====================

    private suspend fun loadFromTorrentData(data: String): LoadResponse? {
        val dataParts = data.split("|")
        val parts = dataParts.take(6)
        val torrentId = parts.getOrNull(0) ?: "0"
        val detailUrl = parts.getOrNull(1) ?: ""
        val downloadUrl = parts.getOrNull(2) ?: ""
        val magnetUrl = parts.getOrNull(3) ?: ""
        val isFree = parts.getOrNull(4) == "1"
        val isExternal = parts.getOrNull(5) == "1"

        var title = "Torrent #$torrentId"
        var posterUrl = ""
        var detailDocCache: Document? = null

        try {
            if (ensureLogin() && detailUrl.isNotBlank()) {
                val request = Request.Builder()
                    .url(toAbsoluteUrl(detailUrl))
                    .headers(getAuthHeaders(referer = "$mainUrl/").toHeaders())
                    .build()

                authClient.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: ""
                    detailDocCache = Jsoup.parse(body, toAbsoluteUrl(detailUrl))
                    title = detailDocCache!!.selectFirst("td#Title h1")?.text()?.trim() ?: title
                    posterUrl = detailDocCache!!.selectFirst("img.listing_poster")?.attr("src") ?: ""
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

        if (isExternal && magnetUrl.startsWith("magnet:")) {
            return newMovieLoadResponse(title, data, pageTvType.toMovieType(), data) {
                this.posterUrl = absPosterUrl
                this.posterHeaders = imageHeaders
            }
        }

        val folderResult = try {
            if (!isExternal && ensureLogin()) {
                // Check resolved URL cache first to avoid re-fetching detail page
                var resolvedUrl = getCachedResolvedUrl(torrentId)
                    ?: toAbsoluteUrl(downloadUrl)
                if (resolvedUrl.isBlank() || !resolvedUrl.contains("&f=")) {
                    // Reuse cached detail doc if we already fetched it above
                    if (detailDocCache != null) {
                        val dlLink = detailDocCache!!.selectFirst("a[href*=download.php]")
                            ?: detailDocCache!!.selectFirst("td#Title h1 a[href*=download.php]")
                        if (dlLink != null) {
                            resolvedUrl = toAbsoluteUrl(dlLink.attr("href"))
                        }
                    } else {
                        val detailPageUrl = toAbsoluteUrl(detailUrl)
                        val detailRequest = Request.Builder()
                            .url(detailPageUrl)
                            .headers(getAuthHeaders(referer = "$mainUrl/").toHeaders())
                            .build()
                        authClient.newCall(detailRequest).execute().use { resp ->
                            val body = resp.body?.string() ?: ""
                            detailDocCache = Jsoup.parse(body, detailPageUrl)
                            val dlLink = detailDocCache!!.selectFirst("a[href*=download.php]")
                                ?: detailDocCache!!.selectFirst("td#Title h1 a[href*=download.php]")
                            if (dlLink != null) {
                                resolvedUrl = toAbsoluteUrl(dlLink.attr("href"))
                            }
                        }
                    }
                }
                // Cache the resolved download URL for loadLinks()
                if (resolvedUrl.contains("&f=")) {
                    cacheResolvedUrl(torrentId, resolvedUrl)
                }
                if (resolvedUrl.contains("&f=")) {
                    var dlResult = downloadTorrentFile(resolvedUrl)

                    // Handle daily limit: thank uploader and retry once
                    if (dlResult is TorrentDownloadResult.DailyLimitExceeded) {
                        Log.w(TAG, "loadFromTorrentData: Daily limit hit for #$torrentId, thanking and retrying...")
                        thankUploader(torrentId, detailUrl)
                        dlResult = downloadTorrentFile(resolvedUrl)
                    }

                    if (dlResult is TorrentDownloadResult.Success) {
                        cacheTorrent(torrentId, dlResult.bytes)
                        val folders = parseTorrentFolders(dlResult.bytes)
                        val totalVideos = folders.sumOf { it.videoFiles.size }
                        Log.d(TAG, "loadFromTorrentData: Torrent #$torrentId has ${totalVideos} video files in ${folders.size} folders")
                        if (totalVideos > 1) {
                            Pair(folders, parts.joinToString("|"))
                        } else null
                    } else null
                } else null
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "loadFromTorrentData split failed: ${e.message}")
            null
        }

        // Fallback: reuse cached detail doc to avoid extra HTTP request
        val fallbackFileNames = if (folderResult == null && !isExternal) {
            if (detailDocCache != null) extractVideoFileNamesFromDoc(detailDocCache!!)
            else parseFileListFromDetailPage(detailUrl)
        } else null

        if (folderResult != null) {
            val (folders, baseData) = folderResult
            val episodes = mutableListOf<Episode>()
            val seasonNamesList = mutableListOf<SeasonData>()
            val hasMultipleFolders = folders.size > 1

            var seasonNum = 1
            if (hasMultipleFolders) {
                // Each folder becomes its own season
                for (folder in folders) {
                    val seasonDisplayName = if (folder.folderName.isNotEmpty())
                        folder.folderName else title
                    seasonNamesList.add(SeasonData(season = seasonNum, name = seasonDisplayName))
                    val globalOffset = folders.takeWhile { it != folder }
                        .sumOf { it.videoFiles.size }
                    for ((localIdx, filePath) in folder.videoFiles.withIndex()) {
                        val fileName = filePath.substringAfterLast("/")
                        val epNameFromFile = fileName.substringBeforeLast(".")
                            .replace(".", " ").replace("_", " ").trim()
                        val globalIdx = globalOffset + localIdx
                        val epData = "$baseData|${globalIdx}"
                        episodes.add(newEpisode(epData, fix = false, initializer = {
                            name = epNameFromFile
                            season = seasonNum
                            episode = localIdx + 1
                            this.posterUrl = absPosterUrl
                        }))
                    }
                    seasonNum++
                }
            } else {
                // Single folder or all files in root — one season
                seasonNamesList.add(SeasonData(season = 1, name = title))
                val allFiles = folders.flatMap { it.videoFiles }
                for ((idx, filePath) in allFiles.withIndex()) {
                    val fileName = filePath.substringAfterLast("/")
                    val epNameFromFile = fileName.substringBeforeLast(".")
                        .replace(".", " ").replace("_", " ").trim()
                    val epData = "$baseData|${idx}"
                    episodes.add(newEpisode(epData, fix = false, initializer = {
                        name = epNameFromFile
                        season = 1
                        episode = idx + 1
                        this.posterUrl = absPosterUrl
                    }))
                }
            }

            return newTvSeriesLoadResponse(title, data, pageTvType.toSeriesType(), episodes) {
                this.posterUrl = absPosterUrl
                this.posterHeaders = imageHeaders
                this.seasonNames = seasonNamesList
            }
        }

        // Fallback: use file names from details page when .torrent download failed
        if (fallbackFileNames != null && fallbackFileNames.size > 1) {
            val baseData = parts.joinToString("|")
            val episodes = mutableListOf<Episode>()
            for ((idx, fileName) in fallbackFileNames.withIndex()) {
                val epNameFromFile = fileName.substringBeforeLast(".")
                    .replace(".", " ").replace("_", " ").trim()
                val epData = "$baseData|${idx}"
                episodes.add(newEpisode(epData, fix = false, initializer = {
                    name = epNameFromFile
                    season = 1
                    episode = idx + 1
                    this.posterUrl = absPosterUrl
                }))
            }
            return newTvSeriesLoadResponse(title, data, pageTvType.toSeriesType(), episodes) {
                this.posterUrl = absPosterUrl
                this.posterHeaders = imageHeaders
                this.seasonNames = listOf(SeasonData(season = 1, name = title))
            }
        }

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
            this.seasonNames = listOf(SeasonData(season = 1, name = title))
        }
    }

    // ==================== LOCAL TORRENT SERVER ====================

    private var localServerSocket: ServerSocket? = null
    private var localServerPort: Int = 0
    private var localServerThread: Thread? = null
    @Volatile
    private var servedTorrentBytes: ByteArray? = null
    @Volatile
    private var lastServerActivity: Long = 0

    private fun startLocalTorrentServer(bytes: ByteArray): String? {
        servedTorrentBytes = bytes
        lastServerActivity = System.currentTimeMillis()

        if (localServerSocket != null && localServerPort > 0 && localServerThread?.isAlive == true) {
            Log.d(TAG, "Local torrent server: reusing existing server on port $localServerPort")
            return "http://127.0.0.1:$localServerPort/torrent.torrent"
        }

        stopLocalTorrentServer()

        try {
            val socket = ServerSocket(0)
            socket.reuseAddress = true
            socket.soTimeout = 5000
            localServerSocket = socket
            localServerPort = socket.localPort

            localServerThread = Thread({
                try {
                    while (!Thread.currentThread().isInterrupted) {
                        if (System.currentTimeMillis() - lastServerActivity > 90_000) {
                            Log.d(TAG, "Local torrent server: shutting down after 90s inactivity")
                            break
                        }
                        try {
                            val client = socket.accept()
                            lastServerActivity = System.currentTimeMillis()
                            handleLocalServerRequest(client)
                        } catch (_: java.net.SocketTimeoutException) {
                            continue
                        }
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Local torrent server stopped: ${e.message}")
                } finally {
                    stopLocalTorrentServer()
                }
            }, "ArabpLocalServer")
            localServerThread?.start()

            Log.d(TAG, "Local torrent server started on port $localServerPort")
            return "http://127.0.0.1:$localServerPort/torrent.torrent"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start local torrent server: ${e.message}")
            return null
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
                val response = "HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n"
                output.write(response.toByteArray())
            } else {
                val response = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/x-bittorrent\r\n" +
                        "Content-Length: ${bytes.size}\r\n" +
                        "Connection: close\r\n\r\n"
                output.write(response.toByteArray())
                output.write(bytes)
                Log.d(TAG, "Local server: served .torrent file (${bytes.size} bytes)")
            }
            output.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Local server request error: ${e.message}")
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

    // ==================== LOAD LINKS ====================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val dataParts = data.split("|")
        val parts = dataParts.take(6)
        val torrentId = parts.getOrNull(0) ?: "0"
        val detailUrl = parts.getOrNull(1) ?: ""
        val downloadUrl = parts.getOrNull(2) ?: ""
        val magnetUrl = parts.getOrNull(3) ?: ""
        val isFree = parts.getOrNull(4) == "1"
        val isExternal = parts.getOrNull(5) == "1"
        val fileIndex = dataParts.getOrNull(6)?.toIntOrNull()

        Log.d(TAG, "loadLinks: id=$torrentId, free=$isFree, external=$isExternal, fileIndex=$fileIndex")

        return try {
            if (!ensureLogin()) {
                Log.e(TAG, "Cannot load links: login failed")
                return false
            }

            if (cachedPasskey == null) {
                fetchPasskeyFromWebsite()
            }

            var foundLink = false

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

            if (!isFree) {
                val thankDetailUrl = if (detailUrl.isNotBlank()) detailUrl
                    else "$mainUrl/index.php?page=torrent-details&id=$torrentId"
                val thanked = thankUploader(torrentId, thankDetailUrl)
                Log.d(TAG, "loadLinks: thank uploader result = $thanked for torrent $torrentId")
            }

            val cachedBytes = getCachedTorrent(torrentId)
            if (cachedBytes != null) {
                Log.d(TAG, "Using cached .torrent for #$torrentId (${cachedBytes.size} bytes)")
                foundLink = handleTorrentDownloadResult(
                    TorrentDownloadResult.Success(cachedBytes),
                    "", callback, fileIndex
                )
                return foundLink
            }

            // Check resolved URL cache first to avoid re-fetching detail page
            var resolvedDownloadUrl = getCachedResolvedUrl(torrentId) ?: downloadUrl

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
                            // Cache the resolved URL for future calls
                            cacheResolvedUrl(torrentId, resolvedDownloadUrl)
                        }

                        val magnetEl = detailDoc.selectFirst("a[href^=magnet:]")
                        if (magnetEl != null) {
                            val pageMagnet = magnetEl.attr("href")
                            if (pageMagnet.startsWith("magnet:")) {
                                callback(
                                    newExtractorLink(
                                        source = this.name,
                                        name = "${this.name} (Magnet)",
                                        url = pageMagnet,
                                        type = ExtractorLinkType.MAGNET
                                    )
                                )
                                foundLink = true
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to fetch detail page: ${e.message}")
                }
            }

            if (resolvedDownloadUrl.isNotBlank() && resolvedDownloadUrl.contains("&f=")) {
                try {
                    val result = downloadTorrentFile(resolvedDownloadUrl)
                    if (result is TorrentDownloadResult.Success) {
                        cacheTorrent(torrentId, result.bytes)
                    }
                    foundLink = handleTorrentDownloadResult(result, resolvedDownloadUrl, callback, fileIndex) || foundLink
                } catch (e: Exception) {
                    Log.e(TAG, "Magnet conversion error: ${e.message}")
                }
            }

            foundLink
        } catch (e: Exception) {
            Log.e(TAG, "loadLinks Error: ${e.message}")
            false
        }
    }

    private suspend fun handleTorrentDownloadResult(
        result: TorrentDownloadResult,
        downloadUrl: String,
        callback: (ExtractorLink) -> Unit,
        fileIndex: Int? = null
    ): Boolean {
        var foundLink = false

        when (result) {
            is TorrentDownloadResult.Success -> {
                Log.d(TAG, "Downloaded .torrent: ${result.bytes.size} bytes")

                val localUrl = startLocalTorrentServer(result.bytes)
                if (localUrl != null) {
                    val torrentUrl = if (fileIndex != null) "$localUrl&file_index=$fileIndex" else localUrl
                    Log.d(TAG, "Serving .torrent via local server: $torrentUrl")
                    callback(
                        newExtractorLink(
                            source = this.name,
                            name = "${this.name} (Torrent)",
                            url = torrentUrl,
                            type = ExtractorLinkType.TORRENT
                        )
                    )
                    foundLink = true
                }

                val magnet = torrentToMagnet(result.bytes)
                if (magnet != null) {
                    Log.d(TAG, "Generated magnet: ${magnet.take(150)}...")
                    callback(
                        newExtractorLink(
                            source = this.name,
                            name = "${this.name} (Magnet)",
                            url = magnet,
                            type = ExtractorLinkType.MAGNET
                        )
                    )
                    foundLink = true
                }
            }
            is TorrentDownloadResult.DailyLimitExceeded -> {
                Log.w(TAG, "DAILY DOWNLOAD LIMIT EXCEEDED, thanking and retrying...")
                val retryResult = downloadTorrentFile(downloadUrl)
                when (retryResult) {
                    is TorrentDownloadResult.Success -> {
                        Log.d(TAG, "Retry succeeded!")

                        val localUrl = startLocalTorrentServer(retryResult.bytes)
                        if (localUrl != null) {
                            val torrentUrl = if (fileIndex != null) "$localUrl&file_index=$fileIndex" else localUrl
                            callback(
                                newExtractorLink(
                                    source = this.name,
                                    name = "${this.name} (Torrent)",
                                    url = torrentUrl,
                                    type = ExtractorLinkType.TORRENT
                                )
                            )
                            foundLink = true
                        }

                        val magnet = torrentToMagnet(retryResult.bytes)
                        if (magnet != null) {
                            callback(
                                newExtractorLink(
                                    source = this.name,
                                    name = "${this.name} (Magnet)",
                                    url = magnet,
                                    type = ExtractorLinkType.MAGNET
                                )
                            )
                            foundLink = true
                        }
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
                    }
                }
            }
            is TorrentDownloadResult.NotLoggedIn -> {
                isLoggedIn = false
                Log.e(TAG, "Session expired")
            }
            is TorrentDownloadResult.Error -> {
                Log.e(TAG, "Download error: ${result.message}")
            }
        }

        return foundLink
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

            for (url in trackerUrls) {
                val match = PASSKEY_REGEX.find(url)
                if (match != null) {
                    cachedPasskey = match.groupValues[1]
                    Log.d(TAG, "torrentToMagnet: extracted passkey from announce URL")
                    break
                }
            }

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

            var pos = 1

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

            firstByte in '0'..'9' -> {
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

    private fun cleanTitleText(text: String): String {
        return text
            .replace(Regex("<[^>]*>"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}