// routes/auth.js
// Two things happen on successful Zerodha login:
//   1. Redirect to /api/v1/auth/login-success?appToken=xxx
//      → Android's LoginActivity WebView intercepts THIS URL and saves
//        the token automatically. This is what makes the app actually log in.
//   2. The /login-success route itself renders a friendly page with the
//      token visible + copyable, for manual curl testing in a browser.
const express    = require('express');
const jwt        = require('jsonwebtoken');
const redis      = require('../config/redis');
const { logger } = require('../config/logger');
const router     = express.Router();

// Step 1: Android or browser calls this → gets Zerodha login URL
router.get('/kite-login', (req, res) => {
  const apiKey = (process.env.KITE_API_KEY || '').trim();
  if (!apiKey) return res.status(503).json({ error: 'KITE_API_KEY not set in .env' });
  const loginUrl = `https://kite.zerodha.com/connect/login?v=3&api_key=${apiKey}`;
  res.json({ loginUrl });
});

// Step 2: Zerodha redirects back here after login
router.get('/kite-callback', async (req, res) => {
  const { request_token } = req.query;

  if (!request_token) {
    return res.status(400).send(page('Missing request_token',
      '<h2>&#10007; Missing request_token</h2><p>Go back and try login again.</p>', true));
  }

  try {
    const { KiteConnect } = require('kiteconnect');
    const apiKey    = (process.env.KITE_API_KEY    || '').trim();
    const apiSecret = (process.env.KITE_API_SECRET || '').trim();

    logger.info('Kite callback received', { request_token: request_token.substring(0, 8) + '...' });

    const kite    = new KiteConnect({ api_key: apiKey });
    const session = await kite.generateSession(request_token.trim(), apiSecret);

    logger.info('Kite session generated', { user_id: session.user_id });

    // Store access token so backend can make API calls
    await redis.set('kite:access_token', session.access_token, 'EX', 86400).catch(() => {});
    process.env.KITE_ACCESS_TOKEN = session.access_token;

    try {
      require('../services/kiteService').setAccessToken(session.access_token);
    } catch (e) {
      logger.warn('Could not set kiteService token:', e.message);
    }

    // Issue JWT for the Android app
    const appToken = jwt.sign(
      { userId: session.user_id, email: session.email || '', name: session.user_name || '' },
      process.env.JWT_SECRET,
      { expiresIn: '12h' }
    );

    logger.info('JWT issued for user', { userId: session.user_id });

    // CRITICAL: redirect (not render) — this is the URL Android's WebView
    // intercepts to extract the token and save it automatically.
    const redirectUrl =
      `/api/v1/auth/login-success?appToken=${encodeURIComponent(appToken)}` +
      `&name=${encodeURIComponent(session.user_name || '')}` +
      `&userId=${encodeURIComponent(session.user_id || '')}`;

    res.redirect(redirectUrl);

  } catch (err) {
    logger.error('Kite callback error', { message: err.message });

    const html = `
<h2>&#10007; Authentication failed</h2>
<p><strong>Error:</strong> ${err.message}</p>
<hr>
<p>Most common reasons:</p>
<ul>
  <li><strong>request_token already used</strong> — each token works only ONCE.</li>
  <li><strong>Token expired</strong> — tokens expire in about 10 minutes.</li>
  <li><strong>KITE_API_SECRET wrong</strong> — check .env has the right secret, no extra spaces.</li>
</ul>
<p>Run on server: <code>pm2 logs tradingapp --lines 30</code></p>
<br>
<a href="/api/v1/auth/kite-login">&#8592; Try login again</a>`;

    res.status(500).send(page('Authentication failed', html, true));
  }
});

// Step 3: Friendly success page — shown when this URL is opened directly
// in a browser. When opened inside the Android app's WebView, the app
// intercepts the redirect before this page ever loads.
router.get('/login-success', (req, res) => {
  const { appToken, name, userId } = req.query;

  if (!appToken) {
    return res.status(400).send(page('Missing token', '<h2>No token in URL</h2>', true));
  }

  const html = `
<h2>&#10003; Login successful — ${name || ''} (${userId || ''})</h2>
<p>Your app token (copy this for API testing):</p>
<textarea rows="4" style="width:100%;font-size:11px">${appToken}</textarea>
<br><br>
<p><strong>Test historical data in terminal:</strong></p>
<pre>curl "https://tradingstock.online/api/v1/market/candles?symbol=NSE:RELIANCE&period=1M" \\
  -H "Authorization: Bearer ${appToken}"</pre>
<p><em>If you're seeing this in a normal browser, this token is for manual testing only.
Open the Zerodha login from INSIDE the Android app for the app to save the token automatically.</em></p>`;

  res.send(page('Login successful', html, false));
});

function page(title, body, isError) {
  const bg = isError ? '#1a0000' : '#001a00';
  const fg = isError ? '#ff6666' : '#66ff66';
  return `<!DOCTYPE html><html><head><title>${title}</title>
<style>body{font-family:monospace;padding:24px;background:${bg};color:${fg};max-width:800px}
textarea{background:#111;color:#ccc;border:1px solid #444;padding:8px;border-radius:4px}
pre{background:#111;padding:12px;border-radius:4px;overflow-x:auto;color:#ccc;white-space:pre-wrap}
a{color:#88aaff}h2{margin-top:0}li{margin:6px 0}</style>
</head><body>${body}</body></html>`;
}

module.exports = router;
