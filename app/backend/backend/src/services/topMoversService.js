// topMoversService.js
// Computes and caches top 5 gainers + losers + top 10 indices.
// Results are stored in top_movers_cache so the API serves them
// in a single fast DB read — no heavy computation per request.

const db = require('../config/database');
const { logger } = require('../config/logger');

// All NSE indices we want to track
const INDEX_SYMBOLS = [
  'NSE:NIFTY 50', 'NSE:NIFTY BANK', 'NSE:NIFTY IT',
  'NSE:NIFTY PHARMA', 'NSE:NIFTY FMCG', 'NSE:NIFTY AUTO',
  'NSE:NIFTY METAL', 'NSE:NIFTY REALTY', 'NSE:NIFTY INFRA',
  'NSE:NIFTY MIDCAP 100', 'NSE:NIFTY 100', 'NSE:NIFTY 200',
  'NSE:INDIA VIX', 'BSE:SENSEX',
];

// Nifty 500 proxy — use Nifty 50 hardcoded for movers since they have
// the most reliable candle data after the first bulk sync.
const UNIVERSE_FOR_MOVERS = [
  'NSE:RELIANCE','NSE:TCS','NSE:HDFCBANK','NSE:INFY','NSE:ICICIBANK',
  'NSE:HINDUNILVR','NSE:SBIN','NSE:BHARTIARTL','NSE:ITC','NSE:AXISBANK',
  'NSE:LT','NSE:BAJFINANCE','NSE:TATASTEEL','NSE:TATAMOTORS','NSE:WIPRO',
  'NSE:ADANIENT','NSE:HINDALCO','NSE:JSWSTEEL','NSE:TECHM','NSE:KOTAKBANK',
];

async function computeAndCache() {
  logger.info('topMoversService: computing top movers...');
  const [rows] = await db.pool.query(
    'SELECT symbol, close, candle_time FROM candles ' +
    'WHERE `interval` = ? AND symbol IN (?) ' +
    'AND candle_time >= DATE_SUB(NOW(), INTERVAL 5 DAY) ' +
    'ORDER BY symbol, candle_time DESC',
    ['day', UNIVERSE_FOR_MOVERS]
  );

  // Group by symbol, keep last 2 candles
  const groups = {};
  rows.forEach(r => {
    if (!groups[r.symbol]) groups[r.symbol] = [];
    if (groups[r.symbol].length < 2) groups[r.symbol].push(r);
  });

  const movers = Object.entries(groups)
    .filter(([, c]) => c.length >= 2)
    .map(([sym, candles]) => {
      const curr = parseFloat(candles[0].close);
      const prev = parseFloat(candles[1].close);
      return {
        symbol:    sym,
        name:      sym.split(':')[1],
        ltp:       curr,
        prevClose: prev,
        changeAbs: parseFloat((curr - prev).toFixed(2)),
        changePct: parseFloat(((curr - prev) / prev * 100).toFixed(2)),
      };
    });

  const gainers = movers.filter(m => m.changePct > 0).sort((a,b) => b.changePct - a.changePct).slice(0, 5);
  const losers  = movers.filter(m => m.changePct < 0).sort((a,b) => a.changePct - b.changePct).slice(0, 5);

  // Also compute index movers
  const [idxRows] = await db.pool.query(
    'SELECT symbol, close, candle_time FROM candles ' +
    'WHERE `interval` = ? AND symbol IN (?) ' +
    'AND candle_time >= DATE_SUB(NOW(), INTERVAL 5 DAY) ' +
    'ORDER BY symbol, candle_time DESC',
    ['day', INDEX_SYMBOLS]
  );
  const idxGroups = {};
  idxRows.forEach(r => {
    if (!idxGroups[r.symbol]) idxGroups[r.symbol] = [];
    if (idxGroups[r.symbol].length < 2) idxGroups[r.symbol].push(r);
  });
  const indices = Object.entries(idxGroups)
    .filter(([, c]) => c.length >= 1)
    .map(([sym, candles]) => {
      const curr = parseFloat(candles[0].close);
      const prev = candles[1] ? parseFloat(candles[1].close) : curr;
      return {
        symbol: sym, name: sym.split(':')[1], ltp: curr, prevClose: prev,
        changeAbs: parseFloat((curr - prev).toFixed(2)),
        changePct: parseFloat(((curr - prev) / prev * 100).toFixed(2)),
      };
    })
    .sort((a, b) => {
      // Sort by a fixed order matching our app's display order
      const order = INDEX_SYMBOLS;
      return order.indexOf(a.symbol) - order.indexOf(b.symbol);
    })
    .slice(0, 10);

  // Persist to cache table
  const rows2insert = [];
  gainers.forEach((m, i) => rows2insert.push(['gainer', i+1, m.symbol, m.name, m.ltp, m.prevClose, m.changePct, m.changeAbs]));
  losers.forEach( (m, i) => rows2insert.push(['loser',  i+1, m.symbol, m.name, m.ltp, m.prevClose, m.changePct, m.changeAbs]));
  indices.forEach((m, i) => rows2insert.push(['index',  i+1, m.symbol, m.name, m.ltp, m.prevClose, m.changePct, m.changeAbs]));

  if (rows2insert.length > 0) {
    await db.pool.query(
      'INSERT INTO top_movers_cache (type, `rank`, symbol, name, ltp, prev_close, change_pct, change_abs) VALUES ? ' +
      'ON DUPLICATE KEY UPDATE symbol=VALUES(symbol), name=VALUES(name), ltp=VALUES(ltp), ' +
      'prev_close=VALUES(prev_close), change_pct=VALUES(change_pct), change_abs=VALUES(change_abs), updated_at=NOW()',
      [rows2insert]
    );
  }

  logger.info('topMoversService: cached ' + gainers.length + ' gainers, ' +
    losers.length + ' losers, ' + indices.length + ' indices');
  return { gainers, losers, indices };
}

async function getFromCache() {
  const [rows] = await db.pool.query(
    'SELECT type, `rank`, symbol, name, ltp, prev_close as prevClose, ' +
    'change_pct as changePct, change_abs as changeAbs, updated_at as updatedAt ' +
    'FROM top_movers_cache ORDER BY type, `rank`'
  );
  const gainers = rows.filter(r => r.type === 'gainer');
  const losers  = rows.filter(r => r.type === 'loser');
  const indices = rows.filter(r => r.type === 'index');
  return { gainers, losers, indices };
}

module.exports = { computeAndCache, getFromCache, INDEX_SYMBOLS };
