package id.gpsmeter

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.*

/**
 * Foreground service: tetap merekam meskipun layar mati / aplikasi diminimalkan.
 */
class TrackingService : Service() {

    companion object {
        const val ACTION_START = "start"
        const val ACTION_PAUSE = "pause"
        const val ACTION_RESUME = "resume"
        const val ACTION_STOP = "stop"
        const val EXTRA_MODE = "mode"
        const val EXTRA_SAVE = "save"
        private const val CHANNEL = "tracking"
        private const val NOTIF_ID = 7

        fun send(ctx: Context, action: String, mode: TrackMode? = null, save: Boolean = true) {
            val i = Intent(ctx, TrackingService::class.java).setAction(action)
            mode?.let { i.putExtra(EXTRA_MODE, it.name) }
            i.putExtra(EXTRA_SAVE, save)
            if (action == ACTION_START) ctx.startForegroundService(i) else ctx.startService(i)
        }
    }

    private lateinit var fused: FusedLocationProviderClient
    private var tracker: DistanceTracker? = null
    private var mode = TrackMode.WALK
    private var accumulatedMs = 0L
    private var startWallTime = 0L
    private var runningSince = 0L
    private var wakeLock: PowerManager.WakeLock? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var ticker: Job? = null

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val t = tracker ?: return
            for (loc in result.locations) {
                t.onFix(
                    Fix(
                        lat = loc.latitude,
                        lng = loc.longitude,
                        accuracyM = if (loc.hasAccuracy()) loc.accuracy else 999f,
                        speedMs = if (loc.hasSpeed()) loc.speed else null,
                        timeMs = loc.elapsedRealtimeNanos / 1_000_000
                    )
                )
            }
            publish(hasFix = true)
        }
    }

    override fun onCreate() {
        super.onCreate()
        fused = LocationServices.getFusedLocationProviderClient(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                mode = intent.getStringExtra(EXTRA_MODE)?.let { TrackMode.valueOf(it) } ?: TrackMode.WALK
                tracker = DistanceTracker(mode)
                accumulatedMs = 0
                startWallTime = System.currentTimeMillis()
                startFg()
                startUpdates()
            }
            ACTION_PAUSE -> {
                stopUpdates()
                accumulatedMs += SystemClock.elapsedRealtime() - runningSince
                TrackingRepo.state.value = TrackingRepo.state.value.copy(status = Status.PAUSED, speedMs = 0.0)
                updateNotification()
            }
            ACTION_RESUME -> {
                tracker?.resetAnchor()
                startUpdates()
            }
            ACTION_STOP -> {
                val wasRunning = TrackingRepo.state.value.status == Status.RUNNING
                stopUpdates()
                if (wasRunning) accumulatedMs += SystemClock.elapsedRealtime() - runningSince
                val t = tracker
                if (intent.getBooleanExtra(EXTRA_SAVE, true) && t != null && t.totalM >= 1.0) {
                    TripStore.add(
                        this, Trip(
                            id = startWallTime,
                            endTime = System.currentTimeMillis(),
                            mode = mode,
                            distanceM = t.totalM,
                            elapsedMs = accumulatedMs,
                            movingMs = t.movingMs,
                            maxSpeedMs = t.maxSpeedMs
                        )
                    )
                    TripStore.saveTrack(this, startWallTime, t.segments)
                }
                tracker = null
                TrackingRepo.state.value = TrackingRepo.state.value.copy(status = Status.IDLE, speedMs = 0.0)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startFg() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else startForeground(NOTIF_ID, n)
    }

    @SuppressLint("MissingPermission", "WakelockTimeout")
    private fun startUpdates() {
        runningSince = SystemClock.elapsedRealtime()
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, mode.intervalMs)
            .setMinUpdateIntervalMillis(mode.intervalMs / 2)
            .setWaitForAccurateLocation(true)
            .setMinUpdateDistanceMeters(0f)
            .build()
        fused.requestLocationUpdates(req, callback, Looper.getMainLooper())

        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GpsMeter::track").apply { acquire() }

        TrackingRepo.state.value = TrackingRepo.state.value.copy(status = Status.RUNNING, mode = mode)
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) { publish(); delay(1000) }
        }
    }

    private fun stopUpdates() {
        fused.removeLocationUpdates(callback)
        ticker?.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun publish(hasFix: Boolean? = null) {
        val t = tracker ?: return
        val cur = TrackingRepo.state.value
        if (cur.status != Status.RUNNING) return
        TrackingRepo.state.value = cur.copy(
            distanceM = t.totalM,
            elapsedMs = accumulatedMs + (SystemClock.elapsedRealtime() - runningSince),
            movingMs = t.movingMs,
            speedMs = t.lastSpeedMs,
            maxSpeedMs = t.maxSpeedMs,
            accuracyM = t.lastAccuracy,
            hasFix = hasFix ?: cur.hasFix
        )
        updateNotification()
    }

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL, "Perekaman jarak", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        val s = TrackingRepo.state.value
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val title = if (s.status == Status.PAUSED) "Dijeda" else "Merekam (${mode.label})"
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(title)
            .setContentText("${Format.distance(s.distanceM)} • ${Format.duration(s.elapsedMs)}")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }

    override fun onDestroy() {
        stopUpdates()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
