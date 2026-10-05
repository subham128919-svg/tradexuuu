package com.tradingapp.ui.kyc

import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.tradingapp.databinding.ActivityKycPreviewBinding

class KycPreviewActivity : AppCompatActivity() {

    private lateinit var b: ActivityKycPreviewBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityKycPreviewBinding.inflate(layoutInflater)
        setContentView(b.root)

        val panNumber     = intent.getStringExtra("pan_number") ?: ""
        val aadhaarNumber = intent.getStringExtra("aadhaar_number") ?: ""
        val panFrontUri     = intent.getStringExtra("pan_front_uri")
        val panBackUri      = intent.getStringExtra("pan_back_uri")
        val aadhaarFrontUri = intent.getStringExtra("aadhaar_front_uri")
        val aadhaarBackUri  = intent.getStringExtra("aadhaar_back_uri")

        // Display data
        b.tvPanNumber.text = panNumber
        b.tvAadhaarNumber.text = aadhaarNumber.chunked(4).joinToString(" ")

        // Display images
        if (panFrontUri != null) b.ivPanFront.setImageURI(Uri.parse(panFrontUri))
        if (panBackUri != null)  b.ivPanBack.setImageURI(Uri.parse(panBackUri))
        if (aadhaarFrontUri != null) b.ivAadhaarFront.setImageURI(Uri.parse(aadhaarFrontUri))
        if (aadhaarBackUri != null)  b.ivAadhaarBack.setImageURI(Uri.parse(aadhaarBackUri))

        b.ivBack.setOnClickListener { finish() }

        b.btnConfirm.setOnClickListener {
            setResult(RESULT_OK)
            finish()
        }

        b.btnEdit.setOnClickListener {
            // Go back to edit
            finish()
        }
    }
}
