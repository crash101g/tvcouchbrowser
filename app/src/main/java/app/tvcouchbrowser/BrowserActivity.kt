package app.tvcouchbrowser

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.Inet4Address
import kotlin.math.min

class BrowserActivity : Activity(), PhoneServer.Actions {

    /** A tab. Its WebView is destroyed when memory runs low and recreated from [url] when shown again. */
    private inner class Tab(var url: String?, var title: String = "") {
        var web: WebView? = null
        @Volatile var pageHost: String? = null
        @Volatile var blocked = 0
        var favicon: Bitmap? = null
        var jsDialogs = 0 // alert/confirm/prompt shown by the current page
    }

    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var blocker: AdBlocker
    private lateinit var bookmarks: Bookmarks
    private lateinit var tokens: TokenStore
    private lateinit var server: PhoneServer
    private lateinit var pageScript: String

    private lateinit var root: FrameLayout
    private lateinit var webFrame: FrameLayout
    private lateinit var startScreen: ScrollView
    private lateinit var startGrid: GridLayout
    private lateinit var cursor: CursorView
    private lateinit var addressBar: LinearLayout
    private lateinit var addressField: EditText
    private lateinit var menu: FrameLayout
    private lateinit var menuGrid: GridLayout
    private lateinit var toastView: TextView
    private lateinit var fullFrame: FrameLayout

    private val tabs = ArrayList<Tab>()
    private var current = 0
    private val tab get() = tabs[current]

    private var cursorMode = true
    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private var dialog: AlertDialog? = null
    private val ui = Handler(Looper.getMainLooper())
    private val density by lazy { resources.displayMetrics.density }

    // ---- lifecycle ----------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("browser", MODE_PRIVATE)
        blocker = AdBlocker(applicationContext)
        bookmarks = Bookmarks(this)
        tokens = TokenStore(this)
        server = PhoneServer(applicationContext, tokens, this)
        pageScript = assets.open("tvcb.js").use { String(it.readBytes()) }
        cursorMode = prefs.getBoolean("cursorMode", true)

        buildUi()
        blocker.start { msg -> if (msg != "Filter lists loaded") ui.post { toast(msg) } }

        restoreTabs()
        val viewUrl = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.dataString
        when {
            viewUrl != null -> load(viewUrl)
            tab.url != null -> showTab(current)
            else -> showStart()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.takeIf { it.action == Intent.ACTION_VIEW }?.dataString?.let { load(it) }
    }

    override fun onStart() {
        super.onStart()
        server.start()
        scheduleListUpdate()
    }

    // Filter lists update once a day. Launch handles a stale set; this covers a TV left on the
    // browser for days: the next update is due a day after the last one.
    private val listUpdate: Runnable = object : Runnable {
        override fun run() {
            blocker.updateIfStale { msg -> Log.i(TAG, "filter lists: $msg") }
            ui.postDelayed(this, AdBlocker.MAX_AGE_MS) // a failed update waits a day too
        }
    }

    private fun scheduleListUpdate() {
        ui.removeCallbacks(listUpdate)
        val due = blocker.lastUpdated + AdBlocker.MAX_AGE_MS - System.currentTimeMillis()
        ui.postDelayed(listUpdate, due.coerceIn(60_000L, AdBlocker.MAX_AGE_MS))
    }

    override fun onResume() {
        super.onResume()
        tab.web?.onResume()
    }

    override fun onPause() {
        tab.web?.onPause()
        saveTabs()
        super.onPause()
    }

    override fun onStop() {
        ui.removeCallbacks(listUpdate)
        server.stop()
        super.onStop()
    }

    override fun onDestroy() {
        tabs.forEach { it.web?.destroy() }
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) unloadBackgroundTabs()
    }

    private fun unloadBackgroundTabs() {
        tabs.forEachIndexed { i, t ->
            if (i != current && t.web != null) {
                t.web?.destroy(); t.web = null
                Log.i(TAG, "unloaded tab $i (${t.url})")
            }
        }
    }

    private fun saveTabs() {
        val arr = JSONArray()
        tabs.forEach { arr.put(JSONObject().put("url", it.url ?: "").put("title", it.title)) }
        prefs.edit().putString("tabs", arr.toString()).putInt("current", current).apply()
    }

    private fun restoreTabs() {
        val arr = try { JSONArray(prefs.getString("tabs", "[]")) } catch (e: Exception) { JSONArray() }
        for (i in 0 until min(arr.length(), MAX_TABS)) {
            val o = arr.getJSONObject(i)
            tabs.add(Tab(o.optString("url").ifEmpty { null }, o.optString("title")))
        }
        if (tabs.isEmpty()) tabs.add(Tab(null))
        current = prefs.getInt("current", 0).coerceIn(0, tabs.size - 1)
    }

    // ---- UI -------------------------------------------------------------------------------------

    private fun dp(v: Number) = (v.toFloat() * density).toInt()

    private fun focusBackground(radius: Float, normal: Int, focused: Int): StateListDrawable {
        fun shape(color: Int, stroke: Int) = GradientDrawable().apply {
            cornerRadius = radius; setColor(color); if (stroke != 0) setStroke(dp(4), stroke)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), shape(focused, Color.WHITE))
            addState(intArrayOf(), shape(normal, 0))
        }
    }

    /** A large tile that grows and lights up when focused, readable from across the room. */
    private fun tile(label: String, symbol: String?, w: Int, h: Int, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = if (symbol != null) "$symbol\n$label" else label
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            isFocusable = true
            isFocusableInTouchMode = true
            background = focusBackground(dp(14).toFloat(), 0xFF2A3446.toInt(), 0xFF2F7FE0.toInt())
            layoutParams = GridLayout.LayoutParams().apply { width = dp(w); height = dp(h); setMargins(dp(8), dp(8), dp(8), dp(8)) }
            setOnClickListener { onClick() }
            setOnFocusChangeListener { v, f -> v.animate().scaleX(if (f) 1.08f else 1f).scaleY(if (f) 1.08f else 1f).setDuration(100).start() }
        }

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(0xFF101318.toInt()) }
        val match = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        webFrame = FrameLayout(this)
        root.addView(webFrame, match)

        startGrid = GridLayout(this).apply { columnCount = 4; useDefaultMargins = false }
        val startColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(32), dp(24), dp(32), dp(24))
            addView(TextView(context).apply {
                text = getString(R.string.app_name); setTextColor(0xFF9FB3C8.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f); setPadding(0, 0, 0, dp(12))
            })
            addView(startGrid)
            addView(TextView(context).apply {
                text = "Menu button (or hold Back): browser menu  ·  Hold OK on a tile: move or remove"
                setTextColor(0xFF6B7D92.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); setPadding(0, dp(18), 0, 0)
            })
        }
        startScreen = ScrollView(this).apply { addView(startColumn); setBackgroundColor(0xFF101318.toInt()); isFillViewport = true }
        root.addView(startScreen, match)

        cursor = CursorView(this)
        root.addView(cursor, match)

        addressField = EditText(this).apply {
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setTextColor(Color.WHITE); setHintTextColor(0xFF6B7D92.toInt())
            hint = "Search or type a web address"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(0xFF0C0F14.toInt()); setStroke(dp(3), 0xFF4DA3FF.toInt()) }
            setPadding(dp(18), dp(12), dp(18), dp(12))
            setOnEditorActionListener { _, _, _ -> submitAddress(); true }
        }
        addressBar = LinearLayout(this).apply {
            setPadding(dp(40), dp(24), dp(40), dp(24))
            setBackgroundColor(0xEE101318.toInt())
            addView(addressField, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            visibility = View.GONE
        }
        root.addView(addressBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        menuGrid = GridLayout(this).apply { columnCount = 5 }
        menu = FrameLayout(this).apply {
            setBackgroundColor(0xDD000000.toInt())
            isClickable = true // a phone-trackpad tap on the backdrop must not reach the page behind it
            addView(menuGrid, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            visibility = View.GONE
        }
        root.addView(menu, match)

        fullFrame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK); visibility = View.GONE }
        root.addView(fullFrame, match)

        toastView = TextView(this).apply {
            setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setPadding(dp(24), dp(12), dp(24), dp(12))
            background = GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(0xE0000000.toInt()) }
            alpha = 0f
        }
        root.addView(toastView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(40) })

        setContentView(root)
    }

    private fun toast(msg: String) {
        toastView.text = msg
        toastView.animate().cancel()
        toastView.alpha = 1f
        toastView.animate().alpha(0f).setStartDelay(1800).setDuration(400).start()
    }

    private val browsing get() = startScreen.visibility != View.VISIBLE && tab.web != null

    // ---- start screen -------------------------------------------------------------------------

    private fun showStart(focusIndex: Int = -1) {
        startGrid.removeAllViews()
        val search = tile("Search or type a web address", "🔍", 0, 0) { openAddress() }
        search.layoutParams = GridLayout.LayoutParams(GridLayout.spec(0), GridLayout.spec(0, 4)).apply {
            width = dp(4 * tileWidthDp + 3 * 16); height = dp(76); setMargins(dp(8), dp(8), dp(8), dp(16))
        }
        startGrid.addView(search)
        bookmarks.items.forEachIndexed { i, b -> startGrid.addView(bookmarkTile(i, b)) }
        startScreen.visibility = View.VISIBLE
        cursor.visibility = View.GONE
        tab.web?.onPause()
        val target = if (focusIndex >= 0) startGrid.getChildAt(focusIndex + 1) else startGrid.getChildAt(if (bookmarks.items.isEmpty()) 0 else 1)
        startGrid.post { (target ?: search).requestFocus() }
    }

    // Four bookmark tiles per row, sized to the screen (32 dp side padding, 8 dp margins around each tile).
    private val tileWidthDp by lazy { ((resources.displayMetrics.widthPixels / density - 64) / 4 - 16).toInt() }

    private fun bookmarkTile(i: Int, b: Bookmark): View {
        val w = dp(tileWidthDp); val h = dp(tileWidthDp * 3 / 4 + 30)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isFocusable = true
            isFocusableInTouchMode = true
            background = focusBackground(dp(14).toFloat(), 0xFF1B2230.toInt(), 0xFF2F7FE0.toInt())
            setPadding(dp(8), dp(8), dp(8), dp(8))
            layoutParams = GridLayout.LayoutParams().apply { width = w; height = h; setMargins(dp(8), dp(8), dp(8), dp(8)) }
            setOnFocusChangeListener { v, f -> v.animate().scaleX(if (f) 1.08f else 1f).scaleY(if (f) 1.08f else 1f).setDuration(100).start() }
            setOnClickListener { load(b.url) }
            setOnLongClickListener { bookmarkOptions(i); true }
        }
        val thumb = bookmarks.thumb(b.url)
        val image: View = if (thumb != null) ImageView(this).apply {
            setImageBitmap(thumb); scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true
            background = GradientDrawable().apply { cornerRadius = dp(8).toFloat() }
        } else TextView(this).apply {
            text = (b.title.firstOrNull { it.isLetterOrDigit() } ?: '•').uppercase()
            gravity = Gravity.CENTER; setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 44f)
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat(); setColor(PALETTE[(b.url.hashCode() and 0x7fffffff) % PALETTE.size])
            }
        }
        box.addView(image, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        box.addView(TextView(this).apply {
            text = b.title.ifBlank { Uri.parse(b.url).host ?: b.url }
            setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); setPadding(dp(4), dp(6), dp(4), 0)
        })
        return box
    }

    private fun bookmarkOptions(i: Int) {
        val b = bookmarks.items[i]
        showDialog(b.title, listOf(
            "Move left" to { showStart(bookmarks.move(i, i - 1)) },
            "Move right" to { showStart(bookmarks.move(i, i + 1)) },
            "Move to first" to { showStart(bookmarks.move(i, 0)) },
            "Remove" to { bookmarks.remove(i); showStart(i.coerceAtMost(bookmarks.items.size - 1)) },
        ))
    }

    // ---- tabs and WebViews --------------------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun createWebView(t: Tab): WebView {
        val web = WebView(this)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false)
        // Don't tell every site this is an app's WebView (the X-Requested-With header): sites and
        // captchas treat WebViews with extra suspicion.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(web.settings, emptySet())
        }
        web.isFocusable = true
        web.addJavascriptInterface(PageBridge(blocker) { playing -> ui.post { keepScreenOn(playing) } }, "TVCB")
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(web, pageScript, setOf("*"))
        }
        web.webViewClient = Client(t)
        web.webChromeClient = Chrome(t)
        web.setDownloadListener { _, _, _, _, _ -> toast("Downloads aren't supported") }
        return web
    }

    private inner class Client(val t: Tab) : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            if (request.isForMainFrame || !blocker.enabled || blocker.isAllowlisted(t.pageHost)) return null
            if (!blocker.shouldBlock(request.url, t.pageHost)) return null
            t.blocked++
            return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val scheme = request.url.scheme?.lowercase()
            if (scheme == "http" || scheme == "https") {
                // A page sending itself to an ad or tracker domain without a click: a forced redirect.
                if (request.isForMainFrame && !request.hasGesture() && blocker.enabled && !blocker.isAllowlisted(t.pageHost) &&
                    t.pageHost != null && blocker.shouldBlock(request.url, t.pageHost)) {
                    Log.i(TAG, "blocked redirect to ${request.url}")
                    toast("Blocked a redirect to ${request.url.host}")
                    return true
                }
                if (request.isForMainFrame && applyUserAgent(view, request.url.toString())) {
                    view.loadUrl(request.url.toString()); return true
                }
                return false
            }
            toast("Can't open ${scheme ?: "this"} links")
            return true
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            t.pageHost = Uri.parse(url).host
            t.blocked = 0
            t.jsDialogs = 0
            t.url = url
        }

        override fun onPageFinished(view: WebView, url: String) {
            t.url = url
            saveTabs() // the process can be killed without onPause (low memory, reinstall)
            if (t === tab && isAutoClean(t.pageHost)) {
                cleanUpPage(quiet = true)
                ui.postDelayed({ if (t === tab) cleanUpPage(quiet = true) }, 3000) // late pop-ups
            }
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            Log.w(TAG, "renderer gone (crash=${detail.didCrash()}) for ${t.url}")
            val wasCurrent = tabs.indexOf(t) == current
            (view.parent as? ViewGroup)?.removeView(view)
            view.destroy()
            if (t.web === view) t.web = null
            if (wasCurrent && t.url != null) { toast("The page ran out of memory and was reloaded"); showTab(current) }
            return true
        }
    }

    private inner class Chrome(val t: Tab) : WebChromeClient() {
        override fun onReceivedTitle(view: WebView, title: String?) { t.title = title ?: "" }
        override fun onReceivedIcon(view: WebView, icon: Bitmap?) { t.favicon = icon }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (fullscreenView != null) { callback.onCustomViewHidden(); return }
            fullscreenView = view; fullscreenCallback = callback
            fullFrame.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            fullFrame.visibility = View.VISIBLE
            cursor.visibility = View.GONE
            keepScreenOn(true)
        }

        override fun onHideCustomView() {
            fullFrame.removeAllViews(); fullFrame.visibility = View.GONE
            fullscreenView = null; fullscreenCallback = null
            updateCursorVisibility()
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            // Only DRM (Widevine) for protected video; never camera or microphone.
            val drm = request.resources.filter { it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID }
            if (drm.isNotEmpty()) request.grant(drm.toTypedArray()) else request.deny()
        }

        // A page gets two alert/confirm/prompt dialogs; after that they are dismissed unseen, so a
        // dialog loop can't trap the remote.
        private fun tooManyDialogs(): Boolean {
            if (++t.jsDialogs <= MAX_JS_DIALOGS) return false
            if (t.jsDialogs == MAX_JS_DIALOGS + 1) ui.post { toast("Blocked more pop-up messages from this page") }
            return true
        }

        override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
            if (tooManyDialogs()) { result.cancel(); return true }
            return false
        }

        override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
            if (tooManyDialogs()) { result.cancel(); return true }
            return false
        }

        override fun onJsPrompt(view: WebView, url: String?, message: String?, defaultValue: String?, result: JsPromptResult): Boolean {
            if (tooManyDialogs()) { result.cancel(); return true }
            return false
        }

        // "Are you sure you want to leave?" never traps Back.
        override fun onJsBeforeUnload(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
            result.confirm(); return true
        }

        override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback) {
            callback.invoke(origin, false, false)
        }

        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message): Boolean {
            if (!isUserGesture) return false // pop-ups and pop-unders
            // A link with target=_blank: catch its URL with a throwaway WebView, then decide.
            val catcher = WebView(this@BrowserActivity)
            catcher.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                    v.destroy()
                    newWindow(t, r.url)
                    return true
                }
            }
            (resultMsg.obj as WebView.WebViewTransport).webView = catcher
            resultMsg.sendToTarget()
            return true
        }

        override fun getDefaultVideoPoster(): Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    }

    private fun showTab(index: Int) {
        current = index
        val t = tab
        var web = t.web
        if (web == null) {
            web = createWebView(t); t.web = web
            t.url?.let { applyUserAgent(web, it); web.loadUrl(it) }
        }
        if (web.parent !== webFrame) {
            webFrame.removeAllViews()
            (web.parent as? ViewGroup)?.removeView(web)
            webFrame.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        tabs.forEach { if (it !== t) it.web?.onPause() }
        web.onResume()
        startScreen.visibility = View.GONE
        web.requestFocus()
        updateCursorVisibility()
    }

    private fun openInNewTab(url: String) {
        if (tabs.size >= MAX_TABS) { load(url); toast("Opened here: ${MAX_TABS} tabs is the limit"); return }
        tabs.add(Tab(url)); showTab(tabs.size - 1)
    }

    /**
     * A new window the page opened after a click. Same-site windows open in a tab. A window to a
     * filtered ad domain is dropped. A window to another site is the click-anywhere pop-under trick
     * as often as a real link, so it asks first.
     */
    private fun newWindow(from: Tab, url: Uri) {
        val scheme = url.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") { toast("Blocked a pop-up"); return }
        val page = from.pageHost
        val active = blocker.enabled && !blocker.isAllowlisted(page)
        if (active && blocker.shouldBlockPopup(url, page)) { toast("Blocked a pop-up ad"); return }
        val host = url.host ?: return
        if (!active || page == null || AdBlocker.site(host) == AdBlocker.site(page)) { openInNewTab(url.toString()); return }
        showDialog("This page wants to open $host", listOf(
            "Open it in a new tab" to { openInNewTab(url.toString()) },
            "Open it here" to { load(url.toString()) },
            "Block it" to { toast("Blocked a pop-up") },
        ))
    }

    private fun closeTab(index: Int) {
        tabs[index].web?.let { (it.parent as? ViewGroup)?.removeView(it); it.destroy() }
        tabs.removeAt(index)
        if (tabs.isEmpty()) { tabs.add(Tab(null)); current = 0; showStart(); return }
        current = current.coerceAtMost(tabs.size - 1)
        if (tab.url == null) showStart() else showTab(current)
    }

    /** Loads [input] (a URL or a search) in the current tab. */
    private fun load(input: String) {
        val url = resolve(input) ?: run { toast("Can't open that address"); return }
        val existing = tab.web
        tab.url = url
        if (existing != null) { applyUserAgent(existing, url); existing.loadUrl(url) }
        showTab(current) // creates the WebView and loads tab.url if there wasn't one
    }

    /** Turns typed text into an http(s) URL, or a search URL. Returns null for other schemes. */
    private fun resolve(input: String): String? {
        val s = input.trim()
        val uri = Uri.parse(s)
        val scheme = uri.scheme?.lowercase()
        if (scheme == "http" || scheme == "https") return s
        if (scheme != null && !s.contains(' ') && !Regex("^[\\w.-]+:\\d+").containsMatchIn(s) && s.contains(':') &&
            !s.substringBefore(':').contains('.')) return null // javascript:, data:, file:, intent: ...
        val looksLikeHost = !s.contains(' ') && (Regex("^[\\w-]+(\\.[\\w-]+)+(:\\d+)?(/.*)?$").matches(s) ||
            s.startsWith("localhost"))
        if (looksLikeHost) return "https://$s"
        return SEARCH_ENGINES[searchEngine].second + Uri.encode(s)
    }

    private var searchEngine: Int
        get() = prefs.getInt("search", 0).coerceIn(0, SEARCH_ENGINES.size - 1)
        set(v) = prefs.edit().putInt("search", v).apply()

    // ---- user agent ---------------------------------------------------------------------------

    // WebView's user agent says "; wv" and "Version/4.0", which some sites and captchas treat as a
    // bot or refuse (Google sign-in). Without them it reads as ordinary Chrome for Android.
    private val defaultUa by lazy {
        WebSettings.getDefaultUserAgent(this).replace("; wv", "").replace(Regex("Version/[\\d.]+ "), "")
    }
    private val desktopUa by lazy {
        val chrome = Regex("Chrome/[\\d.]+").find(defaultUa)?.value ?: "Chrome/140.0.0.0"
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) $chrome Safari/537.36"
    }

    private fun desktopSites() = prefs.getStringSet("desktop", emptySet())!!

    private fun isDesktop(url: String?) = url?.let { Uri.parse(it).host }?.let { AdBlocker.site(it) in desktopSites() } ?: false

    /** Sets the user agent for [url]'s site; true if it changed (the load should be restarted). */
    private fun applyUserAgent(web: WebView, url: String): Boolean {
        val want = if (isDesktop(url)) desktopUa else defaultUa
        if (web.settings.userAgentString == want) return false
        web.settings.userAgentString = want
        return true
    }

    private fun toggleDesktop() {
        val host = tab.pageHost ?: return
        val site = AdBlocker.site(host)
        val set = HashSet(desktopSites())
        val on = !set.remove(site)
        if (on) set.add(site)
        prefs.edit().putStringSet("desktop", set).apply()
        tab.web?.let { applyUserAgent(it, tab.url ?: return@let); it.reload() }
        toast(if (on) "Desktop site on for $site" else "Mobile site for $site")
    }

    // ---- keys -----------------------------------------------------------------------------------

    private var okDownAt = 0L
    private var okLong = false
    private var backLong = false
    private var backDown = false

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val code = e.keyCode
        val down = e.action == KeyEvent.ACTION_DOWN

        // Back: long press opens the menu (for remotes without a Menu button), short press goes back.
        if (code == KeyEvent.KEYCODE_BACK) {
            if (down) {
                if (e.repeatCount == 0) { backDown = true; backLong = false }
                else if (e.isLongPress && !backLong && menu.visibility != View.VISIBLE) { backLong = true; openMenu() }
                return true
            }
            if (backDown && !backLong && !e.isCanceled) goBack()
            backDown = false
            return true
        }
        if (code == KeyEvent.KEYCODE_MENU) {
            if (!down) { if (menu.visibility == View.VISIBLE) closeMenu() else openMenu() }
            return true
        }
        if (menu.visibility == View.VISIBLE || addressBar.visibility == View.VISIBLE || startScreen.visibility == View.VISIBLE) {
            if (down && currentFocus == null) { // e.g. still in touch mode: give the remote something to move
                (if (menu.visibility == View.VISIBLE) menuGrid else startGrid).getChildAt(0)?.requestFocus()
                return true
            }
            return super.dispatchKeyEvent(e)
        }
        if (fullscreenView != null) {
            mediaCommand(code)?.let { if (down) js("__tvcb.media('$it')"); return true }
            return super.dispatchKeyEvent(e)
        }
        if (!browsing) return super.dispatchKeyEvent(e)

        mediaCommand(code)?.let { if (down) js("__tvcb.media('$it')"); return true }

        when (code) {
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> { if (down) tab.web?.pageUp(false); return true }
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> { if (down) tab.web?.pageDown(false); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (down) {
                    if (e.repeatCount == 0) { okDownAt = SystemClock.uptimeMillis(); okLong = false }
                    else if (e.isLongPress && !okLong) { okLong = true; toggleMode() }
                } else if (!okLong) click()
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val dir = when (code) {
                    KeyEvent.KEYCODE_DPAD_UP -> 0; KeyEvent.KEYCODE_DPAD_DOWN -> 1; KeyEvent.KEYCODE_DPAD_LEFT -> 2; else -> 3
                }
                if (cursorMode) {
                    if (down && e.repeatCount == 0) pressDir(dir) else if (!down) releaseDir(dir)
                } else if (down) {
                    js("__tvcb.nav('${DIRS[dir]}')")
                }
                return true
            }
        }
        return super.dispatchKeyEvent(e)
    }

    private fun mediaCommand(code: Int) = when (code) {
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> "playpause"
        KeyEvent.KEYCODE_MEDIA_PLAY -> "play"
        KeyEvent.KEYCODE_MEDIA_PAUSE -> "pause"
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> "ff"
        KeyEvent.KEYCODE_MEDIA_REWIND -> "rew"
        KeyEvent.KEYCODE_MEDIA_NEXT -> "next"
        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "prev"
        else -> null
    }

    private fun goBack() {
        when {
            dialog?.isShowing == true -> dialog?.dismiss()
            menu.visibility == View.VISIBLE -> closeMenu()
            addressBar.visibility == View.VISIBLE -> closeAddress()
            fullscreenView != null -> { js("__tvcb.exitFullscreen()"); fullscreenCallback?.onCustomViewHidden() }
            startScreen.visibility == View.VISIBLE -> if (tab.web != null && tab.url != null) showTab(current) else finish()
            tab.web?.canGoBack() == true -> tab.web?.goBack()
            else -> showStart()
        }
    }

    private fun js(code: String, cb: ((String) -> Unit)? = null) {
        val web = tab.web ?: return
        web.evaluateJavascript("(function(){try{if(!window.__tvcb)return '';return $code;}catch(e){return ''}})()") { r -> cb?.invoke(r ?: "") }
    }

    private fun toggleMode() {
        cursorMode = !cursorMode
        prefs.edit().putBoolean("cursorMode", cursorMode).apply()
        if (cursorMode) js("__tvcb.reset()") else releaseAllDirs()
        updateCursorVisibility()
        toast(if (cursorMode) "Cursor mode" else "Link mode: the D-pad jumps between links")
    }

    private fun updateCursorVisibility() {
        val phone = SystemClock.uptimeMillis() < trackpadUntil
        cursor.visibility = if (phone || (cursorMode && fullscreenView == null && startScreen.visibility != View.VISIBLE &&
                menu.visibility != View.VISIBLE)) View.VISIBLE else View.GONE
    }

    private fun click() {
        if (zapNext) { zapNext = false; zap(); return }
        if (cursorMode) { tap(cursor.cx, cursor.cy); return }
        js("__tvcb.target()") { r ->
            val json = r.trim('"').replace("\\\"", "\"")
            if (json.isEmpty()) { toast("Nothing selected: use the arrows"); return@js }
            val o = JSONObject(json)
            val web = tab.web ?: return@js
            tap((o.getDouble("x") * web.width).toFloat(), (o.getDouble("y") * web.height).toFloat())
        }
    }

    /** A real touch at (x, y), so pages get trusted events: links, selects and inputs all work. */
    private fun tap(x: Float, y: Float) {
        val web = tab.web ?: return
        val t = SystemClock.uptimeMillis()
        val downEv = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        web.dispatchTouchEvent(downEv); downEv.recycle()
        ui.postDelayed({
            val upEv = MotionEvent.obtain(t, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0)
                .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            web.dispatchTouchEvent(upEv); upEv.recycle()
        }, 40)
    }

    private fun hover(x: Float, y: Float) {
        val web = tab.web ?: return
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y })
        val t = SystemClock.uptimeMillis()
        val ev = MotionEvent.obtain(t, t, MotionEvent.ACTION_HOVER_MOVE, 1, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
        web.dispatchGenericMotionEvent(ev); ev.recycle()
    }

    // ---- cursor movement with acceleration ------------------------------------------------------

    private val held = LongArray(4) // press time per direction (up, down, left, right), 0 = not held
    private val holdStart = LongArray(4) // when the current hold began, kept across quick re-presses
    private val released = LongArray(4)
    private var lastHover = 0L
    private var frameScheduled = false

    private fun pressDir(dir: Int) {
        val now = SystemClock.uptimeMillis()
        // Some IR remotes send down/up pairs instead of key repeats while a button is held:
        // a press right after a release continues the same hold, so acceleration still builds.
        if (now - released[dir] < 200) {
            held[dir] = holdStart[dir]
        } else {
            held[dir] = now; holdStart[dir] = now
            val step = 8 * density // a single tap always moves a visible step
            when (dir) {
                0 -> cursor.moveTo(cursor.cx, cursor.cy - step); 1 -> cursor.moveTo(cursor.cx, cursor.cy + step)
                2 -> cursor.moveTo(cursor.cx - step, cursor.cy); else -> cursor.moveTo(cursor.cx + step, cursor.cy)
            }
        }
        if (!frameScheduled) { frameScheduled = true; Choreographer.getInstance().postFrameCallback(frame) }
    }

    private fun releaseDir(dir: Int) {
        if (held[dir] != 0L) released[dir] = SystemClock.uptimeMillis()
        held[dir] = 0
        // Keep the hold going for a moment in case the next press of a down/up-pair remote arrives.
    }
    private fun releaseAllDirs() = held.fill(0)

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val now = SystemClock.uptimeMillis()
            var dx = 0f; var dy = 0f
            for (d in 0..3) {
                if (held[d] == 0L) continue
                val ms = now - held[d]
                // slow for the first 300 ms so single presses are precise, then speed up
                val speed = if (ms < 300) 3f else min(3f + (ms - 300) / 35f, 24f)
                when (d) { 0 -> dy -= speed; 1 -> dy += speed; 2 -> dx -= speed; 3 -> dx += speed }
            }
            if (dx == 0f && dy == 0f) { frameScheduled = false; return }
            dx *= density; dy *= density
            val nx = cursor.cx + dx; val ny = cursor.cy + dy
            cursor.moveTo(nx, ny)
            // Pushing past an edge scrolls whatever is under the cursor.
            val w = cursor.width; val h = cursor.height
            val sx = if (nx < 0 || nx >= w - 1) dx else 0f
            val sy = if (ny < 0 || ny >= h - 1) dy else 0f
            if ((sx != 0f || sy != 0f) && w > 0 && h > 0) {
                @Suppress("DEPRECATION") val css = 1f / maxOf(tab.web?.scale ?: 1f, 0.1f) // view px -> CSS px
                js("__tvcb.scrollAt(${cursor.cx / w},${cursor.cy / h},${(sx * 1.5f * css).toInt()},${(sy * 1.5f * css).toInt()})")
            }
            if (now - lastHover > 80) { lastHover = now; hover(cursor.cx, cursor.cy) }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    // ---- address bar ----------------------------------------------------------------------------

    private fun openAddress() {
        closeMenu()
        addressBar.visibility = View.VISIBLE
        addressField.setText(if (startScreen.visibility == View.VISIBLE) "" else tab.url ?: "")
        addressField.selectAll()
        addressField.requestFocus()
        ui.postDelayed({
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(addressField, InputMethodManager.SHOW_IMPLICIT)
        }, 100)
    }

    private fun closeAddress() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(addressField.windowToken, 0)
        addressBar.visibility = View.GONE
        if (startScreen.visibility == View.VISIBLE) startGrid.getChildAt(0)?.requestFocus() else tab.web?.requestFocus()
    }

    private fun submitAddress() {
        val text = addressField.text.toString()
        closeAddress()
        if (text.isNotBlank()) load(text)
    }

    // ---- menu -----------------------------------------------------------------------------------

    private fun openMenu() {
        if (fullscreenView != null) return
        releaseAllDirs()
        menuGrid.removeAllViews()
        val desktop = isDesktop(tab.url)
        val items: List<Triple<String, String, () -> Unit>> = listOf(
            Triple("Back", "←") { closeMenu(); tab.web?.takeIf { it.canGoBack() }?.goBack() },
            Triple("Forward", "→") { closeMenu(); tab.web?.takeIf { it.canGoForward() }?.goForward() },
            Triple("Reload", "⟳") { closeMenu(); tab.web?.reload() },
            Triple("Address", "⌨") { openAddress() },
            Triple("Tabs (${tabs.size})", "▤") { closeMenu(); tabsDialog() },
            Triple("Bookmarks", "★") { closeMenu(); showStart() },
            Triple("Add bookmark", "＋") { closeMenu(); addBookmark() },
            Triple("Zoom out", "－") { tab.web?.zoomOut() },
            Triple("Zoom in", "＋") { tab.web?.zoomIn() },
            Triple(if (desktop) "Desktop site: on" else "Desktop site: off", "🖥") { closeMenu(); toggleDesktop() },
            Triple("Ad blocking", "⛔") { closeMenu(); adBlockDialog() },
            Triple("Clean up page", "🧹") { closeMenu(); cleanUpPage() },
            Triple("Remove element", "✂") {
                closeMenu(); zapNext = true
                toast(if (cursorMode) "Point at it and press OK to remove it" else "Pick it with the arrows and press OK to remove it")
            },
            Triple("Pair phone", "📱") { closeMenu(); pairDialog() },
            Triple("Settings", "⚙") { closeMenu(); settingsDialog() },
        )
        items.forEach { (label, sym, act) -> menuGrid.addView(tile(label, sym, 170, 120, act)) }
        menu.visibility = View.VISIBLE
        cursor.visibility = View.GONE
        menuGrid.getChildAt(0).requestFocus()
    }

    private fun closeMenu() {
        if (menu.visibility != View.VISIBLE) return
        menu.visibility = View.GONE
        updateCursorVisibility()
        if (startScreen.visibility == View.VISIBLE) startGrid.getChildAt(0)?.requestFocus() else tab.web?.requestFocus()
    }

    private fun showDialog(title: String, items: List<Pair<String, () -> Unit>>) {
        dialog?.dismiss()
        dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(title)
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
            .show()
    }

    private fun tabsDialog() {
        val items = ArrayList<Pair<String, () -> Unit>>()
        tabs.forEachIndexed { i, t ->
            val name = t.title.ifBlank { t.url?.let { Uri.parse(it).host } ?: "Start screen" }
            items.add((if (i == current) "▶ " else "    ") + name to { if (t.url == null) { current = i; showStart() } else showTab(i) })
        }
        if (tabs.size < MAX_TABS) items.add("New tab" to { tabs.add(Tab(null)); current = tabs.size - 1; showStart() })
        items.add("Close this tab" to { closeTab(current) })
        showDialog("Tabs (up to $MAX_TABS)", items)
    }

    private fun addBookmark() {
        val url = tab.url ?: return
        val web = tab.web
        var thumb: Bitmap? = null
        if (web != null && web.width > 0) {
            val w = 384; val h = (384f * web.height / web.width).toInt()
            thumb = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
            Canvas(thumb).apply { scale(w.toFloat() / web.width, w.toFloat() / web.width); web.draw(this) }
        }
        bookmarks.add(Bookmark(url, tab.title.ifBlank { Uri.parse(url).host ?: url }), thumb)
        toast("Bookmarked")
    }

    private fun adBlockDialog() {
        val host = tab.pageHost
        val allowed = blocker.isAllowlisted(host)
        val updated = if (blocker.lastUpdated == 0L) "never" else
            android.text.format.DateFormat.getMediumDateFormat(this).format(java.util.Date(blocker.lastUpdated))
        val items = ArrayList<Pair<String, () -> Unit>>()
        if (host != null) items.add((if (allowed) "Block ads on $host" else "Allow ads on $host") to {
            blocker.setAllowlisted(host, !allowed); tab.web?.reload()
        })
        items.add((if (blocker.enabled) "Turn ad blocking off everywhere" else "Turn ad blocking on") to {
            blocker.enabled = !blocker.enabled; tab.web?.reload()
        })
        if (host != null) {
            val auto = isAutoClean(host)
            items.add((if (auto) "Stop cleaning up $host automatically" else "Clean up $host automatically (overlays, cookie walls)") to {
                setAutoClean(host, !auto); if (!auto) cleanUpPage()
            })
            val zaps = blocker.zapCount(host)
            if (zaps > 0) items.add("Bring back $zaps removed element${if (zaps == 1) "" else "s"} on $host" to {
                blocker.clearZaps(host); tab.web?.reload()
            })
        }
        items.add("Filter lists…" to { filterListsDialog() })
        items.add("Update filter lists now" to { toast("Updating filter lists…"); blocker.updateNow { ui.post { toast(it) } } })
        val state = if (!blocker.enabled) "Off" else if (allowed) "Off for this site" else "On"
        showDialog("Ad blocking: $state · ${tab.blocked} blocked on this page · ${blocker.ruleCount} rules · lists updated $updated", items)
    }

    private fun filterListsDialog() {
        val on = blocker.enabledLists()
        val names = AdBlocker.LISTS.map { it.title }.toTypedArray()
        val checked = BooleanArray(names.size) { AdBlocker.LISTS[it].file in on }
        dialog?.dismiss()
        dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Filter lists (more lists use more memory)")
            .setMultiChoiceItems(names, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("Save") { _, _ ->
                val chosen = AdBlocker.LISTS.filterIndexed { i, _ -> checked[i] }.map { it.file }.toSet()
                if (chosen != on) {
                    blocker.setEnabledLists(chosen)
                    toast("Loading filter lists…")
                    blocker.reload { ui.post { toast(it) } }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- clean up and remove element --------------------------------------------------------------

    private var zapNext = false

    private fun autoCleanSites() = prefs.getStringSet("autoclean", emptySet())!!
    private fun isAutoClean(host: String?) = host != null && AdBlocker.site(host) in autoCleanSites()
    private fun setAutoClean(host: String, on: Boolean) {
        val set = HashSet(autoCleanSites())
        if (on) set.add(AdBlocker.site(host)) else set.remove(AdBlocker.site(host))
        prefs.edit().putStringSet("autoclean", set).apply()
    }

    /** Removes full-screen overlays, cookie walls and sticky ad bars, and lets the page scroll again. */
    private fun cleanUpPage(quiet: Boolean = false) {
        if (!browsing) return
        js("__tvcb.cleanup()") { r ->
            val n = r.trim('"').toIntOrNull() ?: 0
            if (!quiet) toast(if (n > 0) "Cleaned up the page" else "Nothing to clean up here")
        }
    }

    /** Removes the element under the pointer (or the one with the ring in link mode) and remembers it for the site. */
    private fun zap() {
        if (!browsing) return
        val web = tab.web ?: return
        val host = tab.pageHost
        val call = if (cursorMode || SystemClock.uptimeMillis() < trackpadUntil)
            "__tvcb.zapAt(${cursor.cx / web.width.coerceAtLeast(1)},${cursor.cy / web.height.coerceAtLeast(1)})"
        else "__tvcb.zapCurrent()"
        js(call) { r ->
            val sel = try { JSONArray("[$r]").optString(0) } catch (e: Exception) { "" }
            when {
                sel == "" -> toast("Nothing to remove there")
                sel == "-" -> toast("Removed (this page only)")
                host != null && blocker.addZap(host, sel) -> toast("Removed. Ad blocking → Bring back removed elements to undo")
                else -> toast("Removed (this page only)")
            }
        }
    }

    // ---- phone trackpad -------------------------------------------------------------------------

    private var trackpadUntil = 0L
    private var dragging = false
    private var dragDownTime = 0L
    private var lastPadHover = 0L
    private val hideTrackpadCursor = Runnable { updateCursorVisibility() }

    override fun pointerConnected() { ui.post { toast("Phone trackpad connected") } }

    override fun pointerDisconnected() { ui.post { endDrag() } }

    override fun pointer(cmd: PointerProtocol.Command) { ui.post { onPointer(cmd) } }

    /** Something native (menu, start screen, address bar) is on top of the page. */
    private val overlayShown get() = menu.visibility == View.VISIBLE || startScreen.visibility == View.VISIBLE ||
        addressBar.visibility == View.VISIBLE

    private fun onPointer(cmd: PointerProtocol.Command) {
        // Dialogs are separate windows: the trackpad's click works as OK there, and its moves as arrows.
        val d = dialog
        if (d?.isShowing == true) {
            when (cmd.op) {
                'c' -> { d.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER)); d.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER)) }
                'm' -> dialogNudge(d, cmd.b)
            }
            return
        }
        trackpadUntil = SystemClock.uptimeMillis() + TRACKPAD_CURSOR_MS
        ui.removeCallbacks(hideTrackpadCursor); ui.postDelayed(hideTrackpadCursor, TRACKPAD_CURSOR_MS + 50)
        if (cursor.visibility != View.VISIBLE) { cursor.visibility = View.VISIBLE }
        cursor.bringToFront()
        when (cmd.op) {
            'm' -> padMove(cmd.a * density * PAD_GAIN, cmd.b * density * PAD_GAIN)
            's' -> padScroll(cmd.a, cmd.b)
            'c' -> padTap()
            '2' -> { padTap(); ui.postDelayed({ padTap() }, 90) }
            'd' -> startDrag()
            'u' -> endDrag()
            'l' -> { if (overlayShown) nativeViewAt(cursor.cx, cursor.cy)?.performLongClick() else { startDrag(); ui.postDelayed({ endDrag() }, 700) } }
            'z' -> tab.web?.takeIf { browsing && !overlayShown }?.zoomBy(if (cmd.a > 0) 1.25f else 0.8f)
            'x' -> if (!overlayShown) zap()
            'k' -> if (!overlayShown) cleanUpPage()
        }
    }

    private var dialogAcc = 0f
    private fun dialogNudge(d: AlertDialog, dy: Float) {
        dialogAcc += dy
        val step = 40f
        while (dialogAcc > step || dialogAcc < -step) {
            val code = if (dialogAcc > 0) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP
            dialogAcc += if (dialogAcc > 0) -step else step
            d.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code)); d.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        }
    }

    private fun padMove(dx: Float, dy: Float) {
        val nx = cursor.cx + dx; val ny = cursor.cy + dy
        cursor.moveTo(nx, ny)
        val now = SystemClock.uptimeMillis()
        if (dragging) { touch(MotionEvent.ACTION_MOVE, dragDownTime); return }
        if (overlayShown) return
        // Pushing past an edge scrolls the page, as with the remote.
        val w = cursor.width; val h = cursor.height
        val sx = if (nx < 0 || nx >= w - 1) dx else 0f
        val sy = if (ny < 0 || ny >= h - 1) dy else 0f
        if ((sx != 0f || sy != 0f) && browsing) {
            @Suppress("DEPRECATION") val css = 1f / maxOf(tab.web?.scale ?: 1f, 0.1f)
            js("__tvcb.scrollAt(${cursor.cx / w},${cursor.cy / h},${(sx * 2 * css).toInt()},${(sy * 2 * css).toInt()})")
        }
        if (browsing && now - lastPadHover > 50) { lastPadHover = now; hover(cursor.cx, cursor.cy) }
    }

    private fun padScroll(dx: Float, dy: Float) {
        if (startScreen.visibility == View.VISIBLE && menu.visibility != View.VISIBLE) {
            startScreen.scrollBy(0, (dy * density * PAD_SCROLL_GAIN).toInt()); return
        }
        if (!browsing || overlayShown) return
        val w = cursor.width.coerceAtLeast(1); val h = cursor.height.coerceAtLeast(1)
        js("__tvcb.scrollAt(${cursor.cx / w},${cursor.cy / h},${(dx * PAD_SCROLL_GAIN).toInt()},${(dy * PAD_SCROLL_GAIN).toInt()})")
    }

    /** A click from the trackpad: a real touch on the page, or a click on the native tile under the pointer. */
    private fun padTap() {
        if (zapNext) { zapNext = false; zap(); return }
        if (overlayShown) {
            val v = nativeViewAt(cursor.cx, cursor.cy)
            if (v != null) { v.requestFocus(); v.performClick() }
            else if (menu.visibility == View.VISIBLE) closeMenu() // a tap beside the menu closes it
            return
        }
        val t = SystemClock.uptimeMillis()
        touch(MotionEvent.ACTION_DOWN, t)
        ui.postDelayed({ touch(MotionEvent.ACTION_UP, t) }, 40)
    }

    private fun startDrag() {
        if (dragging || overlayShown) return
        dragging = true; dragDownTime = SystemClock.uptimeMillis()
        touch(MotionEvent.ACTION_DOWN, dragDownTime)
    }

    private fun endDrag() {
        if (!dragging) return
        dragging = false
        touch(MotionEvent.ACTION_UP, dragDownTime)
    }

    /**
     * A real touch event at the pointer, sent through the window's view tree so it reaches whatever
     * is on top: the page, or the full-screen video. Pages see trusted events, which captcha
     * checkboxes and press-and-hold buttons require.
     */
    private fun touch(action: Int, downTime: Long) {
        val ev = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, cursor.cx, cursor.cy, 0)
            .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        root.dispatchTouchEvent(ev); ev.recycle()
    }

    /** The clickable native view (menu tile, bookmark, address field) under a point in root coordinates. */
    private fun nativeViewAt(x: Float, y: Float): View? {
        val top: ViewGroup = when {
            menu.visibility == View.VISIBLE -> menu
            addressBar.visibility == View.VISIBLE -> addressBar
            startScreen.visibility == View.VISIBLE -> startScreen
            else -> return null
        }
        val loc = IntArray(2); val rootLoc = IntArray(2)
        root.getLocationOnScreen(rootLoc)
        fun find(v: View): View? {
            if (v.visibility != View.VISIBLE) return null
            v.getLocationOnScreen(loc)
            val lx = x + rootLoc[0] - loc[0]; val ly = y + rootLoc[1] - loc[1]
            if (lx < 0 || ly < 0 || lx >= v.width || ly >= v.height) return null
            if (v is ViewGroup) for (i in v.childCount - 1 downTo 0) find(v.getChildAt(i))?.let { return it }
            return if (v !== top && (v.isClickable || v is EditText)) v else null
        }
        return find(top)
    }

    private fun settingsDialog() {
        showDialog("Settings", listOf(
            "Search engine: ${SEARCH_ENGINES[searchEngine].first}" to {
                searchEngine = (searchEngine + 1) % SEARCH_ENGINES.size
                toast("Search engine: ${SEARCH_ENGINES[searchEngine].first}")
            },
            "Navigation: ${if (cursorMode) "cursor" else "link"} mode (hold OK to switch)" to { toggleMode() },
            "Forget paired phones (new QR code)" to { tokens.rotate(); toast("Paired phones forgotten") },
            "Clear cookies, cache and history" to {
                CookieManager.getInstance().removeAllCookies(null)
                WebStorage.getInstance().deleteAllData()
                tabs.forEach { it.web?.clearCache(true); it.web?.clearHistory() }
                toast("Browsing data cleared")
            },
            "About and licenses" to {
                dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("TVCouchBrowser ${BuildConfig.VERSION_NAME}")
                    .setMessage(assets.open("NOTICE.txt").use { String(it.readBytes()) })
                    .setPositiveButton("OK", null).show()
            },
        ))
    }

    // ---- phone pairing --------------------------------------------------------------------------

    private fun lanAddress(): String? {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val lp = cm.getLinkProperties(cm.activeNetwork) ?: return null
        return lp.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
    }

    private fun pairDialog() {
        val ip = lanAddress()
        if (ip == null || server.port == 0) { toast("Not connected to a network"); return }
        val url = "http://$ip:${server.port}/?t=${tokens.token}"
        val size = dp(220)
        val matrix = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 2))
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) for (y in 0 until size) bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(8))
            addView(ImageView(context).apply { setImageBitmap(bmp) }, LinearLayout.LayoutParams(size, size))
            addView(TextView(context).apply {
                text = "Scan with your iPhone camera to open URLs, type into the TV and use your phone as a remote.\n\n" +
                    "The phone must be on the same Wi-Fi. Works while this browser is open.\n\n" +
                    "Address for the “Send to TV” Shortcut:\nhttp://$ip:${server.port}/open?t=${tokens.token}"
                setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); setPadding(dp(24), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        dialog?.dismiss()
        dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Pair phone").setView(content).setPositiveButton("Done", null).show()
    }

    // PhoneServer.Actions: called on server threads, so hop to the UI thread.

    override fun openUrl(input: String): Boolean {
        val url = resolve(input) ?: return false
        ui.post {
            dialog?.dismiss(); closeMenu()
            if (addressBar.visibility == View.VISIBLE) closeAddress()
            load(url)
        }
        return true
    }

    override fun type(text: String) {
        ui.post {
            if (addressBar.visibility == View.VISIBLE) {
                addressField.text.replace(addressField.selectionStart.coerceAtLeast(0), addressField.selectionEnd.coerceAtLeast(0), text)
                return@post
            }
            js("__tvcb.type(${JSONObject.quote(text)})") { r ->
                when (r.trim('"')) {
                    "ok" -> Unit
                    "iframe" -> typeWithKeys(text) // a cross-origin frame: send key events instead
                    else -> toast("Select a text box on the TV first")
                }
            }
        }
    }

    private fun typeWithKeys(text: String) {
        val web = tab.web ?: return
        KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(text.toCharArray())?.forEach { web.dispatchKeyEvent(it) }
    }

    override fun key(name: String): Boolean {
        val code = when (name) {
            "up" -> KeyEvent.KEYCODE_DPAD_UP; "down" -> KeyEvent.KEYCODE_DPAD_DOWN
            "left" -> KeyEvent.KEYCODE_DPAD_LEFT; "right" -> KeyEvent.KEYCODE_DPAD_RIGHT
            "ok" -> KeyEvent.KEYCODE_DPAD_CENTER; "back" -> KeyEvent.KEYCODE_BACK
            "menu" -> KeyEvent.KEYCODE_MENU; "playpause" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "enter" -> KeyEvent.KEYCODE_ENTER; "backspace" -> KeyEvent.KEYCODE_DEL
            "mode" -> -1
            else -> return false
        }
        ui.post {
            if (code == -1) { if (browsing) toggleMode(); return@post }
            if (code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_DEL) {
                // Text keys go to the focused field: the address bar or the page.
                val target: View = if (addressBar.visibility == View.VISIBLE) addressField else tab.web ?: return@post
                if (target === addressField && code == KeyEvent.KEYCODE_ENTER) { submitAddress(); return@post }
                target.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                target.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
                return@post
            }
            // A phone D-pad press in cursor mode nudges the pointer a fixed step.
            if (browsing && cursorMode && code in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_RIGHT &&
                menu.visibility != View.VISIBLE && addressBar.visibility != View.VISIBLE && dialog?.isShowing != true) {
                val step = 40 * density
                when (code) {
                    KeyEvent.KEYCODE_DPAD_UP -> cursor.moveTo(cursor.cx, cursor.cy - step)
                    KeyEvent.KEYCODE_DPAD_DOWN -> cursor.moveTo(cursor.cx, cursor.cy + step)
                    KeyEvent.KEYCODE_DPAD_LEFT -> cursor.moveTo(cursor.cx - step, cursor.cy)
                    else -> cursor.moveTo(cursor.cx + step, cursor.cy)
                }
                hover(cursor.cx, cursor.cy)
                return@post
            }
            val d = dialog
            val sink: (KeyEvent) -> Unit = if (d?.isShowing == true) { ev -> d.dispatchKeyEvent(ev) } else { ev -> dispatchKeyEvent(ev) }
            sink(KeyEvent(KeyEvent.ACTION_DOWN, code))
            sink(KeyEvent(KeyEvent.ACTION_UP, code))
        }
        return true
    }

    private fun keepScreenOn(on: Boolean) {
        if (on || fullscreenView != null) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    companion object {
        private const val TAG = "TVCouchBrowser"
        const val MAX_TABS = 3
        private const val MAX_JS_DIALOGS = 2
        private const val TRACKPAD_CURSOR_MS = 5000L // the pointer stays up this long after the last trackpad use
        private const val PAD_GAIN = 1f // TV dp per phone px, before the phone's own sensitivity setting
        private const val PAD_SCROLL_GAIN = 2.5f
        private val DIRS = arrayOf("up", "down", "left", "right")
        private val SEARCH_ENGINES = listOf(
            "DuckDuckGo" to "https://duckduckgo.com/?q=",
            "Google" to "https://www.google.com/search?q=",
            "Bing" to "https://www.bing.com/search?q=",
        )
        private val PALETTE = intArrayOf(0xFF2F7FE0.toInt(), 0xFF7A4FD1.toInt(), 0xFF1F9D8B.toInt(), 0xFFD1693A.toInt(), 0xFFB8417A.toInt())
    }
}
