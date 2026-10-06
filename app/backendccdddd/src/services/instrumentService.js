// instrumentService.js — MySQL instrument search (FIXED)
// Root cause of 500: mysql2 execute() with complex CASE...WHEN ORDER BY
// confuses prepared-statement parameter binding when exchange is absent.
// Fix: use pool.query() (not execute) and clear, counted parameters.
const db         = require('../config/database');
const { logger } = require('../config/logger');

async function searchInstruments(query, exchange) {
  if (!query || query.trim().length < 1) return [];
  const q     = query.trim();
  const like  = '%' + q + '%';
  const exact = q.toUpperCase();
  const prefix= exact + '%';

  try {
    // pool.query() (not execute) avoids prepared-statement issues
    // with dynamic ORDER BY CASE param counts.
    const [rows] = await db.pool.query(
      'SELECT instrument_token, tradingsymbol, name, exchange, segment ' +
      'FROM instruments ' +
      'WHERE (tradingsymbol LIKE ? OR name LIKE ?) ' +
      (exchange ? 'AND exchange = ? ' : '') +
      'ORDER BY ' +
      '  CASE WHEN UPPER(tradingsymbol) = ? THEN 0 ' +
      '       WHEN tradingsymbol LIKE ?      THEN 1 ' +
      '       ELSE                                2 END, ' +
      'LENGTH(tradingsymbol) ' +
      'LIMIT 25',
      exchange ? [like, like, exchange, exact, prefix]
               : [like, like,           exact, prefix]
    );
    return rows.map(r => ({
      symbol:          r.tradingsymbol,
      name:            r.name || r.tradingsymbol,
      exchange:        r.exchange,
      segment:         r.segment,
      instrumentToken: r.instrument_token,
    }));
  } catch (err) {
    logger.error('Search error:', err.message);
    throw err;
  }
}

module.exports = { searchInstruments };
