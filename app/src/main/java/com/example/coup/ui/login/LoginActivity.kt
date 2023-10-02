package com.example.coup.ui.login

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import com.example.coup.MainActivity
import com.example.coup.R

class LoginActivity : Activity() {
    // UI references.
    private lateinit var mEmailView: EditText
    private lateinit var mPasswordView: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        // Set up the login form.
        mEmailView = findViewById(R.id.editview_email)
        mPasswordView = findViewById(R.id.editview_password)

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

        // Buttons
        val mEmailSignInButton = findViewById<Button>(R.id.email_sign_in_button)
        val mEmailSignUpButton = findViewById<Button>(R.id.email_sign_up_button)
        val forgotPasswordButton = findViewById<Button>(R.id.password_forgot)

        // event handler
        mEmailSignInButton.setOnClickListener {
            val intent = Intent(applicationContext, MainActivity::class.java)
            startActivity(intent)
            finish()
        }

        // sign-up button event handler
        mEmailSignUpButton.setOnClickListener {
            val intent = Intent(applicationContext, RegisterActivity::class.java)
            startActivity(intent)
        }

        forgotPasswordButton.setOnClickListener{
            val intent = Intent(applicationContext, LoginResetPassword::class.java)
            startActivity(intent)
        }
    }
}
