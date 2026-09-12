package com.example.tunnelapp.tunnel

import android.content.Context
import com.example.tunnelapp.model.ServerConfig
import com.example.tunnelapp.model.VpnSettings
import com.example.tunnelapp.model.VpnSettingsStore
import java.net.DatagramSocket
import java.net.Socket

/**
 * Dispatcher tipis yang memutuskan ENGINE SSH mana yang sebenarnya dipakai
 * (trilead-ssh2 lewat [SshTunnelManager], ATAU sshj lewat [SshjTunnelManager])
 * berdasarkan [VpnSettingsStore.load]`.sshEngine` -- lihat pilihan "SSH Engine"
 * di kartu "VPN Setting" pada layar Pengaturan.
 *
 * SENGAJA dibuat sebagai class terpisah (bukan mengubah [MyVpnService]
 * langsung memilih salah satu dari dua manager) supaya titik integrasi di
 * MyVpnService TETAP SATU BARIS (cuma deklarasi instance-nya yang berubah,
 * dari `SshTunnelManager()` jadi `SshEngineRouter(this)`) -- semua pemanggilan
 * `.connect(...)`/`.disconnect()`/`.disconnectForReconnect()`/`.isConnected()`
 * di MyVpnService lainnya TIDAK PERLU diubah sama sekali, karena signature
 * method di class ini sengaja dibuat identik dengan [SshTunnelManager] yang
 * lama.
 *
 * Setting [VpnSettings.sshEngine] dibaca ULANG setiap kali [connect] dipanggil
 * (bukan cuma sekali di constructor) -- konsisten dengan pola yang sudah ada
 * di MyVpnService utk setting global lain (performanceMode, httpPort, dst.:
 * di-load ulang tiap kali establishTunnel()/reconnect terjadi), supaya kalau
 * user ganti engine SESUDAH tunnel sempat konek tapi SEBELUM reconnect
 * berikutnya, engine baru itu ikut kepakai di reconnect tanpa perlu
 * memberhentikan servicenya dulu.
 *
 * [active] menyimpan engine mana yang TERAKHIR dipakai utk connect() --
 * dipakai supaya disconnect()/disconnectForReconnect()/isConnected() yang
 * dipanggil TANPA connect() baru (mis. langsung stopVpn() dari notifikasi)
 * tetap mengenai instance yang benar, BUKAN ikut membaca ulang setting (yang
 * bisa saja sudah berubah) dan salah menutup instance yang sebenarnya sedang
 * tidak aktif.
 */
class SshEngineRouter(private val context: Context) : SshEngineHandle {

    private val trileadEngine = SshTunnelManager()
    private val sshjEngine = SshjTunnelManager()

    @Volatile
    private var active: SshEngineHandle = trileadEngine

    @Throws(Exception::class)
    override fun connect(
        config: ServerConfig,
        protect: (Socket) -> Boolean,
        protectDatagram: ((DatagramSocket) -> Boolean)?,
        performanceMode: Boolean,
        compressionEnabled: Boolean,
        onUnexpectedDisconnect: (String) -> Unit
    ) {
        val settings = VpnSettingsStore.load(context)
        val engine: SshEngineHandle =
            if (settings.sshEngine == VpnSettings.ENGINE_SSHJ) sshjEngine else trileadEngine
        active = engine
        engine.connect(config, protect, protectDatagram, performanceMode, compressionEnabled, onUnexpectedDisconnect)
    }

    // disconnect()/disconnectForReconnect() SENGAJA memanggil KEDUA engine,
    // bukan cuma `active` -- jaga-jaga kalau user mengganti sshEngine di
    // Pengaturan PERSIS di antara connect() sesi lama (mis. pakai TRILEAD)
    // dan disconnect() sesi itu (yang di titik itu `active` sudah benar
    // menunjuk TRILEAD, jadi sebenarnya tidak perlu) -- tetap dipertahankan
    // sebagai jaring pengaman murah (disconnect() pada engine yang sudah
    // tidak aktif itu no-op aman, lihat isConnected()==false checks di
    // masing-masing implementasi).
    override fun disconnectForReconnect() {
        trileadEngine.disconnectForReconnect()
        sshjEngine.disconnectForReconnect()
    }

    override fun disconnect() {
        trileadEngine.disconnect()
        sshjEngine.disconnect()
    }

    override fun isConnected(): Boolean = active.isConnected()
}
