package com.tradingapp.ui.kyc

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tradingapp.databinding.ActivityKycUploadBinding
import com.tradingapp.ui.auth.AuthViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

@AndroidEntryPoint
class KycUploadActivity : AppCompatActivity() {

    private lateinit var b: ActivityKycUploadBinding
    private val vm: AuthViewModel by viewModels()

    private val fromSignup by lazy { intent.getBooleanExtra("from_signup", false) }

    // File URIs
    private var panFrontUri: Uri? = null
    private var panBackUri: Uri? = null
    private var aadhaarFrontUri: Uri? = null
    private var aadhaarBackUri: Uri? = null

    private var currentPick = 0 // 1=panFront, 2=panBack, 3=aadhaarFront, 4=aadhaarBack

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityKycUploadBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.ivBack.setOnClickListener { finish() }

        // PAN card uploads
        b.boxPanFront.setOnClickListener { pickImage(1) }
        b.boxPanBack.setOnClickListener  { pickImage(2) }

        // Aadhaar uploads
        b.boxAadhaarFront.setOnClickListener { pickImage(3) }
        b.boxAadhaarBack.setOnClickListener  { pickImage(4) }

        // Proceed to preview
        b.btnProceed.setOnClickListener { validateAndProceed() }
    }

    private fun pickImage(which: Int) {
        currentPick = which
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        intent.type = "image/*"
        startActivityForResult(intent, PICK_IMAGE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_IMAGE && resultCode == Activity.RESULT_OK) {
            val uri = data?.data ?: return
            when (currentPick) {
                1 -> { panFrontUri = uri;     updateBox(b.ivPanFront, b.tvPanFrontLabel, uri) }
                2 -> { panBackUri = uri;      updateBox(b.ivPanBack, b.tvPanBackLabel, uri) }
                3 -> { aadhaarFrontUri = uri;  updateBox(b.ivAadhaarFront, b.tvAadhaarFrontLabel, uri) }
                4 -> { aadhaarBackUri = uri;   updateBox(b.ivAadhaarBack, b.tvAadhaarBackLabel, uri) }
            }
        }

        if (requestCode == REQ_PREVIEW && resultCode == RESULT_OK) {
            // Preview confirmed → upload to server
            uploadDocuments()
        }
    }

    private fun updateBox(imageView: ImageView, label: TextView, uri: Uri) {
        imageView.setImageURI(uri)
        imageView.visibility = View.VISIBLE
        label.text = "✓ Uploaded"
        label.setTextColor(0xFF2FBF71.toInt())
    }

    private fun validateAndProceed() {
        val panNum     = b.etPanNumber.text.toString().trim().uppercase()
        val aadhaarNum = b.etAadhaarNumber.text.toString().trim().replace(" ", "")

        if (panFrontUri == null || panBackUri == null) {
            showError("Please upload both sides of your PAN card"); return
        }
        if (aadhaarFrontUri == null || aadhaarBackUri == null) {
            showError("Please upload both sides of your Aadhaar card"); return
        }
        if (!panNum.matches(Regex("[A-Z]{5}[0-9]{4}[A-Z]"))) {
            showError("Invalid PAN number format (e.g., ABCDE1234F)"); return
        }
        if (!aadhaarNum.matches(Regex("\\d{12}"))) {
            showError("Invalid Aadhaar number (must be 12 digits)"); return
        }

        hideError()

        // Navigate to preview screen
        val intent = Intent(this, KycPreviewActivity::class.java).apply {
            putExtra("pan_number", panNum)
            putExtra("aadhaar_number", aadhaarNum)
            putExtra("pan_front_uri", panFrontUri.toString())
            putExtra("pan_back_uri", panBackUri.toString())
            putExtra("aadhaar_front_uri", aadhaarFrontUri.toString())
            putExtra("aadhaar_back_uri", aadhaarBackUri.toString())
        }
        startActivityForResult(intent, REQ_PREVIEW)
    }

    private fun uploadDocuments() {
        val panNum     = b.etPanNumber.text.toString().trim().uppercase()
        val aadhaarNum = b.etAadhaarNumber.text.toString().trim().replace(" ", "")

        b.btnProceed.isEnabled = false
        b.btnProceed.text = "Uploading…"

        lifecycleScope.launch {
            try {
                val panFrontFile     = uriToFile(panFrontUri!!, "pan_front")
                val panBackFile      = uriToFile(panBackUri!!, "pan_back")
                val aadhaarFrontFile = uriToFile(aadhaarFrontUri!!, "aadhaar_front")
                val aadhaarBackFile  = uriToFile(aadhaarBackUri!!, "aadhaar_back")

                val result = vm.uploadKyc(
                    panFrontFile, panBackFile,
                    aadhaarFrontFile, aadhaarBackFile,
                    panNum, aadhaarNum
                )

                b.btnProceed.isEnabled = true
                b.btnProceed.text = "Proceed to Preview"

                result.onSuccess { msg ->
                    Toast.makeText(this@KycUploadActivity, msg, Toast.LENGTH_LONG).show()
                    setResult(RESULT_OK)
                    finish()
                }.onFailure { e ->
                    showError(e.message ?: "Upload failed")
                }
            } catch (e: Exception) {
                b.btnProceed.isEnabled = true
                b.btnProceed.text = "Proceed to Preview"
                showError(e.message ?: "Error preparing files")
            }
        }
    }

    private fun uriToFile(uri: Uri, prefix: String): File {
        val inputStream = contentResolver.openInputStream(uri)!!
        val file = File(cacheDir, "${prefix}_${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use { out -> inputStream.copyTo(out) }
        inputStream.close()
        return file
    }

    private fun showError(msg: String) {
        b.tvError.text = msg; b.tvError.visibility = View.VISIBLE
    }
    private fun hideError() { b.tvError.visibility = View.GONE }

    companion object {
        const val PICK_IMAGE = 3001
        const val REQ_PREVIEW = 3002
    }
}
