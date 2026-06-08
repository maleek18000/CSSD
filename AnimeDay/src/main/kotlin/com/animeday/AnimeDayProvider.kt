package com.animeday

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLDecoder
import java.net.URLEncoder

// ============================================================
// Data Classes
// ============================================================

// SR Server (Stardima) models
data class SRCatalogResponse(
    val data: List<SRCatalogItem>? = null,
    val total: Int? = null,
    val page: Int? = null,
    @JsonProperty("perPage") val perPage: Int? = null
)

data class SRCatalogItem(
    val id: Int? = null,
    val name: String? = null,
    val image: String? = null,
    val slug: String? = null,
    @JsonProperty("secondary_name") val secondaryName: String? = null,
    @JsonProperty("translate_type") val translateType: String? = null,
    @JsonProperty("id_page") val idPage: String? = null
)

data class SRWPMedia(
    val id: Int? = null,
    val title: SRWPTitle? = null,
    @JsonProperty("repeatable_fields")
    val `repeatable-fields`: List<SRRepeatableField>? = null,
    val ids: String? = null,
    val link: String? = null,
    @JsonProperty("dt_poster") val dtPoster: String? = null,
    @JsonProperty("dt_backdrop") val dtBackdrop: String? = null,
    @JsonProperty("imdbRating") val imdbRating: String? = null,
    @JsonProperty("original_name") val originalName: String? = null,
    @JsonProperty("number_of_episodes") val numberOfEpisodes: String? = null,
    val content: SRWPContent? = null,
    // Episode-specific fields from WP REST API
    val episodio: String? = null,
    val temporada: String? = null,
    val serie: String? = null,
    @JsonProperty("episode_name") val episodeName: String? = null
) {
    fun getRepeatableFields(): List<SRRepeatableField> {
        return `repeatable-fields` ?: emptyList()
    }
}

data class SRWPTitle(val rendered: String? = null)
data class SRWPContent(val rendered: String? = null)

data class SRRepeatableField(
    val name: String? = null,
    val select: String? = null,
    val url: String? = null,
    val idioma: String? = null
)

// SA Server (AnimeSlayer) models
data class SACatalogResponse(
    val response: SAResponse? = null
)

data class SAResponse(
    @JsonProperty("meta_data") val metaData: SAMetaData? = null,
    val data: List<SACatalogItem>? = null
)

data class SAMetaData(
    @JsonProperty("_limit") val limit: String? = null,
    @JsonProperty("_offset") val offset: String? = null
)

data class SACatalogItem(
    @JsonProperty("anime_id") val animeId: String? = null,
    @JsonProperty("anime_name") val animeName: String? = null,
    @JsonProperty("anime_type") val animeType: String? = null,
    @JsonProperty("anime_status") val animeStatus: String? = null,
    @JsonProperty("anime_cover_image_url") val coverImageUrl: String? = null,
    @JsonProperty("anime_rating") val rating: String? = null,
    @JsonProperty("anime_genres") val genres: String? = null,
    @JsonProperty("anime_release_year") val releaseYear: String? = null
)

data class SAAnimeDetail(
    @JsonProperty("anime_id") val animeId: String? = null,
    @JsonProperty("anime_name") val animeName: String? = null,
    @JsonProperty("anime_type") val animeType: String? = null,
    @JsonProperty("anime_description") val description: String? = null,
    @JsonProperty("anime_cover_image_url") val coverImageUrl: String? = null,
    @JsonProperty("anime_cover_image_full_url") val coverImageFullUrl: String? = null,
    @JsonProperty("anime_banner_image_url") val bannerImageUrl: String? = null,
    @JsonProperty("anime_rating") val rating: String? = null,
    @JsonProperty("anime_genres") val genres: String? = null,
    @JsonProperty("anime_english_title") val englishTitle: String? = null,
    @JsonProperty("anime_keywords") val keywords: String? = null,
    val episodes: SAEpisodes? = null
)

data class SAEpisodes(
    val data: List<SAEpisode>? = null
)

data class SAEpisode(
    @JsonProperty("episode_id") val episodeId: String? = null,
    @JsonProperty("episode_name") val episodeName: String? = null,
    @JsonProperty("episode_number") val episodeNumber: String? = null,
    @JsonProperty("episode_urls") val episodeUrls: List<SAEpisodeUrl>? = null
)

data class SAEpisodeUrl(
    @JsonProperty("episode_url_id") val urlId: String? = null,
    @JsonProperty("episode_server_id") val serverId: String? = null,
    @JsonProperty("episode_server_name") val serverName: String? = null,
    @JsonProperty("episode_url") val url: String? = null
)

// JO Server (AnimeWitcher) / BO Server (MovieWitcher) - Algolia models
data class AlgoliaResponse(
    val hits: List<AlgoliaHit>? = null,
    @JsonProperty("nbHits") val nbHits: Int? = null,
    val page: Int? = null,
    @JsonProperty("nbPages") val nbPages: Int? = null
)

data class AlgoliaHit(
    @JsonProperty("objectID") val objectID: String? = null,
    val name: String? = null,
    val type: String? = null,
    val poster: String? = null,
    @JsonProperty("poster_uri") val posterUri: String? = null,
    val story: String? = null,
    val tags: List<String>? = null,
    @JsonProperty("cover_uri") val coverUri: String? = null,
    val rating: String? = null,
    val details: String? = null
)

// Firestore models for JO/BO
data class FirestoreDocument(
    val name: String? = null,
    val fields: Map<String, FirestoreValue>? = null
)

data class FirestoreValue(
    val stringValue: String? = null,
    val integerValue: String? = null,
    val booleanValue: Boolean? = null,
    val mapValue: FirestoreMapValue? = null,
    val arrayValue: FirestoreArrayValue? = null
)

data class FirestoreMapValue(val fields: Map<String, FirestoreValue>? = null)
data class FirestoreArrayValue(val values: List<FirestoreValue>? = null)

// ============================================================
// Provider
// ============================================================

class AnimeDayProvider : MainAPI() {
    override var mainUrl = "https://anime-day.com"
    override var name = "Anime Day"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(
        TvType.Anime, TvType.AnimeMovie, TvType.Cartoon, TvType.TvSeries, TvType.Movie
    )

    // SR Server config
    companion object {
        const val SR_BASE = "https://anime-day.com"
        const val SR_WP_BASE = "https://ap45.wiib.top"
        const val SR_AUTH = "Bearer tok3n-MyApp-987654321"
        const val SR_IMG_BASE = "https://image.tmdb.org/t/p/w780"

        // SA Server config
        const val SA_BASE = "https://anslayer.com"
        const val SA_CLIENT_ID = "android-app2"
        const val SA_CLIENT_SECRET = "7befba6263cc14c90d2f1d6da2c5cf9b251bfbbd"

        // JO Server config (AnimeWitcher)
        const val JO_ALGOLIA_APP_ID = "8VREWC6S4T"
        const val JO_ALGOLIA_API_KEY = "7a6d050dcc5fc37edd98a7f9e2d5a223"
        const val JO_ALGOLIA_URL = "https://8VREWC6S4T-3.algolianet.com"
        const val JO_FIRESTORE_URL = "https://firestore.googleapis.com/v1/projects/animewitcher-1c66d/databases/(default)/documents"

        // BO Server config (MovieWitcher)
        const val BO_ALGOLIA_APP_ID = "V67NZNF3RR"
        const val BO_ALGOLIA_API_KEY = "2a0e44dbb2b46865f88fd584d154d0bd"
        const val BO_ALGOLIA_URL = "https://V67NZNF3RR-dsn.algolia.net"
        const val BO_FIRESTORE_URL = "https://firestore.googleapis.com/v1/projects/moviewitcher-133f3/databases/(default)/documents"

        // Custom URL schemes for routing between methods
        // Must use https:// so CloudStream doesn't prepend mainUrl
        const val SCHEME = "https://animeday.app/"
        const val SR_MOVIE = "sr/movie/"
        const val SR_SERIES = "sr/series/"
        const val SR_EPISODE = "sr/episode/"
        const val SA_ANIME = "sa/anime/"
        const val SA_EPISODE = "sa/episode/"
        const val JO_ANIME = "jo/anime/"
        const val BO_MOVIE = "bo/movie/"
    }

    // ============================================================
    // Main Page
    // ============================================================
    override val mainPage = mainPageOf(
        "$SR_BASE/app/stardima.php?&translate_type=sub" to "SR: أفلام مترجمة",
        "$SR_BASE/app/stardima.php?&translate_type=dub" to "SR: مسلسلات مدبلجة",
        "$SA_BASE/anime/public/animes/get-published-animes?json={\"_offset\":0,\"_limit\":21,\"order_by\":\"latest_first\",\"list_type\":\"filter\",\"anime_name\":\"\",\"just_info\":\"Yes\",\"anime_status\":\"Finished Airing\",\"anime_type\":\"TV\"}" to "SA: مسلسلات أنمي",
        "$SA_BASE/anime/public/animes/get-published-animes?json={\"_offset\":0,\"_limit\":21,\"order_by\":\"latest_first\",\"list_type\":\"filter\",\"anime_name\":\"\",\"just_info\":\"Yes\",\"anime_status\":\"Finished Airing\",\"anime_type\":\"Movie\"}" to "SA: أفلام أنمي",
        "$JO_ALGOLIA_URL/1/indexes/recent/query" to "JO: أحدث الأنمي",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data
        val items = try {
            when {
                url.startsWith(SR_BASE) -> getSRMainPage(url, page)
                url.startsWith(SA_BASE) -> getSAMainPage(url, page)
                url.startsWith(JO_ALGOLIA_URL) -> getJOMainPage(url, page)
                url.startsWith(BO_ALGOLIA_URL) -> getBOMainPage(url, page)
                else -> emptyList()
            }
        } catch (e: Exception) {
            // Don't let one server failure crash the entire home page
            emptyList()
        }
        return newHomePageResponse(request.name, items, hasNext = items.size >= 21)
    }

    // ============================================================
    // SR Server: Main Page & Search
    // ============================================================
    private suspend fun getSRMainPage(url: String, page: Int): List<SearchResponse> {
        val pageUrl = if (page > 1) "$url&page=$page" else url
        val res = app.get(pageUrl).parsedSafe<SRCatalogResponse>() ?: return emptyList()
        return res.data?.mapNotNull { item ->
            val name = item.name ?: return@mapNotNull null
            val idPage = item.idPage ?: return@mapNotNull null
            val isMovie = item.slug?.contains("/movies/") == true
            val poster = item.image?.let {
                if (it.startsWith("http")) it else "${SR_IMG_BASE}$it"
            }
            val link = SCHEME + (if (isMovie) SR_MOVIE else SR_SERIES) + idPage
            if (isMovie) {
                newMovieSearchResponse(name, link) {
                    this.posterUrl = poster
                }
            } else {
                newTvSeriesSearchResponse(name, link) {
                    this.posterUrl = poster
                }
            }
        } ?: emptyList()
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()

        // Search SR server
        try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val srRes = app.get("$SR_BASE/app/stardima.php?&secondary_name=$encoded")
                .parsedSafe<SRCatalogResponse>()
            srRes?.data?.forEach { item ->
                val name = item.name ?: return@forEach
                val idPage = item.idPage ?: return@forEach
                val isMovie = item.slug?.contains("/movies/") == true
                val poster = item.image?.let {
                    if (it.startsWith("http")) it else "${SR_IMG_BASE}$it"
                }
                val link = SCHEME + (if (isMovie) SR_MOVIE else SR_SERIES) + idPage
                results.add(
                    if (isMovie) {
                        newMovieSearchResponse(name, link) { this.posterUrl = poster }
                    } else {
                        newTvSeriesSearchResponse(name, link) { this.posterUrl = poster }
                    }
                )
            }
        } catch (_: Exception) {}

        // Search SA server
        try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val saJson = """{"_offset":0,"_limit":42,"order_by":"earliest_first","list_type":"filter","anime_name":"$encoded","just_info":"Yes"}"""
            val saRes = app.get(
                "$SA_BASE/anime/public/animes/get-published-animes?json=${URLEncoder.encode(saJson, "UTF-8")}",
                headers = mapOf(
                    "Client-Id" to SA_CLIENT_ID,
                    "Client-Secret" to SA_CLIENT_SECRET
                )
            ).parsedSafe<SACatalogResponse>()
            saRes?.response?.data?.forEach { item ->
                val name = item.animeName ?: return@forEach
                val animeId = item.animeId ?: return@forEach
                val poster = item.coverImageUrl
                val link = SCHEME + SA_ANIME + animeId
                val isMovie = item.animeType == "Movie"
                results.add(
                    if (isMovie) {
                        newMovieSearchResponse(name, link) { this.posterUrl = poster }
                    } else {
                        newTvSeriesSearchResponse(name, link) { this.posterUrl = poster }
                    }
                )
            }
        } catch (_: Exception) {}

        // Search JO server
        try {
            val joRes = app.post(
                "$JO_ALGOLIA_URL/1/indexes/series/query",
                headers = mapOf(
                    "X-Algolia-Application-Id" to JO_ALGOLIA_APP_ID,
                    "X-Algolia-API-Key" to JO_ALGOLIA_API_KEY,
                    "Content-Type" to "application/json"
                ),
                data = mapOf(
                    "query" to query,
                    "hitsPerPage" to "20"
                )
            ).parsedSafe<AlgoliaResponse>()
            joRes?.hits?.forEach { hit ->
                val name = hit.name ?: return@forEach
                val objectId = hit.objectID ?: return@forEach
                val poster = hit.posterUri ?: hit.poster
                val link = SCHEME + JO_ANIME + objectId
                val isMovie = hit.type == "فيلم"
                results.add(
                    if (isMovie) {
                        newMovieSearchResponse(name, link) { this.posterUrl = poster }
                    } else {
                        newTvSeriesSearchResponse(name, link) { this.posterUrl = poster }
                    }
                )
            }
        } catch (_: Exception) {}

        return results
    }

    // ============================================================
    // SA Server: Main Page
    // ============================================================
    private suspend fun getSAMainPage(url: String, page: Int): List<SearchResponse> {
        val offset = (page - 1) * 21
        // Modify the JSON to change offset
        val jsonBase = url.substringAfter("json=")
        val jsonDecoded = URLDecoder.decode(jsonBase, "UTF-8")
        val updatedJson = jsonDecoded.replace(
            """"_offset":0""",
            """"_offset":$offset"""
        )
        val fullUrl = url.substringBefore("json=") + "json=" + URLEncoder.encode(updatedJson, "UTF-8")

        val res = app.get(fullUrl, headers = mapOf(
            "Client-Id" to SA_CLIENT_ID,
            "Client-Secret" to SA_CLIENT_SECRET
        )).parsedSafe<SACatalogResponse>() ?: return emptyList()

        return res.response?.data?.mapNotNull { item ->
            val name = item.animeName ?: return@mapNotNull null
            val animeId = item.animeId ?: return@mapNotNull null
            val poster = item.coverImageUrl
            val link = SCHEME + SA_ANIME + animeId
            val isMovie = item.animeType == "Movie"
            if (isMovie) {
                newMovieSearchResponse(name, link) { this.posterUrl = poster }
            } else {
                newTvSeriesSearchResponse(name, link) { this.posterUrl = poster }
            }
        } ?: emptyList()
    }

    // ============================================================
    // JO Server: Main Page
    // ============================================================
    private suspend fun getJOMainPage(url: String, page: Int): List<SearchResponse> {
        val res = app.post(url, headers = mapOf(
            "X-Algolia-Application-Id" to JO_ALGOLIA_APP_ID,
            "X-Algolia-API-Key" to JO_ALGOLIA_API_KEY,
            "Content-Type" to "application/json"
        ), data = mapOf(
            "params" to "hitsPerPage=21&page=${page - 1}",
            "query" to ""
        )).parsedSafe<AlgoliaResponse>() ?: return emptyList()

        return res.hits?.mapNotNull { hit ->
            val name = hit.name ?: return@mapNotNull null
            val objectId = hit.objectID ?: return@mapNotNull null
            val poster = hit.posterUri ?: hit.poster
            val link = SCHEME + JO_ANIME + objectId
            val isMovie = hit.type == "فيلم"
            if (isMovie) {
                newMovieSearchResponse(name, link) { this.posterUrl = poster }
            } else {
                newTvSeriesSearchResponse(name, link) { this.posterUrl = poster }
            }
        } ?: emptyList()
    }

    // ============================================================
    // BO Server: Main Page
    // ============================================================
    private suspend fun getBOMainPage(url: String, page: Int): List<SearchResponse> {
        val res = app.post(url, headers = mapOf(
            "X-Algolia-Application-Id" to BO_ALGOLIA_APP_ID,
            "X-Algolia-API-Key" to BO_ALGOLIA_API_KEY,
            "Content-Type" to "application/json"
        ), data = mapOf(
            "params" to "hitsPerPage=21&page=${page - 1}",
            "query" to ""
        )).parsedSafe<AlgoliaResponse>() ?: return emptyList()

        return res.hits?.mapNotNull { hit ->
            val name = hit.name ?: return@mapNotNull null
            val objectId = hit.objectID ?: return@mapNotNull null
            val poster = hit.posterUri ?: hit.poster
            val link = SCHEME + BO_MOVIE + objectId
            newMovieSearchResponse(name, link) { this.posterUrl = poster }
        } ?: emptyList()
    }

    // ============================================================
    // Load (Detail Page)
    // ============================================================
    override suspend fun load(url: String): LoadResponse? {
        // Strip mainUrl prefix if CloudStream prepended it (e.g. https://anime-day.com/https://animeday.app/...)
        val cleanUrl = url.removePrefix(mainUrl).removePrefix("/")
        val fullUrl = if (cleanUrl.startsWith("https://")) cleanUrl else url

        return when {
            fullUrl.startsWith(SCHEME + SR_MOVIE) -> loadSRMovie(fullUrl.removePrefix(SCHEME + SR_MOVIE), fullUrl)
            fullUrl.startsWith(SCHEME + SR_SERIES) -> loadSRSeries(fullUrl.removePrefix(SCHEME + SR_SERIES), fullUrl)
            fullUrl.startsWith(SCHEME + SA_ANIME) -> loadSAAnime(fullUrl.removePrefix(SCHEME + SA_ANIME), fullUrl)
            fullUrl.startsWith(SCHEME + JO_ANIME) -> loadJOAnime(fullUrl.removePrefix(SCHEME + JO_ANIME), fullUrl)
            fullUrl.startsWith(SCHEME + BO_MOVIE) -> loadBOMovie(fullUrl.removePrefix(SCHEME + BO_MOVIE), fullUrl)
            else -> null
        }
    }

    // ============================================================
    // SR Server: Load Movie
    // ============================================================
    private suspend fun loadSRMovie(idPage: String, url: String): LoadResponse? {
        val res = app.get("$SR_WP_BASE/wp-json/wp/v2/movies/$idPage",
            headers = mapOf("Authorization" to SR_AUTH)
        ).parsedSafe<SRWPMedia>() ?: return null

        val title = res.title?.rendered ?: return null
        val poster = res.dtPoster?.let { "${SR_IMG_BASE}$it" }
        val bg = res.dtBackdrop?.let { "${SR_IMG_BASE}$it" }
        val plot = res.content?.rendered?.replace(Regex("<[^>]+>"), "")?.trim()
        val rating = res.imdbRating?.toFloatOrNull()

        // Collect all video sources from repeatable fields
        val videoData = res.getRepeatableFields().mapNotNull { field ->
            field.url?.let { Triple(field.name ?: "SR", field.select ?: "mp4", it) }
        }

        if (videoData.isNotEmpty()) {
            // Pass all video data as JSON in the data field
            val dataJson = videoData.map { (server, type, vidUrl) ->
                """{"server":"$server","type":"$type","url":"${vidUrl.replace("\"", "\\\"")}"}"""
            }.joinToString(",", "[", "]")

            return newMovieLoadResponse(title, url, TvType.AnimeMovie, dataJson) {
                this.posterUrl = poster
                this.backgroundPosterUrl = bg
                this.plot = plot
                this.score = Score.from10(rating)
            }
        }

        // Fallback: pass movie ID for dooplayer extraction in loadLinks
        return newMovieLoadResponse(title, url, TvType.AnimeMovie, "sr_movie:$idPage") {
            this.posterUrl = poster
            this.backgroundPosterUrl = bg
            this.plot = plot
            this.score = Score.from10(rating)
        }
    }

    // ============================================================
    // SR Server: Load Series
    // ============================================================
    private suspend fun loadSRSeries(idPage: String, url: String): LoadResponse? {
        val res = app.get("$SR_WP_BASE/wp-json/wp/v2/tvshows/$idPage",
            headers = mapOf("Authorization" to SR_AUTH)
        ).parsedSafe<SRWPMedia>() ?: return null

        val title = res.title?.rendered ?: return null
        val poster = res.dtPoster?.let { "${SR_IMG_BASE}$it" }
        val bg = res.dtBackdrop?.let { "${SR_IMG_BASE}$it" }
        val plot = res.content?.rendered?.replace(Regex("<[^>]+>"), "")?.trim()
        val rating = res.imdbRating?.toFloatOrNull()
        val originalName = res.originalName

        // Strategy 1: Get episodes via the series HTML page (with auth)
        val link = res.link?.trimEnd('/') ?: ""
        val episodes = mutableListOf<Episode>()

        if (link.isNotEmpty()) {
            try {
                val doc = app.get(link, headers = mapOf(
                    "Authorization" to SR_AUTH,
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"
                )).document

                val seasonSections = doc.select("div.se-c")
                for (season in seasonSections) {
                    val seasonNum = season.selectFirst("span.se-t")?.text()?.toIntOrNull() ?: continue
                    val epItems = season.select("ul.episodios li")
                    for (ep in epItems) {
                        val epNum = ep.selectFirst("div.numerando")?.text()?.trim()
                            ?.substringAfter("- ")?.trim()?.toIntOrNull()
                        val epAnchor = ep.selectFirst("div.episodiotitle a")
                        val epLink = epAnchor?.attr("href")
                        val epTitle = epAnchor?.text()?.trim()
                        if (epLink != null) {
                            // Get episode WP post ID from its slug via the WP API
                            val epSlug = epLink.trimEnd('/').substringAfterLast('/')
                            episodes.add(newEpisode("sr_slug:$epSlug") {
                                this.name = epTitle
                                this.season = seasonNum
                                this.episode = epNum
                            })
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        // Strategy 2: Fallback - search WP API by anime name (try both English and Arabic)
        if (episodes.isEmpty()) {
            val searchNames = mutableListOf<String>()
            originalName?.let { searchNames.add(it) }
            val arabicName = title.substringBefore("–").substringBefore("-").trim()
            if (arabicName != originalName) searchNames.add(arabicName)

            for (searchName in searchNames) {
                try {
                    val encodedSearch = URLEncoder.encode(searchName, "UTF-8")
                    val epRes = app.get(
                        "$SR_WP_BASE/wp-json/wp/v2/episodes?search=$encodedSearch&per_page=100&orderby=date&order=asc",
                        headers = mapOf("Authorization" to SR_AUTH)
                    )
                    val epList = tryParseJson<List<SRWPMedia>>(epRes.text) ?: emptyList()
                    for (ep in epList) {
                        val epId = ep.id ?: continue

                        // Use temporada/episodio fields if available (more reliable)
                        val seasonNum = ep.temporada?.toIntOrNull()
                        val epNum = ep.episodio?.toIntOrNull()

                        if (seasonNum != null && epNum != null) {
                            episodes.add(newEpisode("sr_ep:$epId") {
                                this.name = ep.episodeName ?: ep.title?.rendered?.replace(Regex("<[^>]+>"), "")?.trim() ?: "حلقة $epNum"
                                this.season = seasonNum
                                this.episode = epNum
                            })
                        } else {
                            // Fallback to regex parsing from title
                            val epTitle = ep.title?.rendered ?: continue
                            val match = Regex("(\\d+)×(\\d+)").find(epTitle)
                            if (match != null) {
                                val s = match.groupValues[1].toIntOrNull() ?: 1
                                val e = match.groupValues[2].toIntOrNull() ?: continue
                                episodes.add(newEpisode("sr_ep:$epId") {
                                    this.name = ep.episodeName ?: epTitle.replace(Regex("<[^>]+>"), "").trim()
                                    this.season = s
                                    this.episode = e
                                })
                            }
                        }
                    }
                    if (episodes.isNotEmpty()) break
                } catch (_: Exception) {}
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes.sortedBy { (it.season ?: 1) * 1000 + (it.episode ?: 0) }) {
            this.posterUrl = poster
            this.backgroundPosterUrl = bg
            this.plot = plot
            this.score = Score.from10(rating)
        }
    }

    // ============================================================
    // SA Server: Load Anime
    // ============================================================
    private suspend fun loadSAAnime(animeId: String, url: String): LoadResponse? {
        val res = app.get("$SA_BASE/anime/public/anime/get-anime-details?anime_id=$animeId",
            headers = mapOf(
                "Client-Id" to SA_CLIENT_ID,
                "Client-Secret" to SA_CLIENT_SECRET
            )
        ).parsedSafe<SAAnimeDetail>() ?: return null

        val title = res.animeName ?: return null
        val poster = res.coverImageFullUrl ?: res.coverImageUrl
        val bg = res.bannerImageUrl
        val plot = res.description
        val rating = res.rating?.toFloatOrNull()
        val genres = res.genres?.split(", ")?.map { it.trim() } ?: emptyList()
        val isMovie = res.animeType == "Movie"

        if (isMovie) {
            // For movies, collect all episode URLs as video sources
            val epUrls = res.episodes?.data?.firstOrNull()?.episodeUrls ?: emptyList()
            val dataJson = epUrls.mapNotNull { eu ->
                eu.url?.let { """{"server":"SA ${eu.serverName}","url":"${it.replace("\"","\\\"")}"}""" }
            }.joinToString(",", "[", "]")

            return newMovieLoadResponse(title, url, TvType.AnimeMovie, dataJson) {
                this.posterUrl = poster
                this.backgroundPosterUrl = bg
                this.plot = plot
                this.score = Score.from10(rating)
                this.tags = genres
            }
        }

        // For series, create episodes
        val episodes = res.episodes?.data?.mapNotNull { ep ->
            val epName = ep.episodeName ?: return@mapNotNull null
            val epNum = ep.episodeNumber?.toIntOrNull() ?: return@mapNotNull null
            val urls = ep.episodeUrls ?: emptyList()
            if (urls.isEmpty()) return@mapNotNull null

            // Encode episode URLs as JSON for loadLinks
            val dataJson = urls.mapNotNull { eu ->
                eu.url?.let { """{"server":"SA ${eu.serverName}","url":"${it.replace("\"","\\\"")}"}""" }
            }.joinToString(",", "[", "]")

            newEpisode(dataJson) {
                this.name = epName
                this.episode = epNum
                this.season = 1
            }
        } ?: emptyList()

        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
            this.posterUrl = poster
            this.backgroundPosterUrl = bg
            this.plot = plot
            this.score = Score.from10(rating)
            this.tags = genres
        }
    }

    // ============================================================
    // JO Server: Load Anime
    // ============================================================
    private suspend fun loadJOAnime(objectId: String, url: String): LoadResponse? {
        // Fetch anime details from Firestore
        val docRes = app.get("$JO_FIRESTORE_URL/anime_list/$objectId").text
        val doc = tryParseJson<FirestoreDocument>(docRes) ?: return null
        val fields = doc.fields ?: return null

        val title = fields["name"]?.stringValue ?: return null
        val poster = fields["poster_uri"]?.stringValue ?: fields["poster"]?.stringValue
        val bg = fields["cover_uri"]?.stringValue
        val plot = fields["story"]?.stringValue
        val rating = fields["average_rate"]?.stringValue?.toFloatOrNull()
            ?: fields["rating"]?.stringValue?.toFloatOrNull()
        val type = fields["type"]?.stringValue ?: "مسلسل"
        val isMovie = type == "فيلم"

        // Get episode summaries from Firestore
        val epSummaryRes = app.get("$JO_FIRESTORE_URL/anime_list/$objectId/episodes_summery/summery").text
        val epSummaryDoc = tryParseJson<FirestoreDocument>(epSummaryRes)
        val epValues = epSummaryDoc?.fields?.get("episodes")?.arrayValue?.values ?: emptyList()

        val episodes = epValues.mapNotNull { epValue ->
            val epFields = epValue.mapValue?.fields ?: return@mapNotNull null
            val epName = epFields["name"]?.stringValue ?: return@mapNotNull null
            val docId = epFields["doc_id"]?.stringValue ?: return@mapNotNull null
            val translatedTitle = epFields["title_translated"]?.mapValue?.fields?.get("ar")?.stringValue

            newEpisode("jo_ep:$objectId:$docId") {
                this.name = if (translatedTitle != null) "$epName - $translatedTitle" else epName
                this.episode = docId.toIntOrNull()
                this.season = 1
            }
        }

        if (isMovie) {
            // For movies, we might not have episodes - try to get video from first available source
            val data = "jo_ep:$objectId:001"
            return newMovieLoadResponse(title, url, TvType.AnimeMovie, data) {
                this.posterUrl = poster
                this.backgroundPosterUrl = bg
                this.plot = plot
                this.score = Score.from10(rating)
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
            this.posterUrl = poster
            this.backgroundPosterUrl = bg
            this.plot = plot
            this.score = Score.from10(rating)
        }
    }

    // ============================================================
    // BO Server: Load Movie
    // ============================================================
    private suspend fun loadBOMovie(objectId: String, url: String): LoadResponse? {
        // Try Firestore first, fall back to Algolia
        val docRes = app.get("$BO_FIRESTORE_URL/Movies/$objectId").text
        val doc = tryParseJson<FirestoreDocument>(docRes)

        val fields = doc?.fields
        val title = fields?.get("name")?.stringValue ?: objectId
        val poster = fields?.get("poster_uri")?.stringValue
        val plot = fields?.get("story")?.stringValue
        val rating = fields?.get("rating")?.stringValue?.toFloatOrNull()

        return newMovieLoadResponse(title, url, TvType.Movie, "bo_ep:$objectId") {
            this.posterUrl = poster
            this.plot = plot
            this.score = Score.from10(rating)
        }
    }

    // ============================================================
    // loadLinks (Video Source Extraction)
    // ============================================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return when {
            data.startsWith("[") -> loadLinksFromJson(data, callback, subtitleCallback)
            data.startsWith("sr_movie:") -> loadSRMovieLinks(data.removePrefix("sr_movie:"), callback, subtitleCallback)
            data.startsWith("sr_ep:") -> loadSRLinks(data.removePrefix("sr_ep:"), callback, subtitleCallback)
            data.startsWith("sr_slug:") -> loadSRSlugLinks(data.removePrefix("sr_slug:"), callback, subtitleCallback)
            data.startsWith("jo_ep:") -> loadJOLinks(data.removePrefix("jo_ep:"), callback, subtitleCallback)
            data.startsWith("bo_ep:") -> loadBOLinks(data.removePrefix("bo_ep:"), callback, subtitleCallback)
            else -> false
        }
    }

    // ============================================================
    // Load links from JSON array (SR movies, SA episodes)
    // ============================================================
    private suspend fun loadLinksFromJson(
        data: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Boolean {
        val items = tryParseJson<List<Map<String, String>>>(data) ?: return false
        var found = false

        for (item in items) {
            val server = item["server"] ?: "Unknown"
            val type = item["type"] ?: ""
            val vidUrl = item["url"] ?: continue
            found = extractVideoLink(vidUrl, server, type, callback, subtitleCallback) || found
        }
        return found
    }

    // ============================================================
    // Common video link extractor
    // ============================================================
    private suspend fun extractVideoLink(
        vidUrl: String,
        serverName: String,
        type: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Boolean {
        return when {
            vidUrl.endsWith(".mp4") || type == "mp4" -> {
                callback.invoke(
                    newExtractorLink(
                        source = "AnimeDay $serverName",
                        name = "AnimeDay $serverName",
                        url = vidUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = SR_WP_BASE
                        this.quality = Qualities.Unknown.value
                    }
                )
                true
            }
            vidUrl.contains(".m3u8") || type == "m3u8" -> {
                try {
                    M3u8Helper.generateM3u8(
                        source = "AnimeDay $serverName",
                        streamUrl = vidUrl,
                        referer = SR_WP_BASE
                    ).forEach { callback.invoke(it) }
                    true
                } catch (_: Exception) { false }
            }
            else -> {
                // Try built-in extractor for embed URLs (filemoon, streamtape, etc.)
                try {
                    loadExtractor(vidUrl, SR_WP_BASE, subtitleCallback, callback)
                    true
                } catch (_: Exception) { false }
            }
        }
    }

    // ============================================================
    // SR Server: loadLinks for movies via dooplayer (fallback)
    // ============================================================
    private suspend fun loadSRMovieLinks(
        movieId: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Boolean {
        // Try WP API first (repeatable_fields contains direct video URLs)
        val res = app.get("$SR_WP_BASE/wp-json/wp/v2/movies/$movieId",
            headers = mapOf("Authorization" to SR_AUTH)
        ).parsedSafe<SRWPMedia>()

        val fields = res?.getRepeatableFields()
        if (!fields.isNullOrEmpty()) {
            var found = false
            for (field in fields) {
                val vidUrl = field.url ?: continue
                val serverName = field.name ?: "SR"
                val type = field.select ?: "mp4"
                found = extractVideoLink(vidUrl, serverName, type, callback, subtitleCallback) || found
            }
            if (found) return true
        }

        // Fallback: try dooplayer API with multiple server numbers
        for (nume in 1..5) {
            try {
                val dooRes = app.post(
                    "$SR_WP_BASE/wp-admin/admin-ajax.php",
                    headers = mapOf(
                        "Authorization" to SR_AUTH,
                        "Content-Type" to "application/x-www-form-urlencoded"
                    ),
                    data = mapOf(
                        "action" to "doo_player_ajax",
                        "post" to movieId,
                        "nume" to nume.toString(),
                        "type" to "movie"
                    )
                ).text

                val dooData = tryParseJson<Map<String, Any?>>(dooRes)
                val embedUrl = dooData?.get("embed_url") as? String ?: continue
                if (embedUrl.isBlank()) continue
                val dooType = dooData?.get("type") as? String ?: ""

                // Decode base64 URL
                val decoded = try {
                    String(Base64.decode(embedUrl, Base64.DEFAULT), Charsets.UTF_8)
                } catch (_: Exception) {
                    embedUrl
                }

                // Extract the source URL from the decoded string
                // Format: ?source=ENCODED_URL&id=ID&type=TYPE
                val sourceUrl = Regex("""source=([^&]+)""").find(decoded)?.groupValues?.get(1)
                    ?.let { URLDecoder.decode(URLDecoder.decode(it, "UTF-8"), "UTF-8") }

                if (sourceUrl != null) {
                    val result = extractVideoLink(sourceUrl, "SR Srv$nume", dooType, callback, subtitleCallback)
                    if (result) return true
                }
            } catch (_: Exception) { continue }
        }
        return false
    }

    // ============================================================
    // SR Server: loadLinks for series episodes by slug
    // ============================================================
    private suspend fun loadSRSlugLinks(
        slug: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Boolean {
        // Resolve the slug to a WP post ID first
        val epRes = app.get("$SR_WP_BASE/wp-json/wp/v2/episodes?slug=$slug",
            headers = mapOf("Authorization" to SR_AUTH)
        ).text
        val epList = tryParseJson<List<SRWPMedia>>(epRes) ?: return false
        val epId = epList.firstOrNull()?.id?.toString() ?: return false
        return loadSRLinks(epId, callback, subtitleCallback)
    }

    // ============================================================
    // SR Server: loadLinks for series episodes
    // ============================================================
    private suspend fun loadSRLinks(
        episodeId: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Boolean {
        // Try WP API first (repeatable_fields contains direct video URLs)
        val epRes = app.get("$SR_WP_BASE/wp-json/wp/v2/episodes/$episodeId",
            headers = mapOf("Authorization" to SR_AUTH)
        ).parsedSafe<SRWPMedia>()

        val fields = epRes?.getRepeatableFields()
        if (!fields.isNullOrEmpty()) {
            var found = false
            for (field in fields) {
                val vidUrl = field.url ?: continue
                val serverName = field.name ?: "SR"
                val type = field.select ?: "mp4"
                found = extractVideoLink(vidUrl, serverName, type, callback, subtitleCallback) || found
            }
            if (found) return true
        }

        // Fallback: try dooplayer API with multiple server numbers
        for (nume in 1..5) {
            try {
                val dooRes = app.post(
                    "$SR_WP_BASE/wp-admin/admin-ajax.php",
                    headers = mapOf(
                        "Authorization" to SR_AUTH,
                        "Content-Type" to "application/x-www-form-urlencoded"
                    ),
                    data = mapOf(
                        "action" to "doo_player_ajax",
                        "post" to episodeId,
                        "nume" to nume.toString(),
                        "type" to "tv"
                    )
                ).text

                val dooData = tryParseJson<Map<String, Any?>>(dooRes)
                val embedUrl = dooData?.get("embed_url") as? String ?: continue
                if (embedUrl.isBlank()) continue
                val dooType = dooData?.get("type") as? String ?: ""

                // Decode base64 URL
                val decoded = try {
                    String(Base64.decode(embedUrl, Base64.DEFAULT), Charsets.UTF_8)
                } catch (_: Exception) {
                    embedUrl
                }

                // Extract the source URL from the decoded string
                // Format: ?source=ENCODED_URL&id=ID&type=TYPE
                val sourceUrl = Regex("""source=([^&]+)""").find(decoded)?.groupValues?.get(1)
                    ?.let { URLDecoder.decode(URLDecoder.decode(it, "UTF-8"), "UTF-8" ) }

                if (sourceUrl != null) {
                    val result = extractVideoLink(sourceUrl, "SR Srv$nume", dooType, callback, subtitleCallback)
                    if (result) return true
                }
            } catch (_: Exception) { continue }
        }
        return false
    }

    // ============================================================
    // JO Server: loadLinks
    // ============================================================
    private suspend fun loadJOLinks(
        data: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Boolean {
        val parts = data.split(":")
        if (parts.size < 2) return false
        val animeId = parts[0]
        val docId = parts[1]

        // Get episode data from Firestore - check for bunny_video_id
        val epRes = app.get("$JO_FIRESTORE_URL/anime_list/$animeId/episodes/$docId").text
        val epDoc = tryParseJson<FirestoreDocument>(epRes) ?: return false
        val fields = epDoc.fields ?: return false

        val bunnyId = fields["bunny_video_id"]?.stringValue
        if (!bunnyId.isNullOrBlank()) {
            // Bunny.net video - construct the HLS URL
            val bunnyUrl = "https://vz-af91c956-726.b-cdn.net/$bunnyId/playlist.m3u8"
            try {
                M3u8Helper.generateM3u8(
                    source = "AnimeDay JO (Bunny)",
                    streamUrl = bunnyUrl,
                    referer = ""
                ).forEach { callback.invoke(it) }
                return true
            } catch (_: Exception) {}
        }

        // Try MF server via animeify.net (if MF is true in servers)
        // The anime doc has servers.MF = true for some anime
        val animeDoc = app.get("$JO_FIRESTORE_URL/anime_list/$animeId").text
        val animeFields = tryParseJson<FirestoreDocument>(animeDoc)?.fields
        val mfEnabled = animeFields?.get("servers")?.mapValue?.fields?.get("MF")?.booleanValue == true

        if (mfEnabled) {
            // MF server would use animeify.net - construct URL
            // This requires further reverse engineering of the animeify API
            // For now, try a common URL pattern
            val mfUrl = "https://animeify.net/animeify/files/videos/${animeId}/${docId}.mp4"
            try {
                callback.invoke(
                    newExtractorLink(
                        source = "AnimeDay JO (MF)",
                        name = "AnimeDay JO (MF)",
                        url = mfUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.quality = Qualities.Unknown.value
                    }
                )
                return true
            } catch (_: Exception) {}
        }

        return false
    }

    // ============================================================
    // BO Server: loadLinks
    // ============================================================
    private suspend fun loadBOLinks(
        objectId: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Boolean {
        // BO server video extraction needs further work
        // The Firestore is permission-denied for episode/server data
        // Try Algolia for any video-related data
        return false
    }
}
