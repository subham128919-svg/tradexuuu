package com.tradingapp.ui.orders

import androidx.lifecycle.*; import com.tradingapp.data.model.Order; import com.tradingapp.data.repository.PortfolioRepository
import com.tradingapp.util.Resource; import dagger.hilt.android.lifecycle.HiltViewModel; import kotlinx.coroutines.flow.*; import kotlinx.coroutines.launch; import javax.inject.Inject

@HiltViewModel
class OrdersViewModel @Inject constructor(private val repo: PortfolioRepository) : ViewModel() {
    private val _orders = MutableStateFlow<Resource<List<Order>>>(Resource.Loading)
    val orders: StateFlow<Resource<List<Order>>> = _orders
    private var job: kotlinx.coroutines.Job? = null
    private var session: String? = null
    fun load(status: String) {
        if(session != repo.sessionKey()) { session = repo.sessionKey(); _orders.value = Resource.Loading }
        job?.cancel()
        job = viewModelScope.launch { repo.getOrders(status).collect {
            if (it !is Resource.Loading || _orders.value !is Resource.Success) _orders.value = it
        } }
    }
}
