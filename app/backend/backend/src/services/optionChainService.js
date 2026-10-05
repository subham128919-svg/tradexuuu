const db               = require('../config/database');
const marketDataService = require('../services/marketDataService');
const { logger }        = require('../config/logger');

const INDEX_UNDERLYINGS = ['NIFTY', 'BANKNIFTY', 'FINNIFTY', 'MIDCPNIFTY', 'SENSEX', 'BANKEX'];
const SPOT_SYMBOL_MAP = {
  'NIFTY':      'NIFTY 50',
  'BANKNIFTY':  'NIFTY BANK',
  'FINNIFTY':   'NIFTY FIN SERVICE',
  'MIDCPNIFTY': 'NIFTY MIDCAP 100',
};

function isIndexUnderlying(u) {
  return INDEX_UNDERLYINGS.includes((u || '').toUpperCase());
}

async function getEffectiveLotSize(underlying, kiteLotSize) {
  try {
    const [[row]] = await db.pool.query(
      'SELECT lot_size FROM lot_size_overrides WHERE underlying = ?',
      [underlying.toUpperCase()]
    );
    if (row) return row.lot_size;
  } catch {}
  return kiteLotSize;
}

async function getLotSizeForSymbol(tradingsymbol) {
  const [[row]] = await db.pool.query(
    'SELECT underlying, lot_size FROM instruments WHERE tradingsymbol = ? AND exchange = \'NFO\' LIMIT 1',
    [tradingsymbol]
  );
  if (!row) return null;
  return getEffectiveLotSize(row.underlying, row.lot_size);
}

async function getExpiries(underlying) {
  const [rows] = await db.pool.query(
    'SELECT DISTINCT DATE_FORMAT(expiry, \'%Y-%m-%d\') AS expiry FROM instruments ' +
    'WHERE underlying = ? AND exchange = \'NFO\' AND expiry >= CURDATE() ' +
    'ORDER BY expiry ASC LIMIT 12',
    [underlying.toUpperCase()]
  );
  return rows.map(r => r.expiry);
}

async function getChain(underlying, expiry) {
  const [contracts] = await db.pool.query(
    'SELECT tradingsymbol, instrument_type, strike, lot_size ' +
    'FROM instruments ' +
    'WHERE underlying = ? AND exchange = \'NFO\' AND expiry = ? ' +
    '  AND instrument_type IN (\'CE\',\'PE\') ' +
    'ORDER BY strike ASC',
    [underlying.toUpperCase(), expiry]
  );

  if (!contracts.length) {
    return { underlying, expiry, lotSize: null, spot: null, rows: [] };
  }

  const lotSize = await getEffectiveLotSize(underlying, contracts[0].lot_size);
  const symbols = contracts.map(c => 'NFO:' + c.tradingsymbol);

  const { ticks } = await marketDataService.getQuotesSmart(symbols).catch(err => {
    logger.warn('optionChain: quote fetch failed: ' + err.message);
    return { ticks: {} };
  });

  const upperUnderlying = underlying.toUpperCase();
  const spotExchange    = ['SENSEX', 'BANKEX'].includes(upperUnderlying) ? 'BSE' : 'NSE';
  const spotName        = SPOT_SYMBOL_MAP[upperUnderlying] || upperUnderlying;
  const spotSymbol      = spotExchange + ':' + spotName;
  let spot = null;
  try {
    const { ticks: spotTicks } = await marketDataService.getQuotesSmart([spotSymbol]);
    spot = spotTicks[spotSymbol]?.ltp || null;
  } catch {}

  const byStrike = new Map();
  contracts.forEach(c => {
    const key = String(c.strike);
    if (!byStrike.has(key)) byStrike.set(key, { strike: parseFloat(c.strike), call: null, put: null });
    const tick = ticks['NFO:' + c.tradingsymbol];
    const contract = {
      symbol:         c.tradingsymbol,
      ltp:            tick?.ltp || 0,
      change:         tick?.change || 0,
      changePct:      tick?.changePct || 0,
      priceAvailable: !!tick && tick.ltp > 0,
    };
    if (c.instrument_type === 'CE') byStrike.get(key).call = contract;
    else                             byStrike.get(key).put  = contract;
  });

  const rows = [...byStrike.values()].sort((a, b) => a.strike - b.strike);
  return { underlying, expiry, lotSize, spot, rows };
}

module.exports = { getExpiries, getChain, isIndexUnderlying, getEffectiveLotSize, getLotSizeForSymbol };