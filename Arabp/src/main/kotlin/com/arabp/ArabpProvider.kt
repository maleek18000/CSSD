package com.arabp

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.SubtitleFile
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class Arabp : MainAPI() {
    override var mainUrl = "https://www.arabp2p.net"
    override var name = "Arabp"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    companion object {
        private const val TAG = "Arabp_Log"
        private val NON_DIGITS = Regex("[^0-9]")
    }

    private fun toAbsoluteUrl(url: String): String {
        return when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            else -> "$mainUrl/$url"
        }
    }

    // ==================== HOME PAGE ====================

    override val mainPage = mainPageOf(
        "$mainUrl/index.php?page=anime-listing" to "قائمة الانمي"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = if (page > 1) "${request.data}&pages=$page" else request.data
        val doc = app.get(url).document
        val homeSets = mutableListOf<HomePageList>()

        try {
            val items = doc.select("div.listing_div1").mapNotNull { element ->
                toSearchResult(element)
            }

            if (items.isNotEmpty()) {
                homeSets.add(HomePageList(request.name, items))
            }

            // If we got items, there might be more pages (~350 total pages)
            val hasNextPage = items.isNotEmpty()

            return newHomePageResponse(homeSets, hasNextPage)
        } catch (e: Exception) {
            Log.e(TAG, "MainPage Error: ${e.message}")
        }
        return newHomePageResponse(homeSets)
    }

    private fun toSearchResult(element: Element): SearchResponse? {
        return try {
            val linkEl = element.selectFirst("div.listing_div2 a")
                ?: element.selectFirst("a[href*=anime-listing]")
                ?: return null

            val href = toAbsoluteUrl(linkEl.attr("href"))

            // Title can contain <br> separating English and Arabic names
            val rawTitle = linkEl.html()
                .replace("<br>", " ")
                .replace("<br/>", " ")
                .replace("<br />", " ")
                .trim()
            val title = cleanTitleText(rawTitle)

            val posterUrl = element.selectFirst("img.listing_poster")?.attr("src")
                ?: element.selectFirst("img")?.attr("src")
                ?: ""

            // Determine type from the title or category context
            val tvType = when {
                title.contains("فيلم", ignoreCase = true) || title.contains("Movie", ignoreCase = true) -> TvType.AnimeMovie
                else -> TvType.Anime
            }

            newAnimeSearchResponse(title, href, tvType) {
                this.posterUrl = toAbsoluteUrl(posterUrl)
            }
        } catch (e: Exception) {
            Log.e(TAG, "toSearchResult Error: ${e.message}")
            null
        }
    }

    // ==================== SEARCH ====================

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/index.php?page=anime-listing&search=$encoded"
        val doc = app.get(url).document

        return doc.select("div.listing_div1").mapNotNull { element ->
            toSearchResult(element)
        }
    }

    // ==================== LOAD (Detail Page) ====================

    override suspend fun load(url: String): LoadResponse? {
        val fullUrl = toAbsoluteUrl(url)
        val doc = app.get(fullUrl).document

        return try {
            // Extract title
            val h1 = doc.selectFirst("div.listing_div_id h1")
            val rawTitle = h1?.html()
                ?.replace("<br>", " ")
                ?.replace("<br/>", " ")
                ?.replace("<br />", " ")
                ?.trim()
                ?: ""
            val title = cleanTitleText(rawTitle)

            // Extract poster
            val posterUrl = doc.selectFirst("div.listing_div_id img.listing_poster")?.attr("src")
                ?: doc.selectFirst("img.listing_poster")?.attr("src")
                ?: ""

            // Extract description from the anime listing page (if any)
            val desc = ""

            // Extract episodes (torrent entries)
            val rows = doc.select("table#listing_table tr.torrent")
            val episodes = rows.mapNotNull { row ->
                val nameLink = row.selectFirst("td:first-child a[href*=torrent-details]")
                    ?: return@mapNotNull null

                val epName = cleanTitleText(nameLink.text())
                val epHref = toAbsoluteUrl(nameLink.attr("href"))

                // Extract additional info from the row
                val size = row.select("td")?.getOrNull(3)?.text()?.trim() ?: ""
                val seeders = row.select("td")?.getOrNull(4)?.text()?.trim() ?: ""
                val leechers = row.select("td")?.getOrNull(5)?.text()?.trim() ?: ""
                val date = row.select("td")?.getOrNull(6)?.text()?.trim() ?: ""

                val displayName = buildString {
                    append(epName)
                    if (size.isNotEmpty()) append(" | $size")
                    if (seeders.isNotEmpty()) append(" | ▲$seeders")
                }

                newEpisode(epHref) {
                    name = displayName
                    this.posterUrl = toAbsoluteUrl(posterUrl)
                }
            }

            if (episodes.isEmpty()) {
                // If no episodes found, return as a movie
                newMovieLoadResponse(title, fullUrl, TvType.Anime, fullUrl) {
                    this.posterUrl = toAbsoluteUrl(posterUrl)
                    this.plot = desc
                }
            } else {
                newTvSeriesLoadResponse(title, fullUrl, TvType.Anime, episodes) {
                    this.posterUrl = toAbsoluteUrl(posterUrl)
                    this.plot = desc
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Load Error: ${e.message}")
            null
        }
    }

    // ==================== LOAD LINKS (Torrent/Magnet Extraction) ====================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val fullUrl = toAbsoluteUrl(data)

        return try {
            val doc = app.get(fullUrl).document

            // Try to find magnet link in the torrent detail page
            // The site provides .torrent files, look for magnet links in the description
            val magnetLinks = doc.select("a[href^=magnet:]")
            if (magnetLinks.isNotEmpty()) {
                magnetLinks.forEach { magnetEl ->
                    val magnetUrl = magnetEl.attr("href")
                    callback(
                        newExtractorLink(
                            source = this.name,
                            name = "${this.name} Magnet",
                            url = magnetUrl,
                            type = ExtractorLinkType.MAGNET
                        )
                    )
                }
                return true
            }

            // Try to find .torrent download link
            val torrentLink = doc.selectFirst("a[href*=download.php]")
            if (torrentLink != null) {
                val torrentUrl = toAbsoluteUrl(torrentLink.attr("href"))
                callback(
                    newExtractorLink(
                        source = this.name,
                        name = "${this.name} Torrent",
                        url = torrentUrl,
                        type = ExtractorLinkType.TORRENT
                    )
                )
                return true
            }

            // Try to find any video streaming links in the description
            // Some torrents may have embedded streaming links
            val videoLinks = doc.select("a[href*=.mp4], a[href*=.mkv], a[href*=stream], iframe[src*=player]")
            videoLinks.forEach { link ->
                val videoUrl = link.attr("href") ?: link.attr("src") ?: return@forEach
                if (videoUrl.startsWith("http")) {
                    callback(
                        newExtractorLink(
                            source = this.name,
                            name = "${this.name} Mirror",
                            url = videoUrl,
                            type = ExtractorLinkType.VIDEO
                        )
                    )
                }
            }

            videoLinks.isNotEmpty() || magnetLinks.isNotEmpty() || torrentLink != null
        } catch (e: Exception) {
            Log.e(TAG, "loadLinks Error: ${e.message}")
            false
        }
    }

    // ==================== HELPERS ====================

    private fun cleanTitleText(text: String): String {
        return text.replace("\\n", " ")
            .replace("\n", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
