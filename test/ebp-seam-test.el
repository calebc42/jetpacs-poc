;;; ebp-seam-test.el --- RF-3 live tenant over the --echo host -*- lexical-binding: t; -*-
;; SPDX-License-Identifier: GPL-3.0-or-later

;;; Commentary:

;; RF-3(E2): the extension seam's live gate (PLAN-rf3-seam.md, parent
;; gate (2)) — the jetpacs.echo tenant round-trips elisp<->Kotlin over
;; the RF-2.6 host launched WITH --echo.  One ping proves both message
;; classes through both ends of the seam: an Emacs->Companion request
;; dispatched by the Kotlin ModuleHandler registry, and the
;; Companion->Emacs pulse notification dispatched by ebp.el's module
;; registration.  Gate (3)'s live half rides here too: the same probe
;; without negotiation answers the pinned §7.3 shape from a real engine.
;;
;; A separate suite, not an ebp-host-test.el extension: the two launch
;; DIFFERENT host processes (with/without --echo), and the RF-2.6 suite
;; must stay green untouched (parent gate (1)).  The harness plumbing is
;; shared by loading ebp-host-test.el first — see run-tests.sh.
;;
;; Opt-in exactly like the host suite: unset EBP_HOST_LAUNCH skips
;; everything.  In CI the loopback job (RF-1c) sets it, so this suite
;; runs live on every PR — gate (2) has a CI home from birth.

;;; Code:

(require 'ert)
(require 'cl-lib)
(require 'ebp)
(require 'ebp-host-test)

(defvar ebp-seam-test--host nil
  "The --echo host plist (:port :process :stop), one per suite run.")

(defun ebp-seam-test--host ()
  "The shared --echo host, started on first use, stopped at `kill-emacs'."
  (or ebp-seam-test--host
      (progn
        (setq ebp-seam-test--host
              (ebp-host-test--start-host
               (concat ebp-host-test--launch " --echo")))
        (add-hook 'kill-emacs-hook #'ebp-seam-test--stop-host)
        ebp-seam-test--host)))

(defun ebp-seam-test--stop-host ()
  (when ebp-seam-test--host
    (funcall (plist-get ebp-seam-test--host :stop))
    (setq ebp-seam-test--host nil)))

(defun ebp-seam-test--connect (&rest extra)
  "Dial the --echo host with the KAT identity; EXTRA overrides first."
  (apply #'ebp-connect "127.0.0.1" (plist-get (ebp-seam-test--host) :port)
         (append extra
                 (list :client-name "seam-test" :client-version "0.0.1"
                       :pairing-id ebp-host-test--kat-pid
                       :token ebp-host-test--kat-token
                       :wants '("theme")
                       :client-nonce ebp-host-test--kat-cn
                       :receipt-file (make-temp-file "ebp-seam-receipts")))))

(defmacro ebp-seam-test--with-ready-client (spec &rest body)
  "Bind SPEC = (CLIENT-VAR &rest CONNECT-ARGS), handshake done; skip sans host."
  (declare (indent 1))
  (pcase-let ((`(,client-var . ,connect-args) spec))
    `(progn
       (skip-unless ebp-host-test--launch)
       (let ((,client-var (ebp-seam-test--connect ,@connect-args)))
         (unwind-protect
             (progn
               (should (ebp-host-test--wait
                        (lambda () (eq (ebp-client-state ,client-var) 'ready))))
               ,@body)
           (ignore-errors (ebp-client-close ,client-var 'test-done)))))))

(ert-deftest ebp-seam-test-tenant-round-trip ()
  "RF-3 gate (2): jetpacs.echo round-trips elisp<->Kotlin live.
The module capability reaches `granted' through the real
wants-intersect-supported; the ping's params come back in the Kotlin
handler's result; and the pulse notification the handler asked the
engine to emit arrives at ebp.el's module-dispatched handler with the
same payload."
  (let ((pulses nil) (result nil) (err nil))
    (ebp-seam-test--with-ready-client
        (client :modules `(("jetpacs.echo" "jetpacs.echo"
                            (("jetpacs.echo.pulse"
                              . ,(lambda (_c params)
                                   (push (plist-get params :payload) pulses)))))))
      ;; The grant is the engine's own computation, not an echo of wants.
      (should (seq-contains-p (ebp-client-granted client) "jetpacs.echo"))
      (should (seq-contains-p (ebp-client-granted client) "theme"))
      (ebp-client--request client 'jetpacs\.echo\.ping '(:payload "live" :n 7)
                           (lambda (r e) (setq result r err e)))
      (should (ebp-host-test--wait (lambda () (or result err))))
      (should-not err)
      ;; The Kotlin handler echoed our params as its result.
      (should (equal (plist-get result :payload) "live"))
      (should (equal (plist-get result :n) 7))
      ;; ...and the pulse crossed back with the same payload.
      (should (ebp-host-test--wait (lambda () pulses)))
      (should (equal pulses '("live")))
      (should (eq (ebp-client-state client) 'ready)))))

(ert-deftest ebp-seam-test-unnegotiated-is-unknown-live ()
  "RF-3 gate (3), the live half: the tenant method WITHOUT negotiation
answers the pinned §7.3 shape from a REAL engine that carries the
module.  No local module is registered, so the outbound gate stays
open and the probe reaches the wire."
  (let ((result nil) (err nil))
    (ebp-seam-test--with-ready-client (client)
      (should-not (seq-contains-p (ebp-client-granted client) "jetpacs.echo"))
      (ebp-client--request client 'jetpacs\.echo\.ping '(:payload "x")
                           (lambda (r e) (setq result r err e)))
      (should (ebp-host-test--wait (lambda () (or result err))))
      (should err)
      (should (= (plist-get err :code) -32601))
      (should (equal (plist-get err :message) "Method not found"))
      (should (equal (plist-get (plist-get err :data) :kind)
                     "method-not-found"))
      (should (eq (ebp-client-state client) 'ready)))))

(ert-deftest ebp-seam-test-registered-but-unsupported-gates-outbound ()
  "A module registered locally whose capability the Companion does not
support: the REAL granted computation omits it, and the outbound gate
then refuses the send with `ebp-ungranted' before it can reach the
wire.  Dials the PLAIN host (no --echo) — the shared RF-2.6 one."
  (skip-unless ebp-host-test--launch)
  (let ((client (ebp-host-test--connect
                 :modules '(("jetpacs.echo" "jetpacs.echo"
                             (("jetpacs.echo.pulse" . ignore)))))))
    (unwind-protect
        (progn
          (should (ebp-host-test--wait
                   (lambda () (eq (ebp-client-state client) 'ready))))
          ;; wants carried jetpacs.echo; the plain host supports only
          ;; theme + surfaces.dialog, so the engine's intersection
          ;; dropped it — SPEC 10.2's silent omission, live.
          (should-not (seq-contains-p (ebp-client-granted client)
                                      "jetpacs.echo"))
          (should-error (ebp-client-notify client 'jetpacs\.echo\.ping
                                           '(:payload "x"))
                        :type 'ebp-ungranted))
      (ignore-errors (ebp-client-close client 'test-done)))))

(provide 'ebp-seam-test)
;;; ebp-seam-test.el ends here
