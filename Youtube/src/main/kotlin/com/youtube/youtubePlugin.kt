package com.youtube

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class YoutubeTokenPlugin: Plugin() {
    override fun load(context: Context) {

        val sharedPref = context.getSharedPreferences("YouTube", Context.MODE_PRIVATE)

        registerMainAPI(com.lagradost.cloudstream3.ar.youtube.YoutubeProvider(sharedPref))

        // Register SmartTube as an external player option.
        // When you long-press a YouTube video, "Play in SmartTube" appears
        // in the menu (only if SmartTube is installed).
        // Three flavors are registered; only the installed one(s) will show.
        registerVideoClickAction(SmartTubeStableAction())
        registerVideoClickAction(SmartTubeBetaAction())
        registerVideoClickAction(SmartTubeFdroidAction())

        openSettings = { ctx ->

            val activity = ctx as? AppCompatActivity

            if (activity != null) {

                com.youtube.YoutubeSettingsBottomSheet.show(activity.supportFragmentManager, sharedPref)
            }
        }
    }
}
