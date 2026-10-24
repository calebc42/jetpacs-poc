;;; jetpacs-teardown-test.el --- JA-2 teardown exit gate -*- lexical-binding: t; -*-

;;; Commentary:

;; The JA-2b exit gate (docs/PLAN-jetpacs-apps.md, B2/G5).  Loads
;; ebp.el's ebp-wire-test.el for the loopback harness, so it MUST run under
;; the "^jetpacs-teardown-" selector (loading the harness defines its
;; whole suite too).  The W10 test is the one that fails against the
;; old callback-less remove path and passes with the send-remove fix.

;;; Code:

(require 'ert)
(require 'cl-lib)
(require 'ebp)
(require 'jetpacs-widgets)
(require 'jetpacs-async)
(require 'jetpacs-surfaces)
(require 'jetpacs-shell)

(defconst jetpacs-teardown-test--core
  ["text" "row" "column" "box" "spacer" "divider" "button" "text_input"])

(defun jetpacs-teardown-test--client ()
  (let ((client (ebp-client-create
                 :receipt-file (make-temp-file "jetpacs-teardown-receipts"))))
    (setf (ebp-client-state client) 'ready
          (ebp-client-profiles client)
          `(:app (:node_types ,jetpacs-teardown-test--core
                  :builtins [] :features []))
          (ebp-client-limits client) '(:max_frame_bytes 4194304))
    client))

(defmacro jetpacs-teardown-test--with (client-form &rest body)
  (declare (indent 1))
  `(let ((client ,client-form))
     (unwind-protect
         (progn (jetpacs-attach client) ,@body)
       (jetpacs-detach)
       (jetpacs-test-reset-state))))

(defun jetpacs-teardown-test--setup-owner (owner &optional cancel-flag)
  "Register an action, root, subscription, and async entry for OWNER."
  (with-jetpacs-owner owner
    (jetpacs-defaction (concat owner ".tap") (lambda (_a _p) 'accepted))
    (jetpacs-shell-define-root owner
                               (lambda () '(:t "text" :text "x")))
    (jetpacs-on-state-change "note" #'ignore)
    (jetpacs-async (list (intern owner) 'k)
      (lambda (_resolve _reject)
        (lambda () (when cancel-flag (set cancel-flag t)))))))

(ert-deftest jetpacs-teardown-two-owners-sweeps-one-spares-other ()
  (jetpacs-teardown-test--with (jetpacs-teardown-test--client)
    (defvar jetpacs-teardown-test--alpha-cancel nil)
    (defvar jetpacs-teardown-test--beta-cancel nil)
    (setq jetpacs-teardown-test--alpha-cancel nil
          jetpacs-teardown-test--beta-cancel nil)
    (jetpacs-teardown-test--setup-owner "alpha"
                                        'jetpacs-teardown-test--alpha-cancel)
    (jetpacs-teardown-test--setup-owner "beta"
                                        'jetpacs-teardown-test--beta-cancel)
    (puthash "app:alpha" 7 jetpacs--applied-revisions)
    (puthash "app:beta" 7 jetpacs--applied-revisions)
    (puthash "app:alpha" "v1" jetpacs-shell--current-view)
    (puthash "app:beta" "v1" jetpacs-shell--current-view)
    (puthash "app:alpha" 3 (ebp-client-revisions client))
    (let ((removed '()))
      (cl-letf (((symbol-function 'ebp-client-surface-remove)
                 (cl-function (lambda (_c s &key callback)
                                (push s removed)
                                (when callback (funcall callback "applied" nil))
                                1))))
        (should (equal (jetpacs-teardown-owner "alpha") "alpha")))
      ;; Actions: staging + live allowlist + claim, all gone; beta's stand.
      (should-not (gethash "alpha.tap" jetpacs-action-handlers))
      (should-not (gethash "alpha.tap" (ebp-client-actions client)))
      (should-not (jetpacs--owner-of "action" "alpha.tap"))
      (should (gethash "beta.tap" jetpacs-action-handlers))
      (should (equal (jetpacs--owner-of "action" "beta.tap") "beta"))
      ;; Roots and claims.
      (should-not (alist-get "app:alpha" jetpacs-shell--roots nil nil #'equal))
      (should (alist-get "app:beta" jetpacs-shell--roots nil nil #'equal))
      (should-not (jetpacs--owner-of "surface" "app:alpha"))
      (should (equal (jetpacs--owner-of "surface" "app:beta") "beta"))
      ;; State subscriptions per (SURFACE . ID).
      (should-not (gethash (cons "app:alpha" "note") jetpacs--state-handlers))
      (should (gethash (cons "app:beta" "note") jetpacs--state-handlers))
      ;; Async: alpha cancelled and evicted; beta untouched.
      (should jetpacs-teardown-test--alpha-cancel)
      (should-not jetpacs-teardown-test--beta-cancel)
      ;; Residue.
      (should-not (gethash "app:alpha" jetpacs--applied-revisions))
      (should (= (gethash "app:beta" jetpacs--applied-revisions) 7))
      (should-not (gethash "app:alpha" jetpacs-shell--current-view))
      ;; Exactly one wire remove, for alpha.
      (should (equal removed '("app:alpha")))
      ;; The ownerless core registration survives.
      (should (gethash "view.switched" jetpacs-action-handlers)))))

(ert-deftest jetpacs-teardown-idempotent ()
  (jetpacs-teardown-test--with (jetpacs-teardown-test--client)
    (jetpacs-teardown-test--setup-owner "alpha")
    (puthash "app:alpha" 3 (ebp-client-revisions client))
    (let ((removed '()))
      (cl-letf (((symbol-function 'ebp-client-surface-remove)
                 (cl-function (lambda (_c s &key callback)
                                (push s removed)
                                (when callback (funcall callback "applied" nil))
                                1))))
        (should (equal (jetpacs-teardown-owner "alpha") "alpha"))
        (should (equal (jetpacs-teardown-owner "alpha") "alpha"))
        (should (equal (jetpacs-teardown-owner "alpha") "alpha")))
      (should-not (jetpacs--owned-names "action" "alpha"))
      (should-not (jetpacs--owned-names "surface" "alpha"))
      ;; EXACTLY one: the audit measured three calls = three sends,
      ;; because the claimed revision survives a removal (13.3 keeps
      ;; tombstones) and ">= 1" could not see the re-fire.
      (should (= (length removed) 1)))))

(ert-deftest jetpacs-teardown-never-pushed-owner-sends-no-tombstone ()
  "H5: a surface the client cannot know about gets no permanent
tombstone — local unclaim only."
  (jetpacs-teardown-test--with (jetpacs-teardown-test--client)
    (with-jetpacs-owner "gamma"
      (jetpacs-defaction "gamma.go" (lambda (_a _p) 'accepted)))
    (let ((removed '()))
      (cl-letf (((symbol-function 'ebp-client-surface-remove)
                 (cl-function (lambda (_c s &key _callback)
                                (push s removed) 1))))
        (jetpacs-teardown-owner "gamma"))
      (should (null removed))
      (should-not (gethash "gamma.go" jetpacs-action-handlers))
      (should-not (jetpacs--owner-of "action" "gamma.go"))
      (should (null jetpacs-shell--pending-removals)))))

(ert-deftest jetpacs-teardown-disconnected-queues-removal ()
  (jetpacs-teardown-test--with (jetpacs-teardown-test--client)
    (jetpacs-teardown-test--setup-owner "alpha")
    (setf (ebp-client-state client) 'closed)
    (let ((removed '()))
      (cl-letf (((symbol-function 'ebp-client-surface-remove)
                 (cl-function (lambda (_c s &key _callback)
                                (push s removed) 1))))
        (jetpacs-teardown-owner "alpha"))
      (should (member "app:alpha" jetpacs-shell--pending-removals))
      (should (null removed))
      (should-not (alist-get "app:alpha" jetpacs-shell--roots
                             nil nil #'equal)))))

(ert-deftest jetpacs-teardown-w10-ceiling-refusal-requeues ()
  "THE fix test: drives ebp's REAL held branch — a refused tombstone is
requeued, not silently lost.  Fails against the callback-less remove."
  (jetpacs-teardown-test--with (jetpacs-teardown-test--client)
    (jetpacs-teardown-test--setup-owner "alpha")
    (setf (ebp-client-outstanding client) ebp-overload-hold)
    (jetpacs-teardown-owner "alpha")
    (should (member "app:alpha" jetpacs-shell--pending-removals))))

(ert-deftest jetpacs-teardown-async-error-requeues ()
  (jetpacs-teardown-test--with (jetpacs-teardown-test--client)
    (jetpacs-teardown-test--setup-owner "alpha")
    (cl-letf (((symbol-function 'ebp-client--request)
               (lambda (_c _m _p cb &optional _t)
                 (funcall cb nil '(:code -32000 :message "timeout"))
                 nil)))
      (jetpacs-teardown-owner "alpha"))
    (should (member "app:alpha" jetpacs-shell--pending-removals))))

(ert-deftest jetpacs-teardown-late-async-completion-inert ()
  (jetpacs-teardown-test--with (jetpacs-teardown-test--client)
    (let ((captured-resolve nil))
      (with-jetpacs-owner "alpha"
        (jetpacs-async '(alpha slow)
          (lambda (resolve _reject)
            (setq captured-resolve resolve)
            nil)))
      (cl-letf (((symbol-function 'ebp-client-surface-remove)
                 (cl-function (lambda (&rest _) 1))))
        (jetpacs-teardown-owner "alpha"))
      (funcall captured-resolve 42)
      (should-not (gethash '(alpha slow) jetpacs-async--cache))
      (should-not jetpacs-async--push-timer))))

(ert-deftest jetpacs-teardown-stray-repush-is-noop ()
  "H3: after the local-first sweep, a racing repush finds no root."
  (jetpacs-teardown-test--with (jetpacs-teardown-test--client)
    (jetpacs-teardown-test--setup-owner "alpha")
    (cl-letf (((symbol-function 'ebp-client-surface-remove)
               (cl-function (lambda (&rest _) 1))))
      (jetpacs-teardown-owner "alpha"))
    (let ((pushed '()))
      (cl-letf (((symbol-function 'ebp-client-surface-update)
                 (cl-function (lambda (_c s &rest _) (push s pushed) 1))))
        (should-not (jetpacs-shell-push "alpha"))
        (should (null pushed))))))

(ert-deftest jetpacs-teardown-hook-runs-isolated-after-sweep ()
  (jetpacs-teardown-test--with (jetpacs-teardown-test--client)
    (with-jetpacs-owner "alpha"
      (jetpacs-defaction "alpha.tap" (lambda (_a _p) 'accepted)))
    (let* ((seen nil)
           (jetpacs-teardown-functions
            (list (lambda (_o) (error "boom"))
                  (lambda (o) (setq seen (list o (jetpacs--owned-names
                                                  "action" o)))))))
      (jetpacs-teardown-owner "alpha")
      (should (equal seen '("alpha" nil))))))

;;;; The teardown ledger

(defvar jetpacs-teardown-test--hook nil
  "A scratch hook for the ledger tests.")

(defvar jetpacs-teardown-test--flag nil
  "A scratch setting for the ledger tests.")

(ert-deftest jetpacs-teardown-sweeps-every-ledgered-kind ()
  "Every registration made under an owner is gone after its teardown, and
an ownerless registration in the same registries survives."
  (require 'jetpacs-apps)
  (require 'jetpacs-settings)
  (require 'jetpacs-reader)
  (require 'jetpacs-editor)
  (require 'jetpacs-navigate)
  (require 'jetpacs-reminders)
  (let ((jetpacs-reminders--projections (make-hash-table :test #'equal))
        (jetpacs-apps--registry nil) (jetpacs-apps--current nil)
        (jetpacs-teardown-test--hook nil)
        (jetpacs-reader--adapters nil) (jetpacs-reader--displaced nil)
        (jetpacs-editor--adapters nil) (jetpacs-editor--displaced nil)
        (jetpacs-render-buffer-functions nil)
        (jetpacs-settings-registry nil) (jetpacs-settings-hub-entries nil)
        (jetpacs-settings-links nil)
        (link (lambda () '(:t "text" :text "link"))))
    (unwind-protect
        (progn
          (jetpacs-reader-register 'stock :predicate #'ignore :render #'ignore)
          (with-jetpacs-owner "alpha"
            (jetpacs-add-hook 'jetpacs-teardown-test--hook #'ignore)
            (jetpacs-reader-register 'alpha-r :predicate #'ignore
                                     :render #'ignore)
            (jetpacs-editor-register 'alpha-e :predicate #'ignore)
            (jetpacs-render-buffer-register 'alpha-test-mode #'ignore)
            (jetpacs-settings-register-section
             "Alpha" (list (list 'jetpacs-teardown-test--flag :label "Flag")))
            (jetpacs-settings-register-hub-entry "alpha" :order 1
                                                 :title "Alpha")
            (jetpacs-settings-add-link 10 link)
            (jetpacs-navigate-register-drill-host "app:elsewhere" #'ignore))
          (jetpacs-reminders-define-projection "alpha" #'ignore)
          (jetpacs-defapp "alpha" :surfaces '("alpha"))
          (dolist (kind (remove "theme-provider" jetpacs-teardown-kind-order))
            (should (jetpacs--owned-names kind "alpha")))
          (jetpacs-teardown-owner "alpha")
          (dolist (kind jetpacs-teardown-kind-order)
            (should-not (jetpacs--owned-names kind "alpha")))
          (should-not jetpacs-teardown-test--hook)
          (should (equal (mapcar #'jetpacs-reader-adapter-id
                                 jetpacs-reader--adapters)
                         '(stock)))
          (should-not jetpacs-editor--adapters)
          (should-not jetpacs-render-buffer-functions)
          (should-not jetpacs-settings-registry)
          (should-not jetpacs-settings-hub-entries)
          (should-not jetpacs-settings-links)
          (should-not (gethash "app:elsewhere" jetpacs-navigate--drill-hosts))
          (should-not (jetpacs-reminders-projection-p "alpha"))
          (should-not (assoc "alpha" jetpacs-apps--registry))
          ;; The sweep is idempotent.
          (jetpacs-teardown-owner "alpha"))
      (jetpacs-teardown-owner "alpha")
      (jetpacs-test-reset-state))))

(ert-deftest jetpacs-teardown-own-teardown-hook-runs-then-leaves ()
  "An owner's own `jetpacs-teardown-functions' member runs once for that
owner, then leaves with the rest of its ledger."
  (let* ((seen nil)
         (fn (lambda (owner) (push owner seen))))
    (unwind-protect
        (progn
          (with-jetpacs-owner "alpha"
            (jetpacs-add-hook 'jetpacs-teardown-functions fn))
          (jetpacs-teardown-owner "alpha")
          (should (equal seen '("alpha")))
          (should-not (memq fn jetpacs-teardown-functions))
          (should-not (jetpacs--owned-names "hook" "alpha"))
          (jetpacs-teardown-owner "alpha")
          (should (equal seen '("alpha"))))
      (remove-hook 'jetpacs-teardown-functions fn)
      (jetpacs-test-reset-state))))

(ert-deftest jetpacs-teardown-displaced-adapter-is-restored ()
  "An owner replacing the stock adapter gives the slot back at teardown,
in the same position; an ownerless reassertion takes the slot back from
an owner outright."
  (require 'jetpacs-reader)
  (let ((jetpacs-reader--adapters nil) (jetpacs-reader--displaced nil))
    (unwind-protect
        (progn
          (jetpacs-reader-register 'org :predicate #'ignore :render #'car)
          (jetpacs-reader-register 'other :predicate #'ignore :render #'ignore)
          (with-jetpacs-owner "beta"
            (jetpacs-reader-register 'org :predicate #'ignore :render #'cdr))
          (should (eq (jetpacs-reader-adapter-render
                       (car jetpacs-reader--adapters))
                      #'cdr))
          (jetpacs-teardown-owner "beta")
          (should (equal (mapcar #'jetpacs-reader-adapter-id
                                 jetpacs-reader--adapters)
                         '(org other)))
          (should (eq (jetpacs-reader-adapter-render
                       (car jetpacs-reader--adapters))
                      #'car))
          (should-not (jetpacs--owner-of "reader-adapter" 'org))
          (with-jetpacs-owner "beta"
            (jetpacs-reader-register 'org :predicate #'ignore :render #'cdr))
          (jetpacs-reader-register 'org :predicate #'ignore :render #'car)
          (should-not (jetpacs--owner-of "reader-adapter" 'org))
          (jetpacs-teardown-owner "beta")
          (should (equal (mapcar #'jetpacs-reader-adapter-id
                                 jetpacs-reader--adapters)
                         '(org other))))
      (jetpacs-teardown-owner "beta")
      (jetpacs-test-reset-state))))

(ert-deftest jetpacs-teardown-apps-unregister-is-the-whole-sweep ()
  "`jetpacs-apps-unregister' alone removes the app's actions, root and
registry entry."
  (require 'jetpacs-apps)
  (let ((jetpacs-apps--registry nil) (jetpacs-apps--current nil))
    (unwind-protect
        (progn
          (jetpacs-teardown-test--setup-owner "gamma")
          (jetpacs-defapp "gamma" :surfaces '("gamma"))
          (should (gethash "gamma.tap" jetpacs-action-handlers))
          (jetpacs-apps-unregister "gamma")
          (should-not (gethash "gamma.tap" jetpacs-action-handlers))
          (should-not (alist-get "app:gamma" jetpacs-shell--roots
                                 nil nil #'equal))
          (should-not (assoc "gamma" jetpacs-apps--registry))
          (should-not (jetpacs--owned-names "app" "gamma")))
      (jetpacs-teardown-owner "gamma")
      (setq jetpacs-shell--pending-removals nil)
      (jetpacs-test-reset-state))))

(ert-deftest jetpacs-teardown-live-loopback-remove-on-wire ()
  "The tombstone genuinely hits the wire through the real handshake."
  (require 'ebp-wire-test)
  (let ((server (ebp-test--start-companion (ebp-test--kat-script)))
        client)
    (unwind-protect
        (progn
          (setq client (jetpacs-connect
                        "127.0.0.1" (plist-get server :port)
                        :client-name "test-client" :client-version "0.0.1"
                        :pairing-id ebp-test--kat-pid
                        :token ebp-test--kat-token
                        :wants '("theme") :client-nonce ebp-test--kat-cn
                        :receipt-file (make-temp-file "teardown-receipts")))
          (should (ebp-test--wait
                   (lambda () (eq (ebp-client-state client) 'ready))))
          (with-jetpacs-owner "alpha"
            (jetpacs-shell-define-root
             "alpha" (lambda () '(:t "text" :text "hi"))))
          (should (integerp (with-jetpacs-owner "alpha"
                              (jetpacs-shell-push "alpha"))))
          (should (ebp-test--wait
                   (lambda ()
                     (cl-find-if
                      (lambda (m)
                        (and (equal (alist-get 'method m) "surface.update")
                             (equal (alist-get 'surface (alist-get 'params m))
                                    "app:alpha")))
                      (funcall (plist-get server :received))))))
          (jetpacs-teardown-owner "alpha")
          (should (ebp-test--wait
                   (lambda ()
                     (cl-find-if
                      (lambda (m)
                        (and (equal (alist-get 'method m) "surface.remove")
                             (equal (alist-get 'surface (alist-get 'params m))
                                    "app:alpha")))
                      (funcall (plist-get server :received))))))
          (should (null jetpacs-shell--pending-removals))
          (should-not (alist-get "app:alpha" jetpacs-shell--roots
                                 nil nil #'equal)))
      (when client (ignore-errors (ebp-client-close client 'test-done)))
      (jetpacs-detach)
      (jetpacs-test-reset-state)
      (funcall (plist-get server :stop)))))

(provide 'jetpacs-teardown-test)
;;; jetpacs-teardown-test.el ends here
