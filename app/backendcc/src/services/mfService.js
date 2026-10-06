// mfService.js  —  Mutual Fund data via AMFI (free & official)
// API: https://api.mfapi.in  (no key required)
const axios  = require('axios');
const redis  = require('../config/redis');
const { logger } = require('../config/logger');

const MF_BASE = process.env.AMFI_BASE_URL || 'https://api.mfapi.in';

// Search mutual funds by name
async function searchFunds(query) {
  const cacheKey = `mf:search:${query}`;
  const cached   = await redis.get(cacheKey);
  if (cached) return JSON.parse(cached);

  const res = await axios.get(`${MF_BASE}/mf/search?q=${encodeURIComponent(query)}`);
  await redis.set(cacheKey, JSON.stringify(res.data), 'EX', 3600);
  return res.data;
}

// Get current NAV for a fund scheme
async function getNav(schemeCode) {
  const cacheKey = `mf:nav:${schemeCode}`;
  const cached   = await redis.get(cacheKey);
  if (cached) return JSON.parse(cached);

  const res  = await axios.get(`${MF_BASE}/mf/${schemeCode}/latest`);
  await redis.set(cacheKey, JSON.stringify(res.data), 'EX', 3600); // 1hr
  return res.data;
}

// Get historical NAV (for chart)
async function getNavHistory(schemeCode) {
  const cacheKey = `mf:history:${schemeCode}`;
  const cached   = await redis.get(cacheKey);
  if (cached) return JSON.parse(cached);

  const res  = await axios.get(`${MF_BASE}/mf/${schemeCode}`);
  // Convert to TradingView candle-compatible format (MF has daily NAV only)
  const data = res.data.data.reverse().map(d => ({
    time:  d.date.split('-').reverse().join('-'), // DD-MM-YYYY -> YYYY-MM-DD
    value: parseFloat(d.nav),
  }));
  await redis.set(cacheKey, JSON.stringify(data), 'EX', 3600);
  return data;
}

// Popular fund lists (curated, cached with longer TTL)
async function getTrendingFunds() {
  const cacheKey = 'mf:trending';
  const cached   = await redis.get(cacheKey);
  if (cached) return JSON.parse(cached);

  // Well-known scheme codes for popular funds
  const schemeCodes = [
    { code: '120503', name: 'Parag Parikh Flexi Cap', category: 'Flexi Cap' },
    { code: '120842', name: 'Quant Small Cap',         category: 'Small Cap'  },
    { code: '119598', name: 'HDFC Balanced Advantage', category: 'Balanced'   },
    { code: '118989', name: 'Mirae Asset Large Cap',   category: 'Large Cap'  },
    { code: '118834', name: 'Axis Liquid',             category: 'Liquid'     },
    { code: '119062', name: 'ICICI Pru Infrastructure',category: 'Sectoral'   },
    { code: '119096', name: 'SBI Contra',              category: 'Contra'     },
    { code: '120843', name: 'Quant Mid Cap',           category: 'Mid Cap'    },
  ];

  const funds = await Promise.allSettled(
    schemeCodes.map(async s => {
      const nav = await getNav(s.code);
      return { ...s, nav: nav.data[0]?.nav, date: nav.data[0]?.date };
    })
  );

  const result = funds
    .filter(r => r.status === 'fulfilled')
    .map(r => r.value);

  await redis.set(cacheKey, JSON.stringify(result), 'EX', 3600);
  return result;
}

module.exports = { searchFunds, getNav, getNavHistory, getTrendingFunds };
