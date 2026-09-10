package com.example.tunnelapp

import android.os.Bundle
import android.text.SpannableStringBuilder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.text.HtmlCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.tunnelapp.databinding.FragmentDashboardLogBinding
import com.example.tunnelapp.tunnel.StatusBus
import kotlinx.coroutines.launch

/**
 * Halaman "Log" dari Dashboard (lihat [DashboardPagerAdapter]) -- dipindahkan
 * dari LogActivity yang berdiri sendiri menjadi halaman kanan ViewPager2 di
 * Dashboard, supaya bisa dibuka dengan geser layar ke kiri (atau tap tab
 * "Log"), bukan lagi lewat menu terpisah yang membuka layar baru.
 */
class DashboardLogFragment : Fragment() {

    companion object {
        /** Toleransi (px) untuk anggap posisi scroll "sudah di bawah" -- lihat renderTerminal(). */
        private const val NEAR_BOTTOM_SLOP_PX = 24

        /**
         * Cocokkan prefix timestamp "[HH:mm:ss]" di awal setiap baris log
         * (lihat StatusBus.log()) supaya bisa dibungkus warna cyan terpisah
         * dari sisa baris (permintaan user) -- lihat renderTerminal().
         */
        private val TIMESTAMP_PREFIX = Regex("""^(\[\d{2}:\d{2}:\d{2}\])""")
    }

    private var _binding: FragmentDashboardLogBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDashboardLogBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnClearLog.setOnClickListener { StatusBus.clearLog() }

        // Kartu "Tahapan Koneksi" (StatusBus.steps) sudah dipindahkan ke
        // halaman Main -- lihat DashboardMainFragment.renderLogSteps(). Di
        // sini tinggal collector untuk Terminal mentah.

        // viewLifecycleOwner (bukan Fragment.lifecycleScope) supaya collector
        // berhenti begitu view Fragment ini dihancurkan.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                StatusBus.liveLog.collect { lines -> renderTerminal(lines) }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /**
     * Render log real-time (event asli dari ConnectRelay/SshTunnelManager), auto-scroll ke bawah.
     * (Lihat penjelasan lengkap bug fix HTML entity & nested-scroll di LogActivity versi lama --
     * logikanya dipindah apa adanya ke sini.)
     */
    private fun renderTerminal(lines: List<String>) {
        val b = _binding ?: return
        val sv = b.svTerminal
        val wasNearBottom = !sv.canScrollVertically(1) ||
            sv.scrollY + sv.height >= b.tvTerminal.height - NEAR_BOTTOM_SLOP_PX

        // Hex warna timestamp cyan (permintaan user), diambil dari resource
        // supaya satu sumber kebenaran sama seperti tempat lain di app.
        val timestampHex = String.format(
            "#%06X", 0xFFFFFF and androidx.core.content.ContextCompat.getColor(
                requireContext(), R.color.terminal_timestamp
            )
        )

        val builder = SpannableStringBuilder()
        lines.forEachIndexed { index, line ->
            if (index > 0) builder.append("\n")
            val sanitized = line.replace(Regex("""color=(['"])##"""), "color=$1#")
            // Bungkus prefix "[HH:mm:ss]" di awal baris dengan warna cyan,
            // sisa baris (bisa saja sudah punya tag <font> sendiri dari
            // banner server) dibiarkan apa adanya.
            val tsMatch = TIMESTAMP_PREFIX.find(sanitized)
            val withTimestampColor = if (tsMatch != null) {
                "<font color='$timestampHex'>${tsMatch.value}</font>" + sanitized.substring(tsMatch.value.length)
            } else {
                sanitized
            }
            builder.append(HtmlCompat.fromHtml(withTimestampColor, HtmlCompat.FROM_HTML_MODE_LEGACY))
        }
        b.tvTerminal.text = builder

        if (wasNearBottom) {
            sv.post { sv.fullScroll(View.FOCUS_DOWN) }
        }
    }
}
