'use strict';

/**
 * Small bounded process-local cache for road-route responses.
 *
 * It avoids repeated calls to the public OSRM service for the same endpoints
 * and coalesces concurrent requests. Failed loads are never cached.
 */
class RouteCache {
  constructor({ ttlMs = 5 * 60 * 1000, maxEntries = 256, now = Date.now } = {}) {
    if (!Number.isFinite(ttlMs) || ttlMs < 0) throw new TypeError('ttlMs must be non-negative');
    if (!Number.isInteger(maxEntries) || maxEntries < 1) throw new TypeError('maxEntries must be positive');
    this.ttlMs = ttlMs;
    this.maxEntries = maxEntries;
    this.now = now;
    this.entries = new Map();
    this.inFlight = new Map();
  }

  async getOrLoad(key, loader) {
    const now = this.now();
    const cached = this.entries.get(key);
    if (cached && cached.expiresAt > now) {
      this.entries.delete(key);
      this.entries.set(key, cached);
      return { value: structuredClone(cached.value), cacheHit: true };
    }
    if (cached) this.entries.delete(key);

    const pending = this.inFlight.get(key);
    if (pending) return { value: structuredClone(await pending), cacheHit: true };

    const request = Promise.resolve().then(loader);
    this.inFlight.set(key, request);
    try {
      const value = await request;
      if (this.ttlMs > 0) {
        this.entries.delete(key);
        this.entries.set(key, { value: structuredClone(value), expiresAt: this.now() + this.ttlMs });
        while (this.entries.size > this.maxEntries) {
          this.entries.delete(this.entries.keys().next().value);
        }
      }
      return { value: structuredClone(value), cacheHit: false };
    } finally {
      if (this.inFlight.get(key) === request) this.inFlight.delete(key);
    }
  }

  clear() {
    this.entries.clear();
    this.inFlight.clear();
  }

  get size() {
    return this.entries.size;
  }
}

module.exports = { RouteCache };
