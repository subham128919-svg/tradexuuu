// tickCache.js — Redis-backed "last known tick" store
// FIX: getTicks() now uses a single conceptual batch read instead of
// N sequential redis.get() calls. Also only writes back (self-heals)
// entries that actually needed healing, not every read unconditionally.
const redis = require('../config/redis');

const KEY_PREFIX = 'tick:';

function normalizeTick(tick) {
  if (!tick) return { tick, healed: false };
  if (typeof tick.updatedAt !== 'number' || Number.isNaN(tick.updatedAt)) {
    tick.updatedAt = Date.now();
    return { tick, healed: true };
  }
  return { tick, healed: false };
}

async function setTick(symbol, tick) {
  try {
    tick.updatedAt = typeof tick.updatedAt === 'number' ? tick.updatedAt : Date.now();
    await redis.set(KEY_PREFIX + symbol, JSON.stringify(tick));
  } catch { /* non-fatal */ }
}

async function getTick(symbol) {
  try {
    const raw = await redis.get(KEY_PREFIX + symbol);
    if (!raw) return null;
    const { tick, healed } = normalizeTick(JSON.parse(raw));
    // Only write back if the value actually needed healing
    if (healed) redis.set(KEY_PREFIX + symbol, JSON.stringify(tick)).catch(() => {});
    return tick;
  } catch {
    return null;
  }
}

// FIX: batch read — fires all redis.get() calls concurrently via
// Promise.all instead of awaiting each one sequentially. This turns
// N round-trips into 1 "wave" of concurrent requests (or a single
// pipeline round-trip if the Redis driver supports it).
async function getTicks(symbols) {
  if (!symbols.length) return {};
  const result = {};
  try {
    const promises = symbols.map(sym =>
      redis.get(KEY_PREFIX + sym)
        .then(raw => {
          if (!raw) return;
          const { tick, healed } = normalizeTick(JSON.parse(raw));
          result[sym] = tick;
          if (healed) redis.set(KEY_PREFIX + sym, JSON.stringify(tick)).catch(() => {});
        })
        .catch(() => {})
    );
    await Promise.all(promises);
  } catch { /* non-fatal — return whatever we got */ }
  return result;
}

module.exports = { setTick, getTick, getTicks };