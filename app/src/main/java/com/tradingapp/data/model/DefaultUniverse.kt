package com.tradingapp.data.model

// ─────────────────────────────────────────────────────────────────
//  DefaultUniverse — bundled, offline-available instrument list.
//
//  This is what fixes "blank screen when market is closed / first
//  launch / token expired": the app no longer needs a successful
//  network call to know WHICH stocks to display. It always knows the
//  universe; it only needs the network (or Room cache) to know their
//  current PRICES. If neither is available yet, a placeholder card
//  ("—" / "Fetching…") shows instead of the stock disappearing.
//
//  Mirrors the backend's hardcoded NIFTY50 list in market.js — keep
//  both in sync if you change one.
// ─────────────────────────────────────────────────────────────────
object DefaultUniverse {

    data class Instrument(val symbol: String, val name: String)

    val NIFTY50: List<Instrument> = listOf(
        Instrument("NSE:RELIANCE",   "Reliance Industries"),
        Instrument("NSE:TCS",        "Tata Consultancy Services"),
        Instrument("NSE:HDFCBANK",   "HDFC Bank"),
        Instrument("NSE:INFY",       "Infosys"),
        Instrument("NSE:ICICIBANK",  "ICICI Bank"),
        Instrument("NSE:HINDUNILVR", "Hindustan Unilever"),
        Instrument("NSE:SBIN",       "State Bank of India"),
        Instrument("NSE:BHARTIARTL", "Bharti Airtel"),
        Instrument("NSE:ITC",        "ITC"),
        Instrument("NSE:AXISBANK",   "Axis Bank"),
        Instrument("NSE:LT",         "Larsen & Toubro"),
        Instrument("NSE:BAJFINANCE", "Bajaj Finance"),
        Instrument("NSE:TATASTEEL",  "Tata Steel"),
        Instrument("NSE:TATAMOTORS", "Tata Motors"),
        Instrument("NSE:WIPRO",      "Wipro"),
        Instrument("NSE:ADANIENT",   "Adani Enterprises"),
        Instrument("NSE:HINDALCO",   "Hindalco Industries"),
        Instrument("NSE:JSWSTEEL",   "JSW Steel"),
        Instrument("NSE:TECHM",      "Tech Mahindra"),
        Instrument("NSE:KOTAKBANK",  "Kotak Mahindra Bank"),
    )

    val INDICES: List<Instrument> = listOf(
        Instrument("NSE:NIFTY 50",   "NIFTY 50"),
        Instrument("BSE:SENSEX",     "SENSEX"),
        Instrument("NSE:NIFTY BANK", "NIFTY BANK"),
        Instrument("NSE:INDIA VIX",  "INDIA VIX"),
    )

    fun symbols(): List<String> = NIFTY50.map { it.symbol }

    fun nameFor(symbol: String): String =
        (NIFTY50 + INDICES).firstOrNull { it.symbol == symbol }?.name
            ?: symbol.substringAfter(":")

    // Placeholder shown until a real price is cached — never hides the
    // stock, just shows it's "not priced yet" rather than making it vanish.
    fun placeholder(symbol: String): Quote = Quote(
        symbol   = symbol,
        exchange = symbol.substringBefore(":", "NSE"),
        name     = nameFor(symbol),
        ltp      = 0.0
    )
}
