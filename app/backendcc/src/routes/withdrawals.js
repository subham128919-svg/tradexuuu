// routes/withdrawals.js — Feature #7 "Withdraw funds"
//
//   GET    /api/v1/withdrawals/config    → limits + whether it is enabled
//   GET    /api/v1/withdrawals/methods   → saved UPI / bank accounts
//   POST   /api/v1/withdrawals/methods   → add one
//   DELETE /api/v1/withdrawals/methods/:id
//   POST   /api/v1/withdrawals/request   → create a withdrawal request
//   GET    /api/v1/withdrawals           → my requests
//
// Money handling: the amount is debited from app_wallet the moment the
// request is created (inside a transaction with SELECT ... FOR UPDATE),
// so the same balance can never be withdrawn twice while a request is
// pending. If the admin rejects it, the amount is credited back.
const express  = require('express');
const router   = express.Router();
const database = require('../config/database');
const db       = database.pool;
const auth     = require('../middleware/auth');
const settings = require('../utils/settings');

// ── config ───────────────────────────────────────────────────────
router.get('/config', auth, async (req, res) => {
  try {
    res.json({
      enabled: await settings.getBool('withdraw_enabled', true),
      min:     await settings.getNumber('withdraw_min', 100),
      max:     await settings.getNumber('withdraw_max', 200000),
      note:    await settings.get('withdraw_note', '')
    });
  } catch (e) { res.status(500).json({ error: 'Failed to load config' }); }
});

// ── payout methods ───────────────────────────────────────────────
function describe(m) {
  return m.method_type === 'upi'
    ? `UPI · ${m.upi_id}`
    : `${m.bank_name || 'Bank'} · ****${String(m.account_number || '').slice(-4)} · ${m.ifsc || ''}`;
}

router.get('/methods', auth, async (req, res) => {
  try {
    const [rows] = await db.query(
      'SELECT * FROM payout_methods WHERE user_id = ? ORDER BY is_default DESC, id DESC',
      [req.userId]);
    res.json({
      data: rows.map(m => ({
        id: m.id,
        type: m.method_type,
        upiId: m.upi_id,
        accountHolder: m.account_holder,
        accountMasked: m.account_number ? '****' + String(m.account_number).slice(-4) : null,
        ifsc: m.ifsc,
        bankName: m.bank_name,
        isDefault: !!m.is_default,
        label: describe(m)
      }))
    });
  } catch (e) { res.status(500).json({ error: 'Failed to load payout methods' }); }
});

router.post('/methods', auth, async (req, res) => {
  const type = String(req.body.type || '').toLowerCase();
  try {
    if (type === 'upi') {
      const upi = String(req.body.upiId || '').trim();
      if (!/^[\w.\-]{2,60}@[a-zA-Z]{2,20}$/.test(upi))
        return res.status(400).json({ error: 'Enter a valid UPI ID (e.g. name@okaxis)' });

      const [dup] = await db.query(
        'SELECT id FROM payout_methods WHERE user_id = ? AND upi_id = ?', [req.userId, upi]);
      if (dup.length) return res.status(409).json({ error: 'This UPI ID is already saved' });

      const [r] = await db.query(
        `INSERT INTO payout_methods (user_id, method_type, upi_id, is_default)
         VALUES (?, 'upi', ?, 1)`, [req.userId, upi]);
      await db.query(
        'UPDATE payout_methods SET is_default = 0 WHERE user_id = ? AND id <> ?',
        [req.userId, r.insertId]);
      return res.json({ ok: true, id: r.insertId, message: 'UPI ID saved' });
    }

    if (type === 'bank') {
      const holder = String(req.body.accountHolder || '').trim();
      const accNo  = String(req.body.accountNumber || '').replace(/\s/g, '');
      const ifsc   = String(req.body.ifsc || '').trim().toUpperCase();
      const bank   = String(req.body.bankName || '').trim();

      if (holder.length < 3)        return res.status(400).json({ error: 'Enter the account holder name' });
      if (!/^\d{6,20}$/.test(accNo))return res.status(400).json({ error: 'Enter a valid account number' });
      if (!/^[A-Z]{4}0[A-Z0-9]{6}$/.test(ifsc))
        return res.status(400).json({ error: 'Enter a valid IFSC code (e.g. HDFC0001234)' });

      const [dup] = await db.query(
        'SELECT id FROM payout_methods WHERE user_id = ? AND account_number = ? AND ifsc = ?',
        [req.userId, accNo, ifsc]);
      if (dup.length) return res.status(409).json({ error: 'This bank account is already saved' });

      const [r] = await db.query(
        `INSERT INTO payout_methods
           (user_id, method_type, account_holder, account_number, ifsc, bank_name, is_default)
         VALUES (?, 'bank', ?, ?, ?, ?, 1)`,
        [req.userId, holder, accNo, ifsc, bank || null]);
      await db.query(
        'UPDATE payout_methods SET is_default = 0 WHERE user_id = ? AND id <> ?',
        [req.userId, r.insertId]);
      return res.json({ ok: true, id: r.insertId, message: 'Bank account saved' });
    }

    return res.status(400).json({ error: "type must be 'upi' or 'bank'" });
  } catch (e) {
    console.error('add payout method:', e.message);
    res.status(500).json({ error: 'Could not save payout method' });
  }
});

router.delete('/methods/:id', auth, async (req, res) => {
  try {
    const [r] = await db.query(
      'DELETE FROM payout_methods WHERE id = ? AND user_id = ?', [req.params.id, req.userId]);
    if (!r.affectedRows) return res.status(404).json({ error: 'Not found' });
    res.json({ ok: true });
  } catch (e) { res.status(500).json({ error: 'Could not delete' }); }
});

// ── create a withdrawal request ──────────────────────────────────
router.post('/request', auth, async (req, res) => {
  const amount   = parseFloat(req.body.amount);
  const methodId = parseInt(req.body.methodId, 10);

  if (!Number.isFinite(amount) || amount <= 0)
    return res.status(400).json({ error: 'Enter a valid amount' });
  if (!methodId)
    return res.status(400).json({ error: 'Select a UPI ID or bank account first' });

  const enabled = await settings.getBool('withdraw_enabled', true);
  if (!enabled)
    return res.status(403).json({ error: 'Withdrawals are temporarily disabled. Please try later.' });

  const min = await settings.getNumber('withdraw_min', 100);
  const max = await settings.getNumber('withdraw_max', 200000);
  if (amount < min) return res.status(400).json({ error: `Minimum withdrawal is ₹${min}` });
  if (amount > max) return res.status(400).json({ error: `Maximum withdrawal is ₹${max}` });

  const conn = await db.getConnection();
  try {
    await conn.beginTransaction();

    // KYC must be verified before money leaves the platform
    const [[user]] = await conn.query(
      'SELECT kyc_status, is_blocked FROM app_users WHERE id = ? FOR UPDATE', [req.userId]);
    if (!user)            { await conn.rollback(); conn.release(); return res.status(404).json({ error: 'User not found' }); }
    if (user.is_blocked)  { await conn.rollback(); conn.release(); return res.status(403).json({ error: 'Account blocked' }); }
    if (user.kyc_status !== 'verified') {
      await conn.rollback(); conn.release();
      return res.status(403).json({ error: 'Complete KYC verification before withdrawing funds' });
    }

    const [[method]] = await conn.query(
      'SELECT * FROM payout_methods WHERE id = ? AND user_id = ?', [methodId, req.userId]);
    if (!method) { await conn.rollback(); conn.release(); return res.status(404).json({ error: 'Payout method not found' }); }

    const [[wallet]] = await conn.query(
      'SELECT balance FROM app_wallet WHERE user_id = ? FOR UPDATE', [req.userId]);
    const balance = parseFloat(wallet?.balance || 0);
    if (balance < amount) {
      await conn.rollback(); conn.release();
      return res.status(400).json({ error: `Insufficient balance. Available ₹${balance.toFixed(2)}` });
    }

    // Hold the money now so it cannot be spent twice
    await conn.query('UPDATE app_wallet SET balance = balance - ? WHERE user_id = ?',
      [amount, req.userId]);
    await conn.query(
      `INSERT INTO wallet_transactions (user_id, type, amount, note, done_by)
       VALUES (?, 'debit', ?, ?, 'system')`,
      [req.userId, amount, 'Withdrawal request (on hold)']);

    const [ins] = await conn.query(
      `INSERT INTO withdrawal_requests
         (user_id, amount, method_id, method_type, method_snapshot, status)
       VALUES (?,?,?,?,?, 'pending')`,
      [req.userId, amount, method.id, method.method_type, describe(method)]);

    const [[fresh]] = await conn.query(
      'SELECT balance FROM app_wallet WHERE user_id = ?', [req.userId]);

    await conn.commit();
    conn.release();

    res.json({
      ok: true,
      requestId: ins.insertId,
      newBalance: parseFloat(fresh.balance),
      message: 'Withdrawal request submitted. It will be processed after admin approval.'
    });
  } catch (e) {
    try { await conn.rollback(); } catch {}
    try { conn.release(); } catch {}
    console.error('withdraw request:', e.message);
    res.status(500).json({ error: 'Could not create withdrawal request' });
  }
});

// ── my requests ──────────────────────────────────────────────────
router.get('/', auth, async (req, res) => {
  try {
    const [rows] = await db.query(
      `SELECT id, amount, method_type, method_snapshot, status,
              admin_remarks, reference_no, created_at, processed_at
         FROM withdrawal_requests WHERE user_id = ?
        ORDER BY created_at DESC LIMIT 100`, [req.userId]);
    res.json({
      data: rows.map(r => ({
        id: r.id,
        amount: parseFloat(r.amount),
        methodType: r.method_type,
        method: r.method_snapshot,
        status: r.status,
        remarks: r.admin_remarks,
        referenceNo: r.reference_no,
        createdAt: r.created_at,
        processedAt: r.processed_at
      }))
    });
  } catch (e) { res.status(500).json({ error: 'Failed to load requests' }); }
});

module.exports = router;
