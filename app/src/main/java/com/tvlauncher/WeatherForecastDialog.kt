package com.tvlauncher

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.Window
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.min

/** Rich, TV-friendly week forecast dialog. */
object WeatherForecastDialog {

    /**
     * Shows the cached forecast immediately and refreshes it only when the cache is stale.
     * The update button always forces a download. [onUpdated] runs on the main thread after every
     * load, including when only the last cached forecast is available offline.
     */
    @JvmStatic
    fun show(activity: Activity, onUpdated: (Weather.Forecast?) -> Unit = {}) {
        if (activity.isFinishing || activity.isDestroyed) return
        ForecastDialog(activity, onUpdated).show()
    }
}

private class ForecastDialog(
    private val activity: Activity,
    private val onUpdated: (Weather.Forecast?) -> Unit
) {
    private val dialog = Dialog(activity, R.style.Theme_TVLauncher_AboutDialog)
    private val currentSummary: View
    private val currentIcon: ImageView
    private val currentTemperature: TextView
    private val currentCondition: TextView
    private val daysTitle: TextView
    private val daysScroll: ScrollView
    private val daysContainer: LinearLayout
    private val statusText: TextView
    private val refreshProgress: ProgressBar
    private val refreshButton: Button

    private var loading = false
    private var displayedForecast: Weather.Forecast? = null
    private val dateParser = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val dateFormatter = SimpleDateFormat("dd/MM", Locale("pt", "BR"))

    init {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.weather_v42_dialog)

        currentSummary = dialog.findViewById(R.id.weatherV42CurrentSummary)
        currentIcon = dialog.findViewById(R.id.weatherV42CurrentIcon)
        currentTemperature = dialog.findViewById(R.id.weatherV42CurrentTemperature)
        currentCondition = dialog.findViewById(R.id.weatherV42CurrentCondition)
        daysTitle = dialog.findViewById(R.id.weatherV42DaysTitle)
        daysScroll = dialog.findViewById(R.id.weatherV42DaysScroll)
        daysContainer = dialog.findViewById(R.id.weatherV42DaysContainer)
        statusText = dialog.findViewById(R.id.weatherV42Status)
        refreshProgress = dialog.findViewById(R.id.weatherV42RefreshProgress)
        refreshButton = dialog.findViewById(R.id.weatherV42Refresh)

        currentIcon.setColorFilter(Color.rgb(165, 180, 252))
        refreshButton.setOnClickListener { load(force = true) }
    }

    fun show() {
        if (activity.isFinishing || activity.isDestroyed) return
        dialog.show()
        sizeWindow()
        render(Weather.cached(activity))
        load(force = false)
    }

    private fun sizeWindow() {
        val metrics = activity.resources.displayMetrics
        val width = min(
            (metrics.widthPixels * WIDTH_FRACTION).toInt(),
            (MAX_WIDTH_DP * metrics.density).toInt()
        )
        val height = (metrics.heightPixels * HEIGHT_FRACTION).toInt()
        dialog.window?.let { window ->
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window.setGravity(Gravity.CENTER)
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window.attributes = window.attributes.apply { dimAmount = DIM_AMOUNT }
            window.setLayout(width, height)
        }
    }

    private fun load(force: Boolean) {
        if (loading || activity.isFinishing || activity.isDestroyed) return
        setLoading(true)
        Weather.load(activity, force) { forecast ->
            if (activity.isFinishing || activity.isDestroyed) return@load
            setLoading(false)
            if (dialog.isShowing) render(forecast ?: displayedForecast)
            onUpdated(forecast)
        }
    }

    private fun setLoading(value: Boolean) {
        loading = value
        refreshButton.isEnabled = !value
        refreshButton.setText(
            if (value) R.string.weather_v42_refreshing else R.string.weather_v42_refresh
        )
        refreshProgress.visibility = if (value) View.VISIBLE else View.GONE
        if (displayedForecast == null) {
            statusText.visibility = View.VISIBLE
            statusText.setText(
                if (value) R.string.weather_v42_loading else R.string.weather_v42_unavailable
            )
        }
    }

    private fun render(forecast: Weather.Forecast?) {
        displayedForecast = forecast
        val hasForecast = forecast != null
        currentSummary.visibility = if (hasForecast) View.VISIBLE else View.GONE
        daysTitle.visibility = if (hasForecast) View.VISIBLE else View.GONE
        daysScroll.visibility = if (hasForecast) View.VISIBLE else View.GONE
        statusText.visibility = if (hasForecast) View.GONE else View.VISIBLE
        if (forecast == null) {
            statusText.setText(
                if (loading) R.string.weather_v42_loading else R.string.weather_v42_unavailable
            )
            daysContainer.removeAllViews()
            return
        }

        currentIcon.setImageResource(Weather.iconRes(forecast.code))
        currentTemperature.text = activity.getString(
            R.string.weather_v42_current_temperature,
            forecast.temperatureC
        )
        currentCondition.text = Weather.conditionLabel(forecast.code)

        daysContainer.removeAllViews()
        val inflater = LayoutInflater.from(activity)
        forecast.days.forEachIndexed { index, day ->
            val card = inflater.inflate(R.layout.weather_v42_day_card, daysContainer, false)
            val icon = card.findViewById<ImageView>(R.id.weatherV42DayIcon)
            icon.setImageResource(Weather.iconRes(day.code))
            icon.setColorFilter(Color.rgb(147, 197, 253))
            card.findViewById<TextView>(R.id.weatherV42DayWeekday).text =
                weekdayLabel(index, day.isoDate)
            card.findViewById<TextView>(R.id.weatherV42DayDate).text = dateLabel(day.isoDate)
            card.findViewById<TextView>(R.id.weatherV42DayCondition).text =
                Weather.conditionLabel(day.code)
            card.findViewById<TextView>(R.id.weatherV42DayRain).text = activity.getString(
                R.string.weather_v42_rain_chance,
                day.rainPercent.coerceIn(0, 100)
            )
            card.findViewById<TextView>(R.id.weatherV42DayMax).text = activity.getString(
                R.string.weather_v42_max_temperature,
                day.maxC
            )
            card.findViewById<TextView>(R.id.weatherV42DayMin).text = activity.getString(
                R.string.weather_v42_min_temperature,
                day.minC
            )
            daysContainer.addView(card)
        }
    }

    private fun weekdayLabel(index: Int, isoDate: String): String = when (index) {
        0 -> activity.getString(R.string.weather_v42_today)
        1 -> activity.getString(R.string.weather_v42_tomorrow)
        else -> {
            val label = Weather.weekdayLabel(isoDate)
            if (label.isEmpty()) {
                activity.getString(R.string.weather_v42_unknown_day)
            } else {
                label.substring(0, 1).uppercase(Locale("pt", "BR")) + label.substring(1)
            }
        }
    }

    private fun dateLabel(isoDate: String): String = try {
        dateParser.parse(isoDate)?.let(dateFormatter::format) ?: ""
    } catch (_: Exception) {
        ""
    }

    private companion object {
        const val WIDTH_FRACTION = 0.88f
        const val HEIGHT_FRACTION = 0.86f
        const val MAX_WIDTH_DP = 940
        const val DIM_AMOUNT = 0.72f
    }
}
