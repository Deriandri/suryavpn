package com.example.tunnelapp

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.VpnService
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.tunnelapp.databinding.FragmentDashboardMainBinding
import com.example.tunnelapp.model.ProfileStore
import com.example.tunnelapp.model.ConnectionMode
import com.example.tunnelapp.model.ConfigLockMode
import com.example.tunnelapp.tunnel.ConnectionStep
import com.example.tunnelapp.tunnel.MyVpnService
import com.example.tunnelapp.tunnel.StatusBus
import com.example.tunnelapp.tunnel.StepStatus
import com.example.tunnelapp.tunnel.TrafficStatsBus
import com.example.tunnelapp.tunnel.XrayLinkParser
import kotlinx.coroutines.launch
import java.util.Locale

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
        // REFACTOR (opsi 1+2, toggle independen): diteruskan ke Service
        // lewat EXTRA_TLS_ENABLED/EXTRA_PROXY_ENABLED/EXTRA_PAYLOAD_ENABLED
        // -- default false (tidak relevan untuk PendingConnection Xray).
        val tlsEnabled: Boolean = false,
        val proxyEnabled: Boolean = false,
        val payloadEnabled: Boolean = false,
        // FITUR BARU (permintaan user, "sembunyikan payload di Log untuk akun
        // terkunci total"): diteruskan ke Service lewat
        // EXTRA_HIDE_SENSITIVE_LOGS -- lihat KDoc [ServerConfig.hideSensitiveLogs].
        val hideSensitiveLogs: Boolean,
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

        // FITUR BARU (statistik data real-time): warna garis grafik disamakan
        // dengan warna lencana ikon di kartu masing-masing (hijau =
        // Download/RX, indigo = Upload/TX) supaya warnanya konsisten dari
        // ikon sampai ke grafik.
        binding.sparklineDownload.lineColor = ContextCompat.getColor(requireContext(), R.color.status_success)
        binding.sparklineUpload.lineColor = ContextCompat.getColor(requireContext(), R.color.brand_primary)

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

        // Kartu "Tahapan Koneksi" (daftar bertitik/timeline) DIHILANGKAN
        // (permintaan user) -- diganti kartu "Catatan" (tvNotes). Isinya
        // sekarang punya DUA sumber, lihat renderCatatan():
        //  - Kalau akun aktif punya catatan hasil impor (SavedConfig.note,
        //    lihat ConfigIO.kt) -> catatan ITU yang ditampilkan (dirender
        //    HtmlCompat.fromHtml), MENGGANTIKAN log koneksi sepenuhnya.
        //  - Kalau tidak (note kosong) -> kartu ini tetap menampilkan log
        //    koneksi seperti sebelumnya (StatusBus.steps).
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                StatusBus.steps.collect { steps ->
                    lastConnectionSteps = steps
                    renderCatatan()
                }
            }
        }

        // FITUR BARU (permintaan user: total pemakaian JANGAN reset kalau
        // app ditutup selama VPN masih konek) -- Fragment ini SEKARANG
        // cuma "berlangganan" TrafficStatsBus.state, tidak lagi menghitung
        // byte sendiri. Hitungan sebenarnya (baseline, total, kecepatan)
        // dipegang MyVpnService (lihat startTrafficPolling() di sana),
        // yang hidup selama tunnel aktif -- independen dari Fragment ini
        // dibuka/ditutup berkali-kali. state.active == false berarti tidak
        // ada tunnel aktif sama sekali -> tampilkan 0/grafik kosong.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                TrafficStatsBus.state.collect { snapshot ->
                    val b = _binding ?: return@collect
                    if (snapshot.active) {
                        b.tvDownloadSpeed.text = formatSpeed(snapshot.rxSpeedBps)
                        b.tvUploadSpeed.text = formatSpeed(snapshot.txSpeedBps)
                        b.tvDownloadTotal.text = formatTotalBytes(snapshot.rxTotalBytes)
                        b.tvUploadTotal.text = formatTotalBytes(snapshot.txTotalBytes)
                        b.sparklineDownload.submitSample(snapshot.rxSpeedBps)
                        b.sparklineUpload.submitSample(snapshot.txSpeedBps)
                    } else {
                        b.tvDownloadSpeed.text = "0 KB/s"
                        b.tvUploadSpeed.text = "0 KB/s"
                        b.tvDownloadTotal.text = "Total: 0 MB"
                        b.tvUploadTotal.text = "Total: 0 MB"
                        b.sparklineDownload.clear()
                        b.sparklineUpload.clear()
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /** Format kecepatan (byte/detik) jadi "X.X KB/s" atau "X.XX MB/s" untuk kartu Download/Upload. */
    private fun formatSpeed(bytesPerSec: Float): String {
        val kbps = bytesPerSec / 1024f
        return if (kbps >= 1024f) {
            String.format(Locale.US, "%.2f MB/s", kbps / 1024f)
        } else {
            String.format(Locale.US, "%.1f KB/s", kbps)
        }
    }

    /** Format total byte jadi "Total: X.X MB" atau "Total: X.XX GB" untuk kartu Download/Upload. */
    private fun formatTotalBytes(totalBytes: Long): String {
        val mb = totalBytes / (1024f * 1024f)
        return if (mb >= 1024f) {
            String.format(Locale.US, "Total: %.2f GB", mb / 1024f)
        } else {
            String.format(Locale.US, "Total: %.1f MB", mb)
        }
    }

    override fun onResume() {
        super.onResume()
        // Profil bisa saja baru saja diubah di SshConfigActivity/XrayConfigActivity --
        // refresh ringkasan profil aktif setiap kali halaman Main kembali ditampilkan.
        refreshActiveProfileSummary()
    }

    private enum class ConnectButtonState { IDLE, CONNECTING, CONNECTED }

    private var connectButtonState = ConnectButtonState.IDLE

    /** Cache tahap koneksi terakhir, dipakai [renderCatatan] sebagai fallback
     *  (lihat dokumentasinya) tiap kali profil aktif berganti tanpa perlu
     *  menunggu StatusBus.steps mengeluarkan nilai baru lagi. */
    private var lastConnectionSteps: List<ConnectionStep> = emptyList()

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
        // isConnected sekarang pakai StatusBus.isConnected() (dipindah ke sana
        // supaya ConfigActivity bisa pakai definisi "terhubung" yang sama --
        // lihat kdoc-nya) -- perilaku PERSIS sama seperti sebelumnya.
        val isConnected = StatusBus.isConnected(status)

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

    /**
     * Kartu "Catatan" (menggantikan tampilan "Tahapan Koneksi" lama yang
     * berupa daftar bertitik/timeline dengan garis rail & spinner per baris
     * -- permintaan user). Sekarang tahap-tahap koneksi dirangkum jadi satu
     * blok teks yang dirender lewat [HtmlCompat.fromHtml], supaya ke
     * depannya kartu ini bisa menampilkan catatan berformat HTML APA PUN
     * (bold, warna, link, dst), tidak lagi terikat ke tampilan timeline
     * yang kaku. Warna per baris dibuat lewat tag <font color=...> memakai
     * hex yang sama seperti warna status di tempat lain (status_success/
     * error/running/pending) supaya tetap konsisten secara visual.
     *
     * FITUR BARU (permintaan user, "catatan config hasil impor tampil di
     * menu Catatan, gantikan log -- tapi kalau tidak ada catatan, log
     * tetap ada"): fungsi ini SEKARANG murni fallback tampilan log koneksi
     * -- pemanggilnya SELALU lewat [renderCatatan], yang mengecek dulu
     * apakah akun aktif punya catatan hasil impor ([SavedConfig.note])
     * sebelum memutuskan mau menampilkan catatan itu atau log koneksi ini.
     */
    private fun renderConnectionLogAsNotes(steps: List<ConnectionStep>) {
        val b = _binding ?: return
        val ctx = requireContext()

        if (steps.isEmpty()) {
            b.tvNotes.text = "Belum ada catatan. Tekan Connect di tab Main untuk memulai."
            return
        }

        fun colorHex(colorRes: Int) = String.format(
            "#%06X", 0xFFFFFF and ContextCompat.getColor(ctx, colorRes)
        )

        // "x/y selesai" -- y itung semua tahap SELAIN yang di-skip (mis.
        // "CONNECT dilewati" pada mode raw passthrough), sama seperti
        // logika badge progres versi timeline sebelumnya.
        val countedSteps = steps.count { it.status != StepStatus.SKIPPED }
        val doneSteps = steps.count { it.status == StepStatus.SUCCESS }

        val html = StringBuilder()
        if (countedSteps > 0) {
            html.append("<b>$doneSteps/$countedSteps selesai</b><br><br>")
        }

        steps.forEachIndexed { index, step ->
            if (index > 0) html.append("<br>")
            val (colorRes, prefix) = when (step.status) {
                StepStatus.RUNNING -> R.color.status_running to "&#8226; "
                StepStatus.SUCCESS -> R.color.status_success to "&#10003; "
                StepStatus.ERROR -> R.color.status_error to "&#10007; "
                StepStatus.SKIPPED -> R.color.text_hint to "&#8226; "
                StepStatus.PENDING -> R.color.text_hint to "&#8226; "
            }
            html.append("<font color='${colorHex(colorRes)}'>$prefix${step.label}</font>")
            if (step.status == StepStatus.ERROR && !step.detail.isNullOrEmpty()) {
                html.append("<br><font color='${colorHex(R.color.status_error)}'><small>${step.detail}</small></font>")
            }
        }

        b.tvNotes.text = HtmlCompat.fromHtml(html.toString(), HtmlCompat.FROM_HTML_MODE_LEGACY)
    }

    /**
     * FITUR BARU (permintaan user): pintu masuk TUNGGAL buat isi kartu
     * "Catatan" -- dipanggil tiap kali StatusBus.steps berubah MAUPUN tiap
     * kali profil aktif bisa saja berganti ([refreshActiveProfileSummary],
     * dipanggil dari onResume() & [showAccountPicker]), supaya kartu ini
     * selalu konsisten dengan akun yang sedang aktif SAAT INI:
     *  - Akun aktif punya catatan hasil impor (SavedConfig.note tidak
     *    kosong, lihat ConfigIO.kt/ConfigActivity.showExportDetailsDialog)
     *    -> catatan itu yang ditampilkan (HtmlCompat.fromHtml), MENGGANTIKAN
     *    log koneksi sepenuhnya.
     *  - Akun aktif TIDAK punya catatan (baru dibuat manual di app ini,
     *    atau hasil impor yang memang tidak diisi catatan sama sekali)
     *    -> kartu ini tetap menampilkan log koneksi seperti biasa lewat
     *    [renderConnectionLogAsNotes].
     */
    private fun renderCatatan() {
        val b = _binding ?: return
        val accountNote = ProfileStore.getActive(requireContext())?.config?.note?.trim().orEmpty()
        if (accountNote.isNotEmpty()) {
            b.tvNotes.text = HtmlCompat.fromHtml(accountNote, HtmlCompat.FROM_HTML_MODE_LEGACY)
        } else {
            renderConnectionLogAsNotes(lastConnectionSteps)
        }
    }

    private fun refreshActiveProfileSummary() {
        // FIX (multi-akun): sekarang baca akun AKTIF dari ProfileStore, bukan
        // lagi satu-satunya konfigurasi tersimpan -- lihat ProfileStore.kt.
        val saved = ProfileStore.getActive(requireContext())?.config
        if (saved == null) {
            binding.tvActiveProfile.text = "Profil aktif: belum ada konfigurasi"
            renderCatatan()
            return
        }
        // FITUR BARU (nama akun custom): kalau accountName diisi, tampilkan
        // itu sebagai identitas utama profil aktif -- lebih personal & mudah
        // dikenali dibanding host:port mentah, terutama kalau user punya
        // banyak akun. Kosong = fallback persis seperti sebelumnya.
        val label = accountLabel(saved)
        binding.tvActiveProfile.text = "Profil aktif: $label"
        refreshAccountPickerVisibility()
        // FITUR BARU (permintaan user, "catatan config hasil impor tampil
        // di menu Catatan"): profil aktif bisa saja baru saja berganti
        // (mis. lewat showAccountPicker, atau akun diedit di ConfigActivity
        // lalu kembali ke Dashboard) -- refresh kartu "Catatan" supaya
        // langsung menampilkan catatan akun yang SEKARANG aktif (atau log
        // koneksi kalau akun itu tidak punya catatan), tanpa perlu menunggu
        // StatusBus.steps berubah dulu.
        renderCatatan()
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
        // REFACTOR (opsi 1+2, toggle independen): label ringkas sekarang
        // dirakit dari 3 toggle (bisa kombinasi apa pun), bukan lagi 1
        // nama preset per modeIndex -- lihat SavedConfig.resolvedXxxEnabled().
        val parts = buildList {
            if (config.resolvedTlsEnabled()) add("TLS")
            if (config.resolvedPayloadEnabled()) add("Payload")
            if (config.resolvedProxyEnabled()) add("Proxy")
        }
        val modeName = if (parts.isEmpty()) "SSH" else "SSH (${parts.joinToString(" + ")})"
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
                    hideSensitiveLogs = saved.lockMode == ConfigLockMode.LOCK_ALL ||
                        saved.lockMode == ConfigLockMode.LOCK_PAYLOAD_PROXY,
                    profileId = activeProfile.id
                )
            )
            return
        }

        if (saved.host.isBlank() || saved.username.isBlank()) {
            StatusBus.state.value = "Host/username belum lengkap, buka menu SSH dulu"
            return
        }

        // REFACTOR (opsi 1+2, toggle independen): disalin dari
        // ServerConfig.toServerConfigOrNull supaya konsisten -- baca
        // langsung dari toggle tersimpan (dengan fallback modeIndex lama),
        // bukan pemetaan modeIndex->preset lagi.
        val usesTls = saved.resolvedTlsEnabled()
        val usesPayload = saved.resolvedPayloadEnabled()
        val usesProxy = saved.resolvedProxyEnabled()
        val proxyRawMode = usesProxy && saved.proxyRawMode

        if (usesProxy && proxyRawMode && saved.proxyHost.isBlank()) {
            StatusBus.state.value = "Raw Passthrough butuh host/IP proxy atau CDN diisi"
            return
        }
        if (usesProxy && saved.proxyHost.isBlank()) {
            StatusBus.state.value = "Toggle Remote Proxy aktif, host/IP proxy diisi dulu, buka menu SSH dulu"
            return
        }

        connectWith(
            PendingConnection(
                host = saved.host,
                port = saved.port,
                username = saved.username,
                password = saved.password,
                mode = ConnectionMode.SSH,
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
                tlsEnabled = usesTls,
                proxyEnabled = usesProxy,
                payloadEnabled = usesPayload,
                hideSensitiveLogs = saved.lockMode == ConfigLockMode.LOCK_ALL ||
                    saved.lockMode == ConfigLockMode.LOCK_PAYLOAD_PROXY,
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
            putExtra(MyVpnService.EXTRA_HIDE_SENSITIVE_LOGS, c.hideSensitiveLogs)
            // REFACTOR (opsi 1+2, toggle independen): diteruskan apa adanya --
            // MyVpnService.ACTION_CONNECT sekarang baca toggle ini langsung
            // dari Intent, bukan menebak dari EXTRA_MODE lagi.
            putExtra(MyVpnService.EXTRA_TLS_ENABLED, c.tlsEnabled)
            putExtra(MyVpnService.EXTRA_PROXY_ENABLED, c.proxyEnabled)
            putExtra(MyVpnService.EXTRA_PAYLOAD_ENABLED, c.payloadEnabled)
            if (!c.profileId.isNullOrEmpty()) putExtra(MyVpnService.EXTRA_PROFILE_ID, c.profileId)
        }
        ctx.startForegroundService(intent)
    }

}
