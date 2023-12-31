package com.example.coup

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.coup.ui.login.LoginActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.Filter
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.ktx.Firebase
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.ktx.storage
import de.hdodenhof.circleimageview.CircleImageView
import java.util.Calendar


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

    private val ratingChangeTable = Array(6) { IntArray(6) }

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private lateinit var mNickname: TextView
    private lateinit var mRating: TextView
    private lateinit var mPlays: TextView
    private lateinit var mUserImage : CircleImageView
    private lateinit var mProgressBar: ProgressBar
    private lateinit var mChangeNicknameBtn: Button
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
        db = FirestoreManager.getFirestore()

        val view = inflater.inflate(R.layout.fragment_info, container, false)
        mNickname = view.findViewById(R.id.nickname_info)
        mRating = view.findViewById(R.id.score_info)
        mUserImage = view.findViewById(R.id.image_info)
        mPlays = view.findViewById(R.id.plays_info)
        mProgressBar = view.findViewById(R.id.progressBar_info)
        mChangeNicknameBtn = view.findViewById(R.id.button_change_nickname_info)
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

        mChangeNicknameBtn.setOnClickListener{
            val builder_check_nickname = AlertDialog.Builder(requireContext()).create()
            val dialog_view_check_nickname = inflater.inflate(R.layout.dialog_change_nickname, null)
            builder_check_nickname.setView(dialog_view_check_nickname)

            val dialog_nickname = dialog_view_check_nickname.findViewById<EditText>(R.id.edit_change_nickname)
            db.collection("user").document(user.email.toString()).get().addOnSuccessListener { document->
                dialog_nickname.setText(document["nickname"].toString())
            }

            val okay_button_change_nickname = dialog_view_check_nickname.findViewById<Button>(R.id.button_okay_change_nickname)
            val cancel_button_change_nickname = dialog_view_check_nickname.findViewById<Button>(R.id.button_cancel_change_nickname)
            okay_button_change_nickname.setOnClickListener {
                if(mNickname.text.isNullOrEmpty()) {
                    Toast.makeText(requireContext(), "변경할 닉네임을 입력해주세요", Toast.LENGTH_SHORT).show()
                }
                db.collection("user").document(user.email.toString()).update("nickname", dialog_nickname.text.toString())
                mNickname.text = dialog_nickname.text
                builder_check_nickname.dismiss()
            }
            cancel_button_change_nickname.setOnClickListener {
                builder_check_nickname.dismiss()
            }
            builder_check_nickname.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            builder_check_nickname.show()
        }

        mLogoutButton.setOnClickListener {
            val builder_logout = AlertDialog.Builder(requireContext())
            builder_logout.setTitle("로그아웃") // 다이얼로그 제목 설정
            builder_logout.setMessage("로그아웃 하시겠습니까?") // 다이얼로그 내용 설정

            builder_logout.setPositiveButton("예") { dialog, which ->
                db.collection("user").document(auth.currentUser!!.email.toString()).update("state", false)
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
        db.collection("game_result")
            .where(Filter.and(
                Filter.notEqualTo("timestamp", 0),
                Filter.equalTo("finish", 1),
                Filter.or(
                    Filter.equalTo("p1", user.email.toString()),
                    Filter.equalTo("p2", user.email.toString()),
                    Filter.equalTo("p3", user.email.toString()),
                    Filter.equalTo("p4", user.email.toString()),
                    Filter.equalTo("p5", user.email.toString()),
                    Filter.equalTo("p6", user.email.toString())
                )
            ))
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .get()
            .addOnSuccessListener { documentSnapshots ->
                Log.d(TAG, "recyclerView size : " + documentSnapshots.size().toString())
                mPlays.text = "plays : " + documentSnapshots.size().toString()
                val adapter = CustomAdapter(documentSnapshots)
                val recyclerView = view.findViewById<RecyclerView>(R.id.recyclerView_info)
                if(context != null) {
                    recyclerView.layoutManager = LinearLayoutManager(requireContext())
                    recyclerView.adapter = adapter
                }
            }
            .addOnFailureListener { e->
                Log.e(TAG, "Error fetching documents: $e")
            }
        return view
    }

    inner class CustomAdapter(private val dataSet: QuerySnapshot) :
        RecyclerView.Adapter<CustomAdapter.ViewHolder>() {

        /**
         * Provide a reference to the type of views that you are using
         * (custom ViewHolder).
         */
        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val date: TextView
            val people: TextView
            val rank: TextView
            val score: TextView
            init {
                // Define click listener for the ViewHolder's View.
                date = view.findViewById(R.id.date_match_record)
                people = view.findViewById(R.id.people_match_record)
                rank = view.findViewById(R.id.rank_match_record)
                score = view.findViewById(R.id.score_match_record)
            }
        }

        // Create new views (invoked by the layout manager)
        override fun onCreateViewHolder(viewGroup: ViewGroup, viewType: Int): ViewHolder {
            // Create a new view, which defines the UI of the list item
            val view = LayoutInflater.from(viewGroup.context)
                .inflate(R.layout.match_record_item, viewGroup, false)

            return ViewHolder(view)
        }

        // Replace the contents of a view (invoked by the layout manager)
        override fun onBindViewHolder(viewHolder: ViewHolder, position: Int) {

            // Get element from your dataset at this position and replace the
            // contents of the view with that element
            val document = dataSet.documents[position]
            val max_players = document.get("players").toString().toInt()
            if(max_players >= 2) {
                for(i in 0 until max_players) {
                    if(document.get("p${i+1}").toString() == auth.currentUser?.email && document.get("p${i+1}rank").toString().toInt() > 0) {
                        viewHolder.people.text = max_players.toString()
                        viewHolder.rank.text = document.get("p${i+1}rank").toString()
                        if(ratingChangeTable[max_players - 2][document.get("p${i+1}rank").toString().toInt() - 1] > 0) {
                            viewHolder.score.setTextColor(ContextCompat.getColor(requireContext(), R.color.red))
                            viewHolder.score.text = "+" + ratingChangeTable[max_players - 2][document.get("p${i+1}rank").toString().toInt() - 1].toString()
                        }
                        else {
                            viewHolder.score.setTextColor(ContextCompat.getColor(requireContext(), R.color.box_color))
                            viewHolder.score.text = ratingChangeTable[max_players - 2][document.get("p${i+1}rank").toString().toInt() - 1].toString()
                        }

                        val timestamp = document.getTimestamp("timestamp")!!.toDate()
                        val calendar = Calendar.getInstance()
                        calendar.time = timestamp

                        val year = calendar.get(Calendar.YEAR) % 100
                        val month = calendar.get(Calendar.MONTH) + 1  // 월은 0부터 시작하므로 1을 더함
                        val day = calendar.get(Calendar.DAY_OF_MONTH)

                        val time = year.toString() + "." + month.toString() + "." + day.toString()

                        viewHolder.date.text = time
                    }
                }
            }

        }

        // Return the size of your dataset (invoked by the layout manager)
        override fun getItemCount() = dataSet.size()
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