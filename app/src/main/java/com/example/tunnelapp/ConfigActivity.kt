package com.example.tunnelapp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.tunnelapp.databinding.ActivityConfigBinding
import com.example.tunnelapp.databinding.ItemAccountRowBinding
import com.example.tunnelapp.model.ConfigLockMode
import com.example.tunnelapp.model.ProfileStore
import com.example.tunnelapp.model.SavedProfile
import com.example.tunnelapp.model.buildShareCode
import com.example.tunnelapp.model.importConfigsFromText
import com.example.tunnelapp.model.profilesToJson
import com.example.tunnelapp.tunnel.XrayLinkParser
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
    // exportPendingBytes menampung isi file yang MAU ditulis, diisi sesaat
    // sebelum exportFileLauncher.launch() dipanggil (lihat onExportAllClicked)
    // -- ActivityResultContracts.CreateDocument tidak bisa membawa "extra
    // data" apa pun selain URI hasil pilihan user, jadi isi filenya harus
    // "dititipkan" lewat variabel ini, baru ditulis di callback saat URI-nya
    // sudah didapat.
    //
    // FITUR BARU (permintaan user, "sekalian ganti biner"): sekarang berupa
    // ByteArray (bukan String JSON polos lagi) -- isinya sudah hasil
    // [encryptWholeFileBytes], jadi apa yang ditulis ke file .spn memang
    // biner terenkripsi utuh, bukan JSON yang cuma sebagian fieldnya
    // dikunci.
    private var exportPendingBytes: ByteArray? = null

    // MIME "application/octet-stream" (bukan "application/json") supaya SAF
    // tidak memaksa ganti balik ekstensi nama file ke .json -- ekstensi
    // .spn di [onExportAllClicked] tetap dipakai apa adanya. Sekarang juga
    // memang sesuai isinya: file yang ditulis biner, bukan teks JSON.
    private val exportFileLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val bytes = exportPendingBytes
        exportPendingBytes = null
        if (uri == null || bytes == null) return@registerForActivityResult
        try {
            contentResolver.openOutputStream(uri)?.use { out ->
                out.write(bytes)
            }
            Toast.makeText(this, "Konfigurasi berhasil diekspor", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Gagal menulis file: ${e.message}", Toast.LENGTH_LONG).show()
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
     * Ekspor SEMUA akun tersimpan jadi satu file JSON (lihat
     * [profilesToJson]) lewat SAF (ACTION_CREATE_DOCUMENT) -- user bebas
     * pilih nama & lokasi filenya sendiri, isinya baru ditulis di
     * [exportFileLauncher] setelah URI-nya didapat. Nama file default
     * disisipi tanggal supaya beberapa kali ekspor tidak saling timpa
     * kalau disimpan di folder yang sama.
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
            exportPendingBytes = encryptWholeFileBytes(json)
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            exportFileLauncher.launch("suryavpn-config-$stamp.spn")
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
