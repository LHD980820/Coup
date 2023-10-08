package com.example.coup.ui.login

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.example.coup.FirebaseManager
import com.example.coup.FirestoreManager
import com.example.coup.HomeActivity
import com.example.coup.R
import com.google.android.gms.auth.api.identity.BeginSignInRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.SignInClient
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.SignInButton
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth    //밑에 전부 다 파이어베이스
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.firestore.ktx.firestoreSettings
import com.google.firebase.firestore.ktx.memoryCacheSettings
import com.google.firebase.firestore.ktx.persistentCacheSettings
import com.google.firebase.ktx.Firebase

class LoginActivity : Activity() {
    // UI references.
    private lateinit var mEmailView: EditText
    private lateinit var mPasswordView: EditText
    private lateinit var mGoogleLogin: SignInButton

    // [START declare_auth]
    private lateinit var auth: FirebaseAuth
    // [END declare_auth]

    private lateinit var googleSignInClient: GoogleSignInClient

    private lateinit var oneTapClient: SignInClient
    private lateinit var signInRequest: BeginSignInRequest

    private val REQ_ONE_TAP = 2  // Can be any integer unique to the Activity
    private var showOneTapUI = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        // [START initialize_auth]
        FirebaseManager.initialize()
        FirestoreManager.initialize()
        // [END initialize_auth]
        auth = FirebaseManager.getFirebaseAuth()
        // [START config_signin]
        // Configure Google Sign In
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.default_web_client_id))
            .requestEmail()
            .build()

        googleSignInClient = GoogleSignIn.getClient(this, gso)
        // [END config_signin]

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

        mGoogleLogin.setOnClickListener {
            signIn_google()
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

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        // Result returned from launching the Intent from GoogleSignInApi.getSignInIntent(...);
        if (requestCode == RC_SIGN_IN) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            try {
                // Google Sign In was successful, authenticate with Firebase
                val account = task.getResult(ApiException::class.java)!!
                Log.d(TAG, "firebaseAuthWithGoogle:" + account.id)
                firebaseAuthWithGoogle(account.idToken!!)
            } catch (e: ApiException) {
                // Google Sign In failed, update UI appropriately
                Log.w(TAG, "Google sign in failed", e)
            }
        }
    }



    //로그인
    private fun signIn(email: String, password: String) {
        if(email.isNullOrEmpty()) {
            Toast.makeText(baseContext, "이메일을 입력하세요", Toast.LENGTH_SHORT).show()
            return
        }
        if(password.isNullOrEmpty()) {
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
                        "로그인 실패",
                        Toast.LENGTH_SHORT,
                    ).show()
                    updateUI(null)
                }
            }
        // [END sign_in_with_email]
    }

    // [START signin]
    private fun signIn_google() {
        val signInIntent = googleSignInClient.signInIntent
        startActivityForResult(signInIntent, RC_SIGN_IN)
    }
    // [END signin]

    // [START auth_with_google]
    private fun firebaseAuthWithGoogle(idToken: String) {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        auth.signInWithCredential(credential)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    // Sign in success, update UI with the signed-in user's information
                    Log.d(TAG, "signInWithCredential:success")
                    val user = auth.currentUser
                    updateUI(user)

                    // [START get_firestore_instance]
                    val db = FirestoreManager.getFirestore()
                    // [END get_firestore_instance]

                    val email2name = user!!.email!!.split("@")[0]
                    val user_data = hashMapOf(
                        "nickname" to email2name,
                        "rating" to 1000,
                        "plays" to 0
                    )
                    val user_doc = db.collection("user").document(user!!.email!!.toString())
                    user_doc.get().addOnCompleteListener { task ->
                        if(task.isSuccessful) {
                            val document = task.result
                            if(document == null || !document.exists()) {
                                user_doc.set(user_data)
                            }
                        }
                    }

                    Toast.makeText(baseContext, "${user!!.email}님 환영합니다!", Toast.LENGTH_SHORT).show()
                    val intent = Intent(applicationContext, HomeActivity::class.java)
                    intent.putExtra("userinfo", user)
                    startActivity(intent)
                    finish()
                } else {
                    // If sign in fails, display a message to the user.
                    Log.w(TAG, "signInWithCredential:failure", task.exception)
                    updateUI(null)
                }
            }
    }
    // [END auth_with_google]

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
        private const val TAG = "LoginActivity"
        private const val RC_SIGN_IN = 9001
    }

    //Firebase.auth.signOut()  : 로그아웃
}
