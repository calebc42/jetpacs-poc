;;; jetpacs-credentials-test.el --- ERT for the bundled credential file -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later

(require 'ert)
(require 'jetpacs-credentials)

(defvar jetpacs-pairing-id nil)
(defvar jetpacs-pairing-token nil)

(defconst jetpacs-credentials-test--id "101112131415161718191a1b1c1d1e1f")
(defconst jetpacs-credentials-test--token "AAECAwQFBgcICQoLDA0ODw")

(defmacro jetpacs-credentials-test--with-file (content modes &rest body)
  "Run BODY with `file' bound to a temporary file holding CONTENT under MODES."
  (declare (indent 2))
  `(let ((file (make-temp-file "jetpacs-pairing")))
     (unwind-protect
         (progn
           (with-temp-file file (insert ,content))
           (set-file-modes file ,modes)
           ,@body)
       (delete-file file))))

(ert-deftest jetpacs-credentials-reads-a-private-file ()
  "A 0600 file with both lines sets the pairing variables, CRLF tolerated."
  (jetpacs-credentials-test--with-file
      (format "pairing_id=%s\r\ntoken=%s\r\n"
              jetpacs-credentials-test--id jetpacs-credentials-test--token)
      #o600
    (let ((jetpacs-credentials-file file)
          (jetpacs-pairing-id nil)
          (jetpacs-pairing-token nil))
      (should (jetpacs-credentials-apply))
      (should (equal jetpacs-pairing-id jetpacs-credentials-test--id))
      (should (equal jetpacs-pairing-token jetpacs-credentials-test--token)))))

(ert-deftest jetpacs-credentials-refuses-a-shared-file ()
  "SPEC 9.1's floor: a file other users can read is not a credential."
  (jetpacs-credentials-test--with-file
      (format "pairing_id=%s\ntoken=%s\n"
              jetpacs-credentials-test--id jetpacs-credentials-test--token)
      #o640
    (should-error (jetpacs-credentials-read file)
                  :type 'jetpacs-credentials-invalid)))

(ert-deftest jetpacs-credentials-refuses-malformed-values ()
  "Either value outside its SPEC 9.1 grammar is refused, as is a missing line."
  (dolist (content (list (format "pairing_id=%s\n" jetpacs-credentials-test--id)
                         (format "token=%s\n" jetpacs-credentials-test--token)
                         (format "pairing_id=%s\ntoken=%s==\n"
                                 jetpacs-credentials-test--id
                                 jetpacs-credentials-test--token)
                         (format "pairing_id=%S\ntoken=%s\n"
                                 "ABC" jetpacs-credentials-test--token)))
    (jetpacs-credentials-test--with-file content #o600
      (should-error (jetpacs-credentials-read file)
                    :type 'jetpacs-credentials-invalid))))

(ert-deftest jetpacs-credentials-apply-without-a-file-changes-nothing ()
  "No file configured means the init's own values stand."
  (let ((jetpacs-credentials-file nil)
        (jetpacs-pairing-id "keep")
        (jetpacs-pairing-token "keep"))
    (should-not (jetpacs-credentials-apply))
    (should (equal jetpacs-pairing-id "keep"))))

(provide 'jetpacs-credentials-test)
;;; jetpacs-credentials-test.el ends here
