package com.example.tunnelapp.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.ScrollView

/**
 * ScrollView khusus untuk kotak "Terminal" di LogActivity.
 *
 * PENTING (bug fix): layout aslinya menaruh ScrollView ini (svTerminal) DI
 * DALAM ScrollView lain yang membungkus seluruh layar (dua ScrollView
 * bersarang, sama-sama arah vertikal). Pada ScrollView bawaan Android, kalau
 * ada dua ScrollView bersarang begini, ScrollView LUAR hampir selalu yang
 * "menang" duluan menangkap gesture drag vertikal sebelum ScrollView DALAM
 * sempat memprosesnya -- akibatnya area Terminal terlihat seperti tidak bisa
 * di-scroll sendiri (drag di area itu malah menggerakkan seluruh halaman).
 *
 * Perbaikannya: begitu jari mulai menyentuh (ACTION_DOWN) di dalam area ini,
 * langsung minta parent (ScrollView luar) untuk TIDAK ikut campur
 * (requestDisallowInterceptTouchEvent(true)), supaya semua event drag
 * selanjutnya benar-benar diproses ScrollView ini sendiri. Begitu jari
 * diangkat/dibatalkan, izin itu dikembalikan supaya scroll halaman luar tetap
 * normal di luar area Terminal.
 */
class TerminalScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ScrollView(context, attrs, defStyleAttr) {

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> parent?.requestDisallowInterceptTouchEvent(true)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                parent?.requestDisallowInterceptTouchEvent(false)
        }
        return super.onTouchEvent(ev)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN) {
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        return super.onInterceptTouchEvent(ev)
    }
}
