package com.example.tunnelapp

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.tunnelapp.databinding.FragmentDashboardLogBinding
import com.example.tunnelapp.tunnel.ConnectionStep
import com.example.tunnelapp.tunnel.StatusBus
import com.example.tunnelapp.tunnel.StepStatus
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

        // viewLifecycleOwner (bukan Fragment.lifecycleScope) supaya collector
        // berhenti begitu view Fragment ini dihancurkan.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                StatusBus.steps.collect { steps -> renderLogSteps(steps) }
            }
        }

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

        val builder = SpannableStringBuilder()
        lines.forEachIndexed { index, line ->
            if (index > 0) builder.append("\n")
            val sanitized = line.replace(Regex("""color=(['"])##"""), "color=$1#")
            builder.append(HtmlCompat.fromHtml(sanitized, HtmlCompat.FROM_HTML_MODE_LEGACY))
        }
        b.tvTerminal.text = builder

        if (wasNearBottom) {
            sv.post { sv.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun renderLogSteps(steps: List<ConnectionStep>) {
        val b = _binding ?: return
        if (steps.isEmpty()) {
            b.tvLogEmpty.visibility = View.VISIBLE
            b.llLogSteps.visibility = View.GONE
            b.llLogSteps.removeAllViews()
            return
        }
        b.tvLogEmpty.visibility = View.GONE
        b.llLogSteps.visibility = View.VISIBLE
        b.llLogSteps.removeAllViews()
        val ctx = requireContext()
        val inflater = LayoutInflater.from(ctx)
        steps.forEach { step ->
            val row = inflater.inflate(R.layout.item_log_step, b.llLogSteps, false)
            val dot = row.findViewById<View>(R.id.dot)
            val spinner = row.findViewById<ProgressBar>(R.id.spinner)
            val tvLabel = row.findViewById<TextView>(R.id.tvLabel)
            val tvDetail = row.findViewById<TextView>(R.id.tvDetail)

            tvLabel.text = step.label

            if (step.status == StepStatus.RUNNING) {
                dot.visibility = View.GONE
                spinner.visibility = View.VISIBLE
            } else {
                dot.visibility = View.VISIBLE
                spinner.visibility = View.GONE
                val colorRes = when (step.status) {
                    StepStatus.SUCCESS -> R.color.status_success
                    StepStatus.ERROR -> R.color.status_error
                    else -> R.color.status_pending
                }
                (dot.background.mutate() as GradientDrawable).setColor(ContextCompat.getColor(ctx, colorRes))
            }

            if (step.status == StepStatus.ERROR && !step.detail.isNullOrEmpty()) {
                tvDetail.visibility = View.VISIBLE
                tvDetail.text = step.detail
                tvDetail.setTextColor(ContextCompat.getColor(ctx, R.color.status_error))
            } else {
                tvDetail.visibility = View.GONE
            }

            tvLabel.setTextColor(
                ContextCompat.getColor(
                    ctx,
                    if (step.status == StepStatus.SKIPPED) R.color.text_hint else R.color.text_primary
                )
            )

            b.llLogSteps.addView(row)
        }
    }
}
