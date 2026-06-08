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
    val repeatableFields: List<SRRepeatableField>? = null,
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
)

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
    val doubleValue: Double? = null,
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
        const val BO_ALGOLIA_URL = "https://V67NZNF3RR-1.algolianet.com"
        const val BO_FIRESTORE_URL = "https://firestore.googleapis.com/v1/projects/moviewitcher-133f3/databases/(default)/documents"

        // Custom URL schemes for routing between methods
        const val SCHEME = "https://animeday.app/"
        const val SR_MOVIE = "sr/movie/"
        const val SR_SERIES = "sr/series/"
        const val SA_ANIME = "sa/anime/"
        const val JO_ANIME = "jo/anime/"
        const val BO_MOVIE = "bo/movie/"
    }

    // ============================================================
    // Main Page
    // ============================================================
    override val mainPage = mainPageOf(
        "$SR_BASE/app/stardima.php?&translate_type=sub" to "SR: أفلام مترجمة",
        "$SR_BASE/app/stardima.php?&translate_type=dub" to "SR: مسلسلات مدبلجة",
        "sa_tv" to "SA: مسلسلات أنمي",
        "sa_movie" to "SA: أفلام أنمي",
        "jo_recent" to "JO: أحدث الأنمي",
        "bo_recent" to "BO: أحدث الأفلام",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data
        val items = try {
            when {
                url.startsWith(SR_BASE) -> getSRMainPage(url, page)
                url == "sa_tv" -> getSAMainPage("TV", page)
                url == "sa_movie" -> getSAMainPage("Movie", page)
                url == "jo_recent" -> getJOMainPage(page)
                url == "bo_recent" -> getBOMainPage(page)
                else -> emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
        return newHomePageResponse(request.name, items, hasNext = items.size >= 21)
    }

    // ============================================================
    // SR Server: Main Page
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
                newMovieSearchResponse(name, link) { this.posterUrl = poster }
            } else {
                newTvSeriesSearchResponse(name, link) { this.posterUrl = poster }
            }
        } ?: emptyList()
    }

    // ============================================================
    // Search
    // ============================================================
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
                    "X-Algolia-API-Key" to JO_ALGOLIA_API_KEY
                ),
                json = mapOf(
                    "query" to query,
                    "hitsPerPage" to 20
                )
            ).parsedSafe<AlgoliaResponse>()
            joRes?.hits?.forEach { hit ->
                val name = hit.name ?: return@forEach
                val objectId = hit.objectID ?: return@forEach
                val poster = hit.posterUri ?: hit.poster
                val link = SCHEME + JO_ANIME + URLEncoder.encode(objectId, "UTF-8")
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
    private suspend fun getSAMainPage(animeType: String, page: Int): List<SearchResponse> {
        val offset = (page - 1) * 21
        val saJson = """{"_offset":$offset,"_limit":21,"order_by":"latest_first","list_type":"filter","anime_name":"","just_info":"Yes","anime_type":"$animeType"}"""
        val fullUrl = "$SA_BASE/anime/public/animes/get-published-animes?json=${URLEncoder.encode(saJson, "UTF-8")}"

        val responseText = app.get(fullUrl, headers = mapOf(
            "Client-Id" to SA_CLIENT_ID,
            "Client-Secret" to SA_CLIENT_SECRET
        )).text

        // Try parsedSafe first, then manual parsing as fallback
        val res = tryParseJson<SACatalogResponse>(responseText)
        val items = res?.response?.data

        if (!items.isNullOrEmpty()) {
            return items.mapNotNull { item ->
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
            }
        }

        // Fallback: parse JSON manually
        return parseSACatalogManual(responseText)
    }

    private fun parseSACatalogManual(jsonText: String): List<SearchResponse> {
        val items = mutableListOf<SearchResponse>()
        try {
            val root = tryParseJson<Map<String, Any?>>(jsonText) ?: return items
            val response = root["response"] as? Map<String, Any?> ?: return items
            val data = response["data"] as? List<Map<String, Any?>> ?: return items
            for (item in data) {
                val name = item["anime_name"] as? String ?: continue
                val animeId = item["anime_id"]?.toString() ?: continue
                val poster = item["anime_cover_image_url"] as? String
                val animeType = item["anime_type"] as? String ?: "TV"
                val link = SCHEME + SA_ANIME + animeId
                val isMovie = animeType == "Movie"
                items.add(
                    if (isMovie) {
                        newMovieSearchResponse(name, link) { this.posterUrl = poster }
                    } else {
                        newTvSeriesSearchResponse(name, link) { this.posterUrl = poster }
                    }
                )
            }
        } catch (_: Exception) {}
        return items
    }

    // ============================================================
    // JO Server: Main Page
    // ============================================================
    private suspend fun getJOMainPage(page: Int): List<SearchResponse> {
        val responseText = app.post("$JO_ALGOLIA_URL/1/indexes/recent/query", headers = mapOf(
            "X-Algolia-Application-Id" to JO_ALGOLIA_APP_ID,
            "X-Algolia-API-Key" to JO_ALGOLIA_API_KEY
        ), json = mapOf(
            "hitsPerPage" to 21,
            "page" to (page - 1),
            "query" to ""
        )).text

        // Try parsedSafe first, then manual parsing as fallback
        val res = tryParseJson<AlgoliaResponse>(responseText)
        if (!res?.hits.isNullOrEmpty()) {
            return res!!.hits!!.mapNotNull { hit ->
                val name = hit.name ?: return@mapNotNull null
                val objectId = hit.objectID ?: return@mapNotNull null
                val poster = hit.posterUri ?: hit.poster
                val link = SCHEME + JO_ANIME + URLEncoder.encode(objectId, "UTF-8")
                val isMovie = hit.type == "فيلم"
                if (isMovie) {
                    newMovieSearchResponse(name, link) { this.posterUrl = poster }
                } else {
                    newTvSeriesSearchResponse(name, link) { this.posterUrl = poster }
                }
            }
        }

        // Fallback: parse JSON manually
        return parseJOAlgoliaManual(responseText)
    }

    private fun parseJOAlgoliaManual(jsonText: String): List<SearchResponse> {
        val items = mutableListOf<SearchResponse>()
        try {
            val root = tryParseJson<Map<String, Any?>>(jsonText) ?: return items
            val hits = root["hits"] as? List<Map<String, Any?>> ?: return items
            for (hit in hits) {
                val name = hit["name"] as? String ?: continue
                val objectId = hit["objectID"] as? String ?: continue
                val posterUri = hit["poster_uri"] as? String
                val poster = hit["poster"] as? String
                val type = hit["type"] as? String ?: "مسلسل"
                val link = SCHEME + JO_ANIME + URLEncoder.encode(objectId, "UTF-8")
                val isMovie = type == "فيلم"
                items.add(
                    if (isMovie) {
                        newMovieSearchResponse(name, link) { this.posterUrl = posterUri ?: poster }
                    } else {
                        newTvSeriesSearchResponse(name, link) { this.posterUrl = posterUri ?: poster }
                    }
                )
            }
        } catch (_: Exception) {}
        return items
    }

    // ============================================================
    // BO Server: Main Page
    // ============================================================
    private suspend fun getBOMainPage(page: Int): List<SearchResponse> {
        val responseText = app.post("$BO_ALGOLIA_URL/1/indexes/Movies/query", headers = mapOf(
            "X-Algolia-Application-Id" to BO_ALGOLIA_APP_ID,
            "X-Algolia-API-Key" to BO_ALGOLIA_API_KEY
        ), json = mapOf(
            "hitsPerPage" to 21,
            "page" to (page - 1),
            "query" to ""
        )).text

        // Try parsedSafe first, then manual parsing as fallback
        val res = tryParseJson<AlgoliaResponse>(responseText)
        if (!res?.hits.isNullOrEmpty()) {
            return res!!.hits!!.mapNotNull { hit ->
                val name = hit.name ?: return@mapNotNull null
                val objectId = hit.objectID ?: return@mapNotNull null
                val poster = hit.posterUri ?: hit.poster
                val link = SCHEME + BO_MOVIE + URLEncoder.encode(objectId, "UTF-8")
                newMovieSearchResponse(name, link) { this.posterUrl = poster }
            }
        }

        // Fallback: parse JSON manually
        return parseBOAlgoliaManual(responseText)
    }

    private fun parseBOAlgoliaManual(jsonText: String): List<SearchResponse> {
        val items = mutableListOf<SearchResponse>()
        try {
            val root = tryParseJson<Map<String, Any?>>(jsonText) ?: return items
            val hits = root["hits"] as? List<Map<String, Any?>> ?: return items
            for (hit in hits) {
                val name = hit["name"] as? String ?: continue
                val objectId = hit["objectID"] as? String ?: continue
                val posterUri = hit["poster_uri"] as? String
                val poster = hit["poster"] as? String
                val link = SCHEME + BO_MOVIE + URLEncoder.encode(objectId, "UTF-8")
                items.add(
                    newMovieSearchResponse(name, link) { this.posterUrl = posterUri ?: poster }
                )
            }
        } catch (_: Exception) {}
        return items
    }

    // ============================================================
    // Load (Detail Page)
    // ============================================================
    override suspend fun load(url: String): LoadResponse? {
        // Strip mainUrl prefix if CloudStream prepended it
        val cleanUrl = url.removePrefix(mainUrl).removePrefix("/")
        val fullUrl = if (cleanUrl.startsWith("https://")) cleanUrl else url

        return when {
            fullUrl.startsWith(SCHEME + SR_MOVIE) -> loadSRMovie(fullUrl.removePrefix(SCHEME + SR_MOVIE), fullUrl)
            fullUrl.startsWith(SCHEME + SR_SERIES) -> loadSRSeries(fullUrl.removePrefix(SCHEME + SR_SERIES), fullUrl)
            fullUrl.startsWith(SCHEME + SA_ANIME) -> loadSAAnime(fullUrl.removePrefix(SCHEME + SA_ANIME), fullUrl)
            fullUrl.startsWith(SCHEME + JO_ANIME) -> loadJOAnime(URLDecoder.decode(fullUrl.removePrefix(SCHEME + JO_ANIME), "UTF-8"), fullUrl)
            fullUrl.startsWith(SCHEME + BO_MOVIE) -> loadBOMovie(fullUrl.removePrefix(SCHEME + BO_MOVIE), fullUrl)
            else -> null
        }
    }

    // ============================================================
    // SR Server: Load Movie
    // ============================================================
    private suspend fun loadSRMovie(idPage: String, url: String): LoadResponse? {
        val resText = app.get("$SR_WP_BASE/wp-json/wp/v2/movies/$idPage",
            headers = mapOf("Authorization" to SR_AUTH)
        ).text

        val res = tryParseJson<SRWPMedia>(resText) ?: return null
        val title = res.title?.rendered ?: return null
        val poster = res.dtPoster?.let { "${SR_IMG_BASE}$it" }
        val bg = res.dtBackdrop?.let { "${SR_IMG_BASE}$it" }
        val plot = res.content?.rendered?.replace(Regex("<[^>]+>"), "")?.trim()
        val rating = res.imdbRating?.toFloatOrNull()

        // Collect all video sources from repeatable fields
        val videoData = (res.repeatableFields ?: emptyList()).mapNotNull { field ->
            field.url?.let { Triple(field.name ?: "SR", field.select ?: "mp4", it) }
        }

        if (videoData.isNotEmpty()) {
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

        // Fallback: try manual JSON parsing for repeatable_fields
        val manualFields = extractRepeatableFieldsManual(resText)
        if (manualFields.isNotEmpty()) {
            val dataJson = manualFields.map { (server, type, vidUrl) ->
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
    // Manual JSON parsing for repeatable_fields (fallback)
    // ============================================================
    private fun extractRepeatableFieldsManual(jsonText: String): List<Triple<String, String, String>> {
        val result = mutableListOf<Triple<String, String, String>>()
        try {
            val root = tryParseJson<Map<String, Any?>>(jsonText) ?: return result
            val rfRaw = root["repeatable_fields"] as? List<Map<String, Any?>> ?: return result
            for (field in rfRaw) {
                val name = field["name"] as? String ?: "SR"
                val select = field["select"] as? String ?: "mp4"
                val vidUrl = field["url"] as? String ?: continue
                result.add(Triple(name, select, vidUrl))
            }
        } catch (_: Exception) {}
        return result
    }

    // ============================================================
    // SR Server: Load Series
    // ============================================================
    private suspend fun loadSRSeries(idPage: String, url: String): LoadResponse? {
        val resText = app.get("$SR_WP_BASE/wp-json/wp/v2/tvshows/$idPage",
            headers = mapOf("Authorization" to SR_AUTH)
        ).text

        val res = tryParseJson<SRWPMedia>(resText) ?: return null
        val title = res.title?.rendered ?: return null
        val poster = res.dtPoster?.let { "${SR_IMG_BASE}$it" }
        val bg = res.dtBackdrop?.let { "${SR_IMG_BASE}$it" }
        val plot = res.content?.rendered?.replace(Regex("<[^>]+>"), "")?.trim()
        val rating = res.imdbRating?.toFloatOrNull()
        val originalName = res.originalName

        val episodes = mutableListOf<Episode>()

        // Strategy 1: Get episodes via the series HTML page (with auth)
        val link = res.link?.trimEnd('/') ?: ""
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
                            // Resolve the slug to an episode ID right now
                            val epSlug = epLink.trimEnd('/').substringAfterLast('/')
                            val epId = resolveSlugToId(epSlug)
                            if (epId != null) {
                                episodes.add(newEpisode("sr_ep:$epId") {
                                    this.name = epTitle
                                    this.season = seasonNum
                                    this.episode = epNum
                                })
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        // Strategy 2: Search WP API by anime name
        if (episodes.isEmpty()) {
            val searchNames = mutableListOf<String>()

            originalName?.let { engName ->
                searchNames.add(engName)
                val stripped = engName.replace(Regex("^(The|A|An)\\s+", RegexOption.IGNORE_CASE), "").trim()
                if (stripped != engName && stripped.length > 2) {
                    searchNames.add(stripped)
                }
            }

            val arabicName = title.substringBefore("–").substringBefore("-").trim()
            if (arabicName != originalName) {
                searchNames.add(arabicName)
                val arabicStripped = arabicName
                    .replace(Regex("^(كرتون|مسلسل|فيلم)\\s+"), "").trim()
                if (arabicStripped != arabicName && arabicStripped.length > 2) {
                    searchNames.add(arabicStripped)
                }
            }

            for (searchName in searchNames) {
                try {
                    val encodedSearch = URLEncoder.encode(searchName, "UTF-8")
                    val epResText = app.get(
                        "$SR_WP_BASE/wp-json/wp/v2/episodes?search=$encodedSearch&per_page=100&orderby=date&order=asc",
                        headers = mapOf("Authorization" to SR_AUTH)
                    ).text

                    // Try parsedSafe first
                    val epList = tryParseJson<List<SRWPMedia>>(epResText) ?: emptyList()
                    for (ep in epList) {
                        val epId = ep.id ?: continue
                        val seasonNum = ep.temporada?.toIntOrNull()
                        val epNum = ep.episodio?.toIntOrNull()

                        if (seasonNum != null && epNum != null) {
                            episodes.add(newEpisode("sr_ep:$epId") {
                                this.name = ep.episodeName ?: ep.title?.rendered?.replace(Regex("<[^>]+>"), "")?.trim() ?: "حلقة $epNum"
                                this.season = seasonNum
                                this.episode = epNum
                            })
                        } else {
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

                    // Fallback: manual parsing if parsedSafe returned empty
                    if (episodes.isEmpty()) {
                        parseEpisodesManual(epResText, episodes)
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
    // Resolve a WP episode slug to its post ID
    // ============================================================
    private suspend fun resolveSlugToId(slug: String): Int? {
        return try {
            // URL-decode the slug first (it may be percent-encoded from HTML)
            val decodedSlug = URLDecoder.decode(slug, "UTF-8")
            // Then URL-encode it for the API query parameter
            val encodedSlug = URLEncoder.encode(decodedSlug, "UTF-8")
            val epResText = app.get("$SR_WP_BASE/wp-json/wp/v2/episodes?slug=$encodedSlug",
                headers = mapOf("Authorization" to SR_AUTH)
            ).text

            // Try parsedSafe first
            val epList = tryParseJson<List<SRWPMedia>>(epResText)
            val id = epList?.firstOrNull()?.id
            if (id != null) return id

            // Fallback: manual parsing
            val root = tryParseJson<List<Map<String, Any?>>>(epResText)
            (root?.firstOrNull()?.get("id") as? Number)?.toInt()
        } catch (_: Exception) {
            null
        }
    }

    // ============================================================
    // Manual episode parsing fallback
    // ============================================================
    private fun parseEpisodesManual(jsonText: String, episodes: MutableList<Episode>) {
        try {
            val list = tryParseJson<List<Map<String, Any?>>>(jsonText) ?: return
            for (ep in list) {
                val epId = (ep["id"] as? Number)?.toInt() ?: continue
                val titleObj = ep["title"] as? Map<String, Any?>
                val titleRendered = titleObj?.get("rendered") as? String ?: continue
                val temporada = ep["temporada"] as? String
                val episodio = ep["episodio"] as? String
                val episodeName = ep["episode_name"] as? String

                val seasonNum = temporada?.toIntOrNull()
                val epNum = episodio?.toIntOrNull()

                if (seasonNum != null && epNum != null) {
                    episodes.add(newEpisode("sr_ep:$epId") {
                        this.name = episodeName ?: titleRendered.replace(Regex("<[^>]+>"), "").trim() ?: "حلقة $epNum"
                        this.season = seasonNum
                        this.episode = epNum
                    })
                } else {
                    val match = Regex("(\\d+)×(\\d+)").find(titleRendered)
                    if (match != null) {
                        val s = match.groupValues[1].toIntOrNull() ?: 1
                        val e = match.groupValues[2].toIntOrNull() ?: continue
                        episodes.add(newEpisode("sr_ep:$epId") {
                            this.name = episodeName ?: titleRendered.replace(Regex("<[^>]+>"), "").trim()
                            this.season = s
                            this.episode = e
                        })
                    }
                }
            }
        } catch (_: Exception) {}
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

        val episodes = res.episodes?.data?.mapNotNull { ep ->
            val epName = ep.episodeName ?: return@mapNotNull null
            val epNum = ep.episodeNumber?.toIntOrNull() ?: return@mapNotNull null
            val urls = ep.episodeUrls ?: emptyList()
            if (urls.isEmpty()) return@mapNotNull null

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
        val docRes = app.get("$JO_FIRESTORE_URL/anime_list/${URLEncoder.encode(objectId, "UTF-8")}").text
        val doc = tryParseJson<FirestoreDocument>(docRes) ?: return null
        val fields = doc.fields ?: return null

        val title = fields["name"]?.stringValue ?: return null
        val poster = fields["poster_uri"]?.stringValue ?: fields["poster"]?.stringValue
        val bg = fields["cover_uri"]?.stringValue
        val plot = fields["story"]?.stringValue
        val rating = fields["average_rate"]?.doubleValue?.toFloat()
            ?: fields["average_rate"]?.stringValue?.toFloatOrNull()
            ?: fields["rating"]?.stringValue?.toFloatOrNull()
        val type = fields["type"]?.stringValue ?: "مسلسل"
        val isMovie = type == "فيلم"

        // Get episode summaries from Firestore
        val epSummaryRes = app.get("$JO_FIRESTORE_URL/anime_list/${URLEncoder.encode(objectId, "UTF-8")}/episodes_summery/summery").text
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
            val data = if (episodes.isNotEmpty()) "jo_ep:$objectId:${episodes.first().episode}" else "jo_ep:$objectId:001"
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
            vidUrl.endsWith(".mp4") || vidUrl.endsWith(".mkv") || type == "mp4" -> {
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
        val resText = app.get("$SR_WP_BASE/wp-json/wp/v2/movies/$movieId",
            headers = mapOf("Authorization" to SR_AUTH)
        ).text

        // Try parsedSafe
        val res = tryParseJson<SRWPMedia>(resText)
        val fields = res?.repeatableFields ?: emptyList()
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

        // Fallback: manual parsing
        val manualFields = extractRepeatableFieldsManual(resText)
        if (manualFields.isNotEmpty()) {
            var found = false
            for ((server, type, vidUrl) in manualFields) {
                found = extractVideoLink(vidUrl, server, type, callback, subtitleCallback) || found
            }
            if (found) return true
        }

        // Fallback: try dooplayer API
        return tryDooplayer(movieId, "movie", callback, subtitleCallback)
    }

    // ============================================================
    // SR Server: loadLinks for series episodes
    // ============================================================
    private suspend fun loadSRLinks(
        episodeId: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Boolean {
        // Try WP API first
        val epResText = app.get("$SR_WP_BASE/wp-json/wp/v2/episodes/$episodeId",
            headers = mapOf("Authorization" to SR_AUTH)
        ).text

        // Try parsedSafe
        val epRes = tryParseJson<SRWPMedia>(epResText)
        val fields = epRes?.repeatableFields ?: emptyList()
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

        // Fallback: manual parsing of repeatable_fields
        val manualFields = extractRepeatableFieldsManual(epResText)
        if (manualFields.isNotEmpty()) {
            var found = false
            for ((server, type, vidUrl) in manualFields) {
                found = extractVideoLink(vidUrl, server, type, callback, subtitleCallback) || found
            }
            if (found) return true
        }

        // Fallback: try the episode HTML page for iframe sources
        val epLink = epRes?.link
        if (!epLink.isNullOrBlank()) {
            val result = trySREpisodePageLinks(epLink, callback, subtitleCallback)
            if (result) return true
        }

        // Fallback: try dooplayer API with type=tv
        var found = tryDooplayer(episodeId, "tv", callback, subtitleCallback)
        if (found) return true

        // Fallback: try dooplayer API with type=1 (some dooplay themes use this for episodes)
        found = tryDooplayer(episodeId, "1", callback, subtitleCallback)
        return found
    }

    // ============================================================
    // SR Server: Try scraping episode HTML page for video sources
    // ============================================================
    private suspend fun trySREpisodePageLinks(
        pageUrl: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Boolean {
        var found = false
        try {
            val doc = app.get(pageUrl, headers = mapOf(
                "Authorization" to SR_AUTH,
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"
            )).document

            // Look for iframe sources in the page (common for embed players)
            val iframes = doc.select("iframe")
            for (iframe in iframes) {
                val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
                if (src.isBlank()) continue

                val fullSrc = if (src.startsWith("//")) "https:$src" else src

                try {
                    loadExtractor(fullSrc, SR_WP_BASE, subtitleCallback, callback)
                    found = true
                } catch (_: Exception) {}

                try {
                    found = extractVideoLink(fullSrc, "SR Embed", "", callback, subtitleCallback) || found
                } catch (_: Exception) {}
            }

            // Also look for video tags with direct sources
            val videos = doc.select("video source")
            for (video in videos) {
                val src = video.attr("src")
                if (src.isBlank()) continue
                val fullSrc = if (src.startsWith("//")) "https:$src" else src
                found = extractVideoLink(fullSrc, "SR Direct", "", callback, subtitleCallback) || found
            }

            // Look for dooplayer divs that contain post IDs and nume values
            val dooPlayers = doc.select("div.dooplay_player_option")
            for (player in dooPlayers) {
                val dataPost = player.attr("data-post")
                val dataNume = player.attr("data-nume")
                val dataType = player.attr("data-type")
                if (dataPost.isNotBlank()) {
                    found = tryDooplayer(dataPost, dataType.ifBlank { "tv" }, callback, subtitleCallback) || found
                }
            }
        } catch (_: Exception) {}
        return found
    }

    // ============================================================
    // Dooplayer fallback (shared for movies and episodes)
    // ============================================================
    private suspend fun tryDooplayer(
        postId: String,
        dooType: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Boolean {
        var found = false
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
                        "post" to postId,
                        "nume" to nume.toString(),
                        "type" to dooType
                    )
                ).text

                val dooData = tryParseJson<Map<String, Any?>>(dooRes)
                val embedUrl = dooData?.get("embed_url") as? String ?: continue
                if (embedUrl.isBlank()) continue
                val videoType = dooData?.get("type") as? String ?: ""

                // Try base64 decoding
                val decoded = try {
                    String(Base64.decode(embedUrl, Base64.DEFAULT), Charsets.UTF_8)
                } catch (_: Exception) {
                    embedUrl
                }

                // Collect all candidate URLs to try
                val candidates = mutableListOf<String>()

                // Strategy 1: Extract source= from decoded string
                val sourceUrl = Regex("""source=([^&]+)""").find(decoded)?.groupValues?.get(1)
                    ?.let { URLDecoder.decode(URLDecoder.decode(it, "UTF-8"), "UTF-8") }
                if (sourceUrl != null) {
                    candidates.add(sourceUrl.replace(" ", "%20"))
                }

                // Strategy 2: The decoded string itself might be a URL
                if (decoded.startsWith("http")) {
                    candidates.add(decoded)
                }

                // Strategy 3: The raw embed_url might be a direct URL
                if (embedUrl.startsWith("http")) {
                    candidates.add(embedUrl)
                }

                // Try each candidate URL
                for (url in candidates) {
                    try {
                        found = extractVideoLink(url, "SR Srv$nume", videoType, callback, subtitleCallback) || found
                    } catch (_: Exception) {}
                    // Also try loadExtractor for embed URLs that extractVideoLink can't handle
                    if (!found) {
                        try {
                            loadExtractor(url, SR_WP_BASE, subtitleCallback, callback)
                            found = true
                        } catch (_: Exception) {}
                    }
                }

                if (found) return true
            } catch (_: Exception) { continue }
        }
        return found
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
        val epRes = app.get("$JO_FIRESTORE_URL/anime_list/${URLEncoder.encode(animeId, "UTF-8")}/episodes/$docId").text
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

        // Try MF server via animeify.net
        val animeDoc = app.get("$JO_FIRESTORE_URL/anime_list/${URLEncoder.encode(animeId, "UTF-8")}").text
        val animeFields = tryParseJson<FirestoreDocument>(animeDoc)?.fields
        val mfEnabled = animeFields?.get("servers")?.mapValue?.fields?.get("MF")?.booleanValue == true

        if (mfEnabled) {
            val mfUrl = "https://animeify.net/animeify/files/videos/${URLEncoder.encode(animeId, "UTF-8")}/${docId}.mp4"
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
        var found = false

        // Try Firestore for movie data
        try {
            val docRes = app.get("$BO_FIRESTORE_URL/Movies/${URLEncoder.encode(objectId, "UTF-8")}").text
            val doc = tryParseJson<FirestoreDocument>(docRes)
            val fields = doc?.fields

            // Try bunny_video_id
            val bunnyId = fields?.get("bunny_video_id")?.stringValue
            if (!bunnyId.isNullOrBlank()) {
                val bunnyUrl = "https://vz-af91c956-726.b-cdn.net/$bunnyId/playlist.m3u8"
                try {
                    M3u8Helper.generateM3u8(
                        source = "AnimeDay BO (Bunny)",
                        streamUrl = bunnyUrl,
                        referer = ""
                    ).forEach { callback.invoke(it) }
                    found = true
                } catch (_: Exception) {}
            }

            // Try video_url field
            val videoUrl = fields?.get("video_url")?.stringValue
            if (!videoUrl.isNullOrBlank()) {
                found = extractVideoLink(videoUrl, "BO", "", callback, subtitleCallback) || found
            }

            // Try embed_url field with loadExtractor
            val embedUrl = fields?.get("embed_url")?.stringValue
            if (!embedUrl.isNullOrBlank()) {
                try {
                    loadExtractor(embedUrl, "", subtitleCallback, callback)
                    found = true
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}

        return found
    }
}
