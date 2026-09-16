package com.example.tunnelapp

import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.tunnelapp.model.VpnSettingsStore
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial

/**
 * FITUR BARU (parity dasar dengan V2RayNG "Routing"): satu layar untuk tiga
 * hal yang sebelumnya TIDAK bisa diatur user sama sekali:
 *  - Bypass LAN (lihat [com.example.tunnelapp.tunnel.MyVpnService.applyTunRoutes]).
 *  - Mux on/off + concurrency, khusus mode Xray (lihat
 *    [com.example.tunnelapp.tunnel.XrayConfigBuilder] & [com.example.tunnelapp.tunnel.XrayTunnelManager]) --
 *    sebelumnya hardcoded selalu nyala, concurrency 8.
 *  - Bypass domain/IP manual, khusus mode Xray -- routing dasar TANPA perlu
 *    geosite.dat/geoip.dat (lihat kdoc panjang di XrayConfigBuilder).
 *
 * Semua perubahan di sini baru berlaku mulai KONEKSI BERIKUTNYA (bukan
 * live-reload tunnel yang sedang aktif), sama seperti kartu VPN Setting yang
 * sudah ada sebelumnya.
 */
class RoutingSettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_routing_settings)

        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        val swBypassLan = findViewById<SwitchMaterial>(R.id.swBypassLan)
        val swMux = findViewById<SwitchMaterial>(R.id.swMux)
        val etMuxConcurrency = findViewById<EditText>(R.id.etMuxConcurrency)
        val swFakeDns = findViewById<SwitchMaterial>(R.id.swFakeDns)
        val etDohUrl = findViewById<EditText>(R.id.etDohUrl)
        val etBypassDomains = findViewById<EditText>(R.id.etBypassDomains)
        val etBypassIps = findViewById<EditText>(R.id.etBypassIps)
        val btnSave = findViewById<MaterialButton>(R.id.btnSave)

        val current = VpnSettingsStore.load(this)
        swBypassLan.isChecked = current.bypassLan
        swMux.isChecked = current.muxEnabled
        etMuxConcurrency.setText(current.muxConcurrency.toString())
        swFakeDns.isChecked = current.fakeDnsEnabled
        etDohUrl.setText(current.dohUrl)
        etBypassDomains.setText(current.routingBypassDomains)
        etBypassIps.setText(current.routingBypassIps)

        btnSave.setOnClickListener {
            val concurrency = etMuxConcurrency.text.toString().trim().toIntOrNull()
                ?.coerceIn(1, 1024) ?: current.muxConcurrency
            VpnSettingsStore.save(
                this,
                current.copy(
                    bypassLan = swBypassLan.isChecked,
                    muxEnabled = swMux.isChecked,
                    muxConcurrency = concurrency,
                    fakeDnsEnabled = swFakeDns.isChecked,
                    dohUrl = etDohUrl.text.toString().trim(),
                    routingBypassDomains = etBypassDomains.text.toString(),
                    routingBypassIps = etBypassIps.text.toString()
                )
            )
            Toast.makeText(this, "Routing & Performa disimpan (berlaku koneksi berikutnya)", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
