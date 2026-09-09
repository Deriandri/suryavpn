package com.example.tunnelapp

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Patterns
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.tunnelapp.databinding.ActivitySettingsBinding
import com.example.tunnelapp.model.VpnSettings
import com.example.tunnelapp.model.VpnSettingsStore

/**
 * Layar Pengaturan, dibuka lewat tab "Pengaturan" di bilah navigasi bawah
 * (lihat [DashboardActivity.setupBottomNav]). Isinya kartu "VPN Setting"
 * (DNS/MTU/keep-CPU-awake/battery usage global, lihat [VpnSettingsStore])
 * plus info versi aplikasi.
 *
 * Catatan: kartu "Konfigurasi Server" (SSH & Xray) yang dulu ada di sini
 * SUDAH DIHAPUS -- akses ke Konfigurasi SSH/Xray tetap ada lewat kartu
 * "Menu" di Dashboard (lihat DashboardActivity.rowMenuSsh/rowMenuXray),
 * jadi tidak ada fungsi yang hilang, cuma tidak didobelkan di sini.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadVpnSettingsIntoForm()
        binding.btnSaveVpnSetting.setOnClickListener { saveVpnSettingsFromForm() }
        binding.btnBatteryUsage.setOnClickListener { requestIgnoreBatteryOptimizations() }

        binding.tvAppVersion.text = "TunnelApp — versi ${appVersionName()}"

        setupBottomNav()
    }

    /**
     * Status battery optimization dicek ulang tiap kali layar ini kelihatan
     * lagi (bukan cuma onCreate) -- user bisa saja baru pulang dari dialog
     * sistem ("Izinkan"/"Tolak") yang dipicu [requestIgnoreBatteryOptimizations],
     * atau dari halaman pengaturan baterai OS kalau device tidak mendukung
     * dialog langsung.
     */
    override fun onResume() {
        super.onResume()
        refreshBatteryStatus()
    }

    /**
     * Kalau device SUDAH mengecualikan app ini dari Doze/App Standby, tombol
     * jadi tidak ada gunanya lagi (dan di beberapa OEM malah melempar error
     * kalau diminta ulang) -- disable + ganti teks status. Di bawah Android
     * 6.0 (M) fitur ini tidak ada sama sekali di platform, jadi disembunyikan.
     */
    private fun refreshBatteryStatus() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            binding.tvBatteryStatus.text = "Tidak berlaku di versi Android ini"
            binding.btnBatteryUsage.isEnabled = false
            return
        }
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        val isIgnoring = powerManager.isIgnoringBatteryOptimizations(packageName)
        if (isIgnoring) {
            binding.tvBatteryStatus.text = "Tidak dibatasi -- tunnel aman jalan di background"
            binding.btnBatteryUsage.isEnabled = false
            binding.btnBatteryUsage.text = "Aktif"
        } else {
            binding.tvBatteryStatus.text = "Dioptimalkan sistem -- tunnel bisa terputus di background"
            binding.btnBatteryUsage.isEnabled = true
            binding.btnBatteryUsage.text = "Atur"
        }
    }

    /**
     * Minta pengecualian dari Doze/App Standby lewat dialog sistem langsung
     * (ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS) -- BUKAN sekadar buka
     * halaman pengaturan baterai app (ACTION_APPLICATION_DETAILS_SETTINGS),
     * supaya user tinggal tap "Izinkan" tanpa navigasi manual. Beberapa OEM
     * (mis. custom ROM yang mengunci intent ini) bisa saja tidak
     * mendukungnya -- ditangkap & fallback ke halaman detail app supaya
     * tombol tidak diam saja kalau tetap ditap.
     */
    private fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (e: Exception) {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (e2: Exception) {
                Toast.makeText(this, "Tidak bisa membuka pengaturan baterai di device ini", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Isi form VPN Setting dari [VpnSettingsStore] (dipanggil tiap onCreate, termasuk saat kembali dari layar lain lewat singleTop). */
    private fun loadVpnSettingsIntoForm() {
        val settings = VpnSettingsStore.load(this)
        binding.etVpnDns1.setText(settings.dns1)
        binding.etVpnDns2.setText(settings.dns2)
        binding.etVpnMtu.setText(settings.mtu.toString())
        binding.switchKeepAwake.isChecked = settings.keepCpuAwake
    }

    /**
     * Validasi ringan lalu simpan ke [VpnSettingsStore]. Sama seperti validasi
     * DNS di SshConfigActivity: DNS custom di VpnService.Builder WAJIB
     * literal IP, bukan hostname/domain.
     */
    private fun saveVpnSettingsFromForm() {
        val dns1 = binding.etVpnDns1.text.toString().trim()
        val dns2 = binding.etVpnDns2.text.toString().trim()
        val mtuText = binding.etVpnMtu.text.toString().trim()

        if (dns1.isNotEmpty() && !Patterns.IP_ADDRESS.matcher(dns1).matches()) {
            binding.etVpnDns1.error = "Harus alamat IP (mis. 1.1.1.1), bukan domain"
            return
        }
        if (dns2.isNotEmpty() && !Patterns.IP_ADDRESS.matcher(dns2).matches()) {
            binding.etVpnDns2.error = "Harus alamat IP (mis. 1.0.0.1), bukan domain"
            return
        }

        val mtu = mtuText.toIntOrNull()
        if (mtuText.isEmpty() || mtu == null || mtu < VpnSettings.MIN_MTU || mtu > VpnSettings.MAX_MTU) {
            binding.etVpnMtu.error = "MTU harus angka ${VpnSettings.MIN_MTU}-${VpnSettings.MAX_MTU}"
            return
        }

        VpnSettingsStore.save(
            this,
            VpnSettings(
                dns1 = dns1,
                dns2 = dns2,
                mtu = mtu,
                keepCpuAwake = binding.switchKeepAwake.isChecked
            )
        )
        Toast.makeText(this, "VPN Setting disimpan", Toast.LENGTH_SHORT).show()
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
