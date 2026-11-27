package com.example.ioslauncher

import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : Activity() {

    class App(val label: String, val pkg: String, val icon: Drawable, val intent: Intent, val category: Int)

    companion object {
        // A régler selon la position de la caméra frontale de ton téléphone (en dp)
        const val ISLAND_TOP = 8
        const val ISLAND_W = 96
        const val ISLAND_H = 32
        val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }

    private val apps = mutableListOf<App>()
    private lateinit var outer: FrameLayout
    private lateinit var pager: ViewPager2
    private lateinit var dots: LinearLayout
    private lateinit var island: TextView
    private lateinit var gd: GestureDetector
    private var searchView: FrameLayout? = null
    private var controlView: FrameLayout? = null
    private var torchOn = false
    private val handler = Handler(Looper.getMainLooper())
    private val collapse = Runnable { toggleIsland(false) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun rounded(c: Int, r: Int) = GradientDrawable().apply { cornerRadius = dp(r).toFloat(); setColor(c) }

    // ---------- Données ----------
    private fun loadApps() {
        val pm = packageManager
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        apps.clear()
        for (ri in pm.queryIntentActivities(q, 0)) {
            val pkg = ri.activityInfo.packageName
            if (pkg == packageName) continue
            val launch = pm.getLaunchIntentForPackage(pkg) ?: continue
            apps.add(App(ri.loadLabel(pm).toString(), pkg, ri.loadIcon(pm), launch, pm.getApplicationInfo(pkg, 0).category))
        }
        val unique = apps.distinctBy { it.pkg }.sortedBy { it.label.lowercase() }
        apps.clear(); apps.addAll(unique)
    }

    private fun dockApps(): List<App> {
        val pm = packageManager
        val probes = listOf(
            Intent(Intent.ACTION_DIAL),
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")),
            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.example.com")),
            Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        )
        val found = probes.mapNotNull { p ->
            val pkg = pm.resolveActivity(p, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
            apps.firstOrNull { it.pkg == pkg }
        }.distinctBy { it.pkg }
        return (found + apps.filter { a -> found.none { it.pkg == a.pkg } }).take(4)
    }

    // ---------- Vues d'apps ----------
    private fun iconView(app: App, size: Int): ImageView = ImageView(this).apply {
        setImageDrawable(app.icon)
        layoutParams = LinearLayout.LayoutParams(size, size)
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, v.width * 0.225f) }
        }
        clipToOutline = true
        setOnClickListener { closeOverlays(); startActivity(app.intent) }
    }

    private fun cell(app: App, labels: Boolean = true) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        addView(iconView(app, dp(58)))
        if (labels) addView(TextView(context).apply {
            text = app.label; setTextColor(Color.WHITE); textSize = 11f; maxLines = 1
            ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER
            setShadowLayer(4f, 0f, 1f, Color.BLACK); setPadding(dp(2), dp(4), dp(2), 0)
        })
    }

    private fun gridPage(list: List<App>, rows: Int): View {
        val g = GridLayout(this)
        g.columnCount = 4; g.rowCount = rows
        g.setPadding(dp(8), dp(8), dp(8), 0)
        fun lp() = GridLayout.LayoutParams(
            GridLayout.spec(GridLayout.UNDEFINED, 1f), GridLayout.spec(GridLayout.UNDEFINED, 1f)
        ).apply { width = 0; height = 0; setMargins(0, 0, 0, dp(10)) }
        list.forEach { g.addView(cell(it), lp()) }
        repeat(rows * 4 - list.size) { g.addView(Space(this), lp()) }
        return g
    }

    private fun clockWidget(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(12), dp(20), dp(12))
        background = rounded(0x55FFFFFF, 24)
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(dp(16), dp(8), dp(16), dp(12)) }
        addView(TextClock(context).apply {
            format24Hour = "HH:mm"; format12Hour = "HH:mm"; textSize = 44f; setTextColor(Color.WHITE)
        })
        addView(TextClock(context).apply {
            format24Hour = "EEEE d MMMM"; format12Hour = "EEEE d MMMM"; textSize = 15f; setTextColor(Color.WHITE)
        })
    }

    // ---------- Bibliothèque d'apps ----------
    private fun libraryPage(): View {
        val names = mapOf(
            ApplicationInfo.CATEGORY_SOCIAL to "Réseaux sociaux", ApplicationInfo.CATEGORY_GAME to "Jeux",
            ApplicationInfo.CATEGORY_AUDIO to "Musique", ApplicationInfo.CATEGORY_VIDEO to "Vidéo",
            ApplicationInfo.CATEGORY_IMAGE to "Photo", ApplicationInfo.CATEGORY_NEWS to "Actualités",
            ApplicationInfo.CATEGORY_MAPS to "Cartes", ApplicationInfo.CATEGORY_PRODUCTIVITY to "Productivité"
        )
        val groups = apps.groupBy { names[it.category] ?: "Autres" }.toList().sortedBy { it.first }
        val g = GridLayout(this)
        g.columnCount = 2
        g.setPadding(dp(12), dp(12), dp(12), dp(24))
        groups.forEach { (name, list) ->
            g.addView(folder(name, list), GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f)
            ).apply { width = 0; height = WRAP; setMargins(dp(8), dp(8), dp(8), dp(8)) })
        }
        return ScrollView(this).apply { addView(g) }
    }

    private fun folder(name: String, list: List<App>) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(10), dp(10), dp(10), dp(8))
        background = rounded(0x55FFFFFF, 24)
        val shown = list.take(4)
        for (r in 0..1) addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER
            for (c in 0..1) shown.getOrNull(r * 2 + c)?.let {
                addView(iconView(it, dp(44)).apply { (layoutParams as LinearLayout.LayoutParams).setMargins(dp(4), dp(4), dp(4), dp(4)) })
            }
        })
        addView(TextView(context).apply {
            text = "$name (${list.size})"; setTextColor(Color.WHITE); textSize = 12f; gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
        })
        setOnClickListener {
            AlertDialog.Builder(this@MainActivity).setTitle(name)
                .setItems(list.map { it.label }.toTypedArray()) { _, i -> startActivity(list[i].intent) }.show()
        }
    }

    class PagesAdapter(val pages: List<() -> View>) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = pages.size
        override fun onCreateViewHolder(p: ViewGroup, t: Int) = object : RecyclerView.ViewHolder(
            FrameLayout(p.context).apply { layoutParams = ViewGroup.LayoutParams(MATCH, MATCH) }) {}
        override fun onBindViewHolder(h: RecyclerView.ViewHolder, i: Int) {
            val f = h.itemView as FrameLayout
            f.removeAllViews(); f.addView(pages[i]())
        }
    }

    // ---------- Création ----------
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULL_SCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        loadApps()

        outer = FrameLayout(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        outer.addView(content, MATCH, MATCH)
        @Suppress("DEPRECATION")
        outer.setOnApplyWindowInsetsListener { _, i ->
            content.setPadding(0, i.systemWindowInsetTop + dp(8), 0, i.systemWindowInsetBottom); i
        }

        val dockList = dockApps()
        val rest = apps.filter { a -> dockList.none { it.pkg == a.pkg } }
        val pages = mutableListOf<() -> View>()
        pages.add {
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(clockWidget())
                addView(gridPage(rest.take(16), 4), LinearLayout.LayoutParams(MATCH, 0, 1f))
            }
        }
        rest.drop(16).chunked(24).forEach { c -> pages.add { gridPage(c, 6) } }
        pages.add { libraryPage() }

        pager = ViewPager2(this).apply { adapter = PagesAdapter(pages) }
        content.addView(pager, LinearLayout.LayoutParams(MATCH, 0, 1f))

        dots = LinearLayout(this).apply { gravity = Gravity.CENTER }
        content.addView(dots, LinearLayout.LayoutParams(MATCH, dp(24)))
        fun updateDots(sel: Int) {
            dots.removeAllViews()
            for (i in pages.indices) dots.addView(View(this).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (i == sel) Color.WHITE else 0x66FFFFFF) }
                layoutParams = LinearLayout.LayoutParams(dp(7), dp(7)).apply { setMargins(dp(4), 0, dp(4), 0) }
            })
        }
        updateDots(0)
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) { updateDots(position) }
        })

        val dock = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(12), dp(8), dp(12))
            background = rounded(0x55FFFFFF, 30)
            dockList.forEach { addView(cell(it, false), LinearLayout.LayoutParams(0, WRAP, 1f)) }
        }
        content.addView(dock, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(dp(12), dp(4), dp(12), dp(8)) })

        island = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 14f; gravity = Gravity.CENTER
            background = rounded(Color.BLACK, 20)
            setOnClickListener { toggleIsland(true) }
        }
        outer.addView(island, FrameLayout.LayoutParams(dp(ISLAND_W), dp(ISLAND_H), Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            .apply { topMargin = dp(ISLAND_TOP) })

        gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 == null) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                if (Math.abs(dy) < Math.abs(dx) * 1.5f || Math.abs(dy) < dp(80)) return false
                if (dy > 0) {
                    if (e1.x > outer.width * 0.6f && e1.y < outer.height * 0.12f) openControl()
                    else if (controlView == null) openSearch()
                } else if (controlView != null) closeOverlays()
                return false
            }
        })
        setContentView(outer)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        gd.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        closeOverlays(); pager.setCurrentItem(0, false)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (searchView != null || controlView != null) closeOverlays() else pager.setCurrentItem(0, true)
    }

    // ---------- Dynamic Island ----------
    private fun toggleIsland(expand: Boolean) {
        val lp = island.layoutParams
        val w0 = lp.width; val h0 = lp.height
        val open = expand && lp.width < dp(200)
        val w1 = dp(if (open) 280 else ISLAND_W); val h1 = dp(if (open) 76 else ISLAND_H)
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        island.text = if (open) SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()) +
            "   •   " + bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) + "%" else ""
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 300
            addUpdateListener {
                val f = it.animatedFraction
                lp.width = (w0 + (w1 - w0) * f).toInt(); lp.height = (h0 + (h1 - h0) * f).toInt()
                island.layoutParams = lp
            }
            start()
        }
        handler.removeCallbacks(collapse)
        if (open) handler.postDelayed(collapse, 3000)
    }

    // ---------- Recherche (balayage vers le bas) ----------
    private fun openSearch() {
        if (searchView != null) return
        val v = FrameLayout(this).apply { setBackgroundColor(0xCC000000.toInt()); isClickable = true }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(90), dp(16), dp(16)) }
        val input = EditText(this).apply {
            hint = "Recherche"; setHintTextColor(0x99FFFFFF.toInt()); setTextColor(Color.WHITE); setSingleLine()
            background = rounded(0x33FFFFFF, 14); setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        input.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                results.removeAllViews()
                val q = s?.toString() ?: ""
                if (q.isBlank()) return
                apps.filter { it.label.contains(q, true) }.take(8).forEach { results.addView(row(it)) }
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        col.addView(input)
        col.addView(ScrollView(this).apply { addView(results) })
        v.addView(col)
        outer.addView(v)
        searchView = v
        input.requestFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(input, 0)
    }

    private fun row(a: App) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, dp(6))
        addView(iconView(a, dp(40)))
        addView(TextView(context).apply { text = a.label; setTextColor(Color.WHITE); textSize = 16f; setPadding(dp(12), 0, 0, 0) })
        setOnClickListener { closeOverlays(); startActivity(a.intent) }
    }

    // ---------- Centre de contrôle (balayage coin haut droit) ----------
    private fun openControl() {
        if (controlView != null) return
        val v = FrameLayout(this).apply {
            setBackgroundColor(0x99000000.toInt()); isClickable = true
            setOnClickListener { closeOverlays() }
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; isClickable = true
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = rounded(0xDD2C2C2E.toInt(), 28)
        }
        val g = GridLayout(this).apply { columnCount = 2 }
        fun tile(label: String, action: () -> Unit) {
            g.addView(TextView(this).apply {
                text = label; gravity = Gravity.CENTER; textSize = 14f; setTextColor(Color.WHITE)
                background = rounded(0x33FFFFFF, 20); setOnClickListener { action() }
            }, GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f))
                .apply { width = 0; height = dp(80); setMargins(dp(6), dp(6), dp(6), dp(6)) })
        }
        fun go(action: String) { closeOverlays(); startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        tile("Wi‑Fi / Internet") { go(Settings.Panel.ACTION_INTERNET_CONNECTIVITY) }
        tile("Bluetooth") { go(Settings.ACTION_BLUETOOTH_SETTINGS) }
        tile("Mode avion") { go(Settings.ACTION_AIRPLANE_MODE_SETTINGS) }
        tile("Lampe torche") { toggleTorch() }
        tile("Son") { go(Settings.ACTION_SOUND_SETTINGS) }
        tile("Affichage") { go(Settings.ACTION_DISPLAY_SETTINGS) }
        panel.addView(g)
        panel.addView(SeekBar(this).apply {
            max = 100; progress = 70
            setPadding(dp(12), dp(16), dp(12), dp(8))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {
                    val a = window.attributes; a.screenBrightness = Math.max(p, 5) / 100f; window.attributes = a
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        })
        v.addView(panel, FrameLayout.LayoutParams(dp(300), WRAP, Gravity.TOP or Gravity.END)
            .apply { topMargin = dp(70); marginEnd = dp(12) })
        outer.addView(v)
        controlView = v
    }

    private fun toggleTorch() {
        try {
            val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            torchOn = !torchOn
            cm.setTorchMode(cm.cameraIdList.first(), torchOn)
        } catch (e: Exception) {
            Toast.makeText(this, "Lampe torche indisponible", Toast.LENGTH_SHORT).show()
        }
    }

    private fun closeOverlays() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(outer.windowToken, 0)
        searchView?.let { outer.removeView(it) }
        controlView?.let { outer.removeView(it) }
        searchView = null; controlView = null
    }
}
