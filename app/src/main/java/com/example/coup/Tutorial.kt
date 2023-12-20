package com.example.coup

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView

class Tutorial : AppCompatActivity() {

    private lateinit var numberTextView: TextView
    private lateinit var tutorialImage: ImageView
    private lateinit var backButton: Button
    private lateinit var nextButton: Button
    private lateinit var okButton: Button
    private var pageNumber = 1
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tutorial)

        numberTextView = findViewById(R.id.textViewNumber_tutorial)
        tutorialImage = findViewById(R.id.imageView_tutorial)
        backButton = findViewById(R.id.backButton_tutorial)
        nextButton = findViewById(R.id.nextButton_tutorial)
        okButton = findViewById(R.id.okButton_tutorial)

        tutorialImage.setImageResource(R.drawable.tutorial1)
        backButton.setOnClickListener {
            if(pageNumber > 1) {
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
        when(pageNumber) {
            2->tutorialImage.setImageResource(R.drawable.tutorial2)
            3->tutorialImage.setImageResource(R.drawable.tutorial3)
            4->tutorialImage.setImageResource(R.drawable.tutorial4)
            5->tutorialImage.setImageResource(R.drawable.tutorial5)
            6->tutorialImage.setImageResource(R.drawable.tutorial6)
            7->tutorialImage.setImageResource(R.drawable.tutorial7)
            8->tutorialImage.setImageResource(R.drawable.tutorial8)
            else->tutorialImage.setImageResource(R.drawable.tutorial1)
        }
    }
}