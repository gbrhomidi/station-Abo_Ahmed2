'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { validCoordinate, distanceMeters, parseOsrmRoutes } = require('./server');

test('accepts valid geographic coordinates', () => {
  assert.equal(validCoordinate({ latitude: 15.3694, longitude: 44.1910 }), true);
});

test('rejects missing, out-of-range and placeholder zero coordinates', () => {
  assert.equal(validCoordinate(null), false);
  assert.equal(validCoordinate({ latitude: 91, longitude: 10 }), false);
  assert.equal(validCoordinate({ latitude: 10, longitude: 181 }), false);
  assert.equal(validCoordinate({ latitude: 0, longitude: 0 }), false);
});

test('route sorting uses road distance and handles missing distance', () => {
  assert.equal(distanceMeters({ distanceMeters: 1200 }), 1200);
  assert.equal(distanceMeters({}), Number.MAX_SAFE_INTEGER);
});

test('OSRM alternatives are validated and sorted by actual road distance', () => {
  const origin = { latitude: 15.3, longitude: 44.1 };
  const destination = { latitude: 15.4, longitude: 44.2 };
  const routes = parseOsrmRoutes({
    code: 'Ok',
    routes: [
      { distance: 5000, duration: 600, geometry: 'longer' },
      { distance: 4200, duration: 550, geometry: 'shorter' },
      { distance: 0, duration: 1, geometry: 'invalid' },
      { distance: 4100, duration: 500 }
    ]
  }, origin, destination);
  assert.equal(routes.length, 2);
  assert.equal(routes[0].distanceMeters, 4200);
  assert.equal(routes[0].distanceKm, 4.2);
  assert.equal(routes[0].provider, 'OSRM');
  assert.equal(routes[0].encodedPolyline, 'shorter');
});

test('OSRM failures and malformed payloads never become fabricated routes', () => {
  assert.deepEqual(parseOsrmRoutes({ code: 'NoRoute', routes: [] }, {}, {}), []);
  assert.deepEqual(parseOsrmRoutes({ code: 'Ok', routes: [{ distance: 10, duration: 5 }] }, {}, {}), []);
});

const { RouteCache } = require('./route-cache');

test('route cache reuses successful results and returns defensive copies', async () => {
  let now = 1000;
  const cache = new RouteCache({ ttlMs: 5000, maxEntries: 2, now: () => now });
  let loads = 0;
  const first = await cache.getOrLoad('a', async () => ({ distanceMeters: ++loads }));
  first.value.distanceMeters = -1;
  const second = await cache.getOrLoad('a', async () => ({ distanceMeters: ++loads }));
  assert.equal(first.cacheHit, false);
  assert.equal(second.cacheHit, true);
  assert.equal(second.value.distanceMeters, 1);
  assert.equal(loads, 1);
});

test('route cache coalesces concurrent requests and does not cache failures', async () => {
  const cache = new RouteCache();
  let loads = 0;
  let release;
  const gate = new Promise(resolve => { release = resolve; });
  const loader = async () => { loads += 1; await gate; return { distanceMeters: 900 }; };
  const one = cache.getOrLoad('shared', loader);
  const two = cache.getOrLoad('shared', loader);
  await new Promise(resolve => setImmediate(resolve));
  release();
  const results = await Promise.all([one, two]);
  assert.equal(loads, 1);
  assert.equal(results[0].value.distanceMeters, 900);
  assert.equal(results[1].cacheHit, true);

  await assert.rejects(cache.getOrLoad('failure', async () => { throw new Error('provider down'); }));
  const retry = await cache.getOrLoad('failure', async () => ({ distanceMeters: 901 }));
  assert.equal(retry.cacheHit, false);
});

test('route cache expires entries and enforces a bounded least-recently-used size', async () => {
  let now = 0;
  const cache = new RouteCache({ ttlMs: 10, maxEntries: 2, now: () => now });
  await cache.getOrLoad('a', async () => ({ value: 'a' }));
  await cache.getOrLoad('b', async () => ({ value: 'b' }));
  await cache.getOrLoad('a', async () => ({ value: 'unused' }));
  await cache.getOrLoad('c', async () => ({ value: 'c' }));
  assert.equal(cache.size, 2);
  let loads = 0;
  await cache.getOrLoad('b', async () => ({ value: ++loads }));
  assert.equal(loads, 1);
  now = 11;
  const expired = await cache.getOrLoad('a', async () => ({ value: 'fresh' }));
  assert.equal(expired.cacheHit, false);
  assert.equal(expired.value.value, 'fresh');
});
