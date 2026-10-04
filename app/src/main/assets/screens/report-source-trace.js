(function () {
  'use strict';
  const CONTRACT_VERSION = 1;
  function fail(message) { throw new Error(message || 'عقد المصدر غير صالح'); }
  function buildContract(input) {
    const q = input || {};
    const scope = q.date_scope || {};
    const c = {
      contract_version: CONTRACT_VERSION,
      source_table: String(q.source_table || '').trim().toLowerCase(),
      source_id: Number(q.source_id || 0),
      reference_code: String(q.reference_code || '').trim(),
      station_id: Number(q.station_id || 0),
      date_scope: {
        from_date: String(scope.from_date || q.from_date || '').trim(),
        to_date: String(scope.to_date || q.to_date || '').trim()
      },
      document_type: String(q.document_type || '').trim().toLowerCase()
    };
    if (!c.source_table) fail('source_table مطلوب');
    if (!(c.source_id > 0)) fail('source_id مطلوب');
    if (!c.reference_code) fail('reference_code مطلوب');
    if (!(c.station_id > 0)) fail('station_id مطلوب');
    if (!c.document_type) fail('document_type مطلوب');
    const date = /^\d{4}-\d{2}-\d{2}$/;
    if (c.date_scope.from_date && !date.test(c.date_scope.from_date)) fail('date_scope.from_date غير صالح');
    if (c.date_scope.to_date && !date.test(c.date_scope.to_date)) fail('date_scope.to_date غير صالح');
    if (c.date_scope.from_date && c.date_scope.to_date && c.date_scope.from_date > c.date_scope.to_date) fail('date_scope غير صالح');
    return c;
  }
  function openVerified(input) {
    if (!window.AndroidInterface || typeof AndroidInterface.openReportOperationalDocument !== 'function') fail('جسر Source Trace غير متاح');
    const contract = buildContract(input);
    const raw = AndroidInterface.openReportOperationalDocument(JSON.stringify(contract));
    const result = JSON.parse(raw || '{}');
    if (!result.success || !result.verified) fail(result.error || result.message || 'فشل اعتماد مصدر المستند');
    if (Number(result.contract_version) !== CONTRACT_VERSION) fail('إصدار عقد المصدر غير متوافق');
    const p = result.params || contract;
    if (result.source_table !== contract.source_table || Number(result.source_id) <= 0) fail('المصدر المعتمد غير متطابق');
    const query = Object.keys(p).map(k => encodeURIComponent(k) + '=' + encodeURIComponent(p[k] == null ? '' : p[k])).join('&');
    window.location.href = result.screen + '?' + query;
  }
  window.SourceTraceContract = Object.freeze({ VERSION: CONTRACT_VERSION, build: buildContract, open: openVerified });
})();
