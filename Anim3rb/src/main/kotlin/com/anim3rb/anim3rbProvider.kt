package com.anime3rb

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class Anime3rb(val context: Context) : MainAPI() {
    override var mainUrl = "https://anime3rb.com"
    override var name = "Anim3rbtest"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    companion object {
        private var savedCookies: String = ""
        private const val TAG = "Anime3rb_Log"
        private val NON_DIGITS = Regex("[^0-9]")
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        private val TITLE_EP_REGEX = Regex("الحلقة \\d+")
    }

    private fun toAbsoluteUrl(url: String): String {
        return when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            else -> "$mainUrl/$url"
        }
    }

    private suspend fun getDocumentSmart(url: String): Document? {
        val result = loadVisibleWebViewCheck(url)

        return when (result) {
            is SmartResult.Success -> result.document
            is SmartResult.NeedsCaptcha -> {
               val activity = this.context as? Activity
                CloudflareSolver.solve(activity, url, USER_AGENT)
            }
            else -> null
        }
    }

    sealed class SmartResult {
        data class Success(val document: Document) : SmartResult()
        object NeedsCaptcha : SmartResult()
        object Error : SmartResult()
    }

    private suspend fun loadVisibleWebViewCheck(url: String): SmartResult {
        return suspendCoroutine { continuation ->
            Handler(Looper.getMainLooper()).post {
                val activity = this.context as? Activity
                if (activity == null || activity.isFinishing) {
                    continuation.resume(SmartResult.Error)
                    return@post
                }

                val dialog = Dialog(activity)
                dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
                dialog.setCancelable(false)

                dialog.window?.addFlags(
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                )

                dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
                dialog.window?.setDimAmount(0f)

                val params = WindowManager.LayoutParams()
                params.copyFrom(dialog.window?.attributes)
                params.width = 1
                params.height = 1
                params.gravity = Gravity.TOP or Gravity.START
                params.x = -10 // خارج الشاشة
                params.y = -10 // خارج الشاشة
                dialog.window?.attributes = params

                val webView = WebView(activity)
                dialog.setContentView(
                    webView,
                    ViewGroup.LayoutParams(1, 1) // حجم 1x1 بكسل
                )

                try {
                    webView.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        userAgentString = USER_AGENT
                        blockNetworkImage = true // لا نحتاج الصور في الفحص المخفي
                    }
                } catch (_: Exception) {}

                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                cookieManager.setAcceptThirdPartyCookies(webView, true)

                var isFinished = false
                val handler = Handler(Looper.getMainLooper())

                fun finish(result: SmartResult) {
                    if (isFinished) return
                    isFinished = true
                    handler.removeCallbacksAndMessages(null)
                    try { if (dialog.isShowing) dialog.dismiss() } catch (_: Exception) {}
                    try { webView.destroy() } catch (_: Exception) {}

                    if (result is SmartResult.Success) {
                        cookieManager.flush()
                        savedCookies = cookieManager.getCookie(url) ?: ""
                    }
                    continuation.resume(result)
                }

                val poller = object : Runnable {
                    override fun run() {
                        if (isFinished) return
                        val jsCheck = """
                        (function() {
                            const html = document.documentElement.innerHTML;
                            if (html.includes('challenge-platform') || html.includes('cf-turnstile') || document.getElementById('cf-wrapper')) return 'CAPTCHA';
                            if (document.querySelector('.video-card, .main-content, .title-card')) return 'SUCCESS::' + html;
                            return 'POLLING';
                        })();
                        """
                        webView.evaluateJavascript(jsCheck) { result ->
                            if (isFinished) return@evaluateJavascript
                            val cleanResult = result?.removeSurrounding("\"")
                            when {
                                cleanResult == "CAPTCHA" -> finish(SmartResult.NeedsCaptcha)
                                cleanResult?.startsWith("SUCCESS::") == true -> {
                                    val html = cleanResult.substringAfter("SUCCESS::")
                                    val cleanHtml = html.replace("\\u003C", "<").replace("\\u003E", ">").replace("\\\"", "\"").replace("\\\\", "\\")
                                    finish(SmartResult.Success(Jsoup.parse(cleanHtml)))
                                }
                                else -> handler.postDelayed(this, 1000)
                            }
                        }
                    }
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: android.net.http.SslError?) {
                        handler?.proceed()
                    }
                }

                try {
                    dialog.show()
                    webView.loadUrl(url)
                    handler.postDelayed(poller, 1000)
                    handler.postDelayed({ if (!isFinished) finish(SmartResult.Error) }, 20000)
                } catch (e: Exception) {
                    finish(SmartResult.Error)
                }
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val isHomepage = request.data == "$mainUrl/"

        if (isHomepage) {
            return getHomePage(page, request)
        }

        // All other categories (anime list, genres, filters, etc.) use the list page handler
        return getAnimeListPage(page, request)
    }

    private suspend fun getHomePage(page: Int, request: MainPageRequest): HomePageResponse? {
        val doc = getDocumentSmart(request.data) ?: return null
        val homeSets = mutableListOf<HomePageList>()
        try {
            doc.select("h2:contains(الأنميات المثبتة)").firstOrNull()?.let { header ->
                val list = header.parent()?.parent()?.parent()
                    ?.select(".glide__slide:not(.glide__slide--clone) a.video-card")
                    ?.mapNotNull { toSearchResult(it) }
                if (!list.isNullOrEmpty()) homeSets.add(HomePageList("الأنميات المثبتة", list))
            }
            val latest = doc.select("#videos a.video-card").mapNotNull { toSearchResult(it) }
            if (latest.isNotEmpty()) homeSets.add(HomePageList("أحدث الحلقات", latest))

            doc.select("h3:contains(آخر الأنميات المضافة)").firstOrNull()?.let { header ->
                val list = header.parent()?.parent()?.parent()
                    ?.select(".glide__slide:not(.glide__slide--clone) a.video-card")
                    ?.mapNotNull { toSearchResult(it) }
                if (!list.isNullOrEmpty()) homeSets.add(HomePageList("آخر الأنميات المضافة", list))
            }
        } catch (e: Exception) { Log.e(TAG, "MainPage Error: ${e.message}") }
        return newHomePageResponse(homeSets)
    }

    private suspend fun getAnimeListPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val baseUrl = request.data
        val doc: Document?

        // For page 1, load the page normally via WebView
        // For page > 1, try the Livewire API first (more reliable than ?page=N)
        if (page == 1) {
            doc = getDocumentSmart(baseUrl)
        } else {
            doc = getAnimeListPageViaLivewire(baseUrl, page)
                // Fallback to URL-based pagination if Livewire fails
                ?: getDocumentSmart(buildPaginatedUrl(baseUrl, page))
        }

        if (doc == null) return null

        val homeSets = mutableListOf<HomePageList>()
        try {
            val finalItems = parseAnimeListItems(doc)

            if (finalItems.isNotEmpty()) {
                homeSets.add(HomePageList(request.name, finalItems))
            }

            // Simple and reliable: if we got items, assume there might be more pages.
            // If we got 0 items, we've gone past the last page.
            // The site shows ~20 items per page, so if we got items, hasNextPage = true.
            val hasNextPage = finalItems.isNotEmpty()

            return newHomePageResponse(homeSets, hasNextPage)
        } catch (e: Exception) {
            Log.e(TAG, "AnimeList Error: ${e.message}")
        }
        return newHomePageResponse(homeSets)
    }

    private fun parseAnimeListItems(doc: Document): List<SearchResponse> {
        // Primary: select only the image+title link from each title-card (not the details link)
        val animeItems = doc.select(".title-card > a[href*=/titles/]:first-child")
            .mapNotNull { toAnimeListSearchResult(it) }

        // Fallback: try alternative selectors if the primary ones don't match
        return if (animeItems.isEmpty()) {
            doc.select("a[href*=/titles/]").filter { el ->
                el.selectFirst("img") != null && el.selectFirst("h2, h3, h4") != null
            }.mapNotNull { toAnimeListSearchResult(it) }
                .distinctBy { it.url }
        } else {
            animeItems.distinctBy { it.url }
        }
    }

    /**
     * Navigate to a specific page using the Livewire API.
     * This is more reliable than ?page=N because the site uses Livewire
     * for client-side pagination and the query param may not be respected.
     */
    private suspend fun getAnimeListPageViaLivewire(baseUrl: String, targetPage: Int): Document? {
        return try {
            // First, load the base page to get CSRF token and Livewire snapshot
            val firstPageDoc = getDocumentSmart(baseUrl) ?: return null

            val scriptTag = firstPageDoc.selectFirst("script[src*=livewire.min.js]")
            val csrfToken = scriptTag?.attr("data-csrf") ?: return null

            // Find the Livewire component for the titles list
            val listComponent = firstPageDoc.select("[wire\\:id]").firstOrNull {
                it.attr("wire:snapshot").contains("index-titles") ||
                it.attr("wire:snapshot").contains("type_slug") ||
                it.attr("wire:snapshot").contains("paginators")
            } ?: firstPageDoc.select("[wire\\:id]").firstOrNull() ?: return null

            val snapshotRaw = listComponent.attr("wire:snapshot")
            val snapshotStr = org.jsoup.parser.Parser.unescapeEntities(snapshotRaw, true)
            val wireId = listComponent.attr("wire:id")

            val headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Accept" to "*/*",
                "Content-Type" to "application/json",
                "Origin" to mainUrl,
                "Referer" to baseUrl,
                "Cookie" to savedCookies,
                "X-Livewire" to ""
            )

            val payload = mapOf(
                "_token" to csrfToken,
                "components" to listOf(
                    mapOf(
                        "snapshot" to snapshotStr,
                        "updates" to emptyMap<String, Any>(),
                        "calls" to listOf(
                            mapOf(
                                "method" to "gotoPage",
                                "params" to listOf(targetPage.toString(), "page"),
                                "path" to ""
                            )
                        )
                    )
                )
            )

            val response = app.post("$mainUrl/livewire/update", headers = headers, json = payload)
            if (response.code != 200) return null

            val responseJson = AppUtils.parseJson<Map<String, Any>>(response.text)
            val components = responseJson["components"] as? List<Map<String, Any>> ?: return null
            val effects = components.firstOrNull()?.get("effects") as? Map<String, Any> ?: return null

            // The response can contain HTML in "html" or dirty components in "dirty"
            val htmlContent = effects["html"] as? String ?: return null

            Jsoup.parse(htmlContent)
        } catch (e: Exception) {
            Log.e(TAG, "Livewire Pagination Error: ${e.message}")
            null
        }
    }

    private fun buildPaginatedUrl(baseUrl: String, page: Int): String {
        if (page <= 1) return baseUrl

        // Handle URLs that already have query parameters (e.g. ?year=2024, ?season=spring&year=2025)
        return if (baseUrl.contains("?")) {
            "$baseUrl&page=$page"
        } else {
            "$baseUrl?page=$page"
        }
    }

    private fun toAnimeListSearchResult(element: Element): SearchResponse? {
        return try {
            val href = toAbsoluteUrl(element.attr("href"))

            // Try multiple selectors for the title
            val rawTitle = element.selectFirst("h2.title-name")?.text()
                ?: element.selectFirst("h2")?.text()
                ?: element.selectFirst("h3")?.text()
                ?: element.selectFirst("h4")?.text()
                ?: return null
            val title = cleanTitleText(rawTitle)

            val posterUrl = element.selectFirst("img")?.attr("src") ?: ""

            // Try to extract episode count from badges
            val epText = element.select(".badge").map { it.text() }.find {
                it.contains("حلقة") || it.matches(Regex(".*\\d+.*حلقات.*"))
            }
            val episodeNum = epText?.filter { it.isDigit() }?.toIntOrNull()

            // Determine type from badges or title
            val typeText = element.select(".badge").map { it.text() }.joinToString(" ")
            val tvType = when {
                typeText.contains("Movie", ignoreCase = true) || typeText.contains("فيلم") -> TvType.AnimeMovie
                typeText.contains("OVA", ignoreCase = true) -> TvType.OVA
                else -> TvType.Anime
            }

            newAnimeSearchResponse(title, href, tvType) {
                this.posterUrl = posterUrl
                if (episodeNum != null) addDubStatus(false, episodeNum)
            }
        } catch (e: Exception) {
            Log.e(TAG, "toAnimeListSearchResult Error: ${e.message}")
            null
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/" to "الرئيسية",

        // --- تصنيف حسب النوع (By Type) ---
        "$mainUrl/titles/list" to "قائمة الانمي",
        "$mainUrl/titles/list/tv" to "مسلسلات",
        "$mainUrl/titles/list/movie" to "أفلام",
        "$mainUrl/titles/list/ova" to "أوفا",
        "$mainUrl/titles/list/ona" to "أونا",
        "$mainUrl/titles/list/special" to "حلقات خاصة",
        "$mainUrl/titles/list/tv-special" to "حلقات تلفزيونية خاصة",

        // --- تصنيف حسب التصنيف (By Genre) ---
        "$mainUrl/genre/action" to "أكشن",
        "$mainUrl/genre/adventure" to "مغامرة",
        "$mainUrl/genre/comedy" to "كوميدي",
        "$mainUrl/genre/drama" to "دراما",
        "$mainUrl/genre/fantasy" to "خيال",
        "$mainUrl/genre/horror" to "رعب",
        "$mainUrl/genre/mystery" to "غموض",
        "$mainUrl/genre/romance" to "رومانسي",
        "$mainUrl/genre/sci-fi" to "خيال علمي",
        "$mainUrl/genre/supernatural" to "خارق للطبيعة",
        "$mainUrl/genre/thriller" to "إثارة",
        "$mainUrl/genre/suspense" to "تشويق",
        "$mainUrl/genre/psychological" to "نفسي",
        "$mainUrl/genre/seinen" to "سينين",
        "$mainUrl/genre/shounen" to "شونين",
        "$mainUrl/genre/shoujo" to "شوجو",
        "$mainUrl/genre/isekai" to "إيسيكاي",
        "$mainUrl/genre/slice-of-life" to "شريحة من الحياة",
        "$mainUrl/genre/mecha" to "ميكا",
        "$mainUrl/genre/sports" to "رياضي",
        "$mainUrl/genre/military" to "عسكري",
        "$mainUrl/genre/historical" to "تاريخي",
        "$mainUrl/genre/harem" to "حريم",
        "$mainUrl/genre/ecchi" to "إيتشي",
        "$mainUrl/genre/music" to "موسيقى",
        "$mainUrl/genre/martial-arts" to "قتالي",
        "$mainUrl/genre/super-power" to "قوى خارقة",
        "$mainUrl/genre/mythology" to "أساطير",
        "$mainUrl/genre/school" to "مدرسي",
        "$mainUrl/genre/space" to "فضاء",
        "$mainUrl/genre/vampire" to "مصاصي دماء",
        "$mainUrl/genre/samurai" to "ساموراي",
        "$mainUrl/genre/parody" to "ساخر",
        "$mainUrl/genre/detective" to "بوليسي",
        "$mainUrl/genre/gore" to "دموي",
        "$mainUrl/genre/survival" to "نجاة",
        "$mainUrl/genre/time-travel" to "سفر عبر الزمن",
        "$mainUrl/genre/reincarnation" to "تناسخ",
        "$mainUrl/genre/kids" to "للأطفال",

        // --- تصنيف حسب سنة الإصدار (By Year) ---
        "$mainUrl/titles/list?year=2025" to "أنميات 2025",
        "$mainUrl/titles/list?year=2024" to "أنميات 2024",
        "$mainUrl/titles/list?year=2023" to "أنميات 2023",
        "$mainUrl/titles/list?year=2022" to "أنميات 2022",
        "$mainUrl/titles/list?year=2021" to "أنميات 2021",
        "$mainUrl/titles/list?year=2020" to "أنميات 2020",
        "$mainUrl/titles/list?year=2019" to "أنميات 2019",
        "$mainUrl/titles/list?year=2018" to "أنميات 2018",
        "$mainUrl/titles/list?year=2017" to "أنميات 2017",
        "$mainUrl/titles/list?year=2016" to "أنميات 2016",
        "$mainUrl/titles/list?year=2015" to "أنميات 2015",

        // --- تصنيف حسب الموسم (By Season) ---
        "$mainUrl/titles/list?season=winter&year=2025" to "شتاء 2025",
        "$mainUrl/titles/list?season=spring&year=2025" to "ربيع 2025",
        "$mainUrl/titles/list?season=summer&year=2025" to "صيف 2025",
        "$mainUrl/titles/list?season=fall&year=2025" to "خريف 2025",
        "$mainUrl/titles/list?season=winter&year=2024" to "شتاء 2024",
        "$mainUrl/titles/list?season=spring&year=2024" to "ربيع 2024",
        "$mainUrl/titles/list?season=summer&year=2024" to "صيف 2024",
        "$mainUrl/titles/list?season=fall&year=2024" to "خريف 2024",

        // --- تصنيف حسب الحالة (By Status) ---
        "$mainUrl/titles/list?status[]=airing" to "قيد البث",
        "$mainUrl/titles/list?status[]=ended" to "منتهي",

        // --- الأعلى تقييماً (Top Rated) ---
        "$mainUrl/titles/list?sort_by=rate&sort_dir=desc" to "الأعلى تقييماً",
        "$mainUrl/titles/list?sort_by=release_date&sort_dir=desc" to "الأحدث إصداراً",
        "$mainUrl/titles/list?sort_by=name&sort_dir=asc" to "أبجدي"
    )

    private fun toSearchResult(element: Element): SearchResponse? {
        return try {
            val rawTitle = element.select("h3.title-name").text()
            val title = cleanTitleText(rawTitle)
            val href = toAbsoluteUrl(element.attr("href"))
            val posterUrl = element.select("img").attr("src")
            val episodeText = cleanTitleText(element.select("p.number").text())
            val episodeNum = episodeText.filter { it.isDigit() }.toIntOrNull()

            newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = posterUrl
                addDubStatus(false, episodeNum)
            }
        } catch (e: Exception) { null }
    }

    override suspend fun search(query: String): List<SearchResponse> {

        val mainDoc = getDocumentSmart(mainUrl) ?: return emptyList()

        val scriptTag = mainDoc.selectFirst("script[src*=livewire.min.js]")
        val csrfToken = scriptTag?.attr("data-csrf") ?: return emptyList()

        val form = mainDoc.selectFirst("form[wire:id]")
        val snapshotRaw = form?.attr("wire:snapshot") ?: return emptyList()
        val snapshotStr = org.jsoup.parser.Parser.unescapeEntities(snapshotRaw, true)

        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "*/*",
            "Content-Type" to "application/json",
            "Origin" to mainUrl,
            "Referer" to "$mainUrl/",
            "Cookie" to savedCookies // 👈 إرسال الـ Session هنا
        )

        val updateUrl = "$mainUrl/livewire/update"
        val payload = mapOf(
            "_token" to csrfToken,
            "components" to listOf(
                mapOf(
                    "snapshot" to snapshotStr,
                    "updates" to mapOf("query" to query),
                    "calls" to emptyList<Any>()
                )
            )
        )

        val postRes = app.post(updateUrl, headers = headers, json = payload)

        if (postRes.code != 200) return emptyList()

        val responseJson = AppUtils.parseJson<Map<String, Any>>(postRes.text)
        val components = responseJson["components"] as? List<Map<String, Any>> ?: return emptyList()
        val effects = components.firstOrNull()?.get("effects") as? Map<String, Any> ?: return emptyList()
        val htmlContent = effects["html"] as? String ?: return emptyList()

        val soupResults = Jsoup.parse(htmlContent)

        return soupResults.select("a.simple-title-card").mapNotNull { item ->

            val rawTitle = item.selectFirst("h4")?.text()?.trim() ?: return@mapNotNull null
            val title = cleanTitleText(rawTitle)

            val link = item.attr("href")
            val absoluteLink = toAbsoluteUrl(link)

            val img = item.selectFirst("img")
            val image = img?.attr("src")

            val ratingTag = item.selectFirst(".badge")
            val rating = ratingTag?.text()?.trim() ?: "N/A"

            val type = if (rating.contains("Movie") || rating.contains("Film") || title.contains("فيلم")) {
                TvType.AnimeMovie
            } else {
                TvType.Anime
            }

            newAnimeSearchResponse(title, absoluteLink, type) {
                this.posterUrl = image
            }
        }
    }

















    private fun cleanTitleText(text: String): String {
        return text.replace("\\n", " ")
            .replace("\n", " ")
            .replace(Regex("بترجمة.*"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private suspend fun forceLoadAllEpisodes(url: String, timeoutMs: Long = 20000L): org.jsoup.nodes.Document? =
        suspendCoroutine { cont ->
            Handler(Looper.getMainLooper()).post {
                val webView = WebView(this.context)
                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = false
                    userAgentString = USER_AGENT
                    blockNetworkImage = true
                    loadsImagesAutomatically = false
                    mediaPlaybackRequiresUserGesture = true
                    javaScriptCanOpenWindowsAutomatically = false
                    cacheMode = WebSettings.LOAD_DEFAULT
                }
                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                cookieManager.setAcceptThirdPartyCookies(webView, true)
                var finished = false

                fun finish(doc: org.jsoup.nodes.Document?) {
                    if (finished) return
                    finished = true
                    try { webView.stopLoading(); webView.destroy() } catch (_: Exception) {}
                    try { cookieManager.flush(); val newCookies = cookieManager.getCookie(url); if (!newCookies.isNullOrEmpty()) savedCookies = newCookies } catch (_: Exception) {}
                    cont.resume(doc)
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                        super.onPageFinished(view, loadedUrl)
                        var attempts = 0
                        val maxAttempts = 40
                        val handler = Handler(Looper.getMainLooper())
                        val checkRunnable = object : Runnable {
                            override fun run() {
                                if (finished) return
                                val jsCheck = """
                                    (function() {
                                        var count = document.querySelectorAll('.video-list a, .episodes-list a').length;
                                        if (count > 0) return document.documentElement.outerHTML;
                                        return null;
                                    })();
                                """
                                view?.evaluateJavascript(jsCheck) { html ->
                                    if (html != null && html != "null" && html.length > 100) {
                                        var cleanHtml = html
                                        if (cleanHtml.startsWith("\"") && cleanHtml.endsWith("\"")) cleanHtml = cleanHtml.substring(1, cleanHtml.length - 1)
                                        cleanHtml = cleanHtml.replace("\\u003C", "<").replace("\\u003E", ">").replace("\\\"", "\"").replace("\\\\", "\\")
                                        finish(Jsoup.parse(cleanHtml))
                                    } else {
                                        attempts++
                                        if (attempts < maxAttempts) handler.postDelayed(this, 250)
                                        else finish(null)
                                    }
                                }
                            }
                        }
                        checkRunnable.run()
                    }
                }
                webView.loadUrl(url)
                Handler(Looper.getMainLooper()).postDelayed({ finish(null) }, timeoutMs)
            }
        }

    override suspend fun load(url: String): LoadResponse? {
        val fullUrl = toAbsoluteUrl(url)
        val doc = forceLoadAllEpisodes(fullUrl) ?: return null
        return try {
            var rawTitle = doc.selectFirst("h1")?.text() ?: ""
            rawTitle = cleanTitleText(rawTitle)

            val title = TITLE_EP_REGEX.replace(rawTitle, "")
                .replace("( مسلسل )", "")
                .replace("( فيلم )", "")
                .trim()

            val poster = doc.selectFirst("img[alt*='بوستر']")?.attr("src") ?: ""

            val elements = doc.select(".video-list a, .episodes-list a")
            var episodes = elements.mapNotNull { element ->
                val rawHref = element.attr("href")
                if (rawHref.isNullOrBlank()) return@mapNotNull null
                val href = toAbsoluteUrl(rawHref)

                val videoData = element.selectFirst(".video-data")

                val epText = cleanTitleText(videoData?.selectFirst("span")?.text() ?: videoData?.children()?.getOrNull(0)?.text() ?: "")
                val epNum = NON_DIGITS.replace(epText, "").toIntOrNull()

                val epName = cleanTitleText(videoData?.selectFirst("p")?.text() ?: videoData?.children()?.getOrNull(1)?.text() ?: "")

                val imgAttr = element.selectFirst("img")?.attr("src").orEmpty()

                newEpisode(href) {
                    name = if (epName.isNotBlank()) epName else epText
                    episode = epNum
                    posterUrl = imgAttr
                }
            }

            if (episodes.size > 1) {
                val firstEpNum = episodes.first().episode ?: 0
                val lastEpNum = episodes.last().episode ?: 0
                if (firstEpNum > lastEpNum && lastEpNum != 0) {
                    episodes = episodes.reversed()
                }
            }

            var desc = ""
            if (episodes.isNotEmpty()) {
                try {
                    val sampleEpisodeUrl = episodes.first().data
                    val epDoc = app.get(sampleEpisodeUrl).document
                    desc = epDoc.select("div.py-4.flex.flex-col.gap-2 p, p.synopsis").joinToString("\n") { it.text().trim() }
                    if (desc.isBlank()) {
                        desc = epDoc.select("meta[name=description]").attr("content").trim()
                    }
                } catch (e: Exception) {

                }
            }

            if (desc.isBlank()) {
                desc = doc.select("div.py-4.flex.flex-col.gap-2 p, p.synopsis").joinToString("\n") { it.text().trim() }
            }

            newTvSeriesLoadResponse(title, fullUrl, TvType.Anime, episodes) {
                this.posterUrl = poster
                this.plot = desc
            }
        } catch (e: Exception) {

            null
        }
    }

    private suspend fun hijackAndExtractRaw(
        url: String,
        timeoutMs: Long = 60_000L
    ): List<Pair<String, String>> = suspendCoroutine { cont ->
        Handler(Looper.getMainLooper()).post {
            val webView = WebView(this.context)
            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                userAgentString = USER_AGENT
                blockNetworkImage = false
                mediaPlaybackRequiresUserGesture = false
            }
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

            val extractedRaw = mutableListOf<Pair<String, String>>()
            var isDone = false
            val handler = Handler(Looper.getMainLooper())

            fun finish() {
                if (isDone) return
                isDone = true
                try {
                    handler.removeCallbacksAndMessages(null)
                    (webView.parent as? ViewGroup)?.removeView(webView)
                    webView.destroy()
                } catch (e: Exception) {

                }
                cont.resume(extractedRaw.distinctBy { it.first })
            }

            handler.postDelayed({ finish() }, timeoutMs)

            webView.webViewClient = object : WebViewClient() {
                @SuppressLint("WebViewClientOnReceivedSslError")
                override fun onReceivedSslError(v: WebView?, h: SslErrorHandler?, e: android.net.http.SslError?) = h!!.proceed()

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: android.webkit.WebResourceRequest?
                ): android.webkit.WebResourceResponse? {
                    val reqUrl = request?.url?.toString() ?: return super.shouldInterceptRequest(view, request)

                    if (reqUrl.contains("/player/") && !reqUrl.contains("cf_token=")) {
                        Thread {
                            try {
                                val connection = URL(reqUrl).openConnection() as HttpURLConnection
                                connection.requestMethod = "GET"
                                request.requestHeaders?.forEach { (k, v) ->
                                    if (!k.equals("Accept-Encoding", true)) connection.setRequestProperty(k, v)
                                }
                                CookieManager.getInstance().getCookie(url)?.let { connection.setRequestProperty("Cookie", it) }
                                connection.setRequestProperty("Referer", url)

                                val playerHtml = (if (connection.responseCode < 400) connection.inputStream else connection.errorStream).bufferedReader().readText()
                                val jsonPattern =
                                    """var\s+video_sources\s*=\s*(\[[^;]+]);""".toRegex()
                                val jsonMatch = jsonPattern.find(playerHtml)

                                if (jsonMatch != null) {
                                    val jsonStr = jsonMatch.groupValues[1]
                                    val linksFromJson = AppUtils.parseJson<List<Map<String, Any?>>>(jsonStr)
                                    linksFromJson.forEach { item ->
                                        val src = item["src"]?.toString() ?: item["file"]?.toString()
                                        val label = item["label"]?.toString() ?: "Default"
                                        if (!src.isNullOrBlank()) extractedRaw.add(src to label)
                                    }

                                    if (extractedRaw.isNotEmpty()) {
                                        handler.post { finish() }
                                    }
                                }
                            } catch (e: Exception) {

                            }
                        }.start()
                        return super.shouldInterceptRequest(view, request)
                    }

                    if (reqUrl.contains("/sources") && reqUrl.contains("cf_token=")) {
                        try {
                            val connection = URL(reqUrl).openConnection() as HttpURLConnection
                            connection.requestMethod = "GET"
                            request.requestHeaders?.forEach { (k, v) ->
                                if (!k.equals("Accept-Encoding", true)) connection.setRequestProperty(k, v)
                            }
                            CookieManager.getInstance().getCookie(reqUrl)?.let { connection.setRequestProperty("Cookie", it) }

                            val responseBytes = (if (connection.responseCode < 400) connection.inputStream else connection.errorStream).readBytes()
                            val jsonString = String(responseBytes, Charsets.UTF_8)

                            val linksFromJson = AppUtils.parseJson<List<Map<String, Any?>>>(jsonString)
                            linksFromJson.forEach { item ->
                                val src = item["src"]?.toString() ?: item["file"]?.toString()
                                val label = item["label"]?.toString() ?: "Default"
                                if (!src.isNullOrBlank()) extractedRaw.add(src to label)
                            }

                            if (extractedRaw.isNotEmpty()) {
                                handler.post { finish() }
                            }

                            val contentType = connection.contentType?.split(";")?.get(0) ?: "application/json"
                            return android.webkit.WebResourceResponse(contentType, "UTF-8", ByteArrayInputStream(responseBytes)).apply {
                                responseHeaders = mutableMapOf("Access-Control-Allow-Origin" to "*")
                            }

                        } catch (e: Exception) {

                        }
                    }
                    return super.shouldInterceptRequest(view, request)
                }
            }

            webView.loadUrl(url)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val rawLinks = hijackAndExtractRaw(data)

        if (rawLinks.isEmpty()) {

            return false
        }

        rawLinks.forEach { (src, label) ->
            try {
                callback(
                    newExtractorLink(
                        source = this.name,
                        name = "${this.name} $label",
                        url = src,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        referer = "https://video.vid3rb.com/"
                    }
                )
            } catch (e: Exception) {

            }
        }
        return true
    }

    private fun extractQuality(label: String): Int {
        return Regex("""(\d{3,4})p""", RegexOption.IGNORE_CASE)
            .find(label)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
            ?: Qualities.Unknown.value
    }
}