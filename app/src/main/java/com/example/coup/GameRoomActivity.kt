package com.example.coup

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.ktx.storage
import de.hdodenhof.circleimageview.CircleImageView
import org.w3c.dom.Text

class GameRoomActivity : AppCompatActivity() {

    private lateinit var mLeftCardText: TextView

    private lateinit var mPlayerText: Array<TextView>
    private lateinit var mPlayerCoin: Array<TextView>
    private lateinit var mPlayerCard: Array<Array<ImageView>>
    private lateinit var mPlayerProfileImage: Array<CircleImageView>
    private lateinit var mPlayerNickname: Array<TextView>
    private lateinit var mPlayerCardDie: Array<Array<ImageView>>
    private lateinit var mPlayerThumbsUp: Array<ImageView>
    private lateinit var mPlayerThreeDot: Array<ImageView>
    private lateinit var mPlayerAllDie: Array<ImageView>

    private lateinit var mActionConstraint: ConstraintLayout
    private lateinit var mActionIcon: Array<ImageView>
    private lateinit var mActionText: TextView

    private lateinit var auth: FirebaseAuth
    private lateinit var storage: FirebaseStorage
    private lateinit var db: FirebaseFirestore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_game_room)

        init()
    }

    fun init() {
        mLeftCardText = findViewById(R.id.card_left_game_room)

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
    }
}