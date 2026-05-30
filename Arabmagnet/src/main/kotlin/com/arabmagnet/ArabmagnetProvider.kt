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

        /**
         * Public tracker list to append to magnet URLs.
         * CloudStream's built-in WebTorrent needs these to find peers.
         * Without trackers, WebTorrent relies on DHT which is unreliable on mobile.
         */
        private val PUBLIC_TRACKERS = listOf(
            "udp://tracker.opentrackr.org:1337/announce",
            "udp://open.tracker.cl:1337/announce",
            "udp://tracker.openbittorrent.com:6969/announce",
            "udp://open.stealth.si:80/announce",
            "udp://tracker.torrent.eu.org:451/announce",
            "udp://exodus.desync.com:6969/announce",
            "udp://tracker.tiny-vps.com:6969/announce",
            "udp://tracker.moeking.me:6969/announce",
            "udp://explodie.org:6969/announce",
            "udp://tracker.pomf.se:80/announce",
            "udp://tracker.dler.org:6969/announce",
            "udp://p4p.arenabg.com:1337/announce",
            "udp://movies.zsw.ca:6969/announce",
            "udp://retracker.lanta-net.ru:2710/announce",
            "http://tracker.openbittorrent.com:80/announce",
            "http://tracker.opentrackr.org:1337/announce",
            "https://tracker.lilithraws.org:443/announce",
            "udp://tracker1.myporno.club:80/announce",
            "udp://tracker.theoks.net:6969/announce",
            "udp://tracker-udp.gbitt.info:80/announce",
            "udp://retracker01-msk-virt.corbina.net:80/announce",
            "udp://authing.tk:6969/announce",
            "udp://bt2.archive.org:6969/announce",
            "udp://bt1.archive.org:6969/announce"
        )
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
     * Strip the base URL that CloudStream's fixUrl() prepends to
     * SearchResponse URLs that start with "magnet:".
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

    /**
     * Append public trackers to a magnet URL to improve peer discovery
     * in CloudStream's built-in WebTorrent client.
     * Without trackers, WebTorrent relies on DHT which is unreliable on mobile.
     */
    private fun enrichMagnet(magnetUrl: String): String {
        if (!magnetUrl.startsWith("magnet:")) return magnetUrl

        val existingTrackers = mutableSetOf<String>()
        // Extract existing tr= parameters
        val trPattern = Regex("""[&?]tr=([^&]*)""")
        trPattern.findAll(magnetUrl).forEach { match ->
            existingTrackers.add(match.groupValues[1].lowercase())
        }

        val sb = StringBuilder(magnetUrl)
        for (tracker in PUBLIC_TRACKERS) {
            val encoded = URLEncoder.encode(tracker, "UTF-8")
            if (encoded.lowercase() !in existingTrackers) {
                sb.append("&tr=").append(encoded)
            }
        }

        Log.d(TAG, "enrichMagnet: added ${PUBLIC_TRACKERS.size - existingTrackers.size} trackers")
        return sb.toString()
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
     * PAGE_URL starts with https:// so CloudStream's fixUrl() doesn't corrupt it.
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

            // Must start with https:// so CloudStream doesn't corrupt it
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

        // MovieLoadResponse → user sees "Play" button
        // Data passed to loadLinks: amm|MAGNET_URL
        // (dataUrl is NOT modified by CloudStream — no fixUrl applied)
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
        Log.d(TAG, "loadLinks: data=${data.take(100)}...")

        // Extract magnet URL from any format
        var magnetUrl: String? = null

        // Format: amm|MAGNET_URL
        if (data.startsWith("amm|")) {
            val raw = data.substringAfter("amm|")
            magnetUrl = fixMagnetUrl(raw)
        }
        // Fallback: direct magnet link
        else if (data.startsWith("magnet:")) {
            magnetUrl = data.substringBefore("|")
        }
        // Fallback: magnet embedded somewhere in data
        else if (data.contains("magnet:")) {
            val raw = data.substring(data.indexOf("magnet:"))
            magnetUrl = fixMagnetUrl(raw.substringBefore("|"))
        }

        if (magnetUrl == null || !magnetUrl.startsWith("magnet:")) {
            Log.e(TAG, "loadLinks: no magnet link found in: ${data.take(100)}")
            return false
        }

        // Enrich with public trackers so CloudStream's WebTorrent can find peers
        val enrichedMagnet = enrichMagnet(magnetUrl)
        Log.d(TAG, "loadLinks: passing magnet with ${PUBLIC_TRACKERS.size} trackers, length=${enrichedMagnet.length}")

        callback(
            newExtractorLink(
                source = this.name,
                name = this.name,
                url = enrichedMagnet,
                type = ExtractorLinkType.MAGNET
            )
        )
        return true
    }
}
