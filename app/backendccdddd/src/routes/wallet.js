// routes/wallet.js
const express  = require('express');
const auth     = require('../middleware/auth');
const db       = require('../config/database');
const { logger } = require('../config/logger');
const razorpayService = require('../services/razorpayService');
const router   = express.Router();

// GET /api/v1/wallet — user's own balance + recent transactions
router.get('/', auth, async (req, res) => {
  const uid = req.user.userId;
  try {
    const [[wallet]]  = await db.pool.query(
      'SELECT COALESCE(balance, 0) as balance FROM app_wallet WHERE user_id=?', [uid]);
    const [txns]      = await db.pool.query(
      'SELECT * FROM wallet_transactions WHERE user_id=? ORDER BY created_at DESC LIMIT 20', [uid]);
    res.json({ balance: wallet?.balance || 0, transactions: txns });
  } catch (e) { res.status(500).json({ error: e.message }); }
});

// Admin: POST /api/v1/wallet/admin/adjust { userId, amount, type: 'credit'|'debit', note }
// (admin-only — checked by admin-specific JWT role in admin.js, called from admin panel)
router.post('/admin/adjust', async (req, res) => {
  const adminToken = req.headers.authorization?.split(' ')[1];
  if (!adminToken) return res.status(401).json({ error: 'Not authenticated' });
  const jwt = require('jsonwebtoken');
  try {
    const payload = jwt.verify(adminToken, process.env.JWT_SECRET);
    if (payload.role !== 'admin') return res.status(403).json({ error: 'Admin only' });
  } catch { return res.status(401).json({ error: 'Invalid token' }); }

  const { userId, amount, type, note } = req.body;
  if (!userId || !amount || !type) return res.status(400).json({ error: 'userId, amount, type required' });
  const amt = parseFloat(amount);
  if (isNaN(amt) || amt <= 0) return res.status(400).json({ error: 'Amount must be positive' });
  try {
    // Ensure wallet row exists
    await db.pool.query(
      'INSERT INTO app_wallet (user_id, balance) VALUES (?,0) ON DUPLICATE KEY UPDATE user_id=user_id',
      [userId]
    );
    const delta = type === 'credit' ? amt : -amt;
    await db.pool.query('UPDATE app_wallet SET balance = balance + ? WHERE user_id=?', [delta, userId]);
    await db.pool.query(
      'INSERT INTO wallet_transactions (user_id, type, amount, note, done_by) VALUES (?,?,?,?,?)',
      [userId, type, amt, note || (type === 'credit' ? 'Admin credit' : 'Admin debit'), 'admin']
    );
    const [[wallet]] = await db.pool.query('SELECT balance FROM app_wallet WHERE user_id=?', [userId]);
    res.json({ ok: true, newBalance: wallet.balance });
  } catch (e) { res.status(500).json({ error: e.message }); }
});

// ── Razorpay: Add real money to wallet ──────────────────────────────
//
// Flow:
//  1. App calls POST /wallet/razorpay/create-order { amount }
//     → we create a Razorpay order, return its id + our public key_id
//  2. App opens Razorpay Checkout with that order_id
//  3. On success, Razorpay gives the app: payment_id, order_id, signature
//  4. App calls POST /wallet/razorpay/verify with those three values
//     → we verify the signature server-side (see razorpayService.js —
//       this is the ONLY step that actually credits the wallet; nothing
//       is credited just because the app "says" payment succeeded)
//
// POST /api/v1/wallet/razorpay/create-order  { amount: 500 }
router.post('/razorpay/create-order', auth, async (req, res) => {
  const { amount } = req.body;
  const amt = Number(amount);
  if (!amt || amt <= 0) return res.status(400).json({ error: 'Valid amount required' });
  if (amt > 500000) return res.status(400).json({ error: 'Amount too large for a single transaction' });

  const uid = req.user.userId;
  try {
    const receiptId = 'wallet_' + uid + '_' + Date.now();
    const order = await razorpayService.createOrder(amt, receiptId);

    // Record as 'created' — not yet paid. /verify (or the webhook)
    // will flip this to 'paid' and credit the wallet.
    await db.pool.query(
      'INSERT INTO payment_transactions (user_id, razorpay_order_id, amount, status) VALUES (?,?,?,\'created\')',
      [uid, order.id, amt]
    );

    res.json({
      orderId:  order.id,
      amount:   order.amount,   // paise, as Razorpay Checkout expects
      currency: order.currency,
      keyId:    process.env.RAZORPAY_KEY_ID,
    });
  } catch (e) {
    logger.error('razorpay create-order failed: ' + e.message);
    res.status(500).json({ error: 'Could not create payment order' });
  }
});

// POST /api/v1/wallet/razorpay/verify
// { razorpay_order_id, razorpay_payment_id, razorpay_signature }
router.post('/razorpay/verify', auth, async (req, res) => {
  const { razorpay_order_id, razorpay_payment_id, razorpay_signature } = req.body;
  if (!razorpay_order_id || !razorpay_payment_id || !razorpay_signature) {
    return res.status(400).json({ error: 'Missing payment verification fields' });
  }
  const uid = req.user.userId;

  try {
    const valid = razorpayService.verifySignature(razorpay_order_id, razorpay_payment_id, razorpay_signature);
    if (!valid) {
      logger.warn('razorpay: signature mismatch for order ' + razorpay_order_id + ' (user ' + uid + ')');
      await db.pool.query(
        'UPDATE payment_transactions SET status=\'failed\' WHERE razorpay_order_id=?',
        [razorpay_order_id]
      );
      return res.status(400).json({ error: 'Payment verification failed' });
    }

    // Look up the order — confirms it belongs to this user and hasn't
    // already been credited (idempotency: a replayed /verify call for
    // an already-'paid' order must NOT credit the wallet twice).
    const [[txn]] = await db.pool.query(
      'SELECT * FROM payment_transactions WHERE razorpay_order_id=? AND user_id=?',
      [razorpay_order_id, uid]
    );
    if (!txn) return res.status(404).json({ error: 'Order not found' });
    if (txn.status === 'paid') {
      // Already credited (e.g. app retried the call) — return success
      // without crediting again.
      const [[wallet]] = await db.pool.query('SELECT balance FROM app_wallet WHERE user_id=?', [uid]);
      return res.json({ ok: true, alreadyProcessed: true, newBalance: wallet?.balance || 0 });
    }

    await db.pool.query(
      'UPDATE payment_transactions SET status=\'paid\', razorpay_payment_id=? WHERE razorpay_order_id=?',
      [razorpay_payment_id, razorpay_order_id]
    );
    await db.pool.query(
      'INSERT INTO app_wallet (user_id, balance) VALUES (?,0) ON DUPLICATE KEY UPDATE user_id=user_id',
      [uid]
    );
    await db.pool.query('UPDATE app_wallet SET balance = balance + ? WHERE user_id=?', [txn.amount, uid]);
    await db.pool.query(
      'INSERT INTO wallet_transactions (user_id, type, amount, note, done_by) VALUES (?,?,?,?,?)',
      [uid, 'credit', txn.amount, 'Added via Razorpay (payment ' + razorpay_payment_id + ')', 'razorpay']
    );

    const [[wallet]] = await db.pool.query('SELECT balance FROM app_wallet WHERE user_id=?', [uid]);
    logger.info('razorpay: credited \u20b9' + txn.amount + ' to user ' + uid + ' (payment ' + razorpay_payment_id + ')');
    res.json({ ok: true, newBalance: wallet.balance });
  } catch (e) {
    logger.error('razorpay verify failed: ' + e.message);
    res.status(500).json({ error: 'Verification failed' });
  }
});

// POST /api/v1/wallet/razorpay/webhook — optional but recommended backstop.
// Configure this URL + a webhook secret in your Razorpay Dashboard
// (Settings → Webhooks) for the 'payment.captured' event. Catches the
// case where the app loses network right after payment but before it
// can call /verify — Razorpay's own server tells us directly instead.
// Uses req.rawBody, captured globally by index.js's express.json()
// verify callback, since signature verification needs the exact raw
// bytes Razorpay signed (not the re-serialized parsed object).
router.post('/razorpay/webhook', async (req, res) => {
  const signature = req.headers['x-razorpay-signature'];
  try {
    const valid = razorpayService.verifyWebhookSignature((req.rawBody || Buffer.from('')).toString(), signature);
    if (!valid) return res.status(400).json({ error: 'Invalid webhook signature' });

    const payload = req.body; // already parsed by the global express.json()
    if (payload.event === 'payment.captured') {
      const payment  = payload.payload.payment.entity;
      const orderId  = payment.order_id;
      const paymentId = payment.id;

      const [[txn]] = await db.pool.query(
        'SELECT * FROM payment_transactions WHERE razorpay_order_id=?', [orderId]
      );
      if (txn && txn.status !== 'paid') {
        await db.pool.query(
          'UPDATE payment_transactions SET status=\'paid\', razorpay_payment_id=? WHERE razorpay_order_id=?',
          [paymentId, orderId]
        );
        await db.pool.query('UPDATE app_wallet SET balance = balance + ? WHERE user_id=?', [txn.amount, txn.user_id]);
        await db.pool.query(
          'INSERT INTO wallet_transactions (user_id, type, amount, note, done_by) VALUES (?,?,?,?,?)',
          [txn.user_id, 'credit', txn.amount, 'Added via Razorpay webhook (payment ' + paymentId + ')', 'razorpay_webhook']
        );
        logger.info('razorpay webhook: credited \u20b9' + txn.amount + ' to user ' + txn.user_id);
      }
    }
    res.json({ ok: true });
  } catch (e) {
    logger.error('razorpay webhook error: ' + e.message);
    res.status(500).json({ error: 'Webhook processing failed' });
  }
});

module.exports = router;
