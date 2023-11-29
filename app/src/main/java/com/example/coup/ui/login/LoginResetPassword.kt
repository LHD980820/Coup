package com.example.coup.ui.login

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Toast
import com.example.coup.FirebaseManager
import com.example.coup.R
import com.google.firebase.auth.FirebaseAuth

class LoginResetPassword : Activity() {
    private lateinit var mEmailView: EditText
    private lateinit var mEmailRequestResetButton: Button
    private lateinit var mEmailReset2LoginBtn: ImageButton
    private lateinit var auth: FirebaseAuth
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login_reset_password)

        auth= FirebaseManager.getFirebaseAuth()

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
            val id: String = mEmailView.text.toString()
            requestReset(id)
        }

        mEmailReset2LoginBtn.setOnClickListener{
            val intent = Intent(applicationContext, LoginActivity::class.java)
            startActivity(intent)
            finish()
        }
    }

    //비밀번호 재설정 이메일 전송
    private fun requestReset(email: String) {
        if(email.isNotEmpty()) {
            auth.sendPasswordResetEmail(email)
                .addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        Toast.makeText(this, "이메일을 보냈습니다.", Toast.LENGTH_SHORT).show()
                    }
                }
        }
        else{
            Toast.makeText(this,"올바른 이메일을 입력하세요",Toast.LENGTH_SHORT).show()
            return
        }

        Handler(Looper.getMainLooper()).postDelayed({
            // 일정 시간이 지나면 LoginActivity로 이동
            val intent= Intent( this, LoginActivity::class.java)
            startActivity(intent)
            finish()

        }, 1500)

        val dialog = DialogResetPasswordActivity(this)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
    }
}