package com.youtube

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.schemaStripRegex
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * YouTube extractor for CloudStream.
 *
 * === Why this exists (and why NewPipeExtractor is no longer used) ===
 *
 * Since mid-2025 YouTube deploys SABR ("server-side adaptive bitrate") enforcement
 * on the WEB client. The WEB client's `streamingData.adaptiveFormats` now returns
 * only 360p (or empty) for most videos. NewPipeExtractor v0.26.x uses the WEB
 * client internally, so `StreamInfo.videoOnlyStreams` is SABR-capped to 360p.
 * The SABR workaround that NewPipe app 0.27.7+ uses was never tagged on JitPack,
 * so we cannot fix this by bumping the NewPipeExtractor dependency.
 *
 * The industry-standard workaround (used by yt-dlp, Piped, InnerTube clients) is
 * to call `/youtubei/v1/player` with the **ANDROID** client. The ANDROID client:
 *   - bypasses SABR (returns adaptive formats up to 1080p/1440p/2160p)
 *   - returns URLs that are NOT signature-ciphered (directly playable)
 *   - does not require an API key
 *
 * This extractor:
 *   1. POSTs to https://www.youtube.com/youtubei/v1/player with the ANDROID client
 *   2. Parses streamingData.adaptiveFormats (video-only + audio)
 *   3. Builds a local DASH MPD that muxes one video + one audio per language
 *   4. Serves the MPD via a tiny HTTP server on 127.0.0.1
 *   5. Falls back to IOS client, then WEB client (muxed 360p) if ANDROID fails
 */
open class YoutubeExtractor : ExtractorApi() {
    override val mainUrl = "https://www.youtube.com"
    override val requiresReferer = false
    override val name = "YouTube"

    companion object {
        // NOTE: Removed ytVideos/ytVideosSubtitles caches — they were broken
        // (always removed before check → 0% hit rate) and leaked memory
        // (never cleared old entries). Each getUrl call now builds links fresh.

        private var activeServer: ServerSocket? = null
        private var serverPort: Int = 0
        private val manifestMap = ConcurrentHashMap<String, String>()
        private const val MAX_MANIFESTS = 32  // lowered from 64 — only current video needed
    }

    override fun getExtractorUrl(id: String): String = "$mainUrl/watch?v=$id"

    /** InnerTube client types we know how to talk to. */
    private enum class ClientType(val clientName: String, val clientVersion: String, val userAgent: String) {
        ANDROID(
            "ANDROID",
            "20.10.38",
            "com.google.android.youtube/20.10.38 (Linux; U; Android 11) gzip"
        ),
        IOS(
            "IOS",
            "20.10.4",
            "com.google.ios.youtube/20.10.4 (iPhone15,4; U; CPU iOS 17_4 like Mac OS X)"
        ),
        WEB(
            "WEB",
            "2.20240725.01.00",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        )
    }

    /** Local view of a video-only stream from the InnerTube player response. */
    data class VideoStreamInfo(
        val url: String,
        val mimeType: String,
        val codecs: String,
        val width: Int,
        val height: Int,
        val bitrate: Int,
        val label: String,
        val initRange: String?,
        val indexRange: String?
    )

    /** Local view of an audio stream from the InnerTube player response. */
    data class AudioInfo(
        val url: String,
        val mimeType: String,
        val codecs: String,
        val bitrate: Int,
        val initRange: String?,
        val indexRange: String?,
        val language: String
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val cleanedUrl = url.replace(schemaStripRegex, "")
        val videoId = extractVideoId(url) ?: run {
            try { cleanedUrl.substringAfter("v=").substringBefore("&") } catch (e: Exception) { cleanedUrl }
        }
        if (videoId.length != 11) {
            // Not a valid video id; nothing we can do.
            return
        }

        val builtLinks = mutableListOf<ExtractorLink>()
        var subtitles: List<Pair<String, String>> = emptyList()

        // === Try each client in order until one yields streams ===
        // ANDROID bypasses SABR and returns unciphered URLs (best).
        // IOS is a backup (also bypasses SABR but sometimes rate-limited).
        // WEB returns SABR-capped 360p muxed as a last resort.
        for (client in listOf(ClientType.ANDROID, ClientType.IOS, ClientType.WEB)) {
            try {
                val json = fetchPlayerResponse(videoId, client) ?: continue
                subtitles = extractSubtitles(json)

                val streamingData = json.optJSONObject("streamingData") ?: continue
                val playability = json.optJSONObject("playabilityStatus")
                val status = playability?.optString("status") ?: "OK"
                if (status != "OK" && status != "LIVE_STREAM_OFFLINE") continue

                val videoDetails = json.optJSONObject("videoDetails")
                val isLive = videoDetails?.optString("isLive") == "true" ||
                        (videoDetails?.optString("isLiveContent") == "true" && status == "OK" && videoDetails.optString("lengthSeconds") == "0")

                // FIX: read the real video duration so the MPD's mediaPresentationDuration
                // matches the actual content. A wrong duration makes ExoPlayer compute
                // seek targets outside the byte range of the file -> 404 on seek.
                val durationSeconds = videoDetails?.optString("lengthSeconds")?.toLongOrNull() ?: 0L

                if (isLive || streamingData.optString("hlsManifestUrl").isNotEmpty()) {
                    // === Live stream: emit HLS directly ===
                    val hls = streamingData.optString("hlsManifestUrl").takeIf { it.isNotBlank() }
                    if (!hls.isNullOrEmpty()) {
                        builtLinks.add(
                            newExtractorLink(this.name, "YouTube Live", hls, type = ExtractorLinkType.M3U8) {
                                this.referer = mainUrl
                                this.quality = 0
                            }
                        )
                        break // live streams only need the HLS link
                    }
                }

                // === Adaptive (DASH) streams ===
                val adaptive = streamingData.optJSONArray("adaptiveFormats")
                if (adaptive != null && adaptive.length() > 0) {
                    val (videos, audios) = parseAdaptiveFormats(adaptive)
                    if (videos.isNotEmpty()) {
                        builtLinks.addAll(buildAdaptiveLinks(videos, audios, durationSeconds))
                    }
                }

                // === Muxed/progressive streams (always present, capped at 360p/720p) ===
                val muxed = streamingData.optJSONArray("formats")
                if (muxed != null && muxed.length() > 0) {
                    builtLinks.addAll(buildMuxedLinks(muxed))
                }

                if (builtLinks.isNotEmpty()) break
            } catch (e: Exception) {
                logError(e)
                // try next client
            }
        }

        builtLinks.forEach { callback(it) }
        subtitles.forEach { (lang, content) ->
            try { subtitleCallback(newSubtitleFile(lang, content)) } catch (e: Exception) {}
        }
    }

    /** Extract the 11-char video id from any YouTube URL shape. */
    private fun extractVideoId(url: String): String? {
        val patterns = listOf(
            Regex("""(?:v=|/watch\?v=|youtu\.be/|/embed/|/shorts/|/live/)([A-Za-z0-9_-]{11})"""),
            Regex("""\b([A-Za-z0-9_-]{11})\b""")
        )
        for (p in patterns) {
            val m = p.find(url) ?: continue
            return m.groupValues.getOrNull(1)
        }
        return null
    }

    /** Call the InnerTube /youtubei/v1/player endpoint with a given client context. */
    private suspend fun fetchPlayerResponse(videoId: String, client: ClientType): JSONObject? {
        return try {
            val payload = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", client.clientName)
                        put("clientVersion", client.clientVersion)
                        put("hl", "en")
                        put("gl", "US")
                        if (client == ClientType.ANDROID) {
                            put("androidSdkVersion", 30)
                        }
                    })
                })
                put("videoId", videoId)
                put("contentCheckOk", true)
                put("racyCheckOk", true)
            }

            val apiUrl = "$mainUrl/youtubei/v1/player?prettyPrint=false"

            val headers = mapOf(
                "Content-Type" to "application/json",
                "User-Agent" to client.userAgent,
                "X-YouTube-Client-Name" to if (client == ClientType.ANDROID) "3"
                    else if (client == ClientType.IOS) "5" else "1",
                "X-YouTube-Client-Version" to client.clientVersion,
                "Origin" to mainUrl,
                "Referer" to "$mainUrl/watch?v=$videoId"
            )

            val response = app.post(apiUrl, json = payload, headers = headers)
            val text = response.text
            if (text.isBlank()) return null
            JSONObject(text)
        } catch (e: Exception) {
            logError(e)
            null
        }
    }

    /** Parse streamingData.adaptiveFormats into video-only + audio lists. */
    private fun parseAdaptiveFormats(
        adaptive: org.json.JSONArray
    ): Pair<List<VideoStreamInfo>, List<AudioInfo>> {
        val videos = mutableListOf<VideoStreamInfo>()
        val audios = mutableListOf<AudioInfo>()
        val seenVideoUrls = mutableSetOf<String>()
        val seenAudioKeys = mutableSetOf<String>()

        for (i in 0 until adaptive.length()) {
            val item = adaptive.optJSONObject(i) ?: continue
            try {
                val url = item.optString("url").takeIf { it.isNotBlank() } ?: continue
                val mime = item.optString("mimeType") // e.g. "video/mp4; codecs=\"avc1.640028\""
                val bitrate = item.optInt("bitrate", 0)
                val initRange = item.optJSONObject("initRange")?.let { r ->
                    "${r.optString("start")}-${r.optString("end")}".takeIf { r.has("start") && r.has("end") }
                }
                val indexRange = item.optJSONObject("indexRange")?.let { r ->
                    "${r.optString("start")}-${r.optString("end")}".takeIf { r.has("start") && r.has("end") }
                }

                // Split "video/mp4; codecs=\"avc1.640028\"" into mimeType + codecs.
                val (mimeOnly, codecs) = parseMimeAndCodecs(mime)

                if (mimeOnly.startsWith("video/")) {
                    val height = item.optInt("height", 0)
                    val width = item.optInt("width", 0)
                    if (height <= 0) continue
                    // FIX: skip video streams without init/index range — without them
                    // ExoPlayer cannot build a seek table and seeking fails with 404.
                    if (initRange == null || indexRange == null) continue
                    if (!seenVideoUrls.add(url)) continue

                    val label = "${height}p"
                    videos.add(VideoStreamInfo(url, mimeOnly, codecs, width, height, bitrate, label, initRange, indexRange))
                } else if (mimeOnly.startsWith("audio/")) {
                    // audioTrackId looks like "en" or "en.3" — strip the variant suffix.
                    var lang = item.optString("audioTrackId").takeIf { it.isNotBlank() } ?: "Default"
                    if (lang.contains(".")) lang = lang.substringBefore(".")
                    lang = lang.uppercase()

                    val key = "$lang|$url"
                    if (!seenAudioKeys.add(key)) continue

                    audios.add(AudioInfo(url, mimeOnly, codecs, bitrate, initRange, indexRange, lang))
                }
            } catch (e: Exception) {
                // skip this format
            }
        }

        // Keep best codec per quality bucket for video (avc/mp4 preferred over vp9/webm for compatibility).
        val dedupedVideos = videos
            .groupBy { it.height }
            .mapValues { (_, list) ->
                list.sortedWith(
                    compareByDescending<VideoStreamInfo> { it.mimeType.contains("mp4") }
                        .thenByDescending { it.bitrate }
                ).first()
            }
            .values
            .sortedBy { it.height }

        return Pair(dedupedVideos, audios)
    }

    /** Split "video/mp4; codecs=\"avc1.640028\"" -> ("video/mp4", "avc1.640028"). */
    private fun parseMimeAndCodecs(mime: String): Pair<String, String> {
        val parts = mime.split(";", limit = 2)
        val mimeOnly = parts[0].trim()
        val codecs = if (parts.size > 1) {
            val raw = parts[1].trim()
            // codecs="avc1.640028"
            val match = Regex("""codecs="([^"]+)"""").find(raw)
            match?.groupValues?.getOrNull(1) ?: ""
        } else ""
        return Pair(mimeOnly, codecs)
    }

    /** Build one ExtractorLink per (video quality, language) pair via a local DASH MPD. */
    private suspend fun buildAdaptiveLinks(
        videos: List<VideoStreamInfo>,
        audios: List<AudioInfo>,
        durationSeconds: Long
    ): List<ExtractorLink> {
        val out = mutableListOf<ExtractorLink>()
        if (videos.isEmpty()) return out

        startServerIfNeeded()

        // Filter to only audios that have init/index ranges — otherwise the audio
        // Representation in the MPD would have no SegmentBase and ExoPlayer would
        // fail to load audio after the initial buffer.
        val validAudios = audios.filter { it.initRange != null && it.indexRange != null }
        val audiosByLanguage = validAudios.groupBy { it.language }.ifEmpty { audios.groupBy { it.language } }

        for (video in videos) {
            if (audiosByLanguage.isNotEmpty()) {
                for ((lang, langAudios) in audiosByLanguage) {
                    // Prefer audios that have init/index ranges (required for DASH SegmentBase).
                    val candidate = langAudios.filter { it.initRange != null && it.indexRange != null }.ifEmpty { langAudios }
                    // Pair mp4 video with mp4 audio, webm video with webm/opus audio.
                    val bestAudio = if (video.mimeType.contains("webm")) {
                        candidate.sortedWith(
                            compareByDescending<AudioInfo> { it.mimeType.contains("webm") }
                                .thenByDescending { it.bitrate }
                        ).firstOrNull()
                    } else {
                        candidate.sortedWith(
                            compareByDescending<AudioInfo> { it.mimeType.contains("mp4") }
                                .thenByDescending { it.bitrate }
                        ).firstOrNull()
                    } ?: continue

                    val dashXml = buildDashManifestXml(video, listOf(bestAudio), durationSeconds)
                    val localLink = registerManifestAndGetUrl(dashXml) ?: continue
                    out.add(
                        newExtractorLink(this.name, "${video.label} ($lang)", localLink, type = ExtractorLinkType.DASH) {
                            this.referer = mainUrl
                            this.quality = video.height
                        }
                    )
                }
            } else {
                // No audio available - emit video-only as fallback.
                out.add(
                    newExtractorLink(this.name, "${video.label} (video only)", video.url, type = INFER_TYPE) {
                        this.referer = mainUrl
                        this.quality = video.height
                    }
                )
            }
        }
        return out
    }

    /** Build "Legacy" ExtractorLinks from muxed/progressive formats. */
    private suspend fun buildMuxedLinks(formats: org.json.JSONArray): List<ExtractorLink> {
        val out = mutableListOf<ExtractorLink>()
        val seen = mutableSetOf<String>()
        for (i in 0 until formats.length()) {
            val item = formats.optJSONObject(i) ?: continue
            try {
                val url = item.optString("url").takeIf { it.isNotBlank() } ?: continue
                if (!seen.add(url)) continue
                val height = item.optInt("height", 0)
                val label = if (height > 0) "${height}p" else "video"
                out.add(
                    newExtractorLink(this.name, "$label (Legacy)", url, type = INFER_TYPE) {
                        this.referer = mainUrl
                        this.quality = height
                    }
                )
            } catch (e: Exception) {}
        }
        return out
    }

    /**
     * Extract captions + auto-translations from the player response.
     *
     * Uses the API's translationLanguages field (typically 10-30 real languages)
     * instead of a hardcoded 158-language list (most of which 404).
     */
    private fun extractSubtitles(root: JSONObject): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        val seenUrls = mutableSetOf<String>()
        try {
            val tracklist = root.optJSONObject("captions")
                ?.optJSONObject("playerCaptionsTracklistRenderer") ?: return out

            val tracks = tracklist.optJSONArray("captionTracks") ?: return out
            if (tracks.length() == 0) return out

            // Pick the first English track as the base for auto-translation,
            // falling back to the first track.
            var baseTrack = tracks.optJSONObject(0)
            for (i in 0 until tracks.length()) {
                val t = tracks.optJSONObject(i) ?: continue
                if (t.optString("languageCode") == "en") {
                    baseTrack = t
                    break
                }
            }
            val baseUrl = baseTrack.optString("baseUrl").ifBlank { return out }
            val baseLang = baseTrack.optString("languageCode", "original").lowercase()

            // Add the actual caption tracks (real subtitles, not auto-translated)
            for (i in 0 until tracks.length()) {
                val t = tracks.optJSONObject(i) ?: continue
                val lang = t.optString("languageCode").ifBlank { "unknown" }
                val url = t.optString("baseUrl").ifBlank { continue }
                val vttUrl = if (url.contains("fmt=")) {
                    url.replace(Regex("fmt=[^&]+"), "fmt=vtt")
                } else {
                    "$url&fmt=vtt"
                }
                if (seenUrls.add(vttUrl)) {
                    val name = t.optJSONObject("name")?.optString("simpleText") ?: lang
                    out.add("$name ($lang)" to vttUrl)
                }
            }

            // Add auto-translations using the API's translationLanguages field.
            // This returns ~10-30 real languages that YouTube actually supports,
            // instead of the old hardcoded 158-language list (most of which 404).
            val translationLangs = tracklist.optJSONArray("translationLanguages")
            if (translationLangs != null) {
                for (i in 0 until translationLangs.length()) {
                    val tl = translationLangs.optJSONObject(i) ?: continue
                    val targetLang = tl.optString("languageCode").ifBlank { continue }
                    if (targetLang.equals(baseLang, ignoreCase = true)) continue

                    val autoUrl = if (baseUrl.contains("fmt=")) {
                        baseUrl.replace(Regex("fmt=[^&]+"), "fmt=vtt") + "&tlang=$targetLang"
                    } else {
                        "$baseUrl&fmt=vtt&tlang=$targetLang"
                    }
                    if (seenUrls.add(autoUrl)) {
                        val targetName = tl.optJSONObject("languageName")?.optString("simpleText") ?: targetLang
                        out.add("$baseLang → $targetName" to autoUrl)
                    }
                }
            }
        } catch (e: Exception) {}
        return out
    }

    @Synchronized
    private fun startServerIfNeeded() {
        if (activeServer != null && !activeServer!!.isClosed) return
        try {
            activeServer = ServerSocket(0)
            serverPort = activeServer!!.localPort
            thread {
                try {
                    while (activeServer != null && !activeServer!!.isClosed) {
                        val client = activeServer!!.accept()
                        thread { handleClient(client) }
                    }
                } catch (e: Exception) {}
            }
        } catch (e: Exception) { logError(e) }
    }

    private fun registerManifestAndGetUrl(xmlContent: String): String? {
        if (serverPort == 0) return null
        if (manifestMap.size > MAX_MANIFESTS) {
            manifestMap.keys.take(manifestMap.size - MAX_MANIFESTS + 1).forEach { k ->
                manifestMap.remove(k)
            }
        }
        val id = UUID.randomUUID().toString()
        manifestMap[id] = xmlContent
        return "http://127.0.0.1:$serverPort/$id.mpd"
    }

    /** Serve the MPD via raw OutputStream with proper \r\n framing and Content-Length. */
    private fun handleClient(client: Socket) {
        try {
            client.use { socket ->
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val rawOutput = socket.getOutputStream()
                val line = reader.readLine()
                if (line != null && line.startsWith("GET")) {
                    val parts = line.split(" ")
                    if (parts.size > 1) {
                        var path = parts[1].substring(1)
                        if (path.endsWith(".mpd")) path = path.replace(".mpd", "")
                        val content = manifestMap[path]
                        if (content != null) {
                            val body = content.toByteArray(Charsets.UTF_8)
                            val header = buildString {
                                append("HTTP/1.1 200 OK\r\n")
                                append("Content-Type: application/dash+xml\r\n")
                                append("Content-Length: ${body.size}\r\n")
                                append("Connection: close\r\n")
                                append("Access-Control-Allow-Origin: *\r\n")
                                append("Cache-Control: no-store\r\n")
                                append("\r\n")
                            }.toByteArray(Charsets.US_ASCII)
                            rawOutput.write(header)
                            rawOutput.write(body)
                            rawOutput.flush()
                        } else {
                            val resp = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                                .toByteArray(Charsets.US_ASCII)
                            rawOutput.write(resp)
                            rawOutput.flush()
                        }
                    }
                }
            }
        } catch (e: Exception) {}
    }

    /**
     * Build a minimal DASH MPD that references the video-only stream + one audio stream
     * via BaseURL, with SegmentBase pointing at init/index ranges.
     *
     * FIX: use the real video duration from videoDetails.lengthSeconds, not a hardcoded
     * 3600s. ExoPlayer uses mediaPresentationDuration for the seek bar — a mismatch
     * causes it to compute seek targets outside the actual byte range of the file.
     */
    private fun buildDashManifestXml(
        video: VideoStreamInfo,
        audioList: List<AudioInfo>,
        durationSec: Long
    ): String {
        // Fall back to 3600s only if the API returned no duration.
        val safeDuration = if (durationSec > 0) durationSec else 3600L
        val durationString = "PT${safeDuration}S"
        val sb = StringBuilder()
        sb.append("""<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" profiles="urn:mpeg:dash:profile:isoff-on-demand:2011" type="static" minBufferTime="PT5.0S" mediaPresentationDuration="$durationString">""")
        sb.append("<Period>")

        val vCodecs = video.codecs.ifEmpty { if (video.mimeType.contains("webm")) "vp9" else "avc1.4d401f" }
        val vBandwidth = if (video.bitrate > 0) video.bitrate else 4000000
        val vSegmentBase = if (video.initRange != null && video.indexRange != null) {
            """<SegmentBase indexRange="${video.indexRange}"><Initialization range="${video.initRange}" /></SegmentBase>"""
        } else ""
        val widthAttr = if (video.width > 0) """width="${video.width}" """ else ""

        sb.append("""<AdaptationSet mimeType="${video.mimeType}" subsegmentAlignment="true" subsegmentStartsWithSAP="1"><Representation id="video" bandwidth="$vBandwidth" ${widthAttr}height="${video.height}" codecs="$vCodecs"><BaseURL>${escapeXml(video.url)}</BaseURL>$vSegmentBase</Representation></AdaptationSet>""")

        audioList.forEachIndexed { index, audio ->
            val aCodecs = audio.codecs.ifEmpty { if (audio.mimeType.contains("webm")) "opus" else "mp4a.40.2" }
            val aBandwidth = if (audio.bitrate > 0) audio.bitrate else 128000
            val aSegmentBase = if (audio.initRange != null && audio.indexRange != null) {
                """<SegmentBase indexRange="${audio.indexRange}"><Initialization range="${audio.initRange}" /></SegmentBase>"""
            } else ""
            sb.append("""<AdaptationSet mimeType="${audio.mimeType}" subsegmentAlignment="true" subsegmentStartsWithSAP="1"><Representation id="audio_$index" bandwidth="$aBandwidth" codecs="$aCodecs"><BaseURL>${escapeXml(audio.url)}</BaseURL>$aSegmentBase</Representation></AdaptationSet>""")
        }

        sb.append("</Period>")
        sb.append("</MPD>")
        return sb.toString()
    }

    private fun escapeXml(url: String): String =
        url.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
