;;; jetpacs-design-lab-test.el --- Design Lab acceptance tests -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later

;;; Code:

(require 'ert)
(require 'cl-lib)
(require 'jetpacs-design-lab)

(defun jetpacs-design-lab-test--nodes (tree &optional type)
  "Return typed nodes below TREE, optionally restricted to TYPE."
  (let (nodes)
    (cl-labels ((walk (value)
                  (cond
                   ((vectorp value) (mapc #'walk (append value nil)))
                   ((and (listp value) (keywordp (car value)))
                    (when (and (stringp (plist-get value :t))
                               (or (null type)
                                   (equal type (plist-get value :t))))
                      (push value nodes))
                    (cl-loop for (_key child) on value by #'cddr
                             do (walk child)))
                   ((listp value) (mapc #'walk value)))))
      (walk tree))
    (nreverse nodes)))

(ert-deftest jetpacs-design-lab-is-separate-and-runtime-optional ()
  (let ((entry (assoc jetpacs-design-lab-owner jetpacs-apps--registry)))
    (should entry)
    (should (equal (plist-get (cdr entry) :requires-extensions)
                   '("jetpacs.components")))
    (should-not (member "jetpacs.design"
                        (plist-get (cdr entry) :requires-extensions)))))

(ert-deftest jetpacs-design-lab-source-is-inert-and-round-trips ()
  (let* ((profile (jetpacs-design-lab--baseline-profile))
         (source (jetpacs-authoring-print profile (* 256 1024))))
    (should (equal (jetpacs-design-lab--parse-source source) profile))
    (should-error
     (jetpacs-design-lab--parse-source
      "#.(progn (setq jpdesign-pwned t) nil)"))))

(ert-deftest jetpacs-design-lab-visual-edit-is-digest-addressed-and-atomic ()
  (let* ((jetpacs-design-lab--draft (jetpacs-design-lab--baseline-profile))
         (args (list :domain "typography" :item "body" :field "size"
                     :value "17")))
    (let ((edited (jetpacs-design-lab--edited-profile args)))
      (should (= (plist-get
                  (cdr (assoc "body" (plist-get edited :typography)))
                  :size)
                 17))
      (should (= (plist-get
                  (cdr (assoc "body"
                              (plist-get jetpacs-design-lab--draft
                                         :typography)))
                  :size)
                 (plist-get
                  (cdr (assoc "body"
                              (plist-get (jetpacs-design-baseline-profile)
                                         :typography)))
                  :size))))
    (should-error
     (jetpacs-design-lab--edited-profile
      (plist-put (copy-sequence args) :value "not-a-number")))))

(ert-deftest jetpacs-design-lab-builds-all-sections-and-seven-previews ()
  (let ((jetpacs-design-lab--draft (jetpacs-design-lab--baseline-profile))
        (jetpacs-design-lab--draft-error nil))
    (dolist (section (mapcar #'cdr jetpacs-design-lab--sections))
      (let ((jetpacs-design-lab--section section))
        (cl-letf (((symbol-function 'jetpacs-extension-advertised-p)
                   (lambda (&rest _) t)))
          (let ((screen (jetpacs-design-lab--screen nil)))
            (should (jetpacs-root-node-p screen))
            (should (< (jetpacs-node-wire-bytes screen) (* 4 1024 1024)))))))
    (cl-letf (((symbol-function 'jetpacs-extension-advertised-p)
               (lambda (&rest _) t)))
      (let ((preview (jetpacs-design-lab--preview-content)))
        (dolist (type '("jetpacs.action" "jetpacs.choice" "jetpacs.tabs"
                        "jetpacs.section_navigator" "jetpacs.panel"
                        "text_input" "editor"))
          (should (jetpacs-design-lab-test--nodes preview type)))
        (should (jetpacs-design-lab-test--nodes
                 preview "jetpacs.design_scope"))))
    (cl-letf (((symbol-function 'jetpacs-extension-advertised-p)
               (lambda (&rest _) nil)))
      (should-not
       (jetpacs-design-lab-test--nodes
        (jetpacs-design-lab--preview-content) "jetpacs.design_scope")))))

(provide 'jetpacs-design-lab-test)
;;; jetpacs-design-lab-test.el ends here
