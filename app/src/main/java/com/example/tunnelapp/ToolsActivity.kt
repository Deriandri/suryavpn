package com.example.tunnelapp

import android.content.Intent
import android.os.Bundle
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.tunnelapp.databinding.ActivityToolsBinding
import com.example.tunnelapp.model.VpnSettingsStore
import com.example.tunnelapp.tunnel.SubscriptionAutoUpdateScheduler
import com.example.tunnelapp.tunnel.SubscriptionImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Layar "Tools" -- tab BARU (ke-4) di bilah navigasi bawah, di samping
 * "Pengaturan" (lihat bottom_nav_menu.xml).
 *
 * FITUR BARU (parity dengan V2RayNG, permintaan user "tambahkan semua fungsi
 * yang belum ada"): tiga tool sebenarnya mulai diisi di sini --
 *  - Per-App Proxy -> [AppFilterActivity]
 *  - Routing & Performa (bypass LAN, mux, bypass domain/IP) -> [RoutingSettingsActivity]
 *  - Import Subscription -> dialog URL di bawah, lewat [SubscriptionImporter]
 * Placeholder "Segera hadir" (Ping Test/Speed Test/Cek IP) TETAP ada,
 * terpisah, menyusul belakangan.
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
        setupToolRows()
    }

    private fun setupToolRows() {
        binding.rowPerAppProxy.setOnClickListener {
            startActivity(Intent(this, AppFilterActivity::class.java))
        }
        binding.rowRouting.setOnClickListener {
            startActivity(Intent(this, RoutingSettingsActivity::class.java))
        }
        binding.rowSubscription.setOnClickListener {
            showSubscriptionImportDialog()
        }
    }

    /**
     * Dialog sederhana (bukan Activity terpisah) berisi:
     *  - EditText URL subscription (wajib).
     *  - Checkbox "Auto-update berkala" + EditText interval jam -- FITUR
     *    BARU (parity dengan V2RayNG). Kalau dicentang, URL & interval ini
     *    disimpan ke [VpnSettingsStore] & [SubscriptionAutoUpdateScheduler.schedule]
     *    dipasang SETELAH import pertama berhasil (lihat [runImport]) --
     *    sengaja tidak dipasang duluan supaya kalau URL-nya ternyata salah/
     *    tidak valid, tidak ada alarm nyasar yang ke-pasang.
     *
     * Import berjalan di Dispatchers.IO (network I/O, lihat
     * [SubscriptionImporter.importFromUrl]) supaya tidak nge-block main
     * thread; hasil (jumlah berhasil/dilewati) ditampilkan lewat Toast.
     */
    private fun showSubscriptionImportDialog() {
        val current = VpnSettingsStore.load(this)
        val paddingPx = (16 * resources.displayMetrics.density).toInt()

        val input = EditText(this).apply {
            hint = "https://provider.example.com/subscribe/xxxx"
            setSingleLine()
            setText(current.lastSubscriptionUrl)
        }
        val cbAutoUpdate = CheckBox(this).apply {
            text = "Auto-update berkala"
            isChecked = current.subscriptionAutoUpdateEnabled
        }
        val etInterval = EditText(this).apply {
            hint = "Interval (jam), mis. 12"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(current.subscriptionUpdateIntervalHours.toString())
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(paddingPx, paddingPx / 2, paddingPx, 0)
            addView(input)
            addView(cbAutoUpdate, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = paddingPx / 2 })
            addView(etInterval)
        }

        AlertDialog.Builder(this)
            .setTitle("Import Subscription")
            .setMessage("Tempel URL subscription (isinya banyak link vmess://vless://trojan:// sekaligus, biasanya dari provider VPS/proxy Anda).")
            .setView(container)
            .setPositiveButton("Import") { _, _ ->
                val url = input.text.toString().trim()
                if (url.isEmpty()) {
                    Toast.makeText(this, "URL belum diisi", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val intervalHours = etInterval.text.toString().trim().toIntOrNull()
                    ?.coerceIn(1, 24 * 14) ?: current.subscriptionUpdateIntervalHours
                runImport(url, cbAutoUpdate.isChecked, intervalHours)
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun runImport(url: String, autoUpdateEnabled: Boolean, intervalHours: Int) {
        val progress = AlertDialog.Builder(this)
            .setMessage("Mengambil & mem-parsing subscription...")
            .setCancelable(false)
            .show()

        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { SubscriptionImporter.importFromUrl(this@ToolsActivity, url) }
            }
            progress.dismiss()

            outcome.onSuccess { result ->
                // FITUR BARU: URL & preferensi auto-update baru DISIMPAN di
                // sini -- SETELAH import pertama benar-benar berhasil (lihat
                // kdoc showSubscriptionImportDialog di atas untuk alasannya).
                val settings = VpnSettingsStore.load(this@ToolsActivity)
                VpnSettingsStore.save(
                    this@ToolsActivity,
                    settings.copy(
                        lastSubscriptionUrl = url,
                        subscriptionAutoUpdateEnabled = autoUpdateEnabled,
                        subscriptionUpdateIntervalHours = intervalHours
                    )
                )
                if (autoUpdateEnabled) {
                    SubscriptionAutoUpdateScheduler.schedule(this@ToolsActivity, intervalHours)
                } else {
                    SubscriptionAutoUpdateScheduler.cancel(this@ToolsActivity)
                }

                Toast.makeText(
                    this@ToolsActivity,
                    "Berhasil menambah ${result.importedCount} akun" +
                        if (result.skippedCount > 0) " (${result.skippedCount} baris dilewati)" else "",
                    Toast.LENGTH_LONG
                ).show()
            }.onFailure { e ->
                Toast.makeText(
                    this@ToolsActivity,
                    "Gagal import subscription: ${e.message ?: e.javaClass.simpleName}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun setupBottomNav() {
        binding.bottomNav.selectedItemId = R.id.nav_tools
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_tools -> true
                R.id.nav_dashboard -> {
                    finish()
                    applyNavFadeTransition()
                    true
                }
                R.id.nav_config -> {
                    startActivity(Intent(this, ConfigActivity::class.java))
                    finish()
                    applyNavFadeTransition()
                    true
                }
                R.id.nav_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    finish()
                    applyNavFadeTransition()
                    true
                }
                else -> false
            }
        }
    }
}
