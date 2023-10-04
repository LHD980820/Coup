package com.example.coup

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class GameWaitingRoomActivity : AppCompatActivity() {
    //UI Preferences
    private lateinit var mGameStartButton: Button
    private lateinit var mOptionButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_game_waiting_room)

        mOptionButton = findViewById(R.id.option_btn)
        mOptionButton.setOnClickListener {
            val dialog = RoomOptionDialog(this)
            dialog.show()
        }


    }
}