// routes/tracking.js — called by Android when user opens any stock
const express  = require('express');
const tracker   = require('../services/backgroundTracker');
const candleStore = require('../services/candleStore');
const router   = express.Router();

// POST /api/v1/tracking/add  { symbol: "NSE:TCS" }
// Called the moment a stock detail screen opens.
// Adds symbol to permanent background tracking + seeds historical data.
router.post('/add', async (req, res) => {
  const { symbol } = req.body;
  if (!symbol) return res.status(400).json({ error: 'symbol required' });
  await tracker.addSymbol(symbol, 'user');
  res.json({ ok: true, symbol, tracked: tracker.getTrackedSymbols().length });
});

// GET /api/v1/tracking/list
router.get('/list', (req, res) => {
  res.json({ symbols: tracker.getTrackedSymbols() });
});

module.exports = router;
