package com.example.coup

import android.content.Intent
import android.os.Bundle
import android.provider.ContactsContract.CommonDataKinds.Nickname
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.w3c.dom.Text

class GameWaitingRoomActivity : AppCompatActivity() {
    //UI Preferences
    private lateinit var mTitleText: TextView
    private lateinit var mOutButton: ImageButton
    private lateinit var mGameStartButton: Button
    private lateinit var mOptionButton: Button
    private lateinit var mPlayerNickname: Array<TextView>
    private lateinit var mPlayerRating: Array<TextView>
    private var my_number: Int = 0
    private var now_players: Int = 0
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_game_waiting_room)


        mPlayerNickname = Array(6) { TextView(this) }
        mPlayerRating = Array(6) { TextView(this) }
        mOutButton = findViewById(R.id.back_lobby_waiting_room)
        mGameStartButton = findViewById(R.id.game_start_btn_waiting_room)
        mOptionButton = findViewById(R.id.option_btn_waiting_room)
        mTitleText = findViewById(R.id.waiting_room_title)
        mPlayerNickname[0] = findViewById(R.id.player1_nickname_waiting_room)
        mPlayerRating[0] = findViewById(R.id.player1_rating_waiting_room)
        mPlayerNickname[1] = findViewById(R.id.player2_nickname_waiting_room)
        mPlayerRating[1] = findViewById(R.id.player2_rating_waiting_room)
        mPlayerNickname[2] = findViewById(R.id.player3_nickname_waiting_room)
        mPlayerRating[2] = findViewById(R.id.player3_rating_waiting_room)
        mPlayerNickname[3] = findViewById(R.id.player4_nickname_waiting_room)
        mPlayerRating[3] = findViewById(R.id.player4_rating_waiting_room)
        mPlayerNickname[4] = findViewById(R.id.player5_nickname_waiting_room)
        mPlayerRating[4] = findViewById(R.id.player5_rating_waiting_room)
        mPlayerNickname[5] = findViewById(R.id.player6_nickname_waiting_room)
        mPlayerRating[5] = findViewById(R.id.player6_rating_waiting_room)
        val user = FirebaseManager.getFirebaseAuth()
        val db = FirestoreManager.getFirestore()
        val gameId = intent.getStringExtra("gameId")
        Log.d(TAG, gameId.toString())

        val game_room = db.collection("game_rooms").document(gameId!!)

        // 방 제목 설정, 없는 자리 처음부터 찾아서 넣기(제거, 충돌 문제 처리 : room_list에서 설정해야 함)
        game_room.get().addOnCompleteListener { task->
            if(task.isSuccessful) {
                val document = task.result
                if(document != null) {
                    if(document.exists()) {
                        mTitleText.text = document.get("title").toString()
                        if(document.get("now_players").toString().toInt() >= document.get("max_players").toString().toInt()) {
                            Toast.makeText(baseContext, "인원 수가 다 찼습니다", Toast.LENGTH_SHORT).show()
                            finish()
                        }
                        now_players = document.get("now_players").toString().toInt()
                        now_players++
                        for(i in 1 until document.get("max_players").toString().toInt() + 1) {
                            if(document.data?.get("p${i}").toString() != null) {
                                my_number = i
                                Log.d(TAG, "my_number = ${my_number}")
                                game_room.update("p${my_number}", user.currentUser!!.email)
                                db.collection("user").document(user.currentUser!!.email.toString()).get().addOnSuccessListener { documentSnapshot ->
                                    mPlayerNickname[my_number - 1].text = documentSnapshot.get("nickname").toString()
                                    mPlayerRating[my_number - 1].text = documentSnapshot.get("rating").toString()
                                }
                                break
                            }
                        }

                        Log.d(TAG, "확인하였습니다.")
                    } else {
                        Toast.makeText(baseContext,"방 생성에 실패하였습니다.",Toast.LENGTH_SHORT).show()
                        finish()
                        Log.d(TAG, "문서가 존재하지 않습니다.")
                    }
                } else {
                    Toast.makeText(baseContext,"방 생성에 실패하였습니다.",Toast.LENGTH_SHORT).show()
                    finish()
                    Log.d(TAG, "문서가 null입니다.")
                }
            } else {
                Toast.makeText(baseContext,"방 생성에 실패하였습니다.",Toast.LENGTH_SHORT).show()
                finish()
                Log.d(TAG, "데이터를 가져오는 동안 오류 발생: ${task.exception}")
            }
        }

        mOutButton.setOnClickListener {
            finish()
//            val intent = Intent(applicationContext,HomeActivity::class.java)
//            startActivity(intent)
        }

        mGameStartButton.setOnClickListener {
            // TODO: Game Start
        }

        mOptionButton.setOnClickListener {
            val dialog = RoomOptionDialog(this)
            dialog.show()
        }
    }

    companion object{
        private const val TAG = "GameWaitingRoom"
    }
}