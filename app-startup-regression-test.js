const fs = require('fs');
const vm = require('vm');
const assert = require('assert');

const activityPath = 'app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/MainActivity.kt';
const activity = fs.readFileSync(activityPath, 'utf8');
const login = fs.readFileSync('app/src/main/assets/screens/login.html', 'utf8');

const onCreateStart = activity.indexOf('override fun onCreate(savedInstanceState: Bundle?)');
const onCreateEnd = activity.indexOf('private data class StartupPreparation', onCreateStart);
assert(onCreateStart >= 0 && onCreateEnd > onCreateStart, 'MainActivity onCreate block not found');
const onCreate = activity.slice(onCreateStart, onCreateEnd);
assert(!onCreate.includes('DebtDueDateRepository.ensureSchema'), 'SQLite schema work must not run in onCreate');
assert(!onCreate.includes('migrateLegacySecuritySettings'), 'Security migration must not run in onCreate');
assert(!onCreate.includes('clearSessionAfterDeviceRebootIfNeeded'), 'Session DB work must not run in onCreate');
assert(!onCreate.includes('requestAllPermissions()'), 'Permission dialogs must not block initial screen creation');
assert(onCreate.indexOf('setContent {') < onCreate.indexOf('initializeStartupDatabase()'), 'Compose must be scheduled before startup DB work');
assert(/if\s*\(initializeStartupDatabase\(\)\)\s*\{\s*initializeSystem\(\)\s*\}\s*TaskNotificationManager\.reschedulePendingTasksAsync\(applicationContext\)/.test(onCreate), 'Task notifications must be rescheduled only after startup DB and SMS initialization');
assert(onCreate.indexOf('TaskNotificationManager.reschedulePendingTasksAsync') > onCreate.indexOf('initializeSystem()'), 'Task notification DB reads must not compete with first SQLite initialization');

const onStartStart = activity.indexOf('override fun onStart()');
const onStartEnd = activity.indexOf('override fun onResume()', onStartStart);
assert(onStartStart >= 0 && onStartEnd > onStartStart, 'MainActivity onStart block not found');
const onStart = activity.slice(onStartStart, onStartEnd);
assert(/startupDatabaseReady\s*&&\s*startupHealthCheckCompleted/.test(onStart), 'onStart SQLite health checks must wait for the startup check to finish');
assert(onStart.includes('lifecycleScope.launch(Dispatchers.IO)'), 'onStart SQLite health checks must remain off the UI thread');

const startupInitializer = activity.match(/private suspend fun initializeStartupDatabase\(\): Boolean\s*\{([\s\S]*?)\n\s*override fun onNewIntent/);
assert(startupInitializer, 'Asynchronous startup initializer not found');
assert(startupInitializer[1].includes('withContext(Dispatchers.IO)'), 'Startup SQLite work must use Dispatchers.IO');
assert(startupInitializer[1].includes('DebtDueDateRepository.ensureSchema(dbHelper)'), 'Required schema preparation was removed');
assert(startupInitializer[1].includes('migrateLegacySecuritySettings(dbHelper)'), 'Legacy security migration was removed');
assert(startupInitializer[1].includes('SQLite startup preparation completed in'), 'SQLite startup timing log is missing');

const initializeSystemStart = activity.indexOf('private suspend fun initializeSystem()');
const initializeSystemEnd = activity.indexOf('// ============================================================', initializeSystemStart);
assert(initializeSystemStart >= 0 && initializeSystemEnd > initializeSystemStart, 'initializeSystem block not found');
const initializeSystem = activity.slice(initializeSystemStart, initializeSystemEnd);
assert(/withContext\(Dispatchers\.IO\)\s*\{\s*checkSmsSystemHealth\(\)\s*\}/.test(initializeSystem), 'Startup health checks must not run synchronously on the UI thread');
assert(initializeSystem.includes('startupHealthCheckCompleted = true'), 'Startup health-check completion state is missing');

assert(activity.includes('wv.post {') && activity.includes('loadWebViewFromAssets()'), 'WebView factory must trigger its initial asset load');
assert(activity.includes('startupDatabaseReady && startupSetupRequired'), 'First-user setup routing must use the cached startup result');
assert(activity.includes('fun getStartupState(): String'), 'Startup readiness bridge method is missing');

const inlineScripts = [...login.matchAll(/<script(?:\s[^>]*)?>(\s*[\s\S]*?)<\/script>/gi)]
  .map(match => match[1])
  .filter(script => script.trim().length > 0);
assert(inlineScripts.length > 0, 'No inline login script found');
inlineScripts.forEach((script, index) => new vm.Script(script, { filename: `login.html:inline-script-${index + 1}` }));
assert(login.includes('class="startup-status"'), 'Login startup status notice is missing');
assert(login.includes('await waitForNativeStartup()'), 'Login bridge calls must wait asynchronously for startup readiness');
assert(login.includes('startupRedirect: startupState ==='), 'Startup redirects must not count as failed login attempts');

assert(login.includes('function readNativeStartupState()'), 'Native startup state must be read from the live Android bridge');
assert(/attempt\.then\(releaseWaitSlot, releaseWaitSlot\)/.test(login), 'Settled startup wait promises must release the single-flight slot');
assert(login.includes("document.addEventListener('visibilitychange', refreshNativeStartupStateOnResume)"), 'WebView visibility resume must refresh native startup state');
assert(login.includes("window.addEventListener('pageshow', refreshNativeStartupStateOnResume)"), 'WebView pageshow must refresh native startup state');
assert(login.includes("Always read Android's live state before checking the JS deadline"), 'A resumed page must read native readiness before applying an elapsed JS timeout');

async function verifyStartupWaitRecovery() {
  const bridgeStart = login.indexOf('function hasAndroidBridge()');
  const bridgeEnd = login.indexOf('function logToAndroid(', bridgeStart);
  assert(bridgeStart >= 0 && bridgeEnd > bridgeStart, 'Startup bridge helper block not found');

  const listeners = {};
  const state = { value: 'initializing', reads: 0 };
  const status = {
    textContent: '',
    visible: false,
    classList: {
      add() { status.visible = true; },
      remove() { status.visible = false; }
    }
  };
  const context = vm.createContext({
    AndroidInterface: {
      getStartupState() { state.reads += 1; return state.value; },
      getStartupError() { return 'startup test failure'; }
    },
    document: {
      hidden: false,
      getElementById(id) { return id === 'startupStatus' ? status : null; },
      addEventListener(name, callback) { listeners['document:' + name] = callback; }
    },
    window: {
      addEventListener(name, callback) { listeners['window:' + name] = callback; }
    },
    setTimeout,
    Promise,
    Date
  });

  new vm.Script(login.slice(bridgeStart, bridgeEnd), { filename: 'login-startup-bridge.js' }).runInContext(context);

  const timedOut = await vm.runInContext(
    'waitForNativeStartup({ timeoutMs: 10, pollIntervalMs: 1 })',
    context
  );
  assert.strictEqual(timedOut, 'failed', 'An initializing database should time out visibly');
  assert.strictEqual(vm.runInContext('nativeStartupWaitPromise === null', context), true,
    'A timed-out promise must not remain cached');

  state.value = 'ready';
  const readsBeforeRetry = state.reads;
  const recovered = await vm.runInContext(
    'waitForNativeStartup({ timeoutMs: 100, pollIntervalMs: 1 })',
    context
  );
  assert.strictEqual(recovered, 'ready', 'A later call must re-read and accept native ready state');
  assert(state.reads > readsBeforeRetry, 'A later call must query Android again');
  assert.strictEqual(vm.runInContext('nativeStartupWaitPromise === null', context), true,
    'A successful promise must also release the slot');

  state.value = 'initializing';
  const coalesced = vm.runInContext(
    '(() => { const first = waitForNativeStartup({ timeoutMs: 500, pollIntervalMs: 50 }); const second = waitForNativeStartup({ timeoutMs: 500, pollIntervalMs: 50 }); return first === second; })()',
    context
  );
  assert.strictEqual(coalesced, true, 'Concurrent callers must share the active poll');

  state.value = 'ready';
  const readsBeforeResume = state.reads;
  listeners['document:visibilitychange']();
  assert(state.reads > readsBeforeResume, 'Returning to a visible WebView must query Android immediately');
  assert.strictEqual(status.visible, false, 'A live ready state must clear the startup notice');
  await vm.runInContext('nativeStartupWaitPromise', context);
  assert.strictEqual(vm.runInContext('nativeStartupWaitPromise === null', context), true,
    'The resumed poll must release its slot after observing readiness');
}

verifyStartupWaitRecovery().then(() => {
  console.log('Startup regression PASS: native state is re-read, settled wait promises are released, resume refreshes readiness, and concurrent callers share only an active poll.');
  console.log('Startup regression PASS: DB initialization is asynchronous, WebView loading is anchored to its factory, and login calls are readiness-gated.');
}).catch(error => {
  console.error(error);
  process.exitCode = 1;
});

