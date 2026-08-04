package com.tradingapp.ui.positions

import androidx.lifecycle.*; import com.tradingapp.data.api.PositionsResponse; import com.tradingapp.data.repository.PortfolioRepository
import com.tradingapp.util.Resource; import dagger.hilt.android.lifecycle.HiltViewModel; import kotlinx.coroutines.flow.*; import kotlinx.coroutines.launch; import javax.inject.Inject

@HiltViewModel
class PositionsViewModel @Inject constructor(private val repo: PortfolioRepository) : ViewModel() {
    private val _positions = MutableStateFlow<Resource<PositionsResponse>>(Resource.Loading)
    val positions: StateFlow<Resource<PositionsResponse>> = _positions
    fun load(segment: String) = viewModelScope.launch { repo.getPositions().collect { _positions.value = it } }
}
