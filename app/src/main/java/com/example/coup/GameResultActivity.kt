package com.example.coup

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.graphics.drawable.GradientDrawable
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.storage.FirebaseStorage
import de.hdodenhof.circleimageview.CircleImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class GameResultActivity : AppCompatActivity() {

    private lateinit var constraintLayouts: Array<ConstraintLayout>
    private lateinit var mPlayerNickname: Array<TextView>
    private lateinit var mPlayerRating: Array<TextView>
    private lateinit var mPlayerRatingChange: Array<TextView>
    private lateinit var mPlayerImage: Array<CircleImageView>
    private lateinit var mOkButton: Button

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private lateinit var documentResult: DocumentReference
    private lateinit var documentSnapshot: ListenerRegistration
    private lateinit var storage: FirebaseStorage

    private var gameId = ""
    private var maxNumber = 0
    private val ratingChangeTable = Array(6) { IntArray(6) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_game_result)
        auth = FirebaseAuth.getInstance()
        db = FirestoreManager.getFirestore()

        CoroutineScope(Dispatchers.IO).launch {
            init()
            documentResult.get().addOnCompleteListener { task->
                if(task.isSuccessful) {
                    val result = task.result
                    maxNumber = result["players"].toString().toInt()
                    if(maxNumber >= 2) {
                        for(i in 0 until maxNumber) {
                            if(result["p${i+1}rank"].toString().toInt() != 0) {
                                val rank = result["p${i+1}rank"].toString().toInt()
                                constraintLayouts[rank - 1].visibility = View.VISIBLE
                                db.collection("user").document(result["p${i+1}"].toString()).get().addOnSuccessListener { document->
                                    mPlayerNickname[rank - 1].text = document["nickname"].toString()
                                    mPlayerRating[rank - 1].text = (document["rating"].toString().toInt() + ratingChangeTable[maxNumber - 2][rank - 1]).toString()
                                    if(ratingChangeTable[maxNumber - 2][rank - 1] > 0) {
                                        mPlayerRatingChange[rank - 1].setTextColor(ContextCompat.getColor(this@GameResultActivity, R.color.red))
                                        mPlayerRatingChange[rank - 1].text = "+"+ratingChangeTable[maxNumber - 2][rank - 1].toString()
                                    }
                                    else {
                                        mPlayerRatingChange[rank - 1].setTextColor(ContextCompat.getColor(this@GameResultActivity, R.color.box_color))
                                        mPlayerRatingChange[rank - 1].text = ratingChangeTable[maxNumber - 2][rank - 1].toString()
                                    }
                                    storage.reference.child("profile_images/${document.id}.jpg").downloadUrl.addOnSuccessListener { imageUrl ->
                                        if (!this@GameResultActivity.isDestroyed) {
                                            Glide.with(this@GameResultActivity)
                                                .load(imageUrl)
                                                .into(mPlayerImage[rank - 1])
                                        }
                                    }
                                }
                            }
                        }
                        for(i in maxNumber..5) {
                            constraintLayouts[i].visibility = View.GONE
                        }
                    }
                }
            }.await()
            documentSnapshot = documentResult.addSnapshotListener { snapshot, e->
                if(snapshot != null) {
                    if(snapshot.get("finish").toString().toInt() == 0) {
                        maxNumber = snapshot["players"].toString().toInt()
                        if(maxNumber >= 2) {
                            for(i in 0 until maxNumber) {
                                if(snapshot["p${i+1}rank"].toString().toInt() != 0) {
                                    val rank = snapshot["p${i+1}rank"].toString().toInt()
                                    constraintLayouts[rank - 1].visibility = View.VISIBLE
                                    db.collection("user").document(snapshot["p${i+1}"].toString()).get().addOnSuccessListener { document->
                                        mPlayerNickname[rank - 1].text = document["nickname"].toString()
                                        mPlayerRating[rank - 1].text = (document["rating"].toString().toInt() + ratingChangeTable[maxNumber - 2][rank - 1]).toString()
                                        if(ratingChangeTable[maxNumber - 2][rank - 1] > 0) {
                                            mPlayerRatingChange[rank - 1].setTextColor(ContextCompat.getColor(this@GameResultActivity, R.color.red))
                                            mPlayerRatingChange[rank - 1].text = "+"+ratingChangeTable[maxNumber - 2][rank - 1].toString()
                                        }
                                        else {
                                            mPlayerRatingChange[rank - 1].setTextColor(ContextCompat.getColor(this@GameResultActivity, R.color.box_color))
                                            mPlayerRatingChange[rank - 1].text = ratingChangeTable[maxNumber - 2][rank - 1].toString()
                                        }
                                        storage.reference.child("profile_images/${document.id}.jpg").downloadUrl.addOnSuccessListener { imageUrl ->
                                            if (!this@GameResultActivity.isDestroyed) {
                                                Glide.with(this@GameResultActivity)
                                                    .load(imageUrl)
                                                    .into(mPlayerImage[rank - 1])
                                            }
                                        }
                                    }
                                }
                            }
                            for(i in maxNumber..5) {
                                constraintLayouts[i].visibility = View.GONE
                            }
                        }
                    }
                }
            }
        }
        Handler(Looper.getMainLooper()).postDelayed({
            db.runTransaction { transaction->
                val finish = transaction.get(documentResult).get("finish").toString().toInt()
                val players = transaction.get(documentResult).get("players").toString().toInt()
                Log.d(TAG, "finish: $finish, players: $players")
                if(finish == 0) {
                    val documentUser: MutableList<DocumentSnapshot?> = MutableList(6) { null }
                    val userRating: MutableList<Int> = MutableList(6) { 0 }
                    val userRanking: MutableList<Int> = MutableList(6) { 0 }
                    Log.d(TAG, "finish = 0 들어옴")
                    for(i in 1..players) {
                        documentUser[i-1] = transaction.get(db.collection("user").document(transaction.get(documentResult).get("p$i").toString()))
                        userRanking[i-1] = transaction.get(documentResult).get("p${i}rank").toString().toInt()
                        userRating[i-1] = documentUser[i-1]?.get("rating")?.toString()?.toInt()!!
                        Log.d(TAG, "i: $i, email: ${documentUser[i-1]?.id}, rank: ${userRanking[i-1]}, rating: $userRating")
                        Log.d(TAG, "ratingChange: ${ratingChangeTable[maxNumber - 2][userRanking[i-1] - 1]}")
                    }
                    for(i in 1..players) {
                        transaction.update(documentUser[i-1]!!.reference, "rating", userRating[i-1] + ratingChangeTable[maxNumber - 2][userRanking[i-1] - 1])
                    }
                    transaction.update(documentResult, "finish", 1)
                }
            }.addOnSuccessListener { result->
                Log.d(TAG, "Transaction success: $result")
            }.addOnFailureListener { e->
                Log.w(TAG, "Transaction failure.", e)
            }
        }, 1000)
    }

    private suspend fun init() {
        gameId = intent.getStringExtra("gameId").toString()
        constraintLayouts = Array(6) { ConstraintLayout(this) }
        constraintLayouts[0] = findViewById(R.id.constraint_1_game_end)

        documentResult = db.collection("game_result").document(gameId)
        storage = FirebaseStorage.getInstance()
        /*val gradientDrawable = constraintLayouts[0].background as GradientDrawable

        val colorAnimation = ValueAnimator.ofObject(ArgbEvaluator(), 0xFFD700, 0x0000FF)
        colorAnimation.duration = 3000 // 애니메이션 지속 시간 (3초)

        colorAnimation.addUpdateListener { animator ->
            val animatedValue = animator.animatedValue as Int
            gradientDrawable.setColor(animatedValue)
        }

        colorAnimation.repeatCount = ValueAnimator.INFINITE // 무한 반복
        colorAnimation.repeatMode = ValueAnimator.REVERSE // 역방향 반복

        colorAnimation.start()*/
        ratingChangeTable[0][0] = 30
        ratingChangeTable[0][1] = -20
        ratingChangeTable[1][0] = 50
        ratingChangeTable[1][1] = 20
        ratingChangeTable[1][2] = -40
        ratingChangeTable[2][0] = 70
        ratingChangeTable[2][1] = 30
        ratingChangeTable[2][2] = -30
        ratingChangeTable[2][3] = -50
        ratingChangeTable[3][0] = 100
        ratingChangeTable[3][1] = 60
        ratingChangeTable[3][2] = 30
        ratingChangeTable[3][3] = -40
        ratingChangeTable[3][4] = -70
        ratingChangeTable[4][0] = 130
        ratingChangeTable[4][1] = 70
        ratingChangeTable[4][2] = 40
        ratingChangeTable[4][3] = -30
        ratingChangeTable[4][4] = -60
        ratingChangeTable[4][5] = -90

        constraintLayouts[1] = findViewById(R.id.constraint_2_game_end)
        constraintLayouts[2] = findViewById(R.id.constraint_3_game_end)
        constraintLayouts[3] = findViewById(R.id.constraint_4_game_end)
        constraintLayouts[4] = findViewById(R.id.constraint_5_game_end)
        constraintLayouts[5] = findViewById(R.id.constraint_6_game_end)

        mPlayerNickname = Array(6) { TextView(this) }
        mPlayerNickname[0] = findViewById(R.id.text_nickname1_game_end)
        mPlayerNickname[1] = findViewById(R.id.text_nickname2_game_end)
        mPlayerNickname[2] = findViewById(R.id.text_nickname3_game_end)
        mPlayerNickname[3] = findViewById(R.id.text_nickname4_game_end)
        mPlayerNickname[4] = findViewById(R.id.text_nickname5_game_end)
        mPlayerNickname[5] = findViewById(R.id.text_nickname6_game_end)

        mPlayerRating = Array(6) { TextView(this) }
        mPlayerRating[0] = findViewById(R.id.text_rating1_game_end)
        mPlayerRating[1] = findViewById(R.id.text_rating2_game_end)
        mPlayerRating[2] = findViewById(R.id.text_rating3_game_end)
        mPlayerRating[3] = findViewById(R.id.text_rating4_game_end)
        mPlayerRating[4] = findViewById(R.id.text_rating5_game_end)
        mPlayerRating[5] = findViewById(R.id.text_rating6_game_end)

        mPlayerRatingChange = Array(6) { TextView(this) }
        mPlayerRatingChange[0] = findViewById(R.id.text_change_rating1_game_end)
        mPlayerRatingChange[1] = findViewById(R.id.text_change_rating2_game_end)
        mPlayerRatingChange[2] = findViewById(R.id.text_change_rating3_game_end)
        mPlayerRatingChange[3] = findViewById(R.id.text_change_rating4_game_end)
        mPlayerRatingChange[4] = findViewById(R.id.text_change_rating5_game_end)
        mPlayerRatingChange[5] = findViewById(R.id.text_change_rating6_game_end)

        mPlayerImage = Array(6) { CircleImageView(this) }
        mPlayerImage[0] = findViewById(R.id.image_profile1_game_end)
        mPlayerImage[1] = findViewById(R.id.image_profile2_game_end)
        mPlayerImage[2] = findViewById(R.id.image_profile3_game_end)
        mPlayerImage[3] = findViewById(R.id.image_profile4_game_end)
        mPlayerImage[4] = findViewById(R.id.image_profile5_game_end)
        mPlayerImage[5] = findViewById(R.id.image_profile6_game_end)

        mOkButton = findViewById(R.id.button_ok_game_end)
        mOkButton.setOnClickListener {
            documentSnapshot.remove()
            finish()
            db.runTransaction { transaction->
                if(transaction.get(documentResult).get("finish").toString().toInt() == 0) {
                    for(i in 1..transaction.get(documentResult).get("players").toString().toInt()) {
                        val documentUser = transaction.get(db.collection("user").document(transaction.get(documentResult).get("p$i").toString()))
                        transaction.update(documentUser.reference, "rating",
                            documentUser["rating"].toString().toInt() + ratingChangeTable[maxNumber - 2][transaction.get(documentResult).get("p${i}rank").toString().toInt() - 1])
                    }
                    transaction.update(documentResult, "finish", 1)
                }
            }
        }

    }

    override fun onPause() {
        db.collection("user").document(auth.currentUser?.email.toString()).update("state", false)
        super.onPause()
    }
    override fun onStop() {
        super.onStop()
    }

    override fun onResume() {
        db.collection("user").document(auth.currentUser?.email.toString()).update("state", true)
        super.onResume()
    }

    companion object{
        val TAG = "GameResultActivity"
    }
}