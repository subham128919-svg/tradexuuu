package com.tradingapp.ui.auth

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tradingapp.databinding.ActivitySignupBinding
import com.tradingapp.ui.kyc.KycUploadActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class SignupActivity : AppCompatActivity() {

    private lateinit var b: ActivitySignupBinding
    private val vm: AuthViewModel by viewModels()

    // Store signup data temporarily
    private var signupName  = ""
    private var signupEmail = ""
    private var signupPhone = ""
    private var signupPass  = ""

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        b = ActivitySignupBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.ivBack.setOnClickListener { finish() }
        b.tvLogin.setOnClickListener { finish() }

        b.btnSignup.setOnClickListener {
            signupName  = b.etName.text.toString().trim()
            signupEmail = b.etEmail.text.toString().trim()
            signupPhone = b.etPhone.text.toString().trim().replace("+91", "").replace(" ", "")
            signupPass  = b.etPassword.text.toString()

            if (signupName.isEmpty() || signupEmail.isEmpty() || signupPass.isEmpty()) {
                showError("Name, email and password are required"); return@setOnClickListener
            }
            if (signupPhone.length < 10) {
                showError("Enter a valid 10-digit phone number"); return@setOnClickListener
            }
            if (signupPass.length < 6) {
                showError("Password must be at least 6 characters"); return@setOnClickListener
            }

            // Step 1: Send OTP to phone for verification
            b.btnSignup.isEnabled = false
            b.btnSignup.text = "Sending OTP…"
            hideError()

            lifecycleScope.launch {
                val result = vm.sendOtp(signupPhone, "signup")
                b.btnSignup.isEnabled = true
                b.btnSignup.text = "Create Account"

                result.onSuccess { maskedPhone ->
                    // Navigate to OTP screen
                    val intent = Intent(this@SignupActivity, OtpVerifyActivity::class.java).apply {
                        putExtra("phone", signupPhone)
                        putExtra("phone_masked", maskedPhone)
                        putExtra("purpose", "signup")
                    }
                    startActivityForResult(intent, REQ_OTP_SIGNUP)
                }.onFailure { e ->
                    showError(e.message ?: "Failed to send OTP")
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQ_OTP_SIGNUP && resultCode == RESULT_OK) {
            // Step 2: OTP verified → Register the user
            b.btnSignup.isEnabled = false
            b.btnSignup.text = "Creating account…"

            lifecycleScope.launch {
                val result = vm.register(signupName, signupEmail, signupPhone, signupPass)
                b.btnSignup.isEnabled = true
                b.btnSignup.text = "Create Account"

                result.onSuccess { (token, userName, userId) ->
                    // Save token
                    getSharedPreferences("tradingapp_prefs", Context.MODE_PRIVATE).edit()
                        .putString("jwt_token", token)
                        .putString("user_name", userName)
                        .putString("user_email", signupEmail)
                        .apply()

                    // Step 3: Navigate to KYC document upload
                    val intent = Intent(this@SignupActivity, KycUploadActivity::class.java).apply {
                        putExtra("from_signup", true)
                    }
                    startActivityForResult(intent, REQ_KYC)
                }.onFailure { e ->
                    showError(e.message ?: "Registration failed")
                }
            }
        }

        if (requestCode == REQ_KYC) {
            // KYC flow complete (submitted or skipped) — go to main app
            val intent = Intent(this, com.tradingapp.ui.main.MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            startActivity(intent)
        }
    }

    private fun showError(msg: String) {
        b.tvError.text = msg; b.tvError.visibility = View.VISIBLE
    }
    private fun hideError() { b.tvError.visibility = View.GONE }

    companion object {
        const val REQ_OTP_SIGNUP = 2001
        const val REQ_KYC = 2002
    }
}
