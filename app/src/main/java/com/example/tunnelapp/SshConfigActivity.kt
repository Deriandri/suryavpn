package com.example.tunnelapp

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.tunnelapp.databinding.ActivitySshConfigBinding
import com.example.tunnelapp.model.ConfigLockMode
import com.example.tunnelapp.model.ProfileStore
import com.example.tunnelapp.model.SavedConfig

/**
 * Layar konfigurasi khusus jalur SSH -- REFACTOR (permintaan user, "opsi 1+2,
 * toggle independen"): TLS/Payload/Remote Proxy sekarang 3 toggle berdiri
 * sendiri yang bisa dikombinasikan bebas (lihat activity_ssh_config.xml
 * chipGroupMode & ServerConfig.kt), menggantikan 4 preset mode lama
 * (SSH/SSH SSL/SSH TLS Payload Proxy/Payload+Remote Proxy).
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

        // REVISI (permintaan user, "cabut logika TLS+Proxy-paksa, jadikan
        // Enhanced cuma nyisipkan payload contoh"): changelog resmi
        // DarkTunnel v1.0.20 bilang "Enhanced" itu fitur PAYLOAD ("payload
        // enhanced" + "payload enhanced template"), BUKAN gabungan TLS+Proxy
        // seperti tebakan pertama saya. Mekanisme PERSIS di baliknya tidak
        // bisa dipastikan (DarkTunnel/HTTP Custom closed-source) -- jadi
        // REVISI KE-2 (bukti baru dari log koneksi HTTP Custom yang user
        // kirim -- BUKAN tebakan lagi): pola request-nya beda dari template
        // pertama saya (HEAD+PATCH+[split]) -- yang kelihatan di log adalah
        // method ACL + DUA header Host berbeda + X-Forward-Host, teknik
        // "header confusion" (CDN di depan baca Host pertama/X-Forward-Host
        // buat routing ke target ASLI, ISP/DPI di depan cuma lihat header
        // Host KEDUA yang didekoi ke domain gratisan). [host] otomatis diisi
        // host server SSH asli (dipakai 2x: Host pertama & X-Forward-Host,
        // sama seperti "ayrp.online" muncul 2x di log). Domain dekoi
        // SENGAJA dikosongkan (bukan "line.me" dari log user -- itu spesifik
        // ke kuota gratis ISP tertentu) -- user isi sendiri sesuai domain
        // gratisan yang dipakai.
        // Token yang HARUS diganti user sebelum Simpan (divalidasi di onSaveClicked).
        private const val DECOY_PLACEHOLDER = "[ISI_DOMAIN_GRATIS_ISP_DISINI]"

        private const val ENHANCED_PAYLOAD_TEMPLATE =
            "ACL / HTTP/1.1[crlf]Host: [host][crlf]Upgrade: websocket[crlf]Connection: keep-alive[crlf]" +
                "Proxy-Connection: keep-alive[crlf]Host: " + DECOY_PLACEHOLDER + "[crlf]" +
                "X-Forward-Host: [host][crlf]User-Agent: [ua][crlf][crlf]"
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
        binding.btnQuickPasteApply.setOnClickListener { onQuickPasteApplied() }
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
        binding.etSni.setText(saved.sni)
        binding.etPayload.setText(saved.payload)
        binding.etWsPath.setText(saved.wsPath)
        binding.etCustomHeaders.setText(saved.customHeaders)
        binding.chipIgnoreCertErrors.isChecked = saved.ignoreCertErrors
        // REVISI (permintaan user, "hapus total fallback dns bawaan, dns
        // sekarang hanya tinggal di pengaturan"): field DNS1/DNS2 per-akun
        // di layar ini SUDAH DIHAPUS -- DNS sekarang cuma diatur dari kartu
        // "VPN Setting" di layar Pengaturan. saved.dns1/dns2 (data lama,
        // kalau ada) TIDAK dibaca lagi di sini, dan TIDAK dipakai lagi untuk
        // resolusi DNS mana pun (lihat MyVpnService.applyDnsServers).
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
        // terakhir adalah Xray, layar ini tetap tampil dengan toggle kosong
        // sebagai default (belum pernah dikonfigurasi SSH-nya).
        // REFACTOR (opsi 1+2): 3 toggle independen, bukan 1 chip mode lagi --
        // dibaca lewat resolvedXxxEnabled() supaya akun LAMA (modeIndex based,
        // belum punya field tlsEnabled/proxyEnabled/payloadEnabled eksplisit)
        // tetap dipulihkan dengan kombinasi toggle yang setara, lihat SavedConfig.
        binding.chipToggleTls.isChecked = saved.resolvedTlsEnabled()
        binding.chipTogglePayload.isChecked = saved.resolvedPayloadEnabled()
        binding.chipToggleProxy.isChecked = saved.resolvedProxyEnabled()
        binding.chipToggleEnhanced.isChecked = saved.resolvedEnhancedEnabled()
        binding.chipRawMode.isChecked = saved.proxyRawMode
        // Profil ini SUDAH punya pilihan Raw Passthrough eksplisit tersimpan
        // -- jangan sampai ditimpa auto-default kalau user gonta-ganti toggle
        // selama sesi edit ini (lihat applyDefaultRawModeForEnhancedIfNeeded).
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

        // Toggle Payload/Proxy/Enhanced ikut terkunci (ketiganya masuk daftar
        // field terkunci di ConfigLock.lockedFieldsFor). Saat Simpan, nilainya
        // diambil dari originalConfig, bukan dari chip ini.
        binding.chipTogglePayload.isEnabled = false
        binding.chipToggleProxy.isEnabled = false
        binding.chipToggleEnhanced.isEnabled = false
    }

    private fun setupModeChips() {
        binding.chipGroupMode.setOnCheckedStateChangeListener { _, _ ->
            applyDefaultRawModeForEnhancedIfNeeded()
            updateFieldVisibilityForMode()
        }
        // Listener KHUSUS di chip-nya sendiri (bukan lewat listener grup di
        // atas) supaya applyEnhancedPayloadTemplateIfNeeded() cuma jalan
        // TEPAT saat Enhanced baru dicentang, bukan tiap kali chip lain
        // (TLS/Payload/Proxy) di grup yang sama berubah.
        binding.chipToggleEnhanced.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) applyEnhancedPayloadTemplateIfNeeded()
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
     * FITUR BARU (permintaan user): begitu toggle "Gunakan Proxy" dinyalakan,
     * chip "Raw Passthrough" otomatis DINYALAKAN sebagai default -- tapi
     * tetap bisa dimatikan manual lewat chip-nya sendiri kapan saja.
     * Auto-default ini HANYA berlaku selama rawModeHasExplicitValue masih
     * false, yaitu: profil baru yang belum pernah disentuh chip Raw
     * Passthrough-nya sama sekali di sesi ini. Begitu user tap chip itu
     * sendiri (atau ini profil lama hasil EDIT, lihat restoreSavedConfig),
     * pilihannya dihormati & tidak ditimpa lagi walau toggle proxy
     * dimatikan-nyalakan lagi.
     */
    private fun applyDefaultRawModeForEnhancedIfNeeded() {
        if (proxyToggleOn() && !rawModeHasExplicitValue && !binding.chipRawMode.isChecked) {
            settingRawModeProgrammatically = true
            binding.chipRawMode.isChecked = true
            settingRawModeProgrammatically = false
        }
    }

    // REFACTOR (opsi 1+2, toggle independen): pengganti currentModeIndex()
    // lama -- TLS/Proxy/Payload sekarang 3 chip berdiri sendiri
    // (chipGroupMode multi-select), bisa dikombinasikan bebas, bukan lagi
    // 1 dari 4 preset mode.
    private fun tlsToggleOn(): Boolean = binding.chipToggleTls.isChecked
    private fun payloadToggleOn(): Boolean = binding.chipTogglePayload.isChecked
    private fun proxyToggleOn(): Boolean = binding.chipToggleProxy.isChecked
    private fun enhancedToggleOn(): Boolean = binding.chipToggleEnhanced.isChecked

    /**
     * REVISI (permintaan user, "cabut logika TLS+Proxy-paksa, jadikan
     * Enhanced cuma nyisipkan payload contoh"): dipanggil HANYA saat chip
     * Enhanced baru saja DICENTANG (bukan tiap kali grup chip berubah) --
     * lihat listener khusus chipToggleEnhanced di setupModeChips(). Field
     * Payload diisi [ENHANCED_PAYLOAD_TEMPLATE] KALAU masih kosong saja
     * (tidak menimpa payload yang sudah ditulis user), lalu toggle
     * "Gunakan Payload" ikut dinyalakan otomatis supaya field-nya langsung
     * kelihatan. TLS & Proxy TIDAK disentuh sama sekali di sini lagi.
     */
    private fun applyEnhancedPayloadTemplateIfNeeded() {
        if (binding.etPayload.text.isNullOrEmpty()) {
            binding.etPayload.setText(ENHANCED_PAYLOAD_TEMPLATE)
        }
        binding.chipTogglePayload.isChecked = true
    }

    private fun proxyRawModeEnabled(): Boolean = proxyToggleOn() && binding.chipRawMode.isChecked

    private fun updateFieldVisibilityForMode() {
        val usesTls = tlsToggleOn()
        val usesPayload = payloadToggleOn()
        val usesProxy = proxyToggleOn()
        val usesRawMode = proxyRawModeEnabled()

        binding.tilSni.visibility = if (usesTls) android.view.View.VISIBLE else android.view.View.GONE
        binding.containerTlsVersion.visibility = if (usesTls) android.view.View.VISIBLE else android.view.View.GONE
        binding.chipIgnoreCertErrors.visibility = if (usesTls) android.view.View.VISIBLE else android.view.View.GONE
        binding.tilPayload.visibility = if (usesPayload) android.view.View.VISIBLE else android.view.View.GONE
        // FITUR BARU (permintaan user): "Remote Proxy" (label + chip Raw Passthrough
        // + host/port proxy) sekarang satu blok (containerRemoteProxy) dengan SATU
        // visibility -- lihat activity_ssh_config.xml, chipGroupEnhancedToggle &
        // containerProxy sudah dipindah jadi anak dari blok ini.
        binding.containerRemoteProxy.visibility = if (usesProxy) android.view.View.VISIBLE else android.view.View.GONE

        binding.tilSni.hint = "SNI / Host WebSocket (kosongkan jika tidak perlu)"
        // REFACTOR (opsi 1+2): proxy TIDAK lagi wajib di kombinasi apa pun --
        // begitu toggle "Gunakan Remote Proxy" dinyalakan, host wajib diisi
        // (baru divalidasi saat Simpan, lihat onSaveClicked), makanya hint-nya
        // selalu bilang "wajib" begitu blok ini kelihatan sama sekali.
        binding.tilProxyHost.hint = if (usesRawMode) {
            "Host / IP Proxy atau CDN (wajib)"
        } else {
            "Host / IP Proxy (wajib)"
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
        val usesTls = tlsToggleOn()
        val usesPayload = payloadToggleOn()
        val usesProxy = proxyToggleOn()
        val usesEnhanced = enhancedToggleOn()

        val accountName = binding.etAccountName.text.toString().trim()
        val host = binding.etHost.text.toString().trim()
        val port = binding.etPort.text.toString().trim().toIntOrNull() ?: 22
        val username = binding.etUsername.text.toString().trim()
        val password = binding.etPassword.text.toString()
        val sni = binding.etSni.text.toString().trim()
        val wsPath = binding.etWsPath.text.toString().trim()
        val customHeaders = binding.etCustomHeaders.text.toString().trim()

        if (host.isEmpty() || username.isEmpty()) {
            binding.etHost.error = if (host.isEmpty()) "Wajib diisi" else null
            binding.etUsername.error = if (username.isEmpty()) "Wajib diisi" else null
            return
        }

        // REVISI (permintaan user, "hapus total fallback dns bawaan, dns
        // sekarang hanya tinggal di pengaturan"): field & validasi DNS1/DNS2
        // per-akun di sini SUDAH DIHAPUS -- DNS sekarang cuma diatur dari
        // kartu "VPN Setting" di layar Pengaturan (lihat SettingsActivity &
        // VpnSettingsStore).

        val payloadProxyLocked = originalConfig?.lockMode == ConfigLockMode.LOCK_PAYLOAD_PROXY

        // PERBAIKAN (permintaan user, "kunci payload & remote proxy malah
        // kebuka semua"): kalau field ini sedang dikunci (lihat
        // [applyPayloadProxyLockIfNeeded]), form-nya SENGAJA dikosongkan &
        // dinonaktifkan di layar -- nilai yang benar-benar disimpan HARUS
        // diambil dari [originalConfig], bukan dari form, supaya nilai
        // ASLI-nya tidak ikut hilang cuma karena user menyimpan
        // perubahan lain (mis. ganti nama akun) selagi field ini terkunci.
        val payload = when {
            payloadProxyLocked -> originalConfig?.payload.orEmpty()
            usesPayload -> binding.etPayload.text.toString()
            else -> ""
        }

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

        // Toggle Payload/Proxy/Enhanced juga diambil dari config asli kalau
        // terkunci (chip-nya dinonaktifkan, lihat applyPayloadProxyLockIfNeeded).
        val savedPayloadEnabled =
            if (payloadProxyLocked) (originalConfig?.resolvedPayloadEnabled() ?: usesPayload) else usesPayload
        val savedProxyEnabled =
            if (payloadProxyLocked) (originalConfig?.resolvedProxyEnabled() ?: usesProxy) else usesProxy
        val savedEnhancedEnabled =
            if (payloadProxyLocked) (originalConfig?.resolvedEnhancedEnabled() ?: usesEnhanced) else usesEnhanced

        // Template Enhanced punya token yang wajib diganti -- kalau dibiarkan,
        // teksnya terkirim apa adanya sebagai nilai header Host.
        if (!payloadProxyLocked && usesPayload && payload.contains(DECOY_PLACEHOLDER)) {
            binding.etPayload.error = "Ganti $DECOY_PLACEHOLDER dengan domain bug/gratisan dulu"
            return
        }

        // Validasi Raw Passthrough/Remote Proxy di bawah ini cuma relevan
        // kalau field-nya memang diisi lewat FORM -- kalau sedang terkunci
        // ([payloadProxyLocked]), nilai aslinya sudah pasti valid (tersimpan
        // begitu saat pertama kali diimpor), jadi dilewati saja.
        if (!payloadProxyLocked) {
            if (proxyRawMode && proxyHost.isEmpty()) {
                binding.etProxyHost.error = "Raw Passthrough butuh host/IP proxy atau CDN diisi"
                return
            }
            // REFACTOR (opsi 1+2): proxy TIDAK lagi wajib di kombinasi/mode
            // apa pun secara bawaan -- tapi begitu toggle "Gunakan Remote
            // Proxy" DINYALAKAN, host proxy tetap wajib diisi (toggle nyala +
            // host kosong dianggap konfigurasi belum lengkap).
            if (usesProxy && proxyHost.isEmpty()) {
                binding.etProxyHost.error = "Toggle Remote Proxy aktif, host/IP proxy wajib diisi"
                return
            }
        }

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
                // REFACTOR (opsi 1+2): modeIndex 0 tetap dipakai (cuma untuk
                // bedakan akun SSH vs Xray, lihat modeIndex==5 di tempat
                // lain) -- kombinasi TLS/Proxy/Payload yang sebenarnya
                // SEKARANG disimpan eksplisit lewat tlsEnabled/proxyEnabled/
                // payloadEnabled di bawah, bukan lewat modeIndex lagi.
                modeIndex = 0,
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
                // REVISI (permintaan user, "hapus total fallback dns bawaan,
                // dns sekarang hanya tinggal di pengaturan"): dns1/dns2
                // di sini SENGAJA tidak diisi lagi (fields ini masih ada di
                // SavedConfig cuma untuk data lama, defaultnya "" -- tidak
                // dibaca lagi oleh MyVpnService/XrayTunnelManager mana pun,
                // DNS sekarang cuma diatur dari kartu VPN Setting di
                // Pengaturan).
                accountName = accountName,
                isLocked = originalConfig?.isLocked ?: false,
                lockMode = originalConfig?.lockMode ?: ConfigLockMode.NONE,
                // FITUR BARU (permintaan user, "catatan hasil impor tampil
                // di menu Catatan Dashboard"): dipertahankan apa adanya
                // sama seperti isLocked/lockMode di atas -- tanpa ini,
                // catatan akun hasil impor ke-reset diam-diam jadi kosong
                // tiap kali profil ini disimpan ulang lewat layar edit ini.
                note = originalConfig?.note ?: "",
                tlsEnabled = usesTls,
                proxyEnabled = savedProxyEnabled,
                payloadEnabled = savedPayloadEnabled,
                enhancedEnabled = savedEnhancedEnabled
            )
        )

        finish()
    }
}
