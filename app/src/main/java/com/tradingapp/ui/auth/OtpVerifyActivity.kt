package com.tradingapp.ui.auth

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.CountDownTimer
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tradingapp.databinding.ActivityOtpVerifyBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class OtpVerifyActivity : AppCompatActivity() {

    private lateinit var b: ActivityOtpVerifyBinding
    private val vm: AuthViewModel by viewModels()

    private val phone       by lazy { intent.getStringExtra("phone") ?: "" }
    private val phoneMasked by lazy { intent.getStringExtra("phone_masked") ?: phone }
    private val purpose     by lazy { intent.getStringExtra("purpose") ?: "login" }

    private lateinit var otpFields: List<EditText>
    private var timer: CountDownTimer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityOtpVerifyBinding.inflate(layoutInflater)
        setContentView(b.root)

        // Setup masked phone display
        b.tvPhoneMasked.text = "+91 ${phoneMasked}"

        otpFields = listOf(b.otp1, b.otp2, b.otp3, b.otp4, b.otp5, b.otp6)
        setupOtpInputs()
        startResendTimer()

        b.btnVerify.setOnClickListener { verifyOtp() }

        b.tvResend.setOnClickListener {
            if (b.tvResend.isEnabled) resendOtp()
        }

        b.tvChangePhone.setOnClickListener { finish() }

        // Auto-focus first field
        b.otp1.requestFocus()
    }

    private fun setupOtpInputs() {
        for (i in otpFields.indices) {
            val field = otpFields[i]

            field.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (s?.length == 1 && i < otpFields.lastIndex) {
                        otpFields[i + 1].requestFocus()
                    }
                    // Auto-verify when all 6 digits entered
                    if (getOtp().length == 6) {
                        verifyOtp()
                    }
                }
            })

            field.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_DEL && event.action == KeyEvent.ACTION_DOWN
                    && field.text.isEmpty() && i > 0) {
                    otpFields[i - 1].apply {
                        requestFocus()
                        text.clear()
                    }
                    true
                } else false
            }
        }
    }

    private fun getOtp(): String = otpFields.joinToString("") { it.text.toString() }

    private fun verifyOtp() {
        val otp = getOtp()
        if (otp.length != 6) {
            showError("Please enter complete 6-digit OTP")
            return
        }

        b.btnVerify.isEnabled = false
        b.btnVerify.text = "Verifying…"
        hideError()

        lifecycleScope.launch {
            val result = vm.verifyOtp(phone, otp, purpose)

            result.onSuccess { data ->
                if (purpose == "login") {
                    // For login: OTP verify returns token directly
                    val token    = data["token"] as? String
                    val userName = data["userName"] as? String ?: ""
                    val userEmail = data["userEmail"] as? String ?: ""

                    if (token != null) {
                        val resultIntent = Intent().apply {
                            putExtra("token", token)
                            putExtra("user_name", userName)
                            putExtra("user_email", userEmail)
                        }
                        setResult(RESULT_OK, resultIntent)
                        finish()
                    } else {
                        // Fallback: call login-phone endpoint
                        val loginResult = vm.loginWithPhone(phone)
                        b.btnVerify.isEnabled = true
                        b.btnVerify.text = "Verify OTP"
                        loginResult.onSuccess { (token2, name, email) ->
                            val resultIntent = Intent().apply {
                                putExtra("token", token2)
                                putExtra("user_name", name)
                                putExtra("user_email", email)
                            }
                            setResult(RESULT_OK, resultIntent)
                            finish()
                        }.onFailure { e ->
                            showError(e.message ?: "Login failed")
                        }
                    }
                } else {
                    // For signup: just confirm verified
                    setResult(RESULT_OK)
                    finish()
                }
            }.onFailure { e ->
                b.btnVerify.isEnabled = true
                b.btnVerify.text = "Verify OTP"
                showError(e.message ?: "Invalid OTP")
                // Clear all fields
                otpFields.forEach { it.text.clear() }
                otpFields[0].requestFocus()
            }
        }
    }

    private fun resendOtp() {
        b.tvResend.isEnabled = false
        lifecycleScope.launch {
            val result = vm.sendOtp(phone, purpose)
            result.onSuccess {
                Toast.makeText(this@OtpVerifyActivity, "OTP sent again", Toast.LENGTH_SHORT).show()
                startResendTimer()
            }.onFailure { e ->
                b.tvResend.isEnabled = true
                showError(e.message ?: "Failed to resend")
            }
        }
    }

    private fun startResendTimer() {
        timer?.cancel()
        b.tvResend.isEnabled = false
        b.tvResend.setTextColor(Color.parseColor("#6D5EF8"))

        timer = object : CountDownTimer(30_000, 1000) {
            override fun onTick(millis: Long) {
                val secs = millis / 1000
                b.tvResend.text = "Resend OTP in %02d:%02d".format(secs / 60, secs % 60)
            }
            override fun onFinish() {
                b.tvResend.text = "Resend OTP"
                b.tvResend.isEnabled = true
            }
        }.start()
    }

    private fun showError(msg: String) {
        b.tvError.text = msg; b.tvError.visibility = View.VISIBLE
    }
    private fun hideError() { b.tvError.visibility = View.GONE }

    override fun onDestroy() {
        super.onDestroy()
        timer?.cancel()
    }
}
