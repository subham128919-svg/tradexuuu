package com.tradingapp.ui.auth

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.tradingapp.databinding.ActivityLoginBinding
import com.tradingapp.ui.main.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class LoginActivity : AppCompatActivity() {

    private lateinit var b: ActivityLoginBinding
    private val vm: AuthViewModel by viewModels()

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        b = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(b.root)

        // Keep the form above the keyboard and out of system bars/cutouts.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, b.root).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            b.loginViewport.keyboardVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            insets
        }
        ViewCompat.requestApplyInsets(b.root)

        b.tvSignUp.setOnClickListener {
            startActivity(Intent(this, SignupActivity::class.java))
        }

        b.btnLogin.setOnClickListener {
            val phone = b.etPhone.text.toString().trim().replace("+91", "").replace(" ", "")
            if (phone.length < 10) {
                showError("Please enter a valid 10-digit phone number")
                return@setOnClickListener
            }

            b.btnLogin.isEnabled = false
            b.btnLogin.text = "Sending OTP…"
            hideError()

            lifecycleScope.launch {
                val result = vm.sendOtp(phone, "login")
                b.btnLogin.isEnabled = true
                b.btnLogin.text = "Get OTP"

                result.onSuccess { maskedPhone ->
                    // Navigate to OTP screen
                    val intent = Intent(this@LoginActivity, OtpVerifyActivity::class.java).apply {
                        putExtra("phone", phone)
                        putExtra("phone_masked", maskedPhone)
                        putExtra("purpose", "login")
                    }
                    startActivityForResult(intent, REQ_OTP_LOGIN)
                }.onFailure { e ->
                    showError(e.message ?: "Failed to send OTP")
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_OTP_LOGIN && resultCode == RESULT_OK) {
            // OTP verified + login successful
            val token    = data?.getStringExtra("token") ?: return
            val userName = data.getStringExtra("user_name") ?: ""
            val userEmail = data.getStringExtra("user_email") ?: ""

            getSharedPreferences("tradingapp_prefs", Context.MODE_PRIVATE).edit()
                .putString("jwt_token", token)
                .putString("user_name", userName)
                .putString("user_email", userEmail)
                .apply()

            startActivity(Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        }
    }

    private fun showError(msg: String) {
        b.tvError.text = msg; b.tvError.visibility = View.VISIBLE
    }
    private fun hideError() { b.tvError.visibility = View.GONE }

    companion object {
        const val REQ_OTP_LOGIN = 1001
    }
}
