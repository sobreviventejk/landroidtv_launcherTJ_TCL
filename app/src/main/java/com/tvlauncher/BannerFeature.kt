package com.tvlauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Banners of "videos in high" (YouTube trending) shown under the apps of the "Inicio" tab.
 *
 * The launcher has no YouTube API key, so the feed comes from public, keyless JSON mirrors
 * (Piped/Invidious). Everything is defensive: network timeout, per-request fallback across
 * mirrors, a local cache used offline (and when every mirror fails) and if even the cache is
 * empty the strip simply stays hidden — the launcher keeps working without it.
 */
object BannerFeed {

    private const val CACHE_FILE = "banners_cache.json"
    private const val CACHE_TTL_MS = 6L * 60 * 60 * 1000
    private const val MAX_BANNERS = 10
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 12_000

    /** Public, keyless endpoints that expose the YouTube trending chart. Any one is enough. */
    private val FEED_URLS = listOf(
        "https://pipedapi.kavin.rocks/trending?region=BR",
        "https://pipedapi.adminforge.de/trending?region=BR",
        "https://api.piped.private.coffee/trending?region=BR",
        "https://invidious.jing.rocks/api/v1/trending?region=BR"
    )

    data class VideoBanner(val title: String, val videoUrl: String, val thumbnailUrl: String)

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Loads the feed: cache when fresh, else network (with cache fallback). Main-thread result. */
    fun load(context: Context, force: Boolean, onDone: (List<VideoBanner>) -> Unit) {
        val appContext = context.applicationContext
        val cacheFile = File(appContext.cacheDir, CACHE_FILE)
        val cacheFresh = !force &&
            System.currentTimeMillis() - cacheFile.lastModified() < CACHE_TTL_MS
        if (cacheFresh) {
            val cached = parseCache(cacheFile)
            if (cached.isNotEmpty()) {
                mainHandler.post { onDone(cached) }
                return
            }
        }
        thread(name = "banners-fetch") {
            val fetched = fetch(appContext)
            val list = if (fetched.isNotEmpty()) fetched else parseCache(cacheFile)
            mainHandler.post { onDone(list) }
        }
    }

    /** Tries every mirror; on the first success also refreshes the offline cache. */
    private fun fetch(context: Context): List<VideoBanner> {
        for (url in FEED_URLS) {
            try {
                val body = httpGet(url) ?: continue
                val list = parseFeed(body)
                if (list.isNotEmpty()) {
                    writeCache(context, list)
                    return list
                }
            } catch (_: Exception) {
                // Next mirror.
            }
        }
        return emptyList()
    }

    private fun httpGet(url: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "TJ-TV-Launcher/1.0")
            }
            if (connection.responseCode in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        } finally {
            try {
                connection?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    /** Parses both shapes: Piped (`url`/`title`/`thumbnail`) and Invidious (`videoId`...). */
    private fun parseFeed(body: String): List<VideoBanner> {
        val result = mutableListOf<VideoBanner>()
        try {
            val array = JSONArray(body)
            for (i in 0 until array.length().coerceAtMost(30)) {
                val item = array.optJSONObject(i) ?: continue
                val title = item.optString("title").trim()
                if (title.isEmpty()) continue
                val videoId = extractVideoId(item)
                if (videoId.isEmpty()) continue
                var thumb = item.optString("thumbnail").trim()
                if (thumb.isEmpty()) {
                    val thumbs = item.optJSONArray("videoThumbnails")
                    thumb = thumbs?.optJSONObject(0)?.optString("url").orEmpty()
                }
                if (!thumb.startsWith("https://")) continue
                result.add(
                    VideoBanner(
                        title = title,
                        videoUrl = "https://www.youtube.com/watch?v=$videoId",
                        thumbnailUrl = thumb
                    )
                )
                if (result.size >= MAX_BANNERS) break
            }
        } catch (_: Exception) {
        }
        return result
    }

    private fun extractVideoId(item: org.json.JSONObject): String {
        val raw = item.optString("videoId")
        if (raw.matches(Regex("[A-Za-z0-9_-]{6,20}"))) return raw
        val link = item.optString("url")
        val match = Regex("[?&]v=([A-Za-z0-9_-]{6,20})").find(link)
        return match?.groupValues?.get(1).orEmpty()
    }

    private fun writeCache(context: Context, banners: List<VideoBanner>) {
        try {
            val array = JSONArray()
            banners.forEach { banner ->
                array.put(
                    org.json.JSONObject()
                        .put("title", banner.title)
                        .put("videoUrl", banner.videoUrl)
                        .put("thumbnailUrl", banner.thumbnailUrl)
                )
            }
            File(context.cacheDir, CACHE_FILE).writeText(array.toString())
        } catch (_: Exception) {
        }
    }

    private fun parseCache(file: File): List<VideoBanner> {
        return try {
            if (!file.exists()) emptyList()
            else parseFeedSafe(file.readText())
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseFeedSafe(text: String): List<VideoBanner> {
        val result = mutableListOf<VideoBanner>()
        try {
            val array = JSONArray(text)
            for (i in 0 until array.length().coerceAtMost(MAX_BANNERS)) {
                val item = array.optJSONObject(i) ?: continue
                val title = item.optString("title")
                val videoUrl = item.optString("videoUrl")
                val thumb = item.optString("thumbnailUrl")
                if (title.isNotEmpty() && videoUrl.startsWith("https://")) {
                    result.add(VideoBanner(title, videoUrl, thumb))
                }
            }
        } catch (_: Exception) {
        }
        return result
    }

    // ---- Thumbnails ---------------------------------------------------------------------------

    private val thumbMemory = HashMap<String, Bitmap>()
    private val thumbLock = Any()

    /** Loads a thumbnail bitmap with a memory cache and a file cache; null when unavailable. */
    fun thumbnail(context: Context, url: String, onBitmap: (Bitmap?) -> Unit) {
        if (url.isEmpty()) {
            onBitmap(null)
            return
        }
        synchronized(thumbLock) { thumbMemory[url]?.let { onBitmap(it); return } }
        val disk = thumbFile(context, url)
        if (disk.exists()) {
            val cached = BitmapFactory.decodeFile(disk.absolutePath)
            if (cached != null) {
                synchronized(thumbLock) { thumbMemory[url] = cached }
                onBitmap(cached)
                return
            }
        }
        thread(name = "banner-thumb") {
            val bitmap = try {
                var connection: HttpURLConnection? = null
                try {
                    connection = (URL(url).openConnection() as HttpURLConnection).apply {
                        connectTimeout = CONNECT_TIMEOUT_MS
                        readTimeout = READ_TIMEOUT_MS
                        setRequestProperty("User-Agent", "TJ-TV-Launcher/1.0")
                    }
                    if (connection.responseCode in 200..299) {
                        BitmapFactory.decodeStream(connection.inputStream)
                    } else {
                        null
                    }
                } finally {
                    try {
                        connection?.disconnect()
                    } catch (_: Exception) {
                    }
                }
            } catch (_: Exception) {
                null
            }
            if (bitmap != null) {
                try {
                    disk.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }
                } catch (_: Exception) {
                }
                synchronized(thumbLock) { thumbMemory[url] = bitmap }
            }
            mainHandler.post { onBitmap(bitmap) }
        }
    }

    private fun thumbFile(context: Context, url: String): File {
        val digest = MessageDigest.getInstance("MD5")
            .digest(url.toByteArray(Charsets.UTF_8))
            .joinToString("") { String.format("%02x", it) }
        return File(File(context.cacheDir, "banners").apply { mkdirs() }, "$digest.jpg")
    }
}

/** Horizontal adapter of the banner row: rounded thumbnail card, title below, opens the video. */
class BannerAdapter(
    private val onLaunch: (BannerFeed.VideoBanner) -> Unit
) : RecyclerView.Adapter<BannerAdapter.Holder>() {

    private val items = mutableListOf<BannerFeed.VideoBanner>()

    /** Same focus glow colour as the app cards. */
    var accentColor: Int = Palette.accent(Palette.DEFAULT)
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    /** Read like RecyclerView's currentList by MainActivity. */
    val currentList: List<BannerFeed.VideoBanner>
        get() = items

    fun submitList(list: List<BannerFeed.VideoBanner>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val image: ImageView = itemView.findViewById(R.id.bannerImage)
        val title: TextView = itemView.findViewById(R.id.bannerTitle)
        var boundUrl: String? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_banner_card, parent, false)
        return Holder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val context = holder.itemView.context
        val banner = items[position]
        holder.boundUrl = banner.videoUrl
        holder.title.text = banner.title

        val density = context.resources.displayMetrics.density
        val cardSize = TileStyle.CardSize((184 * density).toInt(), (136 * density).toInt())
        fun style(focused: Boolean) {
            TileStyle.apply(holder.itemView, cardSize, focused, accentColor)
        }
        holder.itemView.setOnFocusChangeListener { _, focused -> style(focused) }
        style(holder.itemView.isFocused)
        holder.image.clipToOutline = true
        holder.image.setImageDrawable(
            ContextCompat.getDrawable(context, R.drawable.banner_placeholder)
        )
        val clicked = View.OnClickListener {
            if (holder.boundUrl == banner.videoUrl) onLaunch(banner)
        }
        holder.itemView.setOnClickListener(clicked)

        BannerFeed.thumbnail(context, banner.thumbnailUrl) { bitmap ->
            if (isPositionBound(holder, banner) && bitmap != null) {
                holder.image.setImageBitmap(bitmap)
            }
        }
    }

    private fun isPositionBound(holder: Holder, banner: BannerFeed.VideoBanner): Boolean {
        return holder.bindingAdapterPosition != RecyclerView.NO_POSITION &&
            holder.boundUrl == banner.videoUrl
    }
}
