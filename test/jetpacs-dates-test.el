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


(ert-deftest jetpacs-dates-device-advice ()
  "SPEC 20.1.2 (#197): today follows the advised zone and clocks follow
the advised cycle; without advice both are unchanged."
  ;; 2026-10-06 23:30 UTC is already the 7th in Tokyo and still the 6th
  ;; in Denver; nil keeps Emacs's own zone, whatever it is.
  (let ((now (encode-time '(0 30 23 6 10 2026 nil nil t))))
    (should (equal (jetpacs-dates-today "Asia/Tokyo" now) "2026-10-07"))
    (should (equal (jetpacs-dates-today "America/Denver" now) "2026-10-06"))
    (should (equal (jetpacs-dates-today "UTC" now) "2026-10-06"))
    (should (string-match-p "\\`[0-9]\\{4\\}-[0-9][0-9]-[0-9][0-9]\\'"
                            (jetpacs-dates-today nil now)))
    (should (string-match-p "\\`[0-9]\\{4\\}-[0-9][0-9]-[0-9][0-9]\\'"
                            (jetpacs-dates-today "" now))))
  ;; h12 renders AM/PM with 12 for midnight and noon; h23, nil and junk
  ;; leave the label alone; malformed times pass through.
  (should (equal (jetpacs-dates-format-clock "00:05" "h12") "12:05 AM"))
  (should (equal (jetpacs-dates-format-clock "09:30" "h12") "9:30 AM"))
  (should (equal (jetpacs-dates-format-clock "12:00" "h12") "12:00 PM"))
  (should (equal (jetpacs-dates-format-clock "15:07" "h12") "3:07 PM"))
  (should (equal (jetpacs-dates-format-clock "23:59" "h12") "11:59 PM"))
  (should (equal (jetpacs-dates-format-clock "15:07" "h23") "15:07"))
  (should (equal (jetpacs-dates-format-clock "15:07" nil) "15:07"))
  (should (equal (jetpacs-dates-format-clock "15:07" "h99") "15:07"))
  (should (equal (jetpacs-dates-format-clock "25:00" "h12") "25:00"))
  (should (equal (jetpacs-dates-format-clock "noon" "h12") "noon"))
  (should (equal (jetpacs-dates-format-clock nil "h12") nil)))

(ert-deftest jetpacs-dates-calendar-helpers ()
  "Validity, day offsets, weekdays and month names, all locale-free."
  (should (equal (jetpacs-dates-parts "2026-10-24") '(10 24 2026)))
  ;; The shape check alone admits a day that does not exist.
  (should (equal (jetpacs-dates-parts "2026-02-30") '(2 30 2026)))
  (should-not (jetpacs-dates-parts "2026-1-24"))
  (should-not (jetpacs-dates-parts "2026-10-24x"))
  (should-not (jetpacs-dates-parts nil))
  (should (jetpacs-dates-valid-p "2024-02-29"))
  (should-not (jetpacs-dates-valid-p "2026-02-29"))
  (should-not (jetpacs-dates-valid-p "2026-13-01"))
  (should (equal (jetpacs-dates-delta "2026-10-25" "2026-10-24") 1))
  (should (equal (jetpacs-dates-delta "2025-10-24" "2026-10-24") -365))
  ;; Across the end of daylight saving time the count stays whole.
  (should (equal (jetpacs-dates-delta "2026-11-02" "2026-10-31") 2))
  (should-not (jetpacs-dates-delta "2026-02-30" "2026-10-24"))
  (should-not (jetpacs-dates-delta "2026-10-24" nil))
  (should (equal (jetpacs-dates-weekday "2026-10-24") "Saturday"))
  (should (equal (jetpacs-dates-weekday "2026-10-25" t) "Sun"))
  (should-not (jetpacs-dates-weekday "2026-02-30"))
  (should (equal (jetpacs-dates-month-name 1) "January"))
  (should (equal (jetpacs-dates-month-name 12) "December"))
  (should-not (jetpacs-dates-month-name 0))
  (should-not (jetpacs-dates-month-name "3")))

(provide 'jetpacs-dates-test)
;;; jetpacs-dates-test.el ends here
