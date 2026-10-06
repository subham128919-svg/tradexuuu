package com.tradingapp.data.api

import com.google.gson.annotations.SerializedName
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.*

/**
 * All endpoints added by the features 1–8 patch.
 *
 * This is a SEPARATE interface from ApiService on purpose: it is built
 * from the very same Retrofit instance (see di/TradexApiModule.kt), so it
 * shares the base URL, the JWT interceptor and the OkHttp client — but
 * your existing ApiService.kt file does not need a single edit.
 */

// ── KYC (progressive upload) ─────────────────────────────────────
data class KycDocUploadResponse(
    val ok: Boolean,
    val docType: String?,
    val url: String?,
    val message: String?
)

data class KycUploadedFlags(
    @SerializedName("pan_front")     val panFront: Boolean = false,
    @SerializedName("pan_back")      val panBack: Boolean = false,
    @SerializedName("aadhaar_front") val aadhaarFront: Boolean = false,
    @SerializedName("aadhaar_back")  val aadhaarBack: Boolean = false
)

data class KycProgressResponse(
    val kycStatus: String?,
    val panNumber: String?,
    val aadhaarNumber: String?,
    val uploaded: KycUploadedFlags?,
    val allUploaded: Boolean = false
)

data class KycSubmitResponse(
    val ok: Boolean,
    val kycStatus: String?,
    val message: String?
)

data class KycStatusFull(
    val kycStatus: String?,
    val panNumber: String?,
    val aadhaarMasked: String?,
    val boDematNumber: String?,
    val submittedAt: String?,
    val remarks: String?
)

// ── Settings (support / links) ───────────────────────────────────
data class SupportDetails(
    val title: String?,
    val subtitle: String?,
    val whatsapp: String?,
    val whatsappText: String?,
    val whatsappUrl: String?,
    val phone: String?,
    val email: String?,
    val hours: String?,
    val address: String?,
    val note: String?
)

data class AppLinks(val about: String?, val charges: String?)

// ── Referrals ────────────────────────────────────────────────────
data class ReferralEntry(
    val name: String?,
    val status: String?,
    val kycStatus: String?,
    val reward: Double = 0.0,
    val joinedAt: String?,
    val paidAt: String?
)

data class ReferralResponse(
    val enabled: Boolean = true,
    val code: String?,
    val shareLink: String?,
    val shareText: String?,
    val rewardPerReferral: Double = 0.0,
    val joiningBonus: Double = 0.0,
    val terms: String?,
    val totalInvited: Int = 0,
    val totalRewarded: Int = 0,
    val totalEarned: Double = 0.0,
    val referrals: List<ReferralEntry> = emptyList()
)

// ── Withdrawals ──────────────────────────────────────────────────
data class WithdrawConfig(
    val enabled: Boolean = true,
    val min: Double = 0.0,
    val max: Double = 0.0,
    val note: String?
)

data class PayoutMethod(
    val id: Int,
    val type: String?,
    val upiId: String?,
    val accountHolder: String?,
    val accountMasked: String?,
    val ifsc: String?,
    val bankName: String?,
    val isDefault: Boolean = false,
    val label: String?
)

data class PayoutMethodList(val data: List<PayoutMethod> = emptyList())

data class SimpleOk(val ok: Boolean = false, val id: Int? = null, val message: String? = null)

data class WithdrawRequestResponse(
    val ok: Boolean = false,
    val requestId: Int? = null,
    val newBalance: Double = 0.0,
    val message: String? = null
)

data class WithdrawalItem(
    val id: Int,
    val amount: Double = 0.0,
    val methodType: String?,
    val method: String?,
    val status: String?,
    val remarks: String?,
    val referenceNo: String?,
    val createdAt: String?,
    val processedAt: String?
)

data class WithdrawalList(val data: List<WithdrawalItem> = emptyList())

// ── Orders ───────────────────────────────────────────────────────
data class AppOrderItem(
    val id: Int,
    val symbol: String = "",
    val exchange: String? = null,
    val name: String? = null,
    val orderType: String? = null,   // BUY / SELL
    val product: String? = null,
    val priceType: String? = null,
    val qty: Int = 0,
    val price: Double = 0.0,
    val avgPrice: Double? = null,
    val status: String? = null,      // PENDING / EXECUTED / CANCELLED
    val placedAt: String? = null
)

data class AppOrderCounts(
    val total: Int = 0,
    val pending: Int = 0,
    val executed: Int = 0,
    val cancelled: Int = 0
)

data class AppOrdersResponse(
    val data: List<AppOrderItem> = emptyList(),
    val counts: AppOrderCounts? = null
)

// ── Profile ──────────────────────────────────────────────────────
data class MeUser(
    val id: Int = 0,
    val name: String? = null,
    val email: String? = null,
    val phone: String? = null,
    val kycStatus: String? = null,
    val boDematNumber: String? = null,
    val referralCode: String? = null,
    val createdAt: String? = null,
    val lastLoginAt: String? = null
)

data class MeResponse(val user: MeUser?)

interface TradexApi {

    // ── KYC ──────────────────────────────────────────────────────
    @Multipart
    @POST("kyc/upload-doc")
    suspend fun uploadKycDoc(
        @Part file: MultipartBody.Part,
        @Part("doc_type") docType: RequestBody
    ): Response<KycDocUploadResponse>

    @GET("kyc/progress")
    suspend fun kycProgress(): Response<KycProgressResponse>

    @POST("kyc/submit")
    suspend fun submitKyc(@Body body: Map<String, String>): Response<KycSubmitResponse>

    @GET("kyc/status")
    suspend fun kycStatusFull(): Response<KycStatusFull>

    // ── Settings ─────────────────────────────────────────────────
    @GET("settings/support")
    suspend fun supportDetails(): Response<SupportDetails>

    @GET("settings/links")
    suspend fun appLinks(): Response<AppLinks>

    // ── Referrals ────────────────────────────────────────────────
    @GET("referrals/me")
    suspend fun referralInfo(): Response<ReferralResponse>

    // ── Withdrawals ──────────────────────────────────────────────
    @GET("withdrawals/config")
    suspend fun withdrawConfig(): Response<WithdrawConfig>

    @GET("withdrawals/methods")
    suspend fun payoutMethods(): Response<PayoutMethodList>

    @POST("withdrawals/methods")
    suspend fun addPayoutMethod(
        @Body body: Map<String, @JvmSuppressWildcards Any>
    ): Response<SimpleOk>

    @DELETE("withdrawals/methods/{id}")
    suspend fun deletePayoutMethod(@Path("id") id: Int): Response<SimpleOk>

    @POST("withdrawals/request")
    suspend fun requestWithdrawal(
        @Body body: Map<String, @JvmSuppressWildcards Any>
    ): Response<WithdrawRequestResponse>

    @GET("withdrawals")
    suspend fun myWithdrawals(): Response<WithdrawalList>

    // ── Orders ───────────────────────────────────────────────────
    @GET("app-orders-ex")
    suspend fun myOrders(@Query("status") status: String? = null): Response<AppOrdersResponse>

    @POST("app-orders-ex/{id}/cancel")
    suspend fun cancelOrder(@Path("id") id: Int): Response<SimpleOk>

    // ── Profile ──────────────────────────────────────────────────
    @GET("users/me")
    suspend fun me(): Response<MeResponse>
}
