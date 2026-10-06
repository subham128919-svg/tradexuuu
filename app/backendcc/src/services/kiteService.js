// src/services/kiteService.js  —  Zerodha Kite Connect API wrapper
// Key fix: uses getQuote() to find instrument tokens instead of
// getInstruments() which downloads a 100MB+ CSV — that caused "Connection is closed."
const { KiteConnect } = require('kiteconnect');
const redis           = require('../config/redis');
const { logger }      = require('../config/logger');

const kite = new KiteConnect({ api_key: process.env.KITE_API_KEY || '' });

// Call this after every Zerodha login (done by auth.js callback)
function setAccessToken(token) {
  kite.setAccessToken(token);
  logger.info('Kite access token set');
}

// Restore token from Redis on server start (survives PM2 restarts)
async function restoreSession() {
  try {
    const token = await redis.get('kite:access_token');
    if (token) {
      kite.setAccessToken(token);
      logger.info('Kite session restored from cache');
    } else {
      logger.warn('No Kite access token in cache — login at /api/v1/auth/kite-login');
    }
  } catch (e) {
    logger.warn('Could not restore Kite session:', e.message);
  }
}
restoreSession();

// ── Quotes ───────────────────────────────────────────────────────────
// symbols: ['NSE:RELIANCE', 'NSE:TCS']
async function getQuotes(symbols) {
  const cacheKey = `quotes:${symbols.sort().join(',')}`;
  try {
    const cached = await redis.get(cacheKey);
    if (cached) return JSON.parse(cached);
  } catch {}

  const quotes = await kite.getQuote(symbols);
  try {
    await redis.set(cacheKey, JSON.stringify(quotes), 'EX', 2);
  } catch {}
  return quotes;
}

// ── Instrument token lookup (using getQuote — fast, no file download) ──
// Old approach: kite.getInstruments(['NSE']) → downloads 100MB CSV — DO NOT USE
// New approach: kite.getQuote(['NSE:RELIANCE']) → tiny call, returns token
async function getInstrumentToken(exchange, symbol) {
  const cacheKey = `token:${exchange}:${symbol}`;
  try {
    const cached = await redis.get(cacheKey);
    if (cached) return parseInt(cached, 10);
  } catch {}

  // Get quote for this symbol — the response includes instrument_token
  const key    = `${exchange}:${symbol}`;
  const quotes = await kite.getQuote([key]);
  const q      = quotes[key];
  if (!q) throw new Error(`Instrument not found: ${key}`);

  const token = q.instrument_token;
  try {
    await redis.set(cacheKey, String(token), 'EX', 86400); // cache 24h
  } catch {}
  logger.info(`Instrument token for ${key}: ${token}`);
  return token;
}

// ── Historical candles ────────────────────────────────────────────────
// interval: 'minute'|'3minute'|'5minute'|'15minute'|'30minute'|'hour'|'day'|'week'
// from/to:  'YYYY-MM-DD'
async function getCandles(instrumentToken, interval, from, to) {
  const cacheKey = `candles:${instrumentToken}:${interval}:${from}:${to}`;
  try {
    const cached = await redis.get(cacheKey);
    if (cached) return JSON.parse(cached);
  } catch {}

  const data = await kite.getHistoricalData(instrumentToken, interval, from, to);
  // data: [{ date, open, high, low, close, volume }, ...]
  const candles = data.map(c => ({
    time:   Math.floor(new Date(c.date).getTime() / 1000),
    open:   c.open,
    high:   c.high,
    low:    c.low,
    close:  c.close,
    volume: c.volume || 0,
  }));

  const ttl = interval === 'day' || interval === 'week' ? 3600 : 60;
  try {
    await redis.set(cacheKey, JSON.stringify(candles), 'EX', ttl);
  } catch {}
  return candles;
}

// ── Search instruments (also uses getQuote, not instruments CSV) ──────
async function searchInstruments(query, exchange = 'NSE') {
  // Zerodha doesn't have a search API without downloading instruments.
  // We return a simple structure based on the query for now.
  // For production, download and cache instruments once per day.
  return [{ symbol: query.toUpperCase(), exchange, name: query.toUpperCase() }];
}

// ── Holdings ─────────────────────────────────────────────────────────
async function getHoldings() {
  const holdings = await kite.getHoldings();
  return holdings.map(h => ({
    symbol:          h.tradingsymbol,
    exchange:        h.exchange,
    quantity:        h.quantity,
    avgCost:         h.average_price,
    currentPrice:    h.last_price,
    currentValue:    h.quantity * h.last_price,
    pnl:             h.pnl,
    pnlPercent:      h.average_price > 0
                       ? ((h.last_price - h.average_price) / h.average_price * 100)
                       : 0,
    instrumentToken: h.instrument_token,
  }));
}

// ── Positions ─────────────────────────────────────────────────────────
async function getPositions() {
  const { day, net } = await kite.getPositions();
  const fmt = p => ({
    symbol:    p.tradingsymbol,
    exchange:  p.exchange,
    product:   p.product,
    quantity:  p.quantity,
    avgCost:   p.average_price,
    lastPrice: p.last_price,
    pnl:       p.pnl,
    unrealised: p.unrealised,
    realised:   p.realised,
  });
  return { day: day.map(fmt), net: net.map(fmt) };
}

// ── Orders ────────────────────────────────────────────────────────────
async function getOrders() {
  const orders = await kite.getOrders();
  return orders.map(o => ({
    id:             o.order_id,
    symbol:         o.tradingsymbol,
    exchange:       o.exchange,
    type:           o.transaction_type,
    product:        o.product,
    orderType:      o.order_type,
    quantity:       o.quantity,
    filledQuantity: o.filled_quantity,
    price:          o.price,
    avgPrice:       o.average_price,
    status:         o.status,
    timestamp:      o.order_timestamp,
  }));
}

// ── Place order ────────────────────────────────────────────────────────
async function placeOrder(params) {
  return kite.placeOrder(params.variety || 'regular', {
    exchange:         params.exchange,
    tradingsymbol:    params.symbol,
    transaction_type: params.transactionType,
    quantity:         params.quantity,
    product:          params.product,
    order_type:       params.orderType,
    price:            params.price || 0,
  });
}

module.exports = {
  kite,
  setAccessToken,
  getQuotes,
  getInstrumentToken,
  getCandles,
  searchInstruments,
  getHoldings,
  getPositions,
  getOrders,
  placeOrder,
};
