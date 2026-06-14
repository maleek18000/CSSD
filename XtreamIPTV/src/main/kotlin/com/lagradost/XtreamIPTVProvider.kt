package com.lagradost

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

// ═══════════════════════════════════════════════════════════════════
//  PLUGIN ENTRY POINT
// ═══════════════════════════════════════════════════════════════════

@CloudstreamPlugin
class XtreamIPTVPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(XtreamIPTVProvider())
    }
}

// ═══════════════════════════════════════════════════════════════════
//  DATA CLASSES  (top-level to avoid D8 metadata crash)
// ═══════════════════════════════════════════════════════════════════

data class M3UEntry(
    val name: String,
    val group: String,
    val logo: String?,
    val streamUrl: String,
    val type: String,
    val seriesName: String = ""
)

data class EntryRef(
    val url: String,
    val type: String,
    val name: String,
    val group: String,
    val logo: String? = null,
    val seriesName: String = ""
)

data class XCat(val category_id: String? = null, val category_name: String? = null)
data class XLive(val name: String = "", val stream_id: Int = 0, val stream_icon: String? = null, val category_id: String = "")
data class XVod(val name: String = "", val stream_id: Int = 0, val stream_icon: String? = null, val category_id: String = "", val container_extension: String? = null)
data class XVodInfo(val info: XVodInfoDetail? = null, val movie_data: XVodStreamData? = null)
data class XVodInfoDetail(val name: String? = null, val plot: String? = null, val genre: String? = null, val rating: String? = null, val duration: String? = null, val movie_image: String? = null, val cover: String? = null)
data class XVodStreamData(val stream_id: Int? = null, val container_extension: String? = null)
data class XSeries(val name: String = "", val series_id: Int = 0, val cover: String? = null, val category_id: String? = null)
data class XSeriesInfo(val info: XSeriesDetail? = null, val episodes: Map<String, List<XEp>>? = null)
data class XSeriesDetail(val name: String? = null, val plot: String? = null, val genre: String? = null, val rating: String? = null, val cover: String? = null)
data class XEp(val id: Int = 0, val episode_num: Int = 0, val season_num: Int = 0, val title: String? = null, val container_extension: String? = null, val info: XEpInfo? = null)
data class XEpInfo(val plot: String? = null, val image: String? = null)

data class ItemRef(val t: String, val id: Int, val n: String, val e: String? = null)
data class LinkData(val t: String, val id: Int, val e: String? = null)

// ═══════════════════════════════════════════════════════════════════
//  RAW HTTP CLIENT  (bypasses CloudStream's app.get to avoid 403)
// ═══════════════════════════════════════════════════════════════════

object RawHttp {

    private val userAgents = listOf(
        "okhttp/4.12.0",
        "IPTVSmarters/2",
        "TiviMate/4.7.0"
    )

    suspend fun get(url: String, readTimeout: Int = 15000, connectTimeout: Int = 6000): String? = withContext(Dispatchers.IO) {
        val uri = try { URI(url) } catch (_: Exception) { return@withContext null }
        val referer = "${uri.scheme}://${uri.host}/"

        for (ua in userAgents) {
            try {
                val result = fetch(url, ua, connectTimeout, readTimeout, mapOf(
                    "Referer" to referer,
                    "Accept" to "*/*"
                ))
                if (result != null && result.length > 10) return@withContext result
            } catch (_: Exception) { continue }
        }
        null
    }

    suspend fun apiGet(url: String, readTimeout: Int = 12000, connectTimeout: Int = 6000): String? = withContext(Dispatchers.IO) {
        try {
            val uri = URI(url)
            val referer = "${uri.scheme}://${uri.host}/"
            fetch(url, "okhttp/4.12.0", connectTimeout, readTimeout, mapOf(
                "Referer" to referer,
                "Accept" to "*/*"
            ))
        } catch (_: Exception) { null }
    }

    private const val MAX_API_RESPONSE_SIZE = 5 * 1024 * 1024  // 5 MB

    private fun fetch(url: String, userAgent: String, connectTimeout: Int, readTimeout: Int, extraHeaders: Map<String, String>): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", userAgent)
            extraHeaders.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.connectTimeout = connectTimeout
            conn.readTimeout = readTimeout
            conn.instanceFollowRedirects = true

            val code = conn.responseCode
            if (code in 400..599) return null

            val reader = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8"))
            val sb = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                sb.append(line).append("\n")
                if (sb.length > MAX_API_RESPONSE_SIZE) {
                    try { reader.close() } catch (_: Exception) {}
                    conn.disconnect()
                    return null
                }
            }
            reader.close()
            return sb.toString()
        } catch (_: Exception) {
            return null
        } finally {
            conn.disconnect()
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  PROVIDER
//
//  ARCHITECTURE: Blazing fast + real content rows
//
//  1. Fetch 3 category endpoints in parallel (tiny JSON, ~1s)
//  2. Fetch ALL per-category streams in parallel (each call is small)
//  3. Build home page:
//     - Movies:  each category = its own row with ALL movie cards
//     - Series:  each category = its own row with ALL series cards
//     - Live TV: ALL categories combined into ONE row
//  4. Everything cached for instant subsequent visits
//  5. Memory-safe: enoughMemory() checks prevent OOM
// ═══════════════════════════════════════════════════════════════════

class XtreamIPTVProvider : MainAPI() {

    override var mainUrl = "http://example.com/username/password"
    override var name = "Xtream IPTV"
    override val supportedTypes = setOf(TvType.Live, TvType.Movie, TvType.TvSeries)
    override val hasMainPage = true
    override val hasQuickSearch = false

    // ═══════════════════════════════════════════════════════════════════
    //  URL HELPERS
    // ═══════════════════════════════════════════════════════════════════

    private fun cleanUrl(): String {
        val raw = mainUrl.trim()
        val tildeIdx = raw.indexOf('~')
        return if (tildeIdx >= 0) raw.substring(0, tildeIdx).trim() else raw
    }

    private fun parseCategoryFilter(): List<String>? {
        val raw = mainUrl.trim()
        val tildeIdx = raw.indexOf('~')
        if (tildeIdx < 0) return null
        val filterPart = raw.substring(tildeIdx + 1)
        val categories = filterPart.split("~").map { it.trim() }.filter { it.isNotEmpty() }
        return if (categories.isEmpty()) null else categories
    }

    // ═══════════════════════════════════════════════════════════════════
    //  CREDENTIAL PARSING
    // ═══════════════════════════════════════════════════════════════════

    private data class Cfg(val server: String, val user: String, val pass: String)

    private fun cfg(): Cfg? {
        val raw = cleanUrl()

        if (raw.contains("get.php") || raw.contains(".m3u") || raw.contains("m3u_plus")) {
            try {
                val qMark = raw.indexOf("?")
                if (qMark < 0) return null
                val query = raw.substring(qMark + 1)
                var user = ""
                var pass = ""
                query.split("&").forEach { param ->
                    val kv = param.split("=", limit = 2)
                    if (kv.size == 2) {
                        when (kv[0].lowercase()) {
                            "username" -> user = URLDecoder.decode(kv[1], "UTF-8")
                            "password" -> pass = URLDecoder.decode(kv[1], "UTF-8")
                        }
                    }
                }
                val gpIdx = raw.indexOf("get.php")
                if (gpIdx > 0) {
                    val serverBase = raw.substring(0, gpIdx).trimEnd('/')
                    if (user.isNotEmpty() && pass.isNotEmpty()) return Cfg(serverBase, user, pass)
                }
            } catch (_: Exception) {}
        }

        if (raw.contains("|")) {
            val p = raw.split("|")
            if (p.size >= 3) return Cfg(p[0].trimEnd('/'), p[1], p[2])
        }

        try {
            val proto = raw.indexOf("//")
            if (proto < 0) return null
            val afterProto = raw.substring(proto + 2)
            val firstSlash = afterProto.indexOf("/")
            if (firstSlash < 0) return null
            val server = raw.substring(0, proto + 2 + firstSlash)
            val rest = afterProto.substring(firstSlash + 1).trimEnd('/')
            val segs = rest.split("/")
            if (segs.size >= 2) {
                val user = segs[segs.size - 2]
                val pass = segs.last()
                if (user != "username" && pass != "password") return Cfg(server, user, pass)
            }
        } catch (_: Exception) {}

        return null
    }

    // ═══════════════════════════════════════════════════════════════════
    //  M3U PARSER
    // ═══════════════════════════════════════════════════════════════════

    private fun parseM3U(text: String): List<M3UEntry> {
        val entries = mutableListOf<M3UEntry>()
        val lines = text.lines()
        var i = 0

        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("#EXTINF")) {
                val logo = extractAttr(line, "tvg-logo")
                val group = extractAttr(line, "group-title") ?: "Uncategorized"
                val commaIdx = line.lastIndexOf(',')
                val name = if (commaIdx >= 0) line.substring(commaIdx + 1).trim() else "Unknown"

                var url = ""
                var j = i + 1
                while (j < lines.size) {
                    val nextLine = lines[j].trim()
                    if (nextLine.isNotEmpty() && !nextLine.startsWith("#")) {
                        url = nextLine
                        break
                    }
                    j++
                }

                if (url.isNotEmpty()) {
                    val detectedType = detectType(url, group, name)
                    val sName = if (detectedType == "series") extractSeriesName(name) else ""
                    entries.add(M3UEntry(name, group, logo, url, detectedType, sName))
                }
                i = j + 1
            } else {
                i++
            }
        }
        return entries
    }

    private suspend fun downloadAndParseM3U(url: String, readTimeout: Int = 60000): List<M3UEntry>? = withContext(Dispatchers.IO) {
        val uri = try { URI(url) } catch (_: Exception) { return@withContext null }
        val referer = "${uri.scheme}://${uri.host}/"
        val uas = listOf("okhttp/4.12.0", "IPTVSmarters/2", "TiviMate/4.7.0")

        for (ua in uas) {
            var conn: HttpURLConnection? = null
            try {
                conn = URL(url).openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", ua)
                conn.setRequestProperty("Referer", referer)
                conn.setRequestProperty("Accept", "*/*")
                conn.connectTimeout = 8000
                conn.readTimeout = readTimeout
                conn.instanceFollowRedirects = true

                val code = conn.responseCode
                if (code in 400..599) { conn.disconnect(); continue }

                val reader = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8"))
                val entries = parseM3UFromReader(reader)
                reader.close()
                conn.disconnect()
                if (entries.isNotEmpty()) return@withContext entries
            } catch (_: Throwable) {
            } finally {
                conn?.disconnect()
            }
        }
        null
    }

    private val MAX_M3U_ENTRIES = 10000

    private fun parseM3UFromReader(reader: BufferedReader): List<M3UEntry> {
        val entries = mutableListOf<M3UEntry>()
        var currentInfLine: String? = null

        try {
            while (true) {
                val line = reader.readLine() ?: break
                if (entries.size >= MAX_M3U_ENTRIES) {
                    reader.close()
                    break
                }
                if (entries.size % 500 == 0) {
                    val rt = Runtime.getRuntime()
                    val freeMB = (rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())) / (1024 * 1024)
                    if (freeMB < 4) {
                        reader.close()
                        break
                    }
                }
                val trimmed = line.trim()
                if (trimmed.startsWith("#EXTINF")) {
                    currentInfLine = trimmed
                } else if (trimmed.isNotEmpty() && !trimmed.startsWith("#") && currentInfLine != null) {
                    val infLine = currentInfLine!!
                    currentInfLine = null

                    val logo = extractAttr(infLine, "tvg-logo")
                    val group = extractAttr(infLine, "group-title") ?: "Uncategorized"
                    val commaIdx = infLine.lastIndexOf(',')
                    val name = if (commaIdx >= 0) infLine.substring(commaIdx + 1).trim() else "Unknown"

                    val detectedType = detectType(trimmed, group, name)
                    val sName = if (detectedType == "series") extractSeriesName(name) else ""
                    entries.add(M3UEntry(name, group, logo, trimmed, detectedType, sName))
                }
            }
        } catch (_: Throwable) {
        }

        return entries
    }

    private fun extractAttr(line: String, attr: String): String? {
        val quotedPattern = """$attr\s*=\s*"([^"]*?)"""".toRegex(RegexOption.IGNORE_CASE)
        quotedPattern.find(line)?.let { match ->
            return match.groupValues.getOrNull(1)?.ifBlank { null }
        }
        val singlePattern = """$attr\s*=\s*'([^']*?)'""".toRegex(RegexOption.IGNORE_CASE)
        singlePattern.find(line)?.let { match ->
            return match.groupValues.getOrNull(1)?.ifBlank { null }
        }
        val unquotedPattern = """$attr\s*=\s*([^\s,]+)""".toRegex(RegexOption.IGNORE_CASE)
        unquotedPattern.find(line)?.let { match ->
            return match.groupValues.getOrNull(1)?.ifBlank { null }
        }
        return null
    }

    private fun detectType(url: String, group: String, name: String): String {
        val lower = url.lowercase()
        val groupLower = group.lowercase()

        if (lower.contains("/live/")) return "live"
        if (lower.contains("/movie/")) return "movie"
        if (lower.contains("/series/")) return "series"

        if (groupLower.contains("series") || groupLower.contains("\u0645\u0633\u0644\u0633\u0644\u0627\u062A") ||
            groupLower.contains("\u0645\u0633\u0644\u0633\u0644") || groupLower.contains("\u062D\u0644\u0642\u0627\u062A") ||
            groupLower.contains("s\u00e9rie") || groupLower.contains("serien") ||
            groupLower.contains("episod") || groupLower.contains("\u0645\u0648\u0633\u0645")) return "series"

        if (groupLower.contains("vod") || groupLower.contains("movie") ||
            groupLower.contains("film") || groupLower.contains("\u0623\u0641\u0644\u0627\u0645") ||
            groupLower.contains("\u0641\u064A\u0644\u0645")) return "movie"

        if (name.contains(Regex("""[Ss]\s*\d+\s*[Ee]\s*\d+""")) ||
            name.contains(Regex("""\d+\s*[xX]\s*\d+""")) ||
            name.contains(Regex("""[Ee][Pp]\s*\d+""")) ||
            name.contains(Regex("""Season\s*\d+""", RegexOption.IGNORE_CASE)) ||
            name.contains(Regex("""\u062D\u0644\u0642\u0629"""))) return "series"

        if (lower.endsWith(".ts") || lower.endsWith(".m3u8")) return "live"
        if (lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".avi")) return "movie"

        return "live"
    }

    private fun extractSeriesName(name: String): String {
        var result = name
            .replace(Regex("""\s*[Ss]\s*\d+\s*[Ee]\s*\d+.*$"""), "")
            .replace(Regex("""\s*\d+\s*[xX]\s*\d+.*$"""), "")
            .replace(Regex("""\s*[Ee][Pp]?\s*\d+.*$"""), "")
            .replace(Regex("""\s*-\s*[Ss]\d+.*$"""), "")
            .replace(Regex("""\s*Season\s*\d+.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*\u062D\u0644\u0642\u0629.*$"""), "")
            .replace(Regex("""\s*\u0627\u0644\u0645\u0648\u0633\u0645.*$"""), "")
            .trim()
            .trimEnd('-', '_', ':', '.', ' ')
            .trim()
        return result.ifBlank { name }
    }

    private fun parseSeasonEpisode(name: String): Pair<Int, Int> {
        Regex("""[Ss]\s*(\d+)\s*[Ee]\s*(\d+)""").find(name)?.let { m ->
            val s = m.groupValues[1].toIntOrNull() ?: 1
            val e = m.groupValues[2].toIntOrNull() ?: 1
            return s to e
        }
        Regex("""(\d+)\s*[xX]\s*(\d+)""").find(name)?.let { m ->
            val s = m.groupValues[1].toIntOrNull() ?: 1
            val e = m.groupValues[2].toIntOrNull() ?: 1
            return s to e
        }
        Regex("""Season\s*(\d+).*Episode\s*(\d+)""", RegexOption.IGNORE_CASE).find(name)?.let { m ->
            val s = m.groupValues[1].toIntOrNull() ?: 1
            val e = m.groupValues[2].toIntOrNull() ?: 1
            return s to e
        }
        Regex("""[Ee][Pp]?\s*(\d+)""").find(name)?.let { m ->
            val e = m.groupValues[1].toIntOrNull() ?: 1
            return 1 to e
        }
        Regex("""\u0627\u0644\u0645\u0648\u0633\u0645\s*(\d+).*\u0627\u0644\u062D\u0644\u0642\u0629\s*(\d+)""").find(name)?.let { m ->
            val s = m.groupValues[1].toIntOrNull() ?: 1
            val e = m.groupValues[2].toIntOrNull() ?: 1
            return s to e
        }
        return 1 to 0
    }

    private fun guessQuality(name: String?): Int {
        if (name == null) return Qualities.Unknown.value
        val n = name.uppercase()
        return when {
            n.contains("4K") || n.contains("2160") -> Qualities.P2160.value
            n.contains("1080") || n.contains("FHD") -> Qualities.P1080.value
            n.contains("720") || n.contains("HD") -> Qualities.P720.value
            else -> Qualities.Unknown.value
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  CACHE
    // ═══════════════════════════════════════════════════════════════════

    private var cachedM3U: List<M3UEntry>? = null

    // Category text caches (small JSON, safe to hold in memory)
    private var cachedXtreamVodCats: String? = null
    private var cachedXtreamSeriesCats: String? = null
    private var cachedXtreamLiveCats: String? = null

    // Per-category stream cache
    private val cachedCatStreams = mutableMapOf<String, List<Any>>()
    private val MAX_CACHED_CATEGORIES = 200

    /** Check if there's enough free memory to continue loading data. */
    private fun enoughMemory(): Boolean {
        val rt = Runtime.getRuntime()
        val freeMB = (rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())) / (1024 * 1024)
        return freeMB >= 15
    }

    /** Check if Xtream categories are already cached. */
    private fun hasXtreamCache(): Boolean {
        return (cachedXtreamVodCats != null || cachedXtreamSeriesCats != null || cachedXtreamLiveCats != null)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  XTREAM API HELPERS
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Fetch ONLY the 3 category endpoints in parallel (tiny JSON).
     */
    private suspend fun fetchXtreamCategories(
        c: Cfg,
        catReadTimeout: Int = 10000,
        connectTimeout: Int = 6000
    ): Triple<String?, String?, String?>? {
        try {
            val encUser = URLEncoder.encode(c.user, "UTF-8")
            val encPass = URLEncoder.encode(c.pass, "UTF-8")
            val apiBase = "${c.server}/player_api.php?username=$encUser&password=$encPass"

            val (vodCatsText, seriesCatsText, liveCatsText) = coroutineScope {
                val vodCatDef = async { RawHttp.apiGet("$apiBase&action=get_vod_categories", catReadTimeout, connectTimeout) }
                val serCatDef = async { RawHttp.apiGet("$apiBase&action=get_series_categories", catReadTimeout, connectTimeout) }
                val liveCatDef = async { RawHttp.apiGet("$apiBase&action=get_live_categories", catReadTimeout, connectTimeout) }
                Triple(vodCatDef.await(), serCatDef.await(), liveCatDef.await())
            }

            if (vodCatsText == null && seriesCatsText == null && liveCatsText == null) return null
            return Triple(vodCatsText, seriesCatsText, liveCatsText)
        } catch (_: Exception) {
            return null
        }
    }

    /**
     * Fetch streams for ALL categories of one type in parallel.
     * Returns a map of category_key -> stream list.
     * Already-cached categories are returned immediately without network calls.
     */
    private suspend fun fetchStreamsForCategories(
        categories: List<XCat>, typePrefix: String, apiBase: String, action: String
    ): Map<String, List<Any>> = coroutineScope {
        val results = mutableMapOf<String, List<Any>>()

        // Add already-cached entries first (instant, no network)
        for (cat in categories) {
            val catId = cat.category_id ?: continue
            val key = "${typePrefix}_$catId"
            cachedCatStreams[key]?.let { results[key] = it }
        }

        // Determine which categories still need fetching
        val uncached = categories.filter { cat ->
            val catId = cat.category_id ?: return@filter false
            !results.containsKey("${typePrefix}_$catId")
        }

        if (uncached.isEmpty() || !enoughMemory()) return@coroutineScope results

        // Fire all uncached fetches in parallel
        val deferreds = uncached.mapNotNull { cat ->
            val catId = cat.category_id ?: return@mapNotNull null
            val key = "${typePrefix}_$catId"
            async {
                try {
                    val text = RawHttp.apiGet("$apiBase&action=$action&category_id=$catId", 12000)
                    if (text != null) {
                        val parsed: List<Any>? = when (typePrefix) {
                            "v" -> tryParseJson<List<XVod>>(text)
                            "s" -> tryParseJson<List<XSeries>>(text)
                            "l" -> tryParseJson<List<XLive>>(text)
                            else -> null
                        }
                        parsed?.let { p -> key to p }
                    } else null
                } catch (_: Throwable) { null }
            }
        }

        // Collect results as they complete
        deferreds.forEach { deferred ->
            try {
                deferred.await()?.let { (key, streams) ->
                    results[key] = streams
                    // Cache for subsequent visits and search
                    if (cachedCatStreams.size < MAX_CACHED_CATEGORIES) {
                        cachedCatStreams[key] = streams
                    }
                }
            } catch (_: Throwable) { }
        }

        results
    }

    /**
     * Filter Xtream categories based on category filter from URL.
     */
    private fun filterCats(cats: List<XCat>, filter: List<String>?): List<XCat> {
        if (filter == null) return cats
        return cats.filter { cat ->
            filter.any { f -> (cat.category_name ?: "").contains(f, ignoreCase = true) }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  HOME PAGE
    // ═══════════════════════════════════════════════════════════════════

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = cleanUrl()
        if (url.isEmpty() || url == "http://example.com/username/password") return null
        if (page > 1) return null

        try {
            return getMainPageInternal(page, request)
        } catch (_: Throwable) {
            return null
        }
    }

    private suspend fun getMainPageInternal(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = cleanUrl()
        if (url.isEmpty() || url == "http://example.com/username/password") return null
        if (page > 1) return null

        val lists = mutableListOf<HomePageList>()
        val catFilter = parseCategoryFilter()

        // ── Return from M3U cache if already loaded (instant, no network) ──
        val cached = cachedM3U
        if (cached != null && cached.isNotEmpty()) {
            buildM3UHomePage(cached, lists)
            if (lists.isNotEmpty()) return newHomePageResponse(lists, false)
        }

        // ── Return from Xtream cache if categories + streams already loaded ──
        if (hasXtreamCache() && cachedCatStreams.isNotEmpty()) {
            buildXtreamRowsFromCache(catFilter, lists)
            if (lists.isNotEmpty()) return newHomePageResponse(lists, false)
        }

        val c = cfg()

        if (c != null) {
            try {
                // Step 1: Fetch categories ONLY (3 tiny API calls — blazing fast)
                val catResult = fetchXtreamCategories(c)
                if (catResult != null) {
                    val (vodCatsText, seriesCatsText, liveCatsText) = catResult
                    // Cache categories
                    vodCatsText?.let { cachedXtreamVodCats = it }
                    seriesCatsText?.let { cachedXtreamSeriesCats = it }
                    liveCatsText?.let { cachedXtreamLiveCats = it }

                    // Check if background preloading has already populated streams
                    if (cachedCatStreams.isNotEmpty()) {
                        buildXtreamRowsFromCache(catFilter, lists)
                        if (lists.isNotEmpty()) return newHomePageResponse(lists, false)
                    }

                    // Step 2: Parse categories
                    val vodCats = filterCats(vodCatsText?.let { tryParseJson<List<XCat>>(it) } ?: emptyList(), catFilter)
                    val seriesCats = filterCats(seriesCatsText?.let { tryParseJson<List<XCat>>(it) } ?: emptyList(), catFilter)
                    val liveCats = filterCats(liveCatsText?.let { tryParseJson<List<XCat>>(it) } ?: emptyList(), catFilter)

                    val encUser = URLEncoder.encode(c.user, "UTF-8")
                    val encPass = URLEncoder.encode(c.pass, "UTF-8")
                    val apiBase = "${c.server}/player_api.php?username=$encUser&password=$encPass"

                    // Step 3: Fetch ALL per-category streams IN PARALLEL
                    // This is the key to speed: each category API call is small,
                    // and they all run at the same time.
                    val (vodStreams, seriesStreams, liveStreams) = coroutineScope {
                        val vodDeferred = async { fetchStreamsForCategories(vodCats, "v", apiBase, "get_vod_streams") }
                        val serDeferred = async { fetchStreamsForCategories(seriesCats, "s", apiBase, "get_series") }
                        val liveDeferred = async { fetchStreamsForCategories(liveCats, "l", apiBase, "get_live_streams") }
                        Triple(vodDeferred.await(), serDeferred.await(), liveDeferred.await())
                    }

                    // Step 4: Build rows
                    buildXtreamRows(vodCats, seriesCats, liveCats, vodStreams, seriesStreams, liveStreams, lists)

                    if (lists.isNotEmpty()) return newHomePageResponse(lists, false)
                }
            } catch (_: Throwable) {
                // OOM or other — fall through to M3U fallback
            }
        }

        // ── Fallback: Xtream API failed -> try M3U ──
        var m3uEntries: List<M3UEntry>? = null
        try {
            m3uEntries = withTimeoutOrNull(30000L) {
                downloadAndParseM3U(url)
            }
        } catch (_: Throwable) { }

        if (m3uEntries != null && m3uEntries.isNotEmpty()) {
            cachedM3U = m3uEntries
            lists.clear()
            buildM3UHomePage(m3uEntries, lists)
            if (lists.isNotEmpty()) return newHomePageResponse(lists, false)
        }

        return if (lists.isEmpty()) null else newHomePageResponse(lists, false)
    }

    /**
     * Build home page rows from Xtream categories with actual content.
     *
     * Layout:
     *   - Series: each category = its own row with ALL series cards (unlimited)
     *   - Movies: each category = its own row with ALL movie cards (unlimited)
     *   - Live TV: ALL categories combined into ONE row (unlimited)
     */
    private fun buildXtreamCategoryRows(
        vodCats: List<XCat>,
        seriesCats: List<XCat>,
        liveCats: List<XCat>,
        vodStreams: Map<String, List<Any>>,
        seriesStreams: Map<String, List<Any>>,
        liveStreams: Map<String, List<Any>>,
        lists: MutableList<HomePageList>
    ) {
        // ══════════════════════════════════════════════════════════════
        //  SERIES ROWS — each category = its own row with ALL series
        // ══════════════════════════════════════════════════════════════
        for (cat in seriesCats) {
            val catId = cat.category_id ?: continue
            val key = "s_$catId"
            val streams = (seriesStreams[key] as? List<XSeries>) ?: continue
            if (streams.isEmpty()) continue

            val items = streams.map { s ->
                newTvSeriesSearchResponse(s.name, ItemRef("s", s.series_id, s.name).toJson(), TvType.TvSeries) {
                    posterUrl = s.cover
                }
            }
            lists.add(HomePageList("\uD83C\uDFA6 ${cat.category_name ?: "Series"}", items))
        }

        // ══════════════════════════════════════════════════════════════
        //  MOVIE ROWS — each category = its own row with ALL movies
        // ══════════════════════════════════════════════════════════════
        for (cat in vodCats) {
            val catId = cat.category_id ?: continue
            val key = "v_$catId"
            val streams = (vodStreams[key] as? List<XVod>) ?: continue
            if (streams.isEmpty()) continue

            val items = streams.map { s ->
                newMovieSearchResponse(s.name, ItemRef("m", s.stream_id, s.name, s.container_extension ?: "mp4").toJson(), TvType.Movie) {
                    posterUrl = s.stream_icon
                }
            }
            lists.add(HomePageList("\uD83C\uDFAC ${cat.category_name ?: "Movies"}", items))
        }

        // ══════════════════════════════════════════════════════════════
        //  LIVE TV — ALL categories combined into ONE row
        // ══════════════════════════════════════════════════════════════
        val allLiveItems = mutableListOf<SearchResponse>()
        for (cat in liveCats) {
            val catId = cat.category_id ?: continue
            val key = "l_$catId"
            val streams = (liveStreams[key] as? List<XLive>) ?: continue
            streams.map { s ->
                newMovieSearchResponse(s.name, ItemRef("l", s.stream_id, s.name).toJson(), TvType.Live) {
                    posterUrl = s.stream_icon
                }
            }.also { allLiveItems.addAll(it) }
        }
        if (allLiveItems.isNotEmpty()) {
            lists.add(HomePageList("\uD83D\uDCE1 Live TV", allLiveItems))
        }
    }

    /** Alias kept for clarity — the main build method. */
    private fun buildXtreamRows(
        vodCats: List<XCat>,
        seriesCats: List<XCat>,
        liveCats: List<XCat>,
        vodStreams: Map<String, List<Any>>,
        seriesStreams: Map<String, List<Any>>,
        liveStreams: Map<String, List<Any>>,
        lists: MutableList<HomePageList>
    ) = buildXtreamCategoryRows(vodCats, seriesCats, liveCats, vodStreams, seriesStreams, liveStreams, lists)

    /**
     * Build home page rows from cached Xtream data (instant, no network).
     * Used on subsequent visits when categories and streams are already cached.
     */
    private fun buildXtreamRowsFromCache(
        catFilter: List<String>?, lists: MutableList<HomePageList>
    ) {
        val vodCats = filterCats(cachedXtreamVodCats?.let { tryParseJson<List<XCat>>(it) } ?: emptyList(), catFilter)
        val seriesCats = filterCats(cachedXtreamSeriesCats?.let { tryParseJson<List<XCat>>(it) } ?: emptyList(), catFilter)
        val liveCats = filterCats(cachedXtreamLiveCats?.let { tryParseJson<List<XCat>>(it) } ?: emptyList(), catFilter)

        buildXtreamCategoryRows(
            vodCats, seriesCats, liveCats,
            cachedCatStreams, cachedCatStreams, cachedCatStreams,
            lists
        )
    }

    /**
     * Build home page lists from parsed M3U entries.
     *
     * Layout:
     *   - Series: each category = its own row with ALL series cards (unlimited)
     *   - Movies: each category = its own row with ALL movie cards (unlimited)
     *   - Live TV: ALL categories combined into ONE row (unlimited)
     */
    private fun buildM3UHomePage(entries: List<M3UEntry>, lists: MutableList<HomePageList>) {
        val filter = parseCategoryFilter()

        val allMovies = entries.filter { it.type == "movie" }
        val allSeries = entries.filter { it.type == "series" }
        val allLive = entries.filter { it.type == "live" }

        val movieGroups = filterGroups(allMovies.groupBy { it.group }, filter)
        val seriesGroups = filterGroups(allSeries.groupBy { it.group }, filter)
        val liveGroups = filterGroups(allLive.groupBy { it.group }, filter)

        // ══════════════════════════════════════════════════════════════
        //  SERIES ROWS — each category = its own row
        // ══════════════════════════════════════════════════════════════
        seriesGroups.entries
            .sortedByDescending { it.value.size }
            .forEach { (group, items) ->
                val uniqueSeries = items.groupBy { it.seriesName.ifBlank { extractSeriesName(it.name) } }
                val homeItems = uniqueSeries.entries.map { (seriesName, episodes) ->
                    val first = episodes.first()
                    val ref = EntryRef(first.streamUrl, "series", seriesName, group, first.logo, seriesName)
                    if (episodes.size > 1) {
                        newTvSeriesSearchResponse(seriesName, ref.toJson(), TvType.TvSeries) { posterUrl = first.logo }
                    } else {
                        newMovieSearchResponse(seriesName, ref.toJson(), TvType.Movie) { posterUrl = first.logo }
                    }
                }
                if (homeItems.isNotEmpty()) {
                    lists.add(HomePageList("\uD83C\uDFA6 $group", homeItems))
                }
            }

        // ══════════════════════════════════════════════════════════════
        //  MOVIE ROWS — each category = its own row
        // ══════════════════════════════════════════════════════════════
        movieGroups.entries
            .sortedByDescending { it.value.size }
            .forEach { (group, items) ->
                val homeItems = items.map { entry ->
                    val ref = EntryRef(entry.streamUrl, "movie", entry.name, entry.group, entry.logo)
                    newMovieSearchResponse(entry.name, ref.toJson(), TvType.Movie) { posterUrl = entry.logo }
                }
                if (homeItems.isNotEmpty()) {
                    lists.add(HomePageList("\uD83C\uDFAC $group", homeItems))
                }
            }

        // ══════════════════════════════════════════════════════════════
        //  LIVE TV — ALL categories combined into ONE row
        // ══════════════════════════════════════════════════════════════
        val allLiveItems = liveGroups.values.flatten().map { entry ->
            val ref = EntryRef(entry.streamUrl, "live", entry.name, entry.group, entry.logo)
            newMovieSearchResponse(entry.name, ref.toJson(), TvType.Live) { posterUrl = entry.logo }
        }
        if (allLiveItems.isNotEmpty()) {
            lists.add(HomePageList("\uD83D\uDCE1 Live TV", allLiveItems))
        }
    }

    private fun filterGroups(
        groups: Map<String, List<M3UEntry>>,
        filter: List<String>?
    ): Map<String, List<M3UEntry>> {
        if (filter == null) return groups
        return groups.filter { (group, _) ->
            filter.any { f -> group.contains(f, ignoreCase = true) }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  SEARCH
    // ═══════════════════════════════════════════════════════════════════

    override suspend fun search(query: String): List<SearchResponse>? {
        val url = cleanUrl()
        if (url.isEmpty() || url == "http://example.com/username/password") return null
        val q = query.lowercase()
        val results = mutableListOf<SearchResponse>()

        // ── Search M3U cache ──
        cachedM3U?.filter {
            it.name.lowercase().contains(q) || it.group.lowercase().contains(q)
        }?.take(30)?.forEach { entry ->
            val ref = EntryRef(entry.streamUrl, entry.type, entry.name, entry.group, entry.logo, entry.seriesName)
            when (entry.type) {
                "movie" -> results.add(newMovieSearchResponse(entry.name, ref.toJson(), TvType.Movie) { posterUrl = entry.logo })
                "series" -> results.add(newTvSeriesSearchResponse(entry.seriesName.ifBlank { entry.name }, ref.toJson(), TvType.TvSeries) { posterUrl = entry.logo })
                else -> results.add(newMovieSearchResponse(entry.name, ref.toJson(), TvType.Live) { posterUrl = entry.logo })
            }
        }

        // ── Search per-category cache ──
        for ((key, streams) in cachedCatStreams) {
            if (results.size >= 30) break
            when {
                key.startsWith("v_") -> {
                    (streams as? List<XVod>)?.filter { it.name.lowercase().contains(q) }?.take(30)?.forEach { s ->
                        results.add(newMovieSearchResponse(s.name, ItemRef("m", s.stream_id, s.name, s.container_extension ?: "mp4").toJson(), TvType.Movie) {
                            posterUrl = s.stream_icon
                        })
                    }
                }
                key.startsWith("s_") -> {
                    (streams as? List<XSeries>)?.filter { it.name.lowercase().contains(q) }?.take(30)?.forEach { s ->
                        results.add(newTvSeriesSearchResponse(s.name, ItemRef("s", s.series_id, s.name).toJson(), TvType.TvSeries) {
                            posterUrl = s.cover
                        })
                    }
                }
                key.startsWith("l_") -> {
                    (streams as? List<XLive>)?.filter { it.name.lowercase().contains(q) }?.take(30)?.forEach { s ->
                        results.add(newMovieSearchResponse(s.name, ItemRef("l", s.stream_id, s.name).toJson(), TvType.Live) {
                            posterUrl = s.stream_icon
                        })
                    }
                }
            }
        }

        // ── If no cached results, search on-demand per-category (limited) ──
        if (results.size < 5) {
            val c = cfg()
            if (c != null) {
                try {
                    val encUser = URLEncoder.encode(c.user, "UTF-8")
                    val encPass = URLEncoder.encode(c.pass, "UTF-8")
                    val apiBase = "${c.server}/player_api.php?username=$encUser&password=$encPass"

                    val vodCats = cachedXtreamVodCats?.let { tryParseJson<List<XCat>>(it) } ?: emptyList()
                    for (cat in vodCats.take(10)) {
                        if (results.size >= 20) break
                        val catId = cat.category_id ?: continue
                        val key = "v_$catId"

                        val vodStreams: List<XVod> = if (cachedCatStreams.containsKey(key)) {
                            cachedCatStreams[key] as? List<XVod> ?: emptyList()
                        } else {
                            val text = RawHttp.apiGet("$apiBase&action=get_vod_streams&category_id=$catId", 10000)
                            if (text != null) {
                                tryParseJson<List<XVod>>(text)?.also { parsed ->
                                    if (cachedCatStreams.size < MAX_CACHED_CATEGORIES) {
                                        cachedCatStreams[key] = parsed
                                    }
                                } ?: emptyList()
                            } else emptyList()
                        }
                        vodStreams.filter { it.name.lowercase().contains(q) }.take(10).forEach { s ->
                            results.add(newMovieSearchResponse(s.name, ItemRef("m", s.stream_id, s.name, s.container_extension ?: "mp4").toJson(), TvType.Movie) {
                                posterUrl = s.stream_icon
                            })
                        }
                    }

                    val serCats = cachedXtreamSeriesCats?.let { tryParseJson<List<XCat>>(it) } ?: emptyList()
                    for (cat in serCats.take(10)) {
                        if (results.size >= 30) break
                        val catId = cat.category_id ?: continue
                        val key = "s_$catId"

                        val serStreams: List<XSeries> = if (cachedCatStreams.containsKey(key)) {
                            cachedCatStreams[key] as? List<XSeries> ?: emptyList()
                        } else {
                            val text = RawHttp.apiGet("$apiBase&action=get_series&category_id=$catId", 10000)
                            if (text != null) {
                                tryParseJson<List<XSeries>>(text)?.also { parsed ->
                                    if (cachedCatStreams.size < MAX_CACHED_CATEGORIES) {
                                        cachedCatStreams[key] = parsed
                                    }
                                } ?: emptyList()
                            } else emptyList()
                        }
                        serStreams.filter { it.name.lowercase().contains(q) }.take(10).forEach { s ->
                            results.add(newTvSeriesSearchResponse(s.name, ItemRef("s", s.series_id, s.name).toJson(), TvType.TvSeries) {
                                posterUrl = s.cover
                            })
                        }
                    }
                } catch (_: Throwable) {
                }
            }
        }

        return if (results.isEmpty()) null else results
    }

    // ═══════════════════════════════════════════════════════════════════
    //  LOAD DETAILS
    // ═══════════════════════════════════════════════════════════════════

    override suspend fun load(url: String): LoadResponse? {
        // EntryRef: has "url" and "group" keys (M3U entries)
        if (url.contains("\"url\"") && url.contains("\"group\"")) {
            val ref = tryParseJson<EntryRef>(url)
            if (ref != null && ref.type != null) {
                return loadM3UEntry(ref)
            }
        }

        // ItemRef: has "t", "id", and "n" keys (from Xtream category pages)
        if (url.contains("\"t\"") && url.contains("\"id\"") && url.contains("\"n\"")) {
            val itemRef = tryParseJson<ItemRef>(url)
            if (itemRef != null) {
                return loadXtreamEntry(itemRef)
            }
        }

        // Fallback
        val ref = tryParseJson<EntryRef>(url)
        if (ref != null && ref.type != null) return loadM3UEntry(ref)
        val itemRef = tryParseJson<ItemRef>(url)
        if (itemRef != null) return loadXtreamEntry(itemRef)

        return null
    }

    private suspend fun loadM3UEntry(ref: EntryRef): LoadResponse? {
        return when (ref.type) {
            "live" -> {
                newMovieLoadResponse(ref.name, ref.toJson(), TvType.Live, ref.toJson()) {
                    posterUrl = ref.logo
                }
            }
            "movie" -> {
                newMovieLoadResponse(ref.name, ref.toJson(), TvType.Movie, ref.toJson()) {
                    posterUrl = ref.logo
                }
            }
            "movie_cat" -> {
                val allEntries = cachedM3U ?: return null
                val catEntries = allEntries.filter { it.group == ref.group && it.type == "movie" }
                if (catEntries.isEmpty()) return null

                val episodes = catEntries.mapIndexed { idx, entry ->
                    val epRef = EntryRef(entry.streamUrl, "movie", entry.name, entry.group, entry.logo)
                    newEpisode(epRef.toJson()) {
                        name = entry.name
                        season = 1
                        episode = idx + 1
                        posterUrl = entry.logo
                    }
                }
                newTvSeriesLoadResponse(ref.group, ref.toJson(), TvType.TvSeries, episodes) {
                    plot = "${catEntries.size} movies in this category"
                }
            }
            "series_cat" -> {
                val allEntries = cachedM3U ?: return null
                val catEntries = allEntries.filter { it.group == ref.group && it.type == "series" }
                if (catEntries.isEmpty()) return null

                val uniqueSeries = catEntries.groupBy { it.seriesName.ifBlank { extractSeriesName(it.name) } }
                val episodes = uniqueSeries.entries.mapIndexed { idx, (seriesName, sEpisodes) ->
                    val epRef = EntryRef("", "series_browse", seriesName, ref.group, null, seriesName)
                    newEpisode(epRef.toJson()) {
                        name = seriesName
                        season = 1
                        episode = idx + 1
                        posterUrl = sEpisodes.first().logo
                    }
                }
                newTvSeriesLoadResponse(ref.group, ref.toJson(), TvType.TvSeries, episodes) {
                    plot = "${uniqueSeries.size} series in this category"
                }
            }
            "live_cat" -> {
                val allEntries = cachedM3U ?: return null
                val catEntries = allEntries.filter { it.group == ref.group && it.type == "live" }
                if (catEntries.isEmpty()) return null

                val episodes = catEntries.mapIndexed { idx, entry ->
                    val epRef = EntryRef(entry.streamUrl, "live", entry.name, entry.group, entry.logo)
                    newEpisode(epRef.toJson()) {
                        name = entry.name
                        season = 1
                        episode = idx + 1
                        posterUrl = entry.logo
                    }
                }
                newTvSeriesLoadResponse(ref.group, ref.toJson(), TvType.TvSeries, episodes) {
                    plot = "${catEntries.size} channels in this category"
                }
            }
            "series" -> {
                val allEntries = cachedM3U ?: return null
                val seriesName = ref.seriesName.ifBlank { extractSeriesName(ref.name) }
                val groupEntries = allEntries.filter {
                    (it.seriesName.ifBlank { extractSeriesName(it.name) }) == seriesName && it.type == "series"
                }

                if (groupEntries.isEmpty()) {
                    newMovieLoadResponse(ref.name, ref.toJson(), TvType.Movie, ref.toJson()) {
                        posterUrl = ref.logo
                    }
                } else if (groupEntries.size == 1) {
                    val entry = groupEntries.first()
                    val epRef = EntryRef(entry.streamUrl, "series", entry.name, entry.group, entry.logo, entry.seriesName)
                    newMovieLoadResponse(entry.name, epRef.toJson(), TvType.Movie, epRef.toJson()) {
                        posterUrl = entry.logo
                    }
                } else {
                    val episodes = groupEntries.map { entry ->
                        val epRef = EntryRef(entry.streamUrl, "series", entry.name, entry.group, entry.logo, entry.seriesName)
                        val (sNum, eNum) = parseSeasonEpisode(entry.name)
                        newEpisode(epRef.toJson()) {
                            name = entry.name
                            season = sNum
                            episode = eNum
                            posterUrl = entry.logo
                        }
                    }
                    newTvSeriesLoadResponse(seriesName, ref.toJson(), TvType.TvSeries, episodes) {
                        posterUrl = ref.logo
                    }
                }
            }
            else -> {
                newMovieLoadResponse(ref.name, ref.toJson(), TvType.Movie, ref.toJson()) {
                    posterUrl = ref.logo
                }
            }
        }
    }

    private suspend fun loadXtreamEntry(ref: ItemRef): LoadResponse? {
        val c = cfg() ?: return null

        return when (ref.t) {
            "l" -> newMovieLoadResponse(ref.n, ref.toJson(), TvType.Live, LinkData("l", ref.id).toJson()) {}
            "m" -> {
                val apiBase = "${c.server}/player_api.php?username=${c.user}&password=${c.pass}"
                val infoText = RawHttp.apiGet("$apiBase&action=get_vod_info&vod_id=${ref.id}", 15000)
                val info = if (!infoText.isNullOrEmpty()) tryParseJson<XVodInfo>(infoText) else null
                val detail = info?.info
                val ext = info?.movie_data?.container_extension ?: ref.e ?: "mp4"
                val streamId = info?.movie_data?.stream_id ?: ref.id
                newMovieLoadResponse(detail?.name ?: ref.n, ref.toJson(), TvType.Movie, LinkData("m", streamId, ext).toJson()) {
                    posterUrl = detail?.movie_image ?: detail?.cover
                    plot = detail?.plot
                    score = Score.from10(detail?.rating)
                }
            }
            "s" -> {
                val apiBase = "${c.server}/player_api.php?username=${c.user}&password=${c.pass}"
                val infoText = RawHttp.apiGet("$apiBase&action=get_series_info&series_id=${ref.id}", 15000)
                val info = if (!infoText.isNullOrEmpty()) tryParseJson<XSeriesInfo>(infoText) else null
                val detail = info?.info
                val epMap = info?.episodes
                if (epMap.isNullOrEmpty()) {
                    newMovieLoadResponse(detail?.name ?: ref.n, ref.toJson(), TvType.Movie, LinkData("m", ref.id, "mp4").toJson()) {
                        posterUrl = detail?.cover
                        plot = detail?.plot
                    }
                } else {
                    val episodes = mutableListOf<Episode>()
                    epMap.toSortedMap(compareBy { it.toIntOrNull() ?: 0 }).forEach { (_, eps) ->
                        eps.sortedBy { it.episode_num }.forEach { ep ->
                            val ext = ep.container_extension ?: "mp4"
                            episodes.add(newEpisode(LinkData("e", ep.id, ext).toJson()) {
                                name = ep.title ?: "S${ep.season_num} E${ep.episode_num}"
                                season = ep.season_num
                                episode = ep.episode_num
                                posterUrl = ep.info?.image
                            })
                        }
                    }
                    newTvSeriesLoadResponse(detail?.name ?: ref.n, ref.toJson(), TvType.TvSeries, episodes) {
                        posterUrl = detail?.cover
                        plot = detail?.plot
                        score = Score.from10(detail?.rating)
                    }
                }
            }
            else -> null
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  LOAD STREAM LINKS
    // ═══════════════════════════════════════════════════════════════════

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // EntryRef: has "url" and "group" keys (M3U mode)
        if (data.contains("\"url\"") && data.contains("\"group\"")) {
            val ref = tryParseJson<EntryRef>(data)
            if (ref != null && ref.type != null) {
                return loadM3ULinks(ref, callback)
            }
        }

        // ItemRef: has "t", "id", AND "n" keys (from category pages)
        if (data.contains("\"t\"") && data.contains("\"id\"") && data.contains("\"n\"")) {
            val itemRef = tryParseJson<ItemRef>(data)
            if (itemRef != null) {
                return loadItemRefLinks(itemRef, callback)
            }
        }

        // LinkData: has "t" and "id" but NO "n" key (direct play links)
        if (data.contains("\"t\"") && data.contains("\"id\"")) {
            val xtRef = tryParseJson<LinkData>(data)
            if (xtRef != null) {
                return loadXtreamLinks(xtRef, callback)
            }
        }

        return false
    }

    private suspend fun loadM3ULinks(ref: EntryRef, callback: (ExtractorLink) -> Unit): Boolean {
        val streamHeaders = mapOf("User-Agent" to "okhttp/4.12.0")

        if (ref.type == "series_browse") {
            val allEntries = cachedM3U ?: return false
            val seriesName = ref.seriesName.ifBlank { extractSeriesName(ref.name) }
            val episodes = allEntries.filter {
                (it.seriesName.ifBlank { extractSeriesName(it.name) }) == seriesName && it.type == "series"
            }
            if (episodes.isEmpty()) return false

            episodes.forEach { entry ->
                val url = entry.streamUrl
                val lower = url.lowercase()
                val (sNum, eNum) = parseSeasonEpisode(entry.name)
                val epLabel = "S${sNum}E${eNum} - ${entry.name}"

                if (lower.endsWith(".m3u8")) {
                    callback(
                        newExtractorLink(name, epLabel, url) {
                            quality = guessQuality(entry.name)
                            type = ExtractorLinkType.M3U8
                            headers = streamHeaders
                        }
                    )
                } else {
                    callback(
                        newExtractorLink(name, epLabel, url) {
                            quality = guessQuality(entry.name)
                            type = ExtractorLinkType.VIDEO
                            headers = streamHeaders
                        }
                    )
                    if (lower.contains("/series/")) {
                        val base = url.substringBeforeLast(".")
                        callback(
                            newExtractorLink(name, "$epLabel (HLS)", "$base.m3u8") {
                                quality = guessQuality(entry.name)
                                type = ExtractorLinkType.M3U8
                                headers = streamHeaders
                            }
                        )
                    }
                }
            }
            return true
        }

        val url = ref.url
        if (url.isEmpty()) return false

        val lower = url.lowercase()

        when {
            ref.type == "live" && lower.contains("/live/") -> {
                val base = url.substringBeforeLast(".")
                callback(
                    newExtractorLink(name, "HLS", "$base.m3u8") {
                        quality = guessQuality(ref.name)
                        type = ExtractorLinkType.M3U8
                        headers = streamHeaders
                    }
                )
                callback(
                    newExtractorLink(name, "MPEG-TS", "$base.ts") {
                        quality = guessQuality(ref.name)
                        type = ExtractorLinkType.VIDEO
                        headers = streamHeaders
                    }
                )
            }
            lower.endsWith(".m3u8") -> {
                callback(
                    newExtractorLink(name, "HLS", url) {
                        quality = guessQuality(ref.name)
                        type = ExtractorLinkType.M3U8
                        headers = streamHeaders
                    }
                )
            }
            else -> {
                val isHls = lower.endsWith(".m3u8")
                callback(
                    newExtractorLink(name, "Play", url) {
                        quality = guessQuality(ref.name)
                        type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        headers = streamHeaders
                    }
                )
                if (lower.contains("/series/") || lower.contains("/movie/")) {
                    val base = url.substringBeforeLast(".")
                    callback(
                        newExtractorLink(name, "HLS Alt", "$base.m3u8") {
                            quality = guessQuality(ref.name)
                            type = ExtractorLinkType.M3U8
                            headers = streamHeaders
                        }
                    )
                }
            }
        }
        return true
    }

    private suspend fun loadXtreamLinks(ld: LinkData, callback: (ExtractorLink) -> Unit): Boolean {
        val c = cfg() ?: return false
        val streamHeaders = mapOf("User-Agent" to "okhttp/4.12.0")

        when (ld.t) {
            "l" -> {
                val base = "${c.server}/live/${c.user}/${c.pass}/${ld.id}"
                callback(
                    newExtractorLink(name, "HLS", "$base.m3u8") {
                        quality = Qualities.Unknown.value
                        type = ExtractorLinkType.M3U8
                        headers = streamHeaders
                    }
                )
                callback(
                    newExtractorLink(name, "MPEG-TS", "$base.ts") {
                        quality = Qualities.Unknown.value
                        type = ExtractorLinkType.VIDEO
                        headers = streamHeaders
                    }
                )
            }
            "m" -> {
                val ext = ld.e ?: "mp4"
                val isHls = ext == "m3u8"
                callback(
                    newExtractorLink(name, "Movie", "${c.server}/movie/${c.user}/${c.pass}/${ld.id}.$ext") {
                        quality = Qualities.Unknown.value
                        type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        headers = streamHeaders
                    }
                )
            }
            "e" -> {
                val ext = ld.e ?: "mp4"
                val isHls = ext == "m3u8"
                callback(
                    newExtractorLink(name, "Episode", "${c.server}/series/${c.user}/${c.pass}/${ld.id}.$ext") {
                        quality = Qualities.Unknown.value
                        type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        headers = streamHeaders
                    }
                )
            }
        }
        return true
    }

    private suspend fun loadItemRefLinks(ref: ItemRef, callback: (ExtractorLink) -> Unit): Boolean {
        val c = cfg() ?: return false
        val streamHeaders = mapOf("User-Agent" to "okhttp/4.12.0")

        when (ref.t) {
            "s" -> {
                val apiBase = "${c.server}/player_api.php?username=${c.user}&password=${c.pass}"
                val infoText = RawHttp.apiGet("$apiBase&action=get_series_info&series_id=${ref.id}", 15000)
                if (infoText == null) return false
                val info = tryParseJson<XSeriesInfo>(infoText) ?: return false
                val epMap = info.episodes ?: return false
                if (epMap.isEmpty()) return false

                epMap.toSortedMap(compareBy { it.toIntOrNull() ?: 0 }).forEach { (_, eps) ->
                    eps.sortedBy { it.episode_num }.forEach { ep ->
                        val ext = ep.container_extension ?: "mp4"
                        val label = "S${ep.season_num}E${ep.episode_num}" +
                            (if (!ep.title.isNullOrBlank()) " - ${ep.title}" else "")
                        callback(
                            newExtractorLink(name, label, "${c.server}/series/${c.user}/${c.pass}/${ep.id}.$ext") {
                                quality = Qualities.Unknown.value
                                type = if (ext == "m3u8") ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                headers = streamHeaders
                            }
                        )
                    }
                }
                return true
            }
            "m" -> {
                val ext = ref.e ?: "mp4"
                callback(
                    newExtractorLink(name, "Movie", "${c.server}/movie/${c.user}/${c.pass}/${ref.id}.$ext") {
                        quality = Qualities.Unknown.value
                        type = if (ext == "m3u8") ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        headers = streamHeaders
                    }
                )
                return true
            }
            "l" -> {
                val base = "${c.server}/live/${c.user}/${c.pass}/${ref.id}"
                callback(
                    newExtractorLink(name, "HLS", "$base.m3u8") {
                        quality = Qualities.Unknown.value
                        type = ExtractorLinkType.M3U8
                        headers = streamHeaders
                    }
                )
                callback(
                    newExtractorLink(name, "MPEG-TS", "$base.ts") {
                        quality = Qualities.Unknown.value
                        type = ExtractorLinkType.VIDEO
                        headers = streamHeaders
                    }
                )
                return true
            }
        }
        return false
    }
}
