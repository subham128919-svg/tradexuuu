// routes/market.js — PUBLIC endpoints
// FIX: every updatedAt sent to the client is now guaranteed a numeric
// epoch (Date.now() or explicit .getTime()), never a raw MySQL Date
// object or ISO string -- that was the root cause of the client-side
// NumberFormatException that blocked all quote updates.
const express             = require('express');
const marketDataService    = require('../services/marketDataService');
const candleStore           = require('../services/candleStore');
const tickCache               = require('../services/tickCache');
const topMoversService          = require('../services/topMoversService');
const { getMarketStatus }        = require('../services/marketStatusService');
const { getFundamentals }         = require('../services/fundamentalsService');
const { logger }                   = require('../config/logger');
const router                        = express.Router();
const db                             = require('../config/database');

let instrumentSvc;
try { instrumentSvc = require('../services/instrumentService'); } catch { instrumentSvc = null; }
const optionChainService = require('../services/optionChainService');

function epochMs(v) {
  if (typeof v === 'number') return v;
  if (v instanceof Date) return v.getTime();
  if (typeof v === 'string') {
    const t = new Date(v).getTime();
    return Number.isNaN(t) ? Date.now() : t;
  }
  return Date.now();
}

async function enrichChangePct(ticks) {
  const zeros = Object.entries(ticks).filter(([,t]) => t.change === 0 && t.ltp > 0).map(([s]) => s);
  for (const sym of zeros) {
    try {
      const [rows] = await db.pool.query(
        'SELECT close FROM candles WHERE symbol=? AND `interval`=? ORDER BY candle_time DESC LIMIT 2', [sym,'day']);
      if (rows.length >= 2) {
        const prev = parseFloat(rows[1].close);
        ticks[sym].change    = parseFloat((ticks[sym].ltp - prev).toFixed(2));
        ticks[sym].changePct = parseFloat(((ticks[sym].ltp - prev) / prev * 100).toFixed(2));
      }
    } catch {}
  }
}

async function getUniverse(section) {
  const [rows] = await db.pool.query(
    'SELECT symbol FROM admin_universe WHERE is_active=1 AND (section=? OR section="both") ORDER BY display_order',
    [section]
  );
  return rows.map(r => r.symbol);
}

router.get('/status', (req, res) => res.json(getMarketStatus()));

router.get('/quotes', async (req, res) => {
  const symbols = req.query.symbols?.split(',').map(s => s.trim());
  if (!symbols?.length) return res.status(400).json({ error: 'symbols required' });
  try {
    const { ticks, marketStatus } = await marketDataService.getQuotesSmart(symbols);
    await enrichChangePct(ticks);
    const data = symbols.map(s => ticks[s]
      ? { ...ticks[s], updatedAt: epochMs(ticks[s].updatedAt), name: s.split(':')[1] }
      : null).filter(Boolean);
    res.json({ data, marketStatus });
  } catch (e) { res.status(500).json({ error: e.message }); }
});

router.get('/candles', async (req, res) => {
  const { symbol, period = '1M' } = req.query;
  if (!symbol) return res.status(400).json({ error: 'symbol required' });
  try {
    const data = await candleStore.getCandles(symbol, period);
    res.json({ data });
  } catch (e) { res.status(500).json({ error: e.message }); }
});

router.get('/stock/:exchange/:symbol', async (req, res) => {
  const fullSym = req.params.exchange + ':' + req.params.symbol;
  try {
    const { ticks, marketStatus } = await marketDataService.getQuotesSmart([fullSym]);
    await enrichChangePct(ticks);
    const tick = ticks[fullSym];
    if (!tick) return res.status(404).json({ error: 'No data for ' + fullSym });
    res.json({ data: { ...tick, updatedAt: epochMs(tick.updatedAt), marketStatus } });
  } catch (e) {
    const cached = await tickCache.getTick(fullSym);
    if (cached) return res.json({ data: { ...cached, updatedAt: epochMs(cached.updatedAt), marketStatus: getMarketStatus() } });
    res.status(500).json({ error: e.message });
  }
});

router.get('/fundamentals/:exchange/:symbol', async (req, res) => {
  const sym = req.params.exchange + ':' + req.params.symbol;
  try {
    const data = await getFundamentals(sym);
    res.json({ data: data || null });
  } catch (e) { res.status(500).json({ error: e.message }); }
});

router.get('/search', async (req, res) => {
  const { q, exchange } = req.query;
  if (!q) return res.status(400).json({ error: 'q required' });
  try {
    if (!instrumentSvc) return res.json({ data: [] });
    res.json({ data: await instrumentSvc.searchInstruments(q, exchange) });
  } catch (e) { res.json({ data: [], error: e.message }); }
});

router.get('/indices', async (req, res) => {
  const MAIN = ['NSE:NIFTY 50','BSE:SENSEX','NSE:NIFTY BANK','NSE:INDIA VIX'];
  try {
    const { ticks, marketStatus } = await marketDataService.getQuotesSmart(MAIN);
    await enrichChangePct(ticks);
    const data = MAIN.map(s => ticks[s]
      ? { ...ticks[s], updatedAt: epochMs(ticks[s].updatedAt), name: s.split(':')[1], isPositive: (ticks[s].change || 0) >= 0 }
      : null).filter(Boolean);
    res.json({ data, marketStatus });
  } catch (e) { res.status(500).json({ error: e.message }); }
});

router.get('/indices/all', async (req, res) => {
  try {
    const symbols = await getUniverse('fno');
    const { ticks, marketStatus } = await marketDataService.getQuotesSmart(symbols);
    await enrichChangePct(ticks);
    const data = symbols.map(s => {
      const t = ticks[s];
      return t ? { ...t, updatedAt: epochMs(t.updatedAt), name: s.split(':')[1], isPositive: (t.change || 0) >= 0 } : null;
    }).filter(Boolean);
    res.json({ data, marketStatus });
  } catch (e) { res.status(500).json({ error: e.message }); }
});

router.get('/top-movers', async (req, res) => {
  try {
    let result = await topMoversService.getFromCache();
    if (!result.gainers.length && !result.losers.length)
      result = await topMoversService.computeAndCache();
    res.json({ ...result, marketStatus: getMarketStatus() });
  } catch (e) { res.status(500).json({ error: e.message }); }
});

// FIX: the DB fallback here used to send raw `r.candle_time` (a MySQL
// Date object) as updatedAt, which JSON-serializes to an ISO string --
// this was the actual root cause of the crash. Now always epochMs().
router.get('/explore', async (req, res) => {
  const { type = 'trending' } = req.query;
  try {
    const UNIVERSE = await getUniverse('explore');
    const { ticks, marketStatus } = await marketDataService.getQuotesSmart(UNIVERSE);
    await enrichChangePct(ticks);
    let list = Object.values(ticks).map(t => ({
      ...t, updatedAt: epochMs(t.updatedAt), name: t.symbol.split(':')[1], isPositive: (t.change||0) >= 0
    }));
    if (!list.length) {
      const [rows] = await db.pool.query(
        'SELECT c.symbol, c.close, c.candle_time FROM candles c INNER JOIN (SELECT symbol, MAX(candle_time) as mt FROM candles WHERE `interval`=? AND symbol IN (?) GROUP BY symbol) m ON c.symbol=m.symbol AND c.candle_time=m.mt WHERE c.`interval`=?',
        ['day', UNIVERSE, 'day']
      );
      list = rows.map(r => ({
        symbol: r.symbol, name: r.symbol.split(':')[1], ltp: parseFloat(r.close),
        change: 0, changePct: 0, isPositive: true,
        updatedAt: epochMs(r.candle_time),   // FIX: was raw Date object before
      }));
    }
    if (type === 'gainers')  list = list.filter(s => s.changePct > 0).sort((a,b) => b.changePct - a.changePct);
    if (type === 'losers')   list = list.filter(s => s.changePct < 0).sort((a,b) => a.changePct - b.changePct);
    if (type === 'trending') list = list.sort((a,b) => Math.abs(b.changePct) - Math.abs(a.changePct));
    res.json({ data: list.slice(0, 20), marketStatus });
  } catch (e) { res.status(500).json({ error: e.message }); }
});

router.get('/universe', async (req, res) => {
  try {
    const { section } = req.query;
    const where = section ? 'AND (section=? OR section="both")' : '';
    const params = section ? [section] : [];
    const [rows] = await db.pool.query(
      'SELECT * FROM admin_universe WHERE is_active=1 ' + where + ' ORDER BY section, display_order', params);
    res.json({ data: rows });
  } catch (e) { res.status(500).json({ error: e.message }); }
});

router.post('/universe', async (req, res) => {
  const { symbol, name, section, display_order } = req.body;
  if (!symbol) return res.status(400).json({ error: 'symbol required' });
  try {
    await db.pool.query(
      'INSERT INTO admin_universe (symbol, name, section, display_order) VALUES (?,?,?,?) ON DUPLICATE KEY UPDATE name=VALUES(name), section=VALUES(section), display_order=VALUES(display_order), is_active=1',
      [symbol, name || symbol.split(':')[1], section || 'explore', display_order || 99]
    );
    res.json({ ok: true });
  } catch (e) { res.status(500).json({ error: e.message }); }
});

router.delete('/universe/:symbol', async (req, res) => {
  try {
    await db.pool.query('UPDATE admin_universe SET is_active=0 WHERE symbol=?', [decodeURIComponent(req.params.symbol)]);
    res.json({ ok: true });
  } catch (e) { res.status(500).json({ error: e.message }); }
});

// ── F&O: Option Chain ──────────────────────────────────────────────
// GET /api/v1/market/option-expiries?underlying=NIFTY
// Returns available expiry dates for an underlying, nearest first.
router.get('/option-expiries', async (req, res) => {
  const { underlying } = req.query;
  if (!underlying) return res.status(400).json({ error: 'underlying required' });
  try {
    const expiries = await optionChainService.getExpiries(underlying);
    res.json({ underlying: underlying.toUpperCase(), expiries });
  } catch (e) { res.status(500).json({ error: e.message }); }
});

// GET /api/v1/market/option-chain?underlying=NIFTY&expiry=2026-08-28
// Returns the CE/PE strike ladder with live prices for that expiry.
router.get('/option-chain', async (req, res) => {
  const { underlying, expiry } = req.query;
  if (!underlying || !expiry) return res.status(400).json({ error: 'underlying and expiry required' });
  try {
    const chain = await optionChainService.getChain(underlying, expiry);
    res.json(chain);
  } catch (e) { res.status(500).json({ error: e.message }); }
});

// GET /api/v1/market/has-options?symbol=NSE:RELIANCE  (or NSE:NIFTY 50)
// Reverse of optionChainService.js's SPOT_SYMBOL_MAP — converts the
// spot trading symbol to the NFO underlying name stored in the DB.
const SPOT_TO_UNDERLYING = {
  'NIFTY 50': 'NIFTY', 'NIFTY BANK': 'BANKNIFTY',
  'NIFTY FIN SERVICE': 'FINNIFTY', 'NIFTY MIDCAP 100': 'MIDCPNIFTY',
  'SENSEX': 'SENSEX', 'BANKEX': 'BANKEX',
};
router.get('/has-options', async (req, res) => {
  const { symbol } = req.query;
  if (!symbol) return res.status(400).json({ error: 'symbol required' });
  try {
    const bare = symbol.includes(':') ? symbol.split(':')[1] : symbol;
    const underlying = SPOT_TO_UNDERLYING[bare] || bare;
    const candidates = [underlying, bare];
    const [rows] = await db.pool.query(
      'SELECT 1 FROM instruments WHERE underlying IN (?) AND exchange=\'NFO\' AND expiry >= CURDATE() LIMIT 1',
      [candidates]
    );
    res.json({ hasOptions: rows.length > 0 });
  } catch (e) { res.json({ hasOptions: false }); }
});

// GET /api/v1/market/lot-size?symbol=NIFTY24AUG24000CE
router.get("/lot-size", async (req, res) => {
  const { symbol } = req.query;
  if (!symbol) return res.status(400).json({ error: "symbol required" });
  try {
    const bare = symbol.includes(":") ? symbol.split(":")[1] : symbol;
    const lotSize = await optionChainService.getLotSizeForSymbol(bare);
    res.json({ lotSize: lotSize || 1 });
  } catch (e) { res.json({ lotSize: 1 }); }
});
module.exports = router;
