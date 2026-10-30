package app.tvcouchbrowser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

data class Bookmark(val url: String, val title: String)

/** Start-screen bookmarks in order, with a page thumbnail per bookmark when one was captured. */
class Bookmarks(context: Context) {
    private val prefs = context.getSharedPreferences("bookmarks", Context.MODE_PRIVATE)
    private val thumbs = File(context.filesDir, "thumbs").apply { mkdirs() }

    var items: MutableList<Bookmark> = load()
        private set

    private fun load(): MutableList<Bookmark> {
        val json = prefs.getString("list", null) ?: return DEFAULTS.toMutableList()
        val arr = JSONArray(json)
        return MutableList(arr.length()) {
            val o = arr.getJSONObject(it); Bookmark(o.getString("url"), o.getString("title"))
        }
    }

    private fun save() {
        val arr = JSONArray()
        items.forEach { arr.put(JSONObject().put("url", it.url).put("title", it.title)) }
        prefs.edit().putString("list", arr.toString()).apply()
    }

    fun contains(url: String) = items.any { it.url == url }

    fun add(b: Bookmark, thumb: Bitmap?) {
        items.removeAll { it.url == b.url }
        items.add(b)
        thumb?.let { bmp -> thumbFile(b.url).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) } }
        save()
    }

    fun remove(index: Int) {
        thumbFile(items.removeAt(index).url).delete(); save()
    }

    /** Moves the bookmark at [from] to [to] (clamped) and returns its new index. */
    fun move(from: Int, to: Int): Int {
        val t = to.coerceIn(0, items.size - 1)
        items.add(t, items.removeAt(from)); save()
        return t
    }

    fun thumb(url: String): Bitmap? = thumbFile(url).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }

    private fun thumbFile(url: String): File {
        val hex = MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(thumbs, "$hex.jpg")
    }

    companion object {
        val DEFAULTS = listOf(
            Bookmark("https://duckduckgo.com/", "DuckDuckGo"),
            Bookmark("https://www.youtube.com/", "YouTube"),
            Bookmark("https://en.wikipedia.org/", "Wikipedia"),
            Bookmark("https://www.bbc.com/news", "BBC News"),
        )
    }
}
