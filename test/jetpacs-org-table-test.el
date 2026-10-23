;;; jetpacs-org-table-test.el --- Org table and Babel verbs -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later

;;; Commentary:

;; The table/Babel verbs' gate, ported with the module from the downstream
;; app that first shipped them: the mutation funnel over a real temp table
;; (align + #+TBLFM recalculation, `accepted'-grade durability), the
;; formula-vs-value routing, the D2 prompting arms' headless refusal, the
;; no-prompt add verbs durable inside the dispatch, Babel's timeout and
;; declined/disabled confirm, and the editable table node's exposure-route
;; descriptors.

;;; Code:

(require 'ert)
(require 'cl-lib)
(require 'ebp)
(require 'jetpacs-org-table)

(defun jetpacs-org-table-test--vault ()
  "A throwaway vault with the table/babel fixture; (VAULT . FILE)."
  (let* ((vault (make-temp-file "jetpacs-org-table" t))
         (file (expand-file-name "table.org" vault)))
    (with-temp-file file
      (insert "* Data\n"
              "| a | b | sum |\n"
              "|---+---+-----|\n"
              "| 1 | 2 |   3 |\n"
              "#+TBLFM: $3=$1+$2\n"
              "\n"
              "* Lone\n"
              "| only |\n"
              "\n"
              "* Code\n"
              "#+begin_src jptest\n"
              "ignored\n"
              "#+end_src\n"))
    (cons vault file)))

(defun jetpacs-org-table-test--cleanup (vault)
  "Kill the vault's buffers, sweep the exposure records, drop the vault."
  (ebp-org-cache-invalidate)
  (jetpacs-buffer-forget-exposed)
  (dolist (buf (buffer-list))
    (let ((f (buffer-file-name buf)))
      (when (and f (string-prefix-p (file-name-as-directory
                                     (file-truename vault))
                                    (file-truename f)))
        (with-current-buffer buf (set-buffer-modified-p nil))
        (kill-buffer buf))))
  (delete-directory vault t))

(defun jetpacs-org-table-test--pos (buf needle)
  "Position of NEEDLE's first char in BUF."
  (with-current-buffer buf
    (save-excursion
      (save-restriction
        (widen)
        (goto-char (point-min))
        (search-forward needle)
        (match-beginning 0)))))

(defun jetpacs-org-table-test--disk (file)
  "FILE's current on-disk content."
  (with-temp-buffer
    (insert-file-contents file)
    (buffer-string)))

(ert-deftest jetpacs-org-table-verbs-are-ownerless-foundation-verbs ()
  "Every table/Babel verb registers at load, ownerless, with a schema doc."
  (dolist (verb jetpacs-org-table-verbs)
    (should (gethash verb jetpacs-action-handlers))
    (should-not (jetpacs--owner-of "action" verb))
    (should (stringp (plist-get (jetpacs-action-schema verb) :doc)))))

(ert-deftest jetpacs-org-table-mutate-edges ()
  "The mutation funnel over a real temp table: align+recalc through a
#+TBLFM line with `accepted'-grade durability (on disk, buffer clean),
formula-vs-value routing decided by `ebp-org-table-field-formula' (the
computed cell edits its formula, a plain cell its value — sanitized),
a killed row realigning from the table's start, the only row's kill
consuming the table without erroring, and the no-table refusal."
  (let* ((fixture (jetpacs-org-table-test--vault))
         (vault (car fixture))
         (file (cdr fixture))
         (ebp-org-roots (list vault)))
    (unwind-protect
        (let ((buf (find-file-noselect file)))
          (with-current-buffer buf
            (unless (derived-mode-p 'org-mode) (org-mode)))
          ;; Routing: the #+TBLFM-computed column names its entry, the
          ;; plain cell does not (the edit worker's fork).
          (with-current-buffer buf
            (org-with-wide-buffer
             (goto-char (jetpacs-org-table-test--pos buf "3"))
             (should (equal (car (ebp-org-table-field-formula)) "$3"))
             (goto-char (+ 2 (jetpacs-org-table-test--pos buf "| 1")))
             (should-not (ebp-org-table-field-formula))))
          ;; Value mutation: durable, realigned, recalculated (1+2
          ;; becomes 4+2 and the sum column follows).
          (jetpacs-org-table-mutate
           buf (+ 2 (jetpacs-org-table-test--pos buf "| 1"))
           (lambda () (org-table-get-field nil "4")))
          (should-not (buffer-modified-p buf))
          (should (string-match-p "| 4 | 2 | +6 |"
                                  (jetpacs-org-table-test--disk file)))
          ;; The bridged edit worker, formula path: prefilled with the
          ;; stored RHS, stores the replacement, recalculates.  The
          ;; prefill is asserted OUTSIDE the stub — a `should' failing
          ;; inside the worker would be caught by its own error arm.
          (let (seen-initial)
            (cl-letf (((symbol-function 'jetpacs-dialog-can-bridge-p)
                       (lambda () t))
                      ((symbol-function 'read-string)
                       (lambda (_prompt &optional initial &rest _)
                         (setq seen-initial initial)
                         "$1*$2"))
                      ((symbol-function 'jetpacs-shell-push)
                       (lambda (&rest _) nil))
                      ((symbol-function 'jetpacs-shell-notify)
                       (lambda (&rest _) nil)))
              (jetpacs-org-table--edit-run
               buf (jetpacs-org-table-test--pos buf "6") "app:test"))
            (should (equal seen-initial "$1+$2")))
          (let ((disk (jetpacs-org-table-test--disk file)))
            (should (string-search "$3=$1*$2" disk))
            (should (string-match-p "| 4 | 2 | +8 |" disk)))
          ;; The value path sanitizes: one line between pipes, always.
          (cl-letf (((symbol-function 'jetpacs-dialog-can-bridge-p)
                     (lambda () t))
                    ((symbol-function 'read-string)
                     (lambda (&rest _) "x|y\nz"))
                    ((symbol-function 'jetpacs-shell-push)
                     (lambda (&rest _) nil))
                    ((symbol-function 'jetpacs-shell-notify)
                     (lambda (&rest _) nil)))
            (jetpacs-org-table--edit-run
             buf (+ 2 (jetpacs-org-table-test--pos buf "| a ")) "app:test"))
          (should (string-search "x\\vert{}y z"
                                 (jetpacs-org-table-test--disk file)))
          ;; The menu worker: deleting the data row leaves a smaller,
          ;; still-aligned table (realign from the table's start).
          (cl-letf (((symbol-function 'jetpacs-dialog-can-bridge-p)
                     (lambda () t))
                    ((symbol-function 'completing-read)
                     (lambda (&rest _) "Delete row"))
                    ((symbol-function 'jetpacs-shell-push)
                     (lambda (&rest _) nil))
                    ((symbol-function 'jetpacs-shell-notify)
                     (lambda (&rest _) nil)))
            (jetpacs-org-table--cell-menu-run
             buf (jetpacs-org-table-test--pos buf "4 | 2") "app:test"))
          (let ((disk (jetpacs-org-table-test--disk file)))
            ;; The computed 8 lived only on the deleted row.
            (should-not (string-search "8" disk))
            (should (string-search "x\\vert{}y z" disk)))
          ;; Killing the ONLY row consumes the table: the funnel skips
          ;; the realign instead of erroring.
          (jetpacs-org-table-mutate
           buf (+ 2 (jetpacs-org-table-test--pos buf "| only"))
           #'org-table-kill-row)
          (should-not (string-search "| only |"
                                     (jetpacs-org-table-test--disk file)))
          ;; No table at the heading: the funnel signals, mutating
          ;; nothing.
          (should-error (jetpacs-org-table-mutate buf 1 #'ignore)))
      (jetpacs-org-table-test--cleanup vault))))

(ert-deftest jetpacs-org-table-headless-refusal ()
  "The D2 sharp edges: every verb answers a status on junk, unexposed
addressing rejects (SPEC 23.1), the prompting verbs reject at dispatch
without a bridgeable session and their WORKERS notify instead of raising
a minibuffer prompt nobody attends (can-bridge nil -> notify + status, no
wedge), the no-prompt add verbs are durable inside the dispatch with
only the refresh deferred, Babel times out and honors a declined confirm
with a stub language."
  (let* ((fixture (jetpacs-org-table-test--vault))
         (vault (car fixture))
         (file (cdr fixture))
         (ebp-org-roots (list vault))
         (jetpacs-org-babel-timeout 1)
         (notes nil)
         (refreshes nil))
    (unwind-protect
        (let* ((buf (find-file-noselect file))
               (name (buffer-name buf)))
          (with-current-buffer buf
            (unless (derived-mode-p 'org-mode) (org-mode)))
          (cl-letf (((symbol-function 'jetpacs-shell-notify)
                     (lambda (text &rest _) (push text notes) nil))
                    ((symbol-function 'jetpacs-buffer-defer-refresh)
                     (lambda (surface) (push surface refreshes) nil)))
            (cl-flet ((run (verb args &optional params)
                        (let ((handler (gethash verb jetpacs-action-handlers)))
                          (should handler)
                          (funcall handler args params))))
              ;; The whole table answers statuses on bare nil/nil input.
              (dolist (verb jetpacs-org-table-verbs)
                (should (memq (run verb nil nil)
                              '(accepted stale rejected))))
              ;; Well-formed but UNEXPOSED addressing rejects.
              (let ((anchor (jetpacs-org-table-test--pos buf "| a ")))
                (should (eq (run "org.table.add-row"
                                 (list :buffer name :pos anchor))
                            'rejected))
                ;; Exposed: durable inside the dispatch — the new row is
                ;; on disk before `accepted' is answered; the refresh is
                ;; deferred, never inline.
                (jetpacs-buffer-expose name anchor "org.table.add-row")
                (jetpacs-buffer-expose name anchor "org.table.add-col")
                (let ((before (length refreshes)))
                  (should (eq (run "org.table.add-row"
                                   (list :buffer name :pos anchor)
                                   (list :surface "app:test"))
                              'accepted))
                  (should (= (length refreshes) (1+ before)))
                  (should (equal (car refreshes) "app:test")))
                ;; Four "|"-rows now: the Data table's three plus the
                ;; Lone table's one.
                (with-temp-buffer
                  (insert-file-contents file)
                  (goto-char (point-min))
                  (should (= 4 (count-matches "^| "))))
                ;; add-col appends at the right edge: the header line
                ;; gains a pipe.
                (should (eq (run "org.table.add-col"
                                 (list :buffer name :pos anchor))
                            'accepted))
                (with-temp-buffer
                  (insert-file-contents file)
                  (goto-char (point-min))
                  (search-forward "sum")
                  (should (= 5 (cl-count ?| (buffer-substring-no-properties
                                             (line-beginning-position)
                                             (line-end-position)))))))
              ;; The prompting verbs: exposure alone is not enough — no
              ;; connected+granted session, no flow, `rejected'.
              (let ((pos (+ 2 (jetpacs-org-table-test--pos buf "| 1"))))
                (jetpacs-buffer-expose name pos "org.table.edit")
                (jetpacs-buffer-expose name pos "org.table.cell-menu")
                (should (eq (run "org.table.edit"
                                 (list :buffer name :pos pos))
                            'rejected))
                (should (eq (run "org.table.cell-menu"
                                 (list :buffer name :pos pos))
                            'rejected))
                ;; With a stubbed session the flow queues and the
                ;; handler answers on its strength; a JSON-float pos
                ;; coerces on the way through.
                (let ((flows nil))
                  (cl-letf (((symbol-function 'jetpacs-connected-p)
                             (lambda () t))
                            ((symbol-function 'jetpacs-granted-p)
                             (lambda (&rest _) t))
                            ((symbol-function 'jetpacs-flow-begin)
                             (lambda (surface fn)
                               (push (cons surface fn) flows) nil)))
                    (should (eq (run "org.table.edit"
                                     (list :buffer name :pos (float pos))
                                     (list :surface "app:test"))
                                'accepted))
                    (should (= (length flows) 1))
                    (should (equal (car (car flows)) "app:test"))))
                ;; The workers' headless refusal: notify, never a
                ;; minibuffer prompt (the stubs would signal).
                (cl-letf (((symbol-function 'jetpacs-dialog-can-bridge-p)
                           (lambda () nil))
                          ((symbol-function 'read-string)
                           (lambda (&rest _)
                             (error "prompt raised headless")))
                          ((symbol-function 'completing-read)
                           (lambda (&rest _)
                             (error "prompt raised headless"))))
                  (setq notes nil)
                  (jetpacs-org-table--edit-run buf pos "app:test")
                  (should (string-search "attended session" (car notes)))
                  (jetpacs-org-table--cell-menu-run buf pos "app:test")
                  (should (string-search "attended session" (car notes)))))
              ;; Babel. Dispatch half: global confirm on, no dialog
              ;; grant — reject with the refusal notified.
              (let ((src (jetpacs-org-table-test--pos buf "#+begin_src")))
                (jetpacs-buffer-expose name src "org.babel.execute")
                (let ((org-confirm-babel-evaluate t))
                  (setq notes nil)
                  (should (eq (run "org.babel.execute"
                                   (list :buffer name :pos src))
                              'rejected))
                  (should (string-search "attended session" (car notes)))
                  ;; Worker half: confirm due, bridge gone between
                  ;; dispatch and flow — notify, never prompt.
                  (cl-letf (((symbol-function 'jetpacs-dialog-can-bridge-p)
                             (lambda () nil))
                            ((symbol-function 'yes-or-no-p)
                             (lambda (&rest _)
                               (error "prompt raised headless"))))
                    (setq notes nil)
                    (jetpacs-org-table--babel-run buf src "app:test")
                    (should (string-search "attended session" (car notes))))
                  ;; Declined confirm: `org-babel-confirm-evaluate'
                  ;; returns nil (it does not signal) — the run must
                  ;; stop on that, not evaluate anyway.
                  (cl-letf (((symbol-function 'jetpacs-dialog-can-bridge-p)
                             (lambda () t))
                            ((symbol-function 'yes-or-no-p)
                             (lambda (&rest _) nil))
                            ((symbol-function 'org-babel-execute:jptest)
                             (lambda (&rest _)
                               (error "evaluated after decline"))))
                    (setq notes nil)
                    (jetpacs-org-table--babel-run buf src "app:test")
                    (should (equal (car notes) "Evaluation declined")))
                  ;; Policy refusal, not a user decline: a nil
                  ;; `org-babel-check-confirm-evaluate' (a `:eval no' block)
                  ;; makes `org-babel-confirm-evaluate' answer nil with no
                  ;; prompt at all — the notification must say so.
                  (cl-letf (((symbol-function 'jetpacs-dialog-can-bridge-p)
                             (lambda () t))
                            ((symbol-function 'org-babel-check-confirm-evaluate)
                             (lambda (&rest _) nil))
                            ((symbol-function 'yes-or-no-p)
                             (lambda (&rest _)
                               (error "prompt raised for a disabled block")))
                            ((symbol-function 'org-babel-execute:jptest)
                             (lambda (&rest _)
                               (error "evaluated despite :eval no"))))
                    (setq notes nil)
                    (jetpacs-org-table--babel-run buf src "app:test")
                    (should (equal (car notes)
                                   "Evaluation disabled for this block"))))
                ;; Timeout: a stub language that outsleeps the budget —
                ;; the timer interrupts it and the failure is a notify,
                ;; not a wedge (no confirm due: option nil).
                (let ((org-confirm-babel-evaluate nil)
                      (ran nil))
                  (cl-letf (((symbol-function 'org-babel-execute:jptest)
                             (lambda (&rest _)
                               (sleep-for 3)
                               (setq ran t)
                               "done")))
                    (setq notes nil)
                    (jetpacs-org-table--babel-run buf src "app:test")
                    (should-not ran)
                    (should (string-search "timed out" (car notes)))))))))
      (jetpacs-org-table-test--cleanup vault))))

(ert-deftest jetpacs-org-table-node-descriptors ()
  "The editable table node: header/rule/data rows in org's own
convention, cell descriptors on the exposure route with no path on the
wire, and the loop closed — a descriptor's own args pass the handler
gate that will receive them."
  (let* ((fixture (jetpacs-org-table-test--vault))
         (vault (car fixture))
         (ebp-org-roots (list vault)))
    (unwind-protect
        (let* ((buf (find-file-noselect (cdr fixture)))
               (name (buffer-name buf))
               node)
          (with-current-buffer buf
            (unless (derived-mode-p 'org-mode) (org-mode))
            (jetpacs-buffer-forget-exposed name)
            (org-with-wide-buffer
             (goto-char (jetpacs-org-table-test--pos buf "| a "))
             (setq node (jetpacs-org-table-node (org-element-at-point)))))
          (should node)
          (should (equal (plist-get node :t) "table"))
          (let ((json (jetpacs-node->canonical-json node)))
            (should (string-search "org.table.edit" json))
            (should (string-search "org.table.cell-menu" json))
            (should (string-search "org.table.add-row" json))
            (should (string-search "org.table.add-col" json))
            ;; Exposure descriptors, never a baked path (S5/23.1).
            (should (string-search "\"buffer\"" json))
            (should-not (string-search "\"file\"" json))
            (should-not (string-search vault json)))
          (let ((rows (append (plist-get node :rows) nil)))
            (should (equal (mapcar (lambda (r) (plist-get r :kind)) rows)
                           '("header" "rule" "data")))
            ;; A data cell's own descriptor: exposed, and its args pass
            ;; the gate (the bridge half rejects without a session).
            (let* ((cells (append (plist-get (nth 2 rows) :cells) nil))
                   (args (plist-get (plist-get (car cells) :on_tap) :args)))
              (should (jetpacs-buffer-exposed-p
                       name (plist-get args :pos) "org.table.edit"))
              (should (eq (funcall (gethash "org.table.edit"
                                            jetpacs-action-handlers)
                                   args nil)
                          'rejected))))
          ;; The add affordances: exposed anchor, and the handler
          ;; mutates through it end to end.
          (let ((args (plist-get (plist-get node :on_add_row) :args)))
            (should (jetpacs-buffer-exposed-p
                     name (plist-get args :pos) "org.table.add-row"))
            (cl-letf (((symbol-function 'jetpacs-buffer-defer-refresh)
                       (lambda (&rest _) nil)))
              (should (eq (funcall (gethash "org.table.add-row"
                                            jetpacs-action-handlers)
                                   args nil)
                          'accepted)))
            (with-temp-buffer
              (insert-file-contents (cdr fixture))
              (goto-char (point-min))
              (should (= 4 (count-matches "^| "))))))
      (jetpacs-org-table-test--cleanup vault))))

(provide 'jetpacs-org-table-test)
;;; jetpacs-org-table-test.el ends here
