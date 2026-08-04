package com.tradingapp.ui.orders

import android.os.Bundle; import android.view.*; import androidx.fragment.app.Fragment; import androidx.fragment.app.viewModels
import com.tradingapp.data.model.Order; import com.tradingapp.databinding.ScreenEquityOrdersOpenBinding
import com.tradingapp.ui.adapter.RowAdapter; import com.tradingapp.ui.adapter.RowItem; import com.tradingapp.util.*
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class OrdersFragment : Fragment() {
    private var _b: ScreenEquityOrdersOpenBinding? = null
    private val b get() = _b!!
    private val vm: OrdersViewModel by viewModels()
    private lateinit var adapter: RowAdapter
    private val segment by lazy { arguments?.getString("segment") ?: "equity" }
    private var currentFilter = "open"

    companion object { fun newInstance(seg: String) = OrdersFragment().apply { arguments = Bundle().also { it.putString("segment", seg) } } }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View { _b = ScreenEquityOrdersOpenBinding.inflate(i, c, false); return b.root }

    override fun onViewCreated(v: View, s: Bundle?) {
        super.onViewCreated(v, s)
        adapter = RowAdapter { /* order tapped */ }
        vm.load(currentFilter)
        launchOnStarted { vm.orders.collect { res ->
            if (res is Resource.Success) {
                adapter.submitList(res.data.map { o ->
                    RowItem(o.symbol.take(2), o.symbol,
                        "${o.orderType} Rs.%.2f · ${o.product}".format(o.price),
                        "${o.filledQuantity}/${o.quantity}", o.status, o.type)
                })
            }
        }}
    }
    override fun onDestroyView() { super.onDestroyView(); _b = null }
}
