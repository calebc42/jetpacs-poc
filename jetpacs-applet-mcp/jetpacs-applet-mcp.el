;;; jetpacs-applet-mcp.el --- MCP server for Jetpacs applet authors -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later
;; Package-Requires: ((emacs "30.1"))

;;; Commentary:

;; A read-only MCP stdio server for building Jetpacs applets.  It indexes the
;; actual Jetpacs/EBP Elisp source plus explicitly allowlisted package source,
;; describes builders/action/node contracts, validates applet registrations
;; and emissions, and returns a lifecycle-complete scaffold.  Applet and
;; reference source is read with the native reader but never loaded/evaluated.
;;
;; Start this file in an Emacs batch process.  README.md gives the exact
;; command and environment variables for static and trusted-runtime modes.

;;; Code:

(require 'json)
(require 'cl-lib)
(require 'seq)
(require 'subr-x)

(load (expand-file-name "jetpacs-applet-tooling.el"
                        (file-name-directory
                         (or load-file-name
                             (bound-and-true-p byte-compile-current-file))))
      nil 'nomessage)

;; The tooling library is path-loaded so this entrypoint remains runnable from
;; an unpackaged checkout.  Declarations give byte compilation the same
;; explicit contract as a normal `require'.
(declare-function jetpacs-applet-tooling--cap "jetpacs-applet-tooling" (text))
(declare-function jetpacs-applet-tooling-applet-manifest "jetpacs-applet-tooling"
                  (path &optional section offset limit))
(declare-function jetpacs-applet-tooling-applet-template "jetpacs-applet-tooling"
                  (id label &optional icon))
(declare-function jetpacs-applet-tooling-action-vocabulary "jetpacs-applet-tooling" ())
(declare-function jetpacs-applet-tooling-api-categories "jetpacs-applet-tooling" ())
(declare-function jetpacs-applet-tooling-check-render-determinism "jetpacs-applet-tooling"
                  (surface &optional include-canonical))
(declare-function jetpacs-applet-tooling-describe-node "jetpacs-applet-tooling" (node-type))
(declare-function jetpacs-applet-tooling-describe-symbol "jetpacs-applet-tooling"
                  (name &optional include-source))
(declare-function jetpacs-applet-tooling-inspect-applet "jetpacs-applet-tooling" (path))
(declare-function jetpacs-applet-tooling-list-api "jetpacs-applet-tooling"
                  (&optional category query limit include-private))
(declare-function jetpacs-applet-tooling-resolve-file "jetpacs-applet-tooling" (path))
(declare-function jetpacs-applet-tooling-runtime-available-p "jetpacs-applet-tooling" ())
(declare-function jetpacs-applet-tooling-runtime-inventory "jetpacs-applet-tooling" ())
(declare-function jetpacs-applet-tooling-summarize-elisp-file "jetpacs-applet-tooling"
                  (filepath &optional include-private))
(declare-function jetpacs-applet-tooling-validate-applet "jetpacs-applet-tooling" (path))
(declare-function jetpacs-applet-tooling-workspace-root "jetpacs-applet-tooling" ())

;;;; Protocol identity and JSON construction

(defconst jetpacs-applet-mcp-version "0.5.0"
  "Semantic version reported by the Jetpacs applet MCP server.")
(defconst jetpacs-applet-mcp-latest-protocol-version "2025-11-25")
(defconst jetpacs-applet-mcp-supported-protocol-versions
  '("2025-11-25" "2025-06-18" "2025-03-26" "2024-11-05"))
(defconst jetpacs-applet-mcp-max-message-bytes (* 4 1024 1024))

(defvar jetpacs-applet-mcp--initialized nil)
(defvar jetpacs-applet-mcp--initialize-seen nil)
(defvar jetpacs-applet-mcp--protocol-version jetpacs-applet-mcp-latest-protocol-version)
(defvar jetpacs-applet-mcp-suppress-auto-start nil
  "Non-nil prevents batch loading this library from entering the stdio loop.")

(defconst jetpacs-applet-mcp--missing (make-symbol "jetpacs-applet-mcp-missing"))

(defun jetpacs-applet-mcp--object (&rest pairs)
  "Build a string-keyed JSON object from alternating PAIRS."
  (let ((object (make-hash-table :test #'equal)))
    (while pairs
      (let ((key (pop pairs)))
        (unless pairs (error "Jetpacs Applet MCP: object key %S has no value" key))
        (puthash key (pop pairs) object)))
    object))

(defun jetpacs-applet-mcp--serialize (object)
  "Serialize OBJECT using explicit JSON null and false sentinels."
  (json-serialize object :null-object :json-null :false-object :json-false))

(defun jetpacs-applet-mcp--text-result (text &optional error-p)
  "Build an MCP text tool result for TEXT; mark ERROR-P when non-nil."
  (let ((result
         (jetpacs-applet-mcp--object
          "content" (vector (jetpacs-applet-mcp--object
                             "type" "text"
                             "text" (jetpacs-applet-tooling--cap text))))))
    (when error-p (puthash "isError" t result))
    result))

(defun jetpacs-applet-mcp--success (id result)
  "Build a JSON-RPC success for ID and RESULT."
  (jetpacs-applet-mcp--object "jsonrpc" "2.0" "id" id "result" result))

(defun jetpacs-applet-mcp--error (id code message)
  "Build a JSON-RPC error for ID, CODE, and MESSAGE."
  (jetpacs-applet-mcp--object
   "jsonrpc" "2.0" "id" id
   "error" (jetpacs-applet-mcp--object "code" code "message" message)))

(defun jetpacs-applet-mcp--property (type description &optional enum)
  "Return a JSON Schema property of TYPE with DESCRIPTION and optional ENUM."
  (let ((property (jetpacs-applet-mcp--object "type" type "description" description)))
    (when enum (puthash "enum" (vconcat enum) property))
    property))

(defun jetpacs-applet-mcp--schema (properties &optional required)
  "Build a closed object schema from PROPERTIES alist and REQUIRED names."
  (jetpacs-applet-mcp--object
   "type" "object"
   "properties" (let ((object (make-hash-table :test #'equal)))
                  (dolist (property properties object)
                    (puthash (car property) (cdr property) object)))
   "required" (vconcat required)
   "additionalProperties" :json-false))

;;;; Self-describing tool catalog

(defun jetpacs-applet-mcp--tool (name title description schema &optional invokes-code)
  "Build tool NAME with TITLE, DESCRIPTION, and input SCHEMA.
INVOKES-CODE marks the explicit trusted-runtime tool that calls an applet
builder; static tools are read-only and idempotent."
  (jetpacs-applet-mcp--object
   "name" name "title" title "description" description "inputSchema" schema
   "annotations" (jetpacs-applet-mcp--object
                  "readOnlyHint" (if invokes-code :json-false t)
                  "destructiveHint" :json-false
                  "idempotentHint" (if invokes-code :json-false t)
                  "openWorldHint" :json-false)))

(defun jetpacs-applet-mcp--tools ()
  "Return all applet-authoring tool descriptors."
  (vconcat
   (vector
   (jetpacs-applet-mcp--tool
    "jetpacs_applet_guide" "Read the applet guide"
    "Return the concise applet lifecycle, ownership, one-IR rendering, determinism, action, manifest, package-source API, and testing guide."
    (jetpacs-applet-mcp--schema nil))
   (jetpacs-applet-mcp--tool
    "jetpacs_describe_actions" "Describe the action model"
    "Derive the open remote-action descriptor contract, closed native builtin vocabulary, and real Elisp builtin constructors from current contract.json and Jetpacs platform source."
    (jetpacs-applet-mcp--schema nil))
   (jetpacs-applet-mcp--tool
    "jetpacs_list_api" "Discover the Jetpacs API"
    "List real, statically indexed Jetpacs/EBP or explicitly allowlisted package-source definitions by category and optional name query."
    (jetpacs-applet-mcp--schema
     `(("category" . ,(jetpacs-applet-mcp--property
                        "string" "Workspace category, references, or configured package-source namespace; defaults to all."
                        (jetpacs-applet-tooling-api-categories)))
       ("query" . ,(jetpacs-applet-mcp--property "string" "Optional literal name filter."))
       ("limit" . ,(jetpacs-applet-mcp--property "integer" "Maximum results, 1..200; defaults to 80."))
       ("includePrivate" . ,(jetpacs-applet-mcp--property "boolean" "Include names containing --.")))))
   (jetpacs-applet-mcp--tool
    "jetpacs_describe_symbol" "Describe a Jetpacs symbol"
    "Return static location, signature, docstring, and optionally exact source for an Elisp definition in the Jetpacs workspace or any explicitly allowlisted package source."
    (jetpacs-applet-mcp--schema
     `(("symbol" . ,(jetpacs-applet-mcp--property "string" "Exact Elisp symbol name."))
       ("includeSource" . ,(jetpacs-applet-mcp--property "boolean" "Append the exact defining form.")))
     '("symbol")))
   (jetpacs-applet-mcp--tool
    "jetpacs_describe_node" "Describe an EBP node"
    "Describe one node type from contract.json together with its real jetpacs-* builder signature and documentation."
    (jetpacs-applet-mcp--schema
     `(("nodeType" . ,(jetpacs-applet-mcp--property "string" "EBP node type, such as button or pane_scaffold.")))
     '("nodeType")))
   (jetpacs-applet-mcp--tool
    "jetpacs_summarize_elisp" "Summarize an Elisp file"
    "Read a workspace Elisp file or NAMESPACE/path.el from an explicitly allowlisted package source and return its token-efficient static API outline without loading it."
    (jetpacs-applet-mcp--schema
     `(("path" . ,(jetpacs-applet-mcp--property "string" "Workspace-relative .el path, or NAMESPACE/FILE.el from a configured read-only package source."))
       ("includePrivate" . ,(jetpacs-applet-mcp--property "boolean" "Include names containing --.")))
     '("path")))
   (jetpacs-applet-mcp--tool
    "jetpacs_inspect_applet" "Inspect an applet"
    "Read current source to inventory files, feature dependencies, owners, app identities, roots, registered handlers, emitted actions, native builtins, and remote provider reconciliation."
    (jetpacs-applet-mcp--schema
     `(("path" . ,(jetpacs-applet-mcp--property "string" "Workspace-relative applet .el file or directory.")))
     '("path")))
   (jetpacs-applet-mcp--tool
   "jetpacs_applet_manifest" "Generate canonical applet manifest"
    "Read applet source without evaluating it and return a deterministic semantic manifest with literal-byte hashes, canonical path/forms, definitions, signatures, docstrings, owners, apps, roots, registered and emitted remote actions, statically reachable native builtins, exact source lines and analysis inputs, generator identity, and a manifest digest. Oversized manifests return a lossless section index instead of truncated data. A trusted-runtime process may already have loaded its explicitly selected applet."
    (jetpacs-applet-mcp--schema
     `(("path" . ,(jetpacs-applet-mcp--property "string" "Workspace-relative applet .el file or directory."))
       ("section" . ,(jetpacs-applet-mcp--property
                       "string" "Optional manifest page or explicit index."
                       '("index" "files" "owner-registrations" "apps"
                         "roots" "actions" "action-emissions"
                         "builtin-emissions" "definitions")))
       ("offset" . ,(jetpacs-applet-mcp--property
                      "integer" "Zero-based section offset; defaults to 0."))
       ("limit" . ,(jetpacs-applet-mcp--property
                     "integer" "Requested records, 1..200; defaults to 25 and may be reduced to preserve complete entries.")))
     '("path")))
   (jetpacs-applet-mcp--tool
    "jetpacs_validate_applet" "Validate an applet"
    "Statically check applet file hygiene, ownership, action identifiers/docs and emitted-provider resolution, public and handler docstrings, lifecycle registrations, and dispatch-time prompting/blocking hazards."
    (jetpacs-applet-mcp--schema
     `(("path" . ,(jetpacs-applet-mcp--property "string" "Workspace-relative applet .el file or directory.")))
     '("path")))
   (jetpacs-applet-mcp--tool
    "jetpacs_applet_template" "Generate an applet scaffold"
    "Return a documented lifecycle-complete owner-scoped applet scaffold. The text is derived from literal Elisp forms and read back for exact equality; this tool never writes files."
    (jetpacs-applet-mcp--schema
     `(("id" . ,(jetpacs-applet-mcp--property "string" "Non-reserved EBP applet identifier."))
       ("label" . ,(jetpacs-applet-mcp--property "string" "Human-readable app label."))
       ("icon" . ,(jetpacs-applet-mcp--property "string" "Optional Material icon identifier.")))
     '("id" "label"))))
   (if (jetpacs-applet-tooling-runtime-available-p)
       (vector
        (jetpacs-applet-mcp--tool
         "jetpacs_runtime_inventory" "Inspect loaded Jetpacs runtime"
         "Return actual live root, app, action, owner, schema, and claim-site registry state from the explicitly loaded trusted applet process."
         (jetpacs-applet-mcp--schema nil))
        (jetpacs-applet-mcp--tool
         "jetpacs_check_render_determinism" "Check a live render"
         "Invoke a trusted applet surface builder twice and compare canonical EBP output; report context and output hashes, size, node/depth/ID analysis, actual non-sending Jetpacs gates, builder failures, and optionally the canonical payload."
         (jetpacs-applet-mcp--schema
          `(("surface" . ,(jetpacs-applet-mcp--property
                            "string" "Registered surface id or bare owner."))
            ("includeCanonical" . ,(jetpacs-applet-mcp--property
                                     "boolean" "Include canonical EBP JSON; may contain app payload.")))
          '("surface"))
         t))
     [])))

;;;; Tool argument validation and dispatch

(defun jetpacs-applet-mcp--required-string (object key)
  "Read required string KEY from hash-table OBJECT."
  (let ((value (gethash key object jetpacs-applet-mcp--missing)))
    (unless (and (stringp value) (not (string-empty-p value)))
      (error "Argument %s must be a non-empty string" key))
    value))

(defun jetpacs-applet-mcp--optional-string (object key)
  "Read optional string KEY from hash-table OBJECT."
  (let ((value (gethash key object jetpacs-applet-mcp--missing)))
    (cond ((eq value jetpacs-applet-mcp--missing) nil)
          ((stringp value) value)
          (t (error "Argument %s must be a string" key)))))

(defun jetpacs-applet-mcp--optional-bool (object key &optional default)
  "Read optional boolean KEY from OBJECT, returning DEFAULT when absent."
  (let ((value (gethash key object jetpacs-applet-mcp--missing)))
    (cond ((eq value jetpacs-applet-mcp--missing) default)
          ((eq value t) t)
          ((eq value :json-false) nil)
          (t (error "Argument %s must be a boolean" key)))))

(defun jetpacs-applet-mcp--optional-int (object key default)
  "Read optional integer KEY from OBJECT, returning DEFAULT when absent."
  (let ((value (gethash key object jetpacs-applet-mcp--missing)))
    (cond ((eq value jetpacs-applet-mcp--missing) default)
          ((integerp value) value)
          (t (error "Argument %s must be an integer" key)))))

(defun jetpacs-applet-mcp--guide ()
  "Return the bundled applet guide."
  (let ((path (jetpacs-applet-tooling-resolve-file "jetpacs-applet-mcp/APPLET-GUIDE.md")))
    (with-temp-buffer
      (insert-file-contents path)
      (buffer-string))))

(defun jetpacs-applet-mcp--determinism-guide ()
  "Return the bundled forms, determinism, and debugging contract."
  (let ((path (jetpacs-applet-tooling-resolve-file "jetpacs-applet-mcp/DETERMINISM.md")))
    (with-temp-buffer
      (insert-file-contents path)
      (buffer-string))))

(defun jetpacs-applet-mcp--package-skins-guide ()
  "Return the bundled existing-package skinning workflow."
  (let ((path (jetpacs-applet-tooling-resolve-file
               "jetpacs-applet-mcp/PACKAGE-SKINS.md")))
    (with-temp-buffer
      (insert-file-contents path)
      (buffer-string))))

(defun jetpacs-applet-mcp--implementation-guide ()
  "Return the root and applet agent contracts in application order."
  (mapconcat
   (lambda (entry)
     (let ((title (car entry))
           (path (jetpacs-applet-tooling-resolve-file (cdr entry))))
       (with-temp-buffer
         (insert (format "# %s\n\n" title))
         (insert-file-contents path)
         (buffer-string))))
   '(("Workspace instructions (`AGENTS.md`)" . "AGENTS.md")
     ("Applet-tooling instructions (`jetpacs-applet-mcp/AGENTS.md`)"
      . "jetpacs-applet-mcp/AGENTS.md"))
   "\n\n"))

(defun jetpacs-applet-mcp--known-tool-p (name)
  "Non-nil when NAME is one of this server's tools."
  (seq-some (lambda (tool) (equal name (gethash "name" tool)))
            (append (jetpacs-applet-mcp--tools) nil)))

(defun jetpacs-applet-mcp--call-tool (params)
  "Execute a tool call PARAMS and return an MCP tool result."
  (let* ((name (jetpacs-applet-mcp--required-string params "name"))
         (raw-arguments (gethash "arguments" params jetpacs-applet-mcp--missing))
         (arguments (cond ((eq raw-arguments jetpacs-applet-mcp--missing)
                           (make-hash-table :test #'equal))
                          ((hash-table-p raw-arguments) raw-arguments)
                          (t (error "Tool arguments must be an object")))))
    (unless (jetpacs-applet-mcp--known-tool-p name)
      (signal 'jetpacs-applet-mcp-unknown-tool (list name)))
    (condition-case err
        (jetpacs-applet-mcp--text-result
         (pcase name
           ("jetpacs_applet_guide" (jetpacs-applet-mcp--guide))
           ("jetpacs_describe_actions"
            (jetpacs-applet-tooling-action-vocabulary))
           ("jetpacs_list_api"
            (jetpacs-applet-tooling-list-api
             (or (jetpacs-applet-mcp--optional-string arguments "category") "all")
             (jetpacs-applet-mcp--optional-string arguments "query")
             (jetpacs-applet-mcp--optional-int arguments "limit" 80)
             (jetpacs-applet-mcp--optional-bool arguments "includePrivate")))
           ("jetpacs_describe_symbol"
            (jetpacs-applet-tooling-describe-symbol
             (jetpacs-applet-mcp--required-string arguments "symbol")
             (jetpacs-applet-mcp--optional-bool arguments "includeSource")))
           ("jetpacs_describe_node"
            (jetpacs-applet-tooling-describe-node
             (jetpacs-applet-mcp--required-string arguments "nodeType")))
           ("jetpacs_summarize_elisp"
            (jetpacs-applet-tooling-summarize-elisp-file
             (jetpacs-applet-mcp--required-string arguments "path")
             (jetpacs-applet-mcp--optional-bool arguments "includePrivate")))
           ("jetpacs_inspect_applet"
            (jetpacs-applet-tooling-inspect-applet
             (jetpacs-applet-mcp--required-string arguments "path")))
           ("jetpacs_applet_manifest"
            (jetpacs-applet-tooling-applet-manifest
             (jetpacs-applet-mcp--required-string arguments "path")
             (jetpacs-applet-mcp--optional-string arguments "section")
             (jetpacs-applet-mcp--optional-int arguments "offset" nil)
             (jetpacs-applet-mcp--optional-int arguments "limit" nil)))
           ("jetpacs_validate_applet"
            (jetpacs-applet-tooling-validate-applet
             (jetpacs-applet-mcp--required-string arguments "path")))
           ("jetpacs_applet_template"
            (jetpacs-applet-tooling-applet-template
             (jetpacs-applet-mcp--required-string arguments "id")
             (jetpacs-applet-mcp--required-string arguments "label")
             (jetpacs-applet-mcp--optional-string arguments "icon")))
           ("jetpacs_runtime_inventory"
            (jetpacs-applet-tooling-runtime-inventory))
           ("jetpacs_check_render_determinism"
            (jetpacs-applet-tooling-check-render-determinism
             (jetpacs-applet-mcp--required-string arguments "surface")
             (jetpacs-applet-mcp--optional-bool arguments "includeCanonical")))))
      (error
       (jetpacs-applet-mcp--text-result (error-message-string err) t)))))

(define-error 'jetpacs-applet-mcp-unknown-tool "Unknown Jetpacs Applet MCP tool")

;;;; Resources and repeatable workflow prompts

(defun jetpacs-applet-mcp--resource-list ()
  "Return the server's static resources result."
  (jetpacs-applet-mcp--object
   "resources"
   (vector
    (jetpacs-applet-mcp--object
     "uri" "jetpacs://applets/implementation-guide"
     "name" "applet-implementation-guide"
     "title" "Jetpacs applet implementation guide"
     "description" "Layered workspace and applet-tooling instructions for lower-model-safe maintenance and applet authoring."
     "mimeType" "text/markdown")
    (jetpacs-applet-mcp--object
     "uri" "jetpacs://applets/guide" "name" "applet-guide"
     "title" "Jetpacs applet guide"
     "description" "Lifecycle, ownership, rendering, action, and test rules."
     "mimeType" "text/markdown")
    (jetpacs-applet-mcp--object
     "uri" "jetpacs://applets/determinism" "name" "determinism-contract"
     "title" "Jetpacs forms, determinism, and debugging"
     "description" "The one-IR model, determinism envelope, real sender gates, manifest provenance, package-source references, and trusted-runtime boundary."
     "mimeType" "text/markdown")
    (jetpacs-applet-mcp--object
     "uri" "jetpacs://applets/package-skins" "name" "package-skin-guide"
     "title" "Skin an Emacs package with Jetpacs"
     "description" "Package/source boundaries, adapter architecture, workflow mapping, action reconciliation, extension decisions, and tests for arbitrary Emacs-package skins."
     "mimeType" "text/markdown")
    (jetpacs-applet-mcp--object
     "uri" "jetpacs://applets/api" "name" "applet-api"
     "title" "Jetpacs applet API index"
     "description" "Public app, chrome, surface, and widget definitions from current source."
     "mimeType" "text/plain")
    (jetpacs-applet-mcp--object
     "uri" "jetpacs://applets/example" "name" "example-applet"
     "title" "Minimal Jetpacs applet"
     "description" "A generated lifecycle-complete reference applet."
     "mimeType" "text/x-emacs-lisp"))))

(defun jetpacs-applet-mcp--read-resource (params)
  "Read the resource named by PARAMS."
  (let* ((uri (jetpacs-applet-mcp--required-string params "uri"))
         (mime "text/plain")
         (text
          (pcase uri
            ("jetpacs://applets/implementation-guide"
             (setq mime "text/markdown")
             (jetpacs-applet-mcp--implementation-guide))
            ("jetpacs://applets/guide"
             (setq mime "text/markdown")
             (jetpacs-applet-mcp--guide))
            ("jetpacs://applets/determinism"
             (setq mime "text/markdown")
             (jetpacs-applet-mcp--determinism-guide))
            ("jetpacs://applets/package-skins"
             (setq mime "text/markdown")
             (jetpacs-applet-mcp--package-skins-guide))
            ("jetpacs://applets/api"
             (concat (jetpacs-applet-tooling-list-api "apps" nil 100 nil)
                     "\n\n" (jetpacs-applet-tooling-list-api "chrome" nil 100 nil)
                     "\n\n" (jetpacs-applet-tooling-list-api "surfaces" nil 100 nil)
                     "\n\n" (jetpacs-applet-tooling-list-api "widgets" nil 200 nil)))
            ("jetpacs://applets/example"
             (setq mime "text/x-emacs-lisp")
             (jetpacs-applet-tooling-applet-template "hello" "Hello"))
            (_ (error "Unknown resource URI: %s" uri)))))
    (jetpacs-applet-mcp--object
     "contents" (vector (jetpacs-applet-mcp--object
                         "uri" uri "mimeType" mime
                         "text" (jetpacs-applet-tooling--cap text))))))

(defun jetpacs-applet-mcp--prompt (name title description arguments)
  "Build prompt NAME with TITLE, DESCRIPTION, and ARGUMENTS.
ARGUMENTS is a list of (NAME REQUIRED)."
  (jetpacs-applet-mcp--object
   "name" name "title" title "description" description
   "arguments" (vconcat
                (mapcar (lambda (arg)
                          (jetpacs-applet-mcp--object
                           "name" (car arg)
                           "required" (if (cadr arg) t :json-false)))
                        arguments))))

(defun jetpacs-applet-mcp--prompt-list ()
  "Build the applet workflow prompt list."
  (jetpacs-applet-mcp--object
   "prompts"
   (vector
    (jetpacs-applet-mcp--prompt
     "create_jetpacs_applet" "Create a Jetpacs applet"
     "Design an owner-scoped applet from a user workflow using current APIs."
     '(("id" t) ("goal" t)))
    (jetpacs-applet-mcp--prompt
     "skin_emacs_package" "Skin an Emacs package"
     "Design a Jetpacs native skin over an existing package's current source and programmatic API."
     '(("id" t) ("package" t) ("goal" t)))
    (jetpacs-applet-mcp--prompt
     "review_jetpacs_applet" "Review a Jetpacs applet"
     "Inspect and validate an existing applet before proposing focused changes."
     '(("path" t) ("goal" nil))))))

(defun jetpacs-applet-mcp--get-prompt (params)
  "Return the requested prompt from PARAMS."
  (let* ((name (jetpacs-applet-mcp--required-string params "name"))
         (raw (gethash "arguments" params jetpacs-applet-mcp--missing))
         (args (cond ((eq raw jetpacs-applet-mcp--missing)
                      (make-hash-table :test #'equal))
                     ((hash-table-p raw) raw)
                     (t (error "Prompt arguments must be an object"))))
         (text
          (pcase name
            ("create_jetpacs_applet"
             (let ((id (jetpacs-applet-mcp--required-string args "id"))
                   (goal (jetpacs-applet-mcp--required-string args "goal")))
               (format "Design a Jetpacs applet with id %S for this workflow:\n\n%s\n\nRead jetpacs://applets/implementation-guide, jetpacs://applets/guide, and jetpacs://applets/determinism first. Generate the reference scaffold, inspect the action model, then discover and describe only the Jetpacs and explicitly allowlisted package APIs needed for the workflow. Treat the package as the domain engine and the applet as its deterministic native skin. Keep one owner, pure screen builders, stable identities, documented named semantic actions, explicit accepted/stale/rejected results, and deferred presentation. Inspect action-provider reconciliation, generate the canonical manifest, validate the completed applet directory, and add offline ERT tests."
                       id goal)))
            ("skin_emacs_package"
             (let ((id (jetpacs-applet-mcp--required-string args "id"))
                   (package (jetpacs-applet-mcp--required-string args "package"))
                   (goal (jetpacs-applet-mcp--required-string args "goal")))
               (format "Design a Jetpacs applet skin with id %S over the Emacs package %S for this workflow:\n\n%s\n\nRead jetpacs://applets/implementation-guide, jetpacs://applets/guide, jetpacs://applets/package-skins, and jetpacs://applets/determinism first. Confirm the package's exact source is available through a read-only namespace. Inspect only the state readers, noninteractive operations, hooks, data structures, and customization variables required by the workflow. Keep the package as domain engine and isolate coupling in a documented adapter. Generate the scaffold, inspect the action model and needed nodes, implement one canonical presenter per domain destination, reconcile every emitted action provider, generate the canonical manifest, validate, and add package-boundary ERT before trusted runtime or device testing. If the workflow needs a native concept absent from EBP, identify that protocol/platform gap explicitly instead of simulating it with package-specific verbs."
                       id package goal)))
            ("review_jetpacs_applet"
             (let ((path (jetpacs-applet-mcp--required-string args "path"))
                   (goal (jetpacs-applet-mcp--optional-string args "goal")))
               (format "Review the Jetpacs applet at %s%s. First inspect it, including emitted-action/provider reconciliation, generate its canonical manifest, and validate it. Then check provenance/docstrings, package API boundaries, owner scope, app/root lifecycle symmetry, builder purity and deterministic inputs, stable identities, action status and durability, D2 prompt deferral, advertised capability gates, EBP limits, and offline ERT coverage. For trusted code, compare static providers with live runtime inventory and compare two canonical renders. Separate correctness defects from optional design improvements."
                       path (if goal (format " against this goal: %s" goal) ""))))
            (_ (signal 'jetpacs-applet-mcp-unknown-prompt (list name))))))
    (jetpacs-applet-mcp--object
     "description" "Jetpacs applet development workflow"
     "messages" (vector
                 (jetpacs-applet-mcp--object
                  "role" "user"
                  "content" (jetpacs-applet-mcp--object "type" "text" "text" text))))))

(define-error 'jetpacs-applet-mcp-unknown-prompt "Unknown Jetpacs Applet MCP prompt")
(define-error 'jetpacs-applet-mcp-invalid-params "Invalid MCP parameters")

;;;; MCP and JSON-RPC lifecycle

(defun jetpacs-applet-mcp--initialize (params)
  "Negotiate initialization PARAMS and return server capabilities."
  (let* ((requested (gethash "protocolVersion" params
                             jetpacs-applet-mcp--missing))
         (capabilities (gethash "capabilities" params
                                jetpacs-applet-mcp--missing))
         (client-info (gethash "clientInfo" params
                               jetpacs-applet-mcp--missing)))
    (unless (and (stringp requested) (not (string-empty-p requested)))
      (signal 'jetpacs-applet-mcp-invalid-params
              '("Initialize protocolVersion must be a non-empty string")))
    (unless (hash-table-p capabilities)
      (signal 'jetpacs-applet-mcp-invalid-params
              '("Initialize capabilities must be an object")))
    (unless (hash-table-p client-info)
      (signal 'jetpacs-applet-mcp-invalid-params
              '("Initialize clientInfo must be an object")))
    (condition-case err
        (progn
          (jetpacs-applet-mcp--required-string client-info "name")
          (jetpacs-applet-mcp--required-string client-info "version"))
      (error
       (signal 'jetpacs-applet-mcp-invalid-params
               (list (error-message-string err)))))
    (setq jetpacs-applet-mcp--protocol-version
          (if (member requested jetpacs-applet-mcp-supported-protocol-versions)
              requested
            jetpacs-applet-mcp-latest-protocol-version)
          jetpacs-applet-mcp--initialized nil
          jetpacs-applet-mcp--initialize-seen t)
    (jetpacs-applet-mcp--object
     "protocolVersion" jetpacs-applet-mcp--protocol-version
     "capabilities" (jetpacs-applet-mcp--object
                     "tools" (jetpacs-applet-mcp--object "listChanged" :json-false)
                     "resources" (jetpacs-applet-mcp--object
                                  "subscribe" :json-false
                                  "listChanged" :json-false)
                     "prompts" (jetpacs-applet-mcp--object "listChanged" :json-false))
     "serverInfo" (jetpacs-applet-mcp--object
                   "name" "jetpacs-applet-mcp"
                   "title" "Jetpacs Applet MCP"
                   "version" jetpacs-applet-mcp-version)
     "instructions"
     (if (jetpacs-applet-tooling-runtime-available-p)
         (concat
          "Trusted-runtime Jetpacs applet workbench. The applet named by "
          "JETPACS_TRUSTED_APPLET has been loaded and may execute through the "
          "render checker. Read the implementation, applet, and determinism "
          "resources first.")
       (concat
        "Static read-only Jetpacs applet workbench. Read "
        "jetpacs://applets/implementation-guide, jetpacs://applets/guide, and "
        "jetpacs://applets/determinism; then inspect current APIs, generate a "
        "scaffold, manifest and validate the result. This mode never evaluates "
        "or writes applet code.")))))

(defun jetpacs-applet-mcp--dispatch-initialized (id method params)
  "Dispatch initialized request METHOD/PARAMS for ID."
  (condition-case err
      (pcase method
        ("tools/list"
         (jetpacs-applet-mcp--success id
                               (jetpacs-applet-mcp--object "tools" (jetpacs-applet-mcp--tools))))
        ("tools/call"
         (jetpacs-applet-mcp--success id (jetpacs-applet-mcp--call-tool params)))
        ("resources/list"
         (jetpacs-applet-mcp--success id (jetpacs-applet-mcp--resource-list)))
        ("resources/read"
         (jetpacs-applet-mcp--success id (jetpacs-applet-mcp--read-resource params)))
        ("prompts/list"
         (jetpacs-applet-mcp--success id (jetpacs-applet-mcp--prompt-list)))
        ("prompts/get"
         (jetpacs-applet-mcp--success id (jetpacs-applet-mcp--get-prompt params)))
        (_ (jetpacs-applet-mcp--error id -32601
                               (format "Method not found: %s" method))))
    (jetpacs-applet-mcp-unknown-tool
     (jetpacs-applet-mcp--error id -32602
                         (format "Unknown tool: %s" (car (cdr err)))))
    (jetpacs-applet-mcp-unknown-prompt
     (jetpacs-applet-mcp--error id -32602
                         (format "Unknown prompt: %s" (car (cdr err)))))
    (error
     (jetpacs-applet-mcp--error id -32602 (error-message-string err)))))

(defun jetpacs-applet-mcp--dispatch (request)
  "Dispatch parsed JSON-RPC REQUEST and return a response object or nil."
  (if (not (hash-table-p request))
      (jetpacs-applet-mcp--error :json-null -32600
                          "Request must be a JSON object")
    (let* ((id (gethash "id" request jetpacs-applet-mcp--missing))
           (has-id (not (eq id jetpacs-applet-mcp--missing)))
           (valid-id (or (not has-id) (stringp id) (numberp id)))
           (method (gethash "method" request jetpacs-applet-mcp--missing))
           (version (gethash "jsonrpc" request jetpacs-applet-mcp--missing))
           (raw-params (gethash "params" request jetpacs-applet-mcp--missing)))
      (cond
       ((not (and valid-id (equal version "2.0") (stringp method)))
        (jetpacs-applet-mcp--error (if (and has-id valid-id) id :json-null)
                            -32600 "Invalid JSON-RPC request"))
       ((not (or (eq raw-params jetpacs-applet-mcp--missing)
                 (hash-table-p raw-params)))
        (and has-id (jetpacs-applet-mcp--error id -32602
                                        "Params must be an object")))
       (t
        (let ((params (if (eq raw-params jetpacs-applet-mcp--missing)
                          (make-hash-table :test #'equal)
                        raw-params)))
          (if (not has-id)
              (progn
                (pcase method
                  ("notifications/initialized"
                   (when jetpacs-applet-mcp--initialize-seen
                     (setq jetpacs-applet-mcp--initialized t)))
                  ((or "notifications/cancelled"
                       "notifications/roots/list_changed") nil)
                  (_ nil))
                nil)
            (condition-case err
                (pcase method
                  ("initialize"
                   (jetpacs-applet-mcp--success id
                                         (jetpacs-applet-mcp--initialize params)))
                  ("ping" (jetpacs-applet-mcp--success id
                                                (jetpacs-applet-mcp--object)))
                  (_ (if jetpacs-applet-mcp--initialized
                         (jetpacs-applet-mcp--dispatch-initialized id method params)
                       (jetpacs-applet-mcp--error
                        id -32002
                        "Server has not received notifications/initialized"))))
              (jetpacs-applet-mcp-invalid-params
               (jetpacs-applet-mcp--error id -32602 (error-message-string err)))
              (error
               (jetpacs-applet-mcp--error
                id -32603
                (format "Internal error: %s"
                        (error-message-string err))))))))))))

(defun jetpacs-applet-mcp-handle-line (json-string)
  "Handle one MCP JSON-STRING and return a serialized response or nil."
  (if (> (string-bytes json-string) jetpacs-applet-mcp-max-message-bytes)
      (jetpacs-applet-mcp--serialize
       (jetpacs-applet-mcp--error :json-null -32600 "MCP message is too large"))
    (condition-case nil
        (when-let* ((response
                     (jetpacs-applet-mcp--dispatch
                      (json-parse-string
                       json-string :object-type 'hash-table :array-type 'array
                       :null-object :json-null :false-object :json-false))))
          (jetpacs-applet-mcp--serialize response))
      (json-error
       (jetpacs-applet-mcp--serialize
        (jetpacs-applet-mcp--error :json-null -32700 "Parse error")))
      (error
       (jetpacs-applet-mcp--serialize
        (jetpacs-applet-mcp--error :json-null -32603 "Internal error"))))))

;;;; Stdio entry point and prototype compatibility

(defun jetpacs-applet-mcp-start-server ()
  "Run the newline-delimited MCP stdio loop until standard input closes."
  (setq jetpacs-applet-mcp--initialized nil
        jetpacs-applet-mcp--initialize-seen nil)
  (message "jetpacs-applet-mcp %s: serving %s over MCP stdio"
           jetpacs-applet-mcp-version (jetpacs-applet-tooling-workspace-root))
  (let (done)
    (while (not done)
      (let ((line (condition-case nil
                      (read-from-minibuffer "")
                    (end-of-file (setq done t) nil))))
        (when (and line (not (string-empty-p (string-trim line))))
          (when-let* ((response (jetpacs-applet-mcp-handle-line line)))
            (princ response)
            (princ "\n")))))))

;; Compatibility for the names used by the original prototype.
(defalias 'mcp-start-server #'jetpacs-applet-mcp-start-server)
(defun mcp--handle-request (json-string)
  "Handle JSON-STRING and print a response, when one is required."
  (when-let* ((response (jetpacs-applet-mcp-handle-line json-string)))
    (princ response)
    (princ "\n")))

(provide 'jetpacs-applet-mcp)

(when (and noninteractive
           (not jetpacs-applet-mcp-suppress-auto-start)
           (not (bound-and-true-p byte-compile-current-file)))
  (jetpacs-applet-mcp-start-server))

;;; jetpacs-applet-mcp.el ends here
