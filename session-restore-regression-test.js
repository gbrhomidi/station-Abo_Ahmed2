const fs = require('fs');
const assert = require('assert');

const source = fs.readFileSync(
  'app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/MainActivity.kt',
  'utf8'
);
const policy = fs.readFileSync(
  'app/src/main/java/com/aistudio/dieselstationsms/kxmpzq/BackgroundReauthenticationPolicy.kt',
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
assert(onCreate.includes('processRestartedWithAuthenticatedShell = true'),
  'A cold process restart is distinguished from ordinary Activity recreation');
assert(onCreate.includes('.putBoolean(KEY_REQUIRE_LOGIN_ON_NEXT_LAUNCH, true)'),
  'A cold process restart forces the biometric-capable login route');
assert(source.includes('processRestartedWithAuthenticatedShell = processRestartedWithAuthenticatedShell'),
  'The cold process restart state reaches the startup reauthentication decision');

const onResumeStart = source.indexOf('override fun onResume()');
const onResumeEnd = source.indexOf('override fun onSaveInstanceState', onResumeStart);
assert(onResumeStart >= 0 && onResumeEnd > onResumeStart, 'onResume block is present');
const onResume = source.slice(onResumeStart, onResumeEnd);
assert(onResume.includes('BackgroundReauthenticationPolicy.shouldRequireReauthentication('),
  'Return-to-app reauthentication uses the monotonic background threshold');
assert(onResume.includes('if (backgroundDurationExceeded &&') &&
  onResume.includes('updateSecurityLockPending(true)'),
  'The background timeout must be reached before native security reauthentication is requested');
assert(policy.includes('const val REQUIRED_BACKGROUND_DURATION_MS: Long = 60_000L'),
  'The background biometric threshold is exactly one minute');
const reauthHelperStart = source.indexOf('private fun hasRememberedBiometricCredentials()');
const reauthHelperEnd = source.indexOf('private fun requiresSecurityReauthentication()', reauthHelperStart);
assert(reauthHelperStart >= 0 && reauthHelperEnd > reauthHelperStart,
  'Remember-Me biometric availability is checked in one helper');
const reauthHelper = source.slice(reauthHelperStart, reauthHelperEnd);
assert(reauthHelper.includes('remember_me') && reauthHelper.includes('saved_token') &&
  reauthHelper.includes('saved_user_id') && reauthHelper.includes('saved_username'),
  'Only complete Remember-Me state enables biometric reauthentication');
const nativeGateStart = source.indexOf('private fun requestSecurityReauthentication()');
const nativeGateEnd = source.indexOf('private fun showSecurityPinFallback()', nativeGateStart);
assert(nativeGateStart >= 0 && nativeGateEnd > nativeGateStart,
  'Native reauthentication gate is present');
assert(source.slice(nativeGateStart, nativeGateEnd).includes('hasRememberedBiometricCredentials()'),
  'Remember-Me reauthentication actually invokes biometric verification');
assert(onResume.includes('forceSecurityLogout("security_auto_logout", allowRememberMeBiometric = true)'),
  'An expired active session returns through Remember-Me biometric login instead of password-only fallback');
assert(!source.includes('biometricCancellationExpected'),
  'A global cancellation flag cannot let an old prompt callback affect a new prompt');
assert((source.match(/if \(promptGeneration != biometricPromptGeneration\) return/g) || []).length >= 4,
  'Success, failure, error, and construction callbacks reject stale prompt generations');

const onDestroyStart = source.indexOf('override fun onDestroy()');
const onDestroyEnd = source.indexOf('// ============================================================', onDestroyStart);
assert(onDestroyStart >= 0 && onDestroyEnd > onDestroyStart, 'onDestroy block is present');
const onDestroy = source.slice(onDestroyStart, onDestroyEnd);
assert(onDestroy.includes('handler.removeCallbacksAndMessages(null)'),
  'Activity handler callbacks are removed during teardown');
assert(onDestroy.includes('biometricPrompt?.cancelAuthentication()') &&
  onDestroy.includes('securityFallbackDialog?.dismiss()'),
  'BiometricPrompt and fallback dialog are released during teardown');

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
