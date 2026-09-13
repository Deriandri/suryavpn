package com.example.tunnelapp

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doOnTextChanged
import com.example.tunnelapp.databinding.ActivityXrayConfigBinding
import com.example.tunnelapp.model.ProfileStore
import com.example.tunnelapp.model.SavedConfig
import com.example.tunnelapp.model.ShareLockMode
import com.example.tunnelapp.model.label
import com.example.tunnelapp.tunnel.XrayLinkBuilder
import com.example.tunnelapp.tunnel.XrayLinkParser
import com.example.tunnelapp.tunnel.XrayOutboundConfig
import com.example.tunnelapp.tunnel.XrayProtocol

/**
 * Layar konfigurasi khusus Xray (VMess/VLESS/Trojan), terpisah dari jalur SSH
 * ([SshConfigActivity]) & dari Dashboard ([DashboardActivity]).
 *
 * Begitu link ditempel/diketik, link otomatis diurai (dengan jeda singkat supaya
 * tidak mengurai di tengah-tengah ketikan) dan field detail akun langsung tampil
 * terisi -- tombol "Urai & Edit Detail Akun" tetap ada untuk mengurai ulang secara
 * manual kapan saja (mis. setelah mengedit link mentah lagi) dan untuk
 * menampilkan pesan error kalau link-nya tidak valid.
 *
 * Field-field itu bisa diedit satu-satu. Saat Simpan ditekan, kalau detail akun
 * ini pernah diurai, link disusun ulang otomatis dari field yang sudah diedit
 * lewat [XrayLinkBuilder] -- user tidak perlu menyusun ulang seluruh link secara
 * manual.
 *
 * FITUR MULTI-AKUN (permintaan user): sama seperti [SshConfigActivity], layar
 * ini beroperasi lewat [ProfileStore] -- tanpa extra [EXTRA_PROFILE_ID] berarti
 * menambah profil Xray baru (tidak menyentuh akun lain apa pun), dengan extra
 * itu berarti mengedit profil Xray yang sudah ada (menimpa profil yang sama).
 */
class XrayConfigActivity : AppCompatActivity() {

    companion object {
        /** Extra Intent opsional: id [com.example.tunnelapp.model.SavedProfile]
         *  yang sedang di-edit. Kosong/tidak ada = mode tambah akun baru. */
        const val EXTRA_PROFILE_ID = "profile_id"
    }

    private lateinit var binding: ActivityXrayConfigBinding

    /** null = mode tambah akun baru. Terisi = mode edit, menimpa profil ini. */
    private var editingProfileId: String? = null

    /** Hasil urai terakhir, dipakai sebagai basis .copy() supaya field lanjutan
     *  yang tidak ada di form (headerType, seed, xhttpMode, dst) tidak hilang. */
    private var lastParsedConfig: XrayOutboundConfig? = null

    /** Jeda debounce sebelum auto-urai jalan, supaya tidak mengurai tiap 1 huruf diketik. */
    private val autoParseRunnable = Runnable { autoParseSilently() }
    private val autoParseDelayMs = 500L

    /**
     * FITUR BARU (permintaan user, "kunci saat mau menyimpan konfig"): sama
     * konsepnya seperti di SshConfigActivity, tapi untuk Xray SELURUH info
     * server (host, port, id/password, path, TLS, dst) sudah menyatu di
     * DALAM satu [com.example.tunnelapp.model.SavedConfig.xrayLink] -- tidak
     * ada field host/user/password terpisah seperti di jalur SSH. Jadi di
     * sini kuncinya bersifat SATU KESATUAN: begitu mode dipilih selain
     * [ShareLockMode.NONE], seluruh link dikunci (lihat applyLockUiState) --
     * bedanya cuma di kode bagikan (lihat ConfigIO.kt), field "xrayLink"
     * tetap masuk grup "payload" yang dienkripsi kalau grup itu ikut
     * terkunci di mode yang dipilih.
     */
    private var currentLockMode: ShareLockMode = ShareLockMode.NONE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityXrayConfigBinding.inflate(layoutInflater)
        setContentView(binding.root)

        editingProfileId = intent.getStringExtra(EXTRA_PROFILE_ID)

        binding.btnBack.setOnClickListener { finish() }

        editingProfileId?.let { ProfileStore.get(this, it)?.config }?.let { saved ->
            binding.etAccountName.setText(saved.accountName)
            binding.etXrayLink.setText(saved.xrayLink)
            // Kalau sudah ada akun tersimpan sebelumnya, langsung urai saat layar
            // dibuka juga -- tidak perlu tunggu tempel/ketik baru dulu.
            autoParseSilently()

            currentLockMode = saved.lockMode
            applyLockUiState(currentLockMode)
        }

        setupDetailFieldToggles()

        // Auto-urai begitu isi link berubah (tempel atau diketik manual), dengan
        // jeda singkat supaya tidak mengurai di tengah-tengah ketikan/paste.
        binding.etXrayLink.doOnTextChanged { _, _, _, _ ->
            binding.etXrayLink.removeCallbacks(autoParseRunnable)
            binding.etXrayLink.postDelayed(autoParseRunnable, autoParseDelayMs)
        }

        binding.btnParseXray.setOnClickListener { onParseClicked() }
        binding.btnSaveXray.setOnClickListener { onSaveClicked() }
    }

    override fun onDestroy() {
        binding.etXrayLink.removeCallbacks(autoParseRunnable)
        super.onDestroy()
    }

    /** Urai otomatis di belakang layar -- diam-diam kalau gagal (user mungkin masih
     *  mengetik/link memang belum lengkap), tidak mengganggu dengan pesan error.
     *  Untuk error yang eksplisit, pakai tombol "Urai & Edit Detail Akun". */
    private fun autoParseSilently() {
        val raw = binding.etXrayLink.text.toString().trim()
        if (raw.isEmpty()) return
        val parsed = runCatching { XrayLinkParser.parse(raw) }.getOrNull() ?: return
        binding.etXrayLink.error = null
        binding.tvXrayParseError.visibility = View.GONE
        lastParsedConfig = parsed
        populateDetailFields(parsed)
        binding.cardXrayDetails.visibility = View.VISIBLE
    }

    /**
     * Kalau [mode] bukan [ShareLockMode.NONE], link Xray dikunci total:
     * field link & tombol "Urai" dinonaktifkan, dan kartu detail akun yang
     * sudah terurai disembunyikan lagi (supaya Simpan tidak diam-diam
     * menyusun ulang link dari field detail yang sebenarnya masih bisa
     * disentuh -- lihat cabang di onSaveClicked yang otomatis jatuh balik
     * ke etXrayLink apa adanya begitu cardXrayDetails tidak VISIBLE).
     */
    private fun applyLockUiState(mode: ShareLockMode) {
        val locked = mode != ShareLockMode.NONE
        binding.etXrayLink.isEnabled = !locked
        binding.tilXrayLink.alpha = if (locked) 0.5f else 1f
        binding.btnParseXray.isEnabled = !locked
        if (locked) {
            binding.cardXrayDetails.visibility = View.GONE
        }
    }

    /** Sama seperti [SshConfigActivity.showLockModeDialog] -- lihat dokumentasinya. */
    private fun showLockModeDialog(current: ShareLockMode, onPicked: (ShareLockMode) -> Unit) {
        val modes = ShareLockMode.values()
        val labels = modes.map { it.label }.toTypedArray()
        var selected = modes.indexOf(current).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle("Kunci konfigurasi ini?")
            .setSingleChoiceItems(labels, selected) { _, which -> selected = which }
            .setNegativeButton("Batal", null)
            .setPositiveButton("Simpan") { _, _ -> onPicked(modes[selected]) }
            .show()
    }

    private fun setupDetailFieldToggles() {
        binding.chipGroupXrayNetwork.setOnCheckedStateChangeListener { _, _ -> updateDetailFieldVisibility() }
        binding.chipGroupXrayTls.setOnCheckedStateChangeListener { _, _ -> updateDetailFieldVisibility() }
    }

    private fun selectedNetwork(): String = when {
        binding.chipNetWs.isChecked -> "ws"
        binding.chipNetGrpc.isChecked -> "grpc"
        binding.chipNetHttpupgrade.isChecked -> "httpupgrade"
        binding.chipNetXhttp.isChecked -> "xhttp"
        binding.chipNetH2.isChecked -> "h2"
        binding.chipNetKcp.isChecked -> "kcp"
        else -> "tcp"
    }

    private fun selectedTlsMode(): String = when {
        binding.chipXrayTlsTls.isChecked -> "tls"
        binding.chipXrayTlsReality.isChecked -> "reality"
        else -> "none"
    }

    /** Chip fingerprint yang dipilih -> nilai string mentah yang dikirim ke Xray-core
     *  (persis nama yang dipakai spesifikasi uTLS: chrome/firefox/safari/ios/android/edge/random). */
    private fun selectedFingerprint(): String = when {
        binding.chipFpFirefox.isChecked -> "firefox"
        binding.chipFpSafari.isChecked -> "safari"
        binding.chipFpIos.isChecked -> "ios"
        binding.chipFpAndroid.isChecked -> "android"
        binding.chipFpEdge.isChecked -> "edge"
        binding.chipFpRandom.isChecked -> "random"
        else -> "chrome"
    }

    private fun updateDetailFieldVisibility() {
        val network = selectedNetwork()
        val tlsMode = selectedTlsMode()
        val showPath = network in setOf("ws", "grpc", "h2", "httpupgrade", "xhttp")
        val showHostHeader = network in setOf("ws", "h2", "httpupgrade", "xhttp")
        val showTlsFields = tlsMode != "none"
        val showReality = tlsMode == "reality"
        val isVless = lastParsedConfig?.protocol == XrayProtocol.VLESS

        binding.tilXrayPath.visibility = if (showPath) View.VISIBLE else View.GONE
        binding.tilXrayPath.hint = if (network == "grpc") "Nama service (gRPC)" else "Path"
        binding.tilXrayHostHeader.visibility = if (showHostHeader) View.VISIBLE else View.GONE
        binding.tilXraySni.visibility = if (showTlsFields) View.VISIBLE else View.GONE
        binding.tvXrayFingerprintLabel.visibility = if (showTlsFields) View.VISIBLE else View.GONE
        binding.chipGroupXrayFingerprint.visibility = if (showTlsFields) View.VISIBLE else View.GONE
        binding.tilXrayFlow.visibility = if (isVless) View.VISIBLE else View.GONE
        binding.containerXrayReality.visibility = if (showReality) View.VISIBLE else View.GONE
        binding.chipGroupXrayInsecure.visibility = if (showTlsFields) View.VISIBLE else View.GONE
    }

    private fun onParseClicked() {
        binding.etXrayLink.removeCallbacks(autoParseRunnable)
        binding.tvXrayParseError.visibility = View.GONE
        val raw = binding.etXrayLink.text.toString().trim()
        if (raw.isEmpty()) {
            binding.etXrayLink.error = "Tempel link Xray dulu"
            return
        }
        val parsed = try {
            XrayLinkParser.parse(raw)
        } catch (e: Exception) {
            binding.tvXrayParseError.text = e.message ?: "Link Xray tidak valid"
            binding.tvXrayParseError.visibility = View.VISIBLE
            binding.cardXrayDetails.visibility = View.GONE
            lastParsedConfig = null
            return
        }
        binding.etXrayLink.error = null
        lastParsedConfig = parsed
        populateDetailFields(parsed)
        binding.cardXrayDetails.visibility = View.VISIBLE
    }

    private fun populateDetailFields(cfg: XrayOutboundConfig) {
        binding.etXrayRemark.setText(cfg.remark)
        binding.etXrayHost.setText(cfg.address)
        binding.etXrayPort.setText(cfg.port.toString())
        binding.etXrayId.setText(cfg.idOrPassword)

        val networkChip = when (cfg.network) {
            "ws" -> binding.chipNetWs
            "grpc" -> binding.chipNetGrpc
            "httpupgrade" -> binding.chipNetHttpupgrade
            "xhttp" -> binding.chipNetXhttp
            "h2" -> binding.chipNetH2
            "kcp" -> binding.chipNetKcp
            else -> binding.chipNetTcp
        }
        networkChip.isChecked = true

        binding.etXrayPath.setText(if (cfg.network == "grpc") cfg.serviceName else cfg.path)
        binding.etXrayHostHeader.setText(cfg.hostHeader)

        val tlsChip = when (cfg.tlsMode) {
            "tls" -> binding.chipXrayTlsTls
            "reality" -> binding.chipXrayTlsReality
            else -> binding.chipXrayTlsNone
        }
        tlsChip.isChecked = true

        binding.etXraySni.setText(cfg.sni)
        binding.etXrayFlow.setText(cfg.flow)
        binding.etXrayRealityPbk.setText(cfg.realityPublicKey)
        binding.etXrayRealitySid.setText(cfg.realityShortId)
        binding.chipXrayAllowInsecure.isChecked = cfg.allowInsecure

        val fingerprintChip = when (cfg.realityFingerprint.lowercase()) {
            "firefox" -> binding.chipFpFirefox
            "safari" -> binding.chipFpSafari
            "ios" -> binding.chipFpIos
            "android" -> binding.chipFpAndroid
            "edge" -> binding.chipFpEdge
            "random", "randomized" -> binding.chipFpRandom
            else -> binding.chipFpChrome // termasuk "chrome" & nilai tak dikenal -- default aman
        }
        fingerprintChip.isChecked = true

        updateDetailFieldVisibility()
    }

    /** Susun [XrayOutboundConfig] baru dari isi form edit, di atas basis [lastParsedConfig]. */
    private fun buildConfigFromDetailFields(base: XrayOutboundConfig): XrayOutboundConfig? {
        val remark = binding.etXrayRemark.text.toString().trim()
        val host = binding.etXrayHost.text.toString().trim()
        val port = binding.etXrayPort.text.toString().trim().toIntOrNull()
        val id = binding.etXrayId.text.toString().trim()

        if (host.isEmpty()) {
            binding.etXrayHost.error = "Wajib diisi"
            return null
        }
        if (port == null) {
            binding.etXrayPort.error = "Port tidak valid"
            return null
        }
        if (id.isEmpty()) {
            binding.etXrayId.error = "Wajib diisi"
            return null
        }

        val network = selectedNetwork()
        val tlsMode = selectedTlsMode()
        val pathOrService = binding.etXrayPath.text.toString().trim().ifEmpty { "/" }

        return base.copy(
            remark = remark.ifEmpty { base.remark },
            address = host,
            port = port,
            idOrPassword = id,
            network = network,
            path = if (network == "grpc") "/" else pathOrService,
            serviceName = if (network == "grpc") pathOrService else "",
            hostHeader = binding.etXrayHostHeader.text.toString().trim(),
            tlsMode = tlsMode,
            sni = binding.etXraySni.text.toString().trim(),
            flow = binding.etXrayFlow.text.toString().trim(),
            allowInsecure = binding.chipXrayAllowInsecure.isChecked,
            realityPublicKey = binding.etXrayRealityPbk.text.toString().trim(),
            realityShortId = binding.etXrayRealitySid.text.toString().trim(),
            realityFingerprint = selectedFingerprint()
        )
    }

    private fun onSaveClicked() {
        val accountName = binding.etAccountName.text.toString().trim()
        val xrayLink: String

        if (binding.cardXrayDetails.visibility == View.VISIBLE && lastParsedConfig != null) {
            val edited = buildConfigFromDetailFields(lastParsedConfig!!) ?: return
            xrayLink = XrayLinkBuilder.build(edited)
            // Pastikan hasil susun ulang tetap bisa diurai balik sebelum disimpan.
            val reparseError = runCatching { XrayLinkParser.parse(xrayLink) }.exceptionOrNull()
            if (reparseError != null) {
                binding.tvXrayParseError.text = "Gagal menyusun ulang link: ${reparseError.message}"
                binding.tvXrayParseError.visibility = View.VISIBLE
                return
            }
        } else {
            val raw = binding.etXrayLink.text.toString().trim()
            if (raw.isEmpty()) {
                binding.etXrayLink.error = "Link Xray wajib diisi"
                return
            }
            val parseError = runCatching { XrayLinkParser.parse(raw) }.exceptionOrNull()
            if (parseError != null) {
                binding.etXrayLink.error = parseError.message ?: "Link Xray tidak valid"
                return
            }
            xrayLink = raw
        }

        // FITUR BARU (permintaan user, "kunci saat mau menyimpan konfig"):
        // sama seperti SshConfigActivity -- dialog pilihan mode kunci
        // ditampilkan dulu, penyimpanan sesungguhnya baru jalan di callback.
        showLockModeDialog(currentLockMode) { chosenLockMode ->
            // FIX (multi-akun): dulu di sini ada logika "pertahankan field SSH
            // profil lain sebelum menyimpan" karena SSH & Xray berbagi SATU slot
            // penyimpanan. Sekarang profil Xray ini berdiri sendiri di
            // [ProfileStore] -- tidak perlu lagi membaca/mempertahankan field
            // profil lain sama sekali.
            editingProfileId = ProfileStore.upsert(
                this,
                editingProfileId,
                SavedConfig(
                    host = "",
                    port = 22,
                    username = "",
                    password = "",
                    modeIndex = 5,
                    sni = "",
                    payload = "",
                    proxyHost = "",
                    proxyPort = "",
                    tlsVersion = "",
                    useWebSocket = false,
                    wsPath = "",
                    proxyRawMode = false,
                    xrayLink = xrayLink,
                    accountName = accountName,
                    lockMode = chosenLockMode
                )
            )

            finish()
        }
    }
}
