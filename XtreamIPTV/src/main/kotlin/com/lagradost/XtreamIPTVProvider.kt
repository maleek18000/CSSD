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

data class M3UEntry(
    val name: String,
    val group: String,
    val logo: String?,
    val streamUrl: String,
    val type: String       // "live", "movie", "series"
)

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

class XtreamIPTVProvider : MainAPI() {

    override var mainUrl = "http://example.com/username/password"
    override var name = "Xtream IPTV"
    override val supportedTypes = setOf(TvType.Live, TvType.Movie, TvType.TvSeries)
    override val hasMainPage = true
    override val hasQuickSearch = false

    // ═══════════════════════════════════════════════════════════════════
    //  USER-AGENT LIST  (try each until one works)
    // ═══════════════════════════════════════════════════════════════════

    private val userAgents = listOf(
        "okhttp/4.12.0",                                          // Most IPTV apps (TiviMate, etc.)
        "IPTVSmarters",                                           // IPTV Smarters
        "okhttp/4.9.3",                                          // Older IPTV apps
        "ExoPlayerLib/2.19.1",                                   // ExoPlayer default
        "Lavf/60.3.100",                                         // FFmpeg/VLC style
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36",    // Mobile browser
    )

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
                    entries.add(M3UEntry(
                        name = name,
                        group = group,
                        logo = logo,
                        streamUrl = url,
                        type = detectType(url, group)
                    ))
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
            groupLower.contains("series") || groupLower.contains("episode") ||
            groupLower.contains("مسلسلات") -> "series"
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
            n.contains("480") || n.contains("SD") -> Qualities.P480.value
            else -> Qualities.Unknown.value
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  M3U DOWNLOAD  (try multiple User-Agents)
    // ═══════════════════════════════════════════════════════════════════

    private var cachedM3U: List<M3UEntry>? = null

    private suspend fun downloadM3U(): List<M3UEntry>? {
        val url = mainUrl.trim()
        if (url.isEmpty() || url == "http://example.com/username/password") return null

        // Try each User-Agent until one works
        for (ua in userAgents) {
            try {
                val headers = mapOf("User-Agent" to ua)
                val response = app.get(url, headers = headers, timeout = 30000L)
                val text = response.text

                // Check if we got valid M3U data
                if (text.startsWith("#EXTM3U") || text.startsWith("#EXTINF")) {
                    val entries = parseM3U(text)
                    if (entries.isNotEmpty()) return entries
                }
            } catch (_: Exception) { continue }
        }

        // Also try with referer = server domain (some servers check this)
        try {
            val uri = URI(url)
            val referer = "${uri.scheme}://${uri.host}/"
            for (ua in userAgents) {
                try {
                    val headers = mapOf(
                        "User-Agent" to ua,
                        "Referer" to referer,
                        "Accept" to "*/*"
                    )
                    val response = app.get(url, headers = headers, timeout = 30000L)
                    val text = response.text
                    if (text.startsWith("#EXTM3U") || text.startsWith("#EXTINF")) {
                        val entries = parseM3U(text)
                        if (entries.isNotEmpty()) return entries
                    }
                } catch (_: Exception) { continue }
            }
        } catch (_: Exception) {}

        return null
    }

    // ═══════════════════════════════════════════════════════════════════
    //  HOME PAGE
    // ═══════════════════════════════════════════════════════════════════

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = mainUrl.trim()
        if (url.isEmpty() || url == "http://example.com/username/password") return null

        val lists = mutableListOf<HomePageList>()

        // Download and parse M3U playlist
        val entries = downloadM3U() ?: return null
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

        return if (lists.isEmpty()) null else newHomePageResponse(lists, false)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  SEARCH
    // ═══════════════════════════════════════════════════════════════════

    override suspend fun search(query: String): List<SearchResponse>? {
        val url = mainUrl.trim()
        if (url.isEmpty() || url == "http://example.com/username/password") return null
        val q = query.lowercase()
        val results = mutableListOf<SearchResponse>()

        val entries = cachedM3U ?: downloadM3U() ?: return null
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
                val allEntries = cachedM3U ?: downloadM3U() ?: return null
                val groupEntries = allEntries.filter { it.group == ref.group && it.type == "series" }

                if (groupEntries.size <= 1) {
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
        val ref = tryParseJson<EntryRef>(data)
        val streamUrl = ref?.url ?: data
        val entryType = ref?.type ?: "live"

        if (streamUrl.isEmpty()) return false

        val lower = streamUrl.lowercase()
        val isHls = lower.endsWith(".m3u8") || lower.contains(".m3u8?")

        when {
            // Live channel with Xtream /live/ URL → provide both formats
            entryType == "live" && lower.contains("/live/") -> {
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
