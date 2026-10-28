package com.sportscc.tv

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.sportscc.core.Alert

/** Draws a small alert card in the top-right corner over whatever app is on screen. */
class OverlayPresenter(private val context: Context) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private var current: View? = null

    fun canDraw(): Boolean = Settings.canDrawOverlays(context)

    fun show(alert: Alert) {
        hide()
        if (!canDraw()) return
        val view = build(alert)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(32)
            y = dp(32)
        }
        runCatching { wm.addView(view, params) }.onSuccess { current = view }
    }

    fun hide() {
        current?.let { runCatching { wm.removeView(it) } }
        current = null
    }

    private fun build(alert: Alert): View {
        val accent = if (alert.big) Color.parseColor("#FF3B30") else Color.parseColor("#3D8BFF")
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.argb(235, 16, 22, 33))
                setStroke(dp(3), accent)
            }
            addView(TextView(context).apply {
                text = "${alert.league.label}${if (alert.big) "  •  BIG PLAY" else ""}"
                setTextColor(accent)
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.08f
            })
            addView(TextView(context).apply {
                text = alert.title
                setTextColor(Color.WHITE)
                textSize = if (alert.big) 22f else 19f
                typeface = Typeface.DEFAULT_BOLD
                maxWidth = dp(460)
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
            })
            alert.detail?.let { d ->
                addView(TextView(context).apply {
                    text = d
                    setTextColor(Color.parseColor("#B8C1D1"))
                    textSize = 15f
                    maxWidth = dp(460)
                    maxLines = 3
                    ellipsize = TextUtils.TruncateAt.END
                })
            }
        }
    }

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()
}
