# SuryaVPN -- aturan R8/ProGuard untuk release build.
#
# LATAR BELAKANG (kenapa file ini perlu HATI-HATI, bukan cuma copy-paste
# default): app ini menyambungkan beberapa library yang mengandalkan
# REFLECTION atau JNI native binding, dua hal yang paling sering DIAM-DIAM
# rusak kalau di-shrink/obfuscate tanpa aturan "keep" yang tepat --
# bedanya dengan error compile biasa, ini nyaris selalu BARU KETAHUAN saat
# app sudah jalan di device (mis. "NoSuchMethodError", "ClassNotFoundException",
# atau native lib gagal resolve method), BUKAN saat build.
#
# CATATAN VERIFIKASI (sama semangatnya dengan catatan di SshjTunnelManager.kt):
# aturan di bawah ditulis berdasarkan pengetahuan umum kebutuhan tiap
# library (JCA/security provider butuh reflection utk cari implementasi
# algoritma by name, gomobile butuh keep total classnya, native method
# otomatis di-keep R8 tapi tetap ditulis eksplisit di sini utk jaga-jaga).
# WAJIB coba build release & tes SEMUA jalur koneksi (SSH biasa, SSH SSL,
# Xray) di device fisik sebelum dipakai/dibagikan -- kalau ada crash/error
# yang baru muncul di build release (padahal debug build normal), coba
# nonaktifkan dulu isMinifyEnabled di app/build.gradle.kts utk konfirmasi
# dugaan ini, lalu laporkan pesan errornya (biasanya cuma butuh SATU baris
# "-keep" tambahan utk kelas yang disebut di pesan error itu).

# --- Xray (libXray, dibangun dari Go via gomobile, lihat app/libs/xray.aar) --
# Binding gomobile memanggil balik ke Java/Kotlin lewat nama kelas & method
# PERSIS seperti aslinya dari sisi native Go -- kalau nama itu diacak
# obfuscation, jembatan Go<->Java putus. Package "go.*" adalah runtime
# bantuan gomobile sendiri (bukan kode app), disertakan demi jaga-jaga
# karena beberapa versi men-generate helper class di situ.
-keep class libXray.** { *; }
-keep class go.** { *; }
-dontwarn libXray.**
-dontwarn go.**

# --- Native JNI (tunneljni: hev-socks5-tunnel + config-lock, lihat
# app/src/main/cpp/) -- method dengan modifier `external`/native SEBENARNYA
# sudah otomatis di-keep oleh rule bawaan Android
# (proguard-android-optimize.txt: "keepclasseswithmembernames"), baris di
# bawah cuma penegasan eksplisit supaya tetap aman walau rule bawaan itu
# berubah di versi AGP mendatang.
-keepclasseswithmembernames,includedescriptorclasses class com.example.tunnelapp.tunnel.HevSocks5Bridge {
    native <methods>;
}
-keepclasseswithmembernames,includedescriptorclasses class com.example.tunnelapp.model.ConfigLock {
    native <methods>;
}

# --- sshj (net.schmizz.sshj) -- menegosiasikan algoritma (cipher/KEX/MAC/
# host-key) lewat daftar NamedFactory yang sebagian dicari/didaftarkan
# secara dinamis; di-keep utuh supaya tidak ada satu pun factory algoritma
# yang hilang diam-diam (gejalanya: satu algoritma tertentu tiba-tiba
# "Neither of client and server can be found" padahal debug build normal).
-keep class net.schmizz.sshj.** { *; }
-dontwarn net.schmizz.sshj.**

# --- trilead-ssh2 (com.trilead.ssh2) -- alasan sama seperti sshj di atas.
-keep class com.trilead.ssh2.** { *; }
-dontwarn com.trilead.ssh2.**

# --- Dependency trilead-ssh2-custom-1.0.0.jar (libs/): jbcrypt & eddsa ---
# jbcrypt (org.mindrot) dipakai trilead-ssh2 secara internal.
-keep class org.mindrot.jbcrypt.** { *; }
-dontwarn org.mindrot.jbcrypt.**
# net.i2p.crypto.eddsa dipakai trilead-ssh2 utk ED25519KeyAlgorithm --
# terdaftar sbg JCA Security Provider (EdDSASecurityProvider) & di-lookup
# oleh JCA lewat reflection/nama algoritma ("EdDSA"), BUKAN pemanggilan
# langsung -- pola yang sama persis dengan kenapa Tink & BouncyCastle di
# atas juga butuh -keep penuh. Tanpa ini, R8 release build bisa diam-diam
# menghapus/mengganti nama kelasnya karena dari sudut pandang R8 kelas ini
# terlihat "tidak dipanggil" via referensi langsung.
-keep class net.i2p.crypto.eddsa.** { *; }
-dontwarn net.i2p.crypto.eddsa.**

# --- Bouncy Castle (org.bouncycastle) -- PALING KRITIS: JCA/JCE (java.security.Security)
# mencari implementasi algoritma lewat Class.forName() memakai STRING NAMA
# KELAS PERSIS dari provider yang terdaftar (lihat SshjTunnelManager.
# ensureBouncyCastleRegistered()) -- kalau nama kelasnya diobfuscate, lookup
# itu gagal total dengan "no such algorithm", PERSIS bug yang sudah pernah
# diperbaiki susah payah sebelumnya, jangan sampai muncul lagi gara-gara R8.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# --- Tink / androidx.security-crypto (enkripsi file konfigurasi akun) --
# Tink punya registry internal berbasis reflection utk pasangan
# key-manager/primitive; androidx.security membungkusnya lewat
# EncryptedSharedPreferences & MasterKey.
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**
-keep class androidx.security.crypto.** { *; }

# --- Kotlin coroutines -- pengaman tambahan utk beberapa versi yang pernah
# bermasalah dgn R8 soal continuation/volatile field internal (issue umum
# di banyak proyek, bukan spesifik app ini).
-keepclassmembernames class kotlinx.coroutines.** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.**

# --- View binding (buildFeatures.viewBinding) -- AGP sudah otomatis
# men-generate consumer rule utk kelas *Binding, baris ini cuma jaga-jaga
# tambahan supaya method statis inflate()/bind() tidak pernah terbuang.
-keep class com.example.tunnelapp.databinding.** { *; }

# --- Baris umum: jangan hapus nomor baris stack trace (LineNumberTable) --
# supaya crash report/logcat dari build release TETAP bisa dipetakan balik
# ke baris kode asli (dikombinasikan dgn mapping file R8 kalau perlu),
# bukan cuma nama kelas yang sudah diacak tanpa konteks.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
