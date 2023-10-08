package com.example.coup

import android.os.Bundle
import android.util.Log
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.compose.material3.AlertDialog
import com.bumptech.glide.Glide
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.firestore.ktx.firestoreSettings
import com.google.firebase.firestore.ktx.memoryCacheSettings
import com.google.firebase.firestore.ktx.persistentCacheSettings
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.ktx.Firebase

// TODO: Rename parameter arguments, choose names that match
// the fragment initialization parameters, e.g. ARG_ITEM_NUMBER
private const val ARG_PARAM1 = "param1"
private const val ARG_PARAM2 = "param2"

/**
 * A simple [Fragment] subclass.
 * Use the [info.newInstance] factory method to
 * create an instance of this fragment.
 */
class info : Fragment() {
    // TODO: Rename and change types of parameters
    private var param1: String? = null
    private var param2: String? = null

    private lateinit var auth: FirebaseAuth
    private lateinit var mNickname: TextView
    private lateinit var mRating: TextView
    private lateinit var mUserImage : ImageView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            param1 = it.getString(ARG_PARAM1)
            param2 = it.getString(ARG_PARAM2)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {

        auth = FirebaseManager.getFirebaseAuth()
        // Inflate the layout for this fragment
        val user = auth.currentUser

        val db = FirestoreManager.getFirestore()

        val view = inflater.inflate(R.layout.fragment_info, container, false)
        mNickname = view.findViewById(R.id.nickname_info)
        mRating = view.findViewById(R.id.score_info)
        mUserImage = view.findViewById(R.id.image_info)

        db.collection("user")
            .document(user!!.email!!.toString())
            .get()
            .addOnCompleteListener { task->
                if(task.isSuccessful) {
                    val document = task.result
                    if(document != null) {
                        if(document.exists()) {
                            mNickname.text = document.get("nickname").toString()
                            mRating.text = document.get("rating").toString()
                            user.photoUrl?.let { imageUrl ->
                                Glide.with(this)
                                    .load(imageUrl)
                                    .into(mUserImage)
                            }
                            Log.d(TAG, "확인하였습니다.${user!!.email!!}")
                        } else {
                            Log.d(TAG, "문서가 존재하지 않습니다.${user!!.email!!}")
                        }
                    } else {
                        Log.d(TAG, "문서가 null입니다.")
                    }
                } else {
                    Log.d(TAG, "데이터를 가져오는 동안 오류 발생: ${task.exception}")
                }
            }
        mUserImage.setOnClickListener {
            val builder = AlertDialog.Builder(requireContext())
            builder.setView(R.layout.dialog_change_profile_image)
        }

        return view
    }

    companion object {
        /**
         * Use this factory method to create a new instance of
         * this fragment using the provided parameters.
         *
         * @param param1 Parameter 1.
         * @param param2 Parameter 2.
         * @return A new instance of fragment info.
         */
        // TODO: Rename and change types and number of parameters
        @JvmStatic
        fun newInstance(param1: String, param2: String) =
            info().apply {
                arguments = Bundle().apply {
                    putString(ARG_PARAM1, param1)
                    putString(ARG_PARAM2, param2)
                }
            }
        private const val TAG = "Info_Fragment"
    }


}