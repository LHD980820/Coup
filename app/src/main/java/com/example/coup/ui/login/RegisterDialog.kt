package com.example.coup.ui.login

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.Window
import com.example.coup.R

class RegisterDialog(context: Context) : Dialog(context) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.dialog_register)
    }
}