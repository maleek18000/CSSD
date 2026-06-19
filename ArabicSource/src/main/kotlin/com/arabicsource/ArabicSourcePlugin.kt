package com.arabicsource

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
class ArabicSourcePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(ArabicSource())
        registerVideoClickAction(AmnisPlayerAction())
    }
}

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

        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage(AMNIS_PACKAGE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            launch(intent)
        } catch (e: Exception) {
            val fallbackIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                launch(fallbackIntent)
            } catch (_: Exception) {}
        }
    }
}