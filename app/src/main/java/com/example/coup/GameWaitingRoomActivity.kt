package com.example.coup

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity

class GameWaitingRoomActivity : AppCompatActivity() {
    //UI Preferences
    private lateinit var mOutButton: ImageButton
    private lateinit var mGameStartButton: Button
    private lateinit var mOptionButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_game_waiting_room)

        mOutButton = findViewById(R.id.back_lobby)
        mGameStartButton = findViewById(R.id.game_start_btn)
        mOptionButton = findViewById(R.id.option_btn)

        mOutButton.setOnClickListener {
            val intent = Intent(applicationContext,HomeActivity::class.java)
            startActivity(intent)
        }

        mGameStartButton.setOnClickListener {
            // TODO: Game Start
        }

        mOptionButton.setOnClickListener {
            val dialog = RoomOptionDialog(this)
            dialog.show()
        }


    }
}