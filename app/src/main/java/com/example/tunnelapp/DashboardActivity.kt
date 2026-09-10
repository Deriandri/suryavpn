package com.example.tunnelapp

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import com.example.tunnelapp.databinding.ActivityDashboardBinding
import com.google.android.material.tabs.TabLayoutMediator

/**
 * Dashboard: layar utama & satu-satunya tempat tombol Connect/Disconnect berada.
 * Konfigurasi server (SSH & Xray) dipisah ke [SshConfigActivity] &
 * [XrayConfigActivity]. Kontennya sendiri sekarang berupa DUA HALAMAN yang bisa
 * DIGESER KE SAMPING lewat ViewPager2 (persis referensi video: tab "Main |
 * Log" di atas, konten bisa di-swipe): halaman "Main" ([DashboardMainFragment],
 * dashboard yang sudah ada) & halaman "Log" ([DashboardLogFragment], log
 * terminal yang sebelumnya jadi layar terpisah -- LogActivity -- sekarang
 * dipindahkan ke bagian kanan Dashboard). Activity ini sendiri jadi tipis:
 * cuma pasang header, sidebar, bottom nav, dan hubungkan ViewPager2+TabLayout;
 * semua logika Connect/Ping/Log ada di masing-masing Fragment.
 */
class DashboardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDashboardBinding

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* lanjut saja */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        setupPager()
        setupBottomNav()
        setupSidebar()
    }

    /**
     * ViewPager2 (halaman Main <-> Log, bisa digeser kesamping) + TabLayout
     * "Main | Log" di atasnya yang disinkronkan lewat TabLayoutMediator --
     * tap tab pindah halaman, geser halaman ikut menggerakkan tab.
     */
    private fun setupPager() {
        binding.dashboardPager.adapter = DashboardPagerAdapter(this)
        // offscreenPageLimit=1: kedua halaman (Main & Log) tetap hidup di
        // memori sekalipun tidak sedang tampil, supaya statusnya (mis. posisi
        // scroll terminal) tidak hilang tiap kali digeser bolak-balik.
        binding.dashboardPager.offscreenPageLimit = 1

        TabLayoutMediator(binding.tabLayout, binding.dashboardPager) { tab, position ->
            tab.text = when (position) {
                DashboardPagerAdapter.PAGE_LOG -> "Log"
                else -> "Main"
            }
        }.attach()
    }

    /** Pindah ke halaman "Log" (dipanggil dari DashboardMainFragment.rowMenuLog & sidebar). */
    fun goToLogPage() {
        binding.dashboardPager.setCurrentItem(DashboardPagerAdapter.PAGE_LOG, true)
    }

    /** Pindah ke halaman "Main" (dipanggil dari sidebar). */
    fun goToMainPage() {
        binding.dashboardPager.setCurrentItem(DashboardPagerAdapter.PAGE_MAIN, true)
    }

    /**
     * Bilah navigasi bawah, sekarang TIGA tab: "Dashboard" (layar ini, sudah
     * aktif dari awal), "Konfigurasi" (buka [ConfigActivity] -- jalan pintas
     * ke Konfigurasi SSH/Xray) di TENGAH, & "Pengaturan" (buka
     * [SettingsActivity] yang berisi VPN Setting + info app) di kanan.
     * ConfigActivity & SettingsActivity di-launch dengan launchMode="singleTop"
     * (lihat AndroidManifest) supaya tap berkali-kali tidak numpuk banyak
     * instance di back stack. DashboardActivity sendiri adalah ROOT: tetap
     * di dasar back stack, tidak pernah finish() dirinya sendiri di sini.
     */
    private fun setupBottomNav() {
        binding.bottomNav.selectedItemId = R.id.nav_dashboard
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_dashboard -> true
                R.id.nav_config -> {
                    startActivity(Intent(this, ConfigActivity::class.java))
                    true
                }
                R.id.nav_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    true
                }
                else -> false
            }
        }
    }

    /**
     * Sidebar (NavigationDrawer) -- item "Dashboard" & "Log" sekarang cuma
     * memindahkan halaman ViewPager2 (bukan lagi startActivity), konsisten
     * dengan tab "Main | Log" & baris menu "Log Koneksi" di halaman Main.
     */
    private fun setupSidebar() {
        binding.btnOpenDrawer.setOnClickListener {
            binding.drawerLayout.openDrawer(GravityCompat.START)
        }

        binding.navDrawer.setCheckedItem(R.id.drawer_dashboard)
        binding.navDrawer.setNavigationItemSelectedListener { item ->
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            when (item.itemId) {
                R.id.drawer_dashboard -> {
                    goToMainPage()
                    true
                }
                R.id.drawer_log -> {
                    goToLogPage()
                    true
                }
                else -> false
            }
        }

        // Back ditekan saat sidebar terbuka -> tutup sidebar dulu, JANGAN
        // langsung keluar activity/app.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })
    }
}
