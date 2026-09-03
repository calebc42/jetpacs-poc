;;; jetpacs-design-test.el --- tests for design authoring -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later

;;; Code:

(require 'ert)
(require 'json)
(require 'jetpacs-components)
(require 'jetpacs-design-material)
(require 'jetpacs-design-profiles)
(require 'jetpacs-design-baseline)

(ert-deftest jetpacs-design-baseline-binds-every-slot-and-presents-once ()
  "The platform baseline is theme-neutral, complete, and presents only a
bare scaffold, only under an advertised runtime."
  (let ((profile (jetpacs-design-baseline-profile)))
    (should (equal (plist-get profile :id) jetpacs-design-baseline-id))
    ;; Every closed slot is bound, so nothing falls to receiver defaults.
    (should (equal (sort (mapcar #'car (plist-get profile :component-styles))
                         #'string<)
                   (sort (copy-sequence jetpacs-design-component-style-slots)
                         #'string<)))
    ;; Theme-neutral: no role is re-declared, every color is a role token.
    (should-not (plist-get profile :theme-roles))
    (dolist (token (plist-get profile :tokens))
      (when (string-prefix-p "color." (car token))
        (should (equal (plist-get (cdr token) :kind) "theme-role"))))
    (should (jetpacs-design-profile-get jetpacs-design-baseline-id)))
  (let ((screen (jetpacs-scaffold :body (jetpacs-text "b")))
        (own (jetpacs-column (jetpacs-scaffold :body (jetpacs-text "o")))))
    (cl-letf (((symbol-function 'jetpacs-extension-advertised-p)
               (lambda (&rest _) nil)))
      (should (eq (jetpacs-design-present screen) screen)))
    (cl-letf (((symbol-function 'jetpacs-extension-advertised-p)
               (lambda (&rest _) t)))
      (let ((presented (jetpacs-design-present screen)))
        (should (equal (plist-get presented :t) "jetpacs.design_scope"))
        (should (eq (aref (plist-get presented :children) 0) screen)))
      ;; An app's own presentation is respected, never wrapped again.
      (should (eq (jetpacs-design-present own) own)))))

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

(ert-deftest jetpacs-design-builds-theme-roles-and-sorted-component-bindings ()
  (let* ((style
          (jetpacs-design-style
           `(("content_color" . ,(jetpacs-design-theme-role "on_surface")))))
         (scope
          (jetpacs-design-scope
           nil `(("label" . ,style)) nil
           :component-styles
           (list
            (jetpacs-design-component-style "tabs.label" '("label"))
            (jetpacs-design-component-style "action.label" '("label"))))))
    (should (equal
             (mapcar (lambda (binding) (plist-get binding :slot))
                     (append (plist-get scope :component_styles) nil))
             '("action.label" "tabs.label")))
    (should-error (jetpacs-design-theme-role "surface_variant"))
    (should-error
     (jetpacs-design-scope
      nil `(("label" . ,style)) nil
      :component-styles
      (list
       (jetpacs-design-component-style "action.label" '("label"))
       (jetpacs-design-component-style "action.label" '("label")))))))

(ert-deftest jetpacs-design-nested-slot-rebinding-scope-keeps-required-maps ()
  "A scope that only rebinds slots still serializes empty tokens and styles."
  (let* ((scope (jetpacs-design-scope
                 nil nil (list (jetpacs-text "body"))
                 :component-styles
                 (list (jetpacs-design-component-style
                        "text.body" '("typography.reading-body")))))
         (json (jetpacs-node->canonical-json scope)))
    (should (string-search "\"tokens\":{}" json))
    (should (string-search "\"styles\":{}" json))
    (should (string-search
             "{\"slot\":\"text.body\",\"styles\":[\"typography.reading-body\"]}"
             json))
    (should (member "text.body" jetpacs-design-component-style-slots))
    (should (= (length jetpacs-design-component-style-slots) 94))
  ;; An empty style is a legal binding that leaves the toolkit's value:
  ;; a plain plist in the profile, a literal `{}' on the wire.
  (let ((empty (jetpacs-design-style nil)))
    (should-not (plist-member empty :properties))
    (should (hash-table-p
             (plist-get
              (cdr (assoc "quiet"
                          (jetpacs-design--plist-pairs
                           (plist-get (jetpacs-design-scope
                                       nil (list (cons "quiet" empty)) nil)
                                      :styles))))
              :properties))))
    (should-error (jetpacs-design-component-style "text.unknown" '("x")))))

(ert-deftest jetpacs-design-scope-redeclares-theme-roles-for-chrome ()
  "Theme roles are a closed, color-valued map that rides the scope."
  (let* ((scope (jetpacs-design-scope
                 `(("paper" . ,(jetpacs-design-color "#F3EDE1")))
                 nil (list (jetpacs-text "x"))
                 :theme-roles
                 `(("surface" . ,(jetpacs-design-token "paper"))
                   ("primary" . ,(jetpacs-design-color "#8A5A2B"))
                   ("on_primary" . ,(jetpacs-design-theme-role "on_surface")))))
         (json (jetpacs-node->canonical-json scope)))
    (should (string-search
             "\"theme_roles\":{\"on_primary\":{\"kind\":\"theme-role\",\"value\":\"on_surface\"},\"primary\":{\"kind\":\"color\",\"value\":\"#8A5A2B\"},\"surface\":{\"kind\":\"token\",\"value\":\"paper\"}}"
             json))
    (should-not (string-search "theme_roles"
                               (jetpacs-node->canonical-json
                                (jetpacs-design-scope nil nil nil))))
    (should-error (jetpacs-design-scope
                   nil nil nil
                   :theme-roles `(("tint" . ,(jetpacs-design-color "#000000")))))
    (should-error (jetpacs-design-scope
                   nil nil nil
                   :theme-roles `(("primary" . ,(jetpacs-design-dimension 4)))))
    (should-error (jetpacs-design-scope
                   nil nil nil
                   :theme-roles `(("primary" . ,(jetpacs-design-token "missing")))))
    ;; A profile carries the same map and a legacy snapshot without it is valid.
    (let ((profile (jetpacs-design-make-profile
                    "roles-test" "Roles"
                    :tokens `(("ink" . ,(jetpacs-design-color "#2A251F")))
                    :theme-roles `(("on_surface" . ,(jetpacs-design-token "ink"))))))
      (should (string-search
               "theme_roles"
               (jetpacs-node->canonical-json
                (jetpacs-design-profile-scope profile (list (jetpacs-text "y"))))))
      (should (jetpacs-design-validate-profile
               (cl-loop for (key value) on profile by #'cddr
                        unless (eq key :theme-roles) append (list key value)))))))

(ert-deftest jetpacs-components-list-item-builds-and-degrades ()
  "The row emits the optional node when advertised, else the canonical one."
  (let ((swipe (jetpacs-swipe
                (list (jetpacs-swipe-action
                       "Done" :icon "done" :color "primary"
                       :on-trigger (jetpacs-action "demo.done")))
                :commit t)))
    (cl-letf (((symbol-function 'jetpacs-node-advertised-p) (lambda (&rest _) t)))
      (let ((row (jetpacs-components-list-item
                  "inbox" :subtitle "Notebooks" :title-max-lines 2
                  :leading (jetpacs-icon "description")
                  :trailing (jetpacs-icon "more_vert")
                  :on-tap (jetpacs-action "demo.open")
                  :swipe-end swipe :key "row-1")))
        (should (equal (plist-get row :t) "jetpacs.list_item"))
        (should (equal (plist-get row :title) "inbox"))
        (should (equal (plist-get row :key) "row-1"))
        (should (equal (plist-get (plist-get row :trailing) :t) "icon"))
        (should (equal (plist-get row :swipe_end) swipe))))
    (cl-letf (((symbol-function 'jetpacs-node-advertised-p) (lambda (&rest _) nil)))
      (let ((row (jetpacs-components-list-item
                  "inbox" :subtitle "Notebooks" :key "row-1"
                  :swipe-end swipe)))
        ;; The canonical composition every receiver can draw.
        (should (equal (plist-get row :t) "card"))
        (should (equal (plist-get row :key) "row-1"))
        (should (equal (plist-get row :swipe_end) swipe))
        (should (string-search "inbox" (jetpacs-node->canonical-json row))))))
  ;; The node carries no children on purpose: an unadvertised extension node
  ;; degrades to "render its children", so a row that grew one would degrade
  ;; to a blank line instead of the canonical card the gate chooses.
  (should-not (plist-member (jetpacs-component-list-item "t") :children))
  (should (member "jetpacs.list_item"
                  (alist-get 'app jetpacs-components-target-node-types)))
  (should-not (alist-get 'widget jetpacs-components-target-node-types))
  ;; Bounded and validated.
  (should-error (jetpacs-component-list-item ""))
  (should-error (jetpacs-component-list-item "t" :title-max-lines 0))
  (should-error (jetpacs-component-list-item
                 "t" :trailing (list (jetpacs-icon "a") (jetpacs-icon "b"))))
  (should-error (jetpacs-component-list-item "t" :leading "not-a-node")))

(ert-deftest jetpacs-design-row-and-swipe-slots-are-bindable ()
  "The new card, row and swipe slots are part of the closed slot set."
  (dolist (slot '("card.filled" "card.elevated" "card.outlined"
                  "list-item.container" "list-item.overline" "list-item.title"
                  "list-item.subtitle"
                  "swipe.cell" "swipe.label"))
    (should (member slot jetpacs-design-component-style-slots))
    (should (jetpacs-design-component-style slot '("some.style"))))
  (should-error (jetpacs-design-component-style "list-item.unknown" '("x"))))

(ert-deftest jetpacs-design-profiles-are-bounded-snapshots-with-fallback ()
  (let* ((jetpacs-design--presets (make-hash-table :test #'equal))
         (jetpacs-design-user-profiles nil)
         (jetpacs-design-active-profile-id nil)
         (profile
          (jetpacs-design-make-profile
           "demo" "Demo"
           :typography
           '(("label" . (:family "plex-sans" :size 15 :weight 600
                         :line-height 20 :letter-spacing 0)))
           :component-styles
           '(("action.label" . ("typography.label"))))))
    (should (equal (jetpacs-design-register-profile profile) "demo"))
    (should (equal (plist-get (jetpacs-design-active-profile "demo") :id)
                   "demo"))
    (let ((scope (jetpacs-design-profile-scope
                  profile (list (jetpacs-text "Preview")))))
      (should (plist-get scope :component_styles))
      (should (plist-get (plist-get scope :styles) :typography.label)))
    (should-error
     (jetpacs-design-register-profile
      (plist-put (copy-tree profile) :label "Changed")))
    (let ((copy (copy-tree profile)))
      (setq copy (plist-put copy :id "user"))
      (setq copy (plist-put copy :label "User"))
      (should (equal (jetpacs-design-save-profile-as copy) "user"))
      (jetpacs-design-set-active-profile "user")
      (should (equal (plist-get (jetpacs-design-active-profile "demo") :id)
                     "user"))
      (jetpacs-design-delete-user-profile "user")
      (should-not jetpacs-design-active-profile-id))))

(provide 'jetpacs-design-test)
;;; jetpacs-design-test.el ends here
