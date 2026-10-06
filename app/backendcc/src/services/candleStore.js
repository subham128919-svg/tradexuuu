// candleStore.js — DB-first candle storage (FIXED)
// Fix 1: bulk insert uses pool.query() not pool.execute() — mysql2
//         execute() does not support VALUES ? (array of arrays).
// Fix 2: explore/indices endpoint now calls this as fallback when
//         Redis is empty and Kite is unreachable.
// Fix 3 (NEW): the old staleness check compared the last stored candle
//         against `new Date(to)`, where `to` is just a "YYYY-MM-DD"
//         string — that parses to 00:00 UTC (~05:30 IST), which is
//         BEFORE almost every intraday candle timestamp from the same
//         day. So the instant ANY candle existed for "today",
//         (toDate - lastCandleTime) went negative, the staleness check
//         always passed, and the DB copy was served forever without
//         ever asking Kite again — freezing intraday data at whatever
//         was captured before a Kite outage (e.g. token not renewed
//         since last night) and never backfilling the gap even after
//         Kite became available again. Now compares the last stored
//         candle against the actual current time, so a genuine gap is
//         always detected and re-fetched. Because Kite's historical
//         endpoint returns Zerodha's own server-side record for the
//         whole requested range (unaffected by how long OUR token was
//         down), a single re-fetch naturally backfills whatever was
//         missed — no separate backfill job needed.
const db         = require('../config/database');
const kite        = require('./kiteService');
const { logger }  = require('../config/logger');

function periodToKite(period) {
  const now  = new Date();
  const fmt  = d => d.toISOString().split('T')[0];
  const ago  = (n) => { const d = new Date(now); d.setDate(d.getDate()-n);       return fmt(d); };
  const agoM = (n) => { const d = new Date(now); d.setMonth(d.getMonth()-n);     return fmt(d); };
  const agoY = (n) => { const d = new Date(now); d.setFullYear(d.getFullYear()-n); return fmt(d); };
  switch (period) {
    case '1D': return { interval: '5minute',  from: ago(1),   to: fmt(now) };
    case '1W': return { interval: '30minute', from: ago(7),   to: fmt(now) };
    case '1M': return { interval: 'day',      from: agoM(1),  to: fmt(now) };
    case '3M': return { interval: 'day',      from: agoM(3),  to: fmt(now) };
    case '1Y': return { interval: 'day',      from: agoY(1),  to: fmt(now) };
    case '5Y': return { interval: 'week',     from: agoY(5),  to: fmt(now) };
    default:   return { interval: 'day',      from: agoM(1),  to: fmt(now) };
  }
}

function rowsToCandles(rows) {
  return rows.map(r => ({
    time:   Math.floor(new Date(r.candle_time).getTime() / 1000),
    open:   parseFloat(r.open),
    high:   parseFloat(r.high),
    low:    parseFloat(r.low),
    close:  parseFloat(r.close),
    volume: Number(r.volume) || 0,
  }));
}

// How old the last stored candle is allowed to be before we re-check
// Kite. Intraday bars get a short leash (a couple of bar-widths) so a
// gap — e.g. Kite token expired overnight, restored mid-morning — gets
// noticed and backfilled quickly. Day/week bars only close once a day
// anyway, so a day's leash is plenty.
function maxStaleMs(interval) {
  switch (interval) {
    case '5minute':  return 10 * 60 * 1000;       // 10 min
    case '30minute': return 60 * 60 * 1000;       // 1 hour
    default:         return 24 * 60 * 60 * 1000;  // day, week
  }
}

async function getCandles(symbol, period) {
  const { interval, from, to } = periodToKite(period);
  const [rows] = await db.pool.query(
    'SELECT candle_time, open, high, low, close, volume FROM candles ' +
    'WHERE symbol = ? AND `interval` = ? AND candle_time BETWEEN ? AND ? ' +
    'ORDER BY candle_time ASC',
    [symbol, interval, from + ' 00:00:00', to + ' 23:59:59']
  );

  const hasData  = rows.length > 0;
  const lastTime = hasData ? new Date(rows[rows.length - 1].candle_time).getTime() : 0;
  const isFresh  = hasData && (Date.now() - lastTime) <= maxStaleMs(interval);

  if (isFresh) {
    logger.info('candleStore: served ' + rows.length + ' candles from DB for ' + symbol + '/' + period);
    return rowsToCandles(rows);
  }

  // DB stale, empty, or gapped — fetch from Kite (the full from→to
  // range, which self-heals any gap) and persist.
  try {
    const [exchange, sym] = symbol.split(':');
    const token   = await kite.getInstrumentToken(exchange, sym);
    const candles = await kite.getCandles(token, interval, from, to);
    if (candles.length > 0) {
      await storeCandles(symbol, interval, candles);
      logger.info('candleStore: fetched & stored ' + candles.length + ' candles from Kite for ' + symbol);
      return candles;
    }
    // Kite returned nothing new (e.g. genuinely no update yet) — don't
    // blank the chart if we already have something in the DB.
    return hasData ? rowsToCandles(rows) : candles;
  } catch (err) {
    logger.warn('candleStore: Kite fetch failed for ' + symbol + ': ' + err.message);
    if (hasData) {
      logger.info('candleStore: serving stale DB data as fallback');
      return rowsToCandles(rows);
    }
    throw err;
  }
}

async function storeCandles(symbol, interval, candles) {
  if (!candles.length) return;
  const CHUNK = 200;
  for (let i = 0; i < candles.length; i += CHUNK) {
    const chunk  = candles.slice(i, i + CHUNK);
    const values = chunk.map(c => [
      symbol, interval,
      new Date(c.time * 1000),
      c.open, c.high, c.low, c.close, c.volume || 0
    ]);
    // pool.query() — NOT pool.execute() — required for VALUES ? (array of arrays)
    await db.pool.query(
      'INSERT INTO candles (symbol, `interval`, candle_time, open, high, low, close, volume) VALUES ? ' +
      'ON DUPLICATE KEY UPDATE open=VALUES(open), high=VALUES(high), ' +
      'low=VALUES(low), close=VALUES(close), volume=VALUES(volume)',
      [values]
    );
  }
}

module.exports = { getCandles, storeCandles, periodToKite };
