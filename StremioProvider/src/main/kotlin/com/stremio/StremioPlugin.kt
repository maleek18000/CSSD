package com.stremio

import android.content.Context
import android.widget.EditText
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.AcraApplication
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import java.util.Locale

@CloudstreamPlugin
class StremioPlugin : Plugin() {

    private val mapper = jacksonObjectMapper()

    override fun load(context: Context) {
        val appLocale = context.getSharedPreferences("stremio_prefs", 0)
            .getString("app_locale", null) ?: getCurrentLocale()
        AcraApplication.setKey("app_locale", appLocale)

        reload()

        openSettings = { ctx ->
            StremioSettingsFragment(this).show(ctx)
        }
    }

    fun reload() {
        // Load links from SharedPreferences
        val links: Array<Link> = try {
            val context = CloudStreamApp.context ?: return
            val prefs = context.getSharedPreferences("stremio_prefs", 0)
            val linksJson = prefs.getString("stremio_links", null)
            if (linksJson != null) {
                mapper.readValue<Array<Link>>(linksJson)
            } else {
                emptyArray()
            }
        } catch (e: Exception) {
            emptyArray()
        }

        // If no links are configured, register a default StremioC provider
        if (links.isEmpty()) {
            registerMainAPI(StremioC("https://stremio.github.io/stremio-static-addon-example", "Stremio Example"))
            return
        }

        // Register providers for each link
        for (link in links) {
            when (link.type) {
                "StremioX" -> registerMainAPI(StremioX(link.mainUrl, link.name))
                "StremioC" -> registerMainAPI(StremioC(link.mainUrl, link.name))
                else -> registerMainAPI(StremioC(link.mainUrl, link.name))
            }
        }
    }

    fun getMapper() = mapper

    companion object {
        fun getCurrentLocale(): String {
            return Locale.getDefault().toLanguageTag().split("-").first()
        }
    }
}
