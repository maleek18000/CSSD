package com.stremio

import android.content.Context
import android.widget.EditText
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.CloudStreamApp

/**
 * Settings UI for the Stremio plugin.
 * Uses android.app.AlertDialog instead of AndroidX BottomSheetDialogFragment
 * since the AndroidX/Material libraries are not available in the plugin SDK classpath.
 */
class StremioSettingsFragment(
    private val plugin: StremioPlugin
) {

    private val mapper = jacksonObjectMapper()

    /**
     * Show the settings dialog. Call from openSettings callback.
     */
    fun show(context: Context) {
        showMainMenuDialog(context)
    }

    private fun showMainMenuDialog(context: Context) {
        val options = arrayOf("Add Stremio Addon Link", "List Saved Links")
        android.app.AlertDialog.Builder(context)
            .setTitle("Stremio Addon Settings")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showAddLinkDialog(context)
                    1 -> showListLinksDialog(context)
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showAddLinkDialog(context: Context) {
        val input = EditText(context)
        input.hint = "Stremio addon URL (e.g., https://addon.example.com/manifest.json)"
        input.setSingleLine()
        input.setPadding(48, 24, 48, 24)
        android.app.AlertDialog.Builder(context)
            .setTitle("Add Stremio Addon Link")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val url = input.text.toString().trim()
                if (url.isNotEmpty()) {
                    addLink(url)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showListLinksDialog(context: Context) {
        val links = getLinks()
        if (links.isEmpty()) {
            android.app.AlertDialog.Builder(context)
                .setTitle("Saved Stremio Addon Links")
                .setMessage("No links saved yet. Add a link first.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val names = links.mapIndexed { index, link ->
            "${index + 1}. ${link.name} (${link.type})\n   ${link.mainUrl}"
        }.toTypedArray()

        android.app.AlertDialog.Builder(context)
            .setTitle("Saved Stremio Addon Links")
            .setItems(names) { _, which ->
                // Show detail for selected link with option to remove
                showLinkDetailDialog(context, links[which], which)
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showLinkDetailDialog(context: Context, link: Link, index: Int) {
        android.app.AlertDialog.Builder(context)
            .setTitle(link.name)
            .setMessage("Type: ${link.type}\nURL: ${link.mainUrl}")
            .setPositiveButton("Remove") { _, _ ->
                removeLink(index)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addLink(url: String) {
        val links = getLinks().toMutableList()
        val fixedUrl = url.replace("/manifest.json", "")
        val name = fixedUrl.substringAfterLast("/").substringBefore(".")
            .replace("-", " ").replace("_", " ").trim()

        // Determine type - default to StremioC for catalog-based
        val type = "StremioC"
        links.add(Link(name, type, fixedUrl))
        saveLinks(links)
        plugin.reload()
    }

    private fun removeLink(index: Int) {
        val links = getLinks().toMutableList()
        if (index in links.indices) {
            links.removeAt(index)
            saveLinks(links)
            plugin.reload()
        }
    }

    private fun getLinks(): Array<Link> {
        return try {
            val context = CloudStreamApp.context ?: return emptyArray()
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
    }

    private fun saveLinks(links: List<Link>) {
        try {
            val context = CloudStreamApp.context ?: return
            val prefs = context.getSharedPreferences("stremio_prefs", 0)
            val json = mapper.writeValueAsString(links.toTypedArray())
            prefs.edit().putString("stremio_links", json).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
