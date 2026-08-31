;;; jetpacs-ebp-complete-integration-test.el --- completion wiring -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later

;;; Code:

(require 'ert)
(require 'cl-lib)
(require 'ebp)
(require 'ebp-complete)
(require 'jetpacs-widgets)
(require 'jetpacs-async)
(require 'jetpacs-surfaces)

(ert-deftest jetpacs-ebp-complete-connect-installs-the-default ()
  "`jetpacs-connect' installs EBP completion unless the caller overrides it."
  (cl-letf (((symbol-function 'ebp-connect)
             (lambda (_host _port &rest config)
               (apply #'ebp-client-create config))))
    (let ((client (jetpacs-connect
                   "127.0.0.1" 0
                   :receipt-file (make-temp-file "jc5-connect-a"))))
      (unwind-protect
          (should (eq (plist-get (ebp-client-config client)
                                 :edit-complete-function)
                      #'ebp-complete-edit-complete))
        (jetpacs-detach)
        (jetpacs-test-reset-state)))
    (let ((client (jetpacs-connect
                   "127.0.0.1" 0
                   :receipt-file (make-temp-file "jc5-connect-b")
                   :edit-complete-function #'ignore)))
      (unwind-protect
          (should (eq (plist-get (ebp-client-config client)
                                 :edit-complete-function)
                      #'ignore))
        (jetpacs-detach)
        (jetpacs-test-reset-state)))))

(provide 'jetpacs-ebp-complete-integration-test)
;;; jetpacs-ebp-complete-integration-test.el ends here
