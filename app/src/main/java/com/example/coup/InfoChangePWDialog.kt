package com.example.coup

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import com.google.firebase.auth.FirebaseAuth

class InfoChangePWDialog (context:Context): Dialog(context){
    private lateinit var mCurrentPasswordView: EditText
    private lateinit var mChangeToView: EditText
    private lateinit var mPasswordCheckView:EditText
    private lateinit var mOkayBtn: Button
    private lateinit var mCancelBtn: Button

    private lateinit var user: FirebaseAuth

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.dialog_change_password)

        mCurrentPasswordView = findViewById(R.id.edit_current_change_password)
        mChangeToView = findViewById(R.id.edit_password_change_password)
        mPasswordCheckView = findViewById(R.id.edit_check_change_password)
        mOkayBtn = findViewById(R.id.button_okay_change_password)
        mCancelBtn = findViewById(R.id.button_cancel_change_password)

        user = FirebaseAuth.getInstance()

        mCurrentPasswordView.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_DONE || id == EditorInfo.IME_NULL) {
                //attemptLogin()
                return@setOnEditorActionListener true
            }
            false
        }
        mChangeToView.setOnEditorActionListener { _, id, _ ->
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

        mOkayBtn.setOnClickListener(){
            val curpw: String = mCurrentPasswordView.text.toString()
            val changepw: String = mChangeToView.text.toString()
            val checkpw: String = mPasswordCheckView.text.toString()

            changePassword(curpw, changepw, checkpw)
        }
        mCancelBtn.setOnClickListener(){
            dismiss()
        }
    }

    private fun changePassword(curPW: String, changePW: String, checkPW:String){
        if(curPW.isNullOrEmpty()) {
            Toast.makeText(context, "현재 비밀번호를 입력하세요.", Toast.LENGTH_SHORT).show()
            return
        }
        //현재 패스워드 체크할 수 있는 방법 ...?? -> 모르겠다...
        if(changePW.isNullOrEmpty()){
            Toast.makeText(context, "변경할 비밀번호를 입력하세요.", Toast.LENGTH_SHORT).show()
            return
        }
        if(checkPW.isNullOrEmpty()) {
            Toast.makeText(context, "비밀번호 확인란을 입력하세요.", Toast.LENGTH_SHORT).show()
            return
        }
        if(changePW != checkPW) {
            Toast.makeText(context, "비밀번호가 맞지 않습니다.", Toast.LENGTH_SHORT).show()
            return
        }

        user.getCurrentUser()!!.updatePassword(changePW)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    Log.d("InfoChangePWDialog", "User password updated.")
                    Toast.makeText(context, "PasswordChange", Toast.LENGTH_SHORT).show()
                }
            }

        dismiss()
    }
}