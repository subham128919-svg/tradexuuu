// routes/appOrders.js — FIXED: atomic transactions, sell validation,
// server-side market price for MARKET orders, market hours enforcement.
//
// CHANGES from previous version:
//  1. Entire POST handler wrapped in BEGIN/COMMIT with FOR UPDATE locks
//     on wallet and holdings rows — prevents double-spend race conditions.
//  2. SELL validates qty <= owned quantity before processing.
//  3. MARKET orders fetch LTP server-side instead of trusting client price.
//  4. Market session check: rejects orders when market is closed.
//  5. All wallet/holdings mutations happen inside the transaction.
const express   = require('express');
const auth       = require('../middleware/auth');
const db          = require('../config/database');
const marketDataService = require('../services/marketDataService');
const { getMarketStatus } = require('../services/marketStatusService');
const tracker      = require('../services/backgroundTracker');
const { logger }   = require('../config/logger');
const router       = express.Router();

// POST /api/v1/app-orders  — place BUY or SELL
router.post('/', auth, async (req, res) => {
  const { symbol, exchange, name, orderType, product, priceType, qty, price } = req.body;
  if (!symbol || !orderType || !qty)
    return res.status(400).json({ error: 'symbol, orderType, qty required' });

  // ── Market session check ──────────────────────────────────────
  const status = getMarketStatus();
  if (!status.isOpen) {
    return res.status(400).json({
      error: 'Market is currently closed (' + status.session + '). Orders can only be placed during market hours (09:15–15:30 IST, Mon–Fri).'
    });
  }

  const uid  = req.user.userId;
  const q    = Number(qty);
  if (!q || q <= 0) return res.status(400).json({ error: 'Quantity must be a positive integer' });

  // ── F&O lot-size validation ───────────────────────────────────
  if ((exchange || '').toUpperCase() === 'NFO') {
    try {
      const [[row]] = await db.pool.query(
        'SELECT lot_size FROM instruments WHERE tradingsymbol=? AND exchange=\'NFO\' LIMIT 1',
        [symbol]
      );
      const lotSize = row?.lot_size;
      if (lotSize && q % lotSize !== 0) {
        return res.status(400).json({
          error: `Quantity must be a multiple of the lot size (${lotSize}) for ${symbol}`
        });
      }
    } catch { /* if lookup fails, don't block the order over it */ }
  }

  // ── Determine execution price ─────────────────────────────────
  // MARKET orders: server fetches current LTP — never trust client price.
  // LIMIT orders: use the price the user entered.
  let execPrice;
  const pType = (priceType || 'MARKET').toUpperCase();

  if (pType === 'MARKET') {
    const fullSymbol = symbol.includes(':') ? symbol : `${exchange || 'NSE'}:${symbol}`;
    try {
      const { ticks } = await marketDataService.getQuotesSmart([fullSymbol]);
      const ltp = ticks[fullSymbol]?.ltp;
      if (!ltp || ltp <= 0) {
        return res.status(400).json({ error: 'Cannot determine market price for ' + symbol + '. Try again in a moment.' });
      }
      execPrice = ltp;
    } catch (err) {
      return res.status(500).json({ error: 'Price lookup failed: ' + err.message });
    }
  } else {
    // LIMIT order — use the provided price
    execPrice = Number(price);
    if (!execPrice || execPrice <= 0) {
      return res.status(400).json({ error: 'A valid limit price is required' });
    }
  }

  const cost = q * execPrice;

  // ── Atomic transaction ────────────────────────────────────────
  const conn = await db.pool.getConnection();
  try {
    await conn.beginTransaction();

    // Ensure wallet row exists (idempotent)
    await conn.query(
      'INSERT INTO app_wallet (user_id, balance) VALUES (?,0) ON DUPLICATE KEY UPDATE user_id=user_id',
      [uid]
    );

    // Lock wallet row to prevent concurrent modifications
    const [[wallet]] = await conn.query(
      'SELECT balance FROM app_wallet WHERE user_id=? FOR UPDATE', [uid]
    );
    const avail = parseFloat(wallet?.balance || 0);

    if (orderType === 'BUY') {
      // ── BUY ───────────────────────────────────────────────────
      if (avail < cost) {
        await conn.rollback();
        conn.release();
        return res.status(400).json({
          error: 'Insufficient balance. Available: \u20b9' + avail.toFixed(2) + ', Required: \u20b9' + cost.toFixed(2)
        });
      }

      // Record order
      await conn.query(
        'INSERT INTO app_orders (user_id, symbol, exchange, name, order_type, product, price_type, qty, price, avg_price, status) VALUES (?,?,?,?,?,?,?,?,?,?,?)',
        [uid, symbol, exchange||'NSE', name||symbol, orderType, product||'DELIVERY', pType, q, execPrice, execPrice, 'EXECUTED']
      );

      // Deduct wallet
      await conn.query('UPDATE app_wallet SET balance = balance - ? WHERE user_id=?', [cost, uid]);

      // Record transaction
      await conn.query(
        'INSERT INTO wallet_transactions (user_id, type, amount, note, done_by) VALUES (?,?,?,?,?)',
        [uid, 'debit', cost, 'BUY ' + q + ' ' + symbol + ' @ \u20b9' + execPrice.toFixed(2), 'system']
      );

      // Upsert holding (lock the row if it exists)
      const [ex] = await conn.query(
        'SELECT * FROM app_holdings WHERE user_id=? AND symbol=? FOR UPDATE', [uid, symbol]
      );
      if (ex.length) {
        const newQty = ex[0].qty + q;
        const newAvg = ((parseFloat(ex[0].avg_price) * ex[0].qty) + cost) / newQty;
        await conn.query('UPDATE app_holdings SET qty=?, avg_price=?, name=? WHERE id=?',
          [newQty, newAvg.toFixed(4), name||ex[0].name, ex[0].id]);
      } else {
        await conn.query(
          'INSERT INTO app_holdings (user_id, symbol, exchange, name, qty, avg_price) VALUES (?,?,?,?,?,?)',
          [uid, symbol, exchange||'NSE', name||symbol, q, execPrice]);
      }

    } else if (orderType === 'SELL') {
      // ── SELL ──────────────────────────────────────────────────
      // Lock holdings row and validate quantity
      const [ex] = await conn.query(
        'SELECT * FROM app_holdings WHERE user_id=? AND symbol=? FOR UPDATE', [uid, symbol]
      );
      if (!ex.length || ex[0].qty <= 0) {
        await conn.rollback();
        conn.release();
        return res.status(400).json({ error: 'You do not hold any ' + symbol + ' to sell' });
      }
      if (q > ex[0].qty) {
        await conn.rollback();
        conn.release();
        return res.status(400).json({
          error: 'Insufficient quantity. You hold ' + ex[0].qty + ' but tried to sell ' + q
        });
      }

      // Record order
      await conn.query(
        'INSERT INTO app_orders (user_id, symbol, exchange, name, order_type, product, price_type, qty, price, avg_price, status) VALUES (?,?,?,?,?,?,?,?,?,?,?)',
        [uid, symbol, exchange||'NSE', name||symbol, orderType, product||'DELIVERY', pType, q, execPrice, execPrice, 'EXECUTED']
      );

      // Credit wallet
      const proceeds = cost;
      await conn.query('UPDATE app_wallet SET balance = balance + ? WHERE user_id=?', [proceeds, uid]);

      // Record transaction
      await conn.query(
        'INSERT INTO wallet_transactions (user_id, type, amount, note, done_by) VALUES (?,?,?,?,?)',
        [uid, 'credit', proceeds, 'SELL ' + q + ' ' + symbol + ' @ \u20b9' + execPrice.toFixed(2), 'system']
      );

      // Update holdings
      const newQty = ex[0].qty - q;
      if (newQty === 0) {
        await conn.query('DELETE FROM app_holdings WHERE id=?', [ex[0].id]);
      } else {
        await conn.query('UPDATE app_holdings SET qty=? WHERE id=?', [newQty, ex[0].id]);
      }
    } else {
      await conn.rollback();
      conn.release();
      return res.status(400).json({ error: 'orderType must be BUY or SELL' });
    }

    // Read final balance INSIDE the transaction
    const [[updWallet]] = await conn.query('SELECT balance FROM app_wallet WHERE user_id=?', [uid]);

    await conn.commit();
    conn.release();

    // Fire-and-forget: track symbol for live polling + historical seeding
    const fullSymbol = symbol.includes(':') ? symbol : `${exchange || 'NSE'}:${symbol}`;
    tracker.addSymbol(fullSymbol, orderType.toLowerCase()).catch(() => {});

    res.json({
      success: true,
      message: orderType + ' order executed @ \u20b9' + execPrice.toFixed(2),
      executedPrice: execPrice,
      newBalance: updWallet?.balance || 0
    });

  } catch (err) {
    try { await conn.rollback(); } catch {}
    conn.release();
    logger.error('appOrders POST error: ' + err.message);
    res.status(500).json({ error: err.message });
  }
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

    const fullKey = h => h.symbol.includes(':') ? h.symbol : `${h.exchange || 'NSE'}:${h.symbol}`;
    const symbols = holdings.map(fullKey);

    const { ticks } = await marketDataService.getQuotesSmart(symbols).catch(() => ({ ticks: {} }));
    let totalValue = 0, totalInvested = 0;

    const positions = await Promise.all(holdings.map(async h => {
      const key = fullKey(h);
      let ltp = ticks[key]?.ltp || 0;
      let priceAvailable = ltp > 0;

      if (!priceAvailable) {
        try {
          const [rows] = await db.pool.query(
            'SELECT close FROM candles WHERE symbol=? AND `interval`=? ORDER BY candle_time DESC LIMIT 1',
            [key, 'day']
          );
          if (rows.length) { ltp = parseFloat(rows[0].close); priceAvailable = true; }
        } catch {}
      }
      if (!priceAvailable) {
        try {
          const [rows] = await db.pool.query(
            'SELECT close FROM candles WHERE symbol=? ORDER BY candle_time DESC LIMIT 1',
            [key]
          );
          if (rows.length) { ltp = parseFloat(rows[0].close); priceAvailable = true; }
        } catch {}
      }

      const invested = parseFloat(h.avg_price) * h.qty;
      const currentVal = priceAvailable ? ltp * h.qty : invested;
      const pnl        = currentVal - invested;
      const pnlPct      = invested > 0 ? (pnl / invested * 100) : 0;
      totalValue    += currentVal;
      totalInvested += invested;
      return {
        symbol: key, exchange: h.exchange, name: h.name,
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