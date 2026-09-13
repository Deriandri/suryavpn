package com.example.tunnelapp.tunnel

import android.content.Context
import com.example.tunnelapp.model.VpnSettings
import com.example.tunnelapp.model.VpnSettingsStore

/**
 * Dispatcher tipis yang memutuskan [TunEngine] mana yang benar-benar dipakai
 * (hev-socks5-tunnel lewat [HevSocks5Engine], ATAU badvpn-tun2socks lewat
 * [BadVpnEngine]) berdasarkan `VpnSettingsStore.load(context).tunEngine` --
 * lihat pilihan "Tunnel Engine" di kartu "VPN Setting" pada layar Pengaturan.
 *
 * Pola class ini SENGAJA dibuat identik dengan [SshEngineRouter] (baca juga
 * catatan panjang di sana) supaya titik integrasi di [MyVpnService] tetap
 * SATU BARIS: cuma ganti `HevSocks5Engine()` jadi `TunEngineRouter(this)` di
 * `startTunEngine()`, signature [start]/[stop] identik dengan [TunEngine]
 * biasa jadi tidak ada pemanggil lain yang perlu berubah.
 *
 * Setting dibaca ULANG setiap [start] dipanggil (bukan cuma sekali di
 * constructor) -- konsisten dengan [SshEngineRouter.connect] dan pola
 * reload-setting lain di MyVpnService, supaya user ganti engine di
 * Pengaturan langsung kepakai di reconnect berikutnya tanpa perlu
 * menghentikan service dulu.
 *
 * [active] menyimpan engine mana yang TERAKHIR dipakai utk [start] -- dipakai
 * supaya [stop] yang mungkin dipanggil tanpa [start] baru sebelumnya (jarang
 * terjadi di alur normal MyVpnService, tapi tetap dijaga demi konsistensi
 * dengan pola SshEngineRouter) tetap mengenai instance yang benar.
 */
class TunEngineRouter(private val context: Context) : TunEngine {

    private val hevEngine = HevSocks5Engine()
    private val badvpnEngine = BadVpnEngine(context)

    @Volatile
    private var active: TunEngine = hevEngine

    override fun start(
        tunFd: Int,
        tunAddress: String,
        mtu: Int,
        socksHost: String,
        socksPort: Int,
        onUnexpectedStop: (() -> Unit)?
    ) {
        val settings = VpnSettingsStore.load(context)
        val engine: TunEngine =
            if (settings.tunEngine == VpnSettings.TUN_ENGINE_BADVPN) badvpnEngine else hevEngine
        active = engine
        engine.start(tunFd, tunAddress, mtu, socksHost, socksPort, onUnexpectedStop)
    }

    // stop() SENGAJA cuma memanggil `active` (BEDA dengan
    // SshEngineRouter.disconnect() yang memanggil KEDUA engine) -- karena di
    // sini cuma ada SATU TunEngine yang bisa hidup dalam satu waktu (satu fd
    // TUN yang sama, tidak seperti SSH yang koneksinya independen dari TUN),
    // jadi memanggil stop() pada engine yang TIDAK PERNAH di-start justru
    // beresiko (mis. BadVpnEngine.stop() no-op aman krn `process` null, TAPI
    // tetap lebih jelas/aman kalau hanya menyentuh yang benar-benar aktif).
    override fun stop() {
        active.stop()
    }
}
