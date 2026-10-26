;;; jetpacs-applet-examples.el --- Harvest catalog examples from the live applets -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later

;;; Commentary:

;; The agent-facing catalog (jetpacs-applet-catalog.el) is static: it reads
;; the contract, the manifests and the platform sources and evaluates
;; nothing.  The examples it wants to show are NOT static — the component
;; catalog's canonical documents compile through the real builders and the
;; Material 3 catalog's samples are functions — so they are harvested here,
;; with the platform loaded, into one committed JSON file the static catalog
;; then joins like any other input:
;;
;;   jetpacs-applet-mcp/catalog/examples.json
;;
;; shadcn/ui publishes its demos as `registry:example' items beside the
;; components and its MCP tells an agent to look them up after a search
;; (ebp/slop-docs/SHADCN-harvest.md §3).  This file is the Jetpacs quarry for
;; that: every example is a real node the catalog applets already render, so
;; nothing here is authored twice.
;;
;; Run from the jetpacs-poc root with the platform on `load-path':
;;
;;   tools/gen-jetpacs-examples.sh      regenerate examples.json
;;   tools/check-jetpacs-examples.sh    drift check (run-tests.sh)
;;
;; Determinism: component-catalog documents are canonical by construction;
;; Material 3 samples are the same pure builders `tools/m3-check.sh' gates,
;; and `jetpacs-m3-example-source' truncates authored text at a fixed cap.

;;; Code:

(require 'cl-lib)
(require 'seq)
(require 'subr-x)
(require 'json)
(require 'jetpacs-widgets)
(require 'jetpacs-authoring)
(require 'jetpacs-component-authoring)
(require 'jetpacs-design-material)
(require 'jetpacs-m3-catalog)

(defconst jetpacs-applet-examples-format 1
  "The examples JSON's own format number.")

(defconst jetpacs-applet-examples-generator "jetpacs-applet-examples/1"
  "The generator name written into the rendering.")

(defconst jetpacs-applet-examples-per-key 2
  "How many examples one node type or one builder keeps, in catalog order.")

(defconst jetpacs-applet-examples-path "jetpacs-applet-mcp/catalog/examples.json"
  "Workspace-relative path of the committed rendering.")

(defun jetpacs-applet-examples--workspace-root ()
  "The jetpacs-poc checkout the rendering is written into."
  (file-name-as-directory
   (expand-file-name (or (getenv "JETPACS_WORKSPACE") default-directory))))

;;;; Nodes to JSON

(defun jetpacs-applet-examples--node-object (node)
  "NODE (a builder's plist) as parsed JSON, keys in canonical order.
Serializing through `jetpacs-node->canonical-json' sorts keys and drops
nil members exactly as the goldens do; parsing it back gives the catalog a
structured object whose insertion order IS that canonical order."
  (json-parse-string
   (let ((text (jetpacs-node->canonical-json node)))
     (if (multibyte-string-p text) text (decode-coding-string text 'utf-8)))
   :object-type 'hash-table :array-type 'array
   :null-object :json-null :false-object :json-false))

(defun jetpacs-applet-examples--entry (source node &rest extra)
  "One example plist: SOURCE, the JSON object of NODE, and EXTRA keys."
  (append (list :source source :node (jetpacs-applet-examples--node-object node))
          extra))

;;;; The component catalog's canonical documents

(defun jetpacs-applet-examples--component-catalog ()
  "Alist of (NODE-TYPE . EXAMPLES) from the component catalog's defaults.
Each catalog component has one canonical inert document; its root compiles
through the public builders into the node the Preview screen shows.  The
`:ir' text is the root as the authoring model prints it — the plist an
applet returns literally."
  (let (result)
    (dolist (id jetpacs-component-authoring-components)
      (let* ((document (jetpacs-component-authoring-default-document id))
             (normalized (jetpacs-component-authoring-normalize-document document))
             (type (cdr (assoc id jetpacs-component-authoring--component-root-types)))
             (node (jetpacs-component-authoring-compile-document document))
             (entry (jetpacs-applet-examples--entry
                     "component-catalog" node
                     :name id
                     :ir (jetpacs-authoring-print
                          (plist-get normalized :root)
                          jetpacs-component-authoring-max-bytes))))
        (push entry (alist-get type result nil nil #'equal))))
    (mapcar (lambda (pair) (cons (car pair) (nreverse (cdr pair))))
            (nreverse result))))

;;;; The design proof library

(defconst jetpacs-applet-examples--design-types
  '(("jetpacs.design_scope" . "jetpacs-design-material-scope")
    ("jetpacs.styled" . "jetpacs-design-material-card")
    ("jetpacs.pressable" . "jetpacs-design-material-filled-button")
    ("jetpacs.inline_text" . "jetpacs-design-inline-text")
    ("jetpacs.inline" . "jetpacs-design-inline")
    ("jetpacs.glyph" . "jetpacs-design-glyph")
    ("jetpacs.wheel" . "jetpacs-design-wheel")
    ("jetpacs.state_switch" . "jetpacs-design-state-switch"))
  "Design nodes and the proof-library builder that produces each one.")

(defun jetpacs-applet-examples--design-tree ()
  "The proof library's own composition: a scope around a card around a button.
`jetpacs.design' has no catalog page and no golden; this is the one tree
its builders are documented to make (jetpacs-design-material.el)."
  (jetpacs-design-material-scope
   (list (jetpacs-design-material-card
          (list (jetpacs-text "Proof library card")
                ;; A paragraph with an app glyph riding inline.
                (jetpacs-design-inline-text
                 (list (jetpacs-text "Logged ")
                       (jetpacs-design-inline
                        (jetpacs-design-glyph '("M0 2h4v8H0zM6 0h4v10H6z") 10 10 14)
                        24 :reserve-text "x" :reserve-extra 8)
                       (jetpacs-text " today")))
                ;; A wheel, and a hint that follows it without a round trip.
                (jetpacs-design-wheel "unit" '("Day" "Week") "Week")
                (jetpacs-design-state-switch
                 "unit" `(("Day" . ,(jetpacs-text "Every day"))
                          ("Week" . ,(jetpacs-text "Every week"))))
                (jetpacs-design-material-filled-button
                 "Save" (jetpacs-action "catalog.save")))))))

(defun jetpacs-applet-examples--find-node (value type)
  "The first node of TYPE inside VALUE (a node plist, vector or list), or nil."
  (cond
   ((and (consp value) (keywordp (car value)))
    (if (equal (plist-get value :t) type)
        value
      (cl-loop for (_key child) on value by #'cddr
               thereis (jetpacs-applet-examples--find-node child type))))
   ((or (vectorp value) (consp value))
    (seq-some (lambda (child) (jetpacs-applet-examples--find-node child type)) value))
   (t nil)))

(defun jetpacs-applet-examples--design ()
  "Alist of (NODE-TYPE . EXAMPLES) from the design proof library's tree."
  (let ((tree (jetpacs-applet-examples--design-tree)))
    (delq nil
          (mapcar (lambda (pair)
                    (when-let* ((node (jetpacs-applet-examples--find-node tree (car pair))))
                      (list (car pair)
                            (jetpacs-applet-examples--entry
                             "design-material" node
                             :name (cdr pair)
                             :ir (jetpacs-authoring-print node (* 64 1024))))))
                  jetpacs-applet-examples--design-types))))

;;;; The Material 3 catalog's samples

(defun jetpacs-applet-examples--m3-node (example)
  "The node EXAMPLE documents, or nil when it is unsupported or has none.
The documenting builder decides (`jetpacs-m3--example-builder'): a sample
that IS chrome claims `:top-bar' (one argument, BACK, nil at the stack
bottom) or a nullary slot, and its `:build' is only a backdrop.  Otherwise
the body: `:build' first, then a bare `:top-bar'."
  (let* ((build (plist-get example :build))
         (top-bar (plist-get example :top-bar))
         (slots (cl-loop for (_key value) on (plist-get example :slots) by #'cddr
                         when (functionp value) collect value))
         (documenting (ignore-errors (jetpacs-m3--example-builder example))))
    (cond
     ((plist-get example :unsupported) nil)
     ((and documenting (eq documenting top-bar)) (funcall top-bar nil))
     ((and documenting (memq documenting slots)) (funcall documenting))
     ((functionp build) (funcall build))
     ((functionp top-bar) (funcall top-bar nil))
     (t nil))))

(defun jetpacs-applet-examples--m3-source (example)
  "The authored Elisp of EXAMPLE's documenting builder, or nil."
  (when-let* ((builder (or (jetpacs-m3--example-builder example)
                           (plist-get example :build))))
    (let ((source (jetpacs-m3-example-source builder)))
      (and (not (string-empty-p (plist-get source :text)))
           (not (string-prefix-p "This example has no elisp" (plist-get source :text)))
           (plist-get source :text)))))

(defun jetpacs-applet-examples--m3 ()
  "Alist of (BUILDER-NAME . EXAMPLES) from the Material 3 catalog.
A component names the vocabulary builders it is made of; its first
examples with a buildable node are kept under each of those builders, so
the static catalog can join them to a node type through the builder it
already found for that type."
  (let (result)
    (dolist (component jetpacs-m3-components)
      (let ((examples
             (seq-take
              (delq nil
                    (mapcar
                     (lambda (example)
                       (when-let* ((node (jetpacs-applet-examples--m3-node example)))
                         (let ((entry (jetpacs-applet-examples--entry
                                       "m3-catalog" node
                                       :name (plist-get example :name)
                                       :component (plist-get component :id))))
                           (when-let* ((elisp (jetpacs-applet-examples--m3-source example)))
                             (setq entry (plist-put entry :elisp elisp)))
                           entry)))
                     (plist-get component :examples)))
              jetpacs-applet-examples-per-key)))
        (when examples
          (dolist (builder (plist-get component :builders))
            (let ((name (symbol-name builder)))
              (unless (assoc name result)
                (push (cons name examples) result)))))))
    (sort result (lambda (a b) (string< (car a) (car b))))))

;;;; The rendering

(defun jetpacs-applet-examples-data ()
  "The examples as a JSON-ready plist."
  (let ((by-type (append (jetpacs-applet-examples--component-catalog)
                         (jetpacs-applet-examples--design)))
        (by-builder (jetpacs-applet-examples--m3))
        (object (make-hash-table :test #'equal)))
    (puthash "format" jetpacs-applet-examples-format object)
    (puthash "generator" jetpacs-applet-examples-generator object)
    (let ((types (make-hash-table :test #'equal)))
      (dolist (pair by-type)
        (puthash (car pair) (vconcat (seq-take (cdr pair) jetpacs-applet-examples-per-key))
                 types))
      (puthash "by_type" types object))
    (let ((builders (make-hash-table :test #'equal)))
      (dolist (pair by-builder)
        (puthash (car pair) (vconcat (cdr pair)) builders))
      (puthash "by_builder" builders object))
    object))

(defun jetpacs-applet-examples-json (data)
  "Serialize DATA as one line of JSON plus a newline, decoded to multibyte."
  (let ((text (json-serialize data :false-object :json-false :null-object :json-null)))
    (concat (if (multibyte-string-p text) text (decode-coding-string text 'utf-8))
            "\n")))

(defun jetpacs-applet-examples-file ()
  "The absolute path of the committed rendering."
  (expand-file-name jetpacs-applet-examples-path
                    (jetpacs-applet-examples--workspace-root)))

(defun jetpacs-applet-examples-write ()
  "Write the rendering into the workspace; return its workspace-relative path."
  (let ((file (jetpacs-applet-examples-file)))
    (make-directory (file-name-directory file) t)
    (with-temp-file file
      (set-buffer-file-coding-system 'utf-8-unix)
      (insert (jetpacs-applet-examples-json (jetpacs-applet-examples-data))))
    jetpacs-applet-examples-path))

(defun jetpacs-applet-examples-check ()
  "Non-nil when the committed rendering equals a fresh one byte for byte."
  (let ((file (jetpacs-applet-examples-file))
        (fresh (jetpacs-applet-examples-json (jetpacs-applet-examples-data))))
    (if (and (file-exists-p file)
             (equal (with-temp-buffer (insert-file-contents file) (buffer-string))
                    fresh))
        t
      (message "stale examples rendering: %s" jetpacs-applet-examples-path)
      nil)))

(provide 'jetpacs-applet-examples)
;;; jetpacs-applet-examples.el ends here
