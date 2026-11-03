package app.tvcouchbrowser

import android.webkit.JavascriptInterface
import org.json.JSONArray

/**
 * Exposed to every page as `window.TVCB`. Pages can call it too, so it only hands out CSS and
 * takes a media-playing flag: nothing that navigates, reads data or runs code.
 */
class PageBridge(
    private val blocker: AdBlocker,
    private val onMedia: (Boolean) -> Unit,
) {
    private fun active(host: String) = blocker.enabled && !blocker.isAllowlisted(host)

    @JavascriptInterface
    fun adblockOn(host: String): Boolean = active(host)

    @JavascriptInterface
    fun cosmetic(host: String, topFrame: Boolean): String = if (active(host)) blocker.cosmeticCss(host, topFrame) else ""

    @JavascriptInterface
    fun generic(host: String, tokensJson: String): String {
        if (!active(host)) return ""
        val arr = try { JSONArray(tokensJson) } catch (e: Exception) { return "" }
        val tokens = ArrayList<String>(minOf(arr.length(), 5000))
        for (i in 0 until minOf(arr.length(), 5000)) tokens.add(arr.optString(i))
        return blocker.genericCss(host, tokens)
    }

    @JavascriptInterface
    fun media(playing: Boolean) = onMedia(playing)
}
