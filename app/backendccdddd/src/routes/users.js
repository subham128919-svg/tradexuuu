// routes/users.js — App user register / login / profile
//
// CHANGES
//  #1  register + change-password now enforce the strong password policy
//      (8+ chars, upper, lower, digit, special).
//  +   register creates the wallet row and a unique referral_code, and
//      records `referred_by` when a valid referral code is supplied.
//  +   /me now also returns kyc_status, bo_demat_number and referral_code
//      so the app can gate the profile screen without an extra call.
const express  = require('express');
const bcrypt   = require('bcryptjs');
const jwt      = require('jsonwebtoken');
const db       = require('../config/database');
const { logger } = require('../config/logger');
const { validatePassword } = require('../utils/password');
const router   = express.Router();

const auth = require('../middleware/auth');

// ── helper: unique referral code ─────────────────────────────────
async function makeReferralCode(userId) {
  for (let i = 0; i < 6; i++) {
    const rand = Math.random().toString(36).slice(2, 6).toUpperCase();
    const code = `TX${String(userId).padStart(4, '0')}${rand}`.slice(0, 12);
    const [dup] = await db.pool.query(
      'SELECT id FROM app_users WHERE referral_code = ? LIMIT 1', [code]);
    if (!dup.length) return code;
  }
  return `TX${Date.now().toString(36).toUpperCase()}`.slice(0, 12);
}

// ── POST /api/v1/users/register ──────────────────────────────────
router.post('/register', async (req, res) => {
  const { name, email, phone, password } = req.body;
  const referralCode = (req.body.referral_code || req.body.referralCode || '')
    .toString().trim().toUpperCase();

  if (!name || !email || !password)
    return res.status(400).json({ error: 'name, email and password are required' });

  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(String(email).trim()))
    return res.status(400).json({ error: 'Enter a valid email address' });

  // ── Feature #1: strong password policy ────────────────────────
  const pw = validatePassword(password);
  if (!pw.ok) return res.status(400).json({ error: pw.message, errors: pw.errors });

  const cleanPhone = phone ? String(phone).replace(/[^0-9]/g, '') : null;

  const conn = await db.pool.getConnection();
  try {
    await conn.beginTransaction();

    const [existing] = await conn.query(
      'SELECT id FROM app_users WHERE email = ? OR (phone IS NOT NULL AND phone = ?) LIMIT 1',
      [email, cleanPhone]
    );
    if (existing.length) {
      await conn.rollback(); conn.release();
      return res.status(409).json({ error: 'Email or phone already registered' });
    }

    // Was the phone OTP verified in the last 30 minutes?
    let phoneVerified = 0;
    if (cleanPhone) {
      const [otp] = await conn.query(
        `SELECT id FROM otp_sessions
          WHERE phone = ? AND purpose = 'signup' AND is_verified = 1
            AND created_at > DATE_SUB(NOW(), INTERVAL 30 MINUTE)
          ORDER BY created_at DESC LIMIT 1`, [cleanPhone]);
      phoneVerified = otp.length ? 1 : 0;
    }

    // Who referred this user?
    let referrerId = null;
    if (referralCode) {
      const [ref] = await conn.query(
        'SELECT id FROM app_users WHERE referral_code = ? LIMIT 1', [referralCode]);
      if (ref.length) referrerId = ref[0].id;
    }

    const hash = await bcrypt.hash(password, 10);

    const [result] = await conn.query(
      `INSERT INTO app_users
         (name, email, phone, password_hash, phone_verified, is_active, kyc_status, referred_by)
       VALUES (?,?,?,?,?,0,'none',?)`,
      [name, email, cleanPhone, hash, phoneVerified, referrerId]
    );
    const userId = result.insertId;

    await conn.query('INSERT IGNORE INTO app_wallet (user_id, balance) VALUES (?, 0.00)', [userId]);
    await conn.commit();
    conn.release();

    // Referral code + ledger row (outside the transaction, non-critical)
    let myCode = null;
    try {
      myCode = await makeReferralCode(userId);
      await db.pool.query('UPDATE app_users SET referral_code = ? WHERE id = ?', [myCode, userId]);
    } catch (e) { logger.warn('referral code gen failed: ' + e.message); }

    if (referrerId) {
      try {
        await db.pool.query(
          `INSERT IGNORE INTO referrals (referrer_id, referee_id, code_used, status)
           VALUES (?,?,?, 'pending')`,
          [referrerId, userId, referralCode]
        );
      } catch (e) { logger.warn('referral ledger failed: ' + e.message); }
    }

    const token = jwt.sign(
      { userId, email, name }, process.env.JWT_SECRET, { expiresIn: '30d' });

    res.json({
      token,
      user: {
        id: userId, name, email, phone: cleanPhone,
        kycStatus: 'none', referralCode: myCode
      }
    });
  } catch (err) {
    try { await conn.rollback(); } catch {}
    try { conn.release(); } catch {}
    if (err.code === 'ER_DUP_ENTRY')
      return res.status(409).json({ error: 'Email already registered' });
    logger.error('Register error: ' + err.message);
    res.status(500).json({ error: 'Registration failed' });
  }
});

// ── POST /api/v1/users/login ─────────────────────────────────────
// NOTE: `is_active` is 0 until KYC is approved, so we must NOT filter on
// it here — the user has to be able to sign in and see the
// "under review" screen (Feature #3).
router.post('/login', async (req, res) => {
  const { email, password } = req.body;
  if (!email || !password)
    return res.status(400).json({ error: 'email and password required' });
  try {
    const [rows] = await db.pool.query('SELECT * FROM app_users WHERE email = ?', [email]);
    if (!rows.length) return res.status(401).json({ error: 'Invalid credentials' });

    const user = rows[0];
    if (user.is_blocked) return res.status(403).json({ error: 'Account blocked' });

    const ok = await bcrypt.compare(password, user.password_hash);
    if (!ok) return res.status(401).json({ error: 'Invalid credentials' });

    await db.pool.query('UPDATE app_users SET last_login_at = NOW() WHERE id = ?', [user.id]);

    const token = jwt.sign(
      { userId: user.id, email: user.email, name: user.name },
      process.env.JWT_SECRET, { expiresIn: '30d' });

    res.json({
      token,
      user: {
        id: user.id, name: user.name, email: user.email, phone: user.phone,
        kycStatus: user.kyc_status, boDematNumber: user.bo_demat_number,
        referralCode: user.referral_code
      }
    });
  } catch (err) {
    logger.error('Login error: ' + err.message);
    res.status(500).json({ error: 'Login failed' });
  }
});

// ── GET /api/v1/users/me ─────────────────────────────────────────
router.get('/me', auth, async (req, res) => {
  try {
    const [rows] = await db.pool.query(
      `SELECT id, name, email, phone, kyc_status, bo_demat_number,
              referral_code, created_at, last_login_at
         FROM app_users WHERE id = ?`, [req.userId]);
    if (!rows.length) return res.status(404).json({ error: 'User not found' });
    const u = rows[0];
    res.json({
      user: {
        id: u.id, name: u.name, email: u.email, phone: u.phone,
        kycStatus: u.kyc_status, boDematNumber: u.bo_demat_number,
        referralCode: u.referral_code,
        createdAt: u.created_at, lastLoginAt: u.last_login_at
      }
    });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// ── POST /api/v1/users/change-password ───────────────────────────
router.post('/change-password', auth, async (req, res) => {
  const { oldPassword, newPassword } = req.body;
  if (!oldPassword || !newPassword)
    return res.status(400).json({ error: 'oldPassword and newPassword are required' });

  const pw = validatePassword(newPassword);
  if (!pw.ok) return res.status(400).json({ error: pw.message, errors: pw.errors });

  try {
    const [rows] = await db.pool.query(
      'SELECT password_hash FROM app_users WHERE id = ?', [req.userId]);
    if (!rows.length) return res.status(404).json({ error: 'User not found' });
    if (!(await bcrypt.compare(oldPassword, rows[0].password_hash)))
      return res.status(401).json({ error: 'Current password is incorrect' });

    const hash = await bcrypt.hash(newPassword, 10);
    await db.pool.query('UPDATE app_users SET password_hash = ? WHERE id = ?', [hash, req.userId]);
    res.json({ ok: true, message: 'Password updated' });
  } catch (err) {
    res.status(500).json({ error: 'Could not update password' });
  }
});

// ── POST /api/v1/users/login-phone ───────────────────────────────
router.post('/login-phone', async (req, res) => {
  try {
    const { phone } = req.body;
    if (!phone) return res.status(400).json({ error: 'Phone required' });
    const cleanPhone = String(phone).replace(/[^0-9]/g, '');

    const [otpCheck] = await db.pool.query(
      `SELECT id FROM otp_sessions
        WHERE phone = ? AND purpose = 'login' AND is_verified = 1
          AND created_at > DATE_SUB(NOW(), INTERVAL 10 MINUTE)
        ORDER BY created_at DESC LIMIT 1`, [cleanPhone]);
    if (!otpCheck.length) return res.status(401).json({ error: 'OTP not verified' });

    const [users] = await db.pool.query(
      `SELECT id, name, email, phone, is_blocked, kyc_status, bo_demat_number, referral_code
         FROM app_users WHERE phone = ?`, [cleanPhone]);
    if (!users.length) return res.status(404).json({ error: 'No account found' });
    if (users[0].is_blocked) return res.status(403).json({ error: 'Account blocked' });

    const u = users[0];
    const token = jwt.sign(
      { userId: u.id, email: u.email, name: u.name },
      process.env.JWT_SECRET, { expiresIn: '30d' });

    await db.pool.query('UPDATE app_users SET last_login_at = NOW() WHERE id = ?', [u.id]);

    res.json({
      token,
      user: {
        id: u.id, name: u.name, email: u.email, phone: u.phone,
        kycStatus: u.kyc_status, boDematNumber: u.bo_demat_number,
        referralCode: u.referral_code
      }
    });
  } catch (err) {
    logger.error('Phone login error: ' + err.message);
    res.status(500).json({ error: 'Login failed' });
  }
});

module.exports = router;
