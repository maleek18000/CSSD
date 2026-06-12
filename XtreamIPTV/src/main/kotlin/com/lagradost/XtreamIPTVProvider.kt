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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
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

    suspend fun get(url: String, readTimeout: Int = 25000): String? = withContext(Dispatchers.IO) {
        val uri = try { URI(url) } catch (_: Exception) { return@withContext null }
        val referer = "${uri.scheme}://${uri.host}/"

        for (ua in userAgents) {
            try {
                val result = fetch(url, ua, readTimeout, mapOf(
                    "Referer" to referer,
                    "Accept" to "*/*"
                ))
                if (result != null && result.length > 10) return@withContext result
            } catch (_: Exception) { continue }
        }
        null
    }

    private fun fetch(url: String, userAgent: String, readTimeout: Int, extraHeaders: Map<String, String>): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", userAgent)
            extraHeaders.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.connectTimeout = 8000
            conn.readTimeout = readTimeout
            conn.instanceFollowRedirects = true

            val code = conn.responseCode
            if (code in 400..599) return null

            val reader = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8"))
            val sb = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                sb.append(line).append("\n")
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
// ═══════════════════════════════════════════════════════════════════

class XtreamIPTVProvider : MainAPI() {

    override var mainUrl = "http://example.com/username/password"
    override var name = "Xtream IPTV"
    override val supportedTypes = setOf(TvType.Live, TvType.Movie, TvType.TvSeries)
    override val hasMainPage = true
    override val hasQuickSearch = false

    // ═══════════════════════════════════════════════════════════════════
    //  URL HELPERS  (strip category filter ~ for HTTP requests)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Strip the category filter part (~...) from the URL before making HTTP requests.
     * Category filter format: M3U_URL~Category1~Category2~Category3
     */
    private fun cleanUrl(): String {
        val raw = mainUrl.trim()
        val tildeIdx = raw.indexOf('~')
        return if (tildeIdx >= 0) raw.substring(0, tildeIdx).trim() else raw
    }

    /**
     * Parse category filter from the URL.
     * Returns null if no filter (show all categories).
     * Returns list of category name fragments if filter specified.
     *
     * Example URL: http://server/get.php?...~Action~Comedy~Arabic
     *   → returns ["Action", "Comedy", "Arabic"]
     * Only categories whose name CONTAINS any filter fragment will be shown.
     */
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

        // M3U URL -> extract username & password from query params
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
                            "username" -> user = kv[1]
                            "password" -> pass = kv[1]
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

        // Pipe format: http://server|user|pass
        if (raw.contains("|")) {
            val p = raw.split("|")
            if (p.size >= 3) return Cfg(p[0].trimEnd('/'), p[1], p[2])
        }

        // Path format: http://server/user/pass
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

    // ═══════════════════════════════════════════════════════════════════
    //  HOME PAGE
    // ═══════════════════════════════════════════════════════════════════

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = cleanUrl()
        if (url.isEmpty() || url == "http://example.com/username/password") return null

        val lists = mutableListOf<HomePageList>()

        // ── Return from cache if already loaded ──
        val cached = cachedM3U
        if (cached != null && cached.isNotEmpty()) {
            buildM3UHomePage(cached, lists)
            if (lists.isNotEmpty()) return newHomePageResponse(lists, false)
        }

        // ── Try M3U download with 80s overall timeout ──
        val m3uText = withTimeoutOrNull(80000L) {
            RawHttp.get(url, readTimeout = 60000)
        }
        if (m3uText != null && (m3uText.startsWith("#EXTM3U") || m3uText.startsWith("#EXTINF"))) {
            val entries = parseM3U(m3uText)
            if (entries.isNotEmpty()) {
                cachedM3U = entries
                lists.clear()
                buildM3UHomePage(entries, lists)
                if (lists.isNotEmpty()) return newHomePageResponse(lists, false)
            }
        }

        // ── Try Xtream Codes API fallback ──
        val c = cfg()
        if (c != null) {
            try {
                val encUser = URLEncoder.encode(c.user, "UTF-8")
                val encPass = URLEncoder.encode(c.pass, "UTF-8")
                val apiBase = "${c.server}/player_api.php?username=$encUser&password=$encPass"

                // Movies - featured row
                val vodStreamsText = RawHttp.get("$apiBase&action=get_vod_streams", 15000)
                if (vodStreamsText != null) {
                    val streams = tryParseJson<List<XVod>>(vodStreamsText) ?: emptyList()
                    val homeItems = streams.take(20).map { s ->
                        newMovieSearchResponse(s.name, ItemRef("m", s.stream_id, s.name, s.container_extension ?: "mp4").toJson(), TvType.Movie) {
                            posterUrl = s.stream_icon
                        }
                    }
                    if (homeItems.isNotEmpty()) lists.add(HomePageList("\uD83C\uDFAC Featured Movies", homeItems))
                }

                // Series - featured row
                val seriesText = RawHttp.get("$apiBase&action=get_series", 15000)
                if (seriesText != null) {
                    val series = tryParseJson<List<XSeries>>(seriesText) ?: emptyList()
                    val homeItems = series.take(20).map { s ->
                        newTvSeriesSearchResponse(s.name, ItemRef("s", s.series_id, s.name).toJson(), TvType.TvSeries) {
                            posterUrl = s.cover
                        }
                    }
                    if (homeItems.isNotEmpty()) lists.add(HomePageList("\uD83C\uDFB6 Featured Series", homeItems))
                }

                // Live - featured row
                val liveStreamsText = RawHttp.get("$apiBase&action=get_live_streams", 15000)
                if (liveStreamsText != null) {
                    val streams = tryParseJson<List<XLive>>(liveStreamsText) ?: emptyList()
                    val homeItems = streams.take(20).map { s ->
                        newMovieSearchResponse(s.name, ItemRef("l", s.stream_id, s.name).toJson(), TvType.Live) {
                            posterUrl = s.stream_icon
                        }
                    }
                    if (homeItems.isNotEmpty()) lists.add(HomePageList("\uD83D\uDCFA Live TV", homeItems))
                }

                // Movie categories (sorted by stream count)
                val vodCatsText = RawHttp.get("$apiBase&action=get_vod_categories", 10000)
                if (vodCatsText != null && vodStreamsText != null) {
                    val cats = tryParseJson<List<XCat>>(vodCatsText) ?: emptyList()
                    val streams = tryParseJson<List<XVod>>(vodStreamsText) ?: emptyList()
                    val catNames = cats.associate { it.category_id to (it.category_name ?: "Movies") }
                    val catCards = streams.groupBy { it.category_id }
                        .mapNotNull { (catId, items) ->
                            val catName = catNames[catId] ?: return@mapNotNull null
                            catName to items.size
                        }
                        .sortedByDescending { it.second }
                        .map { (catName, count) ->
                            val catId = catNames.entries.firstOrNull { it.value == catName }?.key ?: ""
                            val ref = EntryRef("", "xtream_movie_cat", catName, catId)
                            newMovieSearchResponse("$catName ($count)", ref.toJson(), TvType.Movie) {}
                        }
                    if (catCards.isNotEmpty()) lists.add(HomePageList("\uD83D\uDCC2 Movie Categories", catCards))
                }

                // Series categories (sorted by series count)
                val seriesCatsText = RawHttp.get("$apiBase&action=get_series_categories", 10000)
                if (seriesCatsText != null && seriesText != null) {
                    val cats = tryParseJson<List<XCat>>(seriesCatsText) ?: emptyList()
                    val series = tryParseJson<List<XSeries>>(seriesText) ?: emptyList()
                    val catNames = cats.associate { it.category_id to (it.category_name ?: "Series") }
                    val catCards = series.groupBy { it.category_id }
                        .mapNotNull { (catId, items) ->
                            val catName = catNames[catId] ?: return@mapNotNull null
                            catName to items.size
                        }
                        .sortedByDescending { it.second }
                        .map { (catName, count) ->
                            val catId = catNames.entries.firstOrNull { it.value == catName }?.key ?: ""
                            val ref = EntryRef("", "xtream_series_cat", catName, catId)
                            newTvSeriesSearchResponse("$catName ($count)", ref.toJson(), TvType.TvSeries) {}
                        }
                    if (catCards.isNotEmpty()) lists.add(HomePageList("\uD83D\uDCC2 Series Categories", catCards))
                }

                // Live TV categories (sorted by stream count)
                val liveCatsText = RawHttp.get("$apiBase&action=get_live_categories", 10000)
                if (liveCatsText != null && liveStreamsText != null) {
                    val cats = tryParseJson<List<XCat>>(liveCatsText) ?: emptyList()
                    val streams = tryParseJson<List<XLive>>(liveStreamsText) ?: emptyList()
                    val catNames = cats.associate { it.category_id to (it.category_name ?: "Live TV") }
                    val catCards = streams.groupBy { it.category_id }
                        .mapNotNull { (catId, items) ->
                            val catName = catNames[catId] ?: return@mapNotNull null
                            catName to items.size
                        }
                        .sortedByDescending { it.second }
                        .map { (catName, count) ->
                            val catId = catNames.entries.firstOrNull { it.value == catName }?.key ?: ""
                            val ref = EntryRef("", "xtream_live_cat", catName, catId)
                            newMovieSearchResponse("$catName ($count)", ref.toJson(), TvType.Live) {}
                        }
                    if (catCards.isNotEmpty()) lists.add(HomePageList("\uD83D\uDCC2 Live TV Categories", catCards))
                }
            } catch (_: Exception) {}
        }

        return if (lists.isEmpty()) null else newHomePageResponse(lists, false)
    }

    /**
     * Build home page lists from parsed M3U entries.
     *
     * Home screen layout:
     *   1. 🎬 Featured Movies   — 20 movies across all categories
     *   2. 🎞️ Featured Series   — 20 series across all categories
     *   3. 📺 Live TV           — 20 live channels across all categories
     *   4. 📂 Movie Categories  — category cards (sorted by size)
     *   5. 📂 Series Categories — category cards (sorted by size)
     *   6. 📂 Live TV Categories— category cards (sorted by size)
     *
     * Category rows are sorted by content count (largest first).
     * Clicking a category card shows its content directly.
     *
     * If the user added a category filter (~Cat1~Cat2...) in the URL,
     * only matching categories appear, and featured rows only show items from those categories.
     */
    private fun buildM3UHomePage(entries: List<M3UEntry>, lists: MutableList<HomePageList>) {
        val filter = parseCategoryFilter()

        val allMovies = entries.filter { it.type == "movie" }
        val allSeries = entries.filter { it.type == "series" }
        val allLive = entries.filter { it.type == "live" }

        val movieGroups = allMovies.groupBy { it.group }
        val seriesGroups = allSeries.groupBy { it.group }
        val liveGroups = allLive.groupBy { it.group }

        // Apply category filter to groups
        val filteredMovieGroups = filterGroups(movieGroups, filter)
        val filteredSeriesGroups = filterGroups(seriesGroups, filter)
        val filteredLiveGroups = filterGroups(liveGroups, filter)

        val filteredMovies = filteredMovieGroups.values.flatten()
        val filteredSeries = filteredSeriesGroups.values.flatten()
        val filteredLive = filteredLiveGroups.values.flatten()

        // ══════════════════════════════════════════════════════════════
        //  FEATURED ROWS — sample content across filtered categories
        // ══════════════════════════════════════════════════════════════

        // Featured Movies (pick 20)
        if (filteredMovies.isNotEmpty()) {
            val homeItems = filteredMovies.take(20).map { entry ->
                val ref = EntryRef(entry.streamUrl, "movie", entry.name, entry.group, entry.logo)
                newMovieSearchResponse(entry.name, ref.toJson(), TvType.Movie) { posterUrl = entry.logo }
            }
            lists.add(HomePageList("\uD83C\uDFAC Featured Movies", homeItems))
        }

        // Featured Series (pick 20 unique series)
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
            lists.add(HomePageList("\uD83C\uDFB6 Featured Series", homeItems))
        }

        // Live TV (pick 20 channels)
        if (filteredLive.isNotEmpty()) {
            val homeItems = filteredLive.take(20).map { entry ->
                val ref = EntryRef(entry.streamUrl, "live", entry.name, entry.group, entry.logo)
                newMovieSearchResponse(entry.name, ref.toJson(), TvType.Live) { posterUrl = entry.logo }
            }
            lists.add(HomePageList("\uD83D\uDCFA Live TV", homeItems))
        }

        // ══════════════════════════════════════════════════════════════
        //  CATEGORY ROWS — sorted by content count (largest first)
        //  Clicking a category card directly shows its content
        // ══════════════════════════════════════════════════════════════

        // Movie categories (sorted by size)
        if (filteredMovieGroups.isNotEmpty()) {
            val catCards = filteredMovieGroups.entries
                .sortedByDescending { it.value.size }
                .map { (group, items) ->
                    val ref = EntryRef("", "movie_cat", group, group)
                    newMovieSearchResponse("$group (${items.size})", ref.toJson(), TvType.Movie) {}
                }
            if (catCards.isNotEmpty()) {
                lists.add(HomePageList("\uD83D\uDCC2 Movie Categories", catCards))
            }
        }

        // Series categories (sorted by size)
        if (filteredSeriesGroups.isNotEmpty()) {
            val catCards = filteredSeriesGroups.entries
                .sortedByDescending { it.value.size }
                .map { (group, items) ->
                    val uniqueSeries = items.map { it.seriesName.ifBlank { extractSeriesName(it.name) } }.distinct().size
                    val ref = EntryRef("", "series_cat", group, group)
                    newTvSeriesSearchResponse("$group ($uniqueSeries)", ref.toJson(), TvType.TvSeries) {}
                }
            if (catCards.isNotEmpty()) {
                lists.add(HomePageList("\uD83D\uDCC2 Series Categories", catCards))
            }
        }

        // Live TV categories (sorted by size)
        if (filteredLiveGroups.isNotEmpty()) {
            val catCards = filteredLiveGroups.entries
                .sortedByDescending { it.value.size }
                .map { (group, items) ->
                    val ref = EntryRef("", "live_cat", group, group)
                    newMovieSearchResponse("$group (${items.size})", ref.toJson(), TvType.Live) {}
                }
            if (catCards.isNotEmpty()) {
                lists.add(HomePageList("\uD83D\uDCC2 Live TV Categories", catCards))
            }
        }
    }

    /**
     * Filter groups based on category filter from URL.
     * If no filter specified, returns all groups.
     * If filter specified, only returns groups matching any filter fragment.
     */
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

        val entries = cachedM3U ?: return null
        entries.filter {
            it.name.lowercase().contains(q) || it.group.lowercase().contains(q)
        }.take(30).forEach { entry ->
            val ref = EntryRef(entry.streamUrl, entry.type, entry.name, entry.group, entry.logo, entry.seriesName)
            when (entry.type) {
                "movie" -> results.add(newMovieSearchResponse(entry.name, ref.toJson(), TvType.Movie) { posterUrl = entry.logo })
                "series" -> results.add(newTvSeriesSearchResponse(entry.seriesName.ifBlank { entry.name }, ref.toJson(), TvType.TvSeries) { posterUrl = entry.logo })
                else -> results.add(newMovieSearchResponse(entry.name, ref.toJson(), TvType.Live) { posterUrl = entry.logo })
            }
        }
        return results
    }

    // ═══════════════════════════════════════════════════════════════════
    //  LOAD DETAILS
    // ═══════════════════════════════════════════════════════════════════

    override suspend fun load(url: String): LoadResponse? {
        // Try M3U mode first
        val ref = tryParseJson<EntryRef>(url)
        if (ref != null) {
            return loadM3UEntry(ref)
        }

        // Try Xtream API mode
        val itemRef = tryParseJson<ItemRef>(url)
        if (itemRef != null) {
            return loadXtreamEntry(itemRef)
        }

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
            // ── Category browser: Movie category card clicked ──
            "movie_cat" -> {
                val allEntries = cachedM3U ?: return null
                val catEntries = allEntries.filter { it.group == ref.group && it.type == "movie" }
                if (catEntries.isEmpty()) return null

                // Show each movie as its own season with 1 episode, so they're
                // listed as separate cards rather than numbered episodes
                val episodes = catEntries.mapIndexed { idx, entry ->
                    val epRef = EntryRef(entry.streamUrl, "movie", entry.name, entry.group, entry.logo)
                    newEpisode(epRef.toJson()) {
                        name = entry.name
                        season = idx + 1
                        episode = 1
                        posterUrl = entry.logo
                    }
                }
                newTvSeriesLoadResponse(ref.group, ref.toJson(), TvType.TvSeries, episodes) {
                    plot = "${catEntries.size} movies in this category"
                }
            }
            // ── Category browser: Series category card clicked ──
            "series_cat" -> {
                val allEntries = cachedM3U ?: return null
                val catEntries = allEntries.filter { it.group == ref.group && it.type == "series" }
                if (catEntries.isEmpty()) return null

                // Group by series name, show each series as its own "season"
                // This way each show appears as a separate season tab,
                // and clicking it opens the show's detail page with real seasons/episodes
                val uniqueSeries = catEntries.groupBy { it.seriesName.ifBlank { extractSeriesName(it.name) } }
                val episodes = uniqueSeries.entries.mapIndexed { idx, (seriesName, sEpisodes) ->
                    val first = sEpisodes.first()
                    val epRef = EntryRef(first.streamUrl, "series", seriesName, ref.group, first.logo, seriesName)
                    newEpisode(epRef.toJson()) {
                        name = seriesName
                        season = idx + 1
                        episode = 1
                        posterUrl = first.logo
                    }
                }
                newTvSeriesLoadResponse(ref.group, ref.toJson(), TvType.TvSeries, episodes) {
                    plot = "${uniqueSeries.size} series in this category"
                }
            }
            // ── Category browser: Live TV category card clicked ──
            "live_cat" -> {
                val allEntries = cachedM3U ?: return null
                val catEntries = allEntries.filter { it.group == ref.group && it.type == "live" }
                if (catEntries.isEmpty()) return null

                val episodes = catEntries.mapIndexed { idx, entry ->
                    val epRef = EntryRef(entry.streamUrl, "live", entry.name, entry.group, entry.logo)
                    newEpisode(epRef.toJson()) {
                        name = entry.name
                        season = idx + 1
                        episode = 1
                        posterUrl = entry.logo
                    }
                }
                newTvSeriesLoadResponse(ref.group, ref.toJson(), TvType.TvSeries, episodes) {
                    plot = "${catEntries.size} channels in this category"
                }
            }
            // ── Xtream API: Movie category card clicked ──
            "xtream_movie_cat" -> {
                val c = cfg() ?: return null
                val encUser = URLEncoder.encode(c.user, "UTF-8")
                val encPass = URLEncoder.encode(c.pass, "UTF-8")
                val apiBase = "${c.server}/player_api.php?username=$encUser&password=$encPass"
                val streamsText = RawHttp.get("$apiBase&action=get_vod_streams&category_id=${ref.group}", 15000) ?: return null
                val streams = tryParseJson<List<XVod>>(streamsText) ?: return null
                if (streams.isEmpty()) return null

                // Each movie as its own season
                val episodes = streams.mapIndexed { idx, s ->
                    newEpisode(ItemRef("m", s.stream_id, s.name, s.container_extension ?: "mp4").toJson()) {
                        name = s.name
                        season = idx + 1
                        episode = 1
                        posterUrl = s.stream_icon
                    }
                }
                newTvSeriesLoadResponse(ref.name, ref.toJson(), TvType.TvSeries, episodes) {
                    plot = "${streams.size} movies"
                }
            }
            // ── Xtream API: Series category card clicked ──
            "xtream_series_cat" -> {
                val c = cfg() ?: return null
                val encUser = URLEncoder.encode(c.user, "UTF-8")
                val encPass = URLEncoder.encode(c.pass, "UTF-8")
                val apiBase = "${c.server}/player_api.php?username=$encUser&password=$encPass"
                val seriesText = RawHttp.get("$apiBase&action=get_series&category_id=${ref.group}", 15000) ?: return null
                val series = tryParseJson<List<XSeries>>(seriesText) ?: return null
                if (series.isEmpty()) return null

                // Each series as its own season, clicking opens show detail
                val episodes = series.mapIndexed { idx, s ->
                    newEpisode(ItemRef("s", s.series_id, s.name).toJson()) {
                        name = s.name
                        season = idx + 1
                        episode = 1
                        posterUrl = s.cover
                    }
                }
                newTvSeriesLoadResponse(ref.name, ref.toJson(), TvType.TvSeries, episodes) {
                    plot = "${series.size} series"
                }
            }
            // ── Xtream API: Live TV category card clicked ──
            "xtream_live_cat" -> {
                val c = cfg() ?: return null
                val encUser = URLEncoder.encode(c.user, "UTF-8")
                val encPass = URLEncoder.encode(c.pass, "UTF-8")
                val apiBase = "${c.server}/player_api.php?username=$encUser&password=$encPass"
                val streamsText = RawHttp.get("$apiBase&action=get_live_streams&category_id=${ref.group}", 15000) ?: return null
                val streams = tryParseJson<List<XLive>>(streamsText) ?: return null
                if (streams.isEmpty()) return null

                // Each channel as its own season
                val episodes = streams.mapIndexed { idx, s ->
                    newEpisode(ItemRef("l", s.stream_id, s.name).toJson()) {
                        name = s.name
                        season = idx + 1
                        episode = 1
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
                val infoText = RawHttp.get("$apiBase&action=get_vod_info&vod_id=${ref.id}", 15000)
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
                val infoText = RawHttp.get("$apiBase&action=get_series_info&series_id=${ref.id}", 15000)
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
        // Try M3U entry first
        val m3uRef = tryParseJson<EntryRef>(data)
        if (m3uRef != null) {
            return loadM3ULinks(m3uRef, callback)
        }

        // Try Xtream entry
        val xtRef = tryParseJson<LinkData>(data)
        if (xtRef != null) {
            return loadXtreamLinks(xtRef, callback)
        }

        return false
    }

    private suspend fun loadM3ULinks(ref: EntryRef, callback: (ExtractorLink) -> Unit): Boolean {
        val url = ref.url
        if (url.isEmpty()) return false

        val lower = url.lowercase()
        val streamHeaders = mapOf("User-Agent" to "okhttp/4.12.0")

        when {
            // Live stream with /live/ path: offer both .m3u8 and .ts
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
            // Any stream ending in .m3u8
            lower.endsWith(".m3u8") -> {
                callback(
                    newExtractorLink(name, "HLS", url) {
                        quality = guessQuality(ref.name)
                        type = ExtractorLinkType.M3U8
                        headers = streamHeaders
                    }
                )
            }
            // Everything else (movie files, series episodes, etc.)
            else -> {
                val isHls = lower.endsWith(".m3u8")
                callback(
                    newExtractorLink(name, "Play", url) {
                        quality = guessQuality(ref.name)
                        type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        headers = streamHeaders
                    }
                )
                // For /series/ and /movie/ paths, also try .m3u8 variant
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
}
