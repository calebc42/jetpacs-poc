;;; jetpacs-dates.el --- Locale-stable date helpers -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later
;; Package-Requires: ((emacs "30.1"))

;;; Commentary:

;; Pure string/time utilities shared by companion apps.  Dates use the
;; ISO YYYY-MM-DD interchange form, day arithmetic is anchored at noon
;; to avoid DST boundary flips, and short month labels are deliberately
;; English so the same companion document is stable across host locales.

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

(provide 'jetpacs-dates)
;;; jetpacs-dates.el ends here
