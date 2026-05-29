package com.arabmagnet

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ArabmagnetPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Arabmagnet())
    }
}
