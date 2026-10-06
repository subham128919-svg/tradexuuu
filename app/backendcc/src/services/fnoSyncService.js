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
    const chunk = contracts.slice(i, i + CHUNK);
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
  logger.info('fnoSync: upserted ' + contracts.length + ' contracts, removed ' + delResult.affectedRows + ' expired');
  return { synced: contracts.length, removed: delResult.affectedRows };
}

module.exports = { syncFnoInstruments, deriveUnderlying };
