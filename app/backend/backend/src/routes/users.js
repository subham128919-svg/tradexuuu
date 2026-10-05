// routes/users.js — App user register / login / profile
const express  = require('express');
const bcrypt   = require('bcryptjs');
const jwt      = require('jsonwebtoken');
const db       = require('../config/database');
const { logger } = require('../config/logger');
const router   = express.Router();

// POST /api/v1/users/register
router.post('/register', async (req, res) => {
  const { name, email, phone, password } = req.body;
  if (!name || !email || !password)
    return res.status(400).json({ error: 'name, email and password are required' });
  try {
    const hash = await bcrypt.hash(password, 10);
    const [result] = await db.pool.query(
      'INSERT INTO app_users (name, email, phone, password_hash) VALUES (?,?,?,?)',
      [name, email, phone || null, hash]
    );
    const token = jwt.sign(
      { userId: result.insertId, email, name },
      process.env.JWT_SECRET,
      { expiresIn: '30d' }
    );
    res.json({ token, user: { id: result.insertId, name, email } });
  } catch (err) {
    if (err.code === 'ER_DUP_ENTRY')
      return res.status(409).json({ error: 'Email already registered' });
    logger.error('Register error', err.message);
    res.status(500).json({ error: 'Registration failed' });
  }
});

// POST /api/v1/users/login
router.post('/login', async (req, res) => {
  const { email, password } = req.body;
  if (!email || !password)
    return res.status(400).json({ error: 'email and password required' });
  try {
    const [rows] = await db.pool.query(
      'SELECT * FROM app_users WHERE email = ? AND is_active = 1', [email]);
    if (!rows.length) return res.status(401).json({ error: 'Invalid credentials' });
    const user = rows[0];
    if (user.is_blocked) return res.status(403).json({ error: 'Account blocked' });
    const ok = await bcrypt.compare(password, user.password_hash);
    if (!ok) return res.status(401).json({ error: 'Invalid credentials' });
    await db.pool.query('UPDATE app_users SET last_login_at = NOW() WHERE id = ?', [user.id]);
    const token = jwt.sign(
      { userId: user.id, email: user.email, name: user.name },
      process.env.JWT_SECRET,
      { expiresIn: '30d' }
    );
    res.json({ token, user: { id: user.id, name: user.name, email: user.email, phone: user.phone } });
  } catch (err) {
    logger.error('Login error', err.message);
    res.status(500).json({ error: 'Login failed' });
  }
});

// GET /api/v1/users/me  (requires auth)
router.get('/me', require('../middleware/auth'), async (req, res) => {
  try {
    const [rows] = await db.pool.query(
      'SELECT id, name, email, phone, created_at, last_login_at FROM app_users WHERE id = ?',
      [req.user.userId]
    );
    if (!rows.length) return res.status(404).json({ error: 'User not found' });
    res.json({ user: rows[0] });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});
// ── Phone login with OTP ─────────────────────────────────────────
router.post('/login-phone', async (req, res) => {
    try {
        const { phone } = req.body;
        if (!phone) return res.status(400).json({ error: 'Phone required' });
        const cleanPhone = phone.replace(/[^0-9]/g, '');
        const [otpCheck] = await db.pool.query(
            `SELECT id FROM otp_sessions WHERE phone = ? AND purpose = 'login' AND is_verified = 1 AND created_at > DATE_SUB(NOW(), INTERVAL 10 MINUTE) ORDER BY created_at DESC LIMIT 1`,
            [cleanPhone]
        );
        if (otpCheck.length === 0) return res.status(401).json({ error: 'OTP not verified' });
        const [users] = await db.pool.query('SELECT id, name, email, phone, is_blocked, kyc_status, bo_demat_number FROM app_users WHERE phone = ?', [cleanPhone]);
        if (users.length === 0) return res.status(404).json({ error: 'No account found' });
        if (users[0].is_blocked) return res.status(403).json({ error: 'Account blocked' });
        const jwt = require('jsonwebtoken');
        const token = jwt.sign({ userId: users[0].id, email: users[0].email }, process.env.JWT_SECRET || 'your_jwt_secret_key', { expiresIn: '30d' });
        await db.pool.query('UPDATE app_users SET last_login_at = NOW() WHERE id = ?', [users[0].id]);
        return res.json({ token, user: { id: users[0].id, name: users[0].name, email: users[0].email, phone: users[0].phone } });
    } catch (err) { console.error(err); return res.status(500).json({ error: 'Login failed' }); }
});
module.exports = router;
