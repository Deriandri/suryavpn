package com.example.tunnelapp

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.tunnelapp.databinding.ActivityConfigBinding
import com.example.tunnelapp.model.ConfigStore
import com.example.tunnelapp.tunnel.XrayLinkParser

/**
 * Layar "Konfigurasi" -- tab TENGAH baru di bilah navigasi bawah, di antara
 * "Dashboard" & "Pengaturan" (lihat bottom_nav_menu.xml & setupBottomNav()
 * di bawah). Isinya jalan pintas satu-tap ke [SshConfigActivity] &
 * [XrayConfigActivity], supaya kedua layar konfigurasi server itu tidak
 * cuma bisa diakses lewat kartu "Menu" di halaman Main Dashboard (yang
 * tetap ada, tidak dihapus -- ini cuma jalan pintas tambahan yang lebih
 * gampang ditemukan).
 *
 * Navigasi 3-tab: DashboardActivity adalah root (selalu di dasar back
 * stack). ConfigActivity & SettingsActivity adalah "sibling" yang saling
 * finish() diri sendiri sebelum start sibling lain, supaya back stack
 * selalu cuma [Dashboard, <tab aktif>] -- tidak numpuk instance lama tiap
 * kali pindah tab (lihat juga DashboardActivity.setupBottomNav &
 * SettingsActivity.setupBottomNav).
 */
class ConfigActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConfigBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConfigBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.rowConfigSsh.setOnClickListener {
            startActivity(Intent(this, SshConfigActivity::class.java))
        }
        binding.rowConfigXray.setOnClickListener {
            startActivity(Intent(this, XrayConfigActivity::class.java))
        }

        setupBottomNav()
    }

    override fun onResume() {
        super.onResume()
        // Profil bisa saja baru diubah di SshConfigActivity/XrayConfigActivity --
        // refresh ringkasan tiap kali layar ini kembali ditampilkan.
        refreshActiveProfileSummary()
    }

    private fun refreshActiveProfileSummary() {
        val saved = ConfigStore.load(this)
        binding.tvActiveProfileConfig.text = when {
            saved == null -> "Belum ada konfigurasi tersimpan"
            saved.modeIndex == 5 -> {
                val parsed = runCatching { XrayLinkParser.parse(saved.xrayLink) }.getOrNull()
                if (parsed != null) {
                    "Profil aktif: Xray — ${parsed.address}:${parsed.port}"
                } else {
                    "Profil aktif: Xray — link belum valid"
                }
            }
            else -> {
                val modeName = when (saved.modeIndex) {
                    1 -> "SSH SSL"
                    2 -> "SSH TLS Payload Proxy"
                    3 -> "Payload + Remote Proxy"
                    else -> "SSH"
                }
                "Profil aktif: $modeName — ${saved.host}:${saved.port}"
            }
        }
    }

    /**
     * Sama seperti [DashboardActivity.setupBottomNav] & [SettingsActivity],
     * tapi tab yang aktif di layar ini "Konfigurasi". Tap "Dashboard" cukup
     * finish() (Dashboard selalu ada di bawah ConfigActivity di back stack).
     * Tap "Pengaturan" pindah ke sibling lain: start SettingsActivity lalu
     * finish() diri sendiri, supaya back stack tidak numpuk.
     */
    private fun setupBottomNav() {
        binding.bottomNav.selectedItemId = R.id.nav_config
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_config -> true
                R.id.nav_dashboard -> {
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
