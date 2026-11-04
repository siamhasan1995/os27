package com.os27.launcher

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * HyperOS refuses system full-screen gestures for 3rd-party launchers, so we implement our own:
 *  - bottom bar: swipe up = Home, swipe up + hold = Recents (draws an iOS-style home pill)
 *  - left/right edge: swipe inward = Back
 * The bottom bar covers the legacy nav buttons (black in other apps, transparent over the launcher).
 */
class GestureService : AccessibilityService() {
    private lateinit var wm: WindowManager
    private val added = mutableListOf<View>()
    private lateinit var bottom: FrameLayout

    override fun onServiceConnected() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val id = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        val navH = maxOf(if (id > 0) resources.getDimensionPixelSize(id) else 0, dp(24))

        bottom = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            val pill = View(context).apply {
                background = GradientDrawable().apply { cornerRadius = dp(3).toFloat(); setColor(Color.argb(230, 255, 255, 255)) }
            }
            addView(pill, FrameLayout.LayoutParams(dp(120), dp(4), Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM).apply { bottomMargin = dp(8) })
            setOnTouchListener(Swipe(0))
        }
        attach(bottom, WindowManager.LayoutParams.MATCH_PARENT, navH, Gravity.BOTTOM)

        val h = (resources.displayMetrics.heightPixels * 0.6f).toInt()
        attach(View(this).apply { setOnTouchListener(Swipe(1)) }, dp(16), h, Gravity.START or Gravity.CENTER_VERTICAL)
        attach(View(this).apply { setOnTouchListener(Swipe(2)) }, dp(16), h, Gravity.END or Gravity.CENTER_VERTICAL)
    }

    private fun attach(v: View, w: Int, h: Int, g: Int) {
        val lp = WindowManager.LayoutParams(
            w, h, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = g }
        wm.addView(v, lp); added += v
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val p = e.packageName?.toString() ?: return
        if (p == "com.android.systemui") return
        bottom.setBackgroundColor(if (p == packageName) Color.TRANSPARENT else Color.BLACK)
    }

    override fun onInterrupt() {}
    override fun onDestroy() { added.forEach { runCatching { wm.removeView(it) } }; added.clear(); super.onDestroy() }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private inner class Swipe(val kind: Int) : View.OnTouchListener {
        var x0 = 0f; var y0 = 0f; var t0 = 0L; var fired = false
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { x0 = e.rawX; y0 = e.rawY; t0 = e.eventTime; fired = false }
                MotionEvent.ACTION_MOVE -> if (!fired) {
                    val dy = y0 - e.rawY; val dx = e.rawX - x0
                    if (kind == 0 && dy > dp(110) && e.eventTime - t0 > 350) { fired = true; performGlobalAction(GLOBAL_ACTION_RECENTS) }
                    if (kind == 1 && dx > dp(60) && abs(dx) > abs(dy)) { fired = true; performGlobalAction(GLOBAL_ACTION_BACK) }
                    if (kind == 2 && -dx > dp(60) && abs(dx) > abs(dy)) { fired = true; performGlobalAction(GLOBAL_ACTION_BACK) }
                }
                MotionEvent.ACTION_UP -> if (!fired && kind == 0 && y0 - e.rawY > dp(50)) {
                    performGlobalAction(if (e.eventTime - t0 > 350) GLOBAL_ACTION_RECENTS else GLOBAL_ACTION_HOME)
                }
            }
            return true
        }
    }
}
