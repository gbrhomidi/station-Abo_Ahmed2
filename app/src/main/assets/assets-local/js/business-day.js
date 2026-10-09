(function (root) {
  'use strict';
  const pad = value => String(value).padStart(2, '0');

  function asLocalDate(value) {
    if (value instanceof Date) return value;
    if (typeof value === 'string') {
      const dateOnly = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value.trim());
      if (dateOnly) {
        return new Date(Number(dateOnly[1]), Number(dateOnly[2]) - 1, Number(dateOnly[3]));
      }
      // A timezone-less database/UI timestamp is a wall-clock value, not UTC.
      const localDateTime = /^(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2})(?::(\d{2})(?:\.(\d{1,3}))?)?$/.exec(value.trim());
      if (localDateTime) {
        return new Date(
          Number(localDateTime[1]), Number(localDateTime[2]) - 1, Number(localDateTime[3]),
          Number(localDateTime[4]), Number(localDateTime[5]), Number(localDateTime[6] || 0),
          Number((localDateTime[7] || '').padEnd(3, '0') || 0)
        );
      }
    }
    return value instanceof Date ? value : new Date(value);
  }

  function parts(value) {
    const date = asLocalDate(value);
    if (!(date instanceof Date) || Number.isNaN(date.getTime())) {
      throw new TypeError('Invalid business-day date');
    }
    return {
      year: String(date.getFullYear()).padStart(4, '0'),
      month: pad(date.getMonth() + 1),
      day: pad(date.getDate()),
      hour: pad(date.getHours()),
      minute: pad(date.getMinutes()),
      second: pad(date.getSeconds())
    };
  }

  root.businessDayDate = function (date = new Date()) {
    const p = parts(date);
    return `${p.year}-${p.month}-${p.day}`;
  };

  root.businessDayDateTime = function (date = new Date()) {
    const p = parts(date);
    return `${p.year}-${p.month}-${p.day} ${p.hour}:${p.minute}:${p.second}`;
  };

  root.businessDayTimeZone = Intl.DateTimeFormat().resolvedOptions().timeZone || 'local';

  root.createBusinessDayWatcher = function (onChange) {
    let previousDay = null;
    return function (date = new Date()) {
      const currentDay = root.businessDayDate(date);
      if (previousDay === null) {
        previousDay = currentDay;
        return false;
      }
      if (currentDay === previousDay) return false;
      const oldDay = previousDay;
      previousDay = currentDay;
      if (typeof onChange === 'function') onChange(currentDay, oldDay);
      return true;
    };
  };
})(window);
