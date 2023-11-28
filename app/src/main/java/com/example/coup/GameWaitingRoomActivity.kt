package com.example.coup

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import com.bumptech.glide.Glide
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.ktx.Firebase
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.ktx.storage
import de.hdodenhof.circleimageview.CircleImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlin.math.max
import kotlin.random.Random

class GameWaitingRoomActivity : AppCompatActivity() {
    //UI Preferences
    private lateinit var mTitleText: TextView
    private lateinit var mOutButton: ImageButton
    private lateinit var mGameStartButton: Button
    private lateinit var mPlayerNickname: Array<TextView>
    private lateinit var mPlayerRating: Array<TextView>
    private lateinit var mPlayerImage: Array<CircleImageView>
    private lateinit var mPlayerThumbsUpImage: Array<ImageView>
    private lateinit var mNoPersonImage3: ImageView
    private lateinit var mNoPersonImage4: ImageView
    private lateinit var mNoPersonImage5: ImageView
    private lateinit var mNoPersonImage6: ImageView
    private lateinit var mCard3: ConstraintLayout
    private lateinit var mCard4: ConstraintLayout
    private lateinit var mCard5: ConstraintLayout
    private lateinit var mCard6: ConstraintLayout

    private lateinit var user: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private lateinit var storage: FirebaseStorage
    private lateinit var database: FirebaseDatabase
    private lateinit var game_room: DocumentReference


    private lateinit var gameId: String
    private var max_number: Int = 0
    private var number: Int = 0
    private lateinit var snapshotListener: ListenerRegistration
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_game_waiting_room)

        mPlayerNickname = Array(6) { TextView(this) }
        mPlayerRating = Array(6) { TextView(this) }
        mPlayerImage = Array(6) { CircleImageView(this) }
        mPlayerThumbsUpImage  = Array(6) { ImageView(this) }
        mOutButton = findViewById(R.id.back_lobby_waiting_room)
        mGameStartButton = findViewById(R.id.game_start_btn_waiting_room)
        mTitleText = findViewById(R.id.waiting_room_title)
        mPlayerNickname[0] = findViewById(R.id.player1_nickname_waiting_room)
        mPlayerRating[0] = findViewById(R.id.player1_rating_waiting_room)
        mPlayerImage[0] = findViewById(R.id.player1_image_waiting_room)
        mPlayerNickname[1] = findViewById(R.id.player2_nickname_waiting_room)
        mPlayerRating[1] = findViewById(R.id.player2_rating_waiting_room)
        mPlayerImage[1] = findViewById(R.id.player2_image_waiting_room)
        mPlayerThumbsUpImage[1] = findViewById(R.id.player2_thumbs_up_waiting_room)
        mPlayerNickname[2] = findViewById(R.id.player3_nickname_waiting_room)
        mPlayerRating[2] = findViewById(R.id.player3_rating_waiting_room)
        mPlayerImage[2] = findViewById(R.id.player3_image_waiting_room)
        mPlayerThumbsUpImage[2] = findViewById(R.id.player3_thumbs_up_waiting_room)
        mPlayerNickname[3] = findViewById(R.id.player4_nickname_waiting_room)
        mPlayerRating[3] = findViewById(R.id.player4_rating_waiting_room)
        mPlayerImage[3] = findViewById(R.id.player4_image_waiting_room)
        mPlayerThumbsUpImage[3] = findViewById(R.id.player4_thumbs_up_waiting_room)
        mPlayerNickname[4] = findViewById(R.id.player5_nickname_waiting_room)
        mPlayerRating[4] = findViewById(R.id.player5_rating_waiting_room)
        mPlayerImage[4] = findViewById(R.id.player5_image_waiting_room)
        mPlayerThumbsUpImage[4] = findViewById(R.id.player5_thumbs_up_waiting_room)
        mPlayerNickname[5] = findViewById(R.id.player6_nickname_waiting_room)
        mPlayerRating[5] = findViewById(R.id.player6_rating_waiting_room)
        mPlayerImage[5] = findViewById(R.id.player6_image_waiting_room)
        mPlayerThumbsUpImage[5] = findViewById(R.id.player6_thumbs_up_waiting_room)
        mCard3 = findViewById(R.id.card3)
        mCard4 = findViewById(R.id.card4)
        mCard5 = findViewById(R.id.card5)
        mCard6 = findViewById(R.id.card6)
        mNoPersonImage3 = findViewById(R.id.image_no_person3)
        mNoPersonImage4 = findViewById(R.id.image_no_person4)
        mNoPersonImage5 = findViewById(R.id.image_no_person5)
        mNoPersonImage6 = findViewById(R.id.image_no_person6)
        user = FirebaseManager.getFirebaseAuth()
        db = FirestoreManager.getFirestore()
        storage = Firebase.storage
        database = FirebaseDatabase.getInstance()

        gameId = intent.getStringExtra("roomId").toString()
        number = intent.getStringExtra("number")!!.toInt()

        game_room = db.collection("game_rooms").document(gameId)
        game_room.get().addOnFailureListener{
            Toast.makeText(this, "방을 찾을 수 없습니다", Toast.LENGTH_SHORT).show()
        }
        val waiting_hash = hashMapOf(
            "waitingroom.0" to gameId,
            "waitingroom.1" to number.toString()
        )
        db.collection("user").document(user.currentUser!!.email.toString()).update("waitingroom", waiting_hash)
        Log.d(TAG, "게임 입장 : " + gameId)

        mPlayerThumbsUpImage[1].visibility = View.INVISIBLE
        mPlayerThumbsUpImage[2].visibility = View.INVISIBLE
        mPlayerThumbsUpImage[3].visibility = View.INVISIBLE
        mPlayerThumbsUpImage[4].visibility = View.INVISIBLE
        mPlayerThumbsUpImage[5].visibility = View.INVISIBLE

        mNoPersonImage3.visibility = View.INVISIBLE
        mNoPersonImage4.visibility = View.INVISIBLE
        mNoPersonImage5.visibility = View.INVISIBLE
        mNoPersonImage6.visibility = View.INVISIBLE

        if(number != 1) {
            mGameStartButton.text = "ready"
        }

        //방 UI생성
        val game_room = db.collection("game_rooms").document(gameId!!)
        game_room.get().addOnSuccessListener { document->
            if(document["max_players"].toString().toInt() <= 5) {
                mCard6.visibility = View.INVISIBLE
                mNoPersonImage6.visibility = View.VISIBLE
            }
            if(document["max_players"].toString().toInt() <= 4) {
                mCard5.visibility = View.INVISIBLE
                mNoPersonImage5.visibility = View.VISIBLE
            }
            if(document["max_players"].toString().toInt() <= 3) {
                mCard4.visibility = View.INVISIBLE
                mNoPersonImage4.visibility = View.VISIBLE
            }
            if(document["max_players"].toString().toInt() <= 2) {
                mCard3.visibility = View.INVISIBLE
                mNoPersonImage3.visibility = View.VISIBLE
            }
        }
        //스냅샷 설정
        snapshotListener = db.collection("game_rooms").document(gameId).addSnapshotListener { snapshot, e->
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
                val waitingroom = hashMapOf(
                    "waitingroom.0" to null,
                    "waitingroom.1" to "0"
                )
                db.collection("user").document(user.currentUser?.email.toString()).update("waitingroom", waitingroom)
                finish()
                // 여기에서 삭제된 문서에 대한 추가 작업을 수행할 수 있습니다.
                // 예를 들어, UI 업데이트 또는 다른 동작을 수행할 수 있습니다.
            }
            val snapshotData = snapshot?.data
            if(snapshotData != null) {
                Log.d(TAG, "데이터 감지")
                if(snapshotData["state"] == false && number != 1) {
                    Log.d(TAG, "방 입장")
                    CoroutineScope(Dispatchers.IO).launch {
                        snapshotListener.remove()
                        gameStart()
                    }
                }
                else {
                    RoomInfo(snapshotData)
                }
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
                                        if(!isDestroyed) {
                                            Glide.with(this)
                                                .load(imageUrl)
                                                .into(mPlayerImage[i - 1])
                                        }
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

        //버튼 설정
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
                game_room.get().addOnSuccessListener { document->
                    document.reference.update("now_players", (document["now_players"].toString().toInt() - 1))
                }
                game_room.update("p$number", null)
                game_room.update("p${number}ready", false)
                val waitingroom = hashMapOf(
                    "waitingroom.0" to null,
                    "waitingroom.1" to "0"
                )
                db.collection("user").document(user.currentUser?.email.toString()).update("waitingroom", waitingroom)
                finish()
            }
        }

        mGameStartButton.setOnClickListener {
            if(number == 1) {
                CoroutineScope(Dispatchers.IO).launch {
                    gameStart()
                }
            }
            else {
                CoroutineScope(Dispatchers.IO).launch {
                    db.collection("game_rooms").document(gameId).get().addOnCompleteListener{ task->
                        if(task.isSuccessful) {
                            val document = task.result
                            if(document["p${number}ready"] == false) {
                                document.reference.update("p${number}ready", true)
                            }
                            else {
                                document.reference.update("p${number}ready", false)
                            }
                        }
                    }.await()
                }
            }
        }

        //강퇴 기능
        if(number == 1) {
            mPlayerImage[1].setOnClickListener {
                game_room.get().addOnSuccessListener { document->
                    if(document.get("p2") != null) {
                        val builder = AlertDialog.Builder(this).create()
                        val dialogView = layoutInflater.inflate(R.layout.dialog_kick_out, null)
                        val ok = dialogView.findViewById<Button>(R.id.button_okay_kick_out)
                        val cancel = dialogView.findViewById<Button>(R.id.button_cancel_kick_out)
                        builder.setView(dialogView)
                        ok.setOnClickListener {
                            game_room.update("p2", null)
                            game_room.update("p2ready", false)
                            game_room.update("now_players", document["now_players"].toString().toInt() - 1)
                            builder.dismiss()
                        }
                        cancel.setOnClickListener {
                            builder.dismiss()
                        }
                        builder.show()
                    }
                }
            }

            mPlayerImage[2].setOnClickListener {
                game_room.get().addOnSuccessListener { document->
                    if(document.get("p3") != null) {
                        val builder = AlertDialog.Builder(this).create()
                        val dialogView = layoutInflater.inflate(R.layout.dialog_kick_out, null)
                        val ok = dialogView.findViewById<Button>(R.id.button_okay_kick_out)
                        val cancel = dialogView.findViewById<Button>(R.id.button_cancel_kick_out)
                        builder.setView(dialogView)
                        ok.setOnClickListener {
                            game_room.update("p3", null)
                            game_room.update("p3ready", false)
                            game_room.update("now_players", document["now_players"].toString().toInt() - 1)
                            builder.dismiss()
                        }
                        cancel.setOnClickListener {
                            builder.dismiss()
                        }
                        builder.show()
                    }
                }
            }

            mPlayerImage[3].setOnClickListener {
                game_room.get().addOnSuccessListener { document->
                    if(document.get("p4") != null) {
                        val builder = AlertDialog.Builder(this).create()
                        val dialogView = layoutInflater.inflate(R.layout.dialog_kick_out, null)
                        val ok = dialogView.findViewById<Button>(R.id.button_okay_kick_out)
                        val cancel = dialogView.findViewById<Button>(R.id.button_cancel_kick_out)
                        builder.setView(dialogView)
                        ok.setOnClickListener {
                            game_room.update("p4", null)
                            game_room.update("p4ready", false)
                            game_room.update("now_players", document["now_players"].toString().toInt() - 1)
                            builder.dismiss()
                        }
                        cancel.setOnClickListener {
                            builder.dismiss()
                        }
                        builder.show()
                    }
                }
            }

            mPlayerImage[4].setOnClickListener {
                game_room.get().addOnSuccessListener { document->
                    if(document.get("p5") != null) {
                        val builder = AlertDialog.Builder(this).create()
                        val dialogView = layoutInflater.inflate(R.layout.dialog_kick_out, null)
                        val ok = dialogView.findViewById<Button>(R.id.button_okay_kick_out)
                        val cancel = dialogView.findViewById<Button>(R.id.button_cancel_kick_out)
                        builder.setView(dialogView)
                        ok.setOnClickListener {
                            game_room.update("p5", null)
                            game_room.update("p5ready", false)
                            game_room.update("now_players", document["now_players"].toString().toInt() - 1)
                            builder.dismiss()
                        }
                        cancel.setOnClickListener {
                            builder.dismiss()
                        }
                        builder.show()
                    }
                }
            }

            mPlayerImage[5].setOnClickListener {
                game_room.get().addOnSuccessListener { document->
                    if(document.get("p6") != null) {
                        val builder = AlertDialog.Builder(this).create()
                        val dialogView = layoutInflater.inflate(R.layout.dialog_kick_out, null)
                        val ok = dialogView.findViewById<Button>(R.id.button_okay_kick_out)
                        val cancel = dialogView.findViewById<Button>(R.id.button_cancel_kick_out)
                        builder.setView(dialogView)
                        ok.setOnClickListener {
                            game_room.update("p6", null)
                            game_room.update("p6ready", false)
                            game_room.update("now_players", document["now_players"].toString().toInt() - 1)
                            builder.dismiss()
                        }
                        cancel.setOnClickListener {
                            builder.dismiss()
                        }
                        builder.show()
                    }
                }
            }
        }
    }

    private fun RoomInfo(snapshotData: Map<String, Any>) {
        //강퇴 확인
        if(number != 1) {
            game_room.get().addOnSuccessListener { document->
                if(document["p$number"] == null) {
                    Toast.makeText(this, "강퇴되었습니다", Toast.LENGTH_SHORT).show()
                    val waitingroom = hashMapOf(
                        "waitingroom.0" to null,
                        "waitingroom.1" to "0"
                    )
                    db.collection("user").document(user.currentUser?.email.toString()).update("waitingroom", waitingroom)
                    finish()
                }
            }
        }
        //방 정보 수정
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
                        mPlayerNickname[i - 1].text = "Waiting For"
                        mPlayerRating[i - 1].text = "New Player..."
                        mPlayerImage[i - 1].setImageResource(R.drawable.icon)
                    }
            } else {
                mPlayerNickname[i - 1].text = "Waiting For"
                mPlayerRating[i - 1].text = "New Player..."
                mPlayerImage[i - 1].setImageResource(R.drawable.icon)
            }
            if(i != 1) {
                if(snapshotData["p${i}ready"] == true) {
                    mPlayerThumbsUpImage[i - 1].visibility = View.VISIBLE
                }
                else {
                    mPlayerThumbsUpImage[i - 1].visibility = View.INVISIBLE
                }
            }
        }
    }

    private suspend fun gameStart() {
        if(number != 1) {
            runOnUiThread {
                Toast.makeText(this, "게임을 시작합니다", Toast.LENGTH_SHORT).show()
            }

            val intent = Intent(this, GameRoomActivity::class.java)

            intent.putExtra("gameId", gameId)
            intent.putExtra("number", number.toString())
            intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            Log.d(TAG, "number : " + number)
            startActivity(intent)
            Handler(Looper.getMainLooper()).postDelayed({
                finish()
            }, 1000)
        }
        else {
            db.collection("game_rooms").document(gameId).get().addOnSuccessListener { document->
                if(document["state"] == true){
                var readys = 0
                for(i in 2 until max_number + 1) {
                    if(document["p${i}ready"] == true) readys++
                }
                if(readys == max_number - 1) {
                    Toast.makeText(this, "게임을 시작합니다", Toast.LENGTH_SHORT).show()
                    document.reference.update("state", false)
                    val intent = Intent(this, GameRoomActivity::class.java)

                    val cardDeck = ArrayList<Int>()
                    for(cardCnt in 1 until 4){
                        for(cardType in 1 until 6) {
                            cardDeck.add(cardType)
                        }
                    }

                    val numberOfPlayers = max_number
                    val cardsPerPlayer = 2

                    // 사용자 별로 카드 분배
                    var userCard = hashMapOf<String, Any>()
                    for (playerIndex in 0 until numberOfPlayers) {
                        for (cardIndex in 0 until cardsPerPlayer) {
                            val randomCardIndex = (0 until cardDeck.size).random()
                            val card = cardDeck[randomCardIndex]
                            userCard.put("p${playerIndex+1}card${cardIndex+1}", card)
                            cardDeck.removeAt(randomCardIndex)
                            Log.d("GameWaitingRoom", "card random select: $card")
                            Log.d("GameWaitingRoom", "card deck left: ${cardDeck.size}")
                        }
                    }
                    //players랑 cardDeck 둘다 ArrayList로 써서 row&col Index 0부터 시작
                    //players 에는 랜덤 분배된 카드가 2개씩 저장 / cardDeck 에는 남은 카드들의 ID 저장 되어 있음
                    Log.d("GameWaitingRoom", "random select complete. card deck left: ${cardDeck.size}")

                    var cardDeckString: String = ""
                    for (cardDeckIndex in 0 until (cardDeck.size)) {
                        cardDeckString += cardDeck[cardDeckIndex].toString()
                    }
                    userCard.put("card_left", cardDeckString)
                    userCard.put("card_open", 0)
                    val userCoin = hashMapOf<String, Int>()
                    val userAccept = hashMapOf<String, Any?>()

                    val hashmap: HashMap<String, Any> = hashMapOf(
                        "players" to max_number,
                        "turn" to 0,
                    )


                    val gameResult: HashMap<String, Any> = hashMapOf(
                        "timestamp" to 0,
                        "players" to 0,
                        "finish" to 0
                    )

                    for(i in 1 until max_number + 1) {
                        val pValue = document["p$i"]
                        if(pValue != null) {
                            gameResult["p$i"] = pValue.toString()
                            gameResult["p${i}rank"] = 0
                            hashmap["p$i"] = pValue.toString()
                            userCoin["p$i"] = 2
                            userAccept["p$i"] = null
                        }
                    }
                    for(i in max_number+1 .. 6) {
                        gameResult["p$i"] = ""
                    }

                    val doc_result = db.collection("game_result").document(gameId)
                    val doc_info = db.collection("game_playing").document(gameId+"_INFO")
                    val doc_card = db.collection("game_playing").document(gameId+"_CARD")
                    val doc_coin = db.collection("game_playing").document(gameId+"_COIN")
                    val doc_accept = db.collection("game_playing").document(gameId+"_ACCEPT")
                    val doc_action = db.collection("game_playing").document(gameId+"_ACTION")
                    db.runBatch { batch->
                        batch.set(doc_info, hashmap)
                        batch.set(doc_card, userCard)
                        batch.set(doc_coin, userCoin)
                        batch.set(doc_accept, userAccept)
                        batch.set(doc_action, hashMapOf(
                            "from" to 0,
                            "to" to 0,
                            "action" to 0,
                            "challenge" to 0,
                            "challenge_type" to 0,
                            "challenge2" to 0
                        ))
                        batch.set(doc_result, gameResult)
                    }.addOnSuccessListener {
                        Log.d(TAG, "게임 방 생성 성공")
                    }

                    intent.putExtra("gameId", gameId)
                    intent.putExtra("number", number.toString())
                    intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    startActivity(intent)
                    snapshotListener.remove()
                    document.reference.update("state", false)
                    Handler(Looper.getMainLooper()).postDelayed({
                        document.reference.delete()
                        finish()
                    }, 1000)
                }
                else {
                    Toast.makeText(this, "모든 인원이 다 준비해야 시작 가능합니다", Toast.LENGTH_SHORT).show()
                }
            }
            }.await()
        }

    }

    override fun onDestroy() {
        super.onDestroy()
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0);
    }


    override fun onPause() {
        db.collection("user").document(user.currentUser?.email.toString()).update("state", false)
        if(number != 1) {
            db.collection("game_rooms").document(gameId).update("p${number}ready", false)
        }
        super.onPause()
    }
    override fun onStop() {
        super.onStop()
    }

    override fun onResume() {
        db.collection("user").document(user.currentUser?.email.toString()).update("state", true)
        super.onResume()
    }

    override fun onBackPressed() {
        if(number == 1) {
            val builder = AlertDialog.Builder(this)
                .setTitle("나가기")
                .setMessage("방을 폭파시키겠습니까?")
                .setPositiveButton("예") { dialog, which ->
                    db.collection("game_rooms").document(gameId).delete()
                    dialog.dismiss()
                    finish()
                    super.onBackPressed()
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
                    val waitingroom = hashMapOf(
                    "waitingroom.0" to null,
                    "waitingroom.1" to "0"
                    )
                    db.collection("user").document(user.currentUser?.email.toString()).update("waitingroom", waitingroom)
                    db.collection("game_rooms").document(gameId).update("p$number", null)
                    db.collection("game_rooms").document(gameId).update("p${number}ready", null)
                    db.collection("game_rooms").document(gameId).get().addOnSuccessListener { document->
                        document.reference.update("now_players", document["now_players"].toString().toInt() - 1)
                    }
                    dialog.dismiss()
                    finish()
                    super.onBackPressed()
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