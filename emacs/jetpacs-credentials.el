;;; jetpacs-credentials.el --- Pairing credential file of a bundled engine -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later
;; Package-Requires: ((emacs "30.1"))

;;; Commentary:

;; EBP SPEC 9.1 under amendment #209: in a bundled deployment the engine
;; supervisor delivers this Emacs endpoint's copy of the pairing through an
;; application-private file instead of through the user.  The file holds
;; two lines, `pairing_id=' (32 lowercase hex characters) and `token=' (the
;; 22-character base64url display form).  It must be readable by this user
;; alone, and it is read before every connect, so a re-provisioned pairing
;; takes effect on the next dial without a restart.  Nothing here evaluates
;; the file: it is data, checked against the SPEC's two grammars.

;;; Code:

(defvar jetpacs-pairing-id)
(defvar jetpacs-pairing-token)

(defvar jetpacs-credentials-file nil
  "File holding this endpoint's pairing id and token, or nil.
A bundled engine's init names it and `jetpacs-credentials-apply' reads it
before every connect.  Nil leaves `jetpacs-pairing-id' and
`jetpacs-pairing-token' as the user's init set them.")

(define-error 'jetpacs-credentials-invalid
  "Jetpacs pairing credential file is unusable")

(defconst jetpacs-credentials--id-regexp "\\`[0-9a-f]\\{32\\}\\'"
  "SPEC 9.1: the pairing ID as 32 lowercase hexadecimal characters.")

(defconst jetpacs-credentials--token-regexp "\\`[A-Za-z0-9_-]\\{22\\}\\'"
  "SPEC 9.1: the token's 22-character base64url display form, unpadded.")

(defun jetpacs-credentials--line-value (line prefix)
  "Return the value after PREFIX in LINE, or nil when LINE is another line."
  (when (string-prefix-p prefix line)
    (let ((value (substring line (length prefix))))
      (if (string-suffix-p "\r" value) (substring value 0 -1) value))))

(defun jetpacs-credentials-read (file)
  "Return the pairing in FILE as a plist (:pairing-id ID :token TOKEN).
Signal `jetpacs-credentials-invalid' when FILE is readable by other users
\(SPEC 9.1's floor), lacks either line, or holds a malformed value."
  (let ((modes (file-modes file)))
    (when (and modes (/= 0 (logand modes #o077)))
      (signal 'jetpacs-credentials-invalid
              (list file "readable by other users"))))
  (let (id token)
    (with-temp-buffer
      (insert-file-contents-literally file)
      (goto-char (point-min))
      (while (not (eobp))
        (let ((line (buffer-substring-no-properties
                     (line-beginning-position) (line-end-position))))
          (setq id (or (jetpacs-credentials--line-value line "pairing_id=") id)
                token (or (jetpacs-credentials--line-value line "token=") token)))
        (forward-line 1)))
    (unless (and id (string-match-p jetpacs-credentials--id-regexp id))
      (signal 'jetpacs-credentials-invalid (list file "pairing_id")))
    (unless (and token (string-match-p jetpacs-credentials--token-regexp token))
      (signal 'jetpacs-credentials-invalid (list file "token")))
    (list :pairing-id id :token token)))

(defun jetpacs-credentials-apply ()
  "Load `jetpacs-credentials-file' into the pairing variables, when set.
Return non-nil when a file was applied.  A missing or unusable file is an
error rather than a fallback: a bundled engine without its credential must
not dial with the known-answer defaults and pair with the wrong Companion."
  (when jetpacs-credentials-file
    (let ((pairing (jetpacs-credentials-read jetpacs-credentials-file)))
      (setq jetpacs-pairing-id (plist-get pairing :pairing-id)
            jetpacs-pairing-token (plist-get pairing :token))
      t)))

(provide 'jetpacs-credentials)
;;; jetpacs-credentials.el ends here
