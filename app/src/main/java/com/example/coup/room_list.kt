package com.example.coup

import android.app.AlertDialog
import android.os.Bundle
import android.service.autofill.Dataset
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.compose.material3.AlertDialog
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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

        val db = FirestoreManager.getFirestore()
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

    class CustomAdapter(private val dataSet: QuerySnapshot) :
        RecyclerView.Adapter<CustomAdapter.ViewHolder>() {

        /**
         * Provide a reference to the type of views that you are using
         * (custom ViewHolder).
         */
        private val db = FirestoreManager.getFirestore()
        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val constraint: ConstraintLayout
            val title: TextView
            val nickname: TextView
            val person: TextView
            val secret: ImageView
            init {
                // Define click listener for the ViewHolder's View.
                constraint = view.findViewById(R.id.constraint_rooms_item)
                title = view.findViewById(R.id.title_rooms_item)
                nickname = view.findViewById(R.id.nickname_rooms_item)
                person = view.findViewById(R.id.person_rooms_item)
                secret = view.findViewById(R.id.secret_rooms_item)
            }
            fun bind() {
                constraint.setOnClickListener {
                    val position = adapterPosition
                    /*if()
                        if(position != RecyclerView.NO_POSITION) {
                            // 다이얼로그의 레이아웃을 인플레이트합니다.
                            val inflater = LayoutInflater.from(view.context)
                            val dialogView = inflater.inflate(R.layout.dialog_check_password, null)

                            val dialogBuilder = AlertDialog.Builder(view.context)
                            dialogBuilder.setView(dialogView)

                            val mCheckPasswordEditView =
                                dialogView.findViewById<EditText>(R.id.edit_check_password)
                            val mOkayButton =
                                dialogView.findViewById<Button>(R.id.button_okay_check_password)
                            val mCancelButton =
                                dialogView.findViewById<Button>(R.id.button_cancel_check_password)

                            mOkayButton.setOnClickListener {
                                val pw = mCheckPasswordEditView.text.toString()
                                if (pw != dataSet)
                            }
                            mCancelButton.setOnClickListener {
                            }
                        }*/
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
                if(data?.get("password") == null) {
                    viewHolder.secret.visibility = View.INVISIBLE
                }
            }
        }

        // Return the size of your dataset (invoked by the layout manager)
        override fun getItemCount() = dataSet.size()
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
    }
}