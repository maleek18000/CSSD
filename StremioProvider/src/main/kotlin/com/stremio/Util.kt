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
import java.net.URI
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

    // Construct stream URL - matching original: $baseUrl/stream/$type/$id.json
    // For series with season/episode, the ID should be: imdbId:season:episode
    val streamUrl = if (season != null && episode != null) {
        "$fixedUrl/stream/series/$imdbId:$season:$episode.json"
    } else if (season != null) {
        "$fixedUrl/stream/series/$imdbId:$season:1.json"
    } else {
        "$fixedUrl/stream/movie/$imdbId.json"
    }

    Log.d("StremioUtil", "invokeMainSource URL: $streamUrl")

    try {
        val response = app.get(streamUrl, timeout = 15)
        Log.d("StremioUtil", "Response: code=${response.code}, length=${response.text.length}")

        if (!response.isSuccessful || response.text.isEmpty()) return

        // Try standard parsing first
        val streamsResponse = tryParseJson<StreamsResponse>(response.text)
        val streams = streamsResponse?.streams

        if (!streams.isNullOrEmpty()) {
            Log.d("StremioUtil", "Found ${streams.size} streams via tryParseJson")
            for (stream in streams) {
                try {
                    processStreamUtil(stream, providerName, subtitleCallback, callback)
                } catch (e: Exception) {
                    Log.e("StremioUtil", "Error processing stream: ${e.message}")
                }
            }
            return
        }

        // tryParseJson failed or returned empty - try manual JSON parsing
        Log.d("StremioUtil", "tryParseJson returned no streams, trying manual parse")
        val found = tryManualStreamParseUtil(response.text, providerName, subtitleCallback, callback)
        if (found) {
            Log.d("StremioUtil", "Found streams via manual parse")
        }

    } catch (e: Exception) {
        Log.e("StremioUtil", "URL failed: $streamUrl - ${e.message}")
    }
}

/**
 * Process a single Stream object into extractor links.
 * Matches the original working implementation's logic.
 */
private suspend fun processStreamUtil(
    stream: Stream,
    providerName: String,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) {
    // Use if-statements (not when) to process ALL matching types, matching original
    if (stream.url != null) {
        val fixedName = fixSourceName(stream.name, stream.title, stream.description)
        val qualityTitle = buildExtractedTitle(extractSpecs(fixedName))

        // Get headers from proxyHeaders.request first, then fallback to behaviorHints.headers
        val headers = stream.behaviorHints?.proxyHeaders?.request
            ?: stream.behaviorHints?.headers
            ?: emptyMap()

        callback.invoke(
            newExtractorLink(
                source = stream.name ?: "",
                name = qualityTitle,
                url = stream.url,
                type = null  // null = auto-detect (INFER_TYPE), matching original
            ) {
                this.referer = ""
                this.quality = getQuality(listOf(stream.description, stream.title, stream.name))
                this.headers = headers
            }
        )
    }

    if (stream.ytId != null) {
        loadExtractor("https://www.youtube.com/watch?v=${stream.ytId}", subtitleCallback, callback)
    }

    if (stream.externalUrl != null) {
        loadExtractor(stream.externalUrl, subtitleCallback, callback)
    }

    if (stream.infoHash != null) {
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
                type = ExtractorLinkType.TORRENT  // Original uses TORRENT, not MAGNET
            ) {
                this.referer = ""
                this.quality = getQuality(listOf(stream.description, stream.title, stream.name))
            }
        )
    }

    // Handle subtitles in streams
    stream.subtitles?.forEach { sub ->
        if (sub.url != null && sub.lang != null) {
            try {
                subtitleCallback.invoke(SubtitleFile(sub.lang, sub.url))
            } catch (e: Exception) {
                Log.e("StremioUtil", "SubtitleFile failed: ${e.message}")
            }
        }
    }
}

/**
 * Manual JSON parsing fallback - uses Jackson's JsonNode tree model
 * which is much more forgiving than data class binding.
 */
private suspend fun tryManualStreamParseUtil(
    responseText: String,
    providerName: String,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    try {
        val jsonObj = utilMapper.readTree(responseText)
        val streamsNode = jsonObj?.get("streams") ?: return false
        if (!streamsNode.isArray || streamsNode.size() == 0) return false

        var found = false
        for (streamNode in streamsNode) {
            try {
                val url = streamNode.get("url")?.asText()
                val ytId = streamNode.get("ytId")?.asText()
                val externalUrl = streamNode.get("externalUrl")?.asText()
                val infoHash = streamNode.get("infoHash")?.asText()
                val streamTitle = streamNode.get("title")?.asText() ?: streamNode.get("name")?.asText() ?: ""
                val streamName = streamNode.get("name")?.asText() ?: ""
                val streamDesc = streamNode.get("description")?.asText() ?: ""

                // Use if-statements (not when) to process ALL matching types, matching original
                if (url != null) {
                    val fixedName = fixSourceName(streamName, streamTitle, streamDesc)
                    val qualityTitle = buildExtractedTitle(extractSpecs(fixedName))

                    // Get headers from proxyHeaders or behaviorHints.headers (as Map<String, String>)
                    val headers = try {
                        streamNode.get("behaviorHints")
                            ?.get("proxyHeaders")?.get("request")
                            ?.fields()?.asSequence()
                            ?.associate { it.key to it.value.asText() }
                        ?: streamNode.get("behaviorHints")
                            ?.get("headers")
                            ?.fields()?.asSequence()
                            ?.associate { it.key to it.value.asText() }
                        ?: emptyMap()
                    } catch (_: Exception) {
                        emptyMap()
                    }

                    callback.invoke(
                        newExtractorLink(
                            source = streamName.ifBlank { streamTitle },
                            name = qualityTitle.ifBlank { streamTitle },
                            url = url,
                            type = null  // null = auto-detect, matching original
                        ) {
                            this.referer = ""
                            this.quality = getQuality(listOf(streamDesc, streamTitle, streamName))
                            this.headers = headers
                        }
                    )
                    found = true
                }
                if (ytId != null) {
                    loadExtractor("https://www.youtube.com/watch?v=$ytId", subtitleCallback, callback)
                    found = true
                }
                if (externalUrl != null) {
                    loadExtractor(externalUrl, subtitleCallback, callback)
                    found = true
                }
                if (infoHash != null) {
                    val magnet = generateMagnetLink(infoHash)
                    callback.invoke(
                        newExtractorLink(
                            source = providerName,
                            name = streamTitle.ifBlank { streamName },
                            url = magnet,
                            type = ExtractorLinkType.TORRENT  // Original uses TORRENT
                        ) {
                            this.referer = ""
                            this.quality = getQualityFromName(streamTitle)
                        }
                    )
                    found = true
                }

                // Handle subtitles
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
                Log.e("StremioUtil", "Manual parse failed for one stream: ${e.message}")
            }
        }
        return found
    } catch (e: Exception) {
        Log.e("StremioUtil", "Manual JSON parse completely failed: ${e.message}")
        return false
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
