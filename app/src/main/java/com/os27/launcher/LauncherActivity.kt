package com.os27.launcher

import android.app.Activity
import android.app.ActivityOptions
import android.content.*
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import kotlin.math.abs
import kotlin.math.ceil

class LauncherActivity : Activity() {
    private lateinit var root: FrameLayout
    private lateinit var pager: ViewPager2
    private lateinit var dock: LinearLayout
    private lateinit var dots: LinearLayout
    private var apps: List<AppEntry> = emptyList()
    private var topInset = 0
    private var overlay: View? = null
    private val prefs by lazy { getSharedPreferences("os27", MODE_PRIVATE) }
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private val categoryOrder = listOf("Social", "Entertainment", "Games", "Productivity", "Creativity",
        "Information & Reading", "Travel", "Utilities")
    private val pkgReceiver = object : BroadcastReceiver() { override fun onReceive(c: Context?, i: Intent?) = reload() }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        hideBars()
        apps = Repo.load(this)
        root = FrameLayout(this)
        setContentView(root)
        root.setOnApplyWindowInsetsListener { _, ins ->
            val t = ins.getInsets(WindowInsets.Type.statusBars()).top
            if (t != topInset) { topInset = t; pager.adapter?.notifyDataSetChanged() }
            ins
        }

        pager = ViewPager2(this).apply { adapter = PagesAdapter(); offscreenPageLimit = 1 }
        root.addView(pager, FrameLayout.LayoutParams(MATCH, MATCH))

        dots = LinearLayout(this).apply { gravity = Gravity.CENTER }
        root.addView(dots, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply { bottomMargin = dp(124) })

        dock = LinearLayout(this).apply {
            gravity = Gravity.CENTER; background = glass(this@LauncherActivity, 34)
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        root.addView(dock, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply { setMargins(dp(14), 0, dp(14), dp(22)) })
        refreshDock(); refreshDots()

        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageScrolled(position: Int, offset: Float, px: Int) {
                val lib = (pager.adapter?.itemCount ?: 1) - 1
                val f = (position + offset - (lib - 1)).coerceIn(0f, 1f)   // 0 = home, 1 = App Library
                dock.alpha = 1 - f; dots.alpha = 1 - f
                dock.visibility = if (f > 0.99f) View.INVISIBLE else View.VISIBLE
                root.setBackgroundColor(Color.argb((f * 120).toInt(), 0, 0, 0))
                try { window.setBackgroundBlurRadius((f * 80).toInt()) } catch (_: Throwable) {}
            }
            override fun onPageSelected(p: Int) = refreshDots()
        })

        val f = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED); addDataScheme("package")
        }
        registerReceiver(pkgReceiver, f, Context.RECEIVER_EXPORTED)
    }

    override fun onDestroy() { runCatching { unregisterReceiver(pkgReceiver) }; super.onDestroy() }
    override fun onWindowFocusChanged(h: Boolean) { super.onWindowFocusChanged(h); if (h) hideBars() }
    override fun onNewIntent(i: Intent?) {
        super.onNewIntent(i); closeOverlay()
        if (::pager.isInitialized && pager.currentItem != 0) pager.setCurrentItem(0, true)
    }
    @Deprecated("") override fun onBackPressed() {
        if (overlay != null) closeOverlay() else if (pager.currentItem != 0) pager.setCurrentItem(0, true)
    }

    private fun hideBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.navigationBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            isAppearanceLightStatusBars = false
        }
    }

    private fun reload() { apps = Repo.load(this); pager.adapter?.notifyDataSetChanged(); refreshDock(); refreshDots() }

    // ---------- swipe-down = Smart Search ----------
    private val gd by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 == null || overlay != null) return false
                val dy = e2.y - e1.y; val dx = e2.x - e1.x
                val lastIdx = (pager.adapter?.itemCount ?: 1) - 1
                if (pager.currentItem < lastIdx && dy > dp(100) && vy > 900 && abs(dy) > abs(dx) * 1.5f &&
                    e1.y < resources.displayMetrics.heightPixels * 0.7f) { openSearch(); return true }
                return false
            }
        })
    }
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean { gd.onTouchEvent(ev); return super.dispatchTouchEvent(ev) }

    // ---------- data ----------
    private fun dockApps(): List<AppEntry> {
        val saved = prefs.getString("dock", null)
        if (saved != null) return saved.split(",").mapNotNull { p -> apps.firstOrNull { it.pkg == p } }
        val pm = packageManager
        val intents = listOf(Intent(Intent.ACTION_DIAL), Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")),
            Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")), Intent(MediaStore.ACTION_IMAGE_CAPTURE))
        val out = intents.mapNotNull { pm.resolveActivity(it, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName }
            .distinct().mapNotNull { p -> apps.firstOrNull { it.pkg == p } }.toMutableList()
        for (a in apps) if (out.size < 4 && out.none { it.pkg == a.pkg }) out += a
        return out.take(4)
    }

    private fun homeApps(): List<AppEntry> {
        val have = apps.map { it.pkg }.toSet()
        val saved = prefs.getString("home", null)
        val pkgs = if (saved == null) {
            val d = dockApps().map { it.pkg }.toSet()
            apps.map { it.pkg }.distinct().filter { it !in d }.take(16)
                .also { prefs.edit().putString("home", it.joinToString(",")).apply() }
        } else saved.split(",").filter { it.isNotEmpty() && it in have }
        return pkgs.mapNotNull { p -> apps.firstOrNull { it.pkg == p } }
    }
    private fun homePageCount() = 1 + ceil(maxOf(0, homeApps().size - 16) / 24.0).toInt()

    private fun toggleHome(pkg: String) {
        val l = homeApps().map { it.pkg }.toMutableList()
        if (!l.remove(pkg)) l.add(pkg)
        prefs.edit().putString("home", l.joinToString(",")).apply()
        pager.adapter?.notifyDataSetChanged(); refreshDots()
    }

    private fun launch(a: AppEntry, v: View?) {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(a.cn)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        val o = v?.let { ActivityOptions.makeScaleUpAnimation(it, 0, 0, it.width, it.height).toBundle() }
        try { startActivity(i, o) } catch (_: Exception) {}
        prefs.edit().putInt("n_" + a.pkg, prefs.getInt("n_" + a.pkg, 0) + 1).apply()
        closeOverlay()
    }

    private fun menu(v: View, a: AppEntry, onHome: Boolean) {
        PopupMenu(this, v).apply {
            menu.add(0, 1, 0, if (onHome) "Remove from Home Screen" else "Add to Home Screen")
            menu.add(0, 2, 0, "App Info"); menu.add(0, 3, 0, "Uninstall")
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> toggleHome(a.pkg)
                    2 -> startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.pkg}")))
                    3 -> startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${a.pkg}")))
                }; true
            }
            show()
        }
    }

    // ---------- UI pieces ----------
    private fun label(t: String, sp: Float = 11f) = TextView(this).apply {
        text = t; textSize = sp; setTextColor(Color.WHITE); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER; setShadowLayer(4f, 0f, 1f, Color.argb(120, 0, 0, 0))
    }

    private fun tile(a: AppEntry, size: Int, showLabel: Boolean, onHome: Boolean): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        addView(IconView(context, a.icon), LinearLayout.LayoutParams(size, size))
        if (showLabel) addView(label(a.label).apply { setPadding(0, dp(5), 0, 0) }, LinearLayout.LayoutParams(MATCH, WRAP))
        setOnClickListener { launch(a, it) }
        setOnLongClickListener { menu(it, a, onHome); true }
    }

    private fun refreshDock() {
        dock.removeAllViews()
        dockApps().forEach { dock.addView(tile(it, dp(58), false, false), LinearLayout.LayoutParams(0, WRAP, 1f)) }
    }

    private fun refreshDots() {
        dots.removeAllViews()
        val n = homePageCount()
        for (i in 0 until n) dots.addView(View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL
                setColor(if (i == pager.currentItem) Color.WHITE else Color.argb(110, 255, 255, 255)) }
        }, LinearLayout.LayoutParams(dp(7), dp(7)).apply { setMargins(dp(4), 0, dp(4), 0) })
    }

    private fun pageMenu(v: View) {
        PopupMenu(this, v).apply {
            menu.add(0, 1, 0, "Change wallpaper"); menu.add(0, 2, 0, "Enable full-screen gestures")
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> startActivity(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Wallpaper"))
                    2 -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }; true
            }
            show()
        }
    }

    // ---------- pager ----------
    private inner class PagesAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = homePageCount() + 1
        override fun onCreateViewHolder(p: ViewGroup, t: Int): RecyclerView.ViewHolder {
            val f = FrameLayout(this@LauncherActivity)
            f.layoutParams = RecyclerView.LayoutParams(MATCH, MATCH)
            return object : RecyclerView.ViewHolder(f) {}
        }
        override fun onBindViewHolder(h: RecyclerView.ViewHolder, p: Int) {
            val f = h.itemView as FrameLayout
            f.removeAllViews()
            f.addView(if (p == itemCount - 1) buildLibrary() else buildHome(p), FrameLayout.LayoutParams(MATCH, MATCH))
        }
    }

    private fun buildHome(p: Int): View {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), topInset + dp(14), dp(16), dp(150))
            setOnLongClickListener { pageMenu(it); true }
        }
        if (p == 0) page.addView(clockWidget(), LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(18) })
        val start = if (p == 0) 0 else 16 + (p - 1) * 24
        val count = if (p == 0) 16 else 24
        val cw = (resources.displayMetrics.widthPixels - dp(32)) / 4
        val grid = GridLayout(this).apply { columnCount = 4 }
        homeApps().drop(start).take(count).forEachIndexed { i, a ->
            grid.addView(tile(a, dp(58), true, true), GridLayout.LayoutParams(GridLayout.spec(i / 4), GridLayout.spec(i % 4)).apply {
                width = cw; height = WRAP; bottomMargin = dp(14)
            })
        }
        page.addView(grid)
        return page
    }

    private fun clockWidget(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; background = glass(this@LauncherActivity, 26)
        setPadding(dp(20), dp(14), dp(20), dp(14))
        addView(TextClock(context).apply {
            format12Hour = "EEEE, d MMMM"; format24Hour = "EEEE, d MMMM"; textSize = 14f
            setTextColor(Color.argb(210, 255, 255, 255))
        })
        addView(TextClock(context).apply {
            format12Hour = "h:mm"; format24Hour = "HH:mm"; textSize = 58f
            typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL); setTextColor(Color.WHITE)
        })
    }

    // ---------- App Library ----------
    private fun buildLibrary(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), topInset + dp(14), dp(16), dp(40))
        }
        col.addView(TextView(this).apply {
            text = "App Library"; textSize = 16f; setTextColor(Color.argb(210, 255, 255, 255))
            background = glass(this@LauncherActivity, 26); setPadding(dp(20), dp(13), dp(20), dp(13))
            setOnClickListener { openSearch() }
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(18) })

        val cw = (resources.displayMetrics.widthPixels - dp(32 + 14)) / 2
        val used = apps.filter { prefs.getInt("n_" + it.pkg, 0) > 0 }.sortedByDescending { prefs.getInt("n_" + it.pkg, 0) }
        val recent = apps.sortedByDescending { it.installed }
        val cards = mutableListOf<Pair<String, List<AppEntry>>>()
        cards.add("Suggestions" to (used + recent).distinctBy { it.pkg }.take(4))
        cards.add("Recently Added" to recent.take(4))
        val grouped = apps.groupBy { it.cat }
        categoryOrder.forEach { c -> grouped[c]?.let { cards.add(c to it) } }

        val grid = GridLayout(this).apply { columnCount = 2 }
        cards.forEachIndexed { i, (title, list) ->
            grid.addView(card(title, list, cw), GridLayout.LayoutParams(GridLayout.spec(i / 2), GridLayout.spec(i % 2)).apply {
                width = cw; height = WRAP; setMargins(if (i % 2 == 0) 0 else dp(14), 0, 0, dp(14))
            })
        }
        col.addView(grid)
        return ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(col) }
    }

    private fun cellLp(i: Int, cell: Int, gap: Int) =
        GridLayout.LayoutParams(GridLayout.spec(i / 2), GridLayout.spec(i % 2)).apply {
            width = cell; height = cell; setMargins(0, 0, if (i % 2 == 0) gap else 0, if (i < 2) gap else 0)
        }

    private fun card(title: String, list: List<AppEntry>, cw: Int): View {
        val pad = dp(12); val gap = dp(10); val cell = (cw - 2 * pad - gap) / 2
        val special = title == "Suggestions" || title == "Recently Added"
        val shown = if (!special && list.size > 4) list.take(3) else list.take(4)
        val g = GridLayout(this).apply { columnCount = 2 }
        shown.forEachIndexed { i, a ->
            g.addView(IconView(this, a.icon).apply {
                setOnClickListener { launch(a, it) }; setOnLongClickListener { menu(it, a, false); true }
            }, cellLp(i, cell, gap))
        }
        if (!special && list.size > 4) {
            val mini = GridLayout(this).apply { columnCount = 2; setOnClickListener { showCategory(title, list) } }
            val ms = (cell - dp(4)) / 2
            list.drop(3).take(4).forEachIndexed { j, a ->
                mini.addView(IconView(this, a.icon), GridLayout.LayoutParams(GridLayout.spec(j / 2), GridLayout.spec(j % 2)).apply {
                    width = ms; height = ms; setMargins(0, 0, if (j % 2 == 0) dp(4) else 0, if (j < 2) dp(4) else 0)
                })
            }
            g.addView(mini, cellLp(3, cell, gap))
        }
        val box = FrameLayout(this).apply {
            background = glass(this@LauncherActivity, 28); setOnClickListener { showCategory(title, list) }
            addView(g, FrameLayout.LayoutParams(WRAP, WRAP).apply { setMargins(pad, pad, pad, pad) })
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(box, LinearLayout.LayoutParams(cw, cw))
            addView(label(title, 12f).apply { gravity = Gravity.START; setPadding(dp(6), dp(6), 0, 0) })
        }
    }

    // ---------- overlays ----------
    private fun showOverlay(o: View) { closeOverlay(); o.alpha = 0f; root.addView(o, FrameLayout.LayoutParams(MATCH, MATCH)); o.animate().alpha(1f).setDuration(160).start(); overlay = o }
    private fun closeOverlay() {
        overlay?.let {
            root.removeView(it); overlay = null
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(root.windowToken, 0)
        }
    }

    private fun showCategory(title: String, list: List<AppEntry>) {
        val cw = (resources.displayMetrics.widthPixels - dp(32 + 36)) / 4
        val grid = GridLayout(this).apply { columnCount = 4 }
        list.forEachIndexed { i, a ->
            grid.addView(tile(a, dp(56), true, false), GridLayout.LayoutParams(GridLayout.spec(i / 4), GridLayout.spec(i % 4)).apply {
                width = cw; height = WRAP; bottomMargin = dp(14)
            })
        }
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; isClickable = true
            background = glass(this@LauncherActivity, 34, Color.argb(120, 255, 255, 255), Color.argb(70, 255, 255, 255))
            setPadding(dp(18), dp(18), dp(18), dp(6))
            addView(label(title, 16f).apply { gravity = Gravity.START; setPadding(dp(4), 0, 0, dp(14)) })
            addView(ScrollView(context).apply { addView(grid) })
        }
        val o = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(120, 0, 0, 0)); setOnClickListener { closeOverlay() }
            addView(sheet, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER).apply { setMargins(dp(16), dp(60), dp(16), dp(60)) })
        }
        showOverlay(o)
    }

    private val settingsItems = listOf(
        "Wi-Fi" to Settings.ACTION_WIFI_SETTINGS, "Bluetooth" to Settings.ACTION_BLUETOOTH_SETTINGS,
        "Display" to Settings.ACTION_DISPLAY_SETTINGS, "Sound" to Settings.ACTION_SOUND_SETTINGS,
        "Battery" to Intent.ACTION_POWER_USAGE_SUMMARY, "Storage" to Settings.ACTION_INTERNAL_STORAGE_SETTINGS,
        "Location" to Settings.ACTION_LOCATION_SOURCE_SETTINGS, "Accessibility" to Settings.ACTION_ACCESSIBILITY_SETTINGS)

    private fun openSearch() {
        if (overlay != null) return
        val input = EditText(this).apply {
            hint = "Search apps, settings, web"; setHintTextColor(Color.argb(150, 255, 255, 255)); setTextColor(Color.WHITE)
            background = glass(this@LauncherActivity, 26); setPadding(dp(20), dp(13), dp(20), dp(13)); isSingleLine = true
        }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(10), 0, dp(340)) }

        fun row(text: String, a: AppEntry?, onClick: () -> Unit) = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6), dp(8), dp(6), dp(8))
            if (a != null) addView(IconView(context, a.icon), LinearLayout.LayoutParams(dp(44), dp(44)))
            addView(label(text, 16f).apply { gravity = Gravity.START; setPadding(dp(14), 0, 0, 0) }, LinearLayout.LayoutParams(0, WRAP, 1f))
            setOnClickListener { onClick() }
            if (a != null) setOnLongClickListener { menu(it, a, false); true }
        }

        fun render(q: String) {
            list.removeAllViews()
            val query = q.trim().lowercase()
            val hits = if (query.isEmpty()) apps else apps.filter { it.label.lowercase().contains(query) }
            if (query.isNotEmpty()) settingsItems.filter { it.first.lowercase().contains(query) }.forEach { (n, act) ->
                list.addView(row("Settings: $n", null) { startActivity(Intent(act).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); closeOverlay() })
            }
            var last = ' '
            hits.forEach { a ->
                if (query.isEmpty()) {
                    val ch = a.label.firstOrNull()?.uppercaseChar()?.takeIf { it.isLetter() } ?: '#'
                    if (ch != last) { last = ch; list.addView(label(ch.toString(), 13f).apply { gravity = Gravity.START; setPadding(dp(6), dp(14), 0, dp(4)); alpha = 0.7f }) }
                }
                list.addView(row(a.label, a) { launch(a, null) })
            }
            if (query.isNotEmpty()) list.addView(row("Search web for \u201C$q\u201D", null) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); closeOverlay()
            })
        }
        render("")
        input.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = render(s?.toString().orEmpty())
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), topInset + dp(14), dp(16), 0)
            addView(input, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(ScrollView(context).apply { isVerticalScrollBarEnabled = false; addView(list) })
        }
        val o = FrameLayout(this).apply { setBackgroundColor(Color.argb(170, 0, 0, 0)); isClickable = true; addView(col) }
        showOverlay(o)
        input.postDelayed({ input.requestFocus(); (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(input, 0) }, 150)
    }
}
