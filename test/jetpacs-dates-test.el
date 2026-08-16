;;; jetpacs-dates-test.el --- ERT for pure date helpers -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later

(require 'ert)
(require 'jetpacs-dates)

(ert-deftest jetpacs-dates-helpers ()
  "Date arithmetic is noon-anchored, clamped, and locale-stable."
  (let ((decoded (decode-time (jetpacs-dates-encode "2026-08-13"))))
    (should (= (decoded-time-hour decoded) 12))
    (should (= (decoded-time-day decoded) 13))
    (should (= (decoded-time-month decoded) 8))
    (should (= (decoded-time-year decoded) 2026)))
  ;; Day/week shifts cross month and year boundaries.
  (should (equal (jetpacs-dates-shift "2026-08-13" 1 'day)
                 "2026-08-14"))
  (should (equal (jetpacs-dates-shift "2026-08-13" -1 'day)
                 "2026-08-12"))
  (should (equal (jetpacs-dates-shift "2026-01-31" 1 'day)
                 "2026-02-01"))
  (should (equal (jetpacs-dates-shift "2026-01-01" -1 'day)
                 "2025-12-31"))
  (should (equal (jetpacs-dates-shift "2026-12-31" 1 'day)
                 "2027-01-01"))
  (should (equal (jetpacs-dates-shift "2026-08-13" 1 'week)
                 "2026-08-20"))
  (should (equal (jetpacs-dates-shift "2026-08-13" 2 'week)
                 "2026-08-27"))
  ;; Month arithmetic clamps into the target month and walks years;
  ;; leap February keeps its 29th.
  (should (equal (jetpacs-dates-shift "2026-01-31" 1 'month)
                 "2026-02-28"))
  (should (equal (jetpacs-dates-shift "2024-01-31" 1 'month)
                 "2024-02-29"))
  (should (equal (jetpacs-dates-shift "2026-01-15" -1 'month)
                 "2025-12-15"))
  (should (equal (jetpacs-dates-shift "2026-12-15" 1 'month)
                 "2027-01-15"))
  (should (equal (jetpacs-dates-shift "2026-11-01" 3 'month)
                 "2027-02-01"))
  ;; Format rides format-time-string; the C locale pins the weekday.
  (should (equal (jetpacs-dates-format "2026-08-13" "%Y/%m/%d")
                 "2026/08/13"))
  (let ((system-time-locale "C"))
    (should (equal (jetpacs-dates-format "2000-01-01" "%a %Y")
                   "Sat 2000")))
  (should (equal (jetpacs-dates-month-abbrev 1) "Jan"))
  (should (equal (jetpacs-dates-month-abbrev 12) "Dec"))
  (should-not (jetpacs-dates-month-abbrev 0))
  (should-not (jetpacs-dates-month-abbrev 13))
  (should-not (jetpacs-dates-month-abbrev "3")))

(provide 'jetpacs-dates-test)
;;; jetpacs-dates-test.el ends here
