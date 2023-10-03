package com.example.coup.ui.login

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import com.example.coup.HomeActivity
import com.example.coup.R
import com.google.android.gms.common.SignInButton
import com.google.firebase.auth.FirebaseAuth    //밑에 전부 다 파이어베이스
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase

class LoginActivity : Activity() {
    // UI references.
    private lateinit var mEmailView: EditText
    private lateinit var mPasswordView: EditText
    private lateinit var mGoogleLogin: SignInButton

    private lateinit var auth: FirebaseAuth

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        // [START initialize_auth]
        // Initialize Firebase Auth
        auth = FirebaseAuth.getInstance()
        // [END initialize_auth]

        // Set up the login form.
        mEmailView = findViewById(R.id.editview_email)
        mPasswordView = findViewById(R.id.editview_password)
        mGoogleLogin = findViewById(R.id.google_login)

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
        val mEmailLogInButton = findViewById<Button>(R.id.email_log_in_button)
        val mEmailSignUpButton = findViewById<Button>(R.id.email_sign_up_button)
        val forgotPasswordButton = findViewById<Button>(R.id.password_forgot)

        // event handler
        mEmailLogInButton.setOnClickListener {
            val id: String = mEmailView.text.toString()
            val pw: String = mPasswordView.text.toString()
            signIn(id, pw)
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

    // [START on_start_check_user]
    public override fun onStart() {
        super.onStart()
        // Check if user is signed in (non-null) and update UI accordingly.
        val currentUser = auth.currentUser
        if (currentUser != null) {
            reload()
        }
    }
    // [END on_start_check_user]



    //로그인
    private fun signIn(email: String, password: String) {
        if(email == null) {
            Toast.makeText(baseContext, "이메일을 입력하세요", Toast.LENGTH_SHORT).show()
            return
        }
        if(password == null) {
            Toast.makeText(baseContext, "비밀번호를 입력하세요", Toast.LENGTH_SHORT).show()
            return
        }
        // [START sign_in_with_email]
        auth.signInWithEmailAndPassword(email, password)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    // Sign in success, update UI with the signed-in user's information
                    Log.d(TAG, "signInWithEmail:success")
                    val user = auth.currentUser
                    updateUI(user)

                    Toast.makeText(baseContext, "$email 님 환영합니다!", Toast.LENGTH_SHORT).show()
                    val intent = Intent(applicationContext, HomeActivity::class.java)
                    intent.putExtra("userinfo", user)
                    startActivity(intent)
                    finish()
                } else {
                    // If sign in fails, display a message to the user.
                    Log.w(TAG, "signInWithEmail:failure", task.exception)
                    Toast.makeText(
                        baseContext,
                        "Authentication failed.",
                        Toast.LENGTH_SHORT,
                    ).show()
                    updateUI(null)
                }
            }
        // [END sign_in_with_email]
    }

    private fun sendEmailVerification() {
        // [START send_email_verification]
        val user = auth.currentUser!!
        user.sendEmailVerification()
            .addOnCompleteListener(this) { task ->
                // Email Verification sent
            }
        // [END send_email_verification]
    }

    private fun updateUI(user: FirebaseUser?) {
    }

    private fun reload() {
    }

    companion object {
        private const val TAG = "EmailPassword"
    }
}
