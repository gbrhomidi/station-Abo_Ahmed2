const fs = require('fs');
const assert = require('assert');

const source = fs.readFileSync(
  'app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/MainActivity.kt',
  'utf8'
);
const key = 'KEY_AUTHENTICATED_SHELL_PENDING_RESTORE';
const keyDeclaration = `private const val ${key} = "authenticated_shell_pending_restore"`;
assert(source.includes(keyDeclaration), 'Encrypted restoration marker is declared');

const onCreateStart = source.indexOf('override fun onCreate(savedInstanceState: Bundle?)');
const onCreateEnd = source.indexOf('private data class StartupPreparation', onCreateStart);
assert(onCreateStart >= 0 && onCreateEnd > onCreateStart, 'onCreate block is present');
const onCreate = source.slice(onCreateStart, onCreateEnd);
assert(onCreate.indexOf('initEncryptedPrefs()') < onCreate.indexOf(`getBoolean(${key}, false)`),
  'Persisted shell state is read only after encrypted preferences are initialized');
assert(onCreate.includes('wasAuthenticatedShellAtRecreation = true') &&
  onCreate.includes('isActivityRecreation = true'),
  'Process recreation without Bundle restores authenticated-shell context');

const onStopStart = source.indexOf('override fun onStop()');
const onStopEnd = source.indexOf('override fun onDestroy()', onStopStart);
assert(onStopStart >= 0 && onStopEnd > onStopStart, 'onStop block is present');
const onStop = source.slice(onStopStart, onStopEnd);
assert(onStop.includes('if (shouldStartBackgroundClock)'),
  'Internal activity/configuration transitions do not rewrite durable app background state');
assert(onStop.includes('!currentAuthToken.isNullOrBlank() && currentUserId > 0L'),
  'Only a live authenticated user can preserve the shell');
assert(onStop.includes('!webPageOwnsAuthentication()'),
  'Login and lock screens are excluded from protected-shell restoration');
assert(onStop.includes(`putBoolean(${key}, activeAuthenticatedShell)`),
  'The encrypted shell marker is updated atomically with the background transition');
assert(onStop.includes('KEY_SECURITY_BACKGROUND_AT_ELAPSED_REALTIME'),
  'Monotonic background timing is saved with the shell state');

const clearSessionStart = source.indexOf('private fun clearSessionState()');
const clearSessionEnd = source.indexOf('// ✅ دوال مساعدة داخل النشاط (safeEvaluateJs)', clearSessionStart);
assert(clearSessionStart >= 0 && clearSessionEnd > clearSessionStart, 'Session clear block is present');
assert(source.slice(clearSessionStart, clearSessionEnd).includes(`putBoolean(${key}, false)`),
  'Invalid sessions cannot restore the protected shell');
assert(source.includes('.remove(KEY_REQUIRE_LOGIN_ON_NEXT_LAUNCH)\n            .putBoolean(' + key + ', false)'),
  'Device reboot reset clears protected-shell restoration state');

console.log('Session restore regression PASS: encrypted protected-shell state survives process death, background age is retained, auth pages are excluded, and cleared sessions cannot restore it.');
