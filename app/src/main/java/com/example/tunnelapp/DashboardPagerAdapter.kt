package com.example.tunnelapp

import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

/**
 * Adapter untuk [androidx.viewpager2.widget.ViewPager2] di Dashboard: halaman
 * 0 = "Main" ([DashboardMainFragment], dashboard yang sudah ada), halaman 1 =
 * "Log" ([DashboardLogFragment], dipindahkan dari LogActivity ke bagian kanan
 * Dashboard -- geser layar ke kiri, atau tap tab "Log", untuk membukanya).
 */
class DashboardPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {

    companion object {
        const val PAGE_MAIN = 0
        const val PAGE_LOG = 1
        const val PAGE_COUNT = 2
    }

    override fun getItemCount(): Int = PAGE_COUNT

    override fun createFragment(position: Int) = when (position) {
        PAGE_LOG -> DashboardLogFragment()
        else -> DashboardMainFragment()
    }
}
