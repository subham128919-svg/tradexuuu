package com.tradingapp.ui.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.tradingapp.databinding.ItemTopMoverBinding

data class TopMoverItem(
    val symbol: String,
    val name: String,
    val ltp: Double,
    val changePct: Double,
    val changeAbs: Double
)

class TopMoverAdapter(private val onClick: (TopMoverItem) -> Unit) :
    ListAdapter<TopMoverItem, TopMoverAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemTopMoverBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    inner class VH(private val b: ItemTopMoverBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(m: TopMoverItem) {
            val sym = m.symbol.substringAfter(":")
            b.tvMoverLogo.text   = sym.take(2).uppercase()
            b.tvMoverSymbol.text = sym
            b.tvMoverPrice.text  = if (m.ltp > 0) "Rs.%.2f".format(m.ltp) else "—"
            val chg = "%.2f%%".format(m.changePct)
            b.tvMoverChange.text      = if (m.changePct >= 0) "+$chg" else chg
            b.tvMoverChange.setTextColor(
                if (m.changePct >= 0) Color.parseColor("#2FBF71")
                else Color.parseColor("#FF5C5C")
            )
            b.root.setOnClickListener { onClick(m) }
        }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<TopMoverItem>() {
            override fun areItemsTheSame(a: TopMoverItem, b: TopMoverItem) = a.symbol == b.symbol
            override fun areContentsTheSame(a: TopMoverItem, b: TopMoverItem) = a == b
        }
    }
}
