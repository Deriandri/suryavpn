package com.example.tunnelapp

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.util.Patterns
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.tunnelapp.databinding.ActivitySettingsBinding
import com.example.tunnelapp.model.GeneralSettings
import com.example.tunnelapp.model.GeneralSettingsStore
import com.example.tunnelapp.model.VpnSettings
import com.example.tunnelapp.model.VpnSettingsStore

/**
 * Layar Pengaturan, dibuka lewat tab "Pengaturan" di bilah navigasi bawah
 * (lihat [DashboardActivity.setupBottomNav]). Isinya dua kartu independen:
 * "Pengaturan Dasar" (auto ping, lihat [GeneralSettingsStore] -- SENGAJA
 * TERPISAH dari VPN Setting sesuai permintaan awal fitur ini) dan
 * "VPN Setting" (DNS/MTU/keep-CPU-awake/auto-reconnect/battery usage, lihat
 * [VpnSettingsStore]), plus info versi aplikasi.
 *
 * Catatan: kartu "Konfigurasi Server" (SSH & Xray) yang dulu ada di sini
 * SUDAH DIHAPUS -- akses ke Konfigurasi SSH/Xray tetap ada lewat kartu
 * "Menu" di Dashboard (lihat DashboardActivity.rowMenuSsh/rowMenuXray),
 * jadi tidak ada fungsi yang hilang, cuma tidak didobelkan di sini.
 */
class SettingsActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SettingsActivity"
    }

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadVpnSettingsIntoForm()
        binding.btnSaveVpnSetting.setOnClickListener { saveVpnSettingsFromForm() }
        binding.btnBatteryUsage.setOnClickListener { requestIgnoreBatteryOptimizations() }

        loadGeneralSettingsIntoForm()
        binding.btnSaveGeneralSetting.setOnClickListener { saveGeneralSettingsFromForm() }

        binding.tvAppVersion.text = "TunnelApp — versi ${appVersionName()}"

        setupBottomNav()
    }

    /** Isi form Pengaturan Dasar dari [GeneralSettingsStore]. */
    private fun loadGeneralSettingsIntoForm() {
        val settings = GeneralSettingsStore.load(this)
        binding.switchAutoPing.isChecked = settings.autoPingEnabled
        binding.etPingInterval.setText(settings.pingIntervalSeconds.toString())
    }

    /** Validasi ringan lalu simpan ke [GeneralSettingsStore]. */
    private fun saveGeneralSettingsFromForm() {
        val intervalText = binding.etPingInterval.text.toString().trim()
        val interval = intervalText.toIntOrNull()
        if (intervalText.isEmpty() || interval == null ||
            interval < GeneralSettings.MIN_PING_INTERVAL_SECONDS ||
            interval > GeneralSettings.MAX_PING_INTERVAL_SECONDS
        ) {
            binding.etPingInterval.error =
                "Interval harus angka ${GeneralSettings.MIN_PING_INTERVAL_SECONDS}-${GeneralSettings.MAX_PING_INTERVAL_SECONDS} detik"
            return
        }

        GeneralSettingsStore.save(
            this,
            GeneralSettings(
                autoPingEnabled = binding.switchAutoPing.isChecked,
                pingIntervalSeconds = interval
            )
        )
        Toast.makeText(this, "Pengaturan Dasar disimpan", Toast.LENGTH_SHORT).show()
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
     * (ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS) — BUKAN sekadar buka
     * halaman pengaturan baterai app (ACTION_APPLICATION_DETAILS_SETTINGS),
     * supaya user tinggal tap "Izinkan" tanpa navigasi manual.
     *
     * Catatan OEM (Xiaomi/MIUI, Oppo/ColorOS, dll): custom ROM ini sering
     * TIDAK menampilkan dialog AOSP di atas sama sekali walau intent-nya
     * berhasil di-start tanpa exception (bukan crash, cuma sistem custom
     * ROM-nya sendiri yang mengabaikan) -- pengecualian baterai di ROM
     * begini biasanya harus diaktifkan manual lewat app "Keamanan"/"Security"
     * bawaan (mis. MIUI: Keamanan > Baterai > App battery saver > pilih app
     * ini > "Tanpa batasan"). Ini keterbatasan platform, bukan sesuatu yang
     * bisa dipaksa dari kode app pihak ketiga. Toast di bawah cuma menutupi
     * kasus intent-nya sendiri gagal di-resolve (exception) -- BUKAN kasus
     * dialog custom-ROM yang senyap.
     */
    private fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        if (intent.resolveActivity(packageManager) != null) {
            try {
                startActivity(intent)
                return
            } catch (e: Exception) {
                Log.e(TAG, "ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS gagal dijalankan", e)
            }
        } else {
            Log.w(TAG, "ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS tidak didukung device ini")
        }

        // Fallback: intent di atas tidak ada yang menangani / gagal dijalankan
        // -- arahkan ke halaman detail app, minimal user bisa cari menu
        // baterai manual dari sana.
        try {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")
                )
            )
            Toast.makeText(
                this,
                "Dialog izin baterai tidak tersedia di device ini -- cari menu baterai manual di halaman ini, atau di app Keamanan/Security bawaan HP",
                Toast.LENGTH_LONG
            ).show()
        } catch (e2: Exception) {
            Log.e(TAG, "ACTION_APPLICATION_DETAILS_SETTINGS juga gagal", e2)
            Toast.makeText(this, "Tidak bisa membuka pengaturan baterai di device ini", Toast.LENGTH_SHORT).show()
        }
    }

    /** Isi form VPN Setting dari [VpnSettingsStore] (dipanggil tiap onCreate, termasuk saat kembali dari layar lain lewat singleTop). */
    private fun loadVpnSettingsIntoForm() {
        val settings = VpnSettingsStore.load(this)
        binding.etVpnDns1.setText(settings.dns1)
        binding.etVpnDns2.setText(settings.dns2)
        binding.etVpnMtu.setText(settings.mtu.toString())
        binding.switchKeepAwake.isChecked = settings.keepCpuAwake
        binding.switchAutoReconnect.isChecked = settings.autoReconnect
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
                keepCpuAwake = binding.switchKeepAwake.isChecked,
                autoReconnect = binding.switchAutoReconnect.isChecked
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
