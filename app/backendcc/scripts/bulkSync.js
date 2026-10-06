// scripts/bulkSync.js
// Downloads historical data for ALL NSE equities and all indices.
// Resumable: skips symbols already in sync_status (status='done').
// Rate-limited: 3 req/sec to stay within Kite limits.
//
// Runtime estimate:
//   NSE EQ stocks  ~2000 × 4 intervals = ~8000 Kite calls
//   At 3/sec = ~45 minutes total
//
// Run ONCE after seeding:
//   node scripts/bulkSync.js
//   (Can Ctrl+C and re-run — it will resume from where it left off)
require('dotenv').config();
const ioredis = require('ioredis');
const mysql   = require('mysql2/promise');
const { KiteConnect } = require('kiteconnect');

// Only these intervals are stored — covers every period in the app.
const INTERVALS = [
  { name: '5minute',  daysBack: 60  },   // covers 1D and 1W views
  { name: '30minute', daysBack: 60  },   // covers 1W view
  { name: 'day',      daysBack: 1825 },  // 5 years — covers 1M/3M/1Y/5Y
  { name: 'week',     daysBack: 1825 },  // 5 years — covers 5Y view
];

const DELAY_MS = 350;  // ~3 req/sec — safe under Kite rate limits

function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }

function dateStr(daysBack) {
  const d = new Date();
  d.setDate(d.getDate() - daysBack);
  return d.toISOString().split('T')[0];
}
function today() { return new Date().toISOString().split('T')[0]; }

async function storeCandles(db, symbol, interval, candles) {
  if (!candles.length) return;
  const CHUNK = 300;
  for (let i = 0; i < candles.length; i += CHUNK) {
    const values = candles.slice(i, i + CHUNK).map(c => [
      symbol, interval, new Date(c.time * 1000),
      c.open, c.high, c.low, c.close, c.volume || 0
    ]);
    await db.query(
      'INSERT INTO candles (symbol, `interval`, candle_time, open, high, low, close, volume) VALUES ? ' +
      'ON DUPLICATE KEY UPDATE open=VALUES(open),high=VALUES(high),low=VALUES(low),close=VALUES(close),volume=VALUES(volume)',
      [values]
    );
  }
}

async function markStatus(db, symbol, interval, status, count, err) {
  await db.query(
    'INSERT INTO sync_status (symbol, `interval`, candle_count, status, error_msg) VALUES (?,?,?,?,?) ' +
    'ON DUPLICATE KEY UPDATE candle_count=VALUES(candle_count), status=VALUES(status), ' +
    'error_msg=VALUES(error_msg), last_synced=NOW()',
    [symbol, interval, count || 0, status, err || null]
  );
}

async function main() {
  const redis = new ioredis(process.env.REDIS_URL || 'redis://localhost:6379');
  const token = await redis.get('kite:access_token');
  if (!token) { console.error('No Kite token. Log in at /api/v1/auth/kite-login'); process.exit(1); }

  const db = await mysql.createConnection({
    host: process.env.DB_HOST, port: process.env.DB_PORT || 3306,
    user: process.env.DB_USER, password: process.env.DB_PASSWORD,
    database: process.env.DB_NAME, multipleStatements: true,
  });

  const kite = new KiteConnect({ api_key: process.env.KITE_API_KEY });
  kite.setAccessToken(token);

  // Fetch instruments to sync:
  //  1. NSE equities (instrument_type = 'EQ', segment = 'NSE')
  //  2. All indices   (segment = 'INDICES')
  // Skip bonds/debentures (they have complex symbols like '66RJ30-SG').
  const [instruments] = await db.query(
    "SELECT instrument_token, tradingsymbol, exchange FROM instruments " +
    "WHERE (segment = 'INDICES') " +
    "   OR (segment = 'NSE' AND (instrument_type = 'EQ' OR instrument_type IS NULL) " +
    "       AND tradingsymbol NOT LIKE '%-SG' AND tradingsymbol NOT LIKE '%-BE' " +
    "       AND tradingsymbol NOT LIKE '%-SM' AND tradingsymbol NOT LIKE '%-BL' " +
    "       AND tradingsymbol NOT RLIKE '^[0-9]' AND LENGTH(tradingsymbol) <= 15) " +
    "ORDER BY segment, tradingsymbol"
  );
  console.log('Instruments to sync:', instruments.length);

  // Load already-done combinations so we can skip them
  const [doneRows] = await db.query("SELECT symbol, `interval` FROM sync_status WHERE status='done'");
  const doneSet = new Set(doneRows.map(r => r.symbol + '|' + r.interval));

  let done = 0, skipped = 0, failed = 0;
  const total = instruments.length * INTERVALS.length;

  for (const inst of instruments) {
    const fullSym = inst.exchange + ':' + inst.tradingsymbol;

    for (const { name: interval, daysBack } of INTERVALS) {
      const key = fullSym + '|' + interval;
      if (doneSet.has(key)) { skipped++; continue; }

      await sleep(DELAY_MS);

      try {
        const from    = dateStr(daysBack);
        const to      = today();
        const candles = await kite.getHistoricalData(
          inst.instrument_token, interval, from, to
        );
        const mapped = (candles || []).map(c => ({
          time:   Math.floor(new Date(c.date).getTime() / 1000),
          open:   c.open, high: c.high, low: c.low, close: c.close, volume: c.volume || 0
        }));
        await storeCandles(db, fullSym, interval, mapped);
        await markStatus(db, fullSym, interval, 'done', mapped.length, null);
        done++;
      } catch (err) {
        await markStatus(db, fullSym, interval, 'failed', 0, err.message.slice(0, 200));
        failed++;
      }

      const pct = ((done + skipped + failed) / total * 100).toFixed(1);
      process.stdout.write('\r  Progress: ' + (done+skipped+failed) + '/' + total +
        ' (' + pct + '%)  done=' + done + ' skip=' + skipped + ' fail=' + failed + '  ');
    }
  }

  console.log('\n\nBulk sync complete.');
  console.log('Done:', done, '  Skipped:', skipped, '  Failed:', failed);
  console.log('Re-run any time to sync new data (skips already-done combinations).');

  await db.end();
  await redis.quit();
  process.exit(0);
}
main().catch(e => { console.error('\nFatal:', e.message); process.exit(1); });
