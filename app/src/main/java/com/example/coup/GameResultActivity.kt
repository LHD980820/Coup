package com.example.coup

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.graphics.drawable.GradientDrawable
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import com.bumptech.glide.Glide
import com.google.firebase.Firebase
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.storage.FirebaseStorage
import de.hdodenhof.circleimageview.CircleImageView

class GameResultActivity : AppCompatActivity() {

    private lateinit var constraintLayouts: Array<ConstraintLayout>
    private lateinit var mPlayerNickname: Array<TextView>
    private lateinit var mPlayerRating: Array<TextView>
    private lateinit var mPlayerRatingChange: Array<TextView>
    private lateinit var mPlayerImage: Array<CircleImageView>
    private lateinit var mOkButton: Button

    private lateinit var db: FirebaseFirestore
    private lateinit var documentResult: DocumentReference
    private lateinit var documentSnapshot: ListenerRegistration
    private lateinit var storage: FirebaseStorage

    private var gameId = ""
    private var maxNumber = 0
    private var ratingChangeTable = IntArray(6)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_game_result)

        init()
        documentResult.get().addOnCompleteListener { task->
            if(task.isSuccessful) {
                val result = task.result
                maxNumber = result["players"] as Int
                makeTable(maxNumber)
                for(i in 0 until maxNumber) {
                    constraintLayouts[i].visibility = View.VISIBLE
                    db.collection("user").document(result["p${i+1}"].toString()).get().addOnSuccessListener { document->
                        mPlayerNickname[i].text = document["nickname"].toString()
                        mPlayerRating[i].text = (document["rating"].toString().toInt() + ratingChangeTable[i]).toString()
                        if(ratingChangeTable[i] > 0) mPlayerRatingChange[i].text = "+"+ratingChangeTable[i].toString()
                        else mPlayerRatingChange[i].text = ratingChangeTable[i].toString()
                        storage.reference.child("profile_images/${document.id}.jpg").downloadUrl.addOnSuccessListener { imageUrl ->
                            if (!this.isDestroyed) {
                                Glide.with(this)
                                    .load(imageUrl)
                                    .into(mPlayerImage[i])
                            }
                        }
                    }
                }
                for(i in maxNumber..5) {
                    constraintLayouts[i].visibility = View.GONE
                }
            }
        }
        documentSnapshot = documentResult.addSnapshotListener { snapshot, e->
            if(snapshot != null) {
                maxNumber = snapshot["players"] as Int
                makeTable(maxNumber)
                for(i in 0 until maxNumber) {
                    constraintLayouts[i].visibility = View.VISIBLE
                    db.collection("user").document(snapshot["p${i+1}"].toString()).get().addOnSuccessListener { document->
                        mPlayerNickname[i].text = document["nickname"].toString()
                        mPlayerRating[i].text = (document["rating"].toString().toInt() + ratingChangeTable[i]).toString()
                        if(ratingChangeTable[i] > 0) mPlayerRatingChange[i].text = "+"+ratingChangeTable[i].toString()
                        else mPlayerRatingChange[i].text = ratingChangeTable[i].toString()
                        storage.reference.child("profile_images/${document.id}.jpg").downloadUrl.addOnSuccessListener { imageUrl ->
                            if (!this.isDestroyed) {
                                Glide.with(this)
                                    .load(imageUrl)
                                    .into(mPlayerImage[i])
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

    private fun init() {
        gameId = intent.getStringExtra("gameId").toString()
        constraintLayouts = Array(6) { ConstraintLayout(this) }
        constraintLayouts[0] = findViewById(R.id.constraint_1_game_end)
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
        }

        db = FirestoreManager.getFirestore()
        documentResult = db.collection("game_result").document(gameId)
        storage = FirebaseStorage.getInstance()
    }
    private fun makeTable(players: Int) {
        when (players) {
            2-> {
                ratingChangeTable[0] = 50
                ratingChangeTable[1] = -30
            }
            3-> {
                ratingChangeTable[0] = 60
                ratingChangeTable[1] = 20
                ratingChangeTable[2] = -40
            }
            4-> {
                ratingChangeTable[0] = 70
                ratingChangeTable[1] = 30
                ratingChangeTable[2] = -30
                ratingChangeTable[3] = -50
            }
            5-> {
                ratingChangeTable[0] = 80
                ratingChangeTable[1] = 50
                ratingChangeTable[2] = 20
                ratingChangeTable[3] = -30
                ratingChangeTable[4] = -60
            }
            else-> {
                ratingChangeTable[0] = 100
                ratingChangeTable[1] = 70
                ratingChangeTable[2] = 30
                ratingChangeTable[3] = -30
                ratingChangeTable[4] = -50
                ratingChangeTable[5] = -70
            }
        }
    }
}