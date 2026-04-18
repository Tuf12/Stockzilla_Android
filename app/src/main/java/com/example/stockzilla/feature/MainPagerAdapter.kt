package com.example.stockzilla.feature

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

class MainPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {

    override fun getItemCount(): Int = 4

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> PersonalProfileFragment()
            1 -> MainFragment()
            2 -> ViewedStocksFragment()
            3 -> GovNewsFragment()
            else -> throw IllegalArgumentException("Unknown position: $position")
        }
    }
}