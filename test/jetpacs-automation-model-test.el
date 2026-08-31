;;; jetpacs-automation-model-test.el --- Safe automation model tests -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later

(require 'ert)
(require 'cl-lib)
(require 'jetpacs-automation-model)

(defun jetpacs-automation-test-recipe (&optional steps)
  "Return a representative version-1 recipe with STEPS."
  (jetpacs-automation-normalize-recipe
   (list :schema-version 1 :id "user.morning" :name "Morning"
         :inputs [(:name "greeting" :type "text" :value "Good morning")]
         :trigger '(:type "time" :params (:every-s 3600) :when []
                    :policy :queue :ttl-s 86400)
         :steps (or steps
                    [(:id "notify" :kind :action :action "device.notify"
                      :args (:text (:expr
                                    (concat (ref "input.greeting") "!"))))]))))

(ert-deftest jetpacs-authoring-reader-is-one-form-inert-and-throwaway ()
  (let ((name "jetpacs-automation-test-symbol-that-must-not-be-interned"))
    (should-not (intern-soft name))
    (let ((form (jetpacs-authoring-read-one
                 (format "(%s :value 1)" name))))
      (should (equal (symbol-name (car form)) name))
      (should-not (intern-soft name))))
  (should-error (jetpacs-authoring-read-one "(:a 1) (:b 2)")
                :type 'jetpacs-authoring-read-error)
  (should-error (jetpacs-authoring-read-one "#.(progn (error \"ran\") 1)")
                :type 'jetpacs-authoring-read-error)
  (should-error (jetpacs-authoring-read-one "#1=(a . #1#)")
                :type 'jetpacs-authoring-read-error))

(ert-deftest jetpacs-automation-canonical-lisp-round-trips ()
  (let* ((recipe (jetpacs-automation-test-recipe))
         (source (jetpacs-automation-print-recipe recipe))
         (again (jetpacs-automation-read-recipe source)))
    (should (equal recipe again))
    (should (equal source (jetpacs-automation-print-recipe again)))
    (should (equal (jetpacs-automation-recipe-digest recipe)
                   (jetpacs-automation-recipe-digest again)))))

(ert-deftest jetpacs-automation-expression-language-is-closed-and-bounded ()
  (should-error (jetpacs-automation-normalize-expression
                 '(progn (message "not data")))
                :type 'jetpacs-automation-schema-error)
  (should-error (jetpacs-automation-normalize-expression
                 '(mapcar identity (list 1 2)))
                :type 'jetpacs-automation-schema-error)
  (let ((too-many (cons 'concat (make-list 33 "x"))))
    (should-error (jetpacs-automation-normalize-expression too-many)
                  :type 'jetpacs-automation-schema-error))
  (let ((deep "x"))
    (dotimes (_ 9) (setq deep (list 'if t deep deep)))
    (should-error (jetpacs-automation-normalize-expression deep)
                  :type 'jetpacs-automation-schema-error)))

(ert-deftest jetpacs-automation-evaluator-covers-values-and-predicates ()
  (let ((env '(("input.name" . " Ada ")
               ("trigger.data.level" . 17))))
    (should (equal
             "ADA:17"
             (jetpacs-automation-eval-expression
              '(concat (upcase (string-trim (ref "input.name"))) ":"
                       (number-to-string (ref "trigger.data.level")))
              env)))
    (should (eq t (jetpacs-automation-eval-expression
                   '(and (< (ref "trigger.data.level") 20)
                         (starts-with-p "Ada" "Ad")) env)))
    (should (equal [2 3]
                   (jetpacs-automation-eval-expression
                    '(append (list 2) (list 3)) env)))
    (should-error (jetpacs-automation-eval-expression
                   '(ref "run.missing") env)
                  :type 'jetpacs-automation-evaluation-error)))

(ert-deftest jetpacs-automation-references-must-be-lexically-prior ()
  (should-error
   (jetpacs-automation-test-recipe
    [(:id "first" :kind :action :action "emacs.message"
      :args (:text (:expr (ref "step.second.output.text"))))
     (:id "second" :kind :action :action "emacs.message"
      :args (:text "later"))])
   :type 'jetpacs-automation-schema-error)
  (should
   (jetpacs-automation-test-recipe
    [(:id "first" :kind :action :action "emacs.message"
      :args (:text "earlier"))
     (:id "second" :kind :action :action "emacs.message"
      :args (:text (:expr (if (present-p (ref "step.first.output"))
                              "yes" "no"))))])))

(ert-deftest jetpacs-automation-dry-run-is-pure-and-digest-bound ()
  (let* ((recipe (jetpacs-automation-test-recipe))
         (result (jetpacs-automation-dry-run recipe)))
    (should (plist-get result :ok))
    (should (equal (plist-get result :digest)
                   (jetpacs-automation-recipe-digest recipe)))
    (should (equal
             (jetpacs-automation--plist-fetch
              (plist-get (aref (plist-get result :trace) 0) :args) :text)
             "Good morning!"))))

(ert-deftest jetpacs-automation-action-contracts-are-closed-and-resolved ()
  (should-error
   (jetpacs-automation-test-recipe
    [(:id "buzz" :kind :action :action "device.vibrate"
      :args (:ms 0))])
   :type 'jetpacs-automation-schema-error)
  (should-error
   (jetpacs-automation-test-recipe
    [(:id "light" :kind :action :action "device.flashlight"
      :args (:on t :surprise t))])
   :type 'jetpacs-automation-schema-error)
  ;; Dynamic values are legal recipe data, but Dry Run validates the resolved
  ;; value against the same closed contract before Save can be authorized.
  (let* ((recipe
          (jetpacs-automation-test-recipe
           [(:id "bright" :kind :action :action "device.brightness.set"
             :args (:level (:expr (ref "input.greeting"))))]))
         (result (jetpacs-automation-dry-run recipe)))
    (should-not (plist-get result :ok))
    (should (equal (plist-get result :error-kind) "invalid-recipe"))))

(ert-deftest jetpacs-automation-trigger-contracts-and-structure-are-bounded ()
  (let ((recipe (copy-tree (jetpacs-automation-test-recipe) t)))
    (setq recipe
          (plist-put recipe :trigger
                     '(:type "boot" :params (:unknown t) :when []
                       :policy :queue :ttl-s 86400)))
    (should-error (jetpacs-automation-normalize-recipe recipe)
                  :type 'jetpacs-automation-schema-error))
  (let ((steps
         (apply #'vector
                (cl-loop for index below (1+ jetpacs-automation-max-steps)
                         collect
                         (list :id (format "step-%d" index) :kind :action
                               :action "emacs.message" :args '(:text "x"))))))
    (should-error (jetpacs-automation-test-recipe steps)
                  :type 'jetpacs-automation-schema-error)))

(ert-deftest jetpacs-automation-wire-omits-empty-params-and-preserves-false ()
  (let* ((recipe
          (jetpacs-automation-normalize-recipe
           '(:schema-version 1 :id "user.false" :name "False"
             :inputs []
             :trigger (:type "boot" :params nil :when []
                       :policy :queue :ttl-s 86400)
             :steps [(:id "light" :kind :action
                      :action "device.flashlight" :args (:on nil))])))
         (plan (jetpacs-automation-compile
                recipe '(:trigger-types ("boot") :state-types nil
                         :caps ("flashlight")
                         :trigger-caps ("flashlight"))))
         (wire (plist-get plan :wire-trigger))
         (response (aref (plist-get wire :on_fire) 0))
         (args (plist-get response :args)))
    (should-not (jetpacs-automation--plist-present-p wire :params))
    (should (eq (jetpacs-automation--plist-fetch args :on) :false))))

(ert-deftest jetpacs-automation-compiler-splits-maximal-device-prefix ()
  (let* ((recipe
          (jetpacs-automation-test-recipe
           [(:id "notify" :kind :action :action "device.notify"
             :args (:text (:expr
                           (concat (ref "input.greeting") "!"))))
            (:id "host" :kind :action :action "emacs.message"
             :args (:text "continued"))]))
         (profile '(:trigger-types ("time") :state-types nil
                    :caps ("vibrate") :trigger-caps ("vibrate")))
         (plan (jetpacs-automation-compile recipe profile))
         (wire (plist-get plan :wire-trigger)))
    (should (plist-get plan :supported))
    (should (= (plist-get plan :device-prefix-count) 1))
    (should (= (length (plist-get plan :host-steps)) 1))
    (should (equal (plist-get (aref (plist-get wire :on_fire) 0) :notify)
                   '(:text "Good morning!")))))

(ert-deftest jetpacs-automation-compiler-never-translates-unsafe-result-cap ()
  (let* ((recipe
          (jetpacs-automation-test-recipe
           [(:id "volume" :kind :action :action "device.volume.set"
             :args (:stream "music" :level 5))]))
         (profile '(:trigger-types ("time") :state-types nil
                    :caps ("volume.set") :trigger-caps ("volume.set")))
         (plan (jetpacs-automation-compile recipe profile)))
    (should (plist-get plan :supported))
    (should (= (plist-get plan :device-prefix-count) 0))
    (should (= (length (plist-get plan :host-steps)) 1))))

(ert-deftest jetpacs-automation-compiler-honors-device-response-limit ()
  (let* ((recipe
          (jetpacs-automation-test-recipe
           [(:id "first" :kind :action :action "device.vibrate"
             :args (:ms 100))
            (:id "second" :kind :action :action "device.vibrate"
             :args (:ms 200))]))
         (plan
          (jetpacs-automation-compile
           recipe '(:trigger-types ("time") :state-types nil
                    :trackable-state-types nil :caps ("vibrate")
                    :trigger-caps ("vibrate") :max-trigger-responses 1))))
    (should (plist-get plan :supported))
    (should (= (plist-get plan :device-prefix-count) 1))
    (should (= (length (plist-get plan :host-steps)) 1))))

(ert-deftest jetpacs-automation-compiler-refuses-unavailable-portable-needs ()
  (let* ((recipe
          (jetpacs-automation-test-recipe
           [(:id "buzz" :kind :action :action "device.vibrate"
             :args (:ms 250))]))
         (plan (jetpacs-automation-compile
                recipe '(:trigger-types ("boot") :state-types nil
                         :caps nil :trigger-caps nil))))
    (should-not (plist-get plan :supported))
    (should (member "trigger:time" (plist-get plan :missing)))
    (should (member "cap:vibrate" (plist-get plan :missing)))))

(ert-deftest jetpacs-automation-compiler-finds-nested-device-needs ()
  (let* ((recipe
          (jetpacs-automation-test-recipe
           [(:id "branch" :kind :if :condition t
             :then [(:id "light" :kind :action
                     :action "device.flashlight" :args (:on t))]
             :else [])]))
         (plan
          (jetpacs-automation-compile
           recipe '(:trigger-types ("time") :state-types nil
                    :trackable-state-types nil :caps nil
                    :trigger-caps nil :max-trigger-responses 8))))
    (should-not (plist-get plan :supported))
    (should (member "cap:flashlight" (plist-get plan :missing)))))

(ert-deftest jetpacs-automation-compiler-checks-state-edge-trackability ()
  (let* ((recipe (copy-tree (jetpacs-automation-test-recipe []) t))
         (trigger '(:type "state.edge"
                    :params (:when [(:type "battery.level" :below 20)]
                             :edge "rise")
                    :when [] :policy :queue :ttl-s 86400)))
    (setq recipe (jetpacs-automation-normalize-recipe
                  (plist-put recipe :trigger trigger)))
    (let ((missing
           (jetpacs-automation-compile
            recipe '(:trigger-types ("state.edge") :state-types nil
                     :trackable-state-types ("power")
                     :caps nil :trigger-caps nil))))
      (should-not (plist-get missing :supported))
      (should (member "trackable-state:battery.level"
                      (plist-get missing :missing))))
    (should
     (plist-get
      (jetpacs-automation-compile
       recipe '(:trigger-types ("state.edge") :state-types nil
                :trackable-state-types ("battery.level")
                :caps nil :trigger-caps nil))
      :supported))))

(ert-deftest jetpacs-automation-execution-digest-ignores-only-name ()
  (let* ((recipe (jetpacs-automation-test-recipe))
         (renamed (copy-tree recipe t)))
    (setq renamed (plist-put renamed :name "Renamed"))
    (should-not (equal (jetpacs-automation-recipe-digest recipe)
                       (jetpacs-automation-recipe-digest renamed)))
    (should (equal (jetpacs-automation-execution-digest recipe)
                   (jetpacs-automation-execution-digest renamed)))))

(provide 'jetpacs-automation-model-test)
;;; jetpacs-automation-model-test.el ends here
