package com.tvlauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * High-performance, zero-dependency Wallpaper Manager with downsampling for low-RAM devices.
 * Uses resilient multi-tier fetching (Wallhaven, Bing Daily UHD, Picsum, Reddit) with offline disk caching.
 */
object WallpaperManager {

    const val MODE_SOLID = "solid"
    const val MODE_ONLINE = "online"
    const val MODE_CUSTOM = "custom"
    const val MODE_FOLDER = "folder"

    // Backwards compatibility alias
    const val MODE_REDDIT = "online"

    const val CATEGORY_GENERAL = "wallpaper"
    const val CATEGORY_NATURE = "nature"
    const val CATEGORY_CARS = "cars"
    const val CATEGORY_ANIME = "anime"
    const val CATEGORY_SPACE = "space"
    const val CATEGORY_ARCHITECTURE = "city"

    const val INTERVAL_1M = 60 * 1000L
    const val INTERVAL_5M = 5 * 60 * 1000L
    const val INTERVAL_15M = 15 * 60 * 1000L
    const val INTERVAL_30M = 30 * 60 * 1000L
    const val INTERVAL_1H = 60 * 60 * 1000L
    const val INTERVAL_6H = 6 * 60 * 60 * 1000L
    const val INTERVAL_24H = 24 * 60 * 60 * 1000L

    private const val PREFS_NAME = "wallpaper_settings"
    private const val PREF_MODE = "wallpaper_mode"
    private const val PREF_CATEGORY = "wallpaper_category"
    private const val PREF_INTERVAL = "wallpaper_interval"
    private const val PREF_CUSTOM_URL = "wallpaper_custom_url"
    private const val PREF_DIM_OVERLAY = "wallpaper_dim_overlay"
    private const val PREF_LAST_FETCH_TIME = "wallpaper_last_fetch_time"
    private const val PREF_FOLDER_URI = "wallpaper_folder_uri"
    private const val PREF_LOCAL_FOLDER = "wallpaper_local_folder"
    private const val PREF_RECENT_PICKS = "wallpaper_recent_folder_picks"
    private const val CACHE_FILE_NAME = "current_wallpaper.jpg"
    private const val LOCAL_FOLDER_NAME = "wallpaper_picked"
    private const val MAX_REDIRECTS = 5
    private const val MAX_FOLDER_DEPTH = 3
    private const val MAX_FOLDER_IMAGES = 3000
    private const val MAX_LOCAL_IMAGES = 60
    private const val MAX_LOCAL_TOTAL_BYTES = 200L * 1024 * 1024
    private const val MAX_RECENT_PICKS = 40

    /** Refuses absurdly large downloads, which would only waste the TV's storage and RAM. */
    private const val MAX_IMAGE_BYTES = 25L * 1024L * 1024L

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun getWallpaperMode(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val mode = prefs.getString(PREF_MODE, MODE_SOLID) ?: MODE_SOLID
        return if (mode == "reddit") MODE_ONLINE else mode
    }

    fun setWallpaperMode(context: Context, mode: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val normalized = if (mode == "reddit") MODE_ONLINE else mode
        prefs.edit().putString(PREF_MODE, normalized).apply()
    }

    fun getCategory(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(PREF_CATEGORY, CATEGORY_NATURE) ?: CATEGORY_NATURE
    }

    fun setCategory(context: Context, category: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(PREF_CATEGORY, category).apply()
    }

    fun getInterval(context: Context): Long {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getLong(PREF_INTERVAL, INTERVAL_1H)
    }

    fun setInterval(context: Context, interval: Long) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putLong(PREF_INTERVAL, interval).apply()
    }

    fun getCustomUrl(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(PREF_CUSTOM_URL, "") ?: ""
    }

    fun setCustomUrl(context: Context, url: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(PREF_CUSTOM_URL, url).apply()
    }

    /** Folder chosen with the system folder picker (tree URI), or "" when none. */
    fun getFolderUri(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(PREF_FOLDER_URI, "") ?: ""
    }

    fun setFolderUri(context: Context, uri: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(PREF_FOLDER_URI, uri).apply()
    }

    /** Short readable name of the chosen folder, taken from its document id. */
    fun getFolderLabel(context: Context): String = labelForTree(getFolderUri(context))

    /**
     * Fallback used when the TV has no system folder picker: the user selects the photos with any
     * gallery/file app and the launcher keeps its own copy of them, so it can still rotate them.
     */
    fun getLocalFolder(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(PREF_LOCAL_FOLDER, "") ?: ""
    }

    fun localFolderImageCount(context: Context): Int {
        val dir = File(getLocalFolder(context).ifEmpty { "" })
        if (!dir.isDirectory) return 0
        return dir.listFiles()?.count { it.isFile && it.length() > 1024 } ?: 0
    }

    fun hasAnyImageSource(context: Context): Boolean =
        getFolderUri(context).isNotEmpty() || localFolderImageCount(context) > 0

    /**
     * Copies the picked pictures into the app's private storage (at most [MAX_LOCAL_IMAGES]) and
     * turns the folder wallpaper mode on. Returns how many were saved.
     */
    fun savePickedImages(context: Context, uris: List<Uri>): Int {
        val dir = File(context.filesDir, LOCAL_FOLDER_NAME)
        try {
            dir.mkdirs()
            dir.listFiles()?.forEach { it.delete() }
            var saved = 0
            var budget = MAX_LOCAL_TOTAL_BYTES
            uris.take(MAX_LOCAL_IMAGES).forEach { uri ->
                if (budget <= 1024L) return@forEach
                try {
                    val input = context.contentResolver.openInputStream(uri) ?: return@forEach
                    val dest = File(dir, "picked_${saved}_${System.currentTimeMillis()}.img")
                    val limit = minOf(MAX_IMAGE_BYTES, budget)
                    val ok = try {
                        FileOutputStream(dest).use { output ->
                            val buffer = ByteArray(16 * 1024)
                            var total = 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                total += read
                                if (total > limit) break
                                output.write(buffer, 0, read)
                            }
                            total in 1024..limit
                        }
                    } finally {
                        runCatching { input.close() }
                    }
                    if (ok && isDecodableImage(dest)) {
                        budget -= dest.length()
                        saved++
                    } else {
                        dest.delete()
                    }
                } catch (_: Exception) {
                }
            }
            if (saved > 0) {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit()
                    .putString(PREF_LOCAL_FOLDER, dir.absolutePath)
                    .putString(PREF_FOLDER_URI, "")
                    .putString(PREF_MODE, MODE_FOLDER)
                    .putLong(PREF_LAST_FETCH_TIME, 0L)
                    .apply()
            }
            return saved
        } catch (_: Exception) {
            return 0
        }
    }

    fun labelForTree(rawUri: String): String {
        if (rawUri.isEmpty()) return ""
        return try {
            val docId = DocumentsContract.getTreeDocumentId(Uri.parse(rawUri))
            docId.substringAfter(':', docId).ifEmpty { docId }
        } catch (_: Exception) {
            rawUri
        }
    }

    /** True when a wallpaper mode with automatic changes is on and the interval has passed. */
    fun isRefreshDue(context: Context): Boolean {
        if (getWallpaperMode(context) == MODE_SOLID) return false
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastFetch = prefs.getLong(PREF_LAST_FETCH_TIME, 0L)
        return System.currentTimeMillis() - lastFetch >= getInterval(context)
    }

    fun isDimOverlayEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(PREF_DIM_OVERLAY, true)
    }

    fun setDimOverlayEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(PREF_DIM_OVERLAY, enabled).apply()
    }

    fun loadCachedWallpaper(context: Context, targetWidth: Int, targetHeight: Int): Drawable? {
        val file = File(context.cacheDir, CACHE_FILE_NAME)
        if (!file.exists() || file.length() == 0L) return null
        return decodeSampledBitmapFromFile(file.absolutePath, targetWidth, targetHeight)?.let {
            BitmapDrawable(context.resources, it)
        }
    }

    fun refreshWallpaperIfNeeded(
        context: Context,
        targetWidth: Int,
        targetHeight: Int,
        force: Boolean = false,
        onComplete: (Boolean, Drawable?) -> Unit = { _, _ -> }
    ) {
        val mode = getWallpaperMode(context)
        if (mode == MODE_SOLID) {
            onComplete(true, null)
            return
        }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastFetch = prefs.getLong(PREF_LAST_FETCH_TIME, 0L)
        val interval = getInterval(context)
        val now = System.currentTimeMillis()

        val cacheFile = File(context.cacheDir, CACHE_FILE_NAME)
        if (!force && cacheFile.exists() && (now - lastFetch < interval)) {
            val cached = loadCachedWallpaper(context, targetWidth, targetHeight)
            if (cached != null) {
                onComplete(true, cached)
                return
            }
        }

        executor.execute {
            val category = getCategory(context)
            val success = when (mode) {
                MODE_ONLINE -> fetchAndCacheOnlineWallpaper(context, category)
                MODE_CUSTOM -> {
                    val url = getCustomUrl(context)
                    if (url.isNotEmpty()) downloadAndCacheImage(context, url) else false
                }
                MODE_FOLDER -> copyRandomImageFromFolder(context)
                else -> false
            }

            if (success) {
                prefs.edit().putLong(PREF_LAST_FETCH_TIME, now).apply()
                val drawable = loadCachedWallpaper(context, targetWidth, targetHeight)
                mainHandler.post { onComplete(true, drawable) }
            } else {
                val fallbackCached = loadCachedWallpaper(context, targetWidth, targetHeight)
                mainHandler.post { onComplete(fallbackCached != null, fallbackCached) }
            }
        }
    }

    private fun fetchAndCacheOnlineWallpaper(context: Context, category: String): Boolean {
        // Tier 1: Wallhaven HD category query
        val wallhavenUrl = fetchFromWallhaven(category)
        if (!wallhavenUrl.isNullOrEmpty() && downloadAndCacheImage(context, wallhavenUrl)) {
            return true
        }

        // Tier 2: Bing Daily Wallpaper API (Always reliable high-res 1080p landscape photography)
        val bingUrl = fetchFromBing()
        if (!bingUrl.isNullOrEmpty() && downloadAndCacheImage(context, bingUrl)) {
            return true
        }

        // Tier 3: Picsum Photos direct seed
        val picsumSeed = (System.currentTimeMillis() % 1000).toString()
        val picsumUrl = "https://picsum.photos/seed/$picsumSeed/1920/1080"
        if (downloadAndCacheImage(context, picsumUrl)) {
            return true
        }

        // Tier 4: Reddit API fallback
        val redditUrl = fetchFromRedditFallback(category)
        if (!redditUrl.isNullOrEmpty() && downloadAndCacheImage(context, redditUrl)) {
            return true
        }

        return false
    }

    private fun fetchFromWallhaven(category: String): String? {
        return try {
            val query = when (category) {
                CATEGORY_NATURE, "earthporn" -> "nature"
                CATEGORY_CARS, "carporn" -> "cars"
                CATEGORY_ANIME, "animewallpaper" -> "anime"
                CATEGORY_SPACE, "spaceporn" -> "space"
                CATEGORY_ARCHITECTURE, "cityporn" -> "city"
                else -> "wallpaper"
            }
            val endpoint = "https://wallhaven.cc/api/v1/search?q=$query&sorting=random&ratios=16x9,16x10&purity=100"
            val url = URL(endpoint)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            }

            if (conn.responseCode != 200) return null
            val responseText = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(responseText)
            val dataArray = json.getJSONArray("data")
            if (dataArray.length() == 0) return null

            val randomIndex = (0 until dataArray.length()).random()
            val item = dataArray.getJSONObject(randomIndex)
            if (item.has("path")) item.getString("path") else null
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchFromBing(): String? {
        return try {
            val endpoint = "https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=8&mkt=en-US"
            val url = URL(endpoint)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            }

            if (conn.responseCode != 200) return null
            val responseText = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(responseText)
            val images = json.getJSONArray("images")
            if (images.length() == 0) return null

            val randomImage = images.getJSONObject((0 until images.length()).random())
            val relUrl = randomImage.getString("url")
            "https://www.bing.com$relUrl"
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchFromRedditFallback(category: String): String? {
        return try {
            val sub = when (category) {
                CATEGORY_NATURE -> "earthporn"
                CATEGORY_CARS -> "carporn"
                CATEGORY_ANIME -> "animewallpaper"
                CATEGORY_SPACE -> "spaceporn"
                CATEGORY_ARCHITECTURE -> "cityporn"
                else -> "wallpaper"
            }
            val endpoint = "https://www.reddit.com/r/$sub/top.json?t=month&limit=25"
            val url = URL(endpoint)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 6000
                readTimeout = 6000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android TV) AppleWebKit/537.36")
            }

            if (conn.responseCode != 200) return null
            val responseText = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(responseText)
            val children = json.getJSONObject("data").getJSONArray("children")

            val candidates = mutableListOf<String>()
            for (i in 0 until children.length()) {
                val post = children.getJSONObject(i).getJSONObject("data")
                val postUrl = post.optString("url", "")
                val isOver18 = post.optBoolean("over_18", false)
                if (!isOver18 && (postUrl.endsWith(".jpg") || postUrl.endsWith(".jpeg") || postUrl.endsWith(".png"))) {
                    candidates.add(postUrl)
                }
            }
            if (candidates.isEmpty()) null else candidates.random()
        } catch (_: Exception) {
            null
        }
    }

    private fun downloadAndCacheImage(context: Context, imageUrl: String): Boolean {
        var currentUrl = imageUrl
        var redirects = 0
        while (redirects < MAX_REDIRECTS) {
            // Only ever fetch http(s); never file://, content://, etc.
            if (!isAllowedImageUrl(currentUrl)) return false
            val url = try {
                URL(currentUrl)
            } catch (_: Exception) {
                return false
            }
            var conn: HttpURLConnection? = null
            try {
                conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 12000
                    readTimeout = 12000
                    // Redirects are handled manually below (single mechanism),
                    // each hop is scheme-checked and capped by MAX_REDIRECTS.
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                }

                val status = conn.responseCode
                if (status == HttpURLConnection.HTTP_MOVED_TEMP ||
                    status == HttpURLConnection.HTTP_MOVED_PERM ||
                    status == HttpURLConnection.HTTP_SEE_OTHER ||
                    status == 307 || status == 308
                ) {
                    val location = conn.getHeaderField("Location") ?: return false
                    // Resolve relative redirects against the current URL
                    currentUrl = URL(url, location).toString()
                    redirects++
                    continue
                }

                if (status !in 200..299) return false

                val tempFile = File(context.cacheDir, "${CACHE_FILE_NAME}.tmp")
                var total = 0L
                conn.inputStream.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > MAX_IMAGE_BYTES) break
                            output.write(buffer, 0, read)
                        }
                    }
                }

                if (total > MAX_IMAGE_BYTES) {
                    tempFile.delete()
                    return false
                }
                if (!tempFile.exists() || tempFile.length() < 1024) {
                    tempFile.delete()
                    return false
                }
                // Reject anything that is not really a decodable image.
                if (!isDecodableImage(tempFile)) {
                    tempFile.delete()
                    return false
                }

                val destFile = File(context.cacheDir, CACHE_FILE_NAME)
                if (destFile.exists() && !destFile.delete()) {
                    tempFile.delete()
                    return false
                }
                if (!tempFile.renameTo(destFile)) {
                    tempFile.delete()
                    return false
                }
                return true
            } catch (_: Exception) {
                return false
            } finally {
                conn?.disconnect()
            }
        }
        return false
    }

    /** Picks a random image from the chosen folder (not one used recently) and caches it. */
    private fun copyRandomImageFromFolder(context: Context): Boolean {
        val raw = getFolderUri(context)
        if (raw.isEmpty()) return copyRandomLocalImage(context)
        return try {
            val treeUri = Uri.parse(raw)
            val images = listImageIds(context, raw)
            if (images.isEmpty()) return false

            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val recent = prefs.getString(PREF_RECENT_PICKS, null)
                .orEmpty()
                .split('\n')
                .filter { it.isNotEmpty() }
            val choices = images.filter { it !in recent }.ifEmpty { images }
            val picked = choices.random()

            // Keep a small history, so the same photo does not show up again right away.
            val history = (listOf(picked) + recent).take(MAX_RECENT_PICKS)
            prefs.edit().putString(PREF_RECENT_PICKS, history.joinToString("\n")).apply()

            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, picked)
            val input = context.contentResolver.openInputStream(docUri) ?: return false
            saveStreamToCache(context, input)
        } catch (_: Exception) {
            false
        }
    }

    /** Counts the images of the chosen folder in the background (subfolders included). */
    fun countImagesAsync(context: Context, rawUri: String, onCount: (Int) -> Unit) {
        if (rawUri.isEmpty()) {
            val local = localFolderImageCount(context)
            onCount(local)
            return
        }
        val appContext = context.applicationContext
        executor.execute {
            val count = listImageIds(appContext, rawUri).size
            mainHandler.post { onCount(count) }
        }
    }

    /** Same as [copyRandomImageFromFolder], for the photos imported without a folder picker. */
    private fun copyRandomLocalImage(context: Context): Boolean {
        return try {
            val dir = File(getLocalFolder(context))
            val images = dir.listFiles()?.filter { it.isFile && it.length() > 1024 }
                ?: return false
            if (images.isEmpty()) return false
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val recent = prefs.getString(PREF_RECENT_PICKS, null)
                .orEmpty()
                .split('\n')
                .filter { it.isNotEmpty() }
            val choices = images.filter { it.name !in recent }.ifEmpty { images }
            val picked = choices.random()
            val history = (listOf(picked.name) + recent).take(MAX_RECENT_PICKS)
            prefs.edit().putString(PREF_RECENT_PICKS, history.joinToString("\n")).apply()
            context.contentResolver.openInputStream(Uri.fromFile(picked))?.let { input ->
                saveStreamToCache(context, input)
            } ?: false
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Lists the document ids of every image inside a tree folder, including subfolders
     * (up to [MAX_FOLDER_DEPTH] levels). Returns an empty list when the folder is not readable.
     */
    private fun listImageIds(context: Context, rawUri: String): List<String> {
        return try {
            val treeUri = Uri.parse(rawUri)
            val images = mutableListOf<String>()
            val pending = mutableListOf(DocumentsContract.getTreeDocumentId(treeUri) to 0)
            val visited = mutableSetOf<String>()

            while (pending.isNotEmpty() && images.size < MAX_FOLDER_IMAGES) {
                val (documentId, depth) = pending.removeAt(0)
                if (!visited.add(documentId)) continue
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
                try {
                    context.contentResolver.query(
                        childrenUri,
                        arrayOf(
                            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                            DocumentsContract.Document.COLUMN_MIME_TYPE
                        ),
                        null,
                        null,
                        null
                    )?.use { cursor ->
                        while (cursor.moveToNext()) {
                            val childId = cursor.getString(0) ?: continue
                            val mime = cursor.getString(1).orEmpty()
                            when {
                                mime.startsWith("image/") -> images.add(childId)
                                mime == DocumentsContract.Document.MIME_TYPE_DIR &&
                                    depth < MAX_FOLDER_DEPTH -> pending.add(childId to depth + 1)
                            }
                        }
                    }
                } catch (_: Exception) {
                    // Skip folders that cannot be read (permission lost, removed SD card...).
                }
            }
            images
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Writes [input] to the wallpaper cache file. Returns false if the result is not usable. */
    private fun saveStreamToCache(context: Context, input: InputStream): Boolean {
        val tempFile = File(context.cacheDir, "${CACHE_FILE_NAME}.tmp")
        var total = 0L
        FileOutputStream(tempFile).use { output ->
            input.use { source ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_IMAGE_BYTES) break
                    output.write(buffer, 0, read)
                }
            }
        }
        if (total > MAX_IMAGE_BYTES || !tempFile.exists() || tempFile.length() < 1024) {
            tempFile.delete()
            return false
        }
        if (!isDecodableImage(tempFile)) {
            tempFile.delete()
            return false
        }
        val destFile = File(context.cacheDir, CACHE_FILE_NAME)
        if (destFile.exists() && !destFile.delete()) {
            tempFile.delete()
            return false
        }
        if (!tempFile.renameTo(destFile)) {
            tempFile.delete()
            return false
        }
        return true
    }

    /** True when [file] really contains an image (reads only the header). */
    private fun isDecodableImage(file: File): Boolean {
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, options)
            options.outWidth > 0 && options.outHeight > 0
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Only encrypted HTTPS links are accepted. This blocks cleartext images that another device
     * on the network could replace, and every scheme that is not http(s).
     */
    fun isAllowedRemoteUrl(url: String): Boolean {
        if (url.isBlank()) return false
        return try {
            val uri = java.net.URI(url.trim())
            uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrEmpty()
        } catch (_: Exception) {
            false
        }
    }

    private fun isAllowedImageUrl(url: String): Boolean = isAllowedRemoteUrl(url)

    private fun decodeSampledBitmapFromFile(path: String, reqWidth: Int, reqHeight: Int): Bitmap? {
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(path, options)

            val width = if (reqWidth > 0) reqWidth else 1280
            val height = if (reqHeight > 0) reqHeight else 720

            options.inSampleSize = calculateInSampleSize(options, width, height)
            options.inJustDecodeBounds = false
            // Use RGB_565 to cut RAM by 50% for full-screen TV background
            options.inPreferredConfig = Bitmap.Config.RGB_565

            BitmapFactory.decodeFile(path, options)
        } catch (_: Exception) {
            null
        }
    }

    private fun calculateInSampleSize(
        options: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        val (height: Int, width: Int) = options.outHeight to options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize.coerceAtLeast(1)
    }
}
