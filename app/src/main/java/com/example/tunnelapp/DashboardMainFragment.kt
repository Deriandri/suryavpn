package com.example.tunnelapp

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.VpnService
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.tunnelapp.databinding.FragmentDashboardMainBinding
import com.example.tunnelapp.model.ConfigStore
import com.example.tunnelapp.model.ConnectionMode
import com.example.tunnelapp.tunnel.MyVpnService
import com.example.tunnelapp.tunnel.PingUtil
import com.example.tunnelapp.tunnel.StatusBus
import com.example.tunnelapp.tunnel.XrayLinkParser
import kotlinx.coroutines.launch

/**
 * Halaman "Main" dari Dashboard (lihat [DashboardPagerAdapter]) -- persis
 * logika [DashboardActivity] yang lama (tombol Connect/Disconnect, cek ping,
 * ringkasan profil aktif, baris menu SSH/Xray/Log), dipindahkan ke Fragment
 * supaya bisa jadi salah satu halaman ViewPager2 yang digeser-geser dengan
 * halaman "Log" ([DashboardLogFragment]).
 */
class DashboardMainFragment : Fragment() {

    private var _binding: FragmentDashboardMainBinding? = null
    private val binding get() = _binding!!

    // registerForActivityResult HARUS didaftarkan sebelum Fragment mencapai
    // STARTED -- field initializer di sini (jalan saat Fragment dibuat oleh
    // FragmentStateAdapter, sebelum onCreate) memenuhi syarat itu, sama
    // seperti kalau dipanggil di Activity.
    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            startVpnServiceFromPending()
        } else {
            // Lewat StatusBus.state supaya pill warna & state tombol tetap
            // konsisten (bukan nulis langsung ke tvStatus.text).
            StatusBus.state.value = "Gagal: izin VPN ditolak"
            pending = null
        }
    }

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

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDashboardMainBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnConnectToggle.setOnClickListener { onConnectToggleClicked() }
        binding.btnCheckPing.setOnClickListener { onCheckPingClicked() }

        binding.rowMenuSsh.setOnClickListener {
            startActivity(Intent(requireContext(), SshConfigActivity::class.java))
        }
        binding.rowMenuXray.setOnClickListener {
            startActivity(Intent(requireContext(), XrayConfigActivity::class.java))
        }
        // "Log Koneksi" TIDAK LAGI membuka layar baru -- sekarang cuma pindah
        // ke tab/halaman "Log" di sebelah kanan Dashboard (sama seperti geser
        // layar ke kiri), lewat host Activity (lihat DashboardPagerHost).
        binding.rowMenuLog.setOnClickListener {
            (activity as? DashboardActivity)?.goToLogPage()
        }

        // viewLifecycleOwner (bukan Fragment.lifecycleScope) supaya collector
        // berhenti begitu view Fragment ini dihancurkan (mis. ViewPager2
        // membuang halaman ini dari memori saat jauh dari halaman aktif).
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                StatusBus.state.collect { status ->
                    binding.tvStatus.text = status
                    applyStatusPillColor(status)
                    applyConnectButtonState(status)
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onResume() {
        super.onResume()
        // Profil bisa saja baru saja diubah di SshConfigActivity/XrayConfigActivity --
        // refresh ringkasan & target ping setiap kali halaman Main kembali ditampilkan.
        refreshActiveProfileSummary()
        binding.tvPingResult.text = ""
    }

    private enum class ConnectButtonState { IDLE, CONNECTING, CONNECTED }

    private var connectButtonState = ConnectButtonState.IDLE

    private fun onConnectToggleClicked() {
        when (connectButtonState) {
            ConnectButtonState.CONNECTED -> onDisconnectClicked()
            ConnectButtonState.IDLE -> onConnectClicked()
            ConnectButtonState.CONNECTING -> onDisconnectClicked()
        }
    }

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

        val ctx = requireContext()
        when (connectButtonState) {
            ConnectButtonState.CONNECTING -> {
                binding.btnConnectToggle.isEnabled = true
                binding.btnConnectToggle.text = when {
                    status.contains("Memutuskan") -> "Memutuskan..."
                    else -> "Batalkan"
                }
                binding.btnConnectToggle.backgroundTintList =
                    ContextCompat.getColorStateList(ctx, R.color.text_hint)
            }
            ConnectButtonState.CONNECTED -> {
                binding.btnConnectToggle.isEnabled = true
                binding.btnConnectToggle.text = "Disconnect"
                binding.btnConnectToggle.backgroundTintList =
                    ContextCompat.getColorStateList(ctx, R.color.status_error)
            }
            ConnectButtonState.IDLE -> {
                binding.btnConnectToggle.isEnabled = true
                binding.btnConnectToggle.text = "Connect"
                binding.btnConnectToggle.backgroundTintList =
                    ContextCompat.getColorStateList(ctx, R.color.brand_primary)
            }
        }
    }

    private fun refreshActiveProfileSummary() {
        val saved = ConfigStore.load(requireContext())
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
        val saved = ConfigStore.load(requireContext())
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
        binding.pbPinging.visibility = View.VISIBLE
        binding.tvPingResult.text = ""

        viewLifecycleOwner.lifecycleScope.launch {
            val result = PingUtil.tcpPing(host, port)
            val b = _binding ?: return@launch
            b.btnCheckPing.isEnabled = true
            b.pbPinging.visibility = View.GONE
            if (result.success) {
                b.tvPingResult.text = "${result.latencyMs} ms"
                b.tvPingResult.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_success))
            } else {
                b.tvPingResult.text = "Gagal"
                b.tvPingResult.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_error))
            }
        }
    }

    private fun onConnectClicked() {
        val saved = ConfigStore.load(requireContext())
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
        val prepareIntent = VpnService.prepare(requireContext())
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
        val intent = Intent(requireContext(), MyVpnService::class.java).apply {
            action = MyVpnService.ACTION_DISCONNECT
        }
        requireContext().startService(intent)
    }

    private fun startVpnService(c: PendingConnection) {
        val ctx = requireContext()
        val intent = Intent(ctx, MyVpnService::class.java).apply {
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
        ctx.startForegroundService(intent)
    }

    private fun applyStatusPillColor(status: String) {
        val ctx = requireContext()
        val (bg, text) = when {
            status.startsWith("Gagal") -> R.color.status_error_bg to R.color.status_error
            status.contains("Menghubungkan") || status.contains("Membuat") || status.contains("tersambung") ||
                status.contains("Memutuskan") ->
                R.color.status_running_bg to R.color.status_running
            status.contains("aktif", ignoreCase = true) -> R.color.status_success_bg to R.color.status_success
            else -> R.color.status_pending_bg to R.color.text_primary
        }
        (binding.pillStatus.background.mutate() as GradientDrawable).setColor(ContextCompat.getColor(ctx, bg))
        binding.tvStatus.setTextColor(ContextCompat.getColor(ctx, text))
    }
}
