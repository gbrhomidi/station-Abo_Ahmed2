(function (root) {
    'use strict';

    var ISO_DATE = /^([0-9]{4})-([0-9]{2})-([0-9]{2})$/;
    var ISO_TIMESTAMP = /^([0-9]{4}-[0-9]{2}-[0-9]{2})[ T]([0-9]{2}):([0-9]{2}):([0-9]{2})(?:\.([0-9]{1,9}))?(Z|([+-])([0-9]{2}):([0-9]{2}))?$/;

    function parts(value) {
        if (typeof value !== 'string') return null;
        var match = ISO_DATE.exec(value);
        if (!match) return null;
        var year = Number(match[1]);
        var month = Number(match[2]);
        var day = Number(match[3]);
        if (year < 1 || year > 9999 || month < 1 || month > 12) return null;
        var leap = year % 400 === 0 || (year % 4 === 0 && year % 100 !== 0);
        var monthDays = [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];
        if (day < 1 || day > monthDays[month - 1]) return null;
        return { year: year, month: month, day: day };
    }

    function isValidIsoDate(value) {
        return parts(value) !== null;
    }

    function isValidOptionalDate(value) {
        return value === null || value === undefined || value === '' || isValidIsoDate(value);
    }

    function isValidDateRange(from, to) {
        if (!isValidOptionalDate(from) || !isValidOptionalDate(to)) return false;
        return !(from && to && from > to);
    }

    function datePart(value, allowSqlTimestamp) {
        if (isValidIsoDate(value)) return value;
        if (!allowSqlTimestamp || typeof value !== 'string') return null;
        var match = ISO_TIMESTAMP.exec(value);
        if (!match || !isValidIsoDate(match[1])) return null;
        var hour = Number(match[2]);
        var minute = Number(match[3]);
        var second = Number(match[4]);
        if (hour > 23 || minute > 59 || second > 59) return null;
        if (match[6] && match[6] !== 'Z' && (Number(match[8]) > 23 || Number(match[9]) > 59)) return null;
        return match[1];
    }

    function utcMillis(value) {
        var date = parts(value);
        if (!date) return null;
        var result = new Date(0);
        result.setUTCHours(0, 0, 0, 0);
        result.setUTCFullYear(date.year, date.month - 1, date.day);
        return result.getTime();
    }

    function calendarDayDifference(from, to) {
        if (!isValidIsoDate(from) || !isValidIsoDate(to)) return null;
        return Math.round((utcMillis(to) - utcMillis(from)) / 86400000);
    }

    var api = Object.freeze({
        isValidIsoDate: isValidIsoDate,
        isValidOptionalDate: isValidOptionalDate,
        isValidDateRange: isValidDateRange,
        datePart: datePart,
        calendarDayDifference: calendarDayDifference
    });

    root.IsoCivilDate = api;
    if (typeof module !== 'undefined' && module.exports) module.exports = api;
})(typeof globalThis !== 'undefined' ? globalThis : this);
