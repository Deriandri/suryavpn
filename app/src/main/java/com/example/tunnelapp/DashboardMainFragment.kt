package com.example.tunnelapp

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.VpnService
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.tunnelapp.databinding.FragmentDashboardMainBinding
import com.example.tunnelapp.model.ProfileStore
import com.example.tunnelapp.model.ConnectionMode
import com.example.tunnelapp.tunnel.ConnectionStep
import com.example.tunnelapp.tunnel.MyVpnService
import com.example.tunnelapp.tunnel.StatusBus
import com.example.tunnelapp.tunnel.StepStatus
import com.example.tunnelapp.tunnel.XrayLinkParser
import kotlinx.coroutines.launch

/**
 * Halaman "Main" dari Dashboard (lihat [DashboardPagerAdapter]) -- persis
 * logika [DashboardActivity] yang lama (tombol Connect/Disconnect,
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
        val dns2: String,
        // FIX/FITUR BARU (fallback akun cadangan di MyVpnService): id profil
        // ProfileStore yang dipakai request Connect ini -- diteruskan ke
        // Service lewat EXTRA_PROFILE_ID supaya dia tahu profil mana yang
        // HARUS DIKECUALIKAN saat menyusun daftar akun cadangan.
        val profileId: String?
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
        binding.rowSwitchAccount.setOnClickListener { showAccountPicker() }

        // Kartu "Menu" (Konfigurasi SSH/Xray & Log Koneksi) sudah dihapus
        // seluruhnya dari halaman ini -- akses Konfigurasi lewat tab
        // "Konfigurasi" di bilah navigasi bawah (lihat ConfigActivity), akses
        // Log Koneksi lewat tab "Log" di atas / geser layar ke kanan.

        // viewLifecycleOwner (bukan Fragment.lifecycleScope) supaya collector
        // berhenti begitu view Fragment ini dihancurkan (mis. ViewPager2
        // membuang halaman ini dari memori saat jauh dari halaman aktif).
        // pillStatus/tvStatus DIPINDAHKAN KE SINI dari DashboardActivity
        // (permintaan user: kartu status sekarang cuma bagian halaman
        // "Main", tidak lagi fixed & ikut tampil di halaman "Log") --
        // sekalian juga dengarkan status untuk state tombol Connect/Disconnect.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                StatusBus.state.collect { status ->
                    binding.tvStatus.text = status
                    applyStatusPillColor(status)
                    applyConnectButtonState(status)
                }
            }
        }

        // Kartu "Tahapan Koneksi" dipindahkan ke sini dari tab Log (lihat
        // DashboardLogFragment yang lama) -- ditaruh tepat di atas "Profil
        // aktif" supaya progres tahapan koneksi langsung kelihatan di Main.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                StatusBus.steps.collect { steps -> renderLogSteps(steps) }
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
        // refresh ringkasan profil aktif setiap kali halaman Main kembali ditampilkan.
        refreshActiveProfileSummary()
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

    /** Dipindahkan dari DashboardActivity.applyStatusPillColor() -- logikanya sama persis. */
    private fun applyStatusPillColor(status: String) {
        val (bg, text) = when {
            status.startsWith("Gagal") -> R.color.status_error_bg to R.color.status_error
            status.contains("Menghubungkan") || status.contains("Membuat") || status.contains("tersambung") ||
                status.contains("Memutuskan") ->
                R.color.status_running_bg to R.color.status_running
            status.contains("aktif", ignoreCase = true) -> R.color.status_success_bg to R.color.status_success
            else -> R.color.status_pending_bg to R.color.text_primary
        }
        val ctx = requireContext()
        (binding.pillStatus.background.mutate() as GradientDrawable).setColor(ContextCompat.getColor(ctx, bg))
        binding.tvStatus.setTextColor(ContextCompat.getColor(ctx, text))
    }

    private fun renderLogSteps(steps: List<ConnectionStep>) {
        val b = _binding ?: return
        if (steps.isEmpty()) {
            b.tvLogEmpty.visibility = View.VISIBLE
            b.llLogSteps.visibility = View.GONE
            b.llLogSteps.removeAllViews()
            b.tvStepsProgress.visibility = View.GONE
            return
        }
        b.tvLogEmpty.visibility = View.GONE
        b.llLogSteps.visibility = View.VISIBLE
        b.llLogSteps.removeAllViews()
        val ctx = requireContext()
        val inflater = LayoutInflater.from(ctx)

        // REDESIGN (permintaan user: "Tahapan Koneksi" lebih profesional/
        // modern/smooth): badge "x/y selesai" di header kartu -- y itung
        // semua tahap SELAIN yang di-skip (mis. "CONNECT dilewati" pada mode
        // raw passthrough), supaya persentasenya tetap masuk akal biarpun
        // ada tahap yang memang sengaja tidak dijalankan.
        val countedSteps = steps.count { it.status != StepStatus.SKIPPED }
        val doneSteps = steps.count { it.status == StepStatus.SUCCESS }
        if (countedSteps > 0) {
            b.tvStepsProgress.visibility = View.VISIBLE
            b.tvStepsProgress.text = "$doneSteps/$countedSteps selesai"
        } else {
            b.tvStepsProgress.visibility = View.GONE
        }

        steps.forEachIndexed { index, step ->
            val row = inflater.inflate(R.layout.item_log_step, b.llLogSteps, false)
            val rowRoot = row.findViewById<View>(R.id.rowRoot)
            val lineTop = row.findViewById<View>(R.id.lineTop)
            val lineBottom = row.findViewById<View>(R.id.lineBottom)
            val dot = row.findViewById<View>(R.id.dot)
            val dotStatus = row.findViewById<View>(R.id.dotStatus)
            val spinner = row.findViewById<ProgressBar>(R.id.spinner)
            val tvLabel = row.findViewById<TextView>(R.id.tvLabel)
            val tvDetail = row.findViewById<TextView>(R.id.tvDetail)

            tvLabel.text = step.label

            // Garis rail cuma nyambung ke tahap SEBELUM/SESUDAHnya -- tahap
            // paling atas tidak punya garis di atas, tahap paling bawah
            // tidak punya garis di bawah, biar timeline-nya rapi tanpa
            // "nub" nyangkut di ujung kartu.
            lineTop.visibility = if (index == 0) View.INVISIBLE else View.VISIBLE
            lineBottom.visibility = if (index == steps.lastIndex) View.INVISIBLE else View.VISIBLE

            // Sembunyikan semua isi badge dulu, baru tampilkan yang relevan
            // sesuai status -- badge sekarang cuma punya 2 kemungkinan isi:
            // titik kecil (dotStatus, dipakai utk SUCCESS/ERROR/PENDING/
            // SKIPPED, warnanya dimutasi per status di bawah) atau spinner
            // (RUNNING). SUCCESS & ERROR TIDAK lagi pakai ikon centang/
            // silang putih (permintaan user: disamakan gaya titik polos
            // seperti titik teal di chip "Profil aktif").
            dotStatus.visibility = View.GONE
            spinner.visibility = View.GONE

            val circleColorRes: Int
            val dotColorRes: Int
            when (step.status) {
                StepStatus.RUNNING -> {
                    spinner.visibility = View.VISIBLE
                    circleColorRes = R.color.status_running_bg
                    dotColorRes = R.color.status_running
                }
                StepStatus.SUCCESS -> {
                    dotStatus.visibility = View.VISIBLE
                    circleColorRes = R.color.status_success_bg
                    dotColorRes = R.color.status_success
                }
                StepStatus.ERROR -> {
                    dotStatus.visibility = View.VISIBLE
                    circleColorRes = R.color.status_error_bg
                    dotColorRes = R.color.status_error
                }
                else -> {
                    dotStatus.visibility = View.VISIBLE
                    circleColorRes = R.color.status_pending_bg
                    dotColorRes = R.color.status_pending
                }
            }
            (dot.background.mutate() as GradientDrawable).setColor(ContextCompat.getColor(ctx, circleColorRes))
            (dotStatus.background.mutate() as GradientDrawable).setColor(ContextCompat.getColor(ctx, dotColorRes))

            if (step.status == StepStatus.ERROR && !step.detail.isNullOrEmpty()) {
                tvDetail.visibility = View.VISIBLE
                tvDetail.text = step.detail
                tvDetail.setTextColor(ContextCompat.getColor(ctx, R.color.status_error))
            } else {
                tvDetail.visibility = View.GONE
            }

            tvLabel.setTextColor(
                ContextCompat.getColor(
                    ctx,
                    if (step.status == StepStatus.SKIPPED) R.color.text_hint else R.color.text_primary
                )
            )

            // Highlight lembut & bulat di belakang baris yang lagi RUNNING/
            // ERROR supaya tahap yang butuh perhatian user langsung menonjol
            // sekilas dari daftar, tanpa perlu scroll baca satu-satu.
            val highlightColorRes = when (step.status) {
                StepStatus.RUNNING -> R.color.status_running_bg
                StepStatus.ERROR -> R.color.status_error_bg
                else -> android.R.color.transparent
            }
            (rowRoot.background.mutate() as GradientDrawable).setColor(
                ContextCompat.getColor(ctx, highlightColorRes)
            )

            b.llLogSteps.addView(row)
        }
    }

    private fun refreshActiveProfileSummary() {
        // FIX (multi-akun): sekarang baca akun AKTIF dari ProfileStore, bukan
        // lagi satu-satunya konfigurasi tersimpan -- lihat ProfileStore.kt.
        val saved = ProfileStore.getActive(requireContext())?.config
        if (saved == null) {
            binding.tvActiveProfile.text = "Profil aktif: belum ada konfigurasi"
            return
        }
        // FITUR BARU (nama akun custom): kalau accountName diisi, tampilkan
        // itu sebagai identitas utama profil aktif -- lebih personal & mudah
        // dikenali dibanding host:port mentah, terutama kalau user punya
        // banyak akun. Kosong = fallback persis seperti sebelumnya.
        val label = accountLabel(saved)
        binding.tvActiveProfile.text = "Profil aktif: $label"
        refreshAccountPickerVisibility()
    }

    /** Label ringkas satu akun buat ditampilkan di UI (ringkasan Dashboard
     *  maupun daftar pemilih akun) -- nama custom kalau ada, kalau tidak
     *  fallback ke tipe + host:port (SSH) atau tipe + address:port (Xray). */
    private fun accountLabel(config: com.example.tunnelapp.model.SavedConfig): String {
        if (config.accountName.isNotBlank()) return config.accountName
        if (config.modeIndex == 5) {
            val parsed = runCatching { XrayLinkParser.parse(config.xrayLink) }.getOrNull()
            return if (parsed != null) "Xray — ${parsed.address}:${parsed.port}" else "Xray — link belum valid"
        }
        val modeName = when (config.modeIndex) {
            1 -> "SSH SSL"
            2 -> "SSH TLS Payload Proxy"
            3 -> "Payload + Remote Proxy"
            else -> "SSH"
        }
        return "$modeName — ${config.host}:${config.port}"
    }

    /** Baris "Ganti Akun" cuma masuk akal & ditampilkan kalau ada LEBIH DARI
     *  SATU akun tersimpan -- kalau cuma satu (atau nol), tidak ada apa pun
     *  buat dipilih, jadi disembunyikan supaya tidak bikin UI ramai tanpa guna. */
    private fun refreshAccountPickerVisibility() {
        val count = ProfileStore.getAll(requireContext()).size
        binding.rowSwitchAccount.visibility = if (count > 1) View.VISIBLE else View.GONE
    }

    /**
     * FITUR BARU (permintaan user): pemilih akun tersimpan langsung dari
     * Dashboard, tanpa perlu pindah ke tab "Konfigurasi" dulu -- pakai
     * BottomSheetDialog (Material) supaya transisinya smooth/modern,
     * konsisten dengan gaya visual (kartu rounded, warna brand) di seluruh
     * app. Cuma MENGUBAH POINTER akun aktif ([ProfileStore.setActiveId]) --
     * TIDAK memutus/menyambungkan ulang tunnel yang sedang berjalan (sama
     * seperti tombol "Jadikan Aktif" di ConfigActivity), supaya perilakunya
     * konsisten & tidak mengejutkan user yang sedang connect.
     */
    private fun showAccountPicker() {
        val ctx = requireContext()
        val profiles = ProfileStore.getAll(ctx)
        if (profiles.isEmpty()) return
        val activeId = ProfileStore.getActiveId(ctx)

        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(ctx)
        val sheetView = layoutInflater.inflate(R.layout.dialog_account_picker, null)
        dialog.setContentView(sheetView)

        val container = sheetView.findViewById<android.widget.LinearLayout>(R.id.llPickerAccounts)
        val inflater = LayoutInflater.from(ctx)
        profiles.forEachIndexed { index, profile ->
            val itemView = inflater.inflate(R.layout.item_account_picker_row, container, false)
            val ivIcon = itemView.findViewById<android.widget.ImageView>(R.id.ivPickerIcon)
            val ivIconBg = itemView.findViewById<View>(R.id.ivPickerIconBg)
            val tvName = itemView.findViewById<TextView>(R.id.tvPickerName)
            val tvBadge = itemView.findViewById<TextView>(R.id.tvPickerBadge)
            val ivCheck = itemView.findViewById<android.widget.ImageView>(R.id.ivPickerCheck)
            val divider = itemView.findViewById<View>(R.id.dividerPickerRow)

            val config = profile.config
            val isXray = config.modeIndex == 5
            ivIconBg.setBackgroundResource(if (isXray) R.drawable.bg_avatar_xray else R.drawable.bg_avatar_ssh)
            ivIcon.setImageResource(if (isXray) R.drawable.ic_account_xray else R.drawable.ic_account_ssh)
            tvBadge.text = if (isXray) "XRAY" else "SSH"
            tvName.text = accountLabel(config)

            val isActive = profile.id == activeId
            ivCheck.visibility = if (isActive) View.VISIBLE else View.INVISIBLE
            if (index == profiles.lastIndex) divider.visibility = View.GONE

            itemView.setOnClickListener {
                if (!isActive) {
                    ProfileStore.setActiveId(ctx, profile.id)
                    refreshActiveProfileSummary()
                }
                dialog.dismiss()
            }
            container.addView(itemView)
        }

        dialog.show()
    }

    private fun onConnectClicked() {
        val activeProfile = ProfileStore.getActive(requireContext())
        val saved = activeProfile?.config
        if (saved == null) {
            StatusBus.state.value = "Belum ada konfigurasi, buka menu SSH atau Xray dulu"
            return
        }

        if (saved.modeIndex == 5) {
            if (saved.xrayLink.isBlank()) {
                StatusBus.state.value = "Link Xray belum diisi, buka menu Xray dulu"
                return
            }
            connectWith(
                PendingConnection(
                    host = "", port = 0, username = "", password = "", mode = ConnectionMode.XRAY,
                    sni = "", payload = "", proxyHost = "", proxyPort = null, tlsVersion = null,
                    useWebSocket = false, wsPath = "", proxyRawMode = false, xrayLink = saved.xrayLink,
                    customHeaders = "", ignoreCertErrors = false,
                    dns1 = saved.dns1, dns2 = saved.dns2,
                    profileId = activeProfile.id
                )
            )
            return
        }

        if (saved.host.isBlank() || saved.username.isBlank()) {
            StatusBus.state.value = "Host/username belum lengkap, buka menu SSH dulu"
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
        // DIKEMBALIKAN (permintaan user): modeIndex 1 (SSH SSL) dicopot lagi --
        // disalin dari SshConfigActivity/ServerConfig.toServerConfigOrNull supaya
        // konsisten, lihat catatan lengkap di ServerConfig.kt.
        val usesProxy = modeIndex == 2 || modeIndex == 3
        val proxyRawMode = usesProxy && saved.proxyRawMode
        val usesTls = modeIndex == 1 || modeIndex == 2

        if (usesProxy && proxyRawMode && saved.proxyHost.isBlank()) {
            StatusBus.state.value = "Raw Passthrough butuh host/IP proxy atau CDN diisi"
            return
        }
        if (modeIndex == 3 && saved.proxyHost.isBlank()) {
            StatusBus.state.value = "Payload + Remote Proxy butuh host/IP proxy diisi, buka menu SSH dulu"
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
                dns2 = saved.dns2,
                profileId = activeProfile.id
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
            if (!c.profileId.isNullOrEmpty()) putExtra(MyVpnService.EXTRA_PROFILE_ID, c.profileId)
        }
        ctx.startForegroundService(intent)
    }

}
