package id.gpsmeter

import android.graphics.Color
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Menampilkan garis rute perjalanan di peta OpenStreetMap (tanpa API key). */
class MapActivity : AppCompatActivity() {

    companion object { const val EXTRA_ID = "trip_id" }

    private lateinit var map: MapView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().apply {
            userAgentValue = packageName
            load(this@MapActivity, getSharedPreferences("osmdroid", MODE_PRIVATE))
        }
        setContentView(R.layout.activity_map)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val id = intent.getLongExtra(EXTRA_ID, 0)
        val trip = TripStore.load(this).firstOrNull { it.id == id }
        val segments = TripStore.loadTrack(this, id)

        supportActionBar?.title = trip?.let { Format.distance(it.distanceM) } ?: "Peta"
        findViewById<TextView>(R.id.tvMapInfo).text = trip?.let {
            val d = SimpleDateFormat("d MMM yyyy, HH:mm", Locale("id", "ID")).format(Date(it.id))
            "${it.mode.label} • $d • ${Format.duration(it.elapsedMs)} • rata-rata ${Format.kmh(it.avgSpeedMs)}"
        } ?: ""

        map = findViewById(R.id.map)
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.zoomController.setVisibility(
            org.osmdroid.views.CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT
        )

        val all = mutableListOf<GeoPoint>()
        for (seg in segments) {
            val pts = seg.map { GeoPoint(it[0], it[1]) }
            if (pts.size < 2) continue
            all += pts
            map.overlays.add(Polyline(map).apply {
                setPoints(pts)
                outlinePaint.color = Color.parseColor("#1E88E5")
                outlinePaint.strokeWidth = 12f
                outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
            })
        }

        if (all.isNotEmpty()) {
            addMarker(all.first(), "Mulai", Color.parseColor("#43A047"))
            addMarker(all.last(), "Selesai", Color.parseColor("#E53935"))
            map.post {
                if (all.size == 1) {
                    map.controller.setZoom(17.0); map.controller.setCenter(all.first())
                } else {
                    map.zoomToBoundingBox(BoundingBox.fromGeoPoints(all).increaseByScale(1.3f), false, 64)
                }
            }
        } else {
            map.controller.setZoom(15.0)
        }
    }

    private fun addMarker(p: GeoPoint, title: String, color: Int) {
        val m = Marker(map)
        m.position = p
        m.title = title
        m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        m.icon = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.ic_pin)?.mutate()?.apply {
            setTint(color)
        }
        map.overlays.add(m)
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
    override fun onResume() { super.onResume(); map.onResume() }
    override fun onPause() { super.onPause(); map.onPause() }
}
