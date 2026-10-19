package com.tradingapp.ui.optionchain

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tradingapp.data.api.ApiService
import com.tradingapp.data.api.OptionChainResponse
import com.tradingapp.data.repository.MarketRepository
import com.tradingapp.util.Resource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OptionChainViewModel @Inject constructor(
    private val api:  ApiService,
    private val repo: MarketRepository,
    private val savedState: SavedStateHandle
) : ViewModel() {

    private val _expiries = MutableStateFlow<List<String>>(emptyList())
    val expiries: StateFlow<List<String>> = _expiries

    private val _chain = MutableStateFlow<Resource<OptionChainResponse>>(Resource.Loading)
    val chain: StateFlow<Resource<OptionChainResponse>> = _chain

    val liveTicks = repo.liveTicks

    private var chainJob: Job? = null
    private var subscribedSymbols: List<String> = emptyList()
    private var expiryJob: Job? = null

    // FIX: _chain used to only ever get updated inside loadChain(). If the
    // expiries list came back empty, or the request failed, or it threw,
    // loadChain() was never called — so _chain stayed stuck on its initial
    // Resource.Loading value forever, and the screen just spun with nothing
    // rendered (no data, no error, no "no options" message). Every branch
    // below now explicitly resolves _chain to either Success (via
    // loadChain) or Error, so the screen always ends up showing something.
    fun loadExpiries(underlying: String) {
      if (expiryJob?.isActive == true || _expiries.value.isNotEmpty()) return
      expiryJob = viewModelScope.launch {
        try {
            val r = api.getOptionExpiries(underlying)
            if (r.isSuccessful) {
                val list = r.body()?.expiries ?: emptyList()
                _expiries.value = list
                val nearest = savedState.get<String>("expiry")?.takeIf { it in list } ?: list.firstOrNull()
                if (nearest != null) {
                    loadChain(underlying, nearest)
                } else {
                    _chain.value = Resource.Error("No option contracts available for $underlying right now")
                }
            } else {
                _chain.value = Resource.Error("Could not load expiries (${r.code()})")
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            _chain.value = Resource.Error(e.message ?: "Network error")
        }
      }
    }

    fun loadChain(underlying: String, expiry: String) {
        savedState["expiry"] = expiry
        chainJob?.cancel()
        chainJob = viewModelScope.launch {
            if ((_chain.value as? Resource.Success<OptionChainResponse>)?.data?.expiry != expiry) _chain.value = Resource.Loading
            try {
                val r = api.getOptionChain(underlying, expiry)
                if (r.isSuccessful && r.body() != null) {
                    val body = r.body()!!
                    _chain.value = Resource.Success(body)
                    // Subscribe to live ticks for every contract in this
                    // chain, same as ChartActivity does for a single symbol —
                    // this is what makes LTP/change% update in real time
                    // instead of only refreshing on manual reload.
                    resubscribe(body)
                } else {
                    _chain.value = Resource.Error("Could not load option chain (${r.code()})")
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                _chain.value = Resource.Error(e.message ?: "Network error")
            }
        }
    }

    private fun resubscribe(chain: OptionChainResponse) {
        val symbols = chain.rows.flatMap { row ->
            listOfNotNull(row.call, row.put).map { "${it.exchange}:${it.symbol}" }
        }.distinct()
        repo.unsubscribeLive(subscribedSymbols - symbols.toSet())
        repo.subscribeLive(symbols - subscribedSymbols.toSet())
        subscribedSymbols = symbols
    }

    override fun onCleared() {
        super.onCleared()
        if (subscribedSymbols.isNotEmpty()) repo.unsubscribeLive(subscribedSymbols)
    }
}
