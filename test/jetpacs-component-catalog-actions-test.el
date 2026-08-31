;;; jetpacs-component-catalog-actions-test.el --- Catalog action safety tests -*- lexical-binding: t; -*-

;;; Code:

(require 'ert)
(require 'cl-lib)
(require 'ebp)
(require 'jetpacs-widgets)
(require 'jetpacs-surfaces)
(require 'jetpacs-component-catalog-actions)

(cl-defun jetpacs-component-catalog-actions-test--client
    (&key builtins features granted)
  "Return a READY client advertising the supplied app vocabulary."
  (let ((client
         (ebp-client-create
          :receipt-file
          (make-temp-file "jpcatalog-action-safety-receipts"))))
    (setf
     (ebp-client-state client) 'ready
     (ebp-client-granted client) (or granted [])
     (ebp-client-profiles client)
     `(:app
       (:node_types ["text" "column" "button" "text_input"
                     "variant_host"]
        :builtins ,(or builtins [])
        :features ,(or features [])
        :extensions []))
     (ebp-client-limits client)
     '(:max_capture_fields 64 :max_event_bytes 262144))
    client))

(defun jetpacs-component-catalog-actions-test--node-with-action (descriptor)
  "Return one simple button carrying DESCRIPTOR."
  (list :t "button" :label "Run" :on_tap descriptor))

(defun jetpacs-component-catalog-actions-test--only-descriptor (document)
  "Return DOCUMENT's only descriptor, asserting that it is unique."
  (let ((records
         (jetpacs-component-catalog-actions-descriptors document)))
    (should (= (length records) 1))
    (plist-get (car records) :descriptor)))

(defun jetpacs-component-catalog-actions-test--reasons (result)
  "Return compatibility reason symbols from RESULT."
  (mapcar (lambda (issue) (plist-get issue :reason))
          (plist-get result :issues)))

(ert-deftest jetpacs-component-catalog-actions/finds-hooks-and-skips-data ()
  (let* ((document
          '(:schema_version 1
            :root
            (:t "column"
             :children
             [(:t "button" :label "Run"
               :on_tap
               (:action "demo.run"
                :args (:on_tap (:action "data.must-not-be-walked"))))
              (:t "text" :text "Accessible"
               :semantics
               (:actions
                [(:label "Describe"
                  :on_action (:builtin "companion.settings.open"))]))
              (:t "editor" :id "editor" :value "x"
               :toolbar
               [(:label "Format" :on_tap (:action "demo.format"))])])))
         (records
          (jetpacs-component-catalog-actions-descriptors document)))
    (should (= (length records) 3))
    (should
     (equal
      (mapcar (lambda (record) (plist-get record :path)) records)
      '((:root :children 0 :on_tap)
        (:root :children 1 :semantics :actions 0 :on_action)
        (:root :children 2 :toolbar 0 :on_tap))))
    (should
     (equal
      (mapcar
      (lambda (record)
         (let ((descriptor (plist-get record :descriptor)))
           (or (plist-get descriptor :action)
               (plist-get descriptor :builtin))))
       records)
      '("demo.run" "companion.settings.open" "demo.format")))))

(ert-deftest jetpacs-component-catalog-actions/private-wire-args-stay-private ()
  (let* ((wire-name ":jpcatalog-private-wire-arg-x9q7-no-global")
         (private-obarray (make-vector 17 0))
         (private-key (intern wire-name private-obarray))
         (client (jetpacs-component-catalog-actions-test--client))
         (name "jpcatalog.safety-private-args")
         (args (list private-key "sample"))
         (document
          (jetpacs-component-catalog-actions-test--node-with-action
           (list :action name :args args))))
    (should-not (keywordp private-key))
    (should-not (intern-soft wire-name))
    (let ((jetpacs--client client))
      (unwind-protect
          (progn
            (with-jetpacs-owner "jpcatalog"
              (jetpacs-defaction name (lambda (_args _params) 'accepted)))
            (should
             (jetpacs-component-catalog-actions-compatible-p
              document (list :client client)))
            (let* ((projection
                    (jetpacs-component-catalog-actions-instrument
                     document nil (list :client client)))
                   (preserved
                    (plist-get
                     (plist-get (car (plist-get projection :authored))
                                :descriptor)
                     :args)))
              (should (eq (car preserved) private-key))
              (should
               (equal
                (plist-get
                 (jetpacs-component-catalog-actions-test--only-descriptor
                  (plist-get projection :document))
                 :action)
                "jpcatalog.trace")))
            (should-not (intern-soft wire-name)))
        (jetpacs-undefaction name)))))

(ert-deftest jetpacs-component-catalog-actions/unarmed-rewrite-is-drop-only ()
  (let* ((authored
          '(:action "danger.run"
            :args (:secret "never-copy-this")
            :when_offline "queue"
            :dedupe "danger-once"
            :ttl_s 600
            :confirm "Really run it?"
            :capture_fields ["password"]
            :open_surface "app:other"))
         (document
          (list
           :schema_version 1
           :root
           (list
            :t "column"
            :children
            (vector
             '(:t "text_input" :id "password" :value "" :password t)
             (list :t "button" :label "Run" :on_tap authored)))))
         (projection
          (jetpacs-component-catalog-actions-instrument document nil))
         (rendered (plist-get projection :document))
         (trace-records
          (jetpacs-component-catalog-actions-descriptors rendered))
         (trace (plist-get (car trace-records) :descriptor))
         (preserved
          (plist-get (car (plist-get projection :authored)) :descriptor)))
    (should-not (plist-get projection :armed))
    (should (equal preserved authored))
    (should (equal (plist-get trace :action) "jpcatalog.trace"))
    (should (equal (plist-get trace :when_offline) "drop"))
    (should (equal (plist-get trace :confirm) "Really run it?"))
    (should-not (plist-member trace :capture_fields))
    (dolist (forbidden '(:dedupe :ttl_s :open_surface :builtin))
      (should-not (plist-member trace forbidden)))
    (let ((printed (prin1-to-string (plist-get trace :args))))
      (should-not (string-match-p "never-copy-this" printed))
      (should-not (string-match-p "danger-once" printed)))
    ;; Projection is functional: the canonical source remains authored.
    (should (equal
             (plist-get
              (jetpacs-component-catalog-actions-test--only-descriptor
               document)
              :action)
             "danger.run"))))

(ert-deftest jetpacs-component-catalog-actions/trace-summary-redacts-values ()
  (let* ((secret "correct horse battery staple")
         (field-secret "device-only-password")
         (summary
          (jetpacs-component-catalog-actions-trace-summary
           (list :catalog_path "root/on_submit"
                 :catalog_kind "remote"
                 :catalog_name "demo.submit"
                 :catalog_fingerprint (make-string 64 ?a)
                 :value secret
                 :index 17)
           (list :fields
                 (list :password field-secret :ordinary "hello"))))
         (printed (prin1-to-string summary)))
    (should (= (plist-get summary :injected_count) 2))
    (should (= (plist-get summary :field_count) 2))
    (should (= (length (plist-get summary :injected)) 2))
    (should (= (length (plist-get summary :fields)) 2))
    (should-not (string-match-p (regexp-quote secret) printed))
    (should-not (string-match-p (regexp-quote field-secret) printed))
    (should-not (string-match-p ":password" printed))
    (should (equal
             (plist-get (aref (plist-get summary :fields) 0) :length)
             (length field-secret)))))

(ert-deftest jetpacs-component-catalog-actions/remote-arm-needs-live-dispatch ()
  (let* ((client (jetpacs-component-catalog-actions-test--client))
         (local "jpcatalog.safety-local")
         (foreign "foreign.safety-local")
         (global "foreign.safety-global"))
    (let ((jetpacs--client client))
      (unwind-protect
          (progn
            (with-jetpacs-owner "jpcatalog"
              (jetpacs-defaction local (lambda (_args _params) 'accepted)))
            (with-jetpacs-owner "foreign"
              (jetpacs-defaction foreign (lambda (_args _params) 'accepted))
              (jetpacs-defaction global (lambda (_args _params) 'accepted)
                                 :any-surface t))
            (should
             (jetpacs-component-catalog-actions-compatible-p
              (jetpacs-component-catalog-actions-test--node-with-action
               (list :action local))
              (list :client client)))
            (should-not
             (jetpacs-component-catalog-actions-compatible-p
              (jetpacs-component-catalog-actions-test--node-with-action
               (list :action foreign))
              (list :client client)))
            (should
             (jetpacs-component-catalog-actions-compatible-p
              (jetpacs-component-catalog-actions-test--node-with-action
               (list :action global))
              (list :client client)))
            (should-not
             (jetpacs-component-catalog-actions-compatible-p
              (jetpacs-component-catalog-actions-test--node-with-action
               '(:action "missing.safety-action"))
              (list :client client)))
            (dolist (policy '("queue" "wake"))
              (let* ((result
                      (jetpacs-component-catalog-actions-compatibility
                       (jetpacs-component-catalog-actions-test--node-with-action
                        (list :action local :when_offline policy
                              :ttl_s 60))
                       (list :client client))))
                (should-not (plist-get result :compatible))
                (should (memq 'durable-policy
                              (jetpacs-component-catalog-actions-test--reasons
                               result))))))
        (dolist (name (list local foreign global))
          (jetpacs-undefaction name))))))

(ert-deftest jetpacs-component-catalog-actions/remote-feature-and-capture-gates ()
  (let* ((client (jetpacs-component-catalog-actions-test--client))
         (name "jpcatalog.safety-capture")
         (input '(:t "text_input" :id "field" :value "seed"))
         (action
          (list :t "button" :label "Run"
                :on_tap
                (list :action name :capture_fields ["field"]
                      :open_surface "app:target")))
         (document (list :t "column" :children (vector input action))))
    (let ((jetpacs--client client))
      (unwind-protect
          (progn
            (with-jetpacs-owner "jpcatalog"
              (jetpacs-defaction name (lambda (_args _params) 'accepted)))
            (let ((result
                   (jetpacs-component-catalog-actions-compatibility
                    document (list :client client))))
              (should-not (plist-get result :compatible))
              (should (memq 'feature-unadvertised
                            (jetpacs-component-catalog-actions-test--reasons
                             result))))
            (setf
             (ebp-client-profiles client)
             '(:app
               (:node_types ["text" "column" "button" "text_input"]
                :builtins [] :features ["action.open_surface"]
                :extensions [])))
            (should
             (jetpacs-component-catalog-actions-compatible-p
              document (list :client client)))
            (let* ((bad (copy-tree document))
                   (button (aref (plist-get bad :children) 1)))
              (setf (plist-get (plist-get button :on_tap) :capture_fields)
                    ["missing"])
              (let ((result
                     (jetpacs-component-catalog-actions-compatibility
                      bad (list :client client))))
                (should-not (plist-get result :compatible))
                (should (memq
                         'capture-invalid
                         (jetpacs-component-catalog-actions-test--reasons
                          result))))))
        (jetpacs-undefaction name)))))

(ert-deftest jetpacs-component-catalog-actions/password-capture-needs-own-submit ()
  (let* ((client (jetpacs-component-catalog-actions-test--client))
         (name "jpcatalog.safety-password-submit")
         (capture (list :action name :capture_fields ["secret"]))
         (document
          (list
           :t "text_input" :id "secret" :password t
           :on_submit (copy-tree capture)
           :semantics
           (list
            :actions
            (vector
             (list :label "Describe"
                   :on_action (list :action name))
             (list :label "Submit another way"
                   :on_action (copy-tree capture)))))))
    (let ((jetpacs--client client))
      (unwind-protect
          (progn
            (with-jetpacs-owner "jpcatalog"
              (jetpacs-defaction name (lambda (_args _params) 'accepted)))
            (let* ((compatibility
                    (jetpacs-component-catalog-actions-compatibility
                     document (list :client client)))
                   (issues (plist-get compatibility :issues)))
              (should-not (plist-get compatibility :compatible))
              (should (= (length issues) 1))
              (should (eq (plist-get (car issues) :reason) 'capture-invalid))
              (should
               (equal (plist-get (car issues) :path)
                      '(:semantics :actions 1 :on_action))))
            (let* ((projection
                    (jetpacs-component-catalog-actions-instrument
                     document nil (list :client client)))
                   (records
                    (jetpacs-component-catalog-actions-descriptors
                     (plist-get projection :document)))
                   (submit
                    (seq-find
                     (lambda (record)
                       (equal (plist-get record :path) '(:on_submit)))
                     records))
                   (semantic
                    (seq-find
                     (lambda (record)
                       (equal (plist-get record :path)
                              '(:semantics :actions 1 :on_action)))
                     records))
                   (submit-trace (plist-get submit :descriptor))
                   (semantic-trace (plist-get semantic :descriptor)))
              (should (equal (plist-get submit-trace :action)
                             "jpcatalog.trace"))
              (should (equal (plist-get submit-trace :capture_fields)
                             ["secret"]))
              (should (equal (plist-get semantic-trace :action)
                             "jpcatalog.trace"))
              (should-not (plist-member semantic-trace :capture_fields))))
        (jetpacs-undefaction name)))))

(ert-deftest jetpacs-component-catalog-actions/builtin-profile-and-context ()
  (let* ((builtins
          ["view.switch" "variant.switch" "surface.open"
           "clipboard.copy" "companion.settings.open" "trigger.fire"
           "dialog.submit" "dialog.dismiss"])
         (client
          (jetpacs-component-catalog-actions-test--client
           :builtins builtins :granted ["triggers"])))
    (should
     (jetpacs-component-catalog-actions-compatible-p
      (jetpacs-component-catalog-actions-test--node-with-action
       '(:builtin "clipboard.copy" :text "safe sample"))
      (list :client client)))
    (let ((result
           (jetpacs-component-catalog-actions-compatibility
            (jetpacs-component-catalog-actions-test--node-with-action
             '(:builtin "share.send" :text "sample"))
            (list :client client))))
      (should-not (plist-get result :compatible))
      (should (memq 'builtin-unadvertised
                    (jetpacs-component-catalog-actions-test--reasons result))))
    ;; Dialog completion is advertised in this intentionally broad fixture,
    ;; but advertisement alone cannot make it valid on an app surface.
    (should-not
     (jetpacs-component-catalog-actions-compatible-p
      (jetpacs-component-catalog-actions-test--node-with-action
       '(:builtin "dialog.dismiss"))
      (list :client client)))
    ;; A value-producing app hook cannot use an otherwise advertised builtin.
    (let ((result
           (jetpacs-component-catalog-actions-compatibility
            '(:t "text_input" :id "field" :value ""
              :on_submit (:builtin "clipboard.copy" :text "fixed"))
            (list :client client))))
      (should (memq 'value-hook-builtin
                    (jetpacs-component-catalog-actions-test--reasons result))))
    ;; The protocol exposes trigger type support but no accepted manual-ID
    ;; registry, so trigger.fire remains trace-only even with its grant.
    (let ((result
           (jetpacs-component-catalog-actions-compatibility
            (jetpacs-component-catalog-actions-test--node-with-action
             '(:builtin "trigger.fire" :id "manual-demo"))
            (list :client client))))
      (should (memq 'trigger-unverifiable
                    (jetpacs-component-catalog-actions-test--reasons result))))))

(ert-deftest jetpacs-component-catalog-actions/builtin-references-resolve ()
  (let* ((client
          (jetpacs-component-catalog-actions-test--client
           :builtins ["view.switch" "variant.switch"]))
         (views
          (jetpacs-multi-view
           (list
            (cons
             "first"
             (jetpacs-button "Next" (jetpacs-view-switch "second")))
            (cons "second" (jetpacs-text "Second")))
           "first"))
         (host
          (jetpacs-variant-host
           "theme-choice" "light"
           (list (jetpacs-variant "light" (jetpacs-text "Light"))
                 (jetpacs-variant "dark" (jetpacs-text "Dark")))))
         (variant-document
          (jetpacs-column
           host
           (jetpacs-button
            "Dark" (jetpacs-variant-switch "theme-choice" :value "dark")))))
    (should
     (jetpacs-component-catalog-actions-compatible-p
      views (list :client client)))
    (should
     (jetpacs-component-catalog-actions-compatible-p
      variant-document (list :client client)))
    (should-not
     (jetpacs-component-catalog-actions-compatible-p
      (jetpacs-component-catalog-actions-test--node-with-action
       '(:builtin "view.switch" :view "missing"))
      (list :client client)))
    (should-not
     (jetpacs-component-catalog-actions-compatible-p
      (jetpacs-component-catalog-actions-test--node-with-action
       '(:builtin "variant.switch" :id "missing"))
      (list :client client)))))

(ert-deftest jetpacs-component-catalog-actions/arming-falls-back-to-trace ()
  (let* ((document
          (jetpacs-component-catalog-actions-test--node-with-action
           '(:action "unregistered.action")))
         (projection
          (jetpacs-component-catalog-actions-instrument
           document t '(:client nil)))
         (descriptor
          (jetpacs-component-catalog-actions-test--only-descriptor
           (plist-get projection :document))))
    (should-not (plist-get projection :armed))
    (should (equal (plist-get descriptor :action) "jpcatalog.trace"))
    (should (equal (plist-get descriptor :when_offline) "drop")))
  ;; A direct caller still gets a safe inert projection for an invalid hook;
  ;; compatibility reports the fault, and instrumentation itself never evals
  ;; or tries to treat the malformed value as a plist.
  (let* ((projection
          (jetpacs-component-catalog-actions-instrument
           '(:t "button" :label "Bad" :on_tap "not-a-descriptor")
           t '(:client nil)))
         (descriptor
          (jetpacs-component-catalog-actions-test--only-descriptor
           (plist-get projection :document))))
    (should-not (plist-get projection :armed))
    (should (equal (plist-get descriptor :action) "jpcatalog.trace"))
    (should (equal (plist-get (plist-get descriptor :args) :catalog_kind)
                   "invalid"))))

(provide 'jetpacs-component-catalog-actions-test)
;;; jetpacs-component-catalog-actions-test.el ends here
