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


    function normalizeDate(value) {
        const text = String(value ?? '').trim();
        return /^\d{4}-\d{2}-\d{2}$/.test(text) ? text : '';
    }

    function normalizeId(value) {
        const text = String(value ?? '').trim();
        return /^\d+$/.test(text) && Number(text) > 0 ? text : '';
    }

    function normalizeReportFilters(input) {
        const source = input && typeof input === 'object' ? input : {};
        const out = {};
        const from = normalizeDate(source.from_date ?? source.start_date);
        const to = normalizeDate(source.to_date ?? source.end_date);
        if (from) { out.from_date = from; out.start_date = from; }
        if (to) { out.to_date = to; out.end_date = to; }
        if (source.station_id != null && normalizeId(source.station_id)) out.station_id = normalizeId(source.station_id);
        if (source.branch_id != null && normalizeId(source.branch_id)) out.branch_id = normalizeId(source.branch_id);
        if (source.warehouse_id != null && normalizeId(source.warehouse_id)) out.warehouse_id = normalizeId(source.warehouse_id);
        if (source.category_id != null && normalizeId(source.category_id)) out.category_id = normalizeId(source.category_id);
        if (source.product_id != null && normalizeId(source.product_id)) out.product_id = normalizeId(source.product_id);
        if (source.customer_id != null && normalizeId(source.customer_id)) out.customer_id = normalizeId(source.customer_id);
        if (source.fuel_type_id != null && normalizeId(source.fuel_type_id)) out.fuel_type_id = normalizeId(source.fuel_type_id);
        if (source.tank_id != null && normalizeId(source.tank_id)) out.tank_id = normalizeId(source.tank_id);
        if (source.pump_id != null && normalizeId(source.pump_id)) out.pump_id = normalizeId(source.pump_id);
        if (source.shift_id != null && normalizeId(source.shift_id)) out.shift_id = normalizeId(source.shift_id);
        ['payment_method','sale_type','report_type','entry_type','status','search','query','sort_by','sort_direction'].forEach(function (key) {
            const value = String(source[key] ?? '').trim();
            if (value) out[key] = value;
        });
        out.return_all = source.return_all !== false;
        return out;
    }

    function validateReportFilters(filters, options) {
        const f = normalizeReportFilters(filters);
        const opts = options || {};
        if (opts.requireDateRange !== false && (!f.from_date || !f.to_date)) {
            throw new Error('يجب تحديد تاريخ البداية والنهاية للتقرير');
        }
        if (f.from_date && f.to_date && f.from_date > f.to_date) {
            throw new Error('تاريخ البداية يجب أن يسبق أو يساوي تاريخ النهاية');
        }
        return f;
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
        setState,
        normalizeReportFilters,
        validateReportFilters
    });

    document.addEventListener('DOMContentLoaded', function () {
        renderStatus();
        global.addEventListener('pageshow', renderStatus);
    });
}(window));
