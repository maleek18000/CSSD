package com.lagradost.cloudstream3.ar.youtube

import org.json.JSONObject
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import okhttp3.Interceptor
import okhttp3.Response
import java.net.URLEncoder
import java.util.regex.Pattern
import com.lagradost.cloudstream3.AcraApplication
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.ar.youtube.YoutubeProvider.Config.SLEEP_BETWEEN
import kotlin.collections.remove
import kotlin.text.get

class YoutubeProvider(
    private val sharedPref: SharedPreferences? = null
) : MainAPI() {



    data class CustomSection(
        @JsonProperty("name") var name: String = "",
        @JsonProperty("url") var url: String = "",
        @JsonProperty("isEnabled") var isEnabled: Boolean = true // المتغير الجديد لحالة التفعيل
    )
    object Config {
        const val SLEEP_BETWEEN = 1
    }
    override var mainUrl = "https://www.youtube.com"
    override var name = "YouTube"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Movie, TvType.Live)

    companion object {

        private val homeShorts = mutableListOf<Episode>()
        private val searchShorts = mutableListOf<Episode>()

        // Cap shorts lists to prevent unbounded memory growth on low-RAM devices.
        // When the list exceeds the cap, the oldest half is removed.
        private const val MAX_SHORTS = 100

        private fun addShortCapped(list: MutableList<Episode>, episode: Episode) {
            list.add(episode)
            if (list.size > MAX_SHORTS) {
                list.subList(0, MAX_SHORTS / 2).clear()
            }
        }
    }

    private var isSearchContext = false


    class YouTubeInterceptor(private val prefs: SharedPreferences?) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val requestBuilder = chain.request().newBuilder()
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
                )

            val cookieBuilder = StringBuilder()
            val visitor = prefs?.getString("VISITOR_INFO1_LIVE", null)

            if (!visitor.isNullOrBlank()) {
                cookieBuilder.append("VISITOR_INFO1_LIVE=$visitor; ")
            } else {
                cookieBuilder.append("VISITOR_INFO1_LIVE=_Mk3UVhY40g; ")
            }

            val authKeys = listOf("SID", "HSID", "SSID", "APISID", "SAPISID")
            authKeys.forEach { key ->
                val value = prefs?.getString(key, null)
                if (!value.isNullOrBlank()) {
                    cookieBuilder.append("$key=$value; ")
                }
            }
            cookieBuilder.append("PREF=f6=40000000&hl=en; CONSENT=YES+fx.456722336;")

            requestBuilder.addHeader("Cookie", cookieBuilder.toString())
            return chain.proceed(requestBuilder.build())
        }
    }

    private val ytInterceptor = YouTubeInterceptor(sharedPref)

    private var savedContinuationToken: String? = null
    private var savedVisitorData: String? = null
    private var savedApiKey: String? = null
    private var savedClientVersion: String? = null

    // Cache INNERTUBE config values to avoid re-parsing them from HTML on
    // every home page section load. These values are stable for days/weeks.
    private var innerTubeConfigCache: InnerTubeConfig? = null
    private data class InnerTubeConfig(val apiKey: String, val clientVersion: String, val visitorData: String?)

    /**
     * Extract and cache INNERTUBE_API_KEY, INNERTUBE_CLIENT_VERSION, and VISITOR_DATA
     * from the watch page HTML. Only parses once per session — subsequent calls
     * return the cached values.
     */
    private fun getInnerTubeConfig(html: String): InnerTubeConfig {
        innerTubeConfigCache?.let { return it }
        val config = InnerTubeConfig(
            apiKey = findConfig(html, "INNERTUBE_API_KEY") ?: "",
            clientVersion = findConfig(html, "INNERTUBE_CLIENT_VERSION") ?: "2.20240725.01.00",
            visitorData = findConfig(html, "VISITOR_DATA")
        )
        if (config.apiKey.isNotBlank()) {
            innerTubeConfigCache = config
            // Also update the legacy fields for code that still uses them
            savedApiKey = config.apiKey
            savedClientVersion = config.clientVersion
            savedVisitorData = config.visitorData
        }
        return config
    }

    @Suppress("PropertyName")
    private data class PlayerResponse(@JsonProperty("streamingData") val streamingData: StreamingData?)
    private data class StreamingData(@JsonProperty("hlsManifestUrl") val hlsManifestUrl: String?)




    private fun Map<*, *>.getMapKey(key: String): Map<*, *>? =
        this.entries.firstOrNull { (it.key as? String) == key }?.value as? Map<*, *>

    private fun Map<*, *>.getListKey(key: String): List<Map<*, *>>? =
        this.entries.firstOrNull { (it.key as? String) == key }?.value as? List<Map<*, *>>

    private fun Map<*, *>.getString(key: String): String? =
        this.entries.firstOrNull { (it.key as? String) == key }?.value as? String


    private fun getText(obj: Any?): String {
        if (obj == null) return ""
        if (obj is String) return obj
        if (obj is Map<*, *>) {
            return obj.getString("simpleText")
                ?: obj.getString("text")
                ?: obj.getString("content")
                ?: obj.getString("label")
                ?: obj.getListKey("runs")?.joinToString("") { run ->
                    when (run) {
                        is String -> run
                        is Map<*, *> -> run.getString("text")
                            ?: run.getString("simpleText")
                            ?: ""

                        else -> ""
                    }
                }.orEmpty()
                ?: obj.getMapKey("text")?.let { getText(it) }.orEmpty()
        }
        return ""
    }

    private fun extractLockupMetadata(lockup: Map<*, *>): Pair<String, String> {
        var channel = ""
        var views = ""

        try {
            val rows = lockup.getMapKey("metadata")
                ?.getMapKey("lockupMetadataViewModel")
                ?.getMapKey("metadata")
                ?.getMapKey("contentMetadataViewModel")
                ?.getListKey("metadataRows")

            rows?.forEach { row ->
                val parts = row.getListKey("metadataParts")
                parts?.forEach { part ->
                    val text = getText(part.getMapKey("text")) ?: ""
                    if (text.isNotBlank()) {

                        if (text.matches(Regex(".*(\\d+[KMBkmb]|views|مشاهدة).*"))) {
                            views = formatViews(text)
                        }

                        else if (!text.matches(Regex(".*(\\d+:\\d+|ago|قبل).*")) && text.length > 1 && !text.contains(
                                "•"
                            )
                        ) {
                            channel = text
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }

        if (views.isEmpty() || views == "N/A") {
            val direct = getText(
                lockup.getMapKey("metadata")?.getMapKey("lockupMetadataViewModel")
                    ?.getMapKey("viewCount")
            )
            if (!direct.isNullOrBlank()) views = formatViews(direct)
        }

        return Pair(channel, views)
    }

    private fun formatViews(viewText: String?): String {
        if (viewText.isNullOrBlank()) return "N/A"
        val text = viewText.toString()
        if (text.any { it in listOf('K', 'M', 'B', 'k', 'm', 'b') } && text.length < 15) {
            return text.split("view")[0].split("مشاهدة")[0].trim()
        }
        val digits = text.filter { it.isDigit() }
        if (digits.isBlank()) return text
        return try {
            val v = digits.toLong()
            when {
                v < 1000 -> v.toString()
                v < 1_000_000 -> String.format("%.1fK", v / 1000.0).replace(".0K", "K")
                v < 1_000_000_000 -> String.format("%.1fM", v / 1_000_000.0).replace(".0M", "M")
                else -> String.format("%.1fB", v / 1_000_000_000.0).replace(".0B", "B")
            }
        } catch (e: Exception) {
            text
        }
    }

    private fun getRawText(map: Map<*, *>?, key: String): String? {
        val obj = map?.getMapKey(key) ?: return null
        return obj.getString("simpleText")
            ?: obj.getListKey("runs")?.firstOrNull()?.getString("text")
    }

    private fun getBestThumbnail(thumbData: Any?): String? {
        return try {
            val thumbs = when (thumbData) {
                is Map<*, *> -> (thumbData["thumbnails"] as? List<*>)
                    ?: (thumbData["sources"] as? List<*>)

                is List<*> -> thumbData
                else -> null
            }
            val lastThumb = thumbs?.lastOrNull() as? Map<*, *>
            var url = lastThumb?.get("url") as? String
            if (url?.startsWith("//") == true) url = "https:$url"
            url
        } catch (e: Exception) {
            null
        }
    }

    private fun buildThumbnailFromId(videoId: String?): String? {
        if (videoId.isNullOrBlank()) return null
        return "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
    }




    private fun collectFromRenderer(
        renderer: Map<*, *>?,
        seenIds: MutableSet<String>
    ): SearchResponse? {
        if (renderer == null) return null

        val videoData = renderer.getMapKey("videoRenderer")
            ?: renderer.getMapKey("compactVideoRenderer")
            ?: renderer.getMapKey("gridVideoRenderer")

        if (videoData != null) {
            val videoId = videoData.getString("videoId")
            if (!videoId.isNullOrBlank() && seenIds.add(videoId)) {
                val title = getText(videoData.getMapKey("title")) ?: "Video"
                val viewText = getText(videoData.getMapKey("viewCountText"))
                    ?: getText(videoData.getMapKey("shortViewCountText"))
                val channel = getText(videoData.getMapKey("ownerText"))
                    ?: getText(videoData.getMapKey("shortBylineText")) ?: ""
                val views = formatViews(viewText)
                val finalTitle =
                    if (channel.isNotBlank()) "{$channel | $views} $title" else "{$views} $title"
                var poster = getBestThumbnail(videoData.getMapKey("thumbnail"))
                if (poster.isNullOrBlank()) poster = buildThumbnailFromId(videoId)
                return newMovieSearchResponse(
                    finalTitle,
                    "$mainUrl/watch?v=$videoId",
                    TvType.Movie
                ) { this.posterUrl = poster }
            }
            return null
        }

        val richContent = renderer.getMapKey("richItemRenderer")?.getMapKey("content")
        val shortsData = renderer.getMapKey("reelItemRenderer")
            ?: renderer.getMapKey("shortsLockupViewModel")
            ?: richContent?.getMapKey("shortsLockupViewModel")

        if (shortsData != null) {
            val onTap = shortsData.getMapKey("onTap")
            val videoId = onTap?.getMapKey("innertubeCommand")?.getMapKey("reelWatchEndpoint")
                ?.getString("videoId")
                ?: shortsData.getString("videoId")
                ?: shortsData.getString("entityId")?.replace("shorts-shelf-item-", "")

            if (!videoId.isNullOrBlank() && seenIds.add(videoId)) {
                val overlay = shortsData.getMapKey("overlayMetadata")
                val accessibilityText = shortsData.getString("accessibilityText") ?: ""

                var title = overlay?.getMapKey("primaryText")?.getString("content")
                if (title.isNullOrBlank()) title = getText(shortsData.getMapKey("headline"))
                if (title.isNullOrBlank()) title =
                    overlay?.getMapKey("primaryText")?.getString("simpleText")
                if (title.isNullOrBlank() && accessibilityText.contains(",")) title =
                    accessibilityText.substringBefore(",").trim()
                if (title.isNullOrBlank()) title = "Shorts Clip"

                var viewRaw = overlay?.getMapKey("secondaryText")?.getString("content")
                if (viewRaw.isNullOrBlank()) viewRaw =
                    getText(shortsData.getMapKey("viewCountText"))
                if (viewRaw.isNullOrBlank() && accessibilityText.isNotBlank()) {
                    val match = Regex(",\\s*(.*?)\\s*-").find(accessibilityText)
                    if (match != null) viewRaw = match.groupValues[1].trim()
                }
                val views = formatViews(viewRaw)

                var poster =
                    shortsData.getMapKey("thumbnail")?.getListKey("thumbnails")?.lastOrNull()
                        ?.getString("url")
                if (poster.isNullOrBlank()) poster =
                    shortsData.getMapKey("thumbnailViewModel")?.getMapKey("thumbnailViewModel")
                        ?.getMapKey("image")?.getListKey("sources")?.lastOrNull()?.getString("url")
                if (poster.isNullOrBlank()) poster = "https://i.ytimg.com/vi/$videoId/oar2.jpg"

                val currentList = if (isSearchContext) searchShorts else homeShorts
                val episodeNum = currentList.size + 1
                val contextTag = if (isSearchContext) "&ctx=search" else "&ctx=home"
                val finalUrl = "$mainUrl/shorts/$videoId$contextTag"

                if (currentList.none { it.data == finalUrl }) {
                    addShortCapped(currentList, newEpisode(finalUrl) {
                        this.name = title
                        this.posterUrl = poster
                        this.episode = episodeNum
                    })
                }

                val finalTitle = "#$episodeNum {$views} $title"

                return newMovieSearchResponse(finalTitle, finalUrl, TvType.Movie) {
                    this.posterUrl = poster
                }
            }
        }

        val lockup = renderer.getMapKey("lockupViewModel")
        if (lockup != null) {
            if (lockup.getString("contentType") == "LOCKUP_CONTENT_TYPE_PLAYLIST") {
                val playlistId = lockup.getString("contentId")
                if (!playlistId.isNullOrBlank() && seenIds.add(playlistId)) {
                    val title = lockup.getMapKey("metadata")?.getMapKey("lockupMetadataViewModel")
                        ?.getMapKey("title")?.getString("content") ?: "Playlist"
                    val episodeCount =
                        lockup.getMapKey("contentImage")?.getMapKey("collectionThumbnailViewModel")
                            ?.getMapKey("primaryThumbnail")?.getMapKey("thumbnailViewModel")
                            ?.getListKey("overlays")?.firstOrNull()
                            ?.getMapKey("thumbnailOverlayBadgeViewModel")
                            ?.getListKey("thumbnailBadges")?.firstOrNull()
                            ?.getMapKey("thumbnailBadgeViewModel")?.getString("text") ?: ""
                    val poster =
                        lockup.getMapKey("contentImage")?.getMapKey("collectionThumbnailViewModel")
                            ?.getMapKey("primaryThumbnail")?.getMapKey("thumbnailViewModel")
                            ?.getMapKey("image")?.getListKey("sources")?.lastOrNull()
                            ?.getString("url")

                    val finalTitle =
                        if (episodeCount.isNotEmpty()) "$title ($episodeCount)" else title
                    return newTvSeriesSearchResponse(
                        finalTitle,
                        "$mainUrl/playlist?list=$playlistId",
                        TvType.TvSeries
                    ) { this.posterUrl = poster }
                }
            }

            val videoId = lockup.getString("contentId")
                ?: lockup.getMapKey("content")?.getString("videoId")
                ?: (lockup.getMapKey("content")?.getMapKey("videoRenderer")?.getString("videoId"))

            if (!videoId.isNullOrBlank() && seenIds.add(videoId)) {
                var title = getText(
                    lockup.getMapKey("metadata")?.getMapKey("lockupMetadataViewModel")
                        ?.getMapKey("title")
                )
                if (title.isEmpty()) title = "YouTube Video"
                var (channel, views) = extractLockupMetadata(lockup)
                if (channel.isBlank()) {
                    val label = lockup.getMapKey("accessibility")?.getMapKey("accessibilityData")
                        ?.getString("label") ?: ""
                    val match =
                        Regex("(?:by|من|عبر|قناة)\\s+(.*?)\\s+(?:\\d|view|مشاهدة)").find(label)
                    if (match != null) channel = match.groupValues[1].replace("Shorts", "").trim()
                }
                val isShorts = lockup.getMapKey("content")
                    ?.containsKey("shortsLockupViewModel") == true || lockup.toString()
                    .contains("reelWatchEndpoint")
                val finalTitle: String
                val poster: String

                if (isShorts) {
                    val currentList = if (isSearchContext) searchShorts else homeShorts
                    val episodeNum = currentList.size + 1
                    val contextTag = if (isSearchContext) "&ctx=search" else "&ctx=home"
                    val finalUrl = "$mainUrl/shorts/$videoId$contextTag"
                    poster = "https://i.ytimg.com/vi/$videoId/oar2.jpg"
                    if (currentList.none { it.data == finalUrl }) {
                        addShortCapped(currentList, newEpisode(finalUrl) {
                            this.name = title
                            this.posterUrl = poster
                            this.episode = episodeNum
                        })
                    }
                    finalTitle = "#$episodeNum [Shorts] {$views} $title"
                    return newMovieSearchResponse(
                        finalTitle,
                        finalUrl,
                        TvType.Movie
                    ) { this.posterUrl = poster }
                } else {
                    finalTitle =
                        if (channel.isNotBlank()) "{$channel | $views} $title" else "{$views} $title"
                    poster = getBestThumbnail(
                        lockup.getMapKey("contentImage")?.getMapKey("image")?.getListKey("sources")
                    ) ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
                    return newMovieSearchResponse(
                        finalTitle,
                        "$mainUrl/watch?v=$videoId",
                        TvType.Movie
                    ) { this.posterUrl = poster }
                }
            }
        }

        val channelData = renderer.getMapKey("channelRenderer")
        if (channelData != null) {
            val id = channelData.getString("channelId")
            if (!id.isNullOrBlank() && seenIds.add(id)) {
                val title = getText(channelData.getMapKey("title")) ?: "Channel"
                val stats = (getText(channelData.getMapKey("videoCountText"))
                    ?: getText(channelData.getMapKey("subscriberCountText"))) ?: ""
                val poster = getBestThumbnail(channelData.getMapKey("thumbnail"))
                return newMovieSearchResponse(
                    "$title ($stats)",
                    "$mainUrl/channel/$id",
                    TvType.Live
                ) { this.posterUrl = poster }
            }
        }
        return null
    }


    private fun processRecursive(
        data: Any?,
        outList: MutableList<SearchResponse>,
        seenIds: MutableSet<String>,
        playlistMode: Boolean
    ) {
        if (data is Map<*, *>) {

            val extracted = collectFromRenderer(data, seenIds)
            if (extracted != null) {
                if (!playlistMode || extracted.type == TvType.TvSeries) {
                    outList.add(extracted)
                }
                return
            }

            val keysToCheck = listOf(
                "contents",
                "items",
                "gridShelfViewModel",
                "verticalListRenderer",
                "horizontalListRenderer",
                "shelfRenderer",
                "itemSectionRenderer",
                "richShelfRenderer",
                "reelShelfRenderer",
                "appendContinuationItemsAction",
                "onResponseReceivedCommands"
            )
            var foundContainer = false
            for (key in keysToCheck) {
                if (data.containsKey(key)) {
                    processRecursive(data[key], outList, seenIds, playlistMode)
                    foundContainer = true
                }
            }
            if (!foundContainer) {
                for (value in data.values) {
                    if (value is Map<*, *> || value is List<*>) {
                        processRecursive(value, outList, seenIds, playlistMode)
                    }
                }
            }
        } else if (data is List<*>) {
            for (item in data) {
                processRecursive(item, outList, seenIds, playlistMode)
            }
        }
    }

    private fun extractYtInitialData(html: String): Map<String, Any>? {
        // IMPORTANT: the regex must be NON-greedy and terminated by a real end-of-script
        // marker. The original greedy `(\{.*\});` captured from the first `{` to the
        // last `};` in the entire HTML, which pulled in unrelated JavaScript and made
        // JSON parsing fail with "Extra data" — so this function returned null for
        // playlist pages (and any page whose ytInitialData wasn't the last `};` in
        // the document), causing the playlist `load()` handler to silently throw
        // and CloudStream to fall back to "Coming soon".
        val patterns = listOf(
            // Preferred: the JSON is followed by `;</script>` (the standard YouTube layout).
            Regex(
                """(?:var ytInitialData|window\["ytInitialData"\])\s*=\s*(\{.*?\})\s*;\s*</script""",
                RegexOption.DOT_MATCHES_ALL
            ),
            // Fallback: the JSON is followed by `;\n` and a non-JSON line (older layout).
            Regex(
                """(?:var ytInitialData|window\["ytInitialData"\])\s*=\s*(\{.*?\})\s*;\s*\n""",
                RegexOption.DOT_MATCHES_ALL
            )
        )
        for (regex in patterns) {
            val match = try { regex.find(html) } catch (e: Exception) { null } ?: continue
            val candidate = match.groupValues.getOrNull(1) ?: continue
            // Try parsing incrementally — if the candidate has trailing garbage, trim
            // it to the last balanced `}` before giving up.
            try {
                return parseJson<Map<String, Any>>(candidate)
            } catch (_: Exception) {
                val balanced = trimToBalancedJson(candidate) ?: continue
                try {
                    return parseJson<Map<String, Any>>(balanced)
                } catch (_: Exception) { /* try next pattern */ }
            }
        }
        return null
    }

    /**
     * Best-effort trim of a string that starts with `{` to a balanced JSON object.
     * Used as a safety net when the non-greedy regex still captures a tiny bit of
     * trailing content. Returns null if no balanced prefix can be found.
     */
    private fun trimToBalancedJson(s: String): String? {
        if (s.isEmpty() || s[0] != '{') return null
        var depth = 0
        var inString = false
        var escape = false
        s.forEachIndexed { i, c ->
            if (escape) { escape = false; return@forEachIndexed }
            if (inString) {
                when (c) {
                    '\\' -> escape = true
                    '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return s.substring(0, i + 1)
                    }
                }
            }
        }
        return null
    }

    private fun findConfig(html: String, key: String): String? {
        return try {
            val m = Regex("\"$key\"\\s*:\\s*\"([^\"]+)\"").find(html)
            m?.groupValues?.getOrNull(1)
        } catch (e: Exception) {
            null
        }
    }

    private fun findTokenRecursive(data: Any?): String? {
        if (data is Map<*, *>) {
            if (data.containsKey("continuationCommand")) {
                val cmd = data["continuationCommand"] as? Map<*, *>
                // OLD layout: token sits directly under continuationCommand
                val directToken = cmd?.getString("token")
                if (!directToken.isNullOrBlank()) return directToken
                // NEW layout: token is nested one level deeper, under
                // continuationCommand.innertubeCommand.continuationCommand.token
                val innerToken = cmd?.getMapKey("innertubeCommand")
                    ?.getMapKey("continuationCommand")
                    ?.getString("token")
                if (!innerToken.isNullOrBlank()) return innerToken
                // Fallback: keep recursing into the continuationCommand value
                // in case YouTube adds yet another nesting level.
                val deeper = findTokenRecursive(cmd)
                if (!deeper.isNullOrBlank()) return deeper
            }
            for (v in data.values) {
                val t = findTokenRecursive(v); if (t != null) return t
            }
        } else if (data is List<*>) {
            for (i in data) {
                val t = findTokenRecursive(i); if (t != null) return t
            }
        }
        return null
    }

    override val mainPage: List<MainPageData>
        get() {
            val list = mutableListOf<MainPageData>()
            val isEn = lang == "en"

            // Read the trending toggle from the "YouTube" SharedPreferences file.
            // As a safety net, ALSO check the app-wide default SharedPreferences
            // (PreferenceManager.getDefaultSharedPreferences) — because the settings
            // fragment's SwitchPreferenceCompat writes there too. If EITHER file
            // says "off", we hide the row. This makes the toggle bulletproof even
            // if the setOnPreferenceChangeListener in SettingsFragment somehow
            // fails to fire.
            val showTrending = isTrendingEnabled()
            if (showTrending) {
                list.add(MainPageData(if (isEn) "Trending" else "الرئيسية (Trending)", "Home"))
            }

            val customSections = getCustomHomepages()

            customSections.filter { it.isEnabled }.forEach { section ->
                var title = section.name
                if (title.isBlank()) title = extractNameFromUrl(section.url)
                list.add(MainPageData(title, section.url))
            }

            return list
        }

    /**
     * Returns true if the trending row should be shown.
     *
     * Reads from BOTH:
     *   1. The "YouTube" SharedPreferences file (what YoutubeProvider was constructed with)
     *   2. The app-wide default SharedPreferences (where PreferenceFragmentCompat writes
     *      by default — i.e. where the switch value lands if the listener doesn't fire)
     *
     * If EITHER file explicitly contains "show_trending_home" = false, we treat the
     * toggle as OFF. This is the most robust interpretation: the user has expressed
     * intent to hide trending in at least one place, so we hide it.
     */
    private fun isTrendingEnabled(): Boolean {
        // Source 1: the "YouTube" file
        val youTubeValue = sharedPref?.getBoolean("show_trending_home", true) ?: true
        if (!youTubeValue) return false

        // Source 2: app-wide default SharedPreferences (only if context is available)
        try {
            val context = AcraApplication.context
            if (context != null) {
                val defaultPrefs = PreferenceManager.getDefaultSharedPreferences(context)
                // Only honour the default-prefs value if it was explicitly set
                // (contains() == true). Otherwise we'd treat "not set" as the
                // default true, which we already handled above.
                if (defaultPrefs.contains("show_trending_home")) {
                    return defaultPrefs.getBoolean("show_trending_home", true)
                }
            }
        } catch (_: Exception) { /* ignore — fall back to youTubeValue */ }

        return youTubeValue
    }
    private fun getCustomHomepages(): List<CustomSection> {
        val json = sharedPref?.getString("custom_homepages_v3", "[]") ?: "[]"
        return try {
            AppUtils.parseJson<List<CustomSection>>(json)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun extractNameFromUrl(url: String): String {
        return when {
            url.contains("/@") -> "@" + url.substringAfter("/@").substringBefore("/")
            url.contains("/c/") -> url.substringAfter("/c/").substringBefore("/")
            url.contains("/channel/") -> "Channel " + url.substringAfter("/channel/").take(5)
            url.contains("list=") -> "Playlist: " + url.substringAfter("list=").take(8)
            else -> "Custom Section"
        }
    }


    private val continuationTokens = mutableMapOf<String, String>()

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val results = mutableListOf<SearchResponse>()
        val seenIds = mutableSetOf<String>()
        var nextContinuation: String? = null

        val requestData = request.data
        val isPlaylist = if (page == 1) requestData.contains("list=") else requestData.startsWith("playlist_")



        fun extractPlaylistVideos(items: List<*>) {
            // Delegate to the new recursive collector. The old code only inspected
            // `playlistVideoRenderer`, which the new YouTube layout no longer emits
            // (it uses `lockupViewModel` with `LOCKUP_CONTENT_TYPE_VIDEO` instead).
            // The helper handles both layouts.
            val parsed = collectPlaylistVideoItems(items)
            parsed.forEach { renderer ->
                val vId = renderer.videoId ?: return@forEach
                if (seenIds.add(vId)) {
                    val vidUrl = "$mainUrl/watch?v=$vId"
                    val finalTitle = if (!renderer.duration.isNullOrBlank()) {
                        "{${renderer.duration}} ${renderer.title}"
                    } else renderer.title
                    results.add(
                        newMovieSearchResponse(finalTitle, vidUrl, TvType.Movie) {
                            this.posterUrl = renderer.thumbnail ?: buildThumbnailFromId(vId)
                        }
                    )
                }
            }
        }

        fun findPlaylistToken(items: List<*>?): String? {
            if (items == null) return null
            return findPlaylistContinuationToken(items)
        }



        try {
            if (page == 1) {
                continuationTokens.remove(requestData)
                if (requestData == "Home") homeShorts.clear()

                var cleanUrl = requestData
                if (cleanUrl.contains("?")) cleanUrl = cleanUrl.substringBefore("?")

                val targetUrl = when {
                    requestData.startsWith("http") && !isPlaylist && !cleanUrl.endsWith("/videos") -> "$cleanUrl/videos"
                    requestData.startsWith("http") -> requestData
                    else -> mainUrl
                }

                val html = app.get(targetUrl, interceptor = ytInterceptor).text

                // Use cached INNERTUBE config — only parses HTML once per session
                getInnerTubeConfig(html)

                val initialData = extractYtInitialData(html)
                if (initialData != null) {
                    if (isPlaylist) {

                        // The new YouTube layout no longer nests the playlist items
                        // at the hardcoded path `contents.twoColumnBrowseResultsRenderer
                        // .tabs[0].tabRenderer.content.sectionListRenderer.contents[0]
                        // .itemSectionRenderer.contents[0].playlistVideoListRenderer.contents`.
                        // Instead, items live under `itemSectionRenderer.contents[*]`
                        // as `lockupViewModel` (LOCKUP_CONTENT_TYPE_VIDEO). The recursive
                        // collector below handles BOTH layouts, so we feed it the whole
                        // initialData tree and let it find every video renderer.
                        extractPlaylistVideos(listOf(initialData))
                        nextContinuation = findPlaylistContinuationToken(initialData)

                        if (nextContinuation.isNullOrBlank()) {
                            val conts = findContinuationItemsRecursive(initialData)
                            nextContinuation = findPlaylistToken(conts)
                        }

                    } else {

                        processRecursive(initialData, results, seenIds, playlistMode = false)
                        nextContinuation = findTokenRecursive(initialData)
                    }
                }
            } else {



                val tokenToUse = continuationTokens[requestData]
                if (!tokenToUse.isNullOrBlank() && !savedApiKey.isNullOrBlank()) {
                    val decodedToken = java.net.URLDecoder.decode(tokenToUse, "UTF-8")

                    val apiUrl = "$mainUrl/youtubei/v1/browse?key=$savedApiKey"
                    val payload = mapOf(
                        "context" to mapOf("client" to mapOf(
                            "visitorData" to (savedVisitorData ?: ""), "clientName" to "WEB",
                            "clientVersion" to (savedClientVersion ?: "2.20240725.01.00"),
                            "platform" to "DESKTOP"
                        )),
                        "continuation" to decodedToken
                    )
                    val headers = mapOf(
                        "X-Youtube-Client-Name" to "WEB",
                        "X-Youtube-Client-Version" to (savedClientVersion ?: ""),
                        "Origin" to mainUrl, "Referer" to mainUrl
                    )

                    val response = app.post(apiUrl, json = payload, headers = headers, interceptor = ytInterceptor).parsedSafe<Map<String, Any>>()
                    if (response != null) {
                        if (isPlaylist) {

                            val continuationItems = findContinuationItemsRecursive(response)
                            if (continuationItems != null) {
                                extractPlaylistVideos(continuationItems)
                                nextContinuation = findPlaylistToken(continuationItems)
                            }
                        } else {

                            val actions = response["onResponseReceivedActions"] ?: response["onResponseReceivedCommands"] ?: response["continuationContents"] ?: response
                            processRecursive(actions, results, seenIds, playlistMode = false)
                            nextContinuation = findTokenRecursive(response)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logError(e)
        }

        if (!nextContinuation.isNullOrBlank()) {
            continuationTokens[requestData] = nextContinuation
        } else {
            continuationTokens.remove(requestData)
        }

        return newHomePageResponse(request, results, hasNext = !nextContinuation.isNullOrBlank())
    }

    fun findContinuationItemsRecursive(obj: Any?): List<*>? {
        when (obj) {
            is Map<*, *> -> {
                if (obj.containsKey("continuationItems")) return obj["continuationItems"] as? List<*>

                val keysToTry = listOf(
                    "onResponseReceivedActions",
                    "onResponseReceivedCommands",
                    "onResponseReceivedEndpoints",
                    "continuationContents"
                )
                for (k in keysToTry) {
                    val v = obj[k]
                    val r = findContinuationItemsRecursive(v)
                    if (r != null) return r
                }

                for (v in obj.values) {
                    val r = findContinuationItemsRecursive(v)
                    if (r != null) return r
                }
            }

            is List<*> -> {
                for (i in obj) {
                    val r = findContinuationItemsRecursive(i)
                    if (r != null) return r
                }
            }
        }
        return null
    }

    // =========================================================================================
    //  Playlist video extraction helpers
    //  ------------------------------------------------------------------
    //  YouTube has changed the playlist page layout multiple times. To stay
    //  resilient, we walk the JSON tree and pick up every "video-like" renderer
    //  we recognise:
    //
    //    1. NEW layout (2024+): itemSectionRenderer.contents[*].lockupViewModel
    //       where contentType == "LOCKUP_CONTENT_TYPE_VIDEO"
    //    2. MID layout: playlistVideoListRenderer.contents[*].playlistVideoRenderer
    //    3. LEGACY: richItemRenderer.content.videoRenderer / gridVideoRenderer /
    //       compactVideoRenderer (used on /videos tabs of channels and search)
    //
    //  Each renderer is normalised into a PlaylistVideoItem so the caller can
    //  build Episode objects without caring which layout YouTube served.
    // =========================================================================================

    private data class PlaylistVideoItem(
        val videoId: String?,
        val title: String,
        val thumbnail: String?,
        val duration: String?
    )

    /**
     * Walk [data] recursively and return every playlist-video renderer found,
     * normalised to [PlaylistVideoItem]. Order is preserved (encounter order),
     * so the result is safe to turn into a 1-indexed episode list.
     */
    private fun collectPlaylistVideoItems(data: Any?): List<PlaylistVideoItem> {
        val out = mutableListOf<PlaylistVideoItem>()
        val seenLocal = mutableSetOf<String>()
        walkForPlaylistVideos(data, out, seenLocal)
        return out
    }

    private fun walkForPlaylistVideos(
        node: Any?,
        out: MutableList<PlaylistVideoItem>,
        seen: MutableSet<String>
    ) {
        when (node) {
            is Map<*, *> -> {
                // --- NEW layout: lockupViewModel with contentType VIDEO -----------------
                val lockup = node.getMapKey("lockupViewModel")
                if (lockup != null) {
                    val ct = lockup.getString("contentType")
                    if (ct == "LOCKUP_CONTENT_TYPE_VIDEO") {
                        val parsed = parseLockupVideo(lockup)
                        if (parsed.videoId != null && seen.add(parsed.videoId)) {
                            out.add(parsed)
                        }
                        // Even after handling, keep recursing in case the lockup also
                        // wraps nested renderers (rare, but cheap to check).
                    }
                }

                // --- MID layout: playlistVideoRenderer ----------------------------------
                val pvr = node.getMapKey("playlistVideoRenderer")
                if (pvr != null) {
                    val vId = pvr.getString("videoId")
                    if (vId != null && seen.add(vId)) {
                        out.add(parsePlaylistVideoRenderer(pvr))
                    }
                }

                // --- LEGACY: videoRenderer / gridVideoRenderer / compactVideoRenderer ---
                val legacy = node.getMapKey("videoRenderer")
                    ?: node.getMapKey("gridVideoRenderer")
                    ?: node.getMapKey("compactVideoRenderer")
                if (legacy != null) {
                    val vId = legacy.getString("videoId")
                    if (vId != null && seen.add(vId)) {
                        out.add(parseLegacyVideoRenderer(legacy))
                    }
                }

                // Recurse into all values
                for (v in node.values) walkForPlaylistVideos(v, out, seen)
            }
            is List<*> -> {
                for (item in node) walkForPlaylistVideos(item, out, seen)
            }
        }
    }

    /** Extract video metadata from the new-style `lockupViewModel` (LOCKUP_CONTENT_TYPE_VIDEO). */
    private fun parseLockupVideo(lockup: Map<*, *>): PlaylistVideoItem {
        val contentId = lockup.getString("contentId")

        // The videoId is usually the contentId, but on some A/B variants it lives
        // under rendererContext.commandContext.onTap.innertubeCommand.watchEndpoint.videoId.
        val watchEndpointVideoId = lockup.getMapKey("rendererContext")
            ?.getMapKey("commandContext")
            ?.getMapKey("onTap")
            ?.getMapKey("innertubeCommand")
            ?.getMapKey("watchEndpoint")
            ?.getString("videoId")

        val videoId = contentId ?: watchEndpointVideoId

        // Title: metadata.lockupMetadataViewModel.title.content
        val title = lockup.getMapKey("metadata")
            ?.getMapKey("lockupMetadataViewModel")
            ?.getMapKey("title")
            ?.getString("content")
            ?: lockup.getMapKey("metadata")
                ?.getMapKey("lockupMetadataViewModel")
                ?.getMapKey("title")
                ?.let { getText(it) }
            ?: "Video"

        // Thumbnail: contentImage.thumbnailViewModel.image.sources[*].url (last = best)
        val thumb = lockup.getMapKey("contentImage")
            ?.getMapKey("thumbnailViewModel")
            ?.getMapKey("image")
            ?.getListKey("sources")
            ?.lastOrNull()
            ?.getString("url")
            ?: videoId?.let { buildThumbnailFromId(it) }

        // Duration: contentImage.thumbnailViewModel.overlays[*]
        //          .thumbnailBottomOverlayViewModel.badges[*]
        //          .thumbnailBadgeViewModel.text
        var duration: String? = null
        val overlays = lockup.getMapKey("contentImage")
            ?.getMapKey("thumbnailViewModel")
            ?.getListKey("overlays")
        overlays?.forEach { ov ->
            if (duration != null) return@forEach
            val badges = ov.getMapKey("thumbnailBottomOverlayViewModel")
                ?.getListKey("badges")
            badges?.forEach { b ->
                if (duration != null) return@forEach
                val text = b.getMapKey("thumbnailBadgeViewModel")?.getString("text")
                if (text != null && (text.contains(":") || text.matches(Regex("\\d+\\s*(second|minute|hour|sec|min|hr|ثانية|دقيقة|ساعة", RegexOption.IGNORE_CASE)))) {
                    duration = text
                }
            }
        }

        return PlaylistVideoItem(
            videoId = videoId,
            title = title,
            thumbnail = thumb,
            duration = duration
        )
    }

    /** Extract video metadata from the mid-style `playlistVideoRenderer` (older layout). */
    private fun parsePlaylistVideoRenderer(renderer: Map<*, *>): PlaylistVideoItem {
        val vId = renderer.getString("videoId")
        val title = extractTitle(renderer.getMapKey("title")) ?: "Video"
        val thumb = getBestThumbnail(renderer.getMapKey("thumbnail")) ?: vId?.let { buildThumbnailFromId(it) }
        val duration = extractTitle(renderer.getMapKey("lengthText"))
            ?: renderer.getMapKey("lengthText")?.getString("simpleText")
        return PlaylistVideoItem(vId, title, thumb, duration)
    }

    /** Extract video metadata from a legacy `videoRenderer` / `gridVideoRenderer` / `compactVideoRenderer`. */
    private fun parseLegacyVideoRenderer(renderer: Map<*, *>): PlaylistVideoItem {
        val vId = renderer.getString("videoId")
        val title = extractTitle(renderer.getMapKey("title"))
            ?: extractTitle(renderer.getMapKey("headline"))
            ?: "Video"
        val thumb = getBestThumbnail(renderer.getMapKey("thumbnail")) ?: vId?.let { buildThumbnailFromId(it) }
        val duration = extractTitle(renderer.getMapKey("lengthText"))
            ?: extractTitle(renderer.getMapKey("thumbnailOverlays"))
        return PlaylistVideoItem(vId, title, thumb, duration)
    }

    /**
     * Find the continuation token for paginated playlist videos.
     * Handles both the new `continuationItemViewModel.continuationCommand.innertubeCommand.continuationCommand.token`
     * path and the legacy `continuationItemRenderer.continuationEndpoint.continuationCommand.token` path.
     */
    private fun findPlaylistContinuationToken(data: Any?): String? {
        if (data == null) return null
        return when (data) {
            is Map<*, *> -> {
                // Direct hit: this map IS a continuationCommand with a token
                val directToken = data.getString("token")
                if (!directToken.isNullOrBlank()) return directToken

                // New path: continuationCommand.innertubeCommand.continuationCommand.token
                val outer = data.getMapKey("continuationCommand")
                if (outer != null) {
                    val inner = outer.getMapKey("innertubeCommand")
                        ?.getMapKey("continuationCommand")
                    val innerToken = inner?.getString("token")
                    if (!innerToken.isNullOrBlank()) return innerToken
                    // Or: outer itself might have the token
                    val outerToken = outer.getString("token")
                    if (!outerToken.isNullOrBlank()) return outerToken
                }

                // Legacy path: continuationEndpoint.continuationCommand.token
                val ep = data.getMapKey("continuationEndpoint")?.getMapKey("continuationCommand")
                val epToken = ep?.getString("token")
                if (!epToken.isNullOrBlank()) return epToken

                // Recurse into children
                for (v in data.values) {
                    val t = findPlaylistContinuationToken(v)
                    if (!t.isNullOrBlank()) return t
                }
                null
            }
            is List<*> -> {
                for (i in data) {
                    val t = findPlaylistContinuationToken(i)
                    if (!t.isNullOrBlank()) return t
                }
                null
            }
            else -> null
        }
    }

    /**
     * Generic recursive search: returns the first Map<*,*> in the tree that
     * contains the given key. Used to find `playlistMetadataRenderer`,
     * `playlistHeaderRenderer`, etc. without hardcoding their exact paths.
     */
    private fun findFirstKeyRecursive(data: Any?, key: String): Map<*, *>? {
        when (data) {
            is Map<*, *> -> {
                if (data.containsKey(key)) {
                    val v = data[key]
                    if (v is Map<*, *>) return v
                }
                for (v in data.values) {
                    val r = findFirstKeyRecursive(v, key)
                    if (r != null) return r
                }
            }
            is List<*> -> {
                for (i in data) {
                    val r = findFirstKeyRecursive(i, key)
                    if (r != null) return r
                }
            }
        }
        return null
    }



    override suspend fun search(query: String): List<SearchResponse> {
        return search(query, 1)?.items ?: emptyList()
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val results = mutableListOf<SearchResponse>()

        isSearchContext = true
        if (page == 1) {
            searchShorts.clear()
        }


        val seenIds = mutableSetOf<String>()

        var actualQuery = query
        var playlistMode = false
        var spParam = ""

        val playlistTag = sharedPref?.getString("playlist_search_tag", "{p}") ?: "{p}"
        if (query.contains(playlistTag)) {
            actualQuery = query.replace(playlistTag, "").trim()
            playlistMode = true
            spParam = "&sp=EgIQAw%3D%3D"
        }

        try {
            if (page == 1) {
                savedContinuationToken = null
                val encoded = URLEncoder.encode(actualQuery, "utf-8")
                val url = "$mainUrl/results?search_query=$encoded$spParam"
                val html = app.get(url, interceptor = ytInterceptor).text

                val regexKey = Regex(""""INNERTUBE_API_KEY":"([^"]+)"""")
                savedApiKey = regexKey.find(html)?.groupValues?.get(1)
                savedVisitorData = findConfig(html, "VISITOR_DATA")

                val initialData = extractYtInitialData(html)
                if (initialData != null) {
                    processRecursive(initialData, results, seenIds, playlistMode)
                    savedContinuationToken = findTokenRecursive(initialData)
                }
            } else {
                if (!savedContinuationToken.isNullOrBlank() && !savedApiKey.isNullOrBlank()) {
                    val apiUrl = "$mainUrl/youtubei/v1/search?key=$savedApiKey"
                    val payload = mapOf(
                        "context" to mapOf(
                            "client" to mapOf(
                                "clientName" to "WEB",
                                "clientVersion" to "2.20240101.00",
                                "visitorData" to (savedVisitorData ?: "")
                            )
                        ),
                        "continuation" to savedContinuationToken
                    )

                    val response = app.post(apiUrl, json = payload, interceptor = ytInterceptor)
                        .parsedSafe<Map<String, Any>>()
                    if (response != null) {
                        val actions = response["onResponseReceivedCommands"] ?: response
                        processRecursive(actions, results, seenIds, playlistMode)
                        savedContinuationToken = findTokenRecursive(response)
                    }
                }
            }
            return newSearchResponseList(results, !savedContinuationToken.isNullOrBlank())
        } catch (e: Exception) {
            return newSearchResponseList(emptyList(), false)
        }
    }


    private fun safeGet(data: Any?, vararg keys: Any): Any? {
        var current = data
        for (key in keys) {
            current = when {
                current is Map<*, *> && key is String -> current[key]
                current is List<*> && key is Int -> current.getOrNull(key)
                else -> return null
            }
        }
        return current
    }

    private fun extractTitle(titleObject: Map<*, *>?): String? {
        if (titleObject == null) return null
        return titleObject.getString("simpleText")
            ?: titleObject.getListKey("runs")?.joinToString("") { it.getString("text") ?: "" }
            ?: titleObject.getString("text")
    }



    override suspend fun load(url: String): LoadResponse {

        if (url.contains("/shorts/")) {
            val videoId = url.extractYoutubeId() ?: "video"
            val useSearchList = url.contains("&ctx=search")
            val sourceList = if (useSearchList) searchShorts else homeShorts
            val targetEpisodes = sourceList.toMutableList()
            var currentEp = targetEpisodes.find { it.data.extractYoutubeId() == videoId }

            if (currentEp == null) {
                val fallbackEp = newEpisode(url) {
                    this.name = "Shorts Video"
                    this.posterUrl = buildThumbnailFromId(videoId)
                    this.episode = targetEpisodes.size + 1
                }
                targetEpisodes.add(0, fallbackEp)
                currentEp = fallbackEp
            }

            val poster = currentEp?.posterUrl ?: buildThumbnailFromId(videoId)

            return newTvSeriesLoadResponse("Shorts Feed", url, TvType.TvSeries, targetEpisodes) {
                this.posterUrl = poster
                this.plot = "قائمة تشغيل تلقائية من الشورتس (${targetEpisodes.size} فيديو)"
                this.tags = listOf("Shorts", "Feed")
            }
        }



        if (url.contains("/@") || url.contains("/channel/") || url.contains("/c/") || url.contains("/user/")) {
            try {
                val channelUrl = if (url.endsWith("/videos")) url else "$url/videos"
                val response = app.get(channelUrl, interceptor = ytInterceptor)
                val html = response.text
                val data = extractYtInitialData(html)
                    ?: throw ErrorLoadingException("Failed to extract channel data")

                val apiKey = findConfig(html, "INNERTUBE_API_KEY")
                val clientVersion = findConfig(html, "INNERTUBE_CLIENT_VERSION") ?: "2.20240725.01.00"
                val visitorData = findConfig(html, "VISITOR_DATA")

                val header = safeGet(data, "header", "c4TabbedHeaderRenderer")
                    ?: safeGet(data, "header", "pageHeaderRenderer")

                val title = extractTitle(safeGet(header, "title") as? Map<*, *>)
                    ?: extractTitle(safeGet(header, "pageTitle") as? Map<*, *>)
                    ?: response.document.selectFirst("meta[property=og:title]")?.attr("content")
                    ?: "YouTube Channel"

                val poster = getBestThumbnail(safeGet(header, "avatar"))
                    ?: getBestThumbnail(safeGet(header, "content", "pageHeaderViewModel", "image", "decoratedAvatarViewModel", "avatar", "avatarViewModel", "image"))
                    ?: response.document.selectFirst("meta[property=og:image]")?.attr("content")

                val subscriberCount = extractTitle(safeGet(header, "subscriberCountText") as? Map<*, *>)
                    ?: safeGet(header, "metadata", "pageHeaderViewModel", "metadata", "contentMetadataViewModel", "metadataRows", 1, "metadataParts", 0, "text", "content") as? String

                val allEpisodes = mutableListOf<Episode>()

                fun findContinuationItemsRecursive(obj: Any?): List<*>? {
                    when (obj) {
                        is Map<*, *> -> {
                            if (obj.containsKey("continuationItems")) return obj["continuationItems"] as? List<*>
                            val keysToTry = listOf("onResponseReceivedActions", "onResponseReceivedCommands", "onResponseReceivedEndpoints", "continuationContents", "onResponseReceivedResults")
                            for (k in keysToTry) {
                                val v = obj[k]
                                val r = findContinuationItemsRecursive(v)
                                if (r != null) return r
                            }
                            for (v in obj.values) {
                                val r = findContinuationItemsRecursive(v)
                                if (r != null) return r
                            }
                        }
                        is List<*> -> {
                            for (i in obj) {
                                val r = findContinuationItemsRecursive(i)
                                if (r != null) return r
                            }
                        }
                    }
                    return null
                }

                fun findContinuationTokenFromItems(items: List<*>?): String? {
                    if (items == null) return null
                    for (it in items) {
                        val m = it as? Map<*, *> ?: continue
                        val token = safeGet(m, "continuationItemRenderer", "continuationEndpoint", "continuationCommand", "token") as? String
                        if (!token.isNullOrBlank()) return token
                        val token2 = safeGet(m, "continuationItemRenderer", "continuationEndpoint", "browseContinuationEndpoint", "token") as? String
                        if (!token2.isNullOrBlank()) return token2
                        val token3 = safeGet(m, "continuationItemRenderer", "continuationEndpoint", "token") as? String
                        if (!token3.isNullOrBlank()) return token3
                    }
                    return null
                }

                fun extractVideosFromItems(items: List<*>, collectTo: MutableList<Episode>) {
                    items.forEach { item ->
                        val map = item as? Map<*, *> ?: return@forEach
                        val videoRenderer = when {
                            map.containsKey("videoRenderer") -> map["videoRenderer"] as? Map<*, *>
                            map.containsKey("gridVideoRenderer") -> map["gridVideoRenderer"] as? Map<*, *>
                            map.containsKey("compactVideoRenderer") -> map["compactVideoRenderer"] as? Map<*, *>
                            map.containsKey("shortsVideoRenderer") -> map["shortsVideoRenderer"] as? Map<*, *>
                            map.containsKey("reelItemRenderer") -> {
                                val content = safeGet(map, "reelItemRenderer", "content") as? Map<*, *>
                                content?.get("reelItemRenderer") as? Map<*, *>
                            }
                            map.containsKey("richItemRenderer") -> {
                                val content = safeGet(map, "richItemRenderer", "content") as? Map<*, *>
                                (content?.get("videoRenderer") ?: content?.get("gridVideoRenderer") ?: content?.get("shortsLockupViewModel")) as? Map<*, *>
                            }
                            else -> null
                        }

                        if (videoRenderer != null) {
                            val vId = videoRenderer["videoId"] as? String ?: return@forEach
                            val vidTitle = extractTitle(videoRenderer["title"] as? Map<*, *>)
                                ?: extractTitle(videoRenderer["headline"] as? Map<*, *>)
                                ?: extractTitle(videoRenderer["shortBylineText"] as? Map<*, *>)
                                ?: "Video"
                            val thumb = getBestThumbnail(videoRenderer["thumbnail"]) ?: buildThumbnailFromId(vId)
                            val vidUrl = "$mainUrl/watch?v=$vId"
                            val viewCount = formatViews(safeGet(videoRenderer, "viewCountText", "simpleText") as? String)
                            val publishedTime = extractTitle(safeGet(videoRenderer, "publishedTimeText") as? Map<*, *>)

                            collectTo.add(newEpisode(vidUrl) {
                                this.name = vidTitle
                                this.posterUrl = thumb
                                this.description = listOfNotNull(viewCount, publishedTime).joinToString(" • ")
                            })
                        }
                    }
                }

                var initialItems: List<*>? = null
                val tabs = safeGet(data, "contents", "twoColumnBrowseResultsRenderer", "tabs") as? List<*>
                if (tabs != null) {
                    for (tab in tabs) {
                        val tabMap = tab as? Map<*, *>
                        val tabRenderer = tabMap?.get("tabRenderer") as? Map<*, *>
                        val content = tabRenderer?.get("content") as? Map<*, *>
                        if (content?.containsKey("richGridRenderer") == true) {
                            initialItems = safeGet(content, "richGridRenderer", "contents") as? List<*>
                            break
                        }
                        if (content?.containsKey("gridRenderer") == true) {
                            initialItems = safeGet(content, "gridRenderer", "items") as? List<*>
                            break
                        }
                    }
                }
                if (initialItems != null) {
                    extractVideosFromItems(initialItems, allEpisodes)
                }

                var currentToken: String? = findContinuationTokenFromItems(initialItems)
                if (currentToken.isNullOrBlank()) {
                    val conts = findContinuationItemsRecursive(data)
                    currentToken = findContinuationTokenFromItems(conts)
                }

                var pagesFetchedLocal = 1
                val maxPages = sharedPref?.getInt("channel_pages_limit", 6) ?: 6

                while (!currentToken.isNullOrBlank() && pagesFetchedLocal < maxPages && !apiKey.isNullOrBlank()) {
                    try {
                        pagesFetchedLocal += 1
                        val apiUrl = "https://www.youtube.com/youtubei/v1/browse?key=$apiKey"
                        val payload = mapOf(
                            "context" to mapOf(
                                "client" to mapOf(
                                    "clientName" to "WEB",
                                    "clientVersion" to clientVersion,
                                    "visitorData" to (visitorData ?: ""),
                                    "platform" to "DESKTOP"
                                )
                            ),
                            "continuation" to currentToken
                        )
                        val headers = mapOf("X-Youtube-Client-Name" to "WEB", "X-Youtube-Client-Version" to clientVersion)
                        val jsonResponse = app.post(apiUrl, json = payload, headers = headers, interceptor = ytInterceptor).parsedSafe<Map<String, Any>>() ?: break
                        val continuationItems = findContinuationItemsRecursive(jsonResponse) ?: break
                        extractVideosFromItems(continuationItems, allEpisodes)
                        currentToken = findContinuationTokenFromItems(continuationItems)
                        kotlinx.coroutines.delay((SLEEP_BETWEEN * 10).toLong())
                    } catch (e: Exception) {
                        break
                    }
                }

                return newTvSeriesLoadResponse(title, url, TvType.TvSeries, allEpisodes) {
                    this.posterUrl = poster
                    this.plot = "Channel: $title\nSubscribers: ${subscriberCount ?: "N/A"}\nVideos Fetched: ${allEpisodes.size}"
                    this.tags = listOf(title, "Channel")
                }

            } catch (e: Exception) {

            }
        }



        if (url.contains("list=")) {
            try {
                val response = app.get(url, interceptor = ytInterceptor)
                val html = response.text
                val data = extractYtInitialData(html)
                    ?: throw ErrorLoadingException("Failed to extract playlist data (ytInitialData regex did not match). URL=$url")

                // --- Title / author / description -----------------------------------------
                // YouTube has TWO playlist header layouts:
                //   OLD: header.playlistHeaderRenderer.{title, ownerText, description}
                //   NEW: header.pageHeaderRenderer.pageTitle + metadata.playlistMetadataRenderer.title
                // We try every known path recursively and fall back gracefully.
                val playlistMetadataRenderer = findFirstKeyRecursive(data, "playlistMetadataRenderer")
                val playlistHeaderRenderer   = findFirstKeyRecursive(data, "playlistHeaderRenderer")

                val title: String = (
                    playlistMetadataRenderer?.let { extractTitle(it["title"] as? Map<*, *>) }
                    ?: playlistHeaderRenderer?.let { extractTitle(it["title"] as? Map<*, *>) }
                    ?: safeGet(data, "header", "pageHeaderRenderer", "pageTitle") as? String
                    ?: response.document.selectFirst("meta[property=og:title]")?.attr("content")
                ) ?: "YouTube Playlist"

                val author: String = (
                    playlistHeaderRenderer?.let { extractTitle(it["ownerText"] as? Map<*, *>) }
                    ?: response.document.selectFirst("meta[property=og:site_name]")?.attr("content")
                ) ?: "Unknown Channel"

                val description: String? = playlistHeaderRenderer?.let {
                    extractTitle(it["description"] as? Map<*, *>)
                } ?: response.document.selectFirst("meta[property=og:description]")?.attr("content")

                // --- API keys for continuation requests -----------------------------------
                val apiKey = findConfig(html, "INNERTUBE_API_KEY")
                val clientVersion = findConfig(html, "INNERTUBE_CLIENT_VERSION") ?: "2.20240725.01.00"
                val visitorData = findConfig(html, "VISITOR_DATA")

                // --- Collect the first page of playlist videos ----------------------------
                val episodes = mutableListOf<Episode>()
                val seenIds = mutableSetOf<String>()

                val firstPageItems = collectPlaylistVideoItems(data)
                firstPageItems.forEach { renderer ->
                    val vId = renderer.videoId ?: return@forEach
                    if (!seenIds.add(vId)) return@forEach
                    val vidUrl = "$mainUrl/watch?v=$vId"
                    episodes.add(newEpisode(vidUrl) {
                        this.name = renderer.title.ifBlank { "Episode ${episodes.size + 1}" }
                        this.episode = episodes.size + 1
                        this.posterUrl = renderer.thumbnail ?: buildThumbnailFromId(vId)
                        this.description = renderer.duration?.takeIf { it.isNotBlank() }
                            ?.let { "Duration: $it" }
                    })
                }

                // --- Continuation: paginate the rest of the playlist (>100 videos) -------
                var currentToken: String? = findPlaylistContinuationToken(data)
                val maxPages = sharedPref?.getInt("channel_pages_limit", 6) ?: 6
                var pagesFetched = 1

                while (!currentToken.isNullOrBlank() && pagesFetched < maxPages && !apiKey.isNullOrBlank()) {
                    try {
                        pagesFetched += 1
                        val apiUrl = "https://www.youtube.com/youtubei/v1/browse?key=$apiKey"
                        val payload = mapOf(
                            "context" to mapOf(
                                "client" to mapOf(
                                    "clientName" to "WEB",
                                    "clientVersion" to clientVersion,
                                    "visitorData" to (visitorData ?: ""),
                                    "platform" to "DESKTOP"
                                )
                            ),
                            "continuation" to currentToken
                        )
                        val headers = mapOf(
                            "X-Youtube-Client-Name" to "WEB",
                            "X-Youtube-Client-Version" to clientVersion
                        )
                        val jsonResponse = app.post(
                            apiUrl, json = payload, headers = headers, interceptor = ytInterceptor
                        ).parsedSafe<Map<String, Any>>() ?: break

                        val contItems = collectPlaylistVideoItems(jsonResponse)
                        contItems.forEach { renderer ->
                            val vId = renderer.videoId ?: return@forEach
                            if (!seenIds.add(vId)) return@forEach
                            val vidUrl = "$mainUrl/watch?v=$vId"
                            episodes.add(newEpisode(vidUrl) {
                                this.name = renderer.title.ifBlank { "Episode ${episodes.size + 1}" }
                                this.episode = episodes.size + 1
                                this.posterUrl = renderer.thumbnail ?: buildThumbnailFromId(vId)
                                this.description = renderer.duration?.takeIf { it.isNotBlank() }
                                    ?.let { "Duration: $it" }
                            })
                        }

                        currentToken = findPlaylistContinuationToken(jsonResponse)
                        kotlinx.coroutines.delay((SLEEP_BETWEEN * 10).toLong())
                    } catch (e: Exception) {
                        logError(e)
                        break
                    }
                }

                val playlistPoster = episodes.firstOrNull()?.posterUrl
                    ?: response.document.selectFirst("meta[property=og:image]")?.attr("content")

                return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = playlistPoster
                    val finalDescription = if (description.isNullOrBlank()) "Channel: $author" else "Channel: $author\n\n$description"
                    this.plot = finalDescription
                    this.tags = listOf(author)
                }
            } catch (e: Exception) {
                // LOG the error instead of silently swallowing it. The previous silent
                // catch was the reason "Coming soon" appeared: when parsing failed,
                // execution fell through to the video branch, which then threw
                // "Invalid YouTube URL" because a `playlist?list=...` URL has no `v=`.
                logError(e)
            }
        }



        val videoId = url.extractYoutubeId() ?: throw ErrorLoadingException("Invalid YouTube URL")

        val response = app.get(url, interceptor = ytInterceptor)
        val html = response.text
        val data = extractYtInitialData(html)

        var title = "YouTube Video"
        var plot = ""
        var poster = buildThumbnailFromId(videoId)

        var channelName = ""
        var channelId = ""
        var channelAvatar = ""

        val recommendations = mutableListOf<SearchResponse>()
        val seenRecIds = mutableSetOf<String>()

        if (data != null) {

            val resultsContents = safeGet(data, "contents", "twoColumnWatchNextResults", "results", "results", "contents") as? List<*>

            resultsContents?.forEach { item ->
                val m = item as? Map<*, *>

                val primary = m?.get("videoPrimaryInfoRenderer") as? Map<*, *>
                if (primary != null) {
                    val t = extractTitle(primary["title"] as? Map<*, *>)
                    if (!t.isNullOrBlank()) title = t

                    val dateText = extractTitle(primary["dateText"] as? Map<*, *>)
                    if (!dateText.isNullOrBlank()) plot += "$dateText\n\n"
                }

                val secondary = m?.get("videoSecondaryInfoRenderer") as? Map<*, *>
                if (secondary != null) {

                    val owner = safeGet(secondary, "owner", "videoOwnerRenderer") as? Map<*, *>
                    if (owner != null) {
                        channelName = extractTitle(owner["title"] as? Map<*, *>) ?: ""
                        channelAvatar = getBestThumbnail(owner["thumbnail"]) ?: ""
                        channelId = safeGet(owner, "navigationEndpoint", "browseEndpoint", "browseId") as? String ?: ""
                        if (channelId.isEmpty()) {

                            val curl = safeGet(owner, "navigationEndpoint", "commandMetadata", "webCommandMetadata", "url") as? String
                            if (!curl.isNullOrBlank()) channelId = curl.substringAfterLast("/")
                        }
                    }

                    val descObj = secondary["attributedDescription"] as? Map<*, *>
                        ?: secondary["description"] as? Map<*, *>

                    val fullDesc = getText(descObj)// استخدام دالة getText الموحدة
                    if (fullDesc.isNotBlank()) {
                        plot += fullDesc
                    }
                }
            }

            val secondaryResults = safeGet(data, "contents", "twoColumnWatchNextResults", "secondaryResults", "secondaryResults", "results")
            if (secondaryResults != null) {
                processRecursive(secondaryResults, recommendations, seenRecIds, false)
            }

        } else {

            val doc = response.document
            title = doc.selectFirst("meta[property=og:title]")?.attr("content") ?: title
            poster = doc.selectFirst("meta[property=og:image]")?.attr("content") ?: poster
            plot = doc.selectFirst("meta[property=og:description]")?.attr("content") ?: plot
        }


        if (channelName.isNotBlank() && channelId.isNotBlank()) {
            val channelUrlFull = if (channelId.startsWith("UC") || channelId.startsWith("@")) "$mainUrl/channel/$channelId" else "$mainUrl/$channelId"

            val channelCard = newMovieSearchResponse(
                "Channel: $channelName",
                channelUrlFull,
                TvType.Live
            ) {
                this.posterUrl = channelAvatar


            }

            recommendations.add(0, channelCard)
        }

        val filteredRecs = recommendations.filter { !it.url.contains("/shorts/") }

        return newMovieLoadResponse(title, url, TvType.Movie, videoId) {
            this.posterUrl = poster
            this.plot = plot

            if (channelName.isNotBlank()) {
                this.tags = listOf(channelName)
            }

            this.recommendations = filteredRecs
        }
    }





    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val videoId = data.extractYoutubeId() ?: data
        val fullUrl = "https://www.youtube.com/watch?v=$videoId"

        // === SmartTube default player (YouTube plugin only) ===
        // If SmartTube is installed, launch it directly and cancel link loading.
        // This makes one-click play open SmartTube instantly — no source picker,
        // no internal player, no buffering issues.
        // The CancellationException stops the link loading silently (no toast).
        // This only affects the YouTube plugin — other plugins are unaffected.
        val context = AcraApplication.context
        if (context != null) {
            val smartTubePackage = getSmartTubePackage(context)
            if (smartTubePackage != null) {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(fullUrl)).apply {
                        setPackage(smartTubePackage)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    // Cancel the coroutine — this stops link loading silently.
                    // ResultViewModel2 catches CancellationException and does nothing.
                    throw kotlinx.coroutines.CancellationException("Launched SmartTube")
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e  // re-throw to propagate cancellation
                } catch (e: Exception) {
                    logError(e)
                    // If launching SmartTube failed, fall through to internal player
                }
            }
        }

        // === Fallback: internal player (if SmartTube not installed or launch failed) ===
        val playerType = if (context != null) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            prefs.getString("youtube_player_type", "advanced")
        } else {
            "advanced"
        }

        if (playerType == "classic") {

            loadExtractor(fullUrl, subtitleCallback, callback)
        } else {

            com.youtube.YoutubeExtractor().getUrl(fullUrl, null, subtitleCallback, callback)
        }

        // Subtitles are already extracted by YoutubeExtractor.extractSubtitles()
        // (called inside getUrl above). No second /youtubei/v1/player call needed.
        // The old code made a duplicate API call here just for captions and
        // generated ~158 fake auto-translation URLs (most of which 404).
        // YoutubeExtractor now handles subtitles correctly using the API's
        // translationLanguages field.

        return true
    }










    private fun String.extractYoutubeId(): String? {
        val regex = Regex("""(?:v=|\/videos\/|embed\/|youtu\.be\/|shorts\/)([A-Za-z0-9_-]{11})""")
        return regex.find(this)?.groupValues?.getOrNull(1)
    }

    /**
     * Check if SmartTube is installed and return its package name.
     * Supports three flavors: stable, beta, fdroid.
     * Returns null if none are installed.
     */
    private fun getSmartTubePackage(context: android.content.Context): String? {
        val packages = listOf(
            "org.smarttube.stable",
            "org.smarttube.beta",
            "app.smarttube.fdroid"
        )
        val pm = context.packageManager
        for (pkg in packages) {
            try {
                pm.getPackageInfo(pkg, 0)
                return pkg
            } catch (e: PackageManager.NameNotFoundException) {
                // not installed, try next
            }
        }
        return null
    }
}












