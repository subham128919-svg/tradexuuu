// routes/adminExtra.js — extra admin endpoints, mounted on the SAME
// path as routes/admin.js (/api/v1/admin). Express falls through to this
// router for any path the first one does not match, so the existing
// admin.js file does not need to be touched at all.
//
//   GET   /api/v1/admin/settings                → all settings
//   PUT   /api/v1/admin/settings                → save a subset
//   GET   /api/v1/admin/withdrawals?status=     → list requests
//   POST  /api/v1/admin/withdrawals/:id/approve
//   POST  /api/v1/admin/withdrawals/:id/reject
//   GET   /api/v1/admin/referrals               → referral ledger
//   GET   /api/v1/admin/kyc-queue?status=       → KYC queue (convenience)
const express   = require('express');
const router    = express.Router();
const db        = require('../config/database').pool;
const adminAuth = require('../middleware/adminAuth');
const settings  = require('../utils/settings');

// Only these keys may be written from the panel.
const EDITABLE = [
  'support_title', 'support_subtitle', 'support_whatsapp', 'support_whatsapp_text',
  'support_phone', 'support_email', 'support_hours', 'support_address', 'support_note',
  'url_about', 'url_charges',
  'referral_enabled', 'referral_reward_referrer', 'referral_reward_referee',
  'referral_share_url', 'referral_share_message', 'referral_terms',
  'withdraw_enabled', 'withdraw_min', 'withdraw_max', 'withdraw_note',
];

// ── settings ─────────────────────────────────────────────────────
router.get('/settings', adminAuth, async (req, res) => {
  try {
    const all = await settings.getAll(true);
    const out = {};
    EDITABLE.forEach(k => { out[k] = all[k] ?? ''; });
    res.json({ data: out, editable: EDITABLE });
  } catch (e) { res.status(500).json({ error: e.message }); }
});

router.put('/settings', adminAuth, async (req, res) => {
  try {
    const body = req.body || {};
    const patch = {};
    for (const k of EDITABLE) {
      if (Object.prototype.hasOwnProperty.call(body, k)) patch[k] = body[k];
    }
    if (!Object.keys(patch).length)
      return res.status(400).json({ error: 'No editable keys supplied' });

    // light validation so the app never receives junk
    if (patch.url_about   && !/^https?:\/\//i.test(patch.url_about))
      return res.status(400).json({ error: 'About URL must start with http:// or https://' });
    if (patch.url_charges && !/^https?:\/\//i.test(patch.url_charges))
      return res.status(400).json({ error: 'Charges URL must start with http:// or https://' });
    if (patch.referral_share_url && !/^https?:\/\//i.test(patch.referral_share_url))
      return res.status(400).json({ error: 'Referral share URL must start with http:// or https://' });
    if (patch.support_whatsapp)
      patch.support_whatsapp = String(patch.support_whatsapp).replace(/[^0-9]/g, '');

    ['referral_reward_referrer', 'referral_reward_referee', 'withdraw_min', 'withdraw_max']
      .forEach(k => {
        if (patch[k] !== undefined) {
          const n = parseFloat(patch[k]);
          patch[k] = Number.isFinite(n) && n >= 0 ? String(n) : '0';
        }
      });
    ['referral_enabled', 'withdraw_enabled'].forEach(k => {
      if (patch[k] !== undefined)
        patch[k] = (patch[k] === true || patch[k] === '1' || patch[k] === 1 || patch[k] === 'true') ? '1' : '0';
    });

    await settings.setMany(patch);
    res.json({ ok: true, saved: Object.keys(patch).length });
  } catch (e) { res.status(500).json({ error: e.message }); }
});

// ── withdrawals ──────────────────────────────────────────────────
router.get('/withdrawals', adminAuth, async (req, res) => {
  const status = ['pending', 'approved', 'rejected'].includes(req.query.status)
    ? req.query.status : null;
  const page = Math.max(1, parseInt(req.query.page) || 1);
  const lim  = 30, offset = (page - 1) * lim;
  try {
    const where  = status ? 'WHERE w.status = ?' : '';
    const params = status ? [status] : [];
    const [rows] = await db.query(
      `SELECT w.*, u.name AS user_name, u.email, u.phone, u.bo_demat_number
         FROM withdrawal_requests w
         JOIN app_users u ON u.id = w.user_id
         ${where}
        ORDER BY w.created_at DESC
        LIMIT ? OFFSET ?`, [...params, lim, offset]);
    const [[{ cnt }]] = await db.query(
      `SELECT COUNT(*) AS cnt FROM withdrawal_requests w ${where}`, params);
    const [[sum]] = await db.query(
      `SELECT COALESCE(SUM(amount),0) AS pendingAmount
         FROM withdrawal_requests WHERE status = 'pending'`);
    res.json({
      data: rows, total: cnt, page, pages: Math.ceil(cnt / lim),
      pendingAmount: parseFloat(sum.pendingAmount)
    });
  } catch (e) { res.status(500).json({ error: e.message }); }
});

// Approve: money was already deducted when the request was made,
// so approving only stamps the record.
router.post('/withdrawals/:id/approve', adminAuth, async (req, res) => {
  try {
    const [[w]] = await db.query(
      'SELECT * FROM withdrawal_requests WHERE id = ?', [req.params.id]);
    if (!w) return res.status(404).json({ error: 'Request not found' });
    if (w.status !== 'pending')
      return res.status(409).json({ error: 'Already ' + w.status });

    await db.query(
      `UPDATE withdrawal_requests
          SET status = 'approved', reference_no = ?, admin_remarks = ?, processed_at = NOW()
        WHERE id = ?`,
      [req.body.referenceNo || null, req.body.remarks || null, req.params.id]);

    await db.query(
      `UPDATE wallet_transactions SET note = ?
        WHERE user_id = ? AND note = 'Withdrawal request (on hold)'
        ORDER BY id DESC LIMIT 1`,
      ['Withdrawal paid (request #' + w.id + ')', w.user_id]);

    res.json({ ok: true, message: 'Withdrawal approved' });
  } catch (e) { res.status(500).json({ error: e.message }); }
});

// Reject: refund the held amount back to the wallet.
router.post('/withdrawals/:id/reject', adminAuth, async (req, res) => {
  const conn = await db.getConnection();
  try {
    await conn.beginTransaction();

    const [[w]] = await conn.query(
      'SELECT * FROM withdrawal_requests WHERE id = ? FOR UPDATE', [req.params.id]);
    if (!w)                 { await conn.rollback(); conn.release(); return res.status(404).json({ error: 'Request not found' }); }
    if (w.status !== 'pending') { await conn.rollback(); conn.release(); return res.status(409).json({ error: 'Already ' + w.status }); }

    await conn.query('UPDATE app_wallet SET balance = balance + ? WHERE user_id = ?',
      [w.amount, w.user_id]);
    await conn.query(
      `INSERT INTO wallet_transactions (user_id, type, amount, note, done_by)
       VALUES (?, 'credit', ?, ?, 'admin')`,
      [w.user_id, w.amount, 'Withdrawal rejected — amount refunded (request #' + w.id + ')']);
    await conn.query(
      `UPDATE withdrawal_requests
          SET status = 'rejected', admin_remarks = ?, processed_at = NOW()
        WHERE id = ?`,
      [req.body.remarks || 'Rejected by admin', req.params.id]);

    await conn.commit(); conn.release();
    res.json({ ok: true, message: 'Withdrawal rejected and amount refunded' });
  } catch (e) {
    try { await conn.rollback(); } catch {}
    try { conn.release(); } catch {}
    res.status(500).json({ error: e.message });
  }
});

// ── referrals overview ───────────────────────────────────────────
router.get('/referrals', adminAuth, async (req, res) => {
  const page = Math.max(1, parseInt(req.query.page) || 1);
  const lim  = 30, offset = (page - 1) * lim;
  try {
    const [rows] = await db.query(
      `SELECT r.id, r.code_used, r.status, r.referrer_reward, r.referee_reward,
              r.created_at, r.rewarded_at,
              ref.name AS referrer_name, ref.email AS referrer_email,
              ree.name AS referee_name,  ree.kyc_status
         FROM referrals r
         JOIN app_users ref ON ref.id = r.referrer_id
         JOIN app_users ree ON ree.id = r.referee_id
        ORDER BY r.created_at DESC
        LIMIT ? OFFSET ?`, [lim, offset]);
    const [[{ cnt }]]  = await db.query('SELECT COUNT(*) AS cnt FROM referrals');
    const [[paid]]     = await db.query(
      `SELECT COALESCE(SUM(referrer_reward + referee_reward),0) AS total
         FROM referrals WHERE status = 'rewarded'`);
    res.json({
      data: rows, total: cnt, page, pages: Math.ceil(cnt / lim),
      totalPaid: parseFloat(paid.total)
    });
  } catch (e) { res.status(500).json({ error: e.message }); }
});

// ── KYC queue (convenience alias so the panel has one base path) ─
router.get('/kyc-queue', adminAuth, async (req, res) => {
  const status = ['pending', 'rejected', 'verified', 'none'].includes(req.query.status)
    ? req.query.status : 'pending';
  try {
    const [rows] = await db.query(
      `SELECT id, name, email, phone, pan_number, aadhaar_number,
              pan_front_url, pan_back_url, aadhaar_front_url, aadhaar_back_url,
              kyc_status, bo_demat_number, created_at
         FROM app_users WHERE kyc_status = ? ORDER BY created_at ASC LIMIT 200`, [status]);
    const [[counts]] = await db.query(
      `SELECT
         SUM(kyc_status='pending')  AS pending,
         SUM(kyc_status='verified') AS verified,
         SUM(kyc_status='rejected') AS rejected
       FROM app_users`);
    res.json({ data: rows, counts });
  } catch (e) { res.status(500).json({ error: e.message }); }
});

module.exports = router;
