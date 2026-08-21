const fs = require('fs');
const jsdom = require('jsdom');
const { JSDOM } = jsdom;

const html = fs.readFileSync('app/src/main/assets/screens/ai-assistant.html', 'utf8');
const dom = new JSDOM(html);
const document = dom.window.document;

console.log("=== UI Tests for AI Health Center ===");

// Test 1: RTL Support
const dir = document.documentElement.getAttribute('dir');
console.log(`RTL Support: ${dir === 'rtl' ? 'PASS' : 'FAIL'}`);

// Test 2: AI Health Center Panel Exists
const healthPanel = document.querySelector('#ai-health-center');
console.log(`Health Panel Exists: ${healthPanel ? 'PASS' : 'FAIL'}`);

// Test 3: System Status Element Exists
const systemStatus = document.querySelector('#system-status-badge');
console.log(`System Status Badge Exists: ${systemStatus ? 'PASS' : 'FAIL'}`);

// Test 4: Providers List Container Exists
const providersList = document.querySelector('#providers-health-list');
console.log(`Providers List Exists: ${providersList ? 'PASS' : 'FAIL'}`);

// Test 5: Check loadAiHealthStatus function definition
const hasLoadFunction = html.includes('function loadAiHealthStatus()');
console.log(`loadAiHealthStatus Function Exists: ${hasLoadFunction ? 'PASS' : 'FAIL'}`);

// Test 6: Check Android Bridge Call
const callsBridge = html.includes('getAiHealthStatus');
console.log(`Calls Android Bridge: ${callsBridge ? 'PASS' : 'FAIL'}`);

// Test 7: Dark Mode Support
const hasDarkMode = html.includes('[data-theme="dark"]');
console.log(`Dark Mode Classes Exist: ${hasDarkMode ? 'PASS' : 'FAIL'}`);
