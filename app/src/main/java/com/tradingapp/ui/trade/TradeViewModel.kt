package com.tradingapp.ui.trade

import androidx.lifecycle.ViewModel; import com.tradingapp.data.repository.PortfolioRepository; import com.tradingapp.util.Resource
import dagger.hilt.android.lifecycle.HiltViewModel; import javax.inject.Inject

@HiltViewModel
class TradeViewModel @Inject constructor(private val repo: PortfolioRepository) : ViewModel() {

}
