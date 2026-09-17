package com.example.tunnelapp.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

/**
 * FITUR BARU (permintaan user: "Statistik pemakaian data & kecepatan
 * real-time, grafik naik-turun") -- grafik garis kecil ("sparkline") yang
 * dipakai di kartu "Download"/"Upload" pada [DashboardMainFragment].
 *
 * Sengaja custom View ringan (bukan library chart pihak ketiga) supaya
 * tidak menambah dependency baru cuma untuk satu grafik kecil sederhana --
 * cukup Canvas biasa. Menerima daftar sampel kecepatan (bytes/detik) lewat
 * [submitSample]/[setSamples], menyimpan maksimal [maxSamples] titik
 * terakhir (titik lama otomatis dibuang begitu penuh, efek "geser ke kiri"
 * berjalan), lalu digambar ulang sebagai garis halus + area terisi
 * ber-gradasi transparan di bawahnya.
 *
 * Auto-scale: sumbu-Y selalu mengikuti nilai TERBESAR yang sedang tampil di
 * layar saat ini (bukan skala tetap) supaya naik-turunnya tetap kelihatan
 * jelas baik saat kecepatan kecil (KB/s) maupun besar (MB/s).
 */
class SparklineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** Jumlah titik maksimal yang ditampilkan sekaligus di grafik. */
    var maxSamples: Int = 40
        set(value) {
            field = value.coerceAtLeast(2)
            trimToMax()
            invalidate()
        }

    /** Warna garis + area terisi di bawahnya (alpha area diatur otomatis). */
    var lineColor: Int = 0xFF4F46E5.toInt()
        set(value) {
            field = value
            linePaint.color = value
            invalidate()
        }

    private val samples = ArrayDeque<Float>()

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = lineColor
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val linePath = Path()
    private val fillPath = Path()

    private fun trimToMax() {
        while (samples.size > maxSamples) samples.removeFirst()
    }

    /** Tambah satu sampel baru (mis. bytes/detik saat ini) di ujung kanan grafik. */
    fun submitSample(value: Float) {
        samples.addLast(value.coerceAtLeast(0f))
        trimToMax()
        invalidate()
    }

    /** Ganti seluruh isi grafik sekaligus (dipakai saat reset/reconnect). */
    fun setSamples(values: List<Float>) {
        samples.clear()
        samples.addAll(values.takeLast(maxSamples))
        invalidate()
    }

    fun clear() {
        samples.clear()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fillPaint.shader = if (h > 0) {
            LinearGradient(
                0f, 0f, 0f, h.toFloat(),
                (lineColor and 0x00FFFFFF) or 0x55000000,
                (lineColor and 0x00FFFFFF) or 0x00000000,
                Shader.TileMode.CLAMP
            )
        } else null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f || samples.size < 2) return

        val maxValue = (samples.maxOrNull() ?: 0f).coerceAtLeast(1f)
        val stepX = w / (maxSamples - 1).coerceAtLeast(1)
        // Titik-titik dirapatkan ke kanan kalau jumlah sampel belum sampai
        // maxSamples, supaya grafik "muncul dari kanan" & bergeser ke kiri
        // seiring waktu, bukan langsung memenuhi lebar dari sampel pertama.
        val startIndex = maxSamples - samples.size

        linePath.reset()
        fillPath.reset()

        // Padding vertikal kecil supaya puncak grafik tidak mepet ke tepi atas.
        val topPadding = h * 0.12f
        val usableHeight = h - topPadding

        samples.forEachIndexed { i, value ->
            val x = (startIndex + i) * stepX
            val ratio = value / maxValue
            val y = topPadding + (usableHeight * (1f - ratio))
            if (i == 0) {
                linePath.moveTo(x, y)
                fillPath.moveTo(x, h)
                fillPath.lineTo(x, y)
            } else {
                linePath.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }
        val lastX = (startIndex + samples.size - 1) * stepX
        fillPath.lineTo(lastX, h)
        fillPath.close()

        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(linePath, linePaint)
    }
}
