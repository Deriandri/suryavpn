package com.example.tunnelapp.tunnel

import android.content.Context
import android.util.Base64
import com.example.tunnelapp.model.ProfileStore
import com.example.tunnelapp.model.SavedConfig
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * FITUR BARU (parity dengan V2RayNG "Subscription"): sebelumnya
 * [XrayConfigActivity] cuma bisa menambah SATU akun Xray sekaligus lewat
 * satu link share (vmess://, vless://, trojan://) yang ditempel manual.
 * V2RayNG (dan hampir semua client Xray lain) juga mendukung "subscription
 * URL" -- satu URL yang isinya BANYAK link share sekaligus (biasanya di-
 * update berkala oleh provider), supaya user tidak perlu tempel link satu-
 * satu tiap provider menerbitkan server baru.
 *
 * Format body subscription yang didukung (dua-duanya dicoba, sesuai
 * konvensi de-facto v2rayN/v2rayNG):
 *  1. Base64 tunggal yang isinya banyak baris link (paling umum) -- di-decode
 *     dulu, baru dipecah per baris.
 *  2. Teks polos, satu link per baris (beberapa provider menerbitkan
 *     langsung tanpa di-base64 lagi).
 *
 * Baris yang bukan link vmess/vless/trojan yang valid (komentar, baris
 * kosong, format lain yang belum didukung parser ini) dilewati diam-diam --
 * TIDAK menggagalkan seluruh proses import, cuma tidak ikut ditambahkan.
 *
 * SENGAJA taruh di package `tunnel` (bukan `model`) karena fungsinya murni
 * "ambil dari jaringan lalu proses", sama seperti [XrayTunnelManager]/
 * [XrayLinkParser] -- bukan penyimpanan data itu sendiri (itu tetap lewat
 * [ProfileStore] seperti biasa).
 */
object SubscriptionImporter {

    data class ImportResult(
        val importedCount: Int,
        val skippedCount: Int
    )

    /**
     * BLOCKING (network I/O) -- panggil dari coroutine Dispatchers.IO, JANGAN
     * dari main thread.
     *
     * @throws Exception kalau URL gagal diakses sama sekali (network error,
     *         HTTP error, dst.) -- ditangkap & ditampilkan apa adanya oleh
     *         pemanggil (lihat ToolsActivity), BUKAN di-swallow di sini,
     *         supaya user tahu kalau memang URL/koneksinya yang bermasalah
     *         (beda dari baris per-link yang gagal parse, yang memang
     *         sengaja dilewati diam-diam di atas).
     */
    @Throws(Exception::class)
    fun importFromUrl(context: Context, subscriptionUrl: String): ImportResult {
        val url = subscriptionUrl.trim()
        require(url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)) {
            "URL subscription harus diawali http:// atau https://"
        }

        val body = fetchBody(url)
        val links = extractLinks(body)

        var imported = 0
        var skipped = 0
        for (link in links) {
            val parsed = runCatching { XrayLinkParser.parse(link) }.getOrNull()
            if (parsed == null) {
                skipped++
                continue
            }
            val accountName = parsed.remark.ifBlank { "${parsed.address}:${parsed.port}" }
            runCatching {
                ProfileStore.upsert(
                    context,
                    null,
                    SavedConfig(
                        host = "",
                        port = 22,
                        username = "",
                        password = "",
                        modeIndex = 5,
                        sni = "",
                        payload = "",
                        proxyHost = "",
                        proxyPort = "",
                        tlsVersion = "",
                        useWebSocket = false,
                        wsPath = "",
                        proxyRawMode = false,
                        xrayLink = link,
                        accountName = accountName
                    )
                )
            }.onSuccess { imported++ }.onFailure { skipped++ }
        }

        return ImportResult(importedCount = imported, skippedCount = skipped)
    }

    private fun fetchBody(urlStr: String): String {
        val connection = URL(urlStr).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        connection.requestMethod = "GET"
        connection.setRequestProperty("User-Agent", "SuryaVPN/1.0 (subscription-import)")
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("Server subscription membalas HTTP $code")
            }
            return BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Coba anggap seluruh body sebagai SATU blok base64 (format subscription
     * v2rayN/v2rayNG paling umum) dulu -- kalau hasil decode-nya memuat
     * minimal satu baris berawalan vmess/vless/trojan://, pakai itu. Kalau
     * tidak (bukan base64, atau base64 valid tapi isinya bukan daftar link),
     * fallback anggap body itu sendiri teks polos satu-link-per-baris.
     */
    private fun extractLinks(body: String): List<String> {
        val trimmedBody = body.trim()

        val decodedAttempt = runCatching {
            String(Base64.decode(trimmedBody, Base64.DEFAULT))
        }.getOrNull()

        val candidateText = if (decodedAttempt != null && containsSupportedLink(decodedAttempt)) {
            decodedAttempt
        } else {
            trimmedBody
        }

        return candidateText.lines()
            .map { it.trim() }
            .filter { isSupportedLink(it) }
    }

    private fun containsSupportedLink(text: String): Boolean =
        text.lines().any { isSupportedLink(it.trim()) }

    private fun isSupportedLink(line: String): Boolean =
        line.startsWith("vmess://", ignoreCase = true) ||
            line.startsWith("vless://", ignoreCase = true) ||
            line.startsWith("trojan://", ignoreCase = true)
}
