pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // PENTING: JitPack (com.github.jenkinsci:...) sebelumnya dicoba tapi
        // GAGAL di GitHub Actions -- "Could not resolve
        // com.github.jenkinsci:trilead-ssh2:build-217-jenkins-293...".
        // JitPack build artifact on-demand (baru compile saat pertama kali
        // ada yang minta versi itu), jadi gampang gagal/timeout di CI.
        // Solusi lebih stabil: Jenkins sendiri sudah mem-publish binary
        // jadinya (bukan source yang perlu dibuild lagi) di repo Maven
        // resmi mereka -- tinggal didownload langsung, tidak perlu nunggu
        // proses build apa pun.
        maven { url = uri("https://repo.jenkins-ci.org/public/") }
    }
}

rootProject.name = "TunnelApp"
include(":app")
