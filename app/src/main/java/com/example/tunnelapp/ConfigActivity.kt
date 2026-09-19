package com.example.tunnelapp

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.text.format.DateFormat
import android.util.Base64
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
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
import com.example.tunnelapp.model.toServerConfigOrNull
import com.example.tunnelapp.tunnel.MyVpnService
import com.example.tunnelapp.tunnel.PingResult
import com.example.tunnelapp.tunnel.PingUtil
import com.example.tunnelapp.tunnel.StatusBus
import com.example.tunnelapp.tunnel.XrayLinkParser
import java.io.File
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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

    private companion object {
        // Batas panjang kolom "Nama ekspor" di dialog "Detail Ekspor" --
        // lihat dokumentasi [sanitizeExportName]. Angka ini cukup untuk
        // label akun normal (jauh di bawah lebar dialog satu baris) tapi
        // tetap memotong ASCII-art/markup HTML dekoratif dari provider
        // Cloud Config yang bisa berupa ratusan karakter.
        private const val MAX_EXPORT_NAME_LENGTH = 60
    }

    private lateinit var binding: ActivityConfigBinding

    // FITUR BARU (permintaan user, "kunci edit config saat tunnel
    // terhubung"): diisi lewat collector StatusBus.state di onCreate,
    // dipakai bindAccountRow() buat memblokir tombol Edit KHUSUS baris
    // akun yang SEDANG AKTIF (isActive == true) selagi tunnel-nya benar-
    // benar terhubung -- akun lain yang tidak dipakai tunnel tetap bebas
    // diedit seperti biasa, karena tidak memengaruhi tunnel yang sedang
    // jalan. Definisi "terhubung" dipusatkan di StatusBus.isConnected()
    // supaya sama persis dengan yang menentukan tombol Connect/Disconnect
    // di Dashboard.
    private var vpnConnected = false

    // FITUR BARU (permintaan user, "cek ping/latency server sebelum &
    // sesudah connect, urutkan dari yang paling cepat"): hasil TCP ping
    // terakhir per akun (key = SavedProfile.id), diisi lewat
    // [onTestPingAllClicked]. Tetap tersimpan di memori Activity ini
    // (BUKAN persisted ke disk -- angka latency cuma relevan sesaat, tidak
    // ada gunanya dipertahankan lintas sesi app) sehingga tetap bisa dipakai
    // untuk mengurutkan ulang daftar tiap kali [refreshAccountsList]
    // dipanggil lagi (mis. akun baru ditambah, pencarian diketik), sampai
    // Activity ini di-destroy atau tombol ditekan ulang.
    private val pingResults = mutableMapOf<String, PingResult>()

    // Dipakai [onTestPingAllClicked] mencegah tes ganda tertumpuk kalau
    // tombolnya sempat ter-tap dua kali sebelum tes pertama selesai.
    private var pingTestRunning = false

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
     *
     * PERBAIKAN BUG TAMPILAN (kotak outline hilang, jatuh balik ke garis
     * bawah polos -- terlihat di dialog "Detail Ekspor"): percobaan
     * SEBELUMNYA membungkus context dengan [ContextThemeWrapper] memakai
     * [R.style.Field_Outlined] LANGSUNG sebagai tema ternyata tetap tidak
     * cukup -- itu hanya "mengecat" atribut-atribut custom milik
     * Field.Outlined (boxCornerRadius, hintTextColor, dst) ke tema,
     * padahal TextInputLayout(context) TANPA AttributeSet selalu mencari
     * style widget-nya lewat atribut tema `?attr/textInputStyle`, bukan
     * dari atribut-atribut itu. Karena tema app ini tidak pernah menimpa
     * `textInputStyle`, hasilnya tetap jatuh ke default Material3 (garis
     * bawah polos). Sekarang dibungkus dengan [R.style.ThemeOverlay_TunnelApp_OutlinedField]
     * yang isinya SATU baris: menimpa `textInputStyle` agar menunjuk ke
     * Field.Outlined -- ini yang benar-benar dibaca oleh constructor
     * TextInputLayout, sehingga hasilnya kotak outline bulat + garis ungu
     * brand yang konsisten, sama seperti field Host/Port/dst di
     * SshConfigActivity.
     *
     * [placeholderText] (opsional): teks contoh abu-abu yang tampil DI
     * DALAM kotak saat field masih kosong, terpisah dari label mengambang
     * (dipakai di kolom "Catatan" pada [showExportDetailsDialog] untuk
     * mencontohkan format HTML, misal "<b>Akun kantor</b>, dipakai untuk
     * tim support...").
     */
    private fun dialogInputLayout(
        editText: EditText,
        hintText: String? = null,
        placeholderText: String? = null,
        // BARU (redesign dialog Impor, permintaan user): dialog Impor
        // butuh gaya field pill/kaca sendiri (Field.Pill.Import), beda
        // dari Field.Outlined yang dipakai semua dialog lain -- ditambah
        // sebagai parameter opsional di sini (default TETAP
        // OutlinedField lama) supaya semua pemanggil lain (Bagikan akun,
        // Nama file ekspor, dst) tidak perlu diubah sama sekali.
        styleOverlay: Int = R.style.ThemeOverlay_TunnelApp_OutlinedField
    ): TextInputLayout {
        val styledContext = ContextThemeWrapper(this, styleOverlay)
        return TextInputLayout(styledContext).apply {
            id = View.generateViewId()
            hintText?.let { hint = it }
            placeholderText?.let { this.placeholderText = it }
            setPadding(24, 8, 24, 0)
            addView(
                editText,
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
    }

    /**
     * PERBAIKAN BUG (dialog "Detail Ekspor" meluber jadi blok teks HTML
     * raksasa yang tidak terpotong -- lihat dokumentasi [showExportDetailsDialog]):
     * mengandalkan `isSingleLine`/`maxLines`/`ellipsize` SAJA di [EditText]
     * ternyata tidak cukup untuk menjamin tampilan tetap satu baris rapi
     * begitu [defaultName] diisi provider dengan ASCII-art/markup HTML
     * dekoratif ber-newline (kasus akun hasil sinkron "Cloud Config").
     * Sekarang [defaultName] dibersihkan DI LEVEL DATA sebelum sampai ke
     * EditText sama sekali, supaya perilakunya tidak lagi bergantung pada
     * kuirk rendering TextView:
     *  1. buang semua tag HTML (`<...>`),
     *  2. ratakan semua whitespace/baris baru jadi satu spasi,
     *  3. potong ke [MAX_EXPORT_NAME_LENGTH] karakter + "…" kalau lebih
     *     panjang dari itu.
     * Hasilnya selalu satu baris pendek yang aman ditampilkan apa pun
     * konten aslinya.
     */
    private fun sanitizeExportName(raw: String): String {
        val noTags = raw.replace(Regex("<[^>]*>"), " ")
        val collapsed = noTags.replace(Regex("\\s+"), " ").trim()
        return if (collapsed.length > MAX_EXPORT_NAME_LENGTH) {
            collapsed.take(MAX_EXPORT_NAME_LENGTH).trimEnd() + "…"
        } else {
            collapsed
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

        // FITUR BARU (permintaan user, "cek ping/latency server sebelum &
        // sesudah connect"): lihat onTestPingAllClicked().
        binding.btnTestPingAll.setOnClickListener { onTestPingAllClicked() }

        setupAccountSearch()
        setupBottomNav()

        // FITUR BARU (permintaan user, "kunci edit config saat tunnel
        // terhubung"): dengarkan StatusBus.state SELAMA layar ini terlihat
        // (repeatOnLifecycle STARTED, sama pola dengan DashboardMainFragment)
        // supaya kalau user pindah ke Dashboard buat Connect lalu balik lagi
        // ke tab Konfigurasi ini SEBELUM proses connect selesai, begitu
        // status berubah jadi "aktif" tombol Edit baris yang aktif langsung
        // ikut terkunci tanpa perlu keluar-masuk tab dulu.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                StatusBus.state.collect { status ->
                    val nowConnected = StatusBus.isConnected(status)
                    if (nowConnected != vpnConnected) {
                        vpnConnected = nowConnected
                        refreshAccountsList()
                    }
                }
            }
        }
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

    /**
     * Tentukan host:port sasaran TCP ping untuk satu akun tersimpan --
     * MENIRU logika [com.example.tunnelapp.model.ServerConfig.usesProxy]/
     * [com.example.tunnelapp.model.ServerConfig.usesXray] lewat
     * [toServerConfigOrNull] (bukan menduplikasi manual di sini) supaya
     * titik yang diuji BENAR-BENAR sama dengan titik TCP connect PERTAMA
     * yang dipakai [com.example.tunnelapp.tunnel.ConnectRelay]/
     * [com.example.tunnelapp.tunnel.XrayTunnelManager] saat tombol Connect
     * sungguhan ditekan -- kalau mode-nya pakai proxy/CDN (proxyHost
     * terisi), yang diuji proxyHost:proxyPort (itu yang pertama kali
     * di-TCP-connect), BUKAN host SSH asli di baliknya yang baru dihubungi
     * SETELAH proxy/TLS/payload beres.
     *
     * Null (akun dilewati, tidak ikut dites) kalau:
     *  - akun terkunci total ([ConfigLockMode.LOCK_ALL]) -- host-nya memang
     *    sengaja disembunyikan dari UI (lihat bindAccountRow), jangan ikut
     *    "dibocorkan" lewat percobaan koneksi diam-diam.
     *  - konfigurasinya belum valid (host/username kosong, link Xray
     *    kosong/tidak bisa di-parse, dst) -- sama seperti kondisi yang
     *    bikin tombol Connect sungguhan gagal duluan sebelum sempat konek.
     */
    private fun resolvePingTarget(config: SavedConfig): Pair<String, Int>? {
        if (config.lockMode == ConfigLockMode.LOCK_ALL) return null
        val serverConfig = config.toServerConfigOrNull() ?: return null
        return when {
            serverConfig.usesXray() -> {
                val parsed = runCatching { XrayLinkParser.parse(serverConfig.xrayLink.orEmpty()) }
                    .getOrNull() ?: return null
                parsed.address to parsed.port
            }
            serverConfig.usesProxy() && !serverConfig.proxyHost.isNullOrBlank() ->
                serverConfig.proxyHost!! to (serverConfig.proxyPort ?: serverConfig.port)
            else -> serverConfig.host to serverConfig.port
        }
    }

    /**
     * FITUR BARU (permintaan user, "cek ping/latency server -- sebelum
     * connect dan sesudah connect tes semua server tersimpan dan urutkan
     * dari yang paling cepat"): uji TCP ping ke SEMUA akun tersimpan
     * ([ProfileStore.getAll], bukan cuma yang lolos filter pencarian) SEKALI
     * JALAN secara paralel (bukan satu-satu berurutan -- kalau ada banyak
     * akun, menunggu tiap timeout 4 detik bergiliran bakal terasa lama
     * sekali), lalu [refreshAccountsList] otomatis mengurutkan ulang
     * daftarnya dari hasil PALING CEPAT lewat [pingResults].
     *
     * Aman ditekan kapan pun -- SEBELUM tunnel mana pun terhubung (socket
     * biasa memang langsung ke internet asli) MAUPUN SAAT salah satu akun
     * sedang aktif (socket tes di-protect() lewat
     * [MyVpnService.protectSocketIfRunning] supaya tidak ikut tertarik masuk
     * TUN milik tunnel yang sedang jalan -- lihat dokumentasi lengkap di
     * sana & di [PingUtil.tcpPing]).
     */
    private fun onTestPingAllClicked() {
        if (pingTestRunning) return

        val targets = ProfileStore.getAll(this).mapNotNull { profile ->
            resolvePingTarget(profile.config)?.let { (host, port) -> Triple(profile.id, host, port) }
        }
        if (targets.isEmpty()) {
            Toast.makeText(
                this,
                "Tidak ada server yang bisa diuji (belum ada akun tersimpan, atau semuanya terkunci/belum valid).",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        pingTestRunning = true
        binding.btnTestPingAll.isEnabled = false
        binding.btnTestPingAll.text = "Menguji ${targets.size} server..."

        lifecycleScope.launch {
            val results = withContext(Dispatchers.IO) {
                targets.map { (id, host, port) ->
                    async {
                        id to PingUtil.tcpPing(
                            host = host,
                            port = port,
                            protect = { socket -> MyVpnService.protectSocketIfRunning(socket) }
                        )
                    }
                }.map { it.await() }
            }
            results.forEach { (id, result) -> pingResults[id] = result }

            pingTestRunning = false
            binding.btnTestPingAll.isEnabled = true
            binding.btnTestPingAll.text = "Tes Ping Semua Server"

            // Refresh SEKALI di akhir (bukan progresif per-server) -- daftar
            // langsung terurut rapi dari yang paling cepat dalam satu
            // lompatan, tidak "loncat-loncat" posisinya tiap satu server
            // selesai dites.
            refreshAccountsList()

            val gagal = results.count { !it.second.success }
            Toast.makeText(
                this@ConfigActivity,
                if (gagal == 0) "Tes ping selesai untuk ${results.size} server."
                else "Tes ping selesai. $gagal dari ${results.size} server tidak terjangkau.",
                Toast.LENGTH_SHORT
            ).show()
        }
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

        // FITUR BARU (permintaan user, "urutkan dari yang paling cepat"):
        // kalau sudah pernah ada hasil tes ping ([pingResults] tidak kosong),
        // urutkan ulang daftar SEBELUM dirender -- akun dengan ping sukses
        // paling KECIL di paling atas, akun yang gagal/timeout di bawahnya,
        // akun yang BELUM pernah dites (mis. baru ditambah setelah tes
        // terakhir, atau memang dilewati resolvePingTarget) di paling
        // bawah. sortedWith stabil, jadi akun-akun dalam grup yang sama
        // (mis. sama-sama belum dites) tetap mempertahankan urutan
        // ditambahkan seperti biasa. Kalau belum pernah ada tes sama
        // sekali, urutan TETAP seperti sebelum fitur ini ada (urutan
        // ditambahkan), tidak ada yang berubah.
        val sortedProfiles = if (pingResults.isEmpty()) {
            profiles
        } else {
            profiles.sortedWith(
                compareBy(
                    { profile ->
                        val result = pingResults[profile.id]
                        when {
                            result == null -> 2
                            !result.success -> 1
                            else -> 0
                        }
                    },
                    { profile -> pingResults[profile.id]?.latencyMs ?: Long.MAX_VALUE }
                )
            )
        }

        // REDESIGN (permintaan user: "gabungkan card akun tersimpan, rapikan
        // susunannya"): semua akun sekarang baris biasa di dalam SATU kartu
        // besar "Akun Tersimpan" (lihat activity_config.xml), dipisah garis
        // tipis "dividerRow" antar baris -- disembunyikan khusus untuk baris
        // PERTAMA (index == 0) supaya tidak dobel dengan garis header di
        // atasnya, sama seperti pola divider SSH/Xray di kartu atasnya.
        val inflater = LayoutInflater.from(this)
        sortedProfiles.forEachIndexed { index, profile ->
            val row = ItemAccountRowBinding.inflate(inflater, binding.llAccountsContainer, false)
            row.dividerRow.visibility = if (index == 0) View.GONE else View.VISIBLE
            bindAccountRow(row, profile, isActive = profile.id == activeId, pingResult = pingResults[profile.id])
            binding.llAccountsContainer.addView(row.root)
        }
    }

    private fun bindAccountRow(
        row: ItemAccountRowBinding,
        profile: SavedProfile,
        isActive: Boolean,
        pingResult: PingResult?
    ) {
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
            // REFACTOR (opsi 1+2, toggle independen): badge ringkas dirakit
            // dari 3 toggle (kombinasi bebas), bukan 1 nama preset lagi --
            // lihat SavedConfig.resolvedXxxEnabled().
            val parts = buildList {
                if (config.resolvedTlsEnabled()) add("TLS")
                if (config.resolvedProxyEnabled()) add("PROXY")
                if (config.resolvedPayloadEnabled()) add("PAYLOAD")
                if (config.resolvedEnhancedEnabled()) add("ENHANCED")
            }
            val modeName = if (parts.isEmpty()) "SSH" else "SSH ${parts.joinToString(" ")}"
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
            // FITUR BARU (permintaan user, "kunci jadikan aktif saat tunnel
            // terhubung"): selagi ADA tunnel yang terhubung (vpnConnected),
            // ganti akun aktif diblokir -- bukan cuma buat baris yang lagi
            // aktif (itu memang tidak akan kelihatan tombol ini, lihat
            // isActive di atas), tapi buat SEMUA baris lain juga, karena
            // MyVpnService & notifikasi Connect/Reconnect dari luar app
            // (lihat MyVpnService.EXTRA_PROFILE_ID) berpatokan ke
            // ProfileStore.getActive() -- kalau id-nya diganti diam-diam
            // selagi tunnel jalan, reconnect otomatis/tombol notifikasi bisa
            // jadi nyambung ke akun yang salah, beda dari yang tadinya
            // benar-benar terhubung di layar.
            row.tvRowSetActive.alpha = if (vpnConnected) 0.35f else 1f
            row.tvRowSetActive.setOnClickListener {
                if (vpnConnected) {
                    Toast.makeText(
                        this,
                        "Ada tunnel yang sedang terhubung. Disconnect dulu untuk mengganti akun aktif.",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    ProfileStore.setActiveId(this, profile.id)
                    refreshAccountsList()
                }
            }
        }

        // FITUR BARU (permintaan user, "cek ping/latency server sebelum &
        // sesudah connect, urutkan dari yang paling cepat"): render badge
        // hasil TCP ping TERAKHIR akun ini (diisi lewat onTestPingAllClicked,
        // null kalau belum pernah dites SAMA SEKALI sejak Activity ini
        // dibuka, atau akun ini dilewati resolvePingTarget mis. karena
        // terkunci total) -- tersembunyi total sampai ada hasil, supaya
        // tampilan baris tidak berubah sebelum tombol "Tes Ping Semua
        // Server" pernah ditekan, PERSIS seperti sebelum fitur ini ada.
        if (pingResult == null) {
            row.tvRowPing.visibility = View.GONE
        } else {
            row.tvRowPing.visibility = View.VISIBLE
            val latencyMs = pingResult.latencyMs
            val (bgColorRes, textColorRes, label) = when {
                !pingResult.success -> Triple(R.color.status_error_bg, R.color.status_error, "Gagal")
                latencyMs == null -> Triple(R.color.status_error_bg, R.color.status_error, "Gagal")
                latencyMs < 150 -> Triple(R.color.status_success_bg, R.color.status_success, "${latencyMs} ms")
                latencyMs < 400 -> Triple(R.color.status_running_bg, R.color.status_running, "${latencyMs} ms")
                else -> Triple(R.color.status_error_bg, R.color.status_error, "${latencyMs} ms")
            }
            // Pola SAMA PERSIS dengan DashboardMainFragment.applyStatusPillColor()
            // -- background tvRowPing (bg_pill_ping.xml) SENGAJA satu <shape>
            // <solid> tunggal supaya bisa di-mutate() jadi GradientDrawable
            // & warnanya ditimpa runtime tanpa kehilangan <corners> aslinya.
            (row.tvRowPing.background.mutate() as GradientDrawable)
                .setColor(ContextCompat.getColor(this, bgColorRes))
            row.tvRowPing.setTextColor(ContextCompat.getColor(this, textColorRes))
            row.tvRowPing.text = label
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

        // FITUR BARU (permintaan user, "kunci edit config saat tunnel
        // terhubung"): Edit akun ini diblokir TAMBAHAN kalau akun ini yang
        // SEDANG AKTIF (isActive) DAN tunnel-nya benar-benar terhubung
        // (vpnConnected, lihat collector StatusBus.state di onCreate) --
        // akun lain yang bukan sedang dipakai tunnel TIDAK terpengaruh,
        // tetap bebas diedit walau ada tunnel lain yang jalan.
        val editBlockedByVpn = isActive && vpnConnected

        // FITUR BARU (permintaan user, "kunci konfig saat ekspor"): Edit
        // SELALU dipudarkan & diblokir total untuk akun contentLocked, apa
        // pun status isLocked biasa-nya -- beda dari isLocked yang cuma
        // memudarkan kalau memang lagi dikunci user sendiri.
        val lockedAlpha = if (config.isLocked || contentLocked || editBlockedByVpn) 0.35f else 1f
        row.btnRowEdit.alpha = lockedAlpha
        row.btnRowDelete.alpha = if ((config.isLocked && !contentLocked) || editBlockedByVpn) 0.35f else 1f

        row.btnRowEdit.setOnClickListener {
            when {
                contentLocked -> Toast.makeText(
                    this,
                    "Detail konfigurasi akun ini disembunyikan oleh pembuatnya, tidak bisa dilihat/diedit.",
                    Toast.LENGTH_LONG
                ).show()
                config.isLocked -> Toast.makeText(this, "Akun ini terkunci. Buka kunci dulu untuk mengedit.", Toast.LENGTH_SHORT).show()
                editBlockedByVpn -> Toast.makeText(
                    this,
                    "Akun ini sedang dipakai tunnel yang terhubung. Disconnect dulu untuk mengedit.",
                    Toast.LENGTH_SHORT
                ).show()
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
            // FITUR BARU (permintaan user, "kunci hapus saat tunnel
            // terhubung"): akun yang SEDANG AKTIF & tunnel-nya terhubung
            // (editBlockedByVpn, sama syaratnya dengan Edit) tidak boleh
            // dihapus -- menghapus akun yang sedang dipakai tunnel yang
            // masih jalan bisa bikin ProfileStore.getActive() kehilangan
            // profil aktifnya di tengah koneksi (reconnect otomatis/tombol
            // notifikasi jadi tidak tahu harus connect ke mana).
            if (editBlockedByVpn) {
                Toast.makeText(
                    this,
                    "Akun ini sedang dipakai tunnel yang terhubung. Disconnect dulu untuk menghapus.",
                    Toast.LENGTH_SHORT
                ).show()
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
    /**
     * Satu baris opsi format di dialog "Ekspor akun ini"/"Ekspor Akun
     * Terpilih" -- kotak "kaca" bulat ([R.drawable.bg_export_option_row])
     * berisi ikon + label, seluruh baris bisa ditap (bukan cuma teksnya).
     */
    private fun buildExportOptionRow(iconRes: Int, labelText: String, onClick: () -> Unit): LinearLayout {
        val icon = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(56, 56).apply { marginEnd = 28 }
            setImageResource(iconRes)
            setColorFilter(ContextCompat.getColor(context, R.color.export_glass_body_text))
        }
        val label = TextView(this).apply {
            text = labelText
            setTextColor(ContextCompat.getColor(context, R.color.export_glass_body_text))
            textSize = 17f
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(32, 36, 32, 36)
            background = ContextCompat.getDrawable(context, R.drawable.bg_export_option_row)
            isClickable = true
            isFocusable = true
            val ripple = TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
            foreground = ContextCompat.getDrawable(context, ripple.resourceId)
            addView(icon)
            addView(label)
            setOnClickListener { onClick() }
        }
    }

    /**
     * REDESIGN (permintaan user: gaya "glass" ungu-teal + baris opsi kotak
     * bulat sesuai contoh gambar) -- menggantikan pola
     * `newDialogBuilder().setItems(options)` (daftar teks polos sistem)
     * yang sebelumnya dipakai baik di [onShareRowClicked] ("Ekspor akun
     * ini") maupun [onExportAllClicked] ("Ekspor Akun Terpilih"). Dibangun
     * manual persis pola [showExportDetailsDialog]/[showExportAccountPicker]:
     * kartu kaca lewat [R.drawable.bg_dialog_export_glass] dipasang ke
     * window SETELAH create(), tiap opsi lewat [buildExportOptionRow],
     * "Batal" tampil sebagai teks polos di bawah (BUKAN tombol
     * setNegativeButton bawaan) persis contoh gambar. Menekan salah satu
     * opsi otomatis menutup dialog dulu (dialog.dismiss()) sebelum
     * menjalankan [action]-nya, supaya tidak ada dua dialog bertumpuk.
     */
    private fun showExportTypeDialog(title: String, options: List<Triple<Int, String, () -> Unit>>) {
        lateinit var dialog: AlertDialog

        val rows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        options.forEach { (iconRes, label, action) ->
            val row = buildExportOptionRow(iconRes, label) {
                dialog.dismiss()
                action()
            }
            rows.addView(
                row,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { bottomMargin = 24 }
            )
        }

        val batal = TextView(this).apply {
            text = "Batal"
            setTextColor(ContextCompat.getColor(context, R.color.export_outline_button_text))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            textSize = 17f
            gravity = Gravity.CENTER
            setPadding(0, 28, 0, 8)
            isClickable = true
            isFocusable = true
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 16, 0, 8)
            addView(rows)
            addView(batal)
        }

        dialog = MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_TunnelApp_Dialog_Export)
            .setTitle(title)
            .setView(container)
            .create()
        // PENTING: sama seperti dialog "glass" lain di file ini,
        // MaterialAlertDialogBuilder.create() selalu menimpa background
        // window dengan MaterialShapeDrawable solid -- drawable gradasi
        // "kaca" harus dipasang lagi di sini, SETELAH create(), SEBELUM
        // show().
        dialog.window?.setBackgroundDrawableResource(R.drawable.bg_dialog_export_glass)
        batal.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun onShareRowClicked(profile: SavedProfile) {
        // FITUR BARU (permintaan user, "sebelum pemilihan jenis konfig
        // dikasih form nama & catatan"): dialog pilihan format (file/.spn
        // atau kode teks) sekarang BARU muncul SETELAH form
        // [showExportDetailsDialog] dikonfirmasi -- lihat dokumentasinya.
        showExportDetailsDialog(defaultName = profile.config.accountName.trim()) { name, note ->
            // BARU (permintaan user, "nama ekspor konfig dibikin menyatu
            // dengan nama akun -- ketik nama ekspor, nama akun ikut
            // berubah juga"): kalau nama yang diketik di kolom "Nama
            // ekspor" TIDAK KOSONG dan beda dari nama akun saat ini,
            // langsung TIMPA nama akun tersimpan itu juga lewat
            // [ProfileStore.upsert] (id tetap sama, cuma accountName yang
            // berubah) supaya keduanya selalu menyatu/sama. Profil yang
            // dipakai untuk proses ekspor selanjutnya ([effectiveProfile])
            // ikut pakai nama baru ini juga, supaya isi hasil ekspornya
            // (JSON/kode) konsisten dengan nama akun yang baru, bukan
            // nama lama. INI CUMA berlaku di sini (ekspor SATU akun) --
            // TIDAK berlaku sama sekali untuk "Ekspor Semua Akun"
            // ([onExportAllClicked]) karena satu nama yang diketik user
            // di situ tidak boleh menimpa nama SEMUA akun sekaligus.
            val trimmedName = name.trim()
            val effectiveProfile = if (trimmedName.isNotEmpty() && trimmedName != profile.config.accountName.trim()) {
                val renamedConfig = profile.config.copy(accountName = trimmedName)
                ProfileStore.upsert(this, id = profile.id, config = renamedConfig)
                refreshAccountsList()
                profile.copy(config = renamedConfig)
            } else {
                profile
            }

            showExportTypeDialog(
                title = "Ekspor akun ini",
                options = listOf(
                    Triple(R.drawable.ic_export_type_file, "Simpan sebagai File (.spn)") {
                        onExportToFileClicked(listOf(effectiveProfile), trimmedName, note)
                    },
                    Triple(R.drawable.ic_export_type_code, "Kode Teks (Salin/Bagikan)") {
                        shareRowAsTextCode(effectiveProfile, trimmedName, note)
                    }
                )
            )
        }
    }

    /**
     * FITUR BARU (permintaan user): form "Detail Ekspor" yang tampil SEBELUM
     * dialog pemilihan jenis konfig (file/.spn, kode teks, atau clipboard),
     * baik untuk ekspor satu akun ([onShareRowClicked]) maupun ekspor semua
     * ([onExportAllClicked]). Dua kolom, KEDUANYA OPSIONAL (boleh dikosongkan,
     * "Lanjut" tetap jalan):
     *  - Nama: label bebas untuk ekspor ini -- kalau diisi, ikut dipakai
     *    sebagai saran nama file di [showExportFilenameDialog] (jalur
     *    "Simpan sebagai File").
     *  - Catatan: teks bebas yang ditulis APA ADANYA (lihat [profilesToJson]/
     *    [buildShareCode]) ke hasil ekspor -- SENGAJA tidak disaring/di-escape
     *    di sini supaya boleh berisi markup HTML (atau apa pun) yang nanti
     *    bisa dirender sebagai HTML di tempat lain, sesuai permintaan user.
     * [onConfirmed] menerima (nama, catatan) apa adanya (sudah di-trim),
     * dan HANYA dipanggil kalau user menekan "Lanjut" (bukan "Batal").
     *
     * PERBAIKAN BUG (dialog pecah/meluber keluar layar): [defaultName]
     * datang dari [SavedProfile.config.accountName], yang untuk akun hasil
     * sinkron "Cloud Config" bisa saja diisi provider dengan remark/"ps"
     * super panjang berisi ASCII art & tag HTML dekoratif (bukan cuma satu
     * baris nama pendek seperti "TES UNLOCK"). [nameInput] DIKUNCI satu
     * baris (isSingleLine + maxLines=1 + ellipsize) -- nama ekspor memang
     * cuma label singkat, jadi teks berlebih cukup dipotong "..." & tetap
     * bisa digeser/diedit, tidak lagi memaksa tinggi dialog membengkak.
     * [noteInput] (yang memang boleh multi-baris untuk HTML) dibatasi
     * tinggi maksimalnya (maxLines) dengan scroll internal sendiri, supaya
     * kalau user tempel catatan yang sangat panjang, kotaknya berhenti pada
     * tinggi wajar & bisa digulir, bukan mendorong isi ScrollView pembungkus
     * jadi raksasa.
     *
     * GANTI TOTAL (permintaan user, "masih ngebuggggg, ganti total tampilan
     * & pastikan tidak ada bug tampilan lagi"): dua percobaan sebelumnya
     * (mengunci padding lalu mematikan animasi hint saat mengisi teks
     * default) SAMA-SAMA masih menyisakan celah -- root cause SEBENARNYA
     * adalah [TextInputLayout] "label mengambang" itu sendiri: dia hanya
     * BENAR-BENAR pindah ke posisi kecil di atas lewat animasi berbasis
     * timing (fokus/isi berubah), yang gampang meleset kalau teksnya
     * dipasang lewat kode (bukan diketik user) -- apa pun urutan/kondisi
     * baru yang dicoba, celah timing itu tetap ada selama mekanismenya
     * masih dipakai. Sekarang mekanisme itu DIBUANG TOTAL, bukan ditambal
     * lagi: kedua field di dialog ini tidak lagi pakai [TextInputLayout]
     * sama sekali -- label "Nama ekspor (opsional)"/"Catatan (opsional)"
     * sekarang [TextView] STATIS terpisah yang SELALU duduk di atas
     * kotaknya sendiri, tidak pernah animasi/collapse, sehingga SECARA
     * KONSTRUKSI tidak mungkin lagi tumpang tindih dengan isi apa pun
     * kondisi awalnya (kosong, terisi default, atau diisi ulang lewat
     * kode). Lihat [buildStaticExportField].
     */
    private fun showExportDetailsDialog(
        defaultName: String,
        onConfirmed: (name: String, note: String) -> Unit
    ) {
        val nameInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            filters = arrayOf(android.text.InputFilter.LengthFilter(MAX_EXPORT_NAME_LENGTH))
            setTextColor(ContextCompat.getColor(context, R.color.export_glass_title_text))
            setHintTextColor(ContextCompat.getColor(context, R.color.export_glass_placeholder))
            hint = "Contoh: Server Kantor"
            setText(sanitizeExportName(defaultName))
            setSelection(text?.length ?: 0)
        }
        val noteInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            maxLines = 8
            isVerticalScrollBarEnabled = true
            movementMethod = android.text.method.ScrollingMovementMethod()
            setTextColor(ContextCompat.getColor(context, R.color.export_glass_title_text))
            setHintTextColor(ContextCompat.getColor(context, R.color.export_glass_placeholder))
            hint = "Mendukung format HTML..."
        }

        val fieldSpacingPx = 32
        val container = ScrollView(this).apply {
            addView(LinearLayout(this@ConfigActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 16, 0, 4)
                addView(buildStaticExportField("Nama ekspor (opsional)", nameInput))
                addView(
                    buildStaticExportField("Catatan (opsional)", noteInput),
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = fieldSpacingPx }
                )
            })
        }

        val dialog = MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_TunnelApp_Dialog_Export)
            .setTitle("Detail Ekspor")
            .setMessage("Nama & catatan ini disertakan pada hasil ekspor. Kolom catatan mendukung format HTML.")
            .setView(container)
            .setNegativeButton("Batal", null)
            .setPositiveButton("Lanjut") { _, _ ->
                onConfirmed(
                    nameInput.text?.toString().orEmpty().trim(),
                    noteInput.text?.toString().orEmpty().trim()
                )
            }
            .create()
        // PENTING: sama seperti bg_dialog_import_glass, MaterialAlertDialogBuilder
        // .create() selalu menimpa background window dengan MaterialShapeDrawable
        // solid -- drawable gradasi "kaca" ini harus dipasang lagi di sini,
        // SETELAH create(), SEBELUM show().
        dialog.window?.setBackgroundDrawableResource(R.drawable.bg_dialog_export_glass)
        dialog.show()
    }

    /**
     * Satu field di dialog "Detail Ekspor" -- label [labelText] STATIS
     * (bukan hint mengambang [TextInputLayout], lihat catatan "GANTI
     * TOTAL" di [showExportDetailsDialog] untuk alasannya) selalu duduk
     * di atas kotak input, lalu [editText] dibungkus kotak "kaca" bulat
     * ([R.drawable.bg_field_export_static]) yang HANYA berubah warna
     * garis tepi berdasarkan status FOKUS sungguhan (bukan animasi) --
     * [addStatesFromChildren] dipasang true di kotak pembungkus supaya
     * status fokus [editText] di dalamnya ikut "menular" jadi state
     * drawable background kotak ini.
     */
    private fun buildStaticExportField(labelText: String, editText: EditText): LinearLayout {
        val label = TextView(this).apply {
            text = labelText
            setTextColor(ContextCompat.getColor(context, R.color.export_glass_body_text))
            textSize = 13f
            setPadding(4, 0, 0, 10)
        }
        editText.setPadding(28, 24, 28, 24)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(context, R.drawable.bg_field_export_static)
            setAddStatesFromChildren(true)
            addView(
                editText,
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label)
            addView(box)
        }
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
    private fun shareRowAsTextCode(profile: SavedProfile, exportName: String = "", exportNote: String = "") {
        lifecycleScope.launch {
            val code = withContext(Dispatchers.Default) {
                buildShareCode(profile.config, ConfigLockMode.LOCK_ALL, exportName, exportNote)
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
        // PERBAIKAN (bug: tombol Salin/Bagikan tidak kelihatan): sebelumnya
        // [input] langsung dipasang ke setView() tanpa ScrollView, jadi
        // tingginya WRAP_CONTENT mengikuti PANJANG kode apa adanya. Karena
        // kode sekarang SELALU double-enkripsi + LOCK_ALL (lihat
        // shareRowAsTextCode -> selalu lebih panjang dari sebelumnya),
        // tinggi dialog gampang melebihi tinggi layar -- baris tombol di
        // bawah (Tutup/Salin/Bagikan) ikut terdorong ke luar layar dan
        // TIDAK BISA DIJANGKAU sama sekali, walau kode tombolnya sendiri
        // benar. Dibungkus ScrollView + batas tinggi maksimum di sini,
        // sama seperti pola yang sudah dipakai di dialog Impor
        // (onImportClicked), supaya kotak kode yang scroll, bukan seluruh
        // dialognya -- baris tombol selalu tetap kelihatan di bawah.
        val maxHeightPx = (resources.displayMetrics.heightPixels * 0.4f).toInt()
        val container = ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                maxHeightPx
            )
            addView(dialogInputLayout(input))
        }

        newDialogBuilder()
            .setTitle("Bagikan akun")
            .setMessage("Salin atau bagikan kode di bawah. Siapa pun yang menempelkannya lewat tombol \"Impor\" di app ini akan mendapat akun yang sama persis.")
            .setView(container)
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

    /** Ikon & deskripsi singkat tiap [ConfigLockMode], dipakai di [buildLockModeRow]. */
    private fun lockModeIconFor(mode: ConfigLockMode): Int = when (mode) {
        ConfigLockMode.NONE -> R.drawable.ic_lock_open
        ConfigLockMode.LOCK_ALL -> R.drawable.ic_lock_closed
        ConfigLockMode.LOCK_PAYLOAD_PROXY -> R.drawable.ic_lock_closed
    }

    private fun lockModeDescriptionFor(mode: ConfigLockMode): String = when (mode) {
        ConfigLockMode.NONE -> "Semua data konfigurasi tetap bisa dibaca apa adanya."
        ConfigLockMode.LOCK_ALL -> "Seluruh data sensitif (host, akun, payload, dst) disamarkan penuh."
        ConfigLockMode.LOCK_PAYLOAD_PROXY -> "Hanya payload & remote proxy yang disamarkan, sisanya tetap terbaca."
    }

    /**
     * Satu baris pilihan mode kunci di dialog "Kunci konfigurasi?" -- gaya
     * radio kartu kaca (ikon gembok + judul + deskripsi singkat + lingkaran
     * ceklis di ujung kanan), dibangun persis pola [buildExportAccountRow]/
     * [buildExportOptionRow] supaya konsisten dengan dialog "glass"
     * lain di layar ini (Impor/Detail Ekspor/Pilih Akun). Seluruh baris bisa
     * ditap (bukan cuma ikon ceklisnya), & baris yang sedang terpilih
     * ditandai lingkaran hijau + garis tepi teal menyala, sisanya outline
     * transparan tipis -- meniru gaya radio-button Material3 tapi
     * disesuaikan dengan warna brand kartu kaca ungu-teal.
     */
    private fun buildLockModeRow(
        mode: ConfigLockMode,
        selected: Boolean,
        onClick: () -> Unit
    ): LinearLayout {
        val icon = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(64, 64).apply { marginEnd = 28 }
            setImageResource(lockModeIconFor(mode))
            setColorFilter(
                ContextCompat.getColor(
                    context,
                    if (selected) R.color.brand_primary else R.color.export_glass_body_text
                )
            )
        }
        val title = TextView(this).apply {
            text = mode.label
            setTextColor(ContextCompat.getColor(context, R.color.export_glass_title_text))
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val subtitle = TextView(this).apply {
            text = lockModeDescriptionFor(mode)
            setTextColor(ContextCompat.getColor(context, R.color.export_glass_body_text))
            textSize = 13f
            setLineSpacing(2f, 1f)
        }
        val textColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ).apply { marginEnd = 20 }
            addView(title)
            addView(subtitle)
        }
        val check = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(44, 44)
            setImageResource(
                if (selected) R.drawable.ic_export_row_checked else R.drawable.ic_export_row_unchecked
            )
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(32, 32, 32, 32)
            background = ContextCompat.getDrawable(
                context,
                if (selected) R.drawable.bg_export_option_row_selected else R.drawable.bg_export_option_row
            )
            isClickable = true
            isFocusable = true
            val ripple = TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
            foreground = ContextCompat.getDrawable(context, ripple.resourceId)
            addView(icon)
            addView(textColumn)
            addView(check)
            setOnClickListener { onClick() }
        }
    }

    /**
     * REDESIGN (permintaan user: tampilan "Kunci konfigurasi?" dibikin
     * profesional & modern) -- SEBELUMNYA dialog ini pakai
     * `newDialogBuilder().setSingleChoiceItems(...)`, yakni daftar radio
     * BAWAAN Android polos (lihat screenshot laporan user: kotak putih
     * kotak-kotak tajam, radio button generik biru sistem, jauh beda gaya
     * dari kartu kaca ungu-teal yang dipakai dialog Impor/Ekspor lain di
     * layar yang sama). Sekarang dibangun manual mengikuti pola PERSIS yang
     * sama dengan [showExportTypeDialog]/[showExportDetailsDialog]: kartu
     * kaca [R.drawable.bg_dialog_export_glass] dipasang ke window SETELAH
     * create(), tiap mode kunci jadi baris [buildLockModeRow] (ikon gembok +
     * judul + deskripsi singkat + ceklis, BUKAN cuma teks label mentah dari
     * [ConfigLockMode.label]), & tombol "Lanjut"/"Batal" pill sama seperti
     * dialog Detail Ekspor -- supaya seluruh alur ekspor di layar ini
     * terasa satu keluarga desain yang konsisten.
     *
     * Dipakai SEBELUM membangun kode bagikan satu akun ([onShareRowClicked])
     * atau file ekspor semua akun ([onExportAllClicked]) -- cuma ditawarkan
     * untuk akun yang BELUM terkunci sama sekali. [onChosen] dipanggil
     * dengan mode yang dipilih user; dialog dibatalkan begitu saja kalau
     * user menekan "Batal" (tidak memanggil [onChosen] sama sekali).
     */
    private fun showLockModePicker(onChosen: (ConfigLockMode) -> Unit) {
        val modes = ConfigLockMode.entries.toTypedArray()
        var selected = 0
        lateinit var dialog: AlertDialog
        lateinit var rowsContainer: LinearLayout

        fun rebuildRows() {
            rowsContainer.removeAllViews()
            modes.forEachIndexed { index, mode ->
                val row = buildLockModeRow(mode, selected = index == selected) {
                    if (selected != index) {
                        selected = index
                        rebuildRows()
                    }
                }
                rowsContainer.addView(
                    row,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        .apply { bottomMargin = 20 }
                )
            }
        }

        rowsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 16, 0, 4)
        }
        rebuildRows()

        dialog = MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_TunnelApp_Dialog_Export)
            .setTitle("Kunci konfigurasi?")
            .setMessage("Pilih bagaimana data konfigurasi ini disamarkan saat diekspor atau dibagikan.")
            .setView(rowsContainer)
            .setNegativeButton("Batal", null)
            .setPositiveButton("Lanjut") { _, _ -> onChosen(modes[selected]) }
            .create()
        // PENTING: sama seperti dialog "glass" lain di file ini,
        // MaterialAlertDialogBuilder.create() selalu menimpa background
        // window dengan MaterialShapeDrawable solid -- drawable gradasi
        // "kaca" harus dipasang lagi di sini, SETELAH create(), SEBELUM
        // show().
        dialog.window?.setBackgroundDrawableResource(R.drawable.bg_dialog_export_glass)
        dialog.show()
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
            setTextColor(ContextCompat.getColor(context, R.color.import_glass_title_text))
            setHintTextColor(ContextCompat.getColor(context, R.color.import_glass_placeholder))
        }
        // Dibungkus ScrollView supaya tetap nyaman diketik/ditempel kalau
        // isinya panjang (JSON hasil ekspor banyak akun bisa lumayan panjang).
        // REDESIGN (permintaan user: tampilan dialog Impor jadi "glass"
        // ungu-biru sesuai contoh gambar) -- kotaknya sekarang pill/kaca
        // (Field.Pill.Import) lewat parameter [styleOverlay] baru di
        // [dialogInputLayout], dan teksnya dipasang sebagai
        // [placeholderText] (BUKAN [hintText]) supaya tampil sebagai
        // placeholder statis di dalam kotak, tanpa label mengambang --
        // persis seperti contoh gambar.
        val container = ScrollView(this).apply {
            addView(
                dialogInputLayout(
                    input,
                    placeholderText = "Tempel kode akun (SVPN2:...) atau isi file JSON hasil ekspor di sini",
                    styleOverlay = R.style.ThemeOverlay_TunnelApp_PillField_Import
                )
            )
        }

        val dialog = MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_TunnelApp_Dialog_Import)
            .setTitle("Impor konfigurasi")
            .setView(container)
            .setNegativeButton("Batal", null)
            .setNeutralButton("Pilih File") { _, _ ->
                importFileLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
            }
            .setPositiveButton("Impor") { _, _ ->
                performImport(input.text?.toString().orEmpty())
            }
            .create()
        // PENTING: MaterialAlertDialogBuilder.create() SELALU menimpa
        // background window dengan MaterialShapeDrawable solid warna
        // colorSurface, terlepas dari android:windowBackground apa pun
        // yang dipasang di tema overlay -- satu-satunya cara drawable
        // gradasi "kaca" (bg_dialog_import_glass) benar-benar kepakai
        // adalah memasangnya lagi di sini, SETELAH create(), SEBELUM
        // show(). Lihat juga komentar di drawable itu sendiri.
        dialog.window?.setBackgroundDrawableResource(R.drawable.bg_dialog_import_glass)
        dialog.show()
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
     * BARU (permintaan user, "ekspor konfig semua dirubah logikanya jadi
     * flexible dan harus centang konfig yang mau diekspor"): SEBELUM
     * [onExportAllClicked] dulu langsung mengekspor SEMUA akun tanpa
     * tanya-tanya -- sekarang WAJIB lewat checklist di sini dulu, supaya
     * user bebas pilih sendiri kombinasi akun mana saja yang mau ikut
     * (tidak harus benar-benar "semua"). Default checklist ini SEMUA
     * akun ke-centang (kasus paling umum: user memang mau ekspor semua),
     * tapi tiap baris bisa dicentang/dilepas bebas satu-satu SEBELUM
     * menekan "Lanjut". Kalau user menekan "Lanjut" tanpa satupun akun
     * ter-centang, tampilkan Toast peringatan & dialog checklist ini
     * TETAP terbuka (tidak lanjut ke [onPicked]) -- supaya tidak pernah
     * kejadian ekspor dengan daftar akun kosong.
     * [onPicked] menerima sublist [profiles] SESUAI URUTAN ASLINYA (bukan
     * urutan dicentang), berisi hanya akun-akun yang ter-centang.
     */
    /**
     * Satu baris akun di dialog "Pilih Akun untuk Diekspor" -- ikon
     * lingkaran ceklis (hijau = tercentang/ikut ekspor, outline = tidak),
     * nama akun bold, & subjudul statis "Sertakan untuk diekspor" persis
     * contoh gambar. Tap di mana pun pada baris (bukan cuma ikonnya)
     * membalik status centang & memanggil [onToggle] dengan nilai barunya.
     */
    private fun buildExportAccountRow(
        label: String,
        checkedInitially: Boolean,
        onToggle: (Boolean) -> Unit
    ): LinearLayout {
        val icon = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(72, 72).apply { marginEnd = 32 }
            setImageResource(
                if (checkedInitially) R.drawable.ic_export_row_checked else R.drawable.ic_export_row_unchecked
            )
        }
        val title = TextView(this).apply {
            text = label
            setTextColor(ContextCompat.getColor(context, R.color.export_glass_title_text))
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val subtitle = TextView(this).apply {
            text = "Sertakan untuk diekspor"
            setTextColor(ContextCompat.getColor(context, R.color.export_glass_body_text))
            textSize = 14f
        }
        val textColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(title)
            addView(subtitle)
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 28, 0, 28)
            isClickable = true
            isFocusable = true
            val ripple = TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
            setBackgroundResource(ripple.resourceId)
            addView(icon)
            addView(textColumn)

            var checked = checkedInitially
            setOnClickListener {
                checked = !checked
                icon.setImageResource(
                    if (checked) R.drawable.ic_export_row_checked else R.drawable.ic_export_row_unchecked
                )
                onToggle(checked)
            }
        }
    }

    /**
     * REDESIGN (permintaan user: gaya "glass" ungu-teal + ceklis lingkaran
     * hijau sesuai contoh gambar) -- sebelumnya pakai
     * `newDialogBuilder().setMultiChoiceItems(...)` (checklist sistem
     * polos). Sekarang dibangun manual persis pola [showExportDetailsDialog]:
     * kartu kaca lewat [R.drawable.bg_dialog_export_glass] dipasang ke
     * window SETELAH create(), baris akun lewat [buildExportAccountRow],
     * tombol "Lanjut" pill gradasi teal->indigo & "Batal" teks polos
     * (BUKAN pill outline seperti di [showExportDetailsDialog] -- di
     * contoh gambar dialog ini "Batal"-nya memang cuma teks, tanpa
     * garis). Baris peringatan kecil di bawah tombol ("Tombol Ekspor akan
     * menyertakan SEMUA akun...") ikut ditampilkan APA ADANYA sesuai
     * contoh, supaya user paham bahwa akun yang di-uncheck di sini TETAP
     * disertakan kalau nanti pakai tombol "Ekspor" lain (bukan lewat
     * dialog ini) -- lihat catatan yang sama persis di teks caption.
     */
    private fun showExportAccountPicker(
        profiles: List<SavedProfile>,
        onPicked: (List<SavedProfile>) -> Unit
    ) {
        val checked = BooleanArray(profiles.size) { true }

        val rowsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        profiles.forEachIndexed { index, profile ->
            val label = profile.config.accountName.trim().ifEmpty { "(Tanpa nama)" }
            rowsContainer.addView(
                buildExportAccountRow(label, checked[index]) { isChecked -> checked[index] = isChecked }
            )
        }

        // Dibungkus ScrollView supaya tetap nyaman dipakai kalau akun
        // tersimpan banyak (sama pola dengan [onImportClicked]/kode
        // bagikan) -- dibatasi tinggi maksimal 40% layar, sisanya scroll
        // internal, supaya baris tombol di bawah selalu tetap kelihatan.
        val maxHeightPx = (resources.displayMetrics.heightPixels * 0.4f).toInt()
        val rowsScroll = object : ScrollView(this) {
            override fun onMeasure(widthSpec: Int, heightSpec: Int) {
                super.onMeasure(widthSpec, heightSpec)
                if (measuredHeight > maxHeightPx) {
                    setMeasuredDimension(measuredWidth, maxHeightPx)
                }
            }
        }.apply { addView(rowsContainer) }

        val divider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 2).apply {
                topMargin = 24
                bottomMargin = 32
            }
            setBackgroundColor(ContextCompat.getColor(context, R.color.export_glass_divider))
        }

        // REDESIGN (permintaan user, "bikin lebih profesional dan modern"):
        // sebelumnya "Lanjut" & "Batal" berdempetan HORIZONTAL rata kiri
        // (wrap_content keduanya) -- terkesan sempit/asal-taruh karena CTA
        // utama tidak menonjol dan ruang kosong di kanan tidak terpakai.
        // Sekarang: "Lanjut" jadi pill gradasi FULL-WIDTH (pola tombol
        // primer umum di app modern -- gampang di-tap & jelas jadi fokus
        // utama), "Batal" dipindah ke BAWAHNYA sebagai teks polos yang
        // di-center (bukan lagi nempel di sebelah kiri Lanjut) supaya
        // hierarki aksi (utama vs batal) kebaca jelas & tidak berebut
        // perhatian dengan CTA gradasi di atasnya.
        val lanjutButton = Button(this).apply {
            text = "Lanjut"
            isAllCaps = false
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(context, R.color.export_button_text))
            background = ContextCompat.getDrawable(context, R.drawable.bg_button_export_primary)
            setPadding(72, 32, 72, 32)
            stateListAnimator = null
            elevation = 0f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val batalButton = Button(this).apply {
            text = "Batal"
            isAllCaps = false
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(context, R.color.export_outline_button_text))
            background = null
            minWidth = 0
            minimumWidth = 0
            setPadding(32, 24, 32, 24)
            stateListAnimator = null
            elevation = 0f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = 8
            }
        }
        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(lanjutButton)
            addView(batalButton)
        }

        val caption = TextView(this).apply {
            text = "Tombol Ekspor akan menyertakan SEMUA akun tersimpan di bawah ini, termasuk akun yang tidak Anda pilih."
            setTextColor(ContextCompat.getColor(context, R.color.export_glass_body_text))
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 0)
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 8)
            addView(rowsScroll)
            addView(divider)
            addView(buttonRow)
            addView(caption)
        }

        val dialog = MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_TunnelApp_Dialog_Export)
            .setTitle("Pilih Akun untuk Diekspor")
            .setView(container)
            .create()
        // PENTING: sama seperti [showExportDetailsDialog]/bg_dialog_import_glass,
        // MaterialAlertDialogBuilder.create() selalu menimpa background
        // window dengan MaterialShapeDrawable solid -- drawable gradasi
        // "kaca" ini harus dipasang lagi di sini, SETELAH create(),
        // SEBELUM show().
        dialog.window?.setBackgroundDrawableResource(R.drawable.bg_dialog_export_glass)

        lanjutButton.setOnClickListener {
            val selected = profiles.filterIndexed { index, _ -> checked[index] }
            if (selected.isEmpty()) {
                Toast.makeText(this, "Pilih minimal satu akun untuk diekspor", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            dialog.dismiss()
            onPicked(selected)
        }
        batalButton.setOnClickListener { dialog.dismiss() }

        dialog.show()
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
     *
     * REDESIGN (permintaan user, "dirubah logikanya jadi flexible dan
     * harus centang konfig yang mau diekspor"): SEBELUM form Detail
     * Ekspor & pemilihan format di bawah tampil, akun yang ikut diekspor
     * sekarang HARUS dipilih dulu lewat checklist [showExportAccountPicker]
     * -- daftar akun yang benar-benar dipakai di [onExportToFileClicked]/
     * [onExportToClipboardClicked] adalah [selectedProfiles] (hasil
     * checklist), BUKAN lagi seluruh [profiles] begitu saja.
     */
    private fun onExportAllClicked() {
        val profiles = ProfileStore.getAll(this)
        if (profiles.isEmpty()) {
            Toast.makeText(this, "Belum ada akun untuk diekspor", Toast.LENGTH_SHORT).show()
            return
        }
        showExportAccountPicker(profiles) { selectedProfiles ->
            // FITUR BARU (permintaan user, "sebelum pemilihan jenis konfig
            // dikasih form nama & catatan"): sama seperti [onShareRowClicked],
            // dialog pilihan format di bawah baru muncul setelah form
            // [showExportDetailsDialog] dikonfirmasi.
            showExportDetailsDialog(defaultName = "") { name, note ->
                showExportTypeDialog(
                    title = "Ekspor Akun Terpilih",
                    options = listOf(
                        Triple(R.drawable.ic_export_type_file, "Simpan sebagai File (.spn)") {
                            onExportToFileClicked(selectedProfiles, name, note)
                        },
                        Triple(R.drawable.ic_export_type_clipboard, "Salin ke Clipboard") {
                            onExportToClipboardClicked(selectedProfiles, name, note)
                        }
                    )
                )
            }
        }
    }

    /**
     * Jalur 1 dari [onExportAllClicked]: alur lama -- tanya mode kunci dulu
     * (cuma berlaku ke akun yang BELUM terkunci, lihat [profilesToJson]),
     * lalu nama filenya, baru ditulis sebagai file .spn biner terenkripsi
     * ke Download/SuryaVPN/.
     */
    private fun onExportToFileClicked(
        profiles: List<SavedProfile>,
        exportName: String = "",
        exportNote: String = ""
    ) {
        showLockModePicker { chosenMode ->
            showExportFilenameDialog(profiles, exportName) { filename ->
                // PERBAIKAN (permintaan user, "klik Lanjut kok agak nge-
                // freeze"): lihat dokumentasi lengkap di [buildShareCodeAsync]
                // -- masalah & solusinya sama persis di sini, cuma untuk
                // BANYAK akun sekaligus (profilesToJson) jadi potensi
                // freeze-nya malah lebih terasa lagi kalau tetap dijalankan
                // di main thread.
                lifecycleScope.launch {
                    val bytes = withContext(Dispatchers.Default) {
                        val json = profilesToJson(profiles, chosenMode, exportName, exportNote)
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
    private fun onExportToClipboardClicked(
        profiles: List<SavedProfile>,
        exportName: String = "",
        exportNote: String = ""
    ) {
        // PERBAIKAN (permintaan user, "klik Lanjut kok agak nge-freeze"):
        // sama seperti [onExportToFileClicked]/[buildShareCodeAsync] --
        // profilesToJson + encryptWholeFileBytes CPU-bound, dipindah ke
        // Dispatchers.Default supaya tombol menu "Salin ke Clipboard" tidak
        // bikin UI macet sesaat.
        lifecycleScope.launch {
            val text = withContext(Dispatchers.Default) {
                val json = profilesToJson(profiles, ConfigLockMode.LOCK_ALL, exportName, exportNote)
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
     *  - Kalau [exportName] diisi (dari form [showExportDetailsDialog] yang
     *    tampil sebelumnya) -> itu yang disarankan duluan, prioritas
     *    tertinggi.
     *  - Kalau tidak, dan [profiles] cuma berisi SATU akun & akun itu sudah
     *    punya nama custom (accountName tidak kosong) -> nama itu yang
     *    disarankan, user tinggal konfirmasi atau ubah kalau mau.
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
    private fun showExportFilenameDialog(
        profiles: List<SavedProfile>,
        exportName: String = "",
        onConfirmed: (String) -> Unit
    ) {
        val suggestedName = when {
            exportName.isNotBlank() -> exportName
            profiles.size == 1 -> profiles[0].config.accountName.trim()
            else -> ""
        }

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
                    applyNavFadeTransition()
                    true
                }
                R.id.nav_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    finish()
                    applyNavFadeTransition()
                    true
                }
                R.id.nav_tools -> {
                    startActivity(Intent(this, ToolsActivity::class.java))
                    finish()
                    applyNavFadeTransition()
                    true
                }
                else -> false
            }
        }
    }
}
