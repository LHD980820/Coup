package com.example.coup

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

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
        if(auth.currentUser != null) {
            db.collection("game_rooms").whereEqualTo("p1", auth.currentUser!!.email).get().addOnCompleteListener { task->
                if(task.isSuccessful) {
                    for(document in task.result) {
                        db.document(document.id).delete()
                    }
                }
            }
            for(i in 2 until 7){
                db.collection("game_rooms").whereEqualTo("p$i", auth.currentUser!!.email).get().addOnCompleteListener { task->
                    if(task.isSuccessful) {
                        for(document in task.result) {
                            db.document(document.id).update("p$i", null)
                            db.document(document.id).update("p${i}ready", 0)

                        }
                    }
                }
            }
        }
        db.collection("user").document(auth.currentUser!!.email.toString()).update("state", 0)
        super.onTaskRemoved(rootIntent)
    }
}