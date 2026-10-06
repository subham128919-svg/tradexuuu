/**
 * ADDITIONS to your existing users.js routes
 * Add these endpoints to your existing users router
 */

// ─── LOGIN VIA PHONE + OTP (new endpoint) ────────────────────────
// The Android app will now call this AFTER OTP is verified
// POST /api/v1/users/login-phone
router.post('/login-phone', async (req, res) => {
    try {
        const { phone } = req.body;
        if (!phone) return res.status(400).json({ error: 'Phone number required' });
        
        const cleanPhone = phone.replace(/[^0-9]/g, '');
        
        // Check OTP was verified for this phone
        const [otpCheck] = await db.query(
            `SELECT id FROM otp_sessions 
             WHERE phone = ? AND purpose = 'login' AND is_verified = 1 
             AND created_at > DATE_SUB(NOW(), INTERVAL 10 MINUTE)
             ORDER BY created_at DESC LIMIT 1`,
            [cleanPhone]
        );
        if (otpCheck.length === 0) {
            return res.status(401).json({ error: 'OTP not verified. Please verify first.' });
        }
        
        // Find user
        const [users] = await db.query(
            'SELECT id, name, email, phone, is_active, is_blocked, kyc_status, bo_demat_number FROM app_users WHERE phone = ?',
            [cleanPhone]
        );
        if (users.length === 0) {
            return res.status(404).json({ error: 'No account found' });
        }
        
        const user = users[0];
        if (user.is_blocked) {
            return res.status(403).json({ error: 'Account blocked' });
        }
        
        // Generate JWT
        const token = jwt.sign(
            { userId: user.id, email: user.email },
            process.env.JWT_SECRET || 'your_jwt_secret_key',
            { expiresIn: '30d' }
        );
        
        await db.query('UPDATE app_users SET last_login_at = NOW() WHERE id = ?', [user.id]);
        
        return res.json({
            token,
            user: {
                id: user.id,
                name: user.name,
                email: user.email,
                phone: user.phone,
                kycStatus: user.kyc_status,
                boDematNumber: user.bo_demat_number
            }
        });
    } catch (err) {
        console.error('Phone login error:', err);
        return res.status(500).json({ error: 'Login failed' });
    }
});

// ─── REGISTER WITH OTP VERIFIED (modify existing register) ───────
// After phone OTP verified + KYC docs uploaded, the register flow is:
// 1. Signup screen → collects name, email, phone, password
// 2. OTP screen → verifies phone via HanuOTP
// 3. KYC screen → uploads PAN/Aadhaar docs
// 4. Preview screen → confirms
// 5. POST /users/register (same endpoint, but now sets phone_verified = 1)
// 6. POST /kyc/upload (separate call for documents)

// Your existing register endpoint already works. 
// Just add this to the INSERT query to set phone_verified:
// ... phone_verified = 1, kyc_status = 'none' ...
// The KYC upload is a separate call after registration.

// ─── REGISTER (modified version) ─────────────────────────────────
// Replace your existing register handler with this:
router.post('/register', async (req, res) => {
    try {
        const { name, email, phone, password } = req.body;
        if (!name || !email || !password) {
            return res.status(400).json({ error: 'Name, email and password are required' });
        }
        
        const cleanPhone = phone ? phone.replace(/[^0-9]/g, '') : '';
        
        // Check if phone OTP was verified (for the signup flow)
        if (cleanPhone) {
            const [otpCheck] = await db.query(
                `SELECT id FROM otp_sessions 
                 WHERE phone = ? AND purpose = 'signup' AND is_verified = 1 
                 AND created_at > DATE_SUB(NOW(), INTERVAL 30 MINUTE)
                 ORDER BY created_at DESC LIMIT 1`,
                [cleanPhone]
            );
            // If no verified OTP found, still allow registration but mark unverified
            var phoneVerified = otpCheck.length > 0 ? 1 : 0;
        }
        
        // Check duplicates
        const [existing] = await db.query(
            'SELECT id FROM app_users WHERE email = ? OR phone = ?',
            [email, cleanPhone]
        );
        if (existing.length > 0) {
            return res.status(409).json({ error: 'Email or phone already registered' });
        }
        
        // Hash password
        const bcrypt = require('bcryptjs');
        const hash = await bcrypt.hash(password, 10);
        
        // Insert user — is_active = 0 until KYC approved
        const [result] = await db.query(
            `INSERT INTO app_users (name, email, phone, password_hash, phone_verified, is_active, kyc_status) 
             VALUES (?, ?, ?, ?, ?, 0, 'none')`,
            [name, email, cleanPhone, hash, phoneVerified || 0]
        );
        
        const userId = result.insertId;
        
        // Create empty wallet
        await db.query('INSERT INTO app_wallet (user_id, balance) VALUES (?, 0.00)', [userId]);
        
        // Generate JWT
        const token = jwt.sign(
            { userId, email },
            process.env.JWT_SECRET || 'your_jwt_secret_key',
            { expiresIn: '30d' }
        );
        
        return res.json({
            token,
            user: { id: userId, name, email, phone: cleanPhone, kycStatus: 'none' }
        });
    } catch (err) {
        console.error('Register error:', err);
        return res.status(500).json({ error: 'Registration failed' });
    }
});
