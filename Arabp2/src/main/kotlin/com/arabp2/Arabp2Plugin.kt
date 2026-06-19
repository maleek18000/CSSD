package com.arabp2

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class Arabp2Plugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Arabp2())
    }
}
