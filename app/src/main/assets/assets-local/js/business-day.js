(function (root) {
  'use strict';
  const DEFAULT_TIME_ZONE = 'Asia/Aden';
  const pad = value => String(value).padStart(2, '0');

  function wallClockParts(value) {
    if (typeof value !== 'string') return null;
    const text = value.trim();
    let match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(text);
    if (!match) {
      match = /^(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2})(?::(\d{2})(?:\.(\d{1,9}))?)?$/.exec(text);
    }
    if (!match) return null;
    const hasTime = text.length > 10;
    const valueParts = {
      year: +match[1], month: +match[2], day: +match[3],
      hour: hasTime ? +match[4] : 0,
      minute: hasTime ? +match[5] : 0,
      second: hasTime ? +(match[6] || 0) : 0
    };
    if (valueParts.year < 1 || valueParts.year > 9999 ||
        valueParts.month < 1 || valueParts.month > 12 ||
        valueParts.day < 1 || valueParts.day > 31 ||
        valueParts.hour < 0 || valueParts.hour > 23 ||
        valueParts.minute < 0 || valueParts.minute > 59 ||
        valueParts.second < 0 || valueParts.second > 59) {
      throw new TypeError('Invalid business-day date');
    }
    const validationDate = new Date(0);
    validationDate.setUTCFullYear(valueParts.year, valueParts.month - 1, valueParts.day);
    validationDate.setUTCHours(valueParts.hour, valueParts.minute, valueParts.second, 0);
    if (validationDate.getUTCFullYear() !== valueParts.year ||
        validationDate.getUTCMonth() + 1 !== valueParts.month ||
        validationDate.getUTCDate() !== valueParts.day) {
      throw new TypeError('Invalid business-day date');
    }
    return valueParts;
  }

  function parts(value, timeZone) {
    const wall = wallClockParts(value);
    if (wall) return wall; // Date-only and zone-less SQLite values are station wall-clock values.
    const date = value instanceof Date ? value : new Date(value);
    if (!(date instanceof Date) || Number.isNaN(date.getTime())) throw new TypeError('Invalid business-day date');
    const values = {};
    new Intl.DateTimeFormat('en-CA', {
      timeZone, year:'numeric', month:'2-digit', day:'2-digit',
      hour:'2-digit', minute:'2-digit', second:'2-digit', hourCycle:'h23'
    }).formatToParts(date).forEach(part => { if (part.type !== 'literal') values[part.type] = Number(part.value); });
    return { year:values.year, month:values.month, day:values.day, hour:values.hour, minute:values.minute, second:values.second };
  }

  function dateText(p) { return String(p.year).padStart(4,'0') + '-' + pad(p.month) + '-' + pad(p.day); }
  root.DEFAULT_BUSINESS_TIME_ZONE = DEFAULT_TIME_ZONE;
  root.businessDayDate = function (date = new Date(), timeZone = DEFAULT_TIME_ZONE) {
    return dateText(parts(date, timeZone));
  };
  root.businessDayTime = function (date = new Date(), timeZone = DEFAULT_TIME_ZONE) {
    const p = parts(date, timeZone);
    return pad(p.hour) + ':' + pad(p.minute) + ':' + pad(p.second);
  };
  root.businessDayDateTime = function (date = new Date(), timeZone = DEFAULT_TIME_ZONE) {
    const p = parts(date, timeZone);
    return dateText(p) + ' ' + pad(p.hour) + ':' + pad(p.minute) + ':' + pad(p.second);
  };
  root.businessDayTimeZone = DEFAULT_TIME_ZONE;
  root.createBusinessDayWatcher = function (onChange, timeZone = DEFAULT_TIME_ZONE) {
    let previousDay = null;
    return function (date = new Date()) {
      const currentDay = root.businessDayDate(date, timeZone);
      if (previousDay === null) { previousDay = currentDay; return false; }
      if (currentDay === previousDay) return false;
      const oldDay = previousDay;
      previousDay = currentDay;
      if (typeof onChange === 'function') onChange(currentDay, oldDay);
      return true;
    };
  };
})(window);
