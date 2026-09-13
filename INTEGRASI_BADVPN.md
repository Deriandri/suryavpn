# Integrasi Tunnel Engine Kedua (badvpn-tun2socks)

Yang SUDAH selesai ditulis (tidak butuh apa-apa lagi kalau asumsi di bawah benar):
- `TunEngine.kt` -- doc diupdate, kontrak tidak berubah sama sekali (interface-nya
  persis sama, jadi `BadVpnEngine` 100% drop-in sama seperti `HevSocks5Engine`)
- `BadVpnEngine.kt` -- implementasi `TunEngine` yang menjalankan
  `libbadvpn-tun2socks.so` sebagai PROSES TERPISAH lewat `ProcessBuilder`
  (BEDA arsitektur dari `HevSocks5Engine` yang JNI/satu proses -- lihat
  komentar panjang di file itu kenapa)
- `TunEngineRouter.kt` -- dispatcher, pola identik `SshEngineRouter`
- `VpnSettings.tunEngine` (`TUN_ENGINE_HEV` default / `TUN_ENGINE_BADVPN`) +
  persistensinya di `VpnSettingsStore`
- UI: toggle "Tunnel Engine" di kartu "VPN Setting" (`activity_settings.xml`
  + `SettingsActivity.kt`), independen dari toggle "SSH Engine" yang sudah ada
- `MyVpnService.startTunEngine()` -- diganti dari `HevSocks5Engine()` langsung
  jadi `TunEngineRouter(this)`, satu baris, titik pemanggilan lain tidak berubah
- `app/src/main/cpp/CMakeLists.txt` -- step build badvpn-tun2socks dari source
  (ExternalProject_Add) + copy hasilnya ke `app/src/main/jniLibs/<ABI>/libbadvpn-tun2socks.so`

Yang BELUM bisa saya pastikan 100% di sini (butuh akses jaringan buat clone
`ambrop72/badvpn` dan compile beneran, yang tidak tersedia di sandbox saya),
dan jadi tugas kamu SEBELUM rilis ke user:

## 1. Coba build dulu, baca error-nya kalau merah

`CMakeLists.txt` sudah saya tulis supaya proses `git clone` + `cmake` badvpn
otomatis jalan pas kamu build project ini (persis seperti hev-socks5-tunnel
yang sudah ada) -- TIDAK perlu langkah manual seperti `xray.aar` di
`INTEGRASI_XRAY.md`. Tapi ada 3 asumsi yang saya TIDAK bisa verifikasi
tanpa jaringan, ditandai jelas di komentar `CMakeLists.txt`:

1. **Nama opsi CMake** `BUILDING_NOTHING_BY_DEFAULT` / `BUILD_TUN2SOCKS` --
   ini nama opsi yang saya ingat dari struktur project badvpn, tapi BISA SAJA
   beda persis di tag `1.999.130` yang saya pin. Kalau build gagal dengan
   pesan semacam "Manually-specified variables were not used", cek isi
   `CMakeLists.txt` asli badvpn (`cat` langsung dari source yang ter-clone di
   `app/.cxx/.../badvpn-src/badvpn/CMakeLists.txt` setelah percobaan build
   pertama) untuk nama opsi yang benar, lalu sesuaikan.
2. **Path binary hasil build** (`BADVPN_BUILT_BINARY`, saya tebak
   `tun2socks/badvpn-tun2socks` relatif ke build dir) -- kalau step
   `add_custom_command` gagal "file tidak ditemukan", cari manual dengan
   `find app/.cxx -name "*tun2socks*" -type f` setelah build gagal di step
   itu (ExternalProject sendiri akan sudah selesai walau copy-nya gagal),
   lalu perbaiki path-nya.
3. **Tag rilis `1.999.130`** -- cek dulu tag ini masih ada di
   https://github.com/ambrop72/badvpn/tags. Kalau sudah tidak ada, ganti ke
   tag/commit terbaru yang tersedia.

## 2. Cocokkan argumen CLI di `BadVpnEngine.kt`

Setelah berhasil build, dapatkan binary-nya (`app/src/main/jniLibs/<ABI>/libbadvpn-tun2socks.so`)
dan jalankan `--help`-nya (lewat `adb push` + `adb shell` ke device/emulator
dengan ABI yang sama, TIDAK bisa dijalankan langsung di komputer dev kecuali
arch-nya kebetulan cocok). Cocokkan satu-satu ke argumen yang dipakai
`BadVpnEngine.buildArgs()`:
- `--tundev`, `--netif-ipaddr`, `--netif-netmask`, `--socks-server-addr`,
  `--udpgw-remote-server-addr`, `--loglevel` -- ini yang saya cukup yakin
  namanya benar (dokumentasi publik badvpn-tun2socks cukup konsisten soal
  ini di banyak sumber), tapi TETAP cek ejaan/format persis (mis. apakah
  `--socks-server-addr` mau format `host:port` atau dua argumen terpisah).
- Trik `--tundev /proc/self/fd/<N>` (supaya tidak perlu root/tun device path
  asli) -- ini teknik yang umum dipakai integrasi sejenis di Android, tapi
  BELUM saya coba langsung terhadap versi badvpn yang di-pin di sini. Kalau
  gagal dengan error semacam "Bad file descriptor" atau "Permission denied"
  pas `open()`, ini titik pertama yang perlu diperiksa.

## 3. Testing

- Jangan langsung release ke user -- coba dulu di device/emulator ABI yang
  sama dengan yang kamu build (`arm64-v8a` atau `armeabi-v7a` sesuai
  `abiFilters` di `app/build.gradle.kts`), pilih "badvpn-tun2socks" di
  toggle Tunnel Engine, coba connect ke server yang KAMU tahu jalan dulu.
- Kalau "connect" tapi internet tidak jalan sama sekali -> kemungkinan besar
  argumen CLI di poin 2 di atas belum benar, ATAU trik `/proc/self/fd/`
  gagal diam-diam (cek Logcat tag `BadVpnEngine`, semua stdout/stderr proses
  child dipompa ke sana).
- Kalau proses child langsung exit kode non-nol begitu start -> baca baris
  Logcat SEBELUM baris "badvpn-tun2socks berhenti, kode: N" itu -- badvpn
  biasanya mencetak alasan error CLI ke stdout/stderr sebelum keluar.
- UDPGW: kalau `VpnSettings.udpgwPort` diisi, `BadVpnEngine` meneruskannya
  lewat `--udpgw-remote-server-addr` bawaan badvpn-tun2socks sendiri (BUKAN
  lewat `UdpgwClient.kt` custom yang dipakai jalur lain) -- pastikan
  `badvpn-udpgw` beneran jalan & listen di port itu pada server SSH sebelum
  menyalahkan engine ini kalau UDP tidak jalan.

## Kenapa proses terpisah, bukan JNI kayak hev-socks5-tunnel?

Upstream `badvpn` tidak menyediakan API "library" (start/stop function) sama
sekali -- dia murni program CLI dengan `main()` yang berhenti lewat sinyal
(SIGTERM). Menulis wrapper JNI yang membungkus internal reactor loop-nya
(`BReactor`, dst.) untuk dipanggil dalam-proses seperti hev-socks5-tunnel
BUTUH modifikasi source badvpn itu sendiri dan verifikasi mendalam terhadap
detail internal API-nya yang saya TIDAK bisa lakukan dengan aman tanpa akses
untuk membaca source aslinya baris per baris di sandbox ini -- resikonya
salah menebak nama fungsi/struct internal dan menghasilkan kode yang
kelihatan benar tapi diam-diam salah. Menjalankannya sebagai proses terpisah
(exec biasa + SIGTERM utk stop) jauh lebih aman diverifikasi tanpa akses
source lengkap, dan merupakan teknik yang sudah lama dipakai banyak app
tunnel Android lain untuk badvpn-tun2socks/badvpn-udpgw.
