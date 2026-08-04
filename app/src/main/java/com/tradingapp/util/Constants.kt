package com.tradingapp.util

// Top-level constants — allows `import com.tradingapp.util.*` in any file
// Previously was `object Constants { ... }` which blocked star imports in Kotlin

// Segments
const val SEG_EQUITY = "equity"
const val SEG_FNO    = "fno"
const val SEG_MF     = "mf"

// Tab names
const val TAB_EXPLORE   = "Explore"
const val TAB_HOLDINGS  = "Holdings"
const val TAB_POSITIONS = "Positions"
const val TAB_ORDERS    = "Orders"
const val TAB_WATCHLIST = "Watchlist"
const val TAB_SIPS      = "SIPs"

// Intent extras
const val EXTRA_SYMBOL   = "symbol"
const val EXTRA_EXCHANGE = "exchange"
const val EXTRA_NAME     = "name"
const val EXTRA_SEGMENT  = "segment"

// SharedPreferences keys
const val PREF_NAME     = "tradingapp_prefs"
const val KEY_JWT_TOKEN = "jwt_token"
const val KEY_USER_NAME = "user_name"

// Kite API intervals
const val INTERVAL_1D = "5minute"
const val INTERVAL_1W = "30minute"
const val INTERVAL_1M = "day"
const val INTERVAL_3M = "day"
const val INTERVAL_1Y = "day"
const val INTERVAL_5Y = "week"
