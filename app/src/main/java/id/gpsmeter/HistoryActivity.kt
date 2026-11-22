package id.gpsmeter

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryActivity : AppCompatActivity() {

    private lateinit var list: ListView
    private lateinit var empty: TextView
    private lateinit var summary: TextView
    private var trips: List<Trip> = emptyList()
    private val dateFmt = SimpleDateFormat("EEE, d MMM yyyy • HH:mm", Locale("id", "ID"))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)
        supportActionBar?.title = "Riwayat Perjalanan"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        list = findViewById(R.id.list)
        empty = findViewById(R.id.tvEmpty)
        summary = findViewById(R.id.tvSummary)
        list.emptyView = empty

        list.setOnItemClickListener { _, _, pos, _ -> showDetail(trips[pos]) }
        list.setOnItemLongClickListener { _, _, pos, _ -> confirmDelete(trips[pos]); true }
        reload()
    }

    private fun reload() {
        trips = TripStore.load(this)
        list.adapter = Adapter()
        val total = trips.sumOf { it.distanceM }
        summary.visibility = if (trips.isEmpty()) View.GONE else View.VISIBLE
        summary.text = "${trips.size} perjalanan • total ${Format.distance(total)}"
        invalidateOptionsMenu()
    }

    private fun showDetail(t: Trip) {
        val msg = buildString {
            appendLine("Mode: ${t.mode.label}")
            appendLine("Mulai: ${dateFmt.format(Date(t.id))}")
            appendLine("Selesai: ${dateFmt.format(Date(t.endTime))}")
            appendLine()
            appendLine("Jarak: ${Format.distance(t.distanceM)}")
            appendLine("Durasi: ${Format.duration(t.elapsedMs)}")
            appendLine("Waktu bergerak: ${Format.duration(t.movingMs)}")
            appendLine("Rata-rata: ${Format.kmh(t.avgSpeedMs)}")
            appendLine("Maksimum: ${Format.kmh(t.maxSpeedMs)}")
            append("Pace: ${Format.pace(t.distanceM, t.movingMs)}")
        }
        AlertDialog.Builder(this)
            .setTitle(Format.distance(t.distanceM))
            .setMessage(msg)
            .setPositiveButton("Tutup", null)
            .setNegativeButton("Hapus") { _, _ -> confirmDelete(t) }
            .show()
    }

    private fun confirmDelete(t: Trip) {
        AlertDialog.Builder(this)
            .setTitle("Hapus perjalanan ini?")
            .setMessage("${dateFmt.format(Date(t.id))}\n${Format.distance(t.distanceM)} (${t.mode.label})")
            .setPositiveButton("Hapus") { _, _ ->
                TripStore.delete(this, t.id)
                Toast.makeText(this, "Perjalanan dihapus", Toast.LENGTH_SHORT).show()
                reload()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (trips.isNotEmpty()) menu.add(0, 1, 0, "Hapus semua")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> { finish(); return true }
            1 -> {
                AlertDialog.Builder(this)
                    .setTitle("Hapus semua riwayat?")
                    .setMessage("${trips.size} perjalanan akan dihapus permanen.")
                    .setPositiveButton("Hapus semua") { _, _ -> TripStore.clear(this); reload() }
                    .setNegativeButton("Batal", null)
                    .show()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = trips.size
        override fun getItem(p: Int) = trips[p]
        override fun getItemId(p: Int) = trips[p].id
        override fun getView(p: Int, convert: View?, parent: ViewGroup): View {
            val v = convert ?: LayoutInflater.from(parent.context).inflate(R.layout.item_trip, parent, false)
            val t = trips[p]
            v.findViewById<TextView>(R.id.tvIcon).text = if (t.mode == TrackMode.DRIVE) "🚗" else "🚶"
            v.findViewById<TextView>(R.id.tvDist).text = Format.distance(t.distanceM)
            v.findViewById<TextView>(R.id.tvDate).text = dateFmt.format(Date(t.id))
            v.findViewById<TextView>(R.id.tvInfo).text =
                "${Format.duration(t.elapsedMs)} • rata-rata ${Format.kmh(t.avgSpeedMs)}"
            return v
        }
    }
}
