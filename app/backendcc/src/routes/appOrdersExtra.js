// routes/appOrdersExtra.js — Feature #4
//
// Mounted at /api/v1/app-orders-ex (a DIFFERENT path from your existing
// app-orders router) so that routes/appOrders.js does not need a single
// edit and route ordering can never matter.
//
//   GET  /api/v1/app-orders-ex?status=PENDING|EXECUTED|CANCELLED
//   POST /api/v1/app-orders-ex/:id/cancel
const express = require('express');
const router  = express.Router();
const db      = require('../config/database');
const auth    = require('../middleware/auth');

// ── list, with optional status filter + counts for the tabs ──────
router.get('/', auth, async (req, res) => {
  try {
    const raw = String(req.query.status || '').toUpperCase();
    const map = {
      PENDING: 'PENDING', OPEN: 'PENDING', PLACED: 'PENDING',
      EXECUTED: 'EXECUTED', COMPLETE: 'EXECUTED', COMPLETED: 'EXECUTED',
      CANCELLED: 'CANCELLED', CANCELED: 'CANCELLED'
    };
    const status = map[raw] || null;
    const uid    = req.userId;

    const where  = status ? 'WHERE user_id=? AND status=?' : 'WHERE user_id=?';
    const params = status ? [uid, status] : [uid];

    const [rows] = await db.pool.query(
      `SELECT * FROM app_orders ${where} ORDER BY placed_at DESC LIMIT 300`, params);

    const [[c]] = await db.pool.query(
      `SELECT COUNT(*) AS total,
              SUM(status='PENDING')   AS pending,
              SUM(status='EXECUTED')  AS executed,
              SUM(status='CANCELLED') AS cancelled
         FROM app_orders WHERE user_id=?`, [uid]);

    res.json({
      data: rows.map(o => ({
        id: o.id,
        symbol: o.symbol,
        exchange: o.exchange,
        name: o.name,
        orderType: o.order_type,
        product: o.product,
        priceType: o.price_type,
        qty: o.qty,
        price: parseFloat(o.price),
        avgPrice: o.avg_price == null ? null : parseFloat(o.avg_price),
        status: o.status,
        placedAt: o.placed_at
      })),
      counts: {
        total:     Number(c.total)     || 0,
        pending:   Number(c.pending)   || 0,
        executed:  Number(c.executed)  || 0,
        cancelled: Number(c.cancelled) || 0
      }
    });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

// ── cancel a PENDING order; a cancelled BUY refunds the held cash ─
router.post('/:id/cancel', auth, async (req, res) => {
  const conn = await db.pool.getConnection();
  try {
    await conn.beginTransaction();

    const [[o]] = await conn.query(
      'SELECT * FROM app_orders WHERE id=? AND user_id=? FOR UPDATE',
      [req.params.id, req.userId]);

    if (!o) {
      await conn.rollback(); conn.release();
      return res.status(404).json({ error: 'Order not found' });
    }
    if (o.status !== 'PENDING') {
      await conn.rollback(); conn.release();
      return res.status(409).json({ error: 'Only pending orders can be cancelled' });
    }

    if (o.order_type === 'BUY') {
      const refund = Number(o.qty) * parseFloat(o.price);
      await conn.query('UPDATE app_wallet SET balance = balance + ? WHERE user_id=?',
        [refund, req.userId]);
      await conn.query(
        `INSERT INTO wallet_transactions (user_id, type, amount, note, done_by)
         VALUES (?, 'credit', ?, ?, 'system')`,
        [req.userId, refund, 'Refund for cancelled BUY order #' + o.id]);
    }

    await conn.query("UPDATE app_orders SET status='CANCELLED' WHERE id=?", [o.id]);
    await conn.commit(); conn.release();

    res.json({ ok: true, message: 'Order cancelled' });
  } catch (e) {
    try { await conn.rollback(); } catch {}
    try { conn.release(); } catch {}
    res.status(500).json({ error: e.message });
  }
});

module.exports = router;
