// razorpayService.js — Razorpay order creation + payment verification.
//
// SECURITY: never trust a "payment succeeded" claim from the client
// alone — the client (or anyone with a proxy) could call the wallet
// credit endpoint directly with a fake success. Every credit MUST go
// through verifySignature(), which recomputes the HMAC using YOUR
// key_secret (server-side only, never sent to the app) and compares
// it to what Razorpay signed. Only a match proves the payment is real
// and wasn't tampered with in transit.
const crypto   = require('crypto');
const Razorpay = require('razorpay');
const { logger } = require('../config/logger');

let razorpay = null;
function getClient() {
  if (razorpay) return razorpay;
  const key_id     = process.env.RAZORPAY_KEY_ID;
  const key_secret = process.env.RAZORPAY_KEY_SECRET;
  if (!key_id || !key_secret) {
    throw new Error('RAZORPAY_KEY_ID / RAZORPAY_KEY_SECRET not set in .env');
  }
  razorpay = new Razorpay({ key_id, key_secret });
  return razorpay;
}

// Create a Razorpay order for the given amount (in rupees). Returns
// the order object — the client uses order.id to open Razorpay
// Checkout. Nothing is credited to the wallet at this point.
async function createOrder(amountRupees, receiptId) {
  const client = getClient();
  const order = await client.orders.create({
    amount:   Math.round(amountRupees * 100), // paise
    currency: 'INR',
    receipt:  receiptId,
  });
  return order;
}

// Verify the signature Razorpay sends back after checkout completes.
// order_id|payment_id, HMAC-SHA256'd with your key_secret, must match
// what the client reports — this is Razorpay's documented client-side
// verification scheme (see Razorpay docs: "Verify Payment Signature").
function verifySignature(orderId, paymentId, signature) {
  const key_secret = process.env.RAZORPAY_KEY_SECRET;
  if (!key_secret) throw new Error('RAZORPAY_KEY_SECRET not set');
  const expected = crypto
    .createHmac('sha256', key_secret)
    .update(orderId + '|' + paymentId)
    .digest('hex');
  return expected === signature;
}

// Verify a webhook payload's signature (X-Razorpay-Signature header).
// Recommended as a backstop in addition to client-side verify(), in
// case the app crashes/loses network right after payment but before
// calling /verify — the webhook is Razorpay's own server telling you
// directly, independent of the client.
function verifyWebhookSignature(rawBody, signature) {
  const webhookSecret = process.env.RAZORPAY_WEBHOOK_SECRET;
  if (!webhookSecret) {
    logger.warn('razorpay: RAZORPAY_WEBHOOK_SECRET not set — webhook signature check skipped');
    return false;
  }
  const expected = crypto
    .createHmac('sha256', webhookSecret)
    .update(rawBody)
    .digest('hex');
  return expected === signature;
}

module.exports = { createOrder, verifySignature, verifyWebhookSignature };
