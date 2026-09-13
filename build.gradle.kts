// Top-level build file
plugins {
    // NAIK dari 8.5.0 (permintaan user, "kelayakan publish Play Store"):
    // AGP 8.5.0 cuma resmi diuji sampai compileSdk 34 -- sinkronisasi
    // project akan gagal/warning kalau compileSdk di app/build.gradle.kts
    // dinaikkan ke 36 tapi AGP-nya tidak ikut naik. 8.9.2 adalah rilis
    // STABIL pertama yang resmi mendukung compileSdk 36 (Android 16).
    //
    // PENTING: AGP 8.9.x butuh Gradle >= 8.11 -- kalau project ini pakai
    // Gradle Wrapper (gradle/wrapper/gradle-wrapper.properties, TIDAK ada
    // di dalam zip yang diberikan ke saya), naikkan juga distributionUrl
    // wrapper itu ke Gradle 8.11.1 (atau jalankan
    // "./gradlew wrapper --gradle-version 8.11.1" sekali lewat terminal),
    // ATAU kalau buka lewat Android Studio, biarkan "Upgrade Assistant"
    // (Tools > AGP Upgrade Assistant) yang urus otomatis begitu dia
    // mendeteksi versi AGP baru ini.
    id("com.android.application") version "8.9.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}
