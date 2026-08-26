package com.tradingapp.ui.explore

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.tradingapp.R
import com.tradingapp.data.api.MoverItem
import com.tradingapp.databinding.ItemFnoIndexBinding

class FnoIndexAdapter(
    private val onItemClick: (MoverItem) -> Unit
) : ListAdapter<MoverItem, FnoIndexAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): ViewHolder {

        val binding =
            ItemFnoIndexBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )

        return ViewHolder(binding)
    }

    override fun onBindViewHolder(
        holder: ViewHolder,
        position: Int
    ) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val b: ItemFnoIndexBinding
    ) : RecyclerView.ViewHolder(b.root) {

        fun bind(item: MoverItem) {

            b.tvLogo.text =
                item.name
                    .replace("NIFTY", "N")
                    .replace("BANK", "B")
                    .replace("FIN", "F")
                    .take(2)
                    .uppercase()

            b.tvName.text = item.name

            b.tvSub.text =
                item.symbol.substringBefore(
                    ":",
                    "NSE"
                )

            b.tvRight.text =
                if (item.ltp > 0.0) {
                    "%.2f".format(item.ltp)
                } else {
                    "—"
                }

            val changeText =
                if (item.changePct >= 0) {
                    "+%.2f%%".format(item.changePct)
                } else {
                    "%.2f%%".format(item.changePct)
                }

            b.tvRightSub.text = changeText

            when {
                item.ltp <= 0.0 -> {
                    b.tvRightSub.setTextColor(
                        Color.parseColor("#777777")
                    )

                    b.tvRightSub.setBackgroundResource(
                        R.drawable.bg_fno_change_neutral
                    )
                }

                item.changePct > 0 -> {
                    b.tvRightSub.setTextColor(
                        Color.parseColor("#228B57")
                    )

                    b.tvRightSub.setBackgroundResource(
                        R.drawable.bg_fno_change_positive
                    )
                }

                item.changePct < 0 -> {
                    b.tvRightSub.setTextColor(
                        Color.parseColor("#B3261E")
                    )

                    b.tvRightSub.setBackgroundResource(
                        R.drawable.bg_fno_change_negative
                    )
                }

                else -> {
                    b.tvRightSub.setTextColor(
                        Color.parseColor("#777777")
                    )

                    b.tvRightSub.setBackgroundResource(
                        R.drawable.bg_fno_change_neutral
                    )
                }
            }

            b.root.setOnClickListener {
                onItemClick(item)
            }
        }
    }

    companion object {

        private val DIFF =
            object : DiffUtil.ItemCallback<MoverItem>() {

                override fun areItemsTheSame(
                    oldItem: MoverItem,
                    newItem: MoverItem
                ): Boolean =
                    oldItem.symbol == newItem.symbol

                override fun areContentsTheSame(
                    oldItem: MoverItem,
                    newItem: MoverItem
                ): Boolean =
                    oldItem == newItem
            }
    }
}