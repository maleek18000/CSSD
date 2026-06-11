package com.stremio

data class LinkData(
    val id: Int? = null,
    val imdbId: String? = null,
    val tvdbId: Int? = null,
    val type: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val epid: Int? = null,
    val aniId: String? = null,
    val animeId: String? = null,
    val title: String? = null,
    val titleVi: String? = null,
    val year: Int? = null,
    val airedDate: String? = null,
    val airedYear: Int? = null,
    val orgTitle: String? = null,
    val jpTitle: String? = null,
    val date: String? = null,
    val isAnime: Boolean = false,
    val isAsian: Boolean = false,
    val isCartoon: Boolean = false,
    val lastSeason: Int? = null,
    val epsTitle: String? = null,
    val originalLanguage: String? = null
)
