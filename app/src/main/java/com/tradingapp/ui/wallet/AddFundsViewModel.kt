package com.tradingapp.ui.wallet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tradingapp.data.api.ApiService
import com.tradingapp.data.api.RazorpayOrderResponse
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AddFundsViewModel @Inject constructor(private val api: ApiService) : ViewModel() {

    private val _balance = MutableStateFlow(0.0)
    val balance: StateFlow<Double> = _balance

    fun loadBalance() = viewModelScope.launch {
        try {
            val r = api.getWallet()
            if (r.isSuccessful) _balance.value = r.body()?.balance ?: 0.0
        } catch (_: Exception) {}
    }

    // Step 1: ask the backend to create a Razorpay order for this
    // amount. Nothing is credited yet — that only happens after the
    // user actually completes checkout AND we verify the signature.
    suspend fun createOrder(amount: Double): Result<RazorpayOrderResponse> = try {
        val r = api.createRazorpayOrder(mapOf("amount" to amount))
        if (r.isSuccessful && r.body() != null) Result.success(r.body()!!)
        else Result.failure(Exception(r.errorBody()?.string() ?: "Could not start payment (${r.code()})"))
    } catch (e: Exception) {
        Result.failure(e)
    }

    // Step 2: after Razorpay Checkout reports success, send the three
    // values back for server-side signature verification — this is
    // the ONLY step that actually credits the wallet.
    suspend fun verifyPayment(
        orderId: String, paymentId: String, signature: String
    ): Result<Double> = try {
        val body = mapOf(
            "razorpay_order_id" to orderId,
            "razorpay_payment_id" to paymentId,
            "razorpay_signature" to signature
        )
        val r = api.verifyRazorpayPayment(body)
        if (r.isSuccessful && r.body() != null) {
            _balance.value = r.body()!!.newBalance
            Result.success(r.body()!!.newBalance)
        } else {
            Result.failure(Exception(r.errorBody()?.string() ?: "Payment verification failed"))
        }
    } catch (e: Exception) {
        Result.failure(e)
    }
}
