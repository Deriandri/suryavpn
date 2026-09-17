package com.example.tunnelapp.tunnel

import com.example.tunnelapp.model.AppLanguage
import com.example.tunnelapp.model.LocaleStore

/**
 * FITUR BARU (permintaan user, "log terminal ikut ganti bahasa Indonesia/
 * Inggris sesuai Pengaturan -> Bahasa"): teks log tunnel/reconnect/payload
 * yang SEBELUMNYA hardcode Indonesia langsung di StatusBus.log(...)/
 * StatusBus.state, sekarang dipusatkan di sini biar kedua bahasanya gampang
 * dicek berdampingan.
 *
 * Sengaja TIDAK lewat resource strings.xml (R.string.xxx) seperti teks UI
 * biasa: [MyVpnService] memang Context (bisa getString), tapi [ConnectRelay]
 * BUKAN Context (class biasa, dipakai di luar siklus hidup Activity/Service)
 * -- fungsi Kotlin biasa di sini bisa dipanggil dari keduanya tanpa perlu
 * inject Context ke ConnectRelay. Bahasa aktif dibaca langsung dari
 * [LocaleStore.current] tiap dipanggil (tidak disimpan/cache), jadi otomatis
 * ikut berubah walau user ganti bahasa selagi tunnel sedang jalan.
 */
object LogI18n {

    private val isEnglish: Boolean get() = LocaleStore.current() == AppLanguage.ENGLISH

    // --- Alasan tunnel putus ---

    fun deviceNetworkLost(): String =
        if (isEnglish) "Device network lost (mobile data/WiFi off)"
        else "Jaringan device terputus (data/WiFi mati)"

    /**
     * Terjemahkan pesan exception Java/sshj yang panjang & teknis (raw
     * socket, IP:port, "after 15000ms", nama exception) jadi satu kalimat
     * pendek di bahasa yang aktif. Pesan yang sudah pendek & bukan dari
     * exception (mis. hasil [deviceNetworkLost]) tidak disentuh karena tidak
     * cocok pola manapun di bawah.
     */
    fun humanizeReason(raw: String): String {
        val r = raw.lowercase()
        return when {
            "enetunreach" in r || "network is unreachable" in r ->
                if (isEnglish) "network unreachable" else "jaringan tidak terjangkau"
            "ehostunreach" in r || "no route to host" in r ->
                if (isEnglish) "host unreachable" else "host tidak terjangkau"
            "econnrefused" in r || "connection refused" in r ->
                if (isEnglish) "connection refused by server" else "koneksi ditolak server"
            "socket closed" in r || "socket is closed" in r ->
                if (isEnglish) "connection closed" else "koneksi terputus"
            "unknownhost" in r ->
                if (isEnglish) "failed to resolve server address" else "gagal resolve alamat server"
            "after 15000ms" in r && "connect failed" in r ->
                if (isEnglish) "failed to connect to server (timeout)" else "gagal terhubung ke server (timeout)"
            "timed out" in r || "timeout" in r ->
                if (isEnglish) "connection timed out" else "waktu koneksi habis"
            "software caused connection abort" in r ->
                if (isEnglish) "connection aborted by system" else "koneksi dibatalkan sistem"
            "auth fail" in r || "authentication" in r ->
                if (isEnglish) "authentication failed" else "autentikasi gagal"
            else -> raw
        }
    }

    // --- Siklus reconnect ---

    fun tunnelDisconnectedAutoReconnectOff(reason: String): String =
        if (isEnglish) "Tunnel disconnected ($reason) -- auto reconnect off (VPN Setting), not retrying"
        else "Tunnel terputus ($reason) -- auto reconnect nonaktif (VPN Setting), tidak mencoba nyambung ulang"

    fun stateDisconnectedAutoReconnectOff(reason: String): String =
        if (isEnglish) "Disconnected: tunnel down ($reason), auto reconnect off"
        else "Terputus: tunnel mati ($reason), auto reconnect nonaktif"

    fun tunnelDisconnectedGivingUp(reason: String, maxAttempts: Int): String =
        if (isEnglish) "Tunnel disconnected ($reason) -- reached $maxAttempts reconnect attempts, giving up entirely"
        else "Tunnel terputus ($reason) -- sudah $maxAttempts kali percobaan reconnect, menyerah & berhenti total"

    fun stateReconnectFailedStopped(maxAttempts: Int): String =
        if (isEnglish) "Disconnected: reconnect failed $maxAttempts times, stopped"
        else "Terputus: reconnect gagal $maxAttempts kali, dihentikan"

    fun notificationReconnectFailedStopped(maxAttempts: Int): String =
        if (isEnglish) "Reconnect failed $maxAttempts times -- stopped"
        else "Reconnect gagal $maxAttempts kali -- dihentikan"

    fun tunnelDisconnectedRetrying(reason: String, attempt: Int, maxAttempts: Int, delaySec: Long): String =
        if (isEnglish) "Tunnel disconnected ($reason) -- auto reconnect attempt $attempt/$maxAttempts in ${delaySec}s"
        else "Tunnel terputus ($reason) -- reconnect otomatis percobaan $attempt/$maxAttempts dalam ${delaySec}s"

    fun stateReconnecting(attempt: Int, maxAttempts: Int): String =
        if (isEnglish) "Tunnel disconnected — auto reconnecting (attempt $attempt/$maxAttempts)..."
        else "Tunnel terputus — reconnect otomatis (percobaan $attempt/$maxAttempts)..."

    fun notificationReconnecting(attempt: Int, maxAttempts: Int): String =
        if (isEnglish) "Auto reconnecting (attempt $attempt/$maxAttempts)..."
        else "Reconnect otomatis (percobaan $attempt/$maxAttempts)..."

    fun reconnectFailedReason(reason: String): String =
        if (isEnglish) "Reconnect failed: $reason" else "Reconnect gagal: $reason"

    // --- Gagal connect awal (bukan reconnect) ---

    fun failedReason(reason: String): String =
        if (isEnglish) "Failed: $reason" else "Gagal: $reason"

    // --- Stop manual ---

    fun disconnectingByUser(): String =
        if (isEnglish) "Disconnecting tunnel (user requested)..." else "Memutuskan tunnel (diminta pengguna)..."

    fun tunnelDisconnectedAllClosed(): String =
        if (isEnglish) "Tunnel disconnected, all connections closed." else "Tunnel terputus, semua koneksi ditutup."

    // --- Payload ---

    fun sendingPayloadHidden(): String = "Sending Payload"

    fun sendingPayload(text: String): String = "Sending Payload: $text"

    // --- Notifikasi: tidak ada akun tersimpan ---

    fun noSavedAccountForConnect(): String =
        if (isEnglish) "No saved account for Connect from notification."
        else "Tidak ada akun tersimpan untuk Connect dari notifikasi."

    fun noSavedAccountForReconnect(): String =
        if (isEnglish) "No saved account for Reconnect from notification."
        else "Tidak ada akun tersimpan untuk Reconnect dari notifikasi."

    // --- Pembuatan TUN interface ---

    fun creatingTunInterface(): String =
        if (isEnglish) "Creating VPN interface (TUN)..." else "Membuat antarmuka VPN (TUN)..."

    fun waitingForPreviousDisconnect(): String =
        if (isEnglish) "Waiting for previous disconnect to finish..."
        else "Menunggu proses disconnect sebelumnya selesai..."

    fun failedToCreateTunInterface(message: String?): String =
        if (isEnglish) "Failed to create TUN interface: $message"
        else "Gagal membuat TUN interface: $message"

    fun connectingToHostPort(host: String, port: Int): String =
        if (isEnglish) "Connecting to $host:$port..." else "Menghubungkan ke $host:$port..."

    // --- DNS ---

    fun invalidDnsIpIgnored(label: String, value: String): String =
        if (isEnglish) "$label \"$value\" is not a valid IP address -- ignored"
        else "$label \"$value\" bukan alamat IP valid -- diabaikan"

    fun dnsEmptyXrayInternalResolver(): String =
        if (isEnglish) "[DNS] DNS1/DNS2 empty -- Xray mode uses Xray's own internal DNS resolver (not TUN)"
        else "[DNS] DNS1/DNS2 kosong -- mode Xray pakai resolver DNS internal Xray sendiri (bukan TUN)"

    fun dnsEmptyUsingDefault(effectiveDefaultDns: String): String =
        if (isEnglish) "[DNS] DNS1/DNS2 empty -- using default DNS $effectiveDefaultDns"
        else "[DNS] DNS1/DNS2 kosong -- pakai DNS default $effectiveDefaultDns"

    fun dnsEmptyDefaultFailed(effectiveDefaultDns: String): String =
        if (isEnglish) "[DNS] DNS1/DNS2 empty AND default DNS $effectiveDefaultDns failed to apply -- NO DNS set on TUN"
        else "[DNS] DNS1/DNS2 kosong DAN DNS default $effectiveDefaultDns gagal dipasang -- TIDAK ada DNS di TUN"

    fun dnsEmptyAndDefaultDisabled(): String =
        if (isEnglish) "[DNS] DNS1/DNS2 empty AND Auto Default DNS is off -- NO DNS set on TUN"
        else "[DNS] DNS1/DNS2 kosong DAN DNS Default Otomatis dimatikan -- TIDAK ada DNS dipasang ke TUN"

    // --- Tunnel aktif ---

    fun xrayConnectedActivatingTunnel(): String =
        if (isEnglish) "Xray-core connected. Activating tunnel..." else "Xray-core tersambung. Mengaktifkan tunnel..."

    fun sshConnectedActivatingTunnel(): String =
        if (isEnglish) "SSH connected. Activating tunnel..." else "SSH tersambung. Mengaktifkan tunnel..."

    fun tunnelActiveVerified(): String =
        if (isEnglish) "Tunnel active — real traffic check succeeded, all device traffic goes through the tunnel."
        else "Tunnel aktif — verifikasi trafik nyata berhasil, semua koneksi device lewat tunnel."

    fun tunnelActiveAllTrafficViaSsh(): String =
        if (isEnglish) "Tunnel active — all device traffic via SSH" else "Tunnel aktif — semua trafik device lewat SSH"

    fun tunnelUpNoRealTraffic(): String =
        if (isEnglish) "Tunnel is up but no real traffic is passing through it " +
            "-- possibly the device network is down, OR the server account/credentials are no longer valid/expired"
        else "Tunnel nyala tapi tidak ada trafik nyata yang balik lewat tunnel " +
            "-- kemungkinan jaringan device mati, ATAU akun/kredensial server sudah tidak valid/expired"

    // --- Proxy HTTP lokal ---

    fun localHttpProxyActive(port: Int): String =
        if (isEnglish) "Local HTTP proxy active on 127.0.0.1:$port" else "Proxy HTTP lokal aktif di 127.0.0.1:$port"

    fun localHttpProxyFailed(port: Int, message: String?): String =
        if (isEnglish) "Local HTTP proxy FAILED to start on port $port: $message"
        else "Proxy HTTP lokal GAGAL dinyalakan di port $port: $message"

    // --- Disconnect ---

    fun disconnectingState(): String = if (isEnglish) "Disconnecting..." else "Memutuskan..."

    fun disconnectedState(): String = if (isEnglish) "Disconnected" else "Terputus"

    /**
     * State "gagal" bisa berupa "Gagal: ..." (ID) ATAU "Failed: ..." (EN)
     * tergantung bahasa aktif SAAT pesan itu ditulis -- dipakai buat guard
     * "jangan timpa pesan gagal yang sudah lebih spesifik" di stopVpn(), jadi
     * HARUS cek kedua awalan, bukan cuma "Gagal" seperti sebelumnya (kalau
     * tidak, guard-nya diam-diam tidak pernah kena waktu bahasa aktif EN).
     */
    fun isFailedState(state: String): Boolean =
        state.startsWith("Gagal") || state.startsWith("Failed")

    // --- TLS handshake & proxy CONNECT (ConnectRelay) ---

    fun tlsHandshakeSuccess(sniHost: String, protocol: String, certVerificationDisabled: Boolean): String {
        val certNote = if (certVerificationDisabled) {
            if (isEnglish) ", certificate verification DISABLED" else ", verifikasi sertifikat DINONAKTIFKAN"
        } else ""
        return if (isEnglish) "TLS handshake succeeded (SNI: $sniHost, $protocol$certNote)"
        else "TLS handshake sukses (SNI: $sniHost, $protocol$certNote)"
    }

    fun noResponseFromProxy(): String =
        if (isEnglish) "no response from proxy" else "tidak ada respons dari proxy"
}
