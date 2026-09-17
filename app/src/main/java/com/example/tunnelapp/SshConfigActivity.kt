package com.example.tunnelapp

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.tunnelapp.databinding.ActivitySshConfigBinding
import com.example.tunnelapp.model.ConfigLockMode
import com.example.tunnelapp.model.ProfileStore
import com.example.tunnelapp.model.SavedConfig

/**
 * Layar konfigurasi khusus jalur SSH (SSH biasa, SSH SSL, SSH TLS Payload Proxy,
 * Payload + Remote Proxy).
 * Terpisah dari Xray ([XrayConfigActivity]) & dari Dashboard ([DashboardActivity])
 * yang sekarang jadi satu-satunya tempat tombol Connect/Disconnect berada.
 *
 * FITUR MULTI-AKUN (permintaan user): layar ini sekarang beroperasi lewat
 * [ProfileStore], bukan lagi [com.example.tunnelapp.model.ConfigStore] single-
 * slot. Dua mode:
 *   - TAMBAH akun baru: dibuka tanpa extra [EXTRA_PROFILE_ID] (mis. dari
 *     shortcut "Konfigurasi SSH" di kartu "Konfigurasi Server") -- form
 *     kosong, Simpan akan membuat profil SSH baru berdiri sendiri, TIDAK
 *     menyentuh akun SSH/Xray lain yang sudah ada.
 *   - EDIT akun yang sudah ada: dibuka DENGAN extra [EXTRA_PROFILE_ID] (dari
 *     tombol Edit di kartu "Akun Tersimpan") -- form diisi dari profil itu,
 *     Simpan menimpa profil yang SAMA (id tidak berubah), bukan membuat baru.
 */
class SshConfigActivity : AppCompatActivity() {

    companion object {
        /** Extra Intent opsional: id [com.example.tunnelapp.model.SavedProfile]
         *  yang sedang di-edit. Kosong/tidak ada = mode tambah akun baru. */
        const val EXTRA_PROFILE_ID = "profile_id"
    }

    private lateinit var binding: ActivitySshConfigBinding

    /** null = mode tambah akun baru. Terisi = mode edit, menimpa profil ini. */
    private var editingProfileId: String? = null

    /**
     * FITUR BARU (permintaan user, "payload & remote proxy tetap harus
     * terkunci, akun server tetap bisa diedit"): config ASLI dari profil
     * yang sedang diedit (null kalau mode tambah akun baru). Dipakai untuk
     * dua hal saat Simpan (lihat [onSaveClicked]):
     *  1. Mempertahankan [SavedConfig.lockMode] & [SavedConfig.isLocked]
     *     apa adanya -- SEBELUM fitur ini, kedua field itu selalu ke-reset
     *     diam-diam jadi NONE/false tiap kali profil manapun disimpan
     *     ulang lewat layar ini, karena [onSaveClicked] membangun
     *     [SavedConfig] baru dari nol tanpa pernah membawa nilai lama itu.
     *  2. Kalau lockMode-nya [ConfigLockMode.LOCK_PAYLOAD_PROXY], nilai
     *     ASLI payload/proxyHost/proxyPort/proxyRawMode diambil dari sini
     *     (bukan dari form) karena field-field itu dinonaktifkan &
     *     disamarkan di layar (lihat [applyPayloadProxyLockIfNeeded]) --
     *     supaya nilai aslinya TIDAK ikut hilang/terhapus cuma karena user
     *     menyimpan ulang perubahan lain (mis. ganti nama akun).
     */
    private var originalConfig: SavedConfig? = null

    /**
     * FITUR BARU (permintaan user): true kalau chip "Raw Passthrough" sudah
     * punya nilai eksplisit -- baik karena user pernah men-tap chip-nya
     * sendiri di sesi ini, ATAU karena sedang EDIT profil lama yang memang
     * sudah tersimpan nilainya (lihat restoreSavedConfig). Selama masih
     * false, applyDefaultRawModeForEnhancedIfNeeded() boleh menyalakan
     * Raw Passthrough otomatis begitu mode "SSH TLS Payload Proxy" dipilih;
     * begitu true, auto-default itu berhenti menimpa pilihan yang sudah ada.
     */
    private var rawModeHasExplicitValue = false

    /** Dipakai applyDefaultRawModeForEnhancedIfNeeded() supaya perubahan
     *  chipRawMode.isChecked yang dilakukan SENDIRI oleh kode (bukan tap
     *  user) tidak ikut ditandai sebagai "sudah eksplisit" oleh listener
     *  chipRawMode di setupModeChips(). */
    private var settingRawModeProgrammatically = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySshConfigBinding.inflate(layoutInflater)
        setContentView(binding.root)

        editingProfileId = intent.getStringExtra(EXTRA_PROFILE_ID)

        binding.btnBack.setOnClickListener { finish() }

        restoreSavedConfig()
        setupModeChips()
        setupAdvancedFieldsToggle()

        binding.btnSaveSsh.setOnClickListener { onSaveClicked() }

        // FITUR BARU (permintaan user): field Host/Port/Username/Password
        // digabung jadi satu kolom "host:port@username:password" (etQuickPaste,
        // sekarang jadi field utama, bukan cuma "tempel cepat" lagi). Tombol
        // "Terapkan" DIHAPUS -- parsing sekarang berjalan otomatis tiap kali
        // isinya berubah lewat TextWatcher ini, langsung mengisi field asli
        // etHost/etPort/etUsername/etPassword yang sekarang disembunyikan
        // (lihat applyServerComboText & activity_ssh_config.xml).
        binding.etQuickPaste.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                applyServerComboText(s?.toString().orEmpty(), showErrors = false)
            }
        })
    }

    /**
     * FITUR BARU (permintaan user): "Opsi Lanjutan" -- Path WebSocket & Header
     * HTTP tambahan disembunyikan (llAdvancedFields, GONE) secara default
     * supaya form tidak penuh field yang jarang dipakai, dibuka/ditutup lewat
     * rowToggleAdvanced. Auto-dibuka sekali di sini kalau salah satu field
     * SUDAH terisi (mis. lagi EDIT akun lama yang memang pakai path/header
     * custom) supaya isinya tidak "hilang" dari pandangan user.
     */
    private fun setupAdvancedFieldsToggle() {
        val alreadyFilled = binding.etWsPath.text?.isNotBlank() == true ||
            binding.etCustomHeaders.text?.isNotBlank() == true
        setAdvancedFieldsExpanded(alreadyFilled)

        binding.rowToggleAdvanced.setOnClickListener {
            val currentlyExpanded = binding.llAdvancedFields.visibility == android.view.View.VISIBLE
            setAdvancedFieldsExpanded(!currentlyExpanded)
        }
    }

    private fun setAdvancedFieldsExpanded(expanded: Boolean) {
        binding.llAdvancedFields.visibility = if (expanded) android.view.View.VISIBLE else android.view.View.GONE
        binding.ivToggleAdvancedChevron.animate().rotation(if (expanded) 90f else 0f).setDuration(150).start()
        binding.tvToggleAdvancedLabel.text = if (expanded) {
            "Sembunyikan Opsi Lanjutan"
        } else {
            "Opsi Lanjutan (Path WebSocket, Header HTTP)"
        }
    }

    /**
     * Mem-parsing kolom gabungan "host:port@username:password" dan mengisi
     * field asli Host, Port, Username, Password (sekarang disembunyikan di
     * layout, lihat activity_ssh_config.xml). Port bersifat opsional
     * (default 22). Contoh valid:
     *   1.2.3.4:22@user:pass
     *   1.2.3.4@user:pass
     *   example.com:2222@user:p@ss:word   (password boleh mengandung ':' atau '@')
     *
     * showErrors=false dipakai oleh TextWatcher (silent, dipanggil tiap
     * ketikan berubah -- tidak menampilkan error selagi user masih mengetik).
     * showErrors=true dipakai saat tombol Simpan ditekan, supaya error format
     * baru ditampilkan kalau memang mau disimpan.
     */
    private fun applyServerComboText(raw: String, showErrors: Boolean): Boolean {
        val trimmed = raw.trim()
        if (!showErrors) {
            binding.tilQuickPaste.error = null
        }

        if (trimmed.isEmpty()) {
            if (showErrors) binding.tilQuickPaste.error = "Wajib diisi: host:port@username:password"
            return false
        }

        val atIndex = trimmed.indexOf('@')
        if (atIndex <= 0 || atIndex == trimmed.length - 1) {
            if (showErrors) binding.tilQuickPaste.error = "Format harus host:port@username:password"
            return false
        }

        val hostPortPart = trimmed.substring(0, atIndex)
        val userPassPart = trimmed.substring(atIndex + 1)

        val hostPortSplit = hostPortPart.split(":", limit = 2)
        val host = hostPortSplit[0].trim()
        val portText = if (hostPortSplit.size > 1) hostPortSplit[1].trim() else "22"
        val port = portText.toIntOrNull()

        val userPassSplit = userPassPart.split(":", limit = 2)
        val username = userPassSplit.getOrNull(0)?.trim().orEmpty()
        val password = userPassSplit.getOrNull(1).orEmpty()

        if (host.isEmpty() || port == null || username.isEmpty()) {
            if (showErrors) binding.tilQuickPaste.error = "Format harus host:port@username:password"
            return false
        }

        if (showErrors) binding.tilQuickPaste.error = null
        binding.etHost.setText(host)
        binding.etPort.setText(port.toString())
        binding.etUsername.setText(username)
        binding.etPassword.setText(password)
        return true
    }

    /**
     * Menyusun kembali kolom gabungan "host:port@username:password" dari
     * nilai host/port/username/password tersimpan, buat ditampilkan lagi
     * saat EDIT profil lama (lihat restoreSavedConfig).
     */
    private fun buildServerComboText(host: String, port: Int, username: String, password: String): String {
        if (host.isEmpty() && username.isEmpty() && password.isEmpty()) return ""
        return "$host:${if (port > 0) port else 22}@$username:$password"
    }

    private fun restoreSavedConfig() {
        val id = editingProfileId
        val saved: SavedConfig = (if (id != null) ProfileStore.get(this, id)?.config else null) ?: run {
            binding.etPort.setText("22")
            return
        }
        originalConfig = saved
        binding.etAccountName.setText(saved.accountName)
        binding.etHost.setText(saved.host)
        binding.etPort.setText(if (saved.port > 0) saved.port.toString() else "22")
        binding.etUsername.setText(saved.username)
        binding.etPassword.setText(saved.password)
        binding.etQuickPaste.setText(buildServerComboText(saved.host, saved.port, saved.username, saved.password))
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
        // FITUR BARU (permintaan user): modeIndex 4 (ENHANCED) tidak lagi punya
        // chip mode sendiri -- dipetakan balik ke chip dasar "Payload + Remote
        // Proxy" (3, paling dekat semantiknya: proxy/CDN & TLS sama-sama
        // relevan) DITAMBAH chipEnhancedToggle dinyalakan, supaya
        // currentModeIndex() menghasilkan 4 lagi persis seperti sebelum
        // disimpan -- lihat dokumentasi currentModeIndex().
        val chip = when (saved.modeIndex) {
            1 -> binding.chipSshSsl
            2 -> binding.chipSshEnhanced
            3, 4 -> binding.chipPayloadRemoteProxy
            else -> binding.chipSsh
        }
        chip.isChecked = true
        binding.chipEnhancedToggle.isChecked = saved.modeIndex == 4
        binding.chipRawMode.isChecked = saved.proxyRawMode
        // Profil ini SUDAH punya pilihan Raw Passthrough eksplisit tersimpan
        // -- jangan sampai ditimpa auto-default kalau user gonta-ganti chip
        // mode selama sesi edit ini (lihat applyDefaultRawModeForEnhancedIfNeeded).
        rawModeHasExplicitValue = true

        // PERBAIKAN (permintaan user, "kunci payload & remote proxy malah
        // kebuka semua"): dipanggil PALING TERAKHIR di sini, setelah semua
        // field di atas (termasuk etPayload/etProxyHost/etProxyPort) sudah
        // diisi nilai aslinya -- supaya field itu bisa langsung ditimpa
        // kosong & dinonaktifkan kalau memang perlu dikunci, lihat
        // dokumentasi [applyPayloadProxyLockIfNeeded].
        applyPayloadProxyLockIfNeeded(saved)
    }

    /**
     * PERBAIKAN (permintaan user, "kunci payload & remote proxy malah
     * kebuka semua, seharusnya akun server yang tetap bisa diedit"): kalau
     * profil yang sedang diedit lockMode-nya
     * [ConfigLockMode.LOCK_PAYLOAD_PROXY] (hasil impor dari orang lain yang
     * memang mengunci dua field ini saat ekspor -- lihat dokumentasi
     * [ConfigLockMode]), field Payload & seluruh blok Remote Proxy (host
     * proxy, port proxy, toggle Raw Passthrough) DIKOSONGKAN tampilannya &
     * DINONAKTIFKAN (tidak bisa diketik/diubah) -- beda dari akun
     * server (host/username/password/SNI) yang SENGAJA dibiarkan apa
     * adanya, tetap kelihatan & bisa diedit seperti biasa.
     *
     * Nilai ASLI payload/proxy tetap TERSIMPAN utuh di [originalConfig]
     * (tidak pernah dihapus dari data-nya sendiri, cuma disembunyikan dari
     * TAMPILAN form) -- dipakai lagi oleh [onSaveClicked] supaya nilai itu
     * tidak ikut hilang begitu user menyimpan perubahan lain (mis. ganti
     * nama akun) selagi field ini terkunci, dan tetap dipakai apa adanya
     * saat aplikasi connect ke server ini.
     */
    private fun applyPayloadProxyLockIfNeeded(saved: SavedConfig) {
        if (saved.lockMode != ConfigLockMode.LOCK_PAYLOAD_PROXY) return

        val lockedHint = "🔒 Terkunci oleh pembuat konfigurasi"

        binding.etPayload.setText("")
        binding.etPayload.isEnabled = false
        binding.tilPayload.isEnabled = false
        binding.tilPayload.hint = lockedHint

        binding.etProxyHost.setText("")
        binding.etProxyHost.isEnabled = false
        binding.tilProxyHost.isEnabled = false
        binding.tilProxyHost.hint = lockedHint

        binding.etProxyPort.setText("")
        binding.etProxyPort.isEnabled = false
        binding.tilProxyPort.isEnabled = false
        binding.tilProxyPort.hint = "🔒 Terkunci"

        binding.chipRawMode.isChecked = false
        binding.chipRawMode.isEnabled = false
    }

    private fun setupModeChips() {
        binding.chipGroupMode.setOnCheckedStateChangeListener { _, _ ->
            applyDefaultRawModeForEnhancedIfNeeded()
            updateFieldVisibilityForMode()
        }
        binding.chipGroupEnhancedToggle.setOnCheckedStateChangeListener { _, _ -> updateFieldVisibilityForMode() }
        // Raw Passthrough dulu tidak punya listener sama sekali -- toggle-nya
        // KETAHUAN memengaruhi apakah Header HTTP tambahan kepakai (lihat
        // updateWsAndHeaderFieldState) tapi UI tidak pernah di-refresh saat
        // di-tap, jadi disambungkan di sini juga. FITUR BARU (permintaan
        // user): tap MANUAL user di sini juga menandai rawModeHasExplicitValue
        // supaya applyDefaultRawModeForEnhancedIfNeeded() tidak lagi menimpa
        // pilihan user kalau dia balik ke mode "SSH TLS Payload Proxy" lagi
        // nanti -- kecuali ini perubahan PROGRAMATIK dari auto-default itu
        // sendiri (settingRawModeProgrammatically), yang tidak dihitung.
        binding.chipRawMode.setOnCheckedChangeListener { _, _ ->
            if (!settingRawModeProgrammatically) rawModeHasExplicitValue = true
            updateFieldVisibilityForMode()
        }
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
     * FITUR BARU (permintaan user): begitu mode "SSH SSL" (modeIndex 1),
     * "SSH TLS Payload Proxy" (modeIndex 2), ATAU "Payload + Remote Proxy"
     * (modeIndex 3) dipilih, chip "Raw Passthrough" otomatis DINYALAKAN
     * sebagai default -- tapi tetap bisa dimatikan manual lewat chip-nya
     * sendiri kapan saja. Auto-default ini HANYA berlaku selama
     * rawModeHasExplicitValue masih false, yaitu: profil baru yang belum
     * pernah disentuh chip Raw Passthrough-nya sama sekali di sesi ini.
     * Begitu user tap chip itu sendiri (atau ini profil lama hasil EDIT,
     * lihat restoreSavedConfig), pilihannya dihormati & tidak ditimpa lagi
     * walau mode dipindah-pindah.
     */
    private fun applyDefaultRawModeForEnhancedIfNeeded() {
        val modeIndex = currentModeIndex()
        // DIKEMBALIKAN (permintaan user): modeIndex 1 (SSH SSL) dicopot lagi --
        // mode ini tidak lagi punya Remote Proxy sama sekali, lihat updateFieldVisibilityForMode().
        // modeIndex 4 ikut disertakan di sini -- sesuai dokumentasi ConnectionMode.ENHANCED,
        // varian Raw Passthrough (CDN) memang default aktif begitu modeIndex efektifnya 4,
        // baik lewat mode dasar 2 maupun 3 + toggle chipEnhancedToggle (lihat currentModeIndex()).
        if ((modeIndex == 2 || modeIndex == 3 || modeIndex == 4) &&
            !rawModeHasExplicitValue && !binding.chipRawMode.isChecked
        ) {
            settingRawModeProgrammatically = true
            binding.chipRawMode.isChecked = true
            settingRawModeProgrammatically = false
        }
    }

    /**
     * 0 = SSH, 1 = SSH SSL, 2 = SSH TLS Payload Proxy, 3 = Payload + Remote Proxy.
     *
     * Mode 3 (Payload + Remote Proxy) memakai [com.example.tunnelapp.model.ConnectionMode.REMOTE_PROXY]:
     * proxy WAJIB diisi (beda dari mode 1 & 2 yang proxy-nya opsional), payload custom
     * bisa diisi. TLS/SNI hanya relevan untuk mode payload+proxy biasa; begitu
     * toggle Raw Passthrough dinyalakan, koneksi jalan apa adanya tanpa
     * TLS/SNI sama sekali, jadi field itu disembunyikan.
     *
     * FITUR BARU (permintaan user): mode 1 (SSH SSL) sekarang juga ikut punya
     * proxy/CDN opsional + toggle Raw Passthrough, persis seperti mode 2 --
     * bedanya, begitu chip "SSH SSL" dipilih, Raw Passthrough-nya AKTIF secara
     * default (lihat applyDefaultRawModeForEnhancedIfNeeded).
     */
    /**
     * FITUR BARU (permintaan user): "Enhanced" bukan lagi chip mode berdiri
     * sendiri -- sekarang toggle ([chipEnhancedToggle]) di blok Remote Proxy,
     * satu kelompok dengan Raw Passthrough (lihat activity_ssh_config.xml).
     * Toggle ini menumpuk di atas mode dasar yang sedang dipilih: kalau mode
     * dasarnya "SSH TLS Payload Proxy" (2) atau "Payload + Remote Proxy" (3)
     * DAN toggle-nya menyala, modeIndex efektif tetap 4 (ConnectionMode.ENHANCED)
     * persis seperti dulu chip Enhanced berdiri sendiri -- begitu toggle
     * dimatikan, modeIndex balik ke mode dasarnya (2 atau 3) apa adanya. Mode
     * SSH (0) & SSH SSL (1) tidak punya blok Remote Proxy sama sekali, jadi
     * toggle ini tidak berpengaruh di sana walau kebetulan masih menyala dari
     * sesi sebelumnya.
     */
    private fun currentModeIndex(): Int = when {
        binding.chipPayloadRemoteProxy.isChecked ->
            if (binding.chipEnhancedToggle.isChecked) 4 else 3
        binding.chipSshEnhanced.isChecked ->
            if (binding.chipEnhancedToggle.isChecked) 4 else 2
        binding.chipSshSsl.isChecked -> 1
        else -> 0
    }

    private fun proxyRawModeEnabled(): Boolean =
        (currentModeIndex() == 2 || currentModeIndex() == 3 || currentModeIndex() == 4) &&
            binding.chipRawMode.isChecked

    /** Mode 3 pakai TLS/SNI kecuali Raw Passthrough dinyalakan; mode 4 (Enhanced)
     *  SELALU pakai TLS apa pun status Raw Passthrough-nya -- lihat [currentModeIndex]
     *  & dokumentasi ConnectionMode.ENHANCED (TLS selalu aktif). */
    private fun usesTlsForMode(modeIndex: Int): Boolean =
        modeIndex == 1 || modeIndex == 2 || modeIndex == 4 || (modeIndex == 3 && !proxyRawModeEnabled())

    /**
     * FITUR BARU (permintaan user, "Enhanced di HTTP Custom/Darktunnel cuma
     * on/off, bukan buka kolom SNI/TLS"): mode 4 (Enhanced) TETAP selalu
     * pakai TLS secara internal (lihat [usesTlsForMode] & dokumentasi
     * ConnectionMode.ENHANCED) -- yang berubah cuma tampilannya, blok
     * SNI/TLS version/Ignore cert errors SENGAJA disembunyikan lagi khusus
     * untuk mode 4 supaya toggle Enhanced terasa seperti switch murni,
     * tanpa form baru yang muncul.
     *
     * SNI otomatis jatuh ke host asli server begitu field-nya kosong (lihat
     * ConnectRelay.kt baris ~299 & ~410: `config.sslSni?.takeIf { it.isNotBlank() }
     * ?: config.host`), jadi menyembunyikan field ini TIDAK mematikan TLS
     * ataupun butuh nilai default baru -- perilaku koneksinya persis sama,
     * cuma kolom isiannya yang tidak lagi dipaksa muncul ke user.
     */
    private fun showsTlsConfigFieldsForMode(modeIndex: Int): Boolean =
        usesTlsForMode(modeIndex) && modeIndex != 4

    private fun updateFieldVisibilityForMode() {
        val modeIndex = currentModeIndex()
        val showTlsConfigFields = showsTlsConfigFieldsForMode(modeIndex)
        val usesPayload = modeIndex == 2 || modeIndex == 3 || modeIndex == 4
        // DIKEMBALIKAN (permintaan user): mode 1 (SSH SSL) tidak lagi menampilkan
        // blok Remote Proxy/Raw Passthrough -- kembali ke perilaku SSH SSL polos
        // (TLS wrap langsung ke host, tanpa proxy/CDN sama sekali).
        // modeIndex 4 (Enhanced) ikut disertakan -- proxy/CDN opsional kecuali
        // Raw Passthrough aktif (lihat validasi di onSaveClicked).
        val usesProxy = modeIndex == 2 || modeIndex == 3 || modeIndex == 4
        val proxyMandatory = modeIndex == 3
        val usesRawMode = proxyRawModeEnabled()

        binding.tilSni.visibility = if (showTlsConfigFields) android.view.View.VISIBLE else android.view.View.GONE
        binding.containerTlsVersion.visibility = if (showTlsConfigFields) android.view.View.VISIBLE else android.view.View.GONE
        binding.chipIgnoreCertErrors.visibility = if (showTlsConfigFields) android.view.View.VISIBLE else android.view.View.GONE
        binding.tilPayload.visibility = if (usesPayload) android.view.View.VISIBLE else android.view.View.GONE
        // FITUR BARU (permintaan user): "Remote Proxy" (label + chip Raw Passthrough
        // + host/port proxy) sekarang satu blok (containerRemoteProxy) dengan SATU
        // visibility -- lihat activity_ssh_config.xml, chipGroupEnhancedToggle &
        // containerProxy sudah dipindah jadi anak dari blok ini.
        binding.containerRemoteProxy.visibility = if (usesProxy) android.view.View.VISIBLE else android.view.View.GONE

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

        val accountName = binding.etAccountName.text.toString().trim()

        // FITUR BARU (permintaan user): validasi & parsing dilakukan lewat kolom
        // gabungan "host:port@username:password" (etQuickPaste), bukan lagi
        // lewat field Host/Username terpisah -- keduanya sudah disembunyikan
        // di layout & cuma dipakai sebagai penyimpanan hasil parsing di bawah ini.
        if (!applyServerComboText(binding.etQuickPaste.text.toString(), showErrors = true)) {
            return
        }

        val host = binding.etHost.text.toString().trim()
        val port = binding.etPort.text.toString().trim().toIntOrNull() ?: 22
        val username = binding.etUsername.text.toString().trim()
        val password = binding.etPassword.text.toString()
        val sni = binding.etSni.text.toString().trim()
        val wsPath = binding.etWsPath.text.toString().trim()
        val customHeaders = binding.etCustomHeaders.text.toString().trim()
        val dns1 = binding.etDns1.text.toString().trim()
        val dns2 = binding.etDns2.text.toString().trim()

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

        val payloadProxyLocked = originalConfig?.lockMode == ConfigLockMode.LOCK_PAYLOAD_PROXY

        val usesPayload = modeIndex == 2 || modeIndex == 3 || modeIndex == 4
        // PERBAIKAN (permintaan user, "kunci payload & remote proxy malah
        // kebuka semua"): kalau field ini sedang dikunci (lihat
        // [applyPayloadProxyLockIfNeeded]), form-nya SENGAJA dikosongkan &
        // dinonaktifkan di layar -- nilai yang benar-benar disimpan HARUS
        // diambil dari [originalConfig], bukan dari form, supaya nilai
        // ASLI-nya tidak ikut terhapus cuma karena user menyimpan
        // perubahan lain (mis. ganti nama akun) selagi field ini terkunci.
        val payload = when {
            payloadProxyLocked -> originalConfig?.payload.orEmpty()
            usesPayload -> binding.etPayload.text.toString()
            else -> ""
        }

        // DIKEMBALIKAN (permintaan user): modeIndex 1 (SSH SSL) dicopot lagi --
        // lihat updateFieldVisibilityForMode(). modeIndex 4 (Enhanced) disertakan
        // juga di sini supaya proxy/CDN-nya ikut tersimpan.
        val usesProxy = modeIndex == 2 || modeIndex == 3 || modeIndex == 4
        val proxyHost = when {
            payloadProxyLocked -> originalConfig?.proxyHost.orEmpty()
            usesProxy -> binding.etProxyHost.text.toString().trim()
            else -> ""
        }
        val proxyPortText = when {
            payloadProxyLocked -> originalConfig?.proxyPort.orEmpty()
            usesProxy -> binding.etProxyPort.text.toString().trim()
            else -> ""
        }
        val proxyRawMode = when {
            payloadProxyLocked -> originalConfig?.proxyRawMode ?: false
            usesProxy -> proxyRawModeEnabled()
            else -> false
        }

        // Validasi Raw Passthrough/Payload+Remote Proxy di bawah ini cuma
        // relevan kalau field-nya memang diisi lewat FORM -- kalau sedang
        // terkunci ([payloadProxyLocked]), nilai aslinya sudah pasti valid
        // (tersimpan begitu saat pertama kali diimpor), jadi dilewati saja.
        if (!payloadProxyLocked) {
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
        }

        val usesTls = usesTlsForMode(modeIndex)
        val tlsVersion = if (usesTls) selectedTlsVersion() else null
        val ignoreCertErrors = usesTls && binding.chipIgnoreCertErrors.isChecked

        // FIX (multi-akun): dulu di sini ada logika "pertahankan xrayLink
        // profil lain sebelum menyimpan" karena SSH & Xray berbagi SATU slot
        // penyimpanan. Sekarang tiap akun (SSH maupun Xray) adalah profil
        // [ProfileStore] sendiri-sendiri -- menyimpan profil SSH ini TIDAK
        // pernah menyentuh profil lain sama sekali, jadi tidak perlu lagi
        // baca+pertahankan field profil lain di sini.
        //
        // PERBAIKAN (permintaan user, "lockMode ke-reset diam-diam"):
        // isLocked & lockMode SEKARANG dipertahankan apa adanya dari
        // [originalConfig] (config lama SEBELUM diedit) -- sebelumnya
        // fungsi ini selalu membuat [SavedConfig] baru dari nol tanpa
        // membawa kedua field itu, jadi ke-reset diam-diam jadi
        // false/NONE tiap kali profil manapun disimpan ulang lewat layar
        // ini, termasuk akun yang tadinya terkunci.
        editingProfileId = ProfileStore.upsert(
            this,
            editingProfileId,
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
                xrayLink = "",
                customHeaders = customHeaders,
                ignoreCertErrors = ignoreCertErrors,
                dns1 = dns1,
                dns2 = dns2,
                accountName = accountName,
                isLocked = originalConfig?.isLocked ?: false,
                lockMode = originalConfig?.lockMode ?: ConfigLockMode.NONE,
                // FITUR BARU (permintaan user, "catatan hasil impor tampil
                // di menu Catatan Dashboard"): dipertahankan apa adanya
                // sama seperti isLocked/lockMode di atas -- tanpa ini,
                // catatan akun hasil impor ke-reset diam-diam jadi kosong
                // tiap kali profil ini disimpan ulang lewat layar edit ini.
                note = originalConfig?.note ?: ""
            )
        )

        finish()
    }
}
