'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { validCoordinate, distanceMeters } = require('./server');
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
