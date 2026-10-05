package com.tradingapp.ui.auth

import android.net.Uri
import androidx.lifecycle.ViewModel
import com.tradingapp.data.api.ApiService
import dagger.hilt.android.lifecycle.HiltViewModel
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import javax.inject.Inject

@HiltViewModel
class AuthViewModel @Inject constructor(private val api: ApiService) : ViewModel() {

    // ── Existing: Email/password login ───────────────────────────
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

    // ── Existing: Register ──────────────────────────────────────
    suspend fun register(name: String, email: String, phone: String, password: String): Result<Triple<String, String, Int>> = try {
        val body = mapOf("name" to name, "email" to email, "phone" to phone, "password" to password)
        val r    = api.register(body)
        if (r.isSuccessful) {
            val resp = r.body()!!
            Result.success(Triple(resp.token, resp.user.name, resp.user.id))
        } else {
            Result.failure(Exception(r.errorBody()?.string() ?: "Registration failed"))
        }
    } catch (e: Exception) {
        Result.failure(Exception(e.message ?: "Network error"))
    }

    // ── NEW: Send OTP ───────────────────────────────────────────
    suspend fun sendOtp(phone: String, purpose: String): Result<String> = try {
        val r = api.sendOtp(mapOf("phone" to phone, "purpose" to purpose))
        if (r.isSuccessful && r.body()?.ok == true) {
            Result.success(r.body()!!.phoneMasked ?: phone)
        } else {
            val err = r.errorBody()?.string() ?: "Failed to send OTP"
            Result.failure(Exception(err))
        }
    } catch (e: Exception) {
        Result.failure(Exception(e.message ?: "Network error"))
    }

    // ── NEW: Verify OTP ─────────────────────────────────────────
    suspend fun verifyOtp(phone: String, otp: String, purpose: String): Result<Map<String, Any?>> = try {
        val r = api.verifyOtp(mapOf("phone" to phone, "otp" to otp, "purpose" to purpose))
        if (r.isSuccessful) {
            val body = r.body()!!
            val map = mutableMapOf<String, Any?>(
                "ok" to body.ok,
                "verified" to body.verified
            )
            // For login purpose, token and user are returned
            if (body.token != null) map["token"] = body.token
            if (body.user != null) {
                map["userName"] = body.user.name
                map["userEmail"] = body.user.email
                map["userId"] = body.user.id
            }
            Result.success(map)
        } else {
            Result.failure(Exception(r.errorBody()?.string() ?: "OTP verification failed"))
        }
    } catch (e: Exception) {
        Result.failure(Exception(e.message ?: "Network error"))
    }

    // ── NEW: Phone login (after OTP verified) ───────────────────
    suspend fun loginWithPhone(phone: String): Result<Triple<String, String, String>> = try {
        val r = api.loginWithPhone(mapOf("phone" to phone))
        if (r.isSuccessful) {
            val body = r.body()!!
            Result.success(Triple(body.token, body.user.name, body.user.email))
        } else {
            Result.failure(Exception(r.errorBody()?.string() ?: "Login failed"))
        }
    } catch (e: Exception) {
        Result.failure(Exception(e.message ?: "Network error"))
    }

    // ── NEW: Upload KYC Documents ───────────────────────────────
    suspend fun uploadKyc(
        panFrontFile: File, panBackFile: File,
        aadhaarFrontFile: File, aadhaarBackFile: File,
        panNumber: String, aadhaarNumber: String
    ): Result<String> = try {
        val mediaType = "image/*".toMediaTypeOrNull()

        val panFrontPart = MultipartBody.Part.createFormData(
            "pan_front", panFrontFile.name, panFrontFile.asRequestBody(mediaType))
        val panBackPart = MultipartBody.Part.createFormData(
            "pan_back", panBackFile.name, panBackFile.asRequestBody(mediaType))
        val aadhaarFrontPart = MultipartBody.Part.createFormData(
            "aadhaar_front", aadhaarFrontFile.name, aadhaarFrontFile.asRequestBody(mediaType))
        val aadhaarBackPart = MultipartBody.Part.createFormData(
            "aadhaar_back", aadhaarBackFile.name, aadhaarBackFile.asRequestBody(mediaType))

        val panBody     = panNumber.toRequestBody("text/plain".toMediaTypeOrNull())
        val aadhaarBody = aadhaarNumber.toRequestBody("text/plain".toMediaTypeOrNull())

        val r = api.uploadKyc(panFrontPart, panBackPart, aadhaarFrontPart, aadhaarBackPart, panBody, aadhaarBody)
        if (r.isSuccessful && r.body()?.ok == true) {
            Result.success(r.body()!!.message)
        } else {
            Result.failure(Exception(r.errorBody()?.string() ?: "Upload failed"))
        }
    } catch (e: Exception) {
        Result.failure(Exception(e.message ?: "Network error"))
    }
}
