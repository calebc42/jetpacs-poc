;;; jetpacs-app-store-registry.el --- The applet registry as an App Store source -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later

;;; Commentary:

;; The Apps screen knows two sources: the APK's packaged apps and single
;; `.el' bundles side-loaded into a staging directory.  This module adds a
;; third — applet registries (emacs/jetpacs-registry.el) — the way shadcn's
;; `add' reaches configured registries (ebp/slop-docs/SHADCN-harvest.md §5).
;;
;; Configure index URLs in `jetpacs-app-store-registry-indexes'; a refresh
;; fetches each `registry.json', and every published item becomes an
;; "Available" row with the same consent gate the staged bundles wear —
;; installing IS running code.  The install itself is the registry's trust
;; order, asynchronously and never inside a dispatch: fetch the item and
;; its dependencies → sha256 → safe read → declared requirements against
;; the installed extensions (and the live profile when a Companion is
;; connected) → load-path collisions → dry run into the adopt directory →
;; write → lock → only then load, exactly as the staged path loads.  A file
;; a user edited locally is never overwritten blind: the Preview screen
;; shows the diff, and only an install that follows a preview may replace it.
;;
;; Wire: none new beyond the local `apps.registry.preview' action.

;;; Code:

(require 'cl-lib)
(require 'seq)
(require 'subr-x)
(require 'jetpacs-registry)
(require 'jetpacs-widgets)
(require 'jetpacs-surfaces)
(require 'jetpacs-shell)
(require 'jetpacs-chrome)
(require 'jetpacs-apps)
(require 'jetpacs-app-store)

(defcustom jetpacs-app-store-registry-indexes nil
  "URLs of applet registry indexes (`…/registry.json'), fetched on refresh.
A `file://' URL or a plain path works for a local registry."
  :type '(repeat string) :group 'jetpacs-registry)

(defvar jetpacs-app-store-registry--indexes nil
  "Fetched indexes: an alist of (NAMESPACE . (:url URL :items VECTOR :error TEXT)).")

(defvar jetpacs-app-store-registry--preview nil
  "What the Preview screen shows.
A plist: `:name', `:label', `:address', `:plan', `:issues', `:collisions',
`:diffs'.")

(defvar jetpacs-app-store-registry--previewed nil
  "The last previewed install: (:name NAME :digest DIGEST).
An install that would overwrite a local file proceeds only when its plan
digest equals this one — the user has seen that diff.")

;;;; Namespaces from indexes

(defun jetpacs-app-store-registry--namespace-url (index-url)
  "The item template beside INDEX-URL: its directory plus `{name}.json'."
  (concat (file-name-directory index-url) "{name}.json"))

(defun jetpacs-app-store-registry-namespaces ()
  "The registries in force: the customized or environment ones plus the fetched."
  (append (jetpacs-registry-namespaces)
          (mapcar (lambda (entry)
                    (cons (car entry)
                          (list :url (jetpacs-app-store-registry--namespace-url
                                      (plist-get (cdr entry) :url)))))
                  jetpacs-app-store-registry--indexes)))

(defmacro jetpacs-app-store-registry--with-namespaces (&rest body)
  "Run BODY with every known registry in force for the foundation."
  (declare (indent 0))
  `(let ((jetpacs-registry-namespaces (jetpacs-app-store-registry-namespaces)))
     ,@body))

;;;; Refresh: fetch the indexes

(defun jetpacs-app-store-registry--store-index (url text)
  "Record the index TEXT fetched from URL; signal when it is not one."
  (let ((index (jetpacs-registry-parse-json text)))
    (unless (and (hash-table-p index)
                 (eql (gethash "format" index) jetpacs-registry-item-format)
                 (stringp (gethash "name" index))
                 (vectorp (gethash "items" index)))
      (signal 'jetpacs-registry-error (list "not a registry index" url)))
    (seq-doseq (item (gethash "items" index))
      (jetpacs-registry-check-item item))
    (let ((namespace (concat "@" (gethash "name" index))))
      (setf (alist-get namespace jetpacs-app-store-registry--indexes nil nil #'equal)
            (list :url url :items (gethash "items" index)))
      namespace)))

(defun jetpacs-app-store-registry-refresh (&optional callback)
  "Fetch every configured index asynchronously; CALLBACK runs when all answered.
An index that fails keeps an `:error' so the Apps screen can say so."
  (let ((pending (length jetpacs-app-store-registry-indexes)))
    (if (= pending 0)
        (when callback (funcall callback))
      (dolist (url jetpacs-app-store-registry-indexes)
        (jetpacs-registry-fetch-async
         url nil
         (lambda (text err)
           (condition-case problem
               (if err
                   (signal 'jetpacs-registry-error (list (format "%s" err) url))
                 (jetpacs-app-store-registry--store-index url text))
             (error
              (let ((key (seq-find (lambda (ns)
                                     (equal (plist-get (cdr ns) :url) url))
                                   jetpacs-app-store-registry--indexes)))
                (setf (alist-get (if key (car key) (concat "@" url))
                                 jetpacs-app-store-registry--indexes nil nil #'equal)
                      (list :url url :items []
                            :error (error-message-string problem))))))
           (when (= 0 (cl-decf pending))
             (when callback (funcall callback)))))))))

(defun jetpacs-app-store-registry--on-refresh ()
  "Refresh the indexes and re-push the Apps screen when they answer."
  (jetpacs-app-store-registry-refresh
   (lambda () (ignore-errors (jetpacs-shell-push jetpacs-app-store-surface)))))

;;;; The scan source

(defun jetpacs-app-store-registry-main-file (item)
  "The bundle name installed ITEM is known by.
NAME.el when the item ships it, else its first file."
  (let ((names (mapcar (lambda (f) (gethash "path" f)) (append (gethash "files" item) nil)))
        (preferred (concat (gethash "name" item) ".el")))
    (if (member preferred names) preferred (car names))))

(defun jetpacs-app-store-registry-entries ()
  "One scan entry per published item of every fetched index."
  (let (entries)
    (dolist (index jetpacs-app-store-registry--indexes)
      (let ((namespace (car index)))
        (seq-doseq (item (plist-get (cdr index) :items))
          (let* ((name (jetpacs-app-store-registry-main-file item))
                 (lock (jetpacs-registry-lock-entry (gethash "name" item))))
            (push (list :name name
                        :source 'registry
                        :item (gethash "name" item)
                        :address (format "%s/%s" namespace (gethash "name" item))
                        :registry namespace
                        :label (gethash "title" item)
                        :icon "cloud_download"
                        :version (gethash "version" item)
                        :installed-version (and lock (gethash "version" lock))
                        :summary (format "%s %s · %s · from %s"
                                         (gethash "title" item) (gethash "version" item)
                                         (gethash "description" item) namespace))
                  entries)))))
    (nreverse entries)))

(defun jetpacs-app-store-registry--entry-p (entry)
  "Non-nil when scan ENTRY came from a registry."
  (eq (plist-get entry :source) 'registry))

(defun jetpacs-app-store-registry--row-trailing (entry)
  "A Preview button for a registry ENTRY that is not installed."
  (when (and (jetpacs-app-store-registry--entry-p entry)
             (not (plist-get entry :installed)))
    (list (jetpacs-icon-button
           "difference"
           (jetpacs-action "apps.registry.preview"
                           :args (list :bundle (plist-get entry :name))
                           :when-offline "drop")
           :content-description (format "Preview %s" (plist-get entry :label))))))

;;;; Fetching an item tree without blocking

(defun jetpacs-app-store-registry--fetch-tree (addresses done)
  "Fetch ADDRESSES and their dependencies one at a time; DONE gets (TABLE ERROR).
TABLE maps canonical addresses to checked items.  Nothing is evaluated;
every fetch is `jetpacs-registry-fetch-async'."
  (let ((table (make-hash-table :test #'equal))
        (queue (mapcar #'jetpacs-registry-parse-address addresses)))
    (cl-labels
        ((next ()
           (if (null queue)
               (funcall done table nil)
             (let* ((parsed (pop queue))
                    (key (jetpacs-registry-canonical-address parsed)))
               (if (gethash key table)
                   (next)
                 (condition-case err
                     (let ((target (jetpacs-app-store-registry--with-namespaces
                                     (jetpacs-registry-address-url parsed))))
                       (jetpacs-registry-fetch-async
                        (car target) (cdr target)
                        (lambda (text fetch-err)
                          (condition-case problem
                              (progn
                                (when fetch-err
                                  (signal 'jetpacs-registry-error
                                          (list (format "%s" fetch-err) (car target))))
                                (let ((item (jetpacs-registry-parse-item text)))
                                  (when-let* ((name (jetpacs-registry-item-name parsed)))
                                    (unless (equal name (gethash "name" item))
                                      (signal 'jetpacs-registry-error
                                              (list "address and item name differ" key))))
                                  (puthash key item table)
                                  (seq-doseq (dep (or (gethash "registry_dependencies" item) []))
                                    (setq queue
                                          (append queue
                                                  (list (jetpacs-registry-parse-address
                                                         (jetpacs-registry-dependency-address
                                                          dep parsed))))))
                                  (next)))
                            (error (funcall done table problem))))))
                   (error (funcall done table err))))))))
      (next))))

;;;; Planning (pure, once the tree is fetched)

(defun jetpacs-app-store-registry--installed-extensions ()
  "The renderer extensions this Emacs has registered."
  (mapcar #'car jetpacs-renderer-extensions))

(defun jetpacs-app-store-registry--plan (table addresses)
  "Resolve ADDRESSES from the fetched TABLE and classify the install.
A plist (:items :order :issues :collisions :plan); nothing is written."
  (let* ((resolved (jetpacs-registry-resolve
                    addresses
                    :fetch (lambda (parsed)
                             (or (gethash (jetpacs-registry-canonical-address parsed) table)
                                 (signal 'jetpacs-registry-error
                                         (list "not fetched"
                                               (jetpacs-registry-canonical-address parsed)))))))
         (items (plist-get resolved :items))
         (dir (jetpacs-app-store--adopt-dir))
         issues)
    (dolist (entry items)
      (jetpacs-registry-verify-item (cdr entry))
      (jetpacs-registry-check-item-readable (cdr entry))
      (dolist (issue (jetpacs-registry-check-requirements
                      (cdr entry)
                      :extensions (jetpacs-app-store-registry--installed-extensions)
                      :features-fn (lambda (feature)
                                     (or (locate-library feature)
                                         (seq-some (lambda (other)
                                                     (seq-some (lambda (f)
                                                                 (equal (file-name-sans-extension
                                                                         (gethash "path" f))
                                                                        feature))
                                                               (gethash "files" (cdr other))))
                                                   items)))))
        (push (format "%s: %s" (car entry) issue) issues))
      (when (jetpacs-connected-p)
        (seq-doseq (extension (or (gethash "requires_extensions" (cdr entry)) []))
          (unless (jetpacs-extension-advertised-p extension :app)
            (push (format "%s: this Companion does not advertise %s" (car entry) extension)
                  issues)))))
    (list :items items
          :order (plist-get resolved :order)
          :issues (nreverse issues)
          :collisions (jetpacs-registry-basename-collisions items dir)
          :plan (jetpacs-registry-dry-run items dir))))

(defun jetpacs-app-store-registry--plan-digest (plan)
  "A digest of PLAN's paths, actions and new contents."
  (secure-hash 'sha256
               (mapconcat (lambda (entry)
                            (concat (plist-get entry :path) "\0"
                                    (symbol-name (plist-get entry :action)) "\0"
                                    (jetpacs-registry-sha256 (plist-get entry :content)) "\n"))
                          plan "")))

(defun jetpacs-app-store-registry--previewed-p (name plan)
  "Non-nil when PLAN for NAME is the one the user previewed."
  (and jetpacs-app-store-registry--previewed
       (equal (plist-get jetpacs-app-store-registry--previewed :name) name)
       (equal (plist-get jetpacs-app-store-registry--previewed :digest)
              (jetpacs-app-store-registry--plan-digest plan))))

;;;; Install (the App Store's install hook)

(defun jetpacs-app-store-registry--ensure-load-path ()
  "Put the adopt directory on `load-path' once, for multi-file items."
  (let ((dir (directory-file-name (jetpacs-app-store--adopt-dir))))
    (unless (member dir load-path)
      (add-to-list 'load-path dir t))))

(defun jetpacs-app-store-registry--apply (entry report)
  "Write REPORT's plan for ENTRY, lock it, then load each item's main file.
The caller has already passed the consent gate; this is the staged path's
own adopt-compile-load, over a sha-verified, previewed plan."
  (let* ((plan (plist-get report :plan))
         (overwrites (seq-filter (lambda (e) (eq (plist-get e :action) 'overwrite)) plan))
         (name (plist-get entry :name)))
    (when (and overwrites (not (jetpacs-app-store-registry--previewed-p name plan)))
      (signal 'jetpacs-registry-error
              (list (format "%s changed locally — open Preview, then install"
                            (mapconcat (lambda (e) (file-name-nondirectory (plist-get e :path)))
                                       overwrites ", ")))))
    (jetpacs-app-store-registry--ensure-load-path)
    (jetpacs-registry-install-files plan :allow-overwrite (and overwrites t))
    (dolist (item (plist-get report :items))
      (jetpacs-registry-lock-record (cdr item) (car item)
                                    (plist-get (cdr (assoc (plist-get entry :registry)
                                                           jetpacs-app-store-registry--indexes))
                                               :url)))
    ;; Load dependencies first (install order), each item by its main file.
    (dolist (item (plist-get report :items))
      (let ((dest (expand-file-name (jetpacs-app-store-registry-main-file (cdr item))
                                    (jetpacs-app-store--adopt-dir))))
        (ignore-errors (byte-compile-file dest))
        (load dest nil 'nomessage)
        (cl-pushnew (file-name-nondirectory dest) jetpacs-app-store-installed :test #'equal)))
    (jetpacs-app-store--persist)
    (setq jetpacs-app-store-registry--previewed nil)
    plan))

(defun jetpacs-app-store-registry--install (entry)
  "The `jetpacs-app-store-install-functions' member: non-nil for a registry ENTRY.
Runs the whole trust order asynchronously and reports by toast; the
dispatch that called this already returned `accepted'."
  (when (jetpacs-app-store-registry--entry-p entry)
    (let ((label (or (plist-get entry :label) (plist-get entry :name)))
          (address (plist-get entry :address)))
      (jetpacs-app-store-registry--fetch-tree
       (list address)
       (lambda (table err)
         (condition-case problem
             (progn
               (when err (signal (car err) (cdr err)))
               (let ((report (jetpacs-app-store-registry--plan table (list address))))
                 (when (plist-get report :issues)
                   (signal 'jetpacs-registry-error
                           (list (string-join (plist-get report :issues) "; "))))
                 (when (plist-get report :collisions)
                   (signal 'jetpacs-registry-error
                           (list (format "%s is already on the load path"
                                         (car (car (plist-get report :collisions)))))))
                 (jetpacs-app-store-registry--apply entry report)
                 (jetpacs-toast (format "Installed %s %s" label (plist-get entry :version)))))
           (error
            (jetpacs-toast (format "Install failed: %s"
                                   (if (eq (car-safe problem) 'jetpacs-registry-error)
                                       (string-join (mapcar (lambda (x) (format "%s" x))
                                                            (cdr problem))
                                                    ": ")
                                     (error-message-string problem))))))
         (ignore-errors (jetpacs-shell-push jetpacs-app-store-surface))))
      t)))

;;;; Uninstall: every file the lock lists

(defun jetpacs-app-store-registry--uninstall (name)
  "Remove the other files of the item whose main file NAME was uninstalled."
  (let* ((lock (jetpacs-registry-lock-read))
         (installed (gethash "installed" lock))
         (dir (jetpacs-app-store--adopt-dir))
         owner)
    (maphash (lambda (item entry)
               (when (and (not owner) (gethash name (gethash "files" entry)))
                 (setq owner item)))
             installed)
    (when owner
      (maphash (lambda (path _sha)
                 (let ((file (expand-file-name path dir)))
                   (ignore-errors (delete-file file))
                   (ignore-errors (delete-file (concat file "c")))))
               (gethash "files" (gethash owner installed)))
      (jetpacs-registry-lock-forget owner))))

;;;; Preview

(defun jetpacs-app-store-registry--preview-screen (back)
  "Builder for the pushed Preview screen.
Reads `jetpacs-app-store-registry--preview'; BACK is the stack's back action."
  (let ((preview jetpacs-app-store-registry--preview))
    (if (null preview)
        (jetpacs-chrome-screen
         "Preview" (jetpacs-empty-state :icon "difference" :title "Nothing previewed")
         :back back)
      (let* ((plan (plist-get preview :plan))
             (blocked (or (plist-get preview :issues) (plist-get preview :collisions)))
             (overwrites (seq-count (lambda (e) (eq (plist-get e :action) 'overwrite)) plan))
             (name (plist-get preview :name)))
        (jetpacs-chrome-screen
         (jetpacs-scalar-text (format "Preview %s" (plist-get preview :label)))
         (apply #'jetpacs-column
                (append
                 (list (jetpacs-text (format "%s → %s" (plist-get preview :address)
                                             (jetpacs-app-store--adopt-dir))
                                     :style "caption"))
                 (mapcar (lambda (entry)
                           (jetpacs-text (format "%s  %s" (plist-get entry :action)
                                                 (file-name-nondirectory (plist-get entry :path)))
                                         :style "mono"))
                         plan)
                 (mapcar (lambda (issue) (jetpacs-text issue :color "error"))
                         (plist-get preview :issues))
                 (mapcar (lambda (c) (jetpacs-text (format "%s is already at %s" (car c) (cdr c))
                                                   :color "error"))
                         (plist-get preview :collisions))
                 (when (plist-get preview :diffs)
                   (list (jetpacs-text (plist-get preview :diffs) :style "mono" :selectable t)))
                 (unless blocked
                   (list
                    (jetpacs-button
                     (if (> overwrites 0) (format "Overwrite %d and install" overwrites) "Install")
                     (jetpacs-action
                      "apps.install" :args (list :bundle name)
                      :when-offline "drop"
                      :confirm (list :title (if (> overwrites 0) "Overwrite local changes?" "Install app?")
                                     :icon "download"
                                     :text (format "Installing runs %s with your Emacs's full permissions.%s"
                                                   name
                                                   (if (> overwrites 0)
                                                       (format " %d local file%s shown above will be replaced."
                                                               overwrites (if (= overwrites 1) "" "s"))
                                                     ""))
                                     :confirm-label "Install"
                                     :dismiss-label "Cancel")))))
                 (list :spacing 12)))
         :back back)))))

(defun jetpacs-app-store-registry--on-preview (args params)
  "Fetch, plan and show the diff of installing the registry bundle in ARGS."
  (let ((entry (jetpacs-app-store--entry (plist-get args :bundle)))
        (surface (or (plist-get params :surface) jetpacs-app-store-surface)))
    (if (not (and entry (jetpacs-app-store-registry--entry-p entry)))
        'rejected
      (jetpacs-flow-continue
       (lambda () (jetpacs-app-store-registry--preview entry surface)))
      'accepted)))

(defun jetpacs-app-store-registry--preview (entry surface)
  "Fetch ENTRY's tree, plan it, remember the digest and push the screen on SURFACE."
  (let ((address (plist-get entry :address))
        (name (plist-get entry :name)))
    (jetpacs-app-store-registry--fetch-tree
     (list address)
     (lambda (table err)
       (condition-case problem
           (progn
             (when err (signal (car err) (cdr err)))
             (let* ((report (jetpacs-app-store-registry--plan table (list address)))
                    (plan (plist-get report :plan)))
               (setq jetpacs-app-store-registry--preview
                     (list :name name :label (plist-get entry :label) :address address
                           :plan plan :issues (plist-get report :issues)
                           :collisions (plist-get report :collisions)
                           :diffs (let ((text (mapconcat
                                               #'jetpacs-registry-diff-string
                                               (seq-filter (lambda (e) (eq (plist-get e :action) 'overwrite))
                                                           plan)
                                               "\n\n")))
                                    (and (not (string-empty-p text))
                                         (if (> (length text) jetpacs-app-store-max-bytes)
                                             (concat (substring text 0 jetpacs-app-store-max-bytes)
                                                     "\n… diff truncated …")
                                           text))))
                     jetpacs-app-store-registry--previewed
                     (list :name name :digest (jetpacs-app-store-registry--plan-digest plan)))
               (condition-case perr
                   (jetpacs-chrome-push-screen
                    surface "registry-preview" #'jetpacs-app-store-registry--preview-screen)
                 (error (jetpacs-toast (format "Preview failed: %s" (jetpacs-error-label perr)))))))
         (error
          (jetpacs-toast (format "Preview failed: %s"
                                 (if (eq (car-safe problem) 'jetpacs-registry-error)
                                     (string-join (mapcar (lambda (x) (format "%s" x)) (cdr problem))
                                                  ": ")
                                   (error-message-string problem))))))))))

;;;; Wiring

(jetpacs-defaction "apps.registry.preview" #'jetpacs-app-store-registry--on-preview
                   :doc "Fetch a registry applet, plan its install and show the diff"
                   :args '((:name bundle :type "text" :required t)))

(add-hook 'jetpacs-app-store-sources #'jetpacs-app-store-registry-entries)
(add-hook 'jetpacs-app-store-install-functions #'jetpacs-app-store-registry--install)
(add-hook 'jetpacs-app-store-uninstall-functions #'jetpacs-app-store-registry--uninstall)
(add-hook 'jetpacs-app-store-refresh-functions #'jetpacs-app-store-registry--on-refresh)
(add-hook 'jetpacs-app-store-row-trailing-functions #'jetpacs-app-store-registry--row-trailing)
(jetpacs-app-store-registry--ensure-load-path)

(provide 'jetpacs-app-store-registry)
;;; jetpacs-app-store-registry.el ends here
