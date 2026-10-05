// TradingApp Backend — Entry point
require('dotenv').config();
const express     = require('express');
const http        = require('http');
const cors        = require('cors');
const helmet      = require('helmet');
const rateLimit   = require('express-rate-limit');
const path        = require('path');
const { logger }  = require('./config/logger');
const db          = require('./config/database');
const redis       = require('./config/redis');
const PriceStream = require('./websocket/priceStream');
const { startScheduler } = require('./scheduler');
const backgroundTracker = require('./services/backgroundTracker');

const app    = express();
const server = http.createServer(app);

app.set('trust proxy', 1);
app.use(helmet({ contentSecurityPolicy: false }));
app.use(cors({ origin: process.env.CORS_ORIGIN || '*' }));
// FIX: Razorpay webhook signature verification needs the exact raw
// request bytes (HMAC is computed over the raw body, not the
// re-serialized JSON object, which can differ in whitespace/key order
// and would make the signature check always fail). Capturing it here
// via the `verify` callback means every route still gets the normal
// parsed req.body as before — only the webhook route additionally
// reads req.rawBody for its signature check.
app.use(express.json({
  verify: (req, res, buf) => { req.rawBody = buf; }
}));

// FIX: the xForwardedForHeader validation in express-rate-limit v7 was
// throwing a ValidationError on EVERY request coming through Apache
// (all of which carry X-Forwarded-For). In Express v4, unhandled async
// middleware errors cause the request to hang forever (next() never
// called, no response sent). The client waits until readTimeout (15s),
// then the connection dies. Rapid stock switching fires ~3 requests
// per stock × 4 stocks = 12 requests in quick succession — if even a
// few hang, OkHttp's connection pool fills with zombies and the entire
// app goes dead (orders, positions, EVERYTHING, not just market data).
// Fix: disable the broken validation (we manage trust proxy ourselves)
// and use a proper keyGenerator that always works behind a reverse proxy.
app.use(rateLimit({
  windowMs: 60_000,
  max: 600,
  standardHeaders: true,
  legacyHeaders: false,
  validate: { xForwardedForHeader: false },
  keyGenerator: (req) => req.ip || req.socket.remoteAddress || 'unknown',
}));

app.use('/admin', express.static(path.join(__dirname, '../public/admin')));

const API  = '/api/v1';
const auth = require('./middleware/auth');

app.use(`${API}/auth`,      require('./routes/auth'));
app.use(`${API}/market`,    require('./routes/market'));
app.use(`${API}/mf`,        require('./routes/mf'));
app.use(`${API}/tracking`,  require('./routes/tracking'));
app.use(`${API}/wallet`,    require('./routes/wallet'));
app.use(`${API}/users`,     require('./routes/users'));
app.use(`${API}/app-orders`,require('./routes/appOrders'));
app.use(`${API}/portfolio`, auth, require('./routes/portfolio'));
app.use(`${API}/orders`,    auth, require('./routes/orders'));
app.use(`${API}/watchlist`, auth, require('./routes/watchlist'));
app.use(`${API}/admin`,     require('./routes/admin'));
app.use('/uploads/kyc', express.static(path.join(__dirname, '..', 'uploads', 'kyc')));
app.use(`${API}/otp`, require('./routes/otp'));
app.use(`${API}/kyc`, require('./routes/kyc'));

app.get('/health', (_, res) => res.json({ status: 'ok', ts: Date.now() }));
app.get('/admin/*', (_, res) => res.sendFile(path.join(__dirname, '../public/admin/index.html')));

new PriceStream(server);

// Global async error handler — catches any unhandled errors from async
// middleware (like the rate-limit ValidationError that was hanging every
// request). Without this, Express v4 silently drops the request and the
// client waits until its socket timeout fires. With this, the client at
// least gets a 500 response immediately and can retry or show an error.
app.use((err, req, res, next) => {
  logger.error('Express unhandled error: ' + (err.message || err));
  if (!res.headersSent) {
    res.status(500).json({ error: 'Internal server error' });
  }
});

const PORT = process.env.PORT || 3000;

async function start() {
  try {
    try { await redis.connect(); logger.info('Redis connected'); }
    catch (e) { logger.warn('Redis fallback:', e.message); }

    try {
      const token = await redis.get('kite:access_token');
      if (token) { require('./services/kiteService').setAccessToken(token); logger.info('Kite session restored'); }
      else { logger.warn('No Kite token — login at /api/v1/auth/kite-login'); }
    } catch (e) { logger.warn('Kite restore failed:', e.message); }

    await db.connect();
    logger.info('MySQL connected');

    // Start background tracker (independent of WebSocket subscribers)
    await backgroundTracker.init();

    startScheduler();
    server.listen(PORT, () => logger.info('Server on :' + PORT));
  } catch (err) {
    logger.error('Startup failed', err);
    process.exit(1);
  }
}
start();
