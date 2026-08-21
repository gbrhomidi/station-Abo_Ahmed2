/*
 * Reports Runtime Contract v1
 *
 * This helper does not create, cache, or simulate report data. It only exposes
 * the bridge contract state so every reports screen can distinguish verified,
 * unavailable, and incomplete data paths.
 */
(function (global) {
    'use strict';

    function parseBridgeValue(value) {
        if (typeof value !== 'string') return value;
        try { return JSON.parse(value); } catch (_) { return value; }
    }

    function unwrap(value) {
        const parsed = parseBridgeValue(value);
        if (parsed && typeof parsed === 'object' && parsed.dataResponse !== undefined) {
            return unwrap(parsed.dataResponse);
        }
        return parsed;
    }

    function getMethods() {
        const meta = document.querySelector('meta[name="reports-bridge-methods"]');
        return meta ? (meta.content || '').split(',').map(item => item.trim()).filter(Boolean) : [];
    }

    function getStatus() {
        const methods = getMethods();
        const bridge = global.AndroidInterface;
        if (!bridge) return { state: 'unavailable', methods, missing: methods };
        const missing = methods.filter(method => typeof bridge[method] !== 'function');
        return { state: missing.length ? 'incomplete' : 'verified', methods, missing };
    }

    function renderStatus() {
        const target = document.getElementById('reportsDataSource');
        if (!target) return;
        const status = getStatus();
        target.dataset.state = status.state;
        target.classList.remove('is-verified', 'is-incomplete', 'is-unavailable');
        target.classList.add(`is-${status.state}`);
        const icon = status.state === 'verified' ? 'fa-database' : status.state === 'incomplete' ? 'fa-triangle-exclamation' : 'fa-plug-circle-xmark';
        const label = status.state === 'verified'
            ? 'مصدر البيانات: SQLite عبر Android Bridge'
            : status.state === 'incomplete'
                ? `مسار بيانات غير مكتمل: ${status.missing.join('، ')}`
                : 'مصدر البيانات غير متاح: Android Bridge';
        target.innerHTML = `<i class="fas ${icon}" aria-hidden="true"></i><span>${label}</span>`;
        target.setAttribute('aria-live', 'polite');
        target.title = status.methods.length ? `العقد المطلوبة: ${status.methods.join('، ')}` : 'لم يتم تعريف عقد تقرير لهذه الشاشة';
    }

    function setState(state, message) {
        const target = document.getElementById('reportsDataSource');
        if (!target) return;
        target.dataset.state = state;
        if (message) target.querySelector('span').textContent = message;
    }

    global.ReportsRuntime = Object.freeze({
        parseBridgeValue,
        unwrap,
        getStatus,
        renderStatus,
        setState
    });

    document.addEventListener('DOMContentLoaded', function () {
        renderStatus();
        global.addEventListener('pageshow', renderStatus);
    });
}(window));
