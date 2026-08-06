package com.tradingapp.ui.auth

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tradingapp.databinding.ActivitySignupBinding
import com.tradingapp.ui.main.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class SignupActivity : AppCompatActivity() {

    private lateinit var b: ActivitySignupBinding
    private val vm: AuthViewModel by viewModels()

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        b = ActivitySignupBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.ivBack.setOnClickListener { finish() }
        b.tvLogin.setOnClickListener { finish() }

        b.btnSignup.setOnClickListener {
            val name  = b.etName.text.toString().trim()
            val email = b.etEmail.text.toString().trim()
            val phone = b.etPhone.text.toString().trim()
            val pass  = b.etPassword.text.toString()
            if (name.isEmpty() || email.isEmpty() || pass.isEmpty()) {
                showError("Name, email and password are required"); return@setOnClickListener
            }
            if (pass.length < 6) { showError("Password must be at least 6 characters"); return@setOnClickListener }
            b.btnSignup.isEnabled = false
            lifecycleScope.launch {
                val result = vm.register(name, email, phone, pass)
                b.btnSignup.isEnabled = true
                if (result.isSuccess) {
                    val (token, userName) = result.getOrNull()!!
                    getSharedPreferences("tradingapp_prefs", Context.MODE_PRIVATE).edit()
                        .putString("jwt_token", token).putString("user_name", userName).apply()
                    startActivity(Intent(this@SignupActivity, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                } else {
                    showError(result.exceptionOrNull()?.message ?: "Registration failed")
                }
            }
        }
    }

    private fun showError(msg: String) {
        b.tvError.text = msg; b.tvError.visibility = View.VISIBLE
    }
}
