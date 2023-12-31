package com.example.coup

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Window
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import java.sql.Timestamp

class CreateRoomDialog(context: Context): Dialog(context) {
    //UI references
    private lateinit var mNowPerson: TextView
    private lateinit var mTitleView: EditText
    private lateinit var mPasswordView: EditText
    private lateinit var mMinusPersonButton: Button
    private lateinit var mPlusPersonButton: Button
    private lateinit var mOkayButton: Button
    private lateinit var mCloseButton: Button
    private lateinit var db: FirebaseFirestore
    private lateinit var user: FirebaseAuth
    private var number: Int = 2
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.dialog_create_room)

        mTitleView = findViewById(R.id.edit_title_create_room)
        mPasswordView = findViewById(R.id.edit_password_create_room)
        mOkayButton = findViewById(R.id.button_okay_create_room)
        mCloseButton = findViewById(R.id.button_cancel_create_room)
        mMinusPersonButton = findViewById(R.id.button_minus_person_create)
        mPlusPersonButton = findViewById(R.id.button_plus_person_create)
        mNowPerson = findViewById(R.id.text_now_person_create)

        init()
        db = FirestoreManager.getFirestore()
        user = FirebaseManager.getFirebaseAuth()

        //Okay Button -> Activate GameWaitingRoomActivity
        mOkayButton.setOnClickListener{
            if(mTitleView.text.isNullOrEmpty()) Toast.makeText(context, "방 제목을 입력해 주세요", Toast.LENGTH_SHORT).show()
            else {

                val gameData = hashMapOf(
                    "title" to mTitleView.text.toString(),
                    "password" to mPasswordView.text.toString(),
                    "max_players" to number,
                    "state" to true,
                    "now_players" to 1,
                    "p1" to null,
                    "p2" to null,
                    "p3" to null,
                    "p4" to null,
                    "p5" to null,
                    "p6" to null,
                    "p2ready" to false,
                    "p3ready" to false,
                    "p4ready" to false,
                    "p5ready" to false,
                    "p6ready" to false,
                    "timestamp" to com.google.firebase.Timestamp.now()
                )
                db.collection("game_rooms")
                    .add(gameData)
                    .addOnSuccessListener { documentReference ->
                        val roomId = documentReference.id
                        val intent = Intent(context,GameWaitingRoomActivity::class.java)
                        intent.putExtra("roomId", roomId)
                        intent.putExtra("number", "1")
                        db.collection("game_rooms").document(roomId).update("p1", user.currentUser!!.email)
                        context.startActivity(intent)
                        Log.d(TAG, "성공")
                        dismiss()
                    }
                    .addOnFailureListener { e ->
                        Toast.makeText(context, "방 생성에 실패하였습니다. error code : $e", Toast.LENGTH_SHORT).show()
                    }
            }
        }

        //X Button -> Close Dialog
        mCloseButton.setOnClickListener {
            dismiss()
        }

        mMinusPersonButton.setOnClickListener {
            if(number == 2) Toast.makeText(context, "최소 2명은 있어야 게임을 진행할 수 있습니다", Toast.LENGTH_SHORT).show()
            else {
                number--
                mNowPerson.text = number.toString()
            }
        }

        mPlusPersonButton.setOnClickListener {
            if(number == 6) Toast.makeText(context, "인원 수는 최대 6명까지 가능합니다", Toast.LENGTH_SHORT).show()
            else {
                number++
                mNowPerson.text = number.toString()
            }
        }
    }

    private fun init() {
        mNowPerson.text = number.toString()
    }

    companion object{
        private const val TAG = "CreateRoom"
    }
}