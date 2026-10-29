package com.sportscc.tv.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import kotlin.math.max
import kotlin.math.min

/**
 * A remote-friendly web browser: a WebView with an on-screen pointer moved by the D-pad, OK to click,
 * and the page's full-screen video kept inside this view so the stats around it stay visible.
 */
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
class BrowserView(context: Context) : FrameLayout(context) {

    enum class Mode { POINTER, SCROLL }

    var mode = Mode.POINTER
        set(value) {
            field = value
            cursor.visibility = if (value == Mode.POINTER) View.VISIBLE else View.GONE
        }

    /** Up pressed at the very top of the page: hand focus back to the toolbar. */
    var onExitTop: (() -> Unit)? = null
    var onPageChanged: ((url: String, title: String?) -> Unit)? = null
    var onLoading: ((Int) -> Unit)? = null

    val webView = WebView(context)
    private val fullscreen = FrameLayout(context).apply { setBackgroundColor(Color.BLACK); visibility = View.GONE }
    private val cursor = CursorView(context)
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private val defaultUserAgent: String = webView.settings.userAgentString

    private val density = resources.displayMetrics.density
    private var cx = -1f
    private var cy = -1f

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
        setBackgroundColor(Color.BLACK)

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = false
            // Pop-up windows and tabs are the main source of junk on free stream sites: never open them.
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(true)
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.isFocusable = false // keys go through this view so the pointer works
        webView.setDownloadListener { _, _, _, _, _ -> /* no downloads */ }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme?.lowercase()
                // Block intent:, market: and similar links that would launch other apps.
                return scheme != "http" && scheme != "https" && scheme != "about" && scheme != "data"
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                onPageChanged?.invoke(url, view.title)
            }

            override fun onPageFinished(view: WebView, url: String) {
                onPageChanged?.invoke(url, view.title)
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message): Boolean = false

            override fun onProgressChanged(view: WebView, newProgress: Int) {
                onLoading?.invoke(newProgress)
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                onPageChanged?.invoke(view.url ?: "", title)
            }

            // A page's full-screen button: fill this browser area, not the whole TV.
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                exitFullscreen()
                customView = view
                customCallback = callback
                fullscreen.addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
                fullscreen.visibility = View.VISIBLE
                cursor.bringToFront()
            }

            override fun onHideCustomView() {
                fullscreen.removeAllViews()
                fullscreen.visibility = View.GONE
                customView = null
                customCallback = null
            }

            // Lets sites that use standard web DRM (Widevine) try to play.
            override fun onPermissionRequest(request: PermissionRequest) {
                val allowed = request.resources.filter { it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID }
                if (allowed.isEmpty()) request.deny() else request.grant(allowed.toTypedArray())
            }

            override fun getDefaultVideoPoster(): Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        }

        addView(webView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(fullscreen, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(cursor, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun load(input: String) {
        exitFullscreen()
        webView.loadUrl(toUrl(input))
    }

    fun setDesktopMode(on: Boolean) {
        val ua = if (on) DESKTOP_UA else defaultUserAgent
        if (webView.settings.userAgentString != ua) {
            webView.settings.userAgentString = ua
            webView.reload()
        }
    }

    fun goBack(): Boolean {
        if (customView != null) {
            exitFullscreen(); return true
        }
        if (webView.canGoBack()) {
            webView.goBack(); return true
        }
        return false
    }

    fun reload() = webView.reload()

    fun destroy() {
        exitFullscreen()
        webView.stopLoading()
        webView.destroy()
    }

    private fun exitFullscreen() {
        customCallback?.onCustomViewHidden()
        fullscreen.removeAllViews()
        fullscreen.visibility = View.GONE
        customView = null
        customCallback = null
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (cx < 0) {
            cx = w / 2f; cy = h / 2f
        }
        cx = cx.coerceIn(0f, w.toFloat()); cy = cy.coerceIn(0f, h.toFloat())
        cursor.moveTo(cx, cy)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        val dpad = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN ||
            code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
        val ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER
        if (!dpad && !ok) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_UP) return true

        if (ok) {
            if (mode == Mode.POINTER && event.repeatCount == 0) click(cx, cy)
            return true
        }
        val held = event.repeatCount > 0
        if (mode == Mode.SCROLL) {
            when (code) {
                KeyEvent.KEYCODE_DPAD_UP -> scroll(0f, -0.35f, held, exitIfStuck = true)
                KeyEvent.KEYCODE_DPAD_DOWN -> scroll(0f, 0.35f, held)
                KeyEvent.KEYCODE_DPAD_LEFT -> scroll(-0.35f, 0f, held)
                KeyEvent.KEYCODE_DPAD_RIGHT -> scroll(0.35f, 0f, held)
            }
            return true
        }
        // Pointer: speeds up while the button is held; pushing past an edge scrolls the page.
        val step = density * min(14f + event.repeatCount * 6f, 60f)
        val edge = density * 4
        when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> if (cy <= edge) scroll(0f, -0.2f, held, exitIfStuck = true) else cy = max(0f, cy - step)
            KeyEvent.KEYCODE_DPAD_DOWN -> if (cy >= height - edge) scroll(0f, 0.2f, held) else cy = min(height.toFloat(), cy + step)
            KeyEvent.KEYCODE_DPAD_LEFT -> if (cx <= edge) scroll(-0.2f, 0f, held) else cx = max(0f, cx - step)
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (cx >= width - edge) scroll(0.2f, 0f, held) else cx = min(width.toFloat(), cx + step)
        }
        cursor.moveTo(cx, cy)
        return true
    }

    /** What's on screen under the pointer receives a tap: the page, or its full-screen video. */
    private fun target(): View = customView ?: webView

    private fun click(x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        send(MotionEvent.ACTION_DOWN, t, t, x, y)
        send(MotionEvent.ACTION_UP, t, t + 60, x, y)
    }

    /**
     * Scrolls a fixed step ([fx], [fy] are fractions of the visible page), without fling: whatever is
     * scrollable under the pointer (a box with its own scroll bar), else the page itself. Fake finger
     * swipes were read as hard flicks and flew to the end of the page.
     * With [exitIfStuck], nothing left to scroll up means "go to the toolbar".
     */
    private fun scroll(fx: Float, fy: Float, held: Boolean, exitIfStuck: Boolean = false) {
        val px = (if (cx >= 0) cx else width / 2f) / width.coerceAtLeast(1)
        val py = (if (cy >= 0) cy else height / 2f) / height.coerceAtLeast(1)
        val js = SCROLL_JS
            .replace("%PX%", px.toString()).replace("%PY%", py.toString())
            .replace("%FX%", fx.toString()).replace("%FY%", fy.toString())
            .replace("%B%", if (held) "auto" else "smooth")
        val target = customView
        if (target != null) return // a full-screen video has nothing to scroll
        webView.evaluateJavascript(js) { result ->
            if (exitIfStuck && result?.contains("none") == true) onExitTop?.invoke()
        }
    }

    private fun send(action: Int, down: Long, time: Long, x: Float, y: Float) {
        val e = MotionEvent.obtain(down, time, action, x, y, 0)
        e.source = InputDevice.SOURCE_TOUCHSCREEN
        target().dispatchTouchEvent(e)
        e.recycle()
    }

    companion object {
        const val HOME = "https://www.google.com"

        // Finds the nearest scroller under the pointer that can still move that way, else the page.
        private val SCROLL_JS = """
            (function(px, py, fx, fy, b) {
              var dx = fx * window.innerWidth, dy = fy * window.innerHeight;
              function can(e) {
                if (dy < 0 && e.scrollTop > 0) return true;
                if (dy > 0 && e.scrollTop + e.clientHeight < e.scrollHeight - 1) return true;
                if (dx < 0 && e.scrollLeft > 0) return true;
                if (dx > 0 && e.scrollLeft + e.clientWidth < e.scrollWidth - 1) return true;
                return false;
              }
              var e = document.elementFromPoint(px * window.innerWidth, py * window.innerHeight);
              while (e && e !== document.body && e !== document.documentElement) {
                var s = getComputedStyle(e);
                if (/(auto|scroll|overlay)/.test(s.overflowY + s.overflowX) && can(e)) {
                  e.scrollBy({ left: dx, top: dy, behavior: b }); return 'box';
                }
                e = e.parentElement;
              }
              var r = document.scrollingElement || document.documentElement;
              if (can(r)) { window.scrollBy({ left: dx, top: dy, behavior: b }); return 'page'; }
              return 'none';
            })(%PX%, %PY%, %FX%, %FY%, '%B%');
        """.trimIndent()
        private const val DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        /** Typed text becomes a URL if it looks like one, otherwise a Google search. */
        fun toUrl(input: String): String {
            val s = input.trim()
            if (s.isEmpty()) return HOME
            if (s.startsWith("http://") || s.startsWith("https://")) return s
            if (!s.contains(' ') && s.contains('.') && !s.endsWith('.')) return "https://$s"
            return "https://www.google.com/search?q=" + java.net.URLEncoder.encode(s, "UTF-8")
        }
    }
}

/** The arrow pointer drawn on top of the page. */
private class CursorView(context: Context) : View(context) {
    private val d = resources.displayMetrics.density
    private var x = 0f
    private var y = 0f
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 1.6f * d }
    private val arrow = Path()

    init {
        isFocusable = false
        isClickable = false
    }

    fun moveTo(nx: Float, ny: Float) {
        x = nx; y = ny; invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        arrow.reset()
        arrow.moveTo(x, y)
        arrow.lineTo(x, y + 22 * d)
        arrow.lineTo(x + 6 * d, y + 16.5f * d)
        arrow.lineTo(x + 10 * d, y + 25 * d)
        arrow.lineTo(x + 14 * d, y + 23 * d)
        arrow.lineTo(x + 10 * d, y + 15 * d)
        arrow.lineTo(x + 17 * d, y + 15 * d)
        arrow.close()
        canvas.drawPath(arrow, fill)
        canvas.drawPath(arrow, stroke)
    }
}
