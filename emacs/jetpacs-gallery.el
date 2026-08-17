;;; jetpacs-gallery.el --- Interactive widget-primitives gallery -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later
;; Package-Requires: ((emacs "30.1"))

;;; Commentary:

;; A foundation demo of the platform's rendering primitives: charts, canvas,
;; slider, sizing, border, spacing, and alignment.  The interactive loop stays
;; visible: the slider drives a canvas gauge, chips switch chart kind, and a
;; point tap reports its value.  It is composed entirely from `jetpacs-*'
;; constructors, demonstrating that a complete visual surface is Elisp.
;;
;; The Settings row is the only cross-surface opener.  Its push establishes a
;; sanctioned guest; the gallery's inner controls remain owner-scoped for the
;; lifetime of that screen.  It has no Org dependency and no app-private data.

;;; Code:

(require 'cl-lib)
(require 'jetpacs-surfaces)
(require 'jetpacs-widgets)
(require 'jetpacs-shell)
(require 'jetpacs-chrome)
(require 'jetpacs-apps)
(require 'jetpacs-settings)

;;;; State (S2 — the handlers below are the only writers)

(defvar jetpacs-gallery--kind "line"
  "The chart kind the gallery currently renders.
Persists across pushes and re-registration — the chips re-seed
`:selected' from it each render.")

(defvar jetpacs-gallery--level 0.5
  "The gauge value (0.0-1.0) the slider last committed.
The slider re-seeds `:value' from this mirror each render, so the
authored spec never fights the device draft.")

;;;; The gauge (v1 core jetpacs-widgets.el:913-935, transliterated)

(defun jetpacs-gallery--arc-points (cx cy r a0 a1 n)
  "N+1 (X Y) pairs along the arc A0→A1 degrees, centre (CX CY), radius R.
Screen y grows downward, so a top semicircle spans 180°→0°.  Pure
geometry — the gauge's testable half; the builder below lifts the
pairs into ChartPoint-shaped canvas points."
  (cl-loop for i from 0 to n
           for a = (+ a0 (* (- a1 a0) (/ (float i) n)))
           for rad = (degrees-to-radians a)
           collect (list (+ cx (* r (cos rad))) (- cy (* r (sin rad))))))

(defun jetpacs-gallery--arc-path (cx cy r a0 a1 color stroke)
  "A canvas path op tracing the A0→A1 arc (44 segments, like v1)."
  (jetpacs-canvas-path
   (mapcar (lambda (p) (jetpacs-canvas-point (nth 0 p) (nth 1 p)))
           (jetpacs-gallery--arc-points cx cy r a0 a1 44))
   :color color :stroke-width stroke))

(cl-defun jetpacs-gallery--gauge (level &key (width 240) (height 132)
                                          (track-color "#8888aa")
                                          (fill-color "#00A676")
                                          (needle-color "#E64980"))
  "A semicircular canvas gauge filled to LEVEL (0.0-1.0, clamped).
Geometry derives from WIDTH/HEIGHT; §17.5 canvas text has no align
member, so the percentage centres by manual offset — half the label's
approximate advance (0.6 em per glyph at the drawn size)."
  (let* ((level (max 0.0 (min 1.0 (float level))))
         (cx (/ width 2)) (cy (- height 16)) (r (- (/ width 2) 25))
         (end (- 180 (* 180 level)))
         (na (degrees-to-radians end))
         (nx (+ cx (* r 0.9 (cos na))))
         (ny (- cy (* r 0.9 (sin na))))
         (label (format "%d%%" (round (* 100 level))))
         (size 28))
    (jetpacs-canvas
     width height
     (list (jetpacs-gallery--arc-path cx cy r 180 0 track-color 12)
           (jetpacs-gallery--arc-path cx cy r 180 end fill-color 12)
           (jetpacs-canvas-line cx cy nx ny :color needle-color :width 3)
           (jetpacs-canvas-circle cx cy 7 :fill needle-color)
           (jetpacs-canvas-text (- cx (* 0.3 size (length label)))
                                (- height 58) label
                                :size size :color "primary")))))

;;;; The screen

(defconst jetpacs-gallery--chart-kinds '("line" "bar" "area" "sparkline")
  "The kinds the chip rail offers — `jetpacs--chart-kinds' verbatim,
restated so a handler validating against it never depends on a
private core constant.")

(defun jetpacs-gallery--kind-chips ()
  "A chip rail selecting `jetpacs-gallery--kind'."
  (apply #'jetpacs-flow-row
         (append
          (mapcar (lambda (k)
                    (jetpacs-chip k
                                  :selected (jetpacs-bool
                                             (equal k jetpacs-gallery--kind))
                                  :on-tap (jetpacs-action
                                           "demo.gallery.kind"
                                           :args (list :kind k))))
                  jetpacs-gallery--chart-kinds)
          (list :spacing 8))))

(defun jetpacs-gallery--points (ys)
  "YS (a list of numbers) as ChartPoint nodes over ordinal x.
v1 authored bare y-lists; v3 series carry ChartPoint objects (T3)."
  (cl-loop for y in ys for x from 0
           collect (jetpacs-chart-point x y)))

(defun jetpacs-gallery--body ()
  "The scrollable gallery content (a lazy column, so it scrolls)."
  (jetpacs-lazy-column
   (jetpacs-section-header "Chart — tap a point, switch the kind")
   (jetpacs-gallery--kind-chips)
   (jetpacs-chart
    (list (jetpacs-chart-series (jetpacs-gallery--points '(3 7 4 9 6 8 5))
                                :name "alpha" :color "#4C6FFF")
          (jetpacs-chart-series (jetpacs-gallery--points '(5 4 6 5 7 5 8))
                                :name "beta"))
    :kind jetpacs-gallery--kind :height 150 :summary "two sample series"
    :on-point-tap (jetpacs-action "demo.gallery.point"))
   (jetpacs-divider)
   (jetpacs-section-header "Slider → live canvas gauge")
   (jetpacs-slider "gallery.level" (jetpacs-action "demo.gallery.level")
                   :value jetpacs-gallery--level :min 0.0 :max 1.0)
   (jetpacs-gallery--gauge jetpacs-gallery--level)
   (jetpacs-divider)
   (jetpacs-section-header "Sizing · border · spacing · align")
   (jetpacs-row
    ;; :width/:height/:border/:fill_fraction are §16.5 universal attrs
    ;; — inline on a container they SIGNAL (jetpacs--check-options), so
    ;; they ride `jetpacs-with-attrs'.
    (jetpacs-with-attrs
     (jetpacs-surface (list (jetpacs-text "100×64")))
     :width 100 :height 64 :border '(:width 2 :color "primary"))
    (jetpacs-with-attrs
     (jetpacs-surface (list (jetpacs-text "rounded, fills rest"))
                      :color "surface_container" :shape "rounded")
     :height 64 :fill_fraction 1.0)
    :spacing 12 :align "center")
   (jetpacs-spacer :height 12)))

(defun jetpacs-gallery-screen (back)
  "The pushed Widget Gallery screen."
  (jetpacs-chrome-screen "Widget Gallery" (jetpacs-gallery--body)
                         :back back))

;;;; Handlers (S4 — every one answers accepted/stale/rejected)

(defun jetpacs-gallery--push-screen (params)
  "Defer-push the gallery onto PARAMS' surface (D2); `accepted'.
A deferred `jetpacs-chrome-push-screen' must catch its own re-signal
or a refused gate dies in a timer.  Fired FROM the gallery, the push
replaces in place (stack-insert truncates on a duplicate id)."
  (let ((surface (or (plist-get params :surface)
                     (jetpacs-shell-surface-for
                      jetpacs-settings-surface))))
    (jetpacs-flow-continue
     (lambda ()
       (condition-case err
           (jetpacs-chrome-push-screen surface "jetpacs-gallery"
                                       #'jetpacs-gallery-screen)
         (error (message "jetpacs-gallery: push failed: %s"
                         (jetpacs-error-label err))))))
    'accepted))

(defun jetpacs-gallery--on-open (_args params)
  "Push the gallery onto the tapped surface."
  (jetpacs-gallery--push-screen params))

(defun jetpacs-gallery--on-kind (args params)
  "A kind chip tap: store the chart kind and re-render.
The chips author the enum, so an unknown kind is malformed args —
v1's silent \"line\" fallback would repaint a state nobody chose."
  (let ((kind (plist-get args :kind)))
    (if (not (member kind jetpacs-gallery--chart-kinds))
        'rejected
      (setq jetpacs-gallery--kind kind)
      (jetpacs-app-defer-refresh params)
      'accepted)))

(defun jetpacs-gallery--on-level (args params)
  "The slider's commit: mirror `:value', then re-push.
The re-render re-seeds the slider from the mirror — the spec never
fights the device draft.  Out-of-range numbers clamp rather than
reject: the authored :min/:max make them float noise, not malice."
  (let ((v (plist-get args :value)))
    (if (not (numberp v))
        'rejected
      (setq jetpacs-gallery--level (max 0.0 (min 1.0 (float v))))
      (jetpacs-app-defer-refresh params)
      'accepted)))

(defun jetpacs-gallery--on-point (args params)
  "A chart point tap: report the authored point in a snackbar.
The Companion injects the complete authored point as `:value' and its
zero-based ordinal as `:index' (SPEC 17.5).  The snackbar IS the
effect — nothing re-renders, so there is no push."
  (let* ((point (plist-get args :value))
         (index (plist-get args :index))
         (y (and (consp point) (keywordp (car point))
                 (plist-get point :y))))
    (if (not (and (numberp index) (numberp y)))
        'rejected
      (jetpacs-shell-notify (format "point %s = %s" index y)
                            (plist-get params :surface))
      'accepted)))

;;;; Registration

(defun jetpacs-gallery--settings-link ()
  "The Settings-root satellite row leading to the widget gallery."
  (jetpacs-chrome-row "Widget Gallery"
                      :subtitle "Live demo of the rendering primitives"
                      :icon "insights"
                      :on-tap (jetpacs-action "demo.gallery")
                      :key "jetpacs-gallery-link"))

(defconst jetpacs-gallery--verbs
  '("demo.gallery"
    "demo.gallery.kind"
    "demo.gallery.level"
    "demo.gallery.point")
  "The verbs this file owns, for the register/unregister sweep.")

(defun jetpacs-gallery-register ()
  "Register the gallery verbs and its Settings satellite link.
Called by the Jetpacs composition root.  Idempotent: handlers replace in
place and the link is re-added exactly once."
  (with-jetpacs-owner "jetpacs.demo"
    ;; The Settings-root tap precedes guest delegation, so only the opener
    ;; receives a permanent cross-surface grant.
    (jetpacs-defaction "demo.gallery" #'jetpacs-gallery--on-open
                       :any-surface t
                       :doc "Open the widget-primitives gallery")
    (jetpacs-defaction "demo.gallery.kind" #'jetpacs-gallery--on-kind
                       :doc "Switch the gallery chart kind"
                       :args '((:name kind :type "text" :required t)))
    (jetpacs-defaction "demo.gallery.level" #'jetpacs-gallery--on-level
                       :doc "Set the gallery gauge level"
                       :args '((:name value :type "number" :required t)))
    (jetpacs-defaction "demo.gallery.point" #'jetpacs-gallery--on-point
                       :doc "Report a tapped chart point")
    (jetpacs-settings-remove-link #'jetpacs-gallery--settings-link)
    ;; Demos live after the everyday Settings satellites.
    (jetpacs-settings-add-link 84 #'jetpacs-gallery--settings-link)))

(defun jetpacs-gallery-unregister ()
  "Drop the gallery verbs and the Settings link.
The kind/level mirrors deliberately survive — the same S2 persistence
rule as search's filters; the next register serves them as-is."
  (dolist (name jetpacs-gallery--verbs)
    (jetpacs-undefaction name))
  (jetpacs-settings-remove-link #'jetpacs-gallery--settings-link))

;;;###autoload
(defun jetpacs-gallery-open ()
  "Open the interactive widget-primitives gallery on the companion."
  (interactive)
  (with-jetpacs-owner "jetpacs.demo"
    (jetpacs-chrome-push-screen jetpacs-settings-surface
                                "jetpacs-gallery"
                                #'jetpacs-gallery-screen))
  (message (if (jetpacs-connected-p)
               "Widget gallery opened on the companion"
             "Widget gallery staged — it renders when a device connects")))

(provide 'jetpacs-gallery)
;;; jetpacs-gallery.el ends here
