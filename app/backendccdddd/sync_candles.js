#!/usr/bin/env node
// sync_candles.js — Force-sync all tracked symbols from Kite
// Usage: cd /var/www/tradingapp && node sync_candles.js
//
// This script reads the Kite token directly from the running pm2
// process's Redis cache, connects to your DB, and fetches candles
// for every tracked symbol across all time periods. It shows a
// live progress counter so you can see exactly what's been done.

require('dotenv').config();
const db         = require('./src/config/database');
const redis      = require('./src/config/redis');
const kite       = require('./src/services/kiteService');
const candleStore = require('./src/services/candleStore');

const PERIODS = ['1D', '1W', '1M', '3M', '1Y'];

(async () => {
  // Connect Redis
  try { await redis.connect(); } catch (e) {
    console.log('WARN: Redis connect failed (' + e.message + '), trying anyway...');
  }

  // Get Kite token
  let token = null;
  try { token = await redis.get('kite:access_token'); } catch (e) {
    console.log('ERROR: Cannot read Kite token from Redis: ' + e.message);
    console.log('');
    console.log('FIX: Login to Kite first:');
    console.log('  Open https://tradingstock.online/api/v1/auth/kite-login in a browser');
    console.log('  Complete the Zerodha login, then re-run this script.');
    process.exit(1);
  }

  if (!token) {
    console.log('ERROR: No Kite access token found in Redis.');
    console.log('');
    console.log('FIX: Login to Kite first:');
    console.log('  Open https://tradingstock.online/api/v1/auth/kite-login in a browser');
    console.log('  Complete the Zerodha login, then re-run this script.');
    process.exit(1);
  }

  kite.setAccessToken(token);
  console.log('Kite token loaded.');

  // Connect DB
  await db.connect();
  console.log('DB connected.');

  // Get all symbols to sync
  const [rows] = await db.pool.query(
    'SELECT DISTINCT symbol FROM tracked_symbols ' +
    'UNION SELECT symbol FROM admin_universe WHERE is_active=1'
  );
  const symbols = rows.map(r => r.symbol);
  const total   = symbols.length * PERIODS.length;

  console.log('');
  console.log('Syncing ' + symbols.length + ' symbols x ' + PERIODS.length + ' periods = ' + total + ' tasks');
  console.log('Estimated time: ~' + Math.ceil(total * 0.5 / 60) + ' minutes');
  console.log('─'.repeat(60));

  let done = 0, succeeded = 0, failed = 0;

  for (const sym of symbols) {
    for (const p of PERIODS) {
      try {
        const candles = await candleStore.getCandles(sym, p);
        succeeded++;
        console.log('[' + (++done) + '/' + total + '] ✓ ' + sym + '/' + p + ': ' + candles.length + ' candles');
      } catch (e) {
        failed++;
        console.log('[' + (++done) + '/' + total + '] ✗ ' + sym + '/' + p + ': ' + e.message);
      }
      // 400ms delay between calls — Kite rate limit is ~10 req/s for
      // historical data; this keeps us safely under that.
      await new Promise(r => setTimeout(r, 400));
    }
  }

  console.log('─'.repeat(60));
  console.log('Done! ' + succeeded + ' succeeded, ' + failed + ' failed out of ' + total);
  process.exit(0);
})().catch(err => {
  console.error('Fatal error:', err.message);
  process.exit(1);
});
