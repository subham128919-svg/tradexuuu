/**
 * KYC Routes — document upload + admin verification
 * 
 * POST   /api/v1/kyc/upload         → upload KYC docs (authenticated)
 * GET    /api/v1/kyc/status          → get KYC status (authenticated)
 * GET    /api/v1/kyc/account-info    → get account info with demat (authenticated)
 * GET    /api/v1/admin/kyc/pending   → list pending KYC reviews (admin)
 * GET    /api/v1/admin/kyc/:userId   → get user KYC details (admin)
 * POST   /api/v1/admin/kyc/review    → approve/reject KYC (admin)
 */
const express = require('express');
const router  = express.Router();
const multer  = require('multer');
const path    = require('path');
const fs      = require('fs');
const db      = require('../config/database').pool;
const auth    = require('../middleware/auth');

// ─── File upload config ──────────────────────────────────────────
const uploadDir = path.join(__dirname, '..', 'uploads', 'kyc');
if (!fs.existsSync(uploadDir)) {
    fs.mkdirSync(uploadDir, { recursive: true });
}

const storage = multer.diskStorage({
    destination: (req, file, cb) => cb(null, uploadDir),
    filename: (req, file, cb) => {
        const ext = path.extname(file.originalname);
        const name = `${req.userId}_${file.fieldname}_${Date.now()}${ext}`;
        cb(null, name);
    }
});

const upload = multer({
    storage,
    limits: { fileSize: 5 * 1024 * 1024 }, // 5MB max
    fileFilter: (req, file, cb) => {
        const allowed = ['.jpg', '.jpeg', '.png', '.pdf'];
        const ext = path.extname(file.originalname).toLowerCase();
        if (allowed.includes(ext)) cb(null, true);
        else cb(new Error('Only JPG, PNG, PDF files are allowed'));
    }
});

// ─── Generate BO Demat Number ────────────────────────────────────
function generateDematNumber() {
    // Format: 1208 XXXX XXXX XXXX (16 digits like real BO ID)
    const prefix = '1208';
    let rest = '';
    for (let i = 0; i < 12; i++) {
        rest += Math.floor(Math.random() * 10).toString();
    }
    return prefix + rest;
}

// ─── UPLOAD KYC DOCUMENTS ────────────────────────────────────────
router.post('/upload', auth, upload.fields([
    { name: 'pan_front', maxCount: 1 },
    { name: 'pan_back', maxCount: 1 },
    { name: 'aadhaar_front', maxCount: 1 },
    { name: 'aadhaar_back', maxCount: 1 }
]), async (req, res) => {
    try {
        const userId = req.userId;
        const { pan_number, aadhaar_number } = req.body;
        
        if (!pan_number || !aadhaar_number) {
            return res.status(400).json({ error: 'PAN and Aadhaar numbers are required' });
        }
        
        // Validate PAN format: ABCDE1234F
        if (!/^[A-Z]{5}[0-9]{4}[A-Z]$/.test(pan_number.toUpperCase())) {
            return res.status(400).json({ error: 'Invalid PAN number format' });
        }
        
        // Validate Aadhaar: 12 digits
        const cleanAadhaar = aadhaar_number.replace(/\s/g, '');
        if (!/^\d{12}$/.test(cleanAadhaar)) {
            return res.status(400).json({ error: 'Invalid Aadhaar number (must be 12 digits)' });
        }
        
        // Check all 4 files uploaded
        const files = req.files;
        if (!files.pan_front || !files.pan_back || !files.aadhaar_front || !files.aadhaar_back) {
            return res.status(400).json({ error: 'All 4 document images are required' });
        }
        
        // Build URLs (relative paths for serving)
        const baseUrl = '/uploads/kyc/';
        const panFrontUrl     = baseUrl + files.pan_front[0].filename;
        const panBackUrl      = baseUrl + files.pan_back[0].filename;
        const aadhaarFrontUrl = baseUrl + files.aadhaar_front[0].filename;
        const aadhaarBackUrl  = baseUrl + files.aadhaar_back[0].filename;
        
        // Update user record
        await db.query(
            `UPDATE app_users SET 
                pan_number = ?, aadhaar_number = ?,
                pan_front_url = ?, pan_back_url = ?,
                aadhaar_front_url = ?, aadhaar_back_url = ?,
                kyc_status = 'pending', phone_verified = 1
             WHERE id = ?`,
            [pan_number.toUpperCase(), cleanAadhaar, panFrontUrl, panBackUrl, aadhaarFrontUrl, aadhaarBackUrl, userId]
        );
        
        return res.json({
            ok: true,
            message: 'KYC documents submitted. Your account is under review.',
            kycStatus: 'pending'
        });
        
    } catch (err) {
        console.error('KYC upload error:', err.message);
        return res.status(500).json({ error: 'Failed to upload documents' });
    }
});

// ─── GET KYC STATUS ──────────────────────────────────────────────
router.get('/status', auth, async (req, res) => {
    try {
        const [users] = await db.query(
            'SELECT kyc_status, pan_number, aadhaar_number, bo_demat_number FROM app_users WHERE id = ?',
            [req.userId]
        );
        if (users.length === 0) return res.status(404).json({ error: 'User not found' });
        
        const u = users[0];
        return res.json({
            kycStatus: u.kyc_status,
            panNumber: u.pan_number,
            aadhaarMasked: u.aadhaar_number ? 'XXXX XXXX ' + u.aadhaar_number.slice(-4) : null,
            boDematNumber: u.bo_demat_number
        });
    } catch (err) {
        return res.status(500).json({ error: 'Failed to fetch KYC status' });
    }
});

// ─── GET ACCOUNT INFO (with demat) ──────────────────────────────
router.get('/account-info', auth, async (req, res) => {
    try {
        const [users] = await db.query(
            `SELECT id, name, email, phone, created_at, kyc_status, 
                    pan_number, aadhaar_number, bo_demat_number 
             FROM app_users WHERE id = ?`,
            [req.userId]
        );
        if (users.length === 0) return res.status(404).json({ error: 'User not found' });
        
        const u = users[0];
        return res.json({
            name: u.name,
            email: u.email,
            phone: u.phone,
            joinedAt: u.created_at,
            kycStatus: u.kyc_status,
            panNumber: u.pan_number ? u.pan_number.slice(0,2) + '****' + u.pan_number.slice(-2) : null,
            aadhaarMasked: u.aadhaar_number ? 'XXXX XXXX ' + u.aadhaar_number.slice(-4) : null,
            boDematNumber: u.bo_demat_number,
            accountActive: u.kyc_status === 'verified'
        });
    } catch (err) {
        return res.status(500).json({ error: 'Failed to fetch account info' });
    }
});

// ─── ADMIN: List pending KYC ─────────────────────────────────────
router.get('/admin/pending', async (req, res) => {
    // In production, add admin auth middleware
    try {
        const [users] = await db.query(
            `SELECT id, name, email, phone, pan_number, aadhaar_number,
                    pan_front_url, pan_back_url, aadhaar_front_url, aadhaar_back_url,
                    created_at
             FROM app_users WHERE kyc_status = 'pending' ORDER BY created_at ASC`
        );
        return res.json({ data: users });
    } catch (err) {
        return res.status(500).json({ error: 'Failed to fetch pending KYC' });
    }
});

// ─── ADMIN: Get user KYC detail ──────────────────────────────────
router.get('/admin/:userId', async (req, res) => {
    try {
        const [users] = await db.query(
            `SELECT id, name, email, phone, pan_number, aadhaar_number,
                    pan_front_url, pan_back_url, aadhaar_front_url, aadhaar_back_url,
                    kyc_status, bo_demat_number, created_at
             FROM app_users WHERE id = ?`,
            [req.params.userId]
        );
        if (users.length === 0) return res.status(404).json({ error: 'User not found' });
        return res.json({ data: users[0] });
    } catch (err) {
        return res.status(500).json({ error: 'Failed to fetch user' });
    }
});

// ─── ADMIN: Approve / Reject KYC ────────────────────────────────
router.post('/admin/review', async (req, res) => {
    try {
        const { userId, action, remarks } = req.body;
        
        if (!userId || !['approved', 'rejected'].includes(action)) {
            return res.status(400).json({ error: 'userId and action (approved/rejected) required' });
        }
        
        let boDematNumber = null;
        
        if (action === 'approved') {
            // Generate unique BO demat number
            let isUnique = false;
            while (!isUnique) {
                boDematNumber = generateDematNumber();
                const [existing] = await db.query(
                    'SELECT id FROM app_users WHERE bo_demat_number = ?', [boDematNumber]
                );
                if (existing.length === 0) isUnique = true;
            }
            
            // Activate account
            await db.query(
                `UPDATE app_users SET 
                    kyc_status = 'verified', is_active = 1, bo_demat_number = ?
                 WHERE id = ?`,
                [boDematNumber, userId]
            );
            
            // Create wallet if not exists
            await db.query(
                `INSERT IGNORE INTO app_wallet (user_id, balance) VALUES (?, 0.00)`,
                [userId]
            );
        } else {
            // Reject
            await db.query(
                `UPDATE app_users SET kyc_status = 'rejected' WHERE id = ?`,
                [userId]
            );
        }
        
        // Log the review
        await db.query(
            `INSERT INTO kyc_reviews (user_id, action, remarks) VALUES (?, ?, ?)`,
            [userId, action, remarks || null]
        );
        
        return res.json({
            ok: true,
            message: action === 'approved' 
                ? `Account verified. Demat: ${boDematNumber}` 
                : 'Account KYC rejected.',
            boDematNumber
        });
        
    } catch (err) {
        console.error('KYC review error:', err.message);
        return res.status(500).json({ error: 'Review failed' });
    }
});

module.exports = router;
