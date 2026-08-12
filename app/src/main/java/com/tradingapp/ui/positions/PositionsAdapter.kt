package com.tradingapp.ui.positions

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.tradingapp.data.api.AppPosition
import com.tradingapp.databinding.ItemPositionRowBinding

class PositionsAdapter(
    private val onExit: (AppPosition) -> Unit,
    private val onClick: (AppPosition) -> Unit
) : ListAdapter<AppPosition, PositionsAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemPositionRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, pos: Int) = holder.bind(getItem(pos))

    // Payload-based update — only rebind P&L on tick, not whole row
    override fun onBindViewHolder(holder: VH, pos: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) holder.bind(getItem(pos))
        else holder.bindPnlOnly(getItem(pos))
    }

    inner class VH(private val b: ItemPositionRowBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(p: AppPosition) {
            val sym = p.symbol.substringAfter(":")
            b.tvLogo.text   = sym.take(2).uppercase()
            b.tvSymbol.text = sym
            b.tvQtyAvg.text = "${p.qty} qty · avg ₹%.2f".format(p.avgPrice)
            bindPnlOnly(p)
            b.btnExit.setOnClickListener { onExit(p) }
            b.root.setOnClickListener   { onClick(p) }
        }

        // FIX (P&L bug): the LTP text already had a "we don't know yet"
        // guard (shows "—" when ltp<=0) — but P&L never did, so it kept
        // confidently showing "-100%" (computed from a fallback ltp of
        // 0) right next to that "—". Now both share the same guard.
        fun bindPnlOnly(p: AppPosition) {
            b.tvLtp.text = if (p.ltp > 0) "₹%.2f".format(p.ltp) else "—"
            if (!p.priceAvailable) {
                b.tvPnl.text = "Fetching…"
                b.tvPnl.setTextColor(Color.parseColor("#6E7681"))
                return
            }
            val pnlColor = if (p.isProfit) Color.parseColor("#2FBF71") else Color.parseColor("#FF5C5C")
            val sign     = if (p.isProfit) "+" else ""
            b.tvPnl.text = "$sign₹%.2f ($sign%.2f%%)".format(p.pnl, p.pnlPct)
            b.tvPnl.setTextColor(pnlColor)
        }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<AppPosition>() {
            override fun areItemsTheSame(a: AppPosition, b: AppPosition) = a.symbol == b.symbol
            override fun areContentsTheSame(a: AppPosition, b: AppPosition) = a == b
            override fun getChangePayload(a: AppPosition, b: AppPosition): Any? {
                val aS = a.copy(ltp=0.0, pnl=0.0, pnlPct=0.0, currentValue=0.0, isProfit=true, priceAvailable=true)
                val bS = b.copy(ltp=0.0, pnl=0.0, pnlPct=0.0, currentValue=0.0, isProfit=true, priceAvailable=true)
                return if (aS == bS) "PNL_ONLY" else null
            }
        }
    }
}
