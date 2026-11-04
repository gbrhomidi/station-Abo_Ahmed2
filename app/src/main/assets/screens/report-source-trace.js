(function (global) {
  'use strict';

  const allowedScreens = new Set([
    'inventory-movements.html', 'sales-log.html', 'fuel-sales.html', 'fuel-supplies.html',
    'journal-entries.html', 'payments.html', 'receipts.html', 'expenses.html',
    'employee-payments.html', 'cash-movements.html', 'cash-deposits.html',
    'banks-accounts.html', 'stocktake.html'
  ]);

  function destination(verified) {
    if (!verified || verified.success !== true || verified.verified !== true) {
      throw new Error('لم يتم اعتماد مصدر المستند من قاعدة البيانات.');
    }
    const screen = String(verified.operational_screen || '').trim();
    if (!allowedScreens.has(screen)) throw new Error('شاشة المستند المعتمدة غير مسموح بها.');

    const params = new URLSearchParams();
    params.set('contract_version', String(verified.contract_version || ''));
    params.set('source_table', String(verified.source_table || ''));
    params.set('source_id', String(verified.source_id || ''));
    params.set('reference_code', String(verified.reference_code || ''));
    params.set('station_id', String(verified.station_id || ''));
    params.set('document_type', String(verified.document_type || ''));
    params.set('date_scope', JSON.stringify(verified.date_scope || {}));
    params.set('report_context', JSON.stringify(verified.report_context || {}));
    return screen + '?' + params.toString();
  }

  global.ReportSourceTracePage = Object.freeze({ destination });
}(window));
