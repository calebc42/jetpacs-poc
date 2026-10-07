;;; jetpacs-app-store-registry-test.el --- The registry as an App Store source -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later

;; Batch-safe: a file registry in a temp tree, temp adopt and lock paths,
;; immediate continuations, synchronous "asynchronous" fetches.  The test
;; items are real Elisp that only `provide's, so loading them is harmless.

;;; Code:

(require 'ert)
(require 'cl-lib)
(require 'jetpacs-app-store-registry)

(defvar jetpacs-app-store-registry-test--loaded nil)

(defun jetpacs-app-store-registry-test--item (name content &rest extra)
  "A checked item NAME shipping NAME.el with CONTENT and EXTRA keys."
  (let ((item (make-hash-table :test #'equal))
        (file (make-hash-table :test #'equal)))
    (puthash "path" (concat name ".el") file)
    (puthash "sha256" (jetpacs-registry-sha256 content) file)
    (puthash "content" content file)
    (puthash "format" 1 item)
    (puthash "name" name item)
    (puthash "version" "0.1.0" item)
    (puthash "title" (capitalize name) item)
    (puthash "description" (format "The %s applet" name) item)
    (puthash "files" (vector file) item)
    (cl-loop for (key value) on extra by #'cddr do (puthash key value item))
    item))

(defun jetpacs-app-store-registry-test--json (value)
  (let ((text (json-serialize value :false-object :json-false :null-object :json-null)))
    (if (multibyte-string-p text) text (decode-coding-string text 'utf-8))))

(defun jetpacs-app-store-registry-test--publish (dir items)
  "Write ITEMS and their index under DIR as a file registry named `test'."
  (make-directory dir t)
  (let ((index (make-hash-table :test #'equal)))
    (dolist (item items)
      (with-temp-file (expand-file-name (concat (gethash "name" item) ".json") dir)
        (insert (jetpacs-app-store-registry-test--json item))))
    (puthash "format" 1 index)
    (puthash "name" "test" index)
    (puthash "items" (vconcat (mapcar (lambda (item)
                                        (let ((copy (copy-hash-table item)))
                                          (puthash "files"
                                                   (vconcat (mapcar (lambda (f)
                                                                      (let ((c (copy-hash-table f)))
                                                                        (remhash "content" c) c))
                                                                    (gethash "files" item)))
                                                   copy)
                                          copy))
                                      items))
             index)
    (with-temp-file (expand-file-name "registry.json" dir)
      (insert (jetpacs-app-store-registry-test--json index)))))

(defun jetpacs-app-store-registry-test--find-action (node action)
  "The first descriptor named ACTION below NODE, or nil."
  (let (found)
    (cl-labels ((walk (value)
                  (cond
                   ((vectorp value) (mapc #'walk (append value nil)))
                   ((and (listp value) (keywordp (car value)))
                    (when (and (null found) (equal (plist-get value :action) action))
                      (setq found value))
                    (cl-loop for (_key child) on value by #'cddr do (walk child)))
                   ((listp value) (mapc #'walk value)))))
      (walk node))
    found))

(defmacro jetpacs-app-store-registry-test--env (&rest body)
  "A temp home, a published file registry, immediate continuations and fetches."
  (declare (indent 0))
  `(let* ((home (make-temp-file "jetpacs-home" t))
          (registry (expand-file-name "r/" home))
          (user-emacs-directory (file-name-as-directory home))
          (jetpacs-app-store-staging-dirs nil)
          (jetpacs-app-store-file (expand-file-name "apps.el" home))
          (jetpacs-registry-lock-file (expand-file-name "lock.json" home))
          (jetpacs-registry-namespaces nil)
          (jetpacs-app-store-packaged-apps nil)
          (jetpacs-app-store-installed nil)
          (jetpacs-app-store-registry-indexes (list (expand-file-name "registry.json" registry)))
          (jetpacs-app-store-registry--indexes nil)
          (jetpacs-app-store-registry--preview nil)
          (jetpacs-app-store-registry--previewed nil)
          (jetpacs-app-store-registry-test--loaded nil)
          (jetpacs-apps--registry nil)
          (toasts nil) (pushed nil))
     (cl-letf (((symbol-function 'jetpacs-flow-continue) (lambda (fn) (funcall fn)))
               ((symbol-function 'jetpacs-toast)
                (cl-function (lambda (text &key duration-s) (ignore duration-s) (push text toasts))))
               ((symbol-function 'jetpacs-shell-notify) (lambda (&rest _) nil))
               ((symbol-function 'jetpacs-chrome-push-screen)
                (lambda (_surface id _builder) (push id pushed)))
               ((symbol-function 'jetpacs-shell-push) (lambda (&rest _) (push 'store pushed)))
               ((symbol-function 'jetpacs-connected-p) (lambda () nil))
               ;; Fetches answer at once: the file registry needs no event loop.
               ((symbol-function 'jetpacs-registry-fetch-async)
                (lambda (url headers callback)
                  (condition-case err
                      (funcall callback (jetpacs-registry-fetch-sync url headers) nil)
                    (error (funcall callback nil err))))))
       (ignore toasts pushed)
       (with-environment-variables (("JETPACS_REGISTRIES" ""))
         (unwind-protect (progn ,@body)
           (delete-directory home t))))))

(defconst jetpacs-app-store-registry-test--hello
  ";;; hello.el --- Hello -*- lexical-binding: t; -*-\n;; Package-Requires: ((emacs \"30.1\"))\n(require 'hello-lib)\n(setq jetpacs-app-store-registry-test--loaded (cons 'hello jetpacs-app-store-registry-test--loaded))\n(provide 'hello)\n;;; hello.el ends here\n")

(defconst jetpacs-app-store-registry-test--lib
  ";;; hello-lib.el --- Hello lib -*- lexical-binding: t; -*-\n(setq jetpacs-app-store-registry-test--loaded (cons 'lib jetpacs-app-store-registry-test--loaded))\n(provide 'hello-lib)\n;;; hello-lib.el ends here\n")

(ert-deftest jetpacs-app-store-registry-test-refresh-lists-items-with-the-consent-gate ()
  "After a refresh every published item is an Available row behind the gate."
  (jetpacs-app-store-registry-test--env
    (jetpacs-app-store-registry-test--publish
     registry (list (jetpacs-app-store-registry-test--item
                     "hello" jetpacs-app-store-registry-test--hello
                     "registry_dependencies" ["hello-lib"])
                    (jetpacs-app-store-registry-test--item
                     "hello-lib" jetpacs-app-store-registry-test--lib)))
    (should-not (jetpacs-app-store-registry-entries))
    (jetpacs-app-store-registry-refresh)
    (let ((entries (jetpacs-app-store-registry-entries)))
      (should (equal (mapcar (lambda (e) (plist-get e :name)) entries) '("hello.el" "hello-lib.el")))
      (should (equal (plist-get (car entries) :address) "@test/hello"))
      (should (equal (plist-get (car entries) :registry) "@test")))
    (let* ((entry (jetpacs-app-store--entry "hello.el"))
           (row (jetpacs-app-store--row entry))
           (install (jetpacs-app-store-registry-test--find-action row "apps.install"))
           (preview (jetpacs-app-store-registry-test--find-action row "apps.registry.preview")))
      (should (eq (plist-get entry :source) 'registry))
      (should-not (plist-get entry :installed))
      (should install)
      (should (string-search "runs hello.el with your Emacs's full permissions"
                             (plist-get (plist-get install :confirm) :text)))
      (should preview))))

(ert-deftest jetpacs-app-store-registry-test-install-fetches-verifies-writes-locks-and-loads ()
  "Install through apps.install: dependencies first, lock recorded, main files loaded."
  (jetpacs-app-store-registry-test--env
    (jetpacs-app-store-registry-test--publish
     registry (list (jetpacs-app-store-registry-test--item
                     "hello" jetpacs-app-store-registry-test--hello
                     "registry_dependencies" ["hello-lib"])
                    (jetpacs-app-store-registry-test--item
                     "hello-lib" jetpacs-app-store-registry-test--lib)))
    (jetpacs-app-store-registry-refresh)
    (should (eq 'accepted (jetpacs-app-store--action-install '(:bundle "hello.el") nil)))
    (should (file-exists-p (expand-file-name "hello.el" (jetpacs-app-store--adopt-dir))))
    (should (file-exists-p (expand-file-name "hello-lib.el" (jetpacs-app-store--adopt-dir))))
    (should (equal jetpacs-app-store-registry-test--loaded '(hello lib)))
    (should (member "hello.el" jetpacs-app-store-installed))
    (should (member "hello-lib.el" jetpacs-app-store-installed))
    (should (file-exists-p jetpacs-app-store-file))
    (should (equal (gethash "version" (jetpacs-registry-lock-entry "hello")) "0.1.0"))
    (should (seq-some (lambda (t1) (string-prefix-p "Installed Hello" t1)) toasts))
    (should (plist-get (jetpacs-app-store--entry "hello.el") :installed))
    ;; Uninstalling the main file removes every file the lock lists.
    (jetpacs-app-store--uninstall "hello.el")
    (should-not (file-exists-p (expand-file-name "hello.el" (jetpacs-app-store--adopt-dir))))
    (should-not (jetpacs-registry-lock-entry "hello"))))

(ert-deftest jetpacs-app-store-registry-test-local-edit-needs-a-preview-before-overwrite ()
  "A locally changed file blocks a blind install; after Preview the install replaces it."
  (jetpacs-app-store-registry-test--env
    (let ((item (jetpacs-app-store-registry-test--item
                 "solo" "(setq jetpacs-app-store-registry-test--loaded 'solo)\n(provide 'solo)\n")))
      (jetpacs-app-store-registry-test--publish registry (list item))
      (jetpacs-app-store-registry-refresh)
      (make-directory (jetpacs-app-store--adopt-dir) t)
      (with-temp-file (expand-file-name "solo.el" (jetpacs-app-store--adopt-dir))
        (insert "(provide 'solo) ;; my local version\n"))
      (should (eq 'accepted (jetpacs-app-store--action-install '(:bundle "solo.el") nil)))
      (should (seq-some (lambda (t1) (string-search "changed locally" t1)) toasts))
      (should (string-search "my local version"
                             (with-temp-buffer
                               (insert-file-contents (expand-file-name "solo.el" (jetpacs-app-store--adopt-dir)))
                               (buffer-string))))
      (should (eq 'rejected (jetpacs-app-store-registry--on-preview '(:bundle "nope.el") nil)))
      (should (eq 'accepted (jetpacs-app-store-registry--on-preview '(:bundle "solo.el") nil)))
      (should (member "registry-preview" pushed))
      (should (string-search "my local version" (plist-get jetpacs-app-store-registry--preview :diffs)))
      (let ((screen (jetpacs-app-store-registry--preview-screen nil)))
        (should (jetpacs-app-store-registry-test--find-action screen "apps.install")))
      (should (eq 'accepted (jetpacs-app-store--action-install '(:bundle "solo.el") nil)))
      (should (eq jetpacs-app-store-registry-test--loaded 'solo))
      (should-not (string-search "my local version"
                                 (with-temp-buffer
                                   (insert-file-contents (expand-file-name "solo.el" (jetpacs-app-store--adopt-dir)))
                                   (buffer-string)))))))

(ert-deftest jetpacs-app-store-registry-test-refuses-tampered-content-and-missing-extensions ()
  "A sha mismatch and an unregistered extension both refuse before any write."
  (jetpacs-app-store-registry-test--env
    (let ((tampered (jetpacs-app-store-registry-test--item "bad" "(provide 'bad)\n"))
          (needy (jetpacs-app-store-registry-test--item
                  "needy" "(provide 'needy)\n"
                  "requires_extensions" ["jetpacs.no_such_extension"])))
      (puthash "content" "(provide 'bad) ;; tampered\n" (aref (gethash "files" tampered) 0))
      (jetpacs-app-store-registry-test--publish registry (list tampered needy))
      (jetpacs-app-store-registry-refresh)
      (jetpacs-app-store--action-install '(:bundle "bad.el") nil)
      (should (seq-some (lambda (t1) (string-search "sha256" t1)) toasts))
      (should-not (file-exists-p (expand-file-name "bad.el" (jetpacs-app-store--adopt-dir))))
      (jetpacs-app-store--action-install '(:bundle "needy.el") nil)
      (should (seq-some (lambda (t1) (string-search "jetpacs.no_such_extension" t1)) toasts))
      (should-not (file-exists-p (expand-file-name "needy.el" (jetpacs-app-store--adopt-dir)))))))

(provide 'jetpacs-app-store-registry-test)
;;; jetpacs-app-store-registry-test.el ends here
