// scripts/syncInstruments.js  (UPDATED — now also stores instrument_type)
// Run this after market hours to keep the instruments table fresh.
// The instrument_type column enables clean filtering in bulkSync.js.
require('dotenv').config();
const { KiteConnect } = require('kiteconnect');
const ioredis = require('ioredis');

async function main() {
  const redis = new ioredis(process.env.REDIS_URL || 'redis://localhost:6379');
  const token = await redis.get('kite:access_token');
  if (!token) { console.error('No Kite token — log in first'); process.exit(1); }
  const mysql = require('mysql2/promise');
  const db    = await mysql.createConnection({
    host: process.env.DB_HOST, port: process.env.DB_PORT || 3306,
    user: process.env.DB_USER, password: process.env.DB_PASSWORD,
    database: process.env.DB_NAME,
  });
  const kite = new KiteConnect({ api_key: process.env.KITE_API_KEY });
  kite.setAccessToken(token);

  console.log('Downloading NSE instruments...');
  const nse = await kite.getInstruments(['NSE']);
  console.log('Downloading BSE instruments...');
  const bse = await kite.getInstruments(['BSE']);
  console.log('Downloading NSE indices...');
  const nseIdx = await kite.getInstruments(['NSE']).then(list =>
    list.filter(i => i.segment === 'INDICES'));

  const all = [...nse, ...bse, ...nseIdx].filter(i =>
    i.segment === 'NSE' || i.segment === 'BSE' || i.segment === 'INDICES'
  );

  console.log('Upserting', all.length, 'instruments...');
  const CHUNK = 500;
  for (let i = 0; i < all.length; i += CHUNK) {
    const chunk  = all.slice(i, i + CHUNK);
    const values = chunk.map(x => [
      x.instrument_token, x.tradingsymbol,
      x.name || x.tradingsymbol, x.exchange, x.segment,
      x.instrument_type || 'EQ'
    ]);
    await db.query(
      'INSERT INTO instruments (instrument_token, tradingsymbol, name, exchange, segment, instrument_type) VALUES ? ' +
      'ON DUPLICATE KEY UPDATE tradingsymbol=VALUES(tradingsymbol), name=VALUES(name), ' +
      'exchange=VALUES(exchange), segment=VALUES(segment), instrument_type=VALUES(instrument_type)',
      [values]
    );
    process.stdout.write('\r  ' + Math.min(i + CHUNK, all.length) + '/' + all.length);
  }
  console.log('\nDone.');
  await db.end();
  await redis.quit();
  process.exit(0);
}
main().catch(e => { console.error(e.message); process.exit(1); });
