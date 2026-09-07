;;; jetpacs-m3-inspection.el --- Literate Material catalog workspace -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later
;; Package-Requires: ((emacs "30.1"))

;;; Commentary:

;; Preview, Visual, Lisp, and Source are sibling projections of the same live
;; catalog state.  The developer workspace never occupies a specimen scaffold
;; slot: only Preview mounts the sample's bars, FAB, toolbar, snackbar, drawer,
;; or sheet.  Other projections are ordinary full-body catalog screens.

;;; Code:

(require 'cl-lib)
(require 'seq)
(require 'subr-x)
(require 'jetpacs-authoring)
(require 'jetpacs-catalog-inspection)
(require 'jetpacs-m3-core)
(require 'jetpacs-m3-repl)
;; The Design projection presents any catalog screen through the optional
;; `jetpacs.design' runtime.  Its authoring library is a sibling downstream
;; package, so it is a soft dependency: without it the projection explains
;; itself instead of failing.
(require 'jetpacs-design nil t)
(require 'jetpacs-design-profiles nil t)
(declare-function jetpacs-design-scope "jetpacs-design")
(declare-function jetpacs-design-wrap-active-profile "jetpacs-design-profiles")

(defconst jetpacs-m3-inspection-modes
  ["preview" "visual" "lisp" "source" "design"]
  "Stable controlled projection order used by every catalog scope.
Design is last so the four original indices keep their meaning.")

(defconst jetpacs-m3-inspection-mode-labels
  '("Preview" "Visual" "Lisp" "Source" "Design")
  "Selector labels in `jetpacs-m3-inspection-modes' order.")

(defconst jetpacs-m3-inspection-max-chars 65536
  "Maximum visible Lisp, source, or canonical EBP projection.")

(defvar jetpacs-m3-inspection-root-mode "preview"
  "Current catalog-root projection.")

(defvar jetpacs-m3-inspection-component-modes (make-hash-table :test #'equal)
  "Component ID to current projection name.")

(defvar jetpacs-m3-inspection-example-modes (make-hash-table :test #'equal)
  "Example screen ID to current projection name.")

(defun jetpacs-m3-inspection--mode-index (mode)
  "Return MODE's stable tab index, defaulting to Preview."
  (or (seq-position jetpacs-m3-inspection-modes mode #'equal) 0))

(defun jetpacs-m3-inspection--component-mode (id)
  "Return component ID's authored projection, defaulting to Preview."
  (or (gethash id jetpacs-m3-inspection-component-modes) "preview"))

(defun jetpacs-m3-inspection--example-mode (id index)
  "Return component ID example INDEX's projection, defaulting to Preview."
  (or (gethash (jetpacs-m3-example-screen-id id index)
               jetpacs-m3-inspection-example-modes)
      "preview"))

(defun jetpacs-m3-inspection--selector (scope mode &optional component index)
  "Return SCOPE's controlled selector for MODE.
COMPONENT and INDEX address component- and example-scoped selections."
  (let ((args (append (list :scope scope)
                      (and component (list :component component))
                      (and (integerp index) (list :index index)))))
    (jetpacs-tab-selector
     (mapcar (lambda (label) (jetpacs-tab-item label))
             jetpacs-m3-inspection-mode-labels)
     (jetpacs-m3-inspection--mode-index mode)
     (jetpacs-action "m3catalog.presentation" :args args)
     :id (pcase scope
           ("root" "m3-inspect-root")
           ("component" (format "m3-inspect-c-%s" component))
           (_ (format "m3-inspect-e-%s-%d" component index)))
     :style "secondary"
     :scrollable t)))

(defun jetpacs-m3-inspection--prepend-selector (screen selector)
  "Return SCREEN with SELECTOR fixed above its existing scaffold body."
  (let* ((screen (copy-tree screen))
         (body (plist-get screen :body)))
    (unless (and (equal (plist-get screen :t) "scaffold") body)
      (error "M3 inspection expected a scaffold screen"))
    (setq screen
          (plist-put screen :body
                     (jetpacs-column
                      selector
                      (jetpacs-with-attrs body :weight 1)
                      :fill t)))
    screen))

(defun jetpacs-m3-inspection-design-available-p ()
  "Non-nil when the receiver advertises the Elisp design runtime."
  (and (fboundp 'jetpacs-design-scope)
       (jetpacs-extension-advertised-p "jetpacs.design" :app)))

(defun jetpacs-m3-inspection--design-screen (screen selector)
  "Present preview SCREEN with SELECTOR through the active design profile.
Every canonical node in SCREEN then renders through the receiver's
Foundation design overrides where one exists, so the same Material
catalog example becomes a live test of the Jetpacs primitives.  With no
active or fallback profile the scope is empty and the receiver's private
Jetpacs theme shows; without the runtime the Preview is shown with a note."
  (let ((framed (jetpacs-m3-inspection--prepend-selector screen selector)))
    (cond
     ((not (jetpacs-m3-inspection-design-available-p))
      (jetpacs-m3-inspection--prepend-selector
       (plist-put (copy-tree screen) :body
                  (jetpacs-column
                   (jetpacs-with-attrs
                    (jetpacs-text
                     "The Elisp design runtime is not advertised by this receiver; showing Preview."
                     :style "caption")
                    :padding 12)
                   (jetpacs-with-attrs (plist-get screen :body) :weight 1)
                   :fill t))
       selector))
     ((fboundp 'jetpacs-design-wrap-active-profile)
      (let ((wrapped (jetpacs-design-wrap-active-profile (list framed))))
        (if (equal (plist-get (car wrapped) :t) "jetpacs.design_scope")
            (car wrapped)
          (jetpacs-design-scope nil nil (list framed)))))
     (t (jetpacs-design-scope nil nil (list framed))))))

(cl-defun jetpacs-m3-inspection--screen
    (title selector body back &key actions)
  "Build TITLE with SELECTOR fixed above BODY and BACK navigation.
ACTIONS are the optional chrome actions."
  (jetpacs-chrome-screen
   title
   (jetpacs-column
    selector
    (jetpacs-with-attrs body :weight 1)
    :fill t)
   :back back :actions actions))

(defun jetpacs-m3-inspection--mono-block (title caption text &optional copy)
  "Return TITLE read-only TEXT with CAPTION and an optional COPY action."
  (apply #'jetpacs-column
         (append
          (list (jetpacs-text title :style "title")
                (jetpacs-text caption :style "caption"))
          (when copy
            (list (jetpacs-button
                   "Copy" (jetpacs-clipboard-copy text)
                   :icon "content_copy" :variant "tonal")))
          (list (jetpacs-text text :style "mono" :selectable t
                              :syntax "elisp")
                :spacing 8))))

;;;; Root projections

(defun jetpacs-m3-inspection--root-manifest ()
  "Return deterministic inert data describing the loaded catalog program."
  (list
   :schema-version 1
   :kind "jetpacs-m3-catalog-program"
   :owner jetpacs-m3-owner
   :surface "app:m3catalog"
   :navigation
   (list :root "home"
         :open-action "m3catalog.open"
         :example-action "m3catalog.example"
         :projection-action "m3catalog.presentation"
         :screen-depth 3)
   :projections jetpacs-m3-inspection-modes
   :components
   (vconcat
    (mapcar
     (lambda (component)
       (list :id (plist-get component :id)
             :name (plist-get component :name)
             :builders (vconcat (plist-get component :builders))
             :examples (length (plist-get component :examples))))
     jetpacs-m3-components))
   :registration 'jetpacs-m3-register))

(defconst jetpacs-m3-inspection--root-source-specs
  '((:kind variable :symbol jetpacs-m3-components :label "Live registry declaration")
    (:kind function :symbol jetpacs-m3-defcomponent :label "Component registration seam")
    (:kind function :symbol jetpacs-m3-home-preview-screen :label "Home Preview")
    (:kind function :symbol jetpacs-m3-component-preview-screen :label "Component Preview")
    (:kind function :symbol jetpacs-m3-example-preview-screen :label "Example Preview")
    (:kind function :symbol jetpacs-m3-inspection-home-screen :label "Root projection dispatch")
    (:kind function :symbol jetpacs-m3-inspection-component-screen :label "Component projection dispatch")
    (:kind function :symbol jetpacs-m3-inspection-example-screen :label "Example projection dispatch")
    (:kind function :symbol jetpacs-m3-register :label "Application registration"))
  "Curated complete forms defining the catalog program path.")

(defun jetpacs-m3-inspection--root-visual ()
  "Return live root registry and built-Preview inspection nodes."
  (let ((preview (jetpacs-m3-home-preview-screen nil)))
    (apply
     #'jetpacs-lazy-column
     (append
      (list
       (jetpacs-text "Program flow" :style "title")
       (jetpacs-text
        (string-join
         '("Home Preview → m3catalog.open"
           "Component Preview → m3catalog.example"
           "Example workspace → Preview / Visual / Lisp / Source"
           "m3catalog.presentation → validate → store mode → re-push")
         "\n")
        :style "mono" :selectable t)
       (jetpacs-text "Live component registry" :style "title"))
      (mapcar
       (lambda (component)
         (jetpacs-chrome-row
          (format "%s · %s"
                  (plist-get component :name) (plist-get component :id))
          :subtitle
          (format "%d examples · %s"
                  (length (plist-get component :examples))
                  (mapconcat #'symbol-name
                             (plist-get component :builders) ", "))
          :on-tap (jetpacs-m3--open (plist-get component :id))
          :key (format "inspect-root-%s" (plist-get component :id))))
       jetpacs-m3-components)
      (list
       (jetpacs-text "Built Preview EBP tree" :style "title")
       (jetpacs-text
        (jetpacs-catalog-inspection-node-tree preview)
        :style "mono" :selectable t)
       :spacing 10 :content-padding 16)))))

(defun jetpacs-m3-inspection-home-screen (back)
  "Build the catalog-root projection with BACK navigation."
  (let* ((mode jetpacs-m3-inspection-root-mode)
         (selector (jetpacs-m3-inspection--selector "root" mode))
         (actions (jetpacs-m3--actions "home")))
    (pcase mode
      ("preview"
       (jetpacs-m3-inspection--prepend-selector
        (jetpacs-m3-home-preview-screen back) selector))
      ("design"
       (jetpacs-m3-inspection--design-screen
        (jetpacs-m3-home-preview-screen back) selector))
      ("visual"
       (jetpacs-m3-inspection--screen
        "Material 3 Catalog · Visual" selector
        (jetpacs-m3-inspection--root-visual) back :actions actions))
      ("lisp"
       (let ((text (jetpacs-catalog-inspection-manifest-text
                    (jetpacs-m3-inspection--root-manifest)
                    jetpacs-m3-inspection-max-chars)))
         (jetpacs-m3-inspection--screen
          "Material 3 Catalog · Lisp" selector
          (jetpacs-lazy-column
           (jetpacs-m3-inspection--mono-block
            "Inert program manifest"
            "Derived from the live registry and routing contract; never evaluated."
            text t)
           :content-padding 16)
          back :actions actions)))
      (_
       (let* ((source
               (jetpacs-catalog-inspection-source-bundle
                jetpacs-m3-inspection--root-source-specs
                :max-chars jetpacs-m3-inspection-max-chars
                :exact-caption
                "Curated exact defining forms for registry, navigation, projections, and registration."
                :partial-caption
                "Only complete exact forms are shown; unavailable or over-bound sections are identified."))
              (text (plist-get source :text)))
         (jetpacs-m3-inspection--screen
          "Material 3 Catalog · Source" selector
          (jetpacs-lazy-column
           (jetpacs-m3-inspection--mono-block
            "Catalog program source" (plist-get source :caption) text
            (not (eq (plist-get source :origin) 'unavailable)))
           :content-padding 16)
          back :actions actions))))))

;;;; Component projections

(defun jetpacs-m3-inspection--component-manifest (component)
  "Return COMPONENT's deterministic inert registry projection."
  (list
   :schema-version 1
   :kind "jetpacs-m3-component"
   :id (plist-get component :id)
   :name (plist-get component :name)
   :description (plist-get component :description)
   :builders (vconcat (plist-get component :builders))
   :examples
   (vconcat
    (cl-loop
     for example in (plist-get component :examples)
     for index from 0
     collect
     (list :index index
           :name (plist-get example :name)
           :description (plist-get example :description)
           :expressive (and (plist-get example :expressive) t)
           :builder (jetpacs-m3--example-builder example)
           :unsupported (plist-get example :unsupported))))))

(defun jetpacs-m3-inspection--component-symbols (component)
  "Return distinct named implementation symbols for COMPONENT."
  (delete-dups
   (append
    (copy-sequence (plist-get component :builders))
    (delq nil
          (mapcar (lambda (example)
                    (jetpacs-m3--example-builder example))
                  (plist-get component :examples))))))

(defun jetpacs-m3-inspection--component-source (component)
  "Return bounded exact source for COMPONENT's registration and builders."
  (let* ((id (plist-get component :id))
         (file (ignore-errors (find-library-name (format "jetpacs-m3-%s" id))))
         (registration
          (jetpacs-catalog-inspection-exact-call-form
           file 'jetpacs-m3-defcomponent id))
         (specs
          (mapcar (lambda (symbol)
                    (list :kind 'function :symbol symbol
                          :label (format "%s" symbol)))
                  (jetpacs-m3-inspection--component-symbols component)))
         (bundle
          (jetpacs-catalog-inspection-source-bundle
           specs :max-chars jetpacs-m3-inspection-max-chars
           :exact-caption "Exact component builders and sample forms."
           :partial-caption "Available complete component forms; omissions are explicit."))
         (registration-text (plist-get registration :text))
         (body (plist-get bundle :text))
         (prefix (and registration-text
                      (format ";;;; Component registration\n;; %s\n%s"
                              (plist-get registration :caption)
                              registration-text)))
         (joined (if (and prefix
                          (<= (+ (length prefix) 2 (length body))
                              jetpacs-m3-inspection-max-chars))
                     (concat prefix "\n\n" body)
                   body)))
    (list :text joined
          :caption
          (if prefix
              "Exact registration and complete named implementation forms within the projection boundary."
            "The registration form is unavailable; complete named implementation forms are shown where available.")
          :origin (if (or prefix (not (eq (plist-get bundle :origin) 'unavailable)))
                      'mixed 'unavailable)
          :truncated (or (plist-get bundle :truncated)
                         (and prefix (not (string-prefix-p prefix joined)))))))

(defun jetpacs-m3-inspection--component-visual (component)
  "Return COMPONENT's builders, editing coverage, and built EBP tree."
  (let ((preview (jetpacs-m3-component-preview-screen component nil)))
    (apply
     #'jetpacs-lazy-column
     (append
      (list (jetpacs-text "Loaded builders" :style "title"))
      (mapcan
       (lambda (builder)
         (list (jetpacs-text (symbol-name builder)
                             :style "label" :font-weight "bold")
               (jetpacs-text (or (jetpacs-m3-builder-doc builder)
                                 "No loaded docstring.")
                             :style "caption" :selectable t)))
       (plist-get component :builders))
      (list (jetpacs-text "Example authoring coverage" :style "title"))
      (cl-loop
       for example in (plist-get component :examples)
       for index from 0
       for call = (jetpacs-m3-repl--call component index)
       for knobs = (and call (jetpacs-m3-repl-knobs-for call))
       collect
       (jetpacs-chrome-row
        (plist-get example :name)
        :subtitle
        (cond
         ((plist-get example :unsupported) "Unsupported by the current EBP vocabulary")
         (knobs (format "%d derived Visual control%s"
                        (length knobs) (if (= (length knobs) 1) "" "s")))
         (t "Live Preview and Lisp; no safe outer-call controls"))
        :on-tap (jetpacs-m3--open-example (plist-get component :id) index)
        :key (format "inspect-component-%s-%d"
                     (plist-get component :id) index)))
      (list
       (jetpacs-text "Built component EBP tree" :style "title")
       (jetpacs-text (jetpacs-catalog-inspection-node-tree preview)
                     :style "mono" :selectable t)
       :spacing 10 :content-padding 16)))))

(defun jetpacs-m3-inspection-component-screen (component back)
  "Build COMPONENT's selected projection with BACK navigation."
  (let* ((id (plist-get component :id))
         (mode (jetpacs-m3-inspection--component-mode id))
         (selector (jetpacs-m3-inspection--selector "component" mode id))
         (actions (jetpacs-m3--actions
                   (jetpacs-m3-component-screen-id id)
                   (plist-get component :guidelines)
                   (plist-get component :docs)
                   (plist-get component :source))))
    (pcase mode
      ("preview"
       (jetpacs-m3-inspection--prepend-selector
        (jetpacs-m3-component-preview-screen component back) selector))
      ("design"
       (jetpacs-m3-inspection--design-screen
        (jetpacs-m3-component-preview-screen component back) selector))
      ("visual"
       (jetpacs-m3-inspection--screen
        (format "%s · Visual" (plist-get component :name)) selector
        (jetpacs-m3-inspection--component-visual component)
        back :actions actions))
      ("lisp"
       (let ((text (jetpacs-catalog-inspection-manifest-text
                    (jetpacs-m3-inspection--component-manifest component)
                    jetpacs-m3-inspection-max-chars)))
         (jetpacs-m3-inspection--screen
          (format "%s · Lisp" (plist-get component :name)) selector
          (jetpacs-lazy-column
           (jetpacs-m3-inspection--mono-block
            "Inert component manifest"
            "A deterministic read-only projection of the live registry entry."
            text t)
           :content-padding 16)
          back :actions actions)))
      (_
       (let* ((source (jetpacs-m3-inspection--component-source component))
              (text (plist-get source :text)))
         (jetpacs-m3-inspection--screen
          (format "%s · Source" (plist-get component :name)) selector
          (jetpacs-lazy-column
           (jetpacs-m3-inspection--mono-block
            "Component source" (plist-get source :caption) text
            (not (eq (plist-get source :origin) 'unavailable)))
           :content-padding 16)
          back :actions actions))))))

;;;; Example projections

(defun jetpacs-m3-inspection--example-current-node (component index)
  "Return COMPONENT example INDEX's current specimen node."
  (let* ((id (plist-get component :id))
         (example (nth index (plist-get component :examples))))
    (or (jetpacs-m3-repl-override id index)
        (jetpacs-m3--example-body example))))

(defun jetpacs-m3-inspection--example-visual (component index)
  "Return COMPONENT example INDEX's controls and current node inspection."
  (let* ((id (plist-get component :id))
         (overridden (jetpacs-m3-repl-override id index))
         (node (jetpacs-m3-inspection--example-current-node component index)))
    (apply
     #'jetpacs-lazy-column
     (append
      (list
       (jetpacs-row
        (jetpacs-with-attrs
         (jetpacs-text "Visual controls" :style "title") :weight 1)
        (jetpacs-icon-button
         "restart_alt"
         (jetpacs-action "m3catalog.repl.reset"
                         :args (list :component id :index index))
         :content-description "Reset the sample"
         :variant (and overridden "tonal"))
        :align "center" :fill t)
       (jetpacs-text
        "Controls are derived from the outer node constructor's loaded validation contract. Changes update the same Preview override used by Lisp."
        :style "caption"))
      (jetpacs-m3-repl--knob-panel component index)
      (list
       (jetpacs-divider)
       (jetpacs-text "Current specimen EBP tree" :style "title")
       (jetpacs-text (jetpacs-catalog-inspection-node-tree node)
                     :style "mono" :selectable t)
       :spacing 10 :content-padding 16)))))

(defun jetpacs-m3-inspection--example-source (component index)
  "Return COMPONENT example INDEX's read-only exact source and emitted EBP."
  (let* ((example (nth index (plist-get component :examples)))
         (builder (jetpacs-m3--example-builder example))
         (source (jetpacs-elisp-source-for-function builder))
         (source-text (plist-get source :text))
         (preview (jetpacs-m3-example-preview-screen component index nil))
         (canonical
          (decode-coding-string (jetpacs-node->canonical-json preview)
                                'utf-8 t))
         (bounded (jetpacs-catalog-inspection-bounded-text
                   canonical jetpacs-m3-inspection-max-chars))
         (upstream (jetpacs-m3--link (plist-get example :source))))
    (apply
     #'jetpacs-lazy-column
     (append
      (list
       (jetpacs-text (plist-get example :description) :style "caption"))
      (when-let* ((doc (jetpacs-m3-example-doc example)))
        (list (jetpacs-text "Builder documentation" :style "title")
              (jetpacs-text doc :style "caption" :selectable t)))
      (when upstream
        (list (jetpacs-button "Share upstream Kotlin source" upstream
                              :icon "share" :variant "tonal")))
      (list
       (jetpacs-m3-inspection--mono-block
        "Authored Elisp" (plist-get source :caption) source-text
        (not (eq (plist-get source :origin) 'unavailable)))
       (jetpacs-m3-inspection--mono-block
        "Canonical Preview EBP"
        (if (plist-get bounded :truncated)
            "The emitted Preview document is truncated for display."
          "The exact emitted Preview scaffold document.")
        (plist-get bounded :text) t)
       :spacing 12 :content-padding 16)))))

(defun jetpacs-m3-inspection-example-screen (component index back)
  "Build COMPONENT example INDEX's projection with BACK navigation."
  (let* ((id (plist-get component :id))
         (example (nth index (plist-get component :examples)))
         (mode (jetpacs-m3-inspection--example-mode id index))
         (selector (jetpacs-m3-inspection--selector
                    "example" mode id index))
         (screen-id (jetpacs-m3-example-screen-id id index))
         (actions (jetpacs-m3--actions
                   screen-id
                   (plist-get component :guidelines)
                   (plist-get component :docs)
                   (plist-get example :source))))
    (pcase mode
      ("preview"
       (jetpacs-m3-inspection--prepend-selector
        (jetpacs-m3-example-preview-screen component index back) selector))
      ("design"
       (jetpacs-m3-inspection--design-screen
        (jetpacs-m3-example-preview-screen component index back) selector))
      ("visual"
       (jetpacs-m3-inspection--screen
        (format "%s · Visual" (plist-get example :name)) selector
        (jetpacs-m3-inspection--example-visual component index)
        back :actions actions))
      ("lisp"
       (jetpacs-m3-inspection--screen
        (format "%s · Lisp" (plist-get example :name)) selector
        (jetpacs-with-attrs
         (jetpacs-m3-repl-panel component index) :padding 16)
        back :actions actions))
      (_
       (jetpacs-m3-inspection--screen
        (format "%s · Source" (plist-get example :name)) selector
        (jetpacs-m3-inspection--example-source component index)
        back :actions actions)))))

;;;; Selection action and installation

(defun jetpacs-m3-inspection--on-presentation (args params)
  "Validate ARGS, select one projection, and refresh PARAMS' surface."
  (let* ((scope (plist-get args :scope))
         (value (plist-get args :value))
         (component-id (plist-get args :component))
         (index (plist-get args :index))
         (component (and (stringp component-id)
                         (jetpacs-m3-component component-id)))
         (mode (and (integerp value)
                    (<= 0 value)
                    (< value (length jetpacs-m3-inspection-modes))
                    (aref jetpacs-m3-inspection-modes value))))
    (cond
     ((or (not (member scope '("root" "component" "example")))
          (not (integerp value))
          (< value 0)
          (>= value (length jetpacs-m3-inspection-modes)))
      'rejected)
     ((and (member scope '("component" "example")) (null component))
      'stale)
     ((and (equal scope "example")
           (not (and (integerp index)
                     (<= 0 index)
                     (< index (length (plist-get component :examples))))))
      'stale)
     (t
      (pcase scope
        ("root" (setq jetpacs-m3-inspection-root-mode mode))
        ("component"
         (puthash component-id mode jetpacs-m3-inspection-component-modes))
        (_
         (puthash (jetpacs-m3-example-screen-id component-id index) mode
                  jetpacs-m3-inspection-example-modes)))
      (jetpacs-flow-continue
       (lambda ()
         (condition-case err
             (jetpacs-shell-push
              (or (plist-get params :surface) jetpacs-m3-owner))
           (error
            (message "jetpacs-m3: projection refresh failed: %s"
                     (jetpacs-error-label err))))))
      'accepted))))

(defun jetpacs-m3-inspection-register ()
  "Install the catalog's projection dispatchers and action handler."
  (setq jetpacs-m3-home-screen-function
        #'jetpacs-m3-inspection-home-screen
        jetpacs-m3-component-screen-function
        #'jetpacs-m3-inspection-component-screen
        jetpacs-m3-example-screen-function
        #'jetpacs-m3-inspection-example-screen
        jetpacs-m3-presentation-handler-function
        #'jetpacs-m3-inspection--on-presentation))

(jetpacs-m3-inspection-register)

(provide 'jetpacs-m3-inspection)
;;; jetpacs-m3-inspection.el ends here
