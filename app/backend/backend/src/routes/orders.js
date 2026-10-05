const express = require('express');
const auth    = require('../middleware/auth');
const kite    = require('../services/kiteService');
const router  = express.Router();

router.get('/', auth, async (req, res) => {
  try {
    const { status } = req.query;
    let orders = await kite.getOrders();
    if (status === 'open')      orders = orders.filter(o => o.status === 'OPEN');
    if (status === 'executed')  orders = orders.filter(o => o.status === 'COMPLETE');
    if (status === 'cancelled') orders = orders.filter(o => o.status === 'CANCELLED');
    res.json({ data: orders });
  } catch (err) { res.status(500).json({ error: err.message }); }
});

router.post('/', auth, async (req, res) => {
  try {
    const orderId = await kite.placeOrder(req.body);
    res.json({ orderId });
  } catch (err) { res.status(500).json({ error: err.message }); }
});

router.delete('/:orderId', auth, async (req, res) => {
  try {
    await kite.kite.cancelOrder('regular', req.params.orderId);
    res.json({ success: true });
  } catch (err) { res.status(500).json({ error: err.message }); }
});
module.exports = router;
