package com.example.coup

import android.graphics.Color
import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.ui.res.colorResource
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.firebase.ui.firestore.FirestoreRecyclerOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.firestore.ktx.firestoreSettings
import com.google.firebase.firestore.ktx.memoryCacheSettings
import com.google.firebase.firestore.ktx.persistentCacheSettings
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
 * Use the [ranking.newInstance] factory method to
 * create an instance of this fragment.
 */
class ranking : Fragment() {
    // TODO: Rename and change types of parameters
    private var param1: String? = null
    private var param2: String? = null

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
        val view = inflater.inflate(R.layout.fragment_ranking, container, false)
        val db = FirestoreManager.getFirestore()
        db.collection("user")
            .orderBy("rating", Query.Direction.DESCENDING).limit(100)
            .get()
            .addOnSuccessListener { documentSnapshots ->
                val adapter = CustomAdapter(documentSnapshots)
                val recyclerView = view.findViewById<RecyclerView>(R.id.ranking_recyclerview)
                if(context != null) {
                    recyclerView.layoutManager = LinearLayoutManager(requireContext())
                    recyclerView.adapter = adapter
                }
            }
        // Inflate the layout for this fragment
        return view
    }

    class CustomAdapter(private val dataSet: QuerySnapshot) :
        RecyclerView.Adapter<CustomAdapter.ViewHolder>() {

        /**
         * Provide a reference to the type of views that you are using
         * (custom ViewHolder).
         */
        private var auth = FirebaseManager.getFirebaseAuth()
        private var storage = Firebase.storage
        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val number: TextView
            val nickname: TextView
            val rating: TextView
            val profile_image: CircleImageView
            init {
                // Define click listener for the ViewHolder's View.
                number = view.findViewById(R.id.text_number_ranking_item)
                nickname = view.findViewById(R.id.text_nickname_ranking_item)
                rating = view.findViewById(R.id.text_rating_ranking_item)
                profile_image = view.findViewById(R.id.image_profile_ranking_item)
            }
        }

        // Create new views (invoked by the layout manager)
        override fun onCreateViewHolder(viewGroup: ViewGroup, viewType: Int): ViewHolder {
            // Create a new view, which defines the UI of the list item
            val view = LayoutInflater.from(viewGroup.context)
                .inflate(R.layout.ranking_item, viewGroup, false)

            return ViewHolder(view)
        }

        // Replace the contents of a view (invoked by the layout manager)
        override fun onBindViewHolder(viewHolder: ViewHolder, position: Int) {

            // Get element from your dataset at this position and replace the
            // contents of the view with that element
            viewHolder.number.text = (position + 1).toString()
            viewHolder.nickname.text = dataSet.documents[position].data?.get("nickname").toString()
            viewHolder.rating.text = dataSet.documents[position].data?.get("rating").toString()
            if(viewHolder.profile_image.context != null) {
                storage.reference.child("profile_images/${dataSet.documents[position].id}.jpg").downloadUrl.addOnSuccessListener { imageUrl ->
                    Glide.with(viewHolder.profile_image.context)
                        .load(imageUrl)
                        .into(viewHolder.profile_image)
                }
            }

            // Check if the position is 0, 1, or 2 (1st, 2nd, or 3rd place)
            if (position < 3) {
                //1,2,3등의 배경은 금,은,동
                when (position) {
                    0 -> viewHolder.itemView.setBackgroundColor(Color.parseColor("#80ffd700"))
                    1 -> viewHolder.itemView.setBackgroundColor(Color.parseColor("#80c0c0c0"))
                    else -> viewHolder.itemView.setBackgroundColor(Color.parseColor("#80bf8970"))
                }

                // Apply (Blinking) Animation
                //val blinkingAnimation = AnimationUtils.loadAnimation(viewHolder.itemView.context, R.anim.blink_animation)
                //viewHolder.itemView.startAnimation(blinkingAnimation)
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
         * @return A new instance of fragment ranking.
         */
        // TODO: Rename and change types and number of parameters
        @JvmStatic
        fun newInstance(param1: String, param2: String) =
            ranking().apply {
                arguments = Bundle().apply {
                    putString(ARG_PARAM1, param1)
                    putString(ARG_PARAM2, param2)
                }
            }
    }
}