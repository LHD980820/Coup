package com.example.coup

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView

class Tutorial : AppCompatActivity() {

    private lateinit var numberTextView: TextView
    private lateinit var tutorialImage: ImageView
    private lateinit var backButton: Button
    private lateinit var nextButton: Button
    private lateinit var okButton: Button
    private var pageNumber = 0
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tutorial)

        numberTextView = findViewById(R.id.textViewNumber_tutorial)
        tutorialImage = findViewById(R.id.imageView_tutorial)
        backButton = findViewById(R.id.backButton_tutorial)
        nextButton = findViewById(R.id.nextButton_tutorial)
        okButton = findViewById(R.id.okButton_tutorial)

        tutorialImage.setImageResource(R.drawable.tutorial0)
        numberTextView.text = pageNumber.toString()
        backButton.visibility = View.INVISIBLE
        backButton.setOnClickListener {
            if(pageNumber > 0) {
                pageNumber--
                changeImage()
                numberTextView.text = pageNumber.toString()
            }
        }
        nextButton.setOnClickListener {
            if(pageNumber < 8) {
                pageNumber++
                changeImage()
                numberTextView.text = pageNumber.toString()
            }
        }
        okButton.setOnClickListener {
            finish()
        }
    }

    private fun changeImage() {
        if(pageNumber == 0) backButton.visibility = View.INVISIBLE
        else if(pageNumber == 8) nextButton.visibility = View.INVISIBLE
        else {
            backButton.visibility = View.VISIBLE
            nextButton.visibility = View.VISIBLE
        }
        when(pageNumber) {
            1->tutorialImage.setImageResource(R.drawable.tutorial1)
            2->tutorialImage.setImageResource(R.drawable.tutorial2)
            3->tutorialImage.setImageResource(R.drawable.tutorial3)
            4->tutorialImage.setImageResource(R.drawable.tutorial4)
            5->tutorialImage.setImageResource(R.drawable.tutorial5)
            6->tutorialImage.setImageResource(R.drawable.tutorial6)
            7->tutorialImage.setImageResource(R.drawable.tutorial7)
            8->tutorialImage.setImageResource(R.drawable.tutorial8)
            else->tutorialImage.setImageResource(R.drawable.tutorial0)
        }
    }
}