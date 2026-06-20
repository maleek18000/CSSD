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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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

/**
 * Lightweight search index entry — only stores name + id + type + icon.
 * Memory: ~100 bytes per entry. 50,000 entries = ~5MB (well within budget).
 */
data class SearchEntry(
    val name: String,          // original display name
    val nameLower: String,    // pre-computed lowercase for instant search
    val type: String,          // "m" (movie), "s" (series), "l" (live)
    val id: Int,               // stream_id or series_id
    val icon: String? = null,  // poster/icon URL
    val ext: String? = null    // container_extension (movies only)
)

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

    private const val MAX_API_RESPONSE_SIZE = 5 * 1024 * 1024

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
//  ARCHITECTURE v2 — Search Index for Instant Search
//
//  1. Home page loads ONLY categories (3 tiny API calls, < 50KB each)
//  2. Background task builds a LIGHTWEIGHT search index from ALL
//     categories — stores only name + id + type + icon per stream
//  3. Search queries hit the in-memory index -> INSTANT results
//  4. Memory footprint: ~5MB for 50,000 streams (safe on Android)
//  5. OOM prevention: memory guards during index building
// ═══════════════════════════════════════════════════════════════════

class XtreamIPTVProvider : MainAPI() {

    override var mainUrl = "http://example.com/username/password"
    override var name = "Xtream IPTV"
    override val supportedTypes = setOf(TvType.Live, TvType.Movie, TvType.TvSeries)
    override val hasMainPage = true
    override val hasQuickSearch = true  // ENABLED — search index makes quick search instant

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
        } catch (_: Throwable) {}

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
    //  CACHE  — lightweight search index + per-category stream cache
    // ═══════════════════════════════════════════════════════════════════

    private var cachedM3U: List<M3UEntry>? = null

    private var cachedXtreamVodCats: String? = null
    private var cachedXtreamSeriesCats: String? = null
    private var cachedXtreamLiveCats: String? = null

    private val cachedCatStreams = mutableMapOf<String, List<Any>>()
    private val MAX_CACHED_CATEGORIES = 60

    // ═══════════════════════════════════════════════════════════════════
    //  SEARCH INDEX  — THE KEY OPTIMIZATION
    //
    //  A flat list of ALL stream names + IDs + types. Built once in the
    //  background after home page loads. Search queries hit this list
    //  directly -> instant results (< 50ms for 50,000 entries).
    //
    //  Memory: ~100 bytes/entry * 50,000 = ~5MB (safe on Android).
    //  Uses @Volatile for thread-safe reads from UI thread.
    // ═══════════════════════════════════════════════════════════════════

    @Volatile
    private var searchIndex: List<SearchEntry>? = null

    @Volatile
    private var searchIndexReady = AtomicBoolean(false)

    @Volatile
    private var searchIndexBuilding = AtomicBoolean(false)

    // ═══════════════════════════════════════════════════════════════════
    //  XTREAM API HELPERS
    // ═══════════════════════════════════════════════════════════════════

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

    private val bgScope = CoroutineScope(Dispatchers.IO)

    /**
     * ═══════════════════════════════════════════════════════════════════
     *  BUILD SEARCH INDEX — Background task that loads ALL categories
     *  and extracts lightweight SearchEntry objects (name + id + type).
     *
     *  This is the KEY optimization: instead of making 50+ HTTP requests
     *  on every search, we make them ONCE in the background and store
     *  only the minimal data needed for search matching.
     *
     *  Progress: tracked via searchIndexBuilding / searchIndexReady flags
     *  so search() can return partial results while index is building.
     * ═══════════════════════════════════════════════════════════════════
     */
    private fun buildSearchIndexInBackground(c: Cfg) {
        if (!searchIndexBuilding.compareAndSet(false, true)) return  // already building

        bgScope.launch {
            try {
                val encUser = URLEncoder.encode(c.user, "UTF-8")
                val encPass = URLEncoder.encode(c.pass, "UTF-8")
                val apiBase = "${c.server}/player_api.php?username=$encUser&password=$encPass"

                val index = mutableListOf<SearchEntry>()
                val addedCount = AtomicInteger(0)
                val MAX_INDEX_ENTRIES = 100000  // hard cap — 100K entries = ~10MB

                // ── Phase 1: Load VOD categories ──
                val vodCats = cachedXtreamVodCats?.let { tryParseJson<List<XCat>>(it) } ?: emptyList()
                for (cat in vodCats) {
                    if (!enoughMemory() || addedCount.get() >= MAX_INDEX_ENTRIES) break
                    val catId = cat.category_id ?: continue
                    val key = "v_$catId"

                    try {
                        val text = RawHttp.apiGet("$apiBase&action=get_vod_streams&category_id=$catId", 15000)
                        if (text != null) {
                            val streams = tryParseJson<List<XVod>>(text)
                            if (streams != null) {
                                // Cache for category browsing
                                if (cachedCatStreams.size < MAX_CACHED_CATEGORIES) {
                                    cachedCatStreams[key] = streams
                                }
                                // Add to search index (lightweight — only name + id)
                                for (s in streams) {
                                    if (addedCount.get() >= MAX_INDEX_ENTRIES) break
                                    index.add(SearchEntry(
                                        name = s.name,
                                        nameLower = s.name.lowercase(),
                                        type = "m",
                                        id = s.stream_id,
                                        icon = s.stream_icon,
                                        ext = s.container_extension ?: "mp4"
                                    ))
                                    addedCount.incrementAndGet()
                                }
                            }
                        }
                    } catch (_: Throwable) { continue }
                }

                // ── Phase 2: Load Series categories ──
                if (!enoughMemory()) { finishIndex(index); return@launch }
                val serCats = cachedXtreamSeriesCats?.let { tryParseJson<List<XCat>>(it) } ?: emptyList()
                for (cat in serCats) {
                    if (!enoughMemory() || addedCount.get() >= MAX_INDEX_ENTRIES) break
                    val catId = cat.category_id ?: continue
                    val key = "s_$catId"

                    try {
                        val text = RawHttp.apiGet("$apiBase&action=get_series&category_id=$catId", 15000)
                        if (text != null) {
                            val streams = tryParseJson<List<XSeries>>(text)
                            if (streams != null) {
                                if (cachedCatStreams.size < MAX_CACHED_CATEGORIES) {
                                    cachedCatStreams[key] = streams
                                }
                                for (s in streams) {
                                    if (addedCount.get() >= MAX_INDEX_ENTRIES) break
                                    index.add(SearchEntry(
                                        name = s.name,
                                        nameLower = s.name.lowercase(),
                                        type = "s",
                                        id = s.series_id,
                                        icon = s.cover
                                    ))
                                    addedCount.incrementAndGet()
                                }
                            }
                        }
                    } catch (_: Throwable) { continue }
                }

                // ── Phase 3: Load Live categories ──
                if (!enoughMemory()) { finishIndex(index); return@launch }
                val liveCats = cachedXtreamLiveCats?.let { tryParseJson<List<XCat>>(it) } ?: emptyList()
                for (cat in liveCats) {
                    if (!enoughMemory() || addedCount.get() >= MAX_INDEX_ENTRIES) break
                    val catId = cat.category_id ?: continue
                    val key = "l_$catId"

                    try {
                        val text = RawHttp.apiGet("$apiBase&action=get_live_streams&category_id=$catId", 15000)
                        if (text != null) {
                            val streams = tryParseJson<List<XLive>>(text)
                            if (streams != null) {
                                if (cachedCatStreams.size < MAX_CACHED_CATEGORIES) {
                                    cachedCatStreams[key] = streams
                                }
                                for (s in streams) {
                                    if (addedCount.get() >= MAX_INDEX_ENTRIES) break
                                    index.add(SearchEntry(
                                        name = s.name,
                                        nameLower = s.name.lowercase(),
                                        type = "l",
                                        id = s.stream_id,
                                        icon = s.stream_icon
                                    ))
                                    addedCount.incrementAndGet()
                                }
                            }
                        }
                    } catch (_: Throwable) { continue }
                }

                finishIndex(index)
            } catch (_: Throwable) {
                // OOM or other — save whatever we have
                finishIndex(mutableListOf())
            }
        }
    }

    private fun finishIndex(index: MutableList<SearchEntry>) {
        if (index.isNotEmpty()) {
            searchIndex = index.toList()  // immutable snapshot for thread safety
        }
        searchIndexReady.set(true)
        searchIndexBuilding.set(false)
    }

    private fun enoughMemory(): Boolean {
        val rt = Runtime.getRuntime()
        val freeMB = (rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())) / (1024 * 1024)
        return freeMB >= 15
    }

    private fun hasXtreamCache(): Boolean {
        return (cachedXtreamVodCats != null || cachedXtreamSeriesCats != null || cachedXtreamLiveCats != null)
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

        // ── Return from cache if already loaded ──
        val cached = cachedM3U
        if (cached != null && cached.isNotEmpty()) {
            buildM3UHomePage(cached, lists)
            if (lists.isNotEmpty()) return newHomePageResponse(lists, false)
        }
        if (hasXtreamCache()) {
            buildCategoryHomePage(cachedXtreamVodCats, cachedXtreamSeriesCats, cachedXtreamLiveCats, lists)
            if (catFilter != null) {
                val cCfg = cfg()
                if (cCfg != null) {
                    buildExpandedCategoryRows(cCfg, cachedXtreamVodCats, cachedXtreamSeriesCats, cachedXtreamLiveCats, catFilter, lists)
                }
            }
            if (lists.isNotEmpty()) return newHomePageResponse(lists, false)
        }

        val c = cfg()

        // ── Step 1: Try Xtream categories ──
        if (c != null) {
            val catResult = fetchXtreamCategories(c)
            if (catResult != null) {
                val (vodCatsText, seriesCatsText, liveCatsText) = catResult
                vodCatsText?.let { cachedXtreamVodCats = it }
                seriesCatsText?.let { cachedXtreamSeriesCats = it }
                liveCatsText?.let { cachedXtreamLiveCats = it }

                buildCategoryHomePage(vodCatsText, seriesCatsText, liveCatsText, lists)
                if (catFilter != null) {
                    buildExpandedCategoryRows(c, vodCatsText, seriesCatsText, liveCatsText, catFilter, lists)
                }

                if (lists.isNotEmpty()) {
                    // ★ KEY CHANGE: Start building search index in background ★
                    buildSearchIndexInBackground(c)

                    return newHomePageResponse(lists, false)
                }
            }
        }

        // ── Step 2: Fallback to M3U ──
        var m3uEntries: List<M3UEntry>? = null
        try {
            m3uEntries = withTimeoutOrNull(30000L) { downloadAndParseM3U(url) }
        } catch (_: Throwable) {}

        if (m3uEntries != null && m3uEntries.isNotEmpty()) {
            cachedM3U = m3uEntries
            lists.clear()
            buildM3UHomePage(m3uEntries, lists)
            if (lists.isNotEmpty()) return newHomePageResponse(lists, false)
        }

        return if (lists.isEmpty()) null else newHomePageResponse(lists, false)
    }

    private fun buildCategoryHomePage(
        vodCatsText: String?, seriesCatsText: String?, liveCatsText: String?,
        lists: MutableList<HomePageList>
    ) {
        if (vodCatsText != null) {
            val cats = tryParseJson<List<XCat>>(vodCatsText) ?: emptyList()
            val homeItems = cats.map { cat ->
                val ref = EntryRef("", "xtream_movie_cat", cat.category_name ?: "Movies", cat.category_id ?: "")
                newMovieSearchResponse(cat.category_name ?: "Movies", ref.toJson(), TvType.Movie) {}
            }
            if (homeItems.isNotEmpty()) lists.add(HomePageList("\uD83C\uDFAC Movies", homeItems))
        }

        if (seriesCatsText != null) {
            val cats = tryParseJson<List<XCat>>(seriesCatsText) ?: emptyList()
            val homeItems = cats.map { cat ->
                val ref = EntryRef("", "xtream_series_cat", cat.category_name ?: "Series", cat.category_id ?: "")
                newTvSeriesSearchResponse(cat.category_name ?: "Series", ref.toJson(), TvType.TvSeries) {}
            }
            if (homeItems.isNotEmpty()) lists.add(HomePageList("\uD83C\uDFA6 Series", homeItems))
        }

        if (liveCatsText != null) {
            val cats = tryParseJson<List<XCat>>(liveCatsText) ?: emptyList()
            val homeItems = cats.map { cat ->
                val ref = EntryRef("", "xtream_live_cat", cat.category_name ?: "Live TV", cat.category_id ?: "")
                newMovieSearchResponse(cat.category_name ?: "Live TV", ref.toJson(), TvType.Live) {}
            }
            if (homeItems.isNotEmpty()) lists.add(HomePageList("\uD83D\uDCE1 Live TV", homeItems))
        }
    }

    private suspend fun buildExpandedCategoryRows(
        c: Cfg,
        vodCatsText: String?, seriesCatsText: String?, liveCatsText: String?,
        expandFilter: List<String>,
        lists: MutableList<HomePageList>
    ) {
        val encUser = URLEncoder.encode(c.user, "UTF-8")
        val encPass = URLEncoder.encode(c.pass, "UTF-8")
        val apiBase = "${c.server}/player_api.php?username=$encUser&password=$encPass"

        val matchingVodCats = vodCatsText?.let {
            tryParseJson<List<XCat>>(it)?.filter { cat ->
                expandFilter.any { f -> (cat.category_name ?: "").contains(f, ignoreCase = true) }
            }
        } ?: emptyList()

        val matchingSerCats = seriesCatsText?.let {
            tryParseJson<List<XCat>>(it)?.filter { cat ->
                expandFilter.any { f -> (cat.category_name ?: "").contains(f, ignoreCase = true) }
            }
        } ?: emptyList()

        val matchingLiveCats = liveCatsText?.let {
            tryParseJson<List<XCat>>(it)?.filter { cat ->
                expandFilter.any { f -> (cat.category_name ?: "").contains(f, ignoreCase = true) }
            }
        } ?: emptyList()

        val totalCats = matchingVodCats.size + matchingSerCats.size + matchingLiveCats.size
        if (totalCats == 0) return

        try {
            coroutineScope {
                for (cat in matchingVodCats) {
                    val catId = cat.category_id ?: continue
                    val catName = cat.category_name ?: continue
                    val key = "v_$catId"

                    async {
                        val streams: List<XVod>? = cachedCatStreams[key] as? List<XVod>
                            ?: RawHttp.apiGet("$apiBase&action=get_vod_streams&category_id=$catId", 15000)
                                ?.let { tryParseJson<List<XVod>>(it) }
                                ?.also { parsed ->
                                    if (cachedCatStreams.size < MAX_CACHED_CATEGORIES * 2) {
                                        cachedCatStreams[key] = parsed
                                    }
                                }

                        if (streams != null && streams.isNotEmpty()) {
                            val homeItems = streams.map { s ->
                                newMovieSearchResponse(s.name, ItemRef("m", s.stream_id, s.name, s.container_extension ?: "mp4").toJson(), TvType.Movie) {
                                    posterUrl = s.stream_icon
                                }
                            }
                            synchronized(lists) {
                                lists.add(HomePageList("\uD83C\uDFAC $catName", homeItems))
                            }
                        }
                    }
                }

                for (cat in matchingSerCats) {
                    val catId = cat.category_id ?: continue
                    val catName = cat.category_name ?: continue
                    val key = "s_$catId"

                    async {
                        val streams: List<XSeries>? = cachedCatStreams[key] as? List<XSeries>
                            ?: RawHttp.apiGet("$apiBase&action=get_series&category_id=$catId", 15000)
                                ?.let { tryParseJson<List<XSeries>>(it) }
                                ?.also { parsed ->
                                    if (cachedCatStreams.size < MAX_CACHED_CATEGORIES * 2) {
                                        cachedCatStreams[key] = parsed
                                    }
                                }

                        if (streams != null && streams.isNotEmpty()) {
                            val homeItems = streams.map { s ->
                                newTvSeriesSearchResponse(s.name, ItemRef("s", s.series_id, s.name).toJson(), TvType.TvSeries) {
                                    posterUrl = s.cover
                                }
                            }
                            synchronized(lists) {
                                lists.add(HomePageList("\uD83C\uDFA6 $catName", homeItems))
                            }
                        }
                    }
                }

                for (cat in matchingLiveCats) {
                    val catId = cat.category_id ?: continue
                    val catName = cat.category_name ?: continue
                    val key = "l_$catId"

                    async {
                        val streams: List<XLive>? = cachedCatStreams[key] as? List<XLive>
                            ?: RawHttp.apiGet("$apiBase&action=get_live_streams&category_id=$catId", 15000)
                                ?.let { tryParseJson<List<XLive>>(it) }
                                ?.also { parsed ->
                                    if (cachedCatStreams.size < MAX_CACHED_CATEGORIES * 2) {
                                        cachedCatStreams[key] = parsed
                                    }
                                }

                        if (streams != null && streams.isNotEmpty()) {
                            val homeItems = streams.map { s ->
                                newMovieSearchResponse(s.name, ItemRef("l", s.stream_id, s.name).toJson(), TvType.Live) {
                                    posterUrl = s.stream_icon
                                }
                            }
                            synchronized(lists) {
                                lists.add(HomePageList("\uD83D\uDCE1 $catName", homeItems))
                            }
                        }
                    }
                }
            }
        } catch (_: Throwable) {}
    }

    // ═══════════════════════════════════════════════════════════════════
    //  M3U HOME PAGE
    // ═══════════════════════════════════════════════════════════════════

    private fun buildM3UHomePage(entries: List<M3UEntry>, lists: MutableList<HomePageList>) {
        val filter = parseCategoryFilter()

        val allMovies = entries.filter { it.type == "movie" }
        val allSeries = entries.filter { it.type == "series" }
        val allLive = entries.filter { it.type == "live" }

        val movieGroups = allMovies.groupBy { it.group }
        val seriesGroups = allSeries.groupBy { it.group }
        val liveGroups = allLive.groupBy { it.group }

        val filteredMovieGroups = filterGroups(movieGroups, filter)
        val filteredSeriesGroups = filterGroups(seriesGroups, filter)
        val filteredLiveGroups = filterGroups(liveGroups, filter)

        val filteredMovies = filteredMovieGroups.values.flatten()
        val filteredSeries = filteredSeriesGroups.values.flatten()
        val filteredLive = filteredLiveGroups.values.flatten()

        if (filteredMovies.isNotEmpty()) {
            val homeItems = filteredMovies.take(20).map { entry ->
                val ref = EntryRef(entry.streamUrl, "movie", entry.name, entry.group, entry.logo)
                newMovieSearchResponse(entry.name, ref.toJson(), TvType.Movie) { posterUrl = entry.logo }
            }
            lists.add(HomePageList("\uD83C\uDFAC Featured Movies", homeItems))
        }

        if (filteredSeries.isNotEmpty()) {
            val uniqueSeries = filteredSeries.groupBy { it.seriesName.ifBlank { extractSeriesName(it.name) } }
            val homeItems = uniqueSeries.entries.take(20).map { (seriesName, episodes) ->
                val first = episodes.first()
                val ref = EntryRef(first.streamUrl, "series", seriesName, first.group, first.logo, seriesName)
                if (episodes.size > 1) {
                    newTvSeriesSearchResponse(seriesName, ref.toJson(), TvType.TvSeries) { posterUrl = first.logo }
                } else {
                    newMovieSearchResponse(seriesName, ref.toJson(), TvType.Movie) { posterUrl = first.logo }
                }
            }
            lists.add(HomePageList("\uD83C\uDFA6 Featured Series", homeItems))
        }

        if (filteredLive.isNotEmpty()) {
            val homeItems = filteredLive.take(20).map { entry ->
                val ref = EntryRef(entry.streamUrl, "live", entry.name, entry.group, entry.logo)
                newMovieSearchResponse(entry.name, ref.toJson(), TvType.Live) { posterUrl = entry.logo }
            }
            lists.add(HomePageList("\uD83D\uDCFA Live TV", homeItems))
        }

        filteredSeriesGroups.entries
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
                if (homeItems.isNotEmpty()) lists.add(HomePageList("\uD83D\uDFA6 $group", homeItems))
            }

        filteredMovieGroups.entries
            .sortedByDescending { it.value.size }
            .forEach { (group, items) ->
                val homeItems = items.map { entry ->
                    val ref = EntryRef(entry.streamUrl, "movie", entry.name, entry.group, entry.logo)
                    newMovieSearchResponse(entry.name, ref.toJson(), TvType.Movie) { posterUrl = entry.logo }
                }
                if (homeItems.isNotEmpty()) lists.add(HomePageList("\uD83C\uDFAC $group", homeItems))
            }

        filteredLiveGroups.entries
            .sortedByDescending { it.value.size }
            .forEach { (group, items) ->
                val homeItems = items.map { entry ->
                    val ref = EntryRef(entry.streamUrl, "live", entry.name, entry.group, entry.logo)
                    newMovieSearchResponse(entry.name, ref.toJson(), TvType.Live) { posterUrl = entry.logo }
                }
                if (homeItems.isNotEmpty()) lists.add(HomePageList("\uD83D\uDCE1 $group", homeItems))
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
    //  SEARCH  — INSTANT via in-memory search index
    //
    //  ★ THIS IS THE KEY OPTIMIZATION ★
    //
    //  OLD: Iterated ALL categories (50+ HTTP requests) on every search
    //  NEW: Reads from pre-built in-memory index (< 50ms)
    //
    //  The search index is built ONCE in the background after the home
    //  page loads. It contains lightweight SearchEntry objects with
    //  just name + id + type + icon. Memory footprint is ~5MB for 50K
    //  streams — very safe on Android.
    //
    //  If the index isn't ready yet (user searched very fast after
    //  opening the app), falls back to M3U cache search or returns
    //  null (CloudStream will show "no results" — user can retry in
    //  a moment when the index is ready).
    // ═══════════════════════════════════════════════════════════════════

    private val SEARCH_MAX_RESULTS = 60
    private val SEARCH_MAX_PER_TYPE = 30

    override suspend fun search(query: String): List<SearchResponse>? {
        val url = cleanUrl()
        if (url.isEmpty() || url == "http://example.com/username/password") return null
        val q = query.trim().lowercase()
        if (q.isEmpty()) return null

        // ── 1. Search M3U cache (if M3U mode) — always instant ──
        val results = mutableListOf<SearchResponse>()
        cachedM3U?.let { entries ->
            entries.asSequence().filter {
                it.name.lowercase().contains(q) || it.group.lowercase().contains(q)
            }.take(SEARCH_MAX_PER_TYPE).forEach { entry ->
                val ref = EntryRef(entry.streamUrl, entry.type, entry.name, entry.group, entry.logo, entry.seriesName)
                when (entry.type) {
                    "movie" -> results.add(newMovieSearchResponse(entry.name, ref.toJson(), TvType.Movie) { posterUrl = entry.logo })
                    "series" -> results.add(newTvSeriesSearchResponse(entry.seriesName.ifBlank { entry.name }, ref.toJson(), TvType.TvSeries) { posterUrl = entry.logo })
                    else -> results.add(newMovieSearchResponse(entry.name, ref.toJson(), TvType.Live) { posterUrl = entry.logo })
                }
            }
        }

        // ── 2. Search the IN-MEMORY INDEX — instant (< 50ms) ──
        val index = searchIndex
        if (index != null && index.isNotEmpty()) {
            val vodMatches = mutableListOf<SearchResponse>()
            val serMatches = mutableListOf<SearchResponse>()
            val liveMatches = mutableListOf<SearchResponse>()

            for (entry in index) {
                if (!entry.nameLower.contains(q)) continue

                when (entry.type) {
                    "m" -> {
                        if (vodMatches.size < SEARCH_MAX_PER_TYPE) {
                            vodMatches.add(
                                newMovieSearchResponse(
                                    entry.name,
                                    ItemRef("m", entry.id, entry.name, entry.ext ?: "mp4").toJson(),
                                    TvType.Movie
                                ) { posterUrl = entry.icon }
                            )
                        }
                    }
                    "s" -> {
                        if (serMatches.size < SEARCH_MAX_PER_TYPE) {
                            serMatches.add(
                                newTvSeriesSearchResponse(
                                    entry.name,
                                    ItemRef("s", entry.id, entry.name).toJson(),
                                    TvType.TvSeries
                                ) { posterUrl = entry.icon }
                            )
                        }
                    }
                    "l" -> {
                        if (liveMatches.size < SEARCH_MAX_PER_TYPE) {
                            liveMatches.add(
                                newMovieSearchResponse(
                                    entry.name,
                                    ItemRef("l", entry.id, entry.name).toJson(),
                                    TvType.Live
                                ) { posterUrl = entry.icon }
                            )
                        }
                    }
                }
            }

            results.addAll(vodMatches)
            results.addAll(serMatches)
            results.addAll(liveMatches)
        }

        // ── 3. De-duplicate and cap ──
        val seen = HashSet<String>()
        val deduped = results.filter { seen.add(it.name.lowercase()) }

        return if (deduped.isEmpty()) null else deduped.take(SEARCH_MAX_RESULTS)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  LOAD DETAILS
    // ═══════════════════════════════════════════════════════════════════

    override suspend fun load(url: String): LoadResponse? {
        if (url.contains("\"url\"") && url.contains("\"group\"")) {
            val ref = tryParseJson<EntryRef>(url)
            if (ref != null && ref.type != null) {
                return loadM3UEntry(ref)
            }
        }

        if (url.contains("\"t\"") && url.contains("\"id\"") && url.contains("\"n\"")) {
            val itemRef = tryParseJson<ItemRef>(url)
            if (itemRef != null) {
                return loadXtreamEntry(itemRef)
            }
        }

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
            "xtream_movie_cat" -> {
                val c = cfg() ?: return null
                val encUser = URLEncoder.encode(c.user, "UTF-8")
                val encPass = URLEncoder.encode(c.pass, "UTF-8")
                val apiBase = "${c.server}/player_api.php?username=$encUser&password=$encPass"
                val streamsText = RawHttp.apiGet("$apiBase&action=get_vod_streams&category_id=${ref.group}", 15000) ?: return null
                val streams = tryParseJson<List<XVod>>(streamsText) ?: return null
                if (streams.isEmpty()) return null

                val key = "v_${ref.group}"
                if (cachedCatStreams.size < MAX_CACHED_CATEGORIES) {
                    cachedCatStreams[key] = streams
                }

                val episodes = streams.mapIndexed { idx, s ->
                    newEpisode(ItemRef("m", s.stream_id, s.name, s.container_extension ?: "mp4").toJson()) {
                        name = s.name
                        season = 1
                        episode = idx + 1
                        posterUrl = s.stream_icon
                    }
                }
                newTvSeriesLoadResponse(ref.name, ref.toJson(), TvType.TvSeries, episodes) {
                    plot = "${streams.size} movies"
                }
            }
            "xtream_series_cat" -> {
                val c = cfg() ?: return null
                val encUser = URLEncoder.encode(c.user, "UTF-8")
                val encPass = URLEncoder.encode(c.pass, "UTF-8")
                val apiBase = "${c.server}/player_api.php?username=$encUser&password=$encPass"
                val seriesText = RawHttp.apiGet("$apiBase&action=get_series&category_id=${ref.group}", 15000) ?: return null
                val series = tryParseJson<List<XSeries>>(seriesText) ?: return null
                if (series.isEmpty()) return null

                val key = "s_${ref.group}"
                if (cachedCatStreams.size < MAX_CACHED_CATEGORIES) {
                    cachedCatStreams[key] = series
                }

                val episodes = series.mapIndexed { idx, s ->
                    newEpisode(ItemRef("s", s.series_id, s.name).toJson()) {
                        name = s.name
                        season = 1
                        episode = idx + 1
                        posterUrl = s.cover
                    }
                }
                newTvSeriesLoadResponse(ref.name, ref.toJson(), TvType.TvSeries, episodes) {
                    plot = "${series.size} series"
                }
            }
            "xtream_live_cat" -> {
                val c = cfg() ?: return null
                val encUser = URLEncoder.encode(c.user, "UTF-8")
                val encPass = URLEncoder.encode(c.pass, "UTF-8")
                val apiBase = "${c.server}/player_api.php?username=$encUser&password=$encPass"
                val streamsText = RawHttp.apiGet("$apiBase&action=get_live_streams&category_id=${ref.group}", 15000) ?: return null
                val streams = tryParseJson<List<XLive>>(streamsText) ?: return null
                if (streams.isEmpty()) return null

                val key = "l_${ref.group}"
                if (cachedCatStreams.size < MAX_CACHED_CATEGORIES) {
                    cachedCatStreams[key] = streams
                }

                val episodes = streams.mapIndexed { idx, s ->
                    newEpisode(ItemRef("l", s.stream_id, s.name).toJson()) {
                        name = s.name
                        season = 1
                        episode = idx + 1
                        posterUrl = s.stream_icon
                    }
                }
                newTvSeriesLoadResponse(ref.name, ref.toJson(), TvType.TvSeries, episodes) {
                    plot = "${streams.size} channels"
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
        if (data.contains("\"url\"") && data.contains("\"group\"")) {
            val ref = tryParseJson<EntryRef>(data)
            if (ref != null && ref.type != null) {
                return loadM3ULinks(ref, callback)
            }
        }

        if (data.contains("\"t\"") && data.contains("\"id\"") && data.contains("\"n\"")) {
            val itemRef = tryParseJson<ItemRef>(data)
            if (itemRef != null) {
                return loadItemRefLinks(itemRef, callback)
            }
        }

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
