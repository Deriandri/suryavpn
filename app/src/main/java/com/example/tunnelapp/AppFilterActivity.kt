package com.example.tunnelapp

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.tunnelapp.model.VpnSettingsStore
import com.google.android.material.button.MaterialButton
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * FITUR BARU (parity dengan V2RayNG "Per-app proxy"/"Bypass apps"): layar
 * untuk memilih aplikasi mana yang lewat tunnel (mode "Hanya app ini" =
 * allow-list) atau dikecualikan dari tunnel (mode "Kecuali app ini" =
 * block-list). Hasil pilihan disimpan lewat
 * [VpnSettingsStore.savePerAppProxy] dan dibaca [com.example.tunnelapp.tunnel.MyVpnService.applyAppFiltering]
 * setiap kali TUN interface dibuat -- jadi berlaku mulai koneksi BERIKUTNYA
 * (tidak live-update tunnel yang sedang aktif, sama seperti VpnSettings lain).
 *
 * Daftar aplikasi diambil lewat queryIntentActivities(ACTION_MAIN +
 * CATEGORY_LAUNCHER) supaya hanya aplikasi yang PUNYA launcher icon (bukan
 * semua paket sistem) yang ditampilkan -- daftar jadi jauh lebih pendek &
 * relevan buat user, sama seperti V2RayNG/kebanyakan client VPN lain.
 *
 * Butuh elemen <queries> di AndroidManifest.xml (package visibility Android
 * 11+/API 30) supaya queryIntentActivities() ini benar-benar mengembalikan
 * app lain, bukan cuma app ini sendiri.
 */
class AppFilterActivity : AppCompatActivity() {

    private data class AppEntry(
        val label: String,
        val packageName: String,
        val icon: Drawable?
    )

    private var allApps: List<AppEntry> = emptyList()
    private val selected = mutableSetOf<String>()
    private lateinit var adapter: AppListAdapter
    private lateinit var tvSelectedCount: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_filter)

        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        val swEnabled = findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.swEnabled)
        val toggleMode = findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.toggleMode)
        val btnModeAllow = findViewById<MaterialButton>(R.id.btnModeAllow)
        val btnModeBlock = findViewById<MaterialButton>(R.id.btnModeBlock)
        val etSearch = findViewById<android.widget.EditText>(R.id.etSearch)
        val lvApps = findViewById<android.widget.ListView>(R.id.lvApps)
        val btnSave = findViewById<MaterialButton>(R.id.btnSave)
        tvSelectedCount = findViewById(R.id.tvSelectedCount)

        val current = VpnSettingsStore.load(this)
        swEnabled.isChecked = current.perAppProxyEnabled
        selected.addAll(current.perAppProxyPackages)
        if (current.perAppProxyIsAllowList) toggleMode.check(R.id.btnModeAllow) else toggleMode.check(R.id.btnModeBlock)

        adapter = AppListAdapter()
        lvApps.adapter = adapter
        lvApps.setOnItemClickListener { _, _, position, _ ->
            val entry = adapter.getItem(position) ?: return@setOnItemClickListener
            if (!selected.remove(entry.packageName)) selected.add(entry.packageName)
            adapter.notifyDataSetChanged()
            updateSelectedCount()
        }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                adapter.filter(s?.toString().orEmpty())
            }
        })

        btnSave.setOnClickListener {
            val isAllowList = toggleMode.checkedButtonId == R.id.btnModeAllow
            VpnSettingsStore.savePerAppProxy(this, swEnabled.isChecked, isAllowList, selected.toSet())
            Toast.makeText(this, "Per-App Proxy disimpan (berlaku koneksi berikutnya)", Toast.LENGTH_SHORT).show()
            finish()
        }

        updateSelectedCount()
        loadInstalledApps()
    }

    private fun updateSelectedCount() {
        tvSelectedCount.text = "${selected.size} aplikasi dipilih"
    }

    /** Enumerasi app lewat IO dispatcher -- PackageManager bisa lumayan lambat kalau device banyak app terpasang. */
    private fun loadInstalledApps() {
        lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) {
                val pm = packageManager
                val launcherIntent = android.content.Intent(android.content.Intent.ACTION_MAIN)
                    .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
                val resolved = pm.queryIntentActivities(launcherIntent, PackageManager.MATCH_ALL)
                resolved.mapNotNull { ri ->
                    val pkg = ri.activityInfo?.applicationInfo ?: return@mapNotNull null
                    runCatching {
                        AppEntry(
                            label = pm.getApplicationLabel(pkg).toString(),
                            packageName = pkg.packageName,
                            icon = runCatching { pm.getApplicationIcon(pkg) }.getOrNull()
                        )
                    }.getOrNull()
                }
                    .distinctBy { it.packageName }
                    .sortedBy { it.label.lowercase() }
            }
            allApps = apps
            adapter.setData(apps)
        }
    }

    private inner class AppListAdapter : android.widget.BaseAdapter() {
        private var data: List<AppEntry> = emptyList()
        private var filtered: List<AppEntry> = emptyList()

        fun setData(newData: List<AppEntry>) {
            data = newData
            filtered = newData
            notifyDataSetChanged()
        }

        fun filter(query: String) {
            filtered = if (query.isBlank()) {
                data
            } else {
                data.filter {
                    it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true)
                }
            }
            notifyDataSetChanged()
        }

        override fun getCount() = filtered.size
        override fun getItem(position: Int): AppEntry? = filtered.getOrNull(position)
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(this@AppFilterActivity)
                .inflate(R.layout.item_app_filter, parent, false)
            val entry = filtered[position]
            view.findViewById<ImageView>(R.id.ivAppIcon).setImageDrawable(entry.icon)
            view.findViewById<TextView>(R.id.tvAppName).text = entry.label
            view.findViewById<TextView>(R.id.tvAppPackage).text = entry.packageName
            view.findViewById<CheckBox>(R.id.cbAppSelected).isChecked = selected.contains(entry.packageName)
            return view
        }
    }
}
