// fundamentalsService.js — Fetch stock fundamentals from Yahoo Finance
// NSE symbol format for Yahoo Finance: RELIANCE → RELIANCE.NS
const axios    = require('axios');
const redis    = require('../config/redis');
const { logger } = require('../config/logger');

function toYahooSym(symbol) {
  const sym = symbol.includes(':') ? symbol.split(':')[1] : symbol;
  return sym + '.NS';
}

async function getFundamentals(symbol) {
  const cacheKey = 'fundamentals:' + symbol;
  try {
    const cached = await redis.get(cacheKey);
    if (cached) return JSON.parse(cached);
  } catch {}

  const ySym = toYahooSym(symbol);
  const url  = `https://query1.finance.yahoo.com/v10/finance/quoteSummary/${ySym}` +
                `?modules=defaultKeyStatistics,financialData,summaryDetail,assetProfile,price`;
  try {
    const res  = await axios.get(url, {
      headers: { 'User-Agent': 'Mozilla/5.0', Accept: 'application/json' },
      timeout: 8000
    });
    const res2 = res.data?.quoteSummary?.result?.[0];
    if (!res2) throw new Error('No Yahoo data');
    const ks = res2.defaultKeyStatistics || {};
    const fd = res2.financialData         || {};
    const sd = res2.summaryDetail         || {};
    const pr = res2.price                 || {};

    const fmt = (v, fallback='—') => v?.fmt ?? (v?.raw != null ? v.raw : fallback);
    const pct  = (v, fallback='—') => v?.fmt ?? (v?.raw != null ? (v.raw*100).toFixed(2)+'%' : fallback);

    const data = {
      mktCap:      pr.marketCap?.fmt          || fmt(sd.marketCap),
      peRatio:     fmt(sd.trailingPE),
      pbRatio:     fmt(ks.priceToBook),
      eps:         fmt(ks.trailingEps),
      roe:         pct(fd.returnOnEquity),
      divYield:    pct(sd.dividendYield),
      industryPE:  '—',
      bookValue:   fmt(ks.bookValue),
      debtEquity:  fmt(ks.debtToEquity),
      faceValue:   '—',
      sector:      res2.assetProfile?.sector  || '—',
      industry:    res2.assetProfile?.industry || '—',
    };

    await redis.set(cacheKey, JSON.stringify(data), 'EX', 3600).catch(()=>{});
    return data;
  } catch (err) {
    logger.warn('fundamentalsService: ' + err.message);
    return null;
  }
}

module.exports = { getFundamentals };
