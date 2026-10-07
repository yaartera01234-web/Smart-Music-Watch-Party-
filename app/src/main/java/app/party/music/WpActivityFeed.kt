package app.party.music

import android.animation.LayoutTransition
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * v54 ACTIVITY OVERLAY — native twin of the website's fullscreen toast column.
 *
 * Website (party-final1.html, block WP_ACTIVITY_OVERLAY) already decides WHAT to show
 * (dedup, names, HH:MM:SS, join/left, room-chat text). In the native MPV fullscreen the
 * WebView is invisible (web.alpha = 0), so the page hands each toast to
 * `YaarNative.wpActivity(json)` and this view draws it with the same design:
 * upper-left glass cards, log style (oldest on top, newest below), max 5 rows,
 * each ~5 s, a 6th pushes the oldest out. Purely decorative: never touches playback,
 * never consumes touches (gestures/controls of MpvFullscreenControls keep working).
 */
internal data class WpActivityItem(
    val name: String,
    val kind: String,
    val word: String,
    val detail: String,
    val durationMs: Long
) {
    companion object {
        val KINDS: Set<String> = setOf("pause", "play", "seek", "join", "leave", "msg")
        private val WORDS = mapOf("pause" to "Paused", "play" to "Resumed", "seek" to "Seek", "join" to "Joined", "leave" to "Left", "msg" to "")

        /** JSON from the page: {name, kind, t1 (word), t2 (detail line), ms}. Null when unusable. */
        fun parse(json: String?): WpActivityItem? {
            val o = try { JSONObject(json ?: return null) } catch (_: Exception) { return null }
            val kind = o.optString("kind").trim().lowercase()
            if (kind !in KINDS) return null
            val name = o.optString("name").trim().take(20).ifBlank { "Someone" }
            val word = o.optString("t1").trim().take(16).ifBlank { WORDS[kind] ?: "" }
            val detail = o.optString("t2").replace(Regex("\\s+"), " ").trim().take(if (kind == "msg") 120 else 32)
            val ms = o.optLong("ms", 5000L).coerceIn(1500L, 15000L)
            return WpActivityItem(name, kind, word, detail, ms)
        }
    }
}

internal class WpActivityFeed(context: Context) : LinearLayout(context) {
    private val handler = Handler(Looper.getMainLooper())
    private val timers = HashMap<View, Runnable>()
    private val leaving = HashSet<View>()
    var maxRows = 5

    init {
        orientation = VERTICAL
        gravity = Gravity.START
        isClickable = false
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        // Only the siblings' slide (CHANGE_*) is automatic; enter/exit fades are done by hand below.
        layoutTransition = LayoutTransition().apply {
            disableTransitionType(LayoutTransition.APPEARING)
            disableTransitionType(LayoutTransition.DISAPPEARING)
            setDuration(LayoutTransition.CHANGE_APPEARING, 220L)
            setDuration(LayoutTransition.CHANGE_DISAPPEARING, 260L)
        }
    }

    /** Page JSON -> toast. Returns false when the JSON is not a valid activity item. */
    fun show(json: String?): Boolean {
        val item = WpActivityItem.parse(json) ?: return false
        show(item)
        return true
    }

    fun show(item: WpActivityItem) {
        val card = card(item)
        addView(card, LayoutParams(cardWidth(), LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) })

        // Over the limit -> the oldest rows (top of the column) leave immediately.
        var live = 0
        for (i in 0 until childCount) if (getChildAt(i) !in leaving) live++
        var i = 0
        while (live > maxRows && i < childCount) {
            val c = getChildAt(i)
            if (c !in leaving && c !== card) { dismiss(c, fast = true); live-- }
            i++
        }

        card.alpha = 0f
        card.translationX = -dp(14).toFloat()
        card.scaleX = 0.95f
        card.scaleY = 0.95f
        card.animate().alpha(1f).translationX(0f).scaleX(1f).scaleY(1f)
            .setDuration(260L).setInterpolator(DecelerateInterpolator()).start()

        val r = Runnable { dismiss(card, fast = false) }
        timers[card] = r
        handler.postDelayed(r, item.durationMs)
    }

    fun clear() {
        handler.removeCallbacksAndMessages(null)
        timers.clear()
        leaving.clear()
        removeAllViews()
    }

    private fun dismiss(v: View, fast: Boolean) {
        if (!leaving.add(v)) return
        timers.remove(v)?.let { handler.removeCallbacks(it) }
        v.animate().cancel()
        v.animate().alpha(0f).translationX(-dp(10).toFloat())
            .setDuration(if (fast) 180L else 300L)
            .withEndAction { leaving.remove(v); removeView(v) }
            .start()
    }

    private fun cardWidth(): Int = min(dp(290), (resources.displayMetrics.widthPixels * 0.64f).toInt())
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()
    private fun c(argb: Long): Int = argb.toInt()

    private fun card(item: WpActivityItem): View {
        val isMsg = item.kind == "msg"
        val root = FrameLayout(context)
        root.isClickable = false
        root.background = GradientDrawable().apply {
            setColor(c(0x940C091CL))                 // rgba(12,9,28,.58)
            cornerRadius = dp(13).toFloat()
            setStroke(dp(1), c(0x24FFFFFFL))         // 14% white hairline
        }

        val row = LinearLayout(context)
        row.orientation = HORIZONTAL
        row.gravity = if (isMsg) Gravity.TOP else Gravity.CENTER_VERTICAL
        row.setPadding(dp(8), dp(7), dp(12), dp(8))

        val icon = TextView(context)
        icon.gravity = Gravity.CENTER
        icon.textSize = if (item.kind == "join" || item.kind == "leave") 13f else 11f
        icon.setTypeface(Typeface.DEFAULT_BOLD)
        icon.includeFontPadding = false
        val style = ICONS[item.kind] ?: ICONS.getValue("seek")
        icon.text = style.glyph
        icon.setTextColor(c(style.text))
        icon.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(c(style.from), c(style.to))).apply { shape = GradientDrawable.OVAL }
        row.addView(icon, LinearLayout.LayoutParams(dp(26), dp(26)).apply { rightMargin = dp(9); if (isMsg) topMargin = dp(1) })

        val col = LinearLayout(context)
        col.orientation = VERTICAL
        val line1 = TextView(context)
        line1.textSize = 12.5f
        line1.maxLines = 1
        line1.ellipsize = TextUtils.TruncateAt.END
        line1.includeFontPadding = false
        line1.text = SpannableStringBuilder().apply {
            append(item.name)
            setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(Color.WHITE), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (item.word.isNotBlank()) {
                val start = length
                append(" ").append(item.word)
                setSpan(StyleSpan(Typeface.BOLD), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(ForegroundColorSpan(c(0xD1FFFFFFL)), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        col.addView(line1, LinearLayout.LayoutParams(-1, -2))
        if (item.detail.isNotBlank()) {
            val line2 = TextView(context)
            line2.includeFontPadding = false
            if (isMsg) {
                line2.textSize = 12f
                line2.maxLines = 2
                line2.setTextColor(c(0xEBFFFFFFL))
            } else {
                line2.textSize = 11f
                line2.maxLines = 1
                line2.setTypeface(Typeface.DEFAULT_BOLD)
                line2.setTextColor(c(0xA8FFFFFFL))
            }
            line2.ellipsize = TextUtils.TruncateAt.END
            line2.text = item.detail
            col.addView(line2, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(1) })
        }
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(row, FrameLayout.LayoutParams(-1, -2))

        // Life bar: how much time is left (shrinks left -> right over the toast's lifetime).
        val life = View(context)
        life.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(c(0xFFFF5EBCL), c(0xFF8B72FFL), c(0xFF54E8FFL)))
        life.alpha = 0.55f
        life.pivotX = 0f
        root.addView(life, FrameLayout.LayoutParams(-1, dp(2), Gravity.BOTTOM))
        life.animate().scaleX(0f).setDuration(item.durationMs).setInterpolator(LinearInterpolator()).start()
        return root
    }

    private class IconStyle(val glyph: String, val from: Long, val to: Long, val text: Long)

    private companion object {
        val ICONS = mapOf(
            "pause" to IconStyle("❚❚", 0xFFFFB547L, 0xFFFF7A59L, 0xFF2B1400L),
            "play" to IconStyle("▶", 0xFF3DDC97L, 0xFF22B8CFL, 0xFF052A22L),
            "seek" to IconStyle("⏩", 0xFF8B72FFL, 0xFF54E8FFL, 0xFF0C0B2EL),
            "join" to IconStyle("🎉", 0xFFFF5EBCL, 0xFF8B72FFL, 0xFFFFFFFFL),
            "leave" to IconStyle("👋", 0xFFFF6B6BL, 0xFFFF5EBCL, 0xFFFFFFFFL),
            "msg" to IconStyle("💬", 0xFF54E8FFL, 0xFF8B72FFL, 0xFFFFFFFFL)
        )
    }
}
