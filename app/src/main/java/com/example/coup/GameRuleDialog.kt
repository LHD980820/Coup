package com.example.coup

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView

class GameRuleDialog(context: Context): Dialog(context) {

    private lateinit var buttonComponents: Button
    private lateinit var buttonPreparation: Button
    private lateinit var buttonObject: Button
    private lateinit var buttonCardAbilities: Button
    private lateinit var buttonGamePlay: Button
    private lateinit var buttonChallenge: Button
    private lateinit var buttonSummaryTable: Button

    private lateinit var textViewComponents: TextView
    private lateinit var textViewPreparation: TextView
    private lateinit var textViewObject: TextView
    private lateinit var textViewCardAbilities: TextView
    private lateinit var textViewGamePlay1: TextView
    private lateinit var textViewGamePlay2: TextView
    private lateinit var textViewGamePlay3: TextView
    private lateinit var textViewChallenge1: TextView
    private lateinit var textViewChallenge2: TextView
    private lateinit var textViewChallenge3: TextView
    private lateinit var imageViewSummaryTable: ImageView
    private lateinit var buttonCancelDialog: ImageButton
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setCanceledOnTouchOutside(true)
        setCancelable(true)
        setContentView(R.layout.dialog_game_rule)
        init()
    }

    private fun init() {
        buttonComponents = findViewById(R.id.button_components)
        buttonPreparation = findViewById(R.id.button_preparation)
        buttonObject = findViewById(R.id.button_game_object)
        buttonCardAbilities = findViewById(R.id.button_card_abilities)
        buttonGamePlay = findViewById(R.id.button_game_play)
        buttonChallenge = findViewById(R.id.button_challenge_rule_book)
        buttonSummaryTable = findViewById(R.id.button_summary_table)
        buttonCancelDialog = findViewById(R.id.game_rule_cancel)

        textViewComponents = findViewById(R.id.textView_components_explanation)
        textViewPreparation = findViewById(R.id.textView_preparation_explanation)
        textViewObject = findViewById(R.id.textView_game_object_explanation)
        textViewCardAbilities = findViewById(R.id.textView_card_abilities_explanation)
        textViewGamePlay1 = findViewById(R.id.textView_game_play1)
        textViewGamePlay2 = findViewById(R.id.textView_game_play2)
        textViewGamePlay3 = findViewById(R.id.textView_game_play3)
        textViewChallenge1 = findViewById(R.id.textView_challenge_rule_book1)
        textViewChallenge2 = findViewById(R.id.textView_challenge_rule_book2)
        textViewChallenge3 = findViewById(R.id.textView_challenge_rule_book3)
        imageViewSummaryTable = findViewById(R.id.imageView_summary_table)

        buttonComponents.setOnClickListener {
            if(textViewComponents.visibility == View.GONE) {
                buttonComponents.rotation = 0F
                textViewComponents.visibility = View.VISIBLE
            }
            else {
                buttonComponents.rotation = -90F
                textViewComponents.visibility = View.GONE
            }
        }
        buttonPreparation.setOnClickListener {
            if(textViewPreparation.visibility == View.GONE) {
                buttonPreparation.rotation = 0F
                textViewPreparation.visibility = View.VISIBLE
            }
            else {
                buttonPreparation.rotation = -90F
                textViewPreparation.visibility = View.GONE
            }
        }
        buttonObject.setOnClickListener {
            if(textViewObject.visibility == View.GONE) {
                buttonObject.rotation = 0F
                textViewObject.visibility = View.VISIBLE
            }
            else {
                buttonObject.rotation = -90F
                textViewObject.visibility = View.GONE
            }
        }
        buttonCardAbilities.setOnClickListener {
            if(textViewCardAbilities.visibility == View.GONE) {
                buttonCardAbilities.rotation = 0F
                textViewCardAbilities.visibility = View.VISIBLE
            }
            else {
                buttonCardAbilities.rotation = -90F
                textViewCardAbilities.visibility = View.GONE
            }
        }
        buttonGamePlay.setOnClickListener {
            if(textViewGamePlay1.visibility == View.GONE) {
                buttonGamePlay.rotation = 0F
                textViewGamePlay1.visibility = View.VISIBLE
                textViewGamePlay2.visibility = View.VISIBLE
                textViewGamePlay3.visibility = View.VISIBLE
            }
            else {
                buttonGamePlay.rotation = -90F
                textViewGamePlay1.visibility = View.GONE
                textViewGamePlay2.visibility = View.GONE
                textViewGamePlay3.visibility = View.GONE
            }
        }
        buttonChallenge.setOnClickListener {
            if(textViewChallenge1.visibility == View.GONE) {
                buttonChallenge.rotation = 0F
                textViewChallenge1.visibility = View.VISIBLE
                textViewChallenge2.visibility = View.VISIBLE
                textViewChallenge3.visibility = View.VISIBLE
            }
            else {
                buttonChallenge.rotation = -90F
                textViewChallenge1.visibility = View.GONE
                textViewChallenge2.visibility = View.GONE
                textViewChallenge3.visibility = View.GONE
            }
        }
        buttonSummaryTable.setOnClickListener {
            if(imageViewSummaryTable.visibility == View.GONE) {
                buttonSummaryTable.rotation = 0F
                imageViewSummaryTable.visibility = View.VISIBLE
            }
            else {
                buttonSummaryTable.rotation = -90F
                imageViewSummaryTable.visibility = View.GONE
            }
        }

        buttonCancelDialog.setOnClickListener {
            dismiss()
        }
    }
}