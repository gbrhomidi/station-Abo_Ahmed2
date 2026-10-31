'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

module.exports = async function loginAttemptClassificationTests() {
    const appRoot = path.resolve(__dirname, '../../..');
    const login = fs.readFileSync(path.join(appRoot, 'src/main/assets/screens/login.html'), 'utf8');
    const activity = fs.readFileSync(
        path.join(appRoot, 'src/main/java/com/aistudio/dieselstationsms/kxmpzq/MainActivity.kt'),
        'utf8'
    );

    const inlineScripts = [...login.matchAll(/<script(?:\s[^>]*)?>([\s\S]*?)<\/script>/gi)]
        .map(match => match[1])
        .filter(script => script.trim().length > 0);
    assert.ok(inlineScripts.length > 0, 'Login page must contain inline JavaScript.');
    inlineScripts.forEach((script, index) => {
        new vm.Script(script, { filename: 'login-auth:inline-script-' + (index + 1) });
    });

    assert.ok(
        login.includes("else if (isAuthenticationRejected(result)) {"),
        'Only an explicit authentication rejection may enter the attempt-counter branch.'
    );
    assert.ok(
        login.includes("code: startupState === 'failed' ? 'DATABASE_NOT_READY'"),
        'Native startup/database failures must carry DATABASE_NOT_READY.'
    );

    const submitStart = login.indexOf("loginForm.addEventListener('submit'");
    const submitEnd = login.indexOf('// إغلاق المودالات عند النقر خارجها', submitStart);
    assert.ok(submitStart >= 0 && submitEnd > submitStart, 'Login submit handler was not found.');
    const submitHandler = login.slice(submitStart, submitEnd);
    assert.equal(
        (submitHandler.match(/incrementLoginAttempt\(username\)/g) || []).length,
        1,
        'The submit handler must have exactly one guarded attempt-counter call.'
    );

    const classifierStart = login.indexOf('function isAuthenticationRejected(result) {');
    const classifierEnd = login.indexOf('\n        }', classifierStart);
    assert.ok(classifierStart >= 0 && classifierEnd > classifierStart, 'Authentication classifier is missing.');
    const classifierSource = login.slice(classifierStart, classifierEnd + 10);
    const classifierContext = vm.createContext({});
    new vm.Script(
        classifierSource + '\nthis.isAuthenticationRejected = isAuthenticationRejected;'
    ).runInContext(classifierContext);

    assert.equal(
        classifierContext.isAuthenticationRejected({ success: false, code: 'AUTHENTICATION_REJECTED' }),
        true,
        'Incorrect credentials must count as a failed attempt.'
    );
    for (const code of [
        'DATABASE_NOT_READY',
        'DATABASE_ERROR',
        'STARTUP_NOT_READY',
        'LOGIN_PROCESS_ERROR',
        'BRIDGE_EXCEPTION',
        'BRIDGE_MISSING'
    ]) {
        assert.equal(
            classifierContext.isAuthenticationRejected({ success: false, code: code }),
            false,
            code + ' must not consume a failed-login attempt.'
        );
    }
    assert.equal(
        classifierContext.isAuthenticationRejected({ success: false, error: 'unknown failure' }),
        false,
        'Unclassified bridge failures must not increment the counter.'
    );

    const loginBridgeStart = activity.indexOf(
        'fun login(username: String, password: String): String {'
    );
    const loginBridgeEnd = activity.indexOf('\n        @JavascriptInterface', loginBridgeStart);
    assert.ok(loginBridgeStart >= 0 && loginBridgeEnd > loginBridgeStart, 'Native login bridge was not found.');
    const nativeLogin = activity.slice(loginBridgeStart, loginBridgeEnd);
    assert.ok(nativeLogin.includes('"AUTHENTICATION_REJECTED"'), 'Native bridge must label incorrect credentials.');
    assert.ok(nativeLogin.includes('"DATABASE_NOT_READY"'), 'Native bridge must label unavailable SQLite.');
    assert.ok(
        nativeLogin.includes('isDatabaseAccessFailure(e)') && nativeLogin.includes('"DATABASE_ERROR"'),
        'Native SQLite failures must be identified separately from rejected credentials.'
    );
    assert.ok(
        nativeLogin.includes('"LOGIN_PROCESS_ERROR"'),
        'Unexpected login processing failures must not masquerade as incorrect credentials.'
    );
    assert.ok(
        !nativeLogin.includes('errorResponse("بيانات خاطئة")'),
        'The previous untyped wrong-credentials response must be removed.'
    );

    console.log(
        'Login-attempt classification tests passed: only AUTHENTICATION_REJECTED increments the counter; SQLite, startup, and bridge failures do not.'
    );
};
