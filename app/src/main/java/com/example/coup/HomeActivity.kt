package com.example.coup

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.navigation.NavigationBarView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.example.coup.ui.login.LoginActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlin.system.exitProcess


private const val TAG_LIST = "list_fragment"
private const val TAG_RANKING = "ranking_fragment"
private const val TAG_INFO = "info_fragment"
class HomeActivity : AppCompatActivity() {

    private lateinit var bottomNavigationView: NavigationBarView
    private lateinit var db: FirebaseFirestore
    private lateinit var auth: FirebaseAuth
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        auth = FirebaseManager.getFirebaseAuth()
        db = FirestoreManager.getFirestore()

        db.collection("user").document(auth.currentUser?.email.toString()).addSnapshotListener {snapshot, e->
            Log.d(TAG, "state 바뀜 : "+snapshot?.get("state").toString())
        }


        CoroutineScope(Dispatchers.IO).launch {
            val document = try {
                db.collection("user").document(auth.currentUser?.email.toString())
                    .get(Source.SERVER)
                    .await()
            } catch (e: Exception) {
                // 예외 처리
                return@launch
            }

            val isUserOnline = document.getBoolean("state") ?: false
            Log.d(TAG, "isUserOnline: $isUserOnline")

            if (isUserOnline) {
                Log.d(TAG, "여기 들어감")
                runOnUiThread {
                    Toast.makeText(baseContext, "현재 접속 중인 계정으로 로그아웃됩니다", Toast.LENGTH_SHORT).show()
                }
                auth.signOut()
                startActivity(Intent(baseContext, LoginActivity::class.java))
                finish()
            } else {
                db.collection("user").document(auth.currentUser?.email.toString()).update("state", true)
            }
        }


        //대기방 들어가 있는지 확인
        db.collection("user").document(auth.currentUser!!.email.toString()).get().addOnSuccessListener { document->
            val waitingroomArray = document.get("waitingroom") as Map<*, *>
            val waitingroom0 = waitingroomArray["waitingroom.0"].toString()
            val waitingroom1 = waitingroomArray["waitingroom.1"].toString()
            Log.d(TAG, "waitingroom : ${waitingroom0}, ${waitingroom1}")
            if(!waitingroom0.isNullOrEmpty()) {
                val intent = Intent(this, GameWaitingRoomActivity::class.java)
                intent.putExtra("roomId", waitingroom0)
                intent.putExtra("number", waitingroom1)
                startActivity(intent)
            }
        }

        bottomNavigationView = findViewById(R.id.bottom_navigation)
        supportFragmentManager.beginTransaction().add(R.id.home_frame, room_list()).commit()
        bottomNavigationView.setOnItemSelectedListener { item->
            when(item.itemId) {
                R.id.icon_room -> setFragment(room_list())
                R.id.icon_ranking -> setFragment(ranking())
                R.id.icon_user_info -> setFragment(info())
                else -> setFragment(room_list())
            }
            true
        }
    }

    private fun checkConcurrentConnection() {
        db.collection("user").document(auth.currentUser!!.email.toString()).get().addOnSuccessListener { document ->
            Log.d(TAG, "state : ${document["state"]}")

            val isUserOnline = document.getBoolean("state") ?: false
            Log.d(TAG, "${isUserOnline}")

            if (isUserOnline) {
                Log.d(TAG, "여기 들어감")
                Toast.makeText(this, "현재 접속 중인 계정으로 로그아웃됩니다", Toast.LENGTH_SHORT).show()
                auth.signOut()
                startActivity(Intent(this, LoginActivity::class.java))
                finish()
            }
        }
    }

    private fun setFragment(fragment: Fragment) {
        val manager: FragmentManager = supportFragmentManager
        val fragTransaction = manager.beginTransaction()
        fragTransaction.replace(R.id.home_frame, fragment).commit()
    }

    override fun onPause() {
        db.collection("user").document(auth.currentUser?.email.toString()).update("state", false)
        super.onPause()
    }
    override fun onStop() {
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
    }

    override fun onRestart() {
        Log.d("TAG", "onRestart실행됨")
        db.collection("user").document(auth.currentUser?.email.toString()).update("state", true)
        super.onRestart()
    }

    override fun onBackPressed() {
        val builder = AlertDialog.Builder(this)
            .setTitle("게임 종료")
            .setMessage("게임을 종료하시겠습니까?")
            .setPositiveButton("예") { dialog, which->
                dialog.dismiss()
                db.collection("user").document(auth.currentUser!!.email.toString()).update("state", false)
                finish()
            }
            .setNegativeButton("아니요") { dialog, which->
                dialog.dismiss()
            }.show()
        //super.onBackPressed()
    }

    companion object {
        private const val TAG = "HomeActivity"
    }
}
