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
import androidx.appcompat.app.AppCompatDelegate
import com.example.tunnelapp.databinding.ActivitySettingsBinding
import com.example.tunnelapp.model.AppLanguage
import com.example.tunnelapp.model.GeneralSettings
import com.example.tunnelapp.model.GeneralSettingsStore
import com.example.tunnelapp.model.LocaleStore
import com.example.tunnelapp.model.ThemeMode
import com.example.tunnelapp.model.ThemeStore
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
 * SUDAH DIHAPUS -- akses ke Konfigurasi SSH/Xray sekarang lewat tab
 * "Konfigurasi" tersendiri di bilah navigasi bawah (lihat [ConfigActivity]),
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

        loadThemeIntoForm()
        loadLanguageIntoForm()

        loadVpnSettingsIntoForm()
        binding.btnSaveVpnSetting.setOnClickListener { saveVpnSettingsFromForm() }
        binding.btnBatteryUsage.setOnClickListener { requestIgnoreBatteryOptimizations() }

        loadGeneralSettingsIntoForm()
        binding.btnSaveGeneralSetting.setOnClickListener { saveGeneralSettingsFromForm() }

        binding.tvAppVersion.text = getString(R.string.app_version_format, appVersionName())

        setupBottomNav()
    }

    /**
     * FITUR BARU (permintaan user, "tema dark"): isi toggle Terang/Gelap/
     * Ikuti Sistem dari [ThemeStore], lalu pasang listener yang langsung
     * menerapkan & menyimpan pilihan begitu user menekan salah satu opsi
     * (tidak perlu tombol "Simpan" terpisah -- beda dari kartu Pengaturan
     * Dasar/VPN Setting di bawah, karena efeknya harus terasa instan supaya
     * user bisa langsung lihat hasilnya).
     */
    private fun loadThemeIntoForm() {
        binding.toggleThemeMode.check(
            when (ThemeStore.load(this)) {
                ThemeMode.LIGHT -> R.id.btnThemeLight
                ThemeMode.DARK -> R.id.btnThemeDark
                ThemeMode.SYSTEM -> R.id.btnThemeSystem
            }
        )
        binding.toggleThemeMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val mode = when (checkedId) {
                R.id.btnThemeLight -> ThemeMode.LIGHT
                R.id.btnThemeDark -> ThemeMode.DARK
                else -> ThemeMode.SYSTEM
            }
            applyThemeChoice(mode)
        }
    }

    /**
     * Simpan pilihan ke [ThemeStore] lalu terapkan lewat
     * [AppCompatDelegate.setDefaultNightMode] -- ini otomatis me-recreate
     * SEMUA Activity yang lagi terbuka (termasuk layar ini sendiri) supaya
     * warna langsung berubah tanpa perlu tutup-buka app manual.
     */
    private fun applyThemeChoice(mode: ThemeMode) {
        ThemeStore.save(this, mode)
        AppCompatDelegate.setDefaultNightMode(mode.toNightMode())
    }

    /**
     * FITUR BARU (permintaan user, "tambahkan bahasa Inggris"): isi toggle
     * Indonesia/English dari [LocaleStore], lalu pasang listener yang
     * langsung menerapkan pilihan begitu user menekan salah satu opsi --
     * sama seperti [loadThemeIntoForm], tidak perlu tombol "Simpan"
     * terpisah supaya perubahan bahasa langsung terasa. Lihat
     * activity_settings.xml kartu "Bahasa" (tepat di atas kartu
     * "Pengaturan Dasar").
     */
    private fun loadLanguageIntoForm() {
        binding.toggleLanguage.check(
            when (LocaleStore.current()) {
                AppLanguage.INDONESIAN -> R.id.btnLangIndonesian
                AppLanguage.ENGLISH -> R.id.btnLangEnglish
            }
        )
        binding.toggleLanguage.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val language = when (checkedId) {
                R.id.btnLangIndonesian -> AppLanguage.INDONESIAN
                else -> AppLanguage.ENGLISH
            }
            LocaleStore.apply(language)
        }
    }

    /** Isi form Pengaturan Dasar dari [GeneralSettingsStore]. */
    private fun loadGeneralSettingsIntoForm() {
        val settings = GeneralSettingsStore.load(this)
        binding.switchAutoPing.isChecked = settings.autoPingEnabled
        binding.etPingInterval.setText(settings.pingIntervalSeconds.toString())
        binding.etKeepAliveTarget.setText(settings.keepAliveTarget)
        binding.toggleKeepAliveMethod.check(
            if (settings.keepAliveMethod == GeneralSettings.METHOD_HTTP) {
                R.id.btnMethodHttp
            } else {
                R.id.btnMethodTcp
            }
        )
        updateKeepAliveTargetHint(settings.keepAliveMethod)
        // FIX (permintaan user): metode HTTP cukup butuh host/URL, TIDAK
        // butuh port (request-nya selalu ke port 80 -- lihat
        // MyVpnService.HTTP_KEEP_ALIVE_PORT) -- hint field ikut berubah
        // begitu user gonta-ganti toggle metode, bukan cuma pas layar dibuka.
        binding.toggleKeepAliveMethod.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            updateKeepAliveTargetHint(
                if (checkedId == R.id.btnMethodHttp) GeneralSettings.METHOD_HTTP else GeneralSettings.METHOD_TCP
            )
        }
    }

    private fun updateKeepAliveTargetHint(method: String) {
        binding.tilKeepAliveTarget.hint = if (method == GeneralSettings.METHOD_HTTP) {
            getString(R.string.keep_alive_target_hint_http)
        } else {
            getString(R.string.keep_alive_target_hint_tcp)
        }
    }

    /**
     * Validasi ringan lalu simpan ke [GeneralSettingsStore]. Target keep-alive
     * WAJIB format "host:port" (port 1-65535) -- validasi di sini SEBELUM
     * disimpan, biar salah ketik ketahuan langsung di layar Pengaturan,
     * bukan diam-diam fallback ke default nanti pas tunnel jalan (lihat
     * MyVpnService.parseKeepAliveTarget yang juga punya fallback sebagai
     * jaring pengaman kedua kalau ada data lama/aneh di SharedPreferences).
     */
    private fun saveGeneralSettingsFromForm() {
        val intervalText = binding.etPingInterval.text.toString().trim()
        val interval = intervalText.toIntOrNull()
        if (intervalText.isEmpty() || interval == null ||
            interval < GeneralSettings.MIN_PING_INTERVAL_SECONDS ||
            interval > GeneralSettings.MAX_PING_INTERVAL_SECONDS
        ) {
            binding.etPingInterval.error = getString(
                R.string.error_ping_interval,
                GeneralSettings.MIN_PING_INTERVAL_SECONDS,
                GeneralSettings.MAX_PING_INTERVAL_SECONDS
            )
            return
        }

        val keepAliveMethod = if (binding.toggleKeepAliveMethod.checkedButtonId == R.id.btnMethodHttp) {
            GeneralSettings.METHOD_HTTP
        } else {
            GeneralSettings.METHOD_TCP
        }

        // FIX (permintaan user): metode HTTP cukup host/URL, TANPA port --
        // kalau user masih ngetik "host:port" sambil pilih HTTP, bagian
        // ":port"-nya dibuang otomatis di sini sebelum divalidasi/disimpan
        // (request HTTP-nya sendiri selalu ke port 80, lihat
        // MyVpnService.HTTP_KEEP_ALIVE_PORT -- port yang diketik user tidak
        // pernah benar-benar dipakai untuk metode ini).
        val rawKeepAliveTarget = binding.etKeepAliveTarget.text.toString().trim()
        val keepAliveTarget = if (keepAliveMethod == GeneralSettings.METHOD_HTTP) {
            rawKeepAliveTarget.substringBeforeLast(':').ifBlank { rawKeepAliveTarget }
        } else {
            rawKeepAliveTarget
        }
        if (!isValidKeepAliveTarget(keepAliveTarget)) {
            binding.etKeepAliveTarget.error = if (keepAliveMethod == GeneralSettings.METHOD_HTTP) {
                getString(R.string.error_keepalive_http)
            } else {
                getString(R.string.error_keepalive_tcp)
            }
            return
        }

        GeneralSettingsStore.save(
            this,
            GeneralSettings(
                autoPingEnabled = binding.switchAutoPing.isChecked,
                pingIntervalSeconds = interval,
                keepAliveTarget = keepAliveTarget,
                keepAliveMethod = keepAliveMethod
            )
        )
        Toast.makeText(this, getString(R.string.toast_general_saved), Toast.LENGTH_SHORT).show()
    }

    /**
     * Cek target keep-alive -- SEKARANG port opsional (permintaan user):
     * boleh "host" saja (host tidak boleh kosong, tidak boleh mengandung
     * titik dua tapi kosong di kanan/kiri-nya), ATAU "host:port" lengkap
     * dengan port 1-65535 kalau memang diisi.
     *
     * Konsisten dengan parseKeepAliveTarget() di MyVpnService -- fungsi itu
     * jadi jaring pengaman kedua (data lama/aneh di SharedPreferences), yang
     * ini validasi pertama biar user langsung tahu salah ketik di layar
     * Pengaturan.
     */
    private fun isValidKeepAliveTarget(value: String): Boolean {
        if (value.isEmpty()) return false
        val sepIndex = value.lastIndexOf(':')
        if (sepIndex < 0) return true // cuma host, tanpa port sama sekali -- valid
        if (sepIndex == 0 || sepIndex == value.length - 1) return false
        val host = value.substring(0, sepIndex).trim()
        val port = value.substring(sepIndex + 1).trim().toIntOrNull()
        return host.isNotEmpty() && port != null && port in 1..65535
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
            binding.tvBatteryStatus.text = getString(R.string.battery_not_applicable)
            binding.btnBatteryUsage.isEnabled = false
            return
        }
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        val isIgnoring = powerManager.isIgnoringBatteryOptimizations(packageName)
        if (isIgnoring) {
            binding.tvBatteryStatus.text = getString(R.string.battery_not_restricted)
            binding.btnBatteryUsage.isEnabled = false
            binding.btnBatteryUsage.text = getString(R.string.btn_battery_active)
        } else {
            binding.tvBatteryStatus.text = getString(R.string.battery_restricted)
            binding.btnBatteryUsage.isEnabled = true
            binding.btnBatteryUsage.text = getString(R.string.btn_battery_setup)
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
                getString(R.string.battery_dialog_unavailable),
                Toast.LENGTH_LONG
            ).show()
        } catch (e2: Exception) {
            Log.e(TAG, "ACTION_APPLICATION_DETAILS_SETTINGS juga gagal", e2)
            Toast.makeText(this, getString(R.string.battery_settings_open_failed), Toast.LENGTH_SHORT).show()
        }
    }

    /** Isi form VPN Setting dari [VpnSettingsStore] (dipanggil tiap onCreate, termasuk saat kembali dari layar lain lewat singleTop). */
    /**
     * Kunci/buka switchCompression sesuai engine yang SEDANG dipilih di UI
     * (belum tentu sudah disimpan) -- lihat catatan JUJUR di
     * VpnSettingsStore.compressionEnabled soal kenapa cuma sshj yang boleh.
     */
    private fun applyCompressionSwitchAvailability(sshjSelected: Boolean) {
        binding.switchCompression.isEnabled = sshjSelected
        binding.tvCompressionHint.text = if (sshjSelected) {
            getString(R.string.compression_hint_enabled)
        } else {
            getString(R.string.compression_hint_disabled)
        }
    }

    private fun loadVpnSettingsIntoForm() {
        val settings = VpnSettingsStore.load(this)
        binding.etVpnDns1.setText(settings.dns1)
        binding.etVpnDns2.setText(settings.dns2)
        binding.etVpnMtu.setText(settings.mtu.toString())
        binding.switchKeepAwake.isChecked = settings.keepCpuAwake
        binding.switchAutoReconnect.isChecked = settings.autoReconnect
        binding.switchPerformanceMode.isChecked = settings.performanceMode
        // Pilih tombol SSH Engine sesuai setting tersimpan (default TRILEAD).
        binding.toggleSshEngine.check(
            if (settings.sshEngine == VpnSettings.ENGINE_SSHJ) R.id.btnEngineSshj else R.id.btnEngineTrilead
        )
        // Compression cuma BENERAN berfungsi di engine sshj (lihat catatan
        // JUJUR di VpnSettingsStore.compressionEnabled) -- switch-nya
        // dikunci mati kalau engine yang lagi dipilih masih TRILEAD, dan
        // baru bisa dinyalakan user kalau sudah pindah ke sshj. Listener di
        // bawah (addOnButtonCheckedListener) menjaga ini tetap konsisten
        // secara LIVE kalau user gonta-ganti pilihan engine tanpa Simpan dulu.
        applyCompressionSwitchAvailability(settings.sshEngine == VpnSettings.ENGINE_SSHJ)
        binding.switchCompression.isChecked = settings.compressionEnabled && settings.sshEngine == VpnSettings.ENGINE_SSHJ
        binding.toggleSshEngine.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val sshjSelected = checkedId == R.id.btnEngineSshj
            applyCompressionSwitchAvailability(sshjSelected)
            if (!sshjSelected) {
                // Pindah balik ke Trilead -- matikan togglenya juga (bukan
                // cuma dikunci), supaya kalau user langsung Simpan tanpa
                // sadar, compressionEnabled yang tersimpan tetap false
                // (konsisten dengan saveVpnSettingsFromForm di bawah).
                binding.switchCompression.isChecked = false
            }
        }
        // FITUR BARU (permintaan user): pilih tombol Tunnel Engine sesuai
        // setting tersimpan (default HEV) -- mirror persis pola toggleSshEngine
        // di atas, tapi TIDAK butuh listener tambahan (beda dari sshEngine,
        // tidak ada field lain di form ini yang perlu dikunci/dibuka
        // tergantung pilihan tunEngine).
        binding.toggleTunEngine.check(
            if (settings.tunEngine == VpnSettings.ENGINE_BADVPN) R.id.btnEngineBadvpn else R.id.btnEngineHev
        )
        // 0 berarti "tidak diisi" -- tampilkan field kosong, bukan "0",
        // supaya konsisten dengan makna kosong = pakai default/nonaktif.
        binding.etVpnSocksPort.setText(if (settings.socksPort > 0) settings.socksPort.toString() else "")
        binding.etVpnHttpPort.setText(if (settings.httpPort > 0) settings.httpPort.toString() else "")
        binding.etVpnUdpgwPort.setText(if (settings.udpgwPort > 0) settings.udpgwPort.toString() else "")
    }

    /**
     * Validasi satu field port opsional: kosong -> 0 (nonaktif/default),
     * atau angka 1-65535. Mengembalikan null kalau isinya bukan salah satu
     * dari dua kondisi itu (dan menandai [field] dengan pesan error).
     */
    private fun parseOptionalPort(
        field: com.google.android.material.textfield.TextInputEditText,
        label: String
    ): Int? {
        val text = field.text.toString().trim()
        if (text.isEmpty()) return 0
        val port = text.toIntOrNull()
        if (port == null || port < VpnSettings.MIN_PORT || port > VpnSettings.MAX_PORT) {
            field.error = getString(R.string.error_port, label, VpnSettings.MIN_PORT, VpnSettings.MAX_PORT)
            return null
        }
        return port
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
            binding.etVpnDns1.error = getString(R.string.error_dns1)
            return
        }
        if (dns2.isNotEmpty() && !Patterns.IP_ADDRESS.matcher(dns2).matches()) {
            binding.etVpnDns2.error = getString(R.string.error_dns2)
            return
        }

        val mtu = mtuText.toIntOrNull()
        if (mtuText.isEmpty() || mtu == null || mtu < VpnSettings.MIN_MTU || mtu > VpnSettings.MAX_MTU) {
            binding.etVpnMtu.error = getString(R.string.error_mtu, VpnSettings.MIN_MTU, VpnSettings.MAX_MTU)
            return
        }

        val socksPort = parseOptionalPort(binding.etVpnSocksPort, getString(R.string.port_socks5_label)) ?: return
        val httpPort = parseOptionalPort(binding.etVpnHttpPort, getString(R.string.port_http_label)) ?: return
        val udpgwPort = parseOptionalPort(binding.etVpnUdpgwPort, getString(R.string.port_udpgw_label)) ?: return

        val sshEngine = if (binding.toggleSshEngine.checkedButtonId == R.id.btnEngineSshj) {
            VpnSettings.ENGINE_SSHJ
        } else {
            VpnSettings.ENGINE_TRILEAD
        }
        val tunEngine = if (binding.toggleTunEngine.checkedButtonId == R.id.btnEngineBadvpn) {
            VpnSettings.ENGINE_BADVPN
        } else {
            VpnSettings.ENGINE_HEV
        }

        VpnSettingsStore.save(
            this,
            VpnSettings(
                dns1 = dns1,
                dns2 = dns2,
                mtu = mtu,
                keepCpuAwake = binding.switchKeepAwake.isChecked,
                autoReconnect = binding.switchAutoReconnect.isChecked,
                socksPort = socksPort,
                httpPort = httpPort,
                udpgwPort = udpgwPort,
                performanceMode = binding.switchPerformanceMode.isChecked,
                // Simpan apa adanya dari switch UI, TAPI dijaga ganda di
                // sini: kalau entah bagaimana engine yang tersimpan masih
                // TRILEAD (switch seharusnya sudah dikunci mati di kondisi
                // ini oleh applyCompressionSwitchAvailability), tetap paksa
                // false -- jangan pernah simpan compressionEnabled=true
                // berpasangan dengan sshEngine=TRILEAD.
                compressionEnabled = binding.switchCompression.isChecked && sshEngine == VpnSettings.ENGINE_SSHJ,
                sshEngine = sshEngine,
                tunEngine = tunEngine
            )
        )
        Toast.makeText(this, getString(R.string.toast_vpn_saved), Toast.LENGTH_SHORT).show()
    }

    /** Ambil versionName dari PackageManager -- selalu sinkron dengan gradle, tidak perlu di-hardcode di sini. */
    private fun appVersionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "-"
    } catch (e: PackageManager.NameNotFoundException) {
        "-"
    }

    /**
     * Sama seperti [DashboardActivity.setupBottomNav], tapi tab yang aktif di
     * layar ini "Pengaturan". Tap "Dashboard" cukup finish() (Dashboard selalu
     * ada tepat di bawah di back stack -- lihat catatan navigasi 3-tab di
     * [ConfigActivity]). Tap "Konfigurasi" pindah ke sibling [ConfigActivity]:
     * start lalu finish() diri sendiri, supaya back stack tidak numpuk.
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
                R.id.nav_config -> {
                    startActivity(Intent(this, ConfigActivity::class.java))
                    finish()
                    true
                }
                R.id.nav_tools -> {
                    startActivity(Intent(this, ToolsActivity::class.java))
                    finish()
                    true
                }
                else -> false
            }
        }
    }
}
