/**
 * OTP Routes — uses HanuOTP (api.hanuotp.in)
 * 
 * POST /api/v1/otp/send    → send OTP to phone
 * POST /api/v1/otp/verify  → verify OTP
 */
const express = require('express');
const router  = express.Router();
const axios   = require('axios');
const db      = require('../config/database').pool;

// ─── CONFIG ──────────────────────────────────────────────────────
// PUT YOUR HANUOTP API KEY HERE or use env variable
const HANUOTP_API_KEY   = process.env.HANUOTP_API_KEY || 'd6cb3dcb2fda083e154b1cec198b486d';
const HANUOTP_TEMPLATE  = process.env.HANUOTP_TEMPLATE || 'default';
const OTP_EXPIRY_MINUTES = 5;

// ─── Generate random 6-digit OTP ─────────────────────────────────
function generateOTP() {
    return Math.floor(100000 + Math.random() * 900000).toString();
}

// ─── SEND OTP ────────────────────────────────────────────────────
router.post('/send', async (req, res) => {
    try {
        const { phone, purpose } = req.body; // purpose: 'login' or 'signup'
        
        if (!phone || phone.length < 10) {
            return res.status(400).json({ error: 'Valid phone number is required' });
        }
        
        const cleanPhone = phone.replace(/[^0-9]/g, '');
        const validPurpose = ['login', 'signup'].includes(purpose) ? purpose : 'login';
        
        // For login: check if user exists with this phone
        if (validPurpose === 'login') {
            const [users] = await db.query(
                'SELECT id, is_active, is_blocked FROM app_users WHERE phone = ?',
                [cleanPhone]
            );
            if (users.length === 0) {
                return res.status(404).json({ error: 'No account found with this phone number' });
            }
            if (users[0].is_blocked) {
                return res.status(403).json({ error: 'Account is blocked. Contact support.' });
            }
        }
        
        // Rate limit: max 5 OTPs per phone per 10 minutes
        const [recent] = await db.query(
            `SELECT COUNT(*) as cnt FROM otp_sessions 
             WHERE phone = ? AND created_at > DATE_SUB(NOW(), INTERVAL 10 MINUTE)`,
            [cleanPhone]
        );
        if (recent[0].cnt >= 5) {
            return res.status(429).json({ error: 'Too many OTP requests. Try again in 10 minutes.' });
        }
        
        // Generate OTP
        const otp = generateOTP();
        const expiresAt = new Date(Date.now() + OTP_EXPIRY_MINUTES * 60 * 1000);
        
        // Save to DB
        await db.query(
            `INSERT INTO otp_sessions (phone, otp, purpose, expires_at) VALUES (?, ?, ?, ?)`,
            [cleanPhone, otp, validPurpose, expiresAt]
        );
        
        // Send via HanuOTP API
        const hanuUrl = `https://api.hanuotp.in/sms-otp.php?number=${cleanPhone}&OTP=${otp}&apikey=${HANUOTP_API_KEY}&templatesid=${HANUOTP_TEMPLATE}`;
        
        const hanuResp = await axios.get(hanuUrl, { timeout: 10000 });
        
        if (hanuResp.data && (hanuResp.data.status === 'success' || hanuResp.data.success === true || hanuResp.status === 200)) {
            // Mask phone for response
            const masked = cleanPhone.slice(0, 2) + '******' + cleanPhone.slice(-2);
            return res.json({
                ok: true,
                message: 'OTP sent successfully',
                phone_masked: masked,
                expires_in: OTP_EXPIRY_MINUTES * 60 // seconds
            });
        } else {
            console.error('HanuOTP error:', hanuResp.data);
            return res.status(500).json({ error: 'Failed to send OTP. Please try again.' });
        }
        
    } catch (err) {
        console.error('OTP send error:', err.message);
        return res.status(500).json({ error: 'Could not send OTP. Please try again.' });
    }
});

// ─── VERIFY OTP ──────────────────────────────────────────────────
router.post('/verify', async (req, res) => {
    try {
        const { phone, otp, purpose } = req.body;
        
        if (!phone || !otp) {
            return res.status(400).json({ error: 'Phone and OTP are required' });
        }
        
        const cleanPhone = phone.replace(/[^0-9]/g, '');
        const validPurpose = ['login', 'signup'].includes(purpose) ? purpose : 'login';
        
        // Find the latest unexpired, unverified OTP for this phone+purpose
        const [sessions] = await db.query(
            `SELECT id, otp, attempts FROM otp_sessions 
             WHERE phone = ? AND purpose = ? AND is_verified = 0 AND expires_at > NOW()
             ORDER BY created_at DESC LIMIT 1`,
            [cleanPhone, validPurpose]
        );
        
        if (sessions.length === 0) {
            return res.status(400).json({ error: 'OTP expired or not found. Please request a new one.' });
        }
        
        const session = sessions[0];
        
        // Max 5 attempts
        if (session.attempts >= 5) {
            return res.status(429).json({ error: 'Too many wrong attempts. Request a new OTP.' });
        }
        
        // Increment attempts
        await db.query('UPDATE otp_sessions SET attempts = attempts + 1 WHERE id = ?', [session.id]);
        
        // Check OTP match
        if (session.otp !== otp.trim()) {
            return res.status(400).json({ error: 'Invalid OTP. Please try again.' });
        }
        
        // Mark verified
        await db.query('UPDATE otp_sessions SET is_verified = 1 WHERE id = ?', [session.id]);
        
        // For LOGIN purpose: generate JWT and return user data
        if (validPurpose === 'login') {
            const [users] = await db.query(
                'SELECT id, name, email, phone, is_active, kyc_status, bo_demat_number FROM app_users WHERE phone = ?',
                [cleanPhone]
            );
            if (users.length === 0) {
                return res.status(404).json({ error: 'User not found' });
            }
            
            const user = users[0];
            const jwt  = require('jsonwebtoken');
            const token = jwt.sign(
                { userId: user.id, email: user.email },
                process.env.JWT_SECRET || 'your_jwt_secret_key',
                { expiresIn: '30d' }
            );
            
            // Update last login
            await db.query('UPDATE app_users SET last_login_at = NOW() WHERE id = ?', [user.id]);
            
            return res.json({
                ok: true,
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
        }
        
        // For SIGNUP purpose: just confirm OTP is valid
        return res.json({
            ok: true,
            message: 'Phone verified successfully',
            verified: true
        });
        
    } catch (err) {
        console.error('OTP verify error:', err.message);
        return res.status(500).json({ error: 'Verification failed. Please try again.' });
    }
});

module.exports = router;
