package id.gpsmeter

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButtonToggleGroup
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var tvDistance: TextView
    private lateinit var tvTime: TextView
    private lateinit var tvSpeed: TextView
    private lateinit var tvAvg: TextView
    private lateinit var tvMax: TextView
    private lateinit var tvPace: TextView
    private lateinit var tvGps: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var modeGroup: MaterialButtonToggleGroup

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { res ->
        if (res[Manifest.permission.ACCESS_FINE_LOCATION] == true) startTracking()
        else Toast.makeText(this, "Izin lokasi akurat (presisi) diperlukan", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        tvDistance = findViewById(R.id.tvDistance)
        tvTime = findViewById(R.id.tvTime)
        tvSpeed = findViewById(R.id.tvSpeed)
        tvAvg = findViewById(R.id.tvAvg)
        tvMax = findViewById(R.id.tvMax)
        tvPace = findViewById(R.id.tvPace)
        tvGps = findViewById(R.id.tvGps)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        modeGroup = findViewById(R.id.modeGroup)
        modeGroup.check(R.id.btnWalk)

        btnStart.setOnClickListener {
            when (TrackingRepo.state.value.status) {
                Status.IDLE -> checkAndStart()
                Status.RUNNING -> TrackingService.send(this, TrackingService.ACTION_PAUSE)
                Status.PAUSED -> TrackingService.send(this, TrackingService.ACTION_RESUME)
            }
        }
        btnStop.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Selesai merekam?")
                .setMessage("Jarak terakhir: ${Format.distance(TrackingRepo.state.value.distanceM)}")
                .setPositiveButton("Selesai") { _, _ -> TrackingService.send(this, TrackingService.ACTION_STOP) }
                .setNegativeButton("Batal", null)
                .show()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                TrackingRepo.flow.collect { render(it) }
            }
        }
    }

    private fun selectedMode() =
        if (modeGroup.checkedButtonId == R.id.btnDrive) TrackMode.DRIVE else TrackMode.WALK

    private fun checkAndStart() {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            Toast.makeText(this, "Aktifkan GPS/Lokasi dulu", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            return
        }
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        if (fine == PackageManager.PERMISSION_GRANTED) startTracking()
        else permLauncher.launch(perms.toTypedArray())
    }

    private fun startTracking() {
        TrackingRepo.state.value = TrackState(mode = selectedMode())
        TrackingService.send(this, TrackingService.ACTION_START, selectedMode())
    }

    private fun render(s: TrackState) {
        tvDistance.text = Format.distance(s.distanceM)
        tvTime.text = Format.duration(s.elapsedMs)
        tvSpeed.text = Format.kmh(s.speedMs)
        val avg = if (s.movingMs > 0) s.distanceM / (s.movingMs / 1000.0) else 0.0
        tvAvg.text = Format.kmh(avg)
        tvMax.text = Format.kmh(s.maxSpeedMs)
        tvPace.text = Format.pace(s.distanceM, s.movingMs)

        tvGps.text = when {
            s.status == Status.IDLE -> "GPS siap — pilih mode lalu tekan Mulai"
            !s.hasFix -> "Mencari sinyal GPS… (sebaiknya di tempat terbuka)"
            s.accuracyM <= 8 -> "GPS sangat baik (±%.0f m)".format(s.accuracyM)
            s.accuracyM <= s.mode.maxAccuracyM -> "GPS cukup (±%.0f m)".format(s.accuracyM)
            else -> "GPS lemah (±%.0f m) — titik diabaikan".format(s.accuracyM)
        }

        btnStart.text = when (s.status) {
            Status.IDLE -> "Mulai"
            Status.RUNNING -> "Jeda"
            Status.PAUSED -> "Lanjut"
        }
        btnStop.visibility = if (s.status == Status.IDLE) View.GONE else View.VISIBLE
        val idle = s.status == Status.IDLE
        for (i in 0 until modeGroup.childCount) modeGroup.getChildAt(i).isEnabled = idle
    }
}
