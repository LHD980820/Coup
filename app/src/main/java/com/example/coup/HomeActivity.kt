package com.example.coup

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.navigation.NavigationBarView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import android.view.MenuItem


private const val TAG_LIST = "list_fragment"
private const val TAG_RANKING = "ranking_fragment"
private const val TAG_INFO = "info_fragment"
class HomeActivity : AppCompatActivity() {

    private lateinit var bottomNavigationView: NavigationBarView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        bottomNavigationView = findViewById(R.id.bottom_navigation)

        supportFragmentManager.beginTransaction().add(R.id.home_frame, room_list()).commit()
        bottomNavigationView.setOnItemSelectedListener { item->
            when(item.itemId) {
                R.id.icon_room -> setFragment(room_list())
                R.id.icon_ranking -> setFragment(ranking())
                R.id.icon_user_info -> setFragment(info())
                else -> setFragment(room_list())
            }
            true
        }
    }

    private fun setFragment(fragment: Fragment) {
        val manager: FragmentManager = supportFragmentManager
        val fragTransaction = manager.beginTransaction()
        fragTransaction.replace(R.id.home_frame, fragment).commit()
    }
}