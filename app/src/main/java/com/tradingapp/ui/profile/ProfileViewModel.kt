package com.tradingapp.ui.profile

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tradingapp.data.api.ApiService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ProfileState(
    val name: String    = "",
    val email: String   = "",
    val balance: Double = 0.0,
    val initials: String = "??"
)

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val api: ApiService,
    @ApplicationContext private val ctx: Context
) : ViewModel() {

    private val _state = MutableStateFlow(ProfileState())
    val state: StateFlow<ProfileState> = _state

    init { load() }

    fun load() = viewModelScope.launch {
        // Load name/email from SharedPreferences (set at login)
        val prefs    = ctx.getSharedPreferences("tradingapp_prefs", Context.MODE_PRIVATE)
        val name     = prefs.getString("user_name", "") ?: ""
        val email    = prefs.getString("user_email", "") ?: ""
        val initials = name.split(" ").filter { it.isNotEmpty() }
            .take(2).joinToString("") { it.first().uppercase() }.ifEmpty { "??" }

        _state.value = _state.value.copy(name = name, email = email, initials = initials)

        // Load wallet balance from backend
        try {
            val r = api.getWallet()
            if (r.isSuccessful) {
                _state.value = _state.value.copy(balance = r.body()?.balance ?: 0.0)
            }
        } catch (_: Exception) {}
    }

    fun logout(onDone: () -> Unit) {
        val prefs = ctx.getSharedPreferences("tradingapp_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        onDone()
    }
}
