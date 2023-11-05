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
import androidx.core.graphics.alpha
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
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.tasks.await
import java.util.zip.Inflater
import kotlin.random.Random
import kotlin.random.nextInt

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
    private var nowTurn: Int = 0
    private var nowActionCode = 0
    private var nowFrom: Int = 0
    private var nowTo: Int = 0
    private var nowChallengeCode = 0
    private var nowChallenger = 0
    private var nowChallengeCode2 = 0
    private lateinit var snapshotListenerInfo: ListenerRegistration
    private lateinit var snapshotListenerCoin: ListenerRegistration
    private lateinit var snapshotListenerAccept: ListenerRegistration
    private lateinit var snapshotListenerAction: ListenerRegistration
    private lateinit var snapshotListenerCard: ListenerRegistration

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
        gameStart()
        settingSnapshots()
        settingBottomSheetButtonListener()

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
    }
    private fun settingSnapshots() {
        snapshotListenerInfo = documentInfo.addSnapshotListener{ snapshot, e->
            nowTurn = snapshot?.get("turn").toString().toInt()
            if(nowTurn == number) {
                if(mPlayerCoin[number - 1].text.toString().toInt() >= 10) {
                    mActionText.text = "나의 턴. 코인이 10개 이상이므로 COUP만 가능합니다"
                }
                mActionText.text = "나의 턴. 행동을 선택해주세요"
                actionButtonSetting(1)
            }
            else {
                actionButtonSetting(0)
                if(nowTurn == 0) {
                    mActionText.text = "게임 준비 중"
                }
                mActionText.text = "P${nowTurn}턴 행동 대기 중"
            }
        }
        snapshotListenerAction = documentAction.addSnapshotListener { snapshot, e ->
            if(snapshot != null) {
                nowActionCode = snapshot.get("action").toString().toInt()
                nowFrom = snapshot.get("from").toString().toInt()
                nowTo = snapshot.get("to").toString().toInt()
                nowChallengeCode = snapshot.get("challenge_type").toString().toInt()
                nowChallenger = snapshot.get("challenge").toString().toInt()
                nowChallengeCode2 = snapshot.get("challenge2").toString().toInt()
                if(nowChallengeCode2 == 0) {
                    if(nowChallengeCode == 0) {
                        if(nowActionCode == 1) {
                            mActionText.text = "P${nowFrom}의 INCOME : coin +1"
                            documentCoin.update("p${nowFrom}", mPlayerCoin[nowFrom.toString().toInt() - 1].text.toString().toInt() + 1)
                            turnEnd()
                        }
                        if(nowActionCode == 2) {
                            if(nowFrom.toString().toInt() == number) {
                                mActionText.text = "FOREIGN AID시전, 다른 플레이어 응답 대기 중"
                            }
                            else {
                                mActionText.text = "P${snapshot.get("from")}의 FOREIGN AID 시도"
                                actionButtonSetting(3)
                                bottomSheet.show()
                            }
                        }
                        if(nowActionCode == 3) {
                            if(nowTo.toString().toInt() == number) {
                                mActionText.text = "Coup을 당했습니다"
                                cardElimination()
                                turnEnd()
                            }
                            else {
                                mActionText.text = "P${nowFrom}의 COUP to P${nowTo}"
                            }
                        }
                    }
                    else {
                        if(nowChallengeCode == 4) {
                            if(snapshot["challenge"].toString().toInt() == number) {
                                mActionText.text = "DUKE로 막기 시전, 다른 플레이어 응답 대기 중"
                            }
                            else {
                                mActionText.text = "P${snapshot.get("challenge")}의 막기(공작) 시도"
                                actionButtonSetting(2)
                            }
                        }
                    }
                }
                else {
                    mActionText.text = "FORIEGN AID(P${nowTurn} -> BLOCK BY DUKE(P${nowChallenger}\n-> CHALLENGE(P${nowChallengeCode2}"
                    if(nowChallengeCode2 == number) {
                        challengeFunc()
                    }
                }
            }
        }
        snapshotListenerCoin = documentCoin.addSnapshotListener{ snapshot, e->
            for(i in 0 until max_number) {
                mPlayerCoin[i].text = snapshot?.get("p${i + 1}").toString()
            }
        }
        snapshotListenerAccept = documentAccept.addSnapshotListener { snapshot, e->
            if(snapshot != null) {
                setAccept(snapshot)
            }
        }
        snapshotListenerCard = documentCard.addSnapshotListener{ snapshot, e->
            if(snapshot != null) {
                val openCard = snapshot["card_open"].toString().toInt()
                if(openCard == 0) {
                    pCardLeft = snapshot["card_left"].toString()
                    for(i in 0 until max_number) {
                        for(j in 0 until 2) {
                            pCard[i][j] = snapshot["p${i+1}card${j+1}"].toString().toInt()
                            if(i + 1 == number || pCard[i][j] / 10 != 0) {
                                mPlayerCard[i][j].setImageResource(cardFromNumber(pCard[i][j]))
                            }
                            if(pCard[i][j] / 10 != 0) {
                                mPlayerCardDie[i][j].visibility = View.VISIBLE
                            }
                        }
                        if(pCard[i][0] / 10 != 0 && pCard[i][1] / 10 != 0) {
                            mPlayerAllDie[i].visibility = View.VISIBLE
                        }
                    }
                }
                else {
                    cardOpen(openCard)
                }
            }
        }
    }

    private fun actionToCard(actionCode: Int, challengeCode: Int): Int {
        if(challengeCode == 1) {
            return when(actionCode) {
                4->1
                5->4
                6->3
                else->5
            }
        }
        else {
            return when(challengeCode) {
                4->1
                5->2
                6->3
                else->5
            }
        }
    }

    private fun cardOpen(openCard: Int) {
        if(pCard[openCard/10-1][openCard%10-1] == actionToCard(nowActionCode, nowChallengeCode)) {

        }
        else {
            if(nowChallengeCode == 1) {
                mActionText.text = "P${nowChallenger} -> P${nowTurn} 도전 성공"
                Thread.sleep(500)
                mActionText.text = "P${nowTurn}의 카드가 제거됩니다"
            }
            else {
                mActionText.text = "P${nowChallengeCode2} -> P${nowChallenger} 도전 성공"
                Thread.sleep(500)
                mActionText.text = "P${nowChallenger}의 카드가 제거됩니다"
            }
            db.runTransaction{ transaction->
                if(transaction.get(documentCard).get("card_open") != 0) {
                    transaction.update(documentCard, "p${openCard/10-1}card${openCard%10-1}", pCard[openCard/10-1][openCard%10-1]*10)
                    transaction.update(documentCard, "card_open", 0)
                }
            }
        }
    }

    private fun challengeFunc() {
        if(pCard[number-1][0] / 10 == 0 && pCard[number-1][1] / 10 == 0) {
            Toast.makeText(this, "보여줄 카드를 선택해 주세요", Toast.LENGTH_LONG).show()
            val builder = AlertDialog.Builder(this).create()
            val dialogView = layoutInflater.inflate(R.layout.dialog_start_cards_info, null)
            val cardOne = dialogView.findViewById<ImageView>(R.id.card1_start_cards)
            val cardTwo = dialogView.findViewById<ImageView>(R.id.card2_start_cards)
            cardOne.setImageResource(cardFromNumber(pCard[number - 1][0]))
            cardTwo.setImageResource(cardFromNumber(pCard[number - 1][1]))
            val timer = dialogView.findViewById<TextView>(R.id.timer_start_cards)
            val okButton = dialogView.findViewById<Button>(R.id.ok_button_start_cards)
            var countDownTimer: CountDownTimer? = null
            builder.setView(dialogView)
            builder.setCanceledOnTouchOutside(false)

            var selectCard = 1
            cardOne.alpha = 0.3f
            cardOne.setOnClickListener {
                selectCard = 1
                cardOne.alpha = 0.3f
                cardTwo.alpha = 1f
            }
            cardTwo.setOnClickListener {
                selectCard = 2
                cardOne.alpha = 1f
                cardTwo.alpha = 0.3f
            }
            okButton.text = "OPEN"
            okButton.setOnClickListener {
                countDownTimer?.cancel()
                documentCard.update("card_open", number * 10 + selectCard)
                builder.dismiss()
            }
            countDownTimer = object : CountDownTimer(5000, 1000) { // 5초 동안, 1초 간격으로 타이머 설정
                override fun onTick(millisUntilFinished: Long) {
                    // 매 초마다 실행되는 코드
                    val secondsLeft = millisUntilFinished / 1000
                    timer.text = secondsLeft.toString()
                }

                override fun onFinish() {
                    // 타이머가 종료되면 실행되는 코드
                    documentCard.update("card_open", number * 10 + selectCard)
                    builder.dismiss()
                }
            }
            builder.show()
            countDownTimer.start()
        }
        else if(pCard[number-1][0] / 10 == 0) {
            documentCard.update("card_open", number * 10 + 1)
        }
        else {
            documentCard.update("card_open", number * 10 + 2)
        }
    }

    private fun settingBottomSheetButtonListener() {
        buttonIncome.setOnClickListener {
            db.runBatch{ batch->
                batch.update(documentAction, "action", 1)
                batch.update(documentAction, "from", number)
            }
            actionButtonSetting(0)
            bottomSheet.dismiss()
        }
        buttonForeignAid.setOnClickListener {
            db.runBatch{ batch->
                batch.update(documentAction, "from", number)
                batch.update(documentAction, "action", 2)
                for(i in 0 until max_number) {
                    if(i+1 != number) {
                        batch.update(documentAccept,"p${i+1}", 0)
                    }
                }
            }
            settingThreeDot(number)
            actionButtonSetting(0)
            bottomSheet.dismiss()
        }
        buttonCoup.setOnClickListener {
            if(mPlayerCoin[number-1].text.toString().toInt() >= 7) {
                bottomSheet.dismiss()
                mActionText.text = "대상 플레이어를 선택해 주세요"
                settingPlayerClickListener(3)
            }
            else {
                Toast.makeText(this, "코인이 7개 이상일 때 사용할 수 있습니다", Toast.LENGTH_SHORT).show()
            }
        }
        buttonAdmit.setOnClickListener {
            documentAccept.update("p$number", 1)
            actionButtonSetting(0)
            bottomSheet.dismiss()
        }
        buttonChallenge.setOnClickListener {
            db.runTransaction{ transaction->
                if(transaction.get(documentAction)["challenge_type"].toString().toInt() == 0) {
                    if(transaction.get(documentAction)["challenge"].toString().toInt() == 0) {
                        transaction.update(documentAction, "challenge", number)
                        transaction.update(documentAction, "challenge_type", 1)
                    }
                }
                else if(transaction.get(documentAction)["challenge_type"].toString().toInt() == 4) {
                    if(transaction.get(documentAction)["challenge2"].toString().toInt() == 0) {
                        transaction.update(documentAction, "challenge2", number)
                    }
                    transaction.update(documentAction, "challenge", number)
                }
            }
            actionButtonSetting(0)
            bottomSheet.dismiss()
        }
        buttonBlockByDuke.setOnClickListener {
            db.runTransaction{ transaction->
                if(transaction.get(documentAction)["challenge"].toString().toInt() == 0) {
                    transaction.update(documentAction, "challenge", number)
                    transaction.update(documentAction, "challenge_type", 4)
                }
            }
            settingThreeDot(number)
            actionButtonSetting(0)
            bottomSheet.dismiss()
        }
    }

    private fun settingThreeDot(actor: Int) {
        db.runBatch{ batch->
            for( i in 0 until max_number) {
                if(i + 1 != actor) {
                    batch.update(documentAccept, "p${i+1}", 0)
                }
            }
        }
    }

    private fun settingPlayerClickListener(actionNumber: Int){
        for(i in 0 until max_number) {
            mPlayerConstraint[i].isClickable = true
            mPlayerConstraint[i].setOnClickListener {
                if((pCard[i][0] / 10 == 0 || pCard[i][1] / 10 == 0) && i + 1 != number) {
                    actionButtonSetting(0)
                    db.runBatch{ batch->
                        batch.update(documentAction, "from", number)
                        batch.update(documentAction, "action", actionNumber)
                        batch.update(documentAction, "to", i + 1)
                    }
                    for(j in 0 until max_number) {
                        mPlayerConstraint[j].isClickable = false
                    }
                }
                else if(i + 1 == number) {
                    Toast.makeText(this, "본인은 선택할 수 없습니다", Toast.LENGTH_SHORT).show()
                }
                else {
                    Toast.makeText(this, "이미 사망한 플레이어입니다", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun cardElimination() {
        if(pCard[number-1][0] / 10 == 0 && pCard[number-1][1] / 10 == 0) {
            val builder = AlertDialog.Builder(this).create()
            val dialogView = layoutInflater.inflate(R.layout.dialog_start_cards_info, null)
            val cardOne = dialogView.findViewById<ImageView>(R.id.card1_start_cards)
            val cardTwo = dialogView.findViewById<ImageView>(R.id.card2_start_cards)
            cardOne.setImageResource(cardFromNumber(pCard[number - 1][0]))
            cardTwo.setImageResource(cardFromNumber(pCard[number - 1][1]))
            val timer = dialogView.findViewById<TextView>(R.id.timer_start_cards)
            val okButton = dialogView.findViewById<Button>(R.id.ok_button_start_cards)
            var countDownTimer: CountDownTimer? = null
            builder.setView(dialogView)
            builder.setCanceledOnTouchOutside(false)

            var selectCard = 1
            cardOne.alpha = 0.3f
            cardOne.setOnClickListener {
                selectCard = 1
                cardOne.alpha = 0.3f
                cardTwo.alpha = 1f
            }
            cardTwo.setOnClickListener {
                selectCard = 2
                cardOne.alpha = 1f
                cardTwo.alpha = 0.3f
            }
            okButton.text = "Eliminate"
            okButton.setOnClickListener {
                countDownTimer?.cancel()
                documentCard.update("p${number}card$selectCard", pCard[number-1][selectCard-1] * 10)
                builder.dismiss()
            }
            countDownTimer = object : CountDownTimer(5000, 1000) { // 5초 동안, 1초 간격으로 타이머 설정
                override fun onTick(millisUntilFinished: Long) {
                    // 매 초마다 실행되는 코드
                    val secondsLeft = millisUntilFinished / 1000
                    timer.text = secondsLeft.toString()
                }

                override fun onFinish() {
                    // 타이머가 종료되면 실행되는 코드
                    documentCard.update("p${number}card$selectCard", pCard[number-1][selectCard-1] * 10)
                    builder.dismiss()
                }
            }
            builder.show()
            countDownTimer.start()
        }
        else if(pCard[number-1][0] / 10 == 0) {
            documentCard.update("p${number}card1", pCard[number-1][0] * 10)
        }
        else {
            documentCard.update("p${number}card2", pCard[number-1][1] * 10)
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
                        mPlayerCoin[i].text = result["p${i + 1}"].toString()
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
        var Thumbsnumber = 0
        for(i in 0 until max_number) {
            if(snapshot["p${i+1}"] == null) {
                mPlayerThreeDot[i].visibility = View.INVISIBLE
                mPlayerThumbsUp[i].visibility = View.INVISIBLE
            }
            else if(snapshot["p${i+1}"] == true) {
                Thumbsnumber++
                mPlayerThreeDot[i].visibility = View.INVISIBLE
                mPlayerThumbsUp[i].visibility = View.VISIBLE
            }
            else {
                mPlayerThreeDot[i].visibility = View.VISIBLE
                mPlayerThumbsUp[i].visibility = View.INVISIBLE
            }
        }
        if(Thumbsnumber == max_number - 1) {
            if(nowChallengeCode == 0) {
                if(nowActionCode == 2) {
                    foreignAid()
                }
            }
            else if(nowChallengeCode == 4) {
                mActionText.text = "P${nowTurn}의 FOREIGN AID가 DUKE에 의해 막힘"
                turnEnd()
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

    private fun turnEnd() {
        var nextTurn = nowTurn + 1
        if(nextTurn > max_number) nextTurn = 1
        while(nextTurn != nowTurn) {
            if(pCard[nextTurn-1][0] / 10 != 0 && pCard[nextTurn-1][0] / 10 != 0) {
                nextTurn++
                if(nextTurn > max_number) nextTurn = 1
            }
            else {
                break
            }
        }
        if(nextTurn == nowTurn) {
            //게임 끝
        }
        db.runTransaction { transaction->
            if(transaction.get(documentInfo)["turn"] != nextTurn) {
                Thread.sleep(1000)
                transaction.update(documentAction, "action", 0)
                transaction.update(documentAction, "from", 0)
                transaction.update(documentAction, "to", 0)
                transaction.update(documentAction, "challenge", 0)
                transaction.update(documentAction, "challenge_type", 0)
                transaction.update(documentAction, "challenge2", 0)
                transaction.update(documentInfo, "turn", nextTurn)
            }
        }
    }

    private fun actionButtonSetting(number: Int) {
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

    private fun foreignAid() {
        mActionText.text = "P${nowTurn}의 FOREIGN AID : coin + 2"
        if(nowTurn == number) {
            documentCoin.update("p$number", mPlayerCoin[number - 1].text.toString().toInt() + 2)
        }
        turnEnd()
    }

    private fun assassinate() {
        mActionText.text  = "P${nowTurn}의 ASSASSINATE : P${nowTo} life-1"
    }

    private fun steal() {
        if(mPlayerCoin[nowTo-1].text.toString().toInt() < 2) {
            mActionText.text  = "P${nowTurn}의 STEAL : P${nowTo} coin-1"
            if(nowTurn == number) {
                db.runBatch {  batch->
                    batch.update(documentCoin, "p$nowTurn", mPlayerCoin[nowTurn - 1].text.toString().toInt() + 1)
                    batch.update(documentCoin, "p$nowTo", mPlayerCoin[nowTo - 1].text.toString().toInt() - 1)
                }
            }
        }
        else {
            mActionText.text  = "P${nowTurn}의 STEAL : P${nowTo} coin-2"
            if(nowTurn == number) {
                db.runBatch {  batch->
                    batch.update(documentCoin, "p$nowTurn", mPlayerCoin[nowTurn - 1].text.toString().toInt() + 2)
                    batch.update(documentCoin, "p$nowTo", mPlayerCoin[nowTo - 1].text.toString().toInt() - 2)
                }
            }
        }
        turnEnd()
    }

    private fun exchange() {
        if(nowTurn == number) {
            mActionText.text = "버릴 카드 두 장을 선택해 주세요"
            val builder = AlertDialog.Builder(this).create()
            val dialogView = layoutInflater.inflate(R.layout.dialog_exchange, null)
            val cardOne = dialogView.findViewById<ImageView>(R.id.card1_exchange)
            val cardTwo = dialogView.findViewById<ImageView>(R.id.card2_exchange)
            val cardThree = dialogView.findViewById<ImageView>(R.id.card3_exchange)
            val cardFour = dialogView.findViewById<ImageView>(R.id.card4_exchange)
            val timer = dialogView.findViewById<TextView>(R.id.timer_exchange)
            val ok = dialogView.findViewById<Button>(R.id.ok_button_exchange)

            var cardNum = IntArray(4)
            cardNum[0] = pCard[number - 1][0]
            cardNum[1] = pCard[number - 1][1]

            val headOne = pCardLeft[0].toString().toInt()
            val headTwo = pCardLeft[1].toString().toInt()
            cardNum[2] = headOne
            cardNum[3] = headTwo
            pCardLeft = pCardLeft.slice(IntRange(2, pCardLeft.length - 1))
            var countDownTimer: CountDownTimer? = null

            if(pCard[number - 1][0] / 10 != 0) {
                cardOne.setImageResource(cardFromNumber(0))
            }
            else {
                cardOne.setImageResource(cardFromNumber(pCard[number - 1][0]))
            }
            if(pCard[number - 1][1] / 10 != 0) {
                cardTwo.setImageResource(cardFromNumber(0))
            }
            else {
                cardTwo.setImageResource(cardFromNumber(pCard[number - 1][1]))
            }
            cardThree.setImageResource(cardFromNumber(headOne))
            cardFour.setImageResource(cardFromNumber(headTwo))

            var selectOne = 0
            var selectTwo = 0
            cardOne.setOnClickListener {
                if(selectOne == 1 || selectTwo == 1) {
                    if(selectOne == 1) selectOne = 0
                    else selectTwo = 0
                    cardOne.alpha = 1f
                }
                else if(selectOne == 0 || selectTwo == 0){
                    if(selectOne == 0) selectOne = 1
                    else selectTwo = 1
                    cardOne.alpha = 0.3f
                }
                else Toast.makeText(this, "이미 두 장을 선택하였습니다", Toast.LENGTH_SHORT).show()
            }
            cardTwo.setOnClickListener {
                if(selectOne == 2 || selectTwo == 2) {
                    if(selectOne == 2) selectOne = 0
                    else selectTwo = 0
                    cardTwo.alpha = 1f
                }
                else if(selectOne == 0 || selectTwo == 0){
                    if(selectOne == 0) selectOne = 2
                    else selectTwo = 2
                    cardTwo.alpha = 0.3f
                }
                else Toast.makeText(this, "이미 두 장을 선택하였습니다", Toast.LENGTH_SHORT).show()
            }
            cardThree.setOnClickListener {
                if(selectOne == 3 || selectTwo == 3) {
                    if(selectOne == 3) selectOne = 0
                    else selectTwo = 0
                    cardThree.alpha = 1f
                }
                else if(selectOne == 0 || selectTwo == 0){
                    if(selectOne == 0) selectOne = 3
                    else selectTwo = 3
                    cardThree.alpha = 0.3f
                }
                else Toast.makeText(this, "이미 두 장을 선택하였습니다", Toast.LENGTH_SHORT).show()
            }

            cardFour.setOnClickListener {
                if(selectOne == 4 || selectTwo == 4) {
                    if(selectOne == 4) selectOne = 0
                    else selectTwo = 0
                    cardFour.alpha = 1f
                }
                else if(selectOne == 0 || selectTwo == 0){
                    if(selectOne == 0) selectOne = 4
                    else selectTwo = 4
                    cardFour.alpha = 0.3f
                }
                else Toast.makeText(this, "이미 두 장을 선택하였습니다", Toast.LENGTH_SHORT).show()
            }
            ok.setOnClickListener {
                if(selectOne == 0 || selectTwo == 0) Toast.makeText(this, "버릴 카드 두 장을 선택해 주세요", Toast.LENGTH_SHORT).show()
                else {
                    countDownTimer?.cancel()
                    var indexOne = 0
                    var indexTwo = 0
                    val endRandomPoint = pCardLeft.length-1
                    val range = 0..endRandomPoint
                    var randomNum=Random.nextInt(range)
                    indexOne = randomNum
                    while(randomNum == indexOne) {
                        randomNum = Random.nextInt(range)
                    }
                    indexTwo = randomNum

                    val sliceOne = pCardLeft.slice(0 until indexOne)
                    val sliceTwo = pCardLeft.slice(indexOne until indexTwo)
                    val sliceThree = pCardLeft.slice(indexTwo until pCardLeft.length)
                    pCardLeft = sliceOne+cardNum[selectOne-1].toString()+sliceTwo+cardNum[selectTwo-1].toString()+sliceThree
                    var myCard = IntArray(4)
                    myCard[0] = 0
                    myCard[1] = 1
                    myCard[2] = 2
                    myCard[3] = 3
                    myCard[selectOne - 1] = -1
                    myCard[selectTwo - 1] = -1
                    db.runBatch { batch->
                        var cdIndex = 1
                        for(i in 0 until 4) {
                            if(myCard[i] != -1) {
                                batch.update(documentCard, "p${number}card${cdIndex}", cardNum[myCard[i]])
                                cdIndex++
                            }
                        }
                        batch.update(documentCard, "card_left", pCardLeft)
                    }
                    builder.dismiss()
                }
            }

            builder.setView(dialogView)
            builder.setCanceledOnTouchOutside(false)

            countDownTimer = object : CountDownTimer(5000, 1000) { // 5초 동안, 1초 간격으로 타이머 설정
                override fun onTick(millisUntilFinished: Long) {
                    // 매 초마다 실행되는 코드
                    val secondsLeft = millisUntilFinished / 1000
                    timer.text = secondsLeft.toString()
                }

                override fun onFinish() {
                    // 타이머가 종료되면 실행되는 코드
                    pCardLeft = headOne.toString()+headTwo.toString()+pCardLeft
                    builder.dismiss()
                }
            }
            builder.show()
            countDownTimer.start()
        }
        else mActionText.text = "P${nowTurn}의 EXCHANGE"
    }

    companion object{
        val TAG = "GameRoomActivity"
    }

}