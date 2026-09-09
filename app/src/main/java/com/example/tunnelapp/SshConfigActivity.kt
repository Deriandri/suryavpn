package com.example.tunnelapp

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.tunnelapp.databinding.ActivitySshConfigBinding
import com.example.tunnelapp.model.ConfigStore
import com.example.tunnelapp.model.SavedConfig

/**
 * Layar konfigurasi khusus jalur SSH (SSH biasa, SSH SSL, SSH TLS Payload Proxy,
 * Payload + Remote Proxy).
 * Terpisah dari Xray ([XrayConfigActivity]) & dari Dashboard ([DashboardActivity])
 * yang sekarang jadi satu-satunya tempat tombol Connect/Disconnect berada.
 *
 * Menyimpan modeIndex 0/1/2/3 ke [ConfigStore] supaya Dashboard tahu profil SSH ini
 * yang aktif dipakai kalau Connect ditekan -- xrayLink milik profil Xray TETAP
 * dipertahankan (tidak ditimpa) supaya kedua profil bisa disimpan berdampingan.
 */
class SshConfigActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySshConfigBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySshConfigBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        restoreSavedConfig()
        setupModeChips()

        binding.btnSaveSsh.setOnClickListener { onSaveClicked() }
        binding.btnQuickPasteApply.setOnClickListener { onQuickPasteApplied() }
    }

    /**
     * Mem-parsing format tempel cepat "host:port@username:password" dan mengisi
     * field Host, Port, Username, Password. Port bersifat opsional (default 22).
     * Contoh valid:
     *   1.2.3.4:22@user:pass
     *   1.2.3.4@user:pass
     *   example.com:2222@user:p@ss:word   (password boleh mengandung ':' atau '@')
     */
    private fun onQuickPasteApplied() {
        val raw = binding.etQuickPaste.text.toString().trim()
        binding.tilQuickPaste.error = null

        if (raw.isEmpty()) {
            binding.tilQuickPaste.error = "Tempel dulu string konfigurasinya"
            return
        }

        val atIndex = raw.indexOf('@')
        if (atIndex <= 0 || atIndex == raw.length - 1) {
            binding.tilQuickPaste.error = "Format harus host:port@username:password"
            return
        }

        val hostPortPart = raw.substring(0, atIndex)
        val userPassPart = raw.substring(atIndex + 1)

        val hostPortSplit = hostPortPart.split(":", limit = 2)
        val host = hostPortSplit[0].trim()
        val portText = if (hostPortSplit.size > 1) hostPortSplit[1].trim() else "22"
        val port = portText.toIntOrNull()

        val userPassSplit = userPassPart.split(":", limit = 2)
        val username = userPassSplit.getOrNull(0)?.trim().orEmpty()
        val password = userPassSplit.getOrNull(1).orEmpty()

        if (host.isEmpty() || port == null || username.isEmpty()) {
            binding.tilQuickPaste.error = "Format harus host:port@username:password"
            return
        }

        binding.etHost.setText(host)
        binding.etPort.setText(port.toString())
        binding.etUsername.setText(username)
        binding.etPassword.setText(password)
    }

    private fun restoreSavedConfig() {
        val saved: SavedConfig = ConfigStore.load(this) ?: run {
            binding.etPort.setText("22")
            return
        }
        binding.etHost.setText(saved.host)
        binding.etPort.setText(if (saved.port > 0) saved.port.toString() else "22")
        binding.etUsername.setText(saved.username)
        binding.etPassword.setText(saved.password)
        binding.etSni.setText(saved.sni)
        binding.etPayload.setText(saved.payload)
        binding.etWsPath.setText(saved.wsPath)
        binding.etCustomHeaders.setText(saved.customHeaders)
        binding.chipIgnoreCertErrors.isChecked = saved.ignoreCertErrors
        binding.etDns1.setText(saved.dns1)
        binding.etDns2.setText(saved.dns2)
        binding.etProxyHost.setText(saved.proxyHost)
        binding.etProxyPort.setText(saved.proxyPort)
        val tlsChip = when (saved.tlsVersion) {
            "TLSv1" -> binding.chipTlsV1
            "TLSv1.1" -> binding.chipTlsV11
            "TLSv1.2" -> binding.chipTlsV12
            "TLSv1.3" -> binding.chipTlsV13
            else -> binding.chipTlsDefault
        }
        tlsChip.isChecked = true
        // modeIndex 5 (Xray) tidak relevan di sini -- kalau profil tersimpan
        // terakhir adalah Xray, layar ini tetap tampil dengan chip SSH biasa
        // sebagai default (belum pernah dikonfigurasi SSH-nya).
        val chip = when (saved.modeIndex) {
            1 -> binding.chipSshSsl
            2, 4 -> binding.chipSshEnhanced
            3 -> binding.chipPayloadRemoteProxy
            else -> binding.chipSsh
        }
        chip.isChecked = true
        binding.chipRawMode.isChecked = saved.proxyRawMode
    }

    private fun setupModeChips() {
        binding.chipGroupMode.setOnCheckedStateChangeListener { _, _ -> updateFieldVisibilityForMode() }
        binding.chipGroupEnhancedToggle.setOnCheckedStateChangeListener { _, _ -> updateFieldVisibilityForMode() }
        // Raw Passthrough dulu tidak punya listener sama sekali -- toggle-nya
        // KETAHUAN memengaruhi apakah Header HTTP tambahan kepakai (lihat
        // updateWsAndHeaderFieldState) tapi UI tidak pernah di-refresh saat
        // di-tap, jadi disambungkan di sini juga.
        binding.chipRawMode.setOnCheckedChangeListener { _, _ -> updateFieldVisibilityForMode() }
        // Payload custom diketik manual (bukan dipilih dari chip) -- field ini
        // yang menentukan apakah Path WebSocket & Header HTTP tambahan kepakai
        // atau tidak (lihat updateWsAndHeaderFieldState), jadi harus dipantau
        // tiap kali isinya berubah, bukan cuma sekali saat halaman dibuka.
        binding.etPayload.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) = updateFieldVisibilityForMode()
        })
        updateFieldVisibilityForMode()
    }

    /**
     * 0 = SSH, 1 = SSH SSL, 2 = SSH TLS Payload Proxy, 3 = Payload + Remote Proxy.
     *
     * Mode 3 (Payload + Remote Proxy) memakai [com.example.tunnelapp.model.ConnectionMode.REMOTE_PROXY]:
     * proxy WAJIB diisi (beda dari mode 2 yang proxy-nya opsional), payload custom
     * bisa diisi. TLS/SNI hanya relevan untuk mode payload+proxy biasa; begitu
     * toggle Raw Passthrough dinyalakan, koneksi jalan apa adanya tanpa
     * TLS/SNI sama sekali, jadi field itu disembunyikan.
     */
    private fun currentModeIndex(): Int = when {
        binding.chipPayloadRemoteProxy.isChecked -> 3
        binding.chipSshEnhanced.isChecked -> 2
        binding.chipSshSsl.isChecked -> 1
        else -> 0
    }

    private fun proxyRawModeEnabled(): Boolean =
        (currentModeIndex() == 2 || currentModeIndex() == 3) && binding.chipRawMode.isChecked

    /** Mode 3 pakai TLS/SNI kecuali Raw Passthrough dinyalakan -- lihat [currentModeIndex]. */
    private fun usesTlsForMode(modeIndex: Int): Boolean =
        modeIndex == 1 || modeIndex == 2 || (modeIndex == 3 && !proxyRawModeEnabled())

    private fun updateFieldVisibilityForMode() {
        val modeIndex = currentModeIndex()
        val usesTls = usesTlsForMode(modeIndex)
        val usesPayload = modeIndex == 2 || modeIndex == 3
        val usesProxy = modeIndex == 2 || modeIndex == 3
        val proxyMandatory = modeIndex == 3
        val usesRawMode = proxyRawModeEnabled()

        binding.tilSni.visibility = if (usesTls) android.view.View.VISIBLE else android.view.View.GONE
        binding.containerTlsVersion.visibility = if (usesTls) android.view.View.VISIBLE else android.view.View.GONE
        binding.chipIgnoreCertErrors.visibility = if (usesTls) android.view.View.VISIBLE else android.view.View.GONE
        binding.tilPayload.visibility = if (usesPayload) android.view.View.VISIBLE else android.view.View.GONE
        binding.containerProxy.visibility = if (usesProxy) android.view.View.VISIBLE else android.view.View.GONE
        binding.chipGroupEnhancedToggle.visibility = if (modeIndex == 2 || modeIndex == 3) android.view.View.VISIBLE else android.view.View.GONE

        binding.tilSni.hint = "SNI / Host WebSocket (kosongkan jika tidak perlu)"
        binding.tilProxyHost.hint = when {
            usesRawMode -> "Host / IP Proxy atau CDN (wajib)"
            proxyMandatory -> "Host / IP Proxy (wajib)"
            else -> "Host / IP Proxy (opsional)"
        }

        updateWsAndHeaderFieldState(usesPayload, usesProxy, usesRawMode)
    }

    /**
     * Path WebSocket & Header HTTP tambahan TIDAK SELALU dipakai -- keduanya
     * cuma "aktif" kalau kondisi tertentu terpenuhi (lihat ServerConfig.kt &
     * ConnectRelay.kt):
     *
     *  - Path WebSocket: hanya dipakai kalau Payload custom KOSONG
     *    ([com.example.tunnelapp.model.ServerConfig.attemptsFormalWebSocket] --
     *    handshake WebSocket "resmi" & payload custom manual saling
     *    eksklusif; begitu Payload custom diisi, path ini diabaikan total).
     *  - Header HTTP tambahan: dipakai di DUA tempat independen --
     *    request CONNECT ke proxy ([ConnectRelay.sendProxyConnect], hanya
     *    kalau proxy aktif TANPA Raw Passthrough) DAN/ATAU handshake
     *    WebSocket resmi di atas (payload kosong). Salah satu saja
     *    terpenuhi, header custom tetap kepakai.
     *
     * Field yang sedang TIDAK dipakai (menurut kombinasi mode + Raw
     * Passthrough + isi Payload saat ini) di-nonaktifkan (redup, tidak bisa
     * diketik) supaya kelihatan jelas -- bukan diam-diam diabaikan seperti
     * sebelumnya.
     */
    private fun updateWsAndHeaderFieldState(usesPayload: Boolean, usesProxy: Boolean, usesRawMode: Boolean) {
        val payloadFilled = usesPayload && binding.etPayload.text?.isNotBlank() == true

        val wsPathActive = !payloadFilled
        setFieldActive(
            binding.tilWsPath, binding.etWsPath, wsPathActive,
            activeHint = "Path WebSocket, contoh: /ssh-ws (kosongkan untuk \"/\")",
            inactiveHint = "Tidak dipakai -- Payload custom sudah diisi (WebSocket resmi dilewati)"
        )

        val connectUsesHeaders = usesProxy && !usesRawMode
        val headersActive = connectUsesHeaders || !payloadFilled
        setFieldActive(
            binding.tilCustomHeaders, binding.etCustomHeaders, headersActive,
            activeHint = "Header HTTP tambahan (opsional), 1 per baris, contoh: X-Online-Host: [host]",
            inactiveHint = "Tidak dipakai -- Raw Passthrough aktif & Payload custom sudah diisi"
        )
    }

    private fun setFieldActive(
        til: com.google.android.material.textfield.TextInputLayout,
        et: com.google.android.material.textfield.TextInputEditText,
        active: Boolean,
        activeHint: String,
        inactiveHint: String
    ) {
        et.isEnabled = active
        til.isEnabled = active
        til.hint = if (active) activeHint else inactiveHint
        til.alpha = if (active) 1.0f else 0.5f
    }

    private fun selectedTlsVersion(): String? = when {
        binding.chipTlsV1.isChecked -> "TLSv1"
        binding.chipTlsV11.isChecked -> "TLSv1.1"
        binding.chipTlsV12.isChecked -> "TLSv1.2"
        binding.chipTlsV13.isChecked -> "TLSv1.3"
        else -> null
    }

    private fun onSaveClicked() {
        val modeIndex = currentModeIndex()

        val host = binding.etHost.text.toString().trim()
        val port = binding.etPort.text.toString().trim().toIntOrNull() ?: 22
        val username = binding.etUsername.text.toString().trim()
        val password = binding.etPassword.text.toString()
        val sni = binding.etSni.text.toString().trim()
        val wsPath = binding.etWsPath.text.toString().trim()
        val customHeaders = binding.etCustomHeaders.text.toString().trim()
        val dns1 = binding.etDns1.text.toString().trim()
        val dns2 = binding.etDns2.text.toString().trim()

        if (host.isEmpty() || username.isEmpty()) {
            binding.etHost.error = if (host.isEmpty()) "Wajib diisi" else null
            binding.etUsername.error = if (username.isEmpty()) "Wajib diisi" else null
            return
        }

        // Validasi ringan format IP di sini (bukan hostname/domain -- DNS di
        // VpnService.Builder WAJIB literal IP, lihat MyVpnService.applyDnsServers)
        // supaya salah ketik ketahuan langsung saat Simpan, bukan baru gagal diam-diam
        // (fallback ke default) saat Connect nanti.
        if (dns1.isNotEmpty() && !android.util.Patterns.IP_ADDRESS.matcher(dns1).matches()) {
            binding.etDns1.error = "Harus alamat IP (mis. 1.1.1.1), bukan domain"
            return
        }
        if (dns2.isNotEmpty() && !android.util.Patterns.IP_ADDRESS.matcher(dns2).matches()) {
            binding.etDns2.error = "Harus alamat IP (mis. 1.0.0.1), bukan domain"
            return
        }

        val usesPayload = modeIndex == 2 || modeIndex == 3
        val payload = if (usesPayload) binding.etPayload.text.toString() else ""

        val usesProxy = modeIndex == 2 || modeIndex == 3
        val proxyHost = if (usesProxy) binding.etProxyHost.text.toString().trim() else ""
        val proxyPortText = if (usesProxy) binding.etProxyPort.text.toString().trim() else ""
        val proxyRawMode = usesProxy && proxyRawModeEnabled()

        if (proxyRawMode && proxyHost.isEmpty()) {
            binding.etProxyHost.error = "Raw Passthrough butuh host/IP proxy atau CDN diisi"
            return
        }
        // Mode 3 (Payload + Remote Proxy) mewajibkan proxy diisi walau raw mode
        // tidak aktif -- beda dari mode 2 yang proxy-nya opsional.
        if (modeIndex == 3 && proxyHost.isEmpty()) {
            binding.etProxyHost.error = "Payload + Remote Proxy butuh host/IP proxy diisi"
            return
        }

        val usesTls = usesTlsForMode(modeIndex)
        val tlsVersion = if (usesTls) selectedTlsVersion() else null
        val ignoreCertErrors = usesTls && binding.chipIgnoreCertErrors.isChecked

        // Pertahankan profil Xray yang mungkin sudah tersimpan sebelumnya --
        // menyimpan dari layar SSH ini TIDAK boleh menghapus link Xray yang ada.
        val previousXrayLink = ConfigStore.load(this)?.xrayLink.orEmpty()

        ConfigStore.save(
            this,
            SavedConfig(
                host = host,
                port = port,
                username = username,
                password = password,
                modeIndex = modeIndex,
                sni = sni,
                payload = payload,
                proxyHost = proxyHost,
                proxyPort = proxyPortText,
                tlsVersion = tlsVersion.orEmpty(),
                useWebSocket = true,
                wsPath = wsPath,
                proxyRawMode = proxyRawMode,
                xrayLink = previousXrayLink,
                customHeaders = customHeaders,
                ignoreCertErrors = ignoreCertErrors,
                dns1 = dns1,
                dns2 = dns2
            )
        )

        finish()
    }
}
