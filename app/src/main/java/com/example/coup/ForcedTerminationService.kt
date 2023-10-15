package com.example.coup

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.example.coup.ui.login.LoginActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.Filter
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

class ForcedTerminationService : Service() {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    override fun onBind(p0: Intent?): IBinder? {
        return null
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d("TerminationService", "앱종료 함수 실행됨")

        auth = FirebaseManager.getFirebaseAuth()
        db = FirestoreManager.getFirestore()

        Thread.sleep(2000)
        super.onTaskRemoved(rootIntent)
    }
}