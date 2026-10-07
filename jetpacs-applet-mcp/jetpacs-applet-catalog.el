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

(defconst jetpacs-applet-catalog-format 2
  "The catalog JSON's own format number.
Format 2 adds `examples' to every node entry (harvested by
jetpacs-applet-examples.el, with golden lines as the fallback).")

(defconst jetpacs-applet-catalog-generator "jetpacs-applet-catalog/2"
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

(defcustom jetpacs-applet-catalog-examples-path "jetpacs-applet-mcp/catalog/examples.json"
  "Workspace-relative path of the harvested examples (jetpacs-applet-examples.el).
Absent, every node falls back to its golden line alone."
  :type 'string
  :group 'jetpacs-applet-catalog)

(defcustom jetpacs-applet-catalog-core-golden "ebp/goldens/widgets.golden"
  "Repositories-relative golden corpus the core nodes take their fallback from."
  :type 'string
  :group 'jetpacs-applet-catalog)

(defconst jetpacs-applet-catalog-examples-per-node 2
  "How many examples one node entry carries at most.")

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

;;;; Examples
;;
;; shadcn publishes demos as `registry:example' items and its MCP sends an
;; agent from a search to them (ebp/slop-docs/SHADCN-harvest.md §3).  Here
;; every node carries up to two: the harvested ones first (the component
;; catalog's canonical document for the type, then the Material 3 samples of
;; the node's first builder), and always one whose `t' IS the type — the
;; first matching line of the corpus golden — so an agent sees the exact
;; shape even when a sample shows the builder inside a larger node.

(defvar jetpacs-applet-catalog--inputs nil
  "Per-rendering memo of parsed inputs (examples.json, goldens), or nil.
Bound by `jetpacs-applet-catalog-data' so one rendering reads each file once.")

(defun jetpacs-applet-catalog--memo (key thunk)
  "The value of THUNK, memoized under KEY while a rendering is in progress."
  (if jetpacs-applet-catalog--inputs
      (let ((cached (gethash key jetpacs-applet-catalog--inputs 'unread)))
        (if (eq cached 'unread)
            (puthash key (funcall thunk) jetpacs-applet-catalog--inputs)
          cached))
    (funcall thunk)))

(defun jetpacs-applet-catalog--parse-json-arrays (text)
  "Parse JSON TEXT with objects as hash tables and arrays as vectors.
Node objects must round-trip through `json-serialize', which wants
vectors for arrays; `jetpacs-applet-catalog--read-json' reads lists."
  (json-parse-string text :object-type 'hash-table :array-type 'array
                     :null-object :json-null :false-object :json-false))

(defun jetpacs-applet-catalog--examples-index ()
  "The harvested examples as a hash table, or nil when the file is absent."
  (jetpacs-applet-catalog--memo
   'examples
   (lambda ()
     (let ((file (expand-file-name jetpacs-applet-catalog-examples-path
                                   (jetpacs-applet-tooling-workspace-root))))
       (when (file-exists-p file)
         (let ((index (jetpacs-applet-catalog--parse-json-arrays
                       (with-temp-buffer (insert-file-contents file) (buffer-string)))))
           (unless (eql (gethash "format" index) 1)
             (error "Jetpacs Applet Catalog: unknown examples format %S"
                    (gethash "format" index)))
           index))))))

(defun jetpacs-applet-catalog--golden-nodes (relative)
  "The nodes of the golden corpus RELATIVE, one per `NN {json}' line.
Lines that do not parse as an object are skipped; an absent file is empty."
  (jetpacs-applet-catalog--memo
   (cons 'golden relative)
   (lambda ()
     (let ((file (condition-case nil
                     (jetpacs-applet-tooling-resolve-file relative)
                   (error nil))))
       (when (and file (file-exists-p file))
         (delq nil
               (mapcar (lambda (line)
                         (when (string-match "\\`[0-9]+ \\({.*}\\)\\'" line)
                           (ignore-errors
                             (jetpacs-applet-catalog--parse-json-arrays (match-string 1 line)))))
                       (split-string (with-temp-buffer (insert-file-contents file)
                                                       (buffer-string))
                                     "\n" t))))))))

(defun jetpacs-applet-catalog--golden-path (manifest-path)
  "The golden corpus beside the manifest at MANIFEST-PATH."
  (concat (file-name-sans-extension manifest-path) ".golden"))

(defun jetpacs-applet-catalog--example-type (example)
  "The `t' of EXAMPLE's node, or nil."
  (let ((node (gethash "node" example)))
    (and (hash-table-p node) (gethash "t" node))))

(defun jetpacs-applet-catalog--examples (type builders golden)
  "Up to `jetpacs-applet-catalog-examples-per-node' examples of node TYPE.
BUILDERS are the node's builder records; GOLDEN the corpus to fall back
on.  Harvested examples come first; one whose node is literally a TYPE is
guaranteed when the golden has such a line."
  (let* ((index (jetpacs-applet-catalog--examples-index))
         (by-type (and index (gethash type (gethash "by_type" index))))
         (by-builder (and index (> (length builders) 0)
                          (gethash (plist-get (aref builders 0) :name)
                                   (gethash "by_builder" index))))
         (harvested (seq-take (append by-type nil (append by-builder nil))
                              jetpacs-applet-catalog-examples-per-node))
         (exact (seq-find (lambda (node) (equal (gethash "t" node) type))
                          (jetpacs-applet-catalog--golden-nodes golden)))
         (result harvested))
    (when (and exact
               (not (seq-some (lambda (e) (equal (jetpacs-applet-catalog--example-type e) type))
                              result)))
      (let ((entry (make-hash-table :test #'equal)))
        (puthash "source" "golden" entry)
        (puthash "node" exact entry)
        (setq result (seq-take (append result (list entry))
                               jetpacs-applet-catalog-examples-per-node))
        (unless (seq-some (lambda (e) (equal (jetpacs-applet-catalog--example-type e) type))
                          result)
          (setq result (append (butlast result) (list entry))))))
    (vconcat result)))

(defun jetpacs-applet-catalog--example-json (example)
  "EXAMPLE's node as compact JSON text."
  (let ((text (json-serialize (gethash "node" example)
                              :false-object :json-false :null-object :json-null)))
    (if (multibyte-string-p text) text (decode-coding-string text 'utf-8))))

(defconst jetpacs-applet-catalog-brief-max-chars 1200
  "In brief renderings, an example text longer than this is named, not shown.")

(defun jetpacs-applet-catalog--examples-markdown (examples &optional full)
  "Markdown lines for the vector EXAMPLES, indented under a node line.
Each example is one `- example (SOURCE[, NAME]): JSON' line; with FULL,
its `ir' or `elisp' text follows in a fenced block.  Brief renderings
name instead of inline anything over `jetpacs-applet-catalog-brief-max-chars'."
  (mapconcat
   (lambda (example)
     (let* ((source (gethash "source" example))
            (name (gethash "name" example))
            (ir (gethash "ir" example))
            (elisp (gethash "elisp" example))
            (json (jetpacs-applet-catalog--example-json example))
            (fits (lambda (text)
                    (or full (<= (length text) jetpacs-applet-catalog-brief-max-chars))))
            (fence (lambda (text)
                     (format "\n    ```elisp\n%s    ```\n\n"
                             (replace-regexp-in-string "^" "    " (string-trim-right text) t t)))))
       (concat
        (format "  - example (%s%s): %s\n" source
                (if name (format ", %s" name) "")
                (if (funcall fits json)
                    (format "`%s`" json)
                  (format "a %d-character node; see `jetpacs_node_examples`" (length json))))
        (cond
         ((and ir (or full (not elisp)) (funcall fits ir)) (funcall fence ir))
         ((and full elisp) (funcall fence elisp))
         (t "")))))
   examples ""))

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
    (plist-put entry :examples
               (jetpacs-applet-catalog--examples type (plist-get entry :builders)
                                                 jetpacs-applet-catalog-core-golden))))

(defun jetpacs-applet-catalog--extension-node (manifest index type &optional golden)
  "The catalog entry of extension node TYPE from the format-2 MANIFEST.
Builders come from INDEX; GOLDEN is the manifest's corpus, for examples."
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
    (plist-put entry :examples
               (jetpacs-applet-catalog--examples type (plist-get entry :builders) golden))))

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
                                                (jetpacs-applet-catalog--extension-node
                                                 manifest index type
                                                 (jetpacs-applet-catalog--golden-path manifest-path)))
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
  (let* ((jetpacs-applet-catalog--inputs (make-hash-table :test #'equal))
         (contract (jetpacs-applet-tooling--contract))
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

(defun jetpacs-applet-catalog--node-line (node &optional examples)
  "Render catalog NODE as json-render's one-line component form.
`- type: { m: type, opt?: type } - desc [accepts children] [targets: …]
\\[events: …] [elisp: name (args)]', then one indented line per documented
member.  EXAMPLES nil omits the node's examples; `brief' shows them as one
JSON line each (plus the IR of a component-catalog document); `full' adds
every example's authored Elisp in a fenced block."
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
       "")
     (if (and examples (> (length (plist-get node :examples)) 0))
         (jetpacs-applet-catalog--examples-markdown (plist-get node :examples)
                                                    (eq examples 'full))
       ""))))

(defun jetpacs-applet-catalog--header (data)
  "The Markdown header for catalog DATA."
  (let ((contract (plist-get data :contract)))
    (format "# Jetpacs catalog\n\nGenerated by %s from `contract.json` (format %s, spec %s, sha256 %s) and %d renderer-extension manifest(s). Do not edit: regenerate with `tools/gen-jetpacs-catalog.sh`; `tools/check-jetpacs-catalog.sh` pins it.\n\n"
            (plist-get data :generator)
            (plist-get contract :format) (plist-get contract :spec_version)
            (plist-get contract :sha256)
            (length (plist-get data :extensions)))))

(defun jetpacs-applet-catalog--nodes-markdown (data &optional examples)
  "The core and extension node sections of catalog DATA.
EXAMPLES is nil, `brief' or `full' as for `jetpacs-applet-catalog--node-line';
`extensions' shows brief examples under extension nodes only, which keeps
the SKILL.md readable while CATALOG.md carries everything."
  (concat
   (format "## Nodes (EBP core, %d)\n\nEvery core node is built by its `jetpacs-*` constructor; member types are the contract's SPEC 17.1 tags.%s\n\n"
           (length (plist-get data :core_nodes))
           (if (memq examples '(brief full))
               " Each node lists up to two examples: a real node from the catalog applets, and one from the goldens whose `t` is the type."
             " Examples per node are in `jetpacs-applet-mcp/catalog/CATALOG.md` and the `jetpacs_node_examples` MCP tool."))
   (mapconcat (lambda (node)
                (jetpacs-applet-catalog--node-line
                 node (and (memq examples '(brief full)) examples)))
              (plist-get data :core_nodes) "")
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
       (mapconcat (lambda (node)
                    (jetpacs-applet-catalog--node-line
                     node (pcase examples
                            ('extensions 'brief)
                            ((or 'brief 'full) examples)
                            (_ nil))))
                  (plist-get extension :nodes) "")
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

;;;; Rules
;;
;; shadcn's skill keeps its rules as one-liners that each link to a file of
;; Incorrect/Correct code pairs (`skills/shadcn/rules/*.md'); an agent
;; applies a pair far more reliably than a sentence.  The same rules as
;; before, now each with the Elisp it forbids and the Elisp it wants.  The
;; files are generated beside the skill and pinned by the drift check.

(defconst jetpacs-applet-catalog--rule-files
  '(("ir" "IR and builders"
     "A builder returns the plist/vector screen IR and nothing else: the same datum is inspected, gated and serialized. No parallel AST, no codegen, no effects on the way."
     (("Builders are pure readers of explicit state and return the screen IR; effects belong in actions."
       "(defun notes--view ()\n  (notes--reload-from-disk)          ; an effect inside a builder\n  (jetpacs-text (notes--title)))"
       "(defun notes--view ()\n  (jetpacs-text (notes--title)))\n\n(jetpacs-defaction \"notes.reload\" #'notes--on-reload\n                   :doc \"Reload the notes from disk\")"
       "A builder may run on any push, replay or cache check; an effect inside it runs that often too and is never acknowledged.")
      ("Build nodes with the constructors, never as hand-written wire plists."
       "(list :t \"button\" :label \"Go\" :on_tap (list :action \"app.go\"))"
       "(jetpacs-button \"Go\" (jetpacs-action \"app.go\"))"
       "The constructors check members, enum domains and identifiers against the contract; a literal plist skips every check and drifts with the next amendment.")
      ("Children are passed as arguments to a container constructor; the IR is data, not text."
       "(format \"{\\\"t\\\":\\\"column\\\",\\\"children\\\":[%s]}\" rows)"
       "(jetpacs-column (jetpacs-text \"A\") (jetpacs-text \"B\") :spacing 8)"
       "Serialization belongs to the shell. DETERMINISM.md forbids a parallel AST or a code generator; the builder's plist is the one IR.")
      ("Node ids are unique per document and sibling keys per parent; identity is authored, never generated per render."
       "(jetpacs-with-attrs row :id (format \"row-%d\" (random 1000)))"
       "(jetpacs-with-attrs row :key (plist-get item :id))"
       "A generated identity changes on every build, so the Companion sees a new node each time: focus, scroll and drafts are lost and diffs degrade to replacements (SPEC 16.1).")))
    ("data" "Data document, templates and conditional nodes"
     "Values live in the surface data document and nodes bind to them; a handler patches the document and the surface is re-pushed only when its shape changes."
     (("Bind a value with `jetpacs-ref` and patch it; do not bake it into the node and re-push."
       "(jetpacs-text (notes--title))\n;; later, on every change:\n(jetpacs-app-defer-refresh params)"
       "(jetpacs-text (jetpacs-ref \"/title\"))\n;; later, in the handler:\n(jetpacs-app-defer-patch (list (jetpacs-data-set \"/title\" new-title)) params)"
       "A patch is a few hundred bytes and keeps scroll and drafts; a re-push resends the whole tree (SPEC 13.8.3-13.8.4).")
      ("Expand a list with `jetpacs-template` over a bound array; key every row from the item."
       "(apply #'jetpacs-lazy-column\n       (mapcar (lambda (row) (jetpacs-text (plist-get row :title))) rows))"
       "(jetpacs-lazy-column\n (jetpacs-template \"/rows\"\n   (jetpacs-with-attrs (jetpacs-text (jetpacs-ref \"title\"))\n                       :key (jetpacs-ref \"id\"))))"
       "Inside the template a `jetpacs-ref` without a leading slash resolves from the item; the row must bind `:key` and may not author `:key` or `:id`. A Companion without `data.template` receives the expansion from Emacs (SPEC 13.8.5).")
      ("Hide a node with `:visible_when` on a keyed child; keep siblings keyed and nothing stateful beneath it."
       "(jetpacs-column\n (jetpacs-text \"Settings\")\n (jetpacs-with-attrs (jetpacs-text-input \"name\")\n                     :visible_when (vector (jetpacs-when \"/editing\" \"eq\" t))))"
       "(jetpacs-column\n (jetpacs-with-attrs (jetpacs-text \"Settings\") :key \"title\")\n (jetpacs-with-attrs (jetpacs-text \"Unsaved changes\" :color \"error\")\n                     :key \"warning\"\n                     :visible_when (vector (jetpacs-when \"/dirty\" \"eq\" t))))"
       "`visible_when` is a flat AND of bounded predicates evaluated before bindings; the children array holding it is keyed throughout, and an input, editor, `variant_host` or `id` beneath it would lose state when it flips (SPEC 13.8.6, feature `data.visible`).")
      ("Patch with `jetpacs-app-defer-patch` or `-sync`; refresh only when the shape changed."
       "(defun notes--on-toggle (args params)\n  (notes--toggle (plist-get args :id))\n  (jetpacs-app-defer-refresh params)\n  'accepted)"
       "(defun notes--on-toggle (args params)\n  (notes--toggle (plist-get args :id))\n  (jetpacs-app-defer-sync \"/rows\" (notes--rows) params)\n  'accepted)"
       "`jetpacs-app-defer-sync` diffs a keyed array into `insert`/`set`/`remove` ops; a refresh is for a new screen, not a changed value.")))
    ("actions" "Actions and handlers"
     "An action is a dotted identifier the Companion dispatches back; the handler schedules a durable effect and answers immediately."
     (("Action names are dotted EBP identifiers, registered with `jetpacs-defaction` and emitted with `jetpacs-action`; args are plain data."
       "(jetpacs-button \"Save\" (jetpacs-action \"save\"))"
       "(jetpacs-defaction \"notes.save\" #'notes--on-save\n                   :doc \"Save the open note\"\n                   :args '(:id \"the note's id\"))\n\n(jetpacs-button \"Save\" (jetpacs-action \"notes.save\" :args (list :id id)))"
       "The validator refuses an emitted name without a provider and a name without a namespace dot; `:doc` and `:args` are what the catalog and the agent read.")
      ("A handler takes `(ARGS PARAMS)`, schedules its effect and returns `accepted`, `stale` or `rejected`."
       "(defun notes--on-save (args)\n  (notes--write-to-disk args)\n  t)"
       "(defun notes--on-save (args params)\n  (jetpacs-flow-continue\n   (lambda () (notes--write-to-disk args)))\n  'accepted)"
       "`accepted` means the effect is durable or owned by a durable work item; anything else is a protocol lie (SPEC 14.4). The return value is a symbol, not `t`.")
      ("No blocking call and no prompt inside a dispatch; confirmation belongs on the descriptor."
       "(defun notes--on-delete (args _params)\n  (when (y-or-n-p \"Delete?\")\n    (url-retrieve-synchronously (notes--url args)))\n  'accepted)"
       "(jetpacs-button \"Delete\"\n                (jetpacs-action \"notes.delete\" :args (list :id id)\n                                :confirm \"Delete this note?\"))\n\n(defun notes--on-delete (args _params)\n  (jetpacs-flow-continue (lambda () (notes--delete args)))\n  'accepted)"
       "A prompt inside a dispatch blocks the JSON-RPC reader; the validator marks `action.prompt-in-dispatch` an error and `action.blocking-call` a warning. `jetpacs-flow-continue` runs outside the dispatch with the flow identity kept.")
      ("Validate with `jetpacs_validate_applet` and the shell gates; never guess a member the catalog does not list."
       "(jetpacs-button \"Go\" (jetpacs-action \"app.go\") :tooltip \"Go now\")"
       "(jetpacs-button \"Go\" (jetpacs-action \"app.go\") :icon \"arrow_forward\")"
       "A member outside the contract is `1201 content-invalid` on the wire; `jetpacs_describe_node` and `jetpacs_node_examples` show what exists.")))
    ("theme" "Theme, colors and extensions"
     "Colors are theme roles the Companion resolves for its own light or dark scheme; extension nodes travel only where the profile advertises them."
     (("Color with a theme role, never a hex literal."
       "(jetpacs-text \"Overdue\" :color \"#ff0000\")"
       "(jetpacs-text \"Overdue\" :color \"error\")"
       "The thirteen roles (`primary`, `on_primary`, `secondary`, `on_secondary`, `error`, `on_error`, `background`, `on_background`, `surface`, `on_surface`, `outline`, `success`, `warning`) follow the device's scheme and `theme.set`; a literal ignores both (SPEC 16.6, 18.4).")
      ("Never author per-mode colors; the role adapts to dark mode."
       "(jetpacs-text label :color (if (notes--dark-p) \"#ffffff\" \"#000000\"))"
       "(jetpacs-text label :color \"on_background\")"
       "Omitting `dark` in `theme.set` follows the system; the Companion derives a legible on-color when one is missing. Emacs never knows which mode is showing.")
      ("Derived tokens are not EBP names; use a role, not a Material or design token."
       "(jetpacs-text label :color \"on_primary_container\")"
       "(jetpacs-text label :color \"on_primary\")"
       "SPEC 18.4: derived tokens MUST NOT be accepted as `colors` members. Design tokens live in a `jetpacs.design_scope`, which is an extension node, not a color.")
      ("An extension node needs its extension installed and advertised by the target profile; fall back when it is not."
       "(jetpacs-design-profile-scope profile children)"
       "(if (jetpacs-extension-advertised-p \"jetpacs.design\" :app)\n    (jetpacs-design-profile-scope profile children)\n  (jetpacs-column children))"
       "A node travels only to a target whose profile lists both the node type and the extension (SPEC 16.2.1); `jetpacs-defapp :requires-extensions` declares the hard requirement and the Apps screen gates on it."))))
  "The rule files: (NAME TITLE INTRO ((RULE INCORRECT CORRECT WHY) ...)).
Each becomes `skills/jetpacs-applet/rules/NAME.md'; the one-line RULEs are
the Critical Rules list every rendering repeats.")

(defun jetpacs-applet-catalog--rule-file-markdown (entry)
  "The Markdown of rule file ENTRY: a contents list, then one section per pair."
  (pcase-let ((`(,_name ,title ,intro ,rules) entry))
    (concat
     (format "# %s\n\n%s\n\n## Contents\n\n" title intro)
     (mapconcat (lambda (rule) (format "- %s\n" (car rule))) rules "")
     "\n---\n"
     (mapconcat
      (lambda (rule)
        (pcase-let ((`(,sentence ,incorrect ,correct ,why) rule))
          (format "\n## %s\n\n**Incorrect:**\n\n```elisp\n%s\n```\n\n**Correct:**\n\n```elisp\n%s\n```\n\n%s\n"
                  (string-remove-suffix "." sentence) incorrect correct why)))
      rules ""))))

(defun jetpacs-applet-catalog--rules-markdown (&optional link-prefix)
  "The Critical Rules section: one-liners linking to the rule files.
LINK-PREFIX is the relative directory of the rule files from the rendering."
  (let ((prefix (or link-prefix "./rules/")))
    (concat
     "\n## Critical Rules\n\nThese rules are always enforced. Each heading links to a file with Incorrect/Correct Elisp pairs.\n"
     (mapconcat
      (lambda (entry)
        (pcase-let ((`(,name ,title ,_intro ,rules) entry))
          (concat
           (format "\n### %s → [%s.md](%s%s.md)\n\n" title name prefix name)
           (mapconcat (lambda (rule) (format "- **%s**\n" (car rule))) rules ""))))
      jetpacs-applet-catalog--rule-files ""))))

(defun jetpacs-applet-catalog-markdown (data &optional section examples)
  "Render catalog DATA as Markdown; SECTION narrows it.
SECTION is nil or \"all\", or one of \"nodes\", \"attributes\", \"actions\".
EXAMPLES is `brief' (the default: one JSON line per example), `full' (the
committed CATALOG.md: authored Elisp in fenced blocks too) or `none'."
  (let ((examples (pcase examples
                    ((or 'nil 'brief) 'brief)
                    ('full 'full)
                    ('none nil)
                    (_ (error "Jetpacs Applet Catalog: unknown examples mode %S" examples)))))
    (pcase (or section "all")
      ("nodes" (concat (jetpacs-applet-catalog--header data)
                       (jetpacs-applet-catalog--nodes-markdown data examples)))
      ("attributes" (concat (jetpacs-applet-catalog--header data)
                            (jetpacs-applet-catalog--attributes-markdown data)))
      ("actions" (concat (jetpacs-applet-catalog--header data)
                         "\n## Actions\n\n" (plist-get data :actions) "\n"))
      ("all" (concat (jetpacs-applet-catalog--header data)
                     (jetpacs-applet-catalog--nodes-markdown data examples)
                   (jetpacs-applet-catalog--attributes-markdown data)
                   "\n## Actions\n\n" (plist-get data :actions) "\n"
                     (jetpacs-applet-catalog--applet-markdown data)
                     (jetpacs-applet-catalog--rules-markdown
                      "../../skills/jetpacs-applet/rules/")))
      (_ (error "Jetpacs Applet Catalog: unknown section %S" section)))))

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
   "allowed-tools: Bash(sh tools/jetpacs-info.sh), Bash(sh tools/check-jetpacs-catalog.sh), Bash(sh tools/check-jetpacs-examples.sh)\n"
   "---\n\n"
   "<!-- Generated by " (plist-get data :generator)
   " from contract.json and the renderer-extension manifests; regenerate with tools/gen-jetpacs-catalog.sh. Claude Code discovers this file when it is linked into .claude/skills/. -->\n\n"
   "# Jetpacs applet authoring\n\n"
   "## Current project context\n\n"
   "```json\n!`sh tools/jetpacs-info.sh`\n```\n\n"
   "The JSON above is the contract and its digest, the installed renderer extensions, the packaged applets and, once a Companion has connected from this Emacs, `last_profile`: what it advertised the last time a session reached READY. Author against `last_profile` when it is present — only its `node_types`, `builtins`, `features` and `extensions` travel to that device — and against the catalog below otherwise. Run `sh tools/jetpacs-info.sh` again after connecting a different device.\n\n"
   "## Key concepts\n\n"
   "- **One IR.** A builder returns the plist/vector screen IR (`:t` first, wire member names with underscores, children as vectors); the same datum is inspected, gated and serialized. No parallel AST, no codegen.\n"
   "- **Emacs owns state and policy.** The Companion renders, keeps drafts and delivery records, and dispatches named actions back; it evaluates nothing.\n"
   "- **Data document.** `jetpacs-ref` binds a member to a pointer; `jetpacs-template` expands rows; `jetpacs-when` predicates on `:visible_when` show or hide keyed children; handlers patch with `jetpacs-app-defer-patch`/`-sync` and refresh only on a shape change.\n"
   "- **Actions are a durability boundary.** `accepted` means the effect is durable; handlers take `(ARGS PARAMS)` and return `accepted`, `stale` or `rejected`.\n"
   "- **Negotiated vocabulary.** Every node, builtin, feature and extension must be in the target profile; the shell gates refuse what is not.\n\n"
   "## Workflow\n\n"
   "1. Read `jetpacs-applet-mcp/APPLET-GUIDE.md` (lifecycle, builders, data document, actions) and `jetpacs-applet-mcp/DETERMINISM.md` (the determinism envelope). With the `jetpacs-applets` MCP running, `jetpacs_applet_guide`, `jetpacs_describe_node`, `jetpacs_node_examples`, `jetpacs_describe_actions` and `jetpacs_catalog` answer the same questions live; after choosing a node, look at its examples before writing it.\n"
   "2. Scaffold with `jetpacs_applet_template` (or copy `jetpacs-applet-mcp/examples/hello/hello.el`), register the app, its roots and actions.\n"
   "3. Build screens from the catalog below; bind values, template lists, make parts conditional instead of rebuilding.\n"
   "4. Validate statically (`jetpacs_validate_applet`), run the applet's ERT suite, byte-compile with warnings as errors into a temporary directory, then a narrow device flow.\n\n"
   "## Key exports\n\n"
   "| Export | Purpose |\n|---|---|\n"
   (mapconcat (lambda (row) (format "| `%s` | %s |\n" (car row) (cadr row)))
              jetpacs-applet-catalog--key-exports "")
   "\n"
   (jetpacs-applet-catalog--nodes-markdown data 'extensions)
   (jetpacs-applet-catalog--attributes-markdown data)
   "\n## Actions\n\n" (plist-get data :actions) "\n"
   (jetpacs-applet-catalog--rules-markdown)))

;;;; Rendering entry points

(defun jetpacs-applet-catalog-render (&optional format section applet-path examples)
  "Render the catalog in FORMAT (\"markdown\" or \"json\"), narrowed to SECTION.
APPLET-PATH adds an applet's registrations; EXAMPLES is the Markdown
examples mode, `none' by default so the whole catalog fits the MCP result
cap — `jetpacs_node_examples' shows one node's.  The MCP tool's entry point."
  (let ((data (jetpacs-applet-catalog-data applet-path)))
    (pcase (or format "markdown")
      ("json" (jetpacs-applet-catalog-json data))
      ("markdown" (jetpacs-applet-catalog-markdown data section (or examples 'none)))
      (_ (error "Jetpacs Applet Catalog: unknown format %S" format)))))

(defun jetpacs-applet-catalog-describe-node (node-type)
  "Describe extension NODE-TYPE as `jetpacs-applet-tooling-describe-node' does.
Returns nil when no installed manifest declares NODE-TYPE."
  (let ((index (jetpacs-applet-catalog--builder-index)))
    (seq-some
     (lambda (entry)
       (let ((manifest (jetpacs-applet-catalog--read-json (car entry))))
         (when (member node-type (gethash "node_types" manifest))
           (let* ((node (jetpacs-applet-catalog--extension-node
                         manifest index node-type
                         (jetpacs-applet-catalog--golden-path (car entry))))
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
                 "\n\nBuilders: none found in the platform sources")
               (jetpacs-applet-catalog--examples-text (plist-get node :examples))))))))
     jetpacs-applet-catalog-manifests)))

(defun jetpacs-applet-catalog--examples-text (examples &optional limit)
  "The plain-text examples block for the vector EXAMPLES, or an empty string.
LIMIT caps how many are shown."
  (let ((shown (seq-take (append examples nil) (or limit (length examples)))))
    (if (null shown)
        ""
      (concat "\n\nExamples:\n"
              (mapconcat
               (lambda (example)
                 (let ((name (gethash "name" example))
                       (ir (gethash "ir" example))
                       (elisp (gethash "elisp" example)))
                   (concat
                    (format "- %s%s: %s" (gethash "source" example)
                            (if name (format " (%s)" name) "")
                            (jetpacs-applet-catalog--example-json example))
                    (cond (ir (format "\n  IR:\n%s" (replace-regexp-in-string
                                                       "^" "    " (string-trim-right ir) t t)))
                          (elisp (format "\n  Elisp:\n%s" (replace-regexp-in-string
                                                            "^" "    " (string-trim-right elisp) t t)))
                          (t "")))))
               shown "\n")))))

(defun jetpacs-applet-catalog-node-examples (node-type &optional limit)
  "Describe NODE-TYPE's examples: the harvested nodes and the golden line.
NODE-TYPE is a core or an installed extension node; LIMIT caps the count.
Signals an error for an unknown type.  The MCP tool's entry point."
  (unless (and (stringp node-type)
               (string-match-p jetpacs-applet-tooling--identifier-re node-type))
    (error "Jetpacs Applet Catalog: node type must be an EBP identifier"))
  (let* ((jetpacs-applet-catalog--inputs (make-hash-table :test #'equal))
         (contract (jetpacs-applet-tooling--contract))
         (index (jetpacs-applet-catalog--builder-index))
         (node
          (if (member node-type (gethash "node_types" contract))
              (jetpacs-applet-catalog--core-node contract index node-type)
            (seq-some
             (lambda (entry)
               (let ((manifest (jetpacs-applet-catalog--read-json (car entry))))
                 (when (member node-type (gethash "node_types" manifest))
                   (jetpacs-applet-catalog--extension-node
                    manifest index node-type
                    (jetpacs-applet-catalog--golden-path (car entry))))))
             jetpacs-applet-catalog-manifests))))
    (unless node
      (error "Jetpacs Applet Catalog: unknown node type %s" node-type))
    (jetpacs-applet-tooling--cap
     (concat (format "Node: %s (%d example%s)" node-type
                     (length (plist-get node :examples))
                     (if (= (length (plist-get node :examples)) 1) "" "s"))
             (if (> (length (plist-get node :examples)) 0)
                 (jetpacs-applet-catalog--examples-text (plist-get node :examples) limit)
               "\n\nNo example is harvested or in a golden for this node.")))))

;;;; The committed renderings

(defun jetpacs-applet-catalog-outputs ()
  "Alist of (WORKSPACE-RELATIVE-PATH . TEXT) for every committed rendering."
  (let ((data (jetpacs-applet-catalog-data))
        (dir jetpacs-applet-catalog-output-directory)
        (rules-dir (concat (file-name-directory jetpacs-applet-catalog-skill-path) "rules/")))
    (append
     (list (cons (concat dir "/jetpacs-catalog.json") (jetpacs-applet-catalog-json data))
           (cons (concat dir "/CATALOG.md") (jetpacs-applet-catalog-markdown data nil 'full))
           (cons jetpacs-applet-catalog-skill-path (jetpacs-applet-catalog-skill data)))
     (mapcar (lambda (entry)
               (cons (concat rules-dir (car entry) ".md")
                     (jetpacs-applet-catalog--rule-file-markdown entry)))
             jetpacs-applet-catalog--rule-files))))

;;;; Project context (`tools/jetpacs-info.sh', `jetpacs_info')
;;
;; shadcn's skill opens with the project's live configuration, injected at
;; load time by `shadcn info --json'.  The Jetpacs equivalent is static —
;; the contract, the installed manifests, the packaged applets — plus one
;; runtime fact written down by jetpacs-profile-snapshot.el: what the last
;; connected Companion advertised.

(defcustom jetpacs-applet-catalog-packaged-apps-path
  "emacs/apps/packaged-apps/jetpacs-packaged-apps.el"
  "Workspace-relative manifest of the applets the Companion build packages."
  :type 'string
  :group 'jetpacs-applet-catalog)

(defcustom jetpacs-applet-catalog-last-profile-file
  (expand-file-name "jetpacs/last-profile.json" user-emacs-directory)
  "Where jetpacs-profile-snapshot.el writes the last negotiated profile."
  :type 'file
  :group 'jetpacs-applet-catalog)

(defun jetpacs-applet-catalog--packaged-apps ()
  "The packaged-apps manifest as a vector of plists, read, never loaded."
  (let* ((file (condition-case nil
                   (jetpacs-applet-tooling-resolve-file jetpacs-applet-catalog-packaged-apps-path)
                 (error nil)))
         (form (and file (file-exists-p file)
                    (seq-some (lambda (record)
                                (let ((form (plist-get record :form)))
                                  (and (eq (car-safe form) 'setq)
                                       (eq (nth 1 form) 'jetpacs-app-store-packaged-apps)
                                       (nth 2 form))))
                              (jetpacs-applet-tooling--read-file file))))
         (apps (and (eq (car-safe form) 'quote) (nth 1 form))))
    (vconcat
     (mapcar (lambda (app)
               (list :id (plist-get app :app-id)
                     :label (plist-get app :label)
                     :file (plist-get app :name)
                     :feature (symbol-name (plist-get app :feature))
                     :summary (plist-get app :summary)))
             (seq-filter (lambda (app) (and (consp app) (plist-get app :app-id))) apps)))))

(defun jetpacs-applet-catalog-info-data ()
  "The project context as a JSON-ready plist.
`last_profile' is present only when a snapshot exists and parses."
  (let* ((jetpacs-applet-catalog--inputs (make-hash-table :test #'equal))
         (contract (jetpacs-applet-tooling--contract))
         (index (jetpacs-applet-catalog--builder-index))
         (data
          (list :contract (list :format (gethash "contract_format" contract)
                                :spec_version (gethash "spec_version" contract)
                                :sha256 (jetpacs-applet-catalog--sha256
                                         (jetpacs-applet-tooling--contract-file)))
                :catalog (list :format jetpacs-applet-catalog-format
                               :generator jetpacs-applet-catalog-generator
                               :path (concat jetpacs-applet-catalog-output-directory
                                             "/jetpacs-catalog.json")
                               :skill jetpacs-applet-catalog-skill-path)
                :extensions
                (vconcat
                 (mapcar (lambda (entry)
                           (let ((extension (jetpacs-applet-catalog--extension
                                             index (car entry) (cdr entry))))
                             (list :id (plist-get extension :extension)
                                   :manifest (plist-get extension :manifest)
                                   :sha256 (plist-get extension :sha256)
                                   :schema_sha256 (or (plist-get extension :schema_sha256)
                                                      :json-null)
                                   :node_types (vconcat
                                                (mapcar (lambda (n) (plist-get n :type))
                                                        (plist-get extension :nodes))))))
                         jetpacs-applet-catalog-manifests))
                :applets (jetpacs-applet-catalog--packaged-apps))))
    ;; The registries an applet may be added from, when the registry
    ;; foundation is loaded (tools/jetpacs-info.sh loads it; the static MCP
    ;; does once the registry tools are present).
    (when (fboundp 'jetpacs-registry-namespaces)
      (setq data (plist-put data :registries
                            (vconcat
                             (mapcar (lambda (entry)
                                       (list :name (car entry)
                                             :url (or (plist-get (cdr entry) :url) :json-null)))
                                     (jetpacs-registry-namespaces))))))
    (when-let* ((file jetpacs-applet-catalog-last-profile-file)
                (snapshot (and (file-exists-p file)
                               (ignore-errors
                                 (jetpacs-applet-catalog--parse-json-arrays
                                  (with-temp-buffer (insert-file-contents file)
                                                    (buffer-string)))))))
      (setq data (plist-put data :last_profile snapshot)))
    data))

(defun jetpacs-applet-catalog-info-json ()
  "The project context as one line of JSON plus a newline."
  (jetpacs-applet-catalog-json (jetpacs-applet-catalog-info-data)))

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
