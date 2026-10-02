package io.github.lhd980820.coup

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.firestore
import com.google.firebase.firestore.firestoreSettings
import com.google.firebase.firestore.memoryCacheSettings
import com.google.firebase.firestore.persistentCacheSettings
import com.google.firebase.Firebase

object FirestoreManager {
    private lateinit var db: FirebaseFirestore

    fun initialize() {
        db = Firebase.firestore
        val settings = firestoreSettings {
            // Use memory cache
            setLocalCacheSettings(memoryCacheSettings {})
            // Use persistent disk cache (default)
            setLocalCacheSettings(persistentCacheSettings {})
        }
        db.firestoreSettings = settings
    }

    fun getFirestore(): FirebaseFirestore {
        return db
    }
}
