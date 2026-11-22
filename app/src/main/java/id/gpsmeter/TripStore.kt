package id.gpsmeter

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Trip(
    val id: Long,           // waktu mulai (epoch ms), sekaligus ID unik
    val endTime: Long,
    val mode: TrackMode,
    val distanceM: Double,
    val elapsedMs: Long,
    val movingMs: Long,
    val maxSpeedMs: Double
) {
    val avgSpeedMs: Double get() = if (movingMs > 0) distanceM / (movingMs / 1000.0) else 0.0
}

/** Penyimpanan riwayat perjalanan sederhana (file JSON di penyimpanan internal aplikasi). */
object TripStore {
    private const val FILE = "trips.json"

    @Synchronized
    fun load(ctx: Context): List<Trip> {
        val f = File(ctx.filesDir, FILE)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Trip(
                    id = o.getLong("id"),
                    endTime = o.optLong("end", o.getLong("id")),
                    mode = runCatching { TrackMode.valueOf(o.getString("mode")) }.getOrDefault(TrackMode.WALK),
                    distanceM = o.getDouble("dist"),
                    elapsedMs = o.getLong("elapsed"),
                    movingMs = o.optLong("moving", o.getLong("elapsed")),
                    maxSpeedMs = o.optDouble("max", 0.0)
                )
            }.sortedByDescending { it.id }
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    private fun save(ctx: Context, trips: List<Trip>) {
        val arr = JSONArray()
        trips.forEach { t ->
            arr.put(JSONObject().apply {
                put("id", t.id); put("end", t.endTime); put("mode", t.mode.name)
                put("dist", t.distanceM); put("elapsed", t.elapsedMs)
                put("moving", t.movingMs); put("max", t.maxSpeedMs)
            })
        }
        val tmp = File(ctx.filesDir, "$FILE.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(File(ctx.filesDir, FILE))
    }

    fun add(ctx: Context, trip: Trip) = save(ctx, load(ctx) + trip)
    fun delete(ctx: Context, id: Long) = save(ctx, load(ctx).filterNot { it.id == id })
    fun clear(ctx: Context) = save(ctx, emptyList())
}
