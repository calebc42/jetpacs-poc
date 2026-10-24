;;; jetpacs-dates.el --- Locale-stable date helpers -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later
;; Package-Requires: ((emacs "30.1"))

;;; Commentary:

;; Pure string/time utilities shared by companion apps.  Dates use the
;; ISO YYYY-MM-DD interchange form, day arithmetic is anchored at noon
;; to avoid DST boundary flips, and short month labels are deliberately
;; English so the same companion document is stable across host locales.
;;
;; SPEC 20.1.2 (#197): the Companion reports its locale advice — a BCP 47
;; tag, the clock cycle `h12'/`h23' and an IANA time zone — and Emacs
;; formats for it.  The helpers below take that advice as explicit
;; arguments so they stay pure; `jetpacs-device-time-zone' and
;; `jetpacs-device-hour-cycle' in jetpacs-surfaces.el read the live
;; client.  Nothing here consults the host locale: without advice the
;; clock is 24-hour and the zone is Emacs's own, which is what every
;; existing label already showed.

;;; Code:

(require 'calendar)

(defconst jetpacs-dates--month-abbrevs
  ["Jan" "Feb" "Mar" "Apr" "May" "Jun"
   "Jul" "Aug" "Sep" "Oct" "Nov" "Dec"]
  "Short English month labels, independent of the host locale.")

(defun jetpacs-dates-month-abbrev (n)
  "Return the English abbreviation for month N, or nil outside 1..12."
  (and (integerp n) (>= n 1) (<= n 12)
       (aref jetpacs-dates--month-abbrevs (1- n))))

(defconst jetpacs-dates--month-names
  ["January" "February" "March" "April" "May" "June"
   "July" "August" "September" "October" "November" "December"]
  "Full English month names, independent of the host locale.")

(defconst jetpacs-dates--weekdays
  ["Sunday" "Monday" "Tuesday" "Wednesday" "Thursday" "Friday" "Saturday"]
  "English weekday names from Sunday, as `calendar-day-of-week' counts.")

(defun jetpacs-dates-month-name (n)
  "Return the full English name of month N, or nil outside 1..12."
  (and (integerp n) (>= n 1) (<= n 12)
       (aref jetpacs-dates--month-names (1- n))))

(defun jetpacs-dates-parts (date)
  "Return ISO DATE as the calendar's (MONTH DAY YEAR), or nil.
Only the YYYY-MM-DD shape is checked; `jetpacs-dates-valid-p' also
checks that the day exists."
  (when (and (stringp date)
             (string-match
              "\\`\\([0-9]\\{4\\}\\)-\\([0-9]\\{2\\}\\)-\\([0-9]\\{2\\}\\)\\'"
              date))
    (list (string-to-number (match-string 2 date))
          (string-to-number (match-string 3 date))
          (string-to-number (match-string 1 date)))))

(defun jetpacs-dates-valid-p (date)
  "Return non-nil when DATE is a real ISO \"YYYY-MM-DD\" calendar date."
  (when-let* ((parts (jetpacs-dates-parts date)))
    (calendar-date-is-valid-p parts)))

(defun jetpacs-dates-delta (date today)
  "Return ISO DATE's signed day offset from ISO TODAY, or nil.
Nil when either is not a valid date."
  (and (jetpacs-dates-valid-p date)
       (jetpacs-dates-valid-p today)
       (- (calendar-absolute-from-gregorian (jetpacs-dates-parts date))
          (calendar-absolute-from-gregorian (jetpacs-dates-parts today)))))

(defun jetpacs-dates-weekday (date &optional short)
  "Return valid ISO DATE's English weekday name, or nil.
SHORT returns the three-letter form, such as \"Mon\"."
  (when (jetpacs-dates-valid-p date)
    (let ((name (aref jetpacs-dates--weekdays
                      (calendar-day-of-week (jetpacs-dates-parts date)))))
      (if short (substring name 0 3) name))))

(defun jetpacs-dates-encode (date)
  "Encode noon on DATE, an ISO \"YYYY-MM-DD\" string.
Noon avoids DST date flips.  Positional parsing leaves match data
untouched, so this is safe in a regexp replacement function."
  (encode-time 0 0 12
               (string-to-number (substring date 8 10))
               (string-to-number (substring date 5 7))
               (string-to-number (substring date 0 4))))

(defun jetpacs-dates-shift (date n unit)
  "Shift ISO DATE by N UNITs, where UNIT is `day', `week', or `month'.
Month arithmetic clamps the day into the target month, so Jan 31 plus
one month is the last day of February."
  (let ((y (string-to-number (substring date 0 4)))
        (m (string-to-number (substring date 5 7)))
        (d (string-to-number (substring date 8 10))))
    (if (eq unit 'month)
        (let* ((total (+ (* 12 y) (1- m) n))
               (ny (/ total 12))
               (nm (1+ (% total 12))))
          (format "%04d-%02d-%02d" ny nm
                  (min d (calendar-last-day-of-month nm ny))))
      (let ((days (* n (if (eq unit 'week) 7 1))))
        (format-time-string "%Y-%m-%d"
                            (time-add (jetpacs-dates-encode date)
                                      (* days 86400)))))))

(defun jetpacs-dates-format (date fmt)
  "Render ISO DATE through `format-time-string' format FMT."
  (format-time-string fmt (jetpacs-dates-encode date)))

(defun jetpacs-dates-today (&optional zone now)
  "The civil date \"YYYY-MM-DD\" in ZONE, the device's day (SPEC 20.1.2).
ZONE is an IANA time-zone identifier from the Companion's locale
advice, or nil for Emacs's own zone.  NOW defaults to the current time.
A desktop Emacs and a phone in another zone disagree about today for
several hours a day; a builder that shows the device its own today
passes the advice through this function."
  (format-time-string "%Y-%m-%d" now (jetpacs-dates--zone zone)))

(defun jetpacs-dates-format-clock (time &optional hour-cycle)
  "Render TIME, an \"HH:MM\" string, for HOUR-CYCLE (SPEC 20.1.2).
HOUR-CYCLE is \"h12\" or \"h23\" from the Companion's locale advice;
nil and every other value mean 24-hour, so a label is unchanged until
the device says otherwise.  Under \"h12\" midnight is 12:MM AM and
noon 12:MM PM.  A TIME that is not HH:MM is returned as given."
  (if (and (equal hour-cycle "h12")
           (stringp time)
           (string-match "\\`\\([0-9][0-9]?\\):\\([0-5][0-9]\\)\\'" time))
      (let* ((hour (string-to-number (match-string 1 time)))
             (minute (match-string 2 time)))
        (if (> hour 23)
            time
          (format "%d:%s %s"
                  (let ((h (% hour 12))) (if (zerop h) 12 h))
                  minute
                  (if (< hour 12) "AM" "PM"))))
    time))

(defun jetpacs-dates--zone (zone)
  "The ZONE argument `format-time-string' accepts for IANA ZONE, or nil.
A non-empty string is passed as a TZ rule; anything else selects the
local zone, so unknown advice degrades to today's behavior."
  (and (stringp zone) (not (string-empty-p zone)) zone))

(provide 'jetpacs-dates)
;;; jetpacs-dates.el ends here
