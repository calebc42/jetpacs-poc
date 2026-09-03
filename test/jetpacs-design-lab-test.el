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

(defun jetpacs-design-lab-test--section (section)
  "Return SECTION's content nodes over the baseline draft."
  (let ((jetpacs-design-lab--draft (jetpacs-design-lab--baseline-profile))
        (jetpacs-design-lab--section section))
    (cl-letf (((symbol-function 'jetpacs-extension-advertised-p)
               (lambda (&rest _) t)))
      (jetpacs-design-lab--content))))

(defun jetpacs-design-lab-test--control (tree type label item &optional field index)
  "Return the TYPE control labelled LABEL editing ITEM FIELD INDEX below TREE."
  (cl-find-if
   (lambda (node)
     (let ((args (plist-get (or (plist-get node :on_change)
                                (plist-get node :on_submit))
                            :args)))
       (and (equal (plist-get node :label) label)
            (equal (plist-get args :item) item)
            (or (null field) (equal (plist-get args :field) field))
            (equal (plist-get args :index) index))))
   (jetpacs-design-lab-test--nodes tree type)))

(defun jetpacs-design-lab-test--option-values (dropdown)
  "Return DROPDOWN's option values in order."
  (mapcar (lambda (option) (plist-get option :value))
          (append (plist-get dropdown :options) nil)))

(ert-deftest jetpacs-design-lab-entries-fold-into-captioned-disclosures ()
  "Every entry is one folded disclosure whose header names and summarizes it."
  (let ((draft (jetpacs-design-lab--baseline-profile))
        ids)
    (dolist (spec '(("typography" . :typography) ("tokens" . :tokens)
                    ("styles" . :styles) ("motions" . :motions)
                    ("bindings" . :component-styles)))
      (let* ((content (jetpacs-design-lab-test--section (car spec)))
             (disclosures (jetpacs-design-lab-test--nodes content "collapsible"))
             (entries (plist-get draft (cdr spec))))
        (should (= (length disclosures) (length entries)))
        (should-not (jetpacs-design-lab-test--nodes content "jetpacs.panel"))
        (cl-loop for disclosure in disclosures
                 for entry in entries
                 do (let ((header (plist-get disclosure :header)))
                      (should (eq (plist-get disclosure :collapsed) t))
                      (should (equal (plist-get (aref (plist-get header :children) 0)
                                                :text)
                                     (car entry)))
                      (should (stringp (plist-get (aref (plist-get header :children) 1)
                                                  :text)))
                      (should (string-prefix-p "jpdesign-" (plist-get disclosure :id)))
                      (push (plist-get disclosure :id) ids)))))
    (should (= (length ids) (length (delete-dups (copy-sequence ids)))))
    (should (equal (jetpacs-design-lab--typography-caption
                    (cdr (assoc "body" (plist-get draft :typography))))
                   "system 16 / 400 · lh 24"))
    (should (equal (jetpacs-design-lab--token-caption
                    (cdr (assoc "color.primary" (plist-get draft :tokens))))
                   "theme-role · primary"))
    (should (equal (jetpacs-design-lab--motion-caption
                    (cdr (assoc "quick" (plist-get draft :motions))))
                   "120 ms · ease-out"))
    (should (string-match-p "\\`[0-9]+ properties · [0-9]+ rules"
                            (jetpacs-design-lab--style-caption
                             (cdr (assoc "base.row" (plist-get draft :styles))))))
    (should (equal (jetpacs-design-lab--style-caption '(:motion "quick"))
                   "0 properties · 0 rules · motion quick"))))

(ert-deftest jetpacs-design-lab-enum-leaves-are-dropdowns ()
  "Closed leaves offer exactly the values the design runtime accepts."
  (let* ((typography (jetpacs-design-lab-test--section "typography"))
         (tokens (jetpacs-design-lab-test--section "tokens"))
         (styles (jetpacs-design-lab-test--section "styles"))
         (motions (jetpacs-design-lab-test--section "motions"))
         (family (jetpacs-design-lab-test--control
                  typography "dropdown" "family" "body"))
         (weight (jetpacs-design-lab-test--control
                  typography "dropdown" "weight" "body"))
         (role (jetpacs-design-lab-test--control
                tokens "dropdown" "value" "color.primary"))
         (easing (jetpacs-design-lab-test--control
                  motions "dropdown" "easing" "quick"))
         (state (jetpacs-design-lab-test--control
                 styles "dropdown" "state" "base.row" "state" 0))
         (content-color (jetpacs-design-lab-test--control
                         styles "dropdown" "content_color" "base.row"))
         (padding (jetpacs-design-lab-test--control
                   styles "dropdown" "padding_horizontal" "base.row"))
         (motion (jetpacs-design-lab-test--control
                  styles "dropdown" "motion" "base.screen" "motion")))
    (should (equal (jetpacs-design-lab-test--option-values family)
                   jetpacs-design-font-families))
    (should (equal (plist-get family :value) "system"))
    (should (equal (jetpacs-design-lab-test--option-values weight)
                   '("100" "200" "300" "400" "500" "600" "700" "800" "900")))
    (should (equal (plist-get weight :value) "400"))
    (should (equal (jetpacs-design-lab-test--option-values role)
                   jetpacs-theme-roles))
    (should (equal (plist-get role :value) "primary"))
    (should (equal (jetpacs-design-lab-test--option-values easing)
                   jetpacs-design-easings))
    (should (equal (jetpacs-design-lab-test--option-values state)
                   jetpacs-design-states))
    (should (equal (plist-get state :value) "selected"))
    ;; A token reference offers only tokens the property may take.
    (should (member "color.secondary"
                    (jetpacs-design-lab-test--option-values content-color)))
    (should-not (member "space.panel"
                        (jetpacs-design-lab-test--option-values content-color)))
    (should (member "space.control-x"
                    (jetpacs-design-lab-test--option-values padding)))
    (should-not (member "color.primary"
                        (jetpacs-design-lab-test--option-values padding)))
    (should (equal (jetpacs-design-lab-test--option-values motion)
                   '("__none" "press" "quick" "selection")))
    (should (equal (plist-get motion :value) "__none"))
    (dolist (node (append (jetpacs-design-lab-test--nodes typography "text_input")
                          (jetpacs-design-lab-test--nodes motions "text_input")
                          (jetpacs-design-lab-test--nodes styles "text_input")))
      (should-not (member (plist-get node :label)
                          '("family" "weight" "easing" "state" "motion"))))
    (dolist (dropdown (list family weight role easing state content-color motion))
      (let ((descriptor (plist-get dropdown :on_change)))
        (should (equal (plist-get descriptor :action) "jpdesign.edit"))
        (should (stringp (plist-get (plist-get descriptor :args) :digest)))))
    (should (equal (plist-get (plist-get (plist-get state :on_change) :args) :index)
                   0))))

(ert-deftest jetpacs-design-lab-booleans-are-switches-on-the-boolean-action ()
  "A boolean leaf is a switch whose native value joins the string edit path."
  (let* ((styles (jetpacs-design-lab-test--section "styles"))
         (fill (jetpacs-design-lab-test--control
                styles "switch" "fill_width" "base.screen"))
         (schema (jetpacs-action-schema "jpdesign.edit.boolean"))
         forwarded)
    (should fill)
    (should (eq (plist-get fill :checked) t))
    (should (equal (plist-get (plist-get fill :on_change) :action)
                   "jpdesign.edit.boolean"))
    (should (equal (plist-get (cl-find 'value (plist-get schema :args)
                                       :key (lambda (arg) (plist-get arg :name)))
                              :type)
                   "bool"))
    (cl-letf (((symbol-function 'jetpacs-design-lab--on-edit)
               (lambda (args _params) (setq forwarded args) 'accepted)))
      (should (eq (jetpacs-design-lab--on-boolean-edit
                   '(:domain "style" :item "base.screen" :field "fill_width"
                     :value :json-false)
                   nil)
                  'accepted))
      (should (equal (plist-get forwarded :value) "false"))
      (should (eq (jetpacs-design-lab--on-boolean-edit
                   '(:domain "style" :item "base.screen" :field "fill_width"
                     :value "true")
                   nil)
                  'rejected)))))

(ert-deftest jetpacs-design-lab-number-fields-carry-keyboards-and-accept-negatives ()
  "Numeric leaves get numeric keyboards; only the number kind admits a sign."
  (let* ((typography (jetpacs-design-lab-test--section "typography"))
         (motions (jetpacs-design-lab-test--section "motions"))
         (size (jetpacs-design-lab-test--control
                typography "text_input" "size" "body"))
         (duration (jetpacs-design-lab-test--control
                    motions "text_input" "duration" "quick")))
    (should (equal (plist-get size :keyboard) "decimal"))
    (should (equal (plist-get duration :keyboard) "number"))
    (should (= (jetpacs-design-lab--number "-3" nil t) -3))
    (should-error (jetpacs-design-lab--number "-3" nil))
    (should (equal (plist-get (jetpacs-design-lab--value-with-text
                               '(:kind "number" :value "1") "-0.5")
                              :value)
                   "-0.5"))
    (should-error (jetpacs-design-lab--value-with-text
                   '(:kind "dimension" :value "1") "-0.5"))))

(ert-deftest jetpacs-design-lab-profile-picker-is-a-dropdown ()
  "Profiles are picked from one dropdown that loads the chosen draft."
  (let* ((jetpacs-design-lab--draft (jetpacs-design-lab--baseline-profile))
         (jetpacs-design-lab--selected-profile-id "jetpacs.baseline")
         (content (jetpacs-design-lab-test--section "profiles"))
         (picker (car (jetpacs-design-lab-test--nodes content "dropdown"))))
    (should (equal (plist-get picker :id) "jpdesign-profile"))
    (should (equal (plist-get picker :label) "Profile"))
    (should (equal (plist-get picker :value) "jetpacs.baseline"))
    (should (member "jetpacs.baseline"
                    (jetpacs-design-lab-test--option-values picker)))
    (should (equal (plist-get (plist-get picker :on_change) :action)
                   "jpdesign.profile"))
    (should-not
     (cl-find "jpdesign.profile"
              (jetpacs-design-lab-test--nodes content "jetpacs.action")
              :key (lambda (node) (plist-get (plist-get node :on_tap) :action))
              :test #'equal))
    (should (= (length (jetpacs-design-lab-test--nodes content "jetpacs.action"))
               4))
    (should (equal (plist-get (car (plist-get
                                    (jetpacs-action-schema "jpdesign.profile")
                                    :args))
                              :name)
                   'value))
    (cl-letf (((symbol-function 'jetpacs-design-lab--live-p)
               (lambda (&rest _) t))
              ((symbol-function 'jetpacs-design-lab--refresh)
               (lambda (&rest _) nil)))
      (should (eq (jetpacs-design-lab--on-profile
                   '(:value "jetpacs.baseline") nil)
                  'accepted))
      (should (equal jetpacs-design-lab--selected-profile-id
                     "jetpacs.baseline"))
      (should (eq (jetpacs-design-lab--on-profile '(:value "missing") nil)
                  'stale)))))

(ert-deftest jetpacs-design-lab-style-motion-reference-edits ()
  "The motion reference, the one optional leaf, can be set and cleared."
  (let* ((jetpacs-design-lab--draft (jetpacs-design-lab--baseline-profile))
         (rule (lambda (profile)
                 (aref (plist-get (cdr (assoc "base.row" (plist-get profile :styles)))
                                  :rules)
                       0)))
         (set (jetpacs-design-lab--edited-profile
               '(:domain "style" :item "base.row" :field "motion" :index 0
                 :value "quick")))
         (cleared (jetpacs-design-lab--edited-profile
                   '(:domain "style" :item "base.row" :field "motion" :index 0
                     :value "__none")))
         (base (jetpacs-design-lab--edited-profile
                '(:domain "style" :item "base.row" :field "motion"
                  :value "press"))))
    (should (equal (plist-get (funcall rule set) :motion) "quick"))
    (should-not (plist-member (funcall rule cleared) :motion))
    (should (equal (plist-get (cdr (assoc "base.row" (plist-get base :styles)))
                              :motion)
                   "press"))
    (should-error (jetpacs-design-lab--edited-profile
                   '(:domain "style" :item "base.row" :field "motion" :index 0
                     :value "nope")))
    (dolist (verb jetpacs-design-lab--verbs)
      (should (jetpacs-action-schema verb)))))

(provide 'jetpacs-design-lab-test)
;;; jetpacs-design-lab-test.el ends here
