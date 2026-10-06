// routes/mf.js  —  Mutual Fund routes (AMFI data)
// FIX: replaced PostgreSQL $1/$2 placeholder syntax with MySQL ? syntax.
// The original code used $1, $2 which crashes at runtime on MySQL.
const express    = require('express');
const auth       = require('../middleware/auth');
const mfService  = require('../services/mfService');
const db         = require('../config/database');
const router     = express.Router();

router.get('/explore',      auth, async (req, res) => { try { res.json({ data: await mfService.getTrendingFunds() }); } catch(e) { res.status(500).json({ error: e.message }); } });
router.get('/search',       auth, async (req, res) => { try { res.json({ data: await mfService.searchFunds(req.query.q) }); } catch(e) { res.status(500).json({ error: e.message }); } });
router.get('/:code/nav',    auth, async (req, res) => { try { res.json({ data: await mfService.getNav(req.params.code) }); } catch(e) { res.status(500).json({ error: e.message }); } });
router.get('/:code/chart',  auth, async (req, res) => { try { res.json({ data: await mfService.getNavHistory(req.params.code) }); } catch(e) { res.status(500).json({ error: e.message }); } });

// GET /api/v1/mf/holdings  (stored in DB per user)
// FIX: was using PostgreSQL $1 syntax — changed to MySQL ? syntax
router.get('/holdings', auth, async (req, res) => {
  try {
    const [rows] = await db.pool.query(
      'SELECT * FROM mf_holdings WHERE user_id = ?', [req.user.userId]);
    res.json({ data: rows });
  } catch (err) { res.status(500).json({ error: err.message }); }
});

// GET /api/v1/mf/sips
// FIX: was using PostgreSQL $1, $2 syntax — changed to MySQL ? syntax
router.get('/sips', auth, async (req, res) => {
  try {
    const [rows] = await db.pool.query(
      'SELECT * FROM sips WHERE user_id = ? AND status = ?',
      [req.user.userId, 'ACTIVE']);
    res.json({ data: rows });
  } catch (err) { res.status(500).json({ error: err.message }); }
});

module.exports = router;