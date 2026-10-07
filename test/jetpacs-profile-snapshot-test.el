;;; jetpacs-profile-snapshot-test.el --- The last-profile snapshot -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later

;;; Code:

(require 'ert)
(require 'jetpacs-profile-snapshot)

(defun jetpacs-profile-snapshot-test--client ()
  "A client carrying a welcome's negotiated facts, never connected."
  (ebp--make-client
   :profiles (list :app (list :node_types ["text" "column" "button"]
                              :builtins ["view.switch"]
                              :features ["data.template"]
                              :extensions ["jetpacs.components"]))
   :granted ["theme" "data.model"]
   :limits (list :max_nodes 500 :max_node_depth 32)
   :locale (list :tag "en-US" :hour_cycle "h23" :time_zone "America/Denver")))

(ert-deftest jetpacs-profile-snapshot-test-writes-canonical-json-and-reads-back ()
  "The snapshot is canonical JSON of the welcome facts plus a UTC stamp."
  (let* ((dir (make-temp-file "jetpacs-snapshot" t))
         (jetpacs-profile-snapshot-file (expand-file-name "state/last-profile.json" dir)))
    (unwind-protect
        (let ((written (jetpacs-profile-snapshot-write
                        (jetpacs-profile-snapshot-test--client))))
          (should (equal written jetpacs-profile-snapshot-file))
          (should (file-exists-p written))
          (let* ((text (with-temp-buffer (insert-file-contents written) (buffer-string)))
                 (snapshot (jetpacs-profile-snapshot-read)))
            ;; Keys sorted, compact, one line: the goldens' own formula.
            (should (string-prefix-p "{\"at\":\"" text))
            (should (string-suffix-p "}\n" text))
            (should (= 1 (length (split-string text "\n" t))))
            (should (equal (gethash "hour_cycle" (gethash "locale" snapshot)) "h23"))
            (should (equal (append (gethash "granted" snapshot) nil) '("theme" "data.model")))
            (should (equal (append (gethash "node_types"
                                            (gethash "app" (gethash "profiles" snapshot)))
                                   nil)
                           '("text" "column" "button")))
            (should (= (gethash "max_nodes" (gethash "limits" snapshot)) 500))
            (should (string-match-p "\\`[0-9]\\{4\\}-[0-9]\\{2\\}-[0-9]\\{2\\}T[0-9:]\\{8\\}Z\\'"
                                    (gethash "at" snapshot)))))
      (delete-directory dir t))))

(ert-deftest jetpacs-profile-snapshot-test-is-a-ready-hook-member-that-never-signals ()
  "The writer subscribes at load; an unwritable path is a message, not an error."
  (should (memq #'jetpacs-profile-snapshot-write jetpacs-ready-functions))
  (let ((jetpacs-profile-snapshot-file nil))
    (should-not (jetpacs-profile-snapshot-write (jetpacs-profile-snapshot-test--client))))
  (let* ((dir (make-temp-file "jetpacs-snapshot" t))
         (blocker (expand-file-name "blocker" dir))
         (jetpacs-profile-snapshot-file (expand-file-name "inside/last-profile.json" blocker)))
    (unwind-protect
        (progn
          (with-temp-file blocker (insert "not a directory"))
          (should-not (jetpacs-profile-snapshot-write
                       (jetpacs-profile-snapshot-test--client)))
          (should-not (jetpacs-profile-snapshot-read)))
      (delete-directory dir t))))

(ert-deftest jetpacs-profile-snapshot-test-read-tolerates-absence-and-garbage ()
  "No file and a corrupt file both read as nil."
  (let* ((dir (make-temp-file "jetpacs-snapshot" t))
         (file (expand-file-name "last-profile.json" dir)))
    (unwind-protect
        (progn
          (should-not (jetpacs-profile-snapshot-read file))
          (with-temp-file file (insert "{not json"))
          (should-not (jetpacs-profile-snapshot-read file)))
      (delete-directory dir t))))

(provide 'jetpacs-profile-snapshot-test)
;;; jetpacs-profile-snapshot-test.el ends here
