package com.arabmagnet

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.SubtitleFile
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.nodes.Element
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class Arabmagnet : MainAPI() {
    override var mainUrl = "https://arab-torrents.com"
    override var name = "ArabMagnet"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(
        TvType.Anime, TvType.AnimeMovie, TvType.TvSeries, TvType.Movie
    )

    companion object {
        private const val TAG = "ArabMagnet"
        private const val CATEGORY_ID = 100
        private const val MAX_PAGES = 30
        private const val TORRSERVE = "http://127.0.0.1:8090"
        private const val TORRSERVE_POLL_ATTEMPTS = 15
        private const val TORRSERVE_POLL_DELAY = 2000L
    }

    private val imageHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
        "Referer" to "$mainUrl/"
    )

    private val okHttp by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    // ==================== HELPERS ====================

    private fun toAbsoluteUrl(url: String): String = when {
        url.startsWith("http") -> url
        url.startsWith("//") -> "https:$url"
        url.startsWith("/") -> "$mainUrl$url"
        else -> "$mainUrl/$url"
    }

    private fun cleanTitle(text: String): String =
        text.replace("\\n", " ").replace("\n", " ").replace(Regex("\\s+"), " ").trim()
            .removePrefix("تحميل ").removePrefix("تحميل").trim()

    private fun titleFromMagnet(magnet: String): String? = try {
        val dn = magnet.substringAfter("dn=", "").substringBefore("&")
        if (dn.isNotEmpty()) java.net.URLDecoder.decode(dn, "UTF-8") else null
    } catch (_: Exception) { null }

    private fun tvTypeFromTitle(title: String): TvType = when {
        title.contains("فيلم", ignoreCase = true) || title.contains("Movie", ignoreCase = true) -> TvType.AnimeMovie
        else -> TvType.Anime
    }

    /** Strip base URL that CloudStream's fixUrl() prepends to magnet: URLs */
    private fun fixMagnet(url: String): String {
        if (url.startsWith("magnet:")) return url
        val i = url.indexOf("magnet:")
        return if (i > 0) url.substring(i) else url
    }

    /** Extract info hash from magnet URI (supports both v1 and v2) */
    private fun hashFromMagnet(magnet: String): String? {
        // xt=urn:btih:HEX or xt=urn:btmh:HEX
        val xt = Regex("""xt=urn:bt(?:ih|mh):([a-fA-F0-9]{40,})""").find(magnet)
            ?: Regex("""xt=urn:btih:([A-Z2-7]{32})""").find(magnet)
            ?: return null
        return xt.groupValues[1].lowercase()
    }

    // ==================== TORRSERVE ====================

    /**
     * Add a magnet to TorrServe, poll until torrent is ready,
     * and return a list of (fileName, streamUrl) for video files.
     * Returns null if TorrServe is not running or fails.
     */
    private fun torrServeStream(magnet: String): List<Pair<String, String>>? {
        return try {
            val body = JSONObject().apply {
                put("action", "add")
                put("link", magnet)
                put("save", true)
            }

            val req = Request.Builder()
                .url("$TORRSERVE/torrents")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val hash: String
            var fileStats: List<Pair<String, Int>>

            okHttp.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "TorrServe add: HTTP ${resp.code}")
                    return null
                }
                val json = resp.body?.string() ?: return null
                Log.d(TAG, "TorrServe add response: ${json.take(300)}")

                hash = try {
                    JSONObject(json).optString("hash", "")
                } catch (_: Exception) {
                    Regex(""""hash"\s*:\s*"([a-fA-F0-9]{40})"""")
                        .find(json)?.groupValues?.getOrNull(1) ?: ""
                }
                if (hash.isBlank()) return null

                fileStats = parseFileStats(json)
            }

            // Poll if file list is empty (torrent still loading metadata)
            if (fileStats.isEmpty()) {
                fileStats = pollTorrServeFiles(hash)
            }

            if (fileStats.isEmpty()) {
                // No files found — return a single default stream (index=1)
                return listOf(
                    "Video" to "$TORRSERVE/stream/fname?link=${hash.lowercase()}&index=1&play"
                )
            }

            // Filter to video files only
            val videoExts = listOf(".mkv", ".mp4", ".avi", ".wmv", ".flv", ".mov", ".webm", ".m4v", ".ts", ".mpg", ".mpeg", ".rmvb", ".rm", ".m2ts")
            val videos = fileStats.filter { (path, _) ->
                videoExts.any { path.lowercase().endsWith(it) }
            }

            if (videos.isEmpty()) {
                // No video files? Use first file anyway
                val (path, id) = fileStats.first()
                val name = path.replace("\\", "/").substringAfterLast("/")
                return listOf(name to "$TORRSERVE/stream/fname?link=${hash.lowercase()}&index=$id&play")
            }

            // Sort naturally (episode 1 before episode 10)
            videos.sortedBy { (path, _) ->
                path.replace("\\", "/").substringAfterLast("/")
                    .replace(Regex("\\d+")) { it.value.padStart(6, '0') }
                    .lowercase()
            }.map { (path, id) ->
                val name = path.replace("\\", "/").substringAfterLast("/")
                name to "$TORRSERVE/stream/fname?link=${hash.lowercase()}&index=$id&play"
            }
        } catch (_: java.net.ConnectException) {
            Log.w(TAG, "TorrServe not running on $TORRSERVE")
            null
        } catch (e: Exception) {
            Log.w(TAG, "TorrServe error: ${e.message}")
            null
        }
    }

    private fun pollTorrServeFiles(hash: String): List<Pair<String, Int>> {
        for (attempt in 1..TORRSERVE_POLL_ATTEMPTS) {
            try {
                Thread.sleep(TORRSERVE_POLL_DELAY)
                val body = JSONObject().apply {
                    put("action", "get")
                    put("hash", hash)
                }
                val req = Request.Builder()
                    .url("$TORRSERVE/torrents")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                okHttp.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) continue
                    val json = resp.body?.string() ?: continue
                    val stats = parseFileStats(json)
                    if (stats.isNotEmpty()) {
                        Log.d(TAG, "TorrServe poll OK on attempt $attempt: ${stats.size} files")
                        return stats
                    }
                }
            } catch (_: Exception) { }
        }
        return emptyList()
    }

    private fun parseFileStats(json: String): List<Pair<String, Int>> {
        val results = mutableListOf<Pair<String, Int>>()
        try {
            val obj = JSONObject(json)
            val arr = obj.optJSONArray("file_stats") ?: return emptyList()
            for (i in 0 until arr.length()) {
                val f = arr.getJSONObject(i)
                val id = f.optInt("id", -1)
                val path = f.optString("path", "")
                if (id > 0 && path.isNotEmpty()) results.add(path to id)
            }
        } catch (_: Exception) { }
        return results
    }

    // ==================== HOME PAGE ====================

    override val mainPage = mainPageOf(
        "$mainUrl/index.php?cat=$CATEGORY_ID" to "أنمي مدبلج عربي"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        if (page > MAX_PAGES) return newHomePageResponse(mutableListOf(), false)

        val url = if (page == 1) request.data else "${request.data}&p=$page"
        return try {
            val doc = app.get(url, headers = imageHeaders).document
            val items = doc.select("table#torrents tr").mapNotNull { toSearchResult(it) }
            newHomePageResponse(request.name, items, items.size >= 10 && page < MAX_PAGES)
        } catch (_: Exception) {
            newHomePageResponse(mutableListOf(), false)
        }
    }

    // ==================== SEARCH RESULT ====================

    private fun toSearchResult(row: Element): SearchResponse? {
        return try {
            val magnetEl = row.selectFirst("a[href^=magnet:]") ?: return null
            var magnet = magnetEl.attr("href")

            if (magnet.contains("://") && magnet.contains("magnet:")) {
                val i = magnet.indexOf("magnet:")
                if (i > 0) magnet = magnet.substring(i)
            }

            if (!magnet.startsWith("magnet:")) return null

            val title = titleFromMagnet(magnet) ?: cleanTitle(magnetEl.text())
            if (title.isBlank()) return null

            val posterUrl = row.select("img.posterIcon")
                .find { it.attr("src").contains("211677") }
                ?.parent()?.attr("href") ?: ""

            val fileSize = row.selectFirst("div.fsize")?.text()?.trim() ?: ""
            val detailHref = row.selectFirst("a[href*=tid=]")?.attr("href") ?: ""
            val pageUrl = if (detailHref.isNotBlank()) toAbsoluteUrl(detailHref) else "$mainUrl/"

            val displayName = if (fileSize.isNotEmpty()) "$title | $fileSize" else title

            newAnimeSearchResponse(displayName, "$pageUrl|$magnet|$title|$posterUrl|$fileSize", tvTypeFromTitle(title)) {
                this.posterUrl = posterUrl
                this.posterHeaders = imageHeaders
            }
        } catch (_: Exception) { null }
    }

    // ==================== SEARCH ====================

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val searchUrl = "$mainUrl/index.php?page=torrents&search=${URLEncoder.encode(query, "UTF-8")}&cat_id=ar_anime"
            app.get(searchUrl, headers = imageHeaders).document
                .select("table#torrents tr").mapNotNull { toSearchResult(it) }
                .distinctBy { it.name }
        } catch (_: Exception) { emptyList() }
    }

    // ==================== LOAD ====================

    override suspend fun load(url: String): LoadResponse? {
        val parts = url.split("|", limit = 5)
        val magnet = fixMagnet(parts.getOrNull(1) ?: return null)
        val title = parts.getOrNull(2) ?: "Unknown"
        val posterUrl = parts.getOrNull(3) ?: ""

        if (!magnet.startsWith("magnet:")) return null

        val tvType = tvTypeFromTitle(title)

        // Try TorrServe first — if it's running, we get video files
        val tsFiles = torrServeStream(magnet)

        if (tsFiles != null && tsFiles.size > 1) {
            // Multi-file torrent → show as TvSeries with episodes
            val episodes = tsFiles.mapIndexed { idx, (name, _) ->
                newEpisode(name) {
                    this.name = name
                    this.episode = idx + 1
                    this.season = 1
                }
            }

            // Store the magnet in url field, episode data = "ts|index|hash"
            // We'll reconstruct stream URLs in loadLinks
            val hash = hashFromMagnet(magnet) ?: ""
            val epDataList = tsFiles.mapIndexed { idx, (name, _) ->
                "ts|$hash|${idx + 1}|$name"
            }

            return newTvSeriesLoadResponse(title, url, TvType.Anime,
                epDataList.mapIndexed { idx, epData ->
                    newEpisode(epData) {
                        this.name = tsFiles[idx].first
                        this.episode = idx + 1
                        this.season = 1
                    }
                }
            ) {
                this.posterUrl = posterUrl
                this.posterHeaders = imageHeaders
            }
        }

        if (tsFiles != null && tsFiles.size == 1) {
            // Single file → Movie with TorrServe stream
            return newMovieLoadResponse(title, url, tvType, "tsdirect|${tsFiles[0].second}") {
                this.posterUrl = posterUrl
                this.posterHeaders = imageHeaders
            }
        }

        // No TorrServe — fall back to Movie with raw magnet
        return newMovieLoadResponse(title, url, tvType, "amm|$magnet") {
            this.posterUrl = posterUrl
            this.posterHeaders = imageHeaders
        }
    }

    // ==================== LOAD LINKS ====================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(TAG, "loadLinks: data=${data.take(100)}")

        // TorrServe direct stream: tsdirect|HTTP_URL
        if (data.startsWith("tsdirect|")) {
            val streamUrl = data.substringAfter("tsdirect|")
            callback(
                newExtractorLink(
                    source = "$name (TorrServe)",
                    name = name,
                    url = streamUrl,
                    type = ExtractorLinkType.VIDEO
                )
            )
            return true
        }

        // TorrServe episode: ts|HASH|INDEX|FILENAME
        if (data.startsWith("ts|")) {
            val parts = data.split("|", limit = 4)
            val hash = parts.getOrNull(1) ?: return false
            val index = parts.getOrNull(2) ?: "1"
            val fileName = parts.getOrNull(3) ?: "Video"
            val streamUrl = "$TORRSERVE/stream/fname?link=$hash&index=$index&play"

            callback(
                newExtractorLink(
                    source = "$name (TorrServe)",
                    name = fileName,
                    url = streamUrl,
                    type = ExtractorLinkType.VIDEO
                )
            )
            return true
        }

        // Raw magnet: amm|MAGNET_URL
        if (data.startsWith("amm|")) {
            val magnet = fixMagnet(data.substringAfter("amm|"))
            if (magnet.startsWith("magnet:")) {
                // Try TorrServe one more time (maybe it started since load())
                val tsFiles = torrServeStream(magnet)
                if (tsFiles != null) {
                    for ((name, streamUrl) in tsFiles) {
                        callback(
                            newExtractorLink(
                                source = "$name (TorrServe)",
                                name = name,
                                url = streamUrl,
                                type = ExtractorLinkType.VIDEO
                            )
                        )
                    }
                    return true
                }

                // TorrServe not available — pass magnet to CloudStream
                // NOTE: This uses WebTorrent which rarely works for regular torrents.
                // User needs TorrServe for reliable playback.
                callback(
                    newExtractorLink(
                        source = "$name (Magnet)",
                        name = name,
                        url = magnet,
                        type = ExtractorLinkType.MAGNET
                    )
                )
                return true
            }
        }

        // Direct magnet fallback
        val magnet = fixMagnet(data)
        if (magnet.startsWith("magnet:")) {
            callback(
                newExtractorLink(
                    source = "$name (Magnet)",
                    name = name,
                    url = magnet,
                    type = ExtractorLinkType.MAGNET
                )
            )
            return true
        }

        return false
    }
}
