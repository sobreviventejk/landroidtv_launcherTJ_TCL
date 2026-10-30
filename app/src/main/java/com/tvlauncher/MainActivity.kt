package com.tvlauncher

import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Home screen in the Google TV style: category tabs on top, the apps of the selected tab below,
 * and an "All apps" tab used to add apps to a category, move them, remove them or uninstall them.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var appSlots: RecyclerView
    private lateinit var allAppsList: RecyclerView
    private lateinit var tabContainer: LinearLayout
    private lateinit var tabScroll: HorizontalScrollView
    private lateinit var greetingText: TextView
    private lateinit var topApps: RecyclerView
    private lateinit var topAppsLabel: TextView
    private lateinit var emptyHint: TextView
    private lateinit var settingsButton: ImageButton
    private lateinit var searchButton: ImageButton
    private lateinit var customizeButton: ImageButton
    private lateinit var profileButton: ImageButton
    private lateinit var wallpaperImageView: ImageView
    private lateinit var wallpaperDimOverlay: View
    private lateinit var auroraView: AuroraView
    private lateinit var weatherBox: LinearLayout
    private lateinit var weatherIcon: ImageView
    private lateinit var weatherText: TextView
    private lateinit var appManager: AppManager
    private lateinit var layoutPrefs: LayoutPrefs
    private lateinit var appSlotAdapter: AppSlotAdapter
    private lateinit var allAppsAdapter: AllAppsAdapter
    private lateinit var topAdapter: AppSlotAdapter
    private lateinit var pinPrefs: PinPrefs
    private lateinit var gridLayoutManager: GridLayoutManager

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Tab being shown: [TAB_HOME], [TAB_ALL] or the id of a category. */
    private var currentTab: String = TAB_HOME

    /** Last tab that was not "All apps", so Back can return to it. */
    private var previousContentTab: String = TAB_HOME

    /** Category that new apps go to by default (set when "+" is used inside a category tab). */
    private var pendingAddCategoryId: String? = null

    private val tabViews = LinkedHashMap<String, TextView>()

    /** Protected categories opened with the PIN since the launcher was last left. */
    private val unlockedTabs = mutableSetOf<String>()

    /** Greeting text on screen, to avoid redrawing it when nothing changed. */
    private var lastGreeting: String? = null

    /** Last forecast shown in the header, reused when the week dialog opens. */
    private var lastForecast: Weather.Forecast? = null

    /** When the header weather was last updated, to refresh it from time to time. */
    private var lastWeatherUiRefresh = 0L

    /** Key (see AppSlotAdapter.AppSlot.key) of the tile that had focus last. */
    private var lastFocusedKey: String? = null

    /** Apps on the home screen by app identifier, as loaded from the system. */
    private var infoById: Map<String, AppInfo> = emptyMap()

    /** Identifier of the app being moved with the remote (reorder mode), or null. */
    private var reorderingId: String? = null

    private var folderPickCallback: ((Uri) -> Unit)? = null

    private val folderPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {
                }
                folderPickCallback?.invoke(uri)
            }
            folderPickCallback = null
        }

    private val exportPicker =
        registerForActivityResult(ActivityResultContracts.CreateDocument()) { uri ->
            if (uri != null) writeBackup(uri)
        }

    private val importPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) readBackup(uri)
        }

    private val profilePicker =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) showProfilePhotoAdjustDialog(uri)
        }

    private val logExportPicker =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri != null) writeEventLog(uri)
        }

    /** Used when the TV has no system folder picker: the user picks the photos themselves. */
    private val folderPhotosPicker =
        registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            if (!uris.isNullOrEmpty()) importFolderPhotos(uris)
        }

    private val wallpaperTicker = object : Runnable {
        override fun run() {
            if (WallpaperManager.isRefreshDue(this@MainActivity)) {
                applyWallpaper(force = false)
            }
            applyGreeting()
            // Keeps the header forecast fresh; Weather.load reuses the cache when it is still valid.
            if (layoutPrefs.isWeatherEnabled() &&
                System.currentTimeMillis() - lastWeatherUiRefresh >= WEATHER_UI_REFRESH_MS
            ) {
                refreshWeather(force = false)
            }
            mainHandler.postDelayed(this, WALLPAPER_CHECK_MS)
        }
    }

    private val refreshReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            if (intent?.action == LauncherBroadcast.ACTION_REFRESH_HOME) {
                appManager.invalidateSelectionCache()
                appManager.invalidateAppListCache()
                loadHomeInfos()
                refreshContent(null, requestFocus = false)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        appSlots = findViewById(R.id.appSlots)
        allAppsList = findViewById(R.id.allApps)
        tabContainer = findViewById(R.id.tabContainer)
        tabScroll = findViewById(R.id.tabScroll)
        greetingText = findViewById(R.id.greetingText)
        topApps = findViewById(R.id.topApps)
        topAppsLabel = findViewById(R.id.topAppsLabel)
        emptyHint = findViewById(R.id.emptyHint)
        settingsButton = findViewById(R.id.settingsButton)
        searchButton = findViewById(R.id.searchButton)
        customizeButton = findViewById(R.id.customizeButton)
        profileButton = findViewById(R.id.profileButton)
        wallpaperImageView = findViewById(R.id.wallpaperImageView)
        wallpaperDimOverlay = findViewById(R.id.wallpaperDimOverlay)
        auroraView = findViewById(R.id.auroraView)
        weatherBox = findViewById(R.id.weatherBox)
        weatherIcon = findViewById(R.id.weatherIcon)
        weatherText = findViewById(R.id.weatherText)

        appManager = AppManager(this)
        layoutPrefs = LayoutPrefs(this)
        pinPrefs = PinPrefs(this)
        setupRecyclerView()
        setupAllApps()
        setupTopApps()
        setupClickListeners()
        applyIconLook()
        applyPalette()
        applyGreeting()
        loadHomeInfos()
        buildTabs()
        applyProfilePhoto()
        val saved = layoutPrefs.getLastTab()
        val first = if (saved != null && saved != TAB_NEW && tabViews.containsKey(saved)) saved else TAB_HOME
        selectTab(first, requestFocus = true)
        applyWallpaper()
        refreshWeather(force = false)

        ContextCompat.registerReceiver(
            this,
            refreshReceiver,
            android.content.IntentFilter(LauncherBroadcast.ACTION_REFRESH_HOME),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        CrashLog.log(
            this,
            "Launcher iniciada ${getVersionName()} · ${Build.MANUFACTURER} ${Build.MODEL}" +
                " · Android ${Build.VERSION.RELEASE}"
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home key pressed while this launcher is already open: go back to the first tab.
        if (!::appSlots.isInitialized) return
        if (reorderingId != null) stopReorder()
        if (currentTab != TAB_HOME) {
            selectTab(TAB_HOME, requestFocus = true)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (currentTab == TAB_ALL) {
                val target = if (tabViews.containsKey(previousContentTab)) previousContentTab else TAB_HOME
                selectTab(target, requestFocus = false)
                tabViews[target]?.requestFocus()
            } else if (appSlots.childCount > 0) {
                appSlots.getChildAt(0).requestFocus()
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    /**
     * While an app is being reordered, the arrow keys move it instead of moving the focus,
     * and OK / Back finish the move. Both key down and key up are consumed so that OK does not
     * also click the tile.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (reorderingId != null) {
            val isDown = event.action == KeyEvent.ACTION_DOWN
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (isDown) moveReorderingApp(-1, vertical = false)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (isDown) moveReorderingApp(1, vertical = false)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (isDown) moveReorderingApp(-1, vertical = true)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (isDown) moveReorderingApp(1, vertical = true)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER,
                KeyEvent.KEYCODE_BACK -> {
                    if (isDown && event.repeatCount == 0) stopReorder()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ---- Views ----

    private fun setupRecyclerView() {
        val columns = layoutPrefs.getColumns()
        appSlotAdapter = AppSlotAdapter(
            onPlusClick = { openAllAppsToAdd() },
            onAppLongClick = { position -> showAppContextMenu(position) },
            onHeaderClick = { },
            onItemFocused = { key -> lastFocusedKey = key },
            sizing = buildSizing(columns),
            onAppLaunched = { app -> UsageTracker.record(this, AppIdentifier.encode(app)) }
        )

        gridLayoutManager = GridLayoutManager(this, columns)
        gridLayoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int = 1
        }

        appSlots.layoutManager = gridLayoutManager
        appSlots.adapter = appSlotAdapter
        appSlots.setItemViewCacheSize(0)
        appSlots.recycledViewPool.setMaxRecycledViews(AppSlotAdapter.VIEW_TYPE_APP, 12)
        // Update tiles in place so the remote's focus is not lost when the list changes.
        (appSlots.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
    }

    private fun setupAllApps() {
        allAppsAdapter = AllAppsAdapter(
            onClick = { row -> onAllAppsRowClick(row) },
            onLongClick = { row -> showAllAppActions(row) },
            iconSizePx = { allAppsIconSizePx() },
            rowHeightPx = { allAppsRowHeightPx() },
            iconShape = { layoutPrefs.getIconShape() }
        )
        allAppsList.layoutManager = GridLayoutManager(this, allAppsColumns())
        allAppsList.adapter = allAppsAdapter
        (allAppsList.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
    }

    /** Columns of the "All apps" tab; it has its own size, independent of the other tabs. */
    private fun allAppsColumns(): Int =
        if (layoutPrefs.hasColumnsFor(TAB_ALL)) {
            layoutPrefs.getColumnsFor(TAB_ALL).coerceIn(ALL_APPS_MIN_COLUMNS, ALL_APPS_MAX_COLUMNS)
        } else {
            ALL_APPS_COLUMNS
        }

    private fun allAppsRowHeightPx(): Int {
        val density = resources.displayMetrics.density
        val dp = (120f / allAppsColumns() + 20f).coerceIn(40f, 88f)
        return (dp * density).toInt()
    }

    private fun allAppsIconSizePx(): Int = (allAppsRowHeightPx() * 0.62f).toInt()

    /** Small horizontal row with the most used apps, shown above the grid of the first tab. */
    private fun setupTopApps() {
        val slotPx = (72 * resources.displayMetrics.density).toInt()
        topAdapter = AppSlotAdapter(
            onPlusClick = { },
            onAppLongClick = { },
            onHeaderClick = { },
            onItemFocused = { },
            sizing = AppSlotAdapter.Sizing.forSlot(slotPx, resources.displayMetrics.density),
            onAppLaunched = { app -> UsageTracker.record(this, AppIdentifier.encode(app)) }
        )
        topApps.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        topApps.adapter = topAdapter
        (topApps.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
    }

    /** Shows the most used apps on the first tab only, and only when there are any. */
    private fun refreshTopApps() {
        val show = currentTab == TAB_HOME && layoutPrefs.isMostUsedEnabled()
        val validIds = infoById.keys.filterNot { HiddenApps.isHidden(this, it) }.toSet()
        val ids = if (show) UsageTracker.top(this, validIds, TOP_APPS_LIMIT) else emptyList()
        val slots = ids.mapNotNull { id ->
            infoById[id]?.let { AppSlotAdapter.AppSlot(it, false) }
        }
        topAdapter.submitList(slots)
        val visibility = if (slots.isEmpty()) View.GONE else View.VISIBLE
        topApps.visibility = visibility
        topAppsLabel.visibility = visibility
    }

    private fun buildSizing(columns: Int, rows: Int = layoutPrefs.getRows()): AppSlotAdapter.Sizing {
        return AppSlotAdapter.Sizing.forSlot(
            calculateSlotSizePx(columns),
            resources.displayMetrics.density,
            calculateSlotHeightPx(rows)
        )
    }

    private fun setupClickListeners() {
        settingsButton.setOnClickListener { openAndroidSettings() }
        searchButton.setOnClickListener { showSearchDialog() }
        customizeButton.setOnClickListener { showCustomizeDialog() }
        // Like the Google TV avatar: long press changes the photo, OK shows the system usage.
        profileButton.setOnClickListener { showSystemDashboard() }
        profileButton.setOnLongClickListener {
            profilePicker.launch("image/*")
            true
        }
        weatherBox.setOnClickListener { showWeekForecast() }
    }

    // ---- Greeting ----

    private fun applyGreeting() {
        val level = layoutPrefs.getGreetingSize()
        if (level == LayoutPrefs.GREETING_HIDDEN || !layoutPrefs.isAutoMessagesEnabled()) {
            greetingText.visibility = View.GONE
            lastGreeting = null
            return
        }
        val phrase = RandomMessages.pick(
            layoutPrefs.getMessageKindsMask(),
            layoutPrefs.getMessageTarget(),
            System.currentTimeMillis(),
            layoutPrefs.getMessageIntervalMs()
        )
        if (phrase.isNullOrBlank()) {
            greetingText.visibility = View.GONE
            lastGreeting = null
            return
        }
        val signature = "$level|$phrase"
        if (signature == lastGreeting && greetingText.visibility == View.VISIBLE) return
        lastGreeting = signature
        greetingText.visibility = View.VISIBLE
        greetingText.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            LayoutPrefs.greetingBodySp(level) + 2f
        )
        greetingText.setTextColor(Palette.accent(layoutPrefs.getPalette()))
        greetingText.text = phrase
    }

    /** Applies the chosen palette to the tiles, tabs, greeting and aurora glow. */
    private fun applyPalette() {
        val entry = Palette.of(layoutPrefs.getPalette())
        appSlotAdapter.accentColor = entry.accent
        if (::topAdapter.isInitialized) {
            topAdapter.accentColor = entry.accent
        }
        auroraView.setPalette(entry)
        auroraView.visibility =
            if (layoutPrefs.isAuroraEnabled()) View.VISIBLE else View.GONE
        weatherIcon.setColorFilter(entry.accent)
        updateTabStyles()
        lastGreeting = null
        applyGreeting()
    }

    /** Applies the icon shape to the home grid and to the "most used" row. */
    private fun applyIconLook() {
        val shape = layoutPrefs.getIconShape()
        appSlotAdapter.iconShape = shape
        if (::topAdapter.isInitialized) {
            topAdapter.iconShape = shape
        }
        if (::allAppsAdapter.isInitialized) {
            allAppsAdapter.notifyDataSetChanged()
        }
    }

    // ---- Weather (Belém - PA, Terra Firme) ----

    /** Updates the little weather next to the clock. */
    private fun refreshWeather(force: Boolean = false) {
        if (!layoutPrefs.isWeatherEnabled()) {
            weatherBox.visibility = View.GONE
            lastForecast = null
            return
        }
        Weather.load(this, force) { forecast ->
            if (isFinishing || isDestroyed) return@load
            if (forecast == null) {
                if (lastForecast == null) weatherBox.visibility = View.GONE
                return@load
            }
            lastForecast = forecast
            lastWeatherUiRefresh = System.currentTimeMillis()
            weatherBox.visibility = View.VISIBLE
            weatherIcon.setImageResource(Weather.iconRes(forecast.code))
            weatherIcon.setColorFilter(Palette.accent(layoutPrefs.getPalette()))
            weatherText.text = getString(
                R.string.weather_now,
                forecast.temperatureC,
                Weather.conditionLabel(forecast.code)
            )
        }
    }

    /** Week forecast dialog, opened by clicking the weather in the header. */
    private fun showWeekForecast() {
        val forecast = lastForecast ?: Weather.cached(this)
        if (forecast == null) {
            Toast.makeText(this, R.string.weather_unavailable, Toast.LENGTH_SHORT).show()
            refreshWeather(force = true)
            return
        }
        val lines = forecast.days.mapIndexed { index, day ->
            val weekday = when (index) {
                0 -> getString(R.string.weather_today)
                1 -> getString(R.string.weather_tomorrow)
                else -> {
                    val label = Weather.weekdayLabel(day.isoDate)
                    if (label.isEmpty()) label
                    else label.substring(0, 1).uppercase() + label.substring(1)
                }
            }
            val rain = if (day.rainPercent > 0) {
                "  ·  " + getString(R.string.weather_rain_chance, day.rainPercent)
            } else {
                ""
            }
            getString(
                R.string.weather_day_line,
                weekday,
                day.minC,
                day.maxC,
                Weather.conditionLabel(day.code)
            ) + rain
        }
        val dialog = AlertDialog.Builder(this, R.style.Theme_TVLauncher_AboutDialog)
            .setTitle(R.string.weather_week_title)
            .setMessage(lines.joinToString("\n"))
            .setPositiveButton(R.string.close, null)
            .setNeutralButton(R.string.weather_refresh) { _, _ -> refreshWeather(force = true) }
            .create()
        styleDialog(dialog, widthFraction = 0.5f, maxWidthDp = 620)
        dialog.show()
    }

    /**
     * Keeps every dialog in a comfortable size on TV: never the full screen width, and with an
     * optional height cap so long contents scroll instead of being clipped.
     */
    private fun styleDialog(
        dialog: AlertDialog,
        widthFraction: Float = 0.46f,
        maxWidthDp: Int = 600,
        heightFraction: Float = 0f
    ) {
        dialog.setOnShowListener {
            val metrics = resources.displayMetrics
            val width = minOf(
                (metrics.widthPixels * widthFraction).toInt(),
                (maxWidthDp * metrics.density).toInt()
            )
            val height = if (heightFraction > 0f) {
                (metrics.heightPixels * heightFraction).toInt()
            } else {
                ViewGroup.LayoutParams.WRAP_CONTENT
            }
            dialog.window?.setLayout(width, height)
        }
    }

    private fun showGreetingSizeDialog() {
        val labels = arrayOf(
            getString(R.string.greeting_size_hidden),
            getString(R.string.greeting_size_1),
            getString(R.string.greeting_size_2),
            getString(R.string.greeting_size_3),
            getString(R.string.greeting_size_4),
            getString(R.string.greeting_size_5)
        )
        AlertDialog.Builder(this, R.style.Theme_TVLauncher_AboutDialog)
            .setTitle(R.string.customize_greeting_size)
            .setSingleChoiceItems(labels, layoutPrefs.getGreetingSize()) { dialog, which ->
                dialog.dismiss()
                layoutPrefs.setGreetingSize(which)
                lastGreeting = null
                applyGreeting()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showGreetingTextDialog() {
        val density = resources.displayMetrics.density
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 2
            maxLines = 5
            filters = arrayOf<InputFilter>(InputFilter.LengthFilter(LayoutPrefs.MAX_GREETING_LENGTH))
            hint = getString(R.string.greeting_text_hint)
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(context, R.color.text_tertiary))
            setText(layoutPrefs.getGreeting())
            setSelection(text.length)
        }
        val container = FrameLayout(this).apply {
            val side = (24 * density).toInt()
            setPadding(side, (8 * density).toInt(), side, 0)
            addView(
                input,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        val dialog = AlertDialog.Builder(this, R.style.Theme_TVLauncher_AboutDialog)
            .setTitle(R.string.customize_greeting_text)
            .setView(container)
            .setPositiveButton(R.string.done) { _, _ ->
                layoutPrefs.setGreeting(input.text.toString())
                lastGreeting = null
                applyGreeting()
            }
            .setNeutralButton(R.string.greeting_text_reset) { _, _ ->
                layoutPrefs.setGreeting("")
                lastGreeting = null
                applyGreeting()
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
    }

    // ---- Tabs ----

    private fun buildTabs() {
        tabContainer.removeAllViews()
        tabViews.clear()
        addTab(TAB_HOME, getString(R.string.tab_home))
        layoutPrefs.getCategories().forEach { addTab(it.id, tabLabel(it)) }
        // Categories are created from "Personalizar" or from the category picker when adding an
        // app, so there is no "+ Categoria" tab anymore.
        addTab(TAB_ALL, getString(R.string.tab_all_apps))
        updateTabStyles()
    }

    /** Name shown on a category tab; protected categories get a lock. */
    private fun tabLabel(category: Category): String = category.name

    private fun addTab(key: String, label: String) {
        val density = resources.displayMetrics.density
        val tab = TextView(this).apply {
            text = label
            textSize = 14f
            maxLines = 1
            isFocusable = true
            isClickable = true
            background = ContextCompat.getDrawable(context, R.drawable.tab_background)
            val horizontal = (16 * density).toInt()
            val vertical = (8 * density).toInt()
            setPadding(horizontal, vertical, horizontal, vertical)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = (6 * density).toInt() }
        }
        tab.setOnClickListener { onTabClicked(key) }
        tab.setOnLongClickListener {
            // Android detects its standard long press first; require the extra 200 ms so the
            // adjustment only opens after at least 700 ms with the button still held.
            mainHandler.postDelayed({
                if (tab.isPressed) openTabAdjustments(key)
            }, HOME_LONG_PRESS_MS - 500L)
            true
        }
        // Like Google TV: moving the focus over a tab already shows its apps.
        tab.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && key != currentTab) {
                selectTab(key, requestFocus = false)
            }
        }
        tabViews[key] = tab
        tabContainer.addView(tab)
    }

    private fun openTabAdjustments(key: String) {
        if (key == TAB_ALL) {
            showIconSizeDialog(TAB_ALL, getString(R.string.tab_all_apps))
            return
        }
        val category = layoutPrefs.getCategories().firstOrNull { it.id == key }
        if (category != null) {
            showCategoryMenu(category)
        } else {
            showIconSizeDialog(key, tabDisplayName(key))
        }
    }

    private fun updateTabStyles() {
        val dark = ContextCompat.getColor(this, R.color.bg_main)
        val light = ContextCompat.getColor(this, R.color.text_primary)
        val accent = Palette.accent(layoutPrefs.getPalette())
        tabViews.forEach { (key, view) ->
            val selected = key == currentTab
            view.isSelected = selected
            view.setTextColor(if (selected) dark else light)
            // Tints the white/translucent tab background with the palette colour.
            view.backgroundTintList = ColorStateList.valueOf(accent)
        }
    }

    private fun onTabClicked(key: String) {
        when {
            key == TAB_NEW -> promptNewCategory()
            key != currentTab -> selectTab(key, requestFocus = true)
            else -> {
                val category = layoutPrefs.getCategories().firstOrNull { it.id == key }
                if (category != null) {
                    showCategoryMenu(category)
                } else {
                    focusContent()
                }
            }
        }
    }

    private fun focusContent() {
        val list = when (currentTab) {
            TAB_ALL -> allAppsList
            else -> appSlots
        }
        list.getChildAt(0)?.requestFocus()
    }

    private fun selectTab(key: String, requestFocus: Boolean) {
        currentTab = key
        if (key != TAB_ALL) previousContentTab = key
        layoutPrefs.setLastTab(key)
        updateTabStyles()
        tabViews[key]?.let { tab ->
            tabScroll.post { tabScroll.smoothScrollTo((tab.left - tabScroll.width / 3).coerceAtLeast(0), 0) }
        }
        refreshTopApps()
        applyGridForTab()
        appSlots.visibility = View.GONE
        allAppsList.visibility = View.GONE
        emptyHint.visibility = View.GONE
        when (key) {
            TAB_ALL -> {
                allAppsList.visibility = View.VISIBLE
                refreshAllApps(requestFocus)
            }
            else -> {
                appSlots.visibility = View.VISIBLE
                submitSlots(null, requestFocus)
            }
        }
    }

    /**
     * Applies the icon size saved for the current tab (each category can have its own) and the
     * global number of rows. Everything follows the same calculation, so 4K screens get
     * proportionally bigger tiles instead of tiny ones.
     */
    private fun applyGridForTab() {
        if (!::appSlotAdapter.isInitialized) return
        val columns = layoutPrefs.getColumnsFor(currentTab)
        val rows = layoutPrefs.getRows()
        if (gridLayoutManager.spanCount != columns) gridLayoutManager.spanCount = columns
        val sizing = buildSizing(columns, rows)
        if (appSlotAdapter.sizing != sizing) appSlotAdapter.sizing = sizing
        appSlots.setPadding(
            resources.getDimensionPixelSize(R.dimen.grid_padding),
            resources.getDimensionPixelSize(R.dimen.grid_padding),
            resources.getDimensionPixelSize(R.dimen.grid_padding),
            resources.getDimensionPixelSize(R.dimen.grid_padding)
        )
    }

    private fun refreshAllAppsGrid() {
        val columns = allAppsColumns()
        (allAppsList.layoutManager as? GridLayoutManager)?.spanCount = columns
        allAppsAdapter.notifyDataSetChanged()
    }

    // ---- Home grid ----

    private fun loadHomeInfos() {
        val infos = appManager.getSelectedAppInfos()
        infoById = infos.associateBy { AppIdentifier.encode(it) }
        layoutPrefs.pruneAssignments(infoById.keys)
    }

    private fun refreshContent(focusKey: String?, requestFocus: Boolean) {
        when (currentTab) {
            TAB_ALL -> refreshAllApps(requestFocus = false)
            else -> submitSlots(focusKey, requestFocus)
        }
    }

    /** Shows the apps of the current tab followed by the "+" tile. */
    private fun submitSlots(focusKey: String?, requestFocus: Boolean) {
        emptyHint.visibility = View.GONE
        val categories = layoutPrefs.getCategories()
        val order = appManager.getSelectedApps().filter { infoById.containsKey(it) }
        val grouped = HomeLayout.group(order, categories, layoutPrefs.getAssignments())
        val ids = if (currentTab == TAB_HOME) {
            grouped.uncategorized
        } else {
            grouped.byCategory[currentTab].orEmpty()
        }

        val slots = mutableListOf<AppSlotAdapter.AppSlot>()
        ids.forEach { id ->
            if (!HiddenApps.isHidden(this, id)) {
                infoById[id]?.let { slots.add(AppSlotAdapter.AppSlot(it, false)) }
            }
        }
        slots.add(AppSlotAdapter.AppSlot(null, true))

        appSlotAdapter.submitList(slots) {
            if (requestFocus) restoreFocus(focusKey)
        }
    }

    private fun restoreFocus(key: String?) {
        val list = appSlotAdapter.currentList
        if (list.isEmpty()) return
        var target = if (key != null) list.indexOfFirst { it.key == key } else -1
        if (target < 0) target = 0

        // Make sure the tile is on screen, then give it focus once the grid has been laid out.
        appSlots.layoutManager?.scrollToPosition(target)
        appSlots.doOnLayout {
            val holder = appSlots.findViewHolderForAdapterPosition(target)
            (holder?.itemView ?: appSlots.getChildAt(0))?.requestFocus()
        }
    }

    private fun openAllAppsToAdd() {
        pendingAddCategoryId = layoutPrefs.getCategories().firstOrNull { it.id == currentTab }?.id
        selectTab(TAB_ALL, requestFocus = true)
    }

    private fun showAppContextMenu(position: Int) {
        val slot = appSlotAdapter.currentList.getOrNull(position) ?: return
        val app = slot.appInfo ?: return
        val appId = slot.key

        val options = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        options.add(getString(R.string.app_menu_open))
        actions.add { launchApp(app) }

        options.add(getString(R.string.app_menu_reorder))
        actions.add { startReorder(appId) }

        options.add(getString(R.string.app_menu_move_to_category))
        actions.add { showMoveToCategoryDialog(appId) }

        if (!app.isShortcut) {
            options.add(getString(R.string.app_menu_info))
            actions.add { openAppDetails(app.packageName) }
            options.add(getString(R.string.app_menu_permissions))
            actions.add { openAppPermissions(app.packageName) }
        }

        options.add(getString(R.string.app_menu_hide))
        actions.add { hideApp(appId, app.getDisplayName()) }

        options.add(getString(R.string.remove))
        actions.add { confirmRemoveApp(app, position) }

        AlertDialog.Builder(this, R.style.Theme_TVLauncher_AboutDialog)
            .setTitle(app.getDisplayName())
            .setItems(options.toTypedArray()) { _, which ->
                actions.getOrNull(which)?.invoke()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun launchApp(app: AppInfo) {
        UsageTracker.record(this, AppIdentifier.encode(app))
        if (app.isShortcut) {
            ShortcutHelper.launchShortcut(this, app.packageName, app.shortcutId!!)
        } else {
            ShortcutHelper.launchApp(this, app.packageName)
        }
    }

    // ---- Reorder mode: move an app with the remote's arrow keys ----

    private fun startReorder(appId: String) {
        reorderingId = appId
        lastFocusedKey = appId
        appSlotAdapter.movingKey = appId
        restoreFocus(appId)
        Toast.makeText(this, R.string.reorder_hint, Toast.LENGTH_LONG).show()
    }

    private fun stopReorder() {
        if (reorderingId == null) return
        reorderingId = null
        appSlotAdapter.movingKey = null
    }

    /** Moves the app being reordered one tile (or one row when [vertical]) within its group. */
    private fun moveReorderingApp(direction: Int, vertical: Boolean) {
        val appId = reorderingId ?: return
        val order = appManager.getSelectedApps()
        val grouped = HomeLayout.group(
            order,
            layoutPrefs.getCategories(),
            layoutPrefs.getAssignments()
        )
        val group = grouped.groupOf(appId)
        val offset = if (vertical) {
            HomeLayout.rowOffset(group.indexOf(appId), group.size, gridLayoutManager.spanCount, direction)
        } else {
            direction
        }
        val newOrder = HomeLayout.moveInGroup(order, group, appId, offset) ?: return
        appManager.saveSelectedApps(newOrder)
        lastFocusedKey = appId
        submitSlots(appId, requestFocus = true)
    }

    // ---- Categories ----

    /** Moves an app (already on the home screen) to another category from the home grid. */
    private fun assignToCategory(appId: String, categoryId: String?) {
        layoutPrefs.assign(appId, categoryId)
        val order = appManager.getSelectedApps()
        val grouped = HomeLayout.group(
            order,
            layoutPrefs.getCategories(),
            layoutPrefs.getAssignments()
        )
        // Show the app at the end of its new group.
        val newOrder = HomeLayout.moveToGroupEnd(order, appId, grouped.groupOf(appId))
        appManager.saveSelectedApps(newOrder)
        lastFocusedKey = null
        submitSlots(null, requestFocus = true)
    }

    private fun showMoveToCategoryDialog(appId: String) {
        val categories = layoutPrefs.getCategories()
        val currentId = layoutPrefs.getAssignments()[appId]

        val labels = mutableListOf(getString(R.string.category_none))
        categories.forEach { labels.add(it.name) }
        labels.add(getString(R.string.category_new_ellipsis))

        val currentIndex = categories.indexOfFirst { it.id == currentId }
        val checked = if (currentIndex < 0) 0 else currentIndex + 1

        AlertDialog.Builder(this, R.style.Theme_TVLauncher_AboutDialog)
            .setTitle(R.string.app_menu_move_to_category)
            .setSingleChoiceItems(labels.toTypedArray(), checked) { dialog, which ->
                dialog.dismiss()
                when {
                    which == 0 -> assignToCategory(appId, null)
                    which <= categories.size -> assignToCategory(appId, categories[which - 1].id)
                    else -> promptNewCategory { category -> assignToCategory(appId, category.id) }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * Asks for a name and creates a category. With [onCreated] the caller decides what happens
     * next; without it the new category's tab is opened.
     */
    private fun promptNewCategory(onCreated: ((Category) -> Unit)? = null) {
        promptCategoryName(R.string.customize_new_category, "") { name ->
            val category = layoutPrefs.addCategory(name)
            if (category == null) {
                Toast.makeText(this, R.string.category_name_empty, Toast.LENGTH_SHORT).show()
            } else {
                buildTabs()
                if (onCreated != null) {
                    onCreated(category)
                } else {
                    selectTab(category.id, requestFocus = false)
                    tabViews[category.id]?.requestFocus()
                }
            }
        }
    }

    private fun promptCategoryName(titleRes: Int, initial: String, onName: (String) -> Unit) {
        val density = resources.displayMetrics.density
        val input = EditText(this).apply {
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            filters = arrayOf<InputFilter>(InputFilter.LengthFilter(HomeLayout.MAX_CATEGORY_NAME))
            hint = getString(R.string.category_name_hint)
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(context, R.color.text_tertiary))
            setText(initial)
            setSelection(text.length)
        }
        val container = FrameLayout(this).apply {
            val side = (24 * density).toInt()
            setPadding(side, (8 * density).toInt(), side, 0)
            addView(
                input,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        val dialog = AlertDialog.Builder(this, R.style.Theme_TVLauncher_AboutDialog)
            .setTitle(titleRes)
            .setView(container)
            .setPositiveButton(R.string.done) { _, _ -> onName(input.text.toString()) }
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
    }


    /** Menu shown when the selected category tab is pressed. */
    private fun showCategoryMenu(category: Category) {
        val categories = layoutPrefs.getCategories()
        val index = categories.indexOfFirst { it.id == category.id }

        val options = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        options.add(getString(R.string.category_icon_size))
        actions.add { showIconSizeDialog(category.id, category.name) }
        options.add(getString(R.string.category_rename))
        actions.add {
            promptCategoryName(R.string.category_rename, category.name) { name ->
                if (layoutPrefs.renameCategory(category.id, name)) {
                    reopenTab(category.id)
                } else {
                    Toast.makeText(this, R.string.category_name_empty, Toast.LENGTH_SHORT).show()
                }
            }
        }
        if (index > 0) {
            options.add(getString(R.string.category_move_up))
            actions.add { moveCategory(category.id, -1) }
        }
        if (index >= 0 && index < categories.size - 1) {
            options.add(getString(R.string.category_move_down))
            actions.add { moveCategory(category.id, 1) }
        }
        options.add(getString(R.string.category_delete))
        actions.add { confirmDeleteCategory(category) }

        AlertDialog.Builder(this, R.style.Theme_TVLauncher_AboutDialog)
            .setTitle(category.name)
            .setItems(options.toTypedArray()) { _, which ->
                actions.getOrNull(which)?.invoke()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Rebuilds the tab bar and puts the focus back on [key]. */
    private fun reopenTab(key: String) {
        buildTabs()
        selectTab(key, requestFocus = false)
        tabViews[key]?.requestFocus()
    }

    private fun moveCategory(categoryId: String, offset: Int) {
        if (layoutPrefs.moveCategory(categoryId, offset)) {
            reopenTab(categoryId)
        }
    }

    private fun confirmDeleteCategory(category: Category) {
        AlertDialog.Builder(this, R.style.Theme_TVLauncher_AboutDialog)
            .setTitle(R.string.category_delete_title)
            .setMessage(getString(R.string.category_delete_message, category.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                layoutPrefs.deleteCategory(category.id)
                reopenTab(TAB_HOME)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---- All apps tab ----

    private fun refreshAllApps(requestFocus: Boolean) {
        appManager.loadInstalledAppsAsync(false) { apps ->
            if (isFinishing || isDestroyed) return@loadInstalledAppsAsync
            val added = appManager.getSelectedApps().toSet()
            val notAdded = getString(R.string.all_apps_not_added)

            val rows = apps
                .filter { app ->
                    val id = AppIdentifier.encode(app)
                    // All Apps is the inbox for new/unassigned apps. Categorized apps live only
                    // in their category and return here automatically when that shortcut is removed.
                    id !in added && !HiddenApps.isHidden(this, id)
                }
                .sortedBy { it.getDisplayName().lowercase() }
                .map { app ->
                    val id = AppIdentifier.encode(app)
                    AllAppsAdapter.Row(app, id, false, notAdded)
                }

            allAppsAdapter.submitList(rows) {
                if (requestFocus && currentTab == TAB_ALL) {
                    allAppsList.post { allAppsList.getChildAt(0)?.requestFocus() }
                }
            }
        }
    }

    /** OK on a row: apps not yet added ask for a category, apps already added show actions. */
    private fun onAllAppsRowClick(row: AllAppsAdapter.Row) {
        if (row.added) {
            showAllAppActions(row)
        } else {
            chooseCategoryFor(row)
        }
    }

    private fun showAllAppActions(row: AllAppsAdapter.Row) {
        val options = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        if (row.added) {
            options.add(getString(R.string.all_apps_move_to))
            actions.add { chooseCategoryFor(row) }
            options.add(getString(R.string.all_apps_remove_from_home))
            actions.add { removeFromHome(row) }
        } else {
            options.add(getString(R.string.all_apps_add_to))
            actions.add { chooseCategoryFor(row) }
        }
        options.add(getString(R.string.app_menu_open))
        actions.add { launchApp(row.app) }
        options.add(getString(R.string.all_apps_uninstall))
        actions.add { uninstallApp(row.app) }
        options.add(getString(R.string.app_menu_info))
        actions.add { openAppDetails(row.app.packageName) }
        options.add(getString(R.string.app_menu_permissions))
        actions.add { openAppPermissions(row.app.packageName) }
        options.add(getString(R.string.app_menu_hide))
        actions.add { hideApp(row.id, row.app.getDisplayName()) }

        AlertDialog.Builder(this, R.style.Theme_TVLauncher_AboutDialog)
            .setTitle(row.app.getDisplayName())
            .setItems(options.toTypedArray()) { _, which ->
                actions.getOrNull(which)?.invoke()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun hideApp(appId: String, label: String) {
        HiddenApps.setHidden(this, appId, true)
        CrashLog.log(this, "App ocultado: $label")
        Toast.makeText(this, getString(R.string.app_hidden_toast, label), Toast.LENGTH_SHORT).show()
        refreshTopApps()
        refreshContent(lastFocusedKey, requestFocus = false)
    }

    /** Advanced settings: restore apps hidden by the long-press menu. */
    private fun showHiddenAppsDialog() {
        val hidden = HiddenApps.ids(this)
        val apps = appManager.getInstalledApps()
            .filter { AppIdentifier.encode(it) in hidden }
            .sortedBy { it.getDisplayName().lowercase() }
        if (apps.isEmpty()) {
            Toast.makeText(this, R.string.hidden_apps_empty, Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this, R.style.Theme_TVLauncher_AboutDialog)
            .setTitle(R.string.hidden_apps_title)
            .setItems(apps.map { it.getDisplayName() }.toTypedArray()) { _, which ->
                val app = apps[which]
                HiddenApps.setHidden(this, AppIdentifier.encode(app), false)
                Toast.makeText(
                    this,
                    getString(R.string.hidden_app_restored, app.getDisplayName()),
                    Toast.LENGTH_SHORT
                ).show()
                refreshContent(lastFocusedKey, requestFocus = false)
            }
            .setNeutralButton(R.string.hidden_apps_restore_all) { _, _ ->
                HiddenApps.clear(this)
                refreshContent(lastFocusedKey, requestFocus = false)
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    /** Lets the user pick the category an app goes to. */
    private fun chooseCategoryFor(row: AllAppsAdapter.Row) {
        val categories = layoutPrefs.getCategories()
        val labels = mutableListOf(getString(R.string.category_none))
        categories.forEach { labels.add(it.name) }
        labels.add(getString(R.string.category_new_ellipsis))

        val currentId = if (row.added) {
            layoutPrefs.getAssignments()[row.id]
        } else {
            pendingAddCategoryId
        }
        val currentIndex = categories.indexOfFirst { it.id == currentId }
        val checked = if (currentIndex < 0) 0 else currentIndex + 1

        AlertDialog.Builder(this, R.style.Theme_TVLauncher_AboutDialog)
            .setTitle(R.string.all_apps_choose_category)
            .setSingleChoiceItems(labels.toTypedArray(), checked) { dialog, which ->
                dialog.dismiss()
                when {
                    which == 0 -> addAppToHome(row, null)
                    which <= categories.size -> addAppToHome(row, categories[which - 1])
                    else -> promptNewCategory { category -> addAppToHome(row, category) }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Adds the app to the home screen (or moves it) in [category], or in Início when null. */
    private fun addAppToHome(row: AllAppsAdapter.Row, category: Category?) {
        val order = appManager.getSelectedApps()
        val withApp = if (row.id in order) order else order + row.id
        layoutPrefs.assign(row.id, category?.id)
        appManager.saveSelectedApps(withApp)

        val grouped = HomeLayout.group(
            withApp,
            layoutPrefs.getCategories(),
            layoutPrefs.getAssignments()
        )
        val newOrder = HomeLayout.moveToGroupEnd(withApp, row.id, grouped.groupOf(row.id))
        appManager.saveSelectedApps(newOrder)

        loadHomeInfos()
        pendingAddCategoryId = category?.id
        Toast.makeText(
            this,
            getString(
                R.string.all_apps_added_toast,
                row.app.getDisplayName(),
                category?.name ?: getString(R.string.tab_home)
            ),
            Toast.LENGTH_SHORT
        ).show()
        refreshAllApps(requestFocus = false)
    }

    private fun removeFromHome(row: AllAppsAdapter.Row) {
        appManager.removeSelectedApp(row.id)
        appManager.invalidateSelectionCache()
        layoutPrefs.assign(row.id, null)
        loadHomeInfos()
        Toast.makeText(
            this,
            getString(R.string.all_apps_removed_toast, row.app.getDisplayName()),
            Toast.LENGTH_SHORT
        ).show()
        refreshAllApps(requestFocus = false)
    }

    private fun uninstallApp(app: AppInfo) {
        try {
            val info = packageManager.getApplicationInfo(app.packageName, 0)
            val isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
            if (isSystem) {
                Toast.makeText(this, R.string.all_apps_uninstall_blocked, Toast.LENGTH_LONG).show()
                return
            }
            TvHomeOverrideService.pauseOverrideFor(20000)
            startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.packageName}")))
        } catch (_: Exception) {
            Toast.makeText(this, app.packageName, Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmRemoveApp(app: AppInfo, position: Int) {
        AlertDialog.Builder(this, R.style.Theme_TVLauncher_AboutDialog)
            .setTitle(R.string.remove_app_title)
            .setMessage(getString(R.string.remove_app_message, app.getDisplayName()))
            .setPositiveButton(R.string.remove) { _, _ ->
                val appId = AppIdentifier.encode(app)
                // Focus the tile before the removed one afterwards.
                lastFocusedKey = appSlotAdapter.currentList.getOrNull(position - 1)?.key
                appManager.removeSelectedApp(appId)
                appManager.invalidateSelectionCache()
                layoutPrefs.assign(appId, null)
                LauncherBroadcast.refreshHome(this)
                loadHomeInfos()
                submitSlots(lastFocusedKey, requestFocus = true)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---- PIN for categories ----

    /** Asks for the PIN and calls [onOk] when it is right. */
    private fun askPin(titleRes: Int, onOk: () -> Unit) {
        val lockedMs = pinPrefs.lockoutRemainingMs()
        if (lockedMs > 0L) {
            Toast.makeText(this, lockoutMessage(lockedMs), Toast.LENGTH_LONG).show()
            return
        }
        val input = pinInput()
        val dialog = AlertDialog.Builder(this, R.style.Theme_TVLauncher_AboutDialog)
            .setTitle(titleRes)
            .setView(input)
            .setPositiveButton(R.string.ok) { _, _ ->
                if (pinPrefs.verify(input.text.toString())) {
                    onOk()
                } else {
                    val wait = pinPrefs.lockoutRemainingMs()
                    val message = if (wait > 0L) lockoutMessage(wait) else getString(R.string.pin_wrong)
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
        protectPinWindow(dialog)
        dialog.show()
    }

    /** Seconds until the next PIN attempt, in a friendly message. */
    private fun lockoutMessage(remainingMs: Long): String {
        val seconds = ((remainingMs + 999L) / 1000L).coerceAtLeast(1L)
        return getString(R.string.pin_locked, seconds)
    }

    /**
     * Blocks screenshots and screen recordings while the PIN is typed, so neither another app
     * nor an ADB screen capture can read it.
     */
    private fun protectPinWindow(dialog: android.app.Dialog) {
        dialog.window?.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
    }

    private fun pinInput(): EditText {
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        input.filters = arrayOf(InputFilter.LengthFilter(8))
        input.setHint(R.string.pin_hint)
        return input
    }

    /** Protects [category]; creates the PIN first when none exists yet. */
    private fun lockCategory(category: Category) {
        val finish = {
            pinPrefs.setLocked(category.id, true)
            unlockedTabs.remove(category.id)
            Toast.makeText(this, R.string.category_locked_toast, Toast.LENGTH_SHORT).show()
            reopenTab(category.id)
        }
        if (pinPrefs.hasPin()) {
            finish()
        } else {
            createPin { finish() }
        }
    }

    /** Asks for a new PIN twice and saves it. */
    private fun createPin(onDone: () -> Unit) {
        val first = pinInput()
        val firstDialog = AlertDialog.Builder(this, R.style.Theme_TVLauncher_AboutDialog)
            .setTitle(R.string.pin_create_title)
            .setView(first)
            .setPositiveButton(R.string.ok) { _, _ ->
                val pin = first.text.toString()
                if (!PinPrefs.isValidPin(pin)) {
                    Toast.makeText(this, R.string.pin_invalid, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val second = pinInput()
                val secondDialog = AlertDialog.Builder(
                    this,
                    R.style.Theme_TVLauncher_AboutDialog
                )
                    .setTitle(R.string.pin_confirm_title)
                    .setView(second)
                    .setPositiveButton(R.string.ok) { _, _ ->
                        if (second.text.toString() == pin) {
                            pinPrefs.setPin(pin)
                            onDone()
                        } else {
                            Toast.makeText(this, R.string.pin_mismatch, Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .create()
                protectPinWindow(secondDialog)
                secondDialog.show()
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
        protectPinWindow(firstDialog)
        firstDialog.show()
    }

    private fun changePin() {
        askPin(R.string.pin_enter_title) {
            createPin {
                Toast.makeText(this, R.string.pin_changed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ---- Backup and memory ----

    private fun startExport() {
        try {
            exportPicker.launch("launcher_backup.json")
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.backup_no_picker, Toast.LENGTH_LONG).show()
        }
    }

    private fun startImport() {
        try {
            importPicker.launch(arrayOf("application/json", "*/*"))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.backup_no_picker, Toast.LENGTH_LONG).show()
        }
    }

    private fun writeBackup(uri: Uri) {
        val ok = try {
            contentResolver.openOutputStream(uri)?.use { out ->
                out.write(SettingsBackup.export(this).toByteArray(Charsets.UTF_8))
            } != null
        } catch (e: Exception) {
            false
        }
        Toast.makeText(
            this,
            if (ok) R.string.backup_exported else R.string.backup_write_failed,
            Toast.LENGTH_LONG
        ).show()
    }

    private fun readBackup(uri: Uri) {
        val ok = try {
            val text = contentResolver.openInputStream(uri)?.use { input ->
                input.readBytes().toString(Charsets.UTF_8)
            }
            text != null && SettingsBackup.import(this, text)
        } catch (e: Exception) {
            false
        }
        if (ok) {
            // The backup never carries the PIN itself; drop locks that would otherwise ask for
            // a PIN that does not exist on this TV.
            pinPrefs.pruneOrphanLocks()
            Toast.makeText(this, R.string.backup_imported, Toast.LENGTH_LONG).show()
            recreate()
        } else {
            Toast.makeText(this, R.string.backup_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun freeMemory() {
        val before = RamOptimizationHelper.availableMemoryMb(this)
        RamOptimizationHelper.freeMemoryNow(this)
        mainHandler.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            val after = RamOptimizationHelper.availableMemoryMb(this)
            Toast.makeText(
                this,
                getString(R.string.free_memory_done, before, after),
                Toast.LENGTH_LONG
            ).show()
        }, 1500)
    }

    private fun stateLabel(res: Int, on: Boolean): String {
        return getString(res) + ": " + getString(if (on) R.string.state_on else R.string.state_off)
    }

    // ---- Settings of the launcher (icon size, greeting, wallpaper, accessibility...) ----

    private fun showCustomizeDialog() {
        val options = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        options.add(getString(R.string.customize_icon_size_tab, tabDisplayName(currentTab)))
        actions.add { showIconSizeDialog() }
        options.add(getString(R.string.customize_grid_arrangement))
        actions.add { showGridDialog() }
        options.add(getString(R.string.customize_icon_shape))
        actions.add { showIconShapeDialog() }
        options.add(getString(R.string.customize_greeting_size))
        actions.add { showGreetingSizeDialog() }
        options.add(getString(R.string.customize_palette))
        actions.add { showPaletteDialog() }
        options.add(stateLabel(R.string.customize_aurora, layoutPrefs.isAuroraEnabled()))
        actions.add {
            layoutPrefs.setAuroraEnabled(!layoutPrefs.isAuroraEnabled())
            applyPalette()
        }
        options.add(stateLabel(R.string.customize_auto_messages, layoutPrefs.isAutoMessagesEnabled()))
        actions.add {
            layoutPrefs.setAutoMessagesEnabled(!layoutPrefs.isAutoMessagesEnabled())
            lastGreeting = null
            applyGreeting()
        }
        options.add(getString(R.string.customize_message_interval))
        actions.add { showMessageIntervalDialog() }
        options.add(getString(R.string.customize_message_kinds))
        actions.add { showMessageKindsDialog() }
        options.add(getString(R.string.customize_message_target))
        actions.add { showMessageTargetDialog() }
        options.add(stateLabel(R.string.customize_weather, layoutPrefs.isWeatherEnabled()))
        actions.add {
            layoutPrefs.setWeatherEnabled(!layoutPrefs.isWeatherEnabled())
            refreshWeather(force = false)
        }
        options.add(stateLabel(R.string.customize_most_used, layoutPrefs.isMostUsedEnabled()))
        actions.add {
            layoutPrefs.setMostUsedEnabled(!layoutPrefs.isMostUsedEnabled())
            refreshTopApps()
            applyGridForTab()
        }
        options.add(getString(R.string.customize_wallpaper))
        actions.add { showWallpaperDialog() }
        options.add(getString(R.string.customize_sort_az))
        actions.add { sortAppsAlphabetically() }
        // Accessibility, backups, memory, logs and cache all live in the advanced settings now.
        options.add(getString(R.string.advanced_settings))

        val dialog = AlertDialog.Builder(this, R.style.Theme_TVLauncher_AboutDialog)
            .setTitle(R.string.customize_title)
            .setItems(options.toTypedArray()) { _, which ->
                if (which == options.size - 1) {
                    showAdvancedSettingsDialog()
                } else {
                    actions.getOrNull(which)?.invoke()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
        styleDialog(dialog, widthFraction = 0.46f, maxWidthDp = 620, heightFraction = 0.82f)
        dialog.show()
    }

    private fun tabDisplayName(key: String): String = when (key) {
        TAB_HOME -> getString(R.string.tab_home)
        TAB_ALL -> getString(R.string.tab_all_apps)
        else -> layoutPrefs.getCategories().firstOrNull { it.id == key }?.name
            ?: getString(R.string.tab_home)
    }

    /**
     * Icon size of one tab, chosen with a bar instead of a long list of words: dragging to the
     * right makes the icons bigger (fewer per row).
     */
    private fun showIconSizeDialog(
        tabKey: String = currentTab,
        tabName: String = tabDisplayName(currentTab)
    ) {
        val isAllApps = tabKey == TAB_ALL
        val minColumns = if (isAllApps) ALL_APPS_MIN_COLUMNS else LayoutPrefs.MIN_COLUMNS
        val maxColumns = if (isAllApps) ALL_APPS_MAX_COLUMNS else LayoutPrefs.MAX_COLUMNS
        val current = if (isAllApps) allAppsColumns() else layoutPrefs.getColumnsFor(tabKey)
        val density = resources.displayMetrics.density

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (22 * density).toInt(),
                (18 * density).toInt(),
                (22 * density).toInt(),
                (6 * density).toInt()
            )
        }
        val valueText = TextView(this).apply {
            textSize = 15f
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
        }
        val hint = TextView(this).apply {
            text = getString(R.string.icon_size_bar_hint)
            textSize = 11f
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            setPadding(0, (8 * density).toInt(), 0, 0)
        }
        // progress 0 = the most columns (smallest icons); the right side = biggest icons.
        val seek = SeekBar(this).apply {
            this.max = maxColumns - minColumns
            progress = (maxColumns - current).coerceIn(0, this.max)
        }
        fun columns(): Int = maxColumns - seek.progress
        fun updateLabel() {
            valueText.text = getString(R.string.icon_size_bar_value, columns())
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) =
                updateLabel()

            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })
        updateLabel()
        container.addView(valueText)
        container.addView(seek)
        container.addView(hint)

        val dialog = AlertDialog.Builder(this, R.style.Theme_TVLauncher_AboutDialog)
            .setTitle(getString(R.string.icon_size_title_for, tabName))
            .setView(container)
            .setPositiveButton(R.string.apply) { _, _ ->
                applyIconColumnsForTab(tabKey, columns())
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
        styleDialog(dialog, widthFraction = 0.44f, maxWidthDp = 620)
        dialog.show()
    }

    /** Lines x columns of the whole grid, from 1x2 up to 15x15. */
    private fun showGridDialog() {
        val density = resources.displayMetrics.density
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (22 * density).toInt(),
                (16 * density).toInt(),
                (22 * density).toInt(),
                (6 * density).toInt()
            )
        }
        val columnsText = TextView(this).apply {
            textSize = 15f
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
        }
        val columnsBar = SeekBar(this).apply {
            max = LayoutPrefs.MAX_COLUMNS - LayoutPrefs.MIN_COLUMNS
            progress = (layoutPrefs.getColumns() - LayoutPrefs.MIN_COLUMNS).coerceIn(0, max)
        }
        val rowsText = TextView(this).apply {
            textSize = 15f
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setPadding(0, (14 * density).toInt(), 0, 0)
        }
        val rowsBar = SeekBar(this).apply {
            max = LayoutPrefs.MAX_ROWS - LayoutPrefs.MIN_ROWS
            progress = (layoutPrefs.getRows() - LayoutPrefs.MIN_ROWS).coerceIn(0, max)
        }
        fun columns(): Int = LayoutPrefs.MIN_COLUMNS + columnsBar.progress
        fun rows(): Int = LayoutPrefs.MIN_ROWS + rowsBar.progress
        fun updateLabels() {
            columnsText.text = getString(R.string.grid_columns_value, columns())
            rowsText.text = getString(R.string.grid_rows_value, rows())
        }
        columnsBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) =
                updateLabels()

            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })
        rowsBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) =
                updateLabels()

            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })
        updateLabels()
        val hint = TextView(this).apply {
            text = getString(R.string.grid_arrangement_hint)
            textSize = 11f
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            setPadding(0, (10 * density).toInt(), 0, 0)
        }
        container.addView(columnsText)
        container.addView(columnsBar)
        container.addView(rowsText)
        container.addView(rowsBar)
        container.addView(hint)

        val dialog = AlertDialog.Builder(this, R.style.Theme_TVLauncher_AboutDialog)
            .setTitle(R.string.customize_grid_arrangement)
            .setView(container)
            .setPositiveButton(R.string.apply) { _, _ ->
                layoutPrefs.setColumns(columns())
                layoutPrefs.setRows(rows())
                CrashLog.log(this, "Arranjo da grade: ${columns()} colunas x ${rows()} linhas")
                // The arrangement is global: every tab starts using the same grid again.
                layoutPrefs.clearColumnsByTab()
                applyGridForTab()
                refreshAllAppsGrid()
                refreshContent(lastFocusedKey, requestFocus = false)
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
        styleDialog(dialog, widthFraction = 0.44f, maxWidthDp = 620)
        dialog.show()
    }

    /** Changes the icon size of [tabKey] only; applies immediately and keeps the focus. */
    private fun applyIconColumnsForTab(tabKey: String, columns: Int) {
        layoutPrefs.setColumnsFor(tabKey, columns)
        CrashLog.log(this, "Ícones de ${tabDisplayName(tabKey)}: $columns por linha")
        if (tabKey == TAB_ALL) {
            refreshAllAppsGrid()
            return
        }
        // Icons are rendered at a fixed pixel size, so drop the old ones and redraw.
        appSlotAdapter.currentList.forEach { it.appInfo?.clearIcon() }
        appManager.invalidateAppListCache()
        if (currentTab == tabKey) {
            applyGridForTab()
            refreshContent(lastFocusedKey, requestFocus = false)
            restoreFocus(lastFocusedKey)
        }
    }

    private fun showIconShapeDialog() {
        val labels = IconShapes.ALL.map { it.name }.toTypedArray()
        AlertDialog.Builder(this, R.style.Theme_TVLauncher_AboutDialog)
            .setTitle(R.string.customize_icon_shape)
            .setSingleChoiceItems(labels, layoutPrefs.getIconShape()) { dialog, which ->
                dialog.dismiss()
                layoutPrefs.setIconShape(which)
                applyIconLook()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showPaletteDialog() {
        val labels = Palette.ALL.map { it.name }.toTypedArray()
        AlertDialog.Builder(this, R.style.Theme_TVLauncher_AboutDialog)
            .setTitle(R.string.customize_palette)
            .setSingleChoiceItems(labels, layoutPrefs.getPalette()) { dialog, which ->
                dialog.dismiss()
                layoutPrefs.setPalette(which)
                applyPalette()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showMessageIntervalDialog() {
        val minutes = longArrayOf(1, 5, 10, 30, 60, 360, 720, 1440)
        val labels = minutes.map { value ->
            if (value < 60) {
                getString(R.string.message_interval_minutes, value)
            } else {
                getString(R.string.message_interval_hours, value / 60)
            }
        }.toTypedArray()
        val currentMs = layoutPrefs.getMessageIntervalMs()
        val current = minutes.indexOfFirst { it * 60_000L == currentMs }
            .let { if (it < 0) 2 else it }
        AlertDialog.Builder(this, R.style.Theme_TVLauncher_AboutDialog)
            .setTitle(R.string.customize_message_interval)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                dialog.dismiss()
                layoutPrefs.setMessageIntervalMs(minutes[which] * 60_000L)
                lastGreeting = null
                applyGreeting()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showMessageKindsDialog() {
        val kinds = RandomMessages.Kind.values()
        val labels = kinds.map { kindLabel(it) }.toTypedArray()
        val mask = layoutPrefs.getMessageKindsMask()
        val checked = BooleanArray(kinds.size) { RandomMessages.isEnabled(mask, kinds[it]) }
        val dialog = AlertDialog.Builder(this, R.style.Theme_TVLauncher_AboutDialog)
            .setTitle(R.string.customize_message_kinds)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton(R.string.done) { _, _ ->
                val selected = kinds.indices.filter { checked[it] }.map { kinds[it] }.toSet()
                if (selected.isEmpty()) {
                    Toast.makeText(
                        this,
                        R.string.messages_none_selected,
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setPositiveButton
                }
                layoutPrefs.setMessageKindsMask(RandomMessages.maskOf(selected))
                lastGreeting = null
                applyGreeting()
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
        styleDialog(dialog)
        dialog.show()
    }

    private fun showMessageTargetDialog() {
        val targets = RandomMessages.Target.values()
        val labels = targets.map { targetLabel(it) }.toTypedArray()
        val current = targets.indexOf(layoutPrefs.getMessageTarget()).coerceAtLeast(0)
        AlertDialog.Builder(this, R.style.Theme_TVLauncher_AboutDialog)
            .setTitle(R.string.customize_message_target)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                dialog.dismiss()
                layoutPrefs.setMessageTarget(targets[which])
                lastGreeting = null
                applyGreeting()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun kindLabel(kind: RandomMessages.Kind): String = when (kind) {
        RandomMessages.Kind.MESSAGE -> getString(R.string.messages_kind_message)
        RandomMessages.Kind.POEM -> getString(R.string.messages_kind_poem)
        RandomMessages.Kind.JOKE -> getString(R.string.messages_kind_joke)
        RandomMessages.Kind.MOTIVATION -> getString(R.string.messages_kind_motivation)
        RandomMessages.Kind.MOVIE -> getString(R.string.messages_kind_movie)
        RandomMessages.Kind.COUPLE -> getString(R.string.messages_kind_couple)
    }

    private fun targetLabel(target: RandomMessages.Target): String = when (target) {
        RandomMessages.Target.ALTERNATE -> getString(R.string.messages_target_alternate)
        RandomMessages.Target.THIEL -> getString(R.string.messages_target_thiel)
        RandomMessages.Target.JESSICA -> getString(R.string.messages_target_jessica)
        RandomMessages.Target.BOTH -> getString(R.string.messages_target_both)
    }

    private fun sortAppsAlphabetically() {
        val sorted = infoById.values
            .sortedBy { it.getDisplayName().lowercase() }
            .map { AppIdentifier.encode(it) }
        appManager.saveSelectedApps(sorted)
        Toast.makeText(this, R.string.sorted_az_done, Toast.LENGTH_SHORT).show()
        refreshContent(null, requestFocus = false)
    }

    private fun showAdbHelpDialog() {
        AlertDialog.Builder(this, R.style.Theme_TVLauncher_AboutDialog)
            .setTitle(R.string.accessibility_adb_title)
            .setMessage(getString(R.string.accessibility_adb_message, AccessibilityEnabler.ADB_COMMAND))
            .setPositiveButton(R.string.close, null)
            .show()
    }

    // ---- Home key accessibility service ----

    // The launcher never asks for accessibility at start: the Android of some TVs disables the
    // service right after it is enabled, and the popup would appear on every return. Everything
    // about it lives in "Configurações avançadas" instead.

    // ---- Wallpaper ----

    private fun applyWallpaper(force: Boolean = false, onFinish: ((Boolean) -> Unit)? = null) {
        val mode = WallpaperManager.getWallpaperMode(this)
        val dimEnabled = WallpaperManager.isDimOverlayEnabled(this)

        if (mode == WallpaperManager.MODE_SOLID) {
            wallpaperImageView.visibility = View.GONE
            wallpaperDimOverlay.visibility = View.GONE
            onFinish?.invoke(true)
            return
        }

        val displayMetrics = resources.displayMetrics
        val width = displayMetrics.widthPixels
        val height = displayMetrics.heightPixels

        WallpaperManager.refreshWallpaperIfNeeded(this, width, height, force) { success, drawable ->
            if (success && drawable != null) {
                wallpaperImageView.setImageDrawable(drawable)
                wallpaperImageView.visibility = View.VISIBLE
                wallpaperDimOverlay.visibility = if (dimEnabled) View.VISIBLE else View.GONE
                onFinish?.invoke(true)
            } else {
                onFinish?.invoke(false)
            }
        }
    }

    private fun showWallpaperDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_wallpaper_settings, null)
        val sourceButton = dialogView.findViewById<TextView>(R.id.sourceSelectorButton)
        val redditContainer = dialogView.findViewById<LinearLayout>(R.id.redditOptionsContainer)
        val categoryButton = dialogView.findViewById<TextView>(R.id.categorySelectorButton)
        val intervalContainer = dialogView.findViewById<LinearLayout>(R.id.intervalContainer)
        val intervalButton = dialogView.findViewById<TextView>(R.id.intervalSelectorButton)
        val folderContainer = dialogView.findViewById<LinearLayout>(R.id.folderContainer)
        val folderButton = dialogView.findViewById<TextView>(R.id.folderSelectorButton)

        val customUrlContainer = dialogView.findViewById<LinearLayout>(R.id.customUrlContainer)
        val customUrlEdit = dialogView.findViewById<EditText>(R.id.customUrlEdit)
        val dimSwitch = dialogView.findViewById<SwitchCompat>(R.id.dimOverlaySwitch)

        // Sources setup
        val sources = listOf(
            WallpaperManager.MODE_SOLID to getString(R.string.wallpaper_mode_solid),
            WallpaperManager.MODE_FOLDER to getString(R.string.wallpaper_mode_folder),
            WallpaperManager.MODE_ONLINE to getString(R.string.wallpaper_mode_reddit),
            WallpaperManager.MODE_CUSTOM to getString(R.string.wallpaper_mode_custom)
        )
        var currentMode = WallpaperManager.getWallpaperMode(this)
        var selectedSourceIndex = sources.indexOfFirst { it.first == currentMode }.coerceAtLeast(0)

        fun updateVisibility(mode: String) {
            redditContainer.visibility = if (mode == WallpaperManager.MODE_ONLINE) View.VISIBLE else View.GONE
            customUrlContainer.visibility = if (mode == WallpaperManager.MODE_CUSTOM) View.VISIBLE else View.GONE
            folderContainer.visibility = if (mode == WallpaperManager.MODE_FOLDER) View.VISIBLE else View.GONE
            intervalContainer.visibility = if (mode == WallpaperManager.MODE_SOLID) View.GONE else View.VISIBLE
        }

        sourceButton.text = sources[selectedSourceIndex].second
        updateVisibility(currentMode)

        sourceButton.setOnClickListener {
            AlertDialog.Builder(this, R.style.Theme_TVLauncher_AboutDialog)
                .setTitle(R.string.wallpaper_mode)
                .setSingleChoiceItems(
                    sources.map { it.second }.toTypedArray(),
                    selectedSourceIndex
                ) { dialog, which ->
                    selectedSourceIndex = which
                    currentMode = sources[which].first
                    sourceButton.text = sources[which].second
                    updateVisibility(currentMode)
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        // Folder setup
        var selectedFolderUri = WallpaperManager.getFolderUri(this)
        fun showFolderLabel() {
            folderButton.text = if (selectedFolderUri.isEmpty()) {
                getString(R.string.wallpaper_folder_choose)
            } else {
                getString(R.string.wallpaper_folder_selected, WallpaperManager.labelForTree(selectedFolderUri))
            }
        }
        fun updateFolderText() {
            val requested = selectedFolderUri
            if (requested.isEmpty()) {
                // Photos imported through the fallback picker: no tree URI exists.
                val local = WallpaperManager.localFolderImageCount(this)
                folderButton.text = if (local > 0) {
                    getString(R.string.wallpaper_photos_selected_count, local)
                } else {
                    getString(R.string.wallpaper_folder_choose)
                }
                return
            }
            showFolderLabel()
            WallpaperManager.countImagesAsync(this, requested) { count ->
                if (isFinishing || isDestroyed) return@countImagesAsync
                if (requested != selectedFolderUri) return@countImagesAsync
                folderButton.text = if (count == 0) {
                    getString(R.string.wallpaper_folder_empty)
                } else {
                    getString(
                        R.string.wallpaper_folder_selected_count,
                        WallpaperManager.labelForTree(requested),
                        count
                    )
                }
            }
        }
        updateFolderText()
        folderButton.setOnClickListener {
            folderPickCallback = { uri ->
                selectedFolderUri = uri.toString()
                updateFolderText()
            }
            TvHomeOverrideService.pauseOverrideFor(30000)
            // Many TVs have no system folder picker (DocumentsUI). In that case the user picks
            // the photos with any file/gallery app that handles pictures, and the launcher keeps
            // its own copy of them.
            try {
                folderPicker.launch(null)
            } catch (_: ActivityNotFoundException) {
                folderPickCallback = null
                launchPhotoMultiPicker()
            } catch (_: Exception) {
                folderPickCallback = null
                launchPhotoMultiPicker()
            }
        }

        // Categories setup
        val categories = listOf(
            WallpaperManager.CATEGORY_NATURE to getString(R.string.wallpaper_category_nature),
            WallpaperManager.CATEGORY_GENERAL to getString(R.string.wallpaper_category_general),
            WallpaperManager.CATEGORY_SPACE to getString(R.string.wallpaper_category_space),
            WallpaperManager.CATEGORY_ARCHITECTURE to getString(R.string.wallpaper_category_architecture),
            WallpaperManager.CATEGORY_CARS to getString(R.string.wallpaper_category_cars),
            WallpaperManager.CATEGORY_ANIME to getString(R.string.wallpaper_category_anime)
        )
        val initialCategoryKey = WallpaperManager.getCategory(this)
        var selectedCategoryIndex = categories.indexOfFirst { it.first == initialCategoryKey }.coerceAtLeast(0)
        categoryButton.text = categories[selectedCategoryIndex].second
        categoryButton.setOnClickListener {
            AlertDialog.Builder(this, R.style.Theme_TVLauncher_AboutDialog)
                .setTitle(R.string.wallpaper_category)
                .setSingleChoiceItems(
                    categories.map { it.second }.toTypedArray(),
                    selectedCategoryIndex
                ) { dialog, which ->
                    selectedCategoryIndex = which
                    categoryButton.text = categories[which].second
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        // Intervals setup
        val intervals = listOf(
            WallpaperManager.INTERVAL_1M to getString(R.string.wallpaper_interval_1m),
            WallpaperManager.INTERVAL_5M to getString(R.string.wallpaper_interval_5m),
            WallpaperManager.INTERVAL_15M to getString(R.string.wallpaper_interval_15m),
            WallpaperManager.INTERVAL_30M to getString(R.string.wallpaper_interval_30m),
            WallpaperManager.INTERVAL_1H to getString(R.string.wallpaper_interval_1h),
            WallpaperManager.INTERVAL_6H to getString(R.string.wallpaper_interval_6h),
            WallpaperManager.INTERVAL_24H to getString(R.string.wallpaper_interval_24h)
        )
        val initialIntervalValue = WallpaperManager.getInterval(this)
        var selectedIntervalIndex = intervals.indexOfFirst { it.first == initialIntervalValue }.coerceAtLeast(0)
        intervalButton.text = intervals[selectedIntervalIndex].second
        intervalButton.setOnClickListener {
            AlertDialog.Builder(this, R.style.Theme_TVLauncher_AboutDialog)
                .setTitle(R.string.wallpaper_interval)
                .setSingleChoiceItems(
                    intervals.map { it.second }.toTypedArray(),
                    selectedIntervalIndex
                ) { dialog, which ->
                    selectedIntervalIndex = which
                    intervalButton.text = intervals[which].second
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        customUrlEdit.setText(WallpaperManager.getCustomUrl(this))
        dimSwitch.isChecked = WallpaperManager.isDimOverlayEnabled(this)

        fun saveWallpaperSettings(): Boolean {
            val customUrl = customUrlEdit.text.toString().trim()
            if (currentMode == WallpaperManager.MODE_CUSTOM && customUrl.isNotEmpty() &&
                !WallpaperManager.isAllowedRemoteUrl(customUrl)
            ) {
                Toast.makeText(this, R.string.wallpaper_https_only, Toast.LENGTH_LONG).show()
                return false
            }
            WallpaperManager.setWallpaperMode(this, currentMode)
            WallpaperManager.setCategory(this, categories[selectedCategoryIndex].first)
            WallpaperManager.setInterval(this, intervals[selectedIntervalIndex].first)
            WallpaperManager.setCustomUrl(this, customUrl)
            WallpaperManager.setFolderUri(this, selectedFolderUri)
            WallpaperManager.setDimOverlayEnabled(this, dimSwitch.isChecked)
            return true
        }

        val wallpaperDialog = AlertDialog.Builder(this, R.style.Theme_TVLauncher_AboutDialog)
            .setTitle(R.string.wallpaper_settings)
            .setView(dialogView)
            .setPositiveButton(R.string.done) { _, _ ->
                if (saveWallpaperSettings()) {
                    applyWallpaper(force = true)
                }
            }
            .setNeutralButton(R.string.wallpaper_refresh_now) { _, _ ->
                if (saveWallpaperSettings()) {
                    Toast.makeText(this, R.string.wallpaper_updating, Toast.LENGTH_SHORT).show()
                    applyWallpaper(force = true) { success ->
                        val hint = if (success) R.string.wallpaper_updated else R.string.wallpaper_failed
                        Toast.makeText(this, hint, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
        styleDialog(
            wallpaperDialog,
            widthFraction = 0.48f,
            maxWidthDp = 680,
            heightFraction = 0.84f
        )
        wallpaperDialog.show()
    }

    private fun openAppDetails(packageName: String) {
        TvHomeOverrideService.pauseOverrideFor(3000)
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, packageName, Toast.LENGTH_SHORT).show()
        }
    }

    /** Android app settings (storage, battery, notifications...) of another app. */
    private fun openAppAndroidSettings(packageName: String) {
        TvHomeOverrideService.pauseOverrideFor(5000)
        val intents = listOf(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            },
            Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS)
        )
        for (intent in intents) {
            try {
                startActivity(intent)
                return
            } catch (_: Exception) {
            }
        }
        Toast.makeText(this, R.string.app_menu_settings_failed, Toast.LENGTH_SHORT).show()
    }

    /** Goes straight to the Android permission screen of another app when the TV supports it. */
    private fun openAppPermissions(packageName: String) {
        TvHomeOverrideService.pauseOverrideFor(5000)
        val intents = listOf(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null)
            ).putExtra(":settings:fragment_args_key", "permissions"),
            Intent(
                "android.settings.APPLICATION_DETAILS_SETTINGS",
                Uri.fromParts("package", packageName, null)
            )
        )
        for (intent in intents) {
            try {
                startActivity(intent)
                return
            } catch (_: Exception) {
            }
        }
        Toast.makeText(this, R.string.app_menu_settings_failed, Toast.LENGTH_SHORT).show()
    }

    /**
     * "Sobre": only the new banner (the launcher already configured), the name and a short
     * description. The old version/GitHub/photo blocks were removed; everything that changes
     * something lives in "Configurações avançadas".
     */
    private fun showAboutDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_about, null)
        dialogView.findViewById<TextView>(R.id.aboutVersionText).text =
            getString(R.string.about_version, getVersionName())
        dialogView.findViewById<TextView>(R.id.aboutMessageText).text =
            getString(R.string.about_message, getString(R.string.about_tagline))

        val dialog = AlertDialog.Builder(this, R.style.Theme_TVLauncher_AboutDialog)
            .setTitle(R.string.about_title)
            .setView(dialogView)
            .setPositiveButton(R.string.about_close, null)
            .setNeutralButton(R.string.advanced_settings) { _, _ ->
                showAdvancedSettingsDialog()
            }
            .create()
        styleDialog(dialog, widthFraction = 0.62f, maxWidthDp = 900)
        dialog.show()
    }

    private fun openAccessibilitySettings() {
        TvHomeOverrideService.pauseOverrideFor(5000)
        val intents = listOf(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
            Intent("android.settings.ACCESSIBILITY_SETTINGS"),
            Intent(Settings.ACTION_SETTINGS)
        )
        for (intent in intents) {
            try {
                startActivity(intent)
                return
            } catch (_: Exception) {
            }
        }
    }

    /** Only shows the updater switch/check in builds that have the updater (github flavor). */
    private fun showUpdatesDialog() {
        val enabled = UpdaterBridge.isAutoUpdateEnabled(this)
        val options = arrayOf(
            getString(
                R.string.advanced_updates_auto,
                getString(if (enabled) R.string.state_on else R.string.state_off)
            ),
            getString(R.string.update_check_now)
        )
        AlertDialog.Builder(this, R.style.Theme_TVLauncher_AboutDialog)
            .setTitle(R.string.advanced_updates)
            .setItems(options) { _, which ->
                if (which == 0) {
                    UpdaterBridge.setAutoUpdateEnabled(this, !enabled)
                    val hint = if (!enabled) R.string.update_auto_on else R.string.update_auto_off
                    Toast.makeText(this, hint, Toast.LENGTH_SHORT).show()
                } else {
                    checkForUpdatesManually()
                }
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun checkForUpdatesManually() {
        Toast.makeText(this, R.string.update_checking, Toast.LENGTH_SHORT).show()
        Thread {
            val result = UpdaterBridge.checkDownloadAndInstall(
                applicationContext,
                ignoreAutoUpdateSetting = true
            )
            runOnUiThread {
                val message = when (result) {
                    UpdateResult.NoUpdate -> getString(R.string.update_none)
                    UpdateResult.InstallStarted -> getString(R.string.update_ready)
                    UpdateResult.InstallPermissionNeeded ->
                        getString(R.string.update_permission_needed)
                    UpdateResult.DownloadFailed -> getString(R.string.update_failed)
                    UpdateResult.InvalidRelease -> getString(R.string.update_invalid_release)
                    UpdateResult.Skipped -> getString(R.string.update_disabled)
                }
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun getVersionName(): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(0)
                ).versionName
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0).versionName
            }
        } catch (_: Exception) {
            BuildConfig.VERSION_NAME
        } ?: BuildConfig.VERSION_NAME
    }

    /** Width follows the column count, so changing the per-tab icon size always has an effect. */
    private fun calculateSlotSizePx(columns: Int): Int {
        val displayMetrics = resources.displayMetrics
        val density = displayMetrics.density
        val hMargin = resources.getDimensionPixelSize(R.dimen.screen_margin_h)
        val gridPadding = resources.getDimensionPixelSize(R.dimen.grid_padding)
        val itemMargin = resources.getDimensionPixelSize(R.dimen.grid_item_margin)
        val availableWidth = displayMetrics.widthPixels - hMargin * 2 - gridPadding * 2 -
            itemMargin * 2 * columns
        val byWidth = availableWidth / columns.coerceAtLeast(1)
        return byWidth.coerceIn((34 * density).toInt(), (420 * density).toInt())
    }

    /** Height follows the global row arrangement independently of the icon width. */
    private fun calculateSlotHeightPx(rows: Int): Int {
        val metrics = resources.displayMetrics
        val density = metrics.density
        val vMargin = resources.getDimensionPixelSize(R.dimen.screen_margin_v)
        val gridPadding = resources.getDimensionPixelSize(R.dimen.grid_padding)
        val header = (72 * density).toInt()
        val greeting = if (layoutPrefs.isAutoMessagesEnabled()) (46 * density).toInt() else 0
        val mostUsed = if (layoutPrefs.isMostUsedEnabled()) (92 * density).toInt() else 0
        val availableHeight = metrics.heightPixels - vMargin * 2 - header - greeting -
            mostUsed - gridPadding * 2
        return (availableHeight / rows.coerceAtLeast(1))
            .coerceIn((34 * density).toInt(), (360 * density).toInt())
    }

    // ---- Search ----

    /** Header search icon: finds any installed app by name, ignoring accents and case. */
    private fun showSearchDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_app_search, null)
        val edit = dialogView.findViewById<EditText>(R.id.searchEdit)
        val results = dialogView.findViewById<RecyclerView>(R.id.searchResults)
        val summary = dialogView.findViewById<TextView>(R.id.searchSummary)
        val empty = dialogView.findViewById<TextView>(R.id.searchEmpty)
        val external = dialogView.findViewById<LinearLayout>(R.id.searchExternalContainer)
        val internet = dialogView.findViewById<TextView>(R.id.searchInternetButton)
        val youtube = dialogView.findViewById<TextView>(R.id.searchYoutubeButton)
        val playStore = dialogView.findViewById<TextView>(R.id.searchPlayStoreButton)
        val apps = appManager.getInstalledApps()
        val iconPx = (32 * resources.displayMetrics.density).toInt()
        var dialog: AlertDialog? = null

        val adapter = SearchAdapter(
            onClick = { app ->
                dialog?.dismiss()
                launchApp(app)
            },
            iconSizePx = { iconPx }
        )
        val columns = if (resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        ) 2 else 1
        results.layoutManager = GridLayoutManager(this, columns)
        results.adapter = adapter

        fun update(query: String) {
            val clean = query.trim()
            if (clean.isEmpty()) {
                adapter.submitList(emptyList())
                external.visibility = View.GONE
                results.visibility = View.GONE
                summary.visibility = View.GONE
                empty.visibility = View.GONE
                return
            }
            external.visibility = View.VISIBLE
            internet.text = getString(R.string.search_internet, clean)
            youtube.text = getString(R.string.search_youtube, clean)
            playStore.text = getString(R.string.search_play_store, clean)
            val found = SearchAdapter.filter(apps, clean)
            adapter.submitList(found)
            summary.visibility = View.VISIBLE
            summary.text = getString(R.string.search_results_count, found.size)
            results.visibility = if (found.isEmpty()) View.GONE else View.VISIBLE
            empty.visibility = if (found.isEmpty()) View.VISIBLE else View.GONE
        }

        internet.setOnClickListener {
            openExternalSearch(edit.text.toString(), "internet")
            dialog?.dismiss()
        }
        youtube.setOnClickListener {
            openExternalSearch(edit.text.toString(), "youtube")
            dialog?.dismiss()
        }
        playStore.setOnClickListener {
            openExternalSearch(edit.text.toString(), "play")
            dialog?.dismiss()
        }

        edit.doAfterTextChanged { update(it?.toString().orEmpty()) }
        update("")
        edit.setOnEditorActionListener { _, _, _ ->
            if (external.visibility == View.VISIBLE) {
                internet.requestFocus()
            } else {
                results.getChildAt(0)?.requestFocus()
            }
            true
        }

        dialog = AlertDialog.Builder(this, R.style.Theme_TVLauncher_AboutDialog)
            .setTitle(R.string.search_title)
            .setView(dialogView)
            .setNegativeButton(R.string.close, null)
            .create()
        val searchDialog = dialog ?: return
        styleDialog(searchDialog, widthFraction = 0.62f, maxWidthDp = 940, heightFraction = 0.82f)
        searchDialog.show()
        edit.requestFocus()
    }

    private fun openExternalSearch(rawQuery: String, destination: String) {
        val query = rawQuery.trim()
        if (query.isEmpty()) return
        val encoded = Uri.encode(query)
        val url = when (destination) {
            "youtube" -> "https://www.youtube.com/results?search_query=$encoded"
            "play" -> "https://play.google.com/store/search?q=$encoded&c=apps"
            else -> "https://www.google.com/search?q=$encoded"
        }
        val preferred = if (destination == "youtube") {
            ContentBannerManager.playerPackage(this)
        } else {
            null
        }
        TvHomeOverrideService.pauseOverrideFor(5000)
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            if (!preferred.isNullOrBlank()) intent.setPackage(preferred)
            startActivity(intent)
        } catch (_: Exception) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (_: Exception) {
                Toast.makeText(this, R.string.search_external_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ---- Profile avatar: photo + system dashboard ----

    private fun profilePhotoFile(): File = File(filesDir, "profile_photo.jpg")

    private fun applyProfilePhoto() {
        IconShapes.applyTo(profileButton, IconShapes.CIRCLE)
        val file = profilePhotoFile()
        val bitmap = if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null
        if (bitmap != null) {
            profileButton.setImageBitmap(bitmap)
            profileButton.scaleType = ImageView.ScaleType.CENTER_CROP
            profileButton.setPadding(0, 0, 0, 0)
        } else {
            profileButton.scaleType = ImageView.ScaleType.CENTER_INSIDE
            val pad = (9 * resources.displayMetrics.density).toInt()
            profileButton.setPadding(pad, pad, pad, pad)
            profileButton.setImageResource(R.drawable.ic_profile)
        }
    }

    /** Long press on the avatar: choose the visible area, then save a square/circular avatar. */
    private fun showProfilePhotoAdjustDialog(uri: Uri) {
        val decoded = try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null else {
                var sample = 1
                while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) {
                    sample *= 2
                }
                val options = BitmapFactory.Options().apply { inSampleSize = sample }
                contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, options)
                }
            }
        } catch (_: Exception) {
            null
        }
        if (decoded == null) {
            Toast.makeText(this, R.string.profile_photo_failed, Toast.LENGTH_SHORT).show()
            return
        }

        val cropView = ProfileCropView(this)
        cropView.setBitmap(decoded)
        val dialog = AlertDialog.Builder(this, R.style.Theme_TVLauncher_AboutDialog)
            .setTitle(R.string.profile_adjust_title)
            .setView(cropView)
            .setPositiveButton(R.string.done, null)
            .setNegativeButton(R.string.cancel) { _, _ -> decoded.recycle() }
            .create()
        styleDialog(dialog, widthFraction = 0.5f, maxWidthDp = 620, heightFraction = 0.9f)
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val output = try {
                    cropView.createCrop512()
                } catch (_: Exception) {
                    null
                }
                val saved = output != null && try {
                    FileOutputStream(profilePhotoFile()).use { out ->
                        output.compress(Bitmap.CompressFormat.JPEG, 92, out)
                    }
                    true
                } catch (_: Exception) {
                    false
                }
                output?.recycle()
                decoded.recycle()
                dialog.dismiss()
                if (saved) {
                    applyProfilePhoto()
                    CrashLog.log(this, "Foto de perfil ajustada e atualizada")
                    Toast.makeText(this, R.string.profile_photo_saved, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, R.string.profile_photo_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
        dialog.show()
    }

    /**
     * Click on the avatar: memory, processor, the apps using the most memory, the connection and
     * the internal storage. Colors: green = normal, yellow = medium, red = high.
     */
    private fun showSystemDashboard() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_system_info, null)
        val ramText = dialogView.findViewById<TextView>(R.id.sysRamText)
        val ramBar = dialogView.findViewById<ProgressBar>(R.id.sysRamBar)
        val cpuText = dialogView.findViewById<TextView>(R.id.sysCpuText)
        val appsContainer = dialogView.findViewById<LinearLayout>(R.id.sysAppsContainer)
        val networkText = dialogView.findViewById<TextView>(R.id.sysNetworkText)
        val storageText = dialogView.findViewById<TextView>(R.id.sysStorageText)
        val storageBar = dialogView.findViewById<ProgressBar>(R.id.sysStorageBar)

        ramText.text = getString(R.string.dashboard_loading)
        val dialog = AlertDialog.Builder(this, R.style.Theme_TVLauncher_AboutDialog)
            .setTitle(R.string.dashboard_title)
            .setView(dialogView)
            .setPositiveButton(R.string.close, null)
            .setNeutralButton(R.string.dashboard_refresh) { _, _ -> showSystemDashboard() }
            .create()
        styleDialog(dialog, widthFraction = 0.6f, maxWidthDp = 860, heightFraction = 0.85f)

        fun render(snapshot: SystemStats.Snapshot) {
            if (isFinishing || isDestroyed) return
            val usedPercent = if (snapshot.ramTotalBytes > 0) {
                ((snapshot.ramUsedBytes * 100) / snapshot.ramTotalBytes).toInt()
            } else 0
            ramText.text = getString(
                R.string.dashboard_ram_value,
                SystemStats.formatBytes(snapshot.ramUsedBytes),
                SystemStats.formatBytes(snapshot.ramTotalBytes),
                SystemStats.formatBytes(snapshot.ramAvailableBytes),
                usedPercent
            )
            ramBar.progress = usedPercent
            ramBar.progressTintList = ColorStateList.valueOf(
                SystemStats.colorForPercent(this, usedPercent)
            )
            cpuText.text = getString(R.string.dashboard_cpu_value, snapshot.cpuPercent)

            appsContainer.removeAllViews()
            if (snapshot.apps.isEmpty()) {
                appsContainer.addView(TextView(this).apply {
                    text = getString(R.string.dashboard_apps_hidden)
                    setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                    textSize = 12f
                })
            } else {
                val density = resources.displayMetrics.density
                snapshot.apps.forEach { usage ->
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        setPadding(0, (5 * density).toInt(), 0, (5 * density).toInt())
                    }
                    val dot = View(this).apply {
                        background = android.graphics.drawable.GradientDrawable().apply {
                            shape = android.graphics.drawable.GradientDrawable.OVAL
                            setColor(SystemStats.colorForPercent(this@MainActivity, usage.percent))
                        }
                        layoutParams = LinearLayout.LayoutParams(
                            (10 * density).toInt(),
                            (10 * density).toInt()
                        )
                    }
                    val label = TextView(this).apply {
                        text = usage.label
                        setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                        textSize = 14f
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        layoutParams = LinearLayout.LayoutParams(
                            0,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            1f
                        )
                        setPadding((10 * density).toInt(), 0, (8 * density).toInt(), 0)
                    }
                    val value = TextView(this).apply {
                        text = if (usage.memoryBytes > 0) {
                            getString(
                                R.string.dashboard_app_usage,
                                usage.percent,
                                SystemStats.formatBytes(usage.memoryBytes)
                            )
                        } else {
                            getString(R.string.dashboard_app_usage_short, usage.percent)
                        }
                        setTextColor(SystemStats.colorForPercent(context, usage.percent))
                        textSize = 13f
                    }
                    row.addView(dot)
                    row.addView(label)
                    row.addView(value)
                    appsContainer.addView(row)
                }
            }

            val net = snapshot.network
            networkText.text = if (!net.connected) {
                getString(R.string.dashboard_network_offline)
            } else {
                val type = when (net.type) {
                    "wifi" -> getString(R.string.dashboard_network_wifi)
                    "ethernet" -> getString(R.string.dashboard_network_ethernet)
                    "mobile" -> getString(R.string.dashboard_network_mobile)
                    else -> getString(R.string.dashboard_network_other)
                }
                getString(
                    R.string.dashboard_network_value,
                    type,
                    net.name,
                    net.ip.ifEmpty { "-" },
                    SystemStats.formatBytes(net.rxBytes),
                    SystemStats.formatBytes(net.txBytes)
                )
            }

            val storage = snapshot.storage
            val storagePercent = if (storage.totalBytes > 0) {
                ((storage.usedBytes * 100) / storage.totalBytes).toInt()
            } else 0
            storageText.text = getString(
                R.string.dashboard_storage_value,
                SystemStats.formatBytes(storage.totalBytes),
                SystemStats.formatBytes(storage.usedBytes),
                SystemStats.formatBytes(storage.freeBytes),
                storagePercent
            )
            storageBar.progress = storagePercent
            storageBar.progressTintList = ColorStateList.valueOf(
                SystemStats.colorForPercent(this, storagePercent)
            )
        }

        dialog.show()
        Thread {
            val snapshot = SystemStats.snapshot(applicationContext)
            runOnUiThread { render(snapshot) }
        }.start()
    }

    // ---- Advanced settings ----

    private fun showAdvancedSettingsDialog() {
        val options = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        options.add(getString(R.string.advanced_accessibility))
        actions.add { showAccessibilityDialog() }
        options.add(getString(R.string.advanced_default_launcher))
        actions.add { requestDefaultLauncher() }
        options.add(getString(R.string.customize_free_memory))
        actions.add { confirmFreeMemory() }
        options.add(getString(R.string.customize_backup_export))
        actions.add { startExport() }
        options.add(getString(R.string.customize_backup_import))
        actions.add { startImport() }
        options.add(getString(R.string.advanced_event_log))
        actions.add { showEventLogDialog() }
        if (BuildConfig.UPDATES_ENABLED) {
            options.add(getString(R.string.advanced_updates))
            actions.add { showUpdatesDialog() }
        }
        options.add(getString(R.string.advanced_clear_cache))
        actions.add { confirmClearCache() }
        options.add(stateLabel(
            R.string.suppress_stock_ram,
            TvHomeOverrideService.isSuppressStockLauncherEnabled(this)
        ))
        actions.add {
            val enabling = !TvHomeOverrideService.isSuppressStockLauncherEnabled(this)
            if (enabling) {
                AlertDialog.Builder(this, R.style.Theme_TVLauncher_AboutDialog)
                    .setTitle(R.string.suppress_stock_ram)
                    .setMessage(R.string.suppress_stock_ram_confirm)
                    .setPositiveButton(R.string.enable) { _, _ ->
                        TvHomeOverrideService.setSuppressStockLauncherEnabled(this, true)
                        RamOptimizationHelper.suppressStockLauncherRam(applicationContext)
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            } else {
                TvHomeOverrideService.setSuppressStockLauncherEnabled(this, false)
            }
        }
        options.add(getString(R.string.hidden_apps_title))
        actions.add { showHiddenAppsDialog() }

        AlertDialog.Builder(this, R.style.Theme_TVLauncher_AboutDialog)
            .setTitle(R.string.advanced_title)
            .setItems(options.toTypedArray()) { _, which ->
                actions.getOrNull(which)?.invoke()
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    /** Accessibility + Home key help, without ever showing a popup when the launcher starts. */
    private fun showAccessibilityDialog() {
        val serviceOn = TvHomeOverrideService.isAccessibilityServiceEnabled(this)
        val overrideOn = TvHomeOverrideService.isHomeOverrideEnabled(this)
        val status = getString(
            R.string.advanced_accessibility_status,
            getString(if (serviceOn) R.string.state_on else R.string.state_off),
            getString(if (overrideOn) R.string.state_on else R.string.state_off)
        ) + "\n\n" + getString(R.string.advanced_accessibility_why)

        val options = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        options.add(
            getString(
                if (overrideOn) R.string.advanced_home_disable else R.string.advanced_home_enable
            )
        )
        actions.add {
            TvHomeOverrideService.setHomeOverrideEnabled(this, !overrideOn)
            CrashLog.log(this, "Botão Home: ${if (!overrideOn) "ligado" else "desligado"}")
        }
        options.add(getString(R.string.advanced_open_accessibility))
        actions.add { openAccessibilitySettings() }
        options.add(getString(R.string.advanced_enable_automatically))
        actions.add {
            if (AccessibilityEnabler.tryEnable(this)) {
                Toast.makeText(this, R.string.accessibility_auto_enabled, Toast.LENGTH_SHORT)
                    .show()
                CrashLog.log(this, "Acessibilidade ativada automaticamente")
            } else {
                showAdbHelpDialog()
            }
        }
        options.add(getString(R.string.advanced_accessibility_help))
        actions.add { showAdbHelpDialog() }

        AlertDialog.Builder(this, R.style.Theme_TVLauncher_AboutDialog)
            .setTitle(R.string.advanced_accessibility)
            .setMessage(status)
            .setItems(options.toTypedArray()) { _, which ->
                actions.getOrNull(which)?.invoke()
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    /** Asks Android to make this launcher the default Home app. */
    @Suppress("DEPRECATION")
    private fun requestDefaultLauncher() {
        TvHomeOverrideService.pauseOverrideFor(8000)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val roleManager = getSystemService(RoleManager::class.java)
                if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_HOME)) {
                    if (roleManager.isRoleHeld(RoleManager.ROLE_HOME)) {
                        Toast.makeText(this, R.string.advanced_already_default, Toast.LENGTH_SHORT)
                            .show()
                        return
                    }
                    startActivityForResult(
                        roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME),
                        REQUEST_HOME_ROLE
                    )
                    return
                }
            } catch (_: Exception) {
            }
        }
        val intents = listOf(
            Intent(Settings.ACTION_HOME_SETTINGS),
            Intent("android.settings.HOME_SETTINGS"),
            Intent(Settings.ACTION_SETTINGS)
        )
        for (intent in intents) {
            try {
                startActivity(intent)
                return
            } catch (_: Exception) {
            }
        }
        Toast.makeText(this, R.string.advanced_default_unavailable, Toast.LENGTH_LONG).show()
    }

    /** Result of the system "default Home app" role dialog, so the user knows if it worked. */
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_HOME_ROLE) return
        if (isDefaultLauncher()) {
            Toast.makeText(this, R.string.advanced_default_granted, Toast.LENGTH_LONG).show()
            CrashLog.log(this, "Launcher definida como padrão do sistema")
        } else {
            Toast.makeText(this, R.string.advanced_default_denied, Toast.LENGTH_LONG).show()
            CrashLog.log(this, "Troca de launcher padrão não concluída")
        }
    }

    private fun confirmFreeMemory() {
        val before = RamOptimizationHelper.availableMemoryMb(this)
        AlertDialog.Builder(this, R.style.Theme_TVLauncher_AboutDialog)
            .setTitle(R.string.customize_free_memory)
            .setMessage(getString(R.string.free_memory_confirm, before))
            .setPositiveButton(R.string.ok) { _, _ -> freeMemory() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showEventLogDialog() {
        val text = buildEventLogText()
        val dialog = AlertDialog.Builder(this, R.style.Theme_TVLauncher_AboutDialog)
            .setTitle(R.string.advanced_event_log)
            .setMessage(text + "\n\n" + getString(R.string.event_log_tip))
            .setPositiveButton(R.string.event_log_save) { _, _ ->
                try {
                    logExportPicker.launch("tj-tv-launcher-log.txt")
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(this, R.string.backup_no_picker, Toast.LENGTH_LONG).show()
                }
            }
            .setNeutralButton(R.string.event_log_clear) { _, _ ->
                CrashLog.clear(this)
                Toast.makeText(this, R.string.event_log_cleared, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.close, null)
            .create()
        styleDialog(dialog, widthFraction = 0.62f, maxWidthDp = 960, heightFraction = 0.85f)
        dialog.show()
    }

    /**
     * Everything a person needs to understand a problem later: launcher version, TV model,
     * Android version, current settings, memory/storage/network and the recorded events.
     */
    private fun buildEventLogText(): String {
        val builder = StringBuilder()
        builder.appendLine("TJ TV Launcher - log de eventos")
        builder.appendLine("Gerado em: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
        builder.appendLine("Versão: ${getVersionName()}")
        builder.appendLine("Pacote: $packageName")
        builder.appendLine("TV: ${Build.MANUFACTURER} ${Build.MODEL}")
        builder.appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        builder.appendLine("Tela: ${resources.displayMetrics.widthPixels}x${resources.displayMetrics.heightPixels} @ ${resources.displayMetrics.density}x")
        builder.appendLine("Aba atual: ${tabDisplayName(currentTab)}")
        builder.appendLine("Botão Home: acessibilidade=${TvHomeOverrideService.isAccessibilityServiceEnabled(this)}, override=${TvHomeOverrideService.isHomeOverrideEnabled(this)}")
        builder.appendLine("Launcher padrão: ${if (isDefaultLauncher()) "sim" else "não"}")
        builder.appendLine("Grade: ${layoutPrefs.getColumnsFor(currentTab)} colunas x ${layoutPrefs.getRows()} linhas")
        builder.appendLine("Categorias: ${layoutPrefs.getCategories().size}")
        builder.appendLine("Papel de parede: ${WallpaperManager.getWallpaperMode(this)}")
        builder.appendLine("Memória livre: ${RamOptimizationHelper.availableMemoryMb(this)} MB")
        try {
            val stat = StatFs(Environment.getDataDirectory().absolutePath)
            val total = stat.blockCountLong * stat.blockSizeLong
            val free = stat.availableBlocksLong * stat.blockSizeLong
            builder.appendLine("Armazenamento: ${SystemStats.formatBytes(total - free)} usados de ${SystemStats.formatBytes(total)}")
        } catch (_: Exception) {
        }
        builder.appendLine()
        builder.appendLine("Último erro:")
        builder.appendLine(CrashLog.read(this) ?: "nenhum")
        builder.appendLine()
        builder.appendLine("Eventos:")
        builder.appendLine(CrashLog.readEvents(this).ifBlank { "nenhum evento registrado" })
        return builder.toString()
    }

    private fun writeEventLog(uri: Uri) {
        val ok = try {
            contentResolver.openOutputStream(uri)?.use { out ->
                out.write(buildEventLogText().toByteArray(Charsets.UTF_8))
            } != null
        } catch (_: Exception) {
            false
        }
        Toast.makeText(
            this,
            if (ok) R.string.event_log_saved else R.string.backup_write_failed,
            Toast.LENGTH_LONG
        ).show()
    }

    private fun isDefaultLauncher(): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.resolveActivity(
                    intent,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            }
            resolved?.activityInfo?.packageName == packageName
        } catch (_: Exception) {
            false
        }
    }

    private fun confirmClearCache() {
        val size = cacheSizeBytes()
        AlertDialog.Builder(this, R.style.Theme_TVLauncher_AboutDialog)
            .setTitle(R.string.advanced_clear_cache)
            .setMessage(getString(R.string.clear_cache_confirm, SystemStats.formatBytes(size)))
            .setPositiveButton(R.string.ok) { _, _ -> clearLauncherCache() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Only cache: preferences, categories, PIN, profile photo and wallpapers are untouched. */
    private fun clearLauncherCache() {
        val before = cacheSizeBytes()
        try {
            cacheDir.listFiles()?.forEach { it.deleteRecursively() }
            externalCacheDir?.listFiles()?.forEach { it.deleteRecursively() }
        } catch (_: Exception) {
        }
        appManager.clearCache()
        val after = cacheSizeBytes()
        val freed = (before - after).coerceAtLeast(0L)
        CrashLog.log(this, "Cache limpo: ${SystemStats.formatBytes(freed)}")
        Toast.makeText(
            this,
            getString(R.string.clear_cache_done, SystemStats.formatBytes(freed)),
            Toast.LENGTH_LONG
        ).show()
        applyWallpaper(force = true)
    }

    private fun cacheSizeBytes(): Long {
        fun sizeOf(dir: File?): Long {
            if (dir == null || !dir.exists()) return 0L
            return try {
                dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            } catch (_: Exception) {
                0L
            }
        }
        return sizeOf(cacheDir) + sizeOf(externalCacheDir)
    }

    /** Used when the TV does not have the system folder picker. */
    private fun launchPhotoMultiPicker() {
        try {
            folderPhotosPicker.launch("image/*")
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.wallpaper_folder_no_picker, Toast.LENGTH_LONG).show()
        }
    }

    private fun importFolderPhotos(uris: List<Uri>) {
        val saved = WallpaperManager.savePickedImages(this, uris)
        if (saved > 0) {
            CrashLog.log(this, "Papel de parede: $saved fotos importadas")
            Toast.makeText(
                this,
                getString(R.string.wallpaper_photos_saved, saved),
                Toast.LENGTH_LONG
            ).show()
            applyWallpaper(force = true)
        } else {
            Toast.makeText(this, R.string.wallpaper_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun openAndroidSettings() {
        TvHomeOverrideService.pauseOverrideFor(5000)
        try {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        } catch (_: Exception) {
            try {
                startActivity(Intent("android.settings.TV_SETTINGS"))
            } catch (_: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS))
                } catch (_: Exception) {
                }
            }
        }
    }


    override fun onResume() {
        super.onResume()
        loadHomeInfos()
        refreshTopApps()
        val grabFocus = currentFocus == null || appSlots.hasFocus() || allAppsList.hasFocus()
        refreshContent(lastFocusedKey, requestFocus = grabFocus)
        applyWallpaper(force = false)
        RamOptimizationHelper.suppressStockLauncherRam(applicationContext)
        mainHandler.removeCallbacks(wallpaperTicker)
        mainHandler.postDelayed(wallpaperTicker, WALLPAPER_CHECK_MS)
    }

    override fun onPause() {
        super.onPause()
        // Leaving the home screen (or opening a dialog elsewhere) ends reorder mode.
        stopReorder()
        mainHandler.removeCallbacks(wallpaperTicker)
    }

    override fun onStop() {
        super.onStop()
        appManager.invalidateAppListCache()
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(wallpaperTicker)
        try {
            unregisterReceiver(refreshReceiver)
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val TAB_HOME = "tab:home"
        private const val TAB_ALL = "tab:all"
        private const val TAB_NEW = "tab:new"
        private const val TOP_APPS_LIMIT = 6

        /** "All apps" is a compact list, so it allows fewer columns than the home grid. */
        private const val ALL_APPS_COLUMNS = 2
        private const val ALL_APPS_MIN_COLUMNS = 2
        private const val ALL_APPS_MAX_COLUMNS = 8
        private const val WALLPAPER_CHECK_MS = 15_000L
        private const val WEATHER_UI_REFRESH_MS = 30 * 60 * 1000L

        /** Holding OK on the Home key for this long opens the running apps. */
        const val HOME_LONG_PRESS_MS = 700L

        /** Request code for the Android "default Home app" role dialog. */
        private const val REQUEST_HOME_ROLE = 4242
    }
}
