package com.youtube

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.schemaStripRegex
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.SubtitlesStream
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeStreamLinkHandlerFactory
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * YouTube extractor for CloudStream.
 *
 * Builds a local DASH manifest that muxes a video-only stream with one audio track
 * (per language) and serves it through a tiny HTTP server on 127.0.0.1.
 * ExoPlayer then plays the manifest as a single adaptive source.
 *
 * High-level flow:
 *   1. StreamInfo.getInfo(url) -> NewPipeExtractor fetches YouTube page & parses streams
 *   2. videoOnlyStreams (1080p/1440p/4K, DASH) are paired with audioStreams per language
 *   3. For each (video quality, language) pair we generate a DASH MPD and serve it locally
 *   4. Muxed/progressive streams (capped at 360p/720p by YouTube) are added as "Legacy" fallback
 *   5. Live streams use the HLS manifest (info.hlsUrl) directly
 */
open class YoutubeExtractor : ExtractorApi() {
    override val mainUrl = "https://www.youtube.com"
    override val requiresReferer = false
    override val name = "YouTube"

    companion object {
        private var ytVideos: MutableMap<String, List<ExtractorLink>> = mutableMapOf()
        private var ytVideosSubtitles: MutableMap<String, List<SubtitlesStream>> = mutableMapOf()

        private var activeServer: ServerSocket? = null
        private var serverPort: Int = 0
        private val manifestMap = ConcurrentHashMap<String, String>()

        // Simple TTL guard so the manifest map does not grow unbounded across many videos.
        private const val MAX_MANIFESTS = 64
    }

    override fun getExtractorUrl(id: String): String {
        return "$mainUrl/watch?v=$id"
    }

    /** Local view of a video-only stream extracted from NewPipeExtractor. */
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

    /** Local view of an audio stream extracted from NewPipeExtractor. */
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
        val videoId = try {
            YoutubeStreamLinkHandlerFactory.getInstance().fromUrl(url).id
        } catch (e: Exception) {
            try { cleanedUrl.substringAfter("v=").substringBefore("&") } catch (e2: Exception) { cleanedUrl }
        }

        ytVideos.remove(videoId)
        ytVideosSubtitles.remove(videoId)

        if (ytVideos[videoId].isNullOrEmpty()) {
            try {
                // Use StreamInfo.getInfo() (the high-level factory) instead of directly
                // instantiating YoutubeStreamExtractor. It handles SABR fallbacks,
                // age-gated videos, and player response parsing more robustly.
                val watchUrl = "$mainUrl/watch?v=$videoId"
                val info = StreamInfo.getInfo(watchUrl)

                // === Live streams: use HLS directly (no per-quality selection, but >360p) ===
                val isLive = info.streamType == StreamType.LIVE_STREAM ||
                        info.streamType == StreamType.AUDIO_LIVE_STREAM ||
                        info.streamType == StreamType.POST_LIVE_STREAM ||
                        info.streamType == StreamType.POST_LIVE_AUDIO_STREAM

                if (isLive && !info.hlsUrl.isNullOrEmpty()) {
                    val liveLink = newExtractorLink(
                        this.name,
                        "YouTube Live",
                        info.hlsUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = mainUrl
                        // 0 == "Auto" in CloudStream's quality->string mapping.
                        this.quality = 0
                    }
                    ytVideos[videoId] = listOf(liveLink)
                    try {
                        ytVideosSubtitles[videoId] = info.subtitles ?: emptyList()
                    } catch (e: Exception) { }
                    ytVideos[videoId]?.forEach { callback(it) }
                    emitSubtitles(videoId, subtitleCallback)
                    return
                }

                val durationSeconds = if (info.duration > 0) info.duration else 3600L
                val builtLinks = mutableListOf<ExtractorLink>()
                val seenUrls = mutableSetOf<String>()

                // === Video-only (DASH) streams: 144p -> 2160p ===
                val videoOnlyList = (info.videoOnlyStreams ?: emptyList()).mapNotNull { vs ->
                    try {
                        val streamUrl = vs.content ?: return@mapNotNull null
                        if (!seenUrls.add(streamUrl)) return@mapNotNull null

                        val height = runCatching { vs.height ?: 0 }.getOrNull() ?: 0
                        val width = runCatching { vs.width ?: 0 }.getOrNull() ?: 0
                        val bitrate = runCatching { vs.bitrate ?: 0 }.getOrNull() ?: 0

                        var mime = vs.format?.mimeType
                        if (mime.isNullOrEmpty()) mime = getMimeTypeFromUrl(streamUrl, false)

                        // FIX: use the actual codec from the stream rather than a hardcoded
                        // "avc1.4d401f". A wrong codec makes ExoPlayer reject the representation
                        // and fall back to a lower quality (often 360p muxed).
                        var codecs = runCatching { vs.codec }.getOrNull()
                        if (codecs.isNullOrEmpty()) codecs = if (mime.contains("webm")) "vp9" else "avc1.4d401f"

                        val label = if (height > 0) "${height}p" else "video"

                        val initR = if (vs.initStart != null && vs.initEnd != null)
                            "${vs.initStart}-${vs.initEnd}" else null
                        val indexR = if (vs.indexStart != null && vs.indexEnd != null)
                            "${vs.indexStart}-${vs.indexEnd}" else null

                        VideoStreamInfo(streamUrl, mime, codecs, width, height, bitrate, label, initR, indexR)
                    } catch (e: Exception) { null }
                }.distinctBy { it.height } // keep best codec per quality bucket

                // === Audio streams ===
                val audioInfoList = (info.audioStreams ?: emptyList()).mapNotNull { asr ->
                    try {
                        val aUrl = asr.content ?: return@mapNotNull null
                        val bitrate = asr.bitrate ?: 128000
                        var mime = asr.format?.mimeType
                        if (mime.isNullOrEmpty()) mime = getMimeTypeFromUrl(aUrl, true)

                        var codecs = runCatching { asr.codec }.getOrNull()
                        if (codecs.isNullOrEmpty()) codecs = if (mime.contains("webm")) "opus" else "mp4a.40.2"

                        val initR = if (asr.initStart != null && asr.initEnd != null)
                            "${asr.initStart}-${asr.initEnd}" else null
                        val indexR = if (asr.indexStart != null && asr.indexEnd != null)
                            "${asr.indexStart}-${asr.indexEnd}" else null

                        var rawLang = asr.audioTrackId ?: "Default"
                        if (rawLang.contains(".")) rawLang = rawLang.substringBefore(".")

                        AudioInfo(aUrl, mime, codecs, bitrate, initR, indexR, rawLang.uppercase())
                    } catch (e: Exception) { null }
                }.distinctBy { it.url }

                val audiosByLanguage = audioInfoList.groupBy { it.language }

                try {
                    ytVideosSubtitles[videoId] = info.subtitles ?: emptyList()
                } catch (e: Exception) { }

                startServerIfNeeded()

                // === Emit one ExtractorLink per (quality, language) ===
                for (video in videoOnlyList) {
                    if (audiosByLanguage.isNotEmpty()) {
                        for ((lang, audios) in audiosByLanguage) {
                            // Pick the audio whose container matches the video container
                            // (mp4 audio for mp4 video, webm/opus audio for webm video).
                            val bestAudioForLang = if (video.mimeType.contains("webm")) {
                                audios.sortedWith(
                                    compareByDescending<AudioInfo> { it.mimeType.contains("webm") }
                                        .thenByDescending { it.bitrate }
                                ).firstOrNull()
                            } else {
                                audios.sortedWith(
                                    compareByDescending<AudioInfo> { it.mimeType.contains("mp4") }
                                        .thenByDescending { it.bitrate }
                                ).firstOrNull()
                            }

                            if (bestAudioForLang != null) {
                                val dashXml = buildDashManifestXml(video, listOf(bestAudioForLang), durationSeconds)
                                val localLink = registerManifestAndGetUrl(dashXml)

                                if (localLink != null) {
                                    // FIX: label was "${video.label} ($lang) ${video.label}" -> duplicate.
                                    val finalName = "${video.label} ($lang)"

                                    builtLinks.add(
                                        newExtractorLink(
                                            this.name,
                                            finalName,
                                            localLink,
                                            type = ExtractorLinkType.DASH
                                        ) {
                                            this.referer = mainUrl
                                            this.quality = video.height
                                        }
                                    )
                                }
                            }
                        }
                    } else {
                        // No audio available (rare) - emit video-only as a fallback.
                        builtLinks.add(
                            newExtractorLink(
                                this.name,
                                "${video.label} (video only)",
                                video.url,
                                type = INFER_TYPE
                            ) {
                                this.referer = mainUrl
                                this.quality = video.height
                            }
                        )
                    }
                }

                // === Muxed/progressive streams as "Legacy" fallback ===
                // YouTube caps these at 360p / 720p, but they always work even if DASH fails.
                val muxedList = (info.videoStreams ?: emptyList()).mapNotNull { vs ->
                    try {
                        val mUrl = vs.content ?: return@mapNotNull null
                        if (!seenUrls.add(mUrl)) return@mapNotNull null
                        val height = runCatching { vs.height ?: 0 }.getOrNull() ?: 0
                        val label = if (height > 0) "${height}p" else "video"
                        Triple(mUrl, label, height)
                    } catch (e: Exception) { null }
                }
                for ((mUrl, mLabel, mHeight) in muxedList) {
                    builtLinks.add(
                        newExtractorLink(this.name, "$mLabel (Legacy)", mUrl, type = INFER_TYPE) {
                            this.referer = mainUrl
                            this.quality = mHeight
                        }
                    )
                }

                ytVideos[videoId] = builtLinks.toList()

            } catch (e: Exception) { logError(e) }
        }

        ytVideos[videoId]?.forEach { callback(it) }
        emitSubtitles(videoId, subtitleCallback)
    }

    private suspend fun emitSubtitles(
        videoId: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        ytVideosSubtitles[videoId]?.mapNotNull { ss ->
            try {
                val lang = ss.locale?.language ?: return@mapNotNull null
                val content = ss.content ?: ss.getUrl() ?: return@mapNotNull null
                newSubtitleFile(lang, content)
            } catch (e: Exception) { null }
        }?.forEach { subtitleCallback(it) }
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

        // Bound the manifest cache so it doesn't leak across many videos.
        if (manifestMap.size > MAX_MANIFESTS) {
            manifestMap.keys.take(manifestMap.size - MAX_MANIFESTS + 1).forEach { k ->
                manifestMap.remove(k)
            }
        }

        val id = UUID.randomUUID().toString()
        manifestMap[id] = xmlContent
        return "http://127.0.0.1:$serverPort/$id.mpd"
    }

    /**
     * FIX: rewritten to use raw OutputStream with explicit \r\n line terminators
     * and a Content-Length header. The previous PrintWriter.println() approach
     * used the platform line separator (LF on Android) which is technically
     * invalid HTTP, and the missing Content-Length forced ExoPlayer to rely on
     * connection-close framing - which sometimes truncated the manifest.
     */
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
     * Build a minimal DASH MPD that references the video-only stream and one
     * audio stream via BaseURL, with SegmentBase pointing at the init/index
     * ranges YouTube exposes for each.
     *
     * FIXES vs the previous version:
     *   - codecs are taken from the stream (vs hardcoded "avc1.4d401f")
     *   - width is omitted if 0 (vs hardcoded width="0" which is invalid)
     *   - bandwidth comes from the stream (vs hardcoded "4000000")
     *   - audio codec/bandwidth also taken from the stream
     */
    private fun buildDashManifestXml(
        video: VideoStreamInfo,
        audioList: List<AudioInfo>,
        durationSec: Long
    ): String {
        val cleanVideoUrl = escapeXml(video.url)
        val durationString = "PT${durationSec}S"

        val sb = StringBuilder()
        sb.append("""<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" profiles="urn:mpeg:dash:profile:isoff-on-demand:2011" type="static" minBufferTime="PT5.0S" mediaPresentationDuration="$durationString">""")
        sb.append("<Period>")

        val vMime = video.mimeType
        val vCodecs = video.codecs
        val vBandwidth = if (video.bitrate > 0) video.bitrate else 4000000

        val vSegmentBase = if (video.initRange != null && video.indexRange != null) {
            """<SegmentBase indexRange="${video.indexRange}"><Initialization range="${video.initRange}" /></SegmentBase>"""
        } else ""

        // Omit width attribute entirely if unknown (width="0" is invalid DASH).
        val widthAttr = if (video.width > 0) """width="${video.width}" """ else ""
        sb.append("""
            <AdaptationSet mimeType="$vMime" subsegmentAlignment="true" subsegmentStartsWithSAP="1">
              <Representation id="video" bandwidth="$vBandwidth" ${widthAttr}height="${video.height}" codecs="$vCodecs">
                <BaseURL>$cleanVideoUrl</BaseURL>
                $vSegmentBase
              </Representation>
            </AdaptationSet>
        """.trimIndent())

        audioList.forEachIndexed { index, audio ->
            val cleanAudioUrl = escapeXml(audio.url)
            val audioId = "audio_$index"
            val aMime = audio.mimeType
            val aCodecs = audio.codecs
            val aBandwidth = if (audio.bitrate > 0) audio.bitrate else 128000

            val aSegmentBase = if (audio.initRange != null && audio.indexRange != null) {
                """<SegmentBase indexRange="${audio.indexRange}"><Initialization range="${audio.initRange}" /></SegmentBase>"""
            } else ""

            sb.append("""
                <AdaptationSet mimeType="$aMime" subsegmentAlignment="true" subsegmentStartsWithSAP="1">
                  <Representation id="$audioId" bandwidth="$aBandwidth" codecs="$aCodecs">
                    <BaseURL>$cleanAudioUrl</BaseURL>
                    $aSegmentBase
                  </Representation>
                </AdaptationSet>
            """.trimIndent())
        }

        sb.append("</Period>")
        sb.append("</MPD>")
        return sb.toString()
    }

    private fun escapeXml(url: String): String {
        return url.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }

    private fun getMimeTypeFromUrl(url: String, isAudio: Boolean): String {
        return try {
            val decoded = URLDecoder.decode(url, "UTF-8")
            if (decoded.contains("video/webm") || decoded.contains("audio/webm")) {
                if (isAudio) "audio/webm" else "video/webm"
            } else {
                if (isAudio) "audio/mp4" else "video/mp4"
            }
        } catch (e: Exception) { if (isAudio) "audio/mp4" else "video/mp4" }
    }
}
