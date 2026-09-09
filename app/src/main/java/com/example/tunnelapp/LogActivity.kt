package com.example.tunnelapp

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.tunnelapp.databinding.ActivityLogBinding
import com.example.tunnelapp.tunnel.ConnectionStep
import com.example.tunnelapp.tunnel.StatusBus
import com.example.tunnelapp.tunnel.StepStatus
import kotlinx.coroutines.launch

/** Layar log koneksi, dipindahkan keluar dari Dashboard sesuai permintaan pemisahan menu. */
class LogActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        lifecycleScope.launch {
            StatusBus.steps.collect { steps -> renderLogSteps(steps) }
        }

        lifecycleScope.launch {
            StatusBus.liveLog.collect { lines -> renderTerminal(lines) }
        }
    }

    /** Render log mentah real-time (event asli dari ConnectRelay/SshTunnelManager), auto-scroll ke bawah. */
    private fun renderTerminal(lines: List<String>) {
        binding.tvTerminal.text = lines.joinToString("\n")
        binding.svTerminal.post {
            binding.svTerminal.fullScroll(android.view.View.FOCUS_DOWN)
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
