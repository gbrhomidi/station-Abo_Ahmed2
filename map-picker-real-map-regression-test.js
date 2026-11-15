const fs = require('fs');
const path = require('path');
const assert = require('node:assert/strict');
const { JSDOM } = require('jsdom');

const pagePath = path.join(__dirname, 'app/src/main/assets/map_picker.html');
const source = fs.readFileSync(pagePath, 'utf8');
const html = source.replace(/<script\s+src=[\s\S]*?<\/script>/gi, '');
const bridgeCalls = [];
const mapEvents = {};
const tileEvents = {};
const tileUrls = [];
let markerCount = 0;
let initialView = null;
const map = {
  setView(point, zoom) { initialView = { point, zoom }; return this; },
  on(name, callback) { mapEvents[name] = callback; return this; }
};
const tileLayer = {
  on(name, callback) { tileEvents[name] = callback; return this; },
  addTo() { return this; },
  setUrl(url) { tileUrls.push(url); return this; }
};
const dom = new JSDOM(html, {
  runScripts: 'dangerously',
  url: 'https://map-picker.test/',
  beforeParse(window) {
    window.MapSelectionBridge = {
      onMapReady() { bridgeCalls.push(['ready']); },
      onLocationSelected(lat, lon) { bridgeCalls.push(['location', lat, lon]); }
    };
    window.L = {
      map() { return map; },
      tileLayer(url) { tileUrls.push(url); return tileLayer; },
      marker(point) {
        markerCount++;
        return {
          addTo() { return this; },
          on() { return this; },
          setLatLng() { return this; },
          getLatLng() { return { lat: point[0], lng: point[1] }; }
        };
      }
    };
  }
});

(async () => {
  const { window } = dom;
  const error = window.document.getElementById('mapError');
  assert.ok(initialView, 'map picker must initialize a view');
  assert.deepEqual(Array.from(initialView.point), [15.5, 47.5], 'map picker must open centered on the application operating region');
  assert.equal(initialView.zoom, 6, 'initial zoom must make nearby roads useful without fabricating a selected point');
  assert.deepEqual(bridgeCalls, [['ready']], 'initialization must notify Android that the real picker map is ready');
  assert.equal(markerCount, 0, 'no location marker may be fabricated before a user selection or supplied saved coordinate');
  assert.match(source, /assets-local\/vendor\/leaflet\/leaflet\.js/, 'map picker must use the locally packaged Leaflet runtime');
  assert.match(source, /basemaps\.cartocdn\.com[\s\S]*World_Street_Map[\s\S]*tile\.openstreetmap\.org/, 'map picker must define independent street-tile fallbacks');

  tileEvents.tileerror();
  assert.match(error.textContent, /مصدر خرائط بديل/, 'tile failure should tell the user that an alternative provider is being tried');
  await new Promise(resolve => setTimeout(resolve, 650));
  assert.match(tileUrls.at(-1), /World_Street_Map/, 'failed primary tiles must advance to the Esri street fallback');
  tileEvents.tileload();
  assert.equal(error.style.display, 'none', 'successful fallback tile should clear the network warning');

  window.setInitialSelection(15.3694, 44.1910);
  assert.equal(markerCount, 1, 'a valid selected location should create one draggable marker');
  assert.deepEqual(bridgeCalls.at(-1), ['location', 15.3694, 44.1910], 'selected coordinates should pass unchanged to the Android bridge');
  window.setInitialSelection(Number.NaN, 44.1910);
  assert.equal(markerCount, 1, 'invalid coordinates must not create additional map markers');
  mapEvents.click({ latlng: { lat: 15.4, lng: 44.2 } });
  assert.deepEqual(bridgeCalls.at(-1), ['location', 15.4, 44.2], 'map clicks should send the selected real coordinate to Android');
  window.close();
  console.log('Map picker real-map and tile-provider regression tests: PASS');
})().catch(error => {
  console.error(error.stack || error);
  dom.window.close();
  process.exitCode = 1;
});
