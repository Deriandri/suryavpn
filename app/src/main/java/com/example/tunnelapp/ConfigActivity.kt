package com.example.tunnelapp

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.format.DateFormat
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout
import com.example.tunnelapp.databinding.ActivityConfigBinding
import com.example.tunnelapp.databinding.ItemAccountRowBinding
import com.example.tunnelapp.model.CloudConfigSync
import com.example.tunnelapp.model.CloudSyncStore
import com.example.tunnelapp.model.ConfigLockMode
import com.example.tunnelapp.model.ProfileStore
import com.example.tunnelapp.model.SavedConfig
import com.example.tunnelapp.model.SavedProfile
import com.example.tunnelapp.model.buildShareCode
import com.example.tunnelapp.model.decryptWholeFileBytes
import com.example.tunnelapp.model.encryptWholeFileBytes
import com.example.tunnelapp.model.importConfigsFromText
import com.example.tunnelapp.model.profilesToJson
import com.example.tunnelapp.tunnel.XrayLinkParser
import java.io.File
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Layar "Konfigurasi" -- tab TENGAH baru di bilah navigasi bawah, di antara
 * "Dashboard" & "Pengaturan" (lihat bottom_nav_menu.xml & setupBottomNav()
 * di bawah). Isinya jalan pintas satu-tap ke [SshConfigActivity] &
 * [XrayConfigActivity] (dipakai untuk TAMBAH akun baru, dibuka tanpa extra
 * profile_id), supaya kedua layar konfigurasi server itu tidak cuma bisa
 * diakses lewat kartu "Menu" di halaman Main Dashboard (yang tetap ada,
 * tidak dihapus -- ini cuma jalan pintas tambahan yang lebih gampang
 * ditemukan).
 *
 * FITUR MULTI-AKUN (permintaan user): kartu "Akun Tersimpan" di atas sekarang
 * menampilkan SEMUA akun yang tersimpan lewat [ProfileStore] (SSH & Xray
 * campur, tidak dibatasi jumlahnya) -- satu baris per akun, dibangun DINAMIS
 * lewat [refreshAccountsList] (bukan lagi satu profil statis). Tiap baris
 * punya tombol Edit (buka SshConfigActivity/XrayConfigActivity dengan extra
 * profile_id -- mode edit, lihat [SshConfigActivity.EXTRA_PROFILE_ID]), tombol
 * Hapus (dengan konfirmasi), dan badge "Aktif" atau tombol "Jadikan Aktif"
 * supaya user bisa pilih sendiri akun mana yang dipakai saat Connect ditekan
 * di Dashboard (lihat DashboardMainFragment.onConnectClicked ->
 * ProfileStore.getActive).
 *
 * Navigasi 3-tab: DashboardActivity adalah root (selalu di dasar back
 * stack). ConfigActivity & SettingsActivity adalah "sibling" yang saling
 * finish() diri sendiri sebelum start sibling lain, supaya back stack
 * selalu cuma [Dashboard, <tab aktif>] -- tidak numpuk instance lama tiap
 * kali pindah tab (lihat juga DashboardActivity.setupBottomNav &
 * SettingsActivity.setupBottomNav).
 */
class ConfigActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConfigBinding

    // --- Dialog modern (permintaan user, "profesional, modern, smooth") -
    //
    // Semua dialog di layar ini SEBELUMNYA pakai `AlertDialog.Builder` polos
    // -- itu selalu tampil dengan gaya sistem default (kotak persegi, tombol
    // ALL CAPS biru generik), kontras banget dengan kartu/tombol lain di app
    // ini yang sudah bergaya Material3 modern (sudut membulat, warna brand
    // ungu, dst). [newDialogBuilder] menggantikan semua pemanggilan itu
    // dengan `MaterialAlertDialogBuilder` + tema kustom
    // `ThemeOverlay.TunnelApp.Dialog` (lihat themes.xml) supaya sudut dialog
    // ikut membulat, judul jadi bold, dan tombolnya pakai warna brand ungu
    // konsisten dengan tombol lain -- dipakai di SEMUA dialog di file ini
    // (hapus akun, kunci akun, bagikan, pilih mode kunci, impor, ekspor).
    private fun newDialogBuilder(): MaterialAlertDialogBuilder =
        MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_TunnelApp_Dialog)

    /**
     * Bungkus [EditText] polos ke dalam [TextInputLayout] bergaya
     * `Field.Outlined` yang sama dipakai form input lain di app ini
     * (lihat ConfigActivity/SshConfigActivity), supaya kotak teks di
     * dalam dialog (impor, nama file ekspor, kode bagikan) tidak lagi
     * terasa seperti kotak polos bawaan Android, melainkan konsisten
     * dengan seluruh form di app: label mengambang, sudut membulat, garis
     * highlight ungu saat fokus.
     */
    private fun dialogInputLayout(editText: EditText, hintText: String? = null): TextInputLayout {
        return TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply {
            id = View.generateViewId()
            hintText?.let { hint = it }
            setBoxCornerRadii(28f, 28f, 28f, 28f)
            setPadding(24, 8, 24, 0)
            addView(
                editText,
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
    }

    // --- Impor & Ekspor (permintaan user) -------------------------------
    //
    // registerForActivityResult HARUS didaftarkan sebelum Activity mencapai
    // state STARTED (sama seperti vpnPermissionLauncher di
    // DashboardMainFragment/notifPermissionLauncher di DashboardActivity) --
    // makanya sebagai property class, bukan dibuat on-demand di dalam
    // fungsi klik tombol.
    //
    // FITUR BARU (permintaan user, "bikin otomatis dengan izin user"): file
    // .spn sekarang otomatis ditulis ke folder tetap Download/SuryaVPN/ --
    // TIDAK ada lagi dialog file-picker (SAF) tiap kali ekspor. Nama folder
    // "SuryaVPN" konsisten dipakai baik lewat MediaStore (Android 10+, lihat
    // [saveExportViaMediaStore]) maupun lewat File API langsung (Android 9,
    // lihat [saveExportLegacy]).
    //
    // pendingExportBytes/pendingExportFilename menampung ekspor yang MAU
    // ditulis kalau ternyata di Android 9 izin WRITE_EXTERNAL_STORAGE belum
    // ada -- diisi SAAT itu juga di [onExportAllClicked], baru benar-benar
    // ditulis begitu permission dialog dijawab lewat
    // [storagePermissionLauncher] (mirip pola exportPendingBytes versi
    // sebelumnya, cuma sekarang nunggu izin, bukan nunggu URI dari SAF).
    private var pendingExportBytes: ByteArray? = null
    private var pendingExportFilename: String? = null

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val bytes = pendingExportBytes
        val filename = pendingExportFilename
        pendingExportBytes = null
        pendingExportFilename = null
        if (granted && bytes != null && filename != null) {
            writeExportAutomatically(bytes, filename)
        } else if (!granted) {
            Toast.makeText(
                this,
                "Izin penyimpanan ditolak -- tidak bisa menyimpan file konfigurasi",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private val importFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val bytes = try {
            contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (e: Exception) {
            null
        }
        if (bytes == null) {
            Toast.makeText(this, "Gagal membaca file", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        // FITUR BARU (permintaan user, "sekalian ganti biner"): file .spn
        // baru berisi biner terenkripsi utuh -> coba dekripsi dulu lewat
        // decryptWholeFileBytes. Kalau null (bukan hasil enkripsi format
        // ini sama sekali -- mis. file ekspor LAMA yang masih JSON polos,
        // atau file kode bagikan "SVPN1:..." yang memang tidak pernah
        // dienkripsi utuh), fallback baca sebagai teks UTF-8 apa adanya,
        // sama seperti perilaku sebelum fitur ini ada -> tetap kompatibel.
        val text = decryptWholeFileBytes(bytes) ?: bytes.toString(Charsets.UTF_8)
        if (text.isBlank()) {
            Toast.makeText(this, "Gagal membaca file", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        performImport(text)
    }

    /**
     * Tulis [bytes] ke Download/SuryaVPN/[filename] -- jalur berbeda
     * tergantung versi Android:
     *  - Android 10+ (API 29+): lewat [MediaStore.Downloads], SUDAH otomatis
     *    scoped-storage-compliant, TIDAK perlu izin apa pun (lihat
     *    [onExportAllClicked] -- permission dialog di-skip total di jalur ini).
     *  - Android 9 (API 28): folder publik Download harus ditulis langsung
     *    lewat File API, WAJIB izin WRITE_EXTERNAL_STORAGE terlebih dulu
     *    (lihat [storagePermissionLauncher]).
     */
    private fun writeExportAutomatically(bytes: ByteArray, filename: String) {
        val savedOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveExportViaMediaStore(bytes, filename)
        } else {
            saveExportLegacy(bytes, filename)
        }
        if (savedOk) {
            Toast.makeText(
                this, "Tersimpan di Download/SuryaVPN/$filename", Toast.LENGTH_LONG
            ).show()
        } else {
            Toast.makeText(this, "Gagal menyimpan file konfigurasi", Toast.LENGTH_LONG).show()
        }
    }

    private fun saveExportViaMediaStore(bytes: ByteArray, filename: String): Boolean = try {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, filename)
            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SuryaVPN")
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        if (uri == null) {
            false
        } else {
            contentResolver.openOutputStream(uri)?.use { out -> out.write(bytes) }
            true
        }
    } catch (e: Exception) {
        false
    }

    private fun saveExportLegacy(bytes: ByteArray, filename: String): Boolean = try {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val folder = File(downloadsDir, "SuryaVPN")
        if (!folder.exists()) folder.mkdirs()
        File(folder, filename).writeBytes(bytes)
        true
    } catch (e: Exception) {
        false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConfigBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Baris "Konfigurasi SSH"/"Konfigurasi Xray" di kartu bawah selalu
        // dibuka TANPA extra profile_id -> mode TAMBAH akun baru (lihat
        // dokumentasi EXTRA_PROFILE_ID di kedua Activity itu). Mode EDIT
        // akun yang sudah ada dipicu lewat tombol pensil per-baris di
        // refreshAccountsList(), bukan dari sini.
        binding.rowConfigSsh.setOnClickListener {
            startActivity(Intent(this, SshConfigActivity::class.java))
        }
        binding.rowConfigXray.setOnClickListener {
            startActivity(Intent(this, XrayConfigActivity::class.java))
        }
        // FITUR BARU (permintaan user, "cloud config"): baris ketiga, buka
        // dialog pengaturan URL sinkron -- lihat onCloudConfigClicked().
        binding.rowCloudConfig.setOnClickListener { onCloudConfigClicked() }

        binding.btnImportConfig.setOnClickListener { onImportClicked() }
        binding.btnExportAllConfig.setOnClickListener { onExportAllClicked() }

        setupAccountSearch()
        setupBottomNav()
    }

    override fun onResume() {
        super.onResume()
        // Daftar akun bisa saja baru diubah (ditambah/diedit/dihapus/pindah
        // aktif) di SshConfigActivity/XrayConfigActivity -- refresh tiap
        // kali layar ini kembali ditampilkan.
        refreshAccountsList()
        updateCloudConfigSubtitle()

        // FITUR BARU (permintaan user, "cloud config"): kalau auto-sync
        // diaktifkan, sinkron diam-diam tiap kali layar Konfigurasi ini
        // dibuka/kembali terlihat -- TIDAK menampilkan Toast "Menyinkron..."
        // supaya tidak mengganggu kalau user cuma numpang lewat, tapi hasil
        // akhirnya (akun bertambah/berubah & subjudul waktu sinkron) tetap
        // langsung terlihat begitu selesai. Kegagalan diam-diam juga (mis.
        // tidak ada internet) -- user tetap bisa sinkron manual lewat
        // dialog kalau curiga ada masalah.
        val settings = CloudSyncStore.load(this)
        if (settings.autoSyncEnabled && settings.cloudUrl.isNotBlank()) {
            lifecycleScope.launch {
                CloudConfigSync.sync(this@ConfigActivity)
                refreshAccountsList()
                updateCloudConfigSubtitle()
            }
        }
    }

    /** Perbarui teks subjudul baris "Cloud Config" (URL & waktu sinkron terakhir). */
    private fun updateCloudConfigSubtitle() {
        val settings = CloudSyncStore.load(this)
        binding.textCloudConfigSubtitle.text = when {
            settings.cloudUrl.isBlank() -> "Sinkron akun otomatis dari URL online"
            settings.lastSyncTimeMillis <= 0L -> "URL tersimpan -- belum pernah disinkron"
            else -> {
                val time = DateFormat.format("d MMM, HH:mm", settings.lastSyncTimeMillis)
                "Sinkron terakhir $time -- ${settings.lastSyncSummary}"
            }
        }
    }

    // --- Cloud Config (permintaan user, "jadi nanti sistem nya konfig
    // saya update online gtu") ------------------------------------------
    //
    // Sengaja TIDAK bikin format data baru -- URL yang diisi di sini cukup
    // mengembalikan teks yang SAMA PERSIS dengan hasil "Ekspor Semua"
    // (lihat onExportAllClicked/profilesToJson) atau satu kode bagikan
    // "SVPN1:...", persis seperti yang diterima kotak Impor manual. Lihat
    // dokumentasi lengkap di model/CloudConfigSync.kt & CloudSyncStore.kt.

    /**
     * Dialog pengaturan Cloud Config: URL sumber, toggle auto-sync (dipicu
     * tiap [onResume] layar ini), status sinkron terakhir, dan dua aksi --
     * "Simpan" (cuma menyimpan pengaturan) atau "Sinkron Sekarang" (simpan
     * SEKALIGUS langsung menjalankan satu putaran sinkron).
     */
    private fun onCloudConfigClicked() {
        val settings = CloudSyncStore.load(this)

        val urlInput = EditText(this).apply {
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setText(settings.cloudUrl)
            setPadding(48, 32, 48, 32)
        }
        val autoSyncCheckbox = CheckBox(this).apply {
            text = "Sinkron otomatis tiap kali layar ini dibuka"
            isChecked = settings.autoSyncEnabled
            setPadding(48, 4, 48, 4)
        }
        val statusLabel = TextView(this).apply {
            text = if (settings.lastSyncTimeMillis > 0L) {
                val time = DateFormat.format("d MMM yyyy, HH:mm", settings.lastSyncTimeMillis)
                "Sinkron terakhir: $time\n${settings.lastSyncSummary}"
            } else {
                "Belum pernah disinkron"
            }
            setTextColor(ContextCompat.getColor(this@ConfigActivity, R.color.text_secondary))
            textSize = 12f
            setPadding(48, 4, 48, 4)
        }

        val container = ScrollView(this).apply {
            addView(LinearLayout(this@ConfigActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(dialogInputLayout(
                    urlInput,
                    "URL cloud config (hasil \"Ekspor Semua\" atau kode SVPN2:...)"
                ))
                addView(autoSyncCheckbox)
                addView(statusLabel)
            })
        }

        newDialogBuilder()
            .setTitle("Cloud Config")
            .setMessage(
                "Host-kan file hasil \"Ekspor Semua\" di URL statis (mis. GitHub raw, " +
                    "hosting sendiri), lalu tempel URL-nya di sini. Setiap kali disinkron, " +
                    "app menarik isi TERBARU dari URL itu -- akun baru ditambahkan, akun " +
                    "yang berubah diperbarui, dan akun yang sudah dihapus dari sana ikut " +
                    "dihapus di sini. Akun hasil sinkron ini otomatis terkunci total " +
                    "(host/username/password/link tidak bisa dilihat/diedit lewat app) -- " +
                    "cuma bisa dipakai untuk connect atau dihapus."
            )
            .setView(container)
            .setNegativeButton("Tutup", null)
            .setNeutralButton("Sinkron Sekarang") { _, _ ->
                val url = urlInput.text?.toString().orEmpty().trim()
                CloudSyncStore.saveSettings(this, url, autoSyncCheckbox.isChecked)
                updateCloudConfigSubtitle()
                performCloudSync()
            }
            .setPositiveButton("Simpan") { _, _ ->
                val url = urlInput.text?.toString().orEmpty().trim()
                CloudSyncStore.saveSettings(this, url, autoSyncCheckbox.isChecked)
                updateCloudConfigSubtitle()
                Toast.makeText(this, "Pengaturan Cloud Config disimpan", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    /**
     * Jalankan satu putaran [CloudConfigSync.sync] dengan Toast "sedang
     * menyinkron" di awal (dipakai jalur MANUAL lewat tombol "Sinkron
     * Sekarang" -- beda dari auto-sync diam-diam di [onResume]) & ringkasan
     * hasil (berhasil/gagal) di akhir, lalu refresh daftar akun & subjudul.
     */
    private fun performCloudSync() {
        val url = CloudSyncStore.load(this).cloudUrl
        if (url.isBlank()) {
            Toast.makeText(this, "Isi URL cloud config terlebih dahulu", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "Menyinkron konfigurasi...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val result = CloudConfigSync.sync(this@ConfigActivity)
            refreshAccountsList()
            updateCloudConfigSubtitle()
            Toast.makeText(this@ConfigActivity, result.summaryText(), Toast.LENGTH_LONG).show()
        }
    }

    /**
     * FITUR BARU (permintaan user, "tombol search konfig"): query pencarian
     * akun yang sedang aktif, diisi lewat [setupAccountSearch]. Disimpan di
     * sini (bukan cuma dibaca langsung dari [binding.etSearchAccount] tiap
     * kali) supaya [refreshAccountsList] -- yang juga dipanggil dari
     * [onResume]/setelah impor/hapus/dll, TANPA lewat TextWatcher -- tetap
     * menghormati filter yang lagi diketik user, bukan diam-diam ke-reset
     * balik ke daftar penuh.
     */
    private var accountSearchQuery: String = ""

    /**
     * Pasang [TextWatcher] di [binding.etSearchAccount]: filter daftar akun
     * live setiap huruf diketik (tanpa perlu tombol "Cari" terpisah), cocok
     * ke [SavedConfig.accountName] ATAU [SavedConfig.host] (case-insensitive,
     * lihat [refreshAccountsList]). Ikon "x" (clear_text, lihat
     * activity_config.xml) sudah otomatis mengosongkan teks & memicu
     * afterTextChanged ini juga, jadi tidak perlu listener terpisah.
     */
    private fun setupAccountSearch() {
        binding.etSearchAccount.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                accountSearchQuery = s?.toString().orEmpty()
                refreshAccountsList()
            }
        })
    }

    private fun refreshAccountsList() {
        val allProfiles = ProfileStore.getAll(this)
        val activeId = ProfileStore.getActiveId(this)

        // FITUR BARU (permintaan user, "tombol search konfig"): kalau ada
        // query, saring dulu sebelum ditampilkan -- cocokkan ke nama akun
        // ATAU host, case-insensitive. Sengaja TETAP dicocokkan ke [host]
        // walau akunnya [ConfigLockMode.LOCK_ALL] (tersembunyi di UI),
        // karena ini cuma pencarian LOKAL di dalam app milik user sendiri
        // (bukan mengekspos apa pun ke luar) -- user yang sama yang
        // menyimpan akunnya berhak mencari pakai host yang dia tahu.
        val query = accountSearchQuery.trim()
        val profiles = if (query.isEmpty()) {
            allProfiles
        } else {
            allProfiles.filter { profile ->
                profile.config.accountName.contains(query, ignoreCase = true) ||
                    profile.config.host.contains(query, ignoreCase = true)
            }
        }

        binding.llAccountsContainer.removeAllViews()

        if (profiles.isEmpty()) {
            binding.llAccountEmpty.visibility = View.VISIBLE
            binding.textAccountEmptyMessage.text = if (query.isEmpty()) {
                "Belum ada akun ditambahkan. Tambahkan lewat Konfigurasi SSH atau Xray di bawah."
            } else {
                "Tidak ada akun yang cocok dengan pencarian \"$query\"."
            }
            return
        }
        binding.llAccountEmpty.visibility = View.GONE

        // REDESIGN (permintaan user: "gabungkan card akun tersimpan, rapikan
        // susunannya"): semua akun sekarang baris biasa di dalam SATU kartu
        // besar "Akun Tersimpan" (lihat activity_config.xml), dipisah garis
        // tipis "dividerRow" antar baris -- disembunyikan khusus untuk baris
        // PERTAMA (index == 0) supaya tidak dobel dengan garis header di
        // atasnya, sama seperti pola divider SSH/Xray di kartu atasnya.
        val inflater = LayoutInflater.from(this)
        profiles.forEachIndexed { index, profile ->
            val row = ItemAccountRowBinding.inflate(inflater, binding.llAccountsContainer, false)
            row.dividerRow.visibility = if (index == 0) View.GONE else View.VISIBLE
            bindAccountRow(row, profile, isActive = profile.id == activeId)
            binding.llAccountsContainer.addView(row.root)
        }
    }

    private fun bindAccountRow(row: ItemAccountRowBinding, profile: SavedProfile, isActive: Boolean) {
        val config = profile.config
        val isXray = config.modeIndex == 5

        // FITUR BARU (nama akun custom): kalau accountName diisi, dia yang
        // jadi judul utama (bold) & host:port/detail turun jadi subtitle
        // kecil di bawahnya. Kalau kosong, perilaku PERSIS seperti sebelum
        // fitur ini ada -- host:port/detail sebagai judul utama, tanpa subtitle.
        val hasCustomName = config.accountName.isNotBlank()

        // FITUR BARU (permintaan user, "kunci konfig saat ekspor"): akun
        // hasil impor dengan lockMode == LOCK_ALL (lihat [ConfigLockMode] &
        // model/ConfigLock.kt) "terkunci total" -- cuma NAMA-nya yang boleh
        // ditampilkan, detail host:port/payload/proxy/dst TIDAK PERNAH
        // ditampilkan sama sekali di baris ini.
        //
        // PERBAIKAN (permintaan user, "payload & remote proxy malah dikunci
        // semuanya"): LOCK_PAYLOAD_PROXY SENGAJA TIDAK ikut dianggap
        // contentLocked di sini -- mode itu cuma menyamarkan payload &
        // proxy DI DALAM file hasil ekspornya (lihat [resolveLockedFields],
        // field aslinya sudah dikembalikan utuh begitu diimpor), jadi akun
        // server (host/port/username/password/SNI) maupun payload/proxy-nya
        // harus tetap kelihatan & bisa diedit seperti akun biasa, bukan
        // ikut disembunyikan/diblokir Edit-nya.
        val contentLocked = config.lockMode == ConfigLockMode.LOCK_ALL
        val lockedDisplayName = config.accountName.ifBlank { "Akun Terkunci" }

        // FITUR BARU (permintaan user, "kunci akun"): openEditScreen disimpan
        // sebagai lambda dulu (bukan langsung dipasang ke btnRowEdit di tiap
        // cabang isXray seperti sebelumnya), supaya bisa DIBUNGKUS satu kali
        // dengan pengecekan isLocked di bawah -- lihat komentar
        // [SavedConfig.isLocked].
        val openEditScreen: () -> Unit
        if (isXray) {
            row.ivRowAvatarBg.setBackgroundResource(R.drawable.bg_avatar_xray)
            row.ivRowIcon.setImageResource(R.drawable.ic_account_xray)
            row.tvRowTypeBadge.text = "XRAY"
            openEditScreen = {
                startActivity(
                    Intent(this, XrayConfigActivity::class.java)
                        .putExtra(XrayConfigActivity.EXTRA_PROFILE_ID, profile.id)
                )
            }
            if (contentLocked) {
                row.tvRowTitle.text = lockedDisplayName
                row.tvRowSubtitle.text = "🔒 ${config.lockMode.label}"
                row.tvRowSubtitle.visibility = View.VISIBLE
            } else {
                val parsed = runCatching { XrayLinkParser.parse(config.xrayLink) }.getOrNull()
                val detail = if (parsed != null) "${parsed.address}:${parsed.port}" else "Link belum valid"
                row.tvRowTitle.text = if (hasCustomName) config.accountName else detail
                if (hasCustomName) {
                    row.tvRowSubtitle.text = detail
                    row.tvRowSubtitle.visibility = View.VISIBLE
                } else {
                    row.tvRowSubtitle.visibility = View.GONE
                }
            }
        } else {
            val modeName = when (config.modeIndex) {
                1 -> "SSH SSL"
                2 -> "SSH TLS PAYLOAD"
                3 -> "REMOTE PROXY"
                else -> "SSH"
            }
            row.ivRowAvatarBg.setBackgroundResource(R.drawable.bg_avatar_ssh)
            row.ivRowIcon.setImageResource(R.drawable.ic_account_ssh)
            row.tvRowTypeBadge.text = modeName
            openEditScreen = {
                startActivity(
                    Intent(this, SshConfigActivity::class.java)
                        .putExtra(SshConfigActivity.EXTRA_PROFILE_ID, profile.id)
                )
            }
            if (contentLocked) {
                row.tvRowTitle.text = lockedDisplayName
                row.tvRowSubtitle.text = "🔒 ${config.lockMode.label}"
                row.tvRowSubtitle.visibility = View.VISIBLE
            } else {
                val detail = "${config.host}:${config.port}"
                row.tvRowTitle.text = if (hasCustomName) config.accountName else detail
                if (hasCustomName) {
                    row.tvRowSubtitle.text = detail
                    row.tvRowSubtitle.visibility = View.VISIBLE
                } else {
                    row.tvRowSubtitle.visibility = View.GONE
                }
            }
        }

        if (isActive) {
            row.tvRowActiveBadge.visibility = View.VISIBLE
            row.tvRowSetActive.visibility = View.GONE
        } else {
            row.tvRowActiveBadge.visibility = View.GONE
            row.tvRowSetActive.visibility = View.VISIBLE
            row.tvRowSetActive.setOnClickListener {
                ProfileStore.setActiveId(this, profile.id)
                refreshAccountsList()
            }
        }

        // FITUR BARU (permintaan user): bagikan akun ini sendirian sebagai
        // kode teks singkat -- lihat onShareRowClicked & model/ConfigIO.kt.
        // (Bagikan TETAP bisa dipakai walau akun terkunci -- kunci cuma
        // menahan Edit/Hapus, bukan Bagikan, karena bagikan tidak mengubah
        // atau menghapus apa pun.)
        row.btnRowShare.setOnClickListener { onShareRowClicked(profile) }

        // FITUR BARU (permintaan user, "kunci akun"): gembok terbuka/tertutup
        // sesuai config.isLocked, ditap untuk toggle (lewat dialog
        // konfirmasi di onLockRowClicked). Edit & Hapus dipudarkan (alpha)
        // dan diblokir kalau akun ini sedang terkunci -- tetap CLICKABLE
        // (bukan disabled total) supaya user yang menekannya masih dapat
        // Toast penjelasan, bukan cuma diam tidak bereaksi.
        row.btnRowLock.setImageResource(
            if (config.isLocked) R.drawable.ic_lock_closed else R.drawable.ic_lock_open
        )
        row.btnRowLock.setOnClickListener {
            if (contentLocked) {
                // FITUR BARU (permintaan user, "kunci konfig saat ekspor"):
                // beda dari gembok isLocked biasa, lockMode akun hasil impor
                // TIDAK bisa dibuka dari UI sama sekali -- lihat dokumentasi
                // [SavedConfig.lockMode].
                Toast.makeText(
                    this,
                    "Konfigurasi akun ini dikunci oleh pembuatnya (${config.lockMode.label}) dan tidak bisa dibuka.",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                onLockRowClicked(row, profile)
            }
        }

        // FITUR BARU (permintaan user, "kunci konfig saat ekspor"): Edit
        // SELALU dipudarkan & diblokir total untuk akun contentLocked, apa
        // pun status isLocked biasa-nya -- beda dari isLocked yang cuma
        // memudarkan kalau memang lagi dikunci user sendiri.
        val lockedAlpha = if (config.isLocked || contentLocked) 0.35f else 1f
        row.btnRowEdit.alpha = lockedAlpha
        row.btnRowDelete.alpha = if (config.isLocked && !contentLocked) 0.35f else 1f

        row.btnRowEdit.setOnClickListener {
            when {
                contentLocked -> Toast.makeText(
                    this,
                    "Detail konfigurasi akun ini disembunyikan oleh pembuatnya, tidak bisa dilihat/diedit.",
                    Toast.LENGTH_LONG
                ).show()
                config.isLocked -> Toast.makeText(this, "Akun ini terkunci. Buka kunci dulu untuk mengedit.", Toast.LENGTH_SHORT).show()
                else -> openEditScreen()
            }
        }

        row.btnRowDelete.setOnClickListener {
            // Akun contentLocked SENGAJA tetap boleh dihapus (beda dari
            // Edit) -- supaya akun terkunci yang sudah tidak dipakai/salah
            // impor tidak nyangkut permanen di daftar, karena lockMode-nya
            // memang tidak bisa dibuka dari UI. Lihat dokumentasi
            // [SavedConfig.lockMode].
            if (config.isLocked && !contentLocked) {
                Toast.makeText(this, "Akun ini terkunci. Buka kunci dulu untuk menghapus.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // Pakai nama akun kalau ada (row.tvRowTitle sudah menampilkan itu
            // di atas), fallback ke host:port -- konsisten dengan tvRowTitle.
            val label = row.tvRowTitle.text
            newDialogBuilder()
                .setTitle("Hapus akun?")
                .setMessage("Akun \"$label\" akan dihapus permanen dari daftar.")
                .setNegativeButton("Batal", null)
                .setPositiveButton("Hapus") { _, _ ->
                    ProfileStore.delete(this, profile.id)
                    refreshAccountsList()
                }
                .show()
        }
    }

    /**
     * FITUR BARU (permintaan user, "kunci akun"): tampilkan dialog konfirmasi
     * sebelum benar-benar mengunci/membuka kunci akun -- supaya ketapak jari
     * tidak sengaja tidak langsung mengubah status penting ini tanpa sadar.
     * Lihat [SavedConfig.isLocked] & [ProfileStore.setLocked].
     */
    private fun onLockRowClicked(row: ItemAccountRowBinding, profile: SavedProfile) {
        val label = row.tvRowTitle.text
        val currentlyLocked = profile.config.isLocked
        val title = if (currentlyLocked) "Buka kunci akun?" else "Kunci akun?"
        val message = if (currentlyLocked)
            "Akun \"$label\" akan bisa diedit atau dihapus lagi seperti biasa."
        else
            "Akun \"$label\" tidak akan bisa diedit atau dihapus sampai kuncinya dibuka lagi."
        val positiveText = if (currentlyLocked) "Buka Kunci" else "Kunci"

        newDialogBuilder()
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton("Batal", null)
            .setPositiveButton(positiveText) { _, _ ->
                ProfileStore.setLocked(this, profile.id, !currentlyLocked)
                refreshAccountsList()
            }
            .show()
    }

    // --- Bagikan satu akun (permintaan user) ----------------------------

    /**
     * PERUBAHAN (permintaan user, "ekspor satu konfig ikutin ekspor semua
     * yang filenya biner"): ikon share per-baris akun sekarang tanya format
     * dulu -- SAMA persis strukturnya dengan [onExportAllClicked], cuma
     * scope-nya satu akun:
     *  0. "Simpan sebagai File (.spn)" -- pakai [onExportToFileClicked]
     *     APA ADANYA (tidak ditulis ulang sama sekali), cukup dikasih
     *     `listOf(profile)`. Fungsi itu memang sudah generik untuk daftar
     *     akun, jadi hasilnya file .spn biner terenkripsi (AES-GCM, seluruh
     *     envelope JSON disamarkan) yang ditulis ke Download/SuryaVPN/ --
     *     persis proses & format yang sama dengan "Ekspor Semua", isinya
     *     saja yang cuma satu akun. Bonus: [showExportFilenameDialog] sudah
     *     otomatis prefill nama file dari accountName akun ini karena
     *     listnya cuma berisi 1 item (lihat dokumentasi fungsi itu).
     *  1. "Kode Teks (Salin/Bagikan)" -- perilaku LAMA persis (lihat
     *     [shareRowAsTextCode]), dipindah ke fungsi terpisah tanpa
     *     perubahan logika sama sekali supaya kompatibel dengan penerima
     *     yang masih pakai tombol "Impor" kode "SVPN1:...".
     */
    private fun onShareRowClicked(profile: SavedProfile) {
        val options = arrayOf("Simpan sebagai File (.spn)", "Kode Teks (Salin/Bagikan)")
        newDialogBuilder()
            .setTitle("Ekspor akun ini")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> onExportToFileClicked(listOf(profile))
                    1 -> shareRowAsTextCode(profile)
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    /**
     * Tampilkan kode bagikan (format "SVPN1:...", lihat
     * [com.example.tunnelapp.model.buildShareCode]) untuk SATU akun di
     * dialog read-only, dengan tombol "Salin" (clipboard) & "Bagikan"
     * (Android share sheet biasa -- WhatsApp/Telegram/dst, sebagai teks
     * biasa, bukan lampiran file). Jalur 1 dari [onShareRowClicked].
     *
     * PERBAIKAN (permintaan user, "kode teks satu akun disamakan sama
     * ekspor semua yang clipboard-nya biner"): SEBELUMNYA di sini
     * dipakai [buildShareCode] -- format lama "SVPN1:..." yang cuma
     * mengenkripsi field yang dikunci SATU-SATU per akun (lihat
     * [applyLockMode]/[ConfigCipher.encrypt]), BEDA formatnya dari file
     * .spn/clipboard "Ekspor Semua" yang mengenkripsi SELURUH amplop JSON
     * jadi satu blob biner ([encryptWholeFileBytes]). Dua format itu
     * sama-sama valid & tetap bisa diimpor ([importConfigsFromText] baca
     * dua-duanya), tapi user minta konsisten -- SEKARANG kode teks satu
     * akun ini pakai jalur biner yang SAMA PERSIS dengan
     * [onExportToClipboardClicked] ("Ekspor Semua" -> Salin ke
     * Clipboard), cuma isinya [profile] tunggal (dibungkus list 1 item)
     * alih-alih semua akun:
     *   profilesToJson([profile], LOCK_ALL) -> encryptWholeFileBytes ->
     *   Base64 -- SELALU LOCK_ALL, TIDAK ada lagi dialog pilihan mode
     *   kunci (persis alasan yang sama dengan
     *   [onExportToClipboardClicked]: teks ini gampang ke-paste ke mana
     *   pun, jadi selalu disamarkan penuh apa pun mode kunci akun
     *   aslinya). [showLockModePicker] jadi tidak lagi dipakai di jalur
     *   ini -- tetap dipakai di jalur "Simpan sebagai File (.spn)"
     *   ([onExportToFileClicked]) yang memang meniru "Ekspor Semua" versi
     *   file, bukan versi clipboard.
     *
     * Hasilnya sudah otomatis kebaca balik lewat [importConfigsFromText]
     * TANPA perubahan apa pun di sisi impor.
     *
     * PERBAIKAN LANJUTAN (permintaan user, "double enkripsi"): fungsi ini
     * SEBELUMNYA membangun blob-nya sendiri secara manual di sini
     * (profilesToJson + encryptWholeFileBytes + Base64, TANPA prefix
     * apa pun) -- sekarang disatukan supaya manggil [buildShareCode]
     * (satu-satunya sumber logika kode-bagikan double-enkripsi, lihat
     * dokumentasi lengkapnya di model/ConfigIO.kt) dengan
     * [ConfigLockMode.LOCK_ALL] dipaksa persis seperti sebelumnya, supaya:
     *   - TIDAK ada lagi duplikasi logika enkripsi di dua tempat berbeda
     *     (gampang kelewat kalau salah satu di-update tapi yang lain
     *     tidak),
     *   - hasilnya sekarang punya prefix jelas "SVPN2:" (sebelumnya
     *     Base64 biner polos tanpa penanda), jadi kalau ada masalah impor
     *     gampang dikenali formatnya cuma dengan lihat teksnya,
     *   - perilaku enkripsi & lock mode TIDAK berubah sama sekali (tetap
     *     dua lapis: field terkunci LOCK_ALL di lapis-1, seluruh JSON di
     *     lapis-2).
     */
    private fun shareRowAsTextCode(profile: SavedProfile) {
        lifecycleScope.launch {
            val code = withContext(Dispatchers.Default) {
                buildShareCode(profile.config, ConfigLockMode.LOCK_ALL)
            }
            showShareCodeDialog(code)
        }
    }

    /**
     * PERBAIKAN (permintaan user, "klik Lanjut kok agak nge-freeze"):
     * [buildShareCode] internalnya menjalankan PBKDF2 150.000 iterasi +
     * AES-GCM (lihat [com.example.tunnelapp.model.ConfigCipher]) untuk
     * SETIAP field yang dikunci -- kerjaan CPU-bound yang bisa makan
     * ratusan milidetik. Sebelumnya ini dijalankan LANGSUNG di listener
     * tombol "Lanjut"/klik baris (main/UI thread), jadi selama proses itu
     * seluruh UI (termasuk animasi ripple tombolnya sendiri) berhenti total
     * -> terasa nge-freeze/patah-patah sesaat.
     *
     * Di sini kerjaan beratnya dipindah ke [Dispatchers.Default] (thread
     * pool khusus kerjaan CPU-bound) lewat [lifecycleScope], baru hasilnya
     * dikembalikan ke [onResult] di main thread lagi buat nampilin dialog.
     * Kalau Activity sudah tidak hidup lagi (mis. user keluar duluan
     * sebelum hitungannya selesai), [lifecycleScope] otomatis membatalkan
     * coroutine ini -- [onResult] tidak pernah dipanggil, tidak ada crash.
     */
    private fun buildShareCodeAsync(
        config: SavedConfig,
        exportLockMode: ConfigLockMode = config.lockMode,
        onResult: (String) -> Unit
    ) {
        lifecycleScope.launch {
            val code = withContext(Dispatchers.Default) { buildShareCode(config, exportLockMode) }
            onResult(code)
        }
    }

    private fun showShareCodeDialog(code: String) {
        val input = EditText(this).apply {
            setText(code)
            isFocusable = false
            isFocusableInTouchMode = false
            setTextIsSelectable(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setPadding(48, 32, 48, 32)
        }

        newDialogBuilder()
            .setTitle("Bagikan akun")
            .setMessage("Salin atau bagikan kode di bawah. Siapa pun yang menempelkannya lewat tombol \"Impor\" di app ini akan mendapat akun yang sama persis.")
            .setView(dialogInputLayout(input))
            .setNegativeButton("Tutup", null)
            .setNeutralButton("Salin") { _, _ ->
                copyToClipboard("Kode akun SuryaVPN", code)
                Toast.makeText(this, "Kode disalin ke clipboard", Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton("Bagikan") { _, _ ->
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, code)
                }
                startActivity(Intent.createChooser(send, "Bagikan akun via"))
            }
            .show()
    }

    // --- Kunci konfig saat ekspor (permintaan user, mirip HTTP Custom) --

    /**
     * Dialog pilihan mode kunci (lihat [ConfigLockMode]), dipakai SEBELUM
     * membangun kode bagikan satu akun ([onShareRowClicked]) atau file
     * ekspor semua akun ([onExportAllClicked]) -- cuma ditawarkan untuk
     * akun yang BELUM terkunci sama sekali. [onChosen] dipanggil dengan
     * mode yang dipilih user; dialog dibatalkan begitu saja kalau user
     * menekan "Batal" (tidak memanggil [onChosen] sama sekali).
     */
    private fun showLockModePicker(onChosen: (ConfigLockMode) -> Unit) {
        val modes = ConfigLockMode.entries.toTypedArray()
        val labels = modes.map { it.label }.toTypedArray()
        var selected = 0

        newDialogBuilder()
            .setTitle("Kunci konfigurasi?")
            .setSingleChoiceItems(labels, selected) { _, which -> selected = which }
            .setNegativeButton("Batal", null)
            .setPositiveButton("Lanjut") { _, _ -> onChosen(modes[selected]) }
            .show()
    }

    // --- Impor & Ekspor Semua (permintaan user) -------------------------

    /**
     * Dialog impor: satu kotak teks besar tempat user bisa TEMPEL kode
     * bagikan satu akun ("SVPN1:...") ATAU seluruh isi file JSON hasil
     * "Ekspor Semua" (banyak akun sekaligus) -- keduanya diterima sama
     * lewat [importConfigsFromText], lihat dokumentasinya untuk detail
     * format. Tombol "Pilih File" jadi jalan pintas alternatif kalau user
     * lebih suka pilih file .json langsung lewat SAF daripada copy-paste
     * manual.
     */
    private fun onImportClicked() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 4
            setPadding(48, 32, 48, 32)
        }
        // Dibungkus ScrollView supaya tetap nyaman diketik/ditempel kalau
        // isinya panjang (JSON hasil ekspor banyak akun bisa lumayan panjang).
        val container = ScrollView(this).apply {
            addView(dialogInputLayout(input, "Tempel kode akun (SVPN2:...) atau isi file JSON hasil ekspor di sini"))
        }

        newDialogBuilder()
            .setTitle("Impor konfigurasi")
            .setView(container)
            .setNegativeButton("Batal", null)
            .setNeutralButton("Pilih File") { _, _ ->
                importFileLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
            }
            .setPositiveButton("Impor") { _, _ ->
                performImport(input.text?.toString().orEmpty())
            }
            .show()
    }

    /**
     * Parse [rawText] lewat [importConfigsFromText], lalu simpan tiap
     * akun yang berhasil dibaca sebagai akun BARU di [ProfileStore]
     * (selalu upsert dengan id=null -> tidak pernah menimpa akun yang
     * sudah ada, walau host/nama-nya kebetulan sama -- user bisa hapus
     * manual sendiri kalau ternyata duplikat). Tampilkan ringkasan
     * jumlah berhasil, dan jumlah entry yang dilewati kalau ada.
     */
    private fun performImport(rawText: String) {
        // PERBAIKAN (permintaan user, "klik Lanjut kok agak nge-freeze"):
        // [importConfigsFromText] bisa lewat [decryptWholeFileBytes] (jalur
        // clipboard/file .spn terenkripsi) yang sama-sama CPU-bound (PBKDF2)
        // -- dipindah ke Dispatchers.Default juga supaya tombol "Impor"
        // tidak macet sesaat kalau isinya kode terenkripsi/panjang.
        lifecycleScope.launch {
            val configs = withContext(Dispatchers.Default) { importConfigsFromText(rawText) }
            if (configs.isEmpty()) {
                Toast.makeText(this@ConfigActivity, "Tidak ada konfigurasi valid yang ditemukan", Toast.LENGTH_LONG).show()
                return@launch
            }
            configs.forEach { ProfileStore.upsert(this@ConfigActivity, id = null, config = it) }
            refreshAccountsList()
            Toast.makeText(this@ConfigActivity, "${configs.size} akun berhasil diimpor", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Ekspor SEMUA akun tersimpan -- BARU (permintaan user, "tambah fungsi
     * ekspor salin clipboard"): sekarang ada DUA jalur, ditanya dulu lewat
     * dialog pilihan di sini:
     *  1. "Simpan sebagai File (.spn)" -- alur lama, lihat
     *     [onExportToFileClicked]: tanya mode kunci dulu lewat
     *     [showLockModePicker], lalu nama filenya lewat
     *     [showExportFilenameDialog], baru ditulis ke
     *     Download/SuryaVPN/ lewat [proceedWithExport].
     *  2. "Salin ke Clipboard" -- jalur baru, lihat
     *     [onExportToClipboardClicked]: TIDAK menawarkan pilihan mode
     *     kunci sama sekali, LANGSUNG pakai [ConfigLockMode.LOCK_ALL]
     *     (permintaan user, "hasil salin clipboard itu langsung pakai
     *     logika lock all") supaya teks yang gampang ke-paste ke mana pun
     *     itu selalu dalam kondisi paling aman/tersamar.
     */
    private fun onExportAllClicked() {
        val profiles = ProfileStore.getAll(this)
        if (profiles.isEmpty()) {
            Toast.makeText(this, "Belum ada akun untuk diekspor", Toast.LENGTH_SHORT).show()
            return
        }
        val options = arrayOf("Simpan sebagai File (.spn)", "Salin ke Clipboard")
        newDialogBuilder()
            .setTitle("Ekspor Semua Akun")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> onExportToFileClicked(profiles)
                    1 -> onExportToClipboardClicked(profiles)
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    /**
     * Jalur 1 dari [onExportAllClicked]: alur lama -- tanya mode kunci dulu
     * (cuma berlaku ke akun yang BELUM terkunci, lihat [profilesToJson]),
     * lalu nama filenya, baru ditulis sebagai file .spn biner terenkripsi
     * ke Download/SuryaVPN/.
     */
    private fun onExportToFileClicked(profiles: List<SavedProfile>) {
        showLockModePicker { chosenMode ->
            showExportFilenameDialog(profiles) { filename ->
                // PERBAIKAN (permintaan user, "klik Lanjut kok agak nge-
                // freeze"): lihat dokumentasi lengkap di [buildShareCodeAsync]
                // -- masalah & solusinya sama persis di sini, cuma untuk
                // BANYAK akun sekaligus (profilesToJson) jadi potensi
                // freeze-nya malah lebih terasa lagi kalau tetap dijalankan
                // di main thread.
                lifecycleScope.launch {
                    val bytes = withContext(Dispatchers.Default) {
                        val json = profilesToJson(profiles, chosenMode)
                        // FITUR BARU (permintaan user, "sekalian ganti biner"): seluruh
                        // envelope JSON (bukan cuma field yang dikunci per-akun)
                        // dienkripsi jadi satu blob biner di sini, SEBELUM ditulis ke
                        // file -- lihat dokumentasi [encryptWholeFileBytes].
                        encryptWholeFileBytes(json)
                    }
                    proceedWithExport(bytes, filename)
                }
            }
        }
    }

    /**
     * Jalur 2 dari [onExportAllClicked] -- BARU (permintaan user, "ekspor
     * salin clipboard, hasilnya langsung pakai logika lock all"). Beda dari
     * [onExportToFileClicked]:
     *  - TIDAK ada dialog pilihan mode kunci -- SELALU dienkripsi penuh
     *    pakai [ConfigLockMode.LOCK_ALL] (semua field teknis disamarkan,
     *    cuma nama & jenis akun yang tetap polos), apa pun mode kunci akun
     *    aslinya (beda dari [profilesToJson] biasa yang menghormati
     *    lockMode akun yang sudah terkunci -- di sini SEMUA dipaksa
     *    LOCK_ALL demi konsistensi & keamanan, karena hasilnya gampang
     *    ke-paste ke mana pun lewat clipboard).
     *  - TIDAK ditulis ke file sama sekali -- byte hasil enkripsi
     *    di-encode Base64 jadi teks biasa, lalu disalin langsung ke
     *    clipboard (lihat [copyToClipboard]), siap ditempel ke chat/pesan.
     */
    private fun onExportToClipboardClicked(profiles: List<SavedProfile>) {
        // PERBAIKAN (permintaan user, "klik Lanjut kok agak nge-freeze"):
        // sama seperti [onExportToFileClicked]/[buildShareCodeAsync] --
        // profilesToJson + encryptWholeFileBytes CPU-bound, dipindah ke
        // Dispatchers.Default supaya tombol menu "Salin ke Clipboard" tidak
        // bikin UI macet sesaat.
        lifecycleScope.launch {
            val text = withContext(Dispatchers.Default) {
                val json = profilesToJson(profiles, ConfigLockMode.LOCK_ALL)
                val bytes = encryptWholeFileBytes(json)
                Base64.encodeToString(bytes, Base64.NO_WRAP)
            }
            copyToClipboard("Konfigurasi SuryaVPN", text)
            Toast.makeText(
                this@ConfigActivity,
                "Konfigurasi (terkunci penuh) disalin ke clipboard",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /**
     * Dialog "Nama file ekspor" -- ditampilkan SETELAH user memilih mode
     * kunci di [showLockModePicker], SEBELUM file benar-benar ditulis lewat
     * [proceedWithExport]. Mengganti perilaku lama yang langsung menulis
     * file dengan nama timestamp acak ("suryavpn-config-yyyyMMdd-HHmmss.spn")
     * begitu "Lanjut" ditekan di dialog kunci, tanpa kesempatan user
     * mengubah namanya sama sekali.
     *
     * Aturan prefill kolom nama:
     *  - Kalau [profiles] cuma berisi SATU akun & akun itu sudah punya nama
     *    custom (accountName tidak kosong) -> nama itu yang disarankan
     *    duluan, user tinggal konfirmasi atau ubah kalau mau.
     *  - Selain itu (ekspor banyak akun sekaligus, ATAU satu-satunya akun
     *    itu belum punya nama) -> kolom dikosongkan sama sekali, WAJIB
     *    diisi manual oleh user sebelum bisa lanjut menyimpan (lihat
     *    validasi kosong di bawah).
     *
     * Ekstensi ".spn" SELALU ditambahkan otomatis di akhir nama yang
     * dimasukkan user -- tidak perlu (dan tidak boleh) diketik manual.
     * Karakter yang tidak valid untuk nama file (mis. "/", "\", ":", "*",
     * "?", '"', "<", ">", "|") dibuang lewat [sanitizeFilename] sebelum
     * nama akhirnya dipakai, supaya tetap aman ditulis di semua versi
     * Android.
     */
    private fun showExportFilenameDialog(profiles: List<SavedProfile>, onConfirmed: (String) -> Unit) {
        val suggestedName = if (profiles.size == 1) profiles[0].config.accountName.trim() else ""

        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(48, 32, 48, 32)
            setText(suggestedName)
            setSelection(suggestedName.length)
        }

        val inputLayout = dialogInputLayout(input, "Contoh: konfigurasi-kantor")

        val dialog = newDialogBuilder()
            .setTitle("Nama file ekspor")
            .setMessage("File akan disimpan sebagai \"<nama>.spn\" di folder Download/SuryaVPN/.")
            .setView(inputLayout)
            .setNegativeButton("Batal", null)
            // Positive listener dipasang manual lewat setOnShowListener di
            // bawah (bukan langsung di sini) supaya dialog TIDAK otomatis
            // tertutup kalau nama yang diketik ternyata kosong/tidak valid
            // -- user harus perbaiki dulu, bukan diam-diam gagal atau
            // dialog hilang begitu saja.
            .setPositiveButton("Simpan", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val clean = sanitizeFilename(input.text?.toString().orEmpty())
                if (clean.isBlank()) {
                    // Pakai error milik TextInputLayout (garis & teks merah di
                    // bawah kotak, konsisten dengan Field.Outlined di form
                    // lain) alih-alih popup bubble bawaan EditText.setError.
                    inputLayout.error = "Nama file tidak boleh kosong"
                    return@setOnClickListener
                }
                inputLayout.error = null
                dialog.dismiss()
                onConfirmed("$clean.spn")
            }
        }
        dialog.show()
    }

    /**
     * Buang karakter yang tidak diizinkan sebagai nama file (di Android
     * maupun filesystem pada umumnya: "/ \ : * ? " < > |"), rapikan spasi
     * berlebih jadi satu spasi, lalu potong spasi di ujung-ujungnya. Dipakai
     * di [showExportFilenameDialog] sebelum nama yang diketik user benar-
     * benar dipakai sebagai nama file .spn.
     */
    private fun sanitizeFilename(raw: String): String {
        return raw.trim()
            .replace(Regex("[/\\\\:*?\"<>|]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * Langkah terakhir ekspor, dipanggil begitu nama file sudah dikonfirmasi
     * lewat [showExportFilenameDialog] -- persis perilaku
     * "bikin otomatis dengan izin user" yang sudah ada sebelumnya: Android
     * 10+ (API 29+) lewat MediaStore TIDAK butuh izin runtime apa pun
     * (scoped storage) -> langsung tulis. Android 9 (API 28) masih perlu
     * WRITE_EXTERNAL_STORAGE -- minta izin dulu kalau belum ada, baru tulis
     * di callback [storagePermissionLauncher].
     */
    private fun proceedWithExport(bytes: ByteArray, filename: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            writeExportAutomatically(bytes, filename)
        } else {
            pendingExportBytes = bytes
            pendingExportFilename = filename
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    private fun copyToClipboard(label: String, text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    /**
     * Sama seperti [DashboardActivity.setupBottomNav] & [SettingsActivity],
     * tapi tab yang aktif di layar ini "Konfigurasi". Tap "Dashboard" cukup
     * finish() (Dashboard selalu ada di bawah ConfigActivity di back stack).
     * Tap "Pengaturan" pindah ke sibling lain: start SettingsActivity lalu
     * finish() diri sendiri, supaya back stack tidak numpuk.
     */
    private fun setupBottomNav() {
        binding.bottomNav.selectedItemId = R.id.nav_config
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_config -> true
                R.id.nav_dashboard -> {
                    finish()
                    true
                }
                R.id.nav_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    finish()
                    true
                }
                R.id.nav_tools -> {
                    startActivity(Intent(this, ToolsActivity::class.java))
                    finish()
                    true
                }
                else -> false
            }
        }
    }
}
