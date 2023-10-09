package com.example.coup

import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.ktx.Firebase
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.ktx.storage
import de.hdodenhof.circleimageview.CircleImageView

class GameWaitingRoomActivity : AppCompatActivity() {
    //UI Preferences
    private lateinit var mTitleText: TextView
    private lateinit var mOutButton: ImageButton
    private lateinit var mGameStartButton: Button
    private lateinit var mOptionButton: Button
    private lateinit var mPlayerNickname: Array<TextView>
    private lateinit var mPlayerRating: Array<TextView>
    private lateinit var mPlayerImage: Array<CircleImageView>

    private lateinit var user: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private lateinit var storage: FirebaseStorage
    private lateinit var gameId: String
    private var max_number: Int = 0
    private var number: Int = 0
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_game_waiting_room)

        mPlayerNickname = Array(6) { TextView(this) }
        mPlayerRating = Array(6) { TextView(this) }
        mPlayerImage = Array(6) { CircleImageView(this) }
        mOutButton = findViewById(R.id.back_lobby_waiting_room)
        mGameStartButton = findViewById(R.id.game_start_btn_waiting_room)
        mOptionButton = findViewById(R.id.option_btn_waiting_room)
        mTitleText = findViewById(R.id.waiting_room_title)
        mPlayerNickname[0] = findViewById(R.id.player1_nickname_waiting_room)
        mPlayerRating[0] = findViewById(R.id.player1_rating_waiting_room)
        mPlayerImage[0] = findViewById(R.id.player1_image_waiting_room)
        mPlayerNickname[1] = findViewById(R.id.player2_nickname_waiting_room)
        mPlayerRating[1] = findViewById(R.id.player2_rating_waiting_room)
        mPlayerImage[1] = findViewById(R.id.player2_image_waiting_room)
        mPlayerNickname[2] = findViewById(R.id.player3_nickname_waiting_room)
        mPlayerRating[2] = findViewById(R.id.player3_rating_waiting_room)
        mPlayerImage[2] = findViewById(R.id.player3_image_waiting_room)
        mPlayerNickname[3] = findViewById(R.id.player4_nickname_waiting_room)
        mPlayerRating[3] = findViewById(R.id.player4_rating_waiting_room)
        mPlayerImage[3] = findViewById(R.id.player4_image_waiting_room)
        mPlayerNickname[4] = findViewById(R.id.player5_nickname_waiting_room)
        mPlayerRating[4] = findViewById(R.id.player5_rating_waiting_room)
        mPlayerImage[4] = findViewById(R.id.player5_image_waiting_room)
        mPlayerNickname[5] = findViewById(R.id.player6_nickname_waiting_room)
        mPlayerRating[5] = findViewById(R.id.player6_rating_waiting_room)
        mPlayerImage[5] = findViewById(R.id.player6_image_waiting_room)
        user = FirebaseManager.getFirebaseAuth()
        db = FirestoreManager.getFirestore()
        storage = Firebase.storage
        gameId = intent.getStringExtra("roomId").toString()
        number = intent.getStringExtra("number")!!.toInt()
        Log.d(TAG, gameId)

        val game_room = db.collection("game_rooms").document(gameId!!)
        db.collection("game_rooms").document(gameId).addSnapshotListener { snapshot, e->
            if (e != null) {
                // 오류 처리
                Log.e("FirestoreListener", "Error: ${e.message}")
                return@addSnapshotListener
            }
            Log.d(TAG, "스냅샷 감지")
            if (snapshot != null && !snapshot.exists()) {
                // 문서가 삭제됐을 때 실행할 코드
                Log.d("FirestoreListener", "방 폭파")
                Toast.makeText(this, "방이 폭파되었습니다", Toast.LENGTH_SHORT).show()
                finish()
                // 여기에서 삭제된 문서에 대한 추가 작업을 수행할 수 있습니다.
                // 예를 들어, UI 업데이트 또는 다른 동작을 수행할 수 있습니다.
            }
            val snapshotData = snapshot?.data
            if(snapshotData != null) {
                Log.d(TAG, "데이터 감지")
                RoomInfo(snapshotData)
            }
        }
        // 방 제목 설정
        game_room.get().addOnCompleteListener { task->
            if(task.isSuccessful) {
                val document = task.result
                if(document != null) {
                    if(document.exists()) {
                        max_number = document.get("max_players").toString().toInt()
                        Log.d(TAG, "max_number : " + max_number.toString())
                        mTitleText.text = document.get("title").toString()
                        for( i in 1 until max_number + 1) {
                            if(document.get("p${i}") != null) {
                                db.collection("user").document(document.get("p${i}").toString()).get().addOnSuccessListener { Document ->
                                    mPlayerNickname[i - 1].text = Document.get("nickname").toString()
                                    mPlayerRating[i - 1].text = Document.get("rating").toString()
                                    storage.reference.child("profile_images/${Document.id}.jpg").downloadUrl.addOnSuccessListener { imageUrl ->
                                        Glide.with(this)
                                            .load(imageUrl)
                                            .into(mPlayerImage[i - 1])
                                    }
                                }
                            }
                        }
                        Log.d(TAG, "확인하였습니다.")
                    } else {
                        Toast.makeText(baseContext,"이미 사라진 방입니다",Toast.LENGTH_SHORT).show()
                        finish()
                        Log.d(TAG, "문서가 존재하지 않습니다.")
                    }
                } else {
                    Toast.makeText(baseContext,"방 입장에 실패하였습니다.2",Toast.LENGTH_SHORT).show()
                    finish()
                    Log.d(TAG, "문서가 null입니다.")
                }
            } else {
                Toast.makeText(baseContext,"방 입장에 실패하였습니다.3",Toast.LENGTH_SHORT).show()
                finish()
                Log.d(TAG, "데이터를 가져오는 동안 오류 발생: ${task.exception}")
            }
        }

        mOutButton.setOnClickListener {
            Log.d(TAG, "number : $number")
            if(number == 1) {
                val builder = AlertDialog.Builder(this)
                    .setTitle("나가기")
                    .setMessage("방을 폭파시키겠습니까?")
                    .setPositiveButton("예") { dialog, which ->
                        Log.d(TAG, "성공")
                        db.collection("game_rooms").document(gameId).delete()
                        Log.d(TAG, "성공1")
                        dialog.dismiss()
                        Log.d(TAG, "성공2")
                        finish()
                        Log.d(TAG, "성공3")
                    }
                    .setNegativeButton("아니요") { dialog, which ->
                        dialog.dismiss()
                    }
                builder.show()
            }
            else {
                //
                finish()
            }
        }

        mGameStartButton.setOnClickListener {
            // TODO: Game Start
        }

        mOptionButton.setOnClickListener {
            val dialog = RoomOptionDialog(this)
            dialog.show()
        }
    }

    private fun RoomInfo(snapshotData: Map<String, Any>) {
        for (i in 1 until max_number + 1) {
            val playerData = snapshotData["p$i"]
            if (playerData != null) {
                db.collection("user").document(playerData.toString()).get()
                    .addOnSuccessListener { Document ->
                        Log.d(TAG, "읽기 성공$i")
                        mPlayerNickname[i - 1].text = Document.get("nickname").toString()
                        mPlayerRating[i - 1].text = Document.get("rating").toString()
                        storage.reference.child("profile_images/${Document.id}.jpg").downloadUrl.addOnSuccessListener { imageUrl ->
                            if (!this.isDestroyed) {
                                Glide.with(this)
                                    .load(imageUrl)
                                    .into(mPlayerImage[i - 1])
                            }
                        }
                    }
                    .addOnFailureListener {
                        Log.d(TAG, "읽기 실패")
                        mPlayerNickname[i - 1].text = "NAME_TEXT"
                        mPlayerRating[i - 1].text = "SCORE_TEXT"
                        mPlayerImage[i - 1].setImageResource(R.drawable.icon)
                    }
            } else {
                mPlayerNickname[i - 1].text = "NAME_TEXT"
                mPlayerRating[i - 1].text = "SCORE_TEXT"
                mPlayerImage[i - 1].setImageResource(R.drawable.icon)
            }
        }
    }

    override fun onDestroy() {
        if(number == 1) {
            db.collection("game_rooms").document(gameId).delete()
        }
        else {
            db.collection("game_rooms").document(gameId).get().addOnSuccessListener { documentSnapShot ->
                Log.d(TAG, "onDestroy읽기 성공")
                if(documentSnapShot.exists()) {
                    documentSnapShot.reference.update("p${number}", null)
                    documentSnapShot.reference.update("now_players", documentSnapShot["now_players"].toString().toInt() - 1)
                }
            }
        }
        super.onDestroy()
    }

    override fun finish() {
        super.finish()
    }


    override fun onPause() {
        super.onPause()
        finish()

    }
    override fun onStop() {
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
    }

    override fun onBackPressed() {
        super.onBackPressed()
        if(number == 1) {
            val builder = AlertDialog.Builder(this)
                .setTitle("나가기")
                .setMessage("방을 폭파시키겠습니까?")
                .setPositiveButton("예") { dialog, which ->
                    Log.d(TAG, "성공")
                    db.collection("game_rooms").document(gameId).delete()
                    Log.d(TAG, "성공1")
                    dialog.dismiss()
                    Log.d(TAG, "성공2")
                    finish()
                    Log.d(TAG, "성공3")
                }
                .setNegativeButton("아니요") { dialog, which ->
                    dialog.dismiss()
                }
            builder.show()
        }
        else {
            val builder = AlertDialog.Builder(this)
                .setTitle("방 나가기")
                .setMessage("방에서 나가시겠습니까?")
                .setPositiveButton("예") { dialog, which->
                    dialog.dismiss()
                    finish()
                }
                .setNegativeButton("아니요") {dialog, which->
                    dialog.dismiss()
                }
                .show()
        }
    }
    companion object{
        private const val TAG = "GameWaitingRoom"
    }
}