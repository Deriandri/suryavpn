package com.example.tunnelapp

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.tunnelapp.databinding.ActivityDashboardBinding
import com.example.tunnelapp.model.ConfigStore
import com.example.tunnelapp.model.ConnectionMode
import com.example.tunnelapp.tunnel.MyVpnService
import com.example.tunnelapp.tunnel.PingUtil
import com.example.tunnelapp.tunnel.StatusBus
import com.example.tunnelapp.tunnel.XrayLinkParser
import kotlinx.coroutines.launch

/**
 * Dashboard: layar utama & satu-satunya tempat tombol Connect/Disconnect berada.
 * Konfigurasi server (SSH & Xray) sekarang dipisah ke [SshConfigActivity] &
 * [XrayConfigActivity], log koneksi dipisah ke [LogActivity]. Dashboard hanya
 * membaca profil yang sudah disimpan lewat [ConfigStore] untuk tahu apa yang
 * mau disambungkan, plus fitur baru: cek ping ke target sebelum connect.
 */
class DashboardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDashboardBinding

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startVpnServiceFromPending()
        } else {
            // FIX: sebelumnya nulis langsung ke tvStatus.text, melewati StatusBus
            // -- pill warna & state tombol jadi tidak ikut ter-refresh (bisa
            // beda dari teks statusnya). Lewat StatusBus.state supaya semuanya
            // (pill, warna, tombol) tetap konsisten sesuai satu sumber status.
            StatusBus.state.value = "Gagal: izin VPN ditolak"
            pending = null
        }
    }

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* lanjut saja */ }

    private var pending: PendingConnection? = null

    private data class PendingConnection(
        val host: String,
        val port: Int,
        val username: String,
        val password: String,
        val mode: ConnectionMode,
        val sni: String,
        val payload: String,
        val proxyHost: String,
        val proxyPort: Int?,
        val tlsVersion: String?,
        val useWebSocket: Boolean,
        val wsPath: String,
        val proxyRawMode: Boolean,
        val xrayLink: String,
        val customHeaders: String,
        val ignoreCertErrors: Boolean,
        val dns1: String,
        val dns2: String
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        binding.btnConnectToggle.setOnClickListener { onConnectToggleClicked() }
        binding.btnCheckPing.setOnClickListener { onCheckPingClicked() }

        binding.rowMenuSsh.setOnClickListener {
            startActivity(Intent(this, SshConfigActivity::class.java))
        }
        binding.rowMenuXray.setOnClickListener {
            startActivity(Intent(this, XrayConfigActivity::class.java))
        }
        binding.rowMenuLog.setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }

        setupBottomNav()

        lifecycleScope.launch {
            StatusBus.state.collect { status ->
                binding.tvStatus.text = status
                applyStatusPillColor(status)
                applyConnectButtonState(status)
            }
        }
    }

    /**
     * Bilah navigasi bawah: tab "Dashboard" (layar ini, sudah aktif dari awal)
     * & "Pengaturan" (buka [SettingsActivity] yang berisi Konfigurasi
     * SSH/Xray + info app). SettingsActivity di-launch dengan
     * launchMode="singleTop" (lihat AndroidManifest) supaya tap "Pengaturan"
     * berkali-kali tidak numpuk banyak instance di back stack.
     */
    private fun setupBottomNav() {
        binding.bottomNav.selectedItemId = R.id.nav_dashboard
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_dashboard -> true
                R.id.nav_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    true
                }
                else -> false
            }
        }
    }

    /**
     * Tombol Connect/Disconnect digabung jadi satu ([binding.btnConnectToggle]).
     * Klik dibaca sesuai state TERAKHIR yang disimpan lewat [applyConnectButtonState]
     * (bukan dibaca dari teks tombol -- lebih tahan kalau nanti teksnya diganti/di-translate).
     */
    private enum class ConnectButtonState { IDLE, CONNECTING, CONNECTED }

    private var connectButtonState = ConnectButtonState.IDLE

    private fun onConnectToggleClicked() {
        when (connectButtonState) {
            ConnectButtonState.CONNECTED -> onDisconnectClicked()
            ConnectButtonState.IDLE -> onConnectClicked()
            // FIX: dulu tap di sini diabaikan total, jadi kalau proses connect
            // nyangkut (server tidak respons, tidak ada timeout socket) user
            // TERKUNCI tanpa cara membatalkan selain force-close app. Sekarang
            // tap saat CONNECTING dianggap "batalkan" -> kirim ACTION_DISCONNECT
            // yang sudah aman dipanggil kapan pun (stopVpn() idempotent).
            ConnectButtonState.CONNECTING -> onDisconnectClicked()
        }
    }

    /**
     * Ganti teks, warna, dan enabled/disabled tombol sesuai status tunnel terkini.
     *
     * FIX #1 (bug "baru install langsung nyangkut di Batalkan"): status IDLE
     * default/awal dari [StatusBus] adalah literal "Belum tersambung" -- dan
     * string itu SENDIRI memuat substring "tersambung", yang juga dipakai untuk
     * mendeteksi status tengah-proses seperti "SSH tersambung. Mengaktifkan
     * tunnel...". Akibatnya "Belum tersambung" (harusnya IDLE, tombol
     * "Connect") ikut kedeteksi sebagai CONNECTING -> tombol baru start di
     * "Batalkan"/disable, bukan "Connect", padahal belum pernah nyoba connect
     * sama sekali. Fix: status idle/terminal yang diketahui persis ("Belum
     * tersambung" dari StatusBus default, "Terputus" dari stopVpn() sukses)
     * dicek DULUAN via exact match -- bukan substring -- sebelum cek
     * transisi, supaya tidak ketriger oleh kata "tersambung" di dalamnya.
     *
     * FIX #2 (bug "tombol connect/disconnect macet" setelah reconnect gagal):
     * status "Gagal: ..." yang dikirim MyVpnService setelah reconnect otomatis
     * KEHABISAN percobaan masih memuat kata "reconnect otomatis" di dalam
     * kalimatnya (mis. "Gagal: tunnel terputus, reconnect otomatis gagal
     * (...)"). Status yang diawali "Gagal" SELALU dianggap status akhir
     * (bukan transisi) -- dicek di awal, sama seperti prioritas di
     * [applyStatusPillColor] -- supaya tombol balik ke "Connect" dan bisa
     * dicoba lagi.
     */
    private fun applyConnectButtonState(status: String) {
        val isIdle = status == "Belum tersambung" || status == "Terputus"
        val isFailed = !isIdle && status.startsWith("Gagal")
        val isTransitioning = !isIdle && !isFailed && (
            status.contains("Menghubungkan") ||
            status.contains("Membuat") ||
            status.contains("tersambung") ||
            status.contains("Memutuskan") ||
            status.contains("reconnect otomatis", ignoreCase = true)
        )
        val isConnected = !isIdle && !isFailed && !isTransitioning && status.contains("aktif", ignoreCase = true)

        connectButtonState = when {
            isTransitioning -> ConnectButtonState.CONNECTING
            isConnected -> ConnectButtonState.CONNECTED
            else -> ConnectButtonState.IDLE
        }

        when (connectButtonState) {
            ConnectButtonState.CONNECTING -> {
                // FIX: dulu isEnabled = false di sini -- sekarang tetap enabled
                // supaya tap masih bisa membatalkan lewat onConnectToggleClicked().
                binding.btnConnectToggle.isEnabled = true
                binding.btnConnectToggle.text = when {
                    status.contains("Memutuskan") -> "Memutuskan..."
                    else -> "Batalkan"
                }
                binding.btnConnectToggle.backgroundTintList =
                    ContextCompat.getColorStateList(this, R.color.text_hint)
            }
            ConnectButtonState.CONNECTED -> {
                binding.btnConnectToggle.isEnabled = true
                binding.btnConnectToggle.text = "Disconnect"
                binding.btnConnectToggle.backgroundTintList =
                    ContextCompat.getColorStateList(this, R.color.status_error)
            }
            ConnectButtonState.IDLE -> {
                binding.btnConnectToggle.isEnabled = true
                binding.btnConnectToggle.text = "Connect"
                binding.btnConnectToggle.backgroundTintList =
                    ContextCompat.getColorStateList(this, R.color.brand_primary)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Profil bisa saja baru saja diubah di SshConfigActivity/XrayConfigActivity --
        // refresh ringkasan & target ping setiap kali Dashboard kembali ditampilkan.
        refreshActiveProfileSummary()
        binding.tvPingResult.text = ""
    }

    /** Tampilkan ringkasan profil yang sedang aktif (hasil simpan terakhir) & target ping. */
    private fun refreshActiveProfileSummary() {
        val saved = ConfigStore.load(this)
        if (saved == null) {
            binding.tvActiveProfile.text = "Profil aktif: belum ada konfigurasi"
            binding.tvPingTarget.text = "Target: belum ada konfigurasi"
            return
        }
        if (saved.modeIndex == 5) {
            val parsed = runCatching { XrayLinkParser.parse(saved.xrayLink) }.getOrNull()
            if (parsed != null) {
                binding.tvActiveProfile.text = "Profil aktif: Xray — ${parsed.address}:${parsed.port}"
                binding.tvPingTarget.text = "Target: ${parsed.address}:${parsed.port} (Xray)"
            } else {
                binding.tvActiveProfile.text = "Profil aktif: Xray — link belum valid"
                binding.tvPingTarget.text = "Target: link Xray belum valid"
            }
        } else {
            val modeName = when (saved.modeIndex) {
                1 -> "SSH SSL"
                2 -> "SSH TLS Payload Proxy"
                3 -> "Payload + Remote Proxy"
                else -> "SSH"
            }
            binding.tvActiveProfile.text = "Profil aktif: $modeName — ${saved.host}:${saved.port}"
            binding.tvPingTarget.text = "Target: ${saved.host}:${saved.port} ($modeName)"
        }
    }

    private fun onCheckPingClicked() {
        val saved = ConfigStore.load(this)
        if (saved == null) {
            binding.tvPingResult.text = "Belum ada konfigurasi"
            return
        }
        val (host, port) = if (saved.modeIndex == 5) {
            val parsed = runCatching { XrayLinkParser.parse(saved.xrayLink) }.getOrNull()
                ?: run {
                    binding.tvPingResult.text = "Link Xray tidak valid"
                    return
                }
            parsed.address to parsed.port
        } else {
            saved.host to saved.port
        }

        binding.btnCheckPing.isEnabled = false
        binding.pbPinging.visibility = android.view.View.VISIBLE
        binding.tvPingResult.text = ""

        lifecycleScope.launch {
            val result = PingUtil.tcpPing(host, port)
            binding.btnCheckPing.isEnabled = true
            binding.pbPinging.visibility = android.view.View.GONE
            if (result.success) {
                binding.tvPingResult.text = "${result.latencyMs} ms"
                binding.tvPingResult.setTextColor(ContextCompat.getColor(this@DashboardActivity, R.color.status_success))
            } else {
                binding.tvPingResult.text = "Gagal"
                binding.tvPingResult.setTextColor(ContextCompat.getColor(this@DashboardActivity, R.color.status_error))
            }
        }
    }

    private fun onConnectClicked() {
        val saved = ConfigStore.load(this)
        if (saved == null) {
            binding.tvStatus.text = "Belum ada konfigurasi, buka menu SSH atau Xray dulu"
            return
        }

        if (saved.modeIndex == 5) {
            if (saved.xrayLink.isBlank()) {
                binding.tvStatus.text = "Link Xray belum diisi, buka menu Xray dulu"
                return
            }
            connectWith(
                PendingConnection(
                    host = "", port = 0, username = "", password = "", mode = ConnectionMode.XRAY,
                    sni = "", payload = "", proxyHost = "", proxyPort = null, tlsVersion = null,
                    useWebSocket = false, wsPath = "", proxyRawMode = false, xrayLink = saved.xrayLink,
                    customHeaders = "", ignoreCertErrors = false,
                    dns1 = saved.dns1, dns2 = saved.dns2
                )
            )
            return
        }

        if (saved.host.isBlank() || saved.username.isBlank()) {
            binding.tvStatus.text = "Host/username belum lengkap, buka menu SSH dulu"
            return
        }

        val modeIndex = saved.modeIndex
        val mode = when (modeIndex) {
            1 -> ConnectionMode.SSH_SSL
            2 -> ConnectionMode.SSH_SSL_PAYLOAD
            3 -> ConnectionMode.REMOTE_PROXY
            else -> ConnectionMode.SSH
        }
        val usesPayload = modeIndex == 2 || modeIndex == 3
        val usesProxy = modeIndex == 2 || modeIndex == 3
        val proxyRawMode = usesProxy && saved.proxyRawMode
        // Mode 3 (Payload + Remote Proxy) TIDAK pernah dibungkus TLS -- baik
        // proxy CONNECT biasa maupun raw passthrough sama-sama jalan plaintext.
        // Lihat ServerConfig.usesTls().
        val usesTls = modeIndex == 1 || modeIndex == 2

        if (usesProxy && proxyRawMode && saved.proxyHost.isBlank()) {
            binding.tvStatus.text = "Raw Passthrough butuh host/IP proxy atau CDN diisi"
            return
        }
        if (modeIndex == 3 && saved.proxyHost.isBlank()) {
            binding.tvStatus.text = "Payload + Remote Proxy butuh host/IP proxy diisi, buka menu SSH dulu"
            return
        }

        connectWith(
            PendingConnection(
                host = saved.host,
                port = saved.port,
                username = saved.username,
                password = saved.password,
                mode = mode,
                sni = saved.sni,
                payload = if (usesPayload) saved.payload else "",
                proxyHost = if (usesProxy) saved.proxyHost else "",
                proxyPort = if (usesProxy) saved.proxyPort.toIntOrNull() else null,
                tlsVersion = if (usesTls) saved.tlsVersion.ifEmpty { null } else null,
                useWebSocket = true,
                wsPath = saved.wsPath,
                proxyRawMode = proxyRawMode,
                xrayLink = "",
                customHeaders = saved.customHeaders,
                ignoreCertErrors = saved.ignoreCertErrors,
                dns1 = saved.dns1,
                dns2 = saved.dns2
            )
        )
    }

    private fun connectWith(candidate: PendingConnection) {
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            pending = candidate
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
            startVpnService(candidate)
        }
    }

    private fun startVpnServiceFromPending() {
        pending?.let { startVpnService(it) }
    }

    private fun onDisconnectClicked() {
        val intent = Intent(this, MyVpnService::class.java).apply {
            action = MyVpnService.ACTION_DISCONNECT
        }
        startService(intent)
    }

    private fun startVpnService(c: PendingConnection) {
        val intent = Intent(this, MyVpnService::class.java).apply {
            action = MyVpnService.ACTION_CONNECT
            putExtra(MyVpnService.EXTRA_HOST, c.host)
            putExtra(MyVpnService.EXTRA_PORT, c.port)
            putExtra(MyVpnService.EXTRA_USERNAME, c.username)
            putExtra(MyVpnService.EXTRA_PASSWORD, c.password)
            putExtra(MyVpnService.EXTRA_MODE, c.mode.name)
            if (c.sni.isNotEmpty()) putExtra(MyVpnService.EXTRA_SSL_SNI, c.sni)
            if (c.payload.isNotEmpty()) putExtra(MyVpnService.EXTRA_PAYLOAD, c.payload)
            if (c.proxyHost.isNotEmpty()) putExtra(MyVpnService.EXTRA_PROXY_HOST, c.proxyHost)
            if (c.proxyPort != null) putExtra(MyVpnService.EXTRA_PROXY_PORT, c.proxyPort)
            if (!c.tlsVersion.isNullOrEmpty()) putExtra(MyVpnService.EXTRA_TLS_VERSION, c.tlsVersion)
            putExtra(MyVpnService.EXTRA_WEBSOCKET_ENABLED, c.useWebSocket)
            if (c.wsPath.isNotEmpty()) putExtra(MyVpnService.EXTRA_WS_PATH, c.wsPath)
            putExtra(MyVpnService.EXTRA_PROXY_RAW_MODE, c.proxyRawMode)
            if (c.xrayLink.isNotEmpty()) putExtra(MyVpnService.EXTRA_XRAY_LINK, c.xrayLink)
            if (c.customHeaders.isNotEmpty()) putExtra(MyVpnService.EXTRA_CUSTOM_HEADERS, c.customHeaders)
            putExtra(MyVpnService.EXTRA_IGNORE_CERT_ERRORS, c.ignoreCertErrors)
            if (c.dns1.isNotEmpty()) putExtra(MyVpnService.EXTRA_DNS1, c.dns1)
            if (c.dns2.isNotEmpty()) putExtra(MyVpnService.EXTRA_DNS2, c.dns2)
        }
        startForegroundService(intent)
    }

    /**
     * Ganti warna pill status headline sesuai konteksnya (sukses/gagal/proses).
     *
     * FIX (bug "pill hijau/Terhubung nyala duluan padahal masih proses/internet
     * mati"): urutan pengecekan sebelumnya salah -- cabang "aktif" dicek SEBELUM
     * cabang transisi ("tersambung", dll). Padahal status tengah-proses seperti
     * "SSH tersambung. Mengaktifkan tunnel..." / "Xray-core tersambung.
     * Mengaktifkan tunnel..." MENGANDUNG substring "aktif" (dari kata
     * "Mengaktifkan"), jadi ke-match duluan sebagai SUKSES walau proses
     * verifikasi tunnel BENERAN tembus ke internet (keepAliveThroughTunnel di
     * MyVpnService) belum selesai/belum tentu berhasil -- inilah yang bikin
     * status kelihatan "langsung Terhubung" walau internet device masih mati.
     * Sekarang cabang transisi dicek DULUAN (persis seperti prioritas di
     * [applyConnectButtonState]), baru cabang "aktif" -- sehingga pill cuma
     * hijau kalau statusnya BENAR-BENAR "Tunnel aktif ..." (state final
     * setelah verifikasi sukses), bukan status antara yang kebetulan
     * mengandung kata "aktif".
     */
    private fun applyStatusPillColor(status: String) {
        val (bg, text) = when {
            status.startsWith("Gagal") -> R.color.status_error_bg to R.color.status_error
            status.contains("Menghubungkan") || status.contains("Membuat") || status.contains("tersambung") ||
                status.contains("Memutuskan") ->
                R.color.status_running_bg to R.color.status_running
            status.contains("aktif", ignoreCase = true) -> R.color.status_success_bg to R.color.status_success
            else -> R.color.status_pending_bg to R.color.text_primary
        }
        (binding.pillStatus.background.mutate() as GradientDrawable).setColor(ContextCompat.getColor(this, bg))
        binding.tvStatus.setTextColor(ContextCompat.getColor(this, text))
    }
}
