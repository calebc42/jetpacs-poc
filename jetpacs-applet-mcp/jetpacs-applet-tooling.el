;;; jetpacs-applet-tooling.el --- Jetpacs applet source analysis -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later
;; Package-Requires: ((emacs "30.1"))

;;; Commentary:

;; The source-analysis engine behind the Jetpacs applet-authoring tools.  It
;; uses the native Elisp reader to inspect workspace and explicitly allowlisted
;; package source without loading or evaluating it.  `jetpacs-applet-mcp.el'
;; exposes these operations over MCP; `jetpacs-applet-mcp-runtime.el'
;; explicitly adds trusted applet execution.
;;
;; Security is part of the API: every file stays under one canonical workspace
;; root, reads are size-bounded, and there is deliberately no eval or
;; macroexpand endpoint.

;;; Code:

(require 'cl-lib)
(require 'pp)
(require 'seq)
(require 'subr-x)

;;;; Runtime seams kept optional in static mode

(declare-function byte-run-strip-symbol-positions "byte-run" (value))

;; Optional runtime seams.  They are intentionally not required here: the
;; default tooling library reads applets as data and never loads Jetpacs or an
;; applet.
(declare-function jetpacs-action-schema "jetpacs-surfaces" (name))
(declare-function jetpacs-client "jetpacs-surfaces" ())
(declare-function jetpacs-connected-p "jetpacs-surfaces" ())
(declare-function jetpacs-devtools-capture-spec "jetpacs-devtools" (surface))
(declare-function jetpacs-node->canonical-json "jetpacs-widgets" (value))
(declare-function jetpacs-node-wire-bytes "jetpacs-widgets" (value))
(declare-function jetpacs-shell--analyze-spec "jetpacs-shell" (spec))
(declare-function jetpacs-shell--gate-amendments "jetpacs-shell"
                  (client spec &optional analysis))
(declare-function jetpacs-shell--gate-capability "jetpacs-shell"
                  (client surface))
(declare-function jetpacs-shell--gate-ids "jetpacs-shell"
                  (spec stale-spec &optional spec-analysis stale-analysis))
(declare-function jetpacs-shell--gate-size "jetpacs-shell"
                  (client spec stale-spec &optional spec-analysis
                          stale-analysis))
(declare-function jetpacs-shell--gate-spec "jetpacs-shell"
                  (client surface spec stale-spec &optional spec-analysis
                          stale-analysis))
(declare-function jetpacs-shell--gate-variants "jetpacs-shell"
                  (spec &optional analysis))
(declare-function jetpacs-shell-roots "jetpacs-shell" ())
(declare-function jetpacs-window "jetpacs-surfaces" ())
(declare-function ebp-client-granted "ebp" (client))
(declare-function ebp-client-limits "ebp" (client))

(defvar jetpacs--claim-sites)
(defvar jetpacs--registrations)
(defvar jetpacs-action-handlers)
(defvar jetpacs-apps--registry)
(defvar jetpacs-shell-builder-error-functions)
(defvar jetpacs-shell--frame-headroom)
(defvar jetpacs-shell--max-children)
(defvar jetpacs-shell--max-node-depth)
(defvar jetpacs-shell--max-nodes)
(defvar jetpacs-shell--max-variants-per-host)

;;;; Configuration and contract vocabulary

(defconst jetpacs-applet-tooling-version "0.5.0"
  "Semantic version of the Elisp-native Jetpacs applet tooling.")

(defconst jetpacs-applet-tooling-manifest-schema "jetpacs.applet-manifest/2"
  "Schema identifier emitted by `jetpacs-applet-tooling-applet-manifest'.")

(defconst jetpacs-applet-tooling--manifest-section-keys
  '(("files" . :files)
    ("owner-registrations" . :owner-registrations)
    ("apps" . :apps)
    ("roots" . :roots)
    ("actions" . :actions)
    ("action-emissions" . :action-emissions)
    ("builtin-emissions" . :builtin-emissions)
    ("definitions" . :definitions))
  "Public page name to manifest vector key mappings, in canonical order.")

(defgroup jetpacs-applet-tooling nil
  "Read-only source intelligence for Jetpacs development."
  :group 'tools)

(defcustom jetpacs-applet-tooling-workspace-root nil
  "Canonical Jetpacs workspace root, or nil to derive it from this library.
The `JETPACS_WORKSPACE' environment variable takes precedence over derivation."
  :type '(choice (const :tag "Derive" nil) directory)
  :group 'jetpacs-applet-tooling)

(defconst jetpacs-applet-tooling-app-source-names
  '("ebp" "ebp.el" "ebp-kmp" "ebp-compose" "ebp-org" "glasspane"
    "jetpacs-authoring")
  "Allowlisted sibling checkout namespaces used by app source discovery.
The list is deliberately closed: a directory appearing beside the workspace
does not become readable merely because it happens to contain Elisp.")

(defcustom jetpacs-applet-tooling-repositories-root nil
  "Root containing the named Jetpacs and EBP checkout directories.
`JETPACS_REPOSITORIES_ROOT' takes precedence.  When unset, tooling checks the
workspace's immediate parent and grandparent for the exact allowlisted
checkout names.  It never scans a general workspace directory."
  :type '(choice (const :tag "Discover" nil) directory)
  :group 'jetpacs-applet-tooling)

(defcustom jetpacs-applet-tooling-app-source-roots nil
  "Optional explicit app source namespace mapping.
Each element is (NAMESPACE . DIRECTORY), and NAMESPACE must be one of
`jetpacs-applet-tooling-app-source-names'.  Normally the directory is resolved
as NAMESPACE below `JETPACS_REPOSITORIES_ROOT'.  This option exists for tests
and deliberately keeps checkout sources separate from package reference roots."
  :type '(alist :key-type (string :tag "Checkout namespace")
                :value-type (directory :tag "Checkout directory"))
  :group 'jetpacs-applet-tooling)

(defcustom jetpacs-applet-tooling-max-file-bytes (* 2 1024 1024)
  "Largest source file the applet tooling will inspect."
  :type 'natnum
  :group 'jetpacs-applet-tooling)

(defcustom jetpacs-applet-tooling-max-result-chars 60000
  "Maximum characters returned by one applet-tooling operation."
  :type 'natnum
  :group 'jetpacs-applet-tooling)

(defcustom jetpacs-applet-tooling-source-directories
  '("emacs" "jetpacs-applet-mcp"
    "jetpacs-components/lisp" "jetpacs-automations/lisp"
    "jetpacs-component-catalog/lisp")
  "Workspace-relative directories included in the static Elisp index.
Allowlisted sibling app source checkouts are added separately by
`jetpacs-applet-tooling--source-files'."
  :type '(repeat directory)
  :group 'jetpacs-applet-tooling)

(defcustom jetpacs-applet-tooling-platform-source-directory nil
  "Workspace-relative Jetpacs platform Elisp directory, or nil to discover it.
This root supplies platform action providers and builtin constructors for
source reconciliation.  The canonical platform source is `emacs/'.
Applet/example directories are deliberately not provider roots."
  :type '(choice (const :tag "Discover" nil) directory)
  :group 'jetpacs-applet-tooling)

(defcustom jetpacs-applet-tooling-org-source-root nil
  "Legacy read-only Org root, or nil to use JETPACS_ORG_SOURCE/discovery.
Discovery recognizes the external Emacs checkout at
`../emacs/emacs/lisp/org' relative to the flattened Jetpacs workspace (or
`emacs/emacs/lisp/org' below the repositories root).  This is a
separate, non-overlapping allowlisted root; it never broadens applet access.
New integrations should use `jetpacs-applet-tooling-reference-roots'; this
option remains the convenient compatibility spelling for the `org' root."
  :type '(choice (const :tag "Environment/discover" nil) directory)
  :group 'jetpacs-applet-tooling)

(defcustom jetpacs-applet-tooling-reference-roots nil
  "Explicit read-only package source roots keyed by namespace.
Each element is (NAMESPACE . DIRECTORY).  A source below DIRECTORY is exposed
as NAMESPACE/path.el and can be summarized or indexed without joining the
workspace or trusted-runtime load path.  Namespaces and roots must be unique,
must not overlap one another, and roots must not overlap the workspace.

`JETPACS_REFERENCE_ROOTS' may provide the same mapping as a JSON object, for
example {\"magit\":\"/src/magit/lisp\",\"denote\":\"/src/denote\"}.  Its
entries take precedence over this option.  The legacy Org option/environment
and repository discovery add `org' only when no generic mapping supplied it."
  :type '(alist :key-type (string :tag "Namespace")
                :value-type (directory :tag "Source directory"))
  :group 'jetpacs-applet-tooling)

(defcustom jetpacs-applet-tooling-max-reference-roots 32
  "Maximum separately allowlisted package source namespaces."
  :type 'natnum
  :group 'jetpacs-applet-tooling)

(defconst jetpacs-applet-tooling--definition-heads
  '(defun cl-defun defmacro cl-defmacro defsubst
    defvar defconst defcustom cl-defstruct define-error)
  "Top-level definition forms indexed by the applet tooling.")

(defconst jetpacs-applet-tooling--identifier-re
  (rx bos (any "A-Za-z0-9") (** 0 127 (any "A-Za-z0-9._:/-")) eos)
  "EBP identifier grammar used by static applet checks.")

(defconst jetpacs-applet-tooling--prompting-functions
  '(read-from-minibuffer read-string read-passwd completing-read
    completing-read-multiple yes-or-no-p y-or-n-p read-file-name
    read-directory-name call-interactively)
  "Calls which cannot run directly inside a device action dispatch.")

(defconst jetpacs-applet-tooling--blocking-functions
  '(url-retrieve-synchronously package-refresh-contents shell-command
    shell-command-to-string call-process call-process-region process-file)
  "Potentially expensive calls worth reviewing in an action handler.")

(defvar jetpacs-applet-tooling--index nil
  "Cached list of definition plists.")

(defvar jetpacs-applet-tooling--index-stamp nil
  "File/mtime/size stamp corresponding to `jetpacs-applet-tooling--index'.")

(defvar jetpacs-applet-tooling--reference-index-cache
  (make-hash-table :test #'equal)
  "Namespace to stamp/index records for explicitly allowed reference roots.")

(defvar jetpacs-applet-tooling--platform-scans nil
  "Cached native-reader scans of the Jetpacs platform source root.")

(defvar jetpacs-applet-tooling--platform-scans-stamp nil
  "File/mtime/size stamp corresponding to platform action scans.")

(defvar jetpacs-applet-tooling--builtin-constructors-cache nil
  "Cached direct and transitive platform builtin constructor records.")

(defvar jetpacs-applet-tooling--builtin-constructors-stamp nil
  "Platform stamp corresponding to the builtin constructor cache.")

;;;; Canonical source boundaries

(defun jetpacs-applet-tooling-workspace-root ()
  "Return the canonical workspace root with a trailing slash."
  (let* ((configured (or jetpacs-applet-tooling-workspace-root
                         (getenv "JETPACS_WORKSPACE")))
         (library (or load-file-name
                      (bound-and-true-p byte-compile-current-file)
                      (locate-library "jetpacs-applet-tooling")))
         (derived (and library
                       (expand-file-name ".." (file-name-directory library))))
         (root (or configured derived default-directory)))
    (unless (file-directory-p root)
      (error "Jetpacs Applet Tooling: workspace root is not a directory: %s" root))
    (file-name-as-directory (file-truename root))))

(defun jetpacs-applet-tooling--repository-layout-p (root)
  "Return non-nil when ROOT contains the exact app checkout allowlist.
This predicate only examines the ten named child directories; it never walks
ROOT or treats an unrelated sibling as a source namespace."
  (and (file-directory-p root)
       (seq-every-p (lambda (name)
                      (file-directory-p (expand-file-name name root)))
                    jetpacs-applet-tooling-app-source-names)))

(defun jetpacs-applet-tooling-repositories-root ()
  "Return the canonical root containing the named app source checkouts.
An explicit `JETPACS_REPOSITORIES_ROOT' or customization wins.  Without one,
the immediate parent and then grandparent of the workspace are considered in
that order, followed by the workspace itself for a temporary/legacy layout.
Only a root containing the exact allowlist is selected; no broad directory
search is performed."
  (let* ((workspace (jetpacs-applet-tooling-workspace-root))
         (configured (or (getenv "JETPACS_REPOSITORIES_ROOT")
                         jetpacs-applet-tooling-repositories-root))
         (candidates (list (file-name-as-directory
                            (expand-file-name ".." workspace))
                           (file-name-as-directory
                            (expand-file-name "../.." workspace))
                           workspace))
         (root (or configured
                   (seq-find #'jetpacs-applet-tooling--repository-layout-p
                             candidates)
                   ;; Keep the derived parent deterministic even before a
                   ;; move is complete; its missing checkouts simply produce
                   ;; no app-source records.
                   (car candidates))))
    (unless (file-directory-p root)
      (error "Jetpacs Applet Tooling: repositories root is not a directory: %s"
             root))
    (file-name-as-directory (file-truename root))))

(defun jetpacs-applet-tooling--app-source-roots-raw ()
  "Return configured or conventionally named app source directories.
The result is unvalidated and may omit checkouts that are not present yet."
  (let ((repository-root (jetpacs-applet-tooling-repositories-root)))
    (if jetpacs-applet-tooling-app-source-roots
        jetpacs-applet-tooling-app-source-roots
      (mapcar
       (lambda (name)
         (cons name (expand-file-name name repository-root)))
       jetpacs-applet-tooling-app-source-names))))

(defun jetpacs-applet-tooling-resolved-app-source-roots ()
  "Return existing canonical (NAMESPACE . DIRECTORY) app checkout roots.
Only the ten names in `jetpacs-applet-tooling-app-source-names' are accepted.
Each checkout must remain canonically contained by the repositories root;
symlinked checkouts that escape that root are rejected."
  (let ((repository-root (jetpacs-applet-tooling-repositories-root))
        seen seen-roots result)
    (dolist (entry (jetpacs-applet-tooling--app-source-roots-raw))
      (let ((namespace (car-safe entry))
            (directory (cdr-safe entry)))
        (unless (member namespace jetpacs-applet-tooling-app-source-names)
          (error "Jetpacs Applet Tooling: unknown app source namespace %S"
                 namespace))
        (when (member namespace seen)
          (error "Jetpacs Applet Tooling: duplicate app source namespace %S"
                 namespace))
        (push namespace seen)
        (when (file-directory-p directory)
          (let ((root (file-name-as-directory (file-truename directory))))
            (unless (or (file-in-directory-p root repository-root)
                        ;; Compatibility for the pre-flattened checkout only;
                        ;; an explicit repositories root never permits this.
                        (and (not (getenv "JETPACS_REPOSITORIES_ROOT"))
                             (null jetpacs-applet-tooling-repositories-root)
                             (not (jetpacs-applet-tooling--repository-layout-p
                                   repository-root))
                             (file-in-directory-p
                              root (jetpacs-applet-tooling-workspace-root))))
              (error (concat
                      "Jetpacs Applet Tooling: app source checkout %S escapes "
                      "JETPACS_REPOSITORIES_ROOT: %s")
                     namespace directory))
            (let ((legacy-layout
                   (and (not (getenv "JETPACS_REPOSITORIES_ROOT"))
                        (null jetpacs-applet-tooling-repositories-root)
                        (not (jetpacs-applet-tooling--repository-layout-p
                              repository-root)))))
              (when (and (not legacy-layout)
                         (or (file-in-directory-p root
                                                  (jetpacs-applet-tooling-workspace-root))
                             (file-in-directory-p
                              (jetpacs-applet-tooling-workspace-root) root)))
                (error (concat
                        "Jetpacs Applet Tooling: app source checkout %S must "
                        "not overlap the Jetpacs workspace")
                       namespace))
              (dolist (prior-root seen-roots)
                (when (or (equal root prior-root)
                          (file-in-directory-p root prior-root)
                          (file-in-directory-p prior-root root))
                  (error (concat
                          "Jetpacs Applet Tooling: app source checkouts %S "
                          "and another namespace overlap")
                         namespace))))
            (push root seen-roots)
            (push (cons namespace root) result)))))
    (sort result (lambda (left right) (string< (car left) (car right))))))

(defun jetpacs-applet-tooling-app-source-namespaces ()
  "Return existing app checkout namespaces in deterministic order."
  (mapcar #'car (jetpacs-applet-tooling-resolved-app-source-roots)))

(defun jetpacs-applet-tooling--app-source-for-path (path)
  "Return the app checkout entry containing canonical PATH, or nil."
  (let ((true (file-truename path)))
    (seq-find (lambda (entry)
                (file-in-directory-p true (cdr entry)))
              (jetpacs-applet-tooling-resolved-app-source-roots))))

(defun jetpacs-applet-tooling-trusted-source-root (path)
  "Return the trusted checkout root containing canonical PATH, or nil.
Read-only reference roots intentionally return nil.  The trusted launcher uses
this predicate after resolving `JETPACS_TRUSTED_APPLET' so an app source is
confined to its allowlisted checkout and an external package can never become
executable by naming its namespace."
  (let ((true (file-truename path))
        (workspace (jetpacs-applet-tooling-workspace-root)))
    (or (cdr (jetpacs-applet-tooling--app-source-for-path true))
        (and (file-in-directory-p true workspace) workspace))))

(defun jetpacs-applet-tooling-path-has-parent-segment-p (path)
  "Return non-nil when relative PATH contains a `..' component.
Trusted launcher inputs reject these components before canonicalization so a
caller cannot turn a syntactically escaping path into an allowed alias."
  (and (stringp path)
       (not (file-name-absolute-p path))
       (member ".." (split-string path "/" t))))

(defun jetpacs-applet-tooling--app-source-entry (path)
  "Return (ENTRY . RELATIVE) when PATH uses an app checkout namespace."
  (when (and (stringp path) (not (file-name-absolute-p path)))
    (let* ((slash (string-match "/" path))
           (namespace (if slash (substring path 0 slash) path))
           (entry (assoc namespace
                         (jetpacs-applet-tooling-resolved-app-source-roots))))
      (when entry
        (cons entry (if slash (substring path (1+ slash)) ""))))))

(defun jetpacs-applet-tooling--environment-reference-roots ()
  "Return reference mappings declared by JETPACS_REFERENCE_ROOTS.
The environment value is a JSON object whose keys are source namespaces and
whose values are directories."
  (when-let* ((raw (getenv "JETPACS_REFERENCE_ROOTS"))
              ((not (string-empty-p raw))))
    (require 'json)
    (let ((decoded
           (condition-case err
               (json-parse-string raw :object-type 'alist :array-type 'list
                                  :null-object nil :false-object nil)
             (error
              (error "Jetpacs Applet Tooling: invalid JETPACS_REFERENCE_ROOTS JSON: %s"
                     (error-message-string err))))))
      (unless (listp decoded)
        (error "Jetpacs Applet Tooling: JETPACS_REFERENCE_ROOTS must be a JSON object"))
      (mapcar
       (lambda (entry)
         (let ((namespace (cond
                           ((stringp (car entry)) (car entry))
                           ((symbolp (car entry)) (symbol-name (car entry))))))
           (unless (and namespace (stringp (cdr entry)))
           (error (concat
                   "Jetpacs Applet Tooling: every JETPACS_REFERENCE_ROOTS "
                   "entry must map a string namespace to a string directory")))
           (cons namespace (cdr entry))))
       decoded))))

(defun jetpacs-applet-tooling--legacy-org-candidate ()
  "Return the configured or discovered Org source directory, or nil."
  (let* ((configured (or (getenv "JETPACS_ORG_SOURCE")
                         jetpacs-applet-tooling-org-source-root))
         (workspace (jetpacs-applet-tooling-workspace-root))
         (repositories (jetpacs-applet-tooling-repositories-root))
         (candidates
          (delete-dups
           (list (expand-file-name "emacs/emacs/lisp/org" repositories)
                 (expand-file-name "../emacs/emacs/lisp/org" workspace)))))
    (or configured (seq-find #'file-directory-p candidates))))

(defun jetpacs-applet-tooling-resolved-reference-roots ()
  "Return validated (NAMESPACE . CANONICAL-DIRECTORY) reference roots.
This function derives the mapping from current configuration every time.  It
does not add a reference root to `load-path' and does not authorize execution."
  (let ((candidates
         (append (jetpacs-applet-tooling--environment-reference-roots)
                 jetpacs-applet-tooling-reference-roots))
        (workspace (jetpacs-applet-tooling-workspace-root))
        seen-names resolved)
    (when-let* ((org (jetpacs-applet-tooling--legacy-org-candidate)))
      (setq candidates (append candidates (list (cons "org" org)))))
    (dolist (entry candidates)
      (let ((namespace (car-safe entry))
            (directory (cdr-safe entry)))
        ;; Earlier entries deliberately win, giving the environment priority
        ;; and letting a generic `org' mapping override the legacy fallback.
        (unless (member namespace seen-names)
          (unless (and (stringp namespace)
                       (<= 1 (string-bytes namespace) 64)
                       (string-match-p
                        (rx bos alnum (* (any "A-Za-z0-9._-")) eos)
                        namespace))
            (error "Jetpacs Applet Tooling: invalid reference namespace %S"
                   namespace))
          (when (>= (length resolved)
                    jetpacs-applet-tooling-max-reference-roots)
            (error "Jetpacs Applet Tooling: reference root limit is %d"
                   jetpacs-applet-tooling-max-reference-roots))
          (unless (and (stringp directory) (file-directory-p directory))
            (error "Jetpacs Applet Tooling: reference root %S is not a directory: %S"
                   namespace directory))
          (let ((root (file-name-as-directory (file-truename directory))))
            (when (or (equal root workspace)
                      (file-in-directory-p root workspace)
                      (file-in-directory-p workspace root)
                      (seq-some (lambda (app-entry)
                                  (let ((app-root (cdr app-entry)))
                                    (or (equal root app-root)
                                        (file-in-directory-p root app-root)
                                        (file-in-directory-p app-root root))))
                                (jetpacs-applet-tooling-resolved-app-source-roots)))
              (error (concat
                      "Jetpacs Applet Tooling: reference root %S must not "
                      "overlap the Jetpacs workspace: %s")
                     namespace directory))
            (dolist (prior resolved)
              (let ((prior-root (cdr prior)))
                (when (or (equal root prior-root)
                          (file-in-directory-p root prior-root)
                          (file-in-directory-p prior-root root))
                  (error (concat
                          "Jetpacs Applet Tooling: reference roots %S and %S "
                          "must not overlap")
                         namespace (car prior)))))
            (push namespace seen-names)
            (push (cons namespace root) resolved)))))
    (sort resolved (lambda (left right) (string< (car left) (car right))))))

(defun jetpacs-applet-tooling-reference-namespaces ()
  "Return configured read-only package source namespaces in sorted order."
  (mapcar #'car (jetpacs-applet-tooling-resolved-reference-roots)))

(defun jetpacs-applet-tooling-org-source-root ()
  "Return the canonical read-only `org' reference root, or nil."
  (cdr (assoc "org" (jetpacs-applet-tooling-resolved-reference-roots))))

(defun jetpacs-applet-tooling-reference-path-p (path)
  "Return non-nil when PATH addresses a configured reference namespace."
  (and (stringp path)
       (string-match (rx bos (group (+ (not (any "/")))) "/") path)
       (assoc (match-string 1 path)
              (jetpacs-applet-tooling-resolved-reference-roots))))

(defun jetpacs-applet-tooling--relative (path)
  "Return canonical PATH in the workspace or a reference namespace."
  (let* ((true (file-truename path))
         (workspace (jetpacs-applet-tooling-workspace-root))
         (app-source
          (jetpacs-applet-tooling--app-source-for-path true))
         (reference
          (seq-find (lambda (entry) (file-in-directory-p true (cdr entry)))
                    (jetpacs-applet-tooling-resolved-reference-roots))))
    (cond
     (app-source
      (concat (car app-source) "/"
              (file-relative-name true (cdr app-source))))
     ((file-in-directory-p true workspace)
      (file-relative-name true workspace))
     (reference
      (concat (car reference) "/"
              (file-relative-name true (cdr reference))))
     (t (error "Jetpacs Applet Tooling: source path is outside every allowed root: %s"
               path)))))

(defun jetpacs-applet-tooling-resolve-file (path)
  "Resolve PATH inside an allowed source root and enforce the read limit.
Paths beginning with an allowlisted app checkout or read-only reference
NAMESPACE resolve only under that named root; all other relative paths resolve
under the workspace.  Absolute paths are accepted only when their canonical
target is already inside one of those roots."
  (unless (and (stringp path) (not (string-empty-p path)))
    (error "Jetpacs Applet Tooling: path must be a non-empty string"))
  (let* ((app-entry (jetpacs-applet-tooling--app-source-entry path))
         (reference (and (not app-entry)
                         (jetpacs-applet-tooling-reference-path-p path)))
         (workspace (jetpacs-applet-tooling-workspace-root))
         (root (cond
                (app-entry (cdr (car app-entry)))
                (reference (cdr reference))
                ((file-name-absolute-p path) nil)
                (t workspace)))
         (relative (cond
                    (app-entry (cdr app-entry))
                    (reference (substring path (1+ (length (car reference)))))
                    (t path)))
         (expanded (if root
                       (expand-file-name relative root)
                     (file-truename path))))
    (unless (file-exists-p expanded)
      (error "Jetpacs Applet Tooling: file does not exist: %s" path))
    (let ((true (file-truename expanded)))
      (unless (or (and root (file-in-directory-p true root))
                  (and (null root)
                       (or (file-in-directory-p true workspace)
                           (jetpacs-applet-tooling--app-source-for-path true)
                           (seq-some
                            (lambda (entry)
                              (file-in-directory-p true (cdr entry)))
                            (jetpacs-applet-tooling-resolved-reference-roots)))))
        (error "Jetpacs Applet Tooling: path leaves its allowed source root: %s"
               path))
      (unless (file-regular-p true)
        (error "Jetpacs Applet Tooling: path is not a regular file: %s" path))
      (let ((size (file-attribute-size (file-attributes true))))
        (when (> size jetpacs-applet-tooling-max-file-bytes)
          (error "Jetpacs Applet Tooling: file is %d bytes; limit is %d"
                 size jetpacs-applet-tooling-max-file-bytes)))
      true)))

(defun jetpacs-applet-tooling--reference-source-files (namespace)
  "Return canonical .el files for reference NAMESPACE in deterministic order."
  (when-let* ((root (cdr (assoc namespace
                                (jetpacs-applet-tooling-resolved-reference-roots)))))
    (sort
     (delq
      nil
      (mapcar
       (lambda (file)
         (when-let* ((true (file-truename file))
                     ((file-in-directory-p true root))
                     ((file-regular-p true))
                     ((<= (file-attribute-size (file-attributes true))
                          jetpacs-applet-tooling-max-file-bytes)))
           true))
       (directory-files-recursively root (rx ".el" eos))))
     #'string<)))

(defun jetpacs-applet-tooling--org-source-files ()
  "Return canonical Org .el files in deterministic order.
This compatibility wrapper delegates to the generic reference-root index."
  (jetpacs-applet-tooling--reference-source-files "org"))

;;;; Bounded deterministic serialization

(defun jetpacs-applet-tooling--cap (text)
  "Bound TEXT to `jetpacs-applet-tooling-max-result-chars'."
  (if (<= (length text) jetpacs-applet-tooling-max-result-chars)
      text
    (concat (substring text 0 jetpacs-applet-tooling-max-result-chars)
            (format "\n… output truncated at %d characters …"
                    jetpacs-applet-tooling-max-result-chars))))

(defun jetpacs-applet-tooling--cyclic-value-p (value &optional ancestors)
  "Return whether VALUE has a true reference cycle.
ANCESTORS is the identity stack for the current recursive path.
Shared substructure is legal and is deliberately visited again: object
identity is not part of an applet's semantic manifest."
  (when (or (consp value) (vectorp value) (hash-table-p value))
    (if (memq value ancestors)
        t
      (let ((next (cons value ancestors)))
        (cond
         ((consp value)
          (or (jetpacs-applet-tooling--cyclic-value-p (car value) next)
              (jetpacs-applet-tooling--cyclic-value-p (cdr value) next)))
         ((vectorp value)
          (seq-some (lambda (child)
                      (jetpacs-applet-tooling--cyclic-value-p child next))
                    value))
         (t
          (let (cyclic)
            (maphash
             (lambda (key child)
               (when (or (jetpacs-applet-tooling--cyclic-value-p key next)
                         (jetpacs-applet-tooling--cyclic-value-p child next))
                 (setq cyclic t)))
             value)
            cyclic)))))))

(defun jetpacs-applet-tooling--canonical-sexp-string (value)
  "Return VALUE in a reader-stable, untruncated Elisp representation.
The bindings make manifest hashes independent of a caller's printer options."
  (when (jetpacs-applet-tooling--cyclic-value-p value)
    (error "Jetpacs Applet Tooling: cyclic source data has no canonical manifest form"))
  (let ((print-circle nil)
        (print-gensym t)
        (print-length nil)
        (print-level nil)
        (print-quoted t)
        (print-escape-newlines t))
    (prin1-to-string value)))

(defun jetpacs-applet-tooling--stable-pp-string (value)
  "Pretty-print VALUE with tooling-owned settings for deterministic text."
  (when (jetpacs-applet-tooling--cyclic-value-p value)
    (error "Jetpacs Applet Tooling: cyclic data cannot be rendered deterministically"))
  (let ((pp-default-function #'pp-fill)
        (pp-escape-newlines t)
        (print-circle nil)
        (print-gensym t)
        (print-length nil)
        (print-level nil))
    (pp-to-string value #'pp-fill)))

(defun jetpacs-applet-tooling--file-sha256 (file)
  "Return SHA-256 of FILE's literal bytes, without coding conversion."
  (with-temp-buffer
    (set-buffer-multibyte nil)
    (insert-file-contents-literally file)
    (secure-hash 'sha256 (current-buffer))))

;;;; Reader-backed source indexing

(defcustom jetpacs-applet-tooling-max-source-entries 20000
  "Maximum directory entries visited while discovering static source files.
The limit covers all workspace and sibling checkout roots together.  Discovery
fails explicitly on overflow instead of returning an incomplete source index."
  :type 'natnum
  :group 'jetpacs-applet-tooling)

(defcustom jetpacs-applet-tooling-max-source-files 1000
  "Maximum distinct Elisp files retained in the static source index."
  :type 'natnum
  :group 'jetpacs-applet-tooling)

(defun jetpacs-applet-tooling--source-files ()
  "Return canonical indexed .el files in deterministic order.
Walk only configured workspace directories and the named sibling checkouts.
Skip hidden directories, build output and symlinks before descending, and
reject entry, file or depth overflow rather than silently truncating evidence."
  (let ((remaining jetpacs-applet-tooling-max-source-entries)
        (seen (make-hash-table :test #'equal))
        (workspace (jetpacs-applet-tooling-workspace-root))
        files)
    (cl-labels
        ((walk
          (directory boundary depth)
          (when (> depth 64)
            (error "Jetpacs Applet Tooling: source directory depth exceeds 64"))
          (let ((entries (directory-files directory t nil nil (1+ remaining))))
            (when (> (length entries) remaining)
              (error "Jetpacs Applet Tooling: source discovery exceeds %d entries"
                     jetpacs-applet-tooling-max-source-entries))
            (setq remaining (- remaining (length entries)))
            (dolist (entry entries)
              (let ((name (file-name-nondirectory entry)))
                (unless (or (string-prefix-p "." name)
                            (member name '("build" "node_modules"))
                            (file-symlink-p entry))
                  (let ((true (file-truename entry)))
                    (when (file-in-directory-p true boundary)
                      (cond
                       ((file-directory-p true)
                        (walk true boundary (1+ depth)))
                       ((and (string-suffix-p ".el" name)
                             (file-regular-p true)
                             (<= (file-attribute-size (file-attributes true))
                                 jetpacs-applet-tooling-max-file-bytes)
                             (not (gethash true seen)))
                        (when (>= (hash-table-count seen)
                                  jetpacs-applet-tooling-max-source-files)
                          (error "Jetpacs Applet Tooling: source index exceeds %d files"
                                 jetpacs-applet-tooling-max-source-files))
                        (puthash true t seen)
                        (push true files)))))))))))
      (dolist (relative (sort (copy-sequence jetpacs-applet-tooling-source-directories)
                             #'string<))
        (let ((directory (expand-file-name relative workspace)))
          (when (and (file-directory-p directory)
                     (not (file-symlink-p directory)))
            (unless (file-in-directory-p (file-truename directory) workspace)
              (error "Jetpacs Applet Tooling: index directory leaves workspace"))
            (walk directory workspace 0))))
      (dolist (entry (jetpacs-applet-tooling-resolved-app-source-roots))
        (walk (cdr entry) (cdr entry) 0)))
    (sort files #'string<)))

(defun jetpacs-applet-tooling--files-stamp (files)
  "Return a stable modification stamp for FILES."
  (mapcar (lambda (file)
            (let ((attrs (file-attributes file)))
              (list file
                    (file-attribute-modification-time attrs)
                    (file-attribute-size attrs))))
          files))

(defun jetpacs-applet-tooling--form-doc (form)
  "Return FORM's definition docstring, if statically present."
  (pcase (car-safe form)
    ((or 'defun 'cl-defun 'defmacro 'cl-defmacro 'defsubst)
     (and (stringp (nth 3 form)) (nth 3 form)))
    ((or 'defvar 'defconst 'defcustom)
     (and (stringp (nth 3 form)) (nth 3 form)))
    ('define-error (and (stringp (nth 2 form)) (nth 2 form)))
    (_ nil)))

(defun jetpacs-applet-tooling--form-args (form)
  "Return FORM's literal argument list, or nil."
  (when (memq (car-safe form)
              '(defun cl-defun defmacro cl-defmacro defsubst))
    (nth 2 form)))

(defun jetpacs-applet-tooling--collect-positioned-call-lines (form table)
  "Record reader-native source lines for every call-shaped cons in FORM.
TABLE uses cons identity, not printed equality.  This distinction means an
executable call and an identical quoted example retain different provenance
after symbol position wrappers are stripped from FORM.  Recording every head
keeps future action and builtin constructors introspectable without adding
their names to a manually maintained scanner allowlist."
  (when (consp form)
    (let ((head (car form)))
      (when (symbol-with-pos-p head)
        (puthash form
                 (line-number-at-pos (symbol-with-pos-pos head))
                 table)))
    (let ((tail form))
      (while (consp tail)
        (jetpacs-applet-tooling--collect-positioned-call-lines (car tail) table)
        (setq tail (cdr tail))))))

(defun jetpacs-applet-tooling--read-file (file)
  "Read top-level forms from FILE into positioned definition/error records.
No form is evaluated.  Emacs' reader first attaches locations to symbol
occurrences; call cons identity retains those exact locations after the
wrappers are removed, while downstream tools receive ordinary Lisp data."
  (with-temp-buffer
    (insert-file-contents file)
    (delay-mode-hooks (emacs-lisp-mode))
    (goto-char (point-min))
    (let ((relative (jetpacs-applet-tooling--relative file))
          records done)
      (while (not done)
        (condition-case err
            (progn
              (skip-chars-forward " \t\r\n")
              (forward-comment (point-max))
              (if (eobp)
                  (setq done t)
                (let* ((start (point))
                       (line (line-number-at-pos start))
                       (positioned
                        (read-positioning-symbols (current-buffer)))
                       (node-lines (make-hash-table :test #'eq))
                       (end (point))
                       (_ (jetpacs-applet-tooling--collect-positioned-call-lines
                           positioned node-lines))
                       (form (byte-run-strip-symbol-positions positioned))
                       (head (car-safe form)))
                  (push (append
                         (when (memq head jetpacs-applet-tooling--definition-heads)
                           (list :name (format "%s" (nth 1 form))
                                 :symbol (nth 1 form)
                                 :kind head
                                 :args (jetpacs-applet-tooling--form-args form)
                                 :doc (jetpacs-applet-tooling--form-doc form)))
                         (list :form form
                               :node-lines node-lines
                               :source (buffer-substring-no-properties start end)
                               :path relative
                               :line line))
                        records))))
          (end-of-file (setq done t))
          (error
           (push (list :parse-error (error-message-string err)
                       :path relative
                       :line (line-number-at-pos))
                 records)
           (setq done t))))
      (nreverse records))))

(defun jetpacs-applet-tooling--light-index-reference-file (file)
  "Index top-level definitions in reference FILE without retaining forms.
Reference packages may be large.  This first pass uses Elisp syntax shape to
locate column-level definitions; an exact query hydrates only matching forms
through the native reader.  The index is discovery data, never execution."
  (with-temp-buffer
    (insert-file-contents file)
    (delay-mode-hooks (emacs-lisp-mode))
    (goto-char (point-min))
    (let ((pattern
           (rx line-start (* (any " \t")) "("
               (group (or "defun" "cl-defun" "defmacro" "cl-defmacro"
                          "defsubst" "defvar" "defconst" "defcustom"
                          "cl-defstruct" "define-error"))
               (+ (any " \t\n"))
               (group (+ (not (any " \t\r\n()"))))))
          (relative (jetpacs-applet-tooling--relative file))
          (line 1)
          (previous (point-min))
          records)
      (while (re-search-forward pattern nil t)
        (let* ((start (match-beginning 0))
               (state (save-excursion
                        (save-match-data (syntax-ppss start)))))
          (cl-incf line
                   (cl-count ?\n (buffer-substring-no-properties
                                  previous start)))
          (setq previous start)
          (unless (or (> (car state) 0) (nth 3 state) (nth 4 state))
            (push (list :name (match-string-no-properties 2)
                        :symbol (intern (match-string-no-properties 2))
                        :kind (intern (match-string-no-properties 1))
                        :path relative
                        :line line
                        :offset (1- start)
                        :reference-light t)
                  records))))
      (nreverse records))))

(defun jetpacs-applet-tooling--light-index-org-file (file)
  "Compatibility wrapper for indexing one Org reference FILE."
  (jetpacs-applet-tooling--light-index-reference-file file))

(defun jetpacs-applet-tooling--hydrate-record (record)
  "Return RECORD with its exact Elisp form, signature, doc, and source read."
  (if (not (or (plist-get record :reference-light)
               (plist-get record :org-light)))
      record
    (let ((file (jetpacs-applet-tooling-resolve-file (plist-get record :path))))
      (with-temp-buffer
        (insert-file-contents file)
        (delay-mode-hooks (emacs-lisp-mode))
        (goto-char (1+ (plist-get record :offset)))
        (let* ((start (point))
               (state (save-excursion (syntax-ppss start)))
               (form (read (current-buffer)))
               (copy (copy-sequence record)))
          ;; The lightweight index is deliberately cheap.  Refuse stale or
          ;; false-positive offsets here, at the native-reader boundary,
          ;; instead of returning documentation for the wrong form.
          (unless (and (= (car state) 0)
                       (not (nth 3 state))
                       (not (nth 4 state))
                       (eq (car-safe form) (plist-get record :kind))
                       (equal (format "%s" (nth 1 form))
                              (plist-get record :name)))
            (error "Jetpacs Applet Tooling: reference index drift at %s:%d"
                   (plist-get record :path) (plist-get record :line)))
          (setq copy (plist-put copy :form form)
                copy (plist-put copy :args (jetpacs-applet-tooling--form-args form))
                copy (plist-put copy :doc (jetpacs-applet-tooling--form-doc form))
                copy (plist-put copy :source
                                (buffer-substring-no-properties
                                 start (point))))
          copy)))))

(defun jetpacs-applet-tooling-refresh-index (&optional force)
  "Return the static source index, rebuilding when files changed or FORCE."
  (let* ((files (jetpacs-applet-tooling--source-files))
         (stamp (jetpacs-applet-tooling--files-stamp files)))
    (when (or force (null jetpacs-applet-tooling--index)
              (not (equal stamp jetpacs-applet-tooling--index-stamp)))
      (setq jetpacs-applet-tooling--index
            (apply #'append (mapcar #'jetpacs-applet-tooling--read-file files))
            jetpacs-applet-tooling--index-stamp stamp))
    jetpacs-applet-tooling--index))

(defun jetpacs-applet-tooling-refresh-reference-index (namespace &optional force)
  "Return lazy index for reference NAMESPACE, rebuilding on change or FORCE."
  (unless (member namespace (jetpacs-applet-tooling-reference-namespaces))
    (error "Jetpacs Applet Tooling: unknown reference namespace %S" namespace))
  (let* ((files (jetpacs-applet-tooling--reference-source-files namespace))
         (stamp (jetpacs-applet-tooling--files-stamp files))
         (cached (gethash namespace
                          jetpacs-applet-tooling--reference-index-cache)))
    (when (or force (null cached)
              (not (equal stamp (plist-get cached :stamp))))
      (setq cached
            (list :stamp stamp
                  :records
                  (apply #'append
                         (mapcar #'jetpacs-applet-tooling--light-index-reference-file
                                 files))))
      (puthash namespace cached jetpacs-applet-tooling--reference-index-cache))
    (plist-get cached :records)))

(defun jetpacs-applet-tooling-refresh-org-index (&optional force)
  "Return the lazy `org' reference index, rebuilding on change or FORCE."
  (jetpacs-applet-tooling-refresh-reference-index "org" force))

(defun jetpacs-applet-tooling--public-record-p (record)
  "Non-nil when RECORD describes a public definition."
  (let ((name (plist-get record :name)))
    (and name (not (string-search "--" name)))))

;;;; Public source intelligence

(defun jetpacs-applet-tooling--format-record (record &optional include-doc)
  "Format definition RECORD, optionally INCLUDE-DOC."
  (let ((kind (plist-get record :kind))
        (name (plist-get record :name))
        (args (plist-get record :args))
        (doc (plist-get record :doc)))
    (concat (format "%s:%d  (%s %s%s)"
                    (plist-get record :path)
                    (plist-get record :line)
                    kind name
                    (if args (format " %S" args) ""))
            (if (and include-doc doc)
                (format "\n  %s" (replace-regexp-in-string "\n" "\n  " doc))
              ""))))

(defun jetpacs-applet-tooling-summarize-elisp-file (filepath &optional include-private)
  "Return a token-efficient static API summary for FILEPATH.
When INCLUDE-PRIVATE is nil, names containing `--' are omitted."
  (let* ((file (jetpacs-applet-tooling-resolve-file filepath))
         (records (jetpacs-applet-tooling--read-file file))
         (definitions (seq-filter
                       (lambda (record)
                         (and (plist-get record :name)
                              (or include-private
                                  (jetpacs-applet-tooling--public-record-p record))))
                       records))
         (parse-errors (seq-filter (lambda (record)
                                     (plist-get record :parse-error))
                                   records)))
    (jetpacs-applet-tooling--cap
     (concat (format "Elisp API summary: %s\n\n"
                     (jetpacs-applet-tooling--relative file))
             (if definitions
                 (mapconcat (lambda (record)
                              (jetpacs-applet-tooling--format-record record t))
                            definitions "\n\n")
               "No matching top-level definitions.")
             (when parse-errors
               (format "\n\nParse stopped: %s"
                       (plist-get (car parse-errors) :parse-error)))))))

(defun jetpacs-applet-tooling-describe-symbol (name &optional include-source)
  "Describe Elisp symbol NAME from every allowed static source root.
INCLUDE-SOURCE non-nil appends the exact top-level defining form."
  (unless (and (stringp name) (not (string-empty-p name)))
    (error "Jetpacs Applet Tooling: symbol must be a non-empty string"))
  (let* ((workspace-matches
         (seq-filter
           (lambda (record) (equal name (plist-get record :name)))
           (jetpacs-applet-tooling-refresh-index)))
         ;; Exact lookup asks every explicitly allowlisted package tree.  It
         ;; never guesses a package from a symbol prefix: Org alone includes
         ;; org-, ob-, ox-, and unprefixed compatibility names, and arbitrary
         ;; packages are free to make the same sorts of API choices.
         (reference-matches
         (apply #'append
                 (mapcar
                  (lambda (namespace)
                    (seq-filter
                     (lambda (record)
                       (equal name (plist-get record :name)))
                     (jetpacs-applet-tooling-refresh-reference-index
                      namespace)))
                  (jetpacs-applet-tooling-reference-namespaces))))
         (matches (append workspace-matches reference-matches)))
    (setq matches
          (sort matches
                (lambda (left right)
                  (string< (format "%s:%09d" (plist-get left :path)
                                   (or (plist-get left :line) 0))
                           (format "%s:%09d" (plist-get right :path)
                                   (or (plist-get right :line) 0))))))
    (setq matches (mapcar #'jetpacs-applet-tooling--hydrate-record matches))
    (if (null matches)
        (format "No indexed Elisp definition named %s." name)
      (jetpacs-applet-tooling--cap
       (mapconcat
        (lambda (record)
          (concat (jetpacs-applet-tooling--format-record record t)
                  (if include-source
                      (format "\n\n%s" (plist-get record :source))
                    "")))
        matches "\n\n---\n\n")))))

(defun jetpacs-applet-tooling-api-categories ()
  "Return static API categories, including configured package namespaces."
  (append '("all" "widgets" "surfaces" "apps" "chrome" "ebp"
            "references")
          (jetpacs-applet-tooling-reference-namespaces)))

(defun jetpacs-applet-tooling-list-api (&optional category query limit include-private)
  "List indexed APIs in CATEGORY matching literal QUERY.
CATEGORY is a Jetpacs category, `references' for all read-only package roots,
or one configured reference namespace such as `org' or `magit'.  `all' means
the workspace index.  LIMIT defaults to 80.  INCLUDE-PRIVATE exposes names
containing `--'."
  (let* ((category (or category "all"))
         (reference-namespaces
          (jetpacs-applet-tooling-reference-namespaces))
         (reference-category (member category reference-namespaces))
         (limit (min 200 (max 1 (or limit 80))))
         (file-re
          (unless (or reference-category (equal category "references"))
            (pcase category
              ("all" nil)
              ("widgets" (rx "/jetpacs-widgets.el" eos))
              ("surfaces" (rx "/" (or "jetpacs-surfaces.el"
                                        "jetpacs-shell.el") eos))
              ("apps" (rx "/" (or "jetpacs-apps.el"
                                    "jetpacs-app-store.el") eos))
              ("chrome" (rx "/jetpacs-chrome.el" eos))
              ("ebp" (rx "/ebp" (* anychar) ".el" eos))
              (_ (error (concat
                         "Jetpacs Applet Tooling: unknown API category %s; "
                         "available: %s")
                        category
                        (string-join
                         (jetpacs-applet-tooling-api-categories) ", "))))))
         (source-records
          (cond
           (reference-category
            (jetpacs-applet-tooling-refresh-reference-index category))
           ((equal category "references")
            (apply #'append
                   (mapcar #'jetpacs-applet-tooling-refresh-reference-index
                           reference-namespaces)))
           (t (jetpacs-applet-tooling-refresh-index))))
         (records
          (seq-filter
           (lambda (record)
             (let ((name (plist-get record :name)))
               (and name
                    (or include-private (jetpacs-applet-tooling--public-record-p record))
                    (or (null file-re)
                        (string-match-p file-re (concat "/" (plist-get record :path))))
                    (or (null query) (string-empty-p query)
                        (string-search (downcase query) (downcase name))))))
           source-records))
         (records (mapcar #'jetpacs-applet-tooling--hydrate-record
                          (seq-take records limit))))
    (jetpacs-applet-tooling--cap
     (concat (format "Elisp API: category=%s query=%s limit=%d\n\n"
                     category (or query "") limit)
             (if records
                 (mapconcat #'jetpacs-applet-tooling--format-record records "\n")
               "No matching definitions.")))))

(defun jetpacs-applet-tooling-find-ebp-endpoints ()
  "Return a static index of EBP endpoint definitions.
This supersedes the prototype's obarray scan, which only reported whatever a
particular batch process happened to load."
  (jetpacs-applet-tooling-list-api "ebp" nil 200 nil))

;;;; EBP contract introspection

(defun jetpacs-applet-tooling--contract-file ()
  "Return the canonical active EBP contract path."
  (let ((candidates '("ebp/contract.json"))
        found)
    (dolist (candidate candidates)
      (unless found
        (condition-case nil
            (setq found (jetpacs-applet-tooling-resolve-file candidate))
          (error nil))))
    (or found
        (error "Jetpacs Applet Tooling: no active EBP contract.json found"))))

(defun jetpacs-applet-tooling--contract ()
  "Parse and return the active EBP contract as hash tables and lists."
  (require 'json)
  (with-temp-buffer
    (insert-file-contents (jetpacs-applet-tooling--contract-file))
    (json-parse-buffer :object-type 'hash-table :array-type 'list
                       :null-object :json-null :false-object :json-false)))

(defun jetpacs-applet-tooling-describe-node (node-type)
  "Describe EBP NODE-TYPE and its real Elisp builder."
  (unless (and (stringp node-type)
               (string-match-p jetpacs-applet-tooling--identifier-re node-type))
    (error "Jetpacs Applet Tooling: node type must be an EBP identifier"))
  (let* ((contract (jetpacs-applet-tooling--contract))
         (schemas (gethash "node_schema" contract))
         (schema (and schemas (gethash node-type schemas))))
    (unless schema
      (error "Jetpacs Applet Tooling: unknown EBP node type %s" node-type))
    (let* ((required (gethash "required" schema))
           (optional (gethash "optional" schema))
           (fields (append required optional))
           (field-types (gethash "field_types" contract))
           (enums (gethash "enums" contract))
           (builder (concat "jetpacs-"
                            (replace-regexp-in-string "_" "-" node-type)))
           (builder-record
            (seq-find (lambda (record)
                        (equal builder (plist-get record :name)))
                      (jetpacs-applet-tooling-refresh-index))))
      (jetpacs-applet-tooling--cap
       (concat
        (format "EBP node: %s\nBuilder: %s%s\n\n"
                node-type builder
                (if builder-record
                    (format " (%s:%d)" (plist-get builder-record :path)
                            (plist-get builder-record :line))
                  " [not found]"))
        (format "Required fields: %s\n"
                (if required (string-join required ", ") "none"))
        (format "Optional fields: %s\n\n"
                (if optional (string-join optional ", ") "none"))
        "Field contracts:\n"
        (mapconcat
         (lambda (field)
           (let* ((enum-key (concat node-type "." field))
                  (enum (gethash enum-key enums))
                  (type (gethash field field-types)))
             (format "- %s%s%s"
                     field
                     (if type (format ": %s" type) "")
                     (if enum
                         (format "; enum %s [%s]" enum-key
                                 (string-join enum ", "))
                       ""))))
         fields "\n")
        (when builder-record
          (format "\n\n%s" (jetpacs-applet-tooling--format-record builder-record t))))))))

;;;; Homoiconic applet inspection

(defun jetpacs-applet-tooling--resolve-source-target (path)
  "Resolve existing PATH as a file or directory in an allowed source root.
Unlike `jetpacs-applet-tooling-resolve-file', this helper permits a directory
target so a namespaced app checkout can be inspected recursively."
  (unless (and (stringp path) (not (string-empty-p path)))
    (error "Jetpacs Applet Tooling: path must be a non-empty string"))
  (let* ((app-entry (jetpacs-applet-tooling--app-source-entry path))
         (reference (and (not app-entry)
                         (jetpacs-applet-tooling-reference-path-p path)))
         (workspace (jetpacs-applet-tooling-workspace-root))
         (root (cond
                (app-entry (cdr (car app-entry)))
                (reference (cdr reference))
                ((file-name-absolute-p path) nil)
                (t workspace)))
         (relative (cond
                    (app-entry (cdr app-entry))
                    (reference (substring path (1+ (length (car reference)))))
                    (t path)))
         (expanded (if root
                       (expand-file-name relative root)
                     (file-truename path)))
         (true (file-truename expanded)))
    (unless (file-exists-p expanded)
      (error "Jetpacs Applet Tooling: applet path does not exist: %s" path))
    (unless (or (and root (file-in-directory-p true root))
                (and (null root)
                     (or (file-in-directory-p true workspace)
                         (jetpacs-applet-tooling--app-source-for-path true)
                         (seq-some
                          (lambda (entry)
                            (file-in-directory-p true (cdr entry)))
                          (jetpacs-applet-tooling-resolved-reference-roots)))))
      (error "Jetpacs Applet Tooling: applet path leaves its allowed source root: %s"
             path))
    true))

(defun jetpacs-applet-tooling--applet-files (path)
  "Resolve PATH to one .el file or all .el files recursively below a directory."
  (let ((true (jetpacs-applet-tooling--resolve-source-target path)))
    (cond
     ((file-regular-p true)
      (unless (string-match-p (rx ".el" eos) true)
        (error "Jetpacs Applet Tooling: applet file must end in .el: %s" path))
      (list (jetpacs-applet-tooling-resolve-file true)))
     ((file-directory-p true)
      (let ((files
             (sort
              (delq
               nil
               (mapcar
                (lambda (file)
                  (when-let* ((canonical (file-truename file))
                              ((or (file-in-directory-p
                                    canonical
                                    (jetpacs-applet-tooling-workspace-root))
                                   (jetpacs-applet-tooling--app-source-for-path
                                    canonical)))
                              ((file-regular-p canonical))
                              ((<= (file-attribute-size
                                    (file-attributes canonical))
                                   jetpacs-applet-tooling-max-file-bytes)))
                    canonical))
                (directory-files-recursively true (rx ".el" eos))))
              #'string<)))
        (unless files
          (error "Jetpacs Applet Tooling: applet directory contains no .el files: %s"
                 path))
        files))
     (t (error "Jetpacs Applet Tooling: unsupported applet path: %s" path)))))

(defun jetpacs-applet-tooling--walk-form (form visitor &optional ancestors)
  "Call VISITOR for FORM and recursively visit code with ANCESTORS.
Quoted data is skipped.  Lambda bodies inside `(function (lambda ...))' are
code and remain visible."
  (when (consp form)
    (funcall visitor form ancestors)
    (cond
     ((or (eq (car form) 'quote)
          (and (symbolp (car form))
               (equal (symbol-name (car form)) "`")))
      nil)
     ((eq (car form) 'function)
       (when (consp (nth 1 form))
         (jetpacs-applet-tooling--walk-form (nth 1 form) visitor (cons form ancestors))))
     (t
      ;; Source forms can contain dotted pairs (especially fixture alists).
      ;; Walk the proper prefix without asking `dolist' to coerce the tail.
      (let ((tail (cdr form)))
        (while (consp tail)
          (jetpacs-applet-tooling--walk-form (car tail) visitor (cons form ancestors))
          (setq tail (cdr tail))))))))

(defun jetpacs-applet-tooling--literal-name (value &optional literals)
  "Return VALUE's statically provable string/symbol name, or nil.
LITERALS is an alist of symbol to statically known string constants.  A bare
symbol not present there is a variable reference, not a literal; treating it
as its printed name would turn `(jetpacs-action name)' into the fictitious
wire action \"name\" and hide exactly the dynamic site the inventory must show."
  (cond ((stringp value) value)
        ((symbolp value) (alist-get value literals))
        ((and (consp value) (memq (car value) '(quote function))
              (symbolp (nth 1 value)))
         (symbol-name (nth 1 value)))))

(defun jetpacs-applet-tooling--registration-line (node table fallback)
  "Return NODE's reader-native source line from identity TABLE or FALLBACK."
  (or (and table (gethash node table)) fallback))

(defun jetpacs-applet-tooling--scan-applet-file (file)
  "Return registrations and definitions statically found in FILE."
  (let* ((records (jetpacs-applet-tooling--read-file file))
         (relative (jetpacs-applet-tooling--relative file))
         (literals
          (delq nil
                (mapcar
                 (lambda (record)
                   (let ((form (plist-get record :form)))
                     (when (and (memq (car-safe form) '(defconst defvar))
                                (symbolp (nth 1 form))
                                (stringp (nth 2 form)))
                       (cons (nth 1 form) (nth 2 form)))))
                 records)))
        requires provides owners owner-registrations actions action-emissions
        apps roots definitions)
    (dolist (record records)
      (when-let* ((form (plist-get record :form)))
        (let ((registration-lines (plist-get record :node-lines)))
          (when (memq (car form)
                      '(defun cl-defun defmacro cl-defmacro defsubst))
            (push (cons (format "%s" (nth 1 form)) form) definitions))
          (jetpacs-applet-tooling--walk-form
           form
           (lambda (node ancestors)
             (let ((owner
                    (seq-some
                     (lambda (parent)
                       (when (eq (car-safe parent) 'with-jetpacs-owner)
                         (jetpacs-applet-tooling--literal-name (nth 1 parent) literals)))
                     ancestors)))
               (when (memq (car-safe node)
                           '(jetpacs-action
                             jetpacs-shell-action-opening-surface))
                 (push (list
                        :name (jetpacs-applet-tooling--literal-name
                               (nth 1 node) literals)
                        :expression
                        (jetpacs-applet-tooling--canonical-sexp-string
                         (nth 1 node))
                        :constructor (symbol-name (car node))
                        :form (jetpacs-applet-tooling--canonical-sexp-string
                               node)
                        :path relative
                        :line (jetpacs-applet-tooling--registration-line
                               node registration-lines
                               (plist-get record :line)))
                       action-emissions))
               (pcase (car-safe node)
             ('require
              (when-let* ((name (jetpacs-applet-tooling--literal-name (nth 1 node) literals)))
                (push name requires)))
             ('provide
              (when-let* ((name (jetpacs-applet-tooling--literal-name (nth 1 node) literals)))
                (push name provides)))
             ('with-jetpacs-owner
              (when-let* ((name (jetpacs-applet-tooling--literal-name (nth 1 node) literals)))
                (push name owners)
                (push (list :name name
                            :form (jetpacs-applet-tooling--canonical-sexp-string node)
                            :path relative
                            :line (jetpacs-applet-tooling--registration-line
                                   node registration-lines
                                   (plist-get record :line)))
                      owner-registrations)))
             ('jetpacs-defaction
              (let ((options (nthcdr 3 node)))
                (push (append
                       (list
                        :name (jetpacs-applet-tooling--literal-name
                               (nth 1 node) literals)
                        :handler (jetpacs-applet-tooling--literal-name
                                  (nth 2 node) literals)
                        :owner owner
                        :owned (and owner t)
                        :form (jetpacs-applet-tooling--canonical-sexp-string node)
                        :path relative
                        :line (jetpacs-applet-tooling--registration-line
                               node registration-lines
                               (plist-get record :line)))
                       (when (plist-member options :any-surface)
                         (list :any-surface
                               (and (plist-get options :any-surface) t)))
                       (when (plist-member options :args)
                         (list :args
                               (jetpacs-applet-tooling--canonical-sexp-string
                                (plist-get options :args))))
                       (when (stringp (plist-get options :doc))
                         (list :doc (plist-get options :doc))))
                      actions)))
             ('jetpacs-defapp
              (let ((options (nthcdr 2 node)))
                (push (append
                       (list
                        :id (jetpacs-applet-tooling--literal-name
                             (nth 1 node) literals)
                        :form (jetpacs-applet-tooling--canonical-sexp-string node)
                        :path relative
                        :line (jetpacs-applet-tooling--registration-line
                               node registration-lines
                               (plist-get record :line)))
                       (when (stringp (plist-get options :label))
                         (list :label (plist-get options :label)))
                       (when (stringp (plist-get options :icon))
                         (list :icon (plist-get options :icon)))
                       (when (plist-member options :surfaces)
                         (list :surfaces
                               (jetpacs-applet-tooling--canonical-sexp-string
                                (plist-get options :surfaces)))))
                      apps)))
             ('jetpacs-chrome-define-root
              (push (list :surface (jetpacs-applet-tooling--literal-name (nth 1 node) literals)
                          :id (jetpacs-applet-tooling--literal-name (nth 2 node) literals)
                          :owner owner
                          :builder (jetpacs-applet-tooling--canonical-sexp-string
                                    (nth 3 node))
                          :form (jetpacs-applet-tooling--canonical-sexp-string node)
                          :path relative
                          :line (jetpacs-applet-tooling--registration-line
                                 node registration-lines
                                 (plist-get record :line)))
                    roots)))))))))
    (list :path relative
          :records records
          :requires (delete-dups requires)
          :provides (delete-dups provides)
          :owners (delete-dups owners)
          :owner-registrations (nreverse owner-registrations)
          :actions (nreverse actions)
          :action-emissions (nreverse action-emissions)
          :apps (nreverse apps)
          :roots (nreverse roots)
          :definitions definitions)))

(defun jetpacs-applet-tooling--platform-source-root ()
  "Return the canonical Jetpacs platform Elisp root, or nil if unavailable."
  (let* ((workspace (jetpacs-applet-tooling-workspace-root))
         (relative
          (or jetpacs-applet-tooling-platform-source-directory
              (seq-find
               (lambda (candidate)
                 (file-directory-p (expand-file-name candidate workspace)))
               '("emacs"))))
         (candidate (and relative (expand-file-name relative workspace))))
    (when candidate
      (let ((root (file-name-as-directory (file-truename candidate))))
        (unless (or (equal root workspace)
                    (file-in-directory-p root workspace))
          (error "Jetpacs Applet Tooling: platform source leaves workspace: %s"
                 relative))
        root))))

(defun jetpacs-applet-tooling--platform-files ()
  "Return bounded platform .el files in deterministic order."
  (when-let* ((root (jetpacs-applet-tooling--platform-source-root)))
    (sort
     (delq
      nil
      (mapcar
       (lambda (file)
         (when-let* ((true (file-truename file))
                     ((file-in-directory-p true root))
                     ((file-regular-p true))
                     ((<= (file-attribute-size (file-attributes true))
                          jetpacs-applet-tooling-max-file-bytes)))
           true))
       (directory-files-recursively root (rx ".el" eos))))
     #'string<)))

(defun jetpacs-applet-tooling--platform-scans ()
  "Read current platform source as applet-shaped registration data.
There is intentionally no manual provider list and no Glasspane input here:
each reconciliation observes the current platform files through the same
native-reader path used for the applet.  A file stamp cache avoids rereading
unchanged forms within one server process and invalidates on source changes."
  (let* ((files (jetpacs-applet-tooling--platform-files))
         (stamp (jetpacs-applet-tooling--files-stamp files)))
    (when (or (null jetpacs-applet-tooling--platform-scans)
              (not (equal stamp
                          jetpacs-applet-tooling--platform-scans-stamp)))
      (setq jetpacs-applet-tooling--platform-scans
            (mapcar #'jetpacs-applet-tooling--scan-applet-file files)
            jetpacs-applet-tooling--platform-scans-stamp stamp))
    jetpacs-applet-tooling--platform-scans))

(defun jetpacs-applet-tooling--scan-records (scans key)
  "Return all records stored under KEY across SCANS."
  (apply #'append
         (mapcar (lambda (scan) (plist-get scan key)) scans)))

(defun jetpacs-applet-tooling--record-names (records)
  "Return sorted unique literal names from RECORDS."
  (sort (delete-dups
         (delq nil (mapcar (lambda (record) (plist-get record :name))
                           records)))
        #'string<))

(defun jetpacs-applet-tooling--action-reconciliation (scans platform-scans)
  "Reconcile action emissions in SCANS against local and PLATFORM-SCANS.
The result is a plist of unique literal-name classes plus the exact dynamic
emission records.  It is static evidence: a platform provider found in source
still must be loaded in the trusted runtime before it can dispatch."
  (let* ((registrations
          (jetpacs-applet-tooling--scan-records scans :actions))
         (emissions
          (jetpacs-applet-tooling--scan-records scans :action-emissions))
         (local-names
          (jetpacs-applet-tooling--record-names registrations))
         (platform-names
          (jetpacs-applet-tooling--record-names
           (jetpacs-applet-tooling--scan-records platform-scans :actions)))
         (emitted-names
          (jetpacs-applet-tooling--record-names emissions))
         local platform unresolved dynamic)
    (dolist (emission emissions)
      (if-let* ((name (plist-get emission :name)))
          (cond
           ((member name local-names) (cl-pushnew name local :test #'equal))
           ((member name platform-names)
            (cl-pushnew name platform :test #'equal))
           (t (cl-pushnew name unresolved :test #'equal)))
        (push emission dynamic)))
    (list :registrations registrations
          :emissions emissions
          :emitted-names emitted-names
          :local-names (sort local #'string<)
          :platform-names (sort platform #'string<)
          :unresolved-names (sort unresolved #'string<)
          :dynamic-emissions (nreverse dynamic)
          :registered-only-names
          (sort (seq-difference local-names emitted-names #'equal) #'string<))))

(defun jetpacs-applet-tooling--builtin-name-in-node (node)
  "Return literal builtin name constructed by NODE, or nil."
  (when (eq (car-safe node) 'jetpacs-make-node)
    (let ((value (plist-get (nthcdr 2 node) :builtin)))
      (and (stringp value) value))))

(defun jetpacs-applet-tooling--derive-builtin-constructors (platform-scans)
  "Derive builtin constructor records from PLATFORM-SCANS definitions."
  (let (constructors)
    (dolist (scan platform-scans)
      (dolist (record (plist-get scan :records))
        (when-let* ((constructor (plist-get record :name))
                    (form (plist-get record :form)))
          (jetpacs-applet-tooling--walk-form
           form
           (lambda (node _ancestors)
             (when-let* ((builtin
                          (jetpacs-applet-tooling--builtin-name-in-node node)))
               (cl-pushnew
                (list :constructor constructor
                      :builtin builtin
                      :direct t
                      :path (plist-get record :path)
                      :sources (list (plist-get record :path))
                      :line (jetpacs-applet-tooling--registration-line
                             node (plist-get record :node-lines)
                             (plist-get record :line)))
                constructors :test #'equal)))))))
    ;; A platform helper may deliberately wrap a primitive constructor (for
    ;; example `jetpacs-shell-open-surface-action' negotiates surface.open and
    ;; a remote fallback).  Derive that relation from the function call graph
    ;; instead of hard-coding wrapper names.  This is a may-emit relation: it
    ;; documents reachable native vocabulary without pretending to prove a
    ;; runtime branch.
    (let ((changed t))
      (while changed
        (setq changed nil)
        (dolist (scan platform-scans)
          (dolist (record (plist-get scan :records))
            (when-let* ((constructor (plist-get record :name))
                        (form (plist-get record :form)))
              (jetpacs-applet-tooling--walk-form
               form
               (lambda (node _ancestors)
                 (when-let* ((head (car-safe node))
                             ((symbolp head))
                             (provider
                              (seq-find
                               (lambda (candidate)
                                 (equal (symbol-name head)
                                        (plist-get candidate :constructor)))
                               constructors))
                             (builtin (plist-get provider :builtin))
                             ((not
                               (seq-some
                                (lambda (candidate)
                                  (and
                                   (equal constructor
                                          (plist-get candidate :constructor))
                                   (equal builtin
                                          (plist-get candidate :builtin))))
                                constructors))))
                   (push (list :constructor constructor
                               :builtin builtin
                               :direct nil
                               :via (symbol-name head)
                               :path (plist-get record :path)
                               :sources
                               (sort
                                (delete-dups
                                 (cons (plist-get record :path)
                                       (copy-sequence
                                        (plist-get provider :sources))))
                                #'string<)
                               :line
                               (jetpacs-applet-tooling--registration-line
                                node (plist-get record :node-lines)
                                (plist-get record :line)))
                         constructors)
                   (setq changed t)))))))))
    (sort constructors
          (lambda (left right)
            (string< (format "%s:%s" (plist-get left :builtin)
                             (plist-get left :constructor))
                     (format "%s:%s" (plist-get right :builtin)
                             (plist-get right :constructor)))))))

(defun jetpacs-applet-tooling--builtin-constructors (platform-scans)
  "Return source-derived builtin constructors for PLATFORM-SCANS.
The current platform scan uses its source stamp cache.  Explicit fixture scans
are always derived independently so tests and callers cannot inherit unrelated
workspace evidence."
  (if (not (eq platform-scans jetpacs-applet-tooling--platform-scans))
      (jetpacs-applet-tooling--derive-builtin-constructors platform-scans)
    (when (or (null jetpacs-applet-tooling--builtin-constructors-cache)
              (not (equal jetpacs-applet-tooling--platform-scans-stamp
                          jetpacs-applet-tooling--builtin-constructors-stamp)))
      (setq jetpacs-applet-tooling--builtin-constructors-cache
            (jetpacs-applet-tooling--derive-builtin-constructors
             platform-scans)
            jetpacs-applet-tooling--builtin-constructors-stamp
            jetpacs-applet-tooling--platform-scans-stamp))
    jetpacs-applet-tooling--builtin-constructors-cache))

(defun jetpacs-applet-tooling--builtin-emissions (scans constructors)
  "Return statically reachable builtin sites in SCANS using CONSTRUCTORS.
CONSTRUCTORS contains direct primitives and source-derived wrapper reachability,
so this is a conservative may-emit inventory rather than branch proof."
  (let (emissions)
    (dolist (scan scans)
      (dolist (record (plist-get scan :records))
        (when-let* ((form (plist-get record :form)))
          (jetpacs-applet-tooling--walk-form
           form
           (lambda (node _ancestors)
             (let* ((head (car-safe node))
                    (name (and (symbolp head) (symbol-name head)))
                    (matches
                     (and name
                          (seq-filter
                           (lambda (constructor)
                             (equal name (plist-get constructor :constructor)))
                           constructors)))
                    (direct (jetpacs-applet-tooling--builtin-name-in-node node)))
               (dolist (builtin
                        (delete-dups
                         (append (and direct (list direct))
                                 (mapcar (lambda (match)
                                           (plist-get match :builtin))
                                         matches))))
                 (let ((provider-sources
                        (sort
                         (delete-dups
                          (apply
                           #'append
                           (mapcar
                            (lambda (match)
                              (when (equal builtin
                                           (plist-get match :builtin))
                                (copy-sequence
                                 (plist-get match :sources))))
                            matches)))
                         #'string<)))
                   (push (append
                          (list
                           :builtin builtin
                           :constructor (or name "<literal>")
                           :form
                           (jetpacs-applet-tooling--canonical-sexp-string node)
                           :path (plist-get record :path)
                           :line (jetpacs-applet-tooling--registration-line
                                  node (plist-get record :node-lines)
                                  (plist-get record :line)))
                          (when provider-sources
                            (list :provider-sources
                                  (vconcat provider-sources))))
                         emissions)))))))))
    (nreverse emissions)))

(defun jetpacs-applet-tooling--contract-builtin-records ()
  "Return builtin action schemas derived from the active EBP contract."
  (let* ((contract (jetpacs-applet-tooling--contract))
         (actions (gethash "actions" contract))
         (schemas (and actions (gethash "schema" actions)))
         records)
    (unless (hash-table-p schemas)
      (error "Jetpacs Applet Tooling: contract has no actions.schema object"))
    (maphash
     (lambda (name schema)
       (unless (equal name "remote")
         (push (list :builtin name
                     :required (copy-sequence (or (gethash "required" schema)
                                                  '()))
                     :optional (copy-sequence (or (gethash "optional" schema)
                                                  '())))
               records)))
     schemas)
    (sort records (lambda (left right)
                    (string< (plist-get left :builtin)
                             (plist-get right :builtin))))))

(defun jetpacs-applet-tooling-action-vocabulary ()
  "Describe the open remote-action model and closed native builtin vocabulary.
All fields come from the active EBP contract and current Jetpacs Elisp source;
the report contains no Glasspane-maintained allowlist."
  (let* ((contract (jetpacs-applet-tooling--contract))
         (actions (gethash "actions" contract))
         (schemas (gethash "schema" actions))
         (remote (gethash "remote" schemas))
         (policies (gethash "offline_policies" actions))
         (platform-scans (jetpacs-applet-tooling--platform-scans))
         (constructors
          (jetpacs-applet-tooling--builtin-constructors platform-scans))
         (builtins (jetpacs-applet-tooling--contract-builtin-records)))
    (jetpacs-applet-tooling--cap
     (concat
      "Jetpacs action vocabulary (derived from contract.json + platform Elisp)\n\n"
      "Remote semantic actions are open-world names. An applet may define any dotted EBP identifier with jetpacs-defaction and emit it with jetpacs-action; the Companion generically dispatches the name and does not need one Kotlin implementation per applet verb.\n\n"
      (format "Remote descriptor required fields: %s\n"
              (string-join (gethash "required" remote) ", "))
      (format "Remote descriptor optional fields: %s\n"
              (string-join (gethash "optional" remote) ", "))
      (format "Offline policies: %s\n\n" (string-join policies ", "))
      (format "Native builtins (%d; closed, profile-advertised vocabulary):\n"
              (length builtins))
      (mapconcat
       (lambda (builtin)
         (let* ((name (plist-get builtin :builtin))
                (matches
                 (seq-filter
                  (lambda (constructor)
                    (and (plist-get constructor :direct)
                         (equal name (plist-get constructor :builtin))))
                  constructors))
                (constructor-names
                 (mapcar (lambda (record)
                           (plist-get record :constructor))
                         matches)))
           (format "- %s — required: %s; optional: %s; Elisp: %s"
                   name
                   (if-let* ((fields (plist-get builtin :required)))
                       (string-join fields ", ")
                     "none")
                   (if-let* ((fields (plist-get builtin :optional)))
                       (string-join fields ", ")
                     "none")
                   (if constructor-names
                       (string-join (sort (delete-dups constructor-names)
                                          #'string<)
                                    ", ")
                     "no direct constructor found"))))
       builtins "\n")
      "\n\nSupport rule: add ordinary applet behavior by registering an Emacs handler; change EBP/Companion only for a new descriptor field, builtin, node/member, method, capability, or platform integration. Negotiated surface profiles remain authoritative for whether one connected Companion supports a builtin."))))

(defun jetpacs-applet-tooling--format-name-set (names)
  "Format sorted action NAMES compactly, preserving an explicit empty set."
  (if names (string-join names ", ") "none"))

(defun jetpacs-applet-tooling-inspect-applet (path)
  "Return a source-derived applet and action inventory for PATH.
Registered handlers, emitted remote descriptors, provider reconciliation, and
builtin constructor use are separate categories; no applet action total is a
tooling constant."
  (let* ((files (jetpacs-applet-tooling--applet-files path))
         (scans (mapcar #'jetpacs-applet-tooling--scan-applet-file files))
         (platform-scans (jetpacs-applet-tooling--platform-scans))
         (reconciliation
          (jetpacs-applet-tooling--action-reconciliation scans platform-scans))
         (builtin-constructors
          (jetpacs-applet-tooling--builtin-constructors platform-scans))
         (builtin-emissions
          (jetpacs-applet-tooling--builtin-emissions scans builtin-constructors))
         (protocol-builtins
          (mapcar (lambda (record) (plist-get record :builtin))
                  (jetpacs-applet-tooling--contract-builtin-records)))
         (requires (delete-dups (apply #'append
                                       (mapcar (lambda (s) (plist-get s :requires)) scans))))
         (provides (delete-dups (apply #'append
                                       (mapcar (lambda (s) (plist-get s :provides)) scans))))
         (owners (delete-dups (apply #'append
                                     (mapcar (lambda (s) (plist-get s :owners)) scans))))
         (actions (plist-get reconciliation :registrations))
         (action-emissions (plist-get reconciliation :emissions))
         (apps (apply #'append (mapcar (lambda (s) (plist-get s :apps)) scans)))
         (roots (apply #'append (mapcar (lambda (s) (plist-get s :roots)) scans))))
    (jetpacs-applet-tooling--cap
     (concat
      (format "Jetpacs applet inventory: %s\n\n" path)
      (format "Files (%d):\n%s\n\n" (length files)
              (mapconcat (lambda (file) (concat "- " (jetpacs-applet-tooling--relative file)))
                         files "\n"))
      (format "Owners: %s\n" (if owners (string-join owners ", ") "none"))
      (format "Provided features: %s\n"
              (if provides (string-join provides ", ") "none"))
      (format "Required features: %s\n"
              (jetpacs-applet-tooling--format-name-set
               (sort (copy-sequence requires) #'string<)))
      (format "Apps (%d):\n%s\n\n" (length apps)
              (if apps
                  (mapconcat (lambda (app)
                               (format "- %s (%s:%d)"
                                       (or (plist-get app :id) "<dynamic>")
                                       (plist-get app :path) (plist-get app :line)))
                             apps "\n")
                "- none"))
      (format "Roots (%d):\n%s\n\n" (length roots)
              (if roots
                  (mapconcat (lambda (root)
                               (format "- %s / %s (%s:%d)"
                                       (or (plist-get root :surface) "<dynamic>")
                                       (or (plist-get root :id) "<dynamic>")
                                       (plist-get root :path) (plist-get root :line)))
                             roots "\n")
                "- none"))
      (format "Registered remote actions (%d):\n%s\n\n" (length actions)
              (if actions
                  (mapconcat (lambda (action)
                               (format "- %s -> %s%s (%s:%d)"
                                       (or (plist-get action :name) "<dynamic>")
                                       (or (plist-get action :handler) "<lambda/dynamic>")
                                       (if (plist-get action :owned) "" " [not owner-scoped]")
                                       (plist-get action :path) (plist-get action :line)))
                             actions "\n")
                "- none"))
      (format (concat "Emitted remote action sites (%d; %d unique literal; "
                      "%d dynamic):\n")
              (length action-emissions)
              (length (plist-get reconciliation :emitted-names))
              (length (plist-get reconciliation :dynamic-emissions)))
      (format "- applet-provided (%d): %s\n"
              (length (plist-get reconciliation :local-names))
              (jetpacs-applet-tooling--format-name-set
               (plist-get reconciliation :local-names)))
      (format "- Jetpacs-platform-provided in source (%d): %s\n"
              (length (plist-get reconciliation :platform-names))
              (jetpacs-applet-tooling--format-name-set
               (plist-get reconciliation :platform-names)))
      (format "- unresolved literal names (%d): %s\n"
              (length (plist-get reconciliation :unresolved-names))
              (jetpacs-applet-tooling--format-name-set
               (plist-get reconciliation :unresolved-names)))
      (format "- dynamic sites (%d): %s\n"
              (length (plist-get reconciliation :dynamic-emissions))
              (if-let* ((dynamic (plist-get reconciliation :dynamic-emissions)))
                  (mapconcat
                   (lambda (emission)
                     (format "%s via %s (%s:%d)"
                             (plist-get emission :expression)
                             (plist-get emission :constructor)
                             (plist-get emission :path)
                             (plist-get emission :line)))
                   dynamic "; ")
                "none"))
      (format "Registered but not statically emitted (%d): %s\n\n"
              (length (plist-get reconciliation :registered-only-names))
              (jetpacs-applet-tooling--format-name-set
               (plist-get reconciliation :registered-only-names)))
      (format "Statically reachable native builtin sites (%d): %s\n"
              (length builtin-emissions)
              (jetpacs-applet-tooling--format-name-set
               (sort (delete-dups
                      (mapcar (lambda (emission)
                                (plist-get emission :builtin))
                              builtin-emissions))
                     #'string<)))
      (format "Protocol builtin vocabulary (%d): %s\n\n"
              (length protocol-builtins)
              (jetpacs-applet-tooling--format-name-set protocol-builtins))
      "Interpretation: registrations are Emacs handlers; emissions are descriptors the UI can produce. Literal emissions must resolve locally or in Jetpacs platform source. Dynamic sites require runtime/test evidence. Platform-source resolution proves a provider exists in this checkout, not that the applet loaded it; trusted runtime inventory is the load-state authority."))))

;;;; Canonical semantic manifests

(defun jetpacs-applet-tooling--sort-records (records &optional name-key)
  "Return a deterministic copy of RECORDS sorted by source and NAME-KEY."
  (sort (copy-sequence records)
        (lambda (left right)
          (string<
           (format "%s:%09d:%s"
                   (or (plist-get left :path) "")
                   (or (plist-get left :line) 0)
                   (or (and name-key (plist-get left name-key)) ""))
           (format "%s:%09d:%s"
                   (or (plist-get right :path) "")
                   (or (plist-get right :line) 0)
                   (or (and name-key (plist-get right name-key)) ""))))))

(defun jetpacs-applet-tooling--manifest-registration (record fields)
  "Project RECORD onto JSON-safe manifest FIELDS."
  (let (result)
    (dolist (field fields result)
      (when (plist-member record field)
        (setq result
              (append result
                      (list field
                            (let ((value (plist-get record field)))
                              (if (symbolp value)
                                  (symbol-name value)
                                value)))))))))

(defun jetpacs-applet-tooling--applet-manifest-data (path)
  "Return a deterministic, JSON-safe semantic manifest for applet PATH."
  (let* ((files (jetpacs-applet-tooling--applet-files path))
         (canonical-target
          (jetpacs-applet-tooling--resolve-source-target path))
         (canonical-path
          (if (file-regular-p canonical-target)
              (jetpacs-applet-tooling--relative canonical-target)
            (directory-file-name
             (file-relative-name canonical-target
                                 (jetpacs-applet-tooling-workspace-root)))))
         (scans (mapcar #'jetpacs-applet-tooling--scan-applet-file files))
         (owners (sort (delete-dups
                        (apply #'append
                               (mapcar (lambda (scan)
                                         (copy-sequence
                                          (plist-get scan :owners)))
                                       scans)))
                       #'string<))
         (owner-registrations
          (apply #'append
                 (mapcar (lambda (scan)
                           (plist-get scan :owner-registrations))
                         scans)))
         (actions (apply #'append
                         (mapcar (lambda (scan) (plist-get scan :actions))
                                 scans)))
         (action-emissions
          (apply #'append
                 (mapcar (lambda (scan)
                           (plist-get scan :action-emissions))
                         scans)))
         (builtin-emissions
          (jetpacs-applet-tooling--builtin-emissions
           scans
           (jetpacs-applet-tooling--builtin-constructors
            (jetpacs-applet-tooling--platform-scans))))
         (builtin-provider-paths
          (sort
           (delete-dups
            (apply
             #'append
             (mapcar
              (lambda (emission)
                (append (plist-get emission :provider-sources) nil))
              builtin-emissions)))
           #'string<))
         (contract-file (jetpacs-applet-tooling--contract-file))
         (apps (apply #'append
                      (mapcar (lambda (scan) (plist-get scan :apps)) scans)))
         (roots (apply #'append
                       (mapcar (lambda (scan) (plist-get scan :roots)) scans)))
         (definitions
          (apply
           #'append
           (mapcar
            (lambda (scan)
              (delq
               nil
               (mapcar
                (lambda (record)
                  (when-let* ((name (plist-get record :name)))
                    (let ((kind (plist-get record :kind)))
                      (append
                       (list :name name
                             :kind (symbol-name kind)
                             :path (plist-get record :path)
                             :line (plist-get record :line))
                       (when (memq kind
                                   '(defun cl-defun defmacro cl-defmacro
                                     defsubst))
                         ;; Keep `()': absence of arguments documents a real
                         ;; calling contract and is not missing metadata.
                         (list :signature
                               (let ((args (plist-get record :args)))
                                 (if args
                                     (jetpacs-applet-tooling--canonical-sexp-string args)
                                   "()"))))
                       (when-let* ((doc (plist-get record :doc)))
                         (list :doc doc))))))
                (plist-get scan :records))))
            scans)))
         (core
          (list
           :schema jetpacs-applet-tooling-manifest-schema
           :generator (list :name "jetpacs-applet-tooling"
                            :version jetpacs-applet-tooling-version
                            :emacs-version emacs-version)
           :analysis-inputs
           (list
            :contract
            (list :path (jetpacs-applet-tooling--relative contract-file)
                  :sha256 (jetpacs-applet-tooling--file-sha256 contract-file))
            :platform-files
            (vconcat
             (mapcar
              (lambda (relative)
                (let ((file (jetpacs-applet-tooling-resolve-file relative)))
                  (list :path relative
                        :sha256 (jetpacs-applet-tooling--file-sha256 file))))
              builtin-provider-paths)))
           :path canonical-path
           :files
           (vconcat
            (mapcar
             (lambda (scan)
               (let* ((relative (plist-get scan :path))
                      (file (jetpacs-applet-tooling-resolve-file relative)))
                 (list
                  :path relative
                  :sha256 (jetpacs-applet-tooling--file-sha256 file)
                  :requires (vconcat
                             (sort (copy-sequence
                                    (plist-get scan :requires)) #'string<))
                  :provides (vconcat
                             (sort (copy-sequence
                                    (plist-get scan :provides)) #'string<)))))
             (sort (copy-sequence scans)
                   (lambda (left right)
                     (string< (plist-get left :path)
                              (plist-get right :path))))))
           :owners (vconcat owners)
           :owner-registrations
           (vconcat
            (mapcar
             (lambda (record)
               (jetpacs-applet-tooling--manifest-registration
                record '(:name :path :line :form)))
             (jetpacs-applet-tooling--sort-records owner-registrations :name)))
           :apps
           (vconcat
            (mapcar
             (lambda (record)
               (jetpacs-applet-tooling--manifest-registration
                record '(:id :label :icon :surfaces :path :line :form)))
             (jetpacs-applet-tooling--sort-records apps :id)))
           :roots
           (vconcat
            (mapcar
             (lambda (record)
               (jetpacs-applet-tooling--manifest-registration
                record '(:surface :id :owner :builder :path :line :form)))
             (jetpacs-applet-tooling--sort-records roots :surface)))
           :actions
           (vconcat
            (mapcar
             (lambda (record)
               (jetpacs-applet-tooling--manifest-registration
                record '(:name :handler :owner :any-surface :args :doc
                          :path :line :form)))
             (jetpacs-applet-tooling--sort-records actions :name)))
           :action-emissions
           (vconcat
            (mapcar
             (lambda (record)
               (jetpacs-applet-tooling--manifest-registration
                record '(:name :expression :constructor :path :line :form)))
             (jetpacs-applet-tooling--sort-records
              action-emissions :name)))
           :builtin-emissions
           (vconcat
            (mapcar
             (lambda (record)
               (jetpacs-applet-tooling--manifest-registration
                record '(:builtin :constructor :provider-sources
                          :path :line :form)))
             (jetpacs-applet-tooling--sort-records
              builtin-emissions :builtin)))
           :definitions
           (vconcat
            (mapcar
             (lambda (record)
               (jetpacs-applet-tooling--manifest-registration
                record '(:name :kind :signature :doc :path :line)))
             (jetpacs-applet-tooling--sort-records definitions :name))))))
    (append core
            (list :sha256
                  (secure-hash 'sha256
                               (jetpacs-applet-tooling--canonical-sexp-string core))))))

(defun jetpacs-applet-tooling--manifest-full-text (manifest)
  "Render complete canonical MANIFEST text without truncating it."
  (concat "Jetpacs canonical applet manifest\n"
          (format "sha256: %s\n\n" (plist-get manifest :sha256))
          (jetpacs-applet-tooling--stable-pp-string manifest)))

(defun jetpacs-applet-tooling--manifest-index-text (manifest full-chars)
  "Render MANIFEST's lossless retrieval index for a FULL-CHARS document."
  (let* ((sections
          (vconcat
           (mapcar
            (lambda (mapping)
              (list :name (car mapping)
                    :entries (length (plist-get manifest (cdr mapping)))))
            jetpacs-applet-tooling--manifest-section-keys)))
         (index
          (list :schema jetpacs-applet-tooling-manifest-schema
                :generator (plist-get manifest :generator)
                :analysis-inputs (plist-get manifest :analysis-inputs)
                :path (plist-get manifest :path)
                :manifest-sha256 (plist-get manifest :sha256)
                :full-manifest-chars full-chars
                :result-bound-chars jetpacs-applet-tooling-max-result-chars
                :owners (plist-get manifest :owners)
                :sections sections))
         (text
          (concat
           "Jetpacs canonical applet manifest index\n"
           (format "sha256: %s\n" (plist-get manifest :sha256))
           (format (concat "The complete manifest is %d characters; the "
                           "tool result bound is %d.\n")
                   full-chars jetpacs-applet-tooling-max-result-chars)
           (concat
            "Retrieve it losslessly with section + offset. Each page repeats "
            "this digest, its range, and next offset.\n\n")
           (jetpacs-applet-tooling--stable-pp-string index))))
    (when (> (length text) jetpacs-applet-tooling-max-result-chars)
      (error (concat
              "Jetpacs Applet Tooling: manifest index is %d characters, over the %d "
              "character result bound")
             (length text) jetpacs-applet-tooling-max-result-chars))
    text))

(defun jetpacs-applet-tooling--manifest-page-text (manifest section offset limit)
  "Render a bounded page of MANIFEST SECTION from OFFSET through LIMIT entries.
Return the largest prefix fitting
`jetpacs-applet-tooling-max-result-chars'."
  (let* ((mapping (assoc section jetpacs-applet-tooling--manifest-section-keys))
         (key (cdr mapping)))
    (unless mapping
      (error "Jetpacs Applet Tooling: unknown manifest section %S; choose %s"
             section
             (string-join (mapcar #'car jetpacs-applet-tooling--manifest-section-keys)
                          ", ")))
    (unless (and (integerp offset) (>= offset 0))
      (error "Jetpacs Applet Tooling: manifest offset must be a non-negative integer"))
    (unless (and (integerp limit) (<= 1 limit 200))
      (error "Jetpacs Applet Tooling: manifest limit must be an integer from 1 through 200"))
    (let* ((entries (plist-get manifest key))
           (total (length entries)))
      (when (> offset total)
        (error "Jetpacs Applet Tooling: manifest offset %d is beyond %s total %d"
               offset section total))
      (let ((low 0)
            (high (min limit (- total offset)))
            best-text
            (best-count 0))
        (cl-labels
            ((render
              (count)
              (let* ((end (+ offset count))
                     (next (and (< end total) end))
                     (page
                      (list :schema jetpacs-applet-tooling-manifest-schema
                            :generator (plist-get manifest :generator)
                            :path (plist-get manifest :path)
                            :manifest-sha256 (plist-get manifest :sha256)
                            :section section
                            :offset offset
                            :count count
                            :total total
                            :next-offset next
                            :entries (seq-subseq entries offset end))))
                (concat
                 "Jetpacs canonical applet manifest page\n"
                 (format "sha256: %s\n" (plist-get manifest :sha256))
                 (format (concat "section: %s; offset: %d; count: %d; "
                                 "total: %d; next offset: %s\n\n")
                         section offset count total (or next "none"))
                 (jetpacs-applet-tooling--stable-pp-string page)))))
          ;; Result size is monotonic as complete records are appended. Binary
          ;; search retains as many records as possible without ever cutting a
          ;; form, docstring, or provenance record in half.
          (while (<= low high)
            (let* ((middle (/ (+ low high) 2))
                   (candidate (render middle)))
              (if (<= (length candidate) jetpacs-applet-tooling-max-result-chars)
                  (setq best-text candidate
                        best-count middle
                        low (1+ middle))
                (setq high (1- middle))))))
        (when (and (< offset total) (= best-count 0))
          (error (concat
                  "Jetpacs Applet Tooling: one %s manifest entry exceeds the %d "
                  "character result bound")
                 section jetpacs-applet-tooling-max-result-chars))
        (or best-text
            (error "Jetpacs Applet Tooling: manifest page header exceeds the result bound"))))))

(defun jetpacs-applet-tooling-applet-manifest
    (path &optional section offset limit)
  "Return PATH's canonical semantic manifest without evaluating its code.
The digest covers source hashes plus normalized registrations and definitions;
the same checkout under the same tooling and Emacs reader therefore produces
byte-identical manifest text.  Small
manifests are returned whole.  If the complete text exceeds the result bound,
return a lossless retrieval index instead of truncating a form.  SECTION names
one indexed vector; OFFSET defaults to zero and LIMIT defaults to 25 records."
  (let* ((manifest (jetpacs-applet-tooling--applet-manifest-data path))
         (full-text (jetpacs-applet-tooling--manifest-full-text manifest)))
    (cond
     ((null section)
      (when (or offset limit)
        (error "Jetpacs Applet Tooling: manifest offset/limit requires a section"))
      (if (<= (length full-text) jetpacs-applet-tooling-max-result-chars)
          full-text
        (jetpacs-applet-tooling--manifest-index-text manifest (length full-text))))
     ((equal section "index")
      (when (or offset limit)
        (error "Jetpacs Applet Tooling: manifest index does not accept offset/limit"))
      (jetpacs-applet-tooling--manifest-index-text manifest (length full-text)))
     (t
      (jetpacs-applet-tooling--manifest-page-text
       manifest section (or offset 0) (or limit 25))))))

;;;; Explicit trusted-runtime introspection

(defun jetpacs-applet-tooling-runtime-available-p ()
  "Non-nil when this process deliberately loaded the Jetpacs runtime tools."
  (and (fboundp 'jetpacs-devtools-capture-spec)
       (fboundp 'jetpacs-node->canonical-json)
       (fboundp 'jetpacs-shell--analyze-spec)
       (fboundp 'jetpacs-shell-roots)
       (boundp 'jetpacs-action-handlers)))

(defun jetpacs-applet-tooling--runtime-required ()
  "Signal with the explicit trust boundary when runtime mode is unavailable."
  (unless (jetpacs-applet-tooling-runtime-available-p)
    (error (concat
            "Jetpacs Applet Tooling: trusted runtime mode is not loaded; use "
            "JETPACS_TRUSTED_APPLET with jetpacs-applet-mcp-runtime.el.  "
            "The default server never executes applet code"))))

(defun jetpacs-applet-tooling--runtime-source (kind name)
  "Return the runtime claim site for KIND and NAME, when recorded."
  (when-let* (((boundp 'jetpacs--claim-sites))
              ((hash-table-p jetpacs--claim-sites))
              (site (gethash (cons kind name) jetpacs--claim-sites)))
    (condition-case nil
        (jetpacs-applet-tooling--relative site)
      (error site))))

(defun jetpacs-applet-tooling-runtime-inventory ()
  "Return deterministic live registry data from an explicitly loaded runtime."
  (jetpacs-applet-tooling--runtime-required)
  (let (actions)
    (maphash
     (lambda (name _handler)
       (push (list :name name
                   :owner (and (boundp 'jetpacs--registrations)
                               (gethash (cons "action" name)
                                        jetpacs--registrations))
                   :source (jetpacs-applet-tooling--runtime-source "action" name)
                   :schema (jetpacs-action-schema name))
             actions))
     jetpacs-action-handlers)
    (setq actions
          (sort actions (lambda (left right)
                          (string< (plist-get left :name)
                                   (plist-get right :name)))))
    (let* ((roots
            (mapcar
             (lambda (entry)
               (list :surface (car entry)
                     :owner (cdr entry)
                     :source (jetpacs-applet-tooling--runtime-source
                              "surface" (car entry))))
             (sort (copy-sequence (jetpacs-shell-roots))
                   (lambda (left right) (string< (car left) (car right))))))
           (apps
            (when (and (boundp 'jetpacs-apps--registry)
                       (listp jetpacs-apps--registry))
              (mapcar
               (lambda (entry)
                 (let* ((data (cdr entry))
                        (home (car-safe (plist-get data :surfaces)))
                        (surface (and (stringp home)
                                      (if (string-search ":" home)
                                          home
                                        (concat "app:" home)))))
                   (list :id (car entry)
                         ;; App identity itself has no claim registry. Its
                         ;; first claimed surface is the documented home, so
                         ;; that real surface claim is its registration site.
                         :source (and surface
                                      (jetpacs-applet-tooling--runtime-source
                                       "surface" surface))
                         :label (plist-get data :label)
                         :icon (plist-get data :icon)
                         :surfaces (plist-get data :surfaces)
                         :home-route (plist-get data :home-route)
                         :chrome (format "%s" (plist-get data :chrome))
                         :dock-core (and (plist-get data :dock-core) t)
                         :drawer-core (plist-get data :drawer-core)
                         :order (plist-get data :order)
                         :dynamic-destinations
                         (and (functionp (plist-get data :destinations)) t)
                         :dynamic-fab
                         (and (functionp (plist-get data :fab)) t))))
               (sort (copy-sequence jetpacs-apps--registry)
                     (lambda (left right)
                       (string< (car left) (car right))))))))
      (jetpacs-applet-tooling--cap
       (concat
        "Jetpacs trusted runtime inventory\n"
        "This is live registry state, not a static source inference.\n\n"
        (jetpacs-applet-tooling--stable-pp-string
         (list :roots (vconcat roots)
               :apps (vconcat apps)
               :actions (vconcat actions))))))))

(defun jetpacs-applet-tooling--render-fixed-issues (analysis)
  "Return fixed structural-bound failures from render ANALYSIS."
  (let ((counts (plist-get analysis :counts)) issues)
    ;; Read the sender's live constants instead of copying their values into
    ;; developer tooling.  A protocol-bound change therefore changes the
    ;; checker, its context hash, and its prose in the same loaded runtime.
    (when (> (or (plist-get counts :depth) 0)
             jetpacs-shell--max-node-depth)
      (push (format "depth %d exceeds %d"
                    (plist-get counts :depth)
                    jetpacs-shell--max-node-depth)
            issues))
    (when (> (or (plist-get counts :nodes) 0)
             jetpacs-shell--max-nodes)
      (push (format "nodes %d exceeds %d"
                    (plist-get counts :nodes)
                    jetpacs-shell--max-nodes)
            issues))
    (when (> (or (plist-get counts :max-children) 0)
             jetpacs-shell--max-children)
      (push (format "max children %d exceeds %d"
                    (plist-get counts :max-children)
                    jetpacs-shell--max-children)
            issues))
    (when-let* ((id (plist-get analysis :duplicate-id)))
      (push (format "duplicate node id %S" id) issues))
    (when-let* ((key (plist-get analysis :duplicate-key)))
      (push (format "duplicate sibling key %S" key) issues))
    (when-let* ((identity (plist-get analysis :identity-error)))
      (push (format "invalid node identity %S" identity) issues))
    (when-let* ((aggregate (plist-get analysis :aggregate-error)))
      (push (format "malformed aggregate %S" aggregate) issues))
    (nreverse issues)))

(defun jetpacs-applet-tooling--runtime-root-id (surface)
  "Resolve SURFACE to the exact id present in the live root registry."
  (let ((roots (jetpacs-shell-roots)))
    (cond
     ((assoc surface roots) surface)
     ((assoc (concat "app:" surface) roots) (concat "app:" surface))
     (t nil))))

(defun jetpacs-applet-tooling--gate-failure (gate err)
  "Return a stable diagnostic record for GATE's signaled condition ERR."
  (list :gate gate
        :condition (format "%s" (car-safe err))
        :message (error-message-string err)))

(defun jetpacs-applet-tooling--check-runtime-gates (surface spec analysis client)
  "Run Jetpacs' real non-sending gates for SURFACE and SPEC.
ANALYSIS is the shell's one-pass structural artifact.  CLIENT is nil in an
offline runtime, where only gates independent of negotiated welcome data can
run.  Return failures in the same named gate vocabulary used by the shell."
  (let ((root (jetpacs-applet-tooling--runtime-root-id surface))
        failures)
    (unless root
      (error "Jetpacs Applet Tooling: no live root registered for surface %s" surface))
    (cl-labels ((check (name thunk)
                  (condition-case err
                      (funcall thunk)
                    (error (push (jetpacs-applet-tooling--gate-failure name err)
                                 failures)))))
      (if client
          (progn
            (check "gate-1.profile"
                   (lambda ()
                     (jetpacs-shell--gate-spec
                      client root spec nil analysis nil)))
            (check "gate-1.variant"
                   (lambda () (jetpacs-shell--gate-variants spec analysis)))
            (check "gate-3.surface-capability"
                   (lambda ()
                     (jetpacs-shell--gate-capability client root)))
            (check "gate-4.amendments"
                   (lambda ()
                     (jetpacs-shell--gate-amendments client spec analysis)))
            (check "gate-5.size"
                   (lambda ()
                     (jetpacs-shell--gate-size
                      client spec nil analysis nil)))
            (check "gate-1d.identity"
                   (lambda ()
                     (jetpacs-shell--gate-ids spec nil analysis nil))))
        ;; These two gates depend only on the datum and fixed contract limits,
        ;; so they remain authoritative even without a Companion session.
        (check "gate-1.variant"
               (lambda () (jetpacs-shell--gate-variants spec analysis)))
        (check "gate-1d.identity"
               (lambda () (jetpacs-shell--gate-ids spec nil analysis nil)))))
    (nreverse failures)))

(defun jetpacs-applet-tooling-check-render-determinism (surface &optional include-canonical)
  "Build runtime SURFACE twice and return bounded deterministic evidence.
The chosen applet has already been explicitly trusted and loaded.  This calls
its builder twice; equality is evidence for this exact current context, not a
proof over future state, capabilities, time, randomness, or I/O.  When
INCLUDE-CANONICAL is non-nil, append the first canonical EBP JSON payload."
  (jetpacs-applet-tooling--runtime-required)
  (unless (and (stringp surface) (not (string-empty-p surface)))
    (error "Jetpacs Applet Tooling: surface must be a non-empty string"))
  (let ((failures nil))
    ;; A second lexical scope is required: `let' bindings are simultaneous,
    ;; so constructing this closure beside FAILURES would capture no binding.
    (let* ((jetpacs-shell-builder-error-functions
            (list (lambda (context err)
                    (push (list :context context
                                :symbol (format "%s" (if (consp err)
                                                          (car err)
                                                        err))
                                :message (if (consp err)
                                             (error-message-string err)
                                           (format "%s" err)))
                          failures))))
           (first (jetpacs-devtools-capture-spec surface))
           (second (jetpacs-devtools-capture-spec surface)))
      (unless first
        (error "Jetpacs Applet Tooling: no live root registered for surface %s" surface))
      (let* ((first-json (jetpacs-node->canonical-json first))
             (second-json (jetpacs-node->canonical-json second))
             (analysis (jetpacs-shell--analyze-spec first))
             (counts (plist-get analysis :counts))
             (fixed-issues (jetpacs-applet-tooling--render-fixed-issues analysis))
             (client (jetpacs-client))
             (connected (and client (jetpacs-connected-p)))
             (gate-failures
              (jetpacs-applet-tooling--check-runtime-gates
               surface first analysis (and connected client)))
             (stable (and (equal first-json second-json)
                          (null failures)))
             (limits (and connected (ebp-client-limits client)))
             (max-frame (and limits (plist-get limits :max_frame_bytes)))
             (wire-bytes (jetpacs-node-wire-bytes first))
             (frame-ok (and (integerp max-frame)
                            (<= (+ wire-bytes
                                   jetpacs-shell--frame-headroom)
                                max-frame)))
             (fixed-limits
              (list :frame-headroom jetpacs-shell--frame-headroom
                    :max-node-depth jetpacs-shell--max-node-depth
                    :max-nodes jetpacs-shell--max-nodes
                    :max-children jetpacs-shell--max-children
                    :max-variants-per-host
                    jetpacs-shell--max-variants-per-host))
             (resolved-surface (jetpacs-applet-tooling--runtime-root-id surface))
             (registration-source
              (jetpacs-applet-tooling--runtime-source "surface" resolved-surface))
             (context
              (list :mode (if connected 'connected 'offline)
                    :surface resolved-surface
                    :registration-source registration-source
                    :emacs-version emacs-version
                    :window (and client (jetpacs-window))
                    :fixed-limits fixed-limits
                    :limits limits
                    :granted (and connected (ebp-client-granted client))))
             (status
              (cond
               ((or failures (not stable)) "NONDETERMINISTIC-OR-BUILDER-FAILED")
               ((or fixed-issues gate-failures) "BOUNDS-OR-GATE-FAIL")
               (t "STABLE"))))
        (jetpacs-applet-tooling--cap
         (concat
          (format "Jetpacs render check: %s\n" surface)
          (format "Status: %s\n" status)
          (format "Resolved surface: %s; registration source: %s\n"
                  resolved-surface (or registration-source "unknown"))
          (format "Context: %s; window=%S\n"
                  (if connected
                      "connected negotiated session"
                    (concat "offline builder fallbacks (compact/medium window; "
                            "no negotiated profile)"))
                  (and client (jetpacs-window)))
          (format "Context SHA-256: %s\n"
                  (secure-hash 'sha256
                               (jetpacs-applet-tooling--canonical-sexp-string context)))
          (format "Canonical SHA-256 #1: %s\n"
                  (secure-hash 'sha256 first-json))
          (format "Canonical SHA-256 #2: %s\n"
                  (secure-hash 'sha256 second-json))
          (format "Canonical outputs equal: %s\n"
                  (if (equal first-json second-json) "yes" "NO"))
          (format "Wire payload bytes: %d\n" wire-bytes)
          (format "Runtime fixed limits: %S\n" fixed-limits)
          (if (integerp max-frame)
              (format (concat "Negotiated max_frame_bytes: %d; "
                              "%d-byte-headroom check: %s\n")
                      max-frame jetpacs-shell--frame-headroom
                      (if frame-ok "pass" "FAIL"))
            "Negotiated frame bound: unavailable offline\n")
          (format "Counts: %S\n" counts)
          (format "Node types: %s\n"
                  (string-join (sort (copy-sequence
                                      (plist-get analysis :types)) #'string<)
                               ", "))
          (format "Node IDs (%d): %s\n"
                  (length (plist-get analysis :ids))
                  (string-join (sort (copy-sequence
                                      (plist-get analysis :ids)) #'string<)
                               ", "))
          (format "Fixed structural bounds: %s%s\n"
                  (if fixed-issues "FAIL" "pass")
                  (if fixed-issues
                      (concat " — " (string-join fixed-issues "; ")) ""))
          (format "Real Jetpacs gates (%s): %s%s\n"
                  (if connected
                      "full negotiated, non-sending"
                    "offline variant + identity")
                  (if gate-failures "FAIL" "pass")
                  (if gate-failures
                      (concat "\n" (jetpacs-applet-tooling--stable-pp-string
                                    gate-failures)) ""))
          (format "Builder failures captured: %d%s\n"
                  (length failures)
                  (if failures
                      (concat "\n" (jetpacs-applet-tooling--stable-pp-string
                                    (nreverse failures))) ""))
          "\nInterpretation: two equal builds establish repeatability only for this context; Emacs application state is an implicit input. In a connected session this report invokes the real non-sending profile, capability, amendment, aggregate, frame, variant, and identity gates. The normal push path remains authoritative for the final send and device acceptance.\n"
          (if include-canonical
              (concat "\nCanonical EBP JSON:\n" first-json "\n")
            "")))))))

;;;; Static applet validation

(defun jetpacs-applet-tooling--issue (severity code message &optional path line)
  "Build one SEVERITY/CODE validation issue with MESSAGE, PATH, and LINE."
  (list :severity severity :code code :message message :path path :line line))

(defun jetpacs-applet-tooling--calls-in-form (form names &optional allowed-wrappers)
  "Return members of NAMES called directly in FORM.
A call nested beneath a form whose head is in ALLOWED-WRAPPERS is omitted."
  (let (found)
    (jetpacs-applet-tooling--walk-form
     form (lambda (node ancestors)
            (when (and (memq (car-safe node) names)
                       (not (seq-some
                             (lambda (ancestor)
                               (memq (car-safe ancestor) allowed-wrappers))
                             ancestors)))
              (cl-pushnew (car node) found))))
    (nreverse found)))

(defun jetpacs-applet-tooling-validate-applet (path)
  "Statically validate applet file or directory PATH.
The check never loads the applet.  It covers file hygiene, owner scoping,
action identifier grammar and documentation, public definition docstrings,
app/root presence, remote action provider reconciliation, and direct
prompt/blocking calls inside registered handlers."
  (let* ((files (jetpacs-applet-tooling--applet-files path))
         (scans (mapcar #'jetpacs-applet-tooling--scan-applet-file files))
         (reconciliation
          (jetpacs-applet-tooling--action-reconciliation
           scans (jetpacs-applet-tooling--platform-scans)))
         (actions (plist-get reconciliation :registrations))
         (action-emissions (plist-get reconciliation :emissions))
         (apps (apply #'append (mapcar (lambda (s) (plist-get s :apps)) scans)))
         (roots (apply #'append (mapcar (lambda (s) (plist-get s :roots)) scans)))
         (owners (delete-dups (apply #'append
                                     (mapcar (lambda (s) (plist-get s :owners)) scans))))
         (definitions (apply #'append
                             (mapcar (lambda (s) (plist-get s :definitions)) scans)))
         issues)
    (dolist (scan scans)
      (let* ((file (jetpacs-applet-tooling-resolve-file
                    (plist-get scan :path)))
             (text (with-temp-buffer
                     (insert-file-contents file)
                     (buffer-string))))
        (unless (string-match-p "lexical-binding:[[:space:]]*t" text)
          (push (jetpacs-applet-tooling--issue 'error "file.lexical-binding"
                                    "File must declare lexical-binding: t."
                                    (plist-get scan :path) 1)
                issues))
        (unless (string-match-p "Package-Requires:" text)
          (push (jetpacs-applet-tooling--issue 'warning "file.package-requires"
                                    "Declare the Emacs 30.1 package floor."
                                    (plist-get scan :path) 1)
                issues))
        (unless (plist-get scan :provides)
          (push (jetpacs-applet-tooling--issue 'error "file.provide"
                                    "Every applet library must provide a feature."
                                    (plist-get scan :path) nil)
                issues))
        (dolist (record (plist-get scan :records))
          (when-let* ((message (plist-get record :parse-error)))
            (push (jetpacs-applet-tooling--issue 'error "file.read"
                                      message (plist-get record :path)
                                      (plist-get record :line))
                  issues))
          (when (and (memq (plist-get record :kind)
                           '(defun cl-defun defmacro cl-defmacro defsubst))
                     (jetpacs-applet-tooling--public-record-p record)
                     (null (plist-get record :doc)))
            (push (jetpacs-applet-tooling--issue
                   'warning "definition.doc"
                   (format "Public definition %s has no docstring; its contract cannot appear in generated documentation."
                           (plist-get record :name))
                   (plist-get record :path) (plist-get record :line))
                  issues)))))
    (unless owners
      (push (jetpacs-applet-tooling--issue 'error "applet.owner"
                                "No with-jetpacs-owner registration scope found.")
            issues))
    (dolist (owner owners)
      (unless (string-match-p jetpacs-applet-tooling--identifier-re owner)
        (push (jetpacs-applet-tooling--issue 'error "owner.identifier"
                                  (format "Owner %S is not an EBP identifier." owner))
              issues))
      (when (string-prefix-p "jetpacs." owner)
        (push (jetpacs-applet-tooling--issue 'warning "owner.reserved"
                                  (format "Owner %S uses the platform-reserved jetpacs. prefix." owner))
              issues)))
    (unless apps
      (push (jetpacs-applet-tooling--issue 'warning "applet.defapp"
                                "No jetpacs-defapp identity found; validate the complete applet directory if this is a module.")
            issues))
    (unless roots
      (push (jetpacs-applet-tooling--issue 'warning "applet.root"
                                "No jetpacs-chrome-define-root registration found.")
            issues))
    (dolist (name (plist-get reconciliation :unresolved-names))
      (when-let* ((emission
                   (seq-find (lambda (record)
                               (equal name (plist-get record :name)))
                             action-emissions)))
        (push
         (jetpacs-applet-tooling--issue
          'error "action.unresolved-provider"
          (format (concat
                   "Emitted remote action %S has no jetpacs-defaction "
                   "provider in this applet or current Jetpacs platform source.")
                  name)
          (plist-get emission :path) (plist-get emission :line))
         issues)))
    (dolist (action actions)
      (let ((name (plist-get action :name)))
        (cond
         ((null name)
          (push (jetpacs-applet-tooling--issue 'warning "action.dynamic-name"
                                    "Dynamic action name cannot be validated statically."
                                    (plist-get action :path) (plist-get action :line))
                issues))
         ((or (not (string-match-p jetpacs-applet-tooling--identifier-re name))
              (not (string-search "." name)))
          (push (jetpacs-applet-tooling--issue 'error "action.identifier"
                                    (format "Action %S must be a dotted EBP identifier." name)
                                    (plist-get action :path) (plist-get action :line))
                issues)))
        (unless (plist-get action :owned)
          (push (jetpacs-applet-tooling--issue 'error "action.owner-scope"
                                    (format "Action %s is not registered inside with-jetpacs-owner."
                                            (or name "<dynamic>"))
                                    (plist-get action :path) (plist-get action :line))
                issues))
        (unless (and (stringp (plist-get action :doc))
                     (not (string-empty-p (plist-get action :doc))))
          (push (jetpacs-applet-tooling--issue
                 'warning "action.doc"
                 (format "Action %s has no literal :doc; agents and action editors cannot explain it."
                         (or name "<dynamic>"))
                 (plist-get action :path) (plist-get action :line))
                issues))
        (when-let* ((handler (plist-get action :handler)))
          (unless (assoc handler definitions)
            (push (jetpacs-applet-tooling--issue
                   'warning "action.handler-definition"
                   (format "Handler %s is not defined in this applet path; its implementation and docstring cannot be checked."
                           handler)
                   (plist-get action :path) (plist-get action :line))
                  issues)))
        (when-let* ((handler (plist-get action :handler))
                    (definition (assoc handler definitions)))
          (unless (jetpacs-applet-tooling--form-doc (cdr definition))
            (push (jetpacs-applet-tooling--issue
                   'warning "action.handler-doc"
                   (format "Handler %s has no docstring." handler)
                   (plist-get action :path) (plist-get action :line))
                  issues))
          (let ((prompts (jetpacs-applet-tooling--calls-in-form
                          (cdr definition) jetpacs-applet-tooling--prompting-functions
                          '(jetpacs-flow-continue)))
                (blocking (jetpacs-applet-tooling--calls-in-form
                           (cdr definition) jetpacs-applet-tooling--blocking-functions)))
            (when prompts
              (push (jetpacs-applet-tooling--issue
                     'error "action.prompt-in-dispatch"
                     (format "Handler %s directly calls %s; move interaction into jetpacs-flow-continue."
                             handler (mapconcat #'symbol-name prompts ", "))
                     (plist-get action :path) (plist-get action :line))
                    issues))
            (when blocking
              (push (jetpacs-applet-tooling--issue
                     'warning "action.blocking-call"
                     (format "Handler %s directly calls %s; dispatch must return promptly."
                             handler (mapconcat #'symbol-name blocking ", "))
                     (plist-get action :path) (plist-get action :line))
                    issues))))))
    (let* ((issues (nreverse issues))
           (errors (seq-count (lambda (issue)
                                (eq (plist-get issue :severity) 'error))
                              issues))
           (warnings (- (length issues) errors))
           (status (if (> errors 0) "FAIL" (if (> warnings 0) "WARN" "PASS"))))
      (jetpacs-applet-tooling--cap
       (concat
        (format "Jetpacs applet validation: %s\nStatus: %s (%d error%s, %d warning%s)\n"
                path status errors (if (= errors 1) "" "s")
                warnings (if (= warnings 1) "" "s"))
        (format (concat "Inventory: %d file%s, %d owner%s, %d app%s, %d root%s, "
                        "%d registered action%s, %d emitted literal name%s, "
                        "%d dynamic emission site%s, %d unresolved name%s\n")
                (length files) (if (= (length files) 1) "" "s")
                (length owners) (if (= (length owners) 1) "" "s")
                (length apps) (if (= (length apps) 1) "" "s")
                (length roots) (if (= (length roots) 1) "" "s")
                (length actions) (if (= (length actions) 1) "" "s")
                (length (plist-get reconciliation :emitted-names))
                (if (= (length (plist-get reconciliation :emitted-names)) 1)
                    "" "s")
                (length (plist-get reconciliation :dynamic-emissions))
                (if (= (length (plist-get reconciliation :dynamic-emissions)) 1)
                    "" "s")
                (length (plist-get reconciliation :unresolved-names))
                (if (= (length (plist-get reconciliation :unresolved-names)) 1)
                    "" "s"))
        (if issues
            (concat "\n"
                    (mapconcat
                     (lambda (issue)
                       (format "[%s] %s%s%s — %s"
                               (upcase (symbol-name (plist-get issue :severity)))
                               (plist-get issue :code)
                               (if-let* ((issue-path (plist-get issue :path)))
                                   (concat " " issue-path) "")
                               (if-let* ((line (plist-get issue :line)))
                                   (format ":%d" line) "")
                               (plist-get issue :message)))
                     issues "\n"))
          "\nNo static issues found."))))))

;;;; Deterministic applet scaffolding

(defun jetpacs-applet-tooling--applet-template-forms (id label icon)
  "Return the applet scaffold as literal Elisp forms.
ID, LABEL, and ICON have already passed their wire-level checks."
  (let* ((feature-name (replace-regexp-in-string "[.:/]" "-" id))
         (feature (intern feature-name))
         (owner (intern (concat feature-name "-owner")))
         (view (intern (concat feature-name "--view")))
         (handler (intern (concat feature-name "--on-refresh")))
         (register (intern (concat feature-name "-register")))
         (unregister (intern (concat feature-name "-unregister")))
         (action (concat id ".refresh")))
    (list
     '(require 'jetpacs-apps)
     '(require 'jetpacs-chrome)
     '(require 'jetpacs-shell)
     '(require 'jetpacs-surfaces)
     '(require 'jetpacs-widgets)
     `(defconst ,owner ,id "Stable owner and app identity on the EBP wire.")
     `(defun ,view ()
        "Build the applet's root screen without side effects."
        (jetpacs-chrome-screen
         ,label
         (jetpacs-column
          (jetpacs-text ,(format "Hello from %s" label)
                        :style "headline")
          (jetpacs-button "Refresh" (jetpacs-action ,action))
          :spacing 16)))
     `(defun ,handler (_args params)
        "Queue a refresh using PARAMS, then acknowledge the action immediately."
        (jetpacs-app-defer-refresh params)
        'accepted)
     `(defun ,register ()
        "Register this applet idempotently."
        (with-jetpacs-owner ,owner
          (jetpacs-chrome-define-root
           ,owner "home" (lambda (_back) (,view)))
          (jetpacs-defaction
           ,action #',handler :doc "Refresh the applet's root surface"))
        (jetpacs-defapp ,owner
                        :label ,label
                        :icon ,icon
                        :surfaces (list ,owner)))
     `(defun ,unregister ()
        "Remove this applet's live registrations."
        (jetpacs-undefaction ,action)
        (jetpacs-apps-unregister ,owner)
        (jetpacs-chrome-remove ,owner))
     `(,register)
     `(provide ',feature))))

(defun jetpacs-applet-tooling--read-forms-from-string (source)
  "Read and return every top-level form in SOURCE, without evaluating any."
  (with-temp-buffer
    (insert source)
    (goto-char (point-min))
    (let (forms done)
      (while (not done)
        (condition-case nil
            (progn
              (skip-chars-forward " \t\r\n")
              (forward-comment (point-max))
              (if (eobp)
                  (setq done t)
                (push (read (current-buffer)) forms)))
          (end-of-file (setq done t))))
      (nreverse forms))))

(defun jetpacs-applet-tooling-applet-template (id label &optional icon)
  "Return a minimal lifecycle-complete applet using literal ID and LABEL.
ICON defaults to `extension'.  The readable source serialization is checked
against literal forms before return, so generation is deterministic and
homoiconic; no file is written or evaluated."
  (unless (and (stringp id) (string-match-p jetpacs-applet-tooling--identifier-re id)
               (not (string-prefix-p "jetpacs." id)))
    (error "Jetpacs Applet Tooling: applet id must be a non-reserved EBP identifier"))
  (unless (and (stringp label) (not (string-empty-p label)))
    (error "Jetpacs Applet Tooling: label must be a non-empty string"))
  (let* ((feature (replace-regexp-in-string "[.:/]" "-" id))
         (prefix feature)
         (action (concat id ".refresh"))
         (icon (or icon "extension")))
    (unless (string-match-p jetpacs-applet-tooling--identifier-re icon)
      (error "Jetpacs Applet Tooling: icon must be an EBP identifier"))
    (let* ((forms (jetpacs-applet-tooling--applet-template-forms id label icon))
           (source
            (format ";;; %s.el --- %s for Jetpacs -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later
;; Package-Requires: ((emacs \"30.1\"))

;;; Commentary:

;; A Tier-1 Jetpacs applet.  Application state remains in Emacs; this file
;; declares a native surface and handles semantic actions from the device.

;;; Code:

(require 'jetpacs-apps)
(require 'jetpacs-chrome)
(require 'jetpacs-shell)
(require 'jetpacs-surfaces)
(require 'jetpacs-widgets)

(defconst %s-owner %S
  \"Stable owner and app identity on the EBP wire.\")

(defun %s--view ()
  \"Build the applet's root screen without side effects.\"
  (jetpacs-chrome-screen
   %S
   (jetpacs-column
    (jetpacs-text %S :style \"headline\")
    (jetpacs-button
     \"Refresh\"
     (jetpacs-action %S))
    :spacing 16)))

(defun %s--on-refresh (_args params)
  \"Queue a refresh using PARAMS, then acknowledge the action immediately.\"
  (jetpacs-app-defer-refresh params)
  'accepted)

(defun %s-register ()
  \"Register this applet idempotently.\"
  (with-jetpacs-owner %s-owner
    (jetpacs-chrome-define-root %s-owner \"home\"
                                (lambda (_back) (%s--view)))
    (jetpacs-defaction %S #'%s--on-refresh
                       :doc \"Refresh the applet's root surface\"))
  (jetpacs-defapp %s-owner
                  :label %S
                  :icon %S
                  :surfaces (list %s-owner)))

(defun %s-unregister ()
  \"Remove this applet's live registrations.\"
  (jetpacs-undefaction %S)
  (jetpacs-apps-unregister %s-owner)
  (jetpacs-chrome-remove %s-owner))

(%s-register)

(provide '%s)
;;; %s.el ends here
"
                    feature label prefix id prefix label
                    (format "Hello from %s" label) action prefix prefix
                    prefix prefix prefix action prefix prefix label icon prefix
                    prefix action prefix prefix prefix feature feature)))
      (unless (equal forms (jetpacs-applet-tooling--read-forms-from-string source))
        (error "Jetpacs Applet Tooling: internal scaffold serializer drifted from its forms"))
      source)))

(provide 'jetpacs-applet-tooling)
;;; jetpacs-applet-tooling.el ends here
