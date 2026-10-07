;;; jetpacs-profile-snapshot.el --- Remember what the last Companion advertised -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later

;;; Commentary:

;; The welcome's negotiated facts — `surface_profiles', the granted
;; capabilities, the limits and the locale advice — live on the client
;; struct and die with the session.  An authoring agent working against the
;; static catalog therefore sees every node the platform knows, not the ones
;; the owner's Companion actually advertises.  shadcn's skill injects the
;; project's live configuration at load time (`shadcn info --json');
;; the ebp analogue of "the project" is the negotiated profile, so this
;; module writes it down once per session:
;;
;;   ~/.emacs.d/jetpacs/last-profile.json
;;
;; `tools/jetpacs-info.sh' and the `jetpacs_info' MCP tool read it back as
;; `last_profile'.  Presentation advice only — nothing here gates a push;
;; the live gates keep reading the client (SPEC 10.2).  The file lives in
;; Jetpacs's removable state tree beside `jetpacs/apps.el' and never holds
;; a secret: no nonce, no proof, no session identity.

;;; Code:

(require 'cl-lib)
(require 'json)
(require 'ebp)
(require 'jetpacs-widgets)
(require 'jetpacs-surfaces)

(defgroup jetpacs-profile-snapshot nil
  "The last negotiated Companion profile, written for authoring tools."
  :group 'jetpacs)

(defcustom jetpacs-profile-snapshot-file
  (expand-file-name "jetpacs/last-profile.json" user-emacs-directory)
  "Where the last welcome's negotiated facts are written.
Nil disables the snapshot."
  :type '(choice (const :tag "Disabled" nil) file)
  :group 'jetpacs-profile-snapshot)

(defun jetpacs-profile-snapshot-data (client)
  "The snapshot plist for CLIENT: profiles, granted, limits, locale and a stamp.
Values are the client's own decoded welcome members, copied."
  (list :profiles (copy-tree (ebp-client-profiles client))
        :granted (vconcat (copy-sequence (ebp-client-granted client)))
        :limits (copy-tree (ebp-client-limits client))
        :locale (copy-tree (ebp-client-locale client))
        :at (format-time-string "%FT%TZ" nil t)))

(defun jetpacs-profile-snapshot-json (data)
  "DATA as canonical EBP JSON text (sorted keys, multibyte)."
  (let ((text (jetpacs-node->canonical-json data)))
    (concat (if (multibyte-string-p text) text (decode-coding-string text 'utf-8))
            "\n")))

(defun jetpacs-profile-snapshot-write (client)
  "Write CLIENT's negotiated facts to `jetpacs-profile-snapshot-file'.
A `jetpacs-ready-functions' member: runs once a session reaches READY,
when the welcome has been applied.  Returns the file written, or nil.
Never signals — a snapshot that cannot be written is a `message', not a
reason to lose the session."
  (when jetpacs-profile-snapshot-file
    (condition-case err
        (let ((file jetpacs-profile-snapshot-file))
          (make-directory (file-name-directory file) t)
          (with-temp-file file
            (set-buffer-file-coding-system 'utf-8-unix)
            (insert (jetpacs-profile-snapshot-json
                     (jetpacs-profile-snapshot-data client))))
          file)
      (error
       (message "jetpacs-profile-snapshot: not written: %s" (error-message-string err))
       nil))))

(defun jetpacs-profile-snapshot-read (&optional file)
  "The snapshot at FILE (default `jetpacs-profile-snapshot-file') as a hash table.
Nil when there is none or it does not parse."
  (let ((file (or file jetpacs-profile-snapshot-file)))
    (when (and file (file-exists-p file))
      (ignore-errors
        (json-parse-string
         (with-temp-buffer (insert-file-contents file) (buffer-string))
         :object-type 'hash-table :array-type 'array
         :null-object :json-null :false-object :json-false)))))

(add-hook 'jetpacs-ready-functions #'jetpacs-profile-snapshot-write)

(provide 'jetpacs-profile-snapshot)
;;; jetpacs-profile-snapshot.el ends here
