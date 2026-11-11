'use strict';

const express = require('express');
const { initializeApp, getApps } = require('firebase-admin/app');
const { getAppCheck } = require('firebase-admin/app-check');

const app = express();
app.disable('x-powered-by');
app.use(express.json({ limit: '16kb', strict: true }));

const PORT = Number(process.env.PORT || 8080);
const ROUTE_PROVIDER = String(process.env.ROUTE_PROVIDER || 'osrm').toLowerCase();
const OSRM_BASE_URL = String(process.env.OSRM_BASE_URL || 'https://router.project-osrm.org').replace(/\/$/, '');
const GOOGLE_ROUTES_API_KEY = process.env.GOOGLE_ROUTES_API_KEY || '';
const FIREBASE_PROJECT_ID = process.env.FIREBASE_PROJECT_ID || '';
const ROUTES_ENDPOINT = 'https://routes.googleapis.com/directions/v2:computeRoutes';

if (!['osrm', 'google', 'auto'].includes(ROUTE_PROVIDER)) {
  throw new Error('ROUTE_PROVIDER must be osrm, google or auto');
}
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

function parseOsrmRoutes(payload, origin, destination) {
  if (!payload || payload.code !== 'Ok' || !Array.isArray(payload.routes)) return [];
  return payload.routes
    .filter(route => Number.isFinite(route.distance) && route.distance > 0 &&
      Number.isFinite(route.duration) && route.geometry)
    .sort((a, b) => a.distance - b.distance)
    .map(route => ({
      provider: 'OSRM',
      distanceMeters: route.distance,
      distanceKm: Number((route.distance / 1000).toFixed(3)),
      durationSeconds: route.duration,
      encodedPolyline: route.geometry,
      origin,
      destination
    }));
}

async function verifyAppCheck(req) {
  const token = req.get('X-Firebase-AppCheck');
  if (!token || !FIREBASE_PROJECT_ID || getApps().length === 0) {
    const error = new Error('App attestation is required');
    error.status = 401;
    throw error;
  }
  await getAppCheck().verifyToken(token);
}

async function requestOsrmRoute(origin, destination) {
  const coordinates = `${origin.longitude},${origin.latitude};${destination.longitude},${destination.latitude}`;
  const url = `${OSRM_BASE_URL}/route/v1/driving/${coordinates}?overview=full&geometries=polyline&alternatives=true&steps=false`;
  const response = await fetch(url, { headers: { Accept: 'application/json' }, signal: AbortSignal.timeout(15000) });
  const payload = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(`OSRM provider returned HTTP ${response.status}`);
  const routes = parseOsrmRoutes(payload, origin, destination);
  if (!routes.length) throw new Error('OSRM did not return a valid road route');
  return { ...routes[0], alternativesCount: routes.length };
}

async function requestGoogleRoute(origin, destination, travelMode) {
  if (!GOOGLE_ROUTES_API_KEY) throw new Error('Google Routes is not configured');
  const response = await fetch(ROUTES_ENDPOINT, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-Goog-Api-Key': GOOGLE_ROUTES_API_KEY,
      'X-Goog-FieldMask': 'routes.distanceMeters,routes.duration,routes.polyline.encodedPolyline'
    },
    body: JSON.stringify({
      origin: { location: { latLng: { latitude: origin.latitude, longitude: origin.longitude } } },
      destination: { location: { latLng: { latitude: destination.latitude, longitude: destination.longitude } } },
      travelMode,
      routingPreference: 'TRAFFIC_UNAWARE',
      computeAlternativeRoutes: true,
      languageCode: 'ar',
      units: 'METRIC'
    }),
    signal: AbortSignal.timeout(15000)
  });
  const payload = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(`Google Routes returned HTTP ${response.status}`);
  const routes = Array.isArray(payload.routes) ? payload.routes : [];
  const route = routes.filter(item => Number.isFinite(item.distanceMeters) && item.polyline?.encodedPolyline)
    .sort((a, b) => distanceMeters(a) - distanceMeters(b))[0];
  if (!route) throw new Error('Google Routes did not return a valid road route');
  return {
    provider: 'Google Routes API',
    distanceMeters: route.distanceMeters,
    distanceKm: Number((route.distanceMeters / 1000).toFixed(3)),
    duration: route.duration || null,
    encodedPolyline: route.polyline.encodedPolyline,
    alternativesCount: routes.length,
    origin,
    destination
  };
}

app.get('/health', (_req, res) => res.status(200).json({
  status: 'ok',
  service: 'station-routes',
  defaultProvider: ROUTE_PROVIDER,
  googleConfigured: Boolean(GOOGLE_ROUTES_API_KEY)
}));

app.post('/v1/routes', async (req, res) => {
  try {
    await verifyAppCheck(req);
    const { origin, destination, travelMode = 'DRIVE' } = req.body || {};
    if (!validCoordinate(origin) || !validCoordinate(destination)) {
      return res.status(400).json({ error: 'Valid non-zero origin and destination coordinates are required' });
    }
    if (!['DRIVE', 'TWO_WHEELER', 'WALK'].includes(travelMode)) {
      return res.status(400).json({ error: 'Unsupported travel mode' });
    }

    let route;
    if (ROUTE_PROVIDER === 'google') {
      route = await requestGoogleRoute(origin, destination, travelMode);
    } else if (ROUTE_PROVIDER === 'osrm') {
      if (travelMode !== 'DRIVE') return res.status(422).json({ error: 'The configured OSRM profile supports driving routes only' });
      route = await requestOsrmRoute(origin, destination);
    } else {
      try {
        if (travelMode !== 'DRIVE') throw new Error('OSRM driving profile does not support this travel mode');
        route = await requestOsrmRoute(origin, destination);
      } catch (osrmError) {
        if (!GOOGLE_ROUTES_API_KEY) throw osrmError;
        route = await requestGoogleRoute(origin, destination, travelMode);
        route.fallbackReason = 'OSRM provider unavailable';
      }
    }
    return res.status(200).json(route);
  } catch (error) {
    const status = error.status || (error.name === 'AbortError' || error.name === 'TimeoutError' ? 504 : 502);
    console.warn('Route request failed', status, error.message);
    return res.status(status).json({
      error: status === 401 ? 'تعذر التحقق من هوية التطبيق' : 'تعذر الحصول على مسار طريق موثوق',
      provider: ROUTE_PROVIDER
    });
  }
});

if (require.main === module) {
  app.listen(PORT, '0.0.0.0', () => console.log(`station-routes listening on ${PORT}; provider=${ROUTE_PROVIDER}`));
}

module.exports = { app, validCoordinate, distanceMeters, parseOsrmRoutes };
