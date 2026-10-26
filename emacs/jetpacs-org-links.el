;;; jetpacs-org-links.el --- Org links that open on the device -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later
;; Package-Requires: ((emacs "30.1"))

;;; Commentary:

;; One rule for every applet that renders Org text: a link the device's
;; own apps can open — web, mail, phone, voicemail, messages, maps, the
;; system schemes Orgzly Revived hands to Android — is tapped straight
;; into the `uri.open' builtin (EBP amendment #208) and opens on the
;; device; any other link (`id:', `file:', internal targets, custom
;; types) keeps the app's own follow action, which resolves it in Emacs.
;;
;; Org knows `http', `https' and `mailto' but not `tel', `sms' and the
;; rest, so a bare "tel:555-0199" was not even a link.  This module
;; registers them, so Org's parser, its plain-link regexp and every
;; renderer built on them agree on what a link is.  Following one inside
;; Emacs falls back to `browse-url', which on Android Emacs is the
;; platform's own viewer.
;;
;; Internal links are Org's own: `jetpacs.org.follow' runs
;; `org-open-at-point'.  An `id:' link, though, only resolves when Org's
;; ID index already knows the file, and a note in a notebook nobody has
;; visited is not indexed, so the tap failed.  Before following,
;; `jetpacs-org-link-prepare' scans the applet's Org roots (the source
;; file's directory and `ebp-org-roots') into the index, as Orgzly
;; Revived searches every notebook for the note with that ID.

;;; Code:

(require 'cl-lib)
(require 'org)
(require 'ol)
(require 'jetpacs-widgets)               ; jetpacs-uri-open, jetpacs-uri-open-p
(require 'jetpacs-surfaces)              ; jetpacs-builtin-advertised-p
(require 'org-id)

(defconst jetpacs-org-link-system-types
  '("http" "https" "mailto" "tel" "voicemail" "geo" "sms" "smsto" "mms" "mmsto")
  "Link types the device's own apps open: Orgzly Revived's system schemes.")

(defun jetpacs-org-link--follow-system (path _arg)
  "Follow a system link from inside Emacs; PATH is the part after the colon.
The link's own type is read back from Org, so one follower serves all."
  (let ((type (org-element-property :type (org-element-context))))
    (browse-url (concat (or type "https") ":" path))))

(dolist (type jetpacs-org-link-system-types)
  (unless (assoc type org-link-parameters)
    (org-link-set-parameters type :follow #'jetpacs-org-link--follow-system)))

(defun jetpacs-org-link-system-p (link)
  "Non-nil when LINK, a raw link string, is one the device's apps open."
  (and (stringp link)
       (string-match "\\`\\([A-Za-z][A-Za-z0-9+.-]*\\):." link)
       (member (downcase (match-string 1 link)) jetpacs-org-link-system-types)
       t))

(defun jetpacs-org-link-action (link fallback)
  "The tap action for LINK: `uri.open' for a system link, else FALLBACK.
LINK is the raw link string; FALLBACK the app's own follow descriptor,
used for every other link and on a Companion without `uri.open'."
  (if (and (jetpacs-org-link-system-p link)
           (jetpacs-uri-open-p link)
           (jetpacs-builtin-advertised-p "uri.open"))
      (jetpacs-uri-open link)
    fallback))

;;;; Internal links

(declare-function ebp-org--roots "ebp-org" ())

(defcustom jetpacs-org-link-id-scan-limit 2000
  "The most Org files an `id:' lookup scans under the Org roots."
  :type 'natnum :group 'jetpacs)

(defun jetpacs-org-link--candidate-files (source-buffer)
  "Org files an `id:' link from SOURCE-BUFFER may name.
SOURCE-BUFFER's own directory first, then every Org root, recursively,
skipping hidden directories, capped at `jetpacs-org-link-id-scan-limit'."
  (let* ((own (and (buffer-live-p source-buffer)
                   (buffer-file-name source-buffer)
                   (file-name-directory (buffer-file-name source-buffer))))
         (dirs (delete-dups
                (seq-filter #'file-directory-p
                            (delq nil (cons own (and (fboundp 'ebp-org--roots)
                                                       (ignore-errors (ebp-org--roots))))))))
         files)
    (catch 'full
      (dolist (dir dirs)
        (dolist (file (ignore-errors
                        (directory-files-recursively
                         dir "\\.org\\'" nil
                         (lambda (d) (not (string-prefix-p "." (file-name-nondirectory d)))))))
          (unless (member file files)
            (push file files)
            (when (>= (length files) jetpacs-org-link-id-scan-limit)
              (throw 'full nil))))))
    (nreverse files)))

(defun jetpacs-org-link-ensure-id (id &optional source-buffer)
  "The file holding Org ID, indexing the Org roots when Org does not know it.
SOURCE-BUFFER, default the current buffer, anchors the search.  Returns
nil when no candidate file declares ID; never signals."
  (with-current-buffer (if (buffer-live-p source-buffer) source-buffer (current-buffer))
    (condition-case nil
        ;; Not `org-id-find-id-file': it answers the current file for an
        ;; ID it does not know, which hides exactly the miss to repair.
        (cl-flet ((known () (and (hash-table-p org-id-locations)
                                 (gethash id org-id-locations))))
          (unless (hash-table-p org-id-locations) (org-id-locations-load))
          (or (known)
              (let ((org-id-track-globally t))
                (org-id-update-id-locations
                 (jetpacs-org-link--candidate-files (current-buffer)) t)
                (known))
              (and buffer-file-name
                   (org-id-find-id-in-file id buffer-file-name)
                   buffer-file-name)))
      (error nil))))

(defun jetpacs-org-link-prepare ()
  "Make the link at point followable by `org-open-at-point'.
An `id:' link gets its ID indexed from the Org roots first."
  (let ((context (org-element-context)))
    (when (and (eq (org-element-type context) 'link)
               (equal (org-element-property :type context) "id"))
      (let ((id (car (split-string (org-element-property :path context) "::"))))
        (jetpacs-org-link-ensure-id id (current-buffer))))))

(provide 'jetpacs-org-links)
;;; jetpacs-org-links.el ends here
