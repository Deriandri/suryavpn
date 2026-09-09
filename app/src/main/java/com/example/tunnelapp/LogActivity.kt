package com.example.tunnelapp

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.view.LayoutInflater
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import androidx.lifecycle.lifecycleScope
import com.example.tunnelapp.databinding.ActivityLogBinding
import com.example.tunnelapp.tunnel.ConnectionStep
import com.example.tunnelapp.tunnel.StatusBus
import com.example.tunnelapp.tunnel.StepStatus
import kotlinx.coroutines.launch

/** Layar log koneksi, dipindahkan keluar dari Dashboard sesuai permintaan pemisahan menu. */
class LogActivity : AppCompatActivity() {

    companion object {
        /** Toleransi (px) untuk anggap posisi scroll "sudah di bawah" -- lihat renderTerminal(). */
        private const val NEAR_BOTTOM_SLOP_PX = 24
    }

    private lateinit var binding: ActivityLogBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnClearLog.setOnClickListener { StatusBus.clearLog() }

        lifecycleScope.launch {
            StatusBus.steps.collect { steps -> renderLogSteps(steps) }
        }

        lifecycleScope.launch {
            StatusBus.liveLog.collect { lines -> renderTerminal(lines) }
        }
    }

    /**
     * Render log real-time (event asli dari ConnectRelay/SshTunnelManager), auto-scroll ke bawah.
     *
     * PENTING (bug fix): beberapa baris (khususnya "Server Message" dari
     * SSH_MSG_USERAUTH_BANNER -- lihat SshTunnelManager.extractServerBanner)
     * berisi tag HTML sederhana ala DarkTunnel (<font color='#..'>, <b>, <br>,
     * <p>) yang DIKIRIM SERVER supaya tampil berwarna/format, bukan untuk
     * dibaca mentah sebagai teks. Sebelumnya baris-baris ini di-gabung jadi
     * satu String polos lalu di-assign langsung ke TextView.text -- karena
     * TextView TIDAK PERNAH mem-parsing HTML dari String biasa, tag-nya malah
     * muncul apa adanya (<font color='#FF00CF'>...). Sekarang tiap baris
     * di-parse lewat HtmlCompat.fromHtml() dulu (baris tanpa tag HTML tetap
     * tampil normal, hasil parsing-nya sama persis dengan teks aslinya) baru
     * digabung jadi satu Spanned lewat SpannableStringBuilder, supaya warna,
     * bold, dan line-break dari server benar-benar dirender.
     *
     * PENTING (bug fix scroll): dua hal yang sebelumnya bikin kotak Terminal
     * ini terasa "tidak bisa di-scroll":
     * 1) svTerminal ada DI DALAM ScrollView lain yang membungkus seluruh
     *    layar -- dua ScrollView bersarang arah vertikal bikin ScrollView
     *    luar yang menang menangkap gesture drag. Sudah dibereskan dengan
     *    mengganti tag-nya di activity_log.xml jadi TerminalScrollView
     *    (lihat class itu untuk detail).
     * 2) SETIAP ada baris log baru, kode ini dulu SELALU memaksa
     *    fullScroll(FOCUS_DOWN) -- jadi kalaupun (1) sudah benar, begitu user
     *    scroll ke atas buat baca log lama, baris baru masuk lagi dan
     *    langsung "menarik" balik ke bawah, kesannya seperti tidak bisa
     *    di-scroll ke atas sama sekali. Sekarang auto-scroll ke bawah HANYA
     *    dilakukan kalau user memang sudah berada di (atau dekat) posisi
     *    paling bawah SEBELUM baris baru ini masuk -- persis seperti
     *    perilaku auto-scroll aplikasi chat pada umumnya.
     */
    private fun renderTerminal(lines: List<String>) {
        val sv = binding.svTerminal
        val wasNearBottom = !sv.canScrollVertically(1) ||
            sv.scrollY + sv.height >= binding.tvTerminal.height - NEAR_BOTTOM_SLOP_PX

        val builder = SpannableStringBuilder()
        lines.forEachIndexed { index, line ->
            if (index > 0) builder.append("\n")
            // Beberapa server mengirim "##RRGGBB" (double hash, typo di sisi
            // server) pada atribut font color -- Html.fromHtml tidak mengenali
            // ini sebagai warna valid dan diam-diam jatuh ke warna default.
            // Rapikan jadi "#RRGGBB" tunggal supaya warnanya tetap muncul.
            val sanitized = line.replace(Regex("""color=(['"])##"""), "color=$1#")
            builder.append(HtmlCompat.fromHtml(sanitized, HtmlCompat.FROM_HTML_MODE_LEGACY))
        }
        binding.tvTerminal.text = builder

        if (wasNearBottom) {
            sv.post { sv.fullScroll(android.view.View.FOCUS_DOWN) }
        }
    }

    private fun renderLogSteps(steps: List<ConnectionStep>) {
        if (steps.isEmpty()) {
            binding.tvLogEmpty.visibility = android.view.View.VISIBLE
            binding.llLogSteps.visibility = android.view.View.GONE
            binding.llLogSteps.removeAllViews()
            return
        }
        binding.tvLogEmpty.visibility = android.view.View.GONE
        binding.llLogSteps.visibility = android.view.View.VISIBLE
        binding.llLogSteps.removeAllViews()
        val inflater = LayoutInflater.from(this)
        steps.forEach { step ->
            val row = inflater.inflate(R.layout.item_log_step, binding.llLogSteps, false)
            val dot = row.findViewById<android.view.View>(R.id.dot)
            val spinner = row.findViewById<ProgressBar>(R.id.spinner)
            val tvLabel = row.findViewById<TextView>(R.id.tvLabel)
            val tvDetail = row.findViewById<TextView>(R.id.tvDetail)

            tvLabel.text = step.label

            if (step.status == StepStatus.RUNNING) {
                dot.visibility = android.view.View.GONE
                spinner.visibility = android.view.View.VISIBLE
            } else {
                dot.visibility = android.view.View.VISIBLE
                spinner.visibility = android.view.View.GONE
                val colorRes = when (step.status) {
                    StepStatus.SUCCESS -> R.color.status_success
                    StepStatus.ERROR -> R.color.status_error
                    else -> R.color.status_pending
                }
                (dot.background.mutate() as GradientDrawable).setColor(ContextCompat.getColor(this, colorRes))
            }

            if (step.status == StepStatus.ERROR && !step.detail.isNullOrEmpty()) {
                tvDetail.visibility = android.view.View.VISIBLE
                tvDetail.text = step.detail
                tvDetail.setTextColor(ContextCompat.getColor(this, R.color.status_error))
            } else {
                tvDetail.visibility = android.view.View.GONE
            }

            tvLabel.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (step.status == StepStatus.SKIPPED) R.color.text_hint else R.color.text_primary
                )
            )

            binding.llLogSteps.addView(row)
        }
    }
}
