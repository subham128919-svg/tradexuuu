// backgroundTracker.js
// Always-on market recorder. Runs INDEPENDENTLY of WebSocket subscribers.
// Polls Kite every 2s during market hours for ALL tracked symbols.
// When a user opens any stock, it gets added here permanently.
const db            = require('../config/database');
const marketDataSvc  = require('./marketDataService');
const candleStore     = require('./candleStore');
const { getMarketStatus } = require('./marketStatusService');
const { logger }     = require('../config/logger');

// Default universe loaded at startup; grows as users open stocks
const trackedSet = new Set();
let   pollTimer  = null;
const POLL_MS    = 2000;
const CANDLE_PERIODS = ['1D','1W','1M','3M','1Y','5Y'];

// ── Startup: load from DB ─────────────────────────────────────
async function init() {
  try {
    // Load admin universe
    const [uRows] = await db.pool.query("SELECT symbol FROM admin_universe WHERE is_active=1");
    uRows.forEach(r => trackedSet.add(r.symbol));
    // Load user-tracked symbols
    const [tRows] = await db.pool.query("SELECT symbol FROM tracked_symbols");
    tRows.forEach(r => trackedSet.add(r.symbol));
    logger.info('BackgroundTracker: init with ' + trackedSet.size + ' symbols');
    startPolling();
  } catch (e) {
    logger.error('BackgroundTracker init error: ' + e.message);
  }
}

// ── Add a symbol (called when any user opens a stock) ─────────
async function addSymbol(symbol, addedBy = 'user') {
  if (trackedSet.has(symbol)) return; // already tracked
  trackedSet.add(symbol);
  try {
    await db.pool.query(
      'INSERT IGNORE INTO tracked_symbols (symbol, added_by) VALUES (?,?)',
      [symbol, addedBy]
    );
    logger.info('BackgroundTracker: permanently added ' + symbol);
    // Async seed historical data (don't await — fire-and-forget)
    seedHistoricalAsync(symbol);
  } catch (e) {
    logger.warn('BackgroundTracker.addSymbol error: ' + e.message);
  }
}

// ── Seed all historical periods for a newly-tracked symbol ────
async function seedHistoricalAsync(symbol) {
  for (const period of CANDLE_PERIODS) {
    try {
      await candleStore.getCandles(symbol, period);
      await new Promise(r => setTimeout(r, 500)); // gentle pacing
    } catch (e) {
      logger.warn('BackgroundTracker seed ' + symbol + '/' + period + ': ' + e.message);
    }
  }
  logger.info('BackgroundTracker: historical seeded for ' + symbol);
}

// ── Polling loop — runs regardless of active WebSocket clients ──
function startPolling() {
  if (pollTimer) return;
  pollTimer = setInterval(async () => {
    if (!getMarketStatus().isOpen) return; // skip when market closed
    const symbols = [...trackedSet];
    if (!symbols.length) return;
    // Batch into groups of 100 to avoid huge Kite requests
    for (let i = 0; i < symbols.length; i += 100) {
      try {
        await marketDataSvc.getQuotesSmart(symbols.slice(i, i + 100));
      } catch (e) {
        // non-fatal — next tick will retry
      }
    }
  }, POLL_MS);
  logger.info('BackgroundTracker: polling started (' + POLL_MS + 'ms interval)');
}

function getTrackedSymbols() { return [...trackedSet]; }

module.exports = { init, addSymbol, getTrackedSymbols, seedHistoricalAsync };
