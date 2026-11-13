import { createRemoteJWKSet, jwtVerify } from 'jose';

const APP_CHECK_JWKS = createRemoteJWKSet(
  new URL('https://firebaseappcheck.googleapis.com/v1/jwks')
);
const routeCache = new Map();
const MAX_BODY_BYTES = 16 * 1024;
const MAX_OSRM_RESPONSE_BYTES = 1024 * 1024;
const CACHE_TTL_MS = 5 * 60 * 1000;
const MAX_CACHE_ENTRIES = 256;

const json = (body, status = 200) => new Response(JSON.stringify(body), {
  status,
  headers: { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' }
});

export function validCoordinate(point) {
  return Boolean(point &&
    Number.isFinite(point.latitude) && Number.isFinite(point.longitude) &&
    point.latitude >= -90 && point.latitude <= 90 &&
    point.longitude >= -180 && point.longitude <= 180 &&
    !(point.latitude === 0 && point.longitude === 0));
}

export function validateRouteRequest(body) {
  const { origin, destination, travelMode = 'DRIVE' } = body || {};
  if (!validCoordinate(origin) || !validCoordinate(destination)) {
    return { error: 'Valid non-zero origin and destination coordinates are required', status: 400 };
  }
  if (!['DRIVE'].includes(travelMode)) {
    return { error: 'The configured OSRM profile supports DRIVE only', status: 422 };
  }
  return { origin, destination, travelMode };
}

function cacheKey(origin, destination, travelMode) {
  return ['OSRM', travelMode, origin.latitude.toFixed(6), origin.longitude.toFixed(6),
    destination.latitude.toFixed(6), destination.longitude.toFixed(6)].join(':');
}

async function verifyAppCheck(request, env) {
  const token = request.headers.get('X-Firebase-AppCheck');
  if (!token) throw Object.assign(new Error('Missing App Check token'), { status: 401 });
  const projectNumber = String(env.FIREBASE_PROJECT_NUMBER || '');
  const expectedAppId = String(env.FIREBASE_APP_ID || '');
  if (!/^\d+$/.test(projectNumber) || !expectedAppId) {
    throw Object.assign(new Error('App Check configuration is incomplete'), { status: 503 });
  }
  const { payload, protectedHeader } = await jwtVerify(token, APP_CHECK_JWKS, {
    algorithms: ['RS256'],
    issuer: `https://firebaseappcheck.googleapis.com/${projectNumber}`,
    audience: `projects/${projectNumber}`,
    requiredClaims: ['iss', 'aud', 'exp', 'iat', 'sub']
  });
  if (protectedHeader.typ !== 'JWT' || payload.sub !== expectedAppId) {
    throw Object.assign(new Error('App Check token is not for this Android app'), { status: 401 });
  }
}

function makeOsrmUrl(baseUrl, origin, destination) {
  const base = String(baseUrl || 'https://router.project-osrm.org').replace(/\/$/, '');
  const coords = `${origin.longitude},${origin.latitude};${destination.longitude},${destination.latitude}`;
  return `${base}/route/v1/driving/${coords}?overview=full&geometries=polyline&alternatives=true&steps=false`;
}

export async function readBoundedBody(request) {
  const declaredLength = Number(request.headers.get('content-length'));
  if (Number.isFinite(declaredLength) && declaredLength > MAX_BODY_BYTES) {
    throw Object.assign(new Error('Request body is too large'), { status: 413 });
  }

  if (!request.body) return '';
  const reader = request.body.getReader();
  const chunks = [];
  let totalBytes = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      totalBytes += value.byteLength;
      if (totalBytes > MAX_BODY_BYTES) {
        await reader.cancel('Request body exceeds configured limit');
        throw Object.assign(new Error('Request body is too large'), { status: 413 });
      }
      chunks.push(value);
    }
  } finally {
    reader.releaseLock();
  }

  const bytes = new Uint8Array(totalBytes);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  try {
    return new TextDecoder('utf-8', { fatal: true }).decode(bytes);
  } catch {
    throw Object.assign(new Error('Request body must be valid UTF-8'), { status: 400 });
  }
}

export async function readBoundedJsonResponse(response, maxBytes = MAX_OSRM_RESPONSE_BYTES) {
  if (!Number.isSafeInteger(maxBytes) || maxBytes <= 0) {
    throw new TypeError('maxBytes must be a positive safe integer');
  }
  if (!response.body) {
    throw Object.assign(new Error('Routing provider returned an empty response'), { status: 502 });
  }

  const reader = response.body.getReader();
  const chunks = [];
  let totalBytes = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      totalBytes += value.byteLength;
      if (totalBytes > maxBytes) {
        await reader.cancel('Routing provider response exceeds configured limit');
        throw Object.assign(new Error('Routing provider response is too large'), { status: 502 });
      }
      chunks.push(value);
    }
  } finally {
    reader.releaseLock();
  }

  const bytes = new Uint8Array(totalBytes);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  try {
    const text = new TextDecoder('utf-8', { fatal: true }).decode(bytes);
    return JSON.parse(text);
  } catch {
    throw Object.assign(new Error('Routing provider returned invalid JSON'), { status: 502 });
  }
}

async function getRoute(request, env) {
  await verifyAppCheck(request, env);

  let rawBody;
  try { rawBody = await readBoundedBody(request); }
  catch (error) {
    if (error.status === 413 || error.status === 400) {
      return json({ error: error.status === 413 ? 'Request body is too large' : 'Invalid request encoding' }, error.status);
    }
    throw error;
  }

  let body;
  try { body = JSON.parse(rawBody); }
  catch { return json({ error: 'Invalid JSON body' }, 400); }

  const validated = validateRouteRequest(body);
  if (validated.error) return json({ error: validated.error }, validated.status);

  const { origin, destination, travelMode } = validated;
  const key = cacheKey(origin, destination, travelMode);
  const cached = routeCache.get(key);
  if (cached && cached.expiresAt > Date.now()) {
    return json({ ...cached.value, cacheHit: true });
  }
  if (cached) routeCache.delete(key);

  const upstream = await fetch(makeOsrmUrl(env.OSRM_BASE_URL, origin, destination), {
    headers: { accept: 'application/json' },
    signal: AbortSignal.timeout(12000)
  });
  const payload = await readBoundedJsonResponse(upstream);
  const candidates = Array.isArray(payload.routes) ? payload.routes : [];
  const validRoutes = candidates.filter(route =>
    Number.isFinite(route.distance) && route.distance > 0 &&
    Number.isFinite(route.duration) && route.duration > 0 &&
    typeof route.geometry === 'string' && route.geometry.length > 0
  ).sort((a, b) => a.distance - b.distance);

  if (!upstream.ok || payload.code !== 'Ok' || !validRoutes.length) {
    throw Object.assign(new Error('Routing provider unavailable'), { status: 502 });
  }

  const best = validRoutes[0];
  const result = {
    provider: 'OSRM',
    distanceMeters: best.distance,
    distanceKm: Number((best.distance / 1000).toFixed(3)),
    durationSeconds: best.duration,
    encodedPolyline: best.geometry,
    alternativesCount: validRoutes.length,
    origin,
    destination
  };

  routeCache.set(key, { value: result, expiresAt: Date.now() + CACHE_TTL_MS });
  if (routeCache.size > MAX_CACHE_ENTRIES) {
    const oldestKey = routeCache.keys().next().value;
    if (oldestKey) routeCache.delete(oldestKey);
  }
  return json({ ...result, cacheHit: false });
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method === 'GET' && url.pathname === '/health') {
      return json({ status: 'ok', service: 'station-routes', provider: 'OSRM' });
    }
    if (request.method === 'POST' && url.pathname === '/v1/routes') {
      try {
        return await getRoute(request, env);
      } catch (error) {
        const status = error.status || (error.name === 'TimeoutError' || error.name === 'AbortError' ? 504 : 502);
        return json({
          error: status === 401 ? 'unauthorized' :
            status === 503 ? 'service configuration unavailable' :
            status === 504 ? 'routing provider timeout' : 'route request failed'
        }, status);
      }
    }
    return json({ error: 'not found' }, 404);
  }
};
