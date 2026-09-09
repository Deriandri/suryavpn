package com.example.tunnelapp

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.tunnelapp.databinding.ActivitySettingsBinding

/**
 * Layar Pengaturan, dibuka lewat tab "Pengaturan" di bilah navigasi bawah
 * (lihat [DashboardActivity.setupBottomNav]). Isinya konfigurasi server
 * (SSH & Xray, sebelumnya diakses lewat kartu "Menu" di Dashboard) plus info
 * versi aplikasi. Entry SSH/Xray di kartu "Menu" Dashboard SENGAJA dibiarkan
 * tetap ada juga -- permintaan awal cuma "tambahkan" bilah navigasi, bukan
 * pindahkan/hapus akses yang sudah ada.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.rowMenuSsh.setOnClickListener {
            startActivity(Intent(this, SshConfigActivity::class.java))
        }
        binding.rowMenuXray.setOnClickListener {
            startActivity(Intent(this, XrayConfigActivity::class.java))
        }

        binding.tvAppVersion.text = "TunnelApp — versi ${appVersionName()}"

        setupBottomNav()
    }

    /** Ambil versionName dari PackageManager -- selalu sinkron dengan gradle, tidak perlu di-hardcode di sini. */
    private fun appVersionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "-"
    } catch (e: PackageManager.NameNotFoundException) {
        "-"
    }

    /**
     * Sama seperti [DashboardActivity.setupBottomNav], tapi tab yang aktif di
     * layar ini "Pengaturan". Tap "Dashboard" cukup finish() (Dashboard sudah
     * ada di bawah SettingsActivity di back stack -- selalu benar karena
     * SettingsActivity hanya bisa dibuka dari Dashboard, exported="false").
     */
    private fun setupBottomNav() {
        binding.bottomNav.selectedItemId = R.id.nav_settings
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_settings -> true
                R.id.nav_dashboard -> {
                    finish()
                    true
                }
                else -> false
            }
        }
    }
}
