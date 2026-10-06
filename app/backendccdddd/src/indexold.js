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
app.use(express.json());
app.use(rateLimit({ windowMs: 60_000, max: 500 }));

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

app.get('/health', (_, res) => res.json({ status: 'ok', ts: Date.now() }));
app.get('/admin/*', (_, res) => res.sendFile(path.join(__dirname, '../public/admin/index.html')));

new PriceStream(server);

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
