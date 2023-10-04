package com.example.coup

import com.google.firebase.auth.FirebaseAuth

object FirebaseManager {
    private lateinit var auth: FirebaseAuth

    fun initialize() {
        auth = FirebaseAuth.getInstance()
        // Firebase Firestore 설정 등의 초기화도 이곳에서 수행할 수 있습니다.
    }

    fun getFirebaseAuth(): FirebaseAuth {
        return auth
    }
}