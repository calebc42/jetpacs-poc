;;; jetpacs-dialog-slot-test.el --- One live dialog per owner -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later

;;; Commentary:

;; The owner-keyed dialog slot: owners never abandon each other's dialogs,
;; a second show by the same owner abandons the first, the abandoned
;; dialog's late 1301 leaves the successor alone, a slot needs an owner,
;; and the owner's teardown abandons whatever is still live.

;;; Code:

(require 'ert)
(require 'cl-lib)
(require 'jetpacs-dialog-slot)
(require 'jetpacs-shell)

(defmacro jetpacs-dialog-slot-test--with-recorder (abandoned shown ids &rest body)
  "Stub the dialog transport; record ABANDONED ids and SHOWN callbacks.
IDS is the list of request ids the stubbed show hands out in order."
  (declare (indent 3))
  `(progn
     (jetpacs-dialog-slot-reset)
     (let* ((,abandoned nil) (,shown nil) (pending ,ids)
            (client (ebp-client-create
                     :receipt-file (make-temp-file "jetpacs-dialog-slot"))))
       (cl-letf (((symbol-function 'jetpacs-client) (lambda () client))
                 ((symbol-function 'jetpacs-connected-p) #'ignore)
                 ((symbol-function 'ebp-client-abandon)
                  (lambda (_client id) (push id ,abandoned)))
                 ((symbol-function 'ebp-client-dialog-show)
                  (lambda (_client _id _spec &rest keys)
                    (push (plist-get keys :callback) ,shown)
                    (pop pending))))
         (unwind-protect (progn ,@body)
           (jetpacs-dialog-slot-reset))))))

(ert-deftest jetpacs-dialog-slot-single-writer-per-owner ()
  "Owners never abandon each other; the same owner's second show abandons
its first, and the first's late 1301 does not clear the successor."
  (jetpacs-dialog-slot-test--with-recorder abandoned shown '("r1" "r2" "r3")
    (jetpacs-dialog-slot-show "a" nil :owner "one" :params '(:surface "s1"))
    (with-jetpacs-owner "two"
      (jetpacs-dialog-slot-show "b" nil :params '(:surface "s2")))
    (should-not abandoned)
    (should (equal (jetpacs-dialog-slot-params "one") '(:surface "s1")))
    (should (equal (jetpacs-dialog-slot-params "two") '(:surface "s2")))
    (jetpacs-dialog-slot-show "c" nil :owner "one" :params '(:surface "s3"))
    (should (equal abandoned '("r1")))
    (funcall (nth 2 shown) "error" nil '(:code 1301))
    (should (equal (jetpacs-dialog-slot-params "one") '(:surface "s3")))
    (funcall (car shown) "dismissed" nil nil)
    (should-not (jetpacs-dialog-slot-live-p "one"))
    (should (jetpacs-dialog-slot-live-p "two"))
    (should-error (jetpacs-dialog-slot-show "d" nil))))

(ert-deftest jetpacs-dialog-slot-on-submit-routes-fields ()
  "A submitted conclusion hands its captured fields to ON-SUBMIT."
  (jetpacs-dialog-slot-test--with-recorder _abandoned shown '("r1")
    (let (got)
      (jetpacs-dialog-slot-show "a" nil :owner "one"
                                :on-submit (lambda (fields) (setq got fields)))
      (funcall (car shown) "submitted" '(:fields (:name "x")) nil)
      (should (equal got '(:name "x"))))))

(ert-deftest jetpacs-dialog-slot-on-conclude-sees-every-conclusion ()
  "ON-CONCLUDE receives each conclusion, with the slot already cleared."
  (jetpacs-dialog-slot-test--with-recorder _abandoned shown '("r1")
    (let (got live)
      (jetpacs-dialog-slot-show
       "a" nil :owner "one"
       :on-conclude (lambda (status result _error)
                      (setq got (list status (plist-get result :value))
                            live (jetpacs-dialog-slot-live-p "one"))))
      (funcall (car shown) "submitted" '(:value "open") nil)
      (should (equal got '("submitted" "open")))
      (should-not live))))

(ert-deftest jetpacs-dialog-slot-swept-at-teardown ()
  "Tearing down an owner abandons its live dialog and empties its slot."
  (jetpacs-dialog-slot-test--with-recorder abandoned _shown '("r9")
    (jetpacs-dialog-slot-show "x" nil :owner "delta")
    (jetpacs-teardown-owner "delta")
    (should (equal abandoned '("r9")))
    (should-not (jetpacs-dialog-slot-live-p "delta"))))

(provide 'jetpacs-dialog-slot-test)
;;; jetpacs-dialog-slot-test.el ends here
