package com.example.tunnelapp.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.ScrollView

/**
 * ScrollView khusus untuk kotak "Terminal" di LogActivity.
 *
 * BUG FIX (update): versi sebelumnya langsung memanggil
 * requestDisallowInterceptTouchEvent(true) begitu jari MENYENTUH
 * (ACTION_DOWN), sebelum tahu arah geserannya. Itu dibuat untuk kasus lama
 * (ScrollView di dalam ScrollView, dua-duanya arah vertikal) yang sekarang
 * sudah tidak ada lagi (ScrollView pembungkus luar sudah dibuang di
 * fragment_dashboard_log.xml). Masalahnya, halaman Log ini juga ada DI DALAM
 * ViewPager2 (tab "Main | Log", lihat activity_dashboard.xml +
 * DashboardPagerAdapter) -- karena disallow-intercept dipanggil dari
 * ACTION_DOWN, ViewPager2 ikut "dikunci" dan jadi tidak bisa membaca gesture
 * geser horizontal selama jari masih berada di area Terminal, sehingga tab
 * tidak bisa digeser dari Log ke Main (harus tap tulisan "Main").
 *
 * Perbaikannya: tunggu sampai ACTION_MOVE dan baru kunci parent kalau
 * geserannya memang lebih vertikal daripada horizontal (dy > dx). Kalau jari
 * geser ke samping (mau pindah tab), parent (ViewPager2) tetap dibiarkan
 * menangkap gesture itu seperti biasa.
 */
class TerminalScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ScrollView(context, attrs, defStyleAttr) {

    private var downX = 0f
    private var downY = 0f

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                // Belum tahu arah geseran jari, jangan kunci parent dulu
                // supaya ViewPager2 masih bisa mendeteksi swipe horizontal.
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = Math.abs(ev.x - downX)
                val dy = Math.abs(ev.y - downY)
                if (dy > dx) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                parent?.requestDisallowInterceptTouchEvent(false)
        }
        return super.onInterceptTouchEvent(ev)
    }
}
