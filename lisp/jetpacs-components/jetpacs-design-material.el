;;; jetpacs-design-material.el --- small Foundation design proof -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later
;; Package-Requires: ((emacs "30.1"))

;;; Commentary:

;; A deliberately small proof library built only from `jetpacs.design': a
;; filled button, a card, and a two-option selector.  The name describes its
;; familiar component vocabulary; the Android implementation remains in the
;; Material-free Foundation renderer.

;;; Code:

(require 'jetpacs-design)

(defconst jetpacs-design-material-tokens
  `(("color.primary" . ,(jetpacs-design-color "#315C49"))
    ("color.on-primary" . ,(jetpacs-design-color "#FFFFFF"))
    ("color.surface" . ,(jetpacs-design-color "#FBF8F0"))
    ("color.on-surface" . ,(jetpacs-design-color "#272B26"))
    ("color.outline" . ,(jetpacs-design-color "#74796F"))
    ("space.small" . ,(jetpacs-design-dimension 8))
    ("space.medium" . ,(jetpacs-design-dimension 12))
    ("radius.medium" . ,(jetpacs-design-dimension 12)))
  "Default tokens for the small design proof library.")

(defconst jetpacs-design-material-motions
  `(("motion.quick" . ,(jetpacs-design-motion 120 "ease-out")))
  "Default motions for the small design proof library.")

(defconst jetpacs-design-material-styles
  `(("material.button"
     . ,(jetpacs-design-style
         `(("background_color" . ,(jetpacs-design-token "color.primary"))
           ("corner_radius" . ,(jetpacs-design-token "radius.medium"))
           ("min_height" . ,(jetpacs-design-dimension 48))
           ("padding_horizontal" . ,(jetpacs-design-dimension 20))
           ("padding_vertical" . ,(jetpacs-design-token "space.medium")))
         :motion "motion.quick"
         :rules
         (list
          (jetpacs-design-rule
           "pressed" `(("scale" . ,(jetpacs-design-number 0.97))))
          (jetpacs-design-rule
           "focused"
           `(("border_color" . ,(jetpacs-design-token "color.outline"))
             ("border_width" . ,(jetpacs-design-dimension 2))))
          (jetpacs-design-rule
           "disabled" `(("alpha" . ,(jetpacs-design-number 0.38)))))))
    ("material.button.label"
     . ,(jetpacs-design-style
         `(("content_color" . ,(jetpacs-design-token "color.on-primary"))
           ("font_family" . ,(jetpacs-design-font-family "plex-sans"))
           ("font_weight" . ,(jetpacs-design-font-weight 600)))))
    ("material.card"
     . ,(jetpacs-design-style
         `(("background_color" . ,(jetpacs-design-token "color.surface"))
           ("border_color" . ,(jetpacs-design-token "color.outline"))
           ("border_width" . ,(jetpacs-design-dimension 1))
           ("corner_radius" . ,(jetpacs-design-token "radius.medium"))
           ("padding" . ,(jetpacs-design-token "space.medium")))))
    ("material.selector.option"
     . ,(jetpacs-design-style
         `(("border_color" . ,(jetpacs-design-token "color.outline"))
           ("border_width" . ,(jetpacs-design-dimension 1))
           ("corner_radius" . ,(jetpacs-design-token "radius.medium"))
           ("min_height" . ,(jetpacs-design-dimension 48))
           ("padding_horizontal" . ,(jetpacs-design-token "space.medium")))
         :motion "motion.quick"
         :rules
         (list
          (jetpacs-design-rule
           "selected"
           `(("background_color" . ,(jetpacs-design-token "color.primary"))))
          (jetpacs-design-rule
           "pressed" `(("scale" . ,(jetpacs-design-number 0.97))))))))
  "Default styles for the small design proof library.")

(defun jetpacs-design-material-scope (children)
  "Wrap CHILDREN in the default proof-library design scope."
  (jetpacs-design-scope
   jetpacs-design-material-tokens
   jetpacs-design-material-styles
   children
   :motions jetpacs-design-material-motions))

(cl-defun jetpacs-design-material-filled-button
    (label on-tap &key enabled)
  "Build a filled button labeled LABEL dispatching ON-TAP.
ENABLED is t or `:json-false' when authored."
  (jetpacs-require-string label "filled button label")
  (jetpacs-design-pressable
   '("material.button") on-tap
   (list
    (jetpacs-design-styled
     '("material.button.label")
     (list (jetpacs-text label))))
   :enabled enabled))

(defun jetpacs-design-material-card (children)
  "Build a passive card around CHILDREN."
  (jetpacs-design-styled '("material.card") children))

(defun jetpacs-design-material-two-option-selector
    (first-label first-value second-label second-value value on-change)
  "Build a controlled two-option selector.
FIRST-LABEL and SECOND-LABEL are presentation strings.  FIRST-VALUE and
SECOND-VALUE are stable values.  VALUE selects one option.  ON-CHANGE is a
function receiving the selected value and returning its action descriptor."
  (dolist (label (list first-label second-label))
    (jetpacs-require-string label "selector label"))
  (unless (and (stringp first-value) (stringp second-value)
               (not (equal first-value second-value))
               (member value (list first-value second-value)))
    (error "jetpacs-design-material: selector values must be distinct and selected"))
  (unless (functionp on-change)
    (error "jetpacs-design-material: ON-CHANGE must be a function"))
  (jetpacs-row
   (jetpacs-design-pressable
    '("material.selector.option") (funcall on-change first-value)
    (list (jetpacs-text first-label))
    :selected (if (equal value first-value) t :json-false))
   (jetpacs-design-pressable
    '("material.selector.option") (funcall on-change second-value)
    (list (jetpacs-text second-label))
    :selected (if (equal value second-value) t :json-false))))

(provide 'jetpacs-design-material)
;;; jetpacs-design-material.el ends here
