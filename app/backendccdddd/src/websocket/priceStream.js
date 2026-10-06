// priceStream.js -- Public WebSocket, now market-status-aware.
// When the market is closed, the broadcast loop does NOT poll Kite at
// all -- there's nothing new to send, and every connected client
// already has the last known tick from its own initial REST fetch (or
// will get it from marketDataService's cache on next reconnect/fetch).
// This saves Kite API quota entirely outside trading hours.
const WebSocket            = require('ws');
const marketDataService     = require('../services/marketDataService');
const { getMarketStatus }   = require('../services/marketStatusService');
const { logger }            = require('../config/logger');

class PriceStream {
  constructor(httpServer) {
    this.wss           = new WebSocket.Server({ server: httpServer, path: '/ws/prices' });
    this.subscriptions = new Map();
    this.symbolSubs    = new Map();
    this.tickInterval  = null;
    this.wss.on('connection', (ws) => this._onConnect(ws));
    logger.info('WebSocket price stream ready at /ws/prices (public, no auth, market-aware)');
  }

  _onConnect(ws) {
    this.subscriptions.set(ws, new Set());
    ws.on('message', (raw) => {
      try {
        const msg = JSON.parse(raw);
        if (msg.action === 'subscribe')   this._subscribe(ws, msg.symbols || []);
        if (msg.action === 'unsubscribe') this._unsubscribe(ws, msg.symbols || []);
        if (msg.action === 'ping')        ws.send(JSON.stringify({ type: 'pong', ts: Date.now() }));
      } catch {}
    });
    ws.on('close', () => this._cleanup(ws));
    ws.on('error', (e) => logger.error('WS error', e.message));
    ws.send(JSON.stringify({ type: 'connected', marketStatus: getMarketStatus(), ts: Date.now() }));
  }

  _subscribe(ws, symbols) {
    symbols.forEach(sym => {
      this.subscriptions.get(ws)?.add(sym);
      if (!this.symbolSubs.has(sym)) this.symbolSubs.set(sym, new Set());
      this.symbolSubs.get(sym).add(ws);
    });
    this._startTick();
  }

  _unsubscribe(ws, symbols) {
    symbols.forEach(sym => {
      this.subscriptions.get(ws)?.delete(sym);
      this.symbolSubs.get(sym)?.delete(ws);
    });
  }

  _cleanup(ws) {
    const syms = this.subscriptions.get(ws) || new Set();
    syms.forEach(sym => this.symbolSubs.get(sym)?.delete(ws));
    this.subscriptions.delete(ws);
    if ([...this.subscriptions.values()].every(s => s.size === 0)) this._stopTick();
  }

  _startTick() {
    if (this.tickInterval) return;
    this.tickInterval = setInterval(() => this._broadcastTick(), 2000);
  }

  _stopTick() {
    clearInterval(this.tickInterval);
    this.tickInterval = null;
  }

  async _broadcastTick() {
    const allSymbols = [...this.symbolSubs.entries()]
      .filter(([, sockets]) => sockets.size > 0)
      .map(([sym]) => sym);
    if (!allSymbols.length) return;

    const status = getMarketStatus();
    if (!status.isOpen) {
      // Market closed -- nothing changes, skip Kite entirely. Clients
      // already have the last tick from their initial fetch.
      return;
    }

    try {
      const { ticks } = await marketDataService.getQuotesSmart(allSymbols);
      Object.entries(ticks).forEach(([sym, tick]) => {
        const payload = JSON.stringify({ type: 'tick', ...tick });
        this.symbolSubs.get(sym)?.forEach(ws => {
          if (ws.readyState === WebSocket.OPEN) ws.send(payload);
        });
      });
    } catch (err) {
      logger.error('Tick broadcast error', err.message);
    }
  }
}

module.exports = PriceStream;
