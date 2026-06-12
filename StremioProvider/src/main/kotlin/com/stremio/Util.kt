package com.stremio

import android.util.Log
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.AcraApplication
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder
import java.util.Locale

private const val TRACKER_LIST_URL = "https://raw.githubusercontent.com/ngosang/trackerslist/master/trackers_best.txt"

private val utilMapper = jacksonObjectMapper()

val SPEC_OPTIONS = mapOf(
    "quality" to listOf(
        mapOf("value" to "BluRay", "label" to "BluRay"),
        mapOf("value" to "BluRay REMUX", "label" to "BluRay REMUX"),
        mapOf("value" to "BRRip", "label" to "BRRip"),
        mapOf("value" to "BDRip", "label" to "BDRip"),
        mapOf("value" to "WEB-DL", "label" to "WEB-DL"),
        mapOf("value" to "HDRip", "label" to "HDRip"),
        mapOf("value" to "DVDRip", "label" to "DVDRip"),
        mapOf("value" to "HDTV", "label" to "HDTV"),
        mapOf("value" to "CAM", "label" to "CAM"),
        mapOf("value" to "TeleSync", "label" to "TeleSync"),
        mapOf("value" to "SCR", "label" to "SCR")
    ),
    "audio" to listOf(
        mapOf("value" to "AAC", "label" to "AAC"),
        mapOf("value" to "AC3", "label" to "AC3"),
        mapOf("value" to "DTS", "label" to "DTS"),
        mapOf("value" to "DTS-HD MA", "label" to "DTS-HD MA"),
        mapOf("value" to "TrueHD", "label" to "Dolby TrueHD"),
        mapOf("value" to "Atmos", "label" to "Dolby Atmos"),
        mapOf("value" to "DD+", "label" to "DD+"),
        mapOf("value" to "Dolby Digital Plus", "label" to "Dolby Digital Plus"),
        mapOf("value" to "DTS Lossless", "label" to "DTS Lossless")
    ),
    "hdr" to listOf(
        mapOf("value" to "DV", "label" to "Dolby Vision"),
        mapOf("value" to "HDR10+", "label" to "HDR10+"),
        mapOf("value" to "HDR", "label" to "HDR"),
        mapOf("value" to "SDR", "label" to "SDR")
    )
)

fun fixSourceUrl(url: String): String {
    return url.replace("/manifest.json", "").replace("stremio://", "https://")
}

suspend fun generateMagnetLink(infoHash: String): String {
    val trackers = try {
        app.get(TRACKER_LIST_URL).text.trim().split("\n")
    } catch (e: Exception) {
        emptyList()
    }
    val magnet = StringBuilder("magnet:?xt=urn:btih:$infoHash")
    for (tracker in trackers) {
        if (tracker.isNotBlank()) {
            magnet.append("&tr=${tracker.trim()}")
        }
    }
    return magnet.toString()
}

/**
 * Fetch streams from a Stremio addon and create extractor links.
 * Used by StremioX for its primary and fallback sources.
 * Uses Jackson tree model for robust parsing (never fails on unknown JSON fields).
 */
suspend fun invokeMainSource(
    baseUrl: String,
    providerName: String,
    imdbId: String,
    season: Int?,
    episode: Int?,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) {
    if (imdbId.isBlank()) {
        Log.w("StremioUtil", "invokeMainSource: imdbId is blank, skipping")
        return
    }

    val fixedUrl = fixSourceUrl(baseUrl)

    // Use correct path: /stream/series/ for series, /stream/movie/ for movies
    val streamUrl = if (season != null) {
        "$fixedUrl/stream/series/$imdbId:$season:${episode ?: 1}.json"
    } else {
        "$fixedUrl/stream/movie/$imdbId.json"
    }

    Log.d("StremioUtil", "invokeMainSource URL: $streamUrl")

    try {
        val response = app.get(streamUrl, timeout = 30)
        if (!response.isSuccessful || response.text.isEmpty()) return

        // Use tree model for robust parsing
        parseStreamsFromJsonUtil(response.text, providerName, subtitleCallback, callback)
    } catch (e: Exception) {
        Log.e("StremioUtil", "invokeMainSource failed: $streamUrl - ${e.message}")
    }
}

/**
 * Parse streams from JSON using Jackson tree model.
 * NEVER fails on unknown fields like cacheMaxAge, fileIdx, bingeGroup, etc.
 */
private suspend fun parseStreamsFromJsonUtil(
    jsonText: String,
    providerName: String,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) {
    try {
        val rootNode = utilMapper.readTree(jsonText)
        val streamsNode = rootNode?.get("streams") ?: return
        if (!streamsNode.isArray || streamsNode.size() == 0) return

        for (streamNode in streamsNode) {
            try {
                val url = streamNode.get("url")?.asText()
                val ytId = streamNode.get("ytId")?.asText()
                val externalUrl = streamNode.get("externalUrl")?.asText()
                val infoHash = streamNode.get("infoHash")?.asText()
                val streamName = streamNode.get("name")?.asText() ?: ""
                val streamTitle = streamNode.get("title")?.asText() ?: ""
                val streamDesc = streamNode.get("description")?.asText() ?: ""

                // Process URL streams
                if (url != null) {
                    val fixedName = fixSourceName(streamName, streamTitle, streamDesc)
                    val qualityTitle = buildExtractedTitle(extractSpecs(fixedName))

                    val headers = extractHeadersFromNodeUtil(streamNode)

                    callback.invoke(
                        newExtractorLink(
                            source = streamName.ifBlank { streamTitle },
                            name = qualityTitle.ifBlank { streamTitle },
                            url = url,
                            type = null
                        ) {
                            this.referer = ""
                            this.quality = getQuality(listOf(streamDesc, streamTitle, streamName))
                            this.headers = headers
                        }
                    )
                }

                if (ytId != null) {
                    loadExtractor("https://www.youtube.com/watch?v=$ytId", subtitleCallback, callback)
                }

                if (externalUrl != null) {
                    loadExtractor(externalUrl, subtitleCallback, callback)
                }

                if (infoHash != null) {
                    val magnet = generateMagnetLink(infoHash)
                    callback.invoke(
                        newExtractorLink(
                            source = providerName,
                            name = streamTitle.ifBlank { streamName },
                            url = magnet,
                            type = ExtractorLinkType.TORRENT
                        ) {
                            this.referer = ""
                            this.quality = getQualityFromName(streamTitle)
                        }
                    )
                }

                // Process subtitles
                val subtitlesNode = streamNode.get("subtitles")
                if (subtitlesNode != null && subtitlesNode.isArray) {
                    for (subNode in subtitlesNode) {
                        val subUrl = subNode.get("url")?.asText()
                        val subLang = subNode.get("lang")?.asText()
                        if (subUrl != null && subLang != null) {
                            try {
                                subtitleCallback.invoke(SubtitleFile(subLang, subUrl))
                            } catch (_: Exception) {}
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("StremioUtil", "Failed to process one stream: ${e.message}")
            }
        }
    } catch (e: Exception) {
        Log.e("StremioUtil", "Failed to parse streams JSON: ${e.message}")
    }
}

/**
 * Extract headers from behaviorHints in a stream JsonNode.
 * Handles both string and array values for headers.
 */
private fun extractHeadersFromNodeUtil(streamNode: com.fasterxml.jackson.databind.JsonNode): Map<String, String> {
    try {
        val bh = streamNode.get("behaviorHints") ?: return emptyMap()

        // Try proxyHeaders.request first
        val proxyReq = bh.get("proxyHeaders")?.get("request")
        if (proxyReq != null && proxyReq.isObject) {
            val headers = mutableMapOf<String, String>()
            proxyReq.fields().forEach { entry ->
                val value = entry.value
                headers[entry.key] = if (value.isArray) {
                    value.firstOrNull()?.asText() ?: ""
                } else {
                    value.asText()
                }
            }
            return headers
        }

        // Fallback to behaviorHints.headers
        val headersNode = bh.get("headers")
        if (headersNode != null && headersNode.isObject) {
            val headers = mutableMapOf<String, String>()
            headersNode.fields().forEach { entry ->
                val value = entry.value
                headers[entry.key] = if (value.isArray) {
                    value.firstOrNull()?.asText() ?: ""
                } else {
                    value.asText()
                }
            }
            return headers
        }
    } catch (_: Exception) {}
    return emptyMap()
}

fun extractSpecs(title: String): Map<String, List<String>> {
    val result = mutableMapOf<String, List<String>>()

    for ((key, options) in SPEC_OPTIONS) {
        val matches = options.filter { option ->
            val value = option["value"] as String
            Regex("\\b${Regex.escape(value)}\\b", RegexOption.IGNORE_CASE)
                .containsMatchIn(title)
        }.map { it["label"] as String }
        result[key] = matches
    }

    val sizeMatch = Regex("(\\d+(?:\\.\\d+)?\\s?(?:MB|GB))", RegexOption.IGNORE_CASE)
        .find(title)
    if (sizeMatch != null) {
        result["size"] = listOf(sizeMatch.groupValues[1])
    }

    return result.toMap()
}

fun buildExtractedTitle(specs: Map<String, List<String>>): String {
    val keys = listOf("quality", "codec", "audio", "hdr", "language")
    val allSpecs = mutableListOf<String>()
    for (key in keys) {
        allSpecs.addAll(specs[key] ?: emptyList())
    }
    val title = allSpecs.distinct().joinToString(" ")
    val size = specs["size"]?.firstOrNull()
    return if (size != null) "$title [$size]" else title
}

fun fixSourceName(name: String?, title: String?, description: String?): String {
    val fixedName = name?.replace("\n", " ")
    val fixedTitle = title?.replace("\n", " ")

    return when {
        !fixedName.isNullOrEmpty() && !fixedTitle.isNullOrEmpty() ->
            "$fixedName\n$fixedTitle"
        !fixedName.isNullOrEmpty() && description != null ->
            "$fixedName\n$description"
        !fixedTitle.isNullOrEmpty() -> fixedTitle
        description != null -> description
        !fixedName.isNullOrEmpty() -> fixedName
        else -> ""
    }
}

fun capitalizeTitle(title: String): String {
    return title.split(" ").joinToString(" ") { word ->
        val lower = word.lowercase()
        if (lower.isNotEmpty()) {
            val first = lower[0]
            val capitalized = if (!first.isLetter() && first != '(') {
                first.toString()
            } else {
                first.toString().uppercase()
            }
            var result = "$capitalized${lower.substring(1)}"
            if (result.startsWith("(") && result.length > 1) {
                result = "(${result[1].uppercase()}${result.substring(2)}"
            }
            result
        } else word
    }
}

fun getImageUrl(path: String?): String? {
    if (path == null) return null
    return if (path.startsWith("/")) {
        "https://image.tmdb.org/t/p/w500/$path"
    } else path
}

fun getOriImageUrl(path: String?): String? {
    if (path == null) return null
    return if (path.startsWith("/")) {
        "https://image.tmdb.org/t/p/original/$path"
    } else path
}

fun getType(type: String?): TvType {
    return if (type == "movie") TvType.Movie else TvType.TvSeries
}

fun isImdborTmdb(id: String?): Boolean {
    if (id == null) return false
    return id.startsWith("tt") || id.matches(Regex("\\d+"))
}

fun getQuality(specLabels: List<String?>): Int {
    for (label in specLabels) {
        if (label != null) {
            val qualityMatch = Regex("(\\d{3,4}[pP])").find(label)
            if (qualityMatch != null) {
                return getQualityFromName(qualityMatch.groupValues[1])
            }
        }
    }
    return Qualities.Unknown.value
}

fun getStatus(status: String?): ShowStatus {
    return if (status == "Returning Series") ShowStatus.Ongoing else ShowStatus.Completed
}

fun getEpisodeSlug(season: Int? = null, episode: Int? = null): Pair<String, String> {
    if (season == null && episode == null) return "" to ""
    val s = if (season!! >= 10) "$season" else "0$season"
    val e = if (episode!! >= 10) "$episode" else "0$episode"
    return s to e
}

suspend fun readFileFromUrl(url: String): String? {
    return try {
        app.get(url).text.trim()
    } catch (e: Exception) {
        null
    }
}

fun fixUrl(url: String, baseUrl: String): String {
    if (url.startsWith("http")) return url
    if (url.isEmpty()) return ""
    if (url.startsWith("//")) return "https:$url"
    if (url.startsWith("/")) return "$baseUrl$url"
    return "$baseUrl/$url"
}

fun String.encodeUri() = URLEncoder.encode(this, "utf8")

fun removeVietnameseAccents(input: String): String {
    val temp = input.lowercase()
    val replacements = mapOf(
        'á' to 'a', 'à' to 'a', 'ả' to 'a', 'ã' to 'a', 'ạ' to 'a',
        'ă' to 'a', 'ắ' to 'a', 'ằ' to 'a', 'ẳ' to 'a', 'ẵ' to 'a', 'ặ' to 'a',
        'â' to 'a', 'ấ' to 'a', 'ầ' to 'a', 'ẩ' to 'a', 'ẫ' to 'a', 'ậ' to 'a',
        'é' to 'e', 'è' to 'e', 'ẻ' to 'e', 'ẽ' to 'e', 'ẹ' to 'e',
        'ê' to 'e', 'ế' to 'e', 'ề' to 'e', 'ể' to 'e', 'ễ' to 'e', 'ệ' to 'e',
        'í' to 'i', 'ì' to 'i', 'ỉ' to 'i', 'ĩ' to 'i', 'ị' to 'i',
        'ó' to 'o', 'ò' to 'o', 'ỏ' to 'o', 'õ' to 'o', 'ọ' to 'o',
        'ô' to 'o', 'ố' to 'o', 'ồ' to 'o', 'ổ' to 'o', 'ỗ' to 'o', 'ộ' to 'o',
        'ơ' to 'o', 'ớ' to 'o', 'ờ' to 'o', 'ở' to 'o', 'ỡ' to 'o', 'ợ' to 'o',
        'ú' to 'u', 'ù' to 'u', 'ủ' to 'u', 'ũ' to 'u', 'ụ' to 'u',
        'ư' to 'u', 'ứ' to 'u', 'ừ' to 'u', 'ử' to 'u', 'ữ' to 'u', 'ự' to 'u',
        'ý' to 'y', 'ỳ' to 'y', 'ỷ' to 'y', 'ỹ' to 'y', 'ỵ' to 'y',
        'đ' to 'd'
    )
    return temp.map { replacements[it] ?: it }.joinToString("")
}

fun createSlug(input: String?): String? {
    if (input == null) return null
    val noAccents = removeVietnameseAccents(input) ?: return null
    val lower = noAccents.lowercase()
    val noSpecial = Regex("[^a-z0-9\\s]+").replace(lower, "")
    val trimmed = noSpecial.trim()
    return Regex("\\s+").replace(trimmed, "-")
}

fun fixTitle(title: String): String {
    return title
        .replace(Regex("^\\[.*?]\\s*"), "")
        .replace(Regex("\\s*\\[.*?]$"), "")
        .trim()
}
