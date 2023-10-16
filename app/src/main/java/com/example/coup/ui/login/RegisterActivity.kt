package com.example.coup.ui.login

import android.app.Activity
import android.content.Intent
import android.nfc.Tag
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import com.example.coup.FirebaseManager
import com.example.coup.R
import com.google.firebase.auth.FirebaseAuth    //밑에 전부 다 파이어베이스
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.firestore.ktx.firestoreSettings
import com.google.firebase.firestore.ktx.memoryCacheSettings
import com.google.firebase.firestore.ktx.persistentCacheSettings
import com.google.firebase.ktx.Firebase

class RegisterActivity : Activity(){
    // UI references.
    private lateinit var mEmailView: EditText
    private lateinit var mPasswordView: EditText
    private lateinit var mPasswordCheckView: EditText
    private lateinit var mEmailSignUpButton: Button

    private lateinit var auth: FirebaseAuth
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_register)

        auth = FirebaseManager.getFirebaseAuth()

        // Set up the register form.
        mEmailView = findViewById(R.id.editview_email2)
        mPasswordView = findViewById(R.id.editview_password2)
        mPasswordCheckView = findViewById(R.id.editview_check_password)
        mEmailSignUpButton = findViewById<Button>(R.id.email_sign_up_button2)
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

        // sign-up button event handler
        mEmailSignUpButton.setOnClickListener {
            val id: String = mEmailView.text.toString()
            val pw: String = mPasswordView.text.toString()
            val pwc: String = mPasswordCheckView.text.toString()
            if(id.isNullOrEmpty()) {
                Toast.makeText(baseContext, "이메일을 입력하세요", Toast.LENGTH_SHORT).show()
            }
            else if(pw.isNullOrEmpty()) {
                Toast.makeText(baseContext, "비밀번호를 입력하세요", Toast.LENGTH_SHORT).show()
            }
            else if(pwc.isNullOrEmpty()) {
                Toast.makeText(baseContext, "밑에 칸에 비밀번호를 한번 더 입력하세요", Toast.LENGTH_SHORT).show()
            }
            else if(pw != pwc) {
                Toast.makeText(baseContext, "비밀번호가 맞지 않습니다 ($pw + $pwc)", Toast.LENGTH_SHORT).show()
            }
            else if(pw.length < 6) {
                Toast.makeText(this, "비밀번호는 6자 이상이어야 합니다", Toast.LENGTH_SHORT).show()
            }
            else {
                createAccount(id, pw)
            }
        }
    }

    //계정 생성
    private fun createAccount(email: String, password: String) {

        // [START create_user_with_email]
        auth.createUserWithEmailAndPassword(email, password)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    // Sign in success, update UI with the signed-in user's information
                    Log.d(TAG, "createUserWithEmail:success")
                    val user = auth.currentUser
                    updateUI(user)


                    // [START get_firestore_instance]
                    val db = Firebase.firestore
                    // [END get_firestore_instance]

                    // [START set_firestore_settings]
                    val settings = firestoreSettings {
                        // Use memory cache
                        setLocalCacheSettings(memoryCacheSettings {})
                        // Use persistent disk cache (default)
                        setLocalCacheSettings(persistentCacheSettings {})
                    }
                    db.firestoreSettings = settings
                    // [END set_firestore_settings]

                    val email2name = user!!.email!!.split("@")[0]
                    val user_data = hashMapOf(
                        "nickname" to email2name,
                        "rating" to 1000,
                        "plays" to 0,
                        "state" to false,
                        "waitingroom" to hashMapOf(
                            "waitingroom.0" to null,
                            "waitingroom.1" to "0"
                        ),
                        "palyingroom" to hashMapOf(
                            "playingroom.0" to null,
                            "playingroom.1" to "0"
                        )
                    )
                    db.collection("user")
                        .document(user!!.email!!.toString())
                        .set(user_data)


                    val dialog = RegisterDialog(this)
                    dialog.show()

                    Handler(Looper.getMainLooper()).postDelayed({

                        // 일정 시간이 지나면 LoginActivity로 이동
                        val intent= Intent( this, LoginActivity::class.java)
                        startActivity(intent)
                        finish()

                    }, 1000)
                } else {
                    // If sign in fails, display a message to the user.
                    Log.w(RegisterActivity.TAG, "createUserWithEmail:failure", task.exception)
                    Toast.makeText(
                        baseContext,
                        "Authentication failed.",
                        Toast.LENGTH_SHORT,
                    ).show()
                    updateUI(null)
                }
            }
        // [END create_user_with_email]
    }

    private fun updateUI(user: FirebaseUser?) {
    }

    companion object {
        private const val TAG = "RegisterActivity"
    }
}