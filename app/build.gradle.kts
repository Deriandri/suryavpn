plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.tunnelapp"
    compileSdk = 34
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.example.tunnelapp"
        minSdk = 28        // Android 9.0
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        externalNativeBuild {
            cmake {
                // Batasi ke ABI perangkat fisik yang realistis dipakai, biar
                // waktu build di GitHub Actions tidak bengkak (tiap ABI
                // meng-clone & compile ulang hev-socks5-tunnel dari nol).
                // Tambahkan "x86_64" di sini kalau mau tes di emulator.
                abiFilters += listOf("arm64-v8a", "armeabi-v7a")
            }
        }

        // PENTING: samakan dengan abiFilters cmake di atas. xray.aar (Go/gomobile)
        // membawa libgojni.so untuk 4 ABI (arm64-v8a, armeabi-v7a, x86, x86_64),
        // tapi hev-socks5-tunnel (cmake di atas) cuma di-build utk 2 ABI fisik.
        // Tanpa baris ini, Gradle bakal ikut mem-package libgojni.so x86/x86_64
        // dari xray.aar TANPA libtunneljni.so pasangannya -- APK tetap terpasang
        // di emulator x86_64 tapi UnsatisfiedLinkError begitu tunnel dimulai.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            // DIAKTIFKAN (permintaan user, audit performa "apakah sudah
            // maksimal"): SEBELUMNYA false total -- APK release tidak
            // pernah di-shrink/obfuscate/optimize sama sekali, padahal app
            // ini sudah cukup besar (trilead-ssh2 + sshj + Bouncy Castle +
            // xray.aar Go runtime + hev-socks5-tunnel native).
            //
            // PENTING -- WAJIB DIBACA sebelum build/rilis: app ini banyak
            // pakai reflection (JCA/BouncyCastle cari algoritma by nama
            // kelas) & JNI/gomobile (libXray, tunneljni) -- dua hal yang
            // paling rawan diam-diam rusak kalau di-shrink tanpa aturan
            // "keep" yang tepat. Lihat app/proguard-rules.pro utk daftar
            // lengkap keep rules yang sudah disiapkan (Bouncy Castle, sshj,
            // trilead-ssh2, libXray/go, Tink, JNI native methods, dst) --
            // TAPI ini ditulis dari pengetahuan umum kebutuhan tiap
            // library, BUKAN hasil verifikasi build+run sungguhan (lingkungan
            // penyusunan ini tidak punya Android SDK/Gradle utk compile
            // APK). WAJIB: build release APK ini & tes SEMUA jalur koneksi
            // (SSH biasa, SSH SSL, Xray) di device fisik SEBELUM dipakai
            // atau dibagikan -- kalau ada crash/error baru yang HANYA
            // muncul di build release (build debug normal), itu tandanya
            // ada satu "-keep" lagi yang kurang, bukan bug fungsi lain.
            // Kalau ragu/mendesak, set isMinifyEnabled kembali ke false
            // dulu sampai sempat ditest.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }
}

// FIX (error build: "Duplicate class com.google.crypto.tink.* found in modules
// tink-1.12.0.jar ... dan tink-android-1.8.0.jar ..."):
// androidx.security:security-crypto (di bawah) sudah membawa
// com.google.crypto.tink:tink-android:1.8.0 sebagai dependency bawaannya.
// Salah satu dependency lain di proyek ini (kemungkinan besar libs/xray.aar,
// yang menyertakan library Tink versi non-Android untuk fitur VLESS
// Encryption/ML-KEM) juga membawa com.google.crypto.tink:tink:1.12.0. Kedua
// artifact ini punya nama class Java yang SAMA PERSIS (com.google.crypto.tink.*),
// sehingga tugas ':app:checkDebugDuplicateClasses' menganggapnya bentrok dan
// build gagal. Solusinya: buang salah satu (variant "tink" biasa) dari SEMUA
// configuration, supaya yang tersisa cuma "tink-android" (dipakai oleh
// security-crypto untuk EncryptedSharedPreferences lewat Android Keystore).
configurations.all {
    exclude(group = "com.google.crypto.tink", module = "tink")
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")

    // --- FITUR BARU (permintaan user, "enkripsi file konfig"): kunci master
    // AES256-GCM disimpan di Android Keystore (bukan file biasa) lewat
    // MasterKey, dipakai EncryptedSharedPreferences untuk mengenkripsi file
    // penyimpanan akun (lihat model/SecurePrefsFactory.kt & ProfileStore.kt).
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // --- Dashboard geser-kesamping (swipe) Main <-> Log ---
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.fragment:fragment-ktx:1.8.2")

    // --- Modul SSH (tahap 2) ---
    // Engine SSH: trilead-ssh2, versi "custom" dari libs/trilead-ssh2-custom-1.0.0.jar
    // (bukan lagi dependency Maven org.jenkins-ci) -- GANTI ATAS PERMINTAAN USER,
    // memakai file jar yang dilampirkan langsung. Paket Java-nya tetap
    // com.trilead.ssh2.* (identik dengan versi Maven sebelumnya), jadi TIDAK ADA
    // kode Kotlin yang perlu diubah (SshTunnelManager.kt, dst. tetap sama persis).
    //
    // PENTING -- jar yang dilampirkan aslinya adalah "fat jar" ~9.3MB yang men-
    // shade beberapa library lain di dalamnya selain com.trilead.ssh2.* & jbcrypt
    // (org.mindrot) & net.i2p.crypto.eddsa (dipakai utk ED25519KeyAlgorithm --
    // ini TETAP disertakan karena tidak tersedia dari dependency lain manapun di
    // proyek ini): com.google.gson, com.google.protobuf, dan com.google.crypto.tink
    // (dipakai HANYA oleh satu kelas, Curve25519Exchange, utk curve25519-sha256
    // key exchange via com.google.crypto.tink.subtle.X25519). Tiga paket google.*
    // itu SUDAH DIBUANG dari jar sebelum ditaruh di libs/ (lihat ukuran jadi
    // ~430KB) supaya TIDAK bentrok "Duplicate class com.google.crypto.tink.*"
    // dengan tink-android 1.8.0 yang sudah dibawa androidx.security:security-crypto
    // (persis masalah yang sudah pernah muncul & di-exclude utk xray.aar di bawah).
    // gson & protobuf dibuang karena tidak dipakai sama sekali oleh kode
    // trilead/jbcrypt/eddsa itu sendiri (cuma ikut ke-bundle dari build shade-nya).
    //
    // KONSEKUENSI: Curve25519Exchange (key exchange curve25519-sha256) jadi
    // BERGANTUNG pada tink-android dari security-crypto di atas untuk menyediakan
    // com.google.crypto.tink.subtle.X25519 saat runtime. Kalau suatu saat
    // dependency security-crypto itu dihapus/diganti, key exchange curve25519-sha256
    // akan gagal dengan NoClassDefFoundError (algoritma SSH lain tidak terpengaruh).
    // Proguard sudah aman: proguard-rules.pro baris ~72 sudah -keep seluruh
    // com.google.crypto.tink.** (awalnya ditulis utk security-crypto, otomatis
    // ikut melindungi pemakaian dari Curve25519Exchange ini juga).
    implementation(files("libs/trilead-ssh2-custom-1.0.0.jar"))

    // --- Engine SSH KEDUA (permintaan user): sshj ---
    // Dipilih dibanding Apache MINA SSHD (paling lengkap tapi berbasis NIO
    // gaya server, riwayat rewel di Android) karena API-nya blocking/socket
    // biasa (SSHClient.connect ke host:port, cocok dipasangkan ke
    // ConnectRelay lokal yang sama seperti trilead-ssh2 di atas) DAN sudah
    // terbukti dipakai di banyak app Android production. Beda dari trilead-
    // ssh2, sshj BENERAN mendukung kompresi zlib/zlib@openssh.com lewat
    // SSHClient.useCompression() -- lihat SshjTunnelManager & catatan
    // VpnSettingsStore.compressionEnabled.
    implementation("com.hierynomus:sshj:0.38.0")
    // sshj pakai SLF4J utk logging -- tanpa binding, log-nya cuma "no-op"
    // (aman, TIDAK crash), tapi slf4j-android di bawah ini meneruskannya ke
    // Logcat (memudahkan debugging engine ini).
    implementation("org.slf4j:slf4j-android:1.7.36")
    // FIX (laporan user, error nyata: "no such algorithm: X25519 for
    // provider BC", lalu "no such algorithm: EC for provider BC"): provider
    // JCE bernama "BC" BAWAAN ANDROID ternyata sangat terbatas (bukan
    // Bouncy Castle asli/lengkap seperti di JVM desktop) -- sshj berulang
    // kali minta provider bernama PERSIS "BC" utk berbagai operasi kripto.
    // Dependency ini didaftarkan MENGGANTIKAN provider "BC" bawaan Android
    // saat runtime (lihat SshjTunnelManager.ensureBouncyCastleRegistered())
    // supaya SEMUA algoritma yang diminta sshj benar-benar tersedia. AMAN
    // dari konflik kelas duplikat dengan Android sendiri -- implementasi
    // internal Android ada di package berbeda (com.android.org.bouncycastle),
    // bukan org.bouncycastle seperti library resmi ini.
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    // --- Modul Xray (tahap 3) ---
    // 1. Ambil/compile xray.aar dari proyek resmi XTLS/libXray (https://github.com/XTLS/libXray),
    //    letakkan hasilnya di app/libs/xray.aar.
    // 2. Baru aktifkan baris di bawah ini (uncomment) supaya ikut ter-compile.
    // 3. Cek ulang import "libXray.LibXray" di XrayTunnelManager.kt sesuai package
    //    sebenarnya di AAR yang kamu pakai -- lihat komentar panjang di kepala file itu.
    implementation(files("libs/xray.aar"))

    // Coroutines, untuk operasi jaringan di background thread
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
