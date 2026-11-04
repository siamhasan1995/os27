package com.os27.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.*
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.View

fun Context.dp(v: Number): Int = (v.toFloat() * resources.displayMetrics.density).toInt()

/** Frosted "glass" surface: soft vertical sheen + bright hairline edge. */
fun glass(ctx: Context, radiusDp: Int, top: Int = Color.argb(95, 255, 255, 255), bottom: Int = Color.argb(40, 255, 255, 255)) =
    GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, bottom)).apply {
        cornerRadius = ctx.dp(radiusDp).toFloat()
        setStroke(ctx.dp(1), Color.argb(120, 255, 255, 255))
    }

data class AppEntry(
    val pkg: String, val label: String, val cn: ComponentName,
    val cat: String, val icon: Drawable, val installed: Long
)

object Repo {
    fun load(ctx: Context): List<AppEntry> {
        val pm = ctx.packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(i, 0)
            .filter { it.activityInfo.packageName != ctx.packageName }
            .map { ri ->
                val ai = ri.activityInfo
                val t = runCatching { pm.getPackageInfo(ai.packageName, 0).firstInstallTime }.getOrDefault(0L)
                AppEntry(ai.packageName, ri.loadLabel(pm).toString(),
                    ComponentName(ai.packageName, ai.name),
                    categorize(ai.applicationInfo, ai.packageName), ri.loadIcon(pm), t)
            }
            .sortedBy { it.label.lowercase() }
    }

    private fun categorize(ai: ApplicationInfo, pkg: String): String {
        val byFlag = when (ai.category) {
            ApplicationInfo.CATEGORY_GAME -> "Games"
            ApplicationInfo.CATEGORY_AUDIO, ApplicationInfo.CATEGORY_VIDEO -> "Entertainment"
            ApplicationInfo.CATEGORY_IMAGE -> "Creativity"
            ApplicationInfo.CATEGORY_SOCIAL -> "Social"
            ApplicationInfo.CATEGORY_NEWS -> "Information & Reading"
            ApplicationInfo.CATEGORY_MAPS -> "Travel"
            ApplicationInfo.CATEGORY_PRODUCTIVITY -> "Productivity"
            else -> null
        }
        if (byFlag != null) return byFlag
        val p = pkg.lowercase()
        fun has(vararg k: String) = k.any { p.contains(it) }
        return when {
            has("whatsapp", "telegram", "facebook", "instagram", "messenger", "snapchat", "twitter", "discord",
                "tiktok", "signal", "reddit", "linkedin", "mms", "contacts", "dialer") -> "Social"
            has("youtube", "netflix", "spotify", "music", "video", "player", "hotstar", "soundrecorder") -> "Entertainment"
            has("camera", "gallery", "photo", "edit", "draw", "paint") -> "Creativity"
            has("bank", "pay", "wallet", "docs", "office", "mail", "calendar", "notes", "drive", "sheets", "finance") -> "Productivity"
            has("map", "uber", "travel", "booking", "ride", "airline") -> "Travel"
            has("news", "reader", "book", "wiki", "weather", "browser", "chrome") -> "Information & Reading"
            else -> "Utilities"
        }
    }
}

/** Draws any app icon masked to a continuous-corner iOS-style rounded square. */
class IconView(ctx: Context, private val d: Drawable) : View(ctx) {
    private val clip = Path()
    private val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    override fun onDraw(c: Canvas) {
        val s = width.toFloat(); val r = s * 0.225f
        clip.reset(); clip.addRoundRect(0f, 0f, s, s, r, r, Path.Direction.CW)
        c.save(); c.clipPath(clip)
        if (d is AdaptiveIconDrawable) {
            val pad = (s * 0.25f).toInt()           // crop device mask, keep our own
            d.setBounds(-pad, -pad, s.toInt() + pad, s.toInt() + pad)
        } else {
            c.drawPaint(white)
            val pad = (s * 0.12f).toInt()
            d.setBounds(pad, pad, s.toInt() - pad, s.toInt() - pad)
        }
        d.draw(c); c.restore()
    }
}
