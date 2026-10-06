// routes/_patchRoutes.js
//
// Mounts every route added by the features 1–8 patch, so that index.js
// needs exactly ONE new line instead of a block of edits:
//
//     require('./routes/_patchRoutes')(app);
//
// Put that line in src/index.js anywhere AFTER the existing
// app.use(`${API}/admin`, ...) line and BEFORE app.get('/health', ...).
//
// Why these paths are safe:
//  • /api/v1/admin is mounted a SECOND time here. Express tries your
//    existing routes/admin.js first and falls through to adminExtra.js
//    only for paths admin.js does not handle, so nothing is shadowed.
//  • Orders live at /api/v1/app-orders-ex, a different path from your
//    existing /api/v1/app-orders router, so route ordering cannot bite.
//  • public/admin/ is already served by express.static in index.js, so
//    /admin/manage.html works with no extra wiring.

module.exports = function mountPatchRoutes(app) {
  const API = '/api/v1';

  app.use(`${API}/settings`,      require('./settings'));
  app.use(`${API}/referrals`,     require('./referrals'));
  app.use(`${API}/withdrawals`,   require('./withdrawals'));
  app.use(`${API}/app-orders-ex`, require('./appOrdersExtra'));
  app.use(`${API}/admin`,         require('./adminExtra'));

  console.log('[patch] settings, referrals, withdrawals, app-orders-ex, admin-extra mounted');
};
