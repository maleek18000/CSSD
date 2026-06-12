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
//  PLUGIN ENTRY POINT  (required by CloudStream)
// ═══════════════════════════════════════════════════════════════════

@CloudstreamPlugin
class XtreamIPTVPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(XtreamIPTVProvider())
    }
}

// ═══════════════════════════════════════════════════════════════════
//  XTREAM API DATA CLASSES  (top-level to avoid D8 metadata issues)
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
    val category_id: String = "",
    val tv_archive: Int? = null,
    val direct_source: String? = null
)

data class XVod(
    val name: String = "",
    val stream_id: Int = 0,
    val stream_icon: String? = null,
    val rating: String? = null,
    val rating_5based: Double? = null,
    val category_id: String = "",
    val container_extension: String? = null,
    val added: String? = null
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
    val cover: String? = null,
    val backdrop: String? = null
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
    val rating_5based: Double? = null,
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
    val cover: String? = null,
    val backdrop: String? = null
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

/** Light reference stored in SearchResponse.url -> passed to load(). */
data class ItemRef(
    val t: String,      // "l"=live, "m"=movie, "s"=series
    val id: Int,
    val n: String,      // display name
    val e: String? = null // container extension (movies)
)

/** Stream data stored in LoadResponse.data -> passed to loadLinks(). */
data class LinkData(
    val t: String,      // "l"=live, "m"=movie, "e"=episode
    val id: Int,
    val e: String? = null // container extension
)

// ═══════════════════════════════════════════════════════════════════
//  PROVIDER
// ═══════════════════════════════════════════════════════════════════

/**
 * CloudStream provider for Xtream Codes IPTV services.
 *
 * Supports Live TV, Movies (VOD), and Series with full playback.
 *
 * SETUP:
 * In CloudStream, go to Settings -> General -> Clone site -> Xtream IPTV
 * Enter your credentials as the URL in one of these formats:
 *
 *   Format 1 (simple):  http://server:port/username/password
 *   Format 2 (explicit): http://server:port|username|password
 *
 * Examples:
 *   http://myprovider.tv:80/john/secret123
 *   http://myprovider.tv:80|john|secret123
 *
 * The pipe format is useful if your server has a path prefix:
 *   http://myprovider.tv/portal|john|secret123
 */
class XtreamIPTVProvider : MainAPI() {

    override var mainUrl = "http://example.com/username/password"
    override var name = "Xtream IPTV"
    override val supportedTypes = setOf(TvType.Live, TvType.Movie, TvType.TvSeries)
    override val hasMainPage = true
    override val hasQuickSearch = false

    // ═══════════════════════════════════════════════════════════════════
    //  CONFIGURATION
    // ═══════════════════════════════════════════════════════════════════

    private data class Cfg(val server: String, val user: String, val pass: String)

    /** Parse Xtream credentials from the mainUrl set during site cloning. */
    private fun cfg(): Cfg? {
        val raw = mainUrl.trim()
        // Pipe format: http://server:port|user|pass  (unambiguous)
        if (raw.contains("|")) {
            val p = raw.split("|")
            if (p.size >= 3) return Cfg(p[0].trimEnd('/'), p[1], p[2])
        }
        // Path format: http://server:port/user/pass  (last two segments)
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
    //  XTREAM API HELPERS
    // ═══════════════════════════════════════════════════════════════════

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

    /** Live stream base URL (append .ts or .m3u8). */
    private fun liveBase(id: Int): String {
        val c = cfg() ?: return ""
        return "${c.server}/live/${c.user}/${c.pass}/$id"
    }

    /** Movie stream URL. */
    private fun movieUrl(id: Int, ext: String): String {
        val c = cfg() ?: return ""
        return "${c.server}/movie/${c.user}/${c.pass}/$id.${ext.trimStart('.')}"
    }

    /** Series episode stream URL. */
    private fun epUrl(id: Int, ext: String): String {
        val c = cfg() ?: return ""
        return "${c.server}/series/${c.user}/${c.pass}/$id.${ext.trimStart('.')}"
    }

    /** Guess quality from stream name (e.g. "Channel HD", "Movie 4K"). */
    private fun guessQuality(name: String?): Int {
        if (name == null) return Qualities.Unknown.value
        val n = name.uppercase()
        return when {
            n.contains("4K") || n.contains("2160P") -> Qualities.P2160.value
            n.contains("1080P") || n.contains("FHD") -> Qualities.P1080.value
            n.contains("720P") || n.contains("HD") && !n.contains("SD") -> Qualities.P720.value
            n.contains("480P") || n.contains("SD") -> Qualities.P480.value
            else -> Qualities.Unknown.value
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  IN-MEMORY CACHE  (populated on home-page load, reused for search)
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
            val cats = tryParseJson<List<XCat>>(app.get(api("get_live_categories")).text)
                ?: emptyList()
            val streams = tryParseJson<List<XLive>>(app.get(api("get_live_streams")).text)
                ?: emptyList()
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
        } catch (_: Exception) { }

        // -- Movies / VOD --
        try {
            val cats = tryParseJson<List<XCat>>(app.get(api("get_vod_categories")).text)
                ?: emptyList()
            val streams = tryParseJson<List<XVod>>(app.get(api("get_vod_streams")).text)
                ?: emptyList()
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
        } catch (_: Exception) { }

        // -- Series --
        try {
            val cats = tryParseJson<List<XCat>>(app.get(api("get_series_categories")).text)
                ?: emptyList()
            val series = tryParseJson<List<XSeries>>(app.get(api("get_series")).text)
                ?: emptyList()
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
        } catch (_: Exception) { }

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
                cacheLive = tryParseJson<List<XLive>>(
                    app.get(api("get_live_streams")).text
                ) ?: emptyList()
            } catch (_: Exception) { cacheLive = emptyList() }
        }
        if (cacheVod == null) {
            try {
                cacheVod = tryParseJson<List<XVod>>(
                    app.get(api("get_vod_streams")).text
                ) ?: emptyList()
            } catch (_: Exception) { cacheVod = emptyList() }
        }
        if (cacheSeries == null) {
            try {
                cacheSeries = tryParseJson<List<XSeries>>(
                    app.get(api("get_series")).text
                ) ?: emptyList()
            } catch (_: Exception) { cacheSeries = emptyList() }
        }

        // Search live channels
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

        // Search movies
        cacheVod!!
            .filter { it.name.lowercase().contains(q) }
            .take(20)
            .forEach { s ->
                results.add(newMovieSearchResponse(
                    s.name,
                    ItemRef("m", s.stream_id, s.name, s.container_extension ?: "mp4").toJson(),
                    TvType.Movie
                ) {
                    posterUrl = s.stream_icon
                })
            }

        // Search series
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
                    ref.n,
                    url,
                    TvType.Live,
                    LinkData("l", ref.id).toJson()
                ) {
                    posterUrl = stream?.stream_icon
                }
            }

            // -- Movie --
            "m" -> {
                // Try to fetch rich info from the API
                val info = try {
                    tryParseJson<XVodInfo>(
                        app.get(api("get_vod_info", "vod_id" to ref.id.toString())).text
                    )
                } catch (_: Exception) { null }

                val detail = info?.info
                val streamInfo = info?.movie_data
                val ext = streamInfo?.container_extension
                    ?: ref.e
                    ?: "mp4"
                val streamId = streamInfo?.stream_id ?: ref.id

                newMovieLoadResponse(
                    detail?.name ?: ref.n,
                    url,
                    TvType.Movie,
                    LinkData("m", streamId, ext).toJson()
                ) {
                    posterUrl = detail?.movie_image
                        ?: detail?.cover
                        ?: cacheVod?.find { it.stream_id == ref.id }?.stream_icon
                    plot = detail?.plot
                    score = Score.from10(detail?.rating)
                    duration = detail?.duration?.toIntOrNull()
                    tags = detail?.genre
                        ?.split(",")
                        ?.map { it.trim() }
                        ?.filter { it.isNotEmpty() }
                }
            }

            // -- Series --
            "s" -> {
                // Must fetch series info to get episodes
                val info = try {
                    tryParseJson<XSeriesInfo>(
                        app.get(api("get_series_info", "series_id" to ref.id.toString())).text
                    )
                } catch (_: Exception) { null }

                val detail = info?.info
                val epMap = info?.episodes

                if (epMap.isNullOrEmpty()) {
                    // No episodes found -- treat as a single item
                    newMovieLoadResponse(
                        detail?.name ?: ref.n,
                        url,
                        TvType.Movie,
                        LinkData("s", ref.id, "mp4").toJson()
                    ) {
                        posterUrl = detail?.cover
                            ?: cacheSeries?.find { it.series_id == ref.id }?.cover
                        plot = detail?.plot
                    }
                } else {
                    val episodes = mutableListOf<Episode>()
                    epMap.toSortedMap(compareBy { it.toIntOrNull() ?: 0 })
                        .forEach { (seasonKey, eps) ->
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
                        detail?.name ?: ref.n,
                        url,
                        TvType.TvSeries,
                        episodes
                    ) {
                        posterUrl = detail?.cover
                            ?: cacheSeries?.find { it.series_id == ref.id }?.cover
                        plot = detail?.plot
                        score = Score.from10(detail?.rating)
                        tags = detail?.genre
                            ?.split(",")
                            ?.map { it.trim() }
                            ?.filter { it.isNotEmpty() }
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
        if (cfg() == null) return false

        when (ld.t) {

            // -- Live Channel: provide both HLS and MPEG-TS --
            "l" -> {
                val base = liveBase(ld.id)
                if (base.isNotEmpty()) {
                    callback.invoke(
                        newExtractorLink(
                            name,
                            "HLS (.m3u8)",
                            "$base.m3u8",
                        ) {
                            referer = ""
                            quality = guessQuality(cacheLive?.find { it.stream_id == ld.id }?.name)
                            type = ExtractorLinkType.M3U8
                        }
                    )
                    callback.invoke(
                        newExtractorLink(
                            name,
                            "MPEG-TS (.ts)",
                            "$base.ts",
                        ) {
                            referer = ""
                            quality = guessQuality(cacheLive?.find { it.stream_id == ld.id }?.name)
                            type = ExtractorLinkType.VIDEO
                        }
                    )
                }
            }

            // -- Movie --
            "m" -> {
                val ext = ld.e ?: "mp4"
                val url = movieUrl(ld.id, ext)
                if (url.isNotEmpty()) {
                    val isM3u8 = ext == "m3u8" || ext == "m3u"
                    callback.invoke(
                        newExtractorLink(
                            name,
                            "Movie",
                            url,
                        ) {
                            referer = ""
                            quality = Qualities.Unknown.value
                            type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        }
                    )
                }
            }

            // -- Series Episode --
            "e" -> {
                val ext = ld.e ?: "mp4"
                val url = epUrl(ld.id, ext)
                if (url.isNotEmpty()) {
                    val isM3u8 = ext == "m3u8" || ext == "m3u"
                    callback.invoke(
                        newExtractorLink(
                            name,
                            "Episode",
                            url,
                        ) {
                            referer = ""
                            quality = Qualities.Unknown.value
                            type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        }
                    )
                }
            }
        }

        return true
    }
}
