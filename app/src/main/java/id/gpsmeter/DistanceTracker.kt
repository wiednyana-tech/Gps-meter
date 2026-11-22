package id.gpsmeter

import kotlin.math.*

/** Parameter penyaringan per mode. Nilai-nilai ini hasil kompromi umum; bisa disetel ulang. */
enum class TrackMode(
    val label: String,
    val maxAccuracyM: Float,      // fix dengan akurasi lebih buruk dari ini dibuang
    val maxSpeedMs: Double,       // lompatan yang menyiratkan kecepatan di atas ini dibuang
    val minStepM: Double,         // jarak minimum sebelum ditambahkan ke total
    val stationarySpeedMs: Float, // di bawah ini dianggap diam (mencegah "drift" GPS)
    val processNoiseMs: Double,   // noise proses Kalman (perkiraan perubahan kecepatan)
    val intervalMs: Long          // interval update lokasi
) {
    WALK("Jalan kaki", 20f, 7.0, 3.0, 0.5f, 1.0, 1000),
    DRIVE("Berkendara", 30f, 70.0, 6.0, 1.5f, 8.0, 1000)
}

data class Fix(
    val lat: Double,
    val lng: Double,
    val accuracyM: Float,
    val speedMs: Float?,   // null kalau perangkat tidak memberi kecepatan
    val timeMs: Long       // waktu monotonic (elapsedRealtime) dalam ms
)

/**
 * Menghitung jarak dari rangkaian fix GPS dengan:
 *  1. Buang fix yang akurasinya buruk
 *  2. Pemanasan: tunggu beberapa fix bagus sebelum mulai menghitung
 *  3. Kalman filter untuk menghaluskan posisi
 *  4. Tolak lompatan yang tidak masuk akal (kecepatan mustahil)
 *  5. Deteksi diam supaya jarak tidak bertambah saat berhenti
 *  6. Jarak dibatasi oleh integral kecepatan Doppler GPS (anti zig-zag)
 *  7. Jarak dihitung dari "titik jangkar": jarak baru ditambahkan hanya setelah
 *     bergerak melewati ambang, sehingga gerakan pelan tetap terhitung utuh
 *     tetapi getaran GPS kecil tidak menumpuk.
 */
class DistanceTracker(private val mode: TrackMode) {

    var totalM = 0.0; private set
    var lastSpeedMs = 0.0; private set
    var maxSpeedMs = 0.0; private set
    var movingMs = 0L; private set
    var lastAccuracy = 0f; private set
    var acceptedCount = 0; private set
    var rejectedCount = 0; private set

    /** Rute yang direkam: beberapa segmen (terputus saat jeda), tiap titik = [lat, lng]. */
    val segments: MutableList<MutableList<DoubleArray>> = mutableListOf()

    private val kalman = LatLngKalman(mode.processNoiseMs)
    private var anchorLat = 0.0
    private var anchorLng = 0.0
    private var anchorTime = 0L
    private var hasAnchor = false
    private var warmup = 0
    private var consecutiveJumps = 0
    private var lastFixTime = 0L
    private var speedIntegralM = 0.0

    /** Panggil saat jeda/lanjut supaya jarak tidak "melompat" melintasi waktu jeda. */
    fun resetAnchor() {
        hasAnchor = false
        warmup = 0
        consecutiveJumps = 0
        kalman.reset()
    }

    /** @return true jika fix ini menambah jarak */
    fun onFix(f: Fix): Boolean {
        lastAccuracy = f.accuracyM
        if (f.timeMs <= lastFixTime) return false
        val dtFixS = if (lastFixTime == 0L) 0.0 else (f.timeMs - lastFixTime) / 1000.0
        lastFixTime = f.timeMs

        if (f.accuracyM > mode.maxAccuracyM || f.accuracyM <= 0f) {
            rejectedCount++
            return false
        }
        // Integral kecepatan Doppler sejak jangkar terakhir (estimasi jarak yang tidak terpengaruh zig-zag)
        f.speedMs?.let { speedIntegralM += it * dtFixS }

        // Tolak lonjakan (glitch) SEBELUM masuk Kalman agar filter tidak tercemar
        if (kalman.initialized) {
            val dr = haversine(kalman.lat, kalman.lng, f.lat, f.lng)
            val dtk = (f.timeMs - kalman.timeMs).coerceAtLeast(1) / 1000.0
            if (dr > 3 * f.accuracyM && dr / dtk > mode.maxSpeedMs) {
                rejectedCount++
                if (++consecutiveJumps >= 5) {
                    // Mungkin memang berpindah (mis. keluar terowongan): mulai ulang filter
                    resetAnchor()
                }
                return false
            }
        }
        consecutiveJumps = 0

        val (lat, lng) = kalman.process(f.lat, f.lng, f.accuracyM.toDouble(), f.timeMs)

        // Pemanasan: 3 fix bagus pertama hanya untuk menstabilkan filter
        if (warmup < 3 || !hasAnchor) {
            warmup++
            setAnchor(lat, lng, f.timeMs)
            if (warmup >= 3) segments.add(mutableListOf(doubleArrayOf(lat, lng)))
            return false
        }

        val d = haversine(anchorLat, anchorLng, lat, lng)
        val dt = (f.timeMs - anchorTime).coerceAtLeast(1) / 1000.0
        if (d / dt > mode.maxSpeedMs) { rejectedCount++; return false }

        val reportedSpeed = f.speedMs
        lastSpeedMs = reportedSpeed?.toDouble() ?: (d / dt)

        // Deteksi diam: kecepatan Doppler rendah & pergeseran masih dalam radius noise
        if (reportedSpeed != null && reportedSpeed < mode.stationarySpeedMs && d < 2 * f.accuracyM) {
            lastSpeedMs = 0.0
            anchorTime = f.timeMs      // waktu diam tidak dihitung sebagai waktu bergerak
            speedIntegralM = 0.0
            return false
        }

        // Ambang adaptif: minimal minStep, naik kalau akurasi sedang jelek
        if (d < max(mode.minStepM, f.accuracyM * 0.6)) return false

        // Gabungkan jarak posisi & jarak dari kecepatan Doppler:
        // jarak posisi dipakai, tetapi dibatasi ±5% dari integral kecepatan.
        val inc = if (reportedSpeed != null)
            min(speedIntegralM * 1.05 + 0.5, max(d, speedIntegralM * 0.95))
        else d

        totalM += inc
        movingMs += f.timeMs - anchorTime
        if (lastSpeedMs > maxSpeedMs && lastSpeedMs <= mode.maxSpeedMs) maxSpeedMs = lastSpeedMs
        acceptedCount++
        segments.lastOrNull()?.add(doubleArrayOf(lat, lng))
        setAnchor(lat, lng, f.timeMs)
        return true
    }

    private fun setAnchor(lat: Double, lng: Double, t: Long) {
        anchorLat = lat; anchorLng = lng; anchorTime = t; hasAnchor = true
        speedIntegralM = 0.0
    }

    companion object {
        fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6371008.8
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2).pow(2) +
                    cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
            return 2 * r * asin(sqrt(a.coerceIn(0.0, 1.0)))
        }
    }
}

/** Kalman filter 1-state sederhana untuk lat/lng (variance dalam meter²). */
class LatLngKalman(private val qMetresPerSecond: Double) {
    var lat = 0.0; private set
    var lng = 0.0; private set
    var timeMs = 0L; private set
    private var variance = -1.0
    val initialized get() = variance >= 0

    fun reset() { variance = -1.0 }

    fun process(mLat: Double, mLng: Double, accuracy: Double, tMs: Long): Pair<Double, Double> {
        val acc = max(accuracy, 1.0)
        if (variance < 0) {
            lat = mLat; lng = mLng; variance = acc * acc; timeMs = tMs
        } else {
            val dt = tMs - timeMs
            if (dt > 0) {
                variance += dt * qMetresPerSecond * qMetresPerSecond / 1000.0
                timeMs = tMs
            }
            val k = variance / (variance + acc * acc)
            lat += k * (mLat - lat)
            lng += k * (mLng - lng)
            variance *= (1 - k)
        }
        return lat to lng
    }
}
