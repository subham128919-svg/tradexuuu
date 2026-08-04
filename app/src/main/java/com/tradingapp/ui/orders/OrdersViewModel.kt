package com.tradingapp.ui.orders

import androidx.lifecycle.*; import com.tradingapp.data.model.Order; import com.tradingapp.data.repository.PortfolioRepository
import com.tradingapp.util.Resource; import dagger.hilt.android.lifecycle.HiltViewModel; import kotlinx.coroutines.flow.*; import kotlinx.coroutines.launch; import javax.inject.Inject

@HiltViewModel
class OrdersViewModel @Inject constructor(private val repo: PortfolioRepository) : ViewModel() {
    private val _orders = MutableStateFlow<Resource<List<Order>>>(Resource.Loading)
    val orders: StateFlow<Resource<List<Order>>> = _orders
    fun load(status: String) = viewModelScope.launch { repo.getOrders(status).collect { _orders.value = it } }
    suspend fun cancel(orderId: String) = repo.getOrders()  // simplified
}
