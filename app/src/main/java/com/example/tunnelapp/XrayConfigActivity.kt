package com.example.tunnelapp

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doOnTextChanged
import com.example.tunnelapp.databinding.ActivityXrayConfigBinding
import com.example.tunnelapp.model.ProfileStore
import com.example.tunnelapp.model.SavedConfig
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
            realityShortId = binding.etXrayRealitySid.text.toString().trim()
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
                accountName = accountName
            )
        )

        finish()
    }
}
