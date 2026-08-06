package com.tradingapp.ui.auth

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
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

        b.tvSignUp.setOnClickListener {
            startActivity(Intent(this, SignupActivity::class.java))
        }

        b.btnLogin.setOnClickListener {
            val email = b.etEmail.text.toString().trim()
            val pass  = b.etPassword.text.toString()
            if (email.isEmpty() || pass.isEmpty()) {
                showError("Please enter email and password"); return@setOnClickListener
            }
            b.btnLogin.isEnabled = false
            lifecycleScope.launch {
                val result = vm.login(email, pass)
                b.btnLogin.isEnabled = true
                if (result.isSuccess) {
                    saveToken(result.getOrNull()!!.first, result.getOrNull()!!.second)
                    goHome()
                } else {
                    showError(result.exceptionOrNull()?.message ?: "Login failed")
                }
            }
        }
    }

    private fun showError(msg: String) {
        b.tvError.text = msg; b.tvError.visibility = View.VISIBLE
    }

    private fun saveToken(token: String, userName: String) {
        getSharedPreferences("tradingapp_prefs", Context.MODE_PRIVATE).edit()
            .putString("jwt_token", token)
            .putString("user_name", userName)
            .apply()
    }

    private fun goHome() {
        startActivity(Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
    }
}
