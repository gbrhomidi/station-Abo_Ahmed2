(function () {
  'use strict';

  const STORAGE_KEY = 'finance_integrity_snapshot_v1';

  function params() {
    const url = new URL(window.location.href);
    return {
      from_date: url.searchParams.get('from_date') || url.searchParams.get('start_date') || '',
      to_date: url.searchParams.get('to_date') || url.searchParams.get('end_date') || ''
    };
  }

  function bridge() {
    return window.AndroidInterface && typeof window.AndroidInterface.getFinanceIntegritySnapshot === 'function'
      ? window.AndroidInterface
      : null;
  }

  function load() {
    const api = bridge();
    if (!api) return Promise.resolve(null);
    try {
      const response = api.getFinanceIntegritySnapshot(JSON.stringify(params()));
      const parsed = typeof response === 'string' ? JSON.parse(response) : response;
      const snapshot = parsed && parsed.data ? parsed.data : parsed;
      if (snapshot && typeof snapshot === 'object') {
        sessionStorage.setItem(STORAGE_KEY, JSON.stringify(snapshot));
        document.documentElement.dataset.financeReconciled = snapshot.is_reconciled ? '1' : '0';
        document.documentElement.dataset.financeEvidenceVerifiedAt = snapshot.verified_at || '';
      }
      return Promise.resolve(snapshot || null);
    } catch (error) {
      document.documentElement.dataset.financeReconciled = 'error';
      console.error('Finance integrity verification failed', error);
      return Promise.resolve(null);
    }
  }

  window.FinanceEvidence = Object.freeze({
    load,
    current: function () {
      try { return JSON.parse(sessionStorage.getItem(STORAGE_KEY) || 'null'); } catch (_) { return null; }
    },
    params
  });

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', load, { once: true });
  } else {
    load();
  }
})();
