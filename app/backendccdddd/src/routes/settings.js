// routes/settings.js — read-only, app-facing configuration.
// Everything here is editable by the admin (see routes/adminExtra.js).
//
//   GET /api/v1/settings/support   → Feature #5 customer-support screen
//   GET /api/v1/settings/links     → Feature #8 About / Charges WebView URLs
//   GET /api/v1/settings/public    → all of the above in one call
const express  = require('express');
const router   = express.Router();
const settings = require('../utils/settings');

router.get('/support', async (req, res) => {
  try {
    const s = await settings.getAll();
    const wa = (s.support_whatsapp || '').replace(/[^0-9]/g, '');
    res.json({
      title:        s.support_title,
      subtitle:     s.support_subtitle,
      whatsapp:     wa,
      whatsappText: s.support_whatsapp_text,
      whatsappUrl:  wa ? `https://wa.me/${wa}?text=${encodeURIComponent(s.support_whatsapp_text || '')}` : '',
      phone:        s.support_phone,
      email:        s.support_email,
      hours:        s.support_hours,
      address:      s.support_address,
      note:         s.support_note
    });
  } catch (e) { res.status(500).json({ error: 'Failed to load support details' }); }
});

router.get('/links', async (req, res) => {
  try {
    const s = await settings.getAll();
    res.json({ about: s.url_about, charges: s.url_charges });
  } catch (e) { res.status(500).json({ error: 'Failed to load links' }); }
});

router.get('/public', async (req, res) => {
  try {
    const s  = await settings.getAll();
    const wa = (s.support_whatsapp || '').replace(/[^0-9]/g, '');
    res.json({
      support: {
        title: s.support_title, subtitle: s.support_subtitle,
        whatsapp: wa, whatsappText: s.support_whatsapp_text,
        whatsappUrl: wa ? `https://wa.me/${wa}?text=${encodeURIComponent(s.support_whatsapp_text || '')}` : '',
        phone: s.support_phone, email: s.support_email,
        hours: s.support_hours, address: s.support_address, note: s.support_note
      },
      links: { about: s.url_about, charges: s.url_charges },
      withdraw: {
        enabled: s.withdraw_enabled === '1',
        min: parseFloat(s.withdraw_min) || 0,
        max: parseFloat(s.withdraw_max) || 0,
        note: s.withdraw_note
      }
    });
  } catch (e) { res.status(500).json({ error: 'Failed to load settings' }); }
});

module.exports = router;
