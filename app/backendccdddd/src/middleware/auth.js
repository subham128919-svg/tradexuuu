// JWT authentication middleware
//
// FIX (root cause of "Account details not loading"):
// the old version only set `req.user = payload`. But routes/kyc.js reads
// `req.userId` — which was therefore `undefined`, so
//   SELECT ... WHERE id = undefined   -> 0 rows -> 404 / 500
//   and KYC uploads were saved as "undefined_pan_front_...jpg".
// We now set BOTH shapes so every existing route works unchanged.
const jwt = require('jsonwebtoken');

function authMiddleware(req, res, next) {
  const header = req.headers.authorization;
  if (!header || !header.startsWith('Bearer ')) {
    return res.status(401).json({ error: 'Missing auth token' });
  }
  try {
    const token   = header.split(' ')[1];
    const payload = jwt.verify(token, process.env.JWT_SECRET);

    req.user   = payload;                       // { userId, email, name }
    req.userId = payload.userId || payload.id;  // <-- the fix

    if (!req.userId) {
      return res.status(401).json({ error: 'Malformed token payload' });
    }
    next();
  } catch {
    return res.status(401).json({ error: 'Invalid or expired token' });
  }
}

module.exports = authMiddleware;
