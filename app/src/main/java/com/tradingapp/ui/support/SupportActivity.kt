package com.tradingapp.ui.support

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tradingapp.data.api.SupportDetails
import com.tradingapp.data.api.TradexApi
import com.tradingapp.databinding.ActivitySupportBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Feature #5 — Customer support.
 * Every string on this screen comes from /api/v1/settings/support, which
 * the admin edits at /admin/manage.html → "Support details". Nothing is
 * hard-coded, so support numbers can be changed without an app release.
 */
@AndroidEntryPoint
class SupportActivity : AppCompatActivity() {

    @Inject lateinit var api: TradexApi
    private lateinit var b: ActivitySupportBinding
    private var details: SupportDetails? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySupportBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.ivBack.setOnClickListener { finish() }
        b.btnWhatsapp.setOnClickListener { openWhatsApp() }
        b.rowPhone.setOnClickListener   { dial() }
        b.rowEmail.setOnClickListener   { email() }
        b.rowAddress.setOnClickListener { copy("Address", details?.address) }

        load()
    }

    private fun load() = lifecycleScope.launch {
        b.progress.visibility = View.VISIBLE
        b.content.visibility  = View.GONE
        try {
            val r = api.supportDetails()
            b.progress.visibility = View.GONE
            if (!r.isSuccessful || r.body() == null) { fail(); return@launch }

            val d = r.body()!!
            details = d
            b.content.visibility = View.VISIBLE

            b.tvTitle.text    = d.title.orEmpty().ifEmpty { "We are here to help" }
            b.tvSubtitle.text = d.subtitle.orEmpty()

            b.btnWhatsapp.visibility =
                if (d.whatsapp.isNullOrBlank()) View.GONE else View.VISIBLE

            bindRow(b.rowPhone,   b.tvPhone,   d.phone)
            bindRow(b.rowEmail,   b.tvEmail,   d.email)
            bindRow(b.rowHours,   b.tvHours,   d.hours)
            bindRow(b.rowAddress, b.tvAddress, d.address)

            if (d.note.isNullOrBlank()) b.tvNote.visibility = View.GONE
            else { b.tvNote.text = d.note; b.tvNote.visibility = View.VISIBLE }
        } catch (e: Exception) {
            b.progress.visibility = View.GONE
            fail()
        }
    }

    private fun bindRow(row: View, value: TextView, text: String?) {
        if (text.isNullOrBlank()) row.visibility = View.GONE
        else { row.visibility = View.VISIBLE; value.text = text }
    }

    private fun fail() {
        b.content.visibility = View.VISIBLE
        b.tvTitle.text    = "Support is temporarily unreachable"
        b.tvSubtitle.text = "Please check your connection and try again."
        b.btnWhatsapp.visibility = View.GONE
    }

    private fun openWhatsApp() {
        val url = details?.whatsappUrl
        val num = details?.whatsapp
        val target = when {
            !url.isNullOrBlank() -> url
            !num.isNullOrBlank() -> "https://wa.me/$num"
            else -> { toast("WhatsApp support is not configured"); return }
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            toast("WhatsApp is not installed on this device")
        }
    }

    private fun dial() {
        val p = details?.phone ?: return
        try {
            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + p.replace(" ", ""))))
        } catch (e: Exception) { copy("Phone", p) }
    }

    private fun email() {
        val e = details?.email ?: return
        try {
            startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$e")).apply {
                putExtra(Intent.EXTRA_SUBJECT, "TradeX support request")
            })
        } catch (ex: Exception) { copy("Email", e) }
    }

    private fun copy(label: String, value: String?) {
        if (value.isNullOrBlank()) return
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText(label, value))
        toast("$label copied")
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
