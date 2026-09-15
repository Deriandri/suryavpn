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
        // Repo Jenkins (dulu dipakai utk org.jenkins-ci:trilead-ssh2) SUDAH
        // TIDAK DIPERLUKAN -- engine trilead sekarang pakai jar lokal di
        // app/libs/trilead-ssh2-custom-1.0.0.jar (lihat komentar dependency
        // di app/build.gradle.kts), bukan lagi didownload dari Maven.
    }
}

rootProject.name = "TunnelApp"
include(":app")
