package com.example.tunnelapp

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.tunnelapp.databinding.ActivityToolsBinding

/**
 * Layar "Tools" -- tab BARU (ke-4) di bilah navigasi bawah, di samping
 * "Pengaturan" (lihat bottom_nav_menu.xml). PERMINTAAN USER: baru
 * navigasinya dulu yang dipasang -- halaman ini masih placeholder kosong,
 * daftar tool sebenarnya (mis. Ping Test, Speed Test, Cek IP) menyusul
 * belakangan lewat activity_tools.xml.
 *
 * Sibling ke-4 di pola navigasi 4-tab: sama seperti [ConfigActivity] &
 * [SettingsActivity], layar ini bukan root (DashboardActivity tetap root),
 * jadi tap tab lain di sini start sibling yang dituju lalu finish() diri
 * sendiri supaya back stack tidak numpuk instance lama.
 */
class ToolsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityToolsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityToolsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupBottomNav()
    }

    private fun setupBottomNav() {
        binding.bottomNav.selectedItemId = R.id.nav_tools
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_tools -> true
                R.id.nav_dashboard -> {
                    finish()
                    true
                }
                R.id.nav_config -> {
                    startActivity(Intent(this, ConfigActivity::class.java))
                    finish()
                    true
                }
                R.id.nav_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    finish()
                    true
                }
                else -> false
            }
        }
    }
}
