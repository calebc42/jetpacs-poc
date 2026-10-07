;;; jetpacs-registry.el --- Applet registry: addresses, fetch, verify, resolve, install -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later

;;; Commentary:

;; An applet registry is an index of installable Jetpacs applets — Emacs
;; Lisp files with declared requirements — the way shadcn/ui's registry is
;; an index of installable components (ebp/slop-docs/SHADCN-harvest.md §5).
;; This module is the device-side foundation: it parses item addresses,
;; fetches item JSON, verifies every file against its SHA-256, reads the
;; Elisp with a throwaway obarray, resolves dependencies, checks declared
;; requirements, classifies what an install would do, writes files and a
;; lockfile.  It LOADS NOTHING.  Loading is the App Store's consent-gated
;; action (jetpacs-app-store.el, "installing is running code"); the batch
;; tool's `install' subcommand never loads either.
;;
;; Wire: none.  The registry never reaches the Companion.  An installed
;; applet is gated at push time like a packaged one — `jetpacs-defapp
;; :requires-extensions' and the shell gates (SPEC 10.2) stay authoritative.
;;
;; Item JSON (format 1), as `jetpacs-applet-registry.el' builds it:
;;
;;   {"format":1,"name":"hello","version":"0.1.0","title":"Hello",
;;    "description":"…","categories":["example"],"homepage":"…","docs":"…",
;;    "files":[{"path":"hello.el","sha256":"<hex>","content":"…"}],
;;    "package_requires":[["emacs","30.1"]],
;;    "registry_dependencies":["@jetpacs/some-skin"],
;;    "requires_extensions":["jetpacs.components"],
;;    "requires_features":["jetpacs-apps","jetpacs-chrome"],
;;    "applet_manifest_sha256":"<hex>"}
;;
;; Addresses, the five shadcn schemes: a bare `hello' (the `@jetpacs'
;; namespace), `@ns/item', an `https://…/item.json' URL, a `*.json' file
;; path, and `owner/repo/item[#ref]' on GitHub — which fetches the BUILT
;; item at `public/r/item.json' so every fetch has a sha pin.
;;
;; Trust order per item: fetch → byte cap → closed-key parse → sha256 per
;; file (a mismatch refuses, nothing is cached) → safe read → requirements
;; → basename collisions → dry-run → the caller's confirmation → write →
;; lock.  Fetching lives here and in no `jetpacs-defaction' body, so the
;; applet validator's blocking-call rule stays literally true: the App
;; Store source uses `jetpacs-registry-fetch-async' from a flow
;; continuation and the batch tool uses the synchronous form.

;;; Code:

(require 'cl-lib)
(require 'seq)
(require 'subr-x)
(require 'json)
(require 'url)
(require 'url-http)
(require 'diff)

(defgroup jetpacs-registry nil
  "Applet registries: indexes of installable Jetpacs applets."
  :group 'jetpacs)

(defconst jetpacs-registry-item-format 1
  "The item and index JSON format this module reads.")

(defconst jetpacs-registry-lock-format 1
  "The lockfile format this module writes.")

(defcustom jetpacs-registry-max-bytes (* 2 1024 1024)
  "Upper bound on one fetched item or index, in bytes."
  :type 'natnum :group 'jetpacs-registry)

(defcustom jetpacs-registry-timeout-seconds 20
  "How long a synchronous fetch waits."
  :type 'natnum :group 'jetpacs-registry)

(defcustom jetpacs-registry-lock-file
  (expand-file-name "jetpacs/registry-lock.json" user-emacs-directory)
  "Where installed items are recorded: name, address, version, file digests."
  :type 'file :group 'jetpacs-registry)

(defcustom jetpacs-registry-namespaces nil
  "Configured registries: an alist of (\"@name\" . (:url TEMPLATE :headers ALIST)).
TEMPLATE contains `{name}'; `${VAR}' in the template and in header values
expands from the environment, and a header whose variable is empty is
dropped.  `JETPACS_REGISTRIES', a JSON object of the same shape (a string
value is a bare template), overrides this list, as `JETPACS_REFERENCE_ROOTS'
overrides the applet tooling's roots."
  :type '(alist :key-type string :value-type plist)
  :group 'jetpacs-registry)

(defconst jetpacs-registry-default-namespace "@jetpacs"
  "The namespace a bare item name resolves in.")

(define-error 'jetpacs-registry-error "Jetpacs registry")
(define-error 'jetpacs-registry-sha-mismatch "Jetpacs registry: content does not match its sha256"
  'jetpacs-registry-error)
(define-error 'jetpacs-registry-cycle "Jetpacs registry: dependency cycle"
  'jetpacs-registry-error)

(defconst jetpacs-registry--name-re "\\`[a-z][a-z0-9-]*\\'"
  "An item name: lowercase, digits and hyphens, as an Elisp feature is.")

(defconst jetpacs-registry--identifier-re
  "\\`[A-Za-z_][A-Za-z0-9_]*\\(\\.[A-Za-z_][A-Za-z0-9_]*\\)*\\'"
  "An EBP identifier, for extension ids.")

;;;; Addresses

(defun jetpacs-registry-parse-address (address)
  "Parse ADDRESS into a plist with `:scheme' and its parts.
Schemes: `url' (:url), `file' (:path), `namespace' (:namespace :item),
`github' (:owner :repo :item :ref) and `bare' (:namespace :item, the
default namespace).  Signals `jetpacs-registry-error' when malformed."
  (unless (and (stringp address) (not (string-empty-p address))
               (not (string-match-p "[[:space:]]" address)))
    (signal 'jetpacs-registry-error (list "address must be a non-empty string" address)))
  (let ((case-fold-search nil))
    (jetpacs-registry--parse-address-1 address)))

(defun jetpacs-registry--parse-address-1 (address)
  "Parse ADDRESS with case folding off (names are lowercase by grammar)."
  (cond
   ((string-match-p "\\`https?://" address)
    (list :scheme 'url :url address :address address))
   ((string-match-p "\\`file://" address)
    (list :scheme 'file :path (substring address (length "file://")) :address address))
   ((string-suffix-p ".json" address)
    (list :scheme 'file :path address :address address))
   ((string-prefix-p "@" address)
    (unless (string-match "\\`\\(@[a-z][a-z0-9-]*\\)/\\([a-z][a-z0-9-]*\\)\\'" address)
      (signal 'jetpacs-registry-error (list "a namespaced address is @namespace/item" address)))
    (list :scheme 'namespace :namespace (match-string 1 address)
          :item (match-string 2 address) :address address))
   ((string-match "\\`\\([A-Za-z0-9_.-]+\\)/\\([A-Za-z0-9_.-]+\\)/\\([a-z][a-z0-9-]*\\)\\(?:#\\([A-Za-z0-9_./-]+\\)\\)?\\'"
                  address)
    (list :scheme 'github :owner (match-string 1 address) :repo (match-string 2 address)
          :item (match-string 3 address) :ref (match-string 4 address) :address address))
   ((string-match-p jetpacs-registry--name-re address)
    (list :scheme 'bare :namespace jetpacs-registry-default-namespace
          :item address :address address))
   (t (signal 'jetpacs-registry-error (list "unrecognized address" address)))))

(defun jetpacs-registry-canonical-address (parsed)
  "The canonical string of PARSED: a bare name becomes @namespace/item."
  (pcase (plist-get parsed :scheme)
    ((or 'bare 'namespace)
     (format "%s/%s" (plist-get parsed :namespace) (plist-get parsed :item)))
    ('github (format "%s/%s/%s%s" (plist-get parsed :owner) (plist-get parsed :repo)
                     (plist-get parsed :item)
                     (if (plist-get parsed :ref) (concat "#" (plist-get parsed :ref)) "")))
    (_ (plist-get parsed :address))))

(defun jetpacs-registry-item-name (parsed)
  "The item name PARSED names, or nil for a URL or file address."
  (plist-get parsed :item))

(defun jetpacs-registry-dependency-address (dependency parent)
  "DEPENDENCY as written in PARENT's item, resolved to a full address.
A bare name inside a namespaced or GitHub item stays in that namespace or
repository; anything else is taken as written."
  (let ((dep (jetpacs-registry-parse-address dependency)))
    (if (not (eq (plist-get dep :scheme) 'bare))
        dependency
      (pcase (plist-get parent :scheme)
        ('namespace (format "%s/%s" (plist-get parent :namespace) (plist-get dep :item)))
        ('github (format "%s/%s/%s%s" (plist-get parent :owner) (plist-get parent :repo)
                         (plist-get dep :item)
                         (if (plist-get parent :ref) (concat "#" (plist-get parent :ref)) "")))
        (_ dependency)))))

;;;; Namespaces and URLs

(defun jetpacs-registry--expand-env (text)
  "TEXT with every `${VAR}' replaced by the environment's value, or empty."
  (replace-regexp-in-string
   "\\${\\([A-Za-z_][A-Za-z0-9_]*\\)}"
   (lambda (match) (or (getenv (match-string 1 match)) ""))
   text t t))

(defun jetpacs-registry--environment-namespaces ()
  "The `JETPACS_REGISTRIES' JSON object as an alist, or nil."
  (when-let* ((json (getenv "JETPACS_REGISTRIES"))
              ((not (string-empty-p json))))
    (let ((object (condition-case err
                      (json-parse-string json :object-type 'alist :array-type 'list
                                         :false-object :json-false)
                    (error (signal 'jetpacs-registry-error
                                   (list "JETPACS_REGISTRIES is not a JSON object"
                                         (error-message-string err)))))))
      (mapcar (lambda (pair)
                (let ((name (symbol-name (car pair)))
                      (value (cdr pair)))
                  (cons name
                        (if (stringp value)
                            (list :url value)
                          (list :url (alist-get 'url value)
                                :headers (mapcar (lambda (h) (cons (symbol-name (car h)) (cdr h)))
                                                 (alist-get 'headers value)))))))
              object))))

(defun jetpacs-registry-namespaces ()
  "The effective namespace alist: the environment's, else the customization."
  (or (jetpacs-registry--environment-namespaces)
      jetpacs-registry-namespaces))

(defun jetpacs-registry-namespace (name)
  "The configuration plist of namespace NAME, or signal."
  (or (cdr (assoc name (jetpacs-registry-namespaces)))
      (signal 'jetpacs-registry-error
              (list (format "registry %s is not configured; add it to `jetpacs-registry-namespaces' or JETPACS_REGISTRIES"
                            name)))))

(defun jetpacs-registry--headers (alist)
  "Expand header ALIST; a header naming an unset or empty `${VAR}' is dropped.
shadcn's `shouldIncludeHeader': a token header without its token is no
header at all, not a malformed one."
  (delq nil
        (mapcar (lambda (header)
                  (let* ((raw (format "%s" (cdr header)))
                         (start 0)
                         (missing nil))
                    (while (string-match "\\${\\([A-Za-z_][A-Za-z0-9_]*\\)}" raw start)
                      (let ((value (getenv (match-string 1 raw))))
                        (when (or (null value) (string-empty-p value))
                          (setq missing t)))
                      (setq start (match-end 0)))
                    (unless missing
                      (cons (car header) (jetpacs-registry--expand-env raw)))))
                alist)))

(defun jetpacs-registry-address-url (parsed)
  "The (URL . HEADERS) PARSED fetches from.
A namespace's template has `{name}' substituted; GitHub addresses read the
built item at `public/r/ITEM.json' of the ref (HEAD by default), with a
bearer token from `GITHUB_TOKEN' when set."
  (pcase (plist-get parsed :scheme)
    ('url (cons (plist-get parsed :url) nil))
    ('file (cons (concat "file://" (expand-file-name (plist-get parsed :path))) nil))
    ((or 'bare 'namespace)
     (let* ((config (jetpacs-registry-namespace (plist-get parsed :namespace)))
            (template (plist-get config :url)))
       (unless (and (stringp template) (string-search "{name}" template))
         (signal 'jetpacs-registry-error
                 (list (format "registry %s needs a URL template containing {name}"
                               (plist-get parsed :namespace)))))
       (cons (jetpacs-registry--expand-env
              (string-replace "{name}" (plist-get parsed :item) template))
             (jetpacs-registry--headers (plist-get config :headers)))))
    ('github
     (cons (format "https://raw.githubusercontent.com/%s/%s/%s/public/r/%s.json"
                   (plist-get parsed :owner) (plist-get parsed :repo)
                   (or (plist-get parsed :ref) "HEAD") (plist-get parsed :item))
           (jetpacs-registry--headers '(("Authorization" . "Bearer ${GITHUB_TOKEN}")))))
    (scheme (signal 'jetpacs-registry-error (list "unknown scheme" scheme)))))

;;;; Fetching

(defun jetpacs-registry--decode (bytes)
  "BYTES as a multibyte UTF-8 string, bounded by `jetpacs-registry-max-bytes'."
  (when (> (length bytes) jetpacs-registry-max-bytes)
    (signal 'jetpacs-registry-error
            (list (format "response exceeds %d bytes" jetpacs-registry-max-bytes))))
  (decode-coding-string bytes 'utf-8))

(defun jetpacs-registry--read-local (path)
  "The bytes of local PATH, literally."
  (let ((file (expand-file-name path)))
    (unless (file-readable-p file)
      (signal 'jetpacs-registry-error (list "no such file" file)))
    (when (> (file-attribute-size (file-attributes file)) jetpacs-registry-max-bytes)
      (signal 'jetpacs-registry-error
              (list (format "file exceeds %d bytes" jetpacs-registry-max-bytes) file)))
    (with-temp-buffer
      (set-buffer-multibyte nil)
      (insert-file-contents-literally file)
      (buffer-string))))

(defun jetpacs-registry--http-body (buffer url)
  "The response body in BUFFER for URL as unibyte text, or signal."
  (unless (buffer-live-p buffer)
    (signal 'jetpacs-registry-error (list "no response" url)))
  (with-current-buffer buffer
    (set-buffer-multibyte nil)
    (save-excursion
      (goto-char (point-min))
      (unless (looking-at "HTTP/[0-9.]+ \\([0-9]\\{3\\}\\)")
        (signal 'jetpacs-registry-error (list "malformed HTTP response" url)))
      (let ((status (string-to-number (match-string 1))))
        (unless (<= 200 status 299)
          (signal 'jetpacs-registry-error (list (format "HTTP %d" status) url)))
        (unless (re-search-forward "\r?\n\r?\n" nil t)
          (signal 'jetpacs-registry-error (list "malformed HTTP response" url)))
        (buffer-substring-no-properties (point) (point-max))))))

(defun jetpacs-registry-fetch-sync (url &optional headers)
  "Fetch URL with HEADERS synchronously; the body as a multibyte string.
`file://' and plain paths read the local file.  Never called from an
action handler — use the asynchronous form there."
  (cond
   ((string-prefix-p "file://" url)
    (jetpacs-registry--decode (jetpacs-registry--read-local (substring url 7))))
   ((not (string-match-p "\\`https?://" url))
    (jetpacs-registry--decode (jetpacs-registry--read-local url)))
   (t
    (let* ((url-request-extra-headers headers)
           (url-show-status nil)
           (buffer (url-retrieve-synchronously url t t jetpacs-registry-timeout-seconds)))
      (unwind-protect
          (jetpacs-registry--decode (jetpacs-registry--http-body buffer url))
        (when (buffer-live-p buffer) (kill-buffer buffer)))))))

(defun jetpacs-registry-fetch-async (url headers callback)
  "Fetch URL with HEADERS; call CALLBACK with (TEXT ERROR), one of them nil.
Local files answer on the next turn of the event loop, never re-entrantly."
  (if (not (string-match-p "\\`https?://" url))
      (run-at-time 0 nil
                   (lambda ()
                     (condition-case err
                         (funcall callback (jetpacs-registry-fetch-sync url) nil)
                       (error (funcall callback nil err)))))
    (let ((url-request-extra-headers headers)
          (url-show-status nil))
      (url-retrieve
       url
       (lambda (status)
         (let ((buffer (current-buffer)))
           (unwind-protect
               (condition-case err
                   (if-let* ((problem (plist-get status :error)))
                       (funcall callback nil problem)
                     (funcall callback
                              (jetpacs-registry--decode
                               (jetpacs-registry--http-body buffer url))
                              nil))
                 (error (funcall callback nil err)))
             (when (buffer-live-p buffer) (kill-buffer buffer)))))
       nil t t))))

;;;; Items

(defconst jetpacs-registry--item-keys
  '("format" "name" "version" "title" "description" "categories" "homepage" "docs"
    "files" "package_requires" "registry_dependencies" "requires_extensions"
    "requires_features" "applet_manifest_sha256")
  "Every key an item may carry; anything else is refused.")

(defconst jetpacs-registry--file-keys '("path" "sha256" "content")
  "Every key a file entry may carry.")

(defun jetpacs-registry--check-keys (object allowed what)
  "Signal unless hash table OBJECT's keys are all in ALLOWED; WHAT names it."
  (unless (hash-table-p object)
    (signal 'jetpacs-registry-error (list (format "%s must be a JSON object" what))))
  (maphash (lambda (key _)
             (unless (member key allowed)
               (signal 'jetpacs-registry-error
                       (list (format "%s has an unknown key %s" what key)))))
           object))

(defun jetpacs-registry--check-string-array (value what &optional regexp)
  "Signal unless VALUE is a vector of strings (matching REGEXP); WHAT names it."
  (unless (vectorp value)
    (signal 'jetpacs-registry-error (list (format "%s must be an array" what))))
  (seq-doseq (element value)
    (unless (and (stringp element) (or (null regexp) (string-match-p regexp element)))
      (signal 'jetpacs-registry-error
              (list (format "%s holds a malformed entry %S" what element))))))

(defun jetpacs-registry-parse-json (text)
  "Parse JSON TEXT with objects as hash tables and arrays as vectors."
  (condition-case err
      (json-parse-string text :object-type 'hash-table :array-type 'array
                         :null-object :json-null :false-object :json-false)
    (error (signal 'jetpacs-registry-error
                   (list "not JSON" (error-message-string err))))))

(defun jetpacs-registry-check-item (item)
  "Signal unless hash table ITEM is a well-formed format-1 item; return it.
Content is optional (an index strips it); `jetpacs-registry-verify-item'
insists on it."
  (jetpacs-registry--check-keys item jetpacs-registry--item-keys "item")
  (unless (eql (gethash "format" item) jetpacs-registry-item-format)
    (signal 'jetpacs-registry-error
            (list (format "item format must be %d" jetpacs-registry-item-format))))
  (dolist (key '("name" "version" "title" "description"))
    (unless (and (stringp (gethash key item)) (not (string-empty-p (gethash key item))))
      (signal 'jetpacs-registry-error (list (format "item needs a non-empty %s" key)))))
  (unless (let ((case-fold-search nil))
            (string-match-p jetpacs-registry--name-re (gethash "name" item)))
    (signal 'jetpacs-registry-error
            (list "item name must be lowercase letters, digits and hyphens"
                  (gethash "name" item))))
  (let ((files (gethash "files" item)))
    (unless (and (vectorp files) (> (length files) 0))
      (signal 'jetpacs-registry-error (list "item needs at least one file")))
    (let (seen)
      (seq-doseq (file files)
        (jetpacs-registry--check-keys file jetpacs-registry--file-keys "file")
        (let ((path (gethash "path" file)))
          (unless (and (stringp path) (string-suffix-p ".el" path)
                       (equal path (file-name-nondirectory path))
                       (not (string-prefix-p "." path)))
            (signal 'jetpacs-registry-error
                    (list "a file path must be a bare .el basename" path)))
          (when (member path seen)
            (signal 'jetpacs-registry-error (list "duplicate file path" path)))
          (push path seen))
        (unless (and (stringp (gethash "sha256" file))
                     (string-match-p "\\`[0-9a-f]\\{64\\}\\'" (gethash "sha256" file)))
          (signal 'jetpacs-registry-error (list "a file needs a lowercase hex sha256")))
        (let ((content (gethash "content" file)))
          (unless (or (null content) (stringp content))
            (signal 'jetpacs-registry-error (list "file content must be a string")))))))
  (dolist (key '("categories" "registry_dependencies" "requires_features"))
    (when-let* ((value (gethash key item)))
      (jetpacs-registry--check-string-array value key)))
  (when-let* ((value (gethash "requires_extensions" item)))
    (jetpacs-registry--check-string-array value "requires_extensions"
                                          jetpacs-registry--identifier-re)
    (seq-doseq (extension value)
      (unless (string-search "." extension)
        (signal 'jetpacs-registry-error
                (list "an extension id is namespaced" extension)))))
  (seq-doseq (dependency (or (gethash "registry_dependencies" item) []))
    (jetpacs-registry-parse-address dependency))
  (when-let* ((requires (gethash "package_requires" item)))
    (unless (vectorp requires)
      (signal 'jetpacs-registry-error (list "package_requires must be an array")))
    (seq-doseq (pair requires)
      (unless (and (vectorp pair) (= (length pair) 2)
                   (stringp (aref pair 0)) (stringp (aref pair 1)))
        (signal 'jetpacs-registry-error
                (list "package_requires entries are [package, version]" pair)))))
  item)

(defun jetpacs-registry-parse-item (text)
  "Parse and check item JSON TEXT."
  (jetpacs-registry-check-item (jetpacs-registry-parse-json text)))

(defun jetpacs-registry-sha256 (text)
  "The lowercase hex SHA-256 of TEXT's UTF-8 bytes."
  (secure-hash 'sha256 (encode-coding-string text 'utf-8)))

(defun jetpacs-registry-verify-item (item)
  "Signal unless every file of ITEM carries content matching its sha256.
Returns ITEM.  A mismatch is `jetpacs-registry-sha-mismatch'; nothing is
kept from a mismatching item."
  (seq-doseq (file (gethash "files" item))
    (let ((content (gethash "content" file)))
      (unless (stringp content)
        (signal 'jetpacs-registry-error
                (list "file has no content; fetch the item, not the index"
                      (gethash "path" file))))
      (unless (equal (jetpacs-registry-sha256 content) (gethash "sha256" file))
        (signal 'jetpacs-registry-sha-mismatch
                (list (gethash "name" item) (gethash "path" file))))))
  item)

;;;; Reading Elisp without touching the global obarray

(defun jetpacs-registry-safe-read-all (text)
  "Every top-level form of Elisp TEXT, read with a throwaway obarray.
No symbol is interned globally, nothing is evaluated, `#N=' labels are
off.  Signals `jetpacs-registry-error' on unreadable text.  The forms are
returned for callers that want to look; most just want the check."
  (let ((obarray (obarray-make))
        (read-circle nil)
        (read-symbol-shorthands nil)
        (position 0)
        (length (length text))
        forms)
    (cl-flet ((skip-blank ()
                (while (and (< position length)
                            (memq (aref text position) '(?\s ?\t ?\n ?\r ?\f)))
                  (setq position (1+ position)))
                ;; A comment runs to the end of its line.
                (while (and (< position length) (eq (aref text position) ?\;))
                  (let ((eol (or (string-search "\n" text position) length)))
                    (setq position eol))
                  (while (and (< position length)
                              (memq (aref text position) '(?\s ?\t ?\n ?\r ?\f)))
                    (setq position (1+ position))))))
      (skip-blank)
      (while (< position length)
        (condition-case err
            (pcase-let ((`(,form . ,next) (read-from-string text position)))
              (push form forms)
              (setq position next))
          (error (signal 'jetpacs-registry-error
                         (list "unreadable Elisp" (error-message-string err)))))
        (skip-blank)))
    (nreverse forms)))

(defun jetpacs-registry-check-item-readable (item)
  "Signal unless every file of ITEM reads as Elisp; return ITEM."
  (seq-doseq (file (gethash "files" item))
    (condition-case err
        (jetpacs-registry-safe-read-all (gethash "content" file))
      (jetpacs-registry-error
       (signal 'jetpacs-registry-error
               (list (format "%s/%s: %s" (gethash "name" item) (gethash "path" file)
                             (cadr err)))))))
  item)

;;;; Fetching items by address

(defun jetpacs-registry-fetch-item (address)
  "Fetch, parse and check the item at ADDRESS (a string or a parsed plist).
Synchronous; for batch tools."
  (let* ((parsed (if (stringp address) (jetpacs-registry-parse-address address) address))
         (target (jetpacs-registry-address-url parsed))
         (item (jetpacs-registry-parse-item
                (jetpacs-registry-fetch-sync (car target) (cdr target)))))
    (when-let* ((name (jetpacs-registry-item-name parsed)))
      (unless (equal name (gethash "name" item))
        (signal 'jetpacs-registry-error
                (list (format "address names %s but the item is %s" name (gethash "name" item))))))
    item))

;;;; Resolution

(cl-defun jetpacs-registry-resolve (addresses &key fetch)
  "Resolve ADDRESSES and their `registry_dependencies' into install order.
FETCH is a function from a parsed address to a checked item (default
`jetpacs-registry-fetch-item').  Returns a plist: `:items', a list of
(ADDRESS-STRING . ITEM) dependencies first (Kahn's algorithm), and
`:order', the canonical addresses in that order.  A cycle signals
`jetpacs-registry-cycle'; two items shipping one file basename signal
`jetpacs-registry-error' — every apps directory shares one `load-path'."
  (let ((fetch (or fetch #'jetpacs-registry-fetch-item))
        (items (make-hash-table :test #'equal))
        (parsed-by-key (make-hash-table :test #'equal))
        (edges (make-hash-table :test #'equal))
        (queue (mapcar #'jetpacs-registry-parse-address addresses))
        order)
    ;; Breadth-first discovery, keyed by canonical address.
    (while queue
      (let* ((parsed (pop queue))
             (key (jetpacs-registry-canonical-address parsed)))
        (unless (gethash key items)
          (let ((item (funcall fetch parsed)))
            (puthash key item items)
            (puthash key parsed parsed-by-key)
            (push key order)
            (let (deps)
              (seq-doseq (dependency (or (gethash "registry_dependencies" item) []))
                (let* ((full (jetpacs-registry-dependency-address dependency parsed))
                       (dep-parsed (jetpacs-registry-parse-address full))
                       (dep-key (jetpacs-registry-canonical-address dep-parsed)))
                  (push dep-key deps)
                  (unless (gethash dep-key items)
                    (setq queue (append queue (list dep-parsed))))))
              (puthash key (nreverse deps) edges))))))
    (setq order (nreverse order))
    ;; Kahn: an item follows everything it depends on.
    (let ((indegree (make-hash-table :test #'equal))
          (dependents (make-hash-table :test #'equal))
          ready sorted)
      (dolist (key order)
        (puthash key 0 indegree))
      (dolist (key order)
        (dolist (dep (gethash key edges))
          (puthash key (1+ (gethash key indegree)) indegree)
          (puthash dep (append (gethash dep dependents) (list key)) dependents)))
      (dolist (key order)
        (when (= 0 (gethash key indegree)) (push key ready)))
      (setq ready (nreverse ready))
      (while ready
        (let ((key (pop ready)))
          (push key sorted)
          (dolist (dependent (gethash key dependents))
            (puthash dependent (1- (gethash dependent indegree)) indegree)
            (when (= 0 (gethash dependent indegree))
              (setq ready (append ready (list dependent)))))))
      (setq sorted (nreverse sorted))
      (unless (= (length sorted) (length order))
        (signal 'jetpacs-registry-cycle
                (list (seq-difference order sorted))))
      ;; Basename conflicts across items.
      (let ((owners (make-hash-table :test #'equal)))
        (dolist (key sorted)
          (seq-doseq (file (gethash "files" (gethash key items)))
            (let ((path (gethash "path" file)))
              (when-let* ((other (gethash path owners)))
                (signal 'jetpacs-registry-error
                        (list (format "%s and %s both ship %s" other key path))))
              (puthash path key owners)))))
      (list :order sorted
            :items (mapcar (lambda (key) (cons key (gethash key items))) sorted)))))

;;;; Requirements

(cl-defun jetpacs-registry-check-requirements (item &key extensions features-fn emacs)
  "Issues (strings) with ITEM's declared requirements, or nil.
EXTENSIONS is the list of installed renderer-extension ids; FEATURES-FN
decides whether a required feature is loadable (default `locate-library');
EMACS is the version string to check the floor against (default the
running Emacs)."
  (let ((features-fn (or features-fn (lambda (feature) (locate-library feature))))
        (version (or emacs emacs-version))
        issues)
    (seq-doseq (extension (or (gethash "requires_extensions" item) []))
      (unless (member extension extensions)
        (push (format "renderer extension %s is not installed" extension) issues)))
    (seq-doseq (feature (or (gethash "requires_features" item) []))
      (unless (funcall features-fn feature)
        (push (format "required feature %s is not on the load path" feature) issues)))
    (seq-doseq (pair (or (gethash "package_requires" item) []))
      (when (equal (aref pair 0) "emacs")
        (when (version< version (aref pair 1))
          (push (format "needs Emacs %s, this is %s" (aref pair 1) version) issues))))
    (nreverse issues)))

(defun jetpacs-registry-basename-collisions (items &optional target-dir)
  "Files of ITEMS whose basename already exists in a `load-path' directory.
TARGET-DIR (the install directory) is excluded: a same-name file there is
an overwrite the dry-run reports, not a collision.  Returns an alist of
(BASENAME . EXISTING-PATH)."
  (let ((target (and target-dir (file-truename (file-name-as-directory target-dir))))
        collisions)
    (dolist (entry items)
      (seq-doseq (file (gethash "files" (cdr entry)))
        (let ((path (gethash "path" file)))
          (dolist (dir load-path)
            (when (and (stringp dir) (file-directory-p dir)
                       (not (and target (equal (file-truename (file-name-as-directory dir))
                                               target))))
              (let ((existing (expand-file-name path dir)))
                (when (and (file-exists-p existing)
                           (not (assoc path collisions)))
                  (push (cons path existing) collisions))))))))
    (nreverse collisions)))

;;;; The lockfile

(defun jetpacs-registry-lock-read (&optional file)
  "The lockfile at FILE (default `jetpacs-registry-lock-file') as a hash table.
A missing or unreadable lockfile is an empty one."
  (let ((file (or file jetpacs-registry-lock-file))
        (empty (make-hash-table :test #'equal)))
    (puthash "format" jetpacs-registry-lock-format empty)
    (puthash "installed" (make-hash-table :test #'equal) empty)
    (if (and file (file-readable-p file))
        (condition-case nil
            (let ((lock (jetpacs-registry-parse-json
                         (with-temp-buffer (insert-file-contents file) (buffer-string)))))
              (if (and (hash-table-p lock)
                       (eql (gethash "format" lock) jetpacs-registry-lock-format)
                       (hash-table-p (gethash "installed" lock)))
                  lock
                empty))
          (error empty))
      empty)))

(defun jetpacs-registry-lock-write (lock &optional file)
  "Write hash table LOCK to FILE (default `jetpacs-registry-lock-file')."
  (let ((file (or file jetpacs-registry-lock-file)))
    (make-directory (file-name-directory file) t)
    (with-temp-file file
      (set-buffer-file-coding-system 'utf-8-unix)
      (let ((text (json-serialize lock :false-object :json-false :null-object :json-null)))
        (insert (if (multibyte-string-p text) text (decode-coding-string text 'utf-8))
                "\n")))
    file))

(defun jetpacs-registry-lock-entry (name &optional file)
  "The lock entry of installed item NAME, or nil."
  (gethash name (gethash "installed" (jetpacs-registry-lock-read file))))

(defun jetpacs-registry-lock-record (item address registry &optional file)
  "Record ITEM installed from ADDRESS (its registry URL REGISTRY) in the lock."
  (let* ((lock (jetpacs-registry-lock-read file))
         (entry (make-hash-table :test #'equal))
         (files (make-hash-table :test #'equal)))
    (seq-doseq (f (gethash "files" item))
      (puthash (gethash "path" f) (gethash "sha256" f) files))
    (puthash "address" address entry)
    (puthash "registry" (or registry :json-null) entry)
    (puthash "version" (gethash "version" item) entry)
    (puthash "files" files entry)
    (puthash "installed_at" (format-time-string "%FT%TZ" nil t) entry)
    (puthash (gethash "name" item) entry (gethash "installed" lock))
    (jetpacs-registry-lock-write lock file)))

(defun jetpacs-registry-lock-forget (name &optional file)
  "Remove NAME from the lock; return its entry, or nil when it was not there."
  (let* ((lock (jetpacs-registry-lock-read file))
         (installed (gethash "installed" lock))
         (entry (gethash name installed)))
    (when entry
      (remhash name installed)
      (jetpacs-registry-lock-write lock file))
    entry))

;;;; Dry run, diff, install

(defun jetpacs-registry-dry-run (items dir)
  "What installing ITEMS (an alist of ADDRESS . ITEM) into DIR would do.
One plist per file: `:item' (name), `:path' (absolute), `:action' —
`create', `overwrite' or `skip' (byte-equal content) — `:content' and,
for an overwrite, `:existing'.  Nothing is written."
  (let ((dir (file-name-as-directory (expand-file-name dir)))
        plan)
    (dolist (entry items)
      (let ((item (cdr entry)))
        (seq-doseq (file (gethash "files" item))
          (let* ((path (expand-file-name (gethash "path" file) dir))
                 (content (gethash "content" file))
                 (existing (and (file-exists-p path)
                                (with-temp-buffer
                                  (insert-file-contents path)
                                  (buffer-string))))
                 (action (cond ((null existing) 'create)
                               ((equal existing content) 'skip)
                               (t 'overwrite))))
            (push (append (list :item (gethash "name" item) :path path
                                :action action :content content)
                          (and (eq action 'overwrite) (list :existing existing)))
                  plan)))))
    (nreverse plan)))

(defun jetpacs-registry-diff-string (plan-entry)
  "A unified diff from PLAN-ENTRY's existing content to its new content."
  (let ((old (generate-new-buffer " *registry old*"))
        (new (generate-new-buffer " *registry new*"))
        (name (file-name-nondirectory (plist-get plan-entry :path))))
    (unwind-protect
        (progn
          (with-current-buffer old (insert (or (plist-get plan-entry :existing) "")))
          (with-current-buffer new (insert (plist-get plan-entry :content)))
          (let ((buffer (diff-no-select old new "-u" t)))
            (unwind-protect
                (with-current-buffer buffer
                  (let ((text (buffer-substring-no-properties (point-min) (point-max))))
                    (replace-regexp-in-string
                     (regexp-quote (buffer-name old)) (concat "a/" name)
                     (replace-regexp-in-string
                      (regexp-quote (buffer-name new)) (concat "b/" name) text t t)
                     t t)))
              (kill-buffer buffer))))
      (kill-buffer old)
      (kill-buffer new))))

(cl-defun jetpacs-registry-install-files (plan &key allow-overwrite)
  "Write the files of PLAN (from `jetpacs-registry-dry-run'); return the paths.
An `overwrite' entry is refused unless ALLOW-OVERWRITE; nothing is written
when any entry is refused.  Loads nothing."
  (let ((refused (seq-filter (lambda (entry)
                               (and (eq (plist-get entry :action) 'overwrite)
                                    (not allow-overwrite)))
                             plan)))
    (when refused
      (signal 'jetpacs-registry-error
              (list (format "would overwrite %s; preview the diff and allow it explicitly"
                            (mapconcat (lambda (e) (file-name-nondirectory (plist-get e :path)))
                                       refused ", "))))))
  (let (written)
    (dolist (entry plan)
      (unless (eq (plist-get entry :action) 'skip)
        (let ((path (plist-get entry :path)))
          (make-directory (file-name-directory path) t)
          (with-temp-file path
            (set-buffer-file-coding-system 'utf-8-unix)
            (insert (plist-get entry :content)))
          (push path written))))
    (nreverse written)))

(cl-defun jetpacs-registry-install (addresses dir &key allow-overwrite fetch extensions
                                              features-fn registry lock-file)
  "Resolve ADDRESSES, check them, write them into DIR and record the lock.
The whole trust order, synchronously, for batch tools: fetch → verify →
safe read → requirements (against EXTENSIONS and FEATURES-FN) → basename
collisions → dry run → write (overwrites only with ALLOW-OVERWRITE) →
lock.  Returns the plan.  Loads nothing."
  (let* ((resolved (jetpacs-registry-resolve addresses :fetch fetch))
         (items (plist-get resolved :items)))
    (dolist (entry items)
      (jetpacs-registry-verify-item (cdr entry))
      (jetpacs-registry-check-item-readable (cdr entry))
      (when-let* ((issues (jetpacs-registry-check-requirements
                           (cdr entry) :extensions extensions :features-fn features-fn)))
        (signal 'jetpacs-registry-error
                (list (format "%s: %s" (car entry) (string-join issues "; "))))))
    (when-let* ((collisions (jetpacs-registry-basename-collisions items dir)))
      (signal 'jetpacs-registry-error
              (list (format "basename already on the load path: %s"
                            (mapconcat (lambda (c) (format "%s (%s)" (car c) (cdr c)))
                                       collisions ", ")))))
    (let ((plan (jetpacs-registry-dry-run items dir)))
      (jetpacs-registry-install-files plan :allow-overwrite allow-overwrite)
      (dolist (entry items)
        (jetpacs-registry-lock-record (cdr entry) (car entry) registry lock-file))
      plan)))

(provide 'jetpacs-registry)
;;; jetpacs-registry.el ends here
