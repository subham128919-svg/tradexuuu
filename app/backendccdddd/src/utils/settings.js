// Small helper around the `app_settings` key/value table.
// Everything the admin can change at runtime lives here:
// support details, WebView URLs, referral rewards, withdrawal limits.
const db = require('../config/database').pool;

let cache = null;
let cachedAt = 0;
const TTL_MS = 30_000; // 30s — admin edits show up almost immediately

const DEFAULTS = {
  support_title:         'We are here to help',
  support_subtitle:      'Our team replies within a few hours on working days.',
  support_whatsapp:      '',
  support_whatsapp_text: 'Hi TradeX support, I need help with my account.',
  support_phone:         '',
  support_email:         '',
  support_hours:         '',
  support_address:       '',
  support_note:          '',

  url_about:             '',
  url_charges:           '',

  referral_enabled:         '1',
  referral_reward_referrer: '0',
  referral_reward_referee:  '0',
  referral_share_url:       '',
  referral_share_message:   'Join TradeX with my code {code}: {link}',
  referral_terms:           '',

  withdraw_enabled: '1',
  withdraw_min:     '100',
  withdraw_max:     '200000',
  withdraw_note:    '',
};

async function getAll(force = false) {
  if (!force && cache && Date.now() - cachedAt < TTL_MS) return cache;
  try {
    const [rows] = await db.query('SELECT skey, svalue FROM app_settings');
    const map = { ...DEFAULTS };
    rows.forEach(r => { map[r.skey] = r.svalue == null ? '' : String(r.svalue); });
    cache = map;
    cachedAt = Date.now();
    return map;
  } catch (e) {
    // Table missing or DB hiccup — never take the app down over settings
    return cache || { ...DEFAULTS };
  }
}

async function get(key, fallback = '') {
  const all = await getAll();
  const v = all[key];
  return v === undefined || v === '' ? fallback : v;
}

async function getNumber(key, fallback = 0) {
  const v = await get(key, '');
  const n = parseFloat(v);
  return Number.isFinite(n) ? n : fallback;
}

async function getBool(key, fallback = false) {
  const v = (await get(key, '')).toString().toLowerCase();
  if (v === '') return fallback;
  return v === '1' || v === 'true' || v === 'yes';
}

async function setMany(obj) {
  const entries = Object.entries(obj || {});
  if (!entries.length) return 0;
  for (const [k, v] of entries) {
    await db.query(
      'INSERT INTO app_settings (skey, svalue) VALUES (?,?) ' +
      'ON DUPLICATE KEY UPDATE svalue = VALUES(svalue)',
      [String(k).slice(0, 80), v == null ? '' : String(v)]
    );
  }
  invalidate();
  return entries.length;
}

function invalidate() { cache = null; cachedAt = 0; }

module.exports = { getAll, get, getNumber, getBool, setMany, invalidate, DEFAULTS };
