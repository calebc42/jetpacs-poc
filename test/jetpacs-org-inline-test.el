;;; jetpacs-org-inline-test.el --- Org inline markup as spans -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later

;;; Commentary:

;; `jetpacs-org-inline-spans' and `jetpacs-org-inline-prose': emphasis,
;; literal contexts, links and footnote references, the id-title hook,
;; the link tally and the shared budget.

;;; Code:

(require 'ert)
(require 'cl-lib)
(require 'jetpacs-org-inline)

(defun jetpacs-org-inline-test--span (spans text)
  "The first of SPANS whose text is TEXT."
  (cl-find text spans :key (lambda (span) (plist-get span :text)) :test #'equal))

(ert-deftest jetpacs-org-inline-emphasis-hides-markers-and-keeps-styles ()
  "All six emphasis forms hide markers; nesting combines native styles."
  (with-temp-buffer
    (insert "A *bold /nested/*, /italic/, _underline_, +retired+, ~code~, and =verbatim= end.\n")
    (org-mode)
    (let* ((before (buffer-string))
           (node (jetpacs-org-inline-prose before))
           (spans (append (plist-get node :spans) nil))
           (plain (mapconcat (lambda (span) (plist-get span :text)) spans "")))
      (should (equal plain "A bold nested, italic, underline, retired, code, and verbatim end.\n"))
      (dolist (entry `(("bold " :font_weight "bold") ("nested" :italic t)
                       ("nested" :font_weight "bold") ("italic" :italic t)
                       ("underline" :underline t) ("code" :mono t) ("verbatim" :mono t)
                       ("code" :color "secondary") ("verbatim" :color "secondary")
                       ("code" :bg ,jetpacs-org-inline-literal-background)
                       ("verbatim" :bg ,jetpacs-org-inline-literal-background)))
        (let ((span (jetpacs-org-inline-test--span spans (car entry))))
          (should span)
          (should (equal (plist-get span (cadr entry)) (caddr entry)))))
      (should (equal node (jetpacs-org-inline-prose before)))
      (should (equal-including-properties before (buffer-string))))))

(ert-deftest jetpacs-org-inline-preserves-literal-contexts ()
  "Org owns boundaries: code, examples, metadata, URLs and unmatched markers
stay literal; no budget or no rich text keeps the source text."
  (with-temp-buffer
    (insert "*bold* and ~*literal*~.\n\n#+begin_src text\n*source* /source/\n#+end_src\n\n#+begin_example\n*example* =example=\n#+end_example\n\n: *fixed*\n\n#+PROPERTY: MARK *metadata*\n\nhttps://example.org/a/b and snake_case and 2*3 and *unclosed\n")
    (org-mode)
    (let* ((text (buffer-string))
           (node (jetpacs-org-inline-prose text))
           (plain (mapconcat (lambda (span) (plist-get span :text)) (plist-get node :spans) "")))
      (should (string-prefix-p "bold and *literal*." plain))
      (dolist (literal '("*source* /source/" "*example* =example=" ": *fixed*"
                         "*metadata*" "https://example.org/a/b" "snake_case" "2*3" "*unclosed"))
        (should (string-search literal plain)))
      (let ((jetpacs-buffer-budget (cons 0 10000)))
        (should (equal text (plist-get (jetpacs-org-inline-prose text) :text)))
        (should (equal jetpacs-buffer-budget '(0 . 10000))))
      (cl-letf (((symbol-function 'jetpacs-node-advertised-p) (lambda (_type) nil)))
        (should (equal text (plist-get (jetpacs-org-inline-prose text) :text)))))))

(ert-deftest jetpacs-org-inline-links-and-footnotes-follow-exposed-positions ()
  "With a source position, links and references tap their real positions;
an id link asks the title hook first; the tally and budget are charged."
  (with-temp-buffer
    (rename-buffer "inline-test.org" t)
    (insert "* Lab\nSee [[id:abc-123]] and [[https://example.com/api/]], note[fn:1].\n")
    (org-mode)
    (let* ((start (save-excursion (goto-char (point-min)) (forward-line) (point)))
           (text (buffer-substring-no-properties start (point-max)))
           (jetpacs-org-inline-id-title-functions
            (list (lambda (id _buffer) (and (equal id "abc-123") "Target note"))))
           (jetpacs-org-inline-link-tally 0)
           (jetpacs-buffer-budget (cons 100 100000))
           (spans (jetpacs-org-inline-spans text nil nil nil start))
           (target (jetpacs-org-inline-test--span spans "Target note"))
           (web (jetpacs-org-inline-test--span spans "example.com/api"))
           (note (jetpacs-org-inline-test--span spans "[1]")))
      (should (equal (plist-get target :color) "primary"))
      (should (equal (plist-get web :color) "secondary"))
      (should (equal (plist-get note :color) "primary"))
      (dolist (entry `((,target . "jetpacs.org.follow") (,web . "jetpacs.org.follow")
                       (,note . "jetpacs.org.footnote.open")))
        (let* ((tap (plist-get (car entry) :on_tap))
               (args (plist-get tap :args))
               (pos (plist-get args :pos)))
          (should (equal (plist-get tap :action) (cdr entry)))
          (should (equal (plist-get args :buffer) (buffer-name)))
          (should (= (plist-get args :tick) (buffer-chars-modified-tick)))
          (should (jetpacs-buffer-exposed-p (buffer-name) pos (cdr entry)))))
      (should (= jetpacs-org-inline-link-tally 2))
      (should (= (car jetpacs-buffer-budget) (- 100 (length spans))))
      (should (< (cdr jetpacs-buffer-budget) 100000))
      ;; Without the hook's answer an unindexed id keeps a short label.
      (let ((jetpacs-org-inline-id-title-functions nil))
        (should (jetpacs-org-inline-test--span
                 (jetpacs-org-inline-spans text nil nil nil start)
                 "Heading abc-123"))))))

(provide 'jetpacs-org-inline-test)
;;; jetpacs-org-inline-test.el ends here
