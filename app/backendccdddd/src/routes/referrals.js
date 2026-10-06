// routes/referrals.js — Feature #6 "Refer & Earn"
//
//   GET /api/v1/referrals/me  → code, share link, share message, rewards,
//                               totals and the list of people invited.
// All amounts, the share URL and the message template are set by the
// admin in app_settings, so nothing here is hard-coded.
const express  = require('express');
const router   = express.Router();
const db       = require('../config/database').pool;
const auth     = require('../middleware/auth');
const settings = require('../utils/settings');

async function ensureCode(userId, existing) {
  if (existing) return existing;
  for (let i = 0; i < 6; i++) {
    const rand = Math.random().toString(36).slice(2, 6).toUpperCase();
    const code = `TX${String(userId).padStart(4, '0')}${rand}`.slice(0, 12);
    const [dup] = await db.query('SELECT id FROM app_users WHERE referral_code = ? LIMIT 1', [code]);
    if (!dup.length) {
      await db.query('UPDATE app_users SET referral_code = ? WHERE id = ?', [code, userId]);
      return code;
    }
  }
  return null;
}

router.get('/me', auth, async (req, res) => {
  try {
    const [[u]] = await db.query(
      'SELECT id, name, referral_code FROM app_users WHERE id = ?', [req.userId]);
    if (!u) return res.status(404).json({ error: 'User not found' });

    const code = await ensureCode(u.id, u.referral_code);
    const s    = await settings.getAll();

    const baseUrl   = (s.referral_share_url || '').replace(/\/+$/, '');
    const shareLink = baseUrl ? `${baseUrl}?ref=${code}` : '';
    const shareText = (s.referral_share_message || '')
      .replace(/\{code\}/g, code || '')
      .replace(/\{link\}/g, shareLink)
      .replace(/\{name\}/g, u.name || '');

    const [rows] = await db.query(
      `SELECT r.id, r.status, r.referrer_reward, r.created_at, r.rewarded_at,
              a.name AS referee_name, a.kyc_status
         FROM referrals r
         JOIN app_users a ON a.id = r.referee_id
        WHERE r.referrer_id = ?
        ORDER BY r.created_at DESC
        LIMIT 100`, [req.userId]);

    const totalEarned = rows
      .filter(r => r.status === 'rewarded')
      .reduce((sum, r) => sum + parseFloat(r.referrer_reward || 0), 0);

    res.json({
      enabled:       s.referral_enabled === '1',
      code,
      shareLink,
      shareText,
      rewardPerReferral: parseFloat(s.referral_reward_referrer) || 0,
      joiningBonus:      parseFloat(s.referral_reward_referee) || 0,
      terms:             s.referral_terms,
      totalInvited:      rows.length,
      totalRewarded:     rows.filter(r => r.status === 'rewarded').length,
      totalEarned,
      referrals: rows.map(r => ({
        name:      maskName(r.referee_name),
        status:    r.status,
        kycStatus: r.kyc_status,
        reward:    parseFloat(r.referrer_reward || 0),
        joinedAt:  r.created_at,
        paidAt:    r.rewarded_at
      }))
    });
  } catch (err) {
    console.error('referrals/me error:', err.message);
    res.status(500).json({ error: 'Failed to load referral details' });
  }
});

// Privacy: show "Rahul S." rather than the invitee's full name.
function maskName(name) {
  if (!name) return 'TradeX user';
  const parts = String(name).trim().split(/\s+/);
  if (parts.length === 1) return parts[0];
  return parts[0] + ' ' + parts[parts.length - 1][0].toUpperCase() + '.';
}

module.exports = router;
