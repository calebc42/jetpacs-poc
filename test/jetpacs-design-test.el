;;; jetpacs-design-test.el --- tests for design authoring -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later

;;; Code:

(require 'ert)
(require 'json)
(require 'jetpacs-design-material)

(ert-deftest jetpacs-design-sorts-identifier-maps-deterministically ()
  (let* ((style (jetpacs-design-style
                 `(("padding" . ,(jetpacs-design-dimension 8)))))
         (scope (jetpacs-design-scope
                 `(("z" . ,(jetpacs-design-color "#000000"))
                   ("a" . ,(jetpacs-design-color "#FFFFFF")))
                 `(("z-style" . ,style) ("a-style" . ,style))
                 (list (jetpacs-text "Hello"))))
         (tokens (plist-get scope :tokens))
         (styles (plist-get scope :styles)))
    (should (equal (cl-loop for (key _) on tokens by #'cddr collect key)
                   '(:a :z)))
    (should (equal (cl-loop for (key _) on styles by #'cddr collect key)
                   '(:a-style :z-style)))
    (should
     (equal
      (json-serialize scope :false-object :json-false :null-object nil)
      (json-serialize scope :false-object :json-false :null-object nil)))))

(ert-deftest jetpacs-design-rejects-local-authoring-errors ()
  (should-error
   (jetpacs-design-properties
    `(("padding" . ,(jetpacs-design-color "#000000")))))
  (should-error
   (jetpacs-design-scope
    nil
    `(("bad" . ,(jetpacs-design-style
                  `(("padding" . ,(jetpacs-design-token "missing"))))))
    nil))
  (should-error
   (jetpacs-design-scope
    `(("a" . ,(jetpacs-design-token "b"))
      ("b" . ,(jetpacs-design-token "a")))
    nil nil)))

(ert-deftest jetpacs-design-proof-library-builds-three-components ()
  (let* ((action (jetpacs-action "demo.run"))
         (button (jetpacs-design-material-filled-button "Run" action))
         (card (jetpacs-design-material-card (list (jetpacs-text "Card"))))
         (selector
          (jetpacs-design-material-two-option-selector
           "List" "list" "Grid" "grid" "list"
           (lambda (value) (jetpacs-action "demo.select" :args `(:value ,value)))))
         (scope (jetpacs-design-material-scope
                 (list button card selector))))
    (should (equal (plist-get button :t) "jetpacs.pressable"))
    (should (equal (plist-get card :t) "jetpacs.styled"))
    (should (equal (plist-get selector :t) "row"))
    (should (equal (plist-get scope :t) "jetpacs.design_scope"))))

(provide 'jetpacs-design-test)
;;; jetpacs-design-test.el ends here
