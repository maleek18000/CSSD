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
        private const val TAG = "ArabMagnet_Log"

        // Category 100 = Arabic Dubbed Anime Series
        private const val CATEGORY_ID = 100
        private const val MAX_PAGES = 30

        // TorrServe Matrix API — default host:port
        // Change this if your TorrServe runs on a different address
        private const val TORRSERVE_HOST = "http://127.0.0.1:8090"

        // How many times to poll TorrServe for file list after adding magnet
        private const val TORRSERVE_POLL_ATTEMPTS = 10
        // Delay between polls (ms)
        private const val TORRSERVE_POLL_DELAY = 2000L
    }

    private val imageHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
        "Referer" to "$mainUrl/"
    )

    private val torrServeClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    // ==================== HELPERS ====================

    private fun toAbsoluteUrl(url: String): String {
        return when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            else -> "$mainUrl/$url"
        }
    }

    private fun cleanTitleText(text: String): String {
        return text.replace("\\n", " ").replace("\n", " ").replace(Regex("\\s+"), " ").trim()
    }

    /**
     * Strip the "تحميل " prefix that the site prepends to download link text.
     */
    private fun stripDownloadPrefix(title: String): String {
        return title.removePrefix("تحميل ").removePrefix("تحميل").trim()
    }

    /**
     * Parse the display name from the magnet link's dn= parameter.
     * This gives a cleaner title than the link text.
     */
    private fun titleFromMagnet(magnetUrl: String): String? {
        return try {
            val dnParam = magnetUrl.substringAfter("dn=", "")
                .substringBefore("&")
            if (dnParam.isNotEmpty()) {
                java.net.URLDecoder.decode(dnParam, "UTF-8")
            } else null
        } catch (e: Exception) {
            null
        }
    }

    private fun naturalSortKey(str: String): String {
        return str.replace(Regex("\\d+")) { match ->
            match.value.padStart(6, '0')
        }.lowercase()
    }

    // ==================== TV TYPE HELPERS ====================

    private fun tvTypeFromTitle(title: String): TvType {
        return when {
            title.contains("فيلم", ignoreCase = true) || title.contains("Movie", ignoreCase = true) -> TvType.AnimeMovie
            else -> TvType.Anime
        }
    }

    private fun TvType.toSeriesType(): TvType {
        return when (this) {
            TvType.Anime, TvType.AnimeMovie, TvType.OVA -> TvType.Anime
            TvType.Movie -> TvType.TvSeries
            else -> this
        }
    }

    private fun TvType.toMovieType(): TvType {
        return when (this) {
            TvType.Anime, TvType.AnimeMovie, TvType.OVA -> TvType.AnimeMovie
            TvType.TvSeries -> TvType.Movie
            else -> this
        }
    }

    private fun buildSearchResponse(
        title: String,
        url: String,
        tvType: TvType,
        posterUrl: String = "",
        posterHeaders: Map<String, String> = imageHeaders
    ): SearchResponse {
        return when (tvType) {
            TvType.Movie, TvType.AnimeMovie -> newMovieSearchResponse(title, url, tvType) {
                this.posterUrl = posterUrl
                this.posterHeaders = posterHeaders
            }
            TvType.TvSeries -> newTvSeriesSearchResponse(title, url, tvType) {
                this.posterUrl = posterUrl
                this.posterHeaders = posterHeaders
            }
            else -> newAnimeSearchResponse(title, url, tvType) {
                this.posterUrl = posterUrl
                this.posterHeaders = posterHeaders
            }
        }
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
            // Use "table#torrents tr" NOT "table#torrents > tr" because
            // browsers insert <tbody> between <table> and <tr>
            val items = doc.select("table#torrents tr").mapNotNull { toSearchResult(it) }
            Log.d(TAG, "MainPage page $page: found ${items.size} items")
            newHomePageResponse(request.name, items, items.size >= 10 && page < MAX_PAGES)
        } catch (e: Exception) {
            Log.e(TAG, "MainPage Error: ${e.message}")
            newHomePageResponse(mutableListOf(), false)
        }
    }

    // ==================== SEARCH RESULT BUILDER ====================

    /**
     * Data format stored in SearchResponse.url and passed to load():
     *   magnetUrl|TITLE|POSTER_URL|FILE_SIZE|DETAIL_TID
     */
    private fun toSearchResult(row: Element): SearchResponse? {
        return try {
            // Magnet link (required)
            // The HTML has: <a href = "magnet:..."> — Jsoup normalizes the spaces
            val magnetEl = row.selectFirst("a[href^=magnet:]") ?: return null
            val magnetUrl = magnetEl.attr("href")

            if (magnetUrl.isBlank() || !magnetUrl.startsWith("magnet:")) return null

            // Title: prefer dn= from magnet URL, fall back to link text
            val title = titleFromMagnet(magnetUrl)
                ?: stripDownloadPrefix(cleanTitleText(magnetEl.text()))

            if (title.isBlank()) return null

            // Poster URL: find the img.posterIcon with "211677_image_icon" src,
            // then get its parent <a> tag's href (the actual poster image URL)
            val posterUrl = row.select("img.posterIcon")
                .find { it.attr("src").contains("211677") }
                ?.parent()?.attr("href") ?: ""

            // File size
            val fileSize = row.selectFirst("div.fsize")?.text()?.trim() ?: ""

            // Detail page tid (optional, used for reference)
            val detailTid = row.selectFirst("a[href*=tid=]")
                ?.attr("href")
                ?.substringAfter("tid=")
                ?.substringBefore("&")
                ?.trim() ?: ""

            // Build display name with size info
            val displayName = if (fileSize.isNotEmpty()) "$title | $fileSize" else title

            val tvType = tvTypeFromTitle(title)

            // Encode data: magnetUrl|title|posterUrl|fileSize|detailTid
            val data = "$magnetUrl|$title|$posterUrl|$fileSize|$detailTid"

            buildSearchResponse(displayName, data, tvType, posterUrl, imageHeaders)
        } catch (e: Exception) {
            Log.e(TAG, "toSearchResult Error: ${e.message}")
            null
        }
    }

    // ==================== SEARCH ====================

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()

        // Use the site's search
        try {
            val searchUrl = "$mainUrl/index.php?page=torrents&search=${URLEncoder.encode(query, "UTF-8")}&cat_id=ar_anime"
            val doc = app.get(searchUrl, headers = imageHeaders).document
            val items = doc.select("table#torrents tr").mapNotNull { toSearchResult(it) }
            Log.d(TAG, "Search for '$query': found ${items.size} results")
            results.addAll(items)
        } catch (e: Exception) {
            Log.e(TAG, "Search error: ${e.message}")
        }

        return results.distinctBy { it.name }
    }

    // ==================== LOAD (Detail Page) ====================

    override suspend fun load(url: String): LoadResponse? {
        // Data format: magnetUrl|title|posterUrl|fileSize|detailTid
        val parts = url.split("|", limit = 5)
        val magnetUrl = parts.getOrNull(0) ?: return null
        val title = parts.getOrNull(1) ?: "Unknown"
        val posterUrl = parts.getOrNull(2) ?: ""
        val fileSize = parts.getOrNull(3) ?: ""

        if (!magnetUrl.startsWith("magnet:")) return null

        val tvType = tvTypeFromTitle(title)

        // Try to resolve via TorrServe to detect multi-file torrents
        val streamEntries = tryResolveMagnet(magnetUrl)

        if (streamEntries.isNullOrEmpty()) {
            // TorrServe not available or failed — fall back to movie format with magnet link
            Log.w(TAG, "TorrServe resolve failed for '$title', falling back to direct magnet")
            return newMovieLoadResponse(title, url, tvType.toMovieType(), url) {
                this.posterUrl = posterUrl
                this.posterHeaders = imageHeaders
            }
        }

        if (streamEntries.size == 1) {
            // Single file → Movie
            val entry = streamEntries.first()
            val epData = "ts://${entry.streamUrl}|${entry.fileName}"
            return newMovieLoadResponse(title, url, tvType.toMovieType(), epData) {
                this.posterUrl = posterUrl
                this.posterHeaders = imageHeaders
            }
        }

        // Multiple files → TV Series with episodes
        val sortedEntries = streamEntries.sortedBy { naturalSortKey(it.fileName) }
        val folderGroups = sortedEntries.groupBy { it.folderName }
        val distinctFolders = folderGroups.keys.filter { it.isNotEmpty() }.sortedBy { naturalSortKey(it) }

        val episodes = mutableListOf<Episode>()
        val seasonNamesList = mutableListOf<SeasonData>()
        val addedSeasons = mutableSetOf<Int>()

        if (distinctFolders.size > 1) {
            for ((folderIndex, folder) in distinctFolders.withIndex()) {
                val seasonNum = folderIndex + 1
                val folderEntries = folderGroups[folder] ?: continue

                if (!addedSeasons.contains(seasonNum)) {
                    seasonNamesList.add(SeasonData(seasonNum, folder))
                    addedSeasons.add(seasonNum)
                }

                for ((epIndex, entry) in folderEntries.withIndex()) {
                    val epData = "ts://${entry.streamUrl}|${entry.fileName}"
                    episodes.add(
                        newEpisode(epData) {
                            name = entry.fileName
                            season = seasonNum
                            episode = epIndex + 1
                        }
                    )
                }
            }

            val otherEntries = folderGroups[""]
            if (otherEntries != null && otherEntries.isNotEmpty()) {
                val otherSeasonNum = distinctFolders.size + 1
                if (!addedSeasons.contains(otherSeasonNum)) {
                    seasonNamesList.add(SeasonData(otherSeasonNum, "أخرى"))
                    addedSeasons.add(otherSeasonNum)
                }
                for ((epIndex, entry) in otherEntries.withIndex()) {
                    val epData = "ts://${entry.streamUrl}|${entry.fileName}"
                    episodes.add(
                        newEpisode(epData) {
                            name = entry.fileName
                            season = otherSeasonNum
                            episode = epIndex + 1
                        }
                    )
                }
            }
        } else if (distinctFolders.size == 1) {
            val folder = distinctFolders.first()
            val folderEntries = folderGroups[folder] ?: emptyList()
            seasonNamesList.add(SeasonData(1, folder))

            for ((epIndex, entry) in folderEntries.withIndex()) {
                val epData = "ts://${entry.streamUrl}|${entry.fileName}"
                episodes.add(
                    newEpisode(epData) {
                        name = entry.fileName
                        season = 1
                        episode = epIndex + 1
                    }
                )
            }

            val rootEntries = folderGroups[""]
            if (rootEntries != null && rootEntries.isNotEmpty()) {
                for ((epIndex, entry) in rootEntries.withIndex()) {
                    val epData = "ts://${entry.streamUrl}|${entry.fileName}"
                    episodes.add(
                        newEpisode(epData) {
                            name = entry.fileName
                            season = 1
                            episode = folderEntries.size + epIndex + 1
                        }
                    )
                }
            }
        } else {
            seasonNamesList.add(SeasonData(1, "الموسم 1"))
            for ((epIndex, entry) in sortedEntries.withIndex()) {
                val epData = "ts://${entry.streamUrl}|${entry.fileName}"
                episodes.add(
                    newEpisode(epData) {
                        name = entry.fileName
                        season = 1
                        episode = epIndex + 1
                    }
                )
            }
        }

        Log.d(TAG, "load: ${episodes.size} episodes, ${seasonNamesList.size} seasons for '$title'")

        return newTvSeriesLoadResponse(title, url, tvType.toSeriesType(), episodes) {
            this.posterUrl = posterUrl
            this.posterHeaders = imageHeaders
            seasonNames = seasonNamesList.ifEmpty { null }
        }
    }

    // ==================== LOAD LINKS ====================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // === PRE-RESOLVED STREAM (ts://...): from load() with TorrServe ===
        val tsData = if (data.startsWith("ts://")) {
            data
        } else if (data.contains("ts://")) {
            data.substring(data.indexOf("ts://"))
        } else {
            null
        }

        if (tsData != null) {
            val afterPrefix = tsData.substringAfter("ts://")
            val pipeIndex = afterPrefix.indexOf('|')
            val streamUrl = if (pipeIndex >= 0) afterPrefix.substring(0, pipeIndex) else afterPrefix
            val fileName = if (pipeIndex >= 0) afterPrefix.substring(pipeIndex + 1) else "Video"

            Log.d(TAG, "loadLinks: pre-resolved stream → $fileName")
            callback(
                newExtractorLink(
                    source = this.name,
                    name = fileName,
                    url = streamUrl,
                    type = ExtractorLinkType.VIDEO
                )
            )
            return true
        }

        // === FALLBACK: Direct magnet link (TorrServe not available) ===
        val magnetUrl = if (data.startsWith("magnet:")) {
            data.substringBefore("|")
        } else if (data.contains("magnet:")) {
            data.substring(data.indexOf("magnet:")).substringBefore("|")
        } else {
            null
        }

        if (magnetUrl != null && magnetUrl.startsWith("magnet:")) {
            Log.d(TAG, "loadLinks: passing magnet link directly")
            callback(
                newExtractorLink(
                    source = this.name,
                    name = "${this.name} (Magnet)",
                    url = magnetUrl,
                    type = ExtractorLinkType.MAGNET
                )
            )
            return true
        }

        Log.e(TAG, "loadLinks: no valid data format found")
        return false
    }

    // ==================== TORRSERVE INTEGRATION ====================

    data class TorrServeStreamEntry(
        val fileName: String,
        val filePath: String,
        val folderName: String,
        val fileIndex: Int,
        val streamUrl: String
    )

    private fun tryResolveMagnet(magnetUrl: String): List<TorrServeStreamEntry>? {
        return try {
            val jsonBody = JSONObject().apply {
                put("action", "add")
                put("link", magnetUrl)
                put("save", true)
            }

            val addRequest = Request.Builder()
                .url("$TORRSERVE_HOST/torrents")
                .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val hash: String
            var fileStats: List<Pair<String, Int>>

            torrServeClient.newCall(addRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "TorrServe add magnet HTTP error: ${response.code}")
                    return null
                }

                val responseBody = response.body?.string() ?: return null
                Log.d(TAG, "TorrServe add magnet response (${responseBody.length} chars): ${responseBody.take(500)}")

                hash = parseHashFromResponse(responseBody)
                    ?: run {
                        Log.e(TAG, "Could not parse hash from TorrServe response")
                        return null
                    }

                Log.d(TAG, "TorrServe hash: $hash")
                fileStats = parseFileStatsFromResponse(responseBody)
                Log.d(TAG, "Parsed ${fileStats.size} files from add response")
            }

            if (fileStats.isEmpty()) {
                Log.d(TAG, "file_stats empty in add response, polling TorrServe API...")
                fileStats = pollTorrServeFileList(hash)
            }

            if (fileStats.isEmpty()) {
                Log.w(TAG, "No file_stats found after polling — falling back to index=1")
                return listOf(
                    TorrServeStreamEntry(
                        fileName = "Video",
                        filePath = "Video",
                        folderName = "",
                        fileIndex = 1,
                        streamUrl = "$TORRSERVE_HOST/stream/fname?link=$hash&index=1&play"
                    )
                )
            }

            val videoEntries = fileStats
                .filter { (path, _) -> isVideoFile(path) }
                .map { (path, id) ->
                    val normalizedPath = normalizePath(path)
                    val fileName = normalizedPath.substringAfterLast("/")
                    val folderName = extractFolderName(normalizedPath)

                    TorrServeStreamEntry(
                        fileName = fileName,
                        filePath = normalizedPath,
                        folderName = folderName,
                        fileIndex = id,
                        streamUrl = "$TORRSERVE_HOST/stream/fname?link=$hash&index=$id&play"
                    )
                }

            Log.d(TAG, "Created ${videoEntries.size} video stream entries (from ${fileStats.size} total files)")

            if (videoEntries.isEmpty()) {
                val first = fileStats.first()
                val normalizedPath = normalizePath(first.first)
                listOf(
                    TorrServeStreamEntry(
                        fileName = normalizedPath.substringAfterLast("/"),
                        filePath = normalizedPath,
                        folderName = extractFolderName(normalizedPath),
                        fileIndex = first.second,
                        streamUrl = "$TORRSERVE_HOST/stream/fname?link=$hash&index=${first.second}&play"
                    )
                )
            } else {
                videoEntries.sortedBy { naturalSortKey(it.fileName) }
            }
        } catch (e: java.net.ConnectException) {
            Log.e(TAG, "TorrServe connection refused — is TorrServe running on $TORRSERVE_HOST?")
            null
        } catch (e: Exception) {
            Log.e(TAG, "tryResolveMagnet Error: ${e.message}")
            null
        }
    }

    private fun pollTorrServeFileList(hash: String): List<Pair<String, Int>> {
        for (attempt in 1..TORRSERVE_POLL_ATTEMPTS) {
            try {
                Thread.sleep(TORRSERVE_POLL_DELAY)

                val jsonBody = JSONObject().apply {
                    put("action", "get")
                    put("hash", hash)
                }

                val request = Request.Builder()
                    .url("$TORRSERVE_HOST/torrents")
                    .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                torrServeClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "TorrServe poll attempt $attempt: HTTP ${response.code}")
                        return@use
                    }

                    val responseBody = response.body?.string() ?: return@use
                    Log.d(TAG, "TorrServe poll attempt $attempt: ${responseBody.take(300)}")

                    val fileStats = parseFileStatsFromResponse(responseBody)
                    if (fileStats.isNotEmpty()) {
                        Log.d(TAG, "TorrServe poll SUCCESS on attempt $attempt: ${fileStats.size} files")
                        return fileStats
                    }

                    try {
                        val json = JSONObject(responseBody)
                        val stat = json.optInt("stat", -1)
                        val statString = json.optString("stat_string", "")
                        Log.d(TAG, "TorrServe torrent stat=$stat ($statString)")

                        if (stat == 4) {
                            Log.e(TAG, "TorrServe torrent closed/errored, stopping polls")
                            return emptyList()
                        }
                    } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                Log.w(TAG, "TorrServe poll attempt $attempt error: ${e.message}")
            }
        }

        Log.w(TAG, "TorrServe polling exhausted after $TORRSERVE_POLL_ATTEMPTS attempts")
        return emptyList()
    }

    // ==================== JSON PARSING ====================

    private fun parseHashFromResponse(json: String): String? {
        return try {
            val obj = JSONObject(json)
            val hashValue = obj.optString("hash", "")
            if (hashValue.isNotEmpty()) hashValue.lowercase() else null
        } catch (e: Exception) {
            Log.w(TAG, "JSONObject hash parsing failed, trying regex: ${e.message}")
            val hashPattern = """"hash"\s*:\s*"([a-fA-F0-9]{40})"""".toRegex()
            hashPattern.find(json)?.groupValues?.getOrNull(1)?.lowercase()
        }
    }

    private fun parseFileStatsFromResponse(json: String): List<Pair<String, Int>> {
        val results = mutableListOf<Pair<String, Int>>()

        try {
            val jsonObj = JSONObject(json)
            parseFileStatsFromObject(jsonObj, results)
        } catch (_: Exception) {
            try {
                val jsonArr = JSONArray(json)
                for (i in 0 until jsonArr.length()) {
                    parseFileStatsFromObject(jsonArr.getJSONObject(i), results)
                }
            } catch (e: Exception) {
                Log.w(TAG, "parseFileStatsFromResponse: both object and array parsing failed: ${e.message}")
            }
        }

        return results
    }

    private fun parseFileStatsFromObject(obj: JSONObject, results: MutableList<Pair<String, Int>>) {
        val fileStats = obj.optJSONArray("file_stats") ?: return

        for (i in 0 until fileStats.length()) {
            try {
                val fileObj = fileStats.getJSONObject(i)
                val id = fileObj.optInt("id", -1)
                val path = fileObj.optString("path", "")
                if (id > 0 && path.isNotEmpty()) {
                    results.add(path to id)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error parsing file_stats entry $i: ${e.message}")
            }
        }
    }

    // ==================== PATH HELPERS ====================

    private fun normalizePath(path: String): String {
        return path.replace("\\", "/")
    }

    private fun extractFolderName(normalizedPath: String): String {
        val slashIndex = normalizedPath.indexOf('/')
        return if (slashIndex > 0) normalizedPath.substring(0, slashIndex) else ""
    }

    private fun isVideoFile(fileName: String): Boolean {
        val videoExtensions = listOf(
            ".mkv", ".mp4", ".avi", ".wmv", ".flv", ".mov", ".webm",
            ".m4v", ".ts", ".mpg", ".mpeg", ".ogv", ".rmvb", ".rm",
            ".vob", ".m2ts", ".mts", ".3gp", ".divx"
        )
        val lower = fileName.lowercase()
        return videoExtensions.any { lower.endsWith(it) }
    }
}
