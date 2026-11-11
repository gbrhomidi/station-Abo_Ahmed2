'use strict';
const express = require('express');
const { initializeApp, getApps } = require('firebase-admin/app');
const { getAppCheck } = require('firebase-admin/app-check');
const app = express();
app.disable('x-powered-by');
app.use(express.json({ limit: '16kb', strict: true }));
const PORT = Number(process.env.PORT || 8080);
const ROUTES_API_KEY = process.env.GOOGLE_ROUTES_API_KEY;
const FIREBASE_PROJECT_ID = process.env.FIREBASE_PROJECT_ID;
const ROUTES_ENDPOINT = 'https://routes.googleapis.com/directions/v2:computeRoutes';
if (!getApps().length && FIREBASE_PROJECT_ID) initializeApp({ projectId: FIREBASE_PROJECT_ID });
function validCoordinate(point) {
  return !!point && Number.isFinite(point.latitude) && Number.isFinite(point.longitude) &&
    point.latitude >= -90 && point.latitude <= 90 &&
    point.longitude >= -180 && point.longitude <= 180 &&
    !(point.latitude === 0 && point.longitude === 0);
}
function distanceMeters(route) {
  return Number.isFinite(route && route.distanceMeters) ? route.distanceMeters : Number.MAX_SAFE_INTEGER;
}
async function verifyAppCheck(req) {
  const token = req.get('X-Firebase-AppCheck');
  if (!token || !FIREBASE_PROJECT_ID || getApps().length === 0) {
    const error = new Error('App attestation is required'); error.status = 401; throw error;
  }
  await getAppCheck().verifyToken(token);
}
app.get('/health', (_req, res) => res.status(200).json({ status: 'ok', service: 'station-routes' }));
app.post('/v1/routes', async (req, res) => {
  try {
    await verifyAppCheck(req);
    if (!ROUTES_API_KEY) return res.status(503).json({ error: 'Routes service is not configured' });
    const { origin, destination, travelMode = 'DRIVE' } = req.body || {};
    if (!validCoordinate(origin) || !validCoordinate(destination))
      return res.status(400).json({ error: 'Valid non-zero origin and destination coordinates are required' });
    if (!['DRIVE', 'TWO_WHEELER', 'WALK'].includes(travelMode))
      return res.status(400).json({ error: 'Unsupported travel mode' });
    const upstream = await fetch(ROUTES_ENDPOINT, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-Goog-Api-Key': ROUTES_API_KEY,
        'X-Goog-FieldMask': 'routes.distanceMeters,routes.duration,routes.polyline.encodedPolyline,routes.legs.distanceMeters,routes.legs.duration'
      },
      body: JSON.stringify({
        origin: { location: { latLng: { latitude: origin.latitude, longitude: origin.longitude } } },
        destination: { location: { latLng: { latitude: destination.latitude, longitude: destination.longitude } } },
        travelMode, routingPreference: 'TRAFFIC_UNAWARE', computeAlternativeRoutes: true,
        languageCode: 'ar', units: 'METRIC'
      }),
      signal: AbortSignal.timeout(15000)
    });
    const payload = await upstream.json().catch(() => ({}));
    if (!upstream.ok) {
      console.error('Routes API rejected request', upstream.status, payload.error && payload.error.status);
      return res.status(502).json({ error: 'تعذر حساب مسار الطريق من مزود الخرائط', providerStatus: upstream.status });
    }
    const routes = Array.isArray(payload.routes) ? payload.routes : [];
    if (!routes.length) return res.status(404).json({ error: 'لم يعثر مزود الخرائط على مسار مناسب بين الموقعين' });
    const route = routes.slice().sort((a, b) => distanceMeters(a) - distanceMeters(b))[0];
    if (!Number.isFinite(route.distanceMeters) || !route.polyline || !route.polyline.encodedPolyline)
      return res.status(502).json({ error: 'استجابة المسار من مزود الخرائط غير مكتملة' });
    return res.status(200).json({
      provider: 'Google Routes API', distanceMeters: route.distanceMeters,
      distanceKm: Number((route.distanceMeters / 1000).toFixed(3)),
      duration: route.duration || null, encodedPolyline: route.polyline.encodedPolyline,
      alternativesCount: routes.length, origin, destination
    });
  } catch (error) {
    const status = error.status || (error.name === 'AbortError' || error.name === 'TimeoutError' ? 504 : 401);
    console.warn('Route request rejected', status, error.message);
    return res.status(status).json({ error: status === 401 ? 'تعذر التحقق من هوية التطبيق' : 'تعذر إكمال طلب المسار' });
  }
});
if (require.main === module) app.listen(PORT, '0.0.0.0', () => console.log('station-routes listening on ' + PORT));
module.exports = { app, validCoordinate, distanceMeters };
