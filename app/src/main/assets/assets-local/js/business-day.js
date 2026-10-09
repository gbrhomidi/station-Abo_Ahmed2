(function (root) {
  'use strict';
  const TIME_ZONE = 'Asia/Riyadh';

  function parts(date) {
    const values = new Intl.DateTimeFormat('en-US', {
      timeZone: TIME_ZONE,
      year: 'numeric', month: '2-digit', day: '2-digit',
      hour: '2-digit', minute: '2-digit', second: '2-digit',
      hourCycle: 'h23'
    }).formatToParts(date instanceof Date ? date : new Date(date));
    return values.reduce((out, item) => { out[item.type] = item.value; return out; }, {});
  }

  root.businessDayDate = function (date = new Date()) {
    const p = parts(date);
    return `${p.year}-${p.month}-${p.day}`;
  };

  root.businessDayDateTime = function (date = new Date()) {
    const p = parts(date);
    return `${p.year}-${p.month}-${p.day} ${p.hour}:${p.minute}:${p.second}`;
  };

  root.businessDayTimeZone = TIME_ZONE;
})(window);
