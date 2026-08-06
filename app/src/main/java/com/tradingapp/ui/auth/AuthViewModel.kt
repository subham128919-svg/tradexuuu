package com.tradingapp.ui.auth

import androidx.lifecycle.ViewModel
import com.tradingapp.data.api.ApiService
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class AuthViewModel @Inject constructor(private val api: ApiService) : ViewModel() {

    suspend fun login(email: String, password: String): Result<Pair<String, String>> = try {
        val r = api.login(mapOf("email" to email, "password" to password))
        if (r.isSuccessful) {
            val body = r.body()!!
            Result.success(Pair(body.token, body.user.name))
        } else {
            val err = r.errorBody()?.string() ?: "Login failed (${r.code()})"
            Result.failure(Exception(err))
        }
    } catch (e: Exception) {
        Result.failure(Exception(e.message ?: "Network error"))
    }

    suspend fun register(name: String, email: String, phone: String, password: String): Result<Pair<String, String>> = try {
        val body = mapOf("name" to name, "email" to email, "phone" to phone, "password" to password)
        val r    = api.register(body)
        if (r.isSuccessful) {
            val resp = r.body()!!
            Result.success(Pair(resp.token, resp.user.name))
        } else {
            Result.failure(Exception(r.errorBody()?.string() ?: "Registration failed"))
        }
    } catch (e: Exception) {
        Result.failure(Exception(e.message ?: "Network error"))
    }
}
