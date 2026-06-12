package com.stremio

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Interceptor

class StremioX(
    override var mainUrl: String,
    override var name: String
) : MainAPI() {

    companion object {
        private const val apiKey = "8ff0f5d3eb22a8130a33808a70688dce"
        private const val tmdbAPI = "https://api.themoviedb.org/3"
        var language = ""

        init {
            try {
                val context = CloudStreamApp.context
                if (context != null) {
                    val prefs = context.getSharedPreferences("stremio_prefs", 0)
                    val localeStr = prefs.getString("app_locale", null)
                    if (localeStr != null) {
                        language = if (localeStr == "vi") "vi" else "en"
                    }
                }
            } catch (e: Exception) {}
            if (language.isEmpty()) language = "vi"
        }
    }

    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "vi"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.Torrent
    )

    override val mainPage = mainPageOf(
        "$tmdbAPI/trending/all/day?api_key=$apiKey&language=$language" to "Trending",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_networks=213&language=$language" to "Netflix",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_networks=1024&language=$language" to "Amazon",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_networks=2739&language=$language" to "Disney+",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_networks=453&language=$language" to "Hulu",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_networks=2552&language=$language" to "Apple TV+",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_networks=49&language=$language" to "HBO",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_networks=4330&language=$language" to "Paramount+",
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_networks=3353&language=$language" to "Peacock"
    )

    init {
        try {
            val context = CloudStreamApp.context
            if (context != null) {
                val prefs = context.getSharedPreferences("stremio_prefs", 0)
                val localeStr = prefs.getString("app_locale", null)
                if (localeStr != null) {
                    lang = localeStr
                }
            }
        } catch (e: Exception) {}
        if (lang.isEmpty()) lang = "vi"
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val type = if (request.data.contains("/movie")) "movie" else "tv"
        val url = "${request.data}&without_keywords=190370|13059|226161|195669&page=$page"

        return try {
            val res = tryParseJson<Results>(app.get(url).text) ?: return newHomePageResponse(request.name, emptyList())
            val items = res.results?.mapNotNull { it.toSearchResponse(this, type) } ?: emptyList()
            newHomePageResponse(request.name, items)
        } catch (e: Exception) {
            newHomePageResponse(request.name, emptyList())
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? {
        return search(query, 1)?.items
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val url = "$tmdbAPI/search/multi?api_key=$apiKey&language=$language&query=$query&page=$page"
        val res = tryParseJson<Results>(app.get(url).text) ?: return null
        val items = res.results?.mapNotNull { it.toSearchResponse(this) } ?: return null
        return newSearchResponseList(items, items.isNotEmpty())
    }

    private fun Media.toSearchResponse(provider: StremioX, mediaType: String? = null): SearchResponse? {
        val date = releaseDate?.ifBlank { null } ?: firstAirDate
        val year = date?.split("-")?.firstOrNull()?.toIntOrNull()

        if (posterPath == null) return null

        val originalLang = originalLanguage
        if (originalLang in listOf("zh", "pt") && year != null && year > 1980) {
            val genreIds = genreIds
            if (genreIds != null && genreIds.containsAll(listOf(99, 10764, 10767).map { it })) {
                return null
            }
        }

        val title = title ?: name ?: originalTitle ?: return null
        val displayName = capitalizeTitle(title)
        val id = id ?: return null
        val type = mediaType ?: this.mediaType

        return provider.newMovieSearchResponse(
            displayName,
            Data(id, type).toJson(),
            TvType.Others
        ) {
            posterUrl = getImageUrl(posterPath)
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val data = mapper.readValue(url, Data::class.java)
        val type = getType(data.type)
        val isTv = type != TvType.Movie

        val detailUrl = if (isTv) {
            "$tmdbAPI/tv/${data.id}?api_key=$apiKey&language=$language&append_to_response=credits,recommendations"
        } else {
            "$tmdbAPI/movie/${data.id}?api_key=$apiKey&language=$language&append_to_response=credits,recommendations"
        }

        val englishUrl = if (isTv) {
            "$tmdbAPI/tv/${data.id}?api_key=$apiKey&language=en&append_to_response=external_ids,credits,videos"
        } else {
            "$tmdbAPI/movie/${data.id}?api_key=$apiKey&language=en&append_to_response=external_ids,credits,videos"
        }

        val localDetail = tryParseJson<MediaDetail>(app.get(detailUrl).text)
            ?: throw ErrorLoadingException("Invalid Json Response")

        val englishDetail = tryParseJson<MediaDetail>(app.get(englishUrl).text)
            ?: throw ErrorLoadingException("Invalid Json Response")

        var title = localDetail.title ?: localDetail.name ?: return null
        val englishTitle = englishDetail.title ?: englishDetail.name ?: return null
        val originalTitle = englishDetail.originalTitle ?: englishDetail.originalName ?: return null

        if (title == originalTitle) {
            title = englishTitle
        }

        val poster = getOriImageUrl(localDetail.posterPath)
        val bg = getOriImageUrl(localDetail.backdropPath)

        val dateStr = englishDetail.releaseDate ?: englishDetail.firstAirDate
        val year = dateStr?.split("-")?.firstOrNull()?.toIntOrNull()

        val genres = localDetail.genres?.mapNotNull {
            it.name?.substringAfter("Phim")?.trim()
        } ?: emptyList()

        val isAnime = genres.contains("Hoat Hinh") &&
            (englishDetail.original_language in listOf("zh", "ja"))

        val isAsian = when {
            isAnime -> false
            englishDetail.original_language in listOf("zh", "ko", "th") -> true
            else -> false
        }

        val tvType = when {
            isAnime -> TvType.Anime
            isTv -> TvType.TvSeries
            else -> TvType.Movie
        }

        val imdbId = englishDetail.external_ids?.imdb_id
        val tvdbId = englishDetail.external_ids?.tvdb_id

        if (!isTv) {
            val linkData = LinkData(
                id = data.id,
                imdbId = imdbId,
                tvdbId = tvdbId,
                type = data.type,
                title = title,
                year = year,
                orgTitle = originalTitle,
                isAnime = isAnime,
                isAsian = isAsian,
                airedDate = englishDetail.releaseDate,
                originalLanguage = englishDetail.original_language
            )
            return newMovieLoadResponse(title, url, tvType, linkData.toJson()) {
                posterUrl = poster
                this.plot = localDetail.overview
                this.tags = genres
            }
        } else {
            val baseLinkData = LinkData(
                id = data.id,
                imdbId = imdbId,
                tvdbId = tvdbId,
                type = data.type,
                title = title,
                year = year,
                orgTitle = originalTitle,
                isAnime = isAnime,
                isAsian = isAsian,
                airedDate = englishDetail.firstAirDate,
                originalLanguage = englishDetail.original_language,
                lastSeason = englishDetail.seasons?.lastOrNull()?.seasonNumber
            )

            val episodes = englishDetail.seasons?.map { seasonData ->
                val seasonEps = seasonData.episodes?.map { ep ->
                    val epLinkData = baseLinkData.copy(
                        season = ep.seasonNumber ?: seasonData.seasonNumber,
                        episode = ep.episodeNumber
                    )
                    val epName = "${ep.name}${ep.episodeNumber?.let { " - E$it" } ?: ""}"
                    newEpisode(epLinkData.toJson()) {
                        name = epName
                        season = ep.seasonNumber ?: seasonData.seasonNumber
                        episode = ep.episodeNumber
                        posterUrl = getImageUrl(ep.stillPath)
                        description = ep.overview
                    }
                } ?: emptyList()

                val seasonEpisodes = if (seasonEps.isEmpty()) {
                    val seasonUrl = "$tmdbAPI/tv/${data.id}/season/${seasonData.seasonNumber}?api_key=$apiKey&language=$language"
                    val seasonDetail = tryParseJson<MediaDetail>(app.get(seasonUrl).text)
                    seasonDetail?.episodes?.map { ep ->
                        val epLinkData = baseLinkData.copy(
                            season = ep.seasonNumber ?: seasonData.seasonNumber,
                            episode = ep.episodeNumber
                        )
                        val epName = "${ep.name}${ep.episodeNumber?.let { " - E$it" } ?: ""}"
                        newEpisode(epLinkData.toJson()) {
                            name = epName
                            season = ep.seasonNumber ?: seasonData.seasonNumber
                            episode = ep.episodeNumber
                            posterUrl = getImageUrl(ep.stillPath)
                            description = ep.overview
                        }
                    } ?: emptyList()
                } else seasonEps

                seasonEpisodes
            }?.flatten() ?: emptyList()

            return newTvSeriesLoadResponse(title, url, tvType, episodes) {
                posterUrl = poster
                this.plot = localDetail.overview
                this.tags = genres
                this.showStatus = getStatus(englishDetail.status)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("StremioX", "=== loadLinks START ===")
        Log.d("StremioX", "mainUrl=$mainUrl")
        Log.d("StremioX", "raw data=$data")

        val linkData = try {
            mapper.readValue(data, LinkData::class.java)
        } catch (e: Exception) {
            Log.e("StremioX", "Failed to parse LinkData: $data", e)
            return false
        }

        Log.d("StremioX", "Parsed LinkData: imdbId=${linkData.imdbId}, id=${linkData.id}, season=${linkData.season}, episode=${linkData.episode}")

        // Set subtitle auto-select language
        try {
            val context = CloudStreamApp.context
            val subsAutoSelect = try {
                val prefs = context?.getSharedPreferences("stremio_prefs", 0)
                prefs?.getString("app_locale", null)
            } catch (e: Exception) { null }
            if (subsAutoSelect != null) {
                AcraApplication.setKey("subs_auto_select", subsAutoSelect)
            }
        } catch (_: Exception) {}

        val streamId = linkData.imdbId ?: linkData.id?.toString() ?: ""

        // Try the configured addon first
        try {
            invokeMainSource(mainUrl, name, streamId, linkData.season, linkData.episode, subtitleCallback, callback)
        } catch (e: Exception) {
            Log.e("StremioX", "invokeMainSource failed for $mainUrl: ${e.message}")
        }

        // If imdbId exists, also try torrentio as a backup
        if (linkData.imdbId != null) {
            try {
                invokeMainSource("https://torrentio.strem.fun", name, linkData.imdbId, linkData.season, linkData.episode, subtitleCallback, callback)
            } catch (e: Exception) {
                Log.e("StremioX", "Torrentio fallback failed: ${e.message}")
            }
        }

        // Load subtitles with timeout protection
        try {
            coroutineScope {
                listOf(
                    async(Dispatchers.IO) {
                        try {
                            withTimeoutOrNull(8_000L) {
                                invokeStremio(linkData.imdbId, linkData.season, linkData.episode, subtitleCallback)
                            }
                        } catch (_: Exception) {}
                    },
                    async(Dispatchers.IO) {
                        try {
                            withTimeoutOrNull(8_000L) {
                                invokeOpensub(linkData.imdbId, linkData.season, linkData.episode, subtitleCallback)
                            }
                        } catch (_: Exception) {}
                    },
                    async(Dispatchers.IO) {
                        try {
                            withTimeoutOrNull(8_000L) {
                                invokeSubsource(linkData.title, linkData.imdbId, linkData.season, linkData.episode, subtitleCallback)
                            }
                        } catch (_: Exception) {}
                    },
                    async(Dispatchers.IO) {
                        try {
                            withTimeoutOrNull(8_000L) {
                                invokeWatchsomuch(linkData.imdbId, linkData.season, linkData.episode, subtitleCallback)
                            }
                        } catch (_: Exception) {}
                    },
                    async(Dispatchers.IO) {
                        try {
                            withTimeoutOrNull(8_000L) {
                                invokeXemphim(linkData.title, linkData.year, linkData.isAnime, subtitleCallback)
                            }
                        } catch (_: Exception) {}
                    }
                ).awaitAll()
            }
        } catch (e: Exception) {
            Log.e("StremioX", "Subtitle loading failed: ${e.message}")
        }

        Log.d("StremioX", "=== loadLinks END ===")
        return true
    }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? = null

    // ==================== Data Classes ====================

    data class Data(
        val id: Int? = null,
        val type: String? = null,
        val season: Int? = null,
        val episode: Int? = null
    )

    data class Media(
        val id: Int? = null,
        val name: String? = null,
        val title: String? = null,
        val originalTitle: String? = null,
        val mediaType: String? = null,
        val posterPath: String? = null,
        val releaseDate: String? = null,
        val firstAirDate: String? = null,
        val originalLanguage: String? = null,
        val genreIds: ArrayList<Int>? = null,
        val voteAverage: Double? = null
    )

    data class MediaDetail(
        val id: Int? = null,
        val imdbId: String? = null,
        val title: String? = null,
        val name: String? = null,
        val originalTitle: String? = null,
        val originalName: String? = null,
        val posterPath: String? = null,
        val backdropPath: String? = null,
        val releaseDate: String? = null,
        val firstAirDate: String? = null,
        val overview: String? = null,
        val runtime: Int? = null,
        val vote_average: Any? = null,
        val original_language: String? = null,
        val status: String? = null,
        val genres: ArrayList<Genres>? = null,
        val keywords: KeywordResults? = null,
        val last_episode_to_air: LastEpisodeToAir? = null,
        val seasons: ArrayList<Season>? = null,
        val videos: ResultsTrailer? = null,
        val external_ids: ExternalIds? = null,
        val credits: Credits? = null,
        val recommendations: ResultsRecommendations? = null,
        val alternative_titles: ResultsAltTitles? = null,
        val production_countries: ArrayList<ProductionCountry>? = null,
        val episodes: ArrayList<Episode>? = null
    )

    data class Genres(
        val id: Int? = null,
        val name: String? = null
    )

    data class KeywordResults(
        val results: ArrayList<Keyword>? = ArrayList(),
        val keywords: ArrayList<Keyword>? = ArrayList()
    )

    data class Keyword(
        val id: Int? = null,
        val name: String? = null
    )

    data class LastEpisodeToAir(
        val id: Int? = null,
        val name: String? = null,
        val overview: String? = null,
        val episodeNumber: Int? = null,
        val seasonNumber: Int? = null,
        val stillPath: String? = null,
        val voteAverage: Double? = null,
        val airDate: String? = null
    )

    data class Season(
        val id: Int? = null,
        val name: String? = null,
        val seasonNumber: Int? = null,
        val overview: String? = null,
        val posterPath: String? = null,
        val episodeCount: Int? = null,
        val episodes: ArrayList<Episode>? = null
    )

    data class Episode(
        val id: String? = null,
        val name: String? = null,
        val overview: String? = null,
        val episodeNumber: Int? = null,
        val seasonNumber: Int? = null,
        val stillPath: String? = null,
        val voteAverage: Double? = null,
        val airDate: String? = null
    )

    data class ResultsTrailer(
        val results: ArrayList<Trailer>? = ArrayList()
    )

    data class Trailer(
        val id: String? = null,
        val key: String? = null,
        val name: String? = null,
        val site: String? = null,
        val type: String? = null
    )

    data class ExternalIds(
        val imdb_id: String? = null,
        val tvdb_id: Int? = null,
        val wikidata_id: String? = null,
        val facebook_id: String? = null,
        val instagram_id: String? = null,
        val twitter_id: String? = null
    )

    data class Credits(
        val cast: ArrayList<Cast>? = null,
        val crew: ArrayList<Crew>? = null
    )

    data class Cast(
        val id: Int? = null,
        val name: String? = null,
        val originalName: String? = null,
        val character: String? = null,
        val profilePath: String? = null,
        val order: Int? = null
    )

    data class Crew(
        val id: Int? = null,
        val name: String? = null,
        val originalName: String? = null,
        val job: String? = null,
        val department: String? = null,
        val profilePath: String? = null
    )

    data class ResultsRecommendations(
        val results: ArrayList<Media>? = null
    )

    data class ResultsAltTitles(
        val titles: ArrayList<AltTitle>? = null
    )

    data class AltTitle(
        val iso31661: String? = null,
        val title: String? = null,
        val type: String? = null
    )

    data class ProductionCountry(
        val iso31661: String? = null,
        val name: String? = null
    )

    data class Results(
        val results: ArrayList<Media>? = ArrayList()
    )

    data class Episodes(
        val name: String? = null,
        val seasonNumber: Int? = null,
        val episodeNumber: Int? = null,
        val stillPath: String? = null,
        val voteAverage: Double? = null,
        val overview: String? = null
    )
}
