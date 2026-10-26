;;; jetpacs-org-links-test.el --- system links open on the device -*- lexical-binding: t; -*-
;; SPDX-License-Identifier: GPL-3.0-or-later
;;; Code:
(require 'ert)
(require 'jetpacs-org-links)
(require 'jetpacs-org-inline)

(ert-deftest jetpacs-org-links-register-revived-system-schemes ()
  "tel, sms and the rest are Org links now, bare or bracketed."
  (with-temp-buffer
    (org-mode)
    (insert "Dial tel:555-0199 or text sms:555-0199, mail mailto:a@b.org, map geo:1,2 and [[id:abc][a note]].")
    (should (equal (org-element-map (org-element-parse-buffer) 'link
                     (lambda (l) (org-element-property :type l)))
                   '("tel" "sms" "mailto" "geo" "id")))))

(ert-deftest jetpacs-org-links-system-links-tap-into-uri-open ()
  "A system link is the uri.open builtin; id:, file: and the rest keep the
app's follow action, as does any link on a Companion without uri.open."
  (let ((fallback (jetpacs-action "app.follow")))
    (cl-letf (((symbol-function 'jetpacs-builtin-advertised-p) (lambda (&rest _) t)))
      (dolist (link '("tel:555-0199" "sms:555-0199" "mailto:a@b.org" "https://x.org/y" "GEO:1,2"))
        (should (equal (plist-get (jetpacs-org-link-action link fallback) :builtin) "uri.open")))
      (dolist (link '("id:abc" "file:notes.org" "file:notes.org::*Heading" "elisp:(foo)" "https://u@x.org/"))
        (should (eq (jetpacs-org-link-action link fallback) fallback))))
    (cl-letf (((symbol-function 'jetpacs-builtin-advertised-p) #'ignore))
      (should (eq (jetpacs-org-link-action "tel:555-0199" fallback) fallback)))))

(ert-deftest jetpacs-org-links-inline-prose-uses-them ()
  "The shared inline renderer gives a tel link the builtin and an id link
the follow verb."
  (cl-letf (((symbol-function 'jetpacs-builtin-advertised-p) (lambda (&rest _) t)))
    (with-temp-buffer
      (rename-buffer "links-test.org" t)
      (org-mode)
      (insert "Call tel:555-0199 or see [[id:abc][that note]].")
      (let* ((spans (jetpacs-org-inline-spans (buffer-string) nil nil nil (point-min)))
             (taps (delq nil (mapcar (lambda (s) (plist-get s :on_tap)) spans))))
        (should (equal (plist-get (car taps) :builtin) "uri.open"))
        (should (equal (plist-get (car taps) :uri) "tel:555-0199"))
        (should (equal (plist-get (cadr taps) :action) "jetpacs.org.follow"))))))
(provide 'jetpacs-org-links-test)
