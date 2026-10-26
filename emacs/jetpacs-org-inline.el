;;; jetpacs-org-inline.el --- Org inline markup as native spans -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later
;; Package-Requires: ((emacs "30.1"))

;;; Commentary:

;; Org's inline objects, read by `org-element', rendered as RichText
;; spans with their delimiters hidden: bold, italic, underline, code and
;; verbatim, footnote references and links.  Strike-through loses only
;; its markers, since RichSpan has no strike decoration.
;;
;; Given the text's position in the current Org buffer, footnote
;; references open `jetpacs.org.footnote.open' and links follow through
;; `jetpacs.org.follow', each exposed at its real buffer position (SPEC
;; 23.1).  A bare link shows a readable label instead of its raw target;
;; an id link names its heading, and an app with a faster title index
;; (a note database) answers first through
;; `jetpacs-org-inline-id-title-functions'.
;;
;; The spans are charged against `jetpacs-buffer-budget' as one unit:
;; text that does not fit, or a Companion without `rich_text', gets nil,
;; and `jetpacs-org-inline-prose' then keeps the Org source text.  Only a
;; temporary presentation copy is ever modified.

;;; Code:

(require 'cl-lib)
(require 'subr-x)
(require 'org)
(require 'org-element)
(require 'org-id)
(require 'ebp-org)
(require 'jetpacs-surfaces)
(require 'jetpacs-widgets)
(require 'jetpacs-buffer)
(require 'jetpacs-org-links)             ; system links open on the device

(defconst jetpacs-org-inline-literal-background "#80808024"
  "Subtle translucent background for inline code and verbatim text.")

(defconst jetpacs-org-inline-link-label-limit 60
  "Longest readable label synthesized for a link without a description.")

(defconst jetpacs-org-inline--internal-link-types
  '("id" "file" "attachment" "fuzzy" "custom-id" "coderef" "radio")
  "Link types whose destination is Org content rather than an external app.")

(defvar jetpacs-org-inline-id-title-functions nil
  "Abnormal hook naming the heading behind an Org id, fastest first.
Each function is called with (ID SOURCE-BUFFER) and returns a non-empty
title or nil; the first title wins.  Org's own id index is the fallback.
Functions must not signal or edit.")

(defvar jetpacs-org-inline-link-tally nil
  "Count of tappable links rendered, when a caller binds it to a number.
`jetpacs-org-inline-spans' adds the links it exposed; nil counts nothing.")

(defun jetpacs-org-inline-link-color (object)
  "The theme role for link OBJECT: primary inside Org, secondary outside.
Both are roles every Companion resolves, so the distinction survives themes."
  (if (member (org-element-property :type object)
              jetpacs-org-inline--internal-link-types)
      "primary"
    "secondary"))

(defun jetpacs-org-inline--id-title (id source-buffer)
  "The heading title behind Org ID, or nil when it cannot be read cheaply.
`jetpacs-org-inline-id-title-functions' answer first.  Otherwise Org's ID
index names the file, falling back to SOURCE-BUFFER's own file, and the EBP
resolver reads the heading inside the allowlisted roots.  Never signals and
never edits."
  (or (run-hook-with-args-until-success
       'jetpacs-org-inline-id-title-functions id source-buffer)
      (condition-case nil
          (when-let* ((file (with-current-buffer source-buffer
                              (org-id-find-id-file id)))
                      (marker (ebp-org-resolve-ref
                               (list :id id :file (file-truename file)))))
            (unwind-protect
                (with-current-buffer (marker-buffer marker)
                  (org-with-wide-buffer
                   (goto-char marker)
                   (let ((title (org-get-heading t t t t)))
                     (and (stringp title) (not (string-empty-p title)) title))))
              (set-marker marker nil)))
        (error nil))))

(defun jetpacs-org-inline-link-label (object source-buffer)
  "A readable label for link OBJECT that has no description.
An id link shows the heading it names, a file link its file name and target
heading, a web link its address without the scheme.  Other types keep their
raw target.  SOURCE-BUFFER helps resolve ids declared in the same file."
  (let* ((type (org-element-property :type object))
         (path (or (org-element-property :path object) ""))
         (raw (or (org-element-property :raw-link object) path))
         (search (org-element-property :search-option object))
         (label
          (pcase type
            ("id" (or (jetpacs-org-inline--id-title path source-buffer)
                      (concat "Heading " (jetpacs-truncate-text path 9))))
            ((or "file" "attachment")
             (concat (file-name-nondirectory (directory-file-name path))
                     (when (and (stringp search) (not (string-empty-p search)))
                       (concat " › " (string-trim-left search "[*#]+")))))
            ((or "http" "https" "ftp")
             (string-remove-suffix "/" (string-remove-prefix "//" path)))
            ("fuzzy" (string-trim-left path "[*]+"))
            ((or "mailto" "custom-id" "coderef" "radio") path)
            (_ raw))))
    (jetpacs-truncate-text (if (string-empty-p label) raw label)
                           jetpacs-org-inline-link-label-limit)))

(defun jetpacs-org-inline--link-properties (object action source-buffer)
  "Mark link OBJECT in the current presentation copy as a tappable span.
ACTION is its follow descriptor.  A described link keeps its description
text, so emphasis nested inside stays styled; a bare link is replaced by a
readable label.  Return non-nil when OBJECT lay inside the copy."
  (let* ((beg (org-element-property :begin object))
         (end (- (org-element-property :end object)
                 (or (org-element-property :post-blank object) 0)))
         (cb (org-element-property :contents-begin object))
         (ce (org-element-property :contents-end object))
         (attributes (list 'jetpacs-org-inline--action action
                           'jetpacs-org-inline--color
                           (jetpacs-org-inline-link-color object)
                           'jetpacs-org-inline--underline t)))
    (when (<= (point-min) beg end (point-max))
      (if (and cb ce (< cb ce))
          (progn
            (put-text-property beg cb 'jetpacs-org-inline--hide t)
            (put-text-property ce end 'jetpacs-org-inline--hide t)
            (add-text-properties cb ce attributes))
        (set-text-properties beg end nil)
        (add-text-properties
         beg (1+ beg)
         (append (list 'jetpacs-org-inline--replacement
                       (jetpacs-org-inline-link-label object source-buffer))
                 attributes))
        (put-text-property (1+ beg) end 'jetpacs-org-inline--hide t))
      t)))

(defun jetpacs-org-inline-spans (text &optional inline weight color source-start)
  "Render Org emphasis in TEXT as native spans without its delimiters.
INLINE parses a heading or label; otherwise parse full Org elements so code,
examples, keywords and fixed-width text remain literal.  WEIGHT and COLOR
supply base styling.  SOURCE-START maps TEXT to the current Org buffer for
footnote and link taps.  Only the temporary presentation copy is modified.
Return nil when there is no emphasis or the native budget cannot fit it.
RichSpan has no strike decoration; strike-through loses only its markers."
  (when (and (jetpacs-node-advertised-p "rich_text")
             (or (string-match-p "[*_/+~=]" text)
                 (and source-start
                      (or (string-search "[fn:" text)
                          (string-match-p org-link-any-re text)))))
    (let* ((source-buffer (current-buffer))
           (tree (if inline
                     (org-element-parse-secondary-string
                      text (org-element-restriction 'paragraph))
                   (with-temp-buffer
                     (insert text)
                     (let ((org-inhibit-startup t)) (delay-mode-hooks (org-mode)))
                     (org-element-parse-buffer))))
           (objects (org-element-map tree '(bold italic underline strike-through
                                                 code verbatim footnote-reference link)
                      #'identity))
           spans exposed links)
      (when objects
        (with-temp-buffer
          (insert text)
          (dolist (object objects)
            (let* ((type (org-element-type object))
                   (beg (org-element-property :begin object))
                   (cb (or (org-element-property :contents-begin object) (1+ beg)))
                   (ce (or (org-element-property :contents-end object)
                           (+ cb (length (org-element-property :value object)))))
                   (attributes (pcase type
                                 ('bold '(jetpacs-org-inline--weight "bold"))
                                 ('italic '(jetpacs-org-inline--italic t))
                                 ('underline '(jetpacs-org-inline--underline t))
                                 ((or 'code 'verbatim)
                                  (list 'jetpacs-org-inline--mono t
                                        'jetpacs-org-inline--color "secondary"
                                        'jetpacs-org-inline--bg
                                        jetpacs-org-inline-literal-background)))))
              (when (and (not (memq type '(footnote-reference link)))
                         (<= (point-min) beg cb ce (1+ ce) (point-max)))
                (put-text-property beg cb 'jetpacs-org-inline--hide t)
                (put-text-property ce (1+ ce) 'jetpacs-org-inline--hide t)
                (when attributes (add-text-properties cb ce attributes)))))
          (when source-start
            ;; Links first: a reference's own pass below clears any link
            ;; nested inside the note text it hides.
            (dolist (object objects)
              (when (and (eq (org-element-type object) 'link)
                         (not (org-element-lineage object '(footnote-reference link))))
                (let* ((pos (+ source-start (1- (org-element-property :begin object))))
                       (action (jetpacs-org-link-action
                                (org-element-property :raw-link object)
                                (with-current-buffer source-buffer
                                  (jetpacs-action "jetpacs.org.follow"
                                                  :args (list :buffer (buffer-name) :pos pos
                                                              :tick (buffer-chars-modified-tick)))))))
                  (when (jetpacs-org-inline--link-properties object action source-buffer)
                    (push pos links)))))
            (dolist (object objects)
              (when (and (eq (org-element-type object) 'footnote-reference)
                         (not (org-element-lineage object '(footnote-reference))))
                (let* ((beg (org-element-property :begin object))
                       (end (- (org-element-property :end object)
                               (or (org-element-property :post-blank object) 0)))
                       (pos (+ source-start (1- beg)))
                       (label (or (org-element-property :label object) "note"))
                       (action (with-current-buffer source-buffer
                                 (jetpacs-action "jetpacs.org.footnote.open"
                                                 :args (list :buffer (buffer-name) :pos pos
                                                             :tick (buffer-chars-modified-tick))))))
                  (when (<= (point-min) beg end (point-max))
                    (set-text-properties beg end nil)
                    (add-text-properties beg (1+ beg)
                                         (list 'jetpacs-org-inline--replacement
                                               (concat "[" label "]")
                                               'jetpacs-org-inline--action action
                                               'jetpacs-org-inline--color "primary"
                                               'jetpacs-org-inline--underline t))
                    (put-text-property (1+ beg) end 'jetpacs-org-inline--hide t)
                    (push pos exposed))))))
          (goto-char (point-min))
          (while (< (point) (point-max))
            (let* ((beg (point))
                   (end (next-property-change beg nil (point-max))))
              (unless (get-text-property beg 'jetpacs-org-inline--hide)
                (push (jetpacs-span
                       (or (get-text-property beg 'jetpacs-org-inline--replacement)
                           (buffer-substring-no-properties beg end))
                       :font-weight (or (get-text-property beg 'jetpacs-org-inline--weight)
                                        weight)
                       :italic (get-text-property beg 'jetpacs-org-inline--italic)
                       :underline (get-text-property beg 'jetpacs-org-inline--underline)
                       :mono (get-text-property beg 'jetpacs-org-inline--mono)
                       :color (or (get-text-property beg 'jetpacs-org-inline--color) color)
                       :bg (get-text-property beg 'jetpacs-org-inline--bg)
                       :on-tap (get-text-property beg 'jetpacs-org-inline--action))
                      spans))
              (goto-char end))))
        (setq spans (nreverse spans))
        (when (jetpacs-buffer-charge
               (length spans)
               (and (cdr-safe jetpacs-buffer-budget)
                    (jetpacs-buffer-node-bytes
                     (jetpacs-rich-text spans :style "body"))))
          (with-current-buffer source-buffer
            (dolist (pos exposed)
              (jetpacs-buffer-expose (buffer-name) pos "jetpacs.org.footnote.open"))
            (dolist (pos links)
              (jetpacs-buffer-expose (buffer-name) pos "jetpacs.org.follow")))
          (when jetpacs-org-inline-link-tally
            (cl-incf jetpacs-org-inline-link-tally (length links)))
          spans)))))

(defun jetpacs-org-inline-prose (text &optional inline color source-start)
  "Render TEXT with Org emphasis hidden, optionally INLINE and in COLOR.
SOURCE-START is its original buffer position for interactive footnotes and
links.  Retain the original syntax when no emphasis or native support is
available."
  (if-let* ((spans (jetpacs-org-inline-spans text inline nil color source-start)))
      (jetpacs-rich-text spans :style "body")
    (jetpacs-text text :syntax "org" :color color)))

(provide 'jetpacs-org-inline)
;;; jetpacs-org-inline.el ends here
