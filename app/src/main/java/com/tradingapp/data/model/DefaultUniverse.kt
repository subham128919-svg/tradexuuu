package com.tradingapp.data.model

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
        Instrument("NSE:NIFTY 50",       "NIFTY 50"),
        Instrument("BSE:SENSEX",          "SENSEX"),
        Instrument("NSE:NIFTY BANK",      "NIFTY BANK"),
        Instrument("NSE:INDIA VIX",       "INDIA VIX"),
        Instrument("NSE:NIFTY IT",        "NIFTY IT"),
        Instrument("NSE:NIFTY PHARMA",    "NIFTY PHARMA"),
        Instrument("NSE:NIFTY FMCG",      "NIFTY FMCG"),
        Instrument("NSE:NIFTY AUTO",      "NIFTY AUTO"),
        Instrument("NSE:NIFTY METAL",     "NIFTY METAL"),
        Instrument("NSE:NIFTY MIDCAP 100","NIFTY MIDCAP 100"),
    )

    fun symbols(): List<String> = NIFTY50.map { it.symbol }

    fun indexSymbols(): List<String> = INDICES.map { it.symbol }

    fun nameFor(symbol: String): String =
        (NIFTY50 + INDICES).firstOrNull { it.symbol == symbol }?.name
            ?: symbol.substringAfter(":")

    fun placeholder(symbol: String): Quote = Quote(
        symbol   = symbol,
        exchange = symbol.substringBefore(":", "NSE"),
        name     = nameFor(symbol),
        ltp      = 0.0
    )
}
