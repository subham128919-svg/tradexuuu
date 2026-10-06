package com.tradingapp.util

/**
 * Feature #1 — password strength rules.
 * Mirrors backend/src/utils/password.js exactly. The server is still the
 * authority; this exists so the user gets instant feedback instead of a
 * round trip.
 */
object PasswordPolicy {

    private val SPECIAL = Regex("[!@#\$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>/?~`]")

    data class Result(
        val valid: Boolean,
        val message: String?,
        val hasLength: Boolean,
        val hasUpper: Boolean,
        val hasLower: Boolean,
        val hasDigit: Boolean,
        val hasSpecial: Boolean
    )

    fun check(pw: String): Result {
        val hasLength  = pw.length >= 8
        val hasUpper   = pw.any { it.isUpperCase() }
        val hasLower   = pw.any { it.isLowerCase() }
        val hasDigit   = pw.any { it.isDigit() }
        val hasSpecial = SPECIAL.containsMatchIn(pw)
        val hasSpace   = pw.any { it.isWhitespace() }

        val missing = buildList {
            if (!hasLength)  add("at least 8 characters")
            if (!hasUpper)   add("1 uppercase letter")
            if (!hasLower)   add("1 lowercase letter")
            if (!hasDigit)   add("1 number")
            if (!hasSpecial) add("1 special character")
            if (hasSpace)    add("no spaces")
        }

        return Result(
            valid      = missing.isEmpty(),
            message    = if (missing.isEmpty()) null
                         else "Password must contain " + missing.joinToString(", ") + ".",
            hasLength  = hasLength,
            hasUpper   = hasUpper,
            hasLower   = hasLower,
            hasDigit   = hasDigit,
            hasSpecial = hasSpecial
        )
    }

    /** 0..4 — drives the strength bar colour. */
    fun strength(pw: String): Int {
        if (pw.isEmpty()) return 0
        val r = check(pw)
        var s = 0
        if (r.hasLength)  s++
        if (r.hasUpper && r.hasLower) s++
        if (r.hasDigit)   s++
        if (r.hasSpecial) s++
        if (pw.length >= 12 && s == 4) return 4
        return s
    }

    /** Multi-line hint shown under the password field. */
    fun hintFor(pw: String): String {
        val r = check(pw)
        fun mark(ok: Boolean, label: String) = (if (ok) "✓ " else "• ") + label
        return listOf(
            mark(r.hasLength,  "8+ characters"),
            mark(r.hasUpper,   "1 uppercase (A–Z)"),
            mark(r.hasLower,   "1 lowercase (a–z)"),
            mark(r.hasDigit,   "1 number (0–9)"),
            mark(r.hasSpecial, "1 special character (!@#\$…)")
        ).joinToString("   ")
    }
}
