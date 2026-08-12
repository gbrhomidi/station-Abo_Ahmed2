/* Service Worker - WebView Asset Analyzer Pro v3.0 - DYNAMIC */
const CACHE_NAME = 'webview-assets-v3';
const OFFLINE_PAGE = 'offline.html';
const PRECACHE_ASSETS = [
  '.',
  './index.html',
  './js/jspdf.umd.min.js',
  './js/xlsx.full.min.js',
  './css/all.min.css',
  './css/all.min.css',
  './js/html5-qrcode.min.js',
  './images/149071.png',
  './resources/npm/fuse.js@7.0.0',
  './js/chart.umd.min.js',
  './css/bootstrap.min.css',
  './js/bootstrap.bundle.min.js'
];

self.addEventListener('install', e => {
  e.waitUntil(
    caches.open(CACHE_NAME)
      .then(cache => {
        console.log('[SW] Precaching ' + PRECACHE_ASSETS.length + ' assets');
        return cache.addAll(PRECACHE_ASSETS);
      })
      .catch(err => console.error('[SW] Precache failed:', err))
  );
  self.skipWaiting();
});

self.addEventListener('activate', e => {
  e.waitUntil(
    caches.keys().then(names => {
      return Promise.all(
        names.filter(n => n !== CACHE_NAME).map(n => caches.delete(n))
      );
    })
  );
  self.clients.claim();
});

self.addEventListener('fetch', e => {
  if (e.request.method !== 'GET') return;
  if (e.request.url.startsWith('chrome-extension://')) return;
  if (e.request.url.startsWith('file://') && !e.request.url.includes('android_asset')) {
    return;
  }
  e.respondWith(
    caches.match(e.request).then(cached => {
      if (cached) return cached;
      return fetch(e.request).then(res => {
        if (res && res.status === 200) {
          const clone = res.clone();
          caches.open(CACHE_NAME).then(c => c.put(e.request, clone));
        }
        return res;
      }).catch(() => {
        if (e.request.mode === 'navigate') return caches.match(OFFLINE_PAGE);
        if (e.request.destination === 'image') {
          return new Response('<svg xmlns="http://www.w3.org/2000/svg" width="100" height="100"><rect fill="#ddd" width="100" height="100"/></svg>', {headers:{'Content-Type':'image/svg+xml'}});
        }
      });
    })
  );
});
