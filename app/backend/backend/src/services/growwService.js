// ─────────────────────────────────────────────────────────────────
//  growwService.js  —  Groww personal API integration
//
//  HOW TO GET YOUR TOKEN (do this once per day):
//  1. Open https://groww.in in Chrome and log in
//  2. Press F12 → Network tab → Filter: "api"
//  3. Click any stock/fund → find an API call like /trading-info
//  4. Copy the "Authorization: Bearer xxxx..." header value (just the token part)
//  5. Paste it in your .env as GROWW_TOKEN=xxxx
//  6. Call POST /api/v1/auth/update-groww-token to hot-reload without restarting
//
//  Token expires at midnight IST. Update it daily while testing.
//  Once you buy Zerodha Kite (₹500/month), swap this service for kiteService.js.
// ─────────────────────────────────────────────────────────────────
const axios        = require('axios');
const redis        = require('../config/redis');
const { logger }   = require('../config/logger');

const BASE = 'https://groww.in';

// Current token (loaded from env on startup, can be hot-updated via API)
let currentToken = process.env.GROWW_TOKEN || '';

function setToken(token) {
  currentToken = token.trim().replace(/^Bearer\s+/i, '');
  logger.info('Groww token updated');
}

function getToken() {
  if (!currentToken) throw new Error('Groww token not set. Call POST /api/v1/auth/update-groww-token or set GROWW_TOKEN in .env');
  return currentToken;
}

// Shared Axios instance
function client() {
  return axios.create({
    baseURL: BASE,
    timeout: 8000,
    headers: {
      'Authorization': `Bearer ${getToken()}`,
      'User-Agent':    'Mozilla/5.0 (Android)',
      'Accept':        'application/json',
      'Origin':        'https://groww.in',
      'Referer':       'https://groww.in/',
    },
  });
}

// ── Search ────────────────────────────────────────────────────────
// Returns list of instruments with their Groww searchId
async function searchInstruments(query, exchange = 'NSE') {
  const cacheKey = `groww:search:${query}`;
  const cached   = await redis.get(cacheKey).catch(() => null);
  if (cached) return JSON.parse(cached);

  const res = await client().get('/v1/api/search/v3/query/global/all', {
    params: { page: 0, query, size: 20 }
  });

  const items = res.data?.data?.content || [];
  const results = items
    .filter(s => s.nseScriptSymbol || s.bseScriptCode)
    .map(s => ({
      searchId: s.searchId,         // e.g. "NSE_EQ|INE002A01018"
      symbol:   s.nseScriptSymbol || s.bseScriptCode,
      name:     s.companyName || s.schemeName,
      exchange: s.nseScriptSymbol ? 'NSE' : 'BSE',
      type:     s.type || 'EQ',     // EQ, MF, INDEX
    }));

  await redis.set(cacheKey, JSON.stringify(results), 'EX', 300).catch(() => {});
  return results;
}

// ── Get or cache a searchId for symbol ───────────────────────────
async function getSearchId(exchange, symbol) {
  const cacheKey = `groww:sid:${exchange}:${symbol}`;
  const cached   = await redis.get(cacheKey).catch(() => null);
  if (cached) return cached;

  const results = await searchInstruments(symbol, exchange);
  const match   = results.find(r => r.symbol === symbol);
  if (!match) throw new Error(`Groww: instrument not found: ${exchange}:${symbol}`);

  await redis.set(cacheKey, match.searchId, 'EX', 86400).catch(() => {}); // 24h
  return match.searchId;
}

// ── Quotes ────────────────────────────────────────────────────────
// symbols: ['NSE:RELIANCE', 'NSE:TCS', ...]
async function getQuotes(symbols) {
  const cacheKey = `groww:quotes:${symbols.sort().join(',')}`;
  const cached   = await redis.get(cacheKey).catch(() => null);
  if (cached) return JSON.parse(cached);

  const quotes = await Promise.allSettled(
    symbols.map(async (sym) => {
      const [exchange, symbol] = sym.split(':');
      const searchId = await getSearchId(exchange, symbol);
      const res = await client().get(`/v1/api/stocks/${encodeURIComponent(searchId)}/trading-info`);
      const d   = res.data?.liveData || res.data;
      return {
        symbol:    sym,
        ltp:       d.ltp   || d.lastTradedPrice  || 0,
        open:      d.open  || d.openPrice         || 0,
        high:      d.high  || d.dayHigh           || 0,
        low:       d.low   || d.dayLow            || 0,
        close:     d.previousClose                || 0,
        change:    d.netChange || (d.ltp - d.previousClose) || 0,
        changePct: d.percentChange                || 0,
        volume:    d.tradedVolume || d.volume      || 0,
        name:      d.companyName || symbol,
      };
    })
  );

  const result = {};
  quotes.forEach((r, i) => {
    if (r.status === 'fulfilled') result[symbols[i]] = r.value;
    else logger.warn(`Quote failed for ${symbols[i]}: ${r.reason?.message}`);
  });

  await redis.set(cacheKey, JSON.stringify(result), 'EX', 2).catch(() => {}); // 2s cache
  return result;
}

// ── Historical Candles ────────────────────────────────────────────
// interval: '1D'|'1W'|'1M'|'3M'|'1Y'|'5Y'   (our period, mapped to Groww resolution)
async function getCandles(exchange, symbol, period) {
  const cacheKey = `groww:candles:${exchange}:${symbol}:${period}`;
  const cached   = await redis.get(cacheKey).catch(() => null);
  if (cached) return JSON.parse(cached);

  const searchId = await getSearchId(exchange, symbol);

  const now = Date.now();
  const { resolution, startMs } = periodToGroww(period, now);

  try {
    const res = await client().get(`/v1/api/stocks/${encodeURIComponent(searchId)}/chart`, {
      params: { endDate: now, startDate: startMs, resolution }
    });

    // Groww returns: { candles: [[time_ms, open, high, low, close, volume], ...] }
    const raw = res.data?.candles || res.data?.data?.candles || [];
    const candles = raw.map(c => ({
      time:   Math.floor(c[0] / 1000),   // ms → seconds for TradingView
      open:   c[1],
      high:   c[2],
      low:    c[3],
      close:  c[4],
      volume: c[5] || 0,
    })).filter(c => c.open > 0);

    const ttl = period === '1D' ? 30 : 3600;
    await redis.set(cacheKey, JSON.stringify(candles), 'EX', ttl).catch(() => {});
    return candles;
  } catch (err) {
    logger.error('Groww candles error', { symbol, period, err: err.message });
    throw err;
  }
}

// Map our period strings to Groww resolution + start timestamp
function periodToGroww(period, now) {
  const MS = { m: 60000, h: 3600000, D: 86400000, W: 604800000, M: 2592000000 };
  switch (period) {
    case '1D': return { resolution: '5m',   startMs: now - 1  * MS.D };
    case '1W': return { resolution: '1h',   startMs: now - 7  * MS.D };
    case '1M': return { resolution: '1D',   startMs: now - 30 * MS.D };
    case '3M': return { resolution: '1D',   startMs: now - 90 * MS.D };
    case '1Y': return { resolution: '1W',   startMs: now - 365 * MS.D };
    case '5Y': return { resolution: '1M',   startMs: now - 5 * 365 * MS.D };
    default:   return { resolution: '1D',   startMs: now - 30 * MS.D };
  }
}

// ── Indices ───────────────────────────────────────────────────────
const INDEX_SEARCH_IDS = {
  'NSE:NIFTY 50':    'NSE_INDEX|NIFTY 50',
  'NSE:NIFTY BANK':  'NSE_INDEX|NIFTY BANK',
  'BSE:SENSEX':      'BSE_INDEX|S&P BSE SENSEX',
  'NSE:INDIA VIX':   'NSE_INDEX|India VIX',
};

async function getIndexQuotes() {
  const entries = Object.entries(INDEX_SEARCH_IDS);
  const results = await Promise.allSettled(
    entries.map(async ([sym, searchId]) => {
      const res = await client().get(`/v1/api/stocks/${encodeURIComponent(searchId)}/trading-info`);
      const d   = res.data?.liveData || res.data;
      return {
        symbol:    sym,
        name:      sym.split(':')[1],
        ltp:       d.ltp || d.lastTradedPrice || 0,
        change:    d.netChange || 0,
        changePct: d.percentChange || 0,
        open:      d.open || 0, high: d.high || 0, low: d.low || 0, close: d.previousClose || 0,
        isPositive: (d.percentChange || 0) >= 0,
      };
    })
  );
  return results.filter(r => r.status === 'fulfilled').map(r => r.value);
}

// ── Portfolio: Holdings ───────────────────────────────────────────
async function getHoldings() {
  const res      = await client().get('/v1/api/holdings/');
  const holdings = res.data?.data || res.data || [];
  return holdings.map(h => ({
    symbol:        h.tradingSymbol || h.scripCode,
    exchange:      h.exchange || 'NSE',
    quantity:      h.quantity || h.holdings || 0,
    avgCost:       h.avgPrice || h.averagePrice || 0,
    currentPrice:  h.ltp || h.currentMarketPrice || 0,
    currentValue:  (h.quantity || 0) * (h.ltp || 0),
    pnl:           h.pnl || h.unrealizedPnl || 0,
    pnlPercent:    h.pnlPercent || 0,
  }));
}

// ── Portfolio: Positions ──────────────────────────────────────────
async function getPositions() {
  const res       = await client().get('/v1/api/equity/positions');
  const positions = res.data?.data || res.data || [];
  return positions.map(p => ({
    symbol:    p.tradingSymbol || p.scrip,
    exchange:  p.exchange || 'NSE',
    product:   p.product || 'MIS',
    quantity:  p.quantity || 0,
    avgCost:   p.avgPrice || 0,
    lastPrice: p.ltp || 0,
    pnl:       p.pnl || 0,
    unrealised: p.unrealizedPnl || 0,
    realised:  p.realizedPnl || 0,
  }));
}

// ── Orders ────────────────────────────────────────────────────────
async function getOrders() {
  const res    = await client().get('/v1/api/equity/orders');
  const orders = res.data?.data || res.data || [];
  return orders.map(o => ({
    id:             o.orderId || o.id,
    symbol:         o.tradingSymbol || o.scrip,
    exchange:       o.exchange || 'NSE',
    type:           (o.transactionType || o.side || '').toUpperCase(),
    product:        o.product || 'MIS',
    orderType:      o.orderType || 'MARKET',
    quantity:       o.quantity || 0,
    filledQuantity: o.filledQuantity || 0,
    price:          o.price || 0,
    avgPrice:       o.avgPrice || 0,
    status:         (o.status || '').toUpperCase(),
    timestamp:      o.createdAt || o.timestamp || '',
  }));
}

module.exports = {
  setToken, getToken,
  searchInstruments,
  getSearchId,
  getQuotes,
  getCandles,
  getIndexQuotes,
  getHoldings,
  getPositions,
  getOrders,
};
