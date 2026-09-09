# Integrasi Metode Xray (VMess/VLESS/Trojan)

Yang SUDAH selesai ditulis (tidak butuh apa-apa lagi):
- `ConnectionMode.XRAY` + field `ServerConfig.xrayLink`
- `XrayLinkParser.kt` — parse link `vmess://`, `vless://`, `trojan://` (murni Kotlin,
  tidak bergantung ke method konversi bawaan libXray)
- `XrayConfigBuilder.kt` — susun JSON config Xray-core (1 inbound SOCKS5 di
  `127.0.0.1:<socksPort>`, 1 outbound sesuai link)
- UI: chip "Xray (VMess/VLESS/Trojan)" + field link, otomatis menyembunyikan
  field host/port/user/pass/SNI/TLS/payload/proxy punya jalur SSH
- Pipeline TUN tidak berubah: `hev-socks5-tunnel` tetap membaca TUN lalu
  forward sebagai SOCKS5 ke port yang sama — cuma "penyedia" SOCKS5-nya
  yang beda (Xray-core, bukan `SshTunnelManager`)

Yang BELUM bisa saya selesaikan di sini (butuh akses jaringan/compile Go yang
tidak tersedia di sandbox saya), dan jadi tugas kamu:

## 1. Dapatkan `xray.aar`

Compile dari proyek resmi **XTLS/libXray**: https://github.com/XTLS/libXray

```bash
git clone https://github.com/XTLS/libXray
cd libXray
python3 build/main.py android
```

Hasilnya berupa AAR. Letakkan sebagai `app/libs/xray.aar`.

## 2. Aktifkan dependency

Di `app/build.gradle.kts`, uncomment baris:
```kotlin
implementation(files("libs/xray.aar"))
```

## 3. Cek ulang binding di `XrayTunnelManager.kt`

Ini bagian paling penting untuk diverifikasi — saya tulis berdasarkan
dokumentasi API publik libXray per hari ini (single entrypoint
`LibXray.invoke(requestJson): String`, request `{"apiVersion":1,"method":"runXray",...}`),
tapi **nama package Kotlin hasil build** (`import libXray.LibXray`) tergantung
persis bagaimana AAR itu di-generate `gomobile bind`, jadi:

1. Buka `xray.aar` (itu file ZIP) atau `classes.jar` di dalamnya, cek nama
   package sebenarnya dari class `LibXray`.
2. Sesuaikan baris `import` & pemanggilan di `XrayTunnelManager.invokeLibXray()`.
3. Cek juga bagian `registerProtect()` — ini yang paling mungkin beda nama
   antar versi build. Socket yang dibuka Xray-core (Go) ke server VMess/
   VLESS/Trojan **wajib** di-`VpnService.protect()`, kalau tidak semua
   trafik akan loop balik ke TUN sendiri dan tunnel gagal total (macet,
   bukan error yang jelas). Cari interface Go bernama semacam `Controller`
   dengan method protect-fd di AAR-mu, implementasikan, lalu daftarkan
   sebelum memanggil `runXray` (lihat contoh resmi di README libXray:
   `LibXray.setDNS(controller, "8.8.8.8:53")`).

## 4. Testing

- Coba dulu dengan link VMess/VLESS/Trojan yang KAMU tahu jalan (mis. dari
  akun langganan sendiri) sebelum dipakai user lain.
- Kalau tunnel "connect" tapi internet tidak jalan sama sekali → kemungkinan
  besar `registerProtect()` belum benar (socket Xray ikut ke-loop ke TUN).
- Kalau `LibXray.invoke()` melempar exception saat load class → nama
  package/class di `import` belum sesuai AAR asli.

## Kenapa tidak pakai `geoip.dat`/`geosite.dat`?

Config yang disusun `XrayConfigBuilder` sengaja tanpa objek `routing` sama
sekali (semua trafik lewat satu outbound), jadi geo asset tidak dibutuhkan.
Baru wajib disiapkan kalau nanti mau nambah split-tunneling berbasis
domain/IP (mis. bypass domain lokal).
