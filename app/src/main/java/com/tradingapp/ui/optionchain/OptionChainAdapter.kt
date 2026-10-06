package com.tradingapp.ui.optionchain

import android.graphics.Color
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.tradingapp.data.api.OptionChainRow
import com.tradingapp.data.api.OptionContract
import com.tradingapp.databinding.ItemOptionChainRowBinding

/**
 * CHANGED IN THIS VERSION
 *
 * 1. ATM row. The Activity sets [spot] and [atmStrike] before
 *    submitList; the row whose strike matches [atmStrike] draws a
 *    "SPOT Rs.x" marker band above itself and tints its background, so
 *    the strike nearest the index price is obvious the moment the chain
 *    opens. The Activity scrolls to it and pulses it.
 *
 *    ATM is computed here on the client from the spot price your model
 *    already carries, deliberately — that way no change is needed in
 *    ApiService.kt and an older app build keeps working unchanged.
 *
 * 2. Change % no longer lies. A contract that has not traded today has
 *    no previous close to compute a percentage against, so it now shows
 *    "—" instead of a confident "+0.00%", which is what made empty
 *    strikes look like flat ones.
 */
class OptionChainAdapter(
    private val onContractClick: (symbol: String, label: String) -> Unit
) : ListAdapter<OptionChainRow, OptionChainAdapter.VH>(DIFF) {

    var underlyingLabel: String = ""
    var expiryLabel: String = ""

    /** Spot price of the underlying, used for the ATM band caption. */
    var spot: Double? = null

    /** Strike nearest to spot. Set by the Activity before submitList. */
    var atmStrike: Double? = null

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
        val b = ItemOptionChainRowBinding.inflate(
            android.view.LayoutInflater.from(parent.context), parent, false
        )
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        val payload = payloads.filterIsInstance<TickPayload>().lastOrNull()
        if (payload == null) {
            super.onBindViewHolder(holder, position, payloads)
        } else {
            holder.bindTick(payload)
        }
    }

    /** Live tick from the websocket — in-place update, no full rebind. */
    fun updateTick(symbol: String, ltp: Double, changePct: Double) {
        val idx = currentList.indexOfFirst { it.call?.symbol == symbol || it.put?.symbol == symbol }
        if (idx == -1) return
        val isCall = currentList[idx].call?.symbol == symbol
        notifyItemChanged(idx, TickPayload(isCall, ltp, changePct))
    }

    data class TickPayload(val isCall: Boolean, val ltp: Double, val changePct: Double)

    inner class VH(private val b: ItemOptionChainRowBinding) : RecyclerView.ViewHolder(b.root) {

        fun bind(row: OptionChainRow) {
            b.tvStrike.text = row.strike.toInt().toString()
            bindSide(row.call, b.tvCallLtp, b.tvCallChange, b.callContainer, isCall = true)
            bindSide(row.put, b.tvPutLtp, b.tvPutChange, b.putContainer, isCall = false)
            bindAtm(row)
        }

        private fun bindAtm(row: OptionChainRow) {
            val isAtm = atmStrike != null && row.strike == atmStrike
            if (isAtm) {
                b.atmBanner.visibility = android.view.View.VISIBLE
                b.tvAtmLabel.text = spot?.let { "SPOT  ₹%,.2f".format(it) } ?: "SPOT"
                // Subtle tint — the row should still read as a row, not a header.
                b.rowContent.setBackgroundColor(Color.parseColor("#1A6D5EF8"))
            } else {
                b.atmBanner.visibility = android.view.View.GONE
                b.rowContent.setBackgroundColor(Color.TRANSPARENT)
            }
        }

        fun bindTick(p: TickPayload) {
            val ltpView = if (p.isCall) b.tvCallLtp else b.tvPutLtp
            val changeView = if (p.isCall) b.tvCallChange else b.tvPutChange
            ltpView.text = "₹%.2f".format(p.ltp)
            // A live tick means it has traded, so the percentage is real.
            setChangeText(changeView, p.changePct, available = p.ltp > 0)
        }

        private fun bindSide(
            contract: OptionContract?,
            ltpView: android.widget.TextView,
            changeView: android.widget.TextView,
            container: android.view.View,
            isCall: Boolean
        ) {
            if (contract == null) {
                ltpView.text = "—"
                changeView.text = ""
                container.setOnClickListener(null)
                container.isClickable = false
                return
            }
            ltpView.text = if (contract.priceAvailable) "₹%.2f".format(contract.ltp) else "—"
            setChangeText(changeView, contract.changePct, contract.priceAvailable)
            container.isClickable = true
            container.setOnClickListener {
                val side = if (isCall) "CE" else "PE"
                val label = "$underlyingLabel ${strikeOf(contract)} $side • $expiryLabel"
                onContractClick(contract.symbol, label)
            }
        }

        private fun setChangeText(
            view: android.widget.TextView, changePct: Double, available: Boolean
        ) {
            if (!available) {
                // Not traded today — no previous close, so no percentage
                // exists. Say so instead of showing a fake 0.00%.
                view.text = "—"
                view.setTextColor(Color.parseColor("#8A93A0"))
                return
            }
            val sign = if (changePct >= 0) "+" else ""
            view.text = "$sign%.2f%%".format(changePct)
            view.setTextColor(Color.parseColor(if (changePct >= 0) "#2FBF71" else "#FF5C5C"))
        }

        /** NIFTY25O0725000CE -> "25000" */
        private fun strikeOf(c: OptionContract): String =
            Regex("(\\d+)(CE|PE)$").find(c.symbol)?.groupValues?.get(1) ?: ""
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<OptionChainRow>() {
            override fun areItemsTheSame(a: OptionChainRow, b: OptionChainRow) = a.strike == b.strike
            override fun areContentsTheSame(a: OptionChainRow, b: OptionChainRow) = a == b
        }
    }
}