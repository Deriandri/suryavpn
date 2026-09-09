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
        binding.tilPayload.visibility = if (usesPayload) android.view.View.VISIBLE else android.view.View.GONE
        binding.containerProxy.visibility = if (usesProxy) android.view.View.VISIBLE else android.view.View.GONE
        binding.chipGroupEnhancedToggle.visibility = if (modeIndex == 2 || modeIndex == 3) android.view.View.VISIBLE else android.view.View.GONE

        binding.tilSni.hint = "SNI / Host WebSocket (kosongkan jika tidak perlu)"
        binding.tilProxyHost.hint = when {
            usesRawMode -> "Host / IP Proxy atau CDN (wajib)"
            proxyMandatory -> "Host / IP Proxy (wajib)"
            else -> "Host / IP Proxy (opsional)"
        }
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

        if (host.isEmpty() || username.isEmpty()) {
            binding.etHost.error = if (host.isEmpty()) "Wajib diisi" else null
            binding.etUsername.error = if (username.isEmpty()) "Wajib diisi" else null
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
                xrayLink = previousXrayLink
            )
        )

        finish()
    }
}
