;;; jetpacs-registry-test.el --- The applet registry foundation -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later

;;; Code:

(require 'ert)
(require 'cl-lib)
(require 'jetpacs-registry)

;;;; Fixtures

(defconst jetpacs-registry-test--hello
  ";;; hello.el --- Hello -*- lexical-binding: t; -*-\n;; Package-Requires: ((emacs \"30.1\"))\n(require 'jetpacs-apps)\n(defun hello--view () (jetpacs-text \"Hello\"))\n(provide 'hello)\n;;; hello.el ends here\n")

(defun jetpacs-registry-test--item (name content &rest extra)
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
    (cl-loop for (key value) on extra by #'cddr
             do (puthash key value item))
    item))

(defun jetpacs-registry-test--json (item)
  "ITEM as JSON text."
  (let ((text (json-serialize item :false-object :json-false :null-object :json-null)))
    (if (multibyte-string-p text) text (decode-coding-string text 'utf-8))))

(defun jetpacs-registry-test--fetcher (items)
  "A fetch function answering from the alist ITEMS (canonical address . item)."
  (lambda (parsed)
    (let ((key (jetpacs-registry-canonical-address parsed)))
      (or (cdr (assoc key items))
          (signal 'jetpacs-registry-error (list "no such item" key))))))

(defmacro jetpacs-registry-test--with-temp-dir (var &rest body)
  "Run BODY with VAR bound to a fresh temporary directory, deleted after."
  (declare (indent 1))
  `(let ((,var (make-temp-file "jetpacs-registry" t)))
     (unwind-protect (progn ,@body)
       (delete-directory ,var t))))

;;;; A loopback HTTP server serving a directory

(defun jetpacs-registry-test--serve (dir)
  "Serve DIR over HTTP on a loopback port; return (PROCESS . PORT)."
  (let* ((root (file-name-as-directory dir))
         (server
          (make-network-process
           :name "jetpacs-registry-test-http" :server t :host "127.0.0.1"
           :service t :family 'ipv4 :coding 'binary :noquery t
           :filter
           (lambda (proc text)
             (when (string-match "\\`GET \\([^ ]+\\) HTTP" text)
               (let* ((path (substring (match-string 1 text) 1))
                      (file (expand-file-name path root))
                      (ok (and (file-regular-p file) (file-in-directory-p file root)))
                      (body (if ok
                                (with-temp-buffer
                                  (set-buffer-multibyte nil)
                                  (insert-file-contents-literally file)
                                  (buffer-string))
                              "not found"))
                      (auth (string-match-p "^Authorization: Bearer secret\r?$" text)))
                 (when (and ok (string-prefix-p "private/" path) (not auth))
                   (setq ok nil body "forbidden"))
                 (process-send-string
                  proc
                  (concat (format "HTTP/1.0 %s\r\nContent-Type: application/json\r\nContent-Length: %d\r\nConnection: close\r\n\r\n"
                                  (cond (ok "200 OK") ((equal body "forbidden") "403 Forbidden")
                                        (t "404 Not Found"))
                                  (length body))
                          body))
                 (process-send-eof proc)))))))
    (cons server (process-contact server :service))))

;;;; Addresses

(ert-deftest jetpacs-registry-test-parses-the-five-address-schemes ()
  "Bare, namespaced, URL, file and GitHub addresses parse; junk is refused."
  (should (equal (plist-get (jetpacs-registry-parse-address "hello") :scheme) 'bare))
  (should (equal (jetpacs-registry-canonical-address (jetpacs-registry-parse-address "hello"))
                 "@jetpacs/hello"))
  (let ((ns (jetpacs-registry-parse-address "@acme/login-form")))
    (should (equal (plist-get ns :scheme) 'namespace))
    (should (equal (plist-get ns :namespace) "@acme"))
    (should (equal (plist-get ns :item) "login-form")))
  (should (equal (plist-get (jetpacs-registry-parse-address "https://r.example/hello.json") :scheme)
                 'url))
  (should (equal (plist-get (jetpacs-registry-parse-address "./public/r/hello.json") :scheme)
                 'file))
  (should (equal (plist-get (jetpacs-registry-parse-address "file:///tmp/r/hello.json") :path)
                 "/tmp/r/hello.json"))
  (let ((gh (jetpacs-registry-parse-address "acme/applets/hello#v1.2")))
    (should (equal (plist-get gh :scheme) 'github))
    (should (equal (plist-get gh :owner) "acme"))
    (should (equal (plist-get gh :repo) "applets"))
    (should (equal (plist-get gh :item) "hello"))
    (should (equal (plist-get gh :ref) "v1.2"))
    (should (equal (jetpacs-registry-canonical-address gh) "acme/applets/hello#v1.2")))
  (should-not (plist-get (jetpacs-registry-parse-address "acme/applets/hello") :ref))
  (dolist (bad '("" "Hello" "@acme" "@acme/Hello" "acme/applets/hello#bad ref" "a b" "../x"))
    (should-error (jetpacs-registry-parse-address bad) :type 'jetpacs-registry-error)))

(ert-deftest jetpacs-registry-test-dependencies-resolve-in-the-parent-namespace ()
  "A bare dependency inside a namespaced or GitHub item stays there."
  (let ((ns (jetpacs-registry-parse-address "@acme/login"))
        (gh (jetpacs-registry-parse-address "acme/applets/login#main")))
    (should (equal (jetpacs-registry-dependency-address "card" ns) "@acme/card"))
    (should (equal (jetpacs-registry-dependency-address "card" gh) "acme/applets/card#main"))
    (should (equal (jetpacs-registry-dependency-address "@other/card" ns) "@other/card"))))

(ert-deftest jetpacs-registry-test-namespace-urls-expand-env-and-drop-empty-headers ()
  "Templates substitute {name} and ${VAR}; a header on an empty var is dropped."
  (let ((jetpacs-registry-namespaces
         '(("@acme" . (:url "https://${ACME_HOST}/r/{name}.json"
                       :headers (("Authorization" . "Bearer ${ACME_TOKEN}")
                                 ("X-Static" . "yes")))))))
    (with-environment-variables (("ACME_HOST" "r.acme.test") ("ACME_TOKEN" ""))
      (let ((target (jetpacs-registry-address-url (jetpacs-registry-parse-address "@acme/login"))))
        (should (equal (car target) "https://r.acme.test/r/login.json"))
        (should (equal (cdr target) '(("X-Static" . "yes"))))))
    (with-environment-variables (("ACME_HOST" "r.acme.test") ("ACME_TOKEN" "t0k"))
      (should (equal (cdr (jetpacs-registry-address-url (jetpacs-registry-parse-address "@acme/login")))
                     '(("Authorization" . "Bearer t0k") ("X-Static" . "yes")))))
    (should-error (jetpacs-registry-address-url (jetpacs-registry-parse-address "@nowhere/x"))
                  :type 'jetpacs-registry-error))
  (with-environment-variables
      (("JETPACS_REGISTRIES" "{\"@env\":\"https://env.test/{name}.json\",\"@full\":{\"url\":\"https://full.test/{name}.json\",\"headers\":{\"X-A\":\"1\"}}}"))
    (should (equal (car (jetpacs-registry-address-url (jetpacs-registry-parse-address "@env/a")))
                   "https://env.test/a.json"))
    (should (equal (cdr (jetpacs-registry-address-url (jetpacs-registry-parse-address "@full/a")))
                   '(("X-A" . "1")))))
  (with-environment-variables (("GITHUB_TOKEN" ""))
    (let ((target (jetpacs-registry-address-url (jetpacs-registry-parse-address "acme/applets/hello"))))
      (should (equal (car target)
                     "https://raw.githubusercontent.com/acme/applets/HEAD/public/r/hello.json"))
      (should-not (cdr target)))))

;;;; Items

(ert-deftest jetpacs-registry-test-checks-item-shape-and-refuses-unknown-keys ()
  "An item is closed: unknown keys, bad names, nested paths and bad shas refuse."
  (let ((item (jetpacs-registry-test--item "hello" jetpacs-registry-test--hello)))
    (should (eq item (jetpacs-registry-check-item item)))
    (should (jetpacs-registry-parse-item (jetpacs-registry-test--json item))))
  (should-error (jetpacs-registry-parse-item "{\"format\":2,\"name\":\"x\"}")
                :type 'jetpacs-registry-error)
  (should-error (jetpacs-registry-check-item
                 (jetpacs-registry-test--item "hello" "x" "extra" "no"))
                :type 'jetpacs-registry-error)
  (should-error (jetpacs-registry-check-item (jetpacs-registry-test--item "Hello" "x"))
                :type 'jetpacs-registry-error)
  (let ((item (jetpacs-registry-test--item "hello" "x")))
    (puthash "path" "../hello.el" (aref (gethash "files" item) 0))
    (should-error (jetpacs-registry-check-item item) :type 'jetpacs-registry-error))
  (let ((item (jetpacs-registry-test--item "hello" "x")))
    (puthash "sha256" "nope" (aref (gethash "files" item) 0))
    (should-error (jetpacs-registry-check-item item) :type 'jetpacs-registry-error))
  (should-error (jetpacs-registry-check-item
                 (jetpacs-registry-test--item "hello" "x" "requires_extensions" ["components"]))
                :type 'jetpacs-registry-error)
  (should-error (jetpacs-registry-check-item
                 (jetpacs-registry-test--item "hello" "x" "registry_dependencies" ["Bad Name"]))
                :type 'jetpacs-registry-error))

(ert-deftest jetpacs-registry-test-verifies-sha256-and-refuses-mismatch ()
  "Content must hash to its sha256; an index entry without content is refused too."
  (let ((item (jetpacs-registry-test--item "hello" jetpacs-registry-test--hello)))
    (should (eq item (jetpacs-registry-verify-item item)))
    (puthash "content" (concat jetpacs-registry-test--hello ";; tampered\n")
             (aref (gethash "files" item) 0))
    (should-error (jetpacs-registry-verify-item item) :type 'jetpacs-registry-sha-mismatch))
  (let ((item (jetpacs-registry-test--item "hello" jetpacs-registry-test--hello)))
    (remhash "content" (aref (gethash "files" item) 0))
    (should (jetpacs-registry-check-item item))
    (should-error (jetpacs-registry-verify-item item) :type 'jetpacs-registry-error)))

(ert-deftest jetpacs-registry-test-safe-read-interns-nothing-and-evaluates-nothing ()
  "Reading leaves the global obarray alone and refuses unreadable text."
  (let ((forms (jetpacs-registry-safe-read-all
                "(defun jetpacs-registry-test--never-interned-xyzzy () 1)\n(setq jetpacs-registry-test--plugh 2) ; tail\n")))
    (should (= (length forms) 2))
    (should-not (intern-soft "jetpacs-registry-test--never-interned-xyzzy"))
    (should-not (intern-soft "jetpacs-registry-test--plugh"))
    (should-not (fboundp (intern "jetpacs-registry-test--never-interned-xyzzy"))))
  (should-error (jetpacs-registry-safe-read-all "(defun broken (") :type 'jetpacs-registry-error)
  (should-error (jetpacs-registry-safe-read-all "#.(delete-directory \"/\")")
                :type 'jetpacs-registry-error)
  (should (equal (jetpacs-registry-safe-read-all "   \n") nil)))

;;;; Resolution

(ert-deftest jetpacs-registry-test-resolves-dependencies-first-and-refuses-cycles ()
  "Kahn order puts dependencies before dependents; a cycle and a shared basename refuse."
  (let* ((card (jetpacs-registry-test--item "card" "(provide 'card)"))
         (login (jetpacs-registry-test--item "login" "(provide 'login)"
                                             "registry_dependencies" ["card"]))
         (site (jetpacs-registry-test--item "site" "(provide 'site)"
                                            "registry_dependencies" ["@jetpacs/login" "card"]))
         (fetch (jetpacs-registry-test--fetcher
                 `(("@jetpacs/card" . ,card) ("@jetpacs/login" . ,login) ("@jetpacs/site" . ,site))))
         (resolved (jetpacs-registry-resolve '("site") :fetch fetch)))
    (should (equal (plist-get resolved :order)
                   '("@jetpacs/card" "@jetpacs/login" "@jetpacs/site")))
    (should (= 3 (length (plist-get resolved :items)))))
  (let* ((a (jetpacs-registry-test--item "a" "(provide 'a)" "registry_dependencies" ["b"]))
         (b (jetpacs-registry-test--item "b" "(provide 'b)" "registry_dependencies" ["a"]))
         (fetch (jetpacs-registry-test--fetcher `(("@jetpacs/a" . ,a) ("@jetpacs/b" . ,b)))))
    (should-error (jetpacs-registry-resolve '("a") :fetch fetch) :type 'jetpacs-registry-cycle))
  (let* ((a (jetpacs-registry-test--item "a" "(provide 'a)" "registry_dependencies" ["b"]))
         (b (jetpacs-registry-test--item "b" "(provide 'b)")))
    (puthash "path" "a.el" (aref (gethash "files" b) 0))
    (should-error (jetpacs-registry-resolve
                   '("a") :fetch (jetpacs-registry-test--fetcher
                                  `(("@jetpacs/a" . ,a) ("@jetpacs/b" . ,b))))
                  :type 'jetpacs-registry-error)))

(ert-deftest jetpacs-registry-test-requirements-and-collisions ()
  "Extensions, features and the Emacs floor are checked; load-path basenames collide."
  (let ((item (jetpacs-registry-test--item
               "hello" "x"
               "requires_extensions" ["jetpacs.components" "jetpacs.design"]
               "requires_features" ["jetpacs-apps" "no-such-feature-xyzzy"]
               "package_requires" [["emacs" "99.1"]])))
    (let ((issues (jetpacs-registry-check-requirements
                   item :extensions '("jetpacs.components")
                   :features-fn (lambda (f) (equal f "jetpacs-apps"))
                   :emacs "30.1")))
      (should (= 3 (length issues)))
      (should (seq-some (lambda (i) (string-search "jetpacs.design" i)) issues))
      (should (seq-some (lambda (i) (string-search "no-such-feature-xyzzy" i)) issues))
      (should (seq-some (lambda (i) (string-search "99.1" i)) issues)))
    (should-not (jetpacs-registry-check-requirements
                 item :extensions '("jetpacs.components" "jetpacs.design")
                 :features-fn (lambda (_) t) :emacs "99.2")))
  (jetpacs-registry-test--with-temp-dir dir
    (let* ((elsewhere (expand-file-name "elsewhere/" dir))
           (target (expand-file-name "apps/" dir))
           (load-path (cons elsewhere load-path))
           (items (list (cons "@jetpacs/hello"
                              (jetpacs-registry-test--item "hello" "x")))))
      (make-directory elsewhere t)
      (make-directory target t)
      (should-not (jetpacs-registry-basename-collisions items target))
      (with-temp-file (expand-file-name "hello.el" elsewhere) (insert "old"))
      (should (equal (car (car (jetpacs-registry-basename-collisions items target))) "hello.el"))
      ;; The target directory itself is an overwrite, not a collision.
      (with-temp-file (expand-file-name "hello.el" target) (insert "old"))
      (let ((load-path (cons target load-path)))
        (should (= 1 (length (jetpacs-registry-basename-collisions items target))))))))

;;;; Dry run, diff, install, lock

(ert-deftest jetpacs-registry-test-dry-run-classifies-and-install-refuses-overwrite ()
  "create / skip / overwrite; overwrite needs explicit permission; the lock records."
  (jetpacs-registry-test--with-temp-dir dir
    (let* ((jetpacs-registry-lock-file (expand-file-name "lock.json" dir))
           (target (expand-file-name "apps/" dir))
           (item (jetpacs-registry-test--item "hello" jetpacs-registry-test--hello))
           (items (list (cons "@jetpacs/hello" item)))
           (plan (jetpacs-registry-dry-run items target)))
      (should (equal (mapcar (lambda (e) (plist-get e :action)) plan) '(create)))
      (should (equal (jetpacs-registry-install-files plan)
                     (list (expand-file-name "hello.el" target))))
      (should (equal (mapcar (lambda (e) (plist-get e :action))
                             (jetpacs-registry-dry-run items target))
                     '(skip)))
      (jetpacs-registry-lock-record item "@jetpacs/hello" "https://r.test/registry.json")
      (let ((entry (jetpacs-registry-lock-entry "hello")))
        (should (equal (gethash "address" entry) "@jetpacs/hello"))
        (should (equal (gethash "version" entry) "0.1.0"))
        (should (equal (gethash "hello.el" (gethash "files" entry))
                       (jetpacs-registry-sha256 jetpacs-registry-test--hello))))
      ;; A local edit makes the next install an overwrite.
      (with-temp-file (expand-file-name "hello.el" target)
        (insert jetpacs-registry-test--hello ";; local change\n"))
      (let ((plan (jetpacs-registry-dry-run items target)))
        (should (equal (plist-get (car plan) :action) 'overwrite))
        (should (string-search ";; local change" (plist-get (car plan) :existing)))
        (let ((diff (jetpacs-registry-diff-string (car plan))))
          (should (string-search "-;; local change" diff))
          (should (string-search "a/hello.el" diff)))
        (should-error (jetpacs-registry-install-files plan) :type 'jetpacs-registry-error)
        (should (string-search ";; local change"
                               (with-temp-buffer
                                 (insert-file-contents (expand-file-name "hello.el" target))
                                 (buffer-string))))
        (should (jetpacs-registry-install-files plan :allow-overwrite t))
        (should (equal (mapcar (lambda (e) (plist-get e :action))
                               (jetpacs-registry-dry-run items target))
                       '(skip))))
      (should (jetpacs-registry-lock-forget "hello"))
      (should-not (jetpacs-registry-lock-entry "hello")))))

(ert-deftest jetpacs-registry-test-end-to-end-over-file-and-loopback-http ()
  "A file registry and a loopback HTTP registry both install; private needs its header."
  (jetpacs-registry-test--with-temp-dir dir
    (let* ((public (expand-file-name "r/" dir))
           (private (expand-file-name "r/private/" dir))
           (target (expand-file-name "apps/" dir))
           (jetpacs-registry-lock-file (expand-file-name "lock.json" dir))
           (card (jetpacs-registry-test--item "card" "(provide 'card)\n"))
           (login (jetpacs-registry-test--item "login" "(require 'card)\n(provide 'login)\n"
                                               "registry_dependencies" ["card"]
                                               "requires_extensions" ["jetpacs.components"])))
      (make-directory private t)
      (dolist (item (list card login))
        (with-temp-file (expand-file-name (concat (gethash "name" item) ".json") public)
          (insert (jetpacs-registry-test--json item))))
      (with-temp-file (expand-file-name "card.json" private)
        (insert (jetpacs-registry-test--json card)))
      ;; file:// namespace
      (let ((jetpacs-registry-namespaces
             `(("@jetpacs" . (:url ,(concat "file://" public "{name}.json"))))))
        (should-error (jetpacs-registry-install '("login") target :extensions nil
                                                :features-fn (lambda (_) t))
                      :type 'jetpacs-registry-error)
        (let ((plan (jetpacs-registry-install '("login") target
                                              :extensions '("jetpacs.components")
                                              :features-fn (lambda (_) t)
                                              :registry "file-registry")))
          (should (equal (mapcar (lambda (e) (file-name-nondirectory (plist-get e :path))) plan)
                         '("card.el" "login.el")))
          (should (file-exists-p (expand-file-name "card.el" target)))
          (should (equal (gethash "registry" (jetpacs-registry-lock-entry "login")) "file-registry"))))
      ;; loopback HTTP namespace, plus a token-gated path
      (pcase-let ((`(,server . ,port) (jetpacs-registry-test--serve public)))
        (unwind-protect
            (let ((jetpacs-registry-namespaces
                   `(("@http" . (:url ,(format "http://127.0.0.1:%d/{name}.json" port)))
                     ("@private" . (:url ,(format "http://127.0.0.1:%d/private/{name}.json" port)
                                    :headers (("Authorization" . "Bearer ${JRT_TOKEN}"))))))
                  (target2 (expand-file-name "apps2/" dir)))
              (let ((fetched (jetpacs-registry-fetch-item "@http/card")))
                (should (equal (gethash "name" fetched) "card")))
              (should-error (jetpacs-registry-fetch-item "@http/missing") :type 'jetpacs-registry-error)
              (with-environment-variables (("JRT_TOKEN" ""))
                (should-error (jetpacs-registry-fetch-item "@private/card") :type 'jetpacs-registry-error))
              (with-environment-variables (("JRT_TOKEN" "secret"))
                (should (equal (gethash "name" (jetpacs-registry-fetch-item "@private/card")) "card")))
              (should (jetpacs-registry-install '("@http/login") target2
                                                :extensions '("jetpacs.components")
                                                :features-fn (lambda (_) t)))
              (should (file-exists-p (expand-file-name "login.el" target2)))
              ;; The asynchronous form answers through its callback.
              (let (result)
                (jetpacs-registry-fetch-async
                 (format "http://127.0.0.1:%d/card.json" port) nil
                 (lambda (text err) (setq result (list text err))))
                (with-timeout (10 (ert-fail "async fetch timed out"))
                  (while (null result) (accept-process-output nil 0.05)))
                (should-not (cadr result))
                (should (equal (gethash "name" (jetpacs-registry-parse-item (car result))) "card"))))
          (delete-process server))))))

(provide 'jetpacs-registry-test)
;;; jetpacs-registry-test.el ends here
