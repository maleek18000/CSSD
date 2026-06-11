package com.stremio

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.AcraApplication
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.SubtitleHelper
import java.time.LocalDate
import java.time.format.DateTimeFormatter

const val stremioAPI = "https://opensubtitles-v3.strem.io"
const val stremio2API = "https://3b4bbf5252c4-aio-streaming.baby-beamup.club/stremio/languages=vietnamese"
const val opensubAPI = "https://rest.opensubtitles.org"
const val subsourceAPI = "https://api.subsource.net"
const val watchSomuchAPI = "https://watchsomuch.tv"
const val xemphimAPI = "https://phimnew.com"

var responseSearch = ""

val xemphimSearchAPI: String
    get() = "https://phimnew.com/b/suggestions/titles-${LocalDate.now().format(DateTimeFormatter.ISO_DATE)}.js"

suspend fun invokeStremio(
    imdbId: String?,
    season: Int?,
    episode: Int?,
    subtitleCallback: (SubtitleFile) -> Unit
) {
    if (imdbId == null) return

    val (seasonSlug, episodeSlug) = getEpisodeSlug(season, episode)
    val url = if (season != null) {
        "$stremioAPI/subtitles/$imdbId:$seasonSlug:$episodeSlug.json"
    } else {
        "$stremioAPI/subtitles/$imdbId.json"
    }

    try {
        val response = app.get(url).text
        val subs = tryParseJson<StremioSubtitleResponse>(response)?.subtitles ?: return
        for (sub in subs) {
            if (sub.url != null && sub.lang != null) {
                subtitleCallback.invoke(
                    SubtitleFile(
                        SubtitleHelper.fromTwoLettersToLanguage(sub.lang_code ?: sub.lang) ?: sub.lang,
                        sub.url
                    )
                )
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

suspend fun invokeOpensub(
    imdbId: String?,
    season: Int?,
    episode: Int?,
    subtitleCallback: (SubtitleFile) -> Unit
) {
    if (imdbId == null) return

    val imdbIdNum = imdbId.removePrefix("tt").toIntOrNull() ?: return
    val headers = mapOf("User-Agent" to "CloudStream")

    try {
        val url = if (season != null) {
            "$opensubAPI/subtitles/imdbid-$imdbIdNum/season-$season/episode-$episode"
        } else {
            "$opensubAPI/subtitles/imdbid-$imdbIdNum"
        }

        val response = app.get(url, headers = headers).text
        val subs = tryParseJson<List<OpensubSubtitle>>(response) ?: return
        for (sub in subs) {
            val lang = sub.LanguageName ?: continue
            val subUrl = sub.SubDownloadLink ?: continue
            subtitleCallback.invoke(
                SubtitleFile(lang, subUrl.replace(".srt.gz", ".srt"))
            )
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

suspend fun invokeSubsource(
    title: String?,
    imdbId: String?,
    season: Int?,
    episode: Int?,
    subtitleCallback: (SubtitleFile) -> Unit
) {
    if (title == null) return

    try {
        // Search for the movie/show
        val searchUrl = "$subsourceAPI/api/search?query=${title.encodeUri()}"
        val searchResponse = app.get(searchUrl).text
        val searchResult = tryParseJson<SubsourceSearch>(searchResponse) ?: return
        val movie = searchResult.found?.firstOrNull() ?: return

        // Get subtitles for the movie
        val subUrl = "$subsourceAPI/api/getSubs?movie=${movie.linkName}"
        val subResponse = app.get(subUrl).text
        val subResult = tryParseJson<SubsourceResult>(subResponse) ?: return
        val subs = subResult.subs ?: return

        for (sub in subs) {
            val lang = sub.lang ?: sub.langName ?: continue
            val link = sub.subLink ?: continue
            subtitleCallback.invoke(
                SubtitleFile(lang, "$subsourceAPI/api/downloadSub?subLink=$link")
            )
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

suspend fun invokeWatchsomuch(
    imdbId: String?,
    season: Int?,
    episode: Int?,
    subtitleCallback: (SubtitleFile) -> Unit
) {
    if (imdbId == null) return

    try {
        val movieIdUrl = "$watchSomuchAPI/Movie/ByImdb?imdbId=$imdbId"
        val movieResponse = app.get(movieIdUrl).text
        val movieResult = tryParseJson<WatchsomuchResponses>(movieResponse) ?: return
        val torrents = movieResult.movie?.torrents ?: return

        val matchingTorrent = if (season != null) {
            torrents.firstOrNull { it.season == season && it.episode == episode }
                ?: torrents.firstOrNull { it.season == season }
        } else {
            torrents.firstOrNull()
        } ?: return

        val torrentId = matchingTorrent.id ?: return
        val subUrl = "$watchSomuchAPI/Subtitle/Search?torrentId=$torrentId"
        val subResponse = app.get(subUrl).text
        val subResult = tryParseJson<WatchsomuchSubResponses>(subResponse) ?: return

        for (sub in subResult.subtitles ?: return) {
            val label = sub.label ?: continue
            val url = sub.url ?: continue
            subtitleCallback.invoke(SubtitleFile(label, url))
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

suspend fun invokeXemphim(
    title: String?,
    year: Int?,
    isAnime: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit
) {
    if (title == null) return

    try {
        val slug = createSlug(title) ?: return
        val url = "$xemphimAPI/phim/$slug"
        val doc = app.get(url).document

        val subElements = doc.select("track[kind=subtitles]")
        for (element in subElements) {
            val src = element.attr("src")
            val label = element.attr("label")
            if (src.isNotEmpty() && label.isNotEmpty()) {
                subtitleCallback.invoke(SubtitleFile(label, src))
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

// ==================== Data Classes ====================

data class StremioSubtitle(
    val lang_code: String? = null,
    val lang: String? = null,
    val title: String? = null,
    val url: String? = null
)

data class StremioSubtitleResponse(
    val subtitles: List<StremioSubtitle> = emptyList()
)

data class OpensubSubtitle(
    val IDSubMovie: String? = null,
    val LanguageName: String? = null,
    val SubDownloadLink: String? = null,
    val SubFileName: String? = null
)

data class SubsourceSearch(
    val found: List<SubsourceFound>? = null
)

data class SubsourceFound(
    val linkName: String? = null,
    val movieName: String? = null
)

data class SubsourceResult(
    val subs: List<SubsourceSubtitles>? = null
)

data class SubsourceSubtitles(
    val lang: String? = null,
    val langName: String? = null,
    val subLink: String? = null
)

data class WatchsomuchResponses(
    val movie: WatchsomuchMovies? = null
)

data class WatchsomuchMovies(
    val torrents: ArrayList<WatchsomuchTorrents>? = ArrayList()
)

data class WatchsomuchTorrents(
    val id: Int? = null,
    val movieId: Int? = null,
    val season: Int? = null,
    val episode: Int? = null
)

data class WatchsomuchSubResponses(
    val subtitles: ArrayList<WatchsomuchSubtitles>? = ArrayList()
)

data class WatchsomuchSubtitles(
    val url: String? = null,
    val label: String? = null
)
