const fs = require('fs');
const jsdom = require('jsdom');
const { JSDOM } = jsdom;

const html = fs.readFileSync('app/src/main/assets/screens/messages.html', 'utf8');
const dom = new JSDOM(html);
const document = dom.window.document;

console.log("=== UI Tests for SMS Operations Center (messages.html) ===");

let passed = 0;
let total = 0;

function assert(name, condition) {
    total++;
    if (condition) {
        console.log(`✅ PASS: ${name}`);
        passed++;
    } else {
        console.log(`❌ FAIL: ${name}`);
    }
}

// 1. RTL Support
assert('RTL Support (dir="rtl")', document.documentElement.getAttribute('dir') === 'rtl');

// 2. CSS Theme Integration
assert('Links to theme.css', html.includes('theme.css'));
assert('No legacy inline styles for main components', !html.includes('.stats-bar {') && !html.includes('.message-card {'));

// 3. UI Components Exist
assert('Message List Container', document.querySelector('#messageList') !== null);
assert('Stats Dashboard Container', document.querySelector('#statsDashboard') !== null);
assert('Search Input', document.querySelector('#searchInput') !== null);
assert('Weekly Analytics Chart', document.querySelector('#weeklyBars') !== null);
assert('Trace List', document.querySelector('#traceList') !== null);

// 4. Modals and Overlays
assert('Message Detail Modal Overlay', document.querySelector('#messageModal.modal-overlay') !== null);
assert('Compose Modal Overlay', document.querySelector('#composeModal.modal-overlay') !== null);
assert('Template Modal Overlay', document.querySelector('#templateModal.modal-overlay') !== null);

// 5. JavaScript Bridge Calls
assert('Calls getSmsMessagesPage', html.includes('getSmsMessagesPage'));
assert('Calls getSmsOperationalHealth', html.includes('getSmsOperationalHealth'));
assert('Calls getSmsConversationTrace', html.includes('getSmsConversationTrace'));
assert('Calls getSmsWeeklyAnalytics', html.includes('getSmsWeeklyAnalytics'));

// 6. Data Parsing Contract
assert('Parses operational health as raw.data', html.includes('raw.data') && html.includes('getSmsOperationalHealth'));
assert('Parses trace as raw.data', html.includes('raw.data') && html.includes('getSmsConversationTrace'));
assert('Parses weekly analytics top-level', html.includes('raw.success === true ? raw : {}') && html.includes('getSmsWeeklyAnalytics'));

console.log(`\nResults: ${passed}/${total} passed.`);
if (passed !== total) {
    process.exit(1);
}
