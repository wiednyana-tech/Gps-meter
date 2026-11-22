package id.gpsmeter

import java.util.Locale

object Format {
    fun distance(m: Double): String =
        if (m < 1000) String.format(Locale.US, "%.0f m", m)
        else String.format(Locale.US, "%.2f km", m / 1000)

    fun duration(ms: Long): String {
        val s = ms / 1000
        return String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
    }

    fun kmh(ms: Double): String = String.format(Locale.US, "%.1f km/j", ms * 3.6)

    /** Pace (menit per km), berguna untuk jalan kaki/lari. */
    fun pace(distanceM: Double, movingMs: Long): String {
        if (distanceM < 50) return "--:-- /km"
        val secPerKm = (movingMs / 1000.0) / (distanceM / 1000.0)
        val m = (secPerKm / 60).toInt()
        val s = (secPerKm % 60).toInt()
        return String.format(Locale.US, "%d:%02d /km", m, s)
    }
}
