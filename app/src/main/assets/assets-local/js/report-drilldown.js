(function(){
  'use strict';
  window.openReportSourceDrilldown = function(source, payload, filters){
    var data = Object.assign({}, filters || {}, payload || {}, {source: source, level: (payload && payload.level) || 'document'});
    try { sessionStorage.setItem('reportsSourceDrilldown', JSON.stringify(data)); } catch(e) { console.warn(e); }
    window.location.href = 'reports-drilldown.html';
  };
  window.reportDrilldownFilters = function(extra){
    var f = Object.assign({}, extra || {});
    var from = document.querySelector('[name="from_date"],#fromDate,#filterDateFrom,#startDate');
    var to = document.querySelector('[name="to_date"],#toDate,#filterDateTo,#endDate');
    if(from && from.value) f.from_date=from.value;
    if(to && to.value) f.to_date=to.value;
    return f;
  };
})();
