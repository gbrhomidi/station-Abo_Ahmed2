/* Evidence & Reconciliation Integrity runtime v1.
 * This layer never caches report rows and never substitutes UI state for SQLite.
 * It asks the centralized Android evidence bridge for the exact station/date source proof.
 */
(function (global) {
  'use strict';
  function bridge() {
    return global.AndroidInterface || global.androidBridge || null;
  }
  function get(reportType, fromDate, toDate, extra) {
    var b = bridge();
    if (!b || typeof b.getReportEvidence !== 'function') return null;
    var request = Object.assign({
      report_type: reportType,
      from_date: fromDate || '',
      to_date: toDate || ''
    }, extra || {});
    try {
      var raw = b.getReportEvidence(JSON.stringify(request));
      var parsed = typeof raw === 'string' ? JSON.parse(raw) : raw;
      return parsed && parsed.data ? parsed.data : parsed;
    } catch (e) {
      console.error('Report evidence bridge failed:', e);
      return null;
    }
  }
  function csvLines(evidence) {
    if (!evidence) return [];
    function q(v) { return '"' + String(v == null ? '' : v).replace(/"/g, '""') + '"'; }
    return [
      '',
      q('Evidence Contract Version') + ',' + q(evidence.contract_version || ''),
      q('Evidence Hash') + ',' + q(evidence.evidence_hash || ''),
      q('Source Table') + ',' + q(evidence.source_table || ''),
      q('Source Row Count') + ',' + q(evidence.source_row_count == null ? '' : evidence.source_row_count),
      q('Station ID') + ',' + q(evidence.station_id || ''),
      q('Date From') + ',' + q(evidence.date_scope && evidence.date_scope.from || ''),
      q('Date To') + ',' + q(evidence.date_scope && evidence.date_scope.to || ''),
      q('Reconciliation Rule') + ',' + q(evidence.reconciliation_rule || '')
    ];
  }
  global.ReportEvidenceRuntime = { get: get, csvLines: csvLines };
})(window);
