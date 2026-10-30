package com.tvlauncher

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Daily weather for the home screen, using the free Open-Meteo API (no key needed).
 *
 * The default location is Belém - PA (Terra Firme). The last answer is cached as raw JSON so the
 * header still shows something when the TV is offline; the cache is refreshed every
 * [CACHE_MS] minutes.
 */
object Weather {

    /** Belém - PA, Terra Firme. */
    const val DEFAULT_LATITUDE = -1.4550
    const val DEFAULT_LONGITUDE = -48.4740

    private const val PREFS_NAME = "weather_cache"
    private const val KEY_JSON = "forecast_json"
    private const val KEY_TIME = "forecast_time"
    private const val CACHE_MS = 30 * 60 * 1000L
    private const val TIMEOUT_MS = 10_000

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    /** One day of the week forecast. */
    data class Day(
        val isoDate: String,
        val code: Int,
        val maxC: Int,
        val minC: Int,
        val rainPercent: Int
    )

    /** Current weather plus the next days. */
    data class Forecast(
        val temperatureC: Int,
        val code: Int,
        val isDay: Boolean,
        val days: List<Day>
    )

    /**
     * Loads the forecast: cached when it is fresh, otherwise from the network.
     * [onResult] always runs on the main thread; null means "nothing available at all".
     */
    fun load(context: Context, force: Boolean, onResult: (Forecast?) -> Unit) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val cached = prefs.getString(KEY_JSON, null)
        val cachedTime = prefs.getLong(KEY_TIME, 0L)
        val fresh = cached != null && System.currentTimeMillis() - cachedTime < CACHE_MS

        if (!force && fresh) {
            onResult(parse(cached))
            return
        }

        executor.execute {
            val downloaded = download()
            val forecast = if (downloaded != null) {
                prefs.edit()
                    .putString(KEY_JSON, downloaded)
                    .putLong(KEY_TIME, System.currentTimeMillis())
                    .apply()
                parse(downloaded)
            } else {
                // Offline: keep showing the last known forecast instead of nothing.
                parse(cached)
            }
            mainHandler.post { onResult(forecast) }
        }
    }

    /** Last cached forecast, without touching the network. */
    fun cached(context: Context): Forecast? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return parse(prefs.getString(KEY_JSON, null))
    }

    private fun download(): String? {
        val urlText = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=$DEFAULT_LATITUDE&longitude=$DEFAULT_LONGITUDE" +
            "&current=temperature_2m,weather_code,is_day" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
            "&timezone=America%2FSao_Paulo&forecast_days=7&temperature_unit=celsius"
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlText).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("User-Agent", "TVLauncher/1.0 (Android TV)")
            }
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    /** Parses the Open-Meteo JSON. Kept internal so the dialog can rebuild it from the cache. */
    internal fun parse(raw: String?): Forecast? {
        if (raw.isNullOrBlank()) return null
        return try {
            val root = JSONObject(raw)
            val current = root.optJSONObject("current") ?: return null
            val daily = root.optJSONObject("daily") ?: return null

            val codes = daily.optJSONArray("weather_code")
            val maxs = daily.optJSONArray("temperature_2m_max")
            val mins = daily.optJSONArray("temperature_2m_min")
            val rains = daily.optJSONArray("precipitation_probability_max")
            val dates = daily.optJSONArray("time")
            if (codes == null || maxs == null || mins == null || dates == null) return null

            val days = mutableListOf<Day>()
            for (i in 0 until minOf(codes.length(), maxs.length(), mins.length(), dates.length())) {
                days.add(
                    Day(
                        isoDate = dates.optString(i, ""),
                        code = codes.optInt(i, 0),
                        maxC = Math.round(maxs.optDouble(i, 0.0)).toInt(),
                        minC = Math.round(mins.optDouble(i, 0.0)).toInt(),
                        rainPercent = rains?.optInt(i, 0) ?: 0
                    )
                )
            }
            if (days.isEmpty()) return null

            Forecast(
                temperatureC = Math.round(current.optDouble("temperature_2m", 0.0)).toInt(),
                code = current.optInt("weather_code", days[0].code),
                isDay = current.optInt("is_day", 1) == 1,
                days = days
            )
        } catch (_: Exception) {
            null
        }
    }

    /** Short Portuguese description of a WMO weather code. */
    fun conditionLabel(code: Int): String = when (code) {
        0 -> "Céu limpo"
        1 -> "Quase limpo"
        2 -> "Parcialmente nublado"
        3 -> "Nublado"
        45, 48 -> "Neblina"
        51, 53, 55 -> "Garoa"
        56, 57 -> "Garoa congelante"
        61, 63, 65 -> "Chuva"
        66, 67 -> "Chuva congelante"
        71, 73, 75, 77 -> "Neve"
        80, 81, 82 -> "Pancadas de chuva"
        85, 86 -> "Pancadas de neve"
        95 -> "Tempestade"
        96, 99 -> "Tempestade com granizo"
        else -> "Tempo variável"
    }

    /** Small vector icon that matches the weather code. */
    fun iconRes(code: Int): Int = when (code) {
        0, 1 -> R.drawable.ic_weather_clear
        2 -> R.drawable.ic_weather_partly
        3 -> R.drawable.ic_weather_cloudy
        45, 48 -> R.drawable.ic_weather_fog
        51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 -> R.drawable.ic_weather_rain
        71, 73, 75, 77, 85, 86 -> R.drawable.ic_weather_snow
        95, 96, 99 -> R.drawable.ic_weather_storm
        else -> R.drawable.ic_weather_cloudy
    }

    /** Weekday abbreviation for an ISO date ("2026-10-29" -> "qui"). */
    fun weekdayLabel(isoDate: String): String {
        return try {
            val parser = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val date = parser.parse(isoDate) ?: return ""
            val calendar = Calendar.getInstance().apply { time = date }
            WEEKDAYS[calendar.get(Calendar.DAY_OF_WEEK) - 1]
        } catch (_: Exception) {
            ""
        }
    }

    private val WEEKDAYS = arrayOf("dom", "seg", "ter", "qua", "qui", "sex", "sáb")
}
