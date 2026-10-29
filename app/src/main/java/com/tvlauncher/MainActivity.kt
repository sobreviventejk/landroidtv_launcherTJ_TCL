package com.tvlauncher

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import java.util.Calendar

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
    private lateinit var inputsList: RecyclerView
    private lateinit var emptyHint: TextView
    private lateinit var settingsButton: ImageButton
    private lateinit var wallpaperButton: ImageButton
    private lateinit var customizeButton: ImageButton
    private lateinit var wallpaperImageView: ImageView
    private lateinit var wallpaperDimOverlay: View
    private lateinit var appManager: AppManager
    private lateinit var layoutPrefs: LayoutPrefs
    private lateinit var appSlotAdapter: AppSlotAdapter
    private lateinit var allAppsAdapter: AllAppsAdapter
    private lateinit var topAdapter: AppSlotAdapter
    private lateinit var inputsAdapter: InputsAdapter
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

    private var inputEntries: List<TvInputEntry> = emptyList()

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

    private val wallpaperTicker = object : Runnable {
        override fun run() {
            if (WallpaperManager.isRefreshDue(this@MainActivity)) {
                applyWallpaper(force = false)
            }
            applyGreeting()
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
        inputsList = findViewById(R.id.inputsList)
        emptyHint = findViewById(R.id.emptyHint)
        settingsButton = findViewById(R.id.settingsButton)
        wallpaperButton = findViewById(R.id.wallpaperButton)
        customizeButton = findViewById(R.id.customizeButton)
        wallpaperImageView = findViewById(R.id.wallpaperImageView)
        wallpaperDimOverlay = findViewById(R.id.wallpaperDimOverlay)

        appManager = AppManager(this)
        layoutPrefs = LayoutPrefs(this)
        pinPrefs = PinPrefs(this)
        setupRecyclerView()
        setupAllApps()
        setupTopApps()
        setupInputs()
        setupClickListeners()
        applyGreeting()
        loadHomeInfos()
        buildTabs()
        val saved = layoutPrefs.getLastTab()
        val first = if (saved != null && saved != TAB_NEW && !isLockedNow(saved) && tabViews.containsKey(saved)) saved else TAB_HOME
        selectTab(first, requestFocus = true)
        applyWallpaper()

        ContextCompat.registerReceiver(
            this,
            refreshReceiver,
            android.content.IntentFilter(LauncherBroadcast.ACTION_REFRESH_HOME),
            ContextCompat.RECEIVER_NOT_EXPORTED
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
            if (currentTab == TAB_ALL || currentTab == TAB_INPUTS) {
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
        appSlots.setHasFixedSize(true)
        appSlots.setItemViewCacheSize(0)
        appSlots.recycledViewPool.setMaxRecycledViews(AppSlotAdapter.VIEW_TYPE_APP, 12)
        // Update tiles in place so the remote's focus is not lost when the list changes.
        (appSlots.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
    }

    private fun setupAllApps() {
        allAppsAdapter = AllAppsAdapter(
            onClick = { row -> onAllAppsRowClick(row) },
            onLongClick = { row -> showAllAppActions(row) },
            iconSizePx = { appSlotAdapter.sizing.iconPx }
        )
        allAppsList.layoutManager = GridLayoutManager(this, ALL_APPS_COLUMNS)
        allAppsList.adapter = allAppsAdapter
        (allAppsList.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
    }

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

    private fun setupInputs() {
        inputsAdapter = InputsAdapter { entry ->
            if (!TvInputHelper.launch(this, entry)) {
                Toast.makeText(this, R.string.inputs_failed, Toast.LENGTH_SHORT).show()
            }
        }
        inputsList.layoutManager = GridLayoutManager(this, ALL_APPS_COLUMNS)
        inputsList.adapter = inputsAdapter
    }

    /** Shows the most used apps on the first tab only, and only when there are any. */
    private fun refreshTopApps() {
        val show = currentTab == TAB_HOME && layoutPrefs.isMostUsedEnabled()
        val ids = if (show) UsageTracker.top(this, infoById.keys, TOP_APPS_LIMIT) else emptyList()
        val slots = ids.mapNotNull { id ->
            infoById[id]?.let { AppSlotAdapter.AppSlot(it, false) }
        }
        topAdapter.submitList(slots)
        val visibility = if (slots.isEmpty()) View.GONE else View.VISIBLE
        topApps.visibility = visibility
        topAppsLabel.visibility = visibility
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
    }

    // ---- Greeting ----

    private fun applyGreeting() {
        val level = layoutPrefs.getGreetingSize()
        if (level == LayoutPrefs.GREETING_HIDDEN) {
            greetingText.visibility = View.GONE
            lastGreeting = null
            return
        }
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val text = GreetingText.compose(
            layoutPrefs.getGreeting(),
            hour,
            System.currentTimeMillis(),
            layoutPrefs.isGreetingByTime()
        )
        val signature = "$level|$text"
        if (signature == lastGreeting && greetingText.visibility == View.VISIBLE) return
        lastGreeting = signature

        greetingText.visibility = View.VISIBLE
        val bodySp = LayoutPrefs.greetingBodySp(level)
        val titleSp = LayoutPrefs.greetingTitleSp(level)
        val metrics = resources.displayMetrics
        val titlePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, titleSp, metrics).toInt()

        val firstBreak = text.indexOf('\n')
        val titleEnd = if (firstBreak < 0) text.length else firstBreak

        val styled = SpannableStringBuilder(text)
        if (titleEnd > 0) {
            styled.setSpan(AbsoluteSizeSpan(titlePx, false), 0, titleEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            styled.setSpan(StyleSpan(Typeface.BOLD), 0, titleEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            styled.setSpan(
                ForegroundColorSpan(ContextCompat.getColor(this, R.color.text_primary)),
                0,
                titleEnd,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        greetingText.setTextSize(TypedValue.COMPLEX_UNIT_SP, bodySp)
        greetingText.text = styled
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
        inputEntries = TvInputHelper.listInputs(this)
        addTab(TAB_HOME, getString(R.string.tab_home))
        layoutPrefs.getCategories().forEach { addTab(it.id, tabLabel(it)) }
        if (inputEntries.isNotEmpty()) {
            addTab(TAB_INPUTS, getString(R.string.tab_inputs))
        }
        addTab(TAB_NEW, "+ " + getString(R.string.tab_new_category))
        addTab(TAB_ALL, getString(R.string.tab_all_apps))
        updateTabStyles()
    }

    /** Name shown on a category tab; protected categories get a lock. */
    private fun tabLabel(category: Category): String {
        return if (pinPrefs.isLocked(category.id)) "\uD83D\uDD12 " + category.name else category.name
    }

    private fun isLockedNow(key: String): Boolean {
        return pinPrefs.isLocked(key) && key !in unlockedTabs
    }

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
            layoutPrefs.getCategories().firstOrNull { it.id == key }?.let { showCategoryMenu(it) }
            true
        }
        // Like Google TV: moving the focus over a tab already shows its apps.
        tab.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && key != TAB_NEW && key != currentTab) {
                selectTab(key, requestFocus = false)
            }
        }
        tabViews[key] = tab
        tabContainer.addView(tab)
    }

    private fun updateTabStyles() {
        val dark = ContextCompat.getColor(this, R.color.bg_main)
        val light = ContextCompat.getColor(this, R.color.text_primary)
        tabViews.forEach { (key, view) ->
            val selected = key == currentTab
            view.isSelected = selected
            view.setTextColor(if (selected) dark else light)
        }
    }

    private fun onTabClicked(key: String) {
        when {
            key == TAB_NEW -> promptNewCategory()
            isLockedNow(key) -> askPin(R.string.pin_enter_title) {
                unlockedTabs.add(key)
                selectTab(key, requestFocus = true)
            }
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
            TAB_INPUTS -> inputsList
            else -> appSlots
        }
        list.getChildAt(0)?.requestFocus()
    }

    private fun selectTab(key: String, requestFocus: Boolean) {
        currentTab = key
        if (key != TAB_ALL && key != TAB_INPUTS) previousContentTab = key
        layoutPrefs.setLastTab(key)
        updateTabStyles()
        tabViews[key]?.let { tab ->
            tabScroll.post { tabScroll.smoothScrollTo((tab.left - tabScroll.width / 3).coerceAtLeast(0), 0) }
        }
        refreshTopApps()
        appSlots.visibility = View.GONE
        allAppsList.visibility = View.GONE
        inputsList.visibility = View.GONE
        emptyHint.visibility = View.GONE
        when (key) {
            TAB_ALL -> {
                allAppsList.visibility = View.VISIBLE
                refreshAllApps(requestFocus)
            }
            TAB_INPUTS -> {
                inputsList.visibility = View.VISIBLE
                inputsAdapter.submit(inputEntries)
                if (requestFocus) {
                    inputsList.post { inputsList.getChildAt(0)?.requestFocus() }
                }
            }
            else -> {
                appSlots.visibility = View.VISIBLE
                submitSlots(null, requestFocus)
            }
        }
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
            TAB_INPUTS -> Unit
            else -> submitSlots(focusKey, requestFocus)
        }
    }

    /** Shows the apps of the current tab followed by the "+" tile. */
    private fun submitSlots(focusKey: String?, requestFocus: Boolean) {
        if (isLockedNow(currentTab)) {
            appSlotAdapter.submitList(emptyList())
            emptyHint.text = getString(R.string.category_locked_hint)
            emptyHint.visibility = View.VISIBLE
            return
        }
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
            infoById[id]?.let { slots.add(AppSlotAdapter.AppSlot(it, false)) }
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
        }

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
        options.add(getString(R.string.customize_new_category))
        actions.add { promptNewCategory() }
        if (pinPrefs.isLocked(category.id)) {
            options.add(getString(R.string.category_unlock))
            actions.add {
                pinPrefs.setLocked(category.id, false)
                Toast.makeText(this, R.string.category_unlocked_toast, Toast.LENGTH_SHORT).show()
                reopenTab(category.id)
            }
        } else {
            options.add(getString(R.string.category_lock))
            actions.add { lockCategory(category) }
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
                val doDelete = {
                    pinPrefs.setLocked(category.id, false)
                    layoutPrefs.deleteCategory(category.id)
                    reopenTab(TAB_HOME)
                }
                if (pinPrefs.isLocked(category.id)) {
                    askPin(R.string.pin_enter_title) { doDelete() }
                } else {
                    doDelete()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---- All apps tab ----

    private fun refreshAllApps(requestFocus: Boolean) {
        appManager.loadInstalledAppsAsync(false) { apps ->
            if (isFinishing || isDestroyed) return@loadInstalledAppsAsync
            val added = appManager.getSelectedApps().toSet()
            val names = layoutPrefs.getCategories().associate { it.id to it.name }
            val assignments = layoutPrefs.getAssignments()
            val homeLabel = getString(R.string.tab_home)
            val notAdded = getString(R.string.all_apps_not_added)

            val rows = apps
                .sortedBy { it.getDisplayName().lowercase() }
                .map { app ->
                    val id = AppIdentifier.encode(app)
                    val isAdded = id in added
                    val status = if (isAdded) {
                        getString(R.string.all_apps_added_in, assignments[id]?.let { names[it] } ?: homeLabel)
                    } else {
                        notAdded
                    }
                    AllAppsAdapter.Row(app, id, isAdded, status)
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

        AlertDialog.Builder(this, R.style.Theme_TVLauncher_AboutDialog)
            .setTitle(row.app.getDisplayName())
            .setItems(options.toTypedArray()) { _, which ->
                actions.getOrNull(which)?.invoke()
            }
            .setNegativeButton(R.string.cancel, null)
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
        val input = pinInput()
        val dialog = AlertDialog.Builder(this, R.style.Theme_TVLauncher_AboutDialog)
            .setTitle(titleRes)
            .setView(input)
            .setPositiveButton(R.string.ok) { _, _ ->
                if (pinPrefs.verify(input.text.toString())) {
                    onOk()
                } else {
                    Toast.makeText(this, R.string.pin_wrong, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.show()
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
        AlertDialog.Builder(this, R.style.Theme_TVLauncher_AboutDialog)
            .setTitle(R.string.pin_create_title)
            .setView(first)
            .setPositiveButton(R.string.ok) { _, _ ->
                val pin = first.text.toString()
                if (!PinPrefs.isValidPin(pin)) {
                    Toast.makeText(this, R.string.pin_invalid, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val second = pinInput()
                AlertDialog.Builder(this, R.style.Theme_TVLauncher_AboutDialog)
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
                    .show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
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

        options.add(getString(R.string.customize_icon_size))
        actions.add { showIconSizeDialog() }
        options.add(getString(R.string.customize_greeting_size))
        actions.add { showGreetingSizeDialog() }
        options.add(getString(R.string.customize_greeting_text))
        actions.add { showGreetingTextDialog() }
        options.add(stateLabel(R.string.customize_greeting_by_time, layoutPrefs.isGreetingByTime()))
        actions.add {
            layoutPrefs.setGreetingByTime(!layoutPrefs.isGreetingByTime())
            lastGreeting = null
            applyGreeting()
        }
        options.add(stateLabel(R.string.customize_most_used, layoutPrefs.isMostUsedEnabled()))
        actions.add {
            layoutPrefs.setMostUsedEnabled(!layoutPrefs.isMostUsedEnabled())
            refreshTopApps()
        }
        options.add(getString(R.string.customize_new_category))
        actions.add { promptNewCategory() }
        options.add(getString(R.string.customize_wallpaper))
        actions.add { showWallpaperDialog() }
        options.add(getString(R.string.customize_accessibility))
        actions.add { showAboutDialog() }
        options.add(getString(R.string.accessibility_adb_title))
        actions.add { showAdbHelpDialog() }
        options.add(getString(R.string.customize_sort_az))
        actions.add { sortAppsAlphabetically() }
        options.add(getString(R.string.customize_add_remove_apps))
        actions.add { startActivity(Intent(this, AppSelectionActivity::class.java)) }
        options.add(getString(R.string.customize_free_memory))
        actions.add { freeMemory() }
        options.add(getString(R.string.customize_backup_export))
        actions.add { startExport() }
        options.add(getString(R.string.customize_backup_import))
        actions.add { startImport() }
        if (pinPrefs.hasPin()) {
            options.add(getString(R.string.pin_change))
            actions.add { changePin() }
        }
        options.add(getString(R.string.customize_last_error))
        actions.add { showLastErrorDialog() }

        AlertDialog.Builder(this, R.style.Theme_TVLauncher_AboutDialog)
            .setTitle(R.string.customize_title)
            .setItems(options.toTypedArray()) { _, which ->
                actions.getOrNull(which)?.invoke()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showIconSizeDialog() {
        val labels = arrayOf(
            getString(R.string.icon_size_tiny),
            getString(R.string.icon_size_very_small),
            getString(R.string.icon_size_small),
            getString(R.string.icon_size_medium_small),
            getString(R.string.icon_size_medium),
            getString(R.string.icon_size_medium_large),
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
        refreshContent(lastFocusedKey, requestFocus = false)
        if (currentTab != TAB_ALL) restoreFocus(lastFocusedKey)
    }

    private fun sortAppsAlphabetically() {
        val sorted = infoById.values
            .sortedBy { it.getDisplayName().lowercase() }
            .map { AppIdentifier.encode(it) }
        appManager.saveSelectedApps(sorted)
        Toast.makeText(this, R.string.sorted_az_done, Toast.LENGTH_SHORT).show()
        refreshContent(null, requestFocus = false)
    }

    private fun showLastErrorDialog() {
        val text = CrashLog.read(this) ?: getString(R.string.last_error_none)
        AlertDialog.Builder(this, R.style.Theme_TVLauncher_AboutDialog)
            .setTitle(R.string.last_error_title)
            .setMessage(text)
            .setPositiveButton(R.string.close, null)
            .setNeutralButton(R.string.last_error_clear) { _, _ -> CrashLog.clear(this) }
            .show()
    }

    private fun showAdbHelpDialog() {
        AlertDialog.Builder(this, R.style.Theme_TVLauncher_AboutDialog)
            .setTitle(R.string.accessibility_adb_title)
            .setMessage(getString(R.string.accessibility_adb_message, AccessibilityEnabler.ADB_COMMAND))
            .setPositiveButton(R.string.close, null)
            .show()
    }

    // ---- Home key accessibility service ----

    /**
     * Makes sure the Home key service is on. If the app was allowed to write secure settings
     * (see [AccessibilityEnabler]) it turns the service on by itself; otherwise it asks once per
     * start of the app to open the Accessibility settings.
     */
    private fun ensureHomeOverrideService() {
        if (!TvHomeOverrideService.isHomeOverrideEnabled(this)) return
        if (TvHomeOverrideService.isAccessibilityServiceEnabled(this)) return
        if (AccessibilityEnabler.tryEnable(this)) {
            Toast.makeText(this, R.string.accessibility_auto_enabled, Toast.LENGTH_SHORT).show()
            return
        }
        if (accessibilityPrompted) return
        accessibilityPrompted = true
        AlertDialog.Builder(this, R.style.Theme_TVLauncher_AboutDialog)
            .setTitle(R.string.accessibility_setup_title)
            .setMessage(R.string.accessibility_setup_message)
            .setPositiveButton(R.string.accessibility_setup_open) { _, _ -> openAccessibilitySettings() }
            .setNegativeButton(R.string.accessibility_setup_later, null)
            .show()
    }

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
        fun updateFolderText() {
            folderButton.text = if (selectedFolderUri.isEmpty()) {
                getString(R.string.wallpaper_folder_choose)
            } else {
                getString(R.string.wallpaper_folder_selected, WallpaperManager.labelForTree(selectedFolderUri))
            }
        }
        updateFolderText()
        folderButton.setOnClickListener {
            folderPickCallback = { uri ->
                selectedFolderUri = uri.toString()
                updateFolderText()
            }
            TvHomeOverrideService.pauseOverrideFor(30000)
            try {
                folderPicker.launch(null)
            } catch (_: ActivityNotFoundException) {
                folderPickCallback = null
                Toast.makeText(this, R.string.wallpaper_folder_no_picker, Toast.LENGTH_LONG).show()
            } catch (_: Exception) {
                folderPickCallback = null
                Toast.makeText(this, R.string.wallpaper_folder_no_picker, Toast.LENGTH_LONG).show()
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

        fun saveWallpaperSettings() {
            WallpaperManager.setWallpaperMode(this, currentMode)
            WallpaperManager.setCategory(this, categories[selectedCategoryIndex].first)
            WallpaperManager.setInterval(this, intervals[selectedIntervalIndex].first)
            WallpaperManager.setCustomUrl(this, customUrlEdit.text.toString().trim())
            WallpaperManager.setFolderUri(this, selectedFolderUri)
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

        // Safe area on both sides (56dp each) plus the grid padding and the margin of each tile.
        val sideMarginPx = (56 * density).toInt() * 2
        val recyclerPaddingPx = (6 * density).toInt() * 2
        val itemMarginPx = (8 * density).toInt()
        val totalItemMarginsPx = itemMarginPx * 2 * columns

        val availablePx = screenWidthPx - sideMarginPx - recyclerPaddingPx - totalItemMarginsPx
        val rawSize = availablePx / columns
        // 5 columns gives the original tile (up to 130dp). Fewer columns allow bigger tiles,
        // more columns smaller ones.
        val maxSize = (650f / columns * density).toInt()
        val minSize = minOf((60 * density).toInt(), maxSize)
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
        ensureHomeOverrideService()
    }

    override fun onPause() {
        super.onPause()
        // Leaving the home screen (or opening a dialog elsewhere) ends reorder mode.
        stopReorder()
        mainHandler.removeCallbacks(wallpaperTicker)
    }

    override fun onStop() {
        super.onStop()
        // Protected categories ask for the PIN again after the launcher has been left.
        unlockedTabs.clear()
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
        private const val TAB_INPUTS = "tab:inputs"
        private const val TOP_APPS_LIMIT = 6

        private const val ALL_APPS_COLUMNS = 2
        private const val WALLPAPER_CHECK_MS = 15_000L

        /** Asked once per start of the app, so a TV that keeps switching the service off is not nagged. */
        @Volatile
        private var accessibilityPrompted = false

        /** Icons per row for each icon size option, from the smallest to the biggest icons. */
        private val ICON_SIZE_COLUMNS = intArrayOf(10, 8, 7, 6, LayoutPrefs.DEFAULT_COLUMNS, 4, 3, 2)
    }
}
