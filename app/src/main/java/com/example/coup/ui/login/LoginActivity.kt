package com.example.coup.ui.login

import android.app.Activity
import android.content.Intent
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import android.os.Bundle
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.Toast
import com.example.coup.databinding.ActivityLoginBinding
import android.view.View.OnClickListener
import android.view.KeyEvent
import android.widget.Button
import android.widget.TextView
import com.example.coup.MainActivity
import com.example.coup.ui.login.RegisterActivity
import com.example.coup.R

class LoginActivity : Activity() {
    // UI references.
    private lateinit var mEmailView: EditText
    private lateinit var mPasswordView: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        // Set up the login form.
        mEmailView = findViewById(R.id.email)
        mPasswordView = findViewById(R.id.password)

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
            finish()
        }
    }
}
