'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { RouteCache } = require('./route-cache');

test('successful routes are cached for the configured TTL without leaking mutable objects', async () => {
  let now = 100;
  const cache = new RouteCache({ ttlMs: 500, maxEntries: 4, now: () => now });
  let calls = 0;
  const first = await cache.getOrLoad('route-a', async () => ({ route: { distanceMeters: ++calls } }));
  first.value.route.distanceMeters = 0;
  const second = await cache.getOrLoad('route-a', async () => ({ route: { distanceMeters: ++calls } }));
  assert.equal(first.cacheHit, false);
  assert.equal(second.cacheHit, true);
  assert.equal(second.value.route.distanceMeters, 1);
  assert.equal(calls, 1);
  now = 601;
  const expired = await cache.getOrLoad('route-a', async () => ({ route: { distanceMeters: ++calls } }));
  assert.equal(expired.cacheHit, false);
  assert.equal(expired.value.route.distanceMeters, 2);
});

test('identical in-flight route lookups share a single provider call', async () => {
  const cache = new RouteCache();
  let calls = 0;
  let release;
  const gate = new Promise(resolve => { release = resolve; });
  const loader = async () => { calls += 1; await gate; return { distanceMeters: 1250 }; };
  const first = cache.getOrLoad('same-route', loader);
  const second = cache.getOrLoad('same-route', loader);
  await new Promise(resolve => setImmediate(resolve));
  release();
  const results = await Promise.all([first, second]);
  assert.equal(calls, 1);
  assert.equal(results[0].value.distanceMeters, 1250);
  assert.equal(results[1].value.distanceMeters, 1250);
  assert.equal(results[1].cacheHit, true);
});

test('failed requests are not cached and LRU storage remains bounded', async () => {
  const cache = new RouteCache({ ttlMs: 1000, maxEntries: 2 });
  await assert.rejects(cache.getOrLoad('bad', async () => { throw new Error('upstream failure'); }));
  const recovered = await cache.getOrLoad('bad', async () => ({ distanceMeters: 10 }));
  assert.equal(recovered.cacheHit, false);
  await cache.getOrLoad('a', async () => ({ distanceMeters: 20 }));
  await cache.getOrLoad('b', async () => ({ distanceMeters: 30 }));
  await cache.getOrLoad('bad', async () => ({ distanceMeters: 999 }));
  assert.equal(cache.size, 2);
  let calls = 0;
  await cache.getOrLoad('a', async () => ({ distanceMeters: ++calls }));
  assert.equal(calls, 1);
});
