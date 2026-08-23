package com.tradingapp.ui.optionchain

import android.graphics.Color
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.tradingapp.data.api.OptionChainRow
import com.tradingapp.data.api.OptionContract
import com.tradingapp.databinding.ItemOptionChainRowBinding

class OptionChainAdapter(
    private val onContractClick: (symbol: String, label: String) -> Unit
) : ListAdapter<OptionChainRow, OptionChainAdapter.VH>(DIFF) {

    // underlying name + expiry, used to build a readable label when a
    // contract is tapped (e.g. "NIFTY 24000 CE • 28 Aug"). Set by the
    // Activity whenever a new chain loads.
    var underlyingLabel: String = ""
    var expiryLabel: String = ""

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
        val b = ItemOptionChainRowBinding.inflate(
            android.view.LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    // Called by the Activity whenever a live tick arrives for a symbol
    // in this chain — finds the matching row and does a lightweight
    // in-place update instead of rebinding the whole list.
    fun updateTick(symbol: String, ltp: Double, changePct: Double) {
        val idx = currentList.indexOfFirst { it.call?.symbol == symbol || it.put?.symbol == symbol }
        if (idx == -1) return
        val row = currentList[idx]
        val isCall = row.call?.symbol == symbol
        notifyItemChanged(idx, TickPayload(isCall, ltp, changePct))
    }

    data class TickPayload(val isCall: Boolean, val ltp: Double, val changePct: Double)

    override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        val payload = payloads.filterIsInstance<TickPayload>().lastOrNull()
        if (payload == null) { super.onBindViewHolder(holder, position, payloads); return }
        holder.bindTick(payload)
    }

    inner class VH(private val b: ItemOptionChainRowBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(row: OptionChainRow) {
            b.tvStrike.text = row.strike.toInt().toString()
            bindSide(row.call, b.tvCallLtp, b.tvCallChange, b.callContainer, isCall = true)
            bindSide(row.put,  b.tvPutLtp,  b.tvPutChange,  b.putContainer,  isCall = false)
        }

        fun bindTick(p: TickPayload) {
            val ltpView    = if (p.isCall) b.tvCallLtp    else b.tvPutLtp
            val changeView = if (p.isCall) b.tvCallChange else b.tvPutChange
            ltpView.text = "₹%.2f".format(p.ltp)
            setChangeText(changeView, p.changePct)
        }

        private fun bindSide(
            contract: OptionContract?, ltpView: android.widget.TextView,
            changeView: android.widget.TextView, container: android.view.View, isCall: Boolean
        ) {
            if (contract == null) {
                ltpView.text = "—"; changeView.text = ""
                container.setOnClickListener(null)
                return
            }
            ltpView.text = if (contract.priceAvailable) "₹%.2f".format(contract.ltp) else "—"
            setChangeText(changeView, contract.changePct)
            container.setOnClickListener {
                val side  = if (isCall) "CE" else "PE"
                val label = "$underlyingLabel $side • $expiryLabel"
                onContractClick(contract.symbol, label)
            }
        }

        private fun setChangeText(view: android.widget.TextView, changePct: Double) {
            val sign = if (changePct >= 0) "+" else ""
            view.text = "$sign%.2f%%".format(changePct)
            view.setTextColor(Color.parseColor(if (changePct >= 0) "#2FBF71" else "#FF5C5C"))
        }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<OptionChainRow>() {
            override fun areItemsTheSame(a: OptionChainRow, b: OptionChainRow) = a.strike == b.strike
            override fun areContentsTheSame(a: OptionChainRow, b: OptionChainRow) = a == b
        }
    }
}
