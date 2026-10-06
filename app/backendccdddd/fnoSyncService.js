// fnoSyncService.js — the actual F&O instrument sync logic, shared by:
//  1. scheduler.js  — runs in-process, daily, using the already-
//     authenticated kite/db connections the main server already has.
//  2. scripts/syncFnoInstruments.js — a thin CLI wrapper for manual
//     one-off runs, which sets up its own connections since it runs
//     as a separate process outside the server.
//
// Kept as one shared function so the sync logic only exists once.
//
// FIX (root cause of "Options button never shows / chain empty"):
// this used `x.name || x.tradingsymbol` as the underlying. Verified
// directly against Zerodha's own Kite Connect docs and their public
// instrument dump samples — the `name` field is EMPTY for every NFO
// option/future contract (only equity rows have a real name). Since
// "" is falsy in JS, `"" || x.tradingsymbol` always fell through to
// the FULL raw tradingsymbol (e.g. "NIFTY24AUG24000CE") — so
// `underlying` was being stored as the whole contract symbol, never
// as "NIFTY". Every query that looks up "WHERE underlying = 'NIFTY'"
// (has-options, option-expiries, option-chain) then matched nothing,
// while search still worked because it matches on `tradingsymbol`
// directly — which is exactly what was reported: contracts findable
// by search, but never through the option chain screen.
// Fixed by deriving the underlying from the tradingsymbol itself: its
// leading run of non-digit characters (Zerodha's tradingsymbol format
// always starts with the underlying's letters before any digits) —
// "NIFTY24AUG24000CE" → "NIFTY", "M&M24AUG1500CE" → "M&M", etc.
const { kite }   = require('./kiteService');
const { logger } = require('../config/logger');

function deriveUnderlying(tradingsymbol) {
  const match = tradingsymbol.match(/^[A-Z&\-]+/);
  return match ? match[0] : tradingsymbol;
}

async function syncFnoInstruments(dbPool) {
  logger.info('fnoSync: downloading NFO instruments from Kite...');
  const nfo = await kite.getInstruments(['NFO']);

  const contracts = nfo.filter(i =>
    i.instrument_type === 'CE' || i.instrument_type === 'PE' || i.instrument_type === 'FUT'
  );
  logger.info('fnoSync: found ' + contracts.length + ' F&O contracts');

  const CHUNK = 500;
  for (let i = 0; i < contracts.length; i += CHUNK) {
    const chunk  = contracts.slice(i, i + CHUNK);
    const values = chunk.map(x => {
      const underlying = deriveUnderlying(x.tradingsymbol);
      return [
        x.instrument_token, x.tradingsymbol, underlying,
        x.exchange, x.segment, x.instrument_type,
        x.expiry ? new Date(x.expiry).toISOString().slice(0, 10) : null,
        x.strike || null, x.lot_size || null, underlying,
      ];
    });
    await dbPool.query(
      'INSERT INTO instruments ' +
      '(instrument_token, tradingsymbol, name, exchange, segment, instrument_type, expiry, strike, lot_size, underlying) ' +
      'VALUES ? ' +
      'ON DUPLICATE KEY UPDATE ' +
      'tradingsymbol=VALUES(tradingsymbol), name=VALUES(name), exchange=VALUES(exchange), ' +
      'segment=VALUES(segment), instrument_type=VALUES(instrument_type), expiry=VALUES(expiry), ' +
      'strike=VALUES(strike), lot_size=VALUES(lot_size), underlying=VALUES(underlying)',
      [values]
    );
  }

  const [delResult] = await dbPool.query(
    'DELETE FROM instruments WHERE exchange = \'NFO\' AND expiry IS NOT NULL AND expiry < CURDATE()'
  );
  logger.info('fnoSync: upserted ' + contracts.length + ' contracts, removed ' + delResult.affectedRows + ' expired ones');
  return { synced: contracts.length, removed: delResult.affectedRows };
}

module.exports = { syncFnoInstruments, deriveUnderlying };
