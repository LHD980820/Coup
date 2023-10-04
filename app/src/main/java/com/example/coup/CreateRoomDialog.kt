package com.example.coup

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Window
import android.widget.Button
import android.widget.EditText

class CreateRoomDialog(context: Context): Dialog(context) {
    //UI references
    private lateinit var mTitleView: EditText
    private lateinit var mPasswordView: EditText
    private lateinit var mMinusPersonButton: Button
    private lateinit var mPlusPersonButton: Button
    private lateinit var mOkayButton: Button
    private lateinit var mCloseButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.dialog_create_room)

        mOkayButton = findViewById(R.id.button_okay_change_password)
        mCloseButton = findViewById(R.id.button_cancel_change_password)

        //Okay Button -> Activate GameWaitingRoomActivity
        mOkayButton.setOnClickListener{
            val intent = Intent(context,GameWaitingRoomActivity::class.java)
            context.startActivity(intent)
        }

        //X Button -> Close Dialog
        mCloseButton.setOnClickListener {
            dismiss()
        }
    }
}