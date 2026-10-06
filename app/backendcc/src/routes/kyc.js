/**
 * KYC Routes — document upload + admin verification
 *
 *  NEW (Feature #2 — upload as soon as the user picks a document)
 *   POST /api/v1/kyc/upload-doc     → upload ONE document, returns its URL
 *   POST /api/v1/kyc/submit         → finalise (no files, instant)
 *   GET  /api/v1/kyc/progress       → which of the 4 docs are already stored
 *
 *  EXISTING (kept for backwards compatibility with older app builds)
 *   POST /api/v1/kyc/upload         → all 4 files at once
 *   GET  /api/v1/kyc/status
 *   GET  /api/v1/kyc/account-info
 *   GET  /api/v1/kyc/admin/pending
 *   GET  /api/v1/kyc/admin/:userId
 *   POST /api/v1/kyc/admin/review
 *
 *  All handlers use req.userId, which middleware/auth.js now sets.
 */
const express = require('express');
const router  = express.Router();
const multer  = require('multer');
const path    = require('path');
const fs      = require('fs');
const db      = require('../config/database').pool;
const auth    = require('../middleware/auth');
const settings = require('../utils/settings');

// ─── File upload config ──────────────────────────────────────────
const uploadDir = path.join(__dirname, '..', '..', 'uploads', 'kyc');
if (!fs.existsSync(uploadDir)) fs.mkdirSync(uploadDir, { recursive: true });

const storage = multer.diskStorage({
  destination: (req, file, cb) => cb(null, uploadDir),
  filename: (req, file, cb) => {
    const ext  = (path.extname(file.originalname) || '.jpg').toLowerCase();
    const who  = req.userId || 'anon';
    const slot = req.body.doc_type || file.fieldname;
    cb(null, `${who}_${slot}_${Date.now()}${ext}`);
  }
});

const upload = multer({
  storage,
  limits: { fileSize: 8 * 1024 * 1024 }, // 8 MB
  fileFilter: (req, file, cb) => {
    const allowed = ['.jpg', '.jpeg', '.png', '.pdf', '.webp'];
    const ext = path.extname(file.originalname || '').toLowerCase();
    if (allowed.includes(ext)) cb(null, true);
    else cb(new Error('Only JPG, PNG, WEBP and PDF files are allowed'));
  }
});

// doc_type -> database column
const DOC_COLUMNS = {
  pan_front:     'pan_front_url',
  pan_back:      'pan_back_url',
  aadhaar_front: 'aadhaar_front_url',
  aadhaar_back:  'aadhaar_back_url',
};

function generateDematNumber() {
  let rest = '';
  for (let i = 0; i < 12; i++) rest += Math.floor(Math.random() * 10).toString();
  return '1208' + rest;
}

function removeIfLocal(url) {
  if (!url || !url.startsWith('/uploads/kyc/')) return;
  const p = path.join(uploadDir, path.basename(url));
  fs.unlink(p, () => {});
}

// ═════════════════════════════════════════════════════════════════
// FEATURE #2 — upload each document the moment the user picks it
// ═════════════════════════════════════════════════════════════════

// POST /api/v1/kyc/upload-doc   (multipart: file + doc_type)
router.post('/upload-doc', auth, upload.single('file'), async (req, res) => {
  try {
    const docType = String(req.body.doc_type || '').toLowerCase();
    const column  = DOC_COLUMNS[docType];

    if (!column) {
      if (req.file) fs.unlink(req.file.path, () => {});
      return res.status(400).json({
        error: 'doc_type must be one of pan_front, pan_back, aadhaar_front, aadhaar_back'
      });
    }
    if (!req.file) return res.status(400).json({ error: 'No file received' });

    // Already verified? Don't let documents be swapped afterwards.
    const [[current]] = await db.query(
      `SELECT kyc_status, ${column} AS old_url FROM app_users WHERE id = ?`, [req.userId]);
    if (!current) {
      fs.unlink(req.file.path, () => {});
      return res.status(404).json({ error: 'User not found' });
    }
    if (current.kyc_status === 'verified') {
      fs.unlink(req.file.path, () => {});
      return res.status(409).json({ error: 'KYC is already verified' });
    }

    const url = '/uploads/kyc/' + req.file.filename;
    await db.query(`UPDATE app_users SET ${column} = ? WHERE id = ?`, [url, req.userId]);

    // Replacing a document? delete the stale file so the disk doesn't fill.
    removeIfLocal(current.old_url);

    return res.json({
      ok: true,
      docType,
      url,
      size: req.file.size,
      message: 'Document uploaded'
    });
  } catch (err) {
    if (req.file) fs.unlink(req.file.path, () => {});
    console.error('KYC upload-doc error:', err.message);
    return res.status(500).json({ error: err.message || 'Upload failed' });
  }
});

// GET /api/v1/kyc/progress — what is already on the server
router.get('/progress', auth, async (req, res) => {
  try {
    const [[u]] = await db.query(
      `SELECT kyc_status, pan_number, aadhaar_number,
              pan_front_url, pan_back_url, aadhaar_front_url, aadhaar_back_url
         FROM app_users WHERE id = ?`, [req.userId]);
    if (!u) return res.status(404).json({ error: 'User not found' });

    const uploaded = {
      pan_front:     !!u.pan_front_url,
      pan_back:      !!u.pan_back_url,
      aadhaar_front: !!u.aadhaar_front_url,
      aadhaar_back:  !!u.aadhaar_back_url,
    };
    return res.json({
      kycStatus: u.kyc_status,
      panNumber: u.pan_number,
      aadhaarNumber: u.aadhaar_number,
      uploaded,
      allUploaded: Object.values(uploaded).every(Boolean)
    });
  } catch (err) {
    return res.status(500).json({ error: 'Failed to read progress' });
  }
});

// POST /api/v1/kyc/submit — finalise, no file transfer, returns instantly
router.post('/submit', auth, async (req, res) => {
  try {
    const panNumber = String(req.body.pan_number || '').trim().toUpperCase();
    const aadhaar   = String(req.body.aadhaar_number || '').replace(/\s/g, '');

    if (!/^[A-Z]{5}[0-9]{4}[A-Z]$/.test(panNumber))
      return res.status(400).json({ error: 'Invalid PAN number format (e.g. ABCDE1234F)' });
    if (!/^\d{12}$/.test(aadhaar))
      return res.status(400).json({ error: 'Invalid Aadhaar number (must be 12 digits)' });

    const [[u]] = await db.query(
      `SELECT kyc_status, pan_front_url, pan_back_url,
              aadhaar_front_url, aadhaar_back_url
         FROM app_users WHERE id = ?`, [req.userId]);
    if (!u) return res.status(404).json({ error: 'User not found' });
    if (u.kyc_status === 'verified')
      return res.status(409).json({ error: 'KYC is already verified' });

    const missing = [];
    if (!u.pan_front_url)     missing.push('PAN front');
    if (!u.pan_back_url)      missing.push('PAN back');
    if (!u.aadhaar_front_url) missing.push('Aadhaar front');
    if (!u.aadhaar_back_url)  missing.push('Aadhaar back');
    if (missing.length)
      return res.status(400).json({ error: 'Still missing: ' + missing.join(', ') });

    await db.query(
      `UPDATE app_users
          SET pan_number = ?, aadhaar_number = ?,
              kyc_status = 'pending', phone_verified = 1
        WHERE id = ?`,
      [panNumber, aadhaar, req.userId]
    );

    return res.json({
      ok: true,
      kycStatus: 'pending',
      message: 'KYC submitted. Your account is under review.'
    });
  } catch (err) {
    console.error('KYC submit error:', err.message);
    return res.status(500).json({ error: 'Submit failed' });
  }
});

// ═════════════════════════════════════════════════════════════════
// LEGACY — all four files in one request (old app builds)
// ═════════════════════════════════════════════════════════════════
router.post('/upload', auth, upload.fields([
  { name: 'pan_front', maxCount: 1 },
  { name: 'pan_back', maxCount: 1 },
  { name: 'aadhaar_front', maxCount: 1 },
  { name: 'aadhaar_back', maxCount: 1 }
]), async (req, res) => {
  try {
    const { pan_number, aadhaar_number } = req.body;
    if (!pan_number || !aadhaar_number)
      return res.status(400).json({ error: 'PAN and Aadhaar numbers are required' });

    const pan = String(pan_number).toUpperCase();
    if (!/^[A-Z]{5}[0-9]{4}[A-Z]$/.test(pan))
      return res.status(400).json({ error: 'Invalid PAN number format' });

    const cleanAadhaar = String(aadhaar_number).replace(/\s/g, '');
    if (!/^\d{12}$/.test(cleanAadhaar))
      return res.status(400).json({ error: 'Invalid Aadhaar number (must be 12 digits)' });

    const files = req.files || {};
    if (!files.pan_front || !files.pan_back || !files.aadhaar_front || !files.aadhaar_back)
      return res.status(400).json({ error: 'All 4 document images are required' });

    const base = '/uploads/kyc/';
    await db.query(
      `UPDATE app_users SET
          pan_number = ?, aadhaar_number = ?,
          pan_front_url = ?, pan_back_url = ?,
          aadhaar_front_url = ?, aadhaar_back_url = ?,
          kyc_status = 'pending', phone_verified = 1
        WHERE id = ?`,
      [pan, cleanAadhaar,
       base + files.pan_front[0].filename,
       base + files.pan_back[0].filename,
       base + files.aadhaar_front[0].filename,
       base + files.aadhaar_back[0].filename,
       req.userId]
    );

    return res.json({
      ok: true,
      message: 'KYC documents submitted. Your account is under review.',
      kycStatus: 'pending'
    });
  } catch (err) {
    console.error('KYC upload error:', err.message);
    return res.status(500).json({ error: 'Failed to upload documents' });
  }
});

// ═════════════════════════════════════════════════════════════════
// STATUS / ACCOUNT INFO
// ═════════════════════════════════════════════════════════════════
router.get('/status', auth, async (req, res) => {
  try {
    const [users] = await db.query(
      `SELECT kyc_status, pan_number, aadhaar_number, bo_demat_number, created_at
         FROM app_users WHERE id = ?`, [req.userId]);
    if (!users.length) return res.status(404).json({ error: 'User not found' });

    const u = users[0];
    const [[rev]] = await db.query(
      `SELECT remarks FROM kyc_reviews WHERE user_id = ?
        ORDER BY created_at DESC LIMIT 1`, [req.userId]).catch(() => [[null]]);

    return res.json({
      kycStatus:     u.kyc_status,
      panNumber:     u.pan_number,
      aadhaarMasked: u.aadhaar_number ? 'XXXX XXXX ' + String(u.aadhaar_number).slice(-4) : null,
      boDematNumber: u.bo_demat_number,
      submittedAt:   u.created_at,
      remarks:       rev ? rev.remarks : null
    });
  } catch (err) {
    return res.status(500).json({ error: 'Failed to fetch status' });
  }
});

router.get('/account-info', auth, async (req, res) => {
  try {
    const [users] = await db.query(
      `SELECT name, email, phone, created_at, kyc_status,
              pan_number, aadhaar_number, bo_demat_number
         FROM app_users WHERE id = ?`, [req.userId]);
    if (!users.length) return res.status(404).json({ error: 'User not found' });

    const u = users[0];
    return res.json({
      name:  u.name,
      email: u.email,
      phone: u.phone,
      joinedAt: u.created_at,
      kycStatus: u.kyc_status,
      panNumber: u.pan_number
        ? String(u.pan_number).slice(0, 2) + '****' + String(u.pan_number).slice(-2) : null,
      aadhaarMasked: u.aadhaar_number
        ? 'XXXX XXXX ' + String(u.aadhaar_number).slice(-4) : null,
      boDematNumber: u.bo_demat_number,
      accountActive: u.kyc_status === 'verified'
    });
  } catch (err) {
    console.error('account-info error:', err.message);
    return res.status(500).json({ error: 'Failed to fetch account info' });
  }
});

// ═════════════════════════════════════════════════════════════════
// ADMIN
// ═════════════════════════════════════════════════════════════════
const adminAuth = require('../middleware/adminAuth');

router.get('/admin/pending', adminAuth, async (req, res) => {
  try {
    const status = ['pending', 'rejected', 'verified', 'none'].includes(req.query.status)
      ? req.query.status : 'pending';
    const [users] = await db.query(
      `SELECT id, name, email, phone, pan_number, aadhaar_number,
              pan_front_url, pan_back_url, aadhaar_front_url, aadhaar_back_url,
              kyc_status, bo_demat_number, created_at
         FROM app_users WHERE kyc_status = ? ORDER BY created_at ASC`, [status]);
    return res.json({ data: users });
  } catch (err) {
    return res.status(500).json({ error: 'Failed to fetch KYC list' });
  }
});

router.get('/admin/:userId', adminAuth, async (req, res) => {
  try {
    const [users] = await db.query(
      `SELECT id, name, email, phone, pan_number, aadhaar_number,
              pan_front_url, pan_back_url, aadhaar_front_url, aadhaar_back_url,
              kyc_status, bo_demat_number, created_at
         FROM app_users WHERE id = ?`, [req.params.userId]);
    if (!users.length) return res.status(404).json({ error: 'User not found' });
    return res.json({ data: users[0] });
  } catch (err) {
    return res.status(500).json({ error: 'Failed to fetch user' });
  }
});

// POST /api/v1/kyc/admin/review  { userId, action, remarks }
router.post('/admin/review', adminAuth, async (req, res) => {
  const { userId, action, remarks } = req.body;
  if (!userId || !['approved', 'rejected'].includes(action))
    return res.status(400).json({ error: 'userId and action (approved/rejected) required' });

  try {
    let boDematNumber = null;

    if (action === 'approved') {
      let unique = false;
      while (!unique) {
        boDematNumber = generateDematNumber();
        const [dup] = await db.query(
          'SELECT id FROM app_users WHERE bo_demat_number = ?', [boDematNumber]);
        if (!dup.length) unique = true;
      }
      await db.query(
        `UPDATE app_users SET kyc_status = 'verified', is_active = 1, bo_demat_number = ?
          WHERE id = ?`, [boDematNumber, userId]);
      await db.query(
        'INSERT IGNORE INTO app_wallet (user_id, balance) VALUES (?, 0.00)', [userId]);

      // ── Feature #6: pay the referral reward on verification ──
      try { await payReferralReward(userId); }
      catch (e) { console.error('referral reward failed:', e.message); }
    } else {
      await db.query("UPDATE app_users SET kyc_status = 'rejected' WHERE id = ?", [userId]);
    }

    await db.query(
      'INSERT INTO kyc_reviews (user_id, action, remarks) VALUES (?,?,?)',
      [userId, action, remarks || null]);

    return res.json({
      ok: true,
      message: action === 'approved'
        ? `Account verified. Demat: ${boDematNumber}`
        : 'Account KYC rejected.',
      boDematNumber
    });
  } catch (err) {
    console.error('KYC review error:', err.message);
    return res.status(500).json({ error: 'Review failed' });
  }
});

// ── credit referrer + referee once the referee is verified ───────
async function payReferralReward(refereeId) {
  const [[row]] = await db.query(
    `SELECT id, referrer_id, status FROM referrals WHERE referee_id = ? LIMIT 1`, [refereeId]);
  if (!row || row.status !== 'pending') return;

  const enabled = await settings.getBool('referral_enabled', true);
  if (!enabled) return;

  const toReferrer = await settings.getNumber('referral_reward_referrer', 0);
  const toReferee  = await settings.getNumber('referral_reward_referee', 0);

  const conn = await require('../config/database').pool.getConnection();
  try {
    await conn.beginTransaction();

    if (toReferrer > 0) {
      await conn.query('INSERT IGNORE INTO app_wallet (user_id, balance) VALUES (?,0.00)', [row.referrer_id]);
      await conn.query('UPDATE app_wallet SET balance = balance + ? WHERE user_id = ?',
        [toReferrer, row.referrer_id]);
      await conn.query(
        `INSERT INTO wallet_transactions (user_id, type, amount, note, done_by)
         VALUES (?, 'credit', ?, ?, 'system')`,
        [row.referrer_id, toReferrer, 'Referral bonus for inviting user #' + refereeId]);
    }
    if (toReferee > 0) {
      await conn.query('INSERT IGNORE INTO app_wallet (user_id, balance) VALUES (?,0.00)', [refereeId]);
      await conn.query('UPDATE app_wallet SET balance = balance + ? WHERE user_id = ?',
        [toReferee, refereeId]);
      await conn.query(
        `INSERT INTO wallet_transactions (user_id, type, amount, note, done_by)
         VALUES (?, 'credit', ?, ?, 'system')`,
        [refereeId, toReferee, 'Welcome bonus for joining with a referral code']);
    }

    await conn.query(
      `UPDATE referrals SET status = 'rewarded', referrer_reward = ?, referee_reward = ?,
              rewarded_at = NOW() WHERE id = ?`,
      [toReferrer, toReferee, row.id]);

    await conn.commit();
  } catch (e) {
    try { await conn.rollback(); } catch {}
    throw e;
  } finally {
    conn.release();
  }
}

module.exports = router;
