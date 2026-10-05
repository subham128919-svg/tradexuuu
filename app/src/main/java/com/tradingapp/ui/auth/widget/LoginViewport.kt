package com.tradingapp.ui.auth.widget

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.tradingapp.R
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Keeps the native form fitted, with vectors sized around the measured controls. */
class LoginViewport @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ScrollView(context, attrs, defStyleAttr) {

    var keyboardVisible: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
            }
        }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (childCount == 0 || MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val width = (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight).coerceAtLeast(0)
        val height = (MeasureSpec.getSize(heightMeasureSpec) - paddingTop - paddingBottom).coerceAtLeast(0)
        val content = getChildAt(0) as LinearLayout
        val header = findViewById<View>(R.id.headerArtContainer)
        val brand = findViewById<LinearLayout>(R.id.brandSection)
        val card = findViewById<View>(R.id.loginCard)
        val cardContent = findViewById<View>(R.id.loginCardContent)
        val footer = findViewById<View>(R.id.loginFooterArt)
        val security = findViewById<View>(R.id.securityRow)
        val divider = findViewById<View>(R.id.loginDivider)

        fun brandHeight(small: Boolean): Int {
            val logo = findViewById<ImageView>(R.id.logoBox)
            logo.layoutParams.width = dp(if (small) 36 else 72)
            logo.layoutParams.height = logo.layoutParams.width
            brand.setPadding(0, dp(if (small) 8 else 16), 0, dp(8))
            findViewById<View>(R.id.brandTagline).visibility = if (small) View.GONE else View.VISIBLE
            val brandSize = if (small) 24f else 32f
            findViewById<TextView>(R.id.tvBrand).textSize = brandSize
            findViewById<TextView>(R.id.brandX).textSize = brandSize
            brand.measure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            )
            return brand.measuredHeight
        }

        fun formHeight(compact: Boolean, extraSpace: Int = 0): Int {
            security.visibility = if (compact) View.GONE else View.VISIBLE
            divider.visibility = if (compact) View.GONE else View.VISIBLE
            findViewById<TextView>(R.id.tvTitle).textSize = if (compact) 24f else 26f
            // Allocate every remaining pixel to existing form gaps, so no outer
            // top/bottom bands appear on taller phones. Native text stays in sp.
            val totalWeight = if (compact) 7 else 9
            var usedWeight = 0
            fun extra(weight: Int): Int {
                val start = extraSpace.toLong() * usedWeight / totalWeight
                usedWeight += weight
                return (extraSpace.toLong() * usedWeight / totalWeight - start).toInt()
            }
            val topExtra = extra(2)
            fun topMargin(id: Int, base: Int, weight: Int) {
                (findViewById<View>(id).layoutParams as ViewGroup.MarginLayoutParams).topMargin = dp(base) + extra(weight)
            }
            topMargin(R.id.tvTitle, if (compact) 12 else 16, 1)
            topMargin(R.id.phoneLabel, if (compact) 16 else 24, 2)
            topMargin(R.id.btnLogin, if (compact) 12 else 18, 1)
            topMargin(R.id.securityRow, 20, if (compact) 0 else 1)
            topMargin(R.id.loginDivider, 22, if (compact) 0 else 1)
            cardContent.setPadding(dp(22), dp(if (compact) 18 else 24) + topExtra, dp(22), dp(18) + extra(1))
            val fieldHeight = dp(if (compact) 48 else 56)
            findViewById<View>(R.id.phoneInput).minimumHeight = fieldHeight
            findViewById<View>(R.id.etPhone).minimumHeight = fieldHeight
            findViewById<View>(R.id.btnLogin).minimumHeight = fieldHeight
            val margins = card.layoutParams as ViewGroup.MarginLayoutParams
            card.measure(
                MeasureSpec.makeMeasureSpec((width - margins.marginStart - margins.marginEnd).coerceAtLeast(0), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            )
            return card.measuredHeight + margins.topMargin + margins.bottomMargin
        }

        val contentPadding = content.paddingTop + content.paddingBottom
        val footerHeight = (width * 32f / 400f).roundToInt()
        val normalBrandHeight = brandHeight(false)
        var cardHeight = formHeight(false)
        val compact = keyboardVisible || height - cardHeight - footerHeight - contentPadding < normalBrandHeight
        if (compact) cardHeight = formHeight(true)

        footer.visibility = if (compact) View.GONE else View.VISIBLE
        footer.layoutParams.height = footerHeight
        val availableHeader = (height - cardHeight - contentPadding - if (compact) 0 else footerHeight).coerceAtLeast(0)
        val desiredHeader = max((width * 236f / 400f).roundToInt(), min(dp(300), (height * .30f).roundToInt()))
        val headerHeight = min(desiredHeader, availableHeader)
        val smallBrand = compact || headerHeight < normalBrandHeight
        val minimumBrandHeight = brandHeight(smallBrand)
        header.visibility = if (keyboardVisible || headerHeight < minimumBrandHeight) View.GONE else View.VISIBLE
        header.layoutParams.height = headerHeight

        val usedHeader = if (header.visibility == View.VISIBLE) headerHeight else 0
        val usedFooter = if (footer.visibility == View.VISIBLE) footerHeight else 0
        val spareHeight = (height - cardHeight - usedFooter - usedHeader - contentPadding).coerceAtLeast(0)
        if (spareHeight > 0) formHeight(compact, spareHeight)
        // Normal portrait has zero scroll range. A narrow landscape window or
        // very large accessibility fonts may scroll to keep every action usable.
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
