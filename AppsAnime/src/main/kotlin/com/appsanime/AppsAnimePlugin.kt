package com.appsanime

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AppsAnimePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AppsAnimeProvider())
    }
}
