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
//  DATA CLASSES  (top-level to avoid D8 metadata issues)
// ═══════════════════════════════════════════════════════════════════

data class XCat(
    val category_id: String? = null,
    val category_name: String? = null
)

data class XLive(
    val name: String = "",
    val stream_id: Int = 0,
    val stream_icon: String? = null,
    val epg_channel_id: String? = null,
    val category_id: String = ""
)

data class XVod(
    val name: String = "",
    val stream_id: Int = 0,
    val stream_icon: String? = null,
    val rating: String? = null,
    val category_id: String = "",
    val container_extension: String? = null
)

data class XVodInfo(
    val info: XVodInfoDetail? = null,
    val movie_data: XVodStreamData? = null
)

data class XVodInfoDetail(
    val name: String? = null,
    val plot: String? = null,
    val cast: String? = null,
    val director: String? = null,
    val genre: String? = null,
    val release_date: String? = null,
    val rating: String? = null,
    val duration: String? = null,
    val movie_image: String? = null,
    val cover: String? = null
)

data class XVodStreamData(
    val stream_id: Int? = null,
    val container_extension: String? = null
)

data class XSeries(
    val name: String = "",
    val series_id: Int = 0,
    val cover: String? = null,
    val plot: String? = null,
    val cast: String? = null,
    val director: String? = null,
    val genre: String? = null,
    val release_date: String? = null,
    val rating: String? = null,
    val category_id: String? = null
)

data class XSeriesInfo(
    val info: XSeriesDetail? = null,
    val episodes: Map<String, List<XEp>>? = null
)

data class XSeriesDetail(
    val name: String? = null,
    val plot: String? = null,
    val cast: String? = null,
    val director: String? = null,
    val genre: String? = null,
    val release_date: String? = null,
    val rating: String? = null,
    val cover: String? = null
)

data class XEp(
    val id: Int = 0,
    val episode_num: Int = 0,
    val season_num: Int = 0,
    val title: String? = null,
    val container_extension: String? = null,
    val info: XEpInfo? = null
)

data class XEpInfo(
    val plot: String? = null,
    val duration: String? = null,
    val image: String? = null
)

/** Stored in SearchResponse.url / LoadResponse.data. */
data class ItemRef(
    val t: String,          // "l"=live, "m"=movie, "s"=series
    val id: Int,
    val n: String,          // display name
    val e: String? = null   // container extension (movies)
)

/** Stream data passed to loadLinks(). */
data class LinkData(
    val t: String,          // "l"=live, "m"=movie, "e"=episode
    val id: Int,
    val e: String? = null   // container extension
)

// ═══════════════════════════════════════════════════════════════════
//  PROVIDER
// ═══════════════════════════════════════════════════════════════════

/**
 * CloudStream provider for Xtream Codes IPTV.
 *
 * SETUP:
 * Settings -> Clone site -> Xtream IPTV
 * Enter your M3U URL or Xtream credentials:
 *
 *   M3U:  http://server/get.php?username=X&password=X&type=m3u_plus
 *   API:  http://server:port/username/password
 *   API:  http://server:port|username|password
 *
 * The plugin auto-detects M3U URLs and extracts server/user/pass
 * to use the faster Xtream Codes API.
 */
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

    /**
     * Parse credentials from mainUrl.
     * Supports:
     *   1. M3U URL: http://server/get.php?username=X&password=X&...
     *   2. Pipe format: http://server|user|pass
     *   3. Path format: http://server/user/pass
     */
    private fun cfg(): Cfg? {
        val raw = mainUrl.trim()

        // 1) M3U URL - extract username & password from query params
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
                // Server is everything before get.php
                val gpIdx = raw.indexOf("get.php")
                if (gpIdx > 0) {
                    val serverBase = raw.substring(0, gpIdx).trimEnd('/')
                    // Remove trailing path segments to get server root
                    // e.g. http://photos.uploadsite.org:80/get.php -> http://photos.uploadsite.org:80
                    if (user.isNotEmpty() && pass.isNotEmpty()) {
                        return Cfg(serverBase, user, pass)
                    }
                }
            } catch (_: Exception) {}
            return null
        }

        // 2) Pipe format: http://server:port|user|pass
        if (raw.contains("|")) {
            val p = raw.split("|")
            if (p.size >= 3) return Cfg(p[0].trimEnd('/'), p[1], p[2])
        }

        // 3) Path format: http://server:port/user/pass
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

    // ═══════════════════════════════════════════════════════════════════
    //  API HELPERS
    // ═══════════════════════════════════════════════════════════════════

    /** IPTV player User-Agent - same as what real IPTV apps send. */
    private val iptvHeaders = mapOf(
        "User-Agent" to "IPTV/1.0 (Linux;Android) ExoPlayer/2.19.1"
    )

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Build an Xtream Codes API URL. */
    private fun api(action: String, vararg kv: Pair<String, String>): String {
        val c = cfg() ?: return ""
        val sb = StringBuilder()
        sb.append("${c.server}/player_api.php")
        sb.append("?username=${enc(c.user)}")
        sb.append("&password=${enc(c.pass)}")
        sb.append("&action=$action")
        kv.forEach { (k, v) -> sb.append("&$k=${enc(v)}") }
        return sb.toString()
    }

    /** Fetch from Xtream API with IPTV User-Agent. */
    private suspend fun apiGet(url: String): String? {
        if (url.isEmpty()) return null
        return try {
            app.get(url, headers = iptvHeaders, timeout = 15000L).text
        } catch (_: Exception) {
            try {
                app.get(url, timeout = 15000L).text
            } catch (_: Exception) { null }
        }
    }

    /** Guess quality from stream name. */
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

    private var cacheLive: List<XLive>? = null
    private var cacheVod: List<XVod>? = null
    private var cacheSeries: List<XSeries>? = null

    // ═══════════════════════════════════════════════════════════════════
    //  HOME PAGE
    // ═══════════════════════════════════════════════════════════════════

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        if (cfg() == null) return null
        val lists = mutableListOf<HomePageList>()

        // -- Live TV --
        try {
            val catsText = apiGet(api("get_live_categories")) ?: ""
            val streamsText = apiGet(api("get_live_streams")) ?: ""
            val cats = tryParseJson<List<XCat>>(catsText) ?: emptyList()
            val streams = tryParseJson<List<XLive>>(streamsText) ?: emptyList()
            cacheLive = streams

            val catNames = cats.associate { it.category_id to (it.category_name ?: "Live TV") }
            streams.groupBy { it.category_id }.forEach { (catId, items) ->
                val catName = catNames[catId] ?: "Live TV"
                val homeItems = items.take(40).map { s ->
                    newMovieSearchResponse(
                        s.name,
                        ItemRef("l", s.stream_id, s.name).toJson(),
                        TvType.Live
                    ) {
                        posterUrl = s.stream_icon
                    }
                }
                if (homeItems.isNotEmpty()) {
                    lists.add(HomePageList("\uD83D\uDCFA $catName", homeItems))
                }
            }
        } catch (_: Exception) {}

        // -- Movies / VOD --
        try {
            val catsText = apiGet(api("get_vod_categories")) ?: ""
            val streamsText = apiGet(api("get_vod_streams")) ?: ""
            val cats = tryParseJson<List<XCat>>(catsText) ?: emptyList()
            val streams = tryParseJson<List<XVod>>(streamsText) ?: emptyList()
            cacheVod = streams

            val catNames = cats.associate { it.category_id to (it.category_name ?: "Movies") }
            streams.groupBy { it.category_id }.forEach { (catId, items) ->
                val catName = catNames[catId] ?: "Movies"
                val homeItems = items.take(40).map { s ->
                    newMovieSearchResponse(
                        s.name,
                        ItemRef("m", s.stream_id, s.name, s.container_extension ?: "mp4").toJson(),
                        TvType.Movie
                    ) {
                        posterUrl = s.stream_icon
                    }
                }
                if (homeItems.isNotEmpty()) {
                    lists.add(HomePageList("\uD83C\uDFAC $catName", homeItems))
                }
            }
        } catch (_: Exception) {}

        // -- Series --
        try {
            val catsText = apiGet(api("get_series_categories")) ?: ""
            val seriesText = apiGet(api("get_series")) ?: ""
            val cats = tryParseJson<List<XCat>>(catsText) ?: emptyList()
            val series = tryParseJson<List<XSeries>>(seriesText) ?: emptyList()
            cacheSeries = series

            val catNames = cats.associate { it.category_id to (it.category_name ?: "Series") }
            series.groupBy { it.category_id }.forEach { (catId, items) ->
                val catName = catNames[catId] ?: "Series"
                val homeItems = items.take(40).map { s ->
                    newTvSeriesSearchResponse(
                        s.name,
                        ItemRef("s", s.series_id, s.name).toJson(),
                        TvType.TvSeries
                    ) {
                        posterUrl = s.cover
                    }
                }
                if (homeItems.isNotEmpty()) {
                    lists.add(HomePageList("\uD83C\uDFB6 $catName", homeItems))
                }
            }
        } catch (_: Exception) {}

        return if (lists.isEmpty()) null else newHomePageResponse(lists, false)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  SEARCH
    // ═══════════════════════════════════════════════════════════════════

    override suspend fun search(query: String): List<SearchResponse>? {
        if (cfg() == null) return null
        val q = query.lowercase()
        val results = mutableListOf<SearchResponse>()

        // Populate cache lazily
        if (cacheLive == null) {
            try {
                cacheLive = tryParseJson<List<XLive>>(apiGet(api("get_live_streams")) ?: "") ?: emptyList()
            } catch (_: Exception) { cacheLive = emptyList() }
        }
        if (cacheVod == null) {
            try {
                cacheVod = tryParseJson<List<XVod>>(apiGet(api("get_vod_streams")) ?: "") ?: emptyList()
            } catch (_: Exception) { cacheVod = emptyList() }
        }
        if (cacheSeries == null) {
            try {
                cacheSeries = tryParseJson<List<XSeries>>(apiGet(api("get_series")) ?: "") ?: emptyList()
            } catch (_: Exception) { cacheSeries = emptyList() }
        }

        cacheLive!!
            .filter { it.name.lowercase().contains(q) }
            .take(20)
            .forEach { s ->
                results.add(newMovieSearchResponse(
                    s.name,
                    ItemRef("l", s.stream_id, s.name).toJson(),
                    TvType.Live
                ) { posterUrl = s.stream_icon })
            }

        cacheVod!!
            .filter { it.name.lowercase().contains(q) }
            .take(20)
            .forEach { s ->
                results.add(newMovieSearchResponse(
                    s.name,
                    ItemRef("m", s.stream_id, s.name, s.container_extension ?: "mp4").toJson(),
                    TvType.Movie
                ) { posterUrl = s.stream_icon })
            }

        cacheSeries!!
            .filter { it.name.lowercase().contains(q) }
            .take(20)
            .forEach { s ->
                results.add(newTvSeriesSearchResponse(
                    s.name,
                    ItemRef("s", s.series_id, s.name).toJson(),
                    TvType.TvSeries
                ) { posterUrl = s.cover })
            }

        return results
    }

    // ═══════════════════════════════════════════════════════════════════
    //  LOAD DETAILS
    // ═══════════════════════════════════════════════════════════════════

    override suspend fun load(url: String): LoadResponse? {
        val ref = tryParseJson<ItemRef>(url) ?: return null
        if (cfg() == null) return null

        return when (ref.t) {

            // -- Live Channel --
            "l" -> {
                val stream = cacheLive?.find { it.stream_id == ref.id }
                newMovieLoadResponse(
                    ref.n, url, TvType.Live, LinkData("l", ref.id).toJson()
                ) {
                    posterUrl = stream?.stream_icon
                }
            }

            // -- Movie --
            "m" -> {
                val info = try {
                    tryParseJson<XVodInfo>(apiGet(api("get_vod_info", "vod_id" to ref.id.toString())) ?: "")
                } catch (_: Exception) { null }

                val detail = info?.info
                val streamInfo = info?.movie_data
                val ext = streamInfo?.container_extension ?: ref.e ?: "mp4"
                val streamId = streamInfo?.stream_id ?: ref.id

                newMovieLoadResponse(
                    detail?.name ?: ref.n, url, TvType.Movie, LinkData("m", streamId, ext).toJson()
                ) {
                    posterUrl = detail?.movie_image ?: detail?.cover
                        ?: cacheVod?.find { it.stream_id == ref.id }?.stream_icon
                    plot = detail?.plot
                    score = Score.from10(detail?.rating)
                    duration = detail?.duration?.toIntOrNull()
                    tags = detail?.genre?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
                }
            }

            // -- Series --
            "s" -> {
                val info = try {
                    tryParseJson<XSeriesInfo>(apiGet(api("get_series_info", "series_id" to ref.id.toString())) ?: "")
                } catch (_: Exception) { null }

                val detail = info?.info
                val epMap = info?.episodes

                if (epMap.isNullOrEmpty()) {
                    newMovieLoadResponse(
                        detail?.name ?: ref.n, url, TvType.Movie, LinkData("s", ref.id, "mp4").toJson()
                    ) {
                        posterUrl = detail?.cover ?: cacheSeries?.find { it.series_id == ref.id }?.cover
                        plot = detail?.plot
                    }
                } else {
                    val episodes = mutableListOf<Episode>()
                    epMap.toSortedMap(compareBy { it.toIntOrNull() ?: 0 })
                        .forEach { (_, eps) ->
                            eps.sortedBy { it.episode_num }.forEach { ep ->
                                val ext = ep.container_extension ?: "mp4"
                                episodes.add(newEpisode(
                                    LinkData("e", ep.id, ext).toJson()
                                ) {
                                    name = ep.title?.ifBlank { "S${ep.season_num} E${ep.episode_num}" }
                                        ?: "S${ep.season_num} E${ep.episode_num}"
                                    season = ep.season_num
                                    episode = ep.episode_num
                                    posterUrl = ep.info?.image
                                    description = ep.info?.plot
                                })
                            }
                        }

                    newTvSeriesLoadResponse(
                        detail?.name ?: ref.n, url, TvType.TvSeries, episodes
                    ) {
                        posterUrl = detail?.cover ?: cacheSeries?.find { it.series_id == ref.id }?.cover
                        plot = detail?.plot
                        score = Score.from10(detail?.rating)
                        tags = detail?.genre?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
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
        val ld = tryParseJson<LinkData>(data) ?: return false
        val c = cfg() ?: return false

        when (ld.t) {

            // -- Live Channel: provide both HLS and MPEG-TS --
            "l" -> {
                val base = "${c.server}/live/${c.user}/${c.pass}/${ld.id}"
                callback.invoke(
                    newExtractorLink(name, "HLS (.m3u8)", "$base.m3u8") {
                        referer = ""
                        quality = guessQuality(cacheLive?.find { it.stream_id == ld.id }?.name)
                        type = ExtractorLinkType.M3U8
                    }
                )
                callback.invoke(
                    newExtractorLink(name, "MPEG-TS (.ts)", "$base.ts") {
                        referer = ""
                        quality = guessQuality(cacheLive?.find { it.stream_id == ld.id }?.name)
                        type = ExtractorLinkType.VIDEO
                    }
                )
            }

            // -- Movie --
            "m" -> {
                val ext = ld.e ?: "mp4"
                val url = "${c.server}/movie/${c.user}/${c.pass}/${ld.id}.${ext.trimStart('.')}"
                val isHls = ext == "m3u8" || ext == "m3u"
                callback.invoke(
                    newExtractorLink(name, "Movie", url) {
                        referer = ""
                        quality = Qualities.Unknown.value
                        type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    }
                )
            }

            // -- Series Episode --
            "e" -> {
                val ext = ld.e ?: "mp4"
                val url = "${c.server}/series/${c.user}/${c.pass}/${ld.id}.${ext.trimStart('.')}"
                val isHls = ext == "m3u8" || ext == "m3u"
                callback.invoke(
                    newExtractorLink(name, "Episode", url) {
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
