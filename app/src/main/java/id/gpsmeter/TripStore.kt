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
    fun delete(ctx: Context, id: Long) {
        save(ctx, load(ctx).filterNot { it.id == id })
        trackFile(ctx, id).delete()
    }

    fun clear(ctx: Context) {
        save(ctx, emptyList())
        ctx.filesDir.listFiles { f -> f.name.startsWith("track_") }?.forEach { it.delete() }
    }

    // ---- Rute (disimpan per perjalanan agar daftar riwayat tetap ringan) ----
    private fun trackFile(ctx: Context, id: Long) = File(ctx.filesDir, "track_$id.json")

    fun saveTrack(ctx: Context, id: Long, segments: List<List<DoubleArray>>) {
        val arr = JSONArray()
        segments.filter { it.size >= 2 }.forEach { seg ->
            val s = JSONArray()
            seg.forEach { p ->
                s.put(JSONArray().put(Math.round(p[0] * 1e6) / 1e6).put(Math.round(p[1] * 1e6) / 1e6))
            }
            arr.put(s)
        }
        trackFile(ctx, id).writeText(arr.toString())
    }

    fun loadTrack(ctx: Context, id: Long): List<List<DoubleArray>> {
        val f = trackFile(ctx, id)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { i ->
                val s = arr.getJSONArray(i)
                (0 until s.length()).map { j ->
                    val p = s.getJSONArray(j); doubleArrayOf(p.getDouble(0), p.getDouble(1))
                }
            }
        } catch (e: Exception) { emptyList() }
    }

    fun hasTrack(ctx: Context, id: Long) = trackFile(ctx, id).exists()
}
