package com.example.coup.ui.login

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import com.example.coup.R

class LoginResetPassword : Activity() {
    private lateinit var mEmailView: EditText
    private lateinit var mEmailRequestResetButton: Button
    private lateinit var mEmailReset2LoginBtn: ImageButton
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login_reset_password)

        mEmailView = findViewById(R.id.editview_email3)
        mEmailRequestResetButton = findViewById(R.id.button_request_reset)
        mEmailReset2LoginBtn = findViewById(R.id.backward_btn)

        mEmailView.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_DONE || id == EditorInfo.IME_NULL) {
                return@setOnEditorActionListener true
            }
            false
        }

        mEmailRequestResetButton.setOnClickListener {
            val dialog = DialogResetPasswordActivity(this)
            dialog.show()
        }

        mEmailReset2LoginBtn.setOnClickListener{
            val intent = Intent(applicationContext, LoginActivity::class.java)
            startActivity(intent)
            finish()
        }
    }
}