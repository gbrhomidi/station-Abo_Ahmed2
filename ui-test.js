const fs = require('fs');
const jsdom = require('jsdom');
const { JSDOM } = jsdom;

const html = fs.readFileSync('app/src/main/assets/screens/ai-assistant.html', 'utf8');
const dom = new JSDOM(html);
const document = dom.window.document;

console.log('=== UI Tests for AI Health Center ===');
let passed = 0;
let total = 0;
function assert(name, condition) {
    total++;
    if (condition) { console.log(`✅ PASS: ${name}`); passed++; }
    else console.log(`❌ FAIL: ${name}`);
}

assert('RTL Support', document.documentElement.getAttribute('dir') === 'rtl');
assert('AI Health Panel Exists', document.querySelector('#aiHealthList') !== null);
assert('System Status Badge Exists', document.querySelector('#aiSystemBadge') !== null);
assert('Available Providers Badge Exists', document.querySelector('#aiAvailableBadge') !== null);
assert('Cooldown Providers Badge Exists', document.querySelector('#aiCooldownBadge') !== null);
assert('AI Providers Admin Container Contract', html.includes('id="aiProfiles"'));
assert('loadAiHealth Function Exists', html.includes('async function loadAiHealth()'));
assert('Calls getAiHealthStatus Bridge', html.includes('getAiHealthStatus'));
assert('Dark Mode Support', html.includes('[data-theme="dark"]') || html.includes('toggleTheme'));
assert('No Randomized Business Data', !html.includes('Math.random('));

console.log(`\nResults: ${passed}/${total} passed.`);
if (passed !== total) process.exit(1);
