/*
 * Report Context Persistence & Return Integrity v1
 *
 * This module persists the report state only. It never creates report data,
 * changes SQL filters, or substitutes cached rows for SQLite results.
 * It gives every report screen one canonical context envelope for:
 * KPI -> exception/group -> transaction -> operational document -> Back.
 */
(function (global) {
    'use strict';

    const VERSION = 1;
    const STORAGE_PREFIX = 'reports.context.v1.';
    const NAV_KEY = 'reports.navigation.v1';

    function parse(value, fallback) {
        if (value == null || value === '') return fallback;
        try { return JSON.parse(value); } catch (_) { return fallback; }
    }

    function clone(value) {
        return parse(JSON.stringify(value), value);
    }

    function screenKey() {
        return (global.location && global.location.pathname || 'reports').split('/').pop() || 'reports';
    }

    function dateScope() {
        const ids = [
            ['from_date', 'to_date'],
            ['filterDateFrom', 'filterDateTo'],
            ['reportFrom', 'reportTo'],
            ['dateFrom', 'dateTo'],
            ['startDate', 'endDate']
        ];
        for (const pair of ids) {
            const from = document.getElementById(pair[0]);
            const to = document.getElementById(pair[1]);
            if (from || to) return {
                from_date: from ? String(from.value || '') : '',
                to_date: to ? String(to.value || '') : ''
            };
        }
        return { from_date: '', to_date: '' };
    }

    function readControls() {
        const controls = {};
        document.querySelectorAll('input, select, textarea').forEach(function (el) {
            if (!el.id || el.type === 'button' || el.type === 'submit') return;
            if (el.type === 'checkbox' || el.type === 'radio') controls[el.id] = !!el.checked;
            else controls[el.id] = String(el.value == null ? '' : el.value);
        });
        return controls;
    }

    function stationId() {
        const candidates = ['stationId', 'station_id', 'currentStationId', 'filterStation', 'stationFilter'];
        for (const id of candidates) {
            const el = document.getElementById(id);
            if (el && Number(el.value) > 0) return Number(el.value);
        }
        const q = new URLSearchParams(global.location ? global.location.search : '');
        const queryStation = Number(q.get('station_id') || 0);
        if (queryStation > 0) return queryStation;
        if (global.AndroidInterface && typeof global.AndroidInterface.getCurrentStationIdForReports === 'function') {
            try {
                const bridgeStation = Number(global.AndroidInterface.getCurrentStationIdForReports() || 0);
                if (bridgeStation > 0) return bridgeStation;
            } catch (_) { /* station is re-verified by MainActivity during document open */ }
        }
        return 0;
    }

    function build(overrides) {
        const base = {
            version: VERSION,
            report_screen: screenKey(),
            report_url: global.location ? global.location.pathname : '',
            station_id: stationId(),
            date_scope: dateScope(),
            controls: readControls(),
            filters: {},
            grouping: '',
            metric: '',
            kpi: '',
            exception_type: '',
            group_key: '',
            source_trace: null,
            captured_at: new Date().toISOString()
        };
        const context = Object.assign(base, clone(overrides || {}));
        context.date_scope = Object.assign(base.date_scope, clone((overrides || {}).date_scope || {}));
        context.controls = Object.assign(base.controls, clone((overrides || {}).controls || {}));
        return context;
    }

    function key(context) {
        return STORAGE_PREFIX + String(context.report_screen || screenKey());
    }

    function save(context) {
        const normalized = build(context);
        sessionStorage.setItem(key(normalized), JSON.stringify(normalized));
        sessionStorage.setItem(NAV_KEY, JSON.stringify(normalized));
        return normalized;
    }

    function load(targetScreen) {
        const k = STORAGE_PREFIX + String(targetScreen || screenKey());
        const local = parse(sessionStorage.getItem(k), null);
        if (local && local.version === VERSION) return local;
        const nav = parse(sessionStorage.getItem(NAV_KEY), null);
        return nav && nav.version === VERSION && (!targetScreen || nav.report_screen === targetScreen) ? nav : null;
    }

    function restore(context) {
        if (!context) return false;
        const controls = context.controls || {};
        Object.keys(controls).forEach(function (id) {
            const el = document.getElementById(id);
            if (!el) return;
            if (el.type === 'checkbox' || el.type === 'radio') el.checked = !!controls[id];
            else el.value = controls[id] == null ? '' : controls[id];
        });
        return true;
    }

    function capture(overrides) {
        return save(build(overrides));
    }

    function navigate(url, overrides) {
        const context = capture(overrides);
        const destination = new URL(url, global.location.href);
        destination.searchParams.set('report_context', '1');
        destination.searchParams.set('report_screen', context.report_screen);
        sessionStorage.setItem(NAV_KEY, JSON.stringify(context));
        global.location.href = destination.toString();
    }

    function back(fallback) {
        const context = load();
        if (global.history && global.history.length > 1) {
            global.history.back();
            return;
        }
        global.location.href = fallback || (context && context.report_url) || 'reports.html';
    }

    function current() { return load(); }

    global.ReportContextPersistence = Object.freeze({
        VERSION,
        build,
        save,
        load,
        restore,
        capture,
        navigate,
        back,
        current
    });

    document.addEventListener('DOMContentLoaded', function () {
        const q = new URLSearchParams(global.location ? global.location.search : '');
        if (q.get('report_context') === '1') {
            const context = load(q.get('report_screen') || '');
            if (context) restore(context);
        }
        global.addEventListener('pagehide', function () { capture(); });
        global.addEventListener('pageshow', function () {
            if (q.get('report_context') === '1') {
                const context = load(q.get('report_screen') || '');
                if (context) restore(context);
            }
        });
    });
}(window));
