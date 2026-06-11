package com.stremio

import android.util.Log
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
import java.net.URI
import java.net.URLEncoder
import java.util.Locale

private const val TRACKER_LIST_URL = "https://raw.githubusercontent.com/ngosang/trackerslist/master/trackers_best.txt"

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

suspend fun invokeMainSource(
    baseUrl: String,
    providerName: String,
    imdbId: String,
    season: Int?,
    episode: Int?,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) {
    val fixedUrl = fixSourceUrl(baseUrl)

    // Construct proper Stremio stream URL
    // Movies: /stream/movie/{id}.json
    // Series: /stream/series/{id}:{season}:{episode}.json
    val streamUrl = if (season != null && episode != null) {
        "$fixedUrl/stream/series/$imdbId:$season:$episode.json"
    } else if (season != null) {
        "$fixedUrl/stream/series/$imdbId:$season:1.json"
    } else {
        "$fixedUrl/stream/movie/$imdbId.json"
    }

    Log.d("StremioUtil", "invokeMainSource: $streamUrl")

    val response = try {
        app.get(streamUrl, timeout = 30)
    } catch (e: Exception) {
        Log.e("StremioUtil", "Stream request failed: ${e.message}")
        return
    }

    if (!response.isSuccessful) {
        Log.e("StremioUtil", "Stream response not successful: ${response.code}")
        return
    }

    val streamsResponse = tryParseJson<StreamsResponse>(response.text) ?: return
    val streams = streamsResponse.streams

    if (streams.isNullOrEmpty()) {
        Log.w("StremioUtil", "No streams found from $fixedUrl")
        return
    }

    Log.d("StremioUtil", "Found ${streams.size} streams from $fixedUrl")

    for (stream in streams) {
        val streamName = stream.name ?: ""
        val streamTitle = stream.title ?: stream.name ?: ""

        try {
            when {
                stream.url != null -> {
                    var referer: String? = null
                    try {
                        val headers = stream.behaviorHints?.proxyHeaders?.request
                        referer = headers?.get("referer") ?: headers?.get("origin")
                    } catch (e: Exception) {}

                    val isM3u8 = stream.url.endsWith(".m3u8")
                    val linkType = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    callback.invoke(
                        newExtractorLink(
                            source = streamName,
                            name = streamTitle,
                            url = stream.url,
                            type = linkType
                        ) {
                            this.referer = referer ?: ""
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }

                stream.ytId != null -> {
                    loadExtractor("https://www.youtube.com/watch?v=${stream.ytId}", subtitleCallback, callback)
                }

                stream.externalUrl != null -> {
                    loadExtractor(stream.externalUrl, subtitleCallback, callback)
                }

                stream.infoHash != null -> {
                    val magnet = generateMagnetLink(stream.infoHash)
                    val displayName = stream.title ?: stream.name ?: ""
                    val extractedTitle = buildExtractedTitle(extractSpecs(displayName))
                    val fullTitle = "$extractedTitle$displayName"

                    val sizeInfo = when {
                        fullTitle.contains("\uD83D\uDCBE") && fullTitle.contains("\uD83D\uDC64") -> {
                            val sizeIdx = fullTitle.indexOf("\uD83D\uDCBE")
                            val userIdx = fullTitle.indexOf("\uD83D\uDC64")
                            if (sizeIdx >= userIdx) fullTitle.substringAfter("\uD83D\uDC64")
                            else fullTitle.substringAfter("\uD83D\uDCBE")
                        }
                        fullTitle.contains("\uD83D\uDCBE") -> fullTitle.substringAfter("\uD83D\uDCBE")
                        fullTitle.contains("\uD83D\uDC64") -> fullTitle.substringAfter("\uD83D\uDC64")
                        fullTitle.contains("Name: ") -> fullTitle.substringBefore("Size")
                        else -> extractedTitle
                    }

                    callback.invoke(
                        newExtractorLink(
                            source = providerName,
                            name = sizeInfo.ifBlank { extractedTitle },
                            url = magnet,
                            type = ExtractorLinkType.MAGNET
                        ) {
                            this.quality = getQuality(listOf(displayName))
                        }
                    )
                }

                else -> {
                    Log.w("StremioUtil", "Stream has no recognizable URL/ytId/externalUrl/infoHash: $streamTitle")
                }
            }
        } catch (e: Exception) {
            Log.e("StremioUtil", "Error processing stream '$streamTitle': ${e.message}")
        }

        // Handle subtitles in streams
        stream.subtitles?.forEach { sub ->
            if (sub.url != null && sub.lang != null) {
                subtitleCallback.invoke(SubtitleFile(sub.lang, sub.url))
            }
        }
    }
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

    // Extract size
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

// Data classes used by invokeMainSource
data class Stream(
    val name: String? = null,
    val title: String? = null,
    val url: String? = null,
    val description: String? = null,
    val ytId: String? = null,
    val externalUrl: String? = null,
    val behaviorHints: BehaviorHints? = null,
    val infoHash: String? = null,
    val sources: List<String> = emptyList(),
    val subtitles: List<Subtitle>? = emptyList()
)

data class StreamsResponse(
    val streams: List<Stream>? = null
)

data class BehaviorHints(
    val proxyHeaders: ProxyHeaders? = null,
    val headers: Map<String, String>? = null
)

data class ProxyHeaders(
    val request: Map<String, String>? = null
)

data class Subtitle(
    val url: String? = null,
    val lang: String? = null,
    val id: String? = null
)

/**
 * Fix title by removing common Stremio prefixes/suffixes
 */
fun fixTitle(title: String): String {
    return title
        .replace(Regex("^\\[.*?]\\s*"), "")
        .replace(Regex("\\s*\\[.*?]$"), "")
        .trim()
}
