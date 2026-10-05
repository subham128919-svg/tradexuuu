// routes/udf.js  —  TradingView UDF (Universal Data Feed) protocol
//
// This makes our backend speak the exact protocol that TradingView's
// full Charting Library expects. When you get the Charting Library
// (apply free at tradingview.com/HTML5-stock-forex-bitcoin-charting-library/),
// point its data feed URL to: https://your-server.com/udf
//
// Docs: https://github.com/tradingview/charting-library-tutorial
// FIX: /search and /history were still calling growwService — the same
// dead pre-Kite integration described in watchlist.js. GROWW_TOKEN is
// unset, so every history request errored ("Groww token not set") and
// the chart never received data/updates. Now uses the same Kite-backed
// path as /market/candles: instrumentService (MySQL) for search,
// candleStore (Kite + DB cache) for history.
const express = require('express');
const instrumentSvc = require('../services/instrumentService');
const candleStore    = require('../services/candleStore');
const { logger } = require('../config/logger');
const router  = express.Router();

// Configuration endpoint — tells TradingView what our feed supports
router.get('/config', (req, res) => {
  res.json({
    exchanges: [
      { value: 'NSE', name: 'NSE', desc: 'National Stock Exchange' },
      { value: 'BSE', name: 'BSE', desc: 'Bombay Stock Exchange' },
    ],
    symbols_types: [
      { name: 'Stock',         value: 'stock'  },
      { name: 'Index',         value: 'index'  },
      { name: 'Mutual Fund',   value: 'fund'   },
    ],
    supported_resolutions: ['1', '5', '15', '30', '60', 'D', 'W', 'M'],
    supports_time:         true,
    supports_marks:        false,
    supports_timescale_marks: false,
  });
});

// Server time
router.get('/time', (req, res) => res.send(String(Math.floor(Date.now() / 1000))));

// Symbol search
router.get('/search', async (req, res) => {
  const { query, limit = 10 } = req.query;
  try {
    const results = await instrumentSvc.searchInstruments(query);
    res.json(results.slice(0, limit).map(s => ({
      symbol:      `${s.exchange}:${s.symbol}`,
      full_name:   `${s.exchange}:${s.symbol}`,
      description: s.name,
      exchange:    s.exchange,
      type:        'stock',
    })));
  } catch (err) { res.json([]); }
});

// Symbol info
router.get('/symbols', async (req, res) => {
  const { symbol } = req.query;   // e.g. "NSE:RELIANCE"
  const [exchange, sym] = (symbol || '').split(':');
  try {
    res.json({
      name:         sym,
      full_name:    symbol,
      description:  sym,
      exchange:     exchange,
      type:         'stock',
      session:      '0915-1530',
      timezone:     'Asia/Kolkata',
      minmov:       5,
      pricescale:   100,
      has_intraday: true,
      supported_resolutions: ['1', '5', '15', '30', '60', 'D', 'W', 'M'],
      volume_precision: 0,
      data_status: 'streaming',
    });
  } catch (err) { res.status(404).json({ s: 'error', errmsg: err.message }); }
});

// Historical OHLCV data — the core data feed
// resolution: '1'|'5'|'15'|'30'|'60'|'D'|'W'|'M'
router.get('/history', async (req, res) => {
  const { symbol, resolution, from, to } = req.query;

  // Map TradingView resolution → our period
  const periodMap = { '1':'1D','5':'1D','15':'1D','30':'1W','60':'1W','D':'1M','W':'3M','M':'1Y' };
  const period    = periodMap[resolution] || '1M';

  try {
    // candleStore expects "EXCHANGE:SYMBOL" — which is exactly what
    // /udf/search now returns as `symbol`, so no splitting needed.
    const candles = await candleStore.getCandles(symbol, period);
    if (!candles.length) return res.json({ s: 'no_data' });

    // Filter by from/to timestamps
    const filtered = candles.filter(c => c.time >= Number(from) && c.time <= Number(to));
    const out      = filtered.length ? filtered : candles;

    res.json({
      s: 'ok',
      t: out.map(c => c.time),
      o: out.map(c => c.open),
      h: out.map(c => c.high),
      l: out.map(c => c.low),
      c: out.map(c => c.close),
      v: out.map(c => c.volume),
    });
  } catch (err) {
    logger.error('UDF /history error', err.message);
    res.json({ s: 'error', errmsg: err.message });
  }
});

module.exports = router;
