package com.arabicsource

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ArabicSourcePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(ArabicSource())
    }
}