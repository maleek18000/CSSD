package com.arabmagnet

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.SubtitleFile
import org.jsoup.nodes.Element
import java.net.URLEncoder

class Arabmagnet : MainAPI() {
    override var mainUrl = "https://arab-torrents.com"
    override var name = "ArabMagnet"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(
        TvType.Anime, TvType.AnimeMovie, TvType.TvSeries, TvType.Movie
    )

    companion object {
        private const val TAG = "ArabMagnet_Log"
        private const val CATEGORY_ID = 100
        private const val MAX_PAGES = 30
    }

    private val imageHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
        "Referer" to "$mainUrl/"
    )

    // ==================== HELPERS ====================

    private fun toAbsoluteUrl(url: String): String {
        return when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            else -> "$mainUrl/$url"
        }
    }

    private fun cleanTitleText(text: String): String {
        return text.replace("\\n", " ").replace("\n", " ").replace(Regex("\\s+"), " ").trim()
    }

    private fun stripDownloadPrefix(title: String): String {
        return title.removePrefix("تحميل ").removePrefix("تحميل").trim()
    }

    private fun titleFromMagnet(magnetUrl: String): String? {
        return try {
            val dnParam = magnetUrl.substringAfter("dn=", "")
                .substringBefore("&")
            if (dnParam.isNotEmpty()) {
                java.net.URLDecoder.decode(dnParam, "UTF-8")
            } else null
        } catch (e: Exception) {
            null
        }
    }

    private fun tvTypeFromTitle(title: String): TvType {
        return when {
            title.contains("فيلم", ignoreCase = true) || title.contains("Movie", ignoreCase = true) -> TvType.AnimeMovie
            else -> TvType.Anime
        }
    }

    /**
     * CloudStream resolves SearchResponse URLs against mainUrl.
     * If "magnet:?xt=..." was stored, it becomes
     * "https://arab-torrents.com/magnet:?xt=..." which is broken.
     * This function strips the corrupted prefix.
     */
    private fun fixMagnetUrl(url: String): String {
        if (url.startsWith("magnet:")) return url
        val idx = url.indexOf("magnet:")
        if (idx > 0) {
            val fixed = url.substring(idx)
            Log.d(TAG, "fixMagnetUrl: stripped prefix → ${fixed.take(60)}...")
            return fixed
        }
        return url
    }

    // ==================== HOME PAGE ====================

    override val mainPage = mainPageOf(
        "$mainUrl/index.php?cat=$CATEGORY_ID" to "أنمي مدبلج عربي"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        if (page > MAX_PAGES) return newHomePageResponse(mutableListOf(), false)

        val url = if (page == 1) request.data else "${request.data}&p=$page"
        return try {
            val doc = app.get(url, headers = imageHeaders).document
            val items = doc.select("table#torrents tr").mapNotNull { toSearchResult(it) }
            Log.d(TAG, "MainPage page $page: found ${items.size} items")
            newHomePageResponse(request.name, items, items.size >= 10 && page < MAX_PAGES)
        } catch (e: Exception) {
            Log.e(TAG, "MainPage Error: ${e.message}")
            newHomePageResponse(mutableListOf(), false)
        }
    }

    // ==================== SEARCH RESULT BUILDER ====================

    /**
     * Data format: PAGE_URL|MAGNET_URL|TITLE|POSTER_URL|FILE_SIZE
     * PAGE_URL starts with https:// so CloudStream doesn't corrupt it.
     */
    private fun toSearchResult(row: Element): SearchResponse? {
        return try {
            val magnetEl = row.selectFirst("a[href^=magnet:]") ?: return null
            var magnetUrl = magnetEl.attr("href")

            // Jsoup may absolutify the href — strip the base URL if so
            if (magnetUrl.contains("://") && magnetUrl.contains("magnet:")) {
                val idx = magnetUrl.indexOf("magnet:")
                if (idx > 0) magnetUrl = magnetUrl.substring(idx)
            }

            if (magnetUrl.isBlank() || !magnetUrl.startsWith("magnet:")) return null

            val title = titleFromMagnet(magnetUrl)
                ?: stripDownloadPrefix(cleanTitleText(magnetEl.text()))
            if (title.isBlank()) return null

            val posterUrl = row.select("img.posterIcon")
                .find { it.attr("src").contains("211677") }
                ?.parent()?.attr("href") ?: ""

            val fileSize = row.selectFirst("div.fsize")?.text()?.trim() ?: ""

            // Use detail page link or fallback to mainUrl as the first part
            // (must start with https:// so CloudStream doesn't resolve against mainUrl)
            val detailHref = row.selectFirst("a[href*=tid=]")
                ?.attr("href") ?: ""
            val pageUrl = if (detailHref.isNotBlank()) toAbsoluteUrl(detailHref) else "$mainUrl/"

            val displayName = if (fileSize.isNotEmpty()) "$title | $fileSize" else title
            val tvType = tvTypeFromTitle(title)
            val data = "$pageUrl|$magnetUrl|$title|$posterUrl|$fileSize"

            newAnimeSearchResponse(displayName, data, tvType) {
                this.posterUrl = posterUrl
                this.posterHeaders = imageHeaders
            }
        } catch (e: Exception) {
            Log.e(TAG, "toSearchResult Error: ${e.message}")
            null
        }
    }

    // ==================== SEARCH ====================

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val searchUrl = "$mainUrl/index.php?page=torrents&search=${URLEncoder.encode(query, "UTF-8")}&cat_id=ar_anime"
            val doc = app.get(searchUrl, headers = imageHeaders).document
            doc.select("table#torrents tr").mapNotNull { toSearchResult(it) }
                .distinctBy { it.name }
        } catch (e: Exception) {
            Log.e(TAG, "Search error: ${e.message}")
            emptyList()
        }
    }

    // ==================== LOAD (Detail Page) ====================

    override suspend fun load(url: String): LoadResponse? {
        // Data format: pageUrl|magnetUrl|title|posterUrl|fileSize
        val parts = url.split("|", limit = 5)
        val magnetUrl = fixMagnetUrl(parts.getOrNull(1) ?: return null)
        val title = parts.getOrNull(2) ?: "Unknown"
        val posterUrl = parts.getOrNull(3) ?: ""
        val fileSize = parts.getOrNull(4) ?: ""

        if (!magnetUrl.startsWith("magnet:")) {
            Log.e(TAG, "load: invalid magnet URL: ${magnetUrl.take(80)}")
            return null
        }

        Log.d(TAG, "load: title='$title', magnet=${magnetUrl.take(60)}...")

        val tvType = tvTypeFromTitle(title)

        // Return a Movie-style response so user sees "Play" button.
        // The magnet link is stored as the data for loadLinks().
        // CloudStream natively handles magnet links — no TorrServe needed.
        return newMovieLoadResponse(title, url, tvType, "amm|$magnetUrl") {
            this.posterUrl = posterUrl
            this.posterHeaders = imageHeaders
        }
    }

    // ==================== LOAD LINKS ====================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(TAG, "loadLinks: data=${data.take(80)}...")

        // Format: amm|MAGNET_URL
        if (data.startsWith("amm|")) {
            val magnetUrl = fixMagnetUrl(data.substringAfter("amm|"))
            if (magnetUrl.startsWith("magnet:")) {
                Log.d(TAG, "loadLinks: passing magnet link to CloudStream")
                callback(
                    newExtractorLink(
                        source = this.name,
                        name = "${this.name}",
                        url = magnetUrl,
                        type = ExtractorLinkType.MAGNET
                    )
                )
                return true
            }
        }

        // Fallback: direct magnet in data
        val magnetUrl = if (data.startsWith("magnet:")) {
            data
        } else if (data.contains("magnet:")) {
            fixMagnetUrl(data.substring(data.indexOf("magnet:")))
        } else {
            null
        }

        if (magnetUrl != null && magnetUrl.startsWith("magnet:")) {
            callback(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = magnetUrl,
                    type = ExtractorLinkType.MAGNET
                )
            )
            return true
        }

        Log.e(TAG, "loadLinks: no magnet link found in: ${data.take(100)}")
        return false
    }
}
