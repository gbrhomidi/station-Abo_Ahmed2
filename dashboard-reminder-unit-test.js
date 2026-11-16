const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const assert = require('node:assert/strict');
const { JSDOM } = require('jsdom');

const html = fs.readFileSync(path.join(__dirname, 'app/src/main/assets/main.html'), 'utf8');
const script = [...html.matchAll(/<script(?:\s[^>]*)?>([\s\S]*?)<\/script>/gi)]
  .map(match => match[1]).find(block => block.includes('function refreshMainDueReminders'));
assert.ok(script, 'main.html dashboard script must be present');

const dueStart = script.indexOf('        let mainDueReminderRows = [];');
const dueEnd = script.indexOf('        // ================================================================\n        // Dashboard', dueStart);
assert.ok(dueStart >= 0 && dueEnd > dueStart, 'due-reminder module boundary must be present');
const dueModule = script.slice(dueStart, dueEnd);

const bubbleStart = script.indexOf('        function createNotificationBubble(notification) {');
const bubbleEnd = script.indexOf('        function handleBubbleClick(bubble) {', bubbleStart);
assert.ok(bubbleStart >= 0 && bubbleEnd > bubbleStart, 'notification bubble module boundary must be present');
const bubbleModule = script.slice(bubbleStart, bubbleEnd);
const flightStart = script.indexOf('        function animateBubbleFromCards(bubble, sources) {');
const flightEnd = script.indexOf('        function animateBubbleFromCard(bubble) {', flightStart);
assert.ok(flightStart >= 0 && flightEnd > flightStart, 'bubble flight module boundary must be present');
const flightModule = script.slice(flightStart, flightEnd);

function createHarness() {
  const dom = new JSDOM(`<!doctype html><html><body>
    <div id="bubbleContainer"></div>
    <div id="mainDueReminderOverlay" class="show">
      <div id="mainDueReminderFeedback" class="due-reminder-feedback"></div>
      <div class="due-reminder-actions"><button></button><button></button></div>
    </div>
    <div id="mainDueDetailModal"><div id="mainDueDetailContent"></div></div>
    <button id="mainDueReminderBubble" class="notification-bubble due-reminder-notification-bubble" style="display:flex"><i></i><span class="due-bubble-count">0</span></button>
    <div id="card-customer-debts"></div><div id="card-due-invoices"></div>
  </body></html>`, { runScripts: 'outside-only', pretendToBeVisual: true });
  const { window } = dom;
  const apiCalls = [];
  window.matchMedia = () => ({ matches: false });
  window.apiCall = async (method, payload) => {
    apiCalls.push({ method, payload });
    if (window.__apiHandler) return window.__apiHandler(method, payload);
    if (method === 'getSetting') return { success: true, value: '0' };
    if (method === 'setSetting') return { success: true };
    return { success: true, data: [] };
  };
  window.showToast = () => {};
  window.escapeHtml = value => String(value);
  window.CURRENCY = 'ر.س';
  window.activeBubbles = new Map();
  window.notificationsEnabled = true;
  window.isProcessingQueue = false;
  window.notificationQueue = [];
  window.createBubbleContainer = () => window.document.getElementById('bubbleContainer');
  window.animateBubbleFromCard = () => {};
  window.eval(`const DUE_REMINDER_REFRESH_INTERVAL_MS = 15 * 60 * 1000;\n${dueModule}\n${bubbleModule}\n${flightModule}\nwindow.__setMainDueReminderRows = rows => { mainDueReminderRows = rows; };`);
  return { dom, window, apiCalls };
}

const dueRow = (overrides = {}) => ({
  event_id: 'debt-reminder:4:12:2026-11-20:invoice-9:pre',
  customer_party_id: 12,
  nearest_due_date: '2026-11-20',
  days_remaining: 4,
  total_outstanding: 1250,
  ...overrides
});

test('persistMainDueReminderDecision writes the expected typed local reminder decision', async () => {
  const { window, apiCalls } = createHarness();
  window.__setMainDueReminderRows([dueRow()]);
  await window.persistMainDueReminderDecision('deferred', 'local');
  const write = apiCalls.find(call => call.method === 'updateDebtReminderInteractionTyped');
  assert.deepEqual(JSON.parse(JSON.stringify(write.payload)), {
    event_id: dueRow().event_id,
    customer_party_id: 12,
    due_date: '2026-11-20',
    action: 'deferred',
    mode: 'local'
  });
});

test('persistMainDueReminderDecision refuses invalid candidates without calling the bridge', async () => {
  const { window, apiCalls } = createHarness();
  window.__setMainDueReminderRows([dueRow({ event_id: '', days_remaining: 12 })]);
  await assert.rejects(window.persistMainDueReminderDecision('dismissed', 'local'), /لا توجد مستحقات قريبة صالحة/);
  assert.equal(apiCalls.length, 0);
});

test('auto reminder mode saves and verifies before closing the owning modal', async () => {
  const { window, apiCalls } = createHarness();
  const checkbox = window.document.createElement('input');
  checkbox.id = 'mainDueReminderMode_auto';
  checkbox.checked = true;
  window.document.body.appendChild(checkbox);
  window.__apiHandler = async (method, payload) => {
    if (method === 'setSetting') return { success: true };
    if (method === 'getSetting') return { success: true, value: '1' };
    return { success: true, data: [] };
  };
  await window.setMainDebtReminderMode('auto', true);
  assert.deepEqual(apiCalls.map(call => call.method), ['setSetting', 'getSetting']);
  assert.equal(window.document.getElementById('mainDueReminderOverlay').classList.contains('show'), false);
  assert.equal(checkbox.disabled, false);
  assert.equal(window.document.getElementById('mainDueReminderFeedback').textContent, '');
});

test('defer failures are rendered inside the reminder modal, not a global toast', async () => {
  const { window } = createHarness();
  window.__setMainDueReminderRows([dueRow()]);
  window.__apiHandler = async method => method === 'updateDebtReminderInteractionTyped'
    ? { success: false, error: 'تعذر حفظ التفاعل' }
    : { success: true, value: '0' };
  await window.deferMainDueReminder();
  const feedback = window.document.getElementById('mainDueReminderFeedback');
  assert.equal(feedback.textContent, 'تعذر حفظ التفاعل');
  assert.equal(feedback.classList.contains('show'), true);
  assert.equal(window.document.getElementById('mainDueReminderOverlay').classList.contains('show'), true);
});

test('successful defer persists locally, closes the modal, and keeps the reminder bubble visible', async () => {
  const { window, apiCalls } = createHarness();
  window.__setMainDueReminderRows([dueRow()]);
  await window.deferMainDueReminder();
  const interaction = apiCalls.find(call => call.method === 'updateDebtReminderInteractionTyped');
  assert.equal(interaction.payload.action, 'deferred');
  assert.equal(interaction.payload.mode, 'local');
  assert.equal(window.document.getElementById('mainDueReminderOverlay').classList.contains('show'), false);
  assert.equal(window.document.getElementById('mainDueReminderBubble').style.display, 'flex');
});

test('cancel disables SMS, saves the local-only reminder state, dismisses, and closes', async () => {
  const { window, apiCalls } = createHarness();
  window.__setMainDueReminderRows([dueRow()]);
  const settings = new Map();
  window.__apiHandler = async (method, payload) => {
    if (method === 'setSetting') { settings.set(payload.key, payload.value); return { success: true }; }
    if (method === 'getSetting') return { success: true, value: settings.get(payload.key) || '0' };
    if (method === 'getDueSoonDebtRemindersTyped') return { success: true, data: [] };
    return { success: true, data: [] };
  };
  await window.cancelMainDueReminderNotice();
  assert.equal(settings.get('debt_due_auto_sms_enabled'), '0');
  assert.equal(settings.get('debt_due_manual_sms_enabled'), '0');
  assert.equal(settings.get('debt_due_local_notification_enabled'), '1');
  const interaction = apiCalls.find(call => call.method === 'updateDebtReminderInteractionTyped');
  assert.equal(interaction.payload.action, 'dismissed');
  assert.equal(interaction.payload.mode, 'local');
  assert.equal(window.document.getElementById('mainDueReminderOverlay').classList.contains('show'), false);
  assert.equal(window.document.getElementById('mainDueReminderBubble').style.display, 'none');
});

test('details modal renders records and safely escapes customer-controlled cell values', () => {
  const { window } = createHarness();
  window.__setMainDueReminderRows([dueRow({ customer_name: '<img src=x onerror=alert(1)>', phone: '050<script>' })]);
  window.openMainDueReminderDetails();
  const content = window.document.getElementById('mainDueDetailContent');
  assert.equal(window.document.querySelectorAll('#mainDueDetailContent img, #mainDueDetailContent script').length, 0);
  assert.match(content.textContent, /<img src=x onerror=alert\(1\)>/);
  assert.equal(window.document.getElementById('mainDueDetailModal').classList.contains('show'), true);
  assert.equal(window.document.querySelector('.due-detail-table th').textContent, 'العميل');
});

test('bubble creation registers the target and starts the existing card-to-bubble flight', () => {
  const { window } = createHarness();
  let animated = 0;
  window.animateBubbleFromCard = bubble => { animated++; assert.equal(bubble.dataset.target, 'card-customer-debts'); };
  window.createNotificationBubble({ type: 'customer_debts', color: 'info', targetCard: 'card-customer-debts', icon: 'fa-coins', message: 'دين قريب' });
  const bubble = window.document.querySelector('#bubbleContainer .notification-bubble');
  assert.ok(bubble);
  assert.equal(bubble.dataset.type, 'customer_debts');
  assert.equal(window.activeBubbles.has('customer_debts'), true);
  assert.equal(animated, 1);
});

test('card-origin particles fly to the bubble and are removed after the animation', () => {
  const { window } = createHarness();
  const bubble = window.document.getElementById('mainDueReminderBubble');
  const sourceA = window.document.getElementById('card-customer-debts');
  const sourceB = window.document.getElementById('card-due-invoices');
  bubble.getBoundingClientRect = () => ({ left: 40, top: 60, width: 36, height: 36 });
  sourceA.getBoundingClientRect = () => ({ left: 100, top: 200, width: 200, height: 100 });
  sourceB.getBoundingClientRect = () => ({ left: 400, top: 300, width: 180, height: 80 });
  const animations = [];
  window.Element.prototype.animate = function(keyframes, options) {
    const animation = { keyframes, options, onfinish: null };
    animations.push({ element: this, animation });
    return animation;
  };
  window.animateBubbleFromCards(bubble, [sourceA, sourceB]);
  const particles = [...window.document.querySelectorAll('.bubble-flight-particle')];
  assert.equal(particles.length, 2);
  assert.equal(animations.length, 3, 'two particles and one bubble entrance animation should run');
  assert.ok(animations[0].animation.keyframes[2].transform.includes('translate('));
  animations.slice(0, 2).forEach(({ animation }) => animation.onfinish());
  assert.equal(window.document.querySelectorAll('.bubble-flight-particle').length, 0);
});

test('reduced-motion preference suppresses source particles', () => {
  const { window } = createHarness();
  window.matchMedia = () => ({ matches: true });
  const bubble = window.document.getElementById('mainDueReminderBubble');
  const source = window.document.getElementById('card-customer-debts');
  bubble.getBoundingClientRect = () => ({ left: 0, top: 0, width: 36, height: 36 });
  source.getBoundingClientRect = () => ({ left: 0, top: 0, width: 100, height: 100 });
  window.animateBubbleFromCards(bubble, [source]);
  assert.equal(window.document.querySelectorAll('.bubble-flight-particle').length, 0);
});

test('main notification circles keep their three-second wave and responsive-motion override', () => {
  assert.match(html, /\.notification-bubble\s*\{[\s\S]*?border-radius:\s*50%/);
  assert.match(html, /\.notification-bubble::before\s*\{[\s\S]*?animation:\s*goldenBubbleWave 3s ease-out 1 both/);
  assert.match(html, /prefers-reduced-motion:\s*reduce\)[\s\S]*?\.notification-bubble::before\s*\{\s*animation:\s*none/);
});
