// routes/watchlist.js  — MySQL version
// FIX: this route was still calling growwService (a leftover from before
// the app had a Kite subscription — see growwService.js header comment:
// "Once you buy Zerodha Kite, swap this service for kiteService.js").
// GROWW_TOKEN is unset in .env and there is no route wired up to refresh
// it, so groww.getQuotes() always threw "Groww token not set" internally.
// That rejection was swallowed by `.catch(() => ({}))`, so every watchlist
// row silently rendered ltp:0 / changePct:0 forever — indistinguishable
// from "live data not updating". Switched to marketDataService, the same
// Kite-backed pipeline market.js and priceStream.js already use.
const express  = require('express');
const auth     = require('../middleware/auth');
const db       = require('../config/database');
const marketDataService = require('../services/marketDataService');
const router   = express.Router();

router.get('/', auth, async (req, res) => {
  try {
    const { rows } = await db.query(
      'SELECT * FROM watchlist WHERE user_id = ? ORDER BY position, added_at',
      [req.user.userId]
    );
    if (!rows.length) return res.json({ data: [] });
    const symbols = rows.map(r => `${r.exchange}:${r.symbol}`);
    const { ticks } = await marketDataService.getQuotesSmart(symbols).catch(() => ({ ticks: {} }));
    const data = rows.map(r => {
      const key = `${r.exchange}:${r.symbol}`;
      const q   = ticks[key];
      return { ...r, ltp: q?.ltp || 0, changePct: q?.changePct || 0 };
    });
    res.json({ data });
  } catch (err) { res.status(500).json({ error: err.message }); }
});

router.post('/', auth, async (req, res) => {
  const { symbol, exchange = 'NSE', listName = 'My list 1' } = req.body;
  try {
    // MySQL: INSERT IGNORE skips if duplicate (same as PostgreSQL's ON CONFLICT DO NOTHING)
    await db.query(
      'INSERT IGNORE INTO watchlist (user_id, symbol, exchange, list_name) VALUES (?, ?, ?, ?)',
      [req.user.userId, symbol, exchange, listName]
    );
    res.json({ data: { symbol, exchange, listName } });
  } catch (err) { res.status(500).json({ error: err.message }); }
});

router.delete('/:id', auth, async (req, res) => {
  try {
    await db.query('DELETE FROM watchlist WHERE id = ? AND user_id = ?',
      [req.params.id, req.user.userId]);
    res.json({ success: true });
  } catch (err) { res.status(500).json({ error: err.message }); }
});

module.exports = router;
