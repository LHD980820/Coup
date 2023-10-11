package com.example.coup

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.bumptech.glide.Glide
import com.example.coup.ui.login.LoginActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.ktx.Firebase
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.ktx.storage
import de.hdodenhof.circleimageview.CircleImageView


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
    private lateinit var mPlays: TextView
    private lateinit var mUserImage : CircleImageView
    private lateinit var mProgressBar: ProgressBar
    private lateinit var mChangePasswordBtn: Button
    private lateinit var mLogoutButton: Button

    private lateinit var dialog_image: CircleImageView
    private lateinit var dialog_okay: Button
    private lateinit var dialog_cancel: Button

    private lateinit var storage: FirebaseStorage
    private lateinit var selectedImageUri: Uri


    private lateinit var imagePicker: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            param1 = it.getString(ARG_PARAM1)
            param2 = it.getString(ARG_PARAM2)
        }

        imagePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val data: Intent? = result.data
                selectedImageUri = data?.data!!

                // 이미지 업로드 및 프로필 업데이트 작업 수행
                if (selectedImageUri != null) {
                    dialog_image.setImageURI(selectedImageUri)
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {

        auth = FirebaseManager.getFirebaseAuth()
        storage = Firebase.storage
        // Inflate the layout for this fragment
        val user = auth.currentUser
        val db = FirestoreManager.getFirestore()

        val view = inflater.inflate(R.layout.fragment_info, container, false)
        mNickname = view.findViewById(R.id.nickname_info)
        mRating = view.findViewById(R.id.score_info)
        mUserImage = view.findViewById(R.id.image_info)
        mPlays = view.findViewById(R.id.plays_info)
        mProgressBar = view.findViewById(R.id.progressBar_info)
        mChangePasswordBtn = view.findViewById(R.id.button_change_password_info)
        mLogoutButton = view.findViewById(R.id.button_logout_info)

        mProgressBar.visibility = View.INVISIBLE

        db.collection("user")
            .document(user!!.email!!.toString())
            .get()
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val document = task.result
                    if (document != null) {
                        if (document.exists()) {
                            mNickname.text = document.get("nickname").toString()
                            mRating.text = document.get("rating").toString()
                            mPlays.text = "plays : " + document.get("plays").toString()
                            mProgressBar.visibility = View.VISIBLE
                            storage.reference.child("profile_images/${user.email}.jpg").downloadUrl.addOnSuccessListener { imageUrl ->
                                if(isAdded) {
                                    Glide.with(requireContext())
                                        .load(imageUrl)
                                        .into(mUserImage)
                                }
                            }
                            mProgressBar.visibility = View.INVISIBLE
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
            val builder = AlertDialog.Builder(requireContext()).create()
            val dialogview = inflater.inflate(R.layout.dialog_change_profile_image, null)
            builder.setView(dialogview)

            dialog_image = dialogview.findViewById(R.id.image_change_profile_image)
            dialog_okay = dialogview.findViewById(R.id.button_okay_change_profile_image)
            dialog_cancel = dialogview.findViewById(R.id.button_cancel_change_profile_image)

            storage.reference.child("profile_images/${user.email}.jpg").downloadUrl.addOnSuccessListener { imageUrl ->
                if(isAdded) {
                    Glide.with(this)
                        .load(imageUrl)
                        .into(dialog_image)
                }
            }

            dialog_image.setOnClickListener {
                Log.d(TAG, "사진 눌러짐")
                val intent = Intent(Intent.ACTION_PICK)
                intent.type = "image/"
                imagePicker.launch(intent)
            }
            dialog_okay.setOnClickListener {
                if(::selectedImageUri.isInitialized) {
                    uploadImage(selectedImageUri)
                }
                builder.dismiss()
            }
            dialog_cancel.setOnClickListener {
                builder.dismiss()
            }
            builder.show()
        }

        mChangePasswordBtn.setOnClickListener{
            val Dialog = InfoChangePWDialog(requireContext())
            Dialog.show()
        }

        mLogoutButton.setOnClickListener {
            val builder_logout = AlertDialog.Builder(requireContext())
            builder_logout.setTitle("로그아웃") // 다이얼로그 제목 설정
            builder_logout.setMessage("로그아웃 하시겠습니까?") // 다이얼로그 내용 설정

            builder_logout.setPositiveButton("예") { dialog, which ->
                // 확인 버튼을 클릭했을 때 수행할 작업을 여기에 추가
                auth.signOut()
                val intent = Intent(requireContext(), LoginActivity::class.java)
                startActivity(intent)
                activity?.finish()
                dialog.dismiss() // 다이얼로그 닫기
            }

            builder_logout.setNegativeButton("아니요") { dialog, which ->
                // 취소 버튼을 클릭했을 때 수행할 작업을 여기에 추가
                dialog.dismiss() // 다이얼로그 닫기
            }
            val dialog = builder_logout.create()
            dialog.show()
        }

        return view
    }

    private fun uploadImage(imageUri: Uri?) {
        auth = FirebaseManager.getFirebaseAuth()
        val user = auth.currentUser
        val storageRef = storage.reference
        Log.d(TAG, "성공")
        if (user != null && imageUri != null) {
            Log.d(TAG, "성공")
            val imageRef = storageRef.child("profile_images/${user.email}.jpg")

            val uploadTask = imageRef.putFile(imageUri)
            mProgressBar.visibility = View.VISIBLE
            uploadTask.continueWithTask { task ->
                if (!task.isSuccessful) {
                    task.exception?.let {
                        throw it
                    }
                }
                imageRef.downloadUrl
            }.addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val downloadUri: Uri? = task.result
                    val profileUpdates = UserProfileChangeRequest.Builder()
                        .setPhotoUri(downloadUri)
                        .build()

                    user.updateProfile(profileUpdates)
                        .addOnCompleteListener { updateTask ->
                            if (updateTask.isSuccessful) {
                                // 프로필 업데이트 성공.
                                mProgressBar.visibility = View.INVISIBLE
                                storage.reference.child("profile_images/${user.email}.jpg").downloadUrl.addOnSuccessListener { imageUrl ->
                                    Glide.with(this)
                                        .load(imageUrl)
                                        .into(mUserImage)
                                }
                                Log.d(TAG, "성공")
                            } else {
                                // 프로필 업데이트 실패.
                                Log.d(TAG, "실패1")
                            }
                        }
                } else {
                    // 이미지 업로드 실패.
                    Log.d(TAG, "실패2")
                }
            }
        }
    }

    companion object {
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