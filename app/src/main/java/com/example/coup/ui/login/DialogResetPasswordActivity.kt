package com.example.coup.ui.login

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.Window
import android.widget.ImageButton
import com.example.coup.R

class DialogResetPasswordActivity (context: Context) : Dialog(context) {
    private lateinit var mEmailDialogCloseBtn: ImageButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.dialog_reset_password)

        mEmailDialogCloseBtn = findViewById(R.id.close_btn)

        mEmailDialogCloseBtn.setOnClickListener{
            dismiss()
        }
    }
}