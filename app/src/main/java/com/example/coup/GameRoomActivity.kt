package com.example.coup

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
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
import com.google.protobuf.Parser
import de.hdodenhof.circleimageview.CircleImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.tasks.await
import java.util.zip.Inflater
import kotlin.math.max
import kotlin.random.Random
import kotlin.random.nextInt

class GameRoomActivity : AppCompatActivity() {

    private val timerDurationShort = 10000 as Long
    private val timerDurationLong = 20000 as Long

    private var openPlayer = 0
    private var openCardNum = 0

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
    private lateinit var documentResult: DocumentReference

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
    private lateinit var bottomSheetOnlyCoup: ConstraintLayout

    private lateinit var buttonIncome: Button
    private lateinit var buttonForeignAid: Button
    private lateinit var buttonCoup: Button
    private lateinit var buttonTax: Button
    private lateinit var buttonAssassinate: Button
    private lateinit var buttonSteal: Button
    private lateinit var buttonExchange: Button
    private lateinit var buttonChallenge: Button
    private lateinit var buttonAdmit: Button
    private lateinit var buttonForeignAidAdmit: Button
    private lateinit var buttonBlockByCaptain: Button
    private lateinit var buttonBlockByAmbassador: Button
    private lateinit var buttonBlockByDuke: Button
    private lateinit var buttonBlockByContessa: Button
    private lateinit var buttonOnlyCoup: Button

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
                    actionButtonSetting(6)
                }
                else {
                    mActionText.text = "나의 턴. 행동을 선택해주세요"
                    actionButtonSetting(1)
                }
            }
            else if(nowTurn == 9) {
                //게임 종료
                val intent = Intent(this, GameResultActivity::class.java)
                intent.putExtra("gameId", gameId)
                intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                Toast.makeText(this, "GAME END", Toast.LENGTH_SHORT).show()
                snapshotListenerCard.remove()
                snapshotListenerInfo.remove()
                snapshotListenerAccept.remove()
                snapshotListenerCoin.remove()
                snapshotListenerAction.remove()
                startActivity(intent)
                if(number == 1) {
                    Handler(Looper.getMainLooper()).postDelayed({
                        documentCard.delete()
                        documentCoin.delete()
                        documentAccept.delete()
                        documentInfo.delete()
                        documentAction.delete()
                        finish()
                    }, 1000)
                }
                else {
                    finish()
                }
            }
            else {
                actionButtonSetting(0)
                if(nowTurn == 0) mActionText.text = "게임 준비 중"
                else mActionText.text = "P${nowTurn}턴 행동 대기 중"
            }
        }
        snapshotListenerAction = documentAction.addSnapshotListener { snapshot, e ->
            if(snapshot != null) {
                Thread.sleep(3000)
                nowActionCode = snapshot.get("action").toString().toInt()
                nowFrom = snapshot.get("from").toString().toInt()
                nowTo = snapshot.get("to").toString().toInt()
                nowChallengeCode = snapshot.get("challenge_type").toString().toInt()
                nowChallenger = snapshot.get("challenge").toString().toInt()
                nowChallengeCode2 = snapshot.get("challenge2").toString().toInt()
                if(nowChallengeCode2 == 0) {
                    if(nowChallengeCode == 0) {
                        if(nowActionCode == 1) {
                            actionPerform(1)
                            turnEnd()
                        }
                        if(nowActionCode == 2 || (nowActionCode in 4..7)) {
                            settingThreeDot(nowTurn)
                            if(nowActionCode == 2) {
                                mActionText.text = "FOREIGN AID시전, 다른 플레이어 응답 대기 중"
                                if(nowTurn != number) actionButtonSetting(3)
                            }
                            if(nowActionCode == 4) {
                                mActionText.text = "TAX시전, 다른 플레이어 응답 대기 중"
                                if(nowTurn != number) actionButtonSetting(2)
                            }
                            if(nowActionCode == 5) {
                                mActionText.text = "P${nowTo}에게 ASSASSINATION시전, 다른 플레이어 응답 대기 중"
                                if(nowTurn != number) actionButtonSetting(5)
                            }
                            if(nowActionCode == 6) {
                                mActionText.text = "P${nowTo}에게 STEAL시전, 다른 플레이어 응답 대기 중"
                                if(nowTurn != number) actionButtonSetting(4)
                            }
                            if(nowActionCode == 7) {
                                mActionText.text = "EXCHANGE시전, 다른 플레이어 응답 대기 중"
                                if(nowTurn != number) actionButtonSetting(2)
                            }
                        }
                        if(nowActionCode == 3) {
                            actionPerform(3)
                        }
                    }
                    else {
                        if(nowChallengeCode == 1) {
                            if(number == nowTurn) {
                                mActionText.text = "P${nowChallenger}에게 도전 신청을 받았습니다. 공개할 카드를 선택해주세요"
                                selectCard()
                            }
                            else {
                                mActionText.text = "P${nowChallenger}의 도전 신청"
                            }
                        }
                        else if(nowChallengeCode in 4..7) {
                            if(nowChallengeCode == 4) mActionText.text = "P${nowChallenger}가 DUKE로 막기 시전, 다른 플레이어 응답 대기 중"
                            if(nowChallengeCode == 5) mActionText.text = "P${nowChallenger}가 CONTESSA로 막기 시전, 다른 플레이어 응답 대기 중"
                            if(nowChallengeCode == 6) mActionText.text = "P${nowChallenger}가 CAPTAIN로 막기 시전, 다른 플레이어 응답 대기 중"
                            if(nowChallengeCode == 7) mActionText.text = "P${nowChallenger}가 AMBASSADOR로 막기 시전, 다른 플레이어 응답 대기 중"
                            settingThreeDot(nowChallenger)
                            if(number != nowChallenger) {
                                actionButtonSetting(2)
                            }
                        }
                    }
                }
                else {
                    if(number == nowChallenger) {
                    mActionText.text = "P${nowChallengeCode2}에게 도전 신청을 받았습니다. 공개할 카드를 선택해주세요"
                    selectCard()
                }
                else {
                    mActionText.text = "P${nowChallengeCode2}의 도전 신청"
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
                    checkGameEnd()
                }
                else {
                    if(nowChallengeCode2 == 0) {
                        if(nowChallengeCode == 1) {
                            cardOpen(openCard, 1)
                        }
                    }
                    else {
                        cardOpen(openCard, 2)
                    }
                }
            }
        }
    }

    private fun selectCard() {
        val builder = AlertDialog.Builder(this).create()
        val dialogView = layoutInflater.inflate(R.layout.dialog_start_cards_info, null)
        val cardOne = dialogView.findViewById<ImageView>(R.id.card1_start_cards)
        val cardTwo = dialogView.findViewById<ImageView>(R.id.card2_start_cards)
        val text = dialogView.findViewById<TextView>(R.id.text_start_cards)
        cardOne.setImageResource(cardFromNumber(pCard[number - 1][0]))
        cardTwo.setImageResource(cardFromNumber(pCard[number - 1][1]))
        if(pCard[number - 1][0] / 10 != 0) cardOne.setBackgroundResource(R.color.red)
        if(pCard[number - 1][1] / 10 != 0) cardTwo.setBackgroundResource(R.color.red)
        val timer = dialogView.findViewById<TextView>(R.id.timer_start_cards)
        val okButton = dialogView.findViewById<Button>(R.id.ok_button_start_cards)
        var countDownTimer: CountDownTimer? = null
        builder.setView(dialogView)
        builder.setCanceledOnTouchOutside(false)

        var selectCard = 0
        cardOne.setOnClickListener {
            if(pCard[number-1][0] / 10 != 0) Toast.makeText(this, "제거된 카드는 선택할 수 없습니다.", Toast.LENGTH_SHORT).show()
            else {
                selectCard = 1
                cardOne.alpha = 0.3f
                cardTwo.alpha = 1f
            }
        }
        cardTwo.setOnClickListener {
            if(pCard[number-1][1] / 10 != 0) Toast.makeText(this, "제거된 카드는 선택할 수 없습니다", Toast.LENGTH_SHORT).show()
            else {
                selectCard = 2
                cardOne.alpha = 1f
                cardTwo.alpha = 0.3f
            }
        }
        text.text = "Select card to open"
        okButton.setOnClickListener {
            if(selectCard == 0) Toast.makeText(this, "카드를 선택해주세요", Toast.LENGTH_SHORT).show()
            else {
                countDownTimer?.cancel()
                documentCard.update("card_open", number*10+selectCard)
                if(builder.isShowing) builder.dismiss()
            }
        }
        countDownTimer = object : CountDownTimer(timerDurationLong, 1000) { //1초 간격으로 타이머 설정
            override fun onTick(millisUntilFinished: Long) {
                // 매 초마다 실행되는 코드
                val secondsLeft = millisUntilFinished / 1000
                timer.text = secondsLeft.toString()
            }

            override fun onFinish() {
                // 타이머가 종료되면 실행되는 코드
                if(selectCard == 0) {
                    if(pCard[number][0] / 10 == 0) documentCard.update("card_open", number*10+1)
                    else documentCard.update("card_open", number*10+2)
                }
                else {
                    documentCard.update("card_open", number*10+selectCard)
                }
                builder.dismiss()
            }
        }
        builder.show()
        countDownTimer.start()
    }

    private fun actionToCard(challengeNum: Int): Int {
        if(challengeNum == 1) {
            return when(nowActionCode) {
                4->1
                5->4
                6->3
                else->5
            }
        }
        else {
            return when(nowChallengeCode) {
                4->1
                5->2
                6->3
                else->5
            }
        }
    }
    private fun cardOpen(openCard: Int, challengeNum: Int) {
        openPlayer = openCard/10
        openCardNum = openCard%10
        Log.d(TAG, "openPlayer : $openPlayer, openCardNum : $openCardNum")
        if(challengeNum == 1) {
            if(pCard[openPlayer - 1][openCardNum - 1] == actionToCard(challengeNum)) {
                Log.d(TAG, "true1들어옴")
                mActionText.text = "도전 실패로 P${nowChallenger}의 카드가 한장 제거됩니다"
                mPlayerCard[openPlayer - 1][openCardNum - 1].setImageResource(cardFromNumber(pCard[openPlayer - 1][openCardNum - 1]))
                mPlayerCard[openPlayer - 1][openCardNum - 1].setImageResource(cardFromNumber(0))
                if(nowChallenger == number) {
                    Log.d(TAG, "true1, elimination들어옴")
                    Log.d(TAG, "number : $number, nowChallenger : $nowChallenger")
                    if(nowTo == number && nowActionCode == 5) {
                        db.runBatch { batch->
                            if(pCard[number - 1][0] / 10 == 0) {
                                batch.update(documentCard, "p${number}card1", pCard[number - 1][0] * 10)
                                pCard[number - 1][0] *= 10
                            }
                            if(pCard[number - 1][1] / 10 == 0) {
                                batch.update(documentCard, "p${number}card2", pCard[number - 1][1] * 10)
                                pCard[number - 1][1] *= 10
                            }
                            batch.update(documentCard, "card_open", 0)
                            batch.update(documentCoin, "p$nowTurn", mPlayerCoin[nowTurn - 1].text.toString().toInt() - 3)
                        }
                        turnEnd()
                    }
                    else {
                        cardElimination()
                    }
                }
            }
            else {
                Log.d(TAG, "false1들어옴")
                mActionText.text = "도전 성공으로 P${nowTurn}의 카드가 제거됩니다"
                db.runTransaction{ transaction->
                    if(transaction.get(documentCard).get("card_open").toString().toInt() != 0) {
                        transaction.update(documentCard, "card_open", 0)
                        transaction.update(documentCard, "p${openPlayer}card${openCardNum}", pCard[openPlayer - 1][openCardNum - 1]*10)
                    }

                }
                turnEnd()
            }
        }
        else {
            if(pCard[openPlayer - 1][openCardNum - 1] == actionToCard(challengeNum)) {
                Log.d(TAG, "true2들어옴")
                mActionText.text = "도전 실패로 P${nowChallengeCode2}의 카드가 한장 제거됩니다"
                mPlayerCard[openPlayer - 1][openCardNum - 1].setImageResource(cardFromNumber(pCard[openPlayer - 1][openCardNum - 1]))
                mPlayerCard[openPlayer - 1][openCardNum - 1].setImageResource(cardFromNumber(0))
                if(number == nowChallengeCode2) {
                    Log.d(TAG, "true2, elimination들어옴")
                    Log.d(TAG, "number : $number, nowChallenger2 : $nowChallengeCode2")
                    cardElimination()
                }
            }
            else {
                Log.d(TAG, "false2들어옴")
                mActionText.text = "도전 성공으로 P${nowChallenger}의 카드가 한장 제거됩니다"
                db.runBatch{ batch->
                    batch.update(documentCard, "card_open", 0)
                    batch.update(documentCard, "p${openPlayer}card${openCardNum}", pCard[openPlayer - 1][openCardNum - 1]*10)
                }
                pCard[openPlayer - 1][openCardNum - 1] *= 10
                actionPerform(nowActionCode)
            }
        }
    }


    private fun cardChange(player: Int, num: Int) {
        val firstCard = pCardLeft[0].toString().toInt()
        Log.d(TAG, "chardChange에서 firstCard : $firstCard")
        pCardLeft = pCardLeft.slice(1 until pCardLeft.length)
        val insertIndex = Random.nextInt(pCardLeft.indices)
        pCardLeft = pCardLeft.slice(0 until insertIndex) + pCard[player-1][num-1].toString() + pCardLeft.slice(insertIndex until pCardLeft.length)
        db.runBatch { batch->
            batch.update(documentCard, "card_left", pCardLeft)
            batch.update(documentCard, "p${player}card${num}", firstCard)
            Log.d(TAG, "player = $player, num : $num, firstCard : $firstCard")
            batch.update(documentCard, "card_open", 0)
        }
        pCard[player - 1][num - 1] = firstCard
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
            }
            actionButtonSetting(0)
            bottomSheet.dismiss()
        }
        buttonCoup.setOnClickListener {
            if(mPlayerCoin[number-1].text.toString().toInt() >= 7) {
                bottomSheet.dismiss()
                settingPlayerClickListener(3)
            }
            else {
                Toast.makeText(this, "코인이 7개 이상일 때 사용할 수 있습니다", Toast.LENGTH_SHORT).show()
            }
        }
        buttonAdmit.setOnClickListener {
            documentAccept.update("p$number", true)
            actionButtonSetting(0)
            bottomSheet.dismiss()
        }
        buttonForeignAidAdmit.setOnClickListener {
            documentAccept.update("p$number", true)
            actionButtonSetting(0)
            bottomSheet.dismiss()
        }
        buttonChallenge.setOnClickListener {
            db.runTransaction{ transaction->
                val type = transaction.get(documentAction)["challenge_type"].toString().toInt()
                val challenger = transaction.get(documentAction)["challenge"].toString().toInt()
                val challenger2 = transaction.get(documentAction)["challenge2"].toString().toInt()
                if(type == 0) {
                    if(challenger == 0) {
                        transaction.update(documentAction, "challenge", number)
                        transaction.update(documentAction, "challenge_type", 1)
                    }
                }
                else if(type == 4 || type == 5 || type == 6 || type == 7) {
                    if(challenger2 == 0) {
                        transaction.update(documentAction, "challenge2", number)
                    }
                }
            }
            actionButtonSetting(0)
            bottomSheet.dismiss()
        }
        buttonTax.setOnClickListener {
            db.runBatch{ batch->
                batch.update(documentAction, "action", 4)
                batch.update(documentAction, "from", number)
            }
            actionButtonSetting(0)
            bottomSheet.dismiss()
        }
        buttonAssassinate.setOnClickListener {
            if(mPlayerCoin[number-1].text.toString().toInt() >= 3) {
                bottomSheet.dismiss()
                settingPlayerClickListener(5)
            }
            else {
                Toast.makeText(this, "코인이 3개 이상일 때 사용할 수 있습니다", Toast.LENGTH_SHORT).show()
            }
        }
        buttonSteal.setOnClickListener {
            bottomSheet.dismiss()
            settingPlayerClickListener(6)
        }
        buttonExchange.setOnClickListener {
            db.runBatch{ batch->
                batch.update(documentAction, "from", number)
                batch.update(documentAction, "action", 7)
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
            actionButtonSetting(0)
            bottomSheet.dismiss()
        }
        buttonBlockByContessa.setOnClickListener {
            db.runTransaction{ transaction->
                if(transaction.get(documentAction)["challenge"].toString().toInt() == 0) {
                    transaction.update(documentAction, "challenge", number)
                    transaction.update(documentAction, "challenge_type", 5)
                }
            }
            actionButtonSetting(0)
            bottomSheet.dismiss()
        }
        buttonBlockByCaptain.setOnClickListener {
            db.runTransaction{ transaction->
                if(transaction.get(documentAction)["challenge"].toString().toInt() == 0) {
                    transaction.update(documentAction, "challenge", number)
                    transaction.update(documentAction, "challenge_type", 6)
                }
            }
            actionButtonSetting(0)
            bottomSheet.dismiss()
        }
        buttonBlockByAmbassador.setOnClickListener {
            db.runTransaction{ transaction->
                if(transaction.get(documentAction)["challenge"].toString().toInt() == 0) {
                    transaction.update(documentAction, "challenge", number)
                    transaction.update(documentAction, "challenge_type", 7)
                }
            }
            actionButtonSetting(0)
            bottomSheet.dismiss()
        }
        buttonOnlyCoup.setOnClickListener {
            bottomSheet.dismiss()
            settingPlayerClickListener(3)
        }
    }

    private fun settingThreeDot(except: Int) {
        db.runBatch{ batch->
            for( i in 0 until max_number) {
                if(i + 1 != except) {
                    batch.update(documentAccept, "p${i+1}", 0)
                }
                else {
                    batch.update(documentAccept, "p${i+1}", null)
                }
            }
        }
    }

    private fun settingPlayerClickListener(actionNumber: Int){
        if(actionNumber == 3) { mActionText.text = "COUP 대상 플레이어를 선택해 주세요" }
        if(actionNumber == 5) { mActionText.text = "암살 대상 플레이어를 선택해 주세요" }
        if(actionNumber == 6) { mActionText.text = "강탈 대상 플레이어를 선택해 주세요" }
        for(i in 0 until max_number) {
            mPlayerConstraint[i].isClickable = true
            mPlayerConstraint[i].setOnClickListener {
                if((pCard[i][0] / 10 == 0 || pCard[i][1] / 10 == 0) && i + 1 != number) {
                    if(mPlayerCoin[i].text.toString().toInt() <= 0 && actionNumber == 6) {
                        Toast.makeText(this, "코인이 없는 플레이어를 대상으로 선택할 수 없습니다", Toast.LENGTH_SHORT).show()
                    }
                    else {
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
            val text = dialogView.findViewById<TextView>(R.id.text_start_cards)
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
            text.text = "Select card to eliminate"
            okButton.text = "Eliminate"
            okButton.setOnClickListener {
                countDownTimer?.cancel()
                db.runBatch { batch->
                    batch.update(documentCard, "p${number}card$selectCard", pCard[number-1][selectCard-1] * 10)
                    batch.update(documentCard, "card_open", 0)
                }
                pCard[number-1][selectCard-1] *= 10
                builder.dismiss()
                nextElimination()
            }
            countDownTimer = object : CountDownTimer(timerDurationLong, 1000) { // 5초 동안, 1초 간격으로 타이머 설정
                override fun onTick(millisUntilFinished: Long) {
                    // 매 초마다 실행되는 코드
                    val secondsLeft = millisUntilFinished / 1000
                    timer.text = secondsLeft.toString()
                }

                override fun onFinish() {
                    // 타이머가 종료되면 실행되는 코드
                    db.runBatch { batch->
                        batch.update(documentCard, "p${number}card$selectCard", pCard[number-1][selectCard-1] * 10)
                        batch.update(documentCard, "card_open", 0)
                    }
                    pCard[number-1][selectCard-1] *= 10
                    builder.dismiss()
                    nextElimination()
                }
            }
            builder.show()
            countDownTimer.start()
        }
        else if(pCard[number-1][0] / 10 == 0) {
            db.runBatch { batch->
                batch.update(documentCard, "p${number}card1", pCard[number-1][0] * 10)
                batch.update(documentCard, "card_open", 0)
            }
            pCard[number-1][0] *= 10
            nextElimination()
        }
        else if(pCard[number-1][1] / 10 == 0){
            db.runBatch { batch->
                batch.update(documentCard, "p${number}card2", pCard[number-1][1] * 10)
                batch.update(documentCard, "card_open", 0)
            }
            pCard[number-1][1] *= 10
            nextElimination()
        }
        else {
           nextElimination()
        }
    }

    private fun nextElimination() {
        if((nowChallengeCode2 == 0 && nowChallengeCode == 1) || nowChallengeCode2 != 0) {
            cardChange(openPlayer, openCardNum)
            if(nowChallengeCode2 == 0 && nowChallengeCode == 1){
                db.runBatch{ batch->
                    batch.update(documentAction, "challenge" ,0)
                    batch.update(documentAction, "challenge2", 0)
                    batch.update(documentAction, "action", nowActionCode)
                }
            }
            else {
                turnEnd()
            }
        }
        else turnEnd()
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
        bottomSheetOnlyCoup = bottomSheetView.findViewById(R.id.game_action_btn_layout7)
        buttonIncome = bottomSheetView.findViewById(R.id.action_income_btn)
        buttonForeignAid = bottomSheetView.findViewById(R.id.action_foreign_aid_btn)
        buttonCoup = bottomSheetView.findViewById(R.id.action_coup_btn)
        buttonTax = bottomSheetView.findViewById(R.id.action_tax_btn)
        buttonAssassinate = bottomSheetView.findViewById(R.id.action_assassinate_btn)
        buttonSteal = bottomSheetView.findViewById(R.id.action_steal_btn)
        buttonExchange = bottomSheetView.findViewById(R.id.action_exchange_btn)
        buttonChallenge = bottomSheetView.findViewById(R.id.action_challenge_btn)
        buttonAdmit = bottomSheetView.findViewById(R.id.action_admit_btn)
        buttonForeignAidAdmit = bottomSheetView.findViewById(R.id.action_foreignaidadmit_btn)
        buttonBlockByCaptain = bottomSheetView.findViewById(R.id.action_blockbycaptain_btn)
        buttonBlockByAmbassador = bottomSheetView.findViewById(R.id.action_blockbyambassador_btn)
        buttonBlockByDuke = bottomSheetView.findViewById(R.id.action_blockbyduke_btn)
        buttonBlockByContessa = bottomSheetView.findViewById(R.id.action_blockbycontessa_btn)
        buttonOnlyCoup = bottomSheetView.findViewById(R.id.action_onlycoup_btn)

        auth = FirebaseManager.getFirebaseAuth()
        storage = FirebaseStorage.getInstance()
        db = FirestoreManager.getFirestore()
        documentInfo = db.collection("game_playing").document(gameId+"_INFO")
        documentCard = db.collection("game_playing").document(gameId+"_CARD")
        documentCoin = db.collection("game_playing").document(gameId+"_COIN")
        documentAccept = db.collection("game_playing").document(gameId+"_ACCEPT")
        documentAction = db.collection("game_playing").document(gameId+"_ACTION")
        documentResult = db.collection("game_result").document(gameId)
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
                        pCard[i][0] = result["p${i+1}card1"].toString().toInt()
                        pCard[i][1] = result["p${i+1}card2"].toString().toInt()
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
        var thumbsNumber = 0
        for(i in 0 until max_number) {
            if(snapshot["p${i+1}"] == null) {
                mPlayerThreeDot[i].visibility = View.INVISIBLE
                mPlayerThumbsUp[i].visibility = View.INVISIBLE
            }
            else if(snapshot["p${i+1}"] == true) {
                thumbsNumber++
                mPlayerThreeDot[i].visibility = View.INVISIBLE
                mPlayerThumbsUp[i].visibility = View.VISIBLE
            }
            else {
                mPlayerThreeDot[i].visibility = View.VISIBLE
                mPlayerThumbsUp[i].visibility = View.INVISIBLE
            }
        }
        if(thumbsNumber == max_number - 1) {
            if(nowChallengeCode == 0) {
                actionPerform(nowActionCode)
            }
            else {
                if(nowChallengeCode == 4) mActionText.text = "P${nowTurn}의 FOREIGN AID가 DUKE에 의해 막힘"
                if(nowChallengeCode == 5) {
                    mActionText.text = "P${nowTurn}의 ASSASSINATION이 CONTESSA에 의해 막힘"
                    documentCoin.update("p$nowTurn", mPlayerCoin[nowTurn - 1].text.toString().toInt() - 3)
                }
                if(nowChallengeCode == 6) mActionText.text = "P${nowTurn}의 STEAL이 CAPTAIN에 의해 막힘"
                if(nowChallengeCode == 7) mActionText.text = "P${nowTurn}의 STEAL이 AMBASSADOR에 의해 막힘"
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
        Log.d(TAG, "Cardleft(${pCardLeft})")
        Log.d(TAG, "p11(${pCard[0][0]}), p12(${pCard[0][1]}), p21(${pCard[1][0]}), p22(${pCard[1][1]})")
        var nextTurn = nowTurn + 1
        if(nextTurn > max_number) nextTurn = 1
        while(nextTurn != nowTurn) {
            if(pCard[nextTurn-1][0] / 10 != 0 && pCard[nextTurn-1][1] / 10 != 0) {
                nextTurn++
                if(nextTurn > max_number) nextTurn = 1
            }
            else {
                break
            }
        }
        db.runTransaction { transaction->
            if(transaction.get(documentInfo)["turn"] != nextTurn) {
                transaction.update(documentAction, "action", 0)
                transaction.update(documentAction, "from", 0)
                transaction.update(documentAction, "to", 0)
                transaction.update(documentAction, "challenge", 0)
                transaction.update(documentAction, "challenge_type", 0)
                transaction.update(documentAction, "challenge2", 0)
                transaction.update(documentInfo, "turn", nextTurn)
                for(i in 0 until max_number) {
                    transaction.update(documentAccept, "p${i+1}", null)
                }
            }
        }
    }

    private fun checkGameEnd() {
        var dieNum = 0
        for(i in 0 until max_number) {
            if(pCard[i][0] / 10 != 0 && pCard[i][1] / 10 != 0) {
                dieNum++
                db.runTransaction { transaction->
                    val players = transaction.get(documentResult)["players"] as Long
                    val rank = transaction.get(documentResult)["p${i+1}rank"]
                    if(rank.toString().toInt() == 0) {
                        transaction.update(documentResult, "p${i+1}rank", max_number - players.toInt())
                        transaction.update(documentResult, "players", players + 1)
                    }
                }
            }
        }
        if(dieNum + 1 == max_number) {
            //게임 끝
            if(pCard[number-1][0] / 10 == 0 || pCard[number-1][1] / 10 == 0) {
                db.runTransaction() { transaction->
                    if(transaction.get(documentResult).get("players") != max_number) {
                        transaction.update(documentResult, "p${number}rank", 1)
                        transaction.update(documentResult, "players", max_number)
                        transaction.update(documentResult, "timestamp", com.google.firebase.Timestamp.now())
                    }
                }
                    .addOnSuccessListener {
                        val intent = Intent(this, GameResultActivity::class.java)
                        intent.putExtra("gameId", gameId)
                        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        Toast.makeText(this, "GameEND", Toast.LENGTH_SHORT).show()
                        snapshotListenerCard.remove()
                        snapshotListenerInfo.remove()
                        snapshotListenerAccept.remove()
                        snapshotListenerCoin.remove()
                        snapshotListenerAction.remove()
                        startActivity(intent)
                        documentInfo.update("turn", 9)
                        Handler(Looper.getMainLooper()).postDelayed({
                            documentCard.delete()
                            documentCoin.delete()
                            documentAccept.delete()
                            documentInfo.delete()
                            documentAction.delete()
                            finish()
                        }, 1000)
                    }
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
                bottomSheetOnlyCoup.visibility = View.GONE
            }
            1 -> {  //기본행동
                bottomSheetDefault.visibility = View.VISIBLE
                bottomSheetAbility.visibility = View.VISIBLE
                bottomSheetChallenge.visibility = View.GONE
                bottomSheetBlockByDuke.visibility = View.GONE
                bottomSheetBlockByCaptainOrAmbassador.visibility = View.GONE
                bottomSheetBlockByContessa.visibility = View.GONE
                bottomSheetOnlyCoup.visibility = View.GONE
                bottomSheet.show()
            }
            2 -> {  //도전
                bottomSheetDefault.visibility = View.GONE
                bottomSheetAbility.visibility = View.GONE
                bottomSheetChallenge.visibility = View.VISIBLE
                bottomSheetBlockByDuke.visibility = View.GONE
                bottomSheetBlockByCaptainOrAmbassador.visibility = View.GONE
                bottomSheetBlockByContessa.visibility = View.GONE
                bottomSheetOnlyCoup.visibility = View.GONE
                bottomSheet.show()
            }
            3 -> {  //공작으로 막기, 허용
                bottomSheetDefault.visibility = View.GONE
                bottomSheetAbility.visibility = View.GONE
                bottomSheetChallenge.visibility = View.GONE
                bottomSheetBlockByDuke.visibility = View.VISIBLE
                bottomSheetBlockByCaptainOrAmbassador.visibility = View.GONE
                bottomSheetBlockByContessa.visibility = View.GONE
                bottomSheetOnlyCoup.visibility = View.GONE
                bottomSheet.show()
            }
            4 -> {  //외교관으로 막기, 사령관으로 막기, 도전
                bottomSheetDefault.visibility = View.GONE
                bottomSheetAbility.visibility = View.GONE
                bottomSheetChallenge.visibility = View.VISIBLE
                bottomSheetBlockByDuke.visibility = View.GONE
                bottomSheetBlockByCaptainOrAmbassador.visibility = View.VISIBLE
                bottomSheetBlockByContessa.visibility = View.GONE
                bottomSheetOnlyCoup.visibility = View.GONE
                bottomSheet.show()
            }
            5 -> {  //귀부인으로 막기, 도전
                bottomSheetDefault.visibility = View.GONE
                bottomSheetAbility.visibility = View.GONE
                bottomSheetChallenge.visibility = View.VISIBLE
                bottomSheetBlockByDuke.visibility = View.GONE
                bottomSheetBlockByCaptainOrAmbassador.visibility = View.GONE
                bottomSheetBlockByContessa.visibility = View.VISIBLE
                bottomSheetOnlyCoup.visibility = View.GONE
                bottomSheet.show()
            }
            6 -> {  //코인 10개 넘을때 coup만 뜨게 하기
                bottomSheetDefault.visibility = View.GONE
                bottomSheetAbility.visibility = View.GONE
                bottomSheetChallenge.visibility = View.GONE
                bottomSheetBlockByDuke.visibility = View.GONE
                bottomSheetBlockByCaptainOrAmbassador.visibility = View.GONE
                bottomSheetBlockByContessa.visibility = View.GONE
                bottomSheetOnlyCoup.visibility = View.VISIBLE
                bottomSheet.show()
            }
        }
    }

    private fun actionPerform(actionType: Int) {
        when (actionType) {
            1->income()
            2->foreignAid()
            3->coup()
            4->tax()
            5->assassinate()
            6->steal()
            7->exchange()
        }
    }

    private fun income() {
        mActionText.text = "INCOME : coin + 1"
        if(nowTurn == number) {
            documentCoin.update("p$number", mPlayerCoin[number - 1].text.toString().toInt() + 1)
            turnEnd()
        }
    }

    private fun foreignAid() {
        mActionText.text = "FOREIGN AID : coin + 2"
        if(nowTurn == number) {
            documentCoin.update("p$number", mPlayerCoin[number - 1].text.toString().toInt() + 2)
            turnEnd()
        }
    }

    private fun coup() {
        if(nowTo.toString().toInt() == number) {
            mActionText.text = "Coup을 당했습니다"
            documentCoin.update("p$nowTurn", mPlayerCoin[nowTurn - 1].text.toString().toInt() - 7)
            cardElimination()
        }
        else {
            mActionText.text = "P${nowFrom}의 COUP to P${nowTo}"
        }
    }

    private fun tax() {
        mActionText.text  = "P${nowTurn}의 TAX : coin+3"
        if(nowTurn == number) {
            documentCoin.update("p$nowTurn", mPlayerCoin[nowTurn-1].text.toString().toInt() + 3)
            turnEnd()
        }
    }

    private fun assassinate() {
        if(nowTo == number) {
            mActionText.text  = "ASSASSINATE을 당했습니다"
            documentCoin.update("p$nowTurn", mPlayerCoin[nowTurn-1].text.toString().toInt() - 3)
            cardElimination()
        }
        else {
            mActionText.text  = "P${nowTurn}의 ASSASSINATE : P${nowTo} life-1"
        }
    }

    private fun steal() {
        if(mPlayerCoin[nowTo-1].text.toString().toInt() < 2) {
            mActionText.text  = "P${nowTurn}의 STEAL : P${nowTo} coin-1"
            if(nowTurn == number) {
                db.runBatch {  batch->
                    batch.update(documentCoin, "p$nowTurn", mPlayerCoin[nowTurn - 1].text.toString().toInt() + 1)
                    batch.update(documentCoin, "p$nowTo", mPlayerCoin[nowTo - 1].text.toString().toInt() - 1)
                }
                turnEnd()
            }
        }
        else {
            mActionText.text  = "P${nowTurn}의 STEAL : P${nowTo} coin-2"
            if(nowTurn == number) {
                db.runBatch {  batch->
                    batch.update(documentCoin, "p$nowTurn", mPlayerCoin[nowTurn - 1].text.toString().toInt() + 2)
                    batch.update(documentCoin, "p$nowTo", mPlayerCoin[nowTo - 1].text.toString().toInt() - 2)
                }
                turnEnd()
            }
        }
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

            val cardNum = IntArray(4)
            cardNum[0] = pCard[number - 1][0]
            cardNum[1] = pCard[number - 1][1]

            val headOne = pCardLeft[0].toString().toInt()
            val headTwo = pCardLeft[1].toString().toInt()
            cardNum[2] = headOne
            cardNum[3] = headTwo
            pCardLeft = pCardLeft.slice(IntRange(2, pCardLeft.length - 1))
            Log.d("pCardLeft","leftcard : $pCardLeft")
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
                if(pCard[number - 1][0] / 10 != 0)
                    Toast.makeText(this,"이미 제거된 카드입니다.",Toast.LENGTH_SHORT).show()
                else {
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
            }
            cardTwo.setOnClickListener {
                if(pCard[number - 1][1] / 10 != 0)
                    Toast.makeText(this,"이미 제거된 카드입니다.",Toast.LENGTH_SHORT).show()
                else {
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

                    if(indexOne>indexTwo){
                        val tmp =indexOne
                        indexOne=indexTwo
                        indexTwo=tmp
                    }

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
                        batch.update(documentCard, "card_open", 0)
                    }
                    builder.dismiss()
                    turnEnd()
                }
            }

            builder.setView(dialogView)
            builder.setCanceledOnTouchOutside(false)

            countDownTimer = object : CountDownTimer(timerDurationLong, 1000) { // 5초 동안, 1초 간격으로 타이머 설정
                override fun onTick(millisUntilFinished: Long) {
                    // 매 초마다 실행되는 코드
                    val secondsLeft = millisUntilFinished / 1000
                    timer.text = secondsLeft.toString()
                }

                override fun onFinish() {
                    // 타이머가 종료되면 실행되는 코드
                    pCardLeft = headOne.toString()+headTwo.toString()+pCardLeft
                    builder.dismiss()
                    turnEnd()
                }
            }
            builder.show()
            countDownTimer.start()
        }
        else {
            mActionText.text = "P${nowTurn}의 EXCHANGE"
        }
    }

    override fun onBackPressed() {
        val dialog = AlertDialog.Builder(this)
        if(pCard[number-1][0] / 10 == 0 && pCard[number-1][1] / 10 == 0) {
            dialog.setTitle("게임에서 나가시겠습니까?")
                .setPositiveButton("예") { dialog, which->
                    snapshotListenerAction.remove()
                    snapshotListenerCoin.remove()
                    snapshotListenerAccept.remove()
                    snapshotListenerInfo.remove()
                    snapshotListenerCard.remove()
                    dialog.dismiss()
                    super.onBackPressed()
                }
                .setNegativeButton("아니요") { dialog, which->
                    dialog.dismiss()
                }
                .show()
        }
        else {
            dialog.setTitle("기권하시겠습니까?")
                .setPositiveButton("예") { dialog, which->
                    snapshotListenerAction.remove()
                    snapshotListenerCoin.remove()
                    snapshotListenerAccept.remove()
                    snapshotListenerInfo.remove()
                    snapshotListenerCard.remove()
                    db.runTransaction { transaction->
                        if(pCard[number-1][0] / 10 == 0) {
                            transaction.update(documentCard, "p${number}card1", pCard[number-1][0] * 10)
                        }
                        if(pCard[number-1][1] / 10 == 0) {
                            transaction.update(documentCard, "p${number}card2", pCard[number-1][1] * 10)
                        }
                    }
                    if(nowTurn == number) {
                        turnEnd()
                    }
                    dialog.dismiss()
                    super.onBackPressed()
                }
                .setNegativeButton("아니요") { dialog, which->
                    dialog.dismiss()
                }
                .show()
        }

    }

    companion object{
        val TAG = "GameRoomActivity"
    }
}