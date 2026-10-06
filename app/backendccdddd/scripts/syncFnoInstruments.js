// scripts/syncFnoInstruments.js — standalone CLI wrapper.
// The actual sync logic lives in src/services/fnoSyncService.js, shared
// with scheduler.js (which runs this daily in-process automatically).
// Run manually if you need an immediate refresh: node scripts/syncFnoInstruments.js
require('dotenv').config();
const { KiteConnect } = require('kiteconnect');
const ioredis = require('ioredis');
const mysql   = require('mysql2/promise');

async function main() {
  const redis = new ioredis(process.env.REDIS_URL || 'redis://localhost:6379');
  const token = await redis.get('kite:access_token');
  if (!token) { console.error('No Kite token — log in first at /api/v1/auth/kite-login'); process.exit(1); }

  const db = await mysql.createPool({
    host: process.env.DB_HOST, port: process.env.DB_PORT || 3306,
    user: process.env.DB_USER, password: process.env.DB_PASSWORD,
    database: process.env.DB_NAME,
  });

  // kiteService.js exports the shared `kite` instance — set its token
  // directly here since this script runs standalone (no server booted).
  const kiteService = require('../src/services/kiteService');
  kiteService.setAccessToken(token);

  const { syncFnoInstruments } = require('../src/services/fnoSyncService');
  const result = await syncFnoInstruments(db);
  console.log('Synced:', result.synced, '| Removed expired:', result.removed);

  await db.end();
  await redis.quit();
  process.exit(0);
}
main().catch(e => { console.error('FNO sync failed:', e.message); process.exit(1); });
