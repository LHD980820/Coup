package com.example.coup

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent.DispatcherState
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.ui.graphics.Color
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import com.example.coup.ui.login.LoginActivity
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.storage.FirebaseStorage
import de.hdodenhof.circleimageview.CircleImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.zip.Inflater
import kotlin.random.Random

class GameRoomActivity : AppCompatActivity() {

    private lateinit var mTimeLeft: TextView
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
    private lateinit var documentInfo: DocumentReference
    private lateinit var documentCard: DocumentReference
    private lateinit var documentCoin: DocumentReference
    private lateinit var documentAccept: DocumentReference
    private lateinit var documentAction: DocumentReference

    private lateinit var gameId: String
    private var number: Int = 0
    private var max_number: Int = 0
    //카드 정보 로컬에 저장
    private var pCard = Array(6) {IntArray(2) {0} }
    private var pCardLeft = "0"
    private var nowActionCode: String = "0"
    private var nowTurn: Int = 0
    private lateinit var snapshotListenerCoin: ListenerRegistration
    private lateinit var snapshotListenerAccept: ListenerRegistration
    private lateinit var snapshotListenerAction: ListenerRegistration

    private lateinit var bottomSheet: BottomSheetDialog
    private lateinit var bottomSheetView: View
    private lateinit var bottomSheetDefault: ConstraintLayout
    private lateinit var bottomSheetAbility: ConstraintLayout
    private lateinit var bottomSheetBlockByDuke: ConstraintLayout
    private lateinit var bottomSheetBlockByCaptainOrAmbassador: ConstraintLayout
    private lateinit var bottomSheetBlockByContessa: ConstraintLayout
    private lateinit var bottomSheetChallenge: ConstraintLayout

    private lateinit var buttonIncome: Button
    private lateinit var buttonForeignAid: Button
    private lateinit var buttonCoup: Button
    private lateinit var buttonTax: Button
    private lateinit var buttonAssassinate: Button
    private lateinit var buttonSteal: Button
    private lateinit var buttonExchange: Button
    private lateinit var buttonChallenge: Button
    private lateinit var buttonAdmit: Button
    private lateinit var buttonBlockByCaptain: Button
    private lateinit var buttonBlockByAmbassador: Button
    private lateinit var buttonBlockByDuke: Button
    private lateinit var buttonBlockByContessa: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_game_room)

        //게임 시작
        init()
        actionButtonSetting(0)
        //settingClickListener()
        gameStart()
    }
    private fun settingSnapshots() {
        snapshotListenerAction = documentAction.addSnapshotListener { snapshot, e ->

        }
        snapshotListenerCoin = documentCoin.addSnapshotListener{ snapshot, e->

        }
        snapshotListenerAccept = documentAccept.addSnapshotListener { snapshot, e->

        }
    }

    @SuppressLint("UseCompatLoadingForDrawables")
    private fun init() {
        gameId = intent.getStringExtra("gameId").toString()
        Log.d(TAG, "number : " + intent.getStringExtra("number").toString())
        number = intent.getStringExtra("number")?.toString()?.toInt()!!
        mLeftCardText = findViewById(R.id.card_left_game_room)
        mTimeLeft = findViewById(R.id.time_game_room)
        mTimeLeft.visibility = View.INVISIBLE

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


        bottomSheet = BottomSheetDialog(this)
        bottomSheetView = layoutInflater.inflate(R.layout.activity_game_room_bottomsheet, null)
        bottomSheet.setContentView(bottomSheetView)
        bottomSheetDefault = bottomSheetView.findViewById(R.id.game_action_btn_layout1)
        bottomSheetAbility = bottomSheetView.findViewById(R.id.game_action_btn_layout2)
        bottomSheetChallenge = bottomSheetView.findViewById(R.id.game_action_btn_layout3)
        bottomSheetBlockByDuke = bottomSheetView.findViewById(R.id.game_action_btn_layout5)
        bottomSheetBlockByCaptainOrAmbassador = bottomSheetView.findViewById(R.id.game_action_btn_layout4)
        bottomSheetBlockByContessa = bottomSheetView.findViewById(R.id.game_action_btn_layout6)
        buttonIncome = bottomSheetView.findViewById(R.id.action_income_btn)
        buttonForeignAid = bottomSheetView.findViewById(R.id.action_foreign_aid_btn)
        buttonCoup = bottomSheetView.findViewById(R.id.action_coup_btn)
        buttonTax = bottomSheetView.findViewById(R.id.action_tax_btn)
        buttonAssassinate = bottomSheetView.findViewById(R.id.action_assassinate_btn)
        buttonSteal = bottomSheetView.findViewById(R.id.action_steal_btn)
        buttonExchange = bottomSheetView.findViewById(R.id.action_exchange_btn)
        buttonChallenge = bottomSheetView.findViewById(R.id.action_challenge_btn)
        buttonAdmit = bottomSheetView.findViewById(R.id.action_admit_btn)
        buttonBlockByCaptain = bottomSheetView.findViewById(R.id.action_blockbycaptain_btn)
        buttonBlockByAmbassador = bottomSheetView.findViewById(R.id.action_blockbyambassador_btn)
        buttonBlockByDuke = bottomSheetView.findViewById(R.id.action_blockbyduke_btn)
        buttonBlockByContessa = bottomSheetView.findViewById(R.id.action_blockbycontessa_btn)

        auth = FirebaseManager.getFirebaseAuth()
        storage = FirebaseStorage.getInstance()
        db = FirestoreManager.getFirestore()
        documentInfo = db.collection("game_playing").document(gameId+"_INFO")
        documentCard = db.collection("game_playing").document(gameId+"_CARD")
        documentCoin = db.collection("game_playing").document(gameId+"_COIN")
        documentAccept = db.collection("game_playing").document(gameId+"_ACCEPT")
        documentAction = db.collection("game_playing").document(gameId+"_ACTION")
        //시작 ui 설정
        uiUpdateFirst()
    }

    private fun uiUpdateFirst() {
        CoroutineScope(Dispatchers.IO).launch {
            documentInfo.get().addOnCompleteListener { task->
                if(task.isSuccessful) {
                    val result = task.result
                    //인원 수에 맞게 프로필 생성
                    max_number = result["players"].toString().toInt()
                    for(i in 0 until max_number) {
                        mPlayerAllDie[i].visibility = View.INVISIBLE
                        mPlayerThumbsUp[i].visibility = View.INVISIBLE
                        mPlayerThreeDot[i].visibility = View.INVISIBLE
                        mPlayerCardDie[i][0].visibility = View.INVISIBLE
                        mPlayerCardDie[i][1].visibility = View.INVISIBLE
                        db.collection("user").document(result["p${i+1}"].toString()).get()
                            .addOnSuccessListener { user_document ->
                                storage.reference.child("profile_images/${user_document.id}.jpg").downloadUrl.addOnSuccessListener { imageUrl ->
                                    if (!this@GameRoomActivity.isDestroyed) {
                                        Glide.with(this@GameRoomActivity)
                                            .load(imageUrl)
                                            .into(mPlayerProfileImage[i])
                                    }
                                }
                                mPlayerNickname[i].text = user_document["nickname"].toString()
                            }
                    }
                }
                //없는 사람 프로필 없애기
                for(i in max_number until 6) {
                    mPlayerConstraint[i].visibility = View.GONE
                }
            }.await()
            documentCard.get().addOnCompleteListener { task->
                if(task.isSuccessful) {
                    val result = task.result
                    mLeftCardText.text = ": " + result["card_left"].toString().length.toString() + " Left"
                    pCardLeft = result["card_left"].toString()
                    for(i in 0 until max_number) {
                        //자기 번호일 때 "YOU"로 바꾸고, 카드 보이게 하기
                        if(i == number - 1) {
                            mPlayerText[i].text = "YOU"
                            val textColor = ContextCompat.getColor(this@GameRoomActivity, R.color.red)
                            mPlayerText[i].setTextColor(textColor)
                            mPlayerCard[i][0].setImageResource(cardFromNumber(result["p${number}card1"].toString().toInt()))
                            mPlayerCard[i][1].setImageResource(cardFromNumber(result["p${number}card2"].toString().toInt()))
                        }
                        pCard[i][0] = result["p${number}card1"].toString().toInt()
                        pCard[i][1] = result["p${number}card2"].toString().toInt()
                    }
                settingClickListener()
                }
            }.await()
            documentCoin.get().addOnCompleteListener { task->
                if(task.isSuccessful) {
                    val result = task.result
                    for(i in 0 until max_number) {
                        mPlayerCoin[i].text = ": " + result["p${i + 1}"]
                    }
                }
            }.await()
            //카드 클릭 시 카드 정보 띄우기
        }

    }

    private fun settingClickListener() {
        Log.d(TAG, "카드 클릭 리스너 설정 max_number = $max_number")
        val dialogView = layoutInflater.inflate(R.layout.dialog_card_info, null)
        val card = dialogView.findViewById<ImageView>(R.id.card_info)
        val builder = Dialog(this)
        for(i in 0 until max_number) {
            for(j in 0 until 2) {
                Log.d(TAG, "카드 클릭 리스너 설정($i + $j)")
                mPlayerCard[i][j].setOnClickListener{
                    Log.d(TAG, "카드 클릭 됨")
                    builder.dismiss()
                    if(i == number - 1 || pCard[i][j]/10 != 0) {
                        card.setImageResource(cardFromNumber(pCard[i][j].toString().toInt()))
                        Log.d(TAG, "카드 정보 다이얼로그 출력")
                        builder.setContentView(dialogView)
                        builder.show()
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
            if(number == 1) {
                db.runBatch { batch->
                    for(i in 0 until max_number) {
                        batch.update(documentAccept, "p${i+1}", false)
                    }
                }.await()
            }

        }
        documentCard.get().addOnCompleteListener { task->
            if(task.isSuccessful) {
                val document = task.result
                cardOne.setImageResource(cardFromNumber(document["p${number}card1"].toString().toInt()))
                cardTwo.setImageResource(cardFromNumber(document["p${number}card2"].toString().toInt()))

                countDownTimer = object : CountDownTimer(5000, 1000) { // 5초 동안, 1초 간격으로 타이머 설정
                    override fun onTick(millisUntilFinished: Long) {
                        // 매 초마다 실행되는 코드
                        val secondsLeft = millisUntilFinished / 1000
                        timer.text = secondsLeft.toString()
                    }

                    override fun onFinish() {
                        // 타이머가 종료되면 실행되는 코드
                        if(number == 1) {
                            documentInfo.update("turn", Random.nextInt(1, max_number + 1))
                        }
                        builder.dismiss()
                    }
                }
                countDownTimer?.start()
            }
        }
        okButton.setOnClickListener {
            //countDownTimer?.cancel()
            builder.dismiss()
        }
        builder.setCanceledOnTouchOutside(false)
        builder.show()
        mActionText.text = "게임 준비 중"
    }
    private fun setAccept(snapshot: DocumentSnapshot) {
        for(i in 0 until max_number) {
            if(snapshot["p${i+1}"] == null) {
                mPlayerThreeDot[i].visibility = View.INVISIBLE
                mPlayerThumbsUp[i].visibility = View.INVISIBLE
            }
            else if(snapshot["p${i+1}"] == true) {
                mPlayerThreeDot[i].visibility = View.INVISIBLE
                mPlayerThumbsUp[i].visibility = View.VISIBLE
            }
            else {
                mPlayerThreeDot[i].visibility = View.VISIBLE
                mPlayerThumbsUp[i].visibility = View.INVISIBLE
            }
        }
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

    private fun gameEnd() {

    }

    private fun actionButtonSetting(number: Int) {
        //action Button 클릭 시 bottom sheet dialog 띄우기
        findViewById<Button>(R.id.action_button).setOnClickListener {
            if(bottomSheetDefault.visibility == View.GONE &&
                bottomSheetAbility.visibility == View.GONE &&
                bottomSheetChallenge.visibility == View.GONE &&
                bottomSheetBlockByDuke.visibility == View.GONE &&
                bottomSheetBlockByCaptainOrAmbassador.visibility == View.GONE &&
                bottomSheetBlockByContessa.visibility == View.GONE) {
                Toast.makeText(this, "할 수 있는 행동이 없습니다", Toast.LENGTH_SHORT).show()
            }
            else {
                bottomSheet.show()
            }
        }
        when(number) {
            0 -> {  //할거 없을 때
                bottomSheetDefault.visibility = View.GONE
                bottomSheetAbility.visibility = View.GONE
                bottomSheetChallenge.visibility = View.GONE
                bottomSheetBlockByDuke.visibility = View.GONE
                bottomSheetBlockByCaptainOrAmbassador.visibility = View.GONE
                bottomSheetBlockByContessa.visibility = View.GONE
            }
            1 -> {  //기본행동
                bottomSheetDefault.visibility = View.VISIBLE
                bottomSheetAbility.visibility = View.VISIBLE
                bottomSheetChallenge.visibility = View.GONE
                bottomSheetBlockByDuke.visibility = View.GONE
                bottomSheetBlockByCaptainOrAmbassador.visibility = View.GONE
                bottomSheetBlockByContessa.visibility = View.GONE
            }
            2 -> {  //도전
                bottomSheetDefault.visibility = View.GONE
                bottomSheetAbility.visibility = View.GONE
                bottomSheetChallenge.visibility = View.VISIBLE
                bottomSheetBlockByDuke.visibility = View.GONE
                bottomSheetBlockByCaptainOrAmbassador.visibility = View.GONE
                bottomSheetBlockByContessa.visibility = View.GONE
            }
            3 -> {  //공작으로 막기, 허용
                bottomSheetDefault.visibility = View.GONE
                bottomSheetAbility.visibility = View.GONE
                bottomSheetChallenge.visibility = View.GONE
                bottomSheetBlockByDuke.visibility = View.VISIBLE
                bottomSheetBlockByCaptainOrAmbassador.visibility = View.GONE
                bottomSheetBlockByContessa.visibility = View.GONE
            }
            4 -> {  //외교관으로 막기, 사령관으로 막기, 도전
                bottomSheetDefault.visibility = View.GONE
                bottomSheetAbility.visibility = View.GONE
                bottomSheetChallenge.visibility = View.VISIBLE
                bottomSheetBlockByDuke.visibility = View.GONE
                bottomSheetBlockByCaptainOrAmbassador.visibility = View.VISIBLE
                bottomSheetBlockByContessa.visibility = View.GONE
            }
            5 -> {  //귀부인으로 막기, 도전
                bottomSheetDefault.visibility = View.GONE
                bottomSheetAbility.visibility = View.GONE
                bottomSheetChallenge.visibility = View.VISIBLE
                bottomSheetBlockByDuke.visibility = View.GONE
                bottomSheetBlockByCaptainOrAmbassador.visibility = View.GONE
                bottomSheetBlockByContessa.visibility = View.VISIBLE
            }
        }
    }

    private fun oneCoinMain() {

    }

    private fun twoCoinMain() {

    }

    private fun coupMain() {

    }

    private fun dukeMain() {

    }

    private fun contessaMain() {

    }

    private fun captineMain() {

    }

    private fun ambassadorMain() {

    }

    companion object{
        val TAG = "GameRoomActivity"
    }

}