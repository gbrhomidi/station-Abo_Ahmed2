(function (global) {
    'use strict';
    const VERSION = 2;
    const required = ['source_table', 'source_id', 'reference_code', 'station_id', 'document_type'];
    function build(input) {
        const value = input || {};
        const scope = value.date_scope || {};
        const c = {
            contract_version: VERSION,
            source_table: String(value.source_table || '').trim().toLowerCase(),
            source_id: Number(value.source_id || 0),
            reference_code: String(value.reference_code || '').trim(),
            station_id: Number(value.station_id || 0),
            date_scope: { from_date: String(scope.from_date || value.from_date || '').trim(), to_date: String(scope.to_date || value.to_date || '').trim() },
            document_type: String(value.document_type || '').trim().toLowerCase(),
            report_context: value.report_context || {}
        };
        if (!c.source_table) throw new Error('Source Trace: source_table مطلوب');
        if (!(c.source_id > 0)) throw new Error('Source Trace: source_id مطلوب');
        if (!c.reference_code) throw new Error('Source Trace: reference_code مطلوب');
        if (!(c.station_id > 0)) throw new Error('Source Trace: station_id مطلوب');
        if (!c.document_type) throw new Error('Source Trace: document_type مطلوب');
        const date = /^\d{4}-\d{2}-\d{2}$/;
        if (c.date_scope.from_date && !date.test(c.date_scope.from_date)) throw new Error('Source Trace: from_date غير صالح');
        if (c.date_scope.to_date && !date.test(c.date_scope.to_date)) throw new Error('Source Trace: to_date غير صالح');
        if (c.date_scope.from_date && c.date_scope.to_date && c.date_scope.from_date > c.date_scope.to_date) throw new Error('Source Trace: date_scope غير صالح');
        return c;
    }
    function open(input) {
        const value = Object.assign({}, input || {});
        if (!value.report_context && global.ReportContextPersistence) value.report_context = global.ReportContextPersistence.current() || {};
        const c = build(value);
        if (!global.AndroidInterface || typeof global.AndroidInterface.openReportOperationalDocument !== 'function') throw new Error('Source Trace: البوابة المركزية غير متاحة');
        const result = JSON.parse(global.AndroidInterface.openReportOperationalDocument(JSON.stringify(c)) || '{}');
        if (!result.success || !result.verified) throw new Error(result.error || result.message || 'Source Trace: فشل التحقق');
        if (Number(result.contract_version) !== VERSION || result.source_table !== c.source_table || Number(result.source_id) !== c.source_id) throw new Error('Source Trace: هوية المصدر غير متطابقة');
        return result;
    }
    global.SourceTraceContract = Object.freeze({ VERSION, build, open, required });
}(window));

/* Reject operational deep-links that carry only a database ID. */
(function (global) {
    const idKeys = ['movement_id', 'sale_id', 'fuel_sales_id', 'refill_id', 'entry_id', 'stocktake_id'];
    const q = new URLSearchParams((global.location && global.location.search) || '');
    const hasId = idKeys.some(function (key) { return Number(q.get(key) || 0) > 0; });
    if (!hasId) return;
    const missing = required.some(function (key) { return !q.get(key); });
    if (!missing) return;
    idKeys.forEach(function (key) { q.delete(key); });
    if (global.history && global.history.replaceState && global.location) {
        const query = q.toString();
        global.history.replaceState(null, '', global.location.pathname + (query ? '?' + query : ''));
    }
    global.__SOURCE_TRACE_REJECTED__ = true;
    global.__SOURCE_TRACE_REJECTION_REASON__ = 'رفض فتح المستند التشغيلي بدون Source Trace Contract كامل';
}(window));
