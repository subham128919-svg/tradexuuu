// scripts/seedHistorical.js
// Fetches the MAXIMUM available history from Kite for the 3 pilot
// stocks (RELIANCE, TCS, HDFCBANK) and stores everything in MySQL.
//
// Kite's maximum history per interval:
//   day    — ~5.5 years (2000 trading days)
//   week   — ~5.5 years
//   5minute  — 60 days
//   30minute — 60 days
//
// Run ONCE after logging in to Kite today:
//   node scripts/seedHistorical.js
//
// After this, the app serves all charts from MySQL — no Kite calls
// needed for historical data until new candles are needed.
require('dotenv').config();
const ioredis = require('ioredis');
const { KiteConnect } = require('kiteconnect');
const candleStore = require('../src/services/candleStore');
const db = require('../src/config/database');
const kite = require('../src/services/kiteService');

// The 3 pilot stocks for Phase 1 testing
const PILOT_STOCKS = [
  { symbol: 'NSE:RELIANCE', name: 'Reliance Industries' },
  { symbol: 'NSE:TCS',      name: 'Tata Consultancy Services' },
  { symbol: 'NSE:HDFCBANK', name: 'HDFC Bank' },
];

// All timeframes we want to populate
const PERIODS = ['1D', '1W', '1M', '3M', '1Y', '5Y'];

async function sleep(ms) {
  return new Promise(r => setTimeout(r, ms));
}

async function main() {
  console.log('=== TradeX Historical Data Seeder ===');
  console.log('Pilot stocks: RELIANCE, TCS, HDFCBANK\n');

  // Restore Kite token from Redis
  const redis = new ioredis(process.env.REDIS_URL || 'redis://localhost:6379');
  const token = await redis.get('kite:access_token');
  if (!token) {
    console.error('ERROR: No Kite access token in Redis.');
    console.error('Please log in first: https://tradingstock.online/api/v1/auth/kite-login');
    process.exit(1);
  }
  kite.setAccessToken(token);
  console.log('Kite token loaded from Redis.\n');

  await db.connect();
  console.log('MySQL connected.\n');

  let totalCandles = 0;

  for (const stock of PILOT_STOCKS) {
    console.log('── ' + stock.symbol + ' (' + stock.name + ') ──');
    for (const period of PERIODS) {
      try {
        const candles = await candleStore.getCandles(stock.symbol, period);
        totalCandles += candles.length;
        console.log('  ' + period + ': ' + candles.length + ' candles stored');
        await sleep(500); // 500ms between requests to stay under Kite rate limits
      } catch (err) {
        console.error('  ' + period + ': FAILED — ' + err.message);
      }
    }
    console.log('');
    await sleep(1000); // 1s between stocks
  }

  console.log('=== Done. Total candles stored: ' + totalCandles + ' ===');
  console.log('\nNow restart your server: pm2 restart tradingapp');
  console.log('Then test in the app by tapping RELIANCE, TCS, or HDFCBANK.');

  await redis.quit();
  process.exit(0);
}

main().catch(err => {
  console.error('Seeder failed:', err.message);
  process.exit(1);
});
