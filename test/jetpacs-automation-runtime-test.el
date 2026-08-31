;;; jetpacs-automation-runtime-test.el --- Durable automation tests -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later

(require 'ert)
(require 'cl-lib)
(require 'ebp)
(require 'jetpacs-automation-runtime)

(defun jetpacs-automation-runtime-test-recipe (&optional text name)
  "Return a host-continuation recipe displaying TEXT under NAME."
  (jetpacs-automation-normalize-recipe
   (list :schema-version 1 :id "user.host-run"
         :name (or name "Host run") :inputs []
         :trigger '(:type "boot" :params nil :when []
                    :policy :queue :ttl-s 86400)
         :steps (vector
                 (list :id "message" :kind :action
                       :action "emacs.message"
                       :args (list :text (or text "ran")))))))

(defmacro jetpacs-automation-runtime-test-with-storage (&rest body)
  "Run BODY with isolated Customize variables and durable files."
  (declare (indent 0) (debug t))
  `(let* ((directory (make-temp-file "jetpacs-automation-test-" t))
          (jetpacs-automation-data-directory
           (file-name-as-directory directory))
          (jetpacs-automation-recipes nil)
          (jetpacs-automation-enabled-recipes nil)
          (jetpacs--client nil))
     (unwind-protect
         (progn
           (jetpacs-automation-runtime-reset)
           (clrhash jetpacs-automation--dry-run-digests)
           ,@body)
       (jetpacs-automation-runtime-reset)
       (delete-directory directory t))))

(ert-deftest jetpacs-automation-save-is-inert-and-current-digest-gated ()
  (jetpacs-automation-runtime-test-with-storage
    (let* ((recipe (jetpacs-automation-runtime-test-recipe))
           (dry (jetpacs-automation-run-dry-run recipe))
           calls)
      (cl-letf (((symbol-function 'jetpacs-settings-save-variable)
                 (lambda (symbol value)
                   (push symbol calls)
                   (set symbol value))))
        (should-error (jetpacs-automation-save-recipe recipe "wrong"))
        (should (equal recipe
                       (jetpacs-automation-save-recipe
                        recipe (plist-get dry :digest)))))
      (should (equal calls '(jetpacs-automation-recipes)))
      (should (= (length jetpacs-automation-recipes) 1))
      (should-not jetpacs-automation-enabled-recipes))))

(ert-deftest jetpacs-automation-executable-edit-disables-before-save ()
  (jetpacs-automation-runtime-test-with-storage
    (let* ((old (jetpacs-automation-runtime-test-recipe "old"))
           (new (jetpacs-automation-runtime-test-recipe "new"))
           (jetpacs-automation-recipes (list old))
           (jetpacs-automation-enabled-recipes '("user.host-run"))
           (proof (jetpacs-automation-run-dry-run new))
           calls)
      (cl-letf (((symbol-function 'jetpacs-settings-save-variable)
                 (lambda (symbol value)
                   (setq calls (append calls (list symbol)))
                   (set symbol value))))
        (jetpacs-automation-save-recipe new (plist-get proof :digest)))
      (should (equal calls '(jetpacs-automation-enabled-recipes
                             jetpacs-automation-recipes)))
      (should-not jetpacs-automation-enabled-recipes)
      (should (equal "new"
                     (jetpacs-automation--plist-fetch
                      (plist-get (aref (plist-get
                                        (car jetpacs-automation-recipes) :steps)
                                       0)
                                 :args)
                      :text))))))

(ert-deftest jetpacs-automation-enable-refuses-missing-device-needs ()
  (jetpacs-automation-runtime-test-with-storage
    (let* ((recipe (jetpacs-automation-runtime-test-recipe))
           (jetpacs-automation-recipes (list recipe)))
      (jetpacs-automation-run-dry-run recipe)
      (cl-letf (((symbol-function 'jetpacs-connected-p) (lambda () t))
                ((symbol-function 'jetpacs-client) (lambda () 'fake-client))
                ((symbol-function 'jetpacs-automation-device-profile)
                 (lambda (&optional _client)
                   '(:trigger-types ("time") :state-types nil
                     :caps nil :trigger-caps nil))))
        (should-error (jetpacs-automation-enable "user.host-run")
                      :type 'jetpacs-automation-unsupported))
      (should-not jetpacs-automation-enabled-recipes))))

(ert-deftest jetpacs-automation-runtime-uses-wire-false-for-capabilities ()
  (let* ((recipe
          (jetpacs-automation-normalize-recipe
           '(:schema-version 1 :id "user.light" :name "Light" :inputs []
             :trigger (:type "boot" :params nil :when []
                       :policy :queue :ttl-s 86400)
             :steps [(:id "off" :kind :action
                      :action "device.flashlight" :args (:on nil))])))
         (step (aref (plist-get recipe :steps) 0))
         (state '(:recipe_id "user.light" :run_id "run"
                  :trigger_source "nil\n" :run_source "nil\n"
                  :outputs_source "[]\n" :path "steps"))
         invoked)
    (cl-letf (((symbol-function 'jetpacs-connected-p) (lambda () t))
              ((symbol-function 'jetpacs-client) (lambda () 'fake-client))
              ((symbol-function 'ebp-client-capability-invoke)
               (lambda (_client _cap &rest options)
                 (setq invoked (plist-get options :args)))))
      (jetpacs-automation--process-action
       nil state recipe [] [] step "steps/off"))
    (should (eq (jetpacs-automation--plist-fetch invoked :on) :false))))

(ert-deftest jetpacs-automation-trigger-admission-freezes-revision ()
  (jetpacs-automation-runtime-test-with-storage
    (let* ((recipe (jetpacs-automation-runtime-test-recipe "old" "Frozen"))
           (profile '(:trigger-types ("boot") :state-types nil
                      :caps nil :trigger-caps nil))
           (plan (jetpacs-automation-compile recipe profile))
           (pairing (make-string 32 ?a))
           (event (make-string 32 ?b))
           (client (ebp-client-create :pairing-id pairing)))
      (jetpacs-automation--ensure-storage)
      (jetpacs-automation--archive-plan plan)
      (let ((jetpacs--client client))
        (should (eq 'accepted
                    (jetpacs-automation--trigger-fired
                     (list :id (plist-get plan :trigger-id)
                           :type "boot" :data '(:secret "old-data"))
                     (list :event_id event :occurred_at_ms 10)))))
      (when (timerp jetpacs-automation--pump-timer)
        (cancel-timer jetpacs-automation--pump-timer)
        (setq jetpacs-automation--pump-timer nil))
      (let* ((work (car (ebp-store-list-work jetpacs-automation--store)))
             (state (jetpacs-automation--work-state-decode
                     (ebp-store-work-payload-json work)))
             (frozen (jetpacs-automation-read-recipe
                      (plist-get state :recipe_source))))
        (should (equal (plist-get frozen :name) "Frozen"))
        (should (string-match-p "old-data" (plist-get state :trigger_source)))))))

(ert-deftest jetpacs-automation-worker-completes-and-redacts-history ()
  (jetpacs-automation-runtime-test-with-storage
    (let* ((recipe (jetpacs-automation-runtime-test-recipe "hello"))
           (plan (jetpacs-automation-compile
                  recipe '(:trigger-types ("boot") :state-types nil
                           :caps nil :trigger-caps nil)))
           (pairing (make-string 32 ?c))
           (event (make-string 32 ?d))
           (client (ebp-client-create :pairing-id pairing))
           messages)
      (jetpacs-automation--ensure-storage)
      (jetpacs-automation--archive-plan plan)
      (let ((jetpacs--client client))
        (should (eq 'accepted
                    (jetpacs-automation--trigger-fired
                     (list :id (plist-get plan :trigger-id) :type "boot"
                           :data '(:secret "NEVER-IN-HISTORY"))
                     (list :event_id event :occurred_at_ms 20)))))
      (when (timerp jetpacs-automation--pump-timer)
        (cancel-timer jetpacs-automation--pump-timer)
        (setq jetpacs-automation--pump-timer nil))
      (cl-letf (((symbol-function 'message)
                 (lambda (format-string &rest values)
                   (push (apply #'format format-string values) messages))))
        (jetpacs-automation-pump))
      (should (member "hello" messages))
      (should-not (ebp-store-list-work jetpacs-automation--store))
      (let ((history (jetpacs-automation-history "user.host-run")))
        (should (= (length history) 1))
        (should (equal (plist-get (car history) :status) "success")))
      (with-temp-buffer
        (insert-file-contents (jetpacs-automation--file "history.json"))
        (should-not (search-forward "NEVER-IN-HISTORY" nil t)))
      ;; A duplicate event is accepted against the durable inbox receipt but
      ;; creates no second work item or history row.
      (let ((jetpacs--client client))
        (should (eq 'accepted
                    (jetpacs-automation--trigger-fired
                     (list :id (plist-get plan :trigger-id) :type "boot"
                           :data '(:secret "NEVER-IN-HISTORY"))
                     (list :event_id event :occurred_at_ms 20)))))
      (when (timerp jetpacs-automation--pump-timer)
        (cancel-timer jetpacs-automation--pump-timer)
        (setq jetpacs-automation--pump-timer nil))
      (jetpacs-automation-pump)
      (should (= (length (jetpacs-automation-history "user.host-run")) 1)))))

(provide 'jetpacs-automation-runtime-test)
;;; jetpacs-automation-runtime-test.el ends here
