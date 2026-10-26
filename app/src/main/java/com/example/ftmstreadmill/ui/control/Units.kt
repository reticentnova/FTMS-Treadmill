package com.example.ftmstreadmill.ui.control

import java.util.Locale

/** Unit conversion and formatting for display. The data layer always uses metric. */
object Units {
    private const val KM_PER_MILE = 1.609344

    fun speedToDisplay(kmh: Double, imperial: Boolean) = if (imperial) kmh / KM_PER_MILE else kmh

    fun speedFromDisplay(value: Double, imperial: Boolean) = if (imperial) value * KM_PER_MILE else value

    fun formatSpeed(kmh: Double?, imperial: Boolean): String =
        kmh?.let { "%.1f".format(Locale.getDefault(), speedToDisplay(it, imperial)) } ?: "–"

    fun formatDistance(meters: Int?, imperial: Boolean): String {
        meters ?: return "–"
        val km = meters / 1000.0
        return "%.2f".format(Locale.getDefault(), if (imperial) km / KM_PER_MILE else km)
    }

    /** Pace derived from speed, as m:ss per km or per mile. */
    fun formatPace(kmh: Double?, imperial: Boolean): String {
        val speed = speedToDisplay(kmh ?: return "–", imperial)
        if (speed < 0.5) return "–"
        val secondsPerUnit = (3600 / speed).toInt()
        return "%d:%02d".format(secondsPerUnit / 60, secondsPerUnit % 60)
    }

    fun formatDuration(seconds: Int?): String {
        seconds ?: return "–"
        val h = seconds / 3600
        val m = seconds % 3600 / 60
        val s = seconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    fun formatIncline(percent: Double?): String =
        percent?.let { "%.1f".format(Locale.getDefault(), it) } ?: "–"
}
