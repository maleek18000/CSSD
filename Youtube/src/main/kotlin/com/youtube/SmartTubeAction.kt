package com.youtube

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.lagradost.cloudstream3.actions.OpenInAppAction
import com.lagradost.cloudstream3.ui.result.LinkLoadingResult
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.UiText
import com.lagradost.cloudstream3.utils.txt

/**
 * Adds "Play in SmartTube" to the long-press menu for YouTube videos.
 *
 * SmartTube is a native Android TV YouTube client that handles all of YouTube's
 * anti-bot measures (n-sig decryption, PO tokens, SABR) internally — so seeking
 * and all qualities work perfectly.
 *
 * SmartTube's package names (by flavor):
 *   - stable: org.smarttube.stable
 *   - beta: org.smarttube.beta
 *   - fdroid: app.smarttube.fdroid
 *
 * SmartTube accepts ACTION_VIEW with:
 *   - https://www.youtube.com/watch?v=VIDEO_ID
 *   - https://youtu.be/VIDEO_ID
 *   - vnd.youtube://VIDEO_ID
 *
 * We register one action per flavor. Only the installed flavor(s) will show
 * in the long-press menu (via shouldShow → isAppInstalled check).
 */

/** Stable flavor — the most common SmartTube build. */
class SmartTubeStableAction : SmartTubeAction(
    appName = txt("SmartTube"),
    packageName = "org.smarttube.stable"
)

/** Beta flavor. */
class SmartTubeBetaAction : SmartTubeAction(
    appName = txt("SmartTube Beta"),
    packageName = "org.smarttube.beta"
)

/** F-Droid flavor. */
class SmartTubeFdroidAction : SmartTubeAction(
    appName = txt("SmartTube F-Droid"),
    packageName = "app.smarttube.fdroid"
)

/**
 * Base action that launches SmartTube with the YouTube watch URL.
 *
 * We forward video.data (the YouTube URL, e.g. https://www.youtube.com/watch?v=ID)
 * as the intent data — NOT the googlevideo stream URL. SmartTube fetches the
 * video itself using its full anti-bot stack.
 */
abstract class SmartTubeAction(
    appName: UiText,
    packageName: String
) : OpenInAppAction(
    appName = appName,
    packageName = packageName,
    intentClass = null,   // let Android resolve via SmartTube's intent filter
    action = Intent.ACTION_VIEW
) {
    // SmartTube handles the video entirely — no need for source/mirror selection
    override val oneSource = false

    override suspend fun putExtra(
        context: Context,
        intent: Intent,
        video: ResultEpisode,
        result: LinkLoadingResult,
        index: Int?
    ) {
        // video.data is the YouTube URL stored by YoutubeProvider (e.g.
        // "https://www.youtube.com/watch?v=VIDEO_ID" or ".../shorts/VIDEO_ID").
        // SmartTube's intent filter accepts these URLs and plays the video.
        val watchUrl = normalizeYouTubeUrl(video.data)
        intent.data = Uri.parse(watchUrl)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    override fun onResult(activity: Activity, intent: Intent?) {
        // SmartTube doesn't return playback position via intent extras,
        // so we don't update CloudStream's watch progress here.
    }

    /**
     * Ensure the data is a valid YouTube watch URL.
     * video.data should already be a full URL, but handle edge cases.
     */
    private fun normalizeYouTubeUrl(data: String): String {
        val raw = data.trim()
        // Already a full URL
        if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) {
            return raw
        }
        // Bare video ID (11 chars)
        if (raw.matches(Regex("[A-Za-z0-9_-]{11}"))) {
            return "https://www.youtube.com/watch?v=$raw"
        }
        // vnd.youtube://SCHEME
        if (raw.startsWith("vnd.youtube://")) {
            val id = raw.removePrefix("vnd.youtube://").substringBefore("?").trim()
            return "https://www.youtube.com/watch?v=$id"
        }
        // Fallback: treat as video ID
        return "https://www.youtube.com/watch?v=$raw"
    }
}
