package com.tradingapp.util
import android.content.Context; import android.graphics.Color; import android.view.View; import android.widget.TextView
import androidx.fragment.app.Fragment; import androidx.lifecycle.Lifecycle; import androidx.lifecycle.lifecycleScope; import androidx.lifecycle.repeatOnLifecycle; import kotlinx.coroutines.CoroutineScope; import kotlinx.coroutines.launch
import java.text.NumberFormat; import java.util.Locale

fun Double.toRupee(): String { val nf = NumberFormat.getNumberInstance(Locale("en","IN")).also { it.maximumFractionDigits = 2; it.minimumFractionDigits = 2 }; return "Rs." + nf.format(this) }
fun Double.toPct() = "${if (this >= 0) "+" else ""}${"%.2f".format(this)}%"
fun TextView.setChange(v: Double, suffix: String = "") { text = "${if (v >= 0) "+" else ""}${"%.2f".format(v)}$suffix"; setTextColor(if (v >= 0) Color.parseColor("#2FBF71") else Color.parseColor("#FF5C5C")) }
fun Fragment.launchOnStarted(block: suspend CoroutineScope.() -> Unit) { viewLifecycleOwner.lifecycleScope.launch { viewLifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED, block) } }
fun View.show() { visibility = View.VISIBLE }; fun View.hide() { visibility = View.GONE }
