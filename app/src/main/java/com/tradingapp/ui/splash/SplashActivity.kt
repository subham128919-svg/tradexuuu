package com.tradingapp.ui.splash

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tradingapp.databinding.ActivitySplashBinding
import com.tradingapp.ui.auth.LoginActivity
import com.tradingapp.ui.main.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@AndroidEntryPoint
class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        lifecycleScope.launch {
            delay(2200)
            // Check if user is already logged in
            val prefs = getSharedPreferences("tradingapp_prefs", Context.MODE_PRIVATE)
            val token = prefs.getString("jwt_token", null)
            val dest  = if (!token.isNullOrEmpty()) MainActivity::class.java else LoginActivity::class.java
            startActivity(Intent(this@SplashActivity, dest)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        }
    }
}
