import test from 'node:test';
import assert from 'node:assert/strict';
import worker, { readBoundedBody, readBoundedJsonResponse, validCoordinate, validateRouteRequest } from '../src/index.js';

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


test('bounded body reader accepts payloads within the configured byte limit', async () => {
  const body = JSON.stringify({ value: 'station' });
  const request = new Request('https://worker/v1/routes', { method: 'POST', body });
  assert.equal(await readBoundedBody(request), body);
});

test('bounded body reader rejects an oversized declared Content-Length before reading', async () => {
  const request = new Request('https://worker/v1/routes', {
    method: 'POST',
    headers: { 'content-length': String(16 * 1024 + 1) },
    body: '{}'
  });
  await assert.rejects(readBoundedBody(request), error => error.status === 413);
});

test('bounded body reader cancels a streamed payload as soon as it exceeds the limit', async () => {
  let cancelled = false;
  const stream = new ReadableStream({
    start(controller) {
      controller.enqueue(new TextEncoder().encode('x'.repeat(12 * 1024)));
      controller.enqueue(new TextEncoder().encode('x'.repeat(5 * 1024)));
    },
    cancel() { cancelled = true; }
  });
  const request = new Request('https://worker/v1/routes', {
    method: 'POST',
    body: stream,
    duplex: 'half'
  });
  await assert.rejects(readBoundedBody(request), error => error.status === 413);
  assert.equal(cancelled, true);
});

test('bounded body reader rejects malformed UTF-8', async () => {
  const request = new Request('https://worker/v1/routes', {
    method: 'POST',
    body: new Uint8Array([0xc3, 0x28])
  });
  await assert.rejects(readBoundedBody(request), error => error.status === 400);
});


test('OSRM response reader accepts valid JSON within its byte budget', async () => {
  const payload = { code: 'Ok', routes: [{ distance: 1200, duration: 90, geometry: 'abc' }] };
  const response = new Response(JSON.stringify(payload), {
    headers: { 'content-type': 'application/json' }
  });
  assert.deepEqual(await readBoundedJsonResponse(response, 1024), payload);
});

test('OSRM response reader cancels oversized upstream streams', async () => {
  let cancelled = false;
  const stream = new ReadableStream({
    start(controller) {
      controller.enqueue(new TextEncoder().encode('x'.repeat(80)));
      controller.enqueue(new TextEncoder().encode('x'.repeat(80)));
    },
    cancel() { cancelled = true; }
  });
  const response = new Response(stream);
  await assert.rejects(
    readBoundedJsonResponse(response, 100),
    error => error.status === 502
  );
  assert.equal(cancelled, true);
});

test('OSRM response reader rejects malformed JSON and invalid limits', async () => {
  await assert.rejects(
    readBoundedJsonResponse(new Response('{invalid'), 100),
    error => error.status === 502
  );
  await assert.rejects(
    readBoundedJsonResponse(new Response('{}'), 0),
    error => error instanceof TypeError
  );
});
