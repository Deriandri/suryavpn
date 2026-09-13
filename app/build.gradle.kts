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
            isMinifyEnabled = false
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
    // Engine SSH: trilead-ssh2. PENTING: pakai fork "jenkinsci/trilead-ssh2"
    // (dipelihara aktif, dipakai Jenkins sendiri untuk SSH ke ribuan server
    // produksi), BUKAN "com.trilead:trilead-ssh2:1.0.0-build222" yang merupakan
    // build lama ±2015 dan TIDAK mendukung algoritma modern (ed25519, ECDSA,
    // curve25519-sha256, rsa-sha2-256/512, cipher CTR, MAC -etm@openssh.com).
    // Server SSH modern yang sudah mematikan algoritma lama akan gagal total
    // di tahap key-exchange dengan pesan generik "There was a problem while
    // connecting to ..." -- persis error yang tadinya muncul.
    // Ambil dari repo Maven resmi Jenkins (org.jenkins-ci), BUKAN dari JitPack
    // (com.github.jenkinsci) -- JitPack sempat dicoba tapi gagal di GitHub
    // Actions karena JitPack baru compile versi ini saat diminta pertama kali
    // (builds on-demand), gampang timeout/gagal di CI. Paket Java-nya tetap
    // sama (com.trilead.ssh2.*), jadi tidak perlu ubah kode Kotlin manapun.
    implementation("org.jenkins-ci:trilead-ssh2:build-217-jenkins-293.v56de4d4d3515")

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
