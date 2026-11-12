import test from 'node:test';
import assert from 'node:assert/strict';
import worker, { validCoordinate, validateRouteRequest } from '../src/index.js';

test('health is public and identifies the routing provider', async () => {
  const response = await worker.fetch(new Request('https://worker/health'), {});
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { status: 'ok', service: 'station-routes', provider: 'OSRM' });
});

test('route endpoint rejects a missing App Check token', async () => {
  const response = await worker.fetch(new Request('https://worker/v1/routes', {
    method: 'POST', headers: { 'content-type': 'application/json' }, body: '{}'
  }), { FIREBASE_PROJECT_NUMBER: '593620032216', FIREBASE_APP_ID: '1:593620032216:android:30e3116ba61c31055355ad' });
  assert.equal(response.status, 401);
});

test('unknown paths are rejected', async () => {
  const response = await worker.fetch(new Request('https://worker/not-found'), {});
  assert.equal(response.status, 404);
});

test('coordinate validation rejects invalid, out-of-range and null-island points', () => {
  assert.equal(validCoordinate({ latitude: 15.4, longitude: 44.2 }), true);
  assert.equal(validCoordinate({ latitude: 91, longitude: 44.2 }), false);
  assert.equal(validCoordinate({ latitude: 0, longitude: 0 }), false);
  assert.equal(validCoordinate(null), false);
});

test('route contract accepts DRIVE and defaults travelMode to DRIVE', () => {
  const origin = { latitude: 15.4, longitude: 44.2 };
  const destination = { latitude: 15.5, longitude: 44.3 };
  assert.deepEqual(validateRouteRequest({ origin, destination }), { origin, destination, travelMode: 'DRIVE' });
  assert.deepEqual(validateRouteRequest({ origin, destination, travelMode: 'DRIVE' }), { origin, destination, travelMode: 'DRIVE' });
});

test('route contract rejects unsupported travel modes and invalid coordinates', () => {
  const origin = { latitude: 15.4, longitude: 44.2 };
  const destination = { latitude: 15.5, longitude: 44.3 };
  assert.equal(validateRouteRequest({ origin, destination, travelMode: 'WALK' }).status, 422);
  assert.equal(validateRouteRequest({ origin: { latitude: 999, longitude: 0 }, destination }).status, 400);
});
