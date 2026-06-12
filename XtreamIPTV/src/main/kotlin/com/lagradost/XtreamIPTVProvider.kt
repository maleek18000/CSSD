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
//  DATA CLASSES
// ═══════════════════════════════════════════════════════════════════

data class M3UEntry(
    val name: String,
    val group: String,
    val logo: String?,
    val streamUrl: String,
    val type: String
)

data class EntryRef(
    val url: String,
    val type: String,
    val name: String,
    val group: String,
    val logo: String? = null
)

// ═══════════════════════════════════════════════════════════════════
//  RAW HTTP CLIENT  (bypasses CloudStream's app.get to avoid 403)
// ═══════════════════════════════════════════════════════════════════

object RawHttp {

    private val userAgents = listOf(
        "okhttp/4.12.0",
        "IPTVSmarters/2",
        "TiviMate/4.7.0",
        "okhttp/4.9.3",
        "ExoPlayerLib/2.19.1",
        "Lavf/60.3.100"
    )

    /**
     * Download a URL using raw HttpURLConnection with IPTV User-Agents.
     * Tries each User-Agent until one returns valid data.
     */
    suspend fun get(url: String): String? = withContext(Dispatchers.IO) {
        for (ua in userAgents) {
            try {
                val result = fetch(url, ua)
                if (result != null && (result.startsWith("#EXTM3U") || result.startsWith("#EXTINF") || result.startsWith("["))) {
                    return@withContext result
                }
            } catch (_: Exception) { continue }
        }

        // Fallback: try with referer header
        try {
            val uri = URI(url)
            val referer = "${uri.scheme}://${uri.host}/"
            for (ua in userAgents) {
                try {
                    val result = fetch(url, ua, mapOf("Referer" to referer, "Accept" to "*/*"))
                    if (result != null && (result.startsWith("#EXTM3U") || result.startsWith("#EXTINF") || result.startsWith("["))) {
                        return@withContext result
                    }
                } catch (_: Exception) { continue }
            }
        } catch (_: Exception) {}

        null
    }

    private fun fetch(url: String, userAgent: String, extraHeaders: Map<String, String> = emptyMap()): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", userAgent)
            extraHeaders.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.connectTimeout = 15000
            conn.readTimeout = 60000  // M3U can be large
            conn.instanceFollowRedirects = true

            val code = conn.responseCode
            if (code == 403 || code == 521 || code == 503) return null

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
//  XTREAM API DATA CLASSES
// ═══════════════════════════════════════════════════════════════════

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
//  PROVIDER
// ═══════════════════════════════════════════════════════════════════

class XtreamIPTVProvider : MainAPI() {

    override var mainUrl = "http://example.com/username/password"
    override var name = "Xtream IPTV"
    override val supportedTypes = setOf(TvType.Live, TvType.Movie, TvType.TvSeries)
    override val hasMainPage = true
    override val hasQuickSearch = false

    // ═══════════════════════════════════════════════════════════════════
    //  CREDENTIAL PARSING
    // ═══════════════════════════════════════════════════════════════════

    private data class Cfg(val server: String, val user: String, val pass: String)

    private fun cfg(): Cfg? {
        val raw = mainUrl.trim()

        // M3U URL → extract username & password from query params
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
                    entries.add(M3UEntry(name, group, logo, url, detectType(url, group)))
                }
                i = j + 1
            } else {
                i++
            }
        }
        return entries
    }

    private fun extractAttr(line: String, attr: String): String? {
        val pattern = """$attr\s*=\s*["']([^"']*?)["']""".toRegex(RegexOption.IGNORE_CASE)
        return pattern.find(line)?.groupValues?.getOrNull(1)?.ifBlank { null }
    }

    private fun detectType(url: String, group: String): String {
        val lower = url.lowercase()
        val groupLower = group.lowercase()
        return when {
            lower.contains("/live/") -> "live"
            lower.contains("/movie/") -> "movie"
            lower.contains("/series/") -> "series"
            groupLower.contains("vod") || groupLower.contains("movie") ||
            groupLower.contains("film") || groupLower.contains("أفلام") -> "movie"
            groupLower.contains("series") || groupLower.contains("مسلسلات") -> "series"
            lower.endsWith(".ts") || lower.endsWith(".m3u8") -> "live"
            lower.endsWith(".mp4") || lower.endsWith(".mkv") -> "movie"
            else -> "live"
        }
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
        val url = mainUrl.trim()
        if (url.isEmpty() || url == "http://example.com/username/password") return null

        val lists = mutableListOf<HomePageList>()

        // Strategy 1: Try M3U download via raw HTTP
        try {
            val m3uText = RawHttp.get(url)
            if (m3uText != null && (m3uText.startsWith("#EXTM3U") || m3uText.startsWith("#EXTINF"))) {
                val entries = parseM3U(m3uText)
                if (entries.isNotEmpty()) {
                    cachedM3U = entries
                    buildM3UHomePage(entries, lists)
                    if (lists.isNotEmpty()) return newHomePageResponse(lists, false)
                }
            }
        } catch (_: Exception) {}

        // Strategy 2: Try M3U via CloudStream's app.get
        try {
            val m3uText = app.get(url, headers = mapOf("User-Agent" to "okhttp/4.12.0"), timeout = 30000L).text
            if (m3uText.startsWith("#EXTM3U") || m3uText.startsWith("#EXTINF")) {
                val entries = parseM3U(m3uText)
                if (entries.isNotEmpty()) {
                    cachedM3U = entries
                    lists.clear()
                    buildM3UHomePage(entries, lists)
                    if (lists.isNotEmpty()) return newHomePageResponse(lists, false)
                }
            }
        } catch (_: Exception) {}

        // Strategy 3: Try Xtream Codes API via raw HTTP
        val c = cfg()
        if (c != null) {
            try {
                val encUser = URLEncoder.encode(c.user, "UTF-8")
                val encPass = URLEncoder.encode(c.pass, "UTF-8")
                val apiBase = "${c.server}/player_api.php?username=$encUser&password=$encPass"

                // Live
                val liveCatsText = RawHttp.get("$apiBase&action=get_live_categories")
                val liveStreamsText = RawHttp.get("$apiBase&action=get_live_streams")
                if (liveCatsText != null && liveStreamsText != null) {
                    val cats = tryParseJson<List<XCat>>(liveCatsText) ?: emptyList()
                    val streams = tryParseJson<List<XLive>>(liveStreamsText) ?: emptyList()
                    val catNames = cats.associate { it.category_id to (it.category_name ?: "Live TV") }
                    streams.groupBy { it.category_id }.forEach { (catId, items) ->
                        val catName = catNames[catId] ?: "Live TV"
                        val homeItems = items.take(40).map { s ->
                            newMovieSearchResponse(s.name, ItemRef("l", s.stream_id, s.name).toJson(), TvType.Live) {
                                posterUrl = s.stream_icon
                            }
                        }
                        if (homeItems.isNotEmpty()) lists.add(HomePageList("\uD83D\uDCFA $catName", homeItems))
                    }
                }

                // Movies
                val vodCatsText = RawHttp.get("$apiBase&action=get_vod_categories")
                val vodStreamsText = RawHttp.get("$apiBase&action=get_vod_streams")
                if (vodCatsText != null && vodStreamsText != null) {
                    val cats = tryParseJson<List<XCat>>(vodCatsText) ?: emptyList()
                    val streams = tryParseJson<List<XVod>>(vodStreamsText) ?: emptyList()
                    val catNames = cats.associate { it.category_id to (it.category_name ?: "Movies") }
                    streams.groupBy { it.category_id }.forEach { (catId, items) ->
                        val catName = catNames[catId] ?: "Movies"
                        val homeItems = items.take(40).map { s ->
                            newMovieSearchResponse(s.name, ItemRef("m", s.stream_id, s.name, s.container_extension ?: "mp4").toJson(), TvType.Movie) {
                                posterUrl = s.stream_icon
                            }
                        }
                        if (homeItems.isNotEmpty()) lists.add(HomePageList("\uD83C\uDFAC $catName", homeItems))
                    }
                }

                // Series
                val seriesCatsText = RawHttp.get("$apiBase&action=get_series_categories")
                val seriesText = RawHttp.get("$apiBase&action=get_series")
                if (seriesCatsText != null && seriesText != null) {
                    val cats = tryParseJson<List<XCat>>(seriesCatsText) ?: emptyList()
                    val series = tryParseJson<List<XSeries>>(seriesText) ?: emptyList()
                    val catNames = cats.associate { it.category_id to (it.category_name ?: "Series") }
                    series.groupBy { it.category_id }.forEach { (catId, items) ->
                        val catName = catNames[catId] ?: "Series"
                        val homeItems = items.take(40).map { s ->
                            newTvSeriesSearchResponse(s.name, ItemRef("s", s.series_id, s.name).toJson(), TvType.TvSeries) {
                                posterUrl = s.cover
                            }
                        }
                        if (homeItems.isNotEmpty()) lists.add(HomePageList("\uD83C\uDFB6 $catName", homeItems))
                    }
                }
            } catch (_: Exception) {}
        }

        return if (lists.isEmpty()) null else newHomePageResponse(lists, false)
    }

    private fun buildM3UHomePage(entries: List<M3UEntry>, lists: MutableList<HomePageList>) {
        entries.groupBy { it.group }.forEach { (group, items) ->
            val homeItems = items.take(40).map { entry ->
                val ref = EntryRef(entry.streamUrl, entry.type, entry.name, entry.group, entry.logo)
                when (entry.type) {
                    "movie" -> newMovieSearchResponse(entry.name, ref.toJson(), TvType.Movie) { posterUrl = entry.logo }
                    "series" -> newTvSeriesSearchResponse(entry.name, ref.toJson(), TvType.TvSeries) { posterUrl = entry.logo }
                    else -> newMovieSearchResponse(entry.name, ref.toJson(), TvType.Live) { posterUrl = entry.logo }
                }
            }
            if (homeItems.isNotEmpty()) {
                val prefix = when (items.firstOrNull()?.type) {
                    "movie" -> "\uD83C\uDFAC"
                    "series" -> "\uD83C\uDFB6"
                    else -> "\uD83D\uDCFA"
                }
                lists.add(HomePageList("$prefix $group", homeItems))
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  SEARCH
    // ═══════════════════════════════════════════════════════════════════

    override suspend fun search(query: String): List<SearchResponse>? {
        val url = mainUrl.trim()
        if (url.isEmpty() || url == "http://example.com/username/password") return null
        val q = query.lowercase()
        val results = mutableListOf<SearchResponse>()

        val entries = cachedM3U ?: return null
        entries.filter { it.name.lowercase().contains(q) || it.group.lowercase().contains(q) }.take(30).forEach { entry ->
            val ref = EntryRef(entry.streamUrl, entry.type, entry.name, entry.group, entry.logo)
            when (entry.type) {
                "movie" -> results.add(newMovieSearchResponse(entry.name, ref.toJson(), TvType.Movie) { posterUrl = entry.logo })
                "series" -> results.add(newTvSeriesSearchResponse(entry.name, ref.toJson(), TvType.TvSeries) { posterUrl = entry.logo })
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
            "live" -> newMovieLoadResponse(ref.name, ref.toJson(), TvType.Live, ref.toJson()) { posterUrl = ref.logo }
            "movie" -> newMovieLoadResponse(ref.name, ref.toJson(), TvType.Movie, ref.toJson()) { posterUrl = ref.logo }
            "series" -> {
                val allEntries = cachedM3U ?: return null
                val groupEntries = allEntries.filter { it.group == ref.group && it.type == "series" }
                if (groupEntries.size <= 1) {
                    newMovieLoadResponse(ref.name, ref.toJson(), TvType.Movie, ref.toJson()) { posterUrl = ref.logo }
                } else {
                    val episodes = groupEntries.map { entry ->
                        val epRef = EntryRef(entry.streamUrl, "series", entry.name, entry.group, entry.logo)
                        val (sNum, eNum) = parseSeasonEpisode(entry.name)
                        newEpisode(epRef.toJson()) {
                            name = entry.name
                            season = sNum
                            episode = eNum
                            posterUrl = entry.logo
                        }
                    }
                    newTvSeriesLoadResponse(ref.name, ref.toJson(), TvType.TvSeries, episodes) { posterUrl = ref.logo }
                }
            }
            else -> newMovieLoadResponse(ref.name, ref.toJson(), TvType.Movie, ref.toJson()) { posterUrl = ref.logo }
        }
    }

    private suspend fun loadXtreamEntry(ref: ItemRef): LoadResponse? {
        val c = cfg() ?: return null

        return when (ref.t) {
            "l" -> newMovieLoadResponse(ref.n, ref.toJson(), TvType.Live, LinkData("l", ref.id).toJson()) {}
            "m" -> {
                val apiBase = "${c.server}/player_api.php?username=${c.user}&password=${c.pass}"
                val infoText = RawHttp.get("$apiBase&action=get_vod_info&vod_id=${ref.id}")
                val info = if (infoText != null) tryParseJson<XVodInfo>(infoText) else null
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
                val info = try { tryParseJson<XSeriesInfo>(RawHttp.get("$apiBase&action=get_series_info&series_id=${ref.id}") ?: "") } catch (_: Exception) { null }
                val detail = info?.info
                val epMap = info?.episodes
                if (epMap.isNullOrEmpty()) {
                    newMovieLoadResponse(detail?.name ?: ref.n, ref.toJson(), TvType.Movie, LinkData("s", ref.id, "mp4").toJson()) { posterUrl = detail?.cover; plot = detail?.plot }
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
                        posterUrl = detail?.cover; plot = detail?.plot; score = Score.from10(detail?.rating)
                    }
                }
            }
            else -> null
        }
    }

    private fun parseSeasonEpisode(name: String): Pair<Int, Int> {
        val season = """[Ss]\s*(\d+)""".toRegex().find(name)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
        val episode = """[Ee]\s*(\d+)""".toRegex().find(name)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        return season to episode
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

        if (ref.type == "live" && lower.contains("/live/")) {
            val base = url.substringBeforeLast(".")
            callback(newExtractorLink(name, "HLS (.m3u8)", "$base.m3u8") {
                referer = ""; quality = guessQuality(ref.name); type = ExtractorLinkType.M3U8
            })
            callback(newExtractorLink(name, "MPEG-TS (.ts)", "$base.ts") {
                referer = ""; quality = guessQuality(ref.name); type = ExtractorLinkType.VIDEO
            })
        } else {
            val isHls = lower.endsWith(".m3u8")
            callback(newExtractorLink(name, "Play", url) {
                referer = ""; quality = Qualities.Unknown.value
                type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            })
        }
        return true
    }

    private suspend fun loadXtreamLinks(ld: LinkData, callback: (ExtractorLink) -> Unit): Boolean {
        val c = cfg() ?: return false
        when (ld.t) {
            "l" -> {
                val base = "${c.server}/live/${c.user}/${c.pass}/${ld.id}"
                callback(newExtractorLink(name, "HLS (.m3u8)", "$base.m3u8") { referer = ""; quality = Qualities.Unknown.value; type = ExtractorLinkType.M3U8 })
                callback(newExtractorLink(name, "MPEG-TS (.ts)", "$base.ts") { referer = ""; quality = Qualities.Unknown.value; type = ExtractorLinkType.VIDEO })
            }
            "m" -> {
                val ext = ld.e ?: "mp4"
                val isHls = ext == "m3u8"
                callback(newExtractorLink(name, "Movie", "${c.server}/movie/${c.user}/${c.pass}/${ld.id}.$ext") { referer = ""; quality = Qualities.Unknown.value; type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO })
            }
            "e" -> {
                val ext = ld.e ?: "mp4"
                val isHls = ext == "m3u8"
                callback(newExtractorLink(name, "Episode", "${c.server}/series/${c.user}/${c.pass}/${ld.id}.$ext") { referer = ""; quality = Qualities.Unknown.value; type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO })
            }
        }
        return true
    }
}
