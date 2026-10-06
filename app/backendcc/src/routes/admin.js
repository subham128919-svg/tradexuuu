// routes/admin.js — Admin panel API
const express  = require('express');
const jwt      = require('jsonwebtoken');
const db       = require('../config/database');
const redis    = require('../config/redis');
const { getMarketStatus } = require('../services/marketStatusService');
const router   = express.Router();

function adminAuth(req, res, next) {
  const token = req.headers.authorization?.split(' ')[1];
  if (!token) return res.status(401).json({ error: 'Not authenticated' });
  try {
    const payload = jwt.verify(token, process.env.JWT_SECRET);
    if (payload.role !== 'admin') return res.status(403).json({ error: 'Not admin' });
    next();
  } catch { res.status(401).json({ error: 'Invalid token' }); }
}

// POST /api/v1/admin/login
router.post('/login', (req, res) => {
  const { username, password } = req.body;
  const ADMIN_USER = process.env.ADMIN_USERNAME || 'admin';
  const ADMIN_PASS = process.env.ADMIN_PASSWORD || 'admin123';
  if (username !== ADMIN_USER || password !== ADMIN_PASS)
    return res.status(401).json({ error: 'Invalid admin credentials' });
  const token = jwt.sign({ role: 'admin', username }, process.env.JWT_SECRET, { expiresIn: '8h' });
  res.json({ token });
});

// GET /api/v1/admin/dashboard
router.get('/dashboard', adminAuth, async (req, res) => {
  try {
    const [[totals]]   = await db.pool.query('SELECT COUNT(*) as total, SUM(is_blocked) as blocked FROM app_users');
    const [[today]]    = await db.pool.query("SELECT COUNT(*) as cnt FROM app_users WHERE DATE(created_at) = CURDATE()");
    const [[orders]]   = await db.pool.query('SELECT COUNT(*) as total FROM app_orders');
    const [[todayOrd]] = await db.pool.query("SELECT COUNT(*) as cnt FROM app_orders WHERE DATE(placed_at) = CURDATE()");
    const kiteToken    = await redis.get('kite:access_token').catch(() => null);
    res.json({
      users:       { total: totals.total || 0, blocked: totals.blocked || 0, today: today.cnt || 0 },
      orders:      { total: orders.total || 0, today: todayOrd.cnt || 0 },
      marketStatus: getMarketStatus(),
      kiteToken:   kiteToken ? 'active' : 'missing'
    });
  } catch (err) { res.status(500).json({ error: err.message }); }
});

// GET /api/v1/admin/users?page=1&q=search
router.get('/users', adminAuth, async (req, res) => {
  const page = parseInt(req.query.page) || 1;
  const q    = req.query.q || '';
  const lim  = 20, offset = (page - 1) * lim;
  try {
    const where = q ? 'WHERE name LIKE ? OR email LIKE ? OR phone LIKE ?' : '';
    const params = q ? [`%${q}%`, `%${q}%`, `%${q}%`] : [];
    const [rows]  = await db.pool.query(
      `SELECT id, name, email, phone, is_active, is_blocked, created_at, last_login_at FROM app_users ${where} ORDER BY created_at DESC LIMIT ? OFFSET ?`,
      [...params, lim, offset]
    );
    const [[{ cnt }]] = await db.pool.query(`SELECT COUNT(*) as cnt FROM app_users ${where}`, params);
    res.json({ data: rows, total: cnt, page, pages: Math.ceil(cnt / lim) });
  } catch (err) { res.status(500).json({ error: err.message }); }
});

// GET /api/v1/admin/users/:id
router.get('/users/:id', adminAuth, async (req, res) => {
  try {
    const [users] = await db.pool.query('SELECT * FROM app_users WHERE id = ?', [req.params.id]);
    if (!users.length) return res.status(404).json({ error: 'User not found' });
    const [orders] = await db.pool.query('SELECT * FROM app_orders WHERE user_id = ? ORDER BY placed_at DESC LIMIT 20', [req.params.id]);
    const [holdings] = await db.pool.query('SELECT * FROM app_holdings WHERE user_id = ?', [req.params.id]);
    const { password_hash, ...user } = users[0];
    res.json({ user, orders, holdings });
  } catch (err) { res.status(500).json({ error: err.message }); }
});

// PATCH /api/v1/admin/users/:id  (block/unblock)
router.patch('/users/:id', adminAuth, async (req, res) => {
  const { is_blocked, is_active } = req.body;
  try {
    const updates = [];
    const vals    = [];
    if (is_blocked !== undefined) { updates.push('is_blocked=?'); vals.push(is_blocked); }
    if (is_active  !== undefined) { updates.push('is_active=?');  vals.push(is_active);  }
    if (!updates.length) return res.status(400).json({ error: 'Nothing to update' });
    vals.push(req.params.id);
    await db.pool.query(`UPDATE app_users SET ${updates.join(',')} WHERE id=?`, vals);
    res.json({ success: true });
  } catch (err) { res.status(500).json({ error: err.message }); }
});

// GET /api/v1/admin/orders
router.get('/orders', adminAuth, async (req, res) => {
  const page = parseInt(req.query.page) || 1;
  const lim  = 30, offset = (page - 1) * lim;
  try {
    const [rows] = await db.pool.query(
      'SELECT o.*, u.name as user_name, u.email FROM app_orders o LEFT JOIN app_users u ON o.user_id = u.id ORDER BY placed_at DESC LIMIT ? OFFSET ?',
      [lim, offset]
    );
    const [[{ cnt }]] = await db.pool.query('SELECT COUNT(*) as cnt FROM app_orders');
    res.json({ data: rows, total: cnt, page, pages: Math.ceil(cnt / lim) });
  } catch (err) { res.status(500).json({ error: err.message }); }
});

// GET /api/v1/admin/payments — Razorpay transaction history
router.get('/payments', adminAuth, async (req, res) => {
  const page = parseInt(req.query.page) || 1;
  const lim  = 30, offset = (page - 1) * lim;
  try {
    const [rows] = await db.pool.query(
      'SELECT p.*, u.name as user_name, u.email FROM payment_transactions p ' +
      'LEFT JOIN app_users u ON p.user_id = u.id ' +
      'ORDER BY p.created_at DESC LIMIT ? OFFSET ?',
      [lim, offset]
    );
    const [[{ cnt }]]   = await db.pool.query('SELECT COUNT(*) as cnt FROM payment_transactions');
    const [[totals]]    = await db.pool.query(
      'SELECT COALESCE(SUM(amount),0) as totalPaid, COUNT(*) as paidCount ' +
      'FROM payment_transactions WHERE status=\'paid\''
    );
    res.json({ data: rows, total: cnt, page, pages: Math.ceil(cnt / lim), totalPaid: totals.totalPaid, paidCount: totals.paidCount });
  } catch (err) { res.status(500).json({ error: err.message }); }
});
// ─────────────────────────────────────────────────────────────────
// PASTE THIS into /var/www/tradingapp/src/routes/admin.js
// Location: BEFORE the line → module.exports = router;
// ─────────────────────────────────────────────────────────────────

function generateDematNumber() {
  let r = '';
  for (let i = 0; i < 12; i++) r += Math.floor(Math.random() * 10).toString();
  return '1208' + r;
}

// GET /api/v1/admin/kyc-stats
router.get('/kyc-stats', adminAuth, async (req, res) => {
  try {
    const [[p]] = await db.pool.query("SELECT COUNT(*) as c FROM app_users WHERE kyc_status='pending'");
    const [[v]] = await db.pool.query("SELECT COUNT(*) as c FROM app_users WHERE kyc_status='verified'");
    const [[r]] = await db.pool.query("SELECT COUNT(*) as c FROM app_users WHERE kyc_status='rejected'");
    res.json({ pending: p.c, verified: v.c, rejected: r.c });
  } catch (err) { res.status(500).json({ error: err.message }); }
});

// GET /api/v1/admin/kyc-users
router.get('/kyc-users', adminAuth, async (req, res) => {
  try {
    const [users] = await db.pool.query(
      `SELECT id, name, email, phone, pan_number, aadhaar_number,
              pan_front_url, pan_back_url, aadhaar_front_url, aadhaar_back_url,
              kyc_status, bo_demat_number, is_active, created_at
       FROM app_users ORDER BY
          CASE kyc_status
              WHEN 'pending' THEN 0
              WHEN 'none' THEN 1
              WHEN 'rejected' THEN 2
              WHEN 'verified' THEN 3
          END, created_at DESC`
    );
    res.json({ data: users });
  } catch (err) { res.status(500).json({ error: err.message }); }
});

// POST /api/v1/admin/kyc-review
router.post('/kyc-review', adminAuth, async (req, res) => {
  const { userId, action, remarks } = req.body;
  if (!userId || !['approved', 'rejected'].includes(action))
    return res.status(400).json({ error: 'userId and action required' });
  try {
    let boDematNumber = null;
    if (action === 'approved') {
      let unique = false;
      while (!unique) {
        boDematNumber = generateDematNumber();
        const [ex] = await db.pool.query('SELECT id FROM app_users WHERE bo_demat_number = ?', [boDematNumber]);
        if (!ex.length) unique = true;
      }
      await db.pool.query(
        `UPDATE app_users SET kyc_status='verified', is_active=1, bo_demat_number=? WHERE id=?`,
        [boDematNumber, userId]
      );
      await db.pool.query(
        'INSERT INTO app_wallet (user_id, balance) VALUES (?,0) ON DUPLICATE KEY UPDATE user_id=user_id',
        [userId]
      );
    } else {
      await db.pool.query(`UPDATE app_users SET kyc_status='rejected' WHERE id=?`, [userId]);
    }
    await db.pool.query(
      'INSERT INTO kyc_reviews (user_id, action, remarks) VALUES (?,?,?)',
      [userId, action, remarks || null]
    );
    res.json({ ok: true, message: action === 'approved' ? 'Verified' : 'Rejected', boDematNumber });
  } catch (err) { res.status(500).json({ error: err.message }); }
});
module.exports = router;
