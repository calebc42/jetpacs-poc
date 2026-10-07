;;; jetpacs-applet-catalog.el --- The agent-facing catalog export -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later
;; Package-Requires: ((emacs "30.1"))

;;; Commentary:

;; One generated, agent-facing catalog of everything an authoring agent may
;; emit: the EBP contract's core nodes with their member types, every
;; installed renderer-extension manifest's nodes in the format-2 type
;; algebra (invisible to `jetpacs-applet-tooling-describe-node' before this
;; module), each node's real Elisp builders with their documentation, the
;; universal attributes, the action vocabulary, and — for an applet
;; checkout — its `jetpacs-defaction' registrations with their `:doc' and
;; `:args'.  It is rendered three ways: a JSON catalog, a one-line-per-node
;; Markdown catalog in the style json-render's `catalog.prompt()' prints,
;; and a SKILL.md for coding agents.  All three are committed and
;; `jetpacs-applet-catalog-check' pins them, as the vocabulary projections
;; are pinned.
;;
;; Static mode holds: manifests are parsed as JSON, builders come from the
;; tooling's native-reader index and nothing is evaluated.

;;; Code:

(require 'cl-lib)
(require 'json)
(require 'seq)
(require 'subr-x)
(require 'jetpacs-applet-tooling)

(defgroup jetpacs-applet-catalog nil
  "The agent-facing catalog export of the Jetpacs vocabulary."
  :group 'jetpacs-applet-tooling)

(defconst jetpacs-applet-catalog-format 1
  "The catalog JSON's own format number.")

(defconst jetpacs-applet-catalog-generator "jetpacs-applet-catalog/1"
  "The generator name written into every rendering.")

(defcustom jetpacs-applet-catalog-manifests
  '(("renderer-extensions/jetpacs-material3.json"
     . "emacs/apps/jetpacs-material3/jetpacs-material3-vocabulary.el")
    ("jetpacs-components/renderer-extensions/jetpacs-components.json"
     . "jetpacs-components/lisp/jetpacs-components/jetpacs-components-vocabulary.el")
    ("jetpacs-components/renderer-extensions/jetpacs-design.json"
     . "jetpacs-components/lisp/jetpacs-components/jetpacs-design-vocabulary.el"))
  "Workspace-relative renderer-extension manifests and their Elisp projections.
Each manifest's nodes join the catalog in this order; its generated
projection supplies the resolved-schema digest the ebp-compose generator
computed, so the catalog never re-derives it."
  :type '(alist :key-type (string :tag "Manifest")
                :value-type (string :tag "Elisp projection"))
  :group 'jetpacs-applet-catalog)

(defcustom jetpacs-applet-catalog-output-directory "jetpacs-applet-mcp/catalog"
  "Workspace-relative directory of the committed JSON and Markdown catalogs."
  :type 'string
  :group 'jetpacs-applet-catalog)

(defcustom jetpacs-applet-catalog-skill-path "skills/jetpacs-applet/SKILL.md"
  "Workspace-relative path of the generated SKILL.md."
  :type 'string
  :group 'jetpacs-applet-catalog)

(defcustom jetpacs-applet-catalog-builder-roots '("emacs/" "jetpacs-components/lisp/")
  "Workspace-relative prefixes whose public definitions count as builders.
Applet and example sources mention node types too; only the platform's
constructors are the vocabulary's builders."
  :type '(repeat string)
  :group 'jetpacs-applet-catalog)

;;;; Reading

(defun jetpacs-applet-catalog--read-json (relative)
  "Parse the workspace JSON file RELATIVE as hash tables and lists."
  (with-temp-buffer
    (insert-file-contents (jetpacs-applet-tooling-resolve-file relative))
    (json-parse-buffer :object-type 'hash-table :array-type 'list
                       :null-object :json-null :false-object :json-false)))

(defun jetpacs-applet-catalog--sha256 (file)
  "The SHA-256 of FILE's bytes, lowercase hexadecimal."
  (with-temp-buffer
    (set-buffer-multibyte nil)
    (insert-file-contents-literally file)
    (secure-hash 'sha256 (current-buffer))))

(defun jetpacs-applet-catalog--schema-sha256 (projection)
  "The resolved-schema digest the generator wrote into PROJECTION, or nil.
PROJECTION is the workspace-relative generated Elisp file; its
`*-schema-sha256' defconst is read with the Lisp reader, never evaluated."
  (seq-some (lambda (record)
              (let ((form (plist-get record :form)))
                (and (eq (car-safe form) 'defconst)
                     (symbolp (nth 1 form))
                     (string-suffix-p "-schema-sha256" (symbol-name (nth 1 form)))
                     (stringp (nth 2 form))
                     (nth 2 form))))
            (jetpacs-applet-tooling--read-file
             (jetpacs-applet-tooling-resolve-file projection))))

(defun jetpacs-applet-catalog--sorted-keys (table)
  "The keys of hash table TABLE as a sorted list of strings."
  (sort (hash-table-keys table) #'string<))

(defun jetpacs-applet-catalog--first-sentence (text)
  "The first sentence of docstring TEXT, or nil."
  (when (and (stringp text) (not (string-empty-p text)))
    (let ((line (car (split-string text "\n" t))))
      (if (string-match "\\`\\(.*?[.!?]\\)\\(?: \\|\\'\\)" line)
          (match-string 1 line)
        line))))

;;;; Member types

(defun jetpacs-applet-catalog--core-type (contract type member)
  "Render MEMBER's type on core node TYPE from CONTRACT (SPEC 17.1 order).
TYPE nil renders a universal attribute.  The order is the one
`ebp_member_derivation.py' and the generators share: a node-scoped field
type or enum, then the member's own enum, then its field type, then the
hook and children rules."
  (let* ((field-types (gethash "field_types" contract))
         (enums (gethash "enums" contract))
         (scoped (and type (concat type "." member)))
         (domain (or (and scoped (gethash scoped enums)) (gethash member enums)))
         (tag (or (and scoped (gethash scoped field-types))
                  (gethash member field-types)))
         (hooks (gethash "hook_keys" (gethash "actions" contract))))
    (cond
     ((and domain (or (null tag) (equal tag "enum")))
      (format "enum[%s]" (string-join domain "|")))
     (tag tag)
     ((or (member member hooks) (string-prefix-p "on_" member)) "action")
     ((equal member "children") "node-array")
     (t "untyped"))))

(defun jetpacs-applet-catalog--format-2-type (schema &optional depth)
  "Render a format-2 member SCHEMA compactly; DEPTH bounds nested objects.
A `ref' renders as its definition name, which the extension's Definitions
section spells out, so a catalog line stays one line."
  (let ((type (gethash "type" schema)) (depth (or depth 0)))
    (pcase type
      ("ref" (gethash "ref" schema))
      ("boolean" "boolean")
      ((or "integer" "number")
       (format "%s(%s..%s)" type (gethash "minimum" schema) (gethash "maximum" schema)))
      ("string"
       (format "string(%s..%s)" (gethash "min_length" schema) (gethash "max_length" schema)))
      ("enum" (format "enum[%s]" (string-join (gethash "values" schema) "|")))
      ("object"
       (if (> depth 1)
           "object{…}"
         (let ((required (gethash "required" schema))
               (optional (gethash "optional" schema)))
           (format "object{%s}"
                   (string-join
                    (append
                     (mapcar (lambda (m)
                               (format "%s: %s" m
                                       (jetpacs-applet-catalog--format-2-type
                                        (gethash m required) (1+ depth))))
                             (jetpacs-applet-catalog--sorted-keys required))
                     (mapcar (lambda (m)
                               (format "%s?: %s" m
                                       (jetpacs-applet-catalog--format-2-type
                                        (gethash m optional) (1+ depth))))
                             (jetpacs-applet-catalog--sorted-keys optional)))
                    ", ")))))
      ("array"
       (format "array<%s>(%s..%s)"
               (jetpacs-applet-catalog--format-2-type (gethash "items" schema) (1+ depth))
               (gethash "min_items" schema) (gethash "max_items" schema)))
      ("identifier-map"
       (format "map<identifier→%s>(%s..%s)"
               (jetpacs-applet-catalog--format-2-type (gethash "values" schema) (1+ depth))
               (gethash "min_entries" schema) (gethash "max_entries" schema)))
      ("node-array" (format "node-array(%s..%s)"
                            (gethash "min_items" schema) (gethash "max_items" schema)))
      ((or "node" "action" "swipe-side") type)
      (_ (or type "untyped")))))

;;;; Builders

(defun jetpacs-applet-catalog--form-strings (form acc)
  "Collect every string literal of FORM into the hash set ACC; return ACC."
  (cond ((stringp form) (puthash form t acc))
        ((consp form)
         (let ((p form))
           (while (consp p)
             (jetpacs-applet-catalog--form-strings (car p) acc)
             (setq p (cdr p)))
           (when p (jetpacs-applet-catalog--form-strings p acc))))
        ((vectorp form)
         (mapc (lambda (x) (jetpacs-applet-catalog--form-strings x acc)) form)))
  acc)

(defun jetpacs-applet-catalog--builder-index ()
  "Public platform definitions paired with the string literals they contain.
Only `defun', `cl-defun', `defmacro' and `cl-defmacro' records below
`jetpacs-applet-catalog-builder-roots' qualify; a builder is found by the
node-type literal its form carries, never by evaluating it."
  (delq nil
        (mapcar
         (lambda (record)
           (let ((name (plist-get record :name))
                 (path (plist-get record :path)))
             (and name
                  (memq (plist-get record :kind) '(defun cl-defun defmacro cl-defmacro))
                  (jetpacs-applet-tooling--public-record-p record)
                  (seq-some (lambda (root) (string-prefix-p root path))
                            jetpacs-applet-catalog-builder-roots)
                  (list :record record
                        :strings (jetpacs-applet-catalog--form-strings
                                  (plist-get record :form)
                                  (make-hash-table :test #'equal))))))
         (jetpacs-applet-tooling-refresh-index))))

(defun jetpacs-applet-catalog--builders (index node-type)
  "Builder records for NODE-TYPE from INDEX, the conventional name first.
The conventional name is `jetpacs-' plus the type with `.' and `_' as `-';
a `jetpacs-' name ending in the type's last segment ranks next.  Only those
two ranks count while either exists: a function that merely mentions a
common type name, as every button builder mentions \"text\", is not its
builder.  When neither exists every mention is listed, by name, so a new
node without a conventional builder still shows what constructs it."
  (let* ((hyphenated (replace-regexp-in-string "[._]" "-" node-type))
         (conventional (concat "jetpacs-" hyphenated))
         (last-segment (car (last (split-string node-type "\\."))))
         (suffix (concat "-" (replace-regexp-in-string "_" "-" last-segment)))
         (hits (seq-filter (lambda (entry) (gethash node-type (plist-get entry :strings)))
                           index))
         (rank (lambda (entry)
                 (let ((name (plist-get (plist-get entry :record) :name)))
                   (cond ((equal name conventional) 0)
                         ((and (string-prefix-p "jetpacs-" name)
                               (string-suffix-p suffix name))
                          1)
                         (t 2))))))
    (let ((named (seq-filter (lambda (entry) (< (funcall rank entry) 2)) hits)))
      (mapcar (lambda (entry) (plist-get entry :record))
              (sort (or named hits)
                    (lambda (a b)
                      (let ((ra (funcall rank a)) (rb (funcall rank b)))
                        (or (< ra rb)
                            (and (= ra rb)
                                 (string< (plist-get (plist-get a :record) :name)
                                          (plist-get (plist-get b :record) :name)))))))))))

(defun jetpacs-applet-catalog--builder-entry (record)
  "The catalog's view of builder RECORD."
  (let ((entry (list :name (plist-get record :name)
                     :args (format "%S" (plist-get record :args))
                     :path (plist-get record :path)
                     :line (plist-get record :line))))
    (when-let* ((doc (jetpacs-applet-catalog--first-sentence (plist-get record :doc))))
      (setq entry (plist-put entry :doc doc)))
    entry))

;;;; Nodes

(defun jetpacs-applet-catalog--core-node (contract index type)
  "The catalog entry of core node TYPE from CONTRACT, with builders from INDEX."
  (let* ((row (gethash type (gethash "node_schema" contract)))
         (required (gethash "required" row))
         (optional (gethash "optional" row))
         (hooks (gethash "hook_keys" (gethash "actions" contract)))
         (builders (jetpacs-applet-catalog--builders index type))
         (members (vconcat
                   (mapcar (lambda (m)
                             (list :name m :required t
                                   :type (jetpacs-applet-catalog--core-type contract type m)))
                           required)
                   (mapcar (lambda (m)
                             (list :name m :required :json-false
                                   :type (jetpacs-applet-catalog--core-type contract type m)))
                           optional)))
         (entry (list :type type
                      :members members
                      :events (vconcat (seq-filter (lambda (m) (member m hooks))
                                                   (append required optional)))
                      :children (if (member "children" (append required optional)) t :json-false)
                      :builders (vconcat (mapcar #'jetpacs-applet-catalog--builder-entry builders)))))
    (when-let* ((doc (and builders
                          (jetpacs-applet-catalog--first-sentence
                           (plist-get (car builders) :doc)))))
      (setq entry (plist-put entry :description doc)))
    entry))

(defun jetpacs-applet-catalog--extension-node (manifest index type)
  "The catalog entry of extension node TYPE from the format-2 MANIFEST.
Builders come from INDEX."
  (let* ((row (gethash type (gethash "node_schema" manifest)))
         (required (gethash "required" row))
         (optional (gethash "optional" row))
         (targets (gethash "targets" manifest))
         (builders (jetpacs-applet-catalog--builders index type))
         (member-entry
          (lambda (name schema required-p)
            (let ((entry (list :name name :required (if required-p t :json-false)
                               :type (jetpacs-applet-catalog--format-2-type schema))))
              (when-let* ((doc (gethash "description" schema)))
                (setq entry (plist-put entry :description doc)))
              entry)))
         (names (append (jetpacs-applet-catalog--sorted-keys required)
                        (jetpacs-applet-catalog--sorted-keys optional)))
         (entry (list :type type
                      :members (vconcat
                                (mapcar (lambda (m) (funcall member-entry m (gethash m required) t))
                                        (jetpacs-applet-catalog--sorted-keys required))
                                (mapcar (lambda (m) (funcall member-entry m (gethash m optional) nil))
                                        (jetpacs-applet-catalog--sorted-keys optional)))
                      :events (vconcat (seq-filter
                                        (lambda (m)
                                          (equal "action"
                                                 (gethash "type" (or (gethash m required)
                                                                     (gethash m optional)))))
                                        names))
                      :children (if (member "children" names) t :json-false)
                      :targets (vconcat (seq-filter (lambda (target) (member type (gethash target targets)))
                                                    (jetpacs-applet-catalog--sorted-keys targets)))
                      :builders (vconcat (mapcar #'jetpacs-applet-catalog--builder-entry builders)))))
    (when-let* ((doc (or (gethash "description" row)
                         (and builders
                              (jetpacs-applet-catalog--first-sentence
                               (plist-get (car builders) :doc))))))
      (setq entry (plist-put entry :description doc)))
    entry))

(defun jetpacs-applet-catalog--extension (index manifest-path projection)
  "The catalog entry of the manifest at MANIFEST-PATH.
Builders come from INDEX; PROJECTION is the manifest's generated Elisp
file, read for the schema digest."
  (let* ((manifest (jetpacs-applet-catalog--read-json manifest-path))
         (definitions (gethash "definitions" manifest))
         (entry (list :extension (gethash "extension" manifest)
                      :description (gethash "description" manifest)
                      :manifest manifest-path
                      :sha256 (jetpacs-applet-catalog--sha256
                               (jetpacs-applet-tooling-resolve-file manifest-path))
                      :nodes (vconcat (mapcar (lambda (type)
                                                (jetpacs-applet-catalog--extension-node manifest index type))
                                              (gethash "node_types" manifest)))
                      :definitions (vconcat
                                    (mapcar (lambda (name)
                                              (let* ((schema (gethash name definitions))
                                                     (def (list :name name
                                                                :type (jetpacs-applet-catalog--format-2-type schema))))
                                                (when-let* ((doc (gethash "description" schema)))
                                                  (setq def (plist-put def :description doc)))
                                                def))
                                            (jetpacs-applet-catalog--sorted-keys definitions))))))
    (when-let* ((digest (jetpacs-applet-catalog--schema-sha256 projection)))
      (setq entry (plist-put entry :schema_sha256 digest)))
    entry))

;;;; Actions and applets

(defun jetpacs-applet-catalog--applet-actions (applet-path)
  "The `jetpacs-defaction' registrations statically found below APPLET-PATH."
  (let (actions)
    (dolist (file (jetpacs-applet-tooling--applet-files applet-path))
      (let ((scan (jetpacs-applet-tooling--scan-applet-file file)))
        (dolist (action (plist-get scan :actions))
          (let ((entry (list :name (plist-get action :name)
                             :handler (plist-get action :handler)
                             :path (plist-get action :path)
                             :line (plist-get action :line))))
            (when (plist-get action :any-surface)
              (setq entry (plist-put entry :any_surface t)))
            (when (plist-member action :args)
              (setq entry (plist-put entry :args (format "%S" (plist-get action :args)))))
            (when (plist-get action :doc)
              (setq entry (plist-put entry :doc (plist-get action :doc))))
            (push entry actions)))))
    (vconcat (sort actions (lambda (a b)
                             (string< (format "%s" (plist-get a :name))
                                      (format "%s" (plist-get b :name))))))))

;;;; The catalog

(defun jetpacs-applet-catalog-data (&optional applet-path)
  "Return the catalog as a plist of vectors and strings, JSON-ready.
With APPLET-PATH, a workspace-relative applet file or directory, its
`jetpacs-defaction' registrations join as `:applet'.  Deterministic: core
nodes follow the contract's order, extensions follow
`jetpacs-applet-catalog-manifests', and every map is sorted."
  (let* ((contract (jetpacs-applet-tooling--contract))
         (index (jetpacs-applet-catalog--builder-index))
         (data
          (list :catalog_format jetpacs-applet-catalog-format
                :generator jetpacs-applet-catalog-generator
                :contract (list :format (gethash "contract_format" contract)
                                :spec_version (gethash "spec_version" contract)
                                :sha256 (jetpacs-applet-catalog--sha256
                                         (jetpacs-applet-tooling--contract-file)))
                :core_nodes (vconcat (mapcar (lambda (type)
                                               (jetpacs-applet-catalog--core-node contract index type))
                                             (gethash "node_types" contract)))
                :extensions (vconcat (mapcar (lambda (entry)
                                               (jetpacs-applet-catalog--extension
                                                index (car entry) (cdr entry)))
                                             jetpacs-applet-catalog-manifests))
                :universal_attributes
                (vconcat (mapcar (lambda (m)
                                   (list :name m
                                         :type (jetpacs-applet-catalog--core-type contract nil m)))
                                 (gethash "universal_node_attributes" contract)))
                :features (vconcat (gethash "features" contract))
                :capabilities (vconcat (gethash "capabilities" contract))
                :actions (jetpacs-applet-tooling-action-vocabulary))))
    (when applet-path
      (setq data (plist-put data :applet
                            (list :path applet-path
                                  :actions (jetpacs-applet-catalog--applet-actions applet-path)))))
    data))

(defun jetpacs-applet-catalog-json (data)
  "Serialize catalog DATA as one line of JSON plus a newline.
`json-serialize' hands back UTF-8 octets as a unibyte string on Emacs 30;
decoded here so the text joins a multibyte buffer and encodes as UTF-8."
  (let ((text (json-serialize data :false-object :json-false :null-object :json-null)))
    (concat (if (multibyte-string-p text) text (decode-coding-string text 'utf-8))
            "\n")))

;;;; Markdown

(defun jetpacs-applet-catalog--node-line (node)
  "Render catalog NODE as json-render's one-line component form.
`- type: { m: type, opt?: type } - desc [accepts children] [targets: …]
\\[events: …] [elisp: name (args)]', then one indented line per documented
member."
  (let* ((members (plist-get node :members))
         (signature
          (string-join
           (mapcar (lambda (m)
                     (format "%s%s: %s" (plist-get m :name)
                             (if (eq (plist-get m :required) t) "" "?")
                             (plist-get m :type)))
                   members)
           ", "))
         (builders (plist-get node :builders))
         (tags (delq nil
                     (list (and (eq (plist-get node :children) t) "accepts children")
                           (and (> (length (plist-get node :targets)) 0)
                                (format "targets: %s"
                                        (string-join (plist-get node :targets) ", ")))
                           (and (> (length (plist-get node :events)) 0)
                                (format "events: %s"
                                        (string-join (plist-get node :events) ", ")))
                           (and (> (length builders) 0)
                                (format "elisp: %s %s"
                                        (plist-get (aref builders 0) :name)
                                        (plist-get (aref builders 0) :args))))))
         (documented (seq-filter (lambda (m) (plist-get m :description)) members)))
    (concat
     (format "- %s: { %s }%s%s\n"
             (plist-get node :type) signature
             (if-let* ((doc (plist-get node :description))) (format " - %s" doc) "")
             (if tags (format " [%s]" (string-join tags "] [")) ""))
     (mapconcat (lambda (m) (format "  - %s: %s\n" (plist-get m :name) (plist-get m :description)))
                documented "")
     (if (> (length builders) 1)
         (format "  - also: %s\n"
                 (string-join (mapcar (lambda (b) (plist-get b :name))
                                      (cdr (append builders nil)))
                              ", "))
       ""))))

(defun jetpacs-applet-catalog--header (data)
  "The Markdown header for catalog DATA."
  (let ((contract (plist-get data :contract)))
    (format "# Jetpacs catalog\n\nGenerated by %s from `contract.json` (format %s, spec %s, sha256 %s) and %d renderer-extension manifest(s). Do not edit: regenerate with `tools/gen-jetpacs-catalog.sh`; `tools/check-jetpacs-catalog.sh` pins it.\n\n"
            (plist-get data :generator)
            (plist-get contract :format) (plist-get contract :spec_version)
            (plist-get contract :sha256)
            (length (plist-get data :extensions)))))

(defun jetpacs-applet-catalog--nodes-markdown (data)
  "The core and extension node sections of catalog DATA."
  (concat
   (format "## Nodes (EBP core, %d)\n\nEvery core node is built by its `jetpacs-*` constructor; member types are the contract's SPEC 17.1 tags.\n\n"
           (length (plist-get data :core_nodes)))
   (mapconcat #'jetpacs-applet-catalog--node-line (plist-get data :core_nodes) "")
   (mapconcat
    (lambda (extension)
      (concat
       (format "\n## Nodes (%s, %d) - %s\n\nManifest `%s`%s. A node travels only to a target whose profile advertises both the node type and the extension.\n\n"
               (plist-get extension :extension)
               (length (plist-get extension :nodes))
               (plist-get extension :description)
               (plist-get extension :manifest)
               (if-let* ((digest (plist-get extension :schema_sha256)))
                   (format ", schema sha256 %s" digest)
                 ""))
       (mapconcat #'jetpacs-applet-catalog--node-line (plist-get extension :nodes) "")
       (if (> (length (plist-get extension :definitions)) 0)
           (concat (format "\n### Definitions (%s)\n\n" (plist-get extension :extension))
                   (mapconcat (lambda (def)
                                (format "- %s: %s%s\n" (plist-get def :name) (plist-get def :type)
                                        (if-let* ((doc (plist-get def :description)))
                                            (format " - %s" doc)
                                          "")))
                              (plist-get extension :definitions) ""))
         "")))
    (plist-get data :extensions) "")))

(defun jetpacs-applet-catalog--attributes-markdown (data)
  "The universal-attribute section of catalog DATA."
  (concat
   (format "\n## Universal attributes (%d)\n\nApply with `jetpacs-with-attrs`. `bind`, `template` and `visible_when` need the `data.model` capability; `template` and `visible_when` also need the `data.template` and `data.visible` features, which the shell checks before sending.\n\n"
           (length (plist-get data :universal_attributes)))
   (mapconcat (lambda (attr) (format "- %s: %s\n" (plist-get attr :name) (plist-get attr :type)))
              (plist-get data :universal_attributes) "")
   (format "\nFeatures: %s. Capabilities: %s.\n"
           (string-join (plist-get data :features) ", ")
           (string-join (plist-get data :capabilities) ", "))))

(defun jetpacs-applet-catalog--applet-markdown (data)
  "The applet section of catalog DATA, or an empty string."
  (if-let* ((applet (plist-get data :applet)))
      (concat
       (format "\n## Applet `%s` (%d actions)\n\n" (plist-get applet :path)
               (length (plist-get applet :actions)))
       (mapconcat (lambda (action)
                    (format "- %s%s - %s (%s, %s:%s)%s\n"
                            (plist-get action :name)
                            (if (plist-get action :any_surface) " [any surface]" "")
                            (or (plist-get action :doc) "undocumented")
                            (plist-get action :handler)
                            (plist-get action :path) (plist-get action :line)
                            (if-let* ((args (plist-get action :args)))
                                (format "\n  - args: %s" args)
                              "")))
                  (plist-get applet :actions) ""))
    ""))

(defconst jetpacs-applet-catalog--rules
  '("Builders are pure readers of explicit state and return the plist/vector screen IR; effects belong in actions."
    "Action names are dotted EBP identifiers registered with `jetpacs-defaction` and emitted with `jetpacs-action`; `args` are plain data."
    "Bind values with `jetpacs-ref`, lists with `jetpacs-template`, visibility with `jetpacs-with-attrs :visible_when (vector (jetpacs-when …))`; identity (`key`, `id`) and dispatch stay authored."
    "A `children` array holding a conditional node is keyed throughout; nothing beneath a conditional node carries an `id`, input state, an editor or a `variant_host`."
    "An extension node needs its extension installed with `jetpacs-register-renderer-extension` and advertised by the target profile."
    "Node `id`s are unique per document; sibling `key`s are unique per parent."
    "Validate with the applet MCP's `jetpacs_validate_applet` and the shell gates; never guess a member the catalog does not list.")
  "The authoring rules every rendering repeats.")

(defun jetpacs-applet-catalog--rules-markdown ()
  "The numbered rules section."
  (concat "\n## Rules\n\n"
          (let ((n 0))
            (mapconcat (lambda (rule) (format "%d. %s\n" (cl-incf n) rule))
                       jetpacs-applet-catalog--rules ""))))

(defun jetpacs-applet-catalog-markdown (data &optional section)
  "Render catalog DATA as Markdown; SECTION narrows it.
SECTION is nil or \"all\", or one of \"nodes\", \"attributes\", \"actions\"."
  (pcase (or section "all")
    ("nodes" (concat (jetpacs-applet-catalog--header data)
                     (jetpacs-applet-catalog--nodes-markdown data)))
    ("attributes" (concat (jetpacs-applet-catalog--header data)
                          (jetpacs-applet-catalog--attributes-markdown data)))
    ("actions" (concat (jetpacs-applet-catalog--header data)
                       "\n## Actions\n\n" (plist-get data :actions) "\n"))
    ("all" (concat (jetpacs-applet-catalog--header data)
                   (jetpacs-applet-catalog--nodes-markdown data)
                   (jetpacs-applet-catalog--attributes-markdown data)
                   "\n## Actions\n\n" (plist-get data :actions) "\n"
                   (jetpacs-applet-catalog--applet-markdown data)
                   (jetpacs-applet-catalog--rules-markdown)))
    (_ (error "Jetpacs Applet Catalog: unknown section %S" section))))

;;;; SKILL.md

(defconst jetpacs-applet-catalog--key-exports
  '(("jetpacs-defapp" "Register an app with its roots and lifecycle.")
    ("jetpacs-chrome-define-root" "Register a chrome app's screen stack root and its `:data-builder`.")
    ("jetpacs-shell-define-root" "Register a plain surface root and its `:data-builder`.")
    ("jetpacs-defaction" "Register a dotted action handler with `:doc` and `:args`.")
    ("jetpacs-action" "Emit a remote ActionDescriptor; builtins have their own constructors.")
    ("jetpacs-with-attrs" "Merge universal attributes (`:key`, `:id`, `:visible_when`, …) into a node.")
    ("jetpacs-ref" "Bind a member to a JSON Pointer in the surface data document.")
    ("jetpacs-when" "One `visible_when` predicate: path, op and operand.")
    ("jetpacs-template" "Expand one row node per element of a bound array.")
    ("jetpacs-app-defer-refresh" "Re-push a surface after a handler.")
    ("jetpacs-app-defer-patch" "Patch the data document after a handler (`jetpacs-data-set`, `-remove`, `-insert`).")
    ("jetpacs-app-defer-sync" "Patch a keyed array by diff after a handler.")
    ("jetpacs-shell-push" "Push a surface through the sender gates."))
  "The exports the SKILL.md tables, with one-line purposes.")

(defun jetpacs-applet-catalog-skill (data)
  "Render the SKILL.md for coding agents from catalog DATA.
The frontmatter follows the `skills/*/SKILL.md' convention json-render
ships: `name', a trigger-style `description', then task-oriented
sections and the compact catalog."
  (concat
   "---\n"
   "name: jetpacs-applet\n"
   "description: Author, extend or review a Jetpacs applet — an Emacs Lisp screen builder for the EBP Companion. Use when writing jetpacs-* Elisp, choosing EBP core or jetpacs.*/material3.* nodes, binding a surface data document, adding conditional nodes or templates, or wiring jetpacs-defaction handlers.\n"
   "---\n\n"
   "<!-- Generated by " (plist-get data :generator)
   " from contract.json and the renderer-extension manifests; regenerate with tools/gen-jetpacs-catalog.sh. Claude Code discovers this file when it is linked into .claude/skills/. -->\n\n"
   "# Jetpacs applet authoring\n\n"
   "## Key concepts\n\n"
   "- **One IR.** A builder returns the plist/vector screen IR (`:t` first, wire member names with underscores, children as vectors); the same datum is inspected, gated and serialized. No parallel AST, no codegen.\n"
   "- **Emacs owns state and policy.** The Companion renders, keeps drafts and delivery records, and dispatches named actions back; it evaluates nothing.\n"
   "- **Data document.** `jetpacs-ref` binds a member to a pointer; `jetpacs-template` expands rows; `jetpacs-when` predicates on `:visible_when` show or hide keyed children; handlers patch with `jetpacs-app-defer-patch`/`-sync` and refresh only on a shape change.\n"
   "- **Actions are a durability boundary.** `accepted` means the effect is durable; handlers take `(ARGS PARAMS)` and return `accepted`, `stale` or `rejected`.\n"
   "- **Negotiated vocabulary.** Every node, builtin, feature and extension must be in the target profile; the shell gates refuse what is not.\n\n"
   "## Workflow\n\n"
   "1. Read `jetpacs-applet-mcp/APPLET-GUIDE.md` (lifecycle, builders, data document, actions) and `jetpacs-applet-mcp/DETERMINISM.md` (the determinism envelope). With the `jetpacs-applets` MCP running, `jetpacs_applet_guide`, `jetpacs_describe_node`, `jetpacs_describe_actions` and `jetpacs_catalog` answer the same questions live.\n"
   "2. Scaffold with `jetpacs_applet_template` (or copy `jetpacs-applet-mcp/examples/hello/hello.el`), register the app, its roots and actions.\n"
   "3. Build screens from the catalog below; bind values, template lists, make parts conditional instead of rebuilding.\n"
   "4. Validate statically (`jetpacs_validate_applet`), run the applet's ERT suite, byte-compile with warnings as errors into a temporary directory, then a narrow device flow.\n\n"
   "## Key exports\n\n"
   "| Export | Purpose |\n|---|---|\n"
   (mapconcat (lambda (row) (format "| `%s` | %s |\n" (car row) (cadr row)))
              jetpacs-applet-catalog--key-exports "")
   "\n"
   (jetpacs-applet-catalog--nodes-markdown data)
   (jetpacs-applet-catalog--attributes-markdown data)
   "\n## Actions\n\n" (plist-get data :actions) "\n"
   (jetpacs-applet-catalog--rules-markdown)))

;;;; Rendering entry points

(defun jetpacs-applet-catalog-render (&optional format section applet-path)
  "Render the catalog in FORMAT (\"markdown\" or \"json\"), narrowed to SECTION.
APPLET-PATH adds an applet's registrations.  The MCP tool's entry point."
  (let ((data (jetpacs-applet-catalog-data applet-path)))
    (pcase (or format "markdown")
      ("json" (jetpacs-applet-catalog-json data))
      ("markdown" (jetpacs-applet-catalog-markdown data section))
      (_ (error "Jetpacs Applet Catalog: unknown format %S" format)))))

(defun jetpacs-applet-catalog-describe-node (node-type)
  "Describe extension NODE-TYPE as `jetpacs-applet-tooling-describe-node' does.
Returns nil when no installed manifest declares NODE-TYPE."
  (let ((index (jetpacs-applet-catalog--builder-index)))
    (seq-some
     (lambda (entry)
       (let ((manifest (jetpacs-applet-catalog--read-json (car entry))))
         (when (member node-type (gethash "node_types" manifest))
           (let* ((node (jetpacs-applet-catalog--extension-node manifest index node-type))
                  (builders (plist-get node :builders)))
             (jetpacs-applet-tooling--cap
              (concat
               (format "Extension node: %s (extension %s, manifest %s)\n%s\n"
                       node-type (gethash "extension" manifest) (car entry)
                       (or (plist-get node :description) ""))
               (format "Targets: %s\n\n"
                       (if (> (length (plist-get node :targets)) 0)
                           (string-join (plist-get node :targets) ", ")
                         "none"))
               "Members:\n"
               (mapconcat (lambda (m)
                            (format "- %s%s: %s%s" (plist-get m :name)
                                    (if (eq (plist-get m :required) t) "" "?")
                                    (plist-get m :type)
                                    (if-let* ((doc (plist-get m :description)))
                                        (format " - %s" doc)
                                      "")))
                          (plist-get node :members) "\n")
               (if (> (length builders) 0)
                   (concat "\n\nBuilders:\n"
                           (mapconcat (lambda (b)
                                        (format "- %s %s (%s:%s)%s" (plist-get b :name)
                                                (plist-get b :args) (plist-get b :path)
                                                (plist-get b :line)
                                                (if-let* ((doc (plist-get b :doc)))
                                                    (format "\n  %s" doc)
                                                  "")))
                                      builders "\n"))
                 "\n\nBuilders: none found in the platform sources")))))))
     jetpacs-applet-catalog-manifests)))

;;;; The committed renderings

(defun jetpacs-applet-catalog-outputs ()
  "Alist of (WORKSPACE-RELATIVE-PATH . TEXT) for every committed rendering."
  (let ((data (jetpacs-applet-catalog-data))
        (dir jetpacs-applet-catalog-output-directory))
    (list (cons (concat dir "/jetpacs-catalog.json") (jetpacs-applet-catalog-json data))
          (cons (concat dir "/CATALOG.md") (jetpacs-applet-catalog-markdown data))
          (cons jetpacs-applet-catalog-skill-path (jetpacs-applet-catalog-skill data)))))

(defun jetpacs-applet-catalog--output-file (relative)
  "The absolute path of the committed rendering RELATIVE."
  (expand-file-name relative (jetpacs-applet-tooling-workspace-root)))

(defun jetpacs-applet-catalog-write ()
  "Write every rendering into the workspace; return the paths written."
  (mapcar (lambda (out)
            (let ((file (jetpacs-applet-catalog--output-file (car out))))
              (make-directory (file-name-directory file) t)
              (with-temp-file file
                (set-buffer-file-coding-system 'utf-8-unix)
                (insert (cdr out)))
              (car out)))
          (jetpacs-applet-catalog-outputs)))

(defun jetpacs-applet-catalog-check ()
  "Non-nil when every committed rendering equals a fresh one byte for byte.
Prints each stale or missing path, so a drift check names what to regenerate."
  (let (stale)
    (dolist (out (jetpacs-applet-catalog-outputs))
      (let ((file (jetpacs-applet-catalog--output-file (car out))))
        (unless (and (file-exists-p file)
                     (equal (with-temp-buffer
                              (insert-file-contents file)
                              (buffer-string))
                            (cdr out)))
          (push (car out) stale))))
    (dolist (path (nreverse stale))
      (message "stale catalog rendering: %s" path))
    (null stale)))

(provide 'jetpacs-applet-catalog)
;;; jetpacs-applet-catalog.el ends here
