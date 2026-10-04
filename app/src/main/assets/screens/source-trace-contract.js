(function (global) {
    'use strict';
    const VERSION = 2;
    const REQUIRED = ['source_table', 'source_id', 'reference_code', 'station_id', 'document_type'];

    function dateScope(input) {
        const scope = input && input.date_scope ? input.date_scope : {};
        return {
            from_date: String(scope.from_date || input.from_date || '').trim(),
            to_date: String(scope.to_date || input.to_date || '').trim()
        };
    }

    function build(input) {
        const value = input || {};
        const scope = dateScope(value);
        const contract = {
            contract_version: VERSION,
            source_table: String(value.source_table || '').trim().toLowerCase(),
            source_id: Number(value.source_id || 0),
            reference_code: String(value.reference_code || '').trim(),
            station_id: Number(value.station_id || 0),
            date_scope: scope,
            document_type: String(value.document_type || '').trim().toLowerCase(),
            report_context: value.report_context || {}
        };
        if (!contract.source_table) throw new Error('Source Trace: source_table مطلوب');
        if (!(contract.source_id > 0)) throw new Error('Source Trace: source_id مطلوب');
        if (!contract.reference_code) throw new Error('Source Trace: reference_code مطلوب');
        if (!(contract.station_id > 0)) throw new Error('Source Trace: station_id مطلوب');
        if (!contract.document_type) throw new Error('Source Trace: document_type مطلوب');
        const date = /^\d{4}-\d{2}-\d{2}$/;
        if (scope.from_date && !date.test(scope.from_date)) throw new Error('Source Trace: from_date غير صالح');
        if (scope.to_date && !date.test(scope.to_date)) throw new Error('Source Trace: to_date غير صالح');
        if (scope.from_date && scope.to_date && scope.from_date > scope.to_date) throw new Error('Source Trace: date_scope غير صالح');
        return contract;
    }

    function open(contract) {
        const verifiedContract = build(contract);
        if (!global.AndroidInterface || typeof global.AndroidInterface.openReportOperationalDocument !== 'function') {
            throw new Error('Source Trace: البوابة المركزية غير متاحة');
        }
        const result = JSON.parse(global.AndroidInterface.openReportOperationalDocument(JSON.stringify(verifiedContract)) || '{}');
        if (!result.success) throw new Error(result.error || result.message || 'Source Trace: فشل التحقق');
        if (!result.verified || Number(result.contract_version) !== VERSION) throw new Error('Source Trace: عقد غير موثوق');
        if (result.source_table !== verifiedContract.source_table || Number(result.source_id) !== verifiedContract.source_id) {
            throw new Error('Source Trace: تغيرت هوية المصدر أثناء التحقق');
        }
        return result;
    }

    global.SourceTraceContract = Object.freeze({ VERSION, build, open });
})(window);

/* Operational entry guard: report-driven navigation must carry the complete contract. */
(function (global) {
    const idKeys = ['movement_id','sale_id','fuel_sales_id','refill_id','entry_id','stocktake_id'];
    const q = new URLSearchParams(global.location ? global.location.search : '');
    const hasOperationalId = idKeys.some(k => Number(q.get(k) || 0) > 0);
    if (!hasOperationalId) return;
    const required = ['source_table','source_id','reference_code','station_id','document_type'];
    const missing = required.some(k => !q.get(k));
    if (missing) {
        idKeys.forEach(k => q.delete(k));
        if (global.history && global.history.replaceState) {
            const query = q.toString();
            global.history.replaceState(null, '', global.location.pathname + (query ? '?' + query : ''));
        }
        global.__SOURCE_TRACE_REJECTED__ = true;
        global.__SOURCE_TRACE_REJECTION_REASON__ = 'رفض فتح المستند التشغيلي: العقد الكامل غير موجود';
    }
})(window);
