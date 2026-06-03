package com.arabp

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.lagradost.cloudstream3.actions.VideoClickAction
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.ui.result.LinkLoadingResult
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.UiText
import com.lagradost.cloudstream3.utils.txt

@CloudstreamPlugin
class ArabpPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Arabp())
        // Register Amnis Player as a long-press "Play with..." action.
        // When a user long-presses on a MAGNET or TORRENT source,
        // "Amnis Player" will appear alongside VLC, BiglyBT, etc.
        registerVideoClickAction(AmnisPlayerAction())
    }
}

/**
 * Opens magnet/torrent links in Amnis Player (com.amnis).
 *
 * This follows the same pattern as CloudStream's built-in BiglyBT action:
 * - Only shows in the long-press menu if Amnis Player is installed
 * - Only appears for MAGNET and TORRENT source types
 * - User picks one source, then Amnis Player is launched with that URI
 *
 * Because this uses VideoClickAction (not a separate ExtractorLink),
 * there is NO need to add "Amnis Player" as a third source in loadLinks().
 * It automatically appears as a long-press option on any existing source.
 */
class AmnisPlayerAction : VideoClickAction() {
    override val name: UiText = txt("Amnis Player")
    override val sourceTypes: Set<ExtractorLinkType> =
        setOf(ExtractorLinkType.MAGNET, ExtractorLinkType.TORRENT)
    override val oneSource: Boolean = true
    override val isPlayer: Boolean = true

    companion object {
        private const val AMNIS_PACKAGE = "com.amnis"
    }

    override fun shouldShow(context: Context?, video: ResultEpisode?): Boolean {
        if (context == null) return false
        return try {
            context.packageManager.getPackageInfo(AMNIS_PACKAGE, 0) != null
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun runAction(
        context: Context?,
        video: ResultEpisode,
        result: LinkLoadingResult,
        index: Int?
    ) {
        if (context == null) return
        val link = result.links.getOrNull(index ?: 0) ?: return
        val uri = Uri.parse(link.url)

        // Launch Amnis Player directly with the magnet/torrent URI
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage(AMNIS_PACKAGE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            launch(intent)
        } catch (e: Exception) {
            // Fallback: open with any app that handles magnet/torrent URIs
            // (Android will show a chooser if Amnis isn't the default)
            val fallbackIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                launch(fallbackIntent)
            } catch (e2: Exception) {
                // No app available to handle the link
            }
        }
    }
}
