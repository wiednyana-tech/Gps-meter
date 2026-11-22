# GPS Meter — pengukur jarak jalan kaki / berkendara

## Cara build
**Opsi A – Android Studio:** buka folder ini (File > Open), tunggu Gradle sync, lalu
Build > Build APK(s). APK ada di `app/build/outputs/apk/`.

**Opsi B – tanpa Android Studio (GitHub Actions):** upload folder ini ke repo GitHub
baru. Tab *Actions* akan otomatis build; unduh APK dari bagian *Artifacts*.

## Fitur
- Mode Jalan kaki & Berkendara (parameter filter berbeda)
- Jarak, durasi, kecepatan, rata-rata, maksimum, pace
- Tetap merekam saat layar mati (foreground service + notifikasi)
- Jeda / lanjut tanpa menghitung jarak selama jeda

## Cara akurasi dijaga (`DistanceTracker.kt`)
1. Fix dengan akurasi buruk dibuang (>20 m jalan, >30 m kendara)
2. 3 fix pertama untuk pemanasan
3. Kalman filter menghaluskan posisi
4. Lompatan dengan kecepatan mustahil ditolak
5. Deteksi diam: jarak tidak bertambah saat berhenti (anti-drift)
6. Jarak ditambahkan per "titik jangkar" (≥3 m jalan / ≥6 m kendara)

## Tips pemakaian
- Izinkan lokasi **Presisi**, bukan "Perkiraan"
- Matikan optimasi baterai untuk aplikasi ini (Setelan > Aplikasi > GPS Meter > Baterai > Tidak dibatasi),
  terutama di Xiaomi/Oppo/Vivo
- Tunggu status "GPS sangat baik" sebelum mulai jalan
