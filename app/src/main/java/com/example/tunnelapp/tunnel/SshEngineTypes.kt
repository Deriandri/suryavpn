package com.example.tunnelapp.tunnel

import com.example.tunnelapp.model.ServerConfig
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramSocket
import java.net.Socket

/**
 * Abstraksi buat DUA engine SSH yang sekarang didukung app ini:
 *  - trilead-ssh2 (SshTunnelManager, ENGINE ASLI/default)
 *  - sshj (SshjTunnelManager, engine baru -- satu-satunya yang beneran
 *    mendukung kompresi zlib, lihat VpnSettingsStore.compressionEnabled)
 *
 * Ditambahkan supaya [Socks5Server] (yang isinya murni protokol SOCKS5 buatan
 * sendiri) TIDAK perlu tahu/terikat ke tipe koneksi milik satu library SSH
 * tertentu -- dia cukup tahu "channel direct-tcpip" secara umum lewat
 * [DirectTcpipForwarder], dan "koneksi SSH yang sedang aktif" secara umum
 * lewat [SshConnectionHandle]. [SshEngineRouter] yang memutuskan engine mana
 * yang benar-benar dipakai (baca VpnSettingsStore.sshEngine), MyVpnService
 * sendiri tidak perlu tahu/berubah sama sekali.
 */

/**
 * Satu channel "direct-tcpip" (RFC 4254 SS7.2) yang sudah terbuka lewat
 * tunnel SSH yang sedang aktif -- dipakai [Socks5Server] utk meneruskan satu
 * koneksi TCP (perintah CONNECT) atau satu query DNS-over-TCP (fallback UDP
 * ASSOCIATE tanpa UDPGW).
 */
interface DirectTcpipForwarder {
    val inputStream: InputStream
    val outputStream: OutputStream
    fun close()
}

/**
 * Satu koneksi SSH yang sedang aktif -- cukup tahu cara buka channel
 * direct-tcpip baru ke host:port tertentu. Diimplementasikan oleh
 * TrileadConnectionHandle (bungkus com.trilead.ssh2.Connection) dan
 * SshjConnectionHandle (bungkus net.schmizz.sshj.SSHClient).
 */
interface SshConnectionHandle {
    @Throws(Exception::class)
    fun openDirectTcpip(host: String, port: Int): DirectTcpipForwarder
}

/**
 * Kontrak seragam "engine SSH" dipakai [SshEngineRouter] -- sama persis
 * dengan method publik [SshTunnelManager] yang sudah ada dari awal, supaya
 * MyVpnService tidak perlu diubah di titik pemanggilannya sama sekali,
 * cuma deklarasi instance-nya (lihat SshEngineRouter).
 */
interface SshEngineHandle {
    @Throws(Exception::class)
    fun connect(
        config: ServerConfig,
        protect: (Socket) -> Boolean,
        protectDatagram: ((DatagramSocket) -> Boolean)?,
        performanceMode: Boolean,
        compressionEnabled: Boolean,
        onUnexpectedDisconnect: (String) -> Unit
    )

    fun disconnect()
    fun disconnectForReconnect()
    fun isConnected(): Boolean
}
