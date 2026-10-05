// redis.js  —  Optional Redis cache. If Redis is not installed,
// we use a simple in-memory Map so the app still works.
const { logger } = require('./logger');

let redis;

try {
  const Redis = require('ioredis');
  redis = new Redis(process.env.REDIS_URL || 'redis://localhost:6379', {
    lazyConnect:        true,
    enableOfflineQueue: false,
    connectTimeout:     3000,
    retryStrategy:      () => null,   // don't retry — just fall back to memory
  });
  redis.on('error',   () => switchToMemory());
  redis.on('connect', () => logger.info('Redis connected (cache enabled)'));
} catch {
  switchToMemory();
}

// ── In-memory fallback ────────────────────────────────────────────
function switchToMemory() {
  if (redis?._type === 'memory') return;
  logger.warn('Redis unavailable — using in-memory cache (fine for testing)');
  const store = new Map();
  redis = {
    _type: 'memory',
    async get(key)               { const e = store.get(key); if (!e) return null; if (e.ttl && Date.now() > e.ttl) { store.delete(key); return null; } return e.val; },
    async set(key, val, ...args) { let ttl = null; if (args[0]==='EX' && args[1]) ttl = Date.now() + args[1]*1000; store.set(key, { val, ttl }); return 'OK'; },
    async del(key)               { store.delete(key); return 1; },
    async ping()                 { return 'PONG'; },
    async connect()              { return this; },
  };
}

module.exports = redis;
