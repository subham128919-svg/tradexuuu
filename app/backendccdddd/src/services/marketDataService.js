// marketDataService.js (FIXED: updatedAt = Date.now() not ISO string)
const kite            = require('./kiteService');
const tickCache        = require('./tickCache');
const { getMarketStatus } = require('./marketStatusService');
const { logger }       = require('../config/logger');

const LIVE_POLL_STALE_MS = 3000;

async function getQuotesSmart(symbols) {
  const status = getMarketStatus();
  const cached = await tickCache.getTicks(symbols);
  const now    = Date.now();

  const needsFresh = status.isOpen
    ? symbols.filter(s => !cached[s] || (now - (cached[s].updatedAt || 0)) > LIVE_POLL_STALE_MS)
    : symbols.filter(s => !cached[s]);

  if (needsFresh.length > 0) {
    try {
      const fresh = await kite.getQuotes(needsFresh);
      for (const [sym, q] of Object.entries(fresh)) {
        const tick = {
          symbol:    sym,
          ltp:       q.last_price        || 0,
          open:      q.ohlc?.open        || 0,
          high:      q.ohlc?.high        || 0,
          low:       q.ohlc?.low         || 0,
          close:     q.ohlc?.close       || 0,
          change:    q.net_change        || 0,
          changePct: parseFloat(((q.net_change / (q.ohlc?.close || 1)) * 100).toFixed(2)),
          volume:    q.volume_traded     || 0,
          updatedAt: Date.now(),  // FIX: Unix ms, NOT ISO string (caused NumberFormatException)
        };
        await tickCache.setTick(sym, tick);
        cached[sym] = tick;
      }
    } catch (err) {
      logger.warn('getQuotesSmart: Kite fetch failed, serving cache -- ' + err.message);
    }
  }
  return { ticks: cached, marketStatus: status };
}

module.exports = { getQuotesSmart };
