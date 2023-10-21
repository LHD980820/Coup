package com.example.coup

import android.annotation.SuppressLint
import android.app.Dialog
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.util.Log
import android.view.KeyEvent.DispatcherState
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.graphics.Color
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import de.hdodenhof.circleimageview.CircleImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.zip.Inflater

class GameRoomActivity : AppCompatActivity() {

    private lateinit var mLeftCardText: TextView
    private lateinit var mActionConstraint: ConstraintLayout
    private lateinit var mActionIcon: Array<ImageView>
    private lateinit var mActionText: TextView
    private lateinit var mPlayerConstraint: Array<ConstraintLayout>

    private lateinit var mPlayerText: Array<TextView>
    private lateinit var mPlayerCoin: Array<TextView>
    private lateinit var mPlayerCard: Array<Array<ImageView>>
    private lateinit var mPlayerProfileImage: Array<CircleImageView>
    private lateinit var mPlayerNickname: Array<TextView>
    private lateinit var mPlayerCardDie: Array<Array<ImageView>>
    private lateinit var mPlayerThumbsUp: Array<ImageView>
    private lateinit var mPlayerThreeDot: Array<ImageView>
    private lateinit var mPlayerAllDie: Array<ImageView>

    private lateinit var auth: FirebaseAuth
    private lateinit var storage: FirebaseStorage
    private lateinit var db: FirebaseFirestore
    private lateinit var documentRef: DocumentReference

    private lateinit var gameId: String
    private var number: Int = 0
    private var max_number: Int = 0
    private var pCard1: Int = 0
    private var pCard2: Int = 0
    private lateinit var cardDeck: String //추후 lateinit var로 변경 필수



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_game_room)

        init()
        //게임 시작
        gameStart()

        //action Button 클릭 시 bottom sheet dialog 띄우기
        findViewById<Button>(R.id.action_button).setOnClickListener {
            val bottomSheet = BottomSheetDialog(this)
            val view = layoutInflater.inflate(R.layout.activity_game_room_bottomsheet, null)
            val line1 = view.findViewById<ConstraintLayout>(R.id.game_action_btn_layout1)
            line1.visibility = View.GONE
            bottomSheet.setContentView(view)
            bottomSheet.show()

        }

    }

    @SuppressLint("UseCompatLoadingForDrawables")
    fun init() {
        gameId = intent.getStringExtra("gameId").toString()
        Log.d(TAG, "number : " + intent.getStringExtra("number").toString())
        number = intent.getStringExtra("number")?.toString()?.toInt()!!
        mLeftCardText = findViewById(R.id.card_left_game_room)

        mPlayerConstraint = Array(6) { ConstraintLayout(this) }
        mPlayerConstraint[0] = findViewById(R.id.p1_constraint)
        mPlayerConstraint[1] = findViewById(R.id.p2_constraint)
        mPlayerConstraint[2] = findViewById(R.id.p3_constraint)
        mPlayerConstraint[3] = findViewById(R.id.p4_constraint)
        mPlayerConstraint[4] = findViewById(R.id.p5_constraint)
        mPlayerConstraint[5] = findViewById(R.id.p6_constraint)

        mPlayerText = Array(6) { TextView(this) }
        mPlayerText[0] = findViewById(R.id.p1_game_room)
        mPlayerText[1] = findViewById(R.id.p2_game_room)
        mPlayerText[2] = findViewById(R.id.p3_game_room)
        mPlayerText[3] = findViewById(R.id.p4_game_room)
        mPlayerText[4] = findViewById(R.id.p5_game_room)
        mPlayerText[5] = findViewById(R.id.p6_game_room)

        mPlayerCoin = Array(6) { TextView(this) }
        mPlayerCoin[0] = findViewById(R.id.p1_coin_game_room)
        mPlayerCoin[1] = findViewById(R.id.p2_coin_game_room)
        mPlayerCoin[2] = findViewById(R.id.p3_coin_game_room)
        mPlayerCoin[3] = findViewById(R.id.p4_coin_game_room)
        mPlayerCoin[4] = findViewById(R.id.p5_coin_game_room)
        mPlayerCoin[5] = findViewById(R.id.p6_coin_game_room)

        mPlayerCard = Array(6) { Array(2) { ImageView(this) } }
        mPlayerCard[0][0] = findViewById(R.id.p1_card1)
        mPlayerCard[0][1] = findViewById(R.id.p1_card2)
        mPlayerCard[1][0] = findViewById(R.id.p2_card1)
        mPlayerCard[1][1] = findViewById(R.id.p2_card2)
        mPlayerCard[2][0] = findViewById(R.id.p3_card1)
        mPlayerCard[2][1] = findViewById(R.id.p3_card2)
        mPlayerCard[3][0] = findViewById(R.id.p4_card1)
        mPlayerCard[3][1] = findViewById(R.id.p4_card2)
        mPlayerCard[4][0] = findViewById(R.id.p5_card1)
        mPlayerCard[4][1] = findViewById(R.id.p5_card2)
        mPlayerCard[5][0] = findViewById(R.id.p6_card1)
        mPlayerCard[5][1] = findViewById(R.id.p6_card2)

        mPlayerProfileImage = Array(6) { CircleImageView(this) }
        mPlayerProfileImage[0] = findViewById(R.id.p1_profile_game_room)
        mPlayerProfileImage[1] = findViewById(R.id.p2_profile_game_room)
        mPlayerProfileImage[2] = findViewById(R.id.p3_profile_game_room)
        mPlayerProfileImage[3] = findViewById(R.id.p4_profile_game_room)
        mPlayerProfileImage[4] = findViewById(R.id.p5_profile_game_room)
        mPlayerProfileImage[5] = findViewById(R.id.p6_profile_game_room)

        mPlayerNickname = Array(6) { TextView(this) }
        mPlayerNickname[0] = findViewById(R.id.p1_nickname_game_room)
        mPlayerNickname[1] = findViewById(R.id.p2_nickname_game_room)
        mPlayerNickname[2] = findViewById(R.id.p3_nickname_game_room)
        mPlayerNickname[3] = findViewById(R.id.p4_nickname_game_room)
        mPlayerNickname[4] = findViewById(R.id.p5_nickname_game_room)
        mPlayerNickname[5] = findViewById(R.id.p6_nickname_game_room)

        mPlayerCardDie = Array(6) { Array(2) { ImageView(this) } }
        mPlayerCardDie[0][0] = findViewById(R.id.p1_die1)
        mPlayerCardDie[0][1] = findViewById(R.id.p1_die2)
        mPlayerCardDie[1][0] = findViewById(R.id.p2_die1)
        mPlayerCardDie[1][1] = findViewById(R.id.p2_die2)
        mPlayerCardDie[2][0] = findViewById(R.id.p3_die1)
        mPlayerCardDie[2][1] = findViewById(R.id.p3_die2)
        mPlayerCardDie[3][0] = findViewById(R.id.p4_die1)
        mPlayerCardDie[3][1] = findViewById(R.id.p4_die2)
        mPlayerCardDie[4][0] = findViewById(R.id.p5_die1)
        mPlayerCardDie[4][1] = findViewById(R.id.p5_die2)
        mPlayerCardDie[5][0] = findViewById(R.id.p6_die1)
        mPlayerCardDie[5][1] = findViewById(R.id.p6_die2)

        mPlayerThumbsUp = Array(6) { ImageView(this) }
        mPlayerThumbsUp[0] = findViewById(R.id.p1_thumbs_up)
        mPlayerThumbsUp[1] = findViewById(R.id.p2_thumbs_up)
        mPlayerThumbsUp[2] = findViewById(R.id.p3_thumbs_up)
        mPlayerThumbsUp[3] = findViewById(R.id.p4_thumbs_up)
        mPlayerThumbsUp[4] = findViewById(R.id.p5_thumbs_up)
        mPlayerThumbsUp[5] = findViewById(R.id.p6_thumbs_up)

        mPlayerThreeDot = Array(6) { ImageView(this) }
        mPlayerThreeDot[0] = findViewById(R.id.p1_3dot)
        mPlayerThreeDot[1] = findViewById(R.id.p2_3dot)
        mPlayerThreeDot[2] = findViewById(R.id.p3_3dot)
        mPlayerThreeDot[3] = findViewById(R.id.p4_3dot)
        mPlayerThreeDot[4] = findViewById(R.id.p5_3dot)
        mPlayerThreeDot[5] = findViewById(R.id.p6_3dot)

        mPlayerAllDie = Array(6) { ImageView(this) }
        mPlayerAllDie[0] = findViewById(R.id.p1_die_all)
        mPlayerAllDie[1] = findViewById(R.id.p2_die_all)
        mPlayerAllDie[2] = findViewById(R.id.p3_die_all)
        mPlayerAllDie[3] = findViewById(R.id.p4_die_all)
        mPlayerAllDie[4] = findViewById(R.id.p5_die_all)
        mPlayerAllDie[5] = findViewById(R.id.p6_die_all)

        mActionConstraint = findViewById(R.id.action_game_room)
        mActionIcon = Array(2) { ImageView(this) }
        mActionIcon[0] = findViewById(R.id.action_icon_left_game_room)
        mActionIcon[1] = findViewById(R.id.action_icon_right_game_room)
        mActionText = findViewById(R.id.action_text_game_room)

        auth = FirebaseManager.getFirebaseAuth()
        storage = FirebaseStorage.getInstance()
        db = FirestoreManager.getFirestore()
        documentRef = db.collection("game_playing").document(gameId)
        //시작 ui 설정
        uiUpdate()
    }

    private fun uiUpdate() {
        documentRef.get().addOnSuccessListener { document->
            val coin = document["coin"] as HashMap<*, *>
            val email = document["email"] as HashMap<*, *>
            mLeftCardText.text = ": " + document["card_left"].toString().length.toString() + " Left"
            //인원 수에 맞게 프로필 생성
            max_number = document["players"].toString().toInt()
            for(i in 0 until max_number) {
                //자기 번호일 때 "YOU"로 바꾸고, 카드 보이게 하기
                if(i == number - 1) {
                    mPlayerText[i].text = "YOU"
                    val textColor = ContextCompat.getColor(this, R.color.red)
                    mPlayerText[i].setTextColor(textColor)
                    val cardHash = document["card"] as HashMap<*, *>
                    mPlayerCard[i][0].setImageResource(cardFromNumber(cardHash["p${number}card1"].toString().toInt()))
                    mPlayerCard[i][1].setImageResource(cardFromNumber(cardHash["p${number}card2"].toString().toInt()))
                }
                mPlayerAllDie[i].visibility = View.INVISIBLE
                mPlayerThumbsUp[i].visibility = View.INVISIBLE
                mPlayerThreeDot[i].visibility = View.INVISIBLE
                mPlayerCoin[i].text = ": " + coin["p${i + 1}"]
                mPlayerCardDie[i][0].visibility = View.INVISIBLE
                mPlayerCardDie[i][1].visibility = View.INVISIBLE
                db.collection("user").document(email["p${i + 1}"].toString()).get()
                    .addOnSuccessListener { user_document ->
                        storage.reference.child("profile_images/${user_document.id}.jpg").downloadUrl.addOnSuccessListener { imageUrl ->
                            if (!this.isDestroyed) {
                                Glide.with(this)
                                    .load(imageUrl)
                                    .into(mPlayerProfileImage[i])
                            }
                        }
                        mPlayerNickname[i].text = user_document["nickname"].toString()
                    }
            }
            //없는 사람 프로필 없애기
            for(i in max_number until 6) {
                mPlayerConstraint[i].visibility = View.GONE
            }
            //카드 클릭 시 카드 정보 띄우기
            val dialogView = layoutInflater.inflate(R.layout.dialog_card_info, null)
            val card = dialogView.findViewById<ImageView>(R.id.card_info)
            val builder = Dialog(this)
            for(i in 0 until max_number) {
                for(j in 0 until 2) {
                    mPlayerCard[i][j].setOnClickListener{
                        Log.d(TAG, "카드 클릭 됨")
                        builder.dismiss()
                        documentRef.get().addOnCompleteListener { task->
                            if(task.isSuccessful) {
                                val document = task.result
                                val cards = document["card"] as HashMap<*, *>
                                if(i == number - 1 || cards["p${i + 1}card${j + 1}"].toString().toInt()/10 != 0) {
                                    card.setImageResource(cardFromNumber(cards["p${i + 1}card${j + 1}"].toString().toInt()))
                                    Log.d(TAG, "카드 정보 다이얼로그 출력")
                                    builder.setContentView(dialogView)
                                    builder.show()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun gameStart() {
        //자기 카드 dialog 띄우기
        val dialogView = layoutInflater.inflate(R.layout.dialog_start_cards_info, null)
        val builder = AlertDialog.Builder(this)
            .setView(dialogView).create()
        val cardOne = dialogView.findViewById<ImageView>(R.id.card1_start_cards)
        val cardTwo = dialogView.findViewById<ImageView>(R.id.card2_start_cards)
        val okButton = dialogView.findViewById<Button>(R.id.ok_button_start_cards)
        val timer = dialogView.findViewById<TextView>(R.id.timer_start_cards)

        var countDownTimer: CountDownTimer? = null

        CoroutineScope(Dispatchers.IO).launch {
            documentRef.get().addOnCompleteListener { task->
                if(task.isSuccessful) {
                    val document = task.result
                    val cardHash = document["card"] as HashMap<*, *>
                    cardOne.setImageResource(cardFromNumber(cardHash["p${number}card1"].toString().toInt()))
                    cardTwo.setImageResource(cardFromNumber(cardHash["p${number}card2"].toString().toInt()))

                    countDownTimer = object : CountDownTimer(5000, 1000) { // 5초 동안, 1초 간격으로 타이머 설정
                        override fun onTick(millisUntilFinished: Long) {
                            // 매 초마다 실행되는 코드
                            val secondsLeft = millisUntilFinished / 1000
                            timer.text = secondsLeft.toString()
                        }

                        override fun onFinish() {
                            // 타이머가 종료되면 실행되는 코드
                            val accept = document["accept"] as HashMap<*, *>
                            if(accept["p$number"] == false) documentRef.update("accept", hashMapOf("p$number" to true))
                            builder.dismiss()
                        }
                    }
                    countDownTimer?.start()
                }
            }.await()
        }

        okButton.setOnClickListener {
            documentRef.update("accept", hashMapOf("p$number" to true))
            countDownTimer?.cancel()
            builder.dismiss()
        }
        builder.setCanceledOnTouchOutside(false)
        builder.show()
        mActionText.text = "모두가 준비 완료될 때까지 잠시 기다려 주세요"
    }

    private fun cardFromNumber(number: Int): Int {
        return when(number) {
            1-> R.drawable.card_duke
            10-> R.drawable.card_duke
            2-> R.drawable.card_contessa
            20-> R.drawable.card_contessa
            3-> R.drawable.card_captine
            30-> R.drawable.card_captine
            4-> R.drawable.card_assassin
            40-> R.drawable.card_assassin
            5-> R.drawable.card_ambassador
            50-> R.drawable.card_ambassador
            else-> R.drawable.card_back
        }
    }

    private fun newTurn() {
        CoroutineScope(Dispatchers.IO).launch {
            documentRef.get().addOnCompleteListener { task->
                if(task.isSuccessful) {
                    val document = task.result
                    val nowTurn = document["turn"].toString().toInt()
                    if(number == nowTurn) {
                        mActionText.text = "action버튼을 눌러 행동을 선택해 주세요"
                        actionButtonSetting(1)

                        val countDownTimer = object : CountDownTimer(60000, 1000) { // 60초 동안, 1초 간격으로 타이머 설정
                            override fun onTick(millisUntilFinished: Long) {
                                // 매 초마다 실행되는 코드
                                val secondsLeft = millisUntilFinished / 1000
                                // UI 업데이트 등을 수행할 수 있음
                            }

                            override fun onFinish() {
                                // 타이머가 종료되면 실행되는 코드
                            }
                        }

                        countDownTimer.start() // 타이머 시작
                    }
                }
            }.await()
        }
        //게임 종료(한명을 제외한 다른 플레이어의 카드가 모두 죽었을때)인지 체크하고, 게임 종료 시 게임 결과 창 띄우고 아니면 새루운 턴 진행
    }

    private fun actionButtonSetting(number: Int) {
        when(number) {
        }
    }

    private suspend fun oneCoinMain() {

    }

    private suspend fun twoCoinMain() {

    }

    private suspend fun coupMain() {

    }

    private suspend fun dukeMain() {

    }

    private suspend fun contessaMain() {

    }

    private suspend fun captineMain() {

    }

    private suspend fun ambassadorMain() {

    }

    companion object{
        val TAG = "GameRoomActivity"
    }

}