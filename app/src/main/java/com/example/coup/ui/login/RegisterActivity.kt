package com.example.coup.ui.login

import android.app.Activity
import android.content.Intent
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import android.os.Bundle
import androidx.annotation.StringRes
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.Toast
import android.view.View.OnClickListener
import android.view.KeyEvent
import android.widget.Button
import android.widget.TextView
import com.example.coup.MainActivity
import com.example.coup.ui.login.LoginActivity
import com.example.coup.R

class RegisterActivity : Activity(){
    // UI references.
    private lateinit var mEmailView: EditText
    private lateinit var mPasswordView: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_register)

        // Set up the register form.
        mEmailView = findViewById(R.id.register_email)
        mPasswordView = findViewById(R.id.register_password)

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
        val mEmailSignInButton = findViewById<Button>(R.id.register_sign_in_button)
        val mEmailSignUpButton = findViewById<Button>(R.id.register_sign_up_button)

        // event handler
        mEmailSignInButton.setOnClickListener {
            val intent = Intent(applicationContext, MainActivity::class.java)
            startActivity(intent)
            finish()
        }

        // sign-up button event handler
        mEmailSignUpButton.setOnClickListener {
            val intent = Intent(applicationContext, LoginActivity::class.java)
            startActivity(intent)
            finish()
        }
    }
}