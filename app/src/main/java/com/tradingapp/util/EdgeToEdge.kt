package com.tradingapp.util

import android.app.Activity
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

// ─────────────────────────────────────────────────────────────────
//  Real edge-to-edge: background extends fully behind the status bar
//  and nav bar (matching status bar icon colour to the app), while
//  actual content (headers, buttons) gets padded so nothing is ever
//  hidden underneath the system bars.
//
//  Usage: call once in onCreate(), AFTER setContentView():
//    applyEdgeToEdge(topView = binding.appHeader, bottomView = binding.bottomNav)
//
//  Pass only the views that actually sit at the very top/bottom edge
//  of the screen -- these get extra padding equal to the system bar
//  inset. Everything else is untouched.
// ─────────────────────────────────────────────────────────────────
fun Activity.applyEdgeToEdge(topView: View? = null, bottomView: View? = null) {
    WindowCompat.setDecorFitsSystemWindows(window, false)

    val originalTop    = topView?.paddingTop ?: 0
    val originalBottom  = bottomView?.paddingBottom ?: 0
    val root = findViewById<View>(android.R.id.content)

    ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        // Compute from the ORIGINAL padding each time (not accumulated) —
        // this listener can fire multiple times (rotation, IME, etc.)
        topView?.updatePadding(top = originalTop + bars.top)
        bottomView?.updatePadding(bottom = originalBottom + bars.bottom)
        insets
    }
    ViewCompat.requestApplyInsets(root)
}
