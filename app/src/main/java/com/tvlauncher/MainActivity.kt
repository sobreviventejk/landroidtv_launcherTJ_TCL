package com.tvlauncher

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator

class MainActivity : AppCompatActivity() {

    private lateinit var appSlots: RecyclerView
    private lateinit var settingsButton: ImageButton
    private lateinit var wallpaperButton: ImageButton
    private lateinit var customizeButton: ImageButton
    private lateinit var aboutButton: View
    private lateinit var wallpaperImageView: ImageView
    private lateinit var wallpaperDimOverlay: View
    private lateinit var appManager: AppManager
    private lateinit var layoutPrefs: LayoutPrefs
    private lateinit var appSlotAdapter: AppSlotAdapter
    private lateinit var gridLayoutManager: GridLayoutManager

    /** Key (see AppSlotAdapter.AppSlot.key) of the tile that had focus last. */
    private var lastFocusedKey: String? = null

    /** Apps on the home screen by app identifier, as loaded from the system. */
    private var infoById: Map<String, AppInfo> = emptyMap()

    /** Identifier of the app being moved with the remote (reorder mode), or null. */
    private var reorderingId: String? = null

    private val refreshReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            if (intent?.action == LauncherBroadcast.ACTION_REFRESH_HOME) {
                appManager.invalidateSelectionCache()
                appManager.invalidateAppListCache()
                loadAppSlots()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        appSlots = findViewById(R.id.appSlots)
        settingsButton = findViewById(R.id.settingsButton)
        wallpaperButton = findViewById(R.id.wallpaperButton)
        customizeButton = findViewById(R.id.customizeButton)
        aboutButton = findViewById(R.id.aboutButton)
        wallpaperImageView = findViewById(R.id.wallpaperImageView)
        wallpaperDimOverlay = findViewById(R.id.wallpaperDimOverlay)

        appManager = AppManager(this)
        layoutPrefs = LayoutPrefs(this)
        setupRecyclerView()
        setupClickListeners()
        loadAppSlots()
        applyWallpaper()

        ContextCompat.registerReceiver(
            this,
            refreshReceiver,
            android.content.IntentFilter(LauncherBroadcast.ACTION_REFRESH_HOME),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (appSlots.childCount > 0) {
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

    private fun setupRecyclerView() {
        val columns = layoutPrefs.getColumns()
        appSlotAdapter = AppSlotAdapter(
            onPlusClick = { openAppSelection() },
            onAppLongClick = { position -> showAppContextMenu(position) },
            onHeaderClick = { position -> showCategoryMenu(position) },
            onItemFocused = { key -> lastFocusedKey = key },
            sizing = buildSizing(columns)
        )

        gridLayoutManager = GridLayoutManager(this, columns)
        gridLayoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                val slot = appSlotAdapter.currentList.getOrNull(position)
                // A category header takes a whole row; every other item is a single tile.
                return if (slot != null && slot.isHeader) gridLayoutManager.spanCount else 1
            }
        }
        gridLayoutManager.spanSizeLookup.isSpanIndexCacheEnabled = true

        appSlots.layoutManager = gridLayoutManager
        appSlots.adapter = appSlotAdapter
        appSlots.setHasFixedSize(true)
        appSlots.setItemViewCacheSize(0)
        appSlots.recycledViewPool.setMaxRecycledViews(AppSlotAdapter.VIEW_TYPE_APP, 12)
        // Update tiles in place so the remote's focus is not lost when the list changes.
        (appSlots.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
    }

    private fun buildSizing(columns: Int): AppSlotAdapter.Sizing {
        return AppSlotAdapter.Sizing.forSlot(
            calculateSlotSizePx(columns),
            resources.displayMetrics.density
        )
    }

    private fun setupClickListeners() {
        settingsButton.setOnClickListener { openAndroidSettings() }
        wallpaperButton.setOnClickListener { showWallpaperDialog() }
        customizeButton.setOnClickListener { showCustomizeDialog() }
        aboutButton.setOnClickListener { showAboutDialog() }
    }

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
                if (mode == WallpaperManager.MODE_SOLID) {
                    wallpaperImageView.visibility = View.GONE
                    wallpaperDimOverlay.visibility = View.GONE
                }
                onFinish?.invoke(false)
            }
        }
    }

    private fun showWallpaperDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_wallpaper_settings, null)
        val sourceButton = dialogView.findViewById<TextView>(R.id.sourceSelectorButton)
        val redditContainer = dialogView.findViewById<LinearLayout>(R.id.redditOptionsContainer)
        val categoryButton = dialogView.findViewById<TextView>(R.id.categorySelectorButton)
        val intervalButton = dialogView.findViewById<TextView>(R.id.intervalSelectorButton)

        val customUrlContainer = dialogView.findViewById<LinearLayout>(R.id.customUrlContainer)
        val customUrlEdit = dialogView.findViewById<EditText>(R.id.customUrlEdit)
        val dimSwitch = dialogView.findViewById<SwitchCompat>(R.id.dimOverlaySwitch)

        // Sources setup
        val sources = listOf(
            WallpaperManager.MODE_SOLID to getString(R.string.wallpaper_mode_solid),
            WallpaperManager.MODE_ONLINE to getString(R.string.wallpaper_mode_reddit),
            WallpaperManager.MODE_CUSTOM to getString(R.string.wallpaper_mode_custom)
        )
        var currentMode = WallpaperManager.getWallpaperMode(this)
        var selectedSourceIndex = sources.indexOfFirst { it.first == currentMode }.coerceAtLeast(0)

        fun updateVisibility(mode: String) {
            redditContainer.visibility = if (mode == WallpaperManager.MODE_ONLINE) View.VISIBLE else View.GONE
            customUrlContainer.visibility = if (mode == WallpaperManager.MODE_CUSTOM) View.VISIBLE else View.GONE
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
            WallpaperManager.INTERVAL_15M to getString(R.string.wallpaper_interval_15m),
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

        fun saveWallpaperSettings() {
            WallpaperManager.setWallpaperMode(this, currentMode)
            WallpaperManager.setCategory(this, categories[selectedCategoryIndex].first)
            WallpaperManager.setInterval(this, intervals[selectedIntervalIndex].first)
            WallpaperManager.setCustomUrl(this, customUrlEdit.text.toString().trim())
            WallpaperManager.setDimOverlayEnabled(this, dimSwitch.isChecked)
        }

        AlertDialog.Builder(this, R.style.Theme_TVLauncher_AboutDialog)
            .setTitle(R.string.wallpaper_settings)
            .setView(dialogView)
            .setPositiveButton(R.string.done) { _, _ ->
                saveWallpaperSettings()
                applyWallpaper(force = true)
            }
            .setNeutralButton(R.string.wallpaper_refresh_now) { _, _ ->
                Toast.makeText(this, R.string.wallpaper_updating, Toast.LENGTH_SHORT).show()
                saveWallpaperSettings()
                applyWallpaper(force = true) { success ->
                    val hint = if (success) R.string.wallpaper_updated else R.string.wallpaper_failed
                    Toast.makeText(this, hint, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showAppContextMenu(position: Int) {
        val slot = appSlotAdapter.currentList.getOrNull(position) ?: return
        val app = slot.appInfo ?: return
        val appId = slot.key

        val options = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        // 1. Open
        options.add(getString(R.string.app_menu_open))
        actions.add {
            if (app.isShortcut) {
                ShortcutHelper.launchShortcut(this, app.packageName, app.shortcutId!!)
            } else {
                ShortcutHelper.launchApp(this, app.packageName)
            }
        }

        // 2. Reorder with the remote's arrow keys
        options.add(getString(R.string.app_menu_reorder))
        actions.add { startReorder(appId) }

        // 3. Move to a category
        options.add(getString(R.string.app_menu_move_to_category))
        actions.add { showMoveToCategoryDialog(appId) }

        // 4. App Info
        if (!app.isShortcut) {
            options.add(getString(R.string.app_menu_info))
            actions.add {
                openAppDetails(app.packageName)
            }
        }

        // 5. Remove
        options.add(getString(R.string.remove))
        actions.add {
            confirmRemoveApp(app, position)
        }

        AlertDialog.Builder(this, R.style.Theme_TVLauncher_AboutDialog)
            .setTitle(app.getDisplayName())
            .setItems(options.toTypedArray()) { _, which ->
                actions.getOrNull(which)?.invoke()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
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
        submitSlots(appId)
    }

    // ---- Categories ----

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
        lastFocusedKey = appId
        submitSlots(appId)
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
                    else -> promptNewCategory(appId)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Asks for a name and creates a category; if [assignAppId] is set, the app joins it. */
    private fun promptNewCategory(assignAppId: String?) {
        promptCategoryName(R.string.customize_new_category, "") { name ->
            val category = layoutPrefs.addCategory(name)
            if (category == null) {
                Toast.makeText(this, R.string.category_name_empty, Toast.LENGTH_SHORT).show()
            } else if (assignAppId != null) {
                assignToCategory(assignAppId, category.id)
            } else {
                val key = AppSlotAdapter.categoryKey(category.id)
                lastFocusedKey = key
                submitSlots(key)
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

    /** Menu shown when a category header is selected. */
    private fun showCategoryMenu(position: Int) {
        val category = appSlotAdapter.currentList.getOrNull(position)?.category ?: return
        val categories = layoutPrefs.getCategories()
        val index = categories.indexOfFirst { it.id == category.id }
        val headerKey = AppSlotAdapter.categoryKey(category.id)

        val options = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        options.add(getString(R.string.category_rename))
        actions.add {
            promptCategoryName(R.string.category_rename, category.name) { name ->
                if (layoutPrefs.renameCategory(category.id, name)) {
                    lastFocusedKey = headerKey
                    submitSlots(headerKey)
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
        options.add(getString(R.string.customize_new_category))
        actions.add { promptNewCategory(null) }
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

    private fun moveCategory(categoryId: String, offset: Int) {
        if (layoutPrefs.moveCategory(categoryId, offset)) {
            val key = AppSlotAdapter.categoryKey(categoryId)
            lastFocusedKey = key
            submitSlots(key)
        }
    }

    private fun confirmDeleteCategory(category: Category) {
        AlertDialog.Builder(this, R.style.Theme_TVLauncher_AboutDialog)
            .setTitle(R.string.category_delete_title)
            .setMessage(getString(R.string.category_delete_message, category.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                layoutPrefs.deleteCategory(category.id)
                lastFocusedKey = AppSlotAdapter.KEY_PLUS
                submitSlots(AppSlotAdapter.KEY_PLUS)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---- Customize (icon size, sort, new category) ----

    private fun showCustomizeDialog() {
        val options = arrayOf(
            getString(R.string.customize_icon_size),
            getString(R.string.customize_new_category),
            getString(R.string.customize_sort_az),
            getString(R.string.customize_add_remove_apps)
        )
        AlertDialog.Builder(this, R.style.Theme_TVLauncher_AboutDialog)
            .setTitle(R.string.customize_title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showIconSizeDialog()
                    1 -> promptNewCategory(null)
                    2 -> sortAppsAlphabetically()
                    3 -> openAppSelection()
                    else -> Unit
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showIconSizeDialog() {
        val labels = arrayOf(
            getString(R.string.icon_size_very_small),
            getString(R.string.icon_size_small),
            getString(R.string.icon_size_medium),
            getString(R.string.icon_size_large),
            getString(R.string.icon_size_very_large)
        )
        val current = layoutPrefs.getColumns()
        var checked = ICON_SIZE_COLUMNS.indexOf(current)
        if (checked < 0) {
            checked = ICON_SIZE_COLUMNS.indexOf(LayoutPrefs.DEFAULT_COLUMNS)
        }
        AlertDialog.Builder(this, R.style.Theme_TVLauncher_AboutDialog)
            .setTitle(R.string.customize_icon_size)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                dialog.dismiss()
                applyIconColumns(ICON_SIZE_COLUMNS[which])
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Changes the icon size: fewer columns = bigger icons. Applies immediately. */
    private fun applyIconColumns(columns: Int) {
        layoutPrefs.setColumns(columns)
        val applied = layoutPrefs.getColumns()
        // Icons are rendered at a fixed pixel size, so drop the old ones and redraw.
        appSlotAdapter.currentList.forEach { it.appInfo?.clearIcon() }
        appManager.invalidateAppListCache()
        gridLayoutManager.spanCount = applied
        appSlotAdapter.sizing = buildSizing(applied)
        restoreFocus(lastFocusedKey)
    }

    private fun sortAppsAlphabetically() {
        val sorted = infoById.values
            .sortedBy { it.getDisplayName().lowercase() }
            .map { AppIdentifier.encode(it) }
        appManager.saveSelectedApps(sorted)
        Toast.makeText(this, R.string.sorted_az_done, Toast.LENGTH_SHORT).show()
        submitSlots(lastFocusedKey)
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

    private fun showAboutDialog() {
        val versionName = getVersionName()
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_about, null)

        val versionText = dialogView.findViewById<TextView>(R.id.aboutVersionText)
        versionText.text = "v$versionName (FOSS)"

        val cardHomeOverride = dialogView.findViewById<LinearLayout>(R.id.cardHomeOverride)
        val homeOverrideSwitch = dialogView.findViewById<SwitchCompat>(R.id.homeOverrideSwitch)
        val isOverrideConfigured = TvHomeOverrideService.isHomeOverrideEnabled(this)
        val isServiceActive = TvHomeOverrideService.isAccessibilityServiceEnabled(this)
        homeOverrideSwitch.isChecked = isOverrideConfigured && isServiceActive

        cardHomeOverride.setOnClickListener {
            homeOverrideSwitch.toggle()
        }

        homeOverrideSwitch.setOnCheckedChangeListener { _, isChecked ->
            TvHomeOverrideService.setHomeOverrideEnabled(this, isChecked)
            if (isChecked) {
                if (!TvHomeOverrideService.isAccessibilityServiceEnabled(this)) {
                    Toast.makeText(this, R.string.enable_accessibility_prompt, Toast.LENGTH_LONG).show()
                    openAccessibilitySettings()
                }
            }
        }

        // 2. Stock Launcher RAM killer switch
        val cardSuppressRam = dialogView.findViewById<LinearLayout>(R.id.cardSuppressRam)
        val suppressRamSwitch = dialogView.findViewById<SwitchCompat>(R.id.suppressStockRamSwitch)
        suppressRamSwitch.isChecked = TvHomeOverrideService.isSuppressStockLauncherEnabled(this)

        cardSuppressRam.setOnClickListener {
            suppressRamSwitch.toggle()
        }

        suppressRamSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !TvHomeOverrideService.isSuppressStockLauncherEnabled(this)) {
                // Confirm before enabling the RAM killer: it stops background
                // processes of pre-installed TV launchers to free memory.
                AlertDialog.Builder(this, R.style.Theme_TVLauncher_AboutDialog)
                    .setTitle(R.string.suppress_stock_ram)
                    .setMessage(R.string.suppress_stock_ram_confirm)
                    .setPositiveButton(R.string.enable) { _, _ ->
                        TvHomeOverrideService.setSuppressStockLauncherEnabled(this, true)
                        RamOptimizationHelper.suppressStockLauncherRam(applicationContext)
                    }
                    .setNegativeButton(R.string.cancel) { _, _ ->
                        suppressRamSwitch.isChecked = false
                    }
                    .show()
            } else {
                TvHomeOverrideService.setSuppressStockLauncherEnabled(this, isChecked)
            }
        }

        // 3. Auto update switch
        val cardAutoUpdate = dialogView.findViewById<LinearLayout>(R.id.cardAutoUpdate)
        val autoUpdateSwitch = dialogView.findViewById<SwitchCompat>(R.id.autoUpdateSwitch)

        if (!BuildConfig.UPDATES_ENABLED) {
            // F-Droid build: updates are not available here, hide the entire card.
            cardAutoUpdate.visibility = View.GONE
        } else {
            autoUpdateSwitch.isChecked = UpdaterBridge.isAutoUpdateEnabled(this)

            cardAutoUpdate.setOnClickListener {
                autoUpdateSwitch.toggle()
            }

            autoUpdateSwitch.setOnCheckedChangeListener { _, isChecked ->
                UpdaterBridge.setAutoUpdateEnabled(this, isChecked)
                val hint = if (isChecked) R.string.update_auto_on else R.string.update_auto_off
                Toast.makeText(this, hint, Toast.LENGTH_SHORT).show()
            }
        }

        val builder = AlertDialog.Builder(this, R.style.Theme_TVLauncher_AboutDialog)
            .setTitle(R.string.about_title)
            .setView(dialogView)
            .setPositiveButton(R.string.about_open_github) { _, _ ->
                openUrl(getString(R.string.github_repo_url))
            }
            .setNegativeButton(R.string.about_close, null)
        if (BuildConfig.UPDATES_ENABLED) {
            // F-Droid build: no in-app update checking.
            builder.setNeutralButton(R.string.update_check_now) { _, _ ->
                checkForUpdatesManually()
            }
        }
        builder.show()
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

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
        }
    }

    private fun calculateSlotSizePx(columns: Int): Int {
        val displayMetrics = resources.displayMetrics
        val screenWidthPx = displayMetrics.widthPixels
        val density = displayMetrics.density

        val sideMarginPx = (24 * density).toInt() * 2
        val recyclerPaddingPx = (8 * density).toInt() * 2
        val itemMarginPx = (8 * density).toInt()
        val totalItemMarginsPx = itemMarginPx * 2 * columns

        val availablePx = screenWidthPx - sideMarginPx - recyclerPaddingPx - totalItemMarginsPx
        val rawSize = availablePx / columns
        // 5 columns gives the original tile (up to 130dp). Fewer columns allow bigger tiles,
        // more columns smaller ones.
        val maxSize = (650f / columns * density).toInt()
        val minSize = minOf((72 * density).toInt(), maxSize)
        return rawSize.coerceIn(minSize, maxSize)
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

    private fun loadAppSlots() {
        val infos = appManager.getSelectedAppInfos()
        infoById = infos.associateBy { AppIdentifier.encode(it) }
        layoutPrefs.pruneAssignments(infoById.keys)
        submitSlots(lastFocusedKey)
    }

    /**
     * Builds the grid from the saved order: apps without a category first, followed by the "+"
     * tile, then each category as a header with its apps. Focus returns to [focusKey].
     */
    private fun submitSlots(focusKey: String?) {
        val categories = layoutPrefs.getCategories()
        val order = appManager.getSelectedApps().filter { infoById.containsKey(it) }
        val grouped = HomeLayout.group(order, categories, layoutPrefs.getAssignments())

        val slots = mutableListOf<AppSlotAdapter.AppSlot>()
        grouped.uncategorized.forEach { id ->
            infoById[id]?.let { slots.add(AppSlotAdapter.AppSlot(it, false)) }
        }

        // Always show "+" so user can add or change apps
        slots.add(AppSlotAdapter.AppSlot(null, true))

        categories.forEach { category ->
            val ids = grouped.byCategory[category.id].orEmpty()
            slots.add(AppSlotAdapter.AppSlot(null, false, category, ids.size))
            ids.forEach { id ->
                infoById[id]?.let { slots.add(AppSlotAdapter.AppSlot(it, false)) }
            }
        }

        appSlotAdapter.submitList(slots) {
            restoreFocus(focusKey)
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
                loadAppSlots()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun openAppSelection() {
        startActivity(Intent(this, AppSelectionActivity::class.java))
    }

    override fun onResume() {
        super.onResume()
        loadAppSlots()
        applyWallpaper(force = false)
        RamOptimizationHelper.suppressStockLauncherRam(applicationContext)
    }

    override fun onPause() {
        super.onPause()
        // Leaving the home screen (or opening a dialog elsewhere) ends reorder mode.
        stopReorder()
    }

    override fun onStop() {
        super.onStop()
        appManager.invalidateAppListCache()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(refreshReceiver)
        } catch (_: Exception) {
        }
    }

    companion object {
        /** Icons per row for each icon size option, from the smallest to the biggest icons. */
        private val ICON_SIZE_COLUMNS = intArrayOf(7, 6, LayoutPrefs.DEFAULT_COLUMNS, 4, 3)
    }
}
