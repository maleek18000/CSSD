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
    }

    private val imageHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
        "Referer" to "$mainUrl/"
    )

    private val okHttp by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
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

    private fun fixMagnet(url: String): String {
        if (url.startsWith("magnet:")) return url
        val i = url.indexOf("magnet:")
        return if (i > 0) url.substring(i) else url
    }

    private fun hashFromMagnet(magnet: String): String? {
        val match = Regex("""xt=urn:btih:([a-fA-F0-9]{40})""").find(magnet)
            ?: Regex("""xt=urn:btih:([A-Z2-7]{32})""").find(magnet)
        return match?.groupValues?.get(1)?.lowercase()
    }

    // ==================== TORRSERVE ====================

    /**
     * Check if TorrServe is reachable by hitting its root endpoint.
     */
    private fun isTorrServeRunning(): Boolean {
        return try {
            val req = Request.Builder().url(TORRSERVE).build()
            okHttp.newCall(req).execute().use { resp ->
                Log.d(TAG, "TorrServe ping: HTTP ${resp.code}")
                resp.isSuccessful || resp.code == 302 || resp.code == 200
            }
        } catch (e: Exception) {
            Log.w(TAG, "TorrServe not reachable: ${e.message}")
            false
        }
    }

    /**
     * Add magnet to TorrServe and return the torrent hash.
     * Tries multiple API formats since TorrServe versions differ.
     */
    private fun torrServeAddMagnet(magnet: String): String? {
        // Try format 1: POST /torrents with JSON body (TorrServe Matrix)
        try {
            val body = JSONObject().apply {
                put("action", "add")
                put("link", magnet)
                put("save", true)
            }
            val req = Request.Builder()
                .url("$TORRSERVE/torrents")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            okHttp.newCall(req).execute().use { resp ->
                val json = resp.body?.string() ?: ""
                Log.d(TAG, "TorrServe add (format 1): HTTP ${resp.code}, body=${json.take(500)}")

                if (resp.isSuccessful && json.isNotEmpty()) {
                    val hash = parseHash(json)
                    if (hash != null) return hash
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "TorrServe add format 1 error: ${e.message}")
        }

        // Try format 2: POST /torrents with "link" as form data
        try {
            val formBody = "action=add&link=${URLEncoder.encode(magnet, "UTF-8")}&save=true"
            val req = Request.Builder()
                .url("$TORRSERVE/torrents")
                .post(formBody.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                .build()

            okHttp.newCall(req).execute().use { resp ->
                val json = resp.body?.string() ?: ""
                Log.d(TAG, "TorrServe add (format 2): HTTP ${resp.code}, body=${json.take(500)}")

                if (resp.isSuccessful && json.isNotEmpty()) {
                    val hash = parseHash(json)
                    if (hash != null) return hash
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "TorrServe add format 2 error: ${e.message}")
        }

        // Try format 3: GET /torrents/add?link=MAGNET (some older versions)
        try {
            val req = Request.Builder()
                .url("$TORRSERVE/torrents/add?link=${URLEncoder.encode(magnet, "UTF-8")}")
                .get()
                .build()

            okHttp.newCall(req).execute().use { resp ->
                val json = resp.body?.string() ?: ""
                Log.d(TAG, "TorrServe add (format 3): HTTP ${resp.code}, body=${json.take(500)}")

                if (resp.isSuccessful && json.isNotEmpty()) {
                    val hash = parseHash(json)
                    if (hash != null) return hash
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "TorrServe add format 3 error: ${e.message}")
        }

        // Try format 4: If we can extract hash from magnet, try streaming directly
        // Some TorrServe versions auto-add and stream
        val magnetHash = hashFromMagnet(magnet)
        if (magnetHash != null) {
            try {
                // Check if TorrServe already has this torrent
                val req = Request.Builder()
                    .url("$TORRSERVE/stream/fname?link=$magnetHash&index=1&play")
                    .head()
                    .build()
                okHttp.newCall(req).execute().use { resp ->
                    Log.d(TAG, "TorrServe direct stream check: HTTP ${resp.code}")
                    if (resp.isSuccessful) return magnetHash
                }
            } catch (_: Exception) { }
        }

        return null
    }

    private fun parseHash(json: String): String? {
        // Try JSONObject
        try {
            val obj = JSONObject(json)
            val h = obj.optString("hash", "")
            if (h.isNotEmpty() && h.length >= 32) return h.lowercase()
        } catch (_: Exception) { }

        // Try regex
        val match = Regex(""""hash"\s*:\s*"([a-fA-F0-9]{32,})"""").find(json)
        if (match != null) return match.groupValues[1].lowercase()

        // Try array format
        try {
            val obj = JSONObject(json)
            val data = obj.optJSONObject("data")
            if (data != null) {
                val h = data.optString("hash", "")
                if (h.isNotEmpty()) return h.lowercase()
            }
        } catch (_: Exception) { }

        return null
    }

    /**
     * Get file list from TorrServe for a given hash.
     * Polls multiple times since the torrent may still be loading metadata.
     */
    private fun torrServeGetFiles(hash: String): List<Pair<String, Int>> {
        // Try up to 10 times with 2s delay
        for (attempt in 1..10) {
            try {
                Thread.sleep(2000)

                val body = JSONObject().apply {
                    put("action", "get")
                    put("hash", hash)
                }
                val req = Request.Builder()
                    .url("$TORRSERVE/torrents")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                okHttp.newCall(req).execute().use { resp ->
                    val json = resp.body?.string() ?: continue
                    val files = parseFileStats(json)
                    if (files.isNotEmpty()) {
                        Log.d(TAG, "TorrServe got ${files.size} files on attempt $attempt")
                        return files
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

        // Don't try TorrServe here — do it in loadLinks() instead.
        // This keeps load() fast and prevents timeouts.
        // Just pass the magnet URL as data.
        return newMovieLoadResponse(title, url, tvType, magnet) {
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
        val magnet = fixMagnet(data)
        if (!magnet.startsWith("magnet:")) return false

        val title = titleFromMagnet(magnet) ?: name
        var foundAny = false

        // ===== SOURCE 1: TorrServe (direct HTTP stream) =====
        // This is the ONLY reliable way to stream these magnets in CloudStream.
        // WebTorrent (SOURCE 2) cannot connect to regular BitTorrent seeders.
        if (isTorrServeRunning()) {
            Log.d(TAG, "TorrServe is running, adding magnet...")
            val hash = torrServeAddMagnet(magnet)

            if (hash != null) {
                Log.d(TAG, "TorrServe added magnet, hash=$hash, getting file list...")

                // Get file list (polls until metadata is ready)
                val files = torrServeGetFiles(hash)

                val videoExts = listOf(".mkv", ".mp4", ".avi", ".wmv", ".flv", ".mov", ".webm", ".m4v", ".ts", ".mpg", ".mpeg", ".rmvb", ".m2ts")
                val videoFiles = files.filter { (path, _) ->
                    videoExts.any { path.lowercase().endsWith(it) }
                }

                val entries = if (videoFiles.isNotEmpty()) videoFiles else files

                if (entries.isNotEmpty()) {
                    for ((path, id) in entries) {
                        val fileName = path.replace("\\", "/").substringAfterLast("/")
                        val streamUrl = "$TORRSERVE/stream/fname?link=$hash&index=$id&play"

                        Log.d(TAG, "TorrServe source: $fileName (index=$id)")
                        callback(
                            newExtractorLink(
                                source = "$name (TorrServe)",
                                name = fileName,
                                url = streamUrl,
                                type = ExtractorLinkType.VIDEO
                            )
                        )
                        foundAny = true
                    }
                } else {
                    // No file list — try streaming index=1 directly
                    val streamUrl = "$TORRSERVE/stream/fname?link=$hash&index=1&play"
                    Log.d(TAG, "TorrServe source: default index=1")
                    callback(
                        newExtractorLink(
                            source = "$name (TorrServe)",
                            name = title,
                            url = streamUrl,
                            type = ExtractorLinkType.VIDEO
                        )
                    )
                    foundAny = true
                }
            } else {
                Log.w(TAG, "TorrServe is running but failed to add magnet")
            }
        } else {
            Log.w(TAG, "TorrServe is NOT running on $TORRSERVE")
        }

        // ===== SOURCE 2: Raw magnet (WebTorrent) =====
        // This rarely works because CloudStream's WebTorrent uses WebRTC
        // which cannot connect to regular BitTorrent seeders.
        // Only shown as a last resort.
        callback(
            newExtractorLink(
                source = "$name (Magnet - may not work)",
                name = title,
                url = magnet,
                type = ExtractorLinkType.MAGNET
            )
        )
        foundAny = true

        return foundAny
    }
}
