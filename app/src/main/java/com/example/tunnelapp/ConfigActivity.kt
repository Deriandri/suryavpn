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
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.tunnelapp.databinding.ActivityConfigBinding
import com.example.tunnelapp.databinding.ItemAccountRowBinding
import com.example.tunnelapp.model.ConfigLockMode
import com.example.tunnelapp.model.ProfileStore
import com.example.tunnelapp.model.SavedProfile
import com.example.tunnelapp.model.buildShareCode
import com.example.tunnelapp.model.decryptWholeFileBytes
import com.example.tunnelapp.model.encryptWholeFileBytes
import com.example.tunnelapp.model.importConfigsFromText
import com.example.tunnelapp.model.profilesToJson
import com.example.tunnelapp.tunnel.XrayLinkParser
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

        binding.btnImportConfig.setOnClickListener { onImportClicked() }
        binding.btnExportAllConfig.setOnClickListener { onExportAllClicked() }

        setupBottomNav()
    }

    override fun onResume() {
        super.onResume()
        // Daftar akun bisa saja baru diubah (ditambah/diedit/dihapus/pindah
        // aktif) di SshConfigActivity/XrayConfigActivity -- refresh tiap
        // kali layar ini kembali ditampilkan.
        refreshAccountsList()
    }

    private fun refreshAccountsList() {
        val profiles = ProfileStore.getAll(this)
        val activeId = ProfileStore.getActiveId(this)

        binding.llAccountsContainer.removeAllViews()

        if (profiles.isEmpty()) {
            binding.llAccountEmpty.visibility = View.VISIBLE
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
        // hasil impor dengan lockMode != NONE (lihat [ConfigLockMode] &
        // model/ConfigLock.kt) "terkunci total" -- cuma NAMA-nya yang boleh
        // ditampilkan, detail host:port/payload/proxy/dst TIDAK PERNAH
        // ditampilkan sama sekali di baris ini, apa pun mode kunci
        // spesifiknya (yang membedakan cuma field mana yang disamarkan di
        // DALAM file/kode hasil ekspornya, bukan tampilan sesudah diimpor).
        val contentLocked = config.lockMode != ConfigLockMode.NONE
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
            AlertDialog.Builder(this)
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

        AlertDialog.Builder(this)
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
     * Tampilkan kode bagikan (format "SVPN1:...", lihat
     * [com.example.tunnelapp.model.buildShareCode]) untuk SATU akun di
     * dialog read-only, dengan tombol "Salin" (clipboard) & "Bagikan"
     * (Android share sheet biasa -- WhatsApp/Telegram/dst, sebagai teks
     * biasa, bukan lampiran file).
     */
    private fun onShareRowClicked(profile: SavedProfile) {
        // FITUR BARU (permintaan user, "kunci konfig saat ekspor"): akun
        // yang BELUM terkunci ditanya dulu mau pakai mode kunci apa (lihat
        // [showLockModePicker]). Akun yang SUDAH terkunci (hasil impor dari
        // orang lain) langsung dibagikan ulang dengan mode kunci yang SAMA
        // apa adanya -- penerima tidak berhak melonggarkan kunci akun yang
        // bukan miliknya, lihat [buildShareCode].
        if (profile.config.lockMode != ConfigLockMode.NONE) {
            showShareCodeDialog(buildShareCode(profile.config))
            return
        }
        showLockModePicker { chosenMode ->
            showShareCodeDialog(buildShareCode(profile.config, chosenMode))
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

        AlertDialog.Builder(this)
            .setTitle("Bagikan akun")
            .setMessage("Salin atau bagikan kode di bawah. Siapa pun yang menempelkannya lewat tombol \"Impor\" di app ini akan mendapat akun yang sama persis.")
            .setView(input)
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

        AlertDialog.Builder(this)
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
            hint = "Tempel kode akun (SVPN1:...) atau isi file JSON hasil ekspor di sini"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 4
            setPadding(48, 32, 48, 32)
        }
        // Dibungkus ScrollView supaya tetap nyaman diketik/ditempel kalau
        // isinya panjang (JSON hasil ekspor banyak akun bisa lumayan panjang).
        val container = ScrollView(this).apply { addView(input) }

        AlertDialog.Builder(this)
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
        val configs = importConfigsFromText(rawText)
        if (configs.isEmpty()) {
            Toast.makeText(this, "Tidak ada konfigurasi valid yang ditemukan", Toast.LENGTH_LONG).show()
            return
        }
        configs.forEach { ProfileStore.upsert(this, id = null, config = it) }
        refreshAccountsList()
        Toast.makeText(this, "${configs.size} akun berhasil diimpor", Toast.LENGTH_SHORT).show()
    }

    /**
     * Ekspor SEMUA akun tersimpan jadi satu file .spn biner terenkripsi
     * (lihat [profilesToJson] & [encryptWholeFileBytes]). FITUR BARU
     * (permintaan user, "bikin otomatis dengan izin user"): TIDAK ada lagi
     * dialog SAF (file-picker) -- ditulis otomatis ke Download/SuryaVPN/
     * lewat [writeExportAutomatically], minta izin WRITE_EXTERNAL_STORAGE
     * dulu kalau perlu (cuma Android 9, lihat dokumentasi
     * [storagePermissionLauncher]). Nama file disisipi timestamp supaya
     * beberapa kali ekspor tidak saling timpa.
     */
    private fun onExportAllClicked() {
        val profiles = ProfileStore.getAll(this)
        if (profiles.isEmpty()) {
            Toast.makeText(this, "Belum ada akun untuk diekspor", Toast.LENGTH_SHORT).show()
            return
        }
        // FITUR BARU (permintaan user, "kunci konfig saat ekspor"): mode
        // yang dipilih di sini cuma diterapkan ke akun yang BELUM terkunci
        // -- akun yang sudah punya lockMode sendiri (hasil impor dari orang
        // lain) tetap dipertahankan apa adanya, lihat [profilesToJson].
        showLockModePicker { chosenMode ->
            val json = profilesToJson(profiles, chosenMode)
            // FITUR BARU (permintaan user, "sekalian ganti biner"): seluruh
            // envelope JSON (bukan cuma field yang dikunci per-akun)
            // dienkripsi jadi satu blob biner di sini, SEBELUM ditulis ke
            // file -- lihat dokumentasi [encryptWholeFileBytes].
            val bytes = encryptWholeFileBytes(json)
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val filename = "suryavpn-config-$stamp.spn"

            // FITUR BARU (permintaan user, "bikin otomatis dengan izin
            // user"): Android 10+ (API 29+) lewat MediaStore TIDAK butuh
            // izin runtime apa pun (scoped storage) -> langsung tulis.
            // Android 9 (API 28) masih perlu WRITE_EXTERNAL_STORAGE --
            // minta izin dulu kalau belum ada, baru tulis di callback
            // [storagePermissionLauncher].
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
