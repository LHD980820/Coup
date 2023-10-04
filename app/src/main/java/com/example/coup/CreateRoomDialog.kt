package com.example.coup

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Window
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

class CreateRoomDialog(context: Context): Dialog(context) {
    //UI references
    private lateinit var mNowPerson: TextView
    private lateinit var mTitleView: EditText
    private lateinit var mPasswordView: EditText
    private lateinit var mMinusPersonButton: Button
    private lateinit var mPlusPersonButton: Button
    private lateinit var mOkayButton: Button
    private lateinit var mCloseButton: Button
    private var number: Int = 2
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.dialog_create_room)

        mOkayButton = findViewById(R.id.button_okay_create_room)
        mCloseButton = findViewById(R.id.button_cancel_create_room)
        mMinusPersonButton = findViewById(R.id.button_minus_person_create)
        mPlusPersonButton = findViewById(R.id.button_plus_person_create)
        mNowPerson = findViewById(R.id.text_now_person_create)

        init()

        //Okay Button -> Activate GameWaitingRoomActivity
        mOkayButton.setOnClickListener{
            val intent = Intent(context,GameWaitingRoomActivity::class.java)
            context.startActivity(intent)
            dismiss()
        }

        //X Button -> Close Dialog
        mCloseButton.setOnClickListener {
            dismiss()
        }

        mMinusPersonButton.setOnClickListener {
            if(number == 2) Toast.makeText(context, "최소 2명은 있어야 게임을 진행할 수 있습니다", Toast.LENGTH_SHORT).show()
            else {
                number--
                mNowPerson.text = number.toString()
            }
        }

        mPlusPersonButton.setOnClickListener {
            if(number == 6) Toast.makeText(context, "인원 수는 최대 6명까지 가능합니다", Toast.LENGTH_SHORT).show()
            else {
                number++
                mNowPerson.text = number.toString()
            }
        }
    }

    private fun init() {
        mNowPerson.text = number.toString()
    }
}