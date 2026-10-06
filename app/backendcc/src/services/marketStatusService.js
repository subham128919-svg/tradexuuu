// marketStatusService.js
// Determines NSE market session state: PRE_OPEN, OPEN, CLOSED.
// This is the single source of truth every other service consults —
// it decides whether to poll Kite live or serve cache-only.
//
// NSE regular trading hours: Mon-Fri, 09:15-15:30 IST. Pre-open: 09:00-09:15.

const PRE_OPEN_MIN = 9  * 60 + 0;
const OPEN_MIN      = 9  * 60 + 15;
const CLOSE_MIN      = 15 * 60 + 30;

// Fixed-date national holidays -- NSE is always closed on these, safe to
// hardcode (they don't move year to year).
const FIXED_HOLIDAYS_MMDD = ['01-26', '08-15', '10-02']; // Republic Day, Independence Day, Gandhi Jayanti

// Festival-based holidays (Diwali, Holi, Eid, etc.) move every year.
// Update this list annually from NSE's published holiday calendar:
// https://www.nseindia.com/resources/exchange-communication-holidays
const VARIABLE_HOLIDAYS_YYYYMMDD = [
  // '2026-03-06',  // example -- replace with actual dates from NSE calendar
];

function nowIST() {
  return new Date(new Date().toLocaleString('en-US', { timeZone: 'Asia/Kolkata' }));
}

function isHoliday(date) {
  const mmdd     = String(date.getMonth() + 1).padStart(2, '0') + '-' + String(date.getDate()).padStart(2, '0');
  const yyyymmdd = date.getFullYear() + '-' + mmdd;
  return FIXED_HOLIDAYS_MMDD.includes(mmdd) || VARIABLE_HOLIDAYS_YYYYMMDD.includes(yyyymmdd);
}

// Returns: { session: 'PRE_OPEN'|'OPEN'|'CLOSED', isOpen: boolean,
//            reason?: 'weekend'|'holiday', asOf: ISOString }
function getMarketStatus() {
  const ist       = nowIST();
  const day       = ist.getDay(); // 0=Sun, 6=Sat
  const mins      = ist.getHours() * 60 + ist.getMinutes();
  const isWeekend = day === 0 || day === 6;
  const holiday   = isHoliday(ist);

  if (isWeekend || holiday) {
    return { session: 'CLOSED', isOpen: false, reason: isWeekend ? 'weekend' : 'holiday', asOf: ist.toISOString() };
  }
  if (mins >= PRE_OPEN_MIN && mins < OPEN_MIN) {
    return { session: 'PRE_OPEN', isOpen: false, asOf: ist.toISOString() };
  }
  if (mins >= OPEN_MIN && mins < CLOSE_MIN) {
    return { session: 'OPEN', isOpen: true, asOf: ist.toISOString() };
  }
  return { session: 'CLOSED', isOpen: false, asOf: ist.toISOString() };
}

module.exports = { getMarketStatus, isHoliday, nowIST };
