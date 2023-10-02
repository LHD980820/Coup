package com.example.coup.ui.login

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import com.example.coup.R

class RegisterActivity : Activity(){
    // UI references.
    private lateinit var mEmailView: EditText
    private lateinit var mPasswordView: EditText
    private lateinit var mPasswordCheckView: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_register)

        // Set up the register form.
        mEmailView = findViewById(R.id.editview_email2)
        mPasswordView = findViewById(R.id.editview_password2)
        mPasswordCheckView = findViewById(R.id.editview_check_password)

        // Event handlers for EditTexts
        mEmailView.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_DONE || id == EditorInfo.IME_NULL) {
                return@setOnEditorActionListener true
            }
            false
        }

        mPasswordView.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_DONE || id == EditorInfo.IME_NULL) {
                //attemptLogin()
                return@setOnEditorActionListener true
            }
            false
        }

        mPasswordCheckView.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_DONE || id == EditorInfo.IME_NULL) {
                //attemptLogin()
                return@setOnEditorActionListener true
            }
            false
        }

        // Buttons
        val mEmailSignUpButton = findViewById<Button>(R.id.email_sign_in_button2)

        // sign-up button event handler
        mEmailSignUpButton.setOnClickListener {
            val dialog = RegisterDialog(this)
            dialog.show()

            Handler(Looper.getMainLooper()).postDelayed({

                // 일정 시간이 지나면 LoginActivity로 이동
                val intent= Intent( this, LoginActivity::class.java)
                startActivity(intent)

                finish()

            }, 3000)
        }
    }
}