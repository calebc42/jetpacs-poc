;;; jetpacs-org-capture-test.el --- ERT for native Org capture -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later

(require 'ert)
(require 'cl-lib)
(require 'json)
(require 'jetpacs-org-mode)

(defun jetpacs-org-capture-test--kill-buffers-below (directory)
  "Kill visiting buffers whose files are below DIRECTORY."
  (let ((root (file-name-as-directory (file-truename directory))))
    (dolist (buffer (buffer-list))
      (when-let* ((file (buffer-file-name buffer)))
        (when (string-prefix-p root (file-truename file))
          (with-current-buffer buffer
            (set-buffer-modified-p nil))
          (kill-buffer buffer))))))

(ert-deftest jetpacs-org-capture-owner-defaults-and-durable-registration ()
  "The post-cutover owner registers every durable name unconditionally."
  (should (equal jetpacs-org-capture-owner "org-mode"))
  (should (default-value 'jetpacs-org-capture-enabled))
  (let ((jetpacs-org-capture-enabled t))
    (jetpacs-org-capture-register))
  (dolist (name jetpacs-org-capture--verbs)
    (should (gethash name jetpacs-action-handlers))
    (should (equal (jetpacs--owner-of "action" name) "org-mode")))
  (dolist (name '("org.capture.show" "share.text" "org.capture.share"))
    (should (gethash name jetpacs--any-surface-actions)))
  ;; The one-release app-local alias is not a global intake name.
  (should-not (gethash "org-mode.capture" jetpacs--any-surface-actions)))

(ert-deftest jetpacs-org-capture-rollout-flag-disables-only-native-handlers ()
  "The rollback flag removes native handlers and a re-enable restores them."
  (unwind-protect
      (progn
        (let ((jetpacs-org-capture-enabled nil))
          (jetpacs-org-capture-register))
        (dolist (name jetpacs-org-capture--verbs)
          (should-not (gethash name jetpacs-action-handlers)))
        (let ((jetpacs-org-capture-enabled t))
          (jetpacs-org-capture-register))
        (should (eq (gethash "org.capture.show" jetpacs-action-handlers)
                    #'jetpacs-org-capture--on-show)))
    (let ((jetpacs-org-capture-enabled t))
      (jetpacs-org-capture-register))))

(ert-deftest jetpacs-org-capture-filters-prefix-templates ()
  "Legal Org prefix groups never appear as selectable picker entries."
  (let ((org-capture-templates
         '(("b" "Buying templates")
           ("bt" "Buy task" entry (file "/tmp/inbox.org") "* %?")
           ("n" "Note" entry (file "/tmp/inbox.org") "* %^{Title}"))))
    (should (equal (mapcar (lambda (item) (plist-get item :key))
                           (jetpacs-org-capture-templates))
                   '("bt" "n")))
    (should-not (jetpacs-org-capture--template "b"))))

(ert-deftest jetpacs-org-capture-sheet-chain-is-durable-before-celebration ()
  "Picker and form conclusions map fields, write, invalidate globally, then report."
  (let* ((vault (make-temp-file "jetpacs-org-capture" t))
         (file (expand-file-name "inbox.org" vault))
         (org-directory vault)
         (ebp-org-roots nil)
         (org-capture-templates
          `(("t" "Task" entry (file ,file)
             "* TODO %^{Headline}\n%^{Notes|none}\n%?")))
         (jetpacs-org-capture--shared-text "shared body text")
         (jetpacs-org-capture--shared-subject "Shared subject")
         (jetpacs-org-capture--protocol nil)
         (jetpacs-org-capture--dialog nil)
         (notified nil)
         (shown nil)
         (invalidations nil))
    (unwind-protect
        (cl-letf (((symbol-function 'jetpacs-client) (lambda () 'fake))
                  ((symbol-function 'ebp-client-abandon) #'ignore)
                  ((symbol-function 'jetpacs-shell-notify)
                   (lambda (message &rest _)
                     ;; The file must exist already when celebration begins.
                     (push (cons message
                                 (and (file-exists-p file)
                                      (with-temp-buffer
                                        (insert-file-contents file)
                                        (buffer-string))))
                           notified)))
                  ((symbol-function 'jetpacs-toast) #'ignore)
                  ((symbol-function 'jetpacs-flow-continue)
                   (lambda (function) (funcall function)))
                  ((symbol-function 'jetpacs-shell-push) #'ignore)
                  ((symbol-function 'ebp-org-cache-invalidate)
                   (lambda (&optional namespace)
                     (push namespace invalidations)))
                  ((symbol-function 'ebp-client-dialog-show)
                   (lambda (_client id spec &rest keys)
                     (push (list :id id :spec spec
                                 :style (plist-get keys :style)
                                 :callback (plist-get keys :callback))
                           shown)
                     (gensym "capture-request"))))
          (with-temp-file file
            (insert "#+TITLE: Inbox\n"))
          (let ((template (jetpacs-org-capture--template "t")))
            (should template)
            (should (equal (append (plist-get template :prompts) nil)
                           '("Headline" "Notes")))
            (let ((picker-json
                   (jetpacs-node->canonical-json
                    (jetpacs-org-capture--picker-body
                     (jetpacs-org-capture-templates)))))
              (should (string-search "Quick Capture" picker-json))
              (should (string-search "shared body text" picker-json))
              (should (string-search "\"value\":\"t\"" picker-json)))
            (let* ((pairs (jetpacs-org-capture--field-pairs template))
                   (form-json
                    (jetpacs-node->canonical-json
                     (jetpacs-org-capture--form-body template))))
              (should (equal pairs
                             (jetpacs-org-capture--field-pairs template)))
              (dolist (cell pairs)
                (should (jetpacs-identifier-p (cdr cell)))
                (should (string-search (cdr cell) form-json)))
              (should (string-search "\"capture_fields\"" form-json))
              (should (string-search "Shared subject" form-json))))

          ;; Picker -> template key -> form -> echoed field plist -> disk.
          (jetpacs-org-capture--show-picker '(:surface "app:downstream"))
          (should (= (length shown) 1))
          (should (equal (plist-get (car shown) :style) "sheet"))
          (funcall (plist-get (car shown) :callback)
                   "submitted" '(:status "submitted" :value "t") nil)
          (should (= (length shown) 2))
          (let* ((form (car shown))
                 (pairs (jetpacs-org-capture--field-pairs
                         (jetpacs-org-capture--template "t")))
                 (fields
                  (list (intern (concat ":" (cdr (assoc "Headline" pairs))))
                        "Water the ferns"
                        (intern (concat ":" (cdr (assoc "Notes" pairs))))
                        "")))
            (funcall (plist-get form :callback)
                     "submitted"
                     (list :status "submitted" :fields fields)
                     nil))
          (let ((content
                 (with-temp-buffer
                   (insert-file-contents file)
                   (buffer-string))))
            (should (string-search "* TODO Water the ferns" content))
            (should (string-search "none" content))
            (should (string-search "shared body text" content)))
          (should (equal (caar notified) "Captured ✓"))
          (should (string-search "Water the ferns" (cdar notified)))
          ;; A nil argument records the required whole-cache call.
          (should (equal invalidations '(nil)))
          (should-not jetpacs-org-capture--shared-text)
          (should-not jetpacs-org-capture--shared-subject)
          (should-not jetpacs-org-capture--dialog)

          ;; Fresh-template revalidation and dismissal both clear the stash.
          (setq jetpacs-org-capture--shared-text "leftover")
          (jetpacs-org-capture--show-picker nil)
          (let ((count (length shown)))
            (funcall (plist-get (car shown) :callback)
                     "submitted" '(:status "submitted" :value "missing") nil)
            (should (= (length shown) count)))
          (should-not jetpacs-org-capture--shared-text)
          (setq jetpacs-org-capture--shared-text "bail")
          (jetpacs-org-capture--show-picker nil)
          (funcall (plist-get (car shown) :callback) "dismissed" nil nil)
          (should-not jetpacs-org-capture--shared-text)
          (should-not jetpacs-org-capture--dialog))
      (ebp-org-cache-invalidate)
      (jetpacs-org-capture-test--kill-buffers-below vault)
      (delete-directory vault t))))

(ert-deftest jetpacs-org-capture-form-interns-obarray-twin ()
  "The actual form builder makes a runtime field id findable after EBP decode."
  (let* ((org-capture-templates
          '(("t" "Task" entry (file "/tmp/inbox.org") "* %^{Twin Prompt}")))
         (template (jetpacs-org-capture--template "t"))
         (id (cdr (car (jetpacs-org-capture--field-pairs template))))
         (name (concat ":" id)))
    (unwind-protect
        (progn
          (unintern name obarray)
          (jetpacs-org-capture--form-body template)
          (let* ((json (format "{%S:\"found\"}" id))
                 (decoded
                  (let ((obarray (obarray-make)))
                    (json-parse-string json :object-type 'plist
                                       :null-object nil
                                       :false-object :json-false)))
                 (fields (ebp--remap-decoded decoded)))
            (should (equal (plist-get fields (intern name)) "found"))))
      (unintern name obarray))))

(ert-deftest jetpacs-org-capture-share-successor-survives-async-1301 ()
  "An abandoned picker's delayed conclusion cannot erase its successor's stash."
  (let ((org-capture-templates
         '(("t" "Task" entry (file "/tmp/inbox.org") "* %?")))
        (jetpacs-org-capture--shared-text "old")
        (jetpacs-org-capture--shared-subject nil)
        (jetpacs-org-capture--protocol nil)
        (jetpacs-org-capture--dialog nil)
        (callbacks nil)
        (continuation nil)
        (abandoned nil)
        (request 0))
    (cl-letf (((symbol-function 'jetpacs-client) (lambda () 'fake))
              ((symbol-function 'ebp-client-dialog-show)
               (lambda (_client _id _spec &rest keys)
                 (setq callbacks
                       (append callbacks (list (plist-get keys :callback))))
                 (format "request-%d" (cl-incf request))))
              ((symbol-function 'ebp-client-abandon)
               (lambda (_client id) (push id abandoned)))
              ((symbol-function 'jetpacs-flow-continue)
               (lambda (function) (setq continuation function))))
      (jetpacs-org-capture--show-picker nil)
      (let ((old-callback (car callbacks)))
        (should (eq (jetpacs-org-capture--on-share
                     '(:text " successor payload ")
                     '(:surface "app:elsewhere"))
                    'accepted))
        (should (functionp continuation))
        (funcall continuation)
        (should (equal abandoned '("request-1")))
        (should (equal jetpacs-org-capture--shared-text
                       "successor payload"))
        ;; rpc.cancel's error 1301 arrives after request-2 became live.
        (funcall old-callback "error" nil '(:code 1301))
        (should (equal jetpacs-org-capture--shared-text
                       "successor payload"))
        (should (equal (plist-get jetpacs-org-capture--dialog :request-id)
                       "request-2"))))
    (jetpacs-org-capture-dialog-close)
    (jetpacs-org-capture--clear-shared)))

(ert-deftest jetpacs-org-capture-org-protocol-modern-and-legacy ()
  "Modern and legacy URLs use Org's parser and run a fully specified capture."
  (let ((org-capture-templates
         '(("t" "Protocol" entry (file "/tmp/inbox.org")
            "* %?\n%:link\n%i")))
        (jetpacs-org-capture--dialog nil)
        captures)
    (cl-letf (((symbol-function 'jetpacs-client) (lambda () 'fake))
              ((symbol-function 'jetpacs-flow-continue)
               (lambda (function) (funcall function)))
              ((symbol-function 'jetpacs-shell-push) #'ignore)
              ((symbol-function 'jetpacs-shell-notify) #'ignore)
              ((symbol-function 'jetpacs-toast) #'ignore)
              ((symbol-function 'ebp-org-cache-invalidate) #'ignore)
              ((symbol-function 'ebp-org-capture-run)
               (lambda (key values &optional extra)
                 (push (list key values extra
                             (plist-get org-store-link-plist :link)
                             (plist-get org-store-link-plist :initial))
                       captures))))
      (dolist
          (url
           '("org-protocol://capture?template=t&url=https%3A%2F%2Fexample.org%2Fx&title=Hello+World&body=Selected"
             "org-protocol://capture:/t/https%3A%2F%2Fexample.org%2Fx/Hello%20World/Selected"))
        (should (eq (jetpacs-org-capture--on-share
                     (list :text url) '(:surface "app:anywhere"))
                    'accepted)))
      (should (= (length captures) 2))
      (dolist (capture captures)
        (should (equal capture
                       '("t" (("Headline" . "Hello World")) nil
                         "https://example.org/x" "Selected"))))
      (should-not jetpacs-org-capture--protocol)
      (should-not jetpacs-org-capture--shared-text))))

(ert-deftest jetpacs-org-capture-handlers-and-foreign-surface-d1 ()
  "Capture intake statuses are bounded and a downstream-surface tap passes D1."
  (let ((jetpacs-org-capture--shared-text nil)
        (jetpacs-org-capture--shared-subject nil)
        (jetpacs-org-capture--protocol nil)
        continuation)
    (cl-letf (((symbol-function 'jetpacs-client) (lambda () nil)))
      (should (eq (jetpacs-org-capture--on-show nil nil) 'rejected))
      (should (eq (jetpacs-org-capture--on-share
                   '(:text "   " :subject " Subject ") nil)
                  'rejected))
      (should (equal jetpacs-org-capture--shared-text "Subject"))
      (should (equal jetpacs-org-capture--shared-subject "Subject")))
    (cl-letf (((symbol-function 'jetpacs-client) (lambda () 'fake))
              ((symbol-function 'jetpacs-flow-continue)
               (lambda (function) (setq continuation function))))
      (let ((handler (gethash "org.capture.show" jetpacs-action-handlers)))
        (should handler)
        (should
         (eq (jetpacs--dispatch
              nil
              '(:action "org.capture.show" :surface "app:glasspane")
              handler)
             'accepted))
        (should (functionp continuation))))))

(provide 'jetpacs-org-capture-test)
;;; jetpacs-org-capture-test.el ends here
