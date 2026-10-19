package com.tradingapp.ui.holdings

import androidx.lifecycle.*; import com.tradingapp.data.api.HoldingsResponse; import com.tradingapp.data.repository.PortfolioRepository
import com.tradingapp.util.Resource; import dagger.hilt.android.lifecycle.HiltViewModel; import kotlinx.coroutines.flow.*; import kotlinx.coroutines.launch; import javax.inject.Inject

@HiltViewModel
class HoldingsViewModel @Inject constructor(private val repo: PortfolioRepository) : ViewModel() {
    private val _holdings = MutableStateFlow<Resource<HoldingsResponse>>(Resource.Loading)
    val holdings: StateFlow<Resource<HoldingsResponse>> = _holdings
    private var job: kotlinx.coroutines.Job? = null
    private var session: String? = null
    fun load(segment: String) {
        if(session != repo.sessionKey()) { session = repo.sessionKey(); _holdings.value = Resource.Loading }
        job?.cancel()
        job = viewModelScope.launch { repo.getHoldings().collect {
            if (it !is Resource.Loading || _holdings.value !is Resource.Success) _holdings.value = it
        } }
    }
}
