// universe.js -- the set of instruments this app actively tracks.
// Mirrors DefaultUniverse.NIFTY50 on the Android side -- keep both in
// sync if you change one. Used by: /market/explore, the WebSocket
// broadcast default set, and the post-close candle sync scheduler.
module.exports = [
  'NSE:RELIANCE', 'NSE:TCS', 'NSE:HDFCBANK', 'NSE:INFY', 'NSE:ICICIBANK',
  'NSE:HINDUNILVR', 'NSE:SBIN', 'NSE:BHARTIARTL', 'NSE:ITC', 'NSE:AXISBANK',
  'NSE:LT', 'NSE:BAJFINANCE', 'NSE:TATASTEEL', 'NSE:TATAMOTORS', 'NSE:WIPRO',
  'NSE:ADANIENT', 'NSE:HINDALCO', 'NSE:JSWSTEEL', 'NSE:TECHM', 'NSE:KOTAKBANK',
];
