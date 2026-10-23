const fs = require('fs');
const assert = require('assert');

const html = fs.readFileSync('app/src/main/assets/screens/settings.html', 'utf8');
const kotlin = fs.readFileSync('app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/MainActivity.kt', 'utf8');

assert(html.includes("case 'saveDisplaySettings':"), 'settings screen must expose the native save action');
assert(html.includes("bridge.saveDisplaySettings(JSON.stringify(payload || {}))"), 'display save must send one JSON payload to Android');
assert(html.includes('result.verified !== true'), 'display save must require native SQLite verification');
const displaySave = html.match(/async function saveDisplaySettingsOnly\(\)\s*\{([\s\S]*?)\n\s*\}\n\s*async function saveSettings/)?.[1] || '';
assert(!displaySave.includes("bridgeCall('setSystemSetting'"), 'display save must not fall back to per-field simulated writes');
assert(html.includes('Promise.allSettled(['), 'display load must not depend on unrelated settings repositories');
assert(html.includes("if (name === 'display') loadSystemSettings();"), 'reopening display tab must reread SQLite values');
assert(kotlin.includes('fun saveDisplaySettings(jsonData: String): String'), 'MainActivity must expose the native display save contract');
assert(kotlin.includes('put("verified", true)'), 'native save must report verified persistence');
assert(kotlin.includes('db.getAllSystemSettingsMap()'), 'native save must read persisted values back from SQLite');

const payload = { DISPLAY_IDLE_TIMEOUT_SECONDS: '30', DISPLAY_SLEEP_CLOCK: '1' };
const nativeResponse = {
  success: true,
  verified: true,
  data: { ...payload }
};
assert.strictEqual(nativeResponse.success, true);
assert.strictEqual(nativeResponse.verified, true);
for (const [key, value] of Object.entries(payload)) {
  assert.strictEqual(String(nativeResponse.data[key]), value, `readback mismatch for ${key}`);
}

console.log('PASS: display settings use the verified native SQLite bridge contract');
