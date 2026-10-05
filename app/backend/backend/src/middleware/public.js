// public.js — no authentication required
// Used for market data endpoints that all app users can access
// without logging into Zerodha. The SERVER holds the Kite access token
// (owner logs in once daily via browser), not the app users.
function publicRoute(req, res, next) {
  next();
}
module.exports = publicRoute;
