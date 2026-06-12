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
import java.net.URI

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

/** A parsed M3U entry with metadata and stream URL. */
data class M3UEntry(
    val name: String,
    val group: String,
    val logo: String?,
    val streamUrl: String,
    val type: String,       // "live", "movie", "series"
    val tvgId: String?
)

/** Stored in SearchResponse.url / LoadResponse.data for passing between methods. */
data class EntryRef(
    val url: String,
    val type: String,
    val name: String,
    val group: String,
    val logo: String? = null
)

// ═══════════════════════════════════════════════════════════════════
//  PROVIDER
// ═══════════════════════════════════════════════════════════════════

/**
 * CloudStream provider for IPTV via M3U playlists or Xtream Codes API.
 *
 * SETUP:
 * In CloudStream, go to Settings -> General -> Clone site -> Xtream IPTV
 * Enter your M3U playlist URL or Xtream credentials:
 *
 *   M3U URL:  http://server/get.php?username=X&password=X&type=m3u_plus
 *   Xtream:   http://server:port/username/password
 *   Xtream:   http://server:port|username|password
 */
class XtreamIPTVProvider : MainAPI() {

    override var mainUrl = "http://example.com/username/password"
    override var name = "Xtream IPTV"
    override val supportedTypes = setOf(TvType.Live, TvType.Movie, TvType.TvSeries)
    override val hasMainPage = true
    override val hasQuickSearch = false

    // ═══════════════════════════════════════════════════════════════════
    //  URL DETECTION
    // ═══════════════════════════════════════════════════════════════════

    /** Detect if the URL is an M3U playlist. */
    private fun isM3UUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("get.php") ||
                lower.contains(".m3u") ||
                lower.contains("type=m3u") ||
                lower.contains("/playlist") ||
                lower.contains("m3u_plus")
    }

    // ═══════════════════════════════════════════════════════════════════
    //  XTREAM API HELPERS
    // ═══════════════════════════════════════════════════════════════════

    private data class Cfg(val server: String, val user: String, val pass: String)

    private fun parseXtream(): Cfg? {
        val raw = mainUrl.trim()
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
                if (user != "username" && pass != "password") {
                    return Cfg(server, user, pass)
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private fun xtreamApi(action: String): String {
        val c = parseXtream() ?: return ""
        return "${c.server}/player_api.php?username=${c.user}&password=${c.pass}&action=$action"
    }

    // ═══════════════════════════════════════════════════════════════════
    //  M3U PARSER
    // ═══════════════════════════════════════════════════════════════════

    private data class XCat(
        val category_id: String? = null,
        val category_name: String? = null
    )

    private data class XLive(
        val name: String = "",
        val stream_id: Int = 0,
        val stream_icon: String? = null,
        val category_id: String = ""
    )

    private data class XVod(
        val name: String = "",
        val stream_id: Int = 0,
        val stream_icon: String? = null,
        val category_id: String = "",
        val container_extension: String? = null
    )

    private data class XSeries(
        val name: String = "",
        val series_id: Int = 0,
        val cover: String? = null,
        val category_id: String? = null
    )

    /**
     * Parse an M3U/M3U_plus playlist into entries.
     * Handles #EXTINF lines with tvg-logo, group-title, tvg-id attributes.
     */
    private fun parseM3U(text: String): List<M3UEntry> {
        val entries = mutableListOf<M3UEntry>()
        val lines = text.lines()
        var i = 0

        while (i < lines.size) {
            val line = lines[i].trim()

            if (line.startsWith("#EXTINF")) {
                // Parse attributes from the EXTINF line
                val logo = extractAttr(line, "tvg-logo")
                val group = extractAttr(line, "group-title") ?: "Uncategorized"
                val tvgId = extractAttr(line, "tvg-id")

                // Channel name is after the last comma
                val commaIdx = line.lastIndexOf(',')
                val name = if (commaIdx >= 0) line.substring(commaIdx + 1).trim() else "Unknown"

                // Next non-comment line is the stream URL
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
                    val type = detectType(url, group, name)
                    entries.add(M3UEntry(
                        name = name,
                        group = group,
                        logo = logo,
                        streamUrl = url,
                        type = type,
                        tvgId = tvgId
                    ))
                }
                i = j + 1
            } else {
                i++
            }
        }
        return entries
    }

    /** Extract an attribute value from #EXTINF line, e.g. tvg-logo="http://..." */
    private fun extractAttr(line: String, attr: String): String? {
        // Match: attr="value" or attr='value'
        val pattern = """$attr\s*=\s*["']([^"']*?)["']""".toRegex(RegexOption.IGNORE_CASE)
        return pattern.find(line)?.groupValues?.getOrNull(1)?.ifBlank { null }
    }

    /** Detect content type from the stream URL and group name. */
    private fun detectType(url: String, group: String, name: String): String {
        val lower = url.lowercase()
        val groupLower = group.lowercase()

        // URL-based detection (most reliable for Xtream Codes URLs)
        return when {
            lower.contains("/live/") -> "live"
            lower.contains("/movie/") -> "movie"
            lower.contains("/series/") -> "series"
            // Group-based detection
            groupLower.contains("vod") || groupLower.contains("movie") ||
            groupLower.contains("film") -> "movie"
            groupLower.contains("series") || groupLower.contains("episode") ||
            groupLower.contains("season") -> "series"
            // Extension-based detection
            lower.endsWith(".ts") || lower.endsWith(".m3u8") -> "live"
            lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".avi") -> "movie"
            // Default to live for IPTV
            else -> "live"
        }
    }

    /** Guess quality from name/group. */
    private fun guessQuality(name: String?): Int {
        if (name == null) return Qualities.Unknown.value
        val n = name.uppercase()
        return when {
            n.contains("4K") || n.contains("2160") -> Qualities.P2160.value
            n.contains("1080") || n.contains("FHD") -> Qualities.P1080.value
            n.contains("720") || n.contains("HD") && !n.contains("SD") -> Qualities.P720.value
            n.contains("480") || n.contains("SD") -> Qualities.P480.value
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

        if (isM3UUrl(url)) {
            // ── M3U MODE ──
            val entries = loadM3U() ?: return null
            cachedM3U = entries

            // Group by category
            entries.groupBy { it.group }.forEach { (group, items) ->
                val homeItems = items.take(40).map { entry ->
                    val ref = EntryRef(entry.streamUrl, entry.type, entry.name, entry.group, entry.logo)
                    when (entry.type) {
                        "movie" -> newMovieSearchResponse(
                            entry.name, ref.toJson(), TvType.Movie
                        ) { posterUrl = entry.logo }
                        "series" -> newTvSeriesSearchResponse(
                            entry.name, ref.toJson(), TvType.TvSeries
                        ) { posterUrl = entry.logo }
                        else -> newMovieSearchResponse(
                            entry.name, ref.toJson(), TvType.Live
                        ) { posterUrl = entry.logo }
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
        } else {
            // ── XTREAM API MODE ──
            val c = parseXtream() ?: return null

            // Live
            try {
                val cats = tryParseJson<List<XCat>>(app.get(xtreamApi("get_live_categories")).text) ?: emptyList()
                val streams = tryParseJson<List<XLive>>(app.get(xtreamApi("get_live_streams")).text) ?: emptyList()
                val catNames = cats.associate { it.category_id to (it.category_name ?: "Live TV") }
                streams.groupBy { it.category_id }.forEach { (catId, items) ->
                    val catName = catNames[catId] ?: "Live TV"
                    val homeItems = items.take(40).map { s ->
                        val ref = EntryRef(
                            "${c.server}/live/${c.user}/${c.pass}/${s.stream_id}.m3u8",
                            "live", s.name, catName, s.stream_icon
                        )
                        newMovieSearchResponse(s.name, ref.toJson(), TvType.Live) { posterUrl = s.stream_icon }
                    }
                    if (homeItems.isNotEmpty()) lists.add(HomePageList("\uD83D\uDCFA $catName", homeItems))
                }
            } catch (_: Exception) {}

            // Movies
            try {
                val cats = tryParseJson<List<XCat>>(app.get(xtreamApi("get_vod_categories")).text) ?: emptyList()
                val streams = tryParseJson<List<XVod>>(app.get(xtreamApi("get_vod_streams")).text) ?: emptyList()
                val catNames = cats.associate { it.category_id to (it.category_name ?: "Movies") }
                streams.groupBy { it.category_id }.forEach { (catId, items) ->
                    val catName = catNames[catId] ?: "Movies"
                    val homeItems = items.take(40).map { s ->
                        val ext = s.container_extension ?: "mp4"
                        val ref = EntryRef(
                            "${c.server}/movie/${c.user}/${c.pass}/${s.stream_id}.$ext",
                            "movie", s.name, catName, s.stream_icon
                        )
                        newMovieSearchResponse(s.name, ref.toJson(), TvType.Movie) { posterUrl = s.stream_icon }
                    }
                    if (homeItems.isNotEmpty()) lists.add(HomePageList("\uD83C\uDFAC $catName", homeItems))
                }
            } catch (_: Exception) {}

            // Series
            try {
                val cats = tryParseJson<List<XCat>>(app.get(xtreamApi("get_series_categories")).text) ?: emptyList()
                val series = tryParseJson<List<XSeries>>(app.get(xtreamApi("get_series")).text) ?: emptyList()
                val catNames = cats.associate { it.category_id to (it.category_name ?: "Series") }
                series.groupBy { it.category_id }.forEach { (catId, items) ->
                    val catName = catNames[catId] ?: "Series"
                    val homeItems = items.take(40).map { s ->
                        val ref = EntryRef("", "series_api", s.name, catName, s.cover)
                        ref.copy(url = "${s.series_id}").let { r ->
                            newTvSeriesSearchResponse(s.name, r.toJson(), TvType.TvSeries) { posterUrl = s.cover }
                        }
                    }
                    if (homeItems.isNotEmpty()) lists.add(HomePageList("\uD83C\uDFB6 $catName", homeItems))
                }
            } catch (_: Exception) {}
        }

        return if (lists.isEmpty()) null else newHomePageResponse(lists, false)
    }

    /** Fetch and parse the M3U playlist. */
    private suspend fun loadM3U(): List<M3UEntry>? {
        return try {
            val response = app.get(mainUrl.trim()).text
            parseM3U(response)
        } catch (e: Exception) {
            null
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

        // M3U mode search
        val entries = cachedM3U ?: loadM3U() ?: return null
        entries
            .filter { it.name.lowercase().contains(q) || it.group.lowercase().contains(q) }
            .take(30)
            .forEach { entry ->
                val ref = EntryRef(entry.streamUrl, entry.type, entry.name, entry.group, entry.logo)
                when (entry.type) {
                    "movie" -> results.add(newMovieSearchResponse(
                        entry.name, ref.toJson(), TvType.Movie
                    ) { posterUrl = entry.logo })
                    "series" -> results.add(newTvSeriesSearchResponse(
                        entry.name, ref.toJson(), TvType.TvSeries
                    ) { posterUrl = entry.logo })
                    else -> results.add(newMovieSearchResponse(
                        entry.name, ref.toJson(), TvType.Live
                    ) { posterUrl = entry.logo })
                }
            }

        return results
    }

    // ═══════════════════════════════════════════════════════════════════
    //  LOAD DETAILS
    // ═══════════════════════════════════════════════════════════════════

    override suspend fun load(url: String): LoadResponse? {
        val ref = tryParseJson<EntryRef>(url) ?: return null

        return when (ref.type) {
            "live" -> {
                newMovieLoadResponse(ref.name, url, TvType.Live, url) {
                    posterUrl = ref.logo
                }
            }
            "movie" -> {
                newMovieLoadResponse(ref.name, url, TvType.Movie, url) {
                    posterUrl = ref.logo
                }
            }
            "series" -> {
                // M3U series: group episodes by this series' group
                val allEntries = cachedM3U ?: loadM3U() ?: return null
                val groupEntries = allEntries.filter { it.group == ref.group && it.type == "series" }

                if (groupEntries.isEmpty()) {
                    // Single entry fallback
                    newMovieLoadResponse(ref.name, url, TvType.Movie, url) {
                        posterUrl = ref.logo
                    }
                } else {
                    val episodes = groupEntries.mapIndexed { idx, entry ->
                        val epRef = EntryRef(entry.streamUrl, "series", entry.name, entry.group, entry.logo)
                        val (sNum, eNum) = parseSeasonEpisode(entry.name)
                        newEpisode(epRef.toJson()) {
                            name = entry.name
                            season = sNum
                            episode = eNum
                            posterUrl = entry.logo
                        }
                    }
                    newTvSeriesLoadResponse(ref.name, url, TvType.TvSeries, episodes) {
                        posterUrl = ref.logo
                    }
                }
            }
            else -> newMovieLoadResponse(ref.name, url, TvType.Movie, url) { posterUrl = ref.logo }
        }
    }

    /** Try to extract season and episode numbers from a title like "S01 E03" or "S1E3". */
    private fun parseSeasonEpisode(name: String): Pair<Int, Int> {
        val sPattern = """[Ss]\s*(\d+)""".toRegex()
        val ePattern = """[Ee]\s*(\d+)""".toRegex()
        val season = sPattern.find(name)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
        val episode = ePattern.find(name)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
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
        // data could be a JSON EntryRef or a direct stream URL (for load() pass-through)
        val ref = tryParseJson<EntryRef>(data)
        val streamUrl = ref?.url ?: data
        val entryType = ref?.type ?: "live"

        if (streamUrl.isEmpty()) return false

        val lower = streamUrl.lowercase()
        val isHls = lower.endsWith(".m3u8") || lower.contains(".m3u8?") || lower.contains("/live/")
        val isMpegTs = lower.endsWith(".ts") || lower.contains(".ts?")

        when {
            // Live channels: provide both HLS and MPEG-TS if it's a live URL
            entryType == "live" && (lower.contains("/live/") || isHls || isMpegTs) -> {
                // Try HLS version
                if (lower.contains("/live/")) {
                    val base = streamUrl.substringBeforeLast(".")
                    callback.invoke(
                        newExtractorLink(name, "HLS (.m3u8)", "$base.m3u8") {
                            referer = ""
                            quality = guessQuality(ref?.name)
                            type = ExtractorLinkType.M3U8
                        }
                    )
                    callback.invoke(
                        newExtractorLink(name, "MPEG-TS (.ts)", "$base.ts") {
                            referer = ""
                            quality = guessQuality(ref?.name)
                            type = ExtractorLinkType.VIDEO
                        }
                    )
                } else {
                    // Direct stream URL
                    callback.invoke(
                        newExtractorLink(name, "Live Stream", streamUrl) {
                            referer = ""
                            quality = guessQuality(ref?.name)
                            type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        }
                    )
                }
            }
            // Everything else: play directly
            else -> {
                callback.invoke(
                    newExtractorLink(name, "Play", streamUrl) {
                        referer = ""
                        quality = Qualities.Unknown.value
                        type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    }
                )
            }
        }

        return true
    }
}
