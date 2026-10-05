/**
 * ADD THESE LINES to your existing server.js / index.js / app.js
 * wherever you register your Express routes.
 */

// ─── Near the top, with other requires ───────────────────────────
const otpRoutes = require('./routes/otp');
const kycRoutes = require('./routes/kyc');

// ─── With your other app.use() route registrations ───────────────
// Serve uploaded KYC files (for admin to view docs)
app.use('/uploads/kyc', express.static(path.join(__dirname, 'uploads', 'kyc')));

// OTP routes (no auth needed for send/verify)
app.use('/api/v1/otp', otpRoutes);

// KYC routes (mixed: some need auth, admin endpoints separate)
app.use('/api/v1/kyc', kycRoutes);

// Admin KYC routes (add admin auth middleware in production)
app.use('/api/v1/admin/kyc', kycRoutes);

// ─── Required npm packages ───────────────────────────────────────
// Run: npm install multer axios
// You should already have: express, mysql2, jsonwebtoken, bcryptjs
