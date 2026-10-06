package com.tradingapp.ui.kyc

import android.app.Activity
import android.content.Context
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
import com.tradingapp.data.api.TradexApi
import com.tradingapp.databinding.ActivityKycUploadBinding
import com.tradingapp.ui.auth.AuthViewModel
import com.tradingapp.ui.profile.AccountStatusActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

/**
 * FEATURE #2 — documents upload the moment they are picked.
 *
 * Before: all four images were held in memory and shipped in one big
 * multipart request after the user pressed Confirm, which is why that
 * step took so long.
 *
 * Now: each image is POSTed to /kyc/upload-doc the instant it is chosen,
 * in the background, while the user is still typing their PAN and Aadhaar
 * numbers. Confirm then calls /kyc/submit, which carries two short text
 * fields and no files, so it returns almost immediately.
 *
 * If the user leaves and comes back, /kyc/progress restores the ticks so
 * they never re-upload something the server already has.
 */
@AndroidEntryPoint
class KycUploadActivity : AppCompatActivity() {

    private lateinit var b: ActivityKycUploadBinding
    private val vm: AuthViewModel by viewModels()

    @Inject lateinit var tradexApi: TradexApi

    private val fromSignup by lazy { intent.getBooleanExtra("from_signup", false) }

    // File URIs
    private var panFrontUri: Uri? = null
    private var panBackUri: Uri? = null
    private var aadhaarFrontUri: Uri? = null
    private var aadhaarBackUri: Uri? = null

    private var currentPick = 0 // 1=panFront, 2=panBack, 3=aadhaarFront, 4=aadhaarBack

    /** Document types confirmed stored on the server. */
    private val uploaded = mutableSetOf<String>()

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

        restoreProgress()
    }

    /** Don't make the user re-pick documents the server already holds. */
    private fun restoreProgress() = lifecycleScope.launch {
        try {
            val r = tradexApi.kycProgress()
            if (!r.isSuccessful) return@launch
            val body = r.body() ?: return@launch
            val u = body.uploaded ?: return@launch

            if (u.panFront)     { uploaded.add("pan_front");     markDone(b.tvPanFrontLabel) }
            if (u.panBack)      { uploaded.add("pan_back");      markDone(b.tvPanBackLabel) }
            if (u.aadhaarFront) { uploaded.add("aadhaar_front"); markDone(b.tvAadhaarFrontLabel) }
            if (u.aadhaarBack)  { uploaded.add("aadhaar_back");  markDone(b.tvAadhaarBackLabel) }

            body.panNumber?.let { if (b.etPanNumber.text.isNullOrBlank()) b.etPanNumber.setText(it) }
            body.aadhaarNumber?.let {
                if (b.etAadhaarNumber.text.isNullOrBlank()) b.etAadhaarNumber.setText(it)
            }
        } catch (_: Exception) { /* offline — the user can simply pick again */ }
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
                1 -> { panFrontUri = uri;      updateBox(b.ivPanFront, b.tvPanFrontLabel, uri, "pan_front") }
                2 -> { panBackUri = uri;       updateBox(b.ivPanBack, b.tvPanBackLabel, uri, "pan_back") }
                3 -> { aadhaarFrontUri = uri;  updateBox(b.ivAadhaarFront, b.tvAadhaarFrontLabel, uri, "aadhaar_front") }
                4 -> { aadhaarBackUri = uri;   updateBox(b.ivAadhaarBack, b.tvAadhaarBackLabel, uri, "aadhaar_back") }
            }
        }

        if (requestCode == REQ_PREVIEW && resultCode == RESULT_OK) {
            // Preview confirmed → finalise (no files travel here any more)
            uploadDocuments()
        }
    }

    private fun updateBox(imageView: ImageView, label: TextView, uri: Uri, docType: String) {
        imageView.setImageURI(uri)
        imageView.visibility = View.VISIBLE
        label.text = "Uploading…"
        label.setTextColor(0xFF8A8A9C.toInt())
        uploaded.remove(docType)
        uploadOne(docType, uri, label)
    }

    /** Ship one document straight away, in the background. */
    private fun uploadOne(docType: String, uri: Uri, label: TextView) = lifecycleScope.launch {
        var file: File? = null
        try {
            file = uriToFile(uri, docType)
            val part = MultipartBody.Part.createFormData(
                "file", file.name, file.asRequestBody("image/*".toMediaTypeOrNull()))
            val typeBody = docType.toRequestBody("text/plain".toMediaTypeOrNull())

            val r = tradexApi.uploadKycDoc(part, typeBody)

            if (r.isSuccessful && r.body()?.ok == true) {
                uploaded.add(docType)
                markDone(label)
                hideError()
            } else {
                markRetry(label)
                showError("Could not upload that document. Tap the box to pick it again.")
            }
        } catch (e: Exception) {
            markRetry(label)
            showError(e.message ?: "Upload failed")
        } finally {
            runCatching { file?.delete() }
        }
    }

    private fun markDone(label: TextView) {
        label.text = "✓ Uploaded"
        label.setTextColor(0xFF2FBF71.toInt())
    }

    private fun markRetry(label: TextView) {
        label.text = "Tap to retry"
        label.setTextColor(0xFFD14343.toInt())
    }

    private fun validateAndProceed() {
        val panNum     = b.etPanNumber.text.toString().trim().uppercase()
        val aadhaarNum = b.etAadhaarNumber.text.toString().trim().replace(" ", "")

        val missing = listOf("pan_front", "pan_back", "aadhaar_front", "aadhaar_back")
            .filterNot { uploaded.contains(it) }

        if (missing.contains("pan_front") || missing.contains("pan_back")) {
            showError("Please upload both sides of your PAN card"); return
        }
        if (missing.contains("aadhaar_front") || missing.contains("aadhaar_back")) {
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
            putExtra("pan_front_uri", panFrontUri?.toString())
            putExtra("pan_back_uri", panBackUri?.toString())
            putExtra("aadhaar_front_uri", aadhaarFrontUri?.toString())
            putExtra("aadhaar_back_uri", aadhaarBackUri?.toString())
        }
        startActivityForResult(intent, REQ_PREVIEW)
    }

    /** Confirm step — two text fields only, so this returns in a blink. */
    private fun uploadDocuments() {
        val panNum     = b.etPanNumber.text.toString().trim().uppercase()
        val aadhaarNum = b.etAadhaarNumber.text.toString().trim().replace(" ", "")

        val pending = listOf("pan_front", "pan_back", "aadhaar_front", "aadhaar_back")
            .filterNot { uploaded.contains(it) }
        if (pending.isNotEmpty()) {
            showError("Some documents are still uploading. Please wait a moment and try again.")
            return
        }

        b.btnProceed.isEnabled = false
        b.btnProceed.text = "Submitting…"

        lifecycleScope.launch {
            try {
                val r = tradexApi.submitKyc(
                    mapOf("pan_number" to panNum, "aadhaar_number" to aadhaarNum))

                b.btnProceed.isEnabled = true
                b.btnProceed.text = "Proceed to Preview"

                if (r.isSuccessful && r.body()?.ok == true) {
                    getSharedPreferences("tradingapp_prefs", Context.MODE_PRIVATE)
                        .edit().putString("kyc_status", "pending").apply()

                    Toast.makeText(this@KycUploadActivity,
                        r.body()?.message ?: "Submitted for review", Toast.LENGTH_LONG).show()

                    if (!fromSignup) {
                        startActivity(
                            Intent(this@KycUploadActivity, AccountStatusActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
                    }
                    setResult(RESULT_OK)
                    finish()
                } else {
                    val raw = r.errorBody()?.string()
                    showError(
                        Regex("\"error\"\\s*:\\s*\"([^\"]+)\"")
                            .find(raw ?: "")?.groupValues?.get(1) ?: "Submission failed"
                    )
                }
            } catch (e: Exception) {
                b.btnProceed.isEnabled = true
                b.btnProceed.text = "Proceed to Preview"
                showError(e.message ?: "Network error")
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
