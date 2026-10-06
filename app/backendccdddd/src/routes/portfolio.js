const express = require('express');
const auth    = require('../middleware/auth');
const kite    = require('../services/kiteService');
const router  = express.Router();

router.get('/holdings', auth, async (req, res) => {
  try {
    const holdings    = await kite.getHoldings();
    const totalValue  = holdings.reduce((s,h) => s + h.currentValue, 0);
    const totalInvested = holdings.reduce((s,h) => s + h.avgCost * h.quantity, 0);
    const totalPnl    = totalValue - totalInvested;
    res.json({ summary: { totalValue, totalInvested, totalPnl,
      totalPnlPct: totalInvested > 0 ? ((totalPnl/totalInvested)*100).toFixed(2) : 0 }, data: holdings });
  } catch (err) { res.status(500).json({ error: err.message }); }
});

router.get('/positions', auth, async (req, res) => {
  try {
    const positions = await kite.getPositions();
    const dayPnl = positions.day.reduce((s,p) => s + (p.pnl||0), 0);
    res.json({ summary: { dayPnl }, data: positions.day });
  } catch (err) { res.status(500).json({ error: err.message }); }
});
module.exports = router;
