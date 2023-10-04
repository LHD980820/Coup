package com.example.coup

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.Window
import android.widget.Button

class RoomOptionDialog(context: Context): Dialog(context) {
    //UI Preferences
    private lateinit var mChangeButton: Button
    private lateinit var mCancelButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.dialog_room_option)

        mChangeButton = findViewById(R.id.button_okay_option)
        mCancelButton = findViewById(R.id.button_cancel_option)

        mChangeButton.setOnClickListener {
            // TODO : RoomOption Change
        }

        mCancelButton.setOnClickListener {
            dismiss()
        }

    }

}