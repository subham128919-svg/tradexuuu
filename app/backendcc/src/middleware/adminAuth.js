// Admin-only JWT guard. The token comes from POST /api/v1/admin/login
// which signs { role: 'admin', username }.
const jwt = require('jsonwebtoken');

module.exports = function adminAuth(req, res, next) {
  const header = req.headers.authorization || '';
  const token  = header.startsWith('Bearer ') ? header.slice(7) : null;
  if (!token) return res.status(401).json({ error: 'Not authenticated' });
  try {
    const payload = jwt.verify(token, process.env.JWT_SECRET);
    if (payload.role !== 'admin') return res.status(403).json({ error: 'Not admin' });
    req.admin = payload;
    next();
  } catch {
    return res.status(401).json({ error: 'Invalid or expired admin token' });
  }
};
