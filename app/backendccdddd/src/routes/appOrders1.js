// routes/appOrders.js — FIXED: balance check, wallet deduction, ltp fallback
// FIX (P&L bug): GET /positions used to read ONLY from tickCache (a
// passive Redis read — no active Kite call) and, if that came back
// empty, fell back to the latest `day` candle only. If a symbol had
// never been ticked (nothing else currently subscribed to it) and had
// no `day` candle yet (e.g. just bought, historical seed still running
// or Kite briefly unavailable), ltp silently stayed 0. Since
// pnl = (ltp*qty) - invested, ltp=0 makes pnl exactly -invested every
// time — a guaranteed, fake "-100%" loss, not a real one. Two fixes:
//  1. POST / now tracks the symbol the moment it's bought (same thing
//     ChartActivity already does when opened, but guaranteed here too)
//     so live polling + historical seeding start immediately.
//  2. GET /positions now actively refreshes via marketDataService
//     (same pipeline market.js uses) instead of a passive cache read,
//     broadens the candle fallback to any interval (not just 'day'),
//     and — if a price is genuinely unavailable anywhere — reports
//     `priceAvailable:false` with pnl held at 0 instead of a fabricated
//     -100%. "We don't know yet" is not the same as "you lost everything".
const express   = require('express');
const auth       = require('../middleware/auth');
const db          = require('../config/database');
const marketDataService = require('../services/marketDataService');
const tracker      = require('../services/backgroundTracker');
const router       = express.Router();

// POST /api/v1/app-orders  — place BUY or SELL
router.post('/', auth, async (req, res) => {
  const { symbol, exchange, name, orderType, product, priceType, qty, price } = req.body;
  if (!symbol || !orderType || !qty || !price)
    return res.status(400).json({ error: 'symbol, orderType, qty, price required' });

  const uid  = req.user.userId;
  const q    = Number(qty);
  const p    = Number(price);
  const cost = q * p;

  try {
    // FIX #4: Ensure wallet row exists
    await db.pool.query(
      'INSERT INTO app_wallet (user_id, balance) VALUES (?,0) ON DUPLICATE KEY UPDATE user_id=user_id',
      [uid]
    );
    const [[wallet]] = await db.pool.query(
      'SELECT balance FROM app_wallet WHERE user_id=?', [uid]
    );
    const avail = parseFloat(wallet?.balance || 0);

    // FIX #4: Block BUY if insufficient balance
    if (orderType === 'BUY' && avail < cost) {
      return res.status(400).json({
        error: 'Insufficient balance. Available: \u20b9' + avail.toFixed(2) + ', Required: \u20b9' + cost.toFixed(2)
      });
    }

    // Record order
    await db.pool.query(
      'INSERT INTO app_orders (user_id, symbol, exchange, name, order_type, product, price_type, qty, price, avg_price, status) VALUES (?,?,?,?,?,?,?,?,?,?,?)',
      [uid, symbol, exchange||'NSE', name||symbol.split(':')[1], orderType, product||'DELIVERY', priceType||'MARKET', q, p, p, 'EXECUTED']
    );

    // FIX (P&L bug, part 1): make sure this symbol is being live-polled
    // and has its historical data seeded from the moment it's bought —
    // don't rely solely on the chart screen having been opened first.
    // Fire-and-forget: never block/fail the order over this.
    tracker.addSymbol(symbol, 'buy').catch(() => {});

    // FIX #4: Deduct/credit wallet
    if (orderType === 'BUY') {
      await db.pool.query('UPDATE app_wallet SET balance = balance - ? WHERE user_id=?', [cost, uid]);
      await db.pool.query(
        'INSERT INTO wallet_transactions (user_id, type, amount, note, done_by) VALUES (?,?,?,?,?)',
        [uid, 'debit', cost, 'BUY ' + q + ' ' + symbol + ' @ \u20b9' + p, 'system']
      );
      // Upsert holding
      const [ex] = await db.pool.query('SELECT * FROM app_holdings WHERE user_id=? AND symbol=?', [uid, symbol]);
      if (ex.length) {
        const newQty = ex[0].qty + q;
        const newAvg = ((ex[0].avg_price * ex[0].qty) + cost) / newQty;
        await db.pool.query('UPDATE app_holdings SET qty=?, avg_price=?, name=? WHERE id=?',
          [newQty, newAvg.toFixed(4), name||ex[0].name, ex[0].id]);
      } else {
        await db.pool.query('INSERT INTO app_holdings (user_id, symbol, exchange, name, qty, avg_price) VALUES (?,?,?,?,?,?)',
          [uid, symbol, exchange||'NSE', name||symbol.split(':')[1], q, p]);
      }
    } else if (orderType === 'SELL') {
      const proceeds = cost;
      await db.pool.query('UPDATE app_wallet SET balance = balance + ? WHERE user_id=?', [proceeds, uid]);
      await db.pool.query(
        'INSERT INTO wallet_transactions (user_id, type, amount, note, done_by) VALUES (?,?,?,?,?)',
        [uid, 'credit', proceeds, 'SELL ' + q + ' ' + symbol + ' @ \u20b9' + p, 'system']
      );
      const [ex] = await db.pool.query('SELECT * FROM app_holdings WHERE user_id=? AND symbol=?', [uid, symbol]);
      if (ex.length) {
        const newQty = Math.max(0, ex[0].qty - q);
        if (newQty === 0) await db.pool.query('DELETE FROM app_holdings WHERE id=?', [ex[0].id]);
        else              await db.pool.query('UPDATE app_holdings SET qty=? WHERE id=?', [newQty, ex[0].id]);
      }
    }

    const [[updWallet]] = await db.pool.query('SELECT balance FROM app_wallet WHERE user_id=?', [uid]);
    res.json({ success: true, message: 'Order executed', newBalance: updWallet?.balance || 0 });
  } catch (err) { res.status(500).json({ error: err.message }); }
});

// GET /api/v1/app-orders
router.get('/', auth, async (req, res) => {
  try {
    const [rows] = await db.pool.query(
      'SELECT * FROM app_orders WHERE user_id=? ORDER BY placed_at DESC', [req.user.userId]);
    res.json({ data: rows });
  } catch (e) { res.status(500).json({ error: e.message }); }
});

// GET /api/v1/app-orders/positions
router.get('/positions', auth, async (req, res) => {
  try {
    const [holdings] = await db.pool.query(
      'SELECT * FROM app_holdings WHERE user_id=? AND qty>0', [req.user.userId]);
    if (!holdings.length) return res.json({ data:[], totalPnl:0, totalValue:0, totalInvested:0, totalPnlPct:0 });

    const symbols = holdings.map(h => h.symbol);
    // FIX (P&L bug, part 2): actively try Kite (same pipeline as
    // market.js), not just a passive Redis read — this alone fixes the
    // common case where nothing else happened to have ticked this
    // symbol recently.
    const { ticks } = await marketDataService.getQuotesSmart(symbols).catch(() => ({ ticks: {} }));
    let totalValue = 0, totalInvested = 0;

    const positions = await Promise.all(holdings.map(async h => {
      let ltp = ticks[h.symbol]?.ltp || 0;
      let priceAvailable = ltp > 0;

      if (!priceAvailable) {
        // Tier 1: last daily close.
        try {
          const [rows] = await db.pool.query(
            'SELECT close FROM candles WHERE symbol=? AND `interval`=? ORDER BY candle_time DESC LIMIT 1',
            [h.symbol, 'day']
          );
          if (rows.length) { ltp = parseFloat(rows[0].close); priceAvailable = true; }
        } catch {}
      }
      if (!priceAvailable) {
        // Tier 2: last close on ANY interval — covers a symbol whose
        // intraday seed succeeded but whose 'day' candle hasn't landed
        // yet (seeding runs period-by-period, not all at once).
        try {
          const [rows] = await db.pool.query(
            'SELECT close FROM candles WHERE symbol=? ORDER BY candle_time DESC LIMIT 1',
            [h.symbol]
          );
          if (rows.length) { ltp = parseFloat(rows[0].close); priceAvailable = true; }
        } catch {}
      }

      const invested = parseFloat(h.avg_price) * h.qty;
      // FIX: when the price is genuinely unknown anywhere, do NOT
      // pretend ltp=0 (currentVal=0 → pnl=-invested → a fake, exact
      // "-100%" every single time). Hold P&L at 0 and say so
      // explicitly via priceAvailable — "unknown" is not "total loss".
      const currentVal = priceAvailable ? ltp * h.qty : invested;
      const pnl        = currentVal - invested;
      const pnlPct      = invested > 0 ? (pnl / invested * 100) : 0;
      totalValue    += currentVal;
      totalInvested += invested;
      return {
        symbol: h.symbol, exchange: h.exchange, name: h.name,
        qty: h.qty, avgPrice: parseFloat(h.avg_price),
        ltp, currentValue: currentVal, invested,
        pnl: parseFloat(pnl.toFixed(2)),
        pnlPct: parseFloat(pnlPct.toFixed(2)),
        isProfit: pnl >= 0,
        priceAvailable,
      };
    }));

    res.json({
      data: positions,
      totalValue, totalInvested,
      totalPnl:    parseFloat((totalValue - totalInvested).toFixed(2)),
      totalPnlPct: totalInvested > 0
        ? parseFloat(((totalValue - totalInvested) / totalInvested * 100).toFixed(2)) : 0
    });
  } catch (e) { res.status(500).json({ error: e.message }); }
});

module.exports = router;
