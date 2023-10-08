package com.example.coup

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.service.autofill.Dataset
import android.text.InputType
import android.util.Log
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot

// TODO: Rename parameter arguments, choose names that match
// the fragment initialization parameters, e.g. ARG_ITEM_NUMBER
private const val ARG_PARAM1 = "param1"
private const val ARG_PARAM2 = "param2"

/**
 * A simple [Fragment] subclass.
 * Use the [room_list.newInstance] factory method to
 * create an instance of this fragment.
 */
class room_list : Fragment() {
    // TODO: Rename and change types of parameters
    private var param1: String? = null
    private var param2: String? = null
    private lateinit var createRoomButton: Button
    private lateinit var db: FirebaseFirestore
    private lateinit var user: FirebaseAuth

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
        // Inflate the layout for this fragment
        val view = inflater.inflate(R.layout.fragment_room_list, container, false)
        // "create_room" 버튼 찾기
        createRoomButton = view.findViewById(R.id.create_room)

        user = FirebaseManager.getFirebaseAuth()
        db = FirestoreManager.getFirestore()
        db.collection("game_rooms")
            .get()
            .addOnSuccessListener { documentSnapshots ->
                val adapter = CustomAdapter(documentSnapshots)
                val recyclerView = view.findViewById<RecyclerView>(R.id.rooms_recyclerview)
                recyclerView.layoutManager = LinearLayoutManager(requireContext())
                recyclerView.adapter = adapter
            }

        // "create_room" 버튼에 대한 클릭 이벤트 처리
        createRoomButton.setOnClickListener {
            val dialog = CreateRoomDialog(requireContext())
            dialog.show()
        }

        return view

    }

    inner class CustomAdapter(private val dataSet: QuerySnapshot) :
        RecyclerView.Adapter<CustomAdapter.ViewHolder>() {

        /**
         * Provide a reference to the type of views that you are using
         * (custom ViewHolder).
         */
        private val db = FirestoreManager.getFirestore()
        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView
            val nickname: TextView
            val person: TextView
            val secret: ImageView
            init {
                // Define click listener for the ViewHolder's View.
                title = view.findViewById(R.id.title_rooms_item)
                nickname = view.findViewById(R.id.nickname_rooms_item)
                person = view.findViewById(R.id.person_rooms_item)
                secret = view.findViewById(R.id.secret_rooms_item)

                view.setOnClickListener {
                    if(dataSet.documents[adapterPosition].get("password").toString() != "") {
                        val builder = AlertDialog.Builder(itemView.context).create()
                        val inflater = LayoutInflater.from(itemView.context)
                        val dialogview = inflater.inflate(R.layout.dialog_check_password, null)
                        builder.setView(dialogview)

                        val mCheckEditText = dialogview.findViewById<EditText>(R.id.edit_check_password)
                        val mOkayButton = dialogview.findViewById<Button>(R.id.button_okay_check_password)
                        val mCancelButton = dialogview.findViewById<Button>(R.id.button_cancel_check_password)

                        mOkayButton.setOnClickListener {
                            if(mCheckEditText.text.toString() == dataSet.documents[adapterPosition].get("password").toString()) {
                                RoomIn(dataSet.documents[adapterPosition])
                                builder.dismiss()
                            }
                            else {
                                Toast.makeText(itemView.context, "비밀번호가 일치하지 않습니다", Toast.LENGTH_SHORT).show()
                            }
                        }

                        mCancelButton.setOnClickListener {
                            builder.dismiss()
                        }
                        builder.show()
                    }
                    else {
                        RoomIn(dataSet.documents[adapterPosition])
                    }
                }
            }
        }

        // Create new views (invoked by the layout manager)
        override fun onCreateViewHolder(viewGroup: ViewGroup, viewType: Int): ViewHolder {
            // Create a new view, which defines the UI of the list item
            val view = LayoutInflater.from(viewGroup.context)
                .inflate(R.layout.rooms_item, viewGroup, false)

            return ViewHolder(view)
        }

        // Replace the contents of a view (invoked by the layout manager)
        override fun onBindViewHolder(viewHolder: ViewHolder, position: Int) {

            // Get element from your dataset at this position and replace the
            // contents of the view with that element
            val data = dataSet.documents[position].data
            db.collection("user").document(data?.get("p1").toString()).get().addOnSuccessListener { documentSnapshot ->
                viewHolder.title.text = data?.get("title").toString()
                viewHolder.nickname.text = "@"+documentSnapshot["nickname"].toString()
                viewHolder.person.text = data?.get("now_players").toString() + " / " + data?.get("max_players").toString()
                if(data?.get("password").toString() == "") {
                    viewHolder.secret.visibility = View.INVISIBLE
                }
            }
        }

        // Return the size of your dataset (invoked by the layout manager)
        override fun getItemCount() = dataSet.size()
    }
    private fun RoomIn(Docsnapshot: DocumentSnapshot) {
        db.runTransaction { transaction ->
            val snapshot = transaction.get(Docsnapshot.reference)
            val now_player = snapshot.get("now_players").toString().toInt()
            if(now_player >= snapshot.get("max_players").toString().toInt()) {
                Log.d(TAG, "인원 수 다 참 (${now_player} + ${snapshot.get("max_players").toString()})")
                activity?.runOnUiThread {
                    Toast.makeText(requireContext(), "인원 수가 다 찼습니다", Toast.LENGTH_SHORT).show()
                }
                Log.d(TAG, "토스트 출력 후")
            }
            else if(snapshot.get("state").toString().toInt() != 1) {
                activity?.runOnUiThread {
                    Toast.makeText(requireContext(), "게임 중인 방입니다", Toast.LENGTH_SHORT).show()
                }
            }
            else {
                transaction.update(Docsnapshot.reference, "now_players", now_player + 1)
                var my_number: Int = 1
                for(i in 1 until Docsnapshot.get("max_players").toString().toInt() + 1) {
                    if(Docsnapshot.get("p${i}") == null) {
                        transaction.update(Docsnapshot.reference, "p${i}", user.currentUser!!.email)
                        my_number = i
                        break
                    }
                }
                val intent = Intent(requireContext(), GameWaitingRoomActivity::class.java)
                intent.putExtra("roomId", Docsnapshot.id)
                intent.putExtra("number", my_number.toString())
                startActivity(intent)
            }
        }
    }


    companion object {
        /**
         * Use this factory method to create a new instance of
         * this fragment using the provided parameters.
         *
         * @param param1 Parameter 1.
         * @param param2 Parameter 2.
         * @return A new instance of fragment room_list.
         */
        // TODO: Rename and change types and number of parameters
        @JvmStatic
        fun newInstance(param1: String, param2: String) =
            room_list().apply {
                arguments = Bundle().apply {
                    putString(ARG_PARAM1, param1)
                    putString(ARG_PARAM2, param2)
                }
            }
        private const val TAG = "RoomList"
    }
}