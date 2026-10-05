// scheduler.js — background jobs
const cron             = require('node-cron');
const candleStore       = require('./services/candleStore');
const topMoversService   = require('./services/topMoversService');
const { syncFnoInstruments } = require('./services/fnoSyncService');
const db                    = require('./config/database');
const UNIVERSE            = require('./config/universe');
const { logger }          = require('./config/logger');

function startScheduler() {
  // 15:35 IST Mon-Fri: post-close candle sync for all tracked stocks
  cron.schedule('35 15 * * 1-5', async () => {
    logger.info('Scheduler: post-close candle sync');
    for (const symbol of UNIVERSE) {
      try {
        await candleStore.getCandles(symbol, '1D');
        await candleStore.getCandles(symbol, '1M');
      } catch (err) {
        logger.error('Candle sync failed for ' + symbol + ': ' + err.message);
      }
      await new Promise(r => setTimeout(r, 300));
    }
    // Also sync index candles
    for (const sym of topMoversService.INDEX_SYMBOLS) {
      try {
        await candleStore.getCandles(sym, '1M');
      } catch (err) {
        logger.error('Index candle sync failed for ' + sym + ': ' + err.message);
      }
      await new Promise(r => setTimeout(r, 300));
    }
    logger.info('Scheduler: candle sync done');

    // 15:40 IST: compute and cache top movers (slightly after candle sync)
    setTimeout(async () => {
      try {
        await topMoversService.computeAndCache();
        logger.info('Scheduler: top movers cached');
      } catch (err) {
        logger.error('Scheduler: top movers failed: ' + err.message);
      }
    }, 5 * 60 * 1000); // 5 min after candle sync
  }, { timezone: 'Asia/Kolkata' });

  // 08:00 IST every day (incl. weekends — harmless, just a no-op re-sync):
  // refresh F&O contracts (strikes/expiries roll weekly/monthly) before
  // market open at 09:15 IST, so the option chain is accurate all day.
  cron.schedule('0 8 * * *', async () => {
    logger.info('Scheduler: F&O instrument sync starting');
    try {
      const result = await syncFnoInstruments(db.pool);
      logger.info('Scheduler: F&O sync done — ' + result.synced + ' contracts, ' + result.removed + ' expired removed');
    } catch (err) {
      logger.error('Scheduler: F&O sync failed: ' + err.message);
    }
  }, { timezone: 'Asia/Kolkata' });

  logger.info('Scheduler ready — candle sync + movers at 15:35 IST, F&O sync at 08:00 IST, weekdays');
}

module.exports = { startScheduler };
