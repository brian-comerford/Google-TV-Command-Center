package com.sportscc.tv

import android.content.Context
import android.graphics.Color
import androidx.core.content.res.ResourcesCompat
import android.graphics.RectF
import android.graphics.Paint
import android.graphics.Canvas
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
import com.sportscc.core.Prop
import com.sportscc.core.Pace
import com.sportscc.core.PropProgress
import com.sportscc.core.StatFormat

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
        // Design 1: a charcoal card with a yellow (orange for big plays) edge, Poppins type.
        val accent = if (alert.big) ORANGE else YELLOW
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(Color.parseColor("#F834353B"))
                setStroke(dp(2), accent)
            }
            addView(TextView(context).apply {
                text = "${alert.league.label}${if (alert.big) "  •  BIG PLAY" else ""}"
                setTextColor(accent)
                textSize = 13f
                typeface = bold
                letterSpacing = 0.08f
            })
            addView(TextView(context).apply {
                text = alert.title
                setTextColor(TEXT)
                textSize = if (alert.big) 22f else 19f
                typeface = bold
                maxWidth = dp(460)
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
            })
            alert.detail?.let { d ->
                addView(TextView(context).apply {
                    text = d
                    setTextColor(MUTED)
                    textSize = 15f
                    typeface = regular
                    maxWidth = dp(460)
                    maxLines = if (alert.props.isEmpty()) 3 else 2
                    ellipsize = TextUtils.TruncateAt.END
                })
            }
            alert.props.forEach { addView(propRow(it)) }
        }
    }

    /** Same as the card's prop rows: a ring toward the line, green winning, yellow chasing, orange busted. */
    private fun propRow(p: PropProgress): View {
        val over = p.prop.side == Prop.Side.OVER
        val color = when {
            p.winning -> GREEN
            over -> YELLOW
            else -> ORANGE
        }
        val status = when {
            over && p.winning -> "HIT at ${StatFormat.number(p.current)}"
            !over && !p.winning -> "BUSTED at ${StatFormat.number(p.current)}"
            else -> null
        }
        val hit = over && p.winning
        val pace = p.projected
        val paceWins = p.paceWinning
        val ring = RingView(context, if (hit) 1f else p.fraction.toFloat(), color, if (hit) "✓" else StatFormat.number(p.current), if (hit) GREEN else TEXT, bold)
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
            addView(TextView(context).apply {
                text = "${if (over) "Over" else "Under"} ${StatFormat.number(p.prop.line)} ${p.def.short}"
                setTextColor(TEXT)
                textSize = 14f
                typeface = semibold
            })
            val line = status ?: if (pace != null && paceWins != null) "On pace for ${Pace.format(pace)}" else if (over) "Chasing · ${StatFormat.number(p.current)}" else "Holding · ${StatFormat.number(p.current)}"
            addView(TextView(context).apply {
                text = line
                setTextColor(if (status != null) color else if (pace != null && paceWins != null) (if (paceWins) GREEN else ORANGE) else color)
                textSize = 12f
                typeface = semibold
            })
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(Color.parseColor("#3E3F46")) }
            addView(ring, LinearLayout.LayoutParams(dp(40), dp(40)))
            addView(texts)
            layoutParams = LinearLayout.LayoutParams(dp(420), LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
        }
    }

    private val bold by lazy { runCatching { ResourcesCompat.getFont(context, R.font.poppins_bold) }.getOrNull() ?: Typeface.DEFAULT_BOLD }
    private val semibold by lazy { runCatching { ResourcesCompat.getFont(context, R.font.poppins_semibold) }.getOrNull() ?: Typeface.DEFAULT_BOLD }
    private val regular by lazy { runCatching { ResourcesCompat.getFont(context, R.font.poppins_regular) }.getOrNull() ?: Typeface.DEFAULT }

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    private companion object {
        val YELLOW = Color.parseColor("#F6C446")
        val ORANGE = Color.parseColor("#F07B3F")
        val GREEN = Color.parseColor("#4FC48A")
        val TEXT = Color.parseColor("#EDEDF0")
        val MUTED = Color.parseColor("#B3B5BD")
    }
}

/** A small ring gauge with a label in the middle, for the overlay's prop rows. */
private class RingView(
    context: Context,
    private val fraction: Float,
    private val color: Int,
    private val label: String,
    private val labelColor: Int,
    typeface: Typeface,
) : View(context) {
    private val d = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 5 * d; this.color = Color.parseColor("#2C2D32") }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 5 * d; strokeCap = Paint.Cap.ROUND; this.color = color }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = labelColor; textSize = 11 * d; textAlign = Paint.Align.CENTER; this.typeface = typeface }
    private val box = RectF()

    override fun onDraw(canvas: Canvas) {
        val inset = 2.5f * d
        box.set(inset, inset, width - inset, height - inset)
        canvas.drawArc(box, 0f, 360f, false, track)
        if (fraction > 0f) canvas.drawArc(box, -90f, 360f * fraction.coerceIn(0f, 1f), false, arc)
        canvas.drawText(label, width / 2f, height / 2f - (text.ascent() + text.descent()) / 2, text)
    }
}
