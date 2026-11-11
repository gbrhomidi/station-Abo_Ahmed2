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
