;;; jetpacs-applet-registry.el --- Build, validate and check an applet registry -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later

;;; Commentary:

;; The publisher's side of the applet registry (emacs/jetpacs-registry.el
;; is the installer's).  A SOURCE index names applet directories:
;;
;;   {"format":1,"name":"jetpacs","homepage":"…",
;;    "items":[{"path":"../examples/hello"}],
;;    "include":["sub/registry.json"]}
;;
;; Each directory carries its source and a `jetpacs-applet.json' of
;; authored keys (version, title, description, categories, homepage, docs,
;; registry_dependencies, name, main).  `build' derives the rest from the
;; applet manifest the tooling already computes — every file's sha256,
;; the required features (requires minus provides), the extensions the
;; `jetpacs-defapp' declares, the Package-Requires header — runs the static
;; validator (an error fails the build), inlines every file's content and
;; writes `<out>/<name>.json' plus a `registry.json' index with content
;; stripped, the way shadcn's `build' does (ebp/slop-docs/SHADCN-harvest.md
;; §5).  `check' pins the committed output byte for byte.
;;
;; The batch tool (tools/jetpacs-registry.sh) also drives the installer
;; from here: resolve, dry-run, diff and install, with the fetched Elisp
;; validated statically from a staging directory before any write.  No
;; subcommand loads an applet.

;;; Code:

(require 'cl-lib)
(require 'seq)
(require 'subr-x)
(require 'json)
(require 'jetpacs-applet-tooling)
(require 'jetpacs-applet-catalog)
(require 'jetpacs-registry)

(defconst jetpacs-applet-registry-format 1
  "The source index, item and published index format.")

(defconst jetpacs-applet-registry-max-include-depth 32
  "How deep `include' may nest.")

(defcustom jetpacs-applet-registry-source "jetpacs-applet-mcp/registry/registry.json"
  "Workspace-relative source index of the repository's own registry."
  :type 'string :group 'jetpacs-registry)

(defcustom jetpacs-applet-registry-output "jetpacs-applet-mcp/registry/public/r"
  "Workspace-relative directory the repository's registry is built into."
  :type 'string :group 'jetpacs-registry)

(defconst jetpacs-applet-registry--authored-keys
  '("name" "version" "title" "description" "categories" "homepage" "docs"
    "registry_dependencies" "main")
  "The keys `jetpacs-applet.json' may carry.")

(defconst jetpacs-applet-registry--index-keys '("format" "name" "homepage" "items" "include")
  "The keys a source index may carry.")

;;;; The source index

(defun jetpacs-applet-registry--read-json-file (file)
  "Parse FILE as JSON with objects as hash tables and arrays as vectors."
  (unless (file-readable-p file)
    (signal 'jetpacs-registry-error (list "no such registry file" file)))
  (jetpacs-registry-parse-json
   (with-temp-buffer (insert-file-contents file) (buffer-string))))

(defun jetpacs-applet-registry--relative-only (path what file)
  "Signal unless PATH is relative; WHAT and FILE name the offender."
  (when (or (file-name-absolute-p path) (string-prefix-p "~" path))
    (signal 'jetpacs-registry-error
            (list (format "%s %S in %s must be relative" what path file)))))

(defun jetpacs-applet-registry-read-source (registry-file &optional depth seen)
  "The source index at REGISTRY-FILE with includes resolved.
Returns a plist (:name :homepage :items), each item a plist (:path
ABSOLUTE-DIRECTORY :source INDEX-FILE).  Includes are relative only, nest
at most `jetpacs-applet-registry-max-include-depth' deep and may not repeat."
  (let* ((file (file-truename (expand-file-name registry-file)))
         (depth (or depth 0))
         (index (jetpacs-applet-registry--read-json-file file))
         (dir (file-name-directory file)))
    (when (> depth jetpacs-applet-registry-max-include-depth)
      (signal 'jetpacs-registry-error (list "registry include tree is too deep" file)))
    (when (member file seen)
      (signal 'jetpacs-registry-error (list "registry includes itself" file)))
    (jetpacs-registry--check-keys index jetpacs-applet-registry--index-keys "registry")
    (unless (eql (gethash "format" index) jetpacs-applet-registry-format)
      (signal 'jetpacs-registry-error
              (list (format "registry format must be %d" jetpacs-applet-registry-format) file)))
    (when (= depth 0)
      (unless (and (stringp (gethash "name" index)) (not (string-empty-p (gethash "name" index))))
        (signal 'jetpacs-registry-error (list "registry needs a name" file))))
    (let ((items (gethash "items" index))
          (includes (gethash "include" index))
          result)
      (unless (or (vectorp items) (vectorp includes))
        (signal 'jetpacs-registry-error (list "registry needs items or include" file)))
      (when (vectorp items)
        (seq-doseq (entry items)
          (jetpacs-registry--check-keys entry '("path") "registry item")
          (let ((path (gethash "path" entry)))
            (unless (stringp path)
              (signal 'jetpacs-registry-error (list "registry item needs a path" file)))
            (jetpacs-applet-registry--relative-only path "item path" file)
            (push (list :path (file-truename (expand-file-name path dir)) :source file)
                  result))))
      (when (vectorp includes)
        (seq-doseq (include includes)
          (unless (stringp include)
            (signal 'jetpacs-registry-error (list "include entries are paths" file)))
          (jetpacs-applet-registry--relative-only include "include" file)
          (let ((nested (jetpacs-applet-registry-read-source
                         (expand-file-name include dir) (1+ depth) (cons file seen))))
            (setq result (append (reverse (plist-get nested :items)) result)))))
      (setq result (nreverse result))
      (let ((paths (mapcar (lambda (item) (plist-get item :path)) result)))
        (when (/= (length paths) (length (delete-dups (copy-sequence paths))))
          (signal 'jetpacs-registry-error (list "registry lists an applet twice" file))))
      (list :name (gethash "name" index)
            :homepage (gethash "homepage" index)
            :items result))))

;;;; Building one item

(defun jetpacs-applet-registry--authored (dir)
  "The authored `jetpacs-applet.json' of applet DIR, checked."
  (let* ((file (expand-file-name "jetpacs-applet.json" dir))
         (authored (jetpacs-applet-registry--read-json-file file)))
    (jetpacs-registry--check-keys authored jetpacs-applet-registry--authored-keys
                                  "jetpacs-applet.json")
    (dolist (key '("version" "title" "description"))
      (unless (and (stringp (gethash key authored)) (not (string-empty-p (gethash key authored))))
        (signal 'jetpacs-registry-error
                (list (format "jetpacs-applet.json needs a non-empty %s" key) file))))
    authored))

(defun jetpacs-applet-registry--package-requires (file)
  "The `Package-Requires:' header of FILE as a vector of [name version], or nil."
  (with-temp-buffer
    (insert-file-contents file nil 0 4000)
    (goto-char (point-min))
    (when (re-search-forward "^;; Package-Requires: *\\((.*)\\) *$" nil t)
      (let ((form (ignore-errors (car (read-from-string (match-string 1))))))
        (when (proper-list-p form)
          (vconcat
           (mapcar (lambda (entry)
                     (unless (and (consp entry) (symbolp (car entry))
                                  (stringp (cadr entry)))
                       (signal 'jetpacs-registry-error
                               (list "malformed Package-Requires entry" entry file)))
                     (vector (symbol-name (car entry)) (cadr entry)))
                   form)))))))

(defun jetpacs-applet-registry--workspace-relative (path)
  "PATH relative to the workspace root, for the tooling's path-taking entry points."
  (let ((workspace (jetpacs-applet-tooling-workspace-root))
        (true (file-truename path)))
    (unless (file-in-directory-p true workspace)
      (signal 'jetpacs-registry-error
              (list "a registry's applet must live inside the workspace" path)))
    (directory-file-name (file-relative-name true workspace))))

(defun jetpacs-applet-registry--strings (value)
  "VALUE (a vector or list of strings, or nil) as a sorted, deduplicated list."
  (sort (delete-dups (mapcar #'identity (append value nil))) #'string<))

(cl-defun jetpacs-applet-registry-build-item (dir &key skip-validation)
  "Build the item of applet directory DIR; a hash table in item key order.
Derived from the applet manifest and the authored `jetpacs-applet.json';
the static validator runs unless SKIP-VALIDATION and an error fails the
build.  Signals `jetpacs-registry-error' on anything wrong."
  (let* ((authored (jetpacs-applet-registry--authored dir))
         (relative (jetpacs-applet-registry--workspace-relative dir))
         (files (jetpacs-applet-tooling--applet-files relative))
         (manifest (jetpacs-applet-tooling--applet-manifest-data relative))
         (apps (append (plist-get manifest :apps) nil))
         (name (or (gethash "name" authored)
                   (and (= (length apps) 1) (plist-get (car apps) :id))))
         (basenames (mapcar #'file-name-nondirectory files))
         (main (or (gethash "main" authored)
                   (and (= (length files) 1) (car basenames))
                   (and name (member (concat name ".el") basenames) (concat name ".el"))))
         (item (make-hash-table :test #'equal)))
    (unless name
      (signal 'jetpacs-registry-error
              (list (format "%s registers %d apps; name the item in jetpacs-applet.json"
                            relative (length apps)))))
    (unless (and main (member main basenames))
      (signal 'jetpacs-registry-error
              (list (format "%s: name the main file in jetpacs-applet.json" relative))))
    (when (/= (length basenames) (length (delete-dups (copy-sequence basenames))))
      (signal 'jetpacs-registry-error
              (list (format "%s: file basenames must be unique (installs are flat)" relative))))
    (unless skip-validation
      (let ((report (jetpacs-applet-tooling-validate-applet relative)))
        (when (string-match-p "^Status: FAIL" report)
          (signal 'jetpacs-registry-error (list (format "%s fails validation:\n%s" relative report))))))
    (puthash "format" jetpacs-applet-registry-format item)
    (puthash "name" name item)
    (puthash "version" (gethash "version" authored) item)
    (puthash "title" (gethash "title" authored) item)
    (puthash "description" (gethash "description" authored) item)
    (when-let* ((v (gethash "categories" authored))) (puthash "categories" v item))
    (when-let* ((v (gethash "homepage" authored))) (puthash "homepage" v item))
    (when-let* ((v (gethash "docs" authored))) (puthash "docs" v item))
    (puthash "files"
             (vconcat
              (mapcar (lambda (file)
                        (let ((entry (make-hash-table :test #'equal))
                              (content (with-temp-buffer
                                         (insert-file-contents file)
                                         (buffer-string))))
                          (puthash "path" (file-name-nondirectory file) entry)
                          (puthash "sha256" (jetpacs-registry-sha256 content) entry)
                          (puthash "content" content entry)
                          entry))
                      files))
             item)
    (when-let* ((requires (jetpacs-applet-registry--package-requires
                           (seq-find (lambda (f) (equal (file-name-nondirectory f) main)) files))))
      (puthash "package_requires" requires item))
    (when-let* ((v (gethash "registry_dependencies" authored)))
      (puthash "registry_dependencies" v item))
    (let ((extensions (apply #'append
                             (mapcar (lambda (app)
                                       (append (plist-get app :requires-extensions) nil))
                                     apps))))
      (when extensions
        (puthash "requires_extensions" (vconcat (jetpacs-applet-registry--strings extensions)) item)))
    (let* ((file-records (append (plist-get manifest :files) nil))
           (requires (apply #'append
                            (mapcar (lambda (r) (append (plist-get r :requires) nil)) file-records)))
           (provides (apply #'append
                            (mapcar (lambda (r) (append (plist-get r :provides) nil)) file-records)))
           (needed (seq-difference (jetpacs-applet-registry--strings requires)
                                   (jetpacs-applet-registry--strings provides))))
      (when needed
        (puthash "requires_features" (vconcat needed) item)))
    (puthash "applet_manifest_sha256" (plist-get manifest :sha256) item)
    (jetpacs-registry-check-item item)))

(defun jetpacs-applet-registry--stripped (item)
  "ITEM without file contents, for the published index."
  (let ((copy (copy-hash-table item)))
    (puthash "files"
             (vconcat (mapcar (lambda (file)
                                (let ((entry (copy-hash-table file)))
                                  (remhash "content" entry)
                                  entry))
                              (gethash "files" item)))
             copy)
    copy))

(defun jetpacs-applet-registry--json-line (value)
  "VALUE as one line of JSON plus a newline, decoded to multibyte."
  (let ((text (json-serialize value :false-object :json-false :null-object :json-null)))
    (concat (if (multibyte-string-p text) text (decode-coding-string text 'utf-8)) "\n")))

;;;; Build, validate, check

(cl-defun jetpacs-applet-registry-build-data (registry-file &key skip-validation)
  "Every published file of the registry at REGISTRY-FILE as (NAME . TEXT).
NAME is the published basename (`<item>.json' and `registry.json')."
  (let* ((source (jetpacs-applet-registry-read-source registry-file))
         (items (mapcar (lambda (entry)
                          (jetpacs-applet-registry-build-item
                           (plist-get entry :path) :skip-validation skip-validation))
                        (plist-get source :items)))
         (names (mapcar (lambda (item) (gethash "name" item)) items))
         (index (make-hash-table :test #'equal)))
    (when (/= (length names) (length (delete-dups (copy-sequence names))))
      (signal 'jetpacs-registry-error (list "two applets build to one item name" names)))
    (dolist (item items)
      (seq-doseq (dependency (or (gethash "registry_dependencies" item) []))
        (let ((parsed (jetpacs-registry-parse-address dependency)))
          (when (and (eq (plist-get parsed :scheme) 'bare)
                     (not (member (plist-get parsed :item) names)))
            (signal 'jetpacs-registry-error
                    (list (format "%s depends on %s, which this registry does not publish"
                                  (gethash "name" item) dependency)))))))
    (puthash "format" jetpacs-applet-registry-format index)
    (puthash "name" (plist-get source :name) index)
    (when-let* ((homepage (plist-get source :homepage))) (puthash "homepage" homepage index))
    (puthash "items" (vconcat (mapcar #'jetpacs-applet-registry--stripped items)) index)
    (append (mapcar (lambda (item)
                      (cons (concat (gethash "name" item) ".json")
                            (jetpacs-applet-registry--json-line item)))
                    items)
            (list (cons "registry.json" (jetpacs-applet-registry--json-line index))))))

(defun jetpacs-applet-registry-build (registry-file out-dir)
  "Build the registry at REGISTRY-FILE into OUT-DIR; return the paths written."
  (let ((outputs (jetpacs-applet-registry-build-data registry-file)))
    (make-directory out-dir t)
    (mapcar (lambda (out)
              (let ((file (expand-file-name (car out) out-dir)))
                (with-temp-file file
                  (set-buffer-file-coding-system 'utf-8-unix)
                  (insert (cdr out)))
                file))
            outputs)))

(defun jetpacs-applet-registry-check (registry-file out-dir)
  "Non-nil when OUT-DIR holds exactly a fresh build of REGISTRY-FILE."
  (let ((outputs (jetpacs-applet-registry-build-data registry-file))
        stale)
    (dolist (out outputs)
      (let ((file (expand-file-name (car out) out-dir)))
        (unless (and (file-exists-p file)
                     (equal (with-temp-buffer (insert-file-contents file) (buffer-string))
                            (cdr out)))
          (push (car out) stale))))
    (when (file-directory-p out-dir)
      (dolist (file (directory-files out-dir nil "\\.json\\'"))
        (unless (assoc file outputs)
          (push (concat file " (not built from the source index)") stale))))
    (dolist (name (nreverse stale))
      (message "stale registry rendering: %s" name))
    (null stale)))

(defun jetpacs-applet-registry-validate (registry-file)
  "A report on the registry at REGISTRY-FILE: every item built and checked.
Never signals for an item's problems; the report lists them and ends in
`Status: PASS' or `Status: FAIL'."
  (let (lines (failures 0))
    (condition-case err
        (let ((outputs (jetpacs-applet-registry-build-data registry-file)))
          (dolist (out outputs)
            (push (format "- %s: %d bytes" (car out) (string-bytes (cdr out))) lines)))
      (jetpacs-registry-error
       (cl-incf failures)
       (push (format "[ERROR] %s" (string-join (mapcar (lambda (x) (format "%s" x)) (cdr err)) ": "))
             lines))
      (error
       (cl-incf failures)
       (push (format "[ERROR] %s" (error-message-string err)) lines)))
    (concat (format "Jetpacs applet registry validation: %s\nStatus: %s\n\n"
                    registry-file (if (> failures 0) "FAIL" "PASS"))
            (string-join (nreverse lines) "\n")
            "\n")))

;;;; Installing from the batch tool

(defun jetpacs-applet-registry-installed-extensions ()
  "The ids of the renderer extensions this checkout installs."
  (mapcar (lambda (entry)
            (gethash "extension" (jetpacs-applet-catalog--read-json (car entry))))
          jetpacs-applet-catalog-manifests))

(defun jetpacs-applet-registry-validate-fetched (item)
  "Statically validate fetched ITEM from a staging directory; a report string.
The files are written under a temporary reference root the tooling may
read, never under the workspace, and never loaded."
  (let* ((stage (make-temp-file "jetpacs-registry-stage" t))
         (dir (expand-file-name (gethash "name" item) stage)))
    (unwind-protect
        (progn
          (make-directory dir t)
          (seq-doseq (file (gethash "files" item))
            (with-temp-file (expand-file-name (gethash "path" file) dir)
              (set-buffer-file-coding-system 'utf-8-unix)
              (insert (gethash "content" file))))
          (let ((jetpacs-applet-tooling-reference-roots
                 (list (cons "registry-staging" (file-name-as-directory (file-truename stage))))))
            (with-environment-variables (("JETPACS_REFERENCE_ROOTS" ""))
              (jetpacs-applet-tooling-validate-applet
               (concat "registry-staging/" (gethash "name" item))))))
      (delete-directory stage t))))

(defun jetpacs-applet-registry-feature-available-p (feature items)
  "Non-nil when FEATURE is on the load path, in this checkout, or shipped by ITEMS.
The batch tool may run without the platform on `load-path'; the checkout's
`emacs/' and `emacs/apps/*/' hold the features an applet requires."
  (let ((workspace (jetpacs-applet-tooling-workspace-root))
        (file (concat feature ".el")))
    (or (locate-library feature)
        (file-exists-p (expand-file-name (concat "emacs/" file) workspace))
        (seq-some (lambda (dir) (file-exists-p (expand-file-name file dir)))
                  (ignore-errors
                    (directory-files (expand-file-name "emacs/apps" workspace) t
                                     directory-files-no-dot-files-regexp)))
        (seq-some (lambda (other)
                    (seq-some (lambda (f)
                                (equal (file-name-sans-extension (gethash "path" f)) feature))
                              (gethash "files" (cdr other))))
                  items))))

(defun jetpacs-applet-registry-resolve-report (addresses dir)
  "Resolve ADDRESSES for DIR: order, requirement issues, collisions, dry run.
A plist (:order :issues :collisions :plan :items), nothing written."
  (let* ((resolved (jetpacs-registry-resolve addresses))
         (items (plist-get resolved :items))
         (extensions (jetpacs-applet-registry-installed-extensions))
         issues)
    (dolist (entry items)
      (jetpacs-registry-verify-item (cdr entry))
      (jetpacs-registry-check-item-readable (cdr entry))
      (dolist (issue (jetpacs-registry-check-requirements
                      (cdr entry) :extensions extensions
                      :features-fn (lambda (feature)
                                     (jetpacs-applet-registry-feature-available-p
                                      feature items))))
        (push (format "%s: %s" (car entry) issue) issues))
      (let ((report (jetpacs-applet-registry-validate-fetched (cdr entry))))
        (when (string-match-p "^Status: FAIL" report)
          (push (format "%s fails static validation:\n%s" (car entry) report) issues))))
    (list :order (plist-get resolved :order)
          :items items
          :issues (nreverse issues)
          :collisions (jetpacs-registry-basename-collisions items dir)
          :plan (jetpacs-registry-dry-run items dir))))

(defun jetpacs-applet-registry--format-plan (report)
  "REPORT (from `jetpacs-applet-registry-resolve-report') as text."
  (concat
   (format "Order: %s\n" (string-join (plist-get report :order) " → "))
   (mapconcat (lambda (entry)
                (format "%-10s %s\n" (plist-get entry :action) (plist-get entry :path)))
              (plist-get report :plan) "")
   (if-let* ((collisions (plist-get report :collisions)))
       (concat "Collisions on the load path:\n"
               (mapconcat (lambda (c) (format "  %s already at %s\n" (car c) (cdr c))) collisions ""))
     "")
   (if-let* ((issues (plist-get report :issues)))
       (concat "Issues:\n" (mapconcat (lambda (i) (format "  %s\n" i)) issues ""))
     "")))

(cl-defun jetpacs-applet-registry-batch-install (addresses dir &key allow-overwrite)
  "Resolve, check and install ADDRESSES into DIR; the plan text.
Refuses when any issue or collision stands, or an overwrite is not
allowed.  Loads nothing."
  (let ((report (jetpacs-applet-registry-resolve-report addresses dir)))
    (when (or (plist-get report :issues) (plist-get report :collisions))
      (signal 'jetpacs-registry-error
              (list (concat "not installed:\n" (jetpacs-applet-registry--format-plan report)))))
    (jetpacs-registry-install-files (plist-get report :plan) :allow-overwrite allow-overwrite)
    (dolist (entry (plist-get report :items))
      (jetpacs-registry-lock-record (cdr entry) (car entry) nil))
    (jetpacs-applet-registry--format-plan report)))

(defun jetpacs-applet-registry-batch (argv)
  "Run one subcommand from the shell: ARGV is (SUBCOMMAND . ARGS).
Subcommands: build SOURCE OUT; validate SOURCE; check SOURCE OUT;
resolve ADDRESS... --dir DIR; dry-run ADDRESS... --dir DIR;
diff ADDRESS... --dir DIR; install ADDRESS... --dir DIR [--overwrite].
Prints a report and returns the exit status."
  (let* ((command (car argv))
         (args (cdr argv))
         (dir (or (cadr (member "--dir" args))
                  (expand-file-name "jetpacs/apps/" user-emacs-directory)))
         (overwrite (member "--overwrite" args))
         (addresses (let (acc (rest args))
                      (while rest
                        (pcase (car rest)
                          ("--dir" (setq rest (cddr rest)))
                          ("--overwrite" (setq rest (cdr rest)))
                          (a (push a acc) (setq rest (cdr rest)))))
                      (nreverse acc))))
    (condition-case err
        (pcase command
          ("build"
           (dolist (file (jetpacs-applet-registry-build (nth 0 args) (nth 1 args)))
             (princ (format "wrote %s\n" file)))
           0)
          ("validate"
           (let ((report (jetpacs-applet-registry-validate (nth 0 args))))
             (princ report)
             (if (string-match-p "^Status: PASS" report) 0 1)))
          ("check"
           (if (jetpacs-applet-registry-check (nth 0 args) (nth 1 args)) 0 1))
          ((or "resolve" "dry-run")
           (let ((report (jetpacs-applet-registry-resolve-report addresses dir)))
             (princ (jetpacs-applet-registry--format-plan report))
             (if (or (plist-get report :issues) (plist-get report :collisions)) 1 0)))
          ("diff"
           (let ((report (jetpacs-applet-registry-resolve-report addresses dir)))
             (dolist (entry (plist-get report :plan))
               (when (eq (plist-get entry :action) 'overwrite)
                 (princ (jetpacs-registry-diff-string entry))))
             0))
          ("install"
           (princ (jetpacs-applet-registry-batch-install
                   addresses dir :allow-overwrite (and overwrite t)))
           0)
          (_ (princ "usage: jetpacs-registry.sh {build SOURCE OUT|validate SOURCE|check SOURCE OUT|resolve|dry-run|diff|install ADDRESS... [--dir DIR] [--overwrite]}\n")
             2))
      (jetpacs-registry-error
       (princ (format "jetpacs-registry: %s\n"
                      (string-join (mapcar (lambda (x) (format "%s" x)) (cdr err)) ": ")))
       1)
      (error
       (princ (format "jetpacs-registry: %s\n" (error-message-string err)))
       1))))

(provide 'jetpacs-applet-registry)
;;; jetpacs-applet-registry.el ends here
