;;; jetpacs-automation-model.el --- Safe automation recipe model -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later
;; Package-Requires: ((emacs "30.1"))

;;; Commentary:

;; The versioned, inert Lisp representation behind Jetpacs Automations.  This
;; package owns normalization, validation, a deliberately closed expression
;; interpreter, dry-run traces, and compilation to the existing EBP trigger
;; vocabulary.  It never calls `eval' and never treats authored symbols as
;; functions.

;;; Code:

(require 'cl-lib)
(require 'seq)
(require 'subr-x)
(require 'jetpacs-authoring)

(define-error 'jetpacs-automation-error "Automation error")
(define-error 'jetpacs-automation-schema-error "Invalid automation recipe"
  'jetpacs-automation-error)
(define-error 'jetpacs-automation-evaluation-error
  "Automation expression failed" 'jetpacs-automation-error)
(define-error 'jetpacs-automation-unsupported "Automation is unavailable"
  'jetpacs-automation-error)

(defconst jetpacs-automation-schema-version 1
  "Current canonical automation recipe schema version.")
(defconst jetpacs-automation-max-recipe-bytes 65536
  "Maximum canonical recipe size in UTF-8 bytes.")
(defconst jetpacs-automation-max-expression-bytes 8192
  "Maximum canonical size of one expression in UTF-8 bytes.")
(defconst jetpacs-automation-max-expression-depth 8
  "Maximum nested expression depth.")
(defconst jetpacs-automation-max-expression-nodes 128
  "Maximum nodes in one expression tree.")
(defconst jetpacs-automation-max-variadic-args 32
  "Maximum arguments to a variadic expression operator.")
(defconst jetpacs-automation-max-inputs 64
  "Maximum configurable inputs in one recipe.")
(defconst jetpacs-automation-max-steps 128
  "Maximum action and conditional nodes in one recipe.")
(defconst jetpacs-automation-max-step-depth 16
  "Maximum conditional nesting depth in one recipe.")
(defconst jetpacs-automation-max-leaf-string-bytes 2048
  "Maximum UTF-8 byte length of a recipe string leaf.")
(defconst jetpacs-automation-max-result-cost 20000
  "Maximum cumulative size cost of expression intermediates.")
(defconst jetpacs-automation-max-safe-integer 9007199254740991
  "Largest exactly interoperable JSON integer.")
(defconst jetpacs-automation-default-ttl-s 86400
  "Default durable trigger time-to-live, in seconds.")

(defvar jetpacs-automation-trigger-registry (make-hash-table :test #'equal)
  "Trigger type to portable authoring descriptor.")
(defvar jetpacs-automation-action-registry (make-hash-table :test #'equal)
  "Action name to explicitly automation-safe descriptor.")

(defconst jetpacs-automation--missing (make-symbol "missing")
  "Private sentinel distinguishing a missing plist member from nil.")
(defvar jetpacs-automation--data-obarray (make-vector 257 0)
  "Private stable obarray for normalized user-authored object keys.")

(defconst jetpacs-automation--operators
  '("and" "or" "not" "present-p" "equal" "=" "<" "<=" ">" ">="
    "member-p" "contains-p" "starts-with-p" "ends-with-p" "ref" "if"
    "concat" "string-trim" "downcase" "upcase" "replace-literal"
    "number-to-string" "list" "append" "nth" "length" "join" "+" "-"
    "*" "min" "max" "clamp")
  "The complete expression language; every other call form is rejected.")

;;;; Inert plist and scalar helpers

(defun jetpacs-automation--symbol-name (value)
  "Return VALUE's symbol name, or nil."
  (and (symbolp value) (symbol-name value)))

(defun jetpacs-automation--key-name (key)
  "Return KEY's colon-prefixed name, or nil when it is not a data key."
  (let ((name (jetpacs-automation--symbol-name key)))
    (and name (string-prefix-p ":" name) name)))

(defun jetpacs-automation--key-equal-p (left right)
  "Whether plist keys LEFT and RIGHT have the same printed name."
  (equal (jetpacs-automation--key-name left)
         (jetpacs-automation--key-name right)))

(defun jetpacs-automation--plist-p (value)
  "Whether VALUE is a proper even plist with colon-prefixed symbol keys."
  (and (proper-list-p value)
       (cl-evenp (length value))
       (cl-loop for (key _value) on value by #'cddr
                always (jetpacs-automation--key-name key))))

(defun jetpacs-automation--plist-fetch (plist key)
  "Return PLIST's value for name-equivalent KEY, or the missing sentinel."
  (let ((tail plist)
        (result jetpacs-automation--missing))
    (while tail
      (when (jetpacs-automation--key-equal-p (car tail) key)
        (setq result (cadr tail)
              tail nil))
      (when tail (setq tail (cddr tail))))
    result))

(defun jetpacs-automation--plist-present-p (plist key)
  "Whether PLIST contains name-equivalent KEY."
  (not (eq (jetpacs-automation--plist-fetch plist key)
           jetpacs-automation--missing)))

(defun jetpacs-automation--schema-error (path kind)
  "Signal a bounded schema error identifying PATH and KIND."
  (signal 'jetpacs-automation-schema-error
          (list (format "%s: %s" path kind))))

(defun jetpacs-automation--require-object (value path allowed)
  "Require VALUE to be a duplicate-free plist restricted to ALLOWED keys.
Return VALUE.  Key equality is by name so throwaway-obarray symbols are safe."
  (unless (jetpacs-automation--plist-p value)
    (jetpacs-automation--schema-error path "must be a plist"))
  (let (seen)
    (cl-loop for (key _value) on value by #'cddr
             for name = (jetpacs-automation--key-name key)
             do (when (member name seen)
                  (jetpacs-automation--schema-error path
                                                     "has a duplicate key"))
             do (push name seen)
             do (unless (member name allowed)
                  (jetpacs-automation--schema-error
                   path (format "unknown key %s" name)))))
  value)

(defun jetpacs-automation--required (plist key path)
  "Return required KEY from PLIST or signal at PATH."
  (let ((value (jetpacs-automation--plist-fetch plist key)))
    (when (eq value jetpacs-automation--missing)
      (jetpacs-automation--schema-error path
                                        (format "missing %s" key)))
    value))

(defun jetpacs-automation--string (value path &optional nonempty)
  "Return bounded string VALUE or signal at PATH.
When NONEMPTY is non-nil, reject the empty string."
  (unless (and (stringp value)
               (or (not nonempty) (not (string-empty-p value)))
               (<= (string-bytes value)
                   jetpacs-automation-max-leaf-string-bytes))
    (jetpacs-automation--schema-error path "must be a bounded string"))
  (substring-no-properties value))

(defun jetpacs-automation--identifier (value path)
  "Return checked dotted-capable identifier string VALUE or signal at PATH."
  (setq value (jetpacs-automation--string value path t))
  (unless (and (<= (string-bytes value) 128)
               (string-match-p
                "\\`[A-Za-z0-9][A-Za-z0-9._:/-]*\\'" value))
    (jetpacs-automation--schema-error path "must be an identifier"))
  value)

(defun jetpacs-automation--safe-number-p (value)
  "Whether VALUE is a finite interoperable JSON number."
  (and (numberp value)
       (= value value)
       (<= (abs value) jetpacs-automation-max-safe-integer)))

(defun jetpacs-automation--canonical-bool (value path)
  "Return VALUE as canonical t/nil, accepting a throwaway-obarray `t'."
  (cond ((or (null value)
             (and (symbolp value) (equal (symbol-name value) "nil"))) nil)
        ((and (symbolp value) (equal (symbol-name value) "t")) t)
        (t (jetpacs-automation--schema-error path "must be boolean"))))

(defun jetpacs-automation--canonical-key (name)
  "Return a stable private plist key whose printed name is NAME.
The package-private obarray makes separately normalized recipes structurally
equal without interning user-authored field names into Emacs's global obarray."
  (intern name jetpacs-automation--data-obarray))

(defun jetpacs-automation--literal (value path &optional depth)
  "Normalize bounded inert JSON-like VALUE at PATH.
DEPTH is internal recursion state.  Objects become name-sorted plists and
arrays remain vectors.  Lists are reserved for expression call forms."
  (let ((depth (or depth 0)))
    (when (> depth jetpacs-automation-max-expression-depth)
      (jetpacs-automation--schema-error path "data nesting is too deep"))
    (cond
     ((or (null value)
          (and (symbolp value) (equal (symbol-name value) "nil"))) nil)
     ((and (symbolp value) (equal (symbol-name value) "t")) t)
     ((and (symbolp value)
           (member (symbol-name value) '(":false" ":json-false"))) nil)
     ((stringp value) (jetpacs-automation--string value path))
     ((numberp value)
      (unless (jetpacs-automation--safe-number-p value)
        (jetpacs-automation--schema-error path "number is not JSON-safe"))
      value)
     ((vectorp value)
      (when (> (length value) jetpacs-automation-max-variadic-args)
        (jetpacs-automation--schema-error path "array is too long"))
      (apply #'vector
             (cl-loop for item across value for index from 0
                      collect (jetpacs-automation--literal
                               item (format "%s[%d]" path index) (1+ depth)))))
     ((jetpacs-automation--plist-p value)
      (let (pairs seen)
        (cl-loop for (key item) on value by #'cddr
                 for name = (jetpacs-automation--key-name key)
                 do (when (member name seen)
                      (jetpacs-automation--schema-error path
                                                         "has a duplicate key"))
                 do (push name seen)
                 do (push (cons name
                                (jetpacs-automation--literal
                                 item (concat path name) (1+ depth)))
                          pairs))
        (cl-loop for (name . item) in (sort pairs
                                             (lambda (a b)
                                               (string< (car a) (car b))))
                 append (list (jetpacs-automation--canonical-key name) item))))
     (t (jetpacs-automation--schema-error path
                                           "must be inert JSON-like data")))))

;;;; Portable registries

(defun jetpacs-automation-register-trigger (descriptor)
  "Register one portable trigger DESCRIPTOR and return it.
DESCRIPTOR requires identifier string `:type', bounded `:label', and may carry
GUI-only `:fields' metadata.  Registration does not imply current-device
availability."
  (let* ((type (jetpacs-automation--identifier
                (plist-get descriptor :type) "trigger descriptor :type"))
         (label (jetpacs-automation--string
                 (plist-get descriptor :label) "trigger descriptor :label" t)))
    (setq descriptor (copy-tree descriptor))
    (setq descriptor (plist-put descriptor :type type))
    (setq descriptor (plist-put descriptor :label label))
    (puthash type descriptor jetpacs-automation-trigger-registry)
    descriptor))

(defun jetpacs-automation-register-action (descriptor)
  "Register one explicitly automation-safe action DESCRIPTOR and return it.
DESCRIPTOR requires `:action', `:label', and an `:executor' of `device' or
`host'.  A device action carries either `:notify' or a string `:cap'.  Only a
descriptor with `:trigger-safe' non-nil may enter an unattended `on_fire'."
  (let* ((name (jetpacs-automation--identifier
                (plist-get descriptor :action) "action descriptor :action"))
         (label (jetpacs-automation--string
                 (plist-get descriptor :label) "action descriptor :label" t))
         (executor (plist-get descriptor :executor)))
    (unless (memq executor '(device host))
      (jetpacs-automation--schema-error
       "action descriptor :executor" "must be device or host"))
    (when (and (eq executor 'device)
               (not (or (plist-get descriptor :notify)
                        (stringp (plist-get descriptor :cap)))))
      (jetpacs-automation--schema-error
       "action descriptor" "device action needs :notify or :cap"))
    (setq descriptor (copy-tree descriptor))
    (setq descriptor (plist-put descriptor :action name))
    (setq descriptor (plist-put descriptor :label label))
    (puthash name descriptor jetpacs-automation-action-registry)
    descriptor))

(defun jetpacs-automation-trigger-descriptor (type)
  "Return the portable descriptor for trigger TYPE, or nil."
  (and (stringp type) (gethash type jetpacs-automation-trigger-registry)))

(defun jetpacs-automation-action-descriptor (action)
  "Return the automation descriptor for ACTION, or nil."
  (and (stringp action) (gethash action jetpacs-automation-action-registry)))

(defun jetpacs-automation-trigger-descriptors ()
  "Return portable trigger descriptors sorted by label."
  (sort (hash-table-values jetpacs-automation-trigger-registry)
        (lambda (a b) (string< (plist-get a :label) (plist-get b :label)))))

(defun jetpacs-automation-action-descriptors ()
  "Return registered action descriptors sorted by label."
  (sort (hash-table-values jetpacs-automation-action-registry)
        (lambda (a b) (string< (plist-get a :label) (plist-get b :label)))))

;;;; Expression normalization and references

(defun jetpacs-automation--operator-arity (operator count path)
  "Validate OPERATOR's argument COUNT at PATH."
  (let ((valid
         (pcase operator
           ((or "not" "present-p" "string-trim" "downcase" "upcase"
                "number-to-string" "length") (= count 1))
           ((or "equal" "=" "<" "<=" ">" ">=" "member-p" "contains-p"
                "starts-with-p" "ends-with-p" "join") (= count 2))
           ((or "ref") (= count 1))
           ((or "if" "replace-literal" "clamp") (= count 3))
           ("nth" (memq count '(2 3)))
           ((or "min" "max") (<= 1 count jetpacs-automation-max-variadic-args))
           ((or "-" "append") (<= 1 count jetpacs-automation-max-variadic-args))
           ((or "and" "or" "concat" "list" "+" "*")
            (<= count jetpacs-automation-max-variadic-args))
           (_ nil))))
    (unless valid
      (jetpacs-automation--schema-error path
                                         "operator has the wrong arity"))))

(defun jetpacs-automation-normalize-expression (expression &optional path)
  "Return canonical closed-language EXPRESSION or signal.
PATH defaults to `expression'.  Resource bounds are enforced before the
expression can enter a recipe or interpreter."
  (let ((nodes 0)
        (path (or path "expression")))
    (cl-labels
        ((walk
          (value here depth)
          (cl-incf nodes)
          (when (> nodes jetpacs-automation-max-expression-nodes)
            (jetpacs-automation--schema-error here "has too many nodes"))
          (when (> depth jetpacs-automation-max-expression-depth)
            (jetpacs-automation--schema-error here "is too deeply nested"))
          (cond
           ((or (null value)
                (and (symbolp value) (equal (symbol-name value) "nil"))) nil)
           ((and (symbolp value) (equal (symbol-name value) "t")) t)
           ((stringp value) (jetpacs-automation--string value here))
           ((numberp value)
            (unless (jetpacs-automation--safe-number-p value)
              (jetpacs-automation--schema-error here "number is not JSON-safe"))
            value)
           ((consp value)
            (unless (proper-list-p value)
              (jetpacs-automation--schema-error here "must be a proper call"))
            (let* ((raw-op (car value))
                   (operator (jetpacs-automation--symbol-name raw-op))
                   (arguments (cdr value)))
              (unless (member operator jetpacs-automation--operators)
                (jetpacs-automation--schema-error here
                                                   "operator is not allowed"))
              (jetpacs-automation--operator-arity
               operator (length arguments) here)
              (when (equal operator "ref")
                (unless (stringp (car arguments))
                  (jetpacs-automation--schema-error here
                                                     "ref path must be a string")))
              (cons (intern operator)
                    (cl-loop for argument in arguments for index from 0
                             collect (walk argument
                                           (format "%s[%d]" here index)
                                           (1+ depth))))))
           (t (jetpacs-automation--schema-error here
                                                 "unsupported expression value")))))
      (let ((normalized (walk expression path 1)))
        (when (> (string-bytes (jetpacs-authoring-print
                                normalized jetpacs-automation-max-recipe-bytes))
                 jetpacs-automation-max-expression-bytes)
          (jetpacs-automation--schema-error path "is too large"))
        normalized))))

(defun jetpacs-automation--expression-refs (expression)
  "Return all string reference paths in normalized EXPRESSION."
  (let (refs)
    (cl-labels ((walk (form)
                  (when (consp form)
                    (if (eq (car form) 'ref)
                        (push (cadr form) refs)
                      (mapc #'walk (cdr form))))))
      (walk expression))
    (nreverse refs)))

;;;; Recipe normalization

(defun jetpacs-automation--normalize-input (input index)
  "Normalize INPUT at INDEX."
  (let ((path (format "recipe.inputs[%d]" index)))
    (jetpacs-automation--require-object
     input path '(":name" ":type" ":value" ":label" ":doc" ":options"))
    (let* ((name (jetpacs-automation--identifier
                  (jetpacs-automation--required input :name path)
                  (concat path ".name")))
           (type (jetpacs-automation--string
                  (jetpacs-automation--required input :type path)
                  (concat path ".type") t))
           (value (jetpacs-automation--literal
                   (jetpacs-automation--required input :value path)
                   (concat path ".value")))
           (label (jetpacs-automation--plist-fetch input :label))
           (doc (jetpacs-automation--plist-fetch input :doc))
           (options (jetpacs-automation--plist-fetch input :options)))
      (unless (member type '("text" "number" "bool" "enum" "list"))
        (jetpacs-automation--schema-error (concat path ".type")
                                           "unknown input type"))
      (pcase type
        ("text" (unless (stringp value)
                  (jetpacs-automation--schema-error path "text value must be a string")))
        ("number" (unless (numberp value)
                    (jetpacs-automation--schema-error path "number value must be numeric")))
        ("bool" (unless (memq value '(nil t))
                  (jetpacs-automation--schema-error path "bool value must be boolean")))
        ("list" (unless (and (vectorp value)
                             (seq-every-p
                              (lambda (x) (or (stringp x) (numberp x)
                                              (memq x '(nil t))))
                              value))
                  (jetpacs-automation--schema-error
                   path "list value must contain only scalar literals")))
        ("enum"
         (when (eq options jetpacs-automation--missing)
           (jetpacs-automation--schema-error path "enum requires options"))))
      (unless (eq options jetpacs-automation--missing)
        (setq options (jetpacs-automation--literal options
                                                    (concat path ".options")))
        (unless (and (vectorp options) (> (length options) 0)
                     (seq-some (lambda (x) (equal x value)) options))
          (jetpacs-automation--schema-error
           path "options must be nonempty and contain the value")))
      (append (list :name name :type type :value value)
              (unless (eq label jetpacs-automation--missing)
                (list :label (jetpacs-automation--string label
                                                         (concat path ".label"))))
              (unless (eq doc jetpacs-automation--missing)
                (list :doc (jetpacs-automation--string doc
                                                       (concat path ".doc"))))
              (unless (eq options jetpacs-automation--missing)
                (list :options options))))))

(defun jetpacs-automation--normalize-expression-wrapper (value path)
  "Normalize one action argument VALUE at PATH."
  (if (and (jetpacs-automation--plist-p value)
           (= (length value) 2)
           (jetpacs-automation--plist-present-p value :expr))
      (list :expr
            (jetpacs-automation-normalize-expression
             (jetpacs-automation--required value :expr path)
             (concat path ".expr")))
    (jetpacs-automation--literal value path)))

(defun jetpacs-automation--normalize-args (args path)
  "Normalize action ARGS object at PATH."
  (unless (jetpacs-automation--plist-p args)
    (jetpacs-automation--schema-error path "must be a plist"))
  (let (pairs seen)
    (cl-loop for (key value) on args by #'cddr
             for name = (jetpacs-automation--key-name key)
             do (when (member name seen)
                  (jetpacs-automation--schema-error path "has a duplicate key"))
             do (push name seen)
             do (push (cons name
                            (jetpacs-automation--normalize-expression-wrapper
                             value (concat path name)))
                      pairs))
    (cl-loop for (name . value) in (sort pairs
                                             (lambda (a b)
                                               (string< (car a) (car b))))
             append (list (jetpacs-automation--canonical-key name) value))))

(defun jetpacs-automation--expression-wrapper-p (value)
  "Whether VALUE is one canonical dynamic action argument."
  (and (jetpacs-automation--plist-p value)
       (= (length value) 2)
       (jetpacs-automation--plist-present-p value :expr)))

(defun jetpacs-automation--action-args-shape (args allowed required path)
  "Validate closed action ARGS at PATH against ALLOWED and REQUIRED keys."
  (jetpacs-automation--require-object args path allowed)
  (dolist (key required)
    (unless (jetpacs-automation--plist-present-p args key)
      (jetpacs-automation--schema-error
       path (format "missing %s" (symbol-name key)))))
  args)

(defun jetpacs-automation--validate-static-arg
    (args key path allow-expressions predicate description &optional optional)
  "Validate ARGS KEY with PREDICATE unless it is an allowed expression.
DESCRIPTION is used in bounded schema errors.  OPTIONAL permits absence."
  (let ((value (jetpacs-automation--plist-fetch args key)))
    (cond
     ((and optional (eq value jetpacs-automation--missing)) nil)
     ((and allow-expressions
           (jetpacs-automation--expression-wrapper-p value)) value)
     ((funcall predicate value) value)
     (t (jetpacs-automation--schema-error path description)))))

(defun jetpacs-automation--validate-vibration-pattern (value)
  "Whether VALUE is a bounded Companion vibration pattern."
  (and (vectorp value)
       (<= 1 (length value) 64)
       (seq-every-p (lambda (item)
                      (and (integerp item) (<= 0 item 60000)))
                    value)
       (<= (cl-loop for item across value sum item) 60000)))

(defun jetpacs-automation-validate-action-args
    (action args &optional allow-expressions path)
  "Validate ACTION's closed ARGS contract and return ARGS.
When ALLOW-EXPRESSIONS is non-nil, a canonical `(:expr ...)' may stand in for
any field value, but required fields and the closed member set are still
checked.  PATH defaults to `args'.  This mirrors the Companion capability
catalog so bad resolved values are rejected before any side effect."
  (let ((path (or path "args"))
        (stringp* (lambda (value) (stringp value)))
        (boolp (lambda (value) (memq value '(nil t)))))
    (pcase action
      ((or "device.notify" "emacs.message")
       (jetpacs-automation--action-args-shape
        args (if (equal action "device.notify") '(":text" ":title")
               '(":text"))
        '(:text) path)
       (jetpacs-automation--validate-static-arg
        args :text (concat path ".text") allow-expressions stringp*
        "must be a string")
       (when (equal action "device.notify")
         (jetpacs-automation--validate-static-arg
          args :title (concat path ".title") allow-expressions stringp*
          "must be a string" t)))
      ("device.vibrate"
       (let* ((present
               (seq-filter
                (lambda (key)
                  (jetpacs-automation--plist-present-p args key))
                '(:ms :pattern)))
              (selector (car present)))
         (unless (= (length present) 1)
           (jetpacs-automation--schema-error
            path "requires exactly one selector"))
         (jetpacs-automation--action-args-shape
          args (list (symbol-name selector)) (list selector) path)
         (if (eq selector :ms)
             (jetpacs-automation--validate-static-arg
              args :ms (concat path ".ms") allow-expressions
              (lambda (value) (and (integerp value) (<= 1 value 60000)))
              "must be an integer from 1 to 60000")
           (jetpacs-automation--validate-static-arg
            args :pattern (concat path ".pattern") allow-expressions
            #'jetpacs-automation--validate-vibration-pattern
            "must be 1..64 integer durations totaling at most 60000 ms"))))
      ("device.volume.set"
       (jetpacs-automation--action-args-shape
        args '(":stream" ":level") '(:stream :level) path)
       (jetpacs-automation--validate-static-arg
        args :stream (concat path ".stream") allow-expressions
        (lambda (value)
          (and (stringp value)
               (member value '("music" "ring" "alarm" "notification"
                               "call" "system"))))
        "must be a supported stream")
       (jetpacs-automation--validate-static-arg
        args :level (concat path ".level") allow-expressions
        (lambda (value)
          (and (integerp value) (<= 0 value 2147483647)))
        "must be a nonnegative 32-bit integer"))
      ("device.tts.speak"
       (jetpacs-automation--action-args-shape
        args '(":text" ":pitch" ":rate") '(:text) path)
       (jetpacs-automation--validate-static-arg
        args :text (concat path ".text") allow-expressions stringp*
        "must be a string")
       (dolist (key '(:pitch :rate))
         (jetpacs-automation--validate-static-arg
          args key (concat path (symbol-name key)) allow-expressions
          (lambda (value)
            (and (jetpacs-automation--safe-number-p value)
                 (<= 0.5 value 2.0)))
          "must be a number from 0.5 to 2.0" t)))
      ((or "device.ringer.mode" "device.dnd.set")
       (let ((allowed (if (equal action "device.ringer.mode")
                          '("normal" "vibrate" "silent")
                        '("on" "off" "priority"))))
         (jetpacs-automation--action-args-shape
          args '(":mode") '(:mode) path)
         (jetpacs-automation--validate-static-arg
          args :mode (concat path ".mode") allow-expressions
          (lambda (value) (and (stringp value) (member value allowed)))
          "must be a supported mode")))
      ((or "device.flashlight" "device.screen.keep_on")
       (jetpacs-automation--action-args-shape args '(":on") '(:on) path)
       (jetpacs-automation--validate-static-arg
        args :on (concat path ".on") allow-expressions boolp
        "must be boolean"))
      ("device.media.key"
       (jetpacs-automation--action-args-shape args '(":key") '(:key) path)
       (jetpacs-automation--validate-static-arg
        args :key (concat path ".key") allow-expressions
        (lambda (value)
          (and (stringp value)
               (member value '("play_pause" "play" "pause" "next"
                               "previous" "stop" "fast_forward" "rewind"))))
        "must be a supported media key"))
      ("device.brightness.set"
       (jetpacs-automation--action-args-shape
        args '(":level") '(:level) path)
       (jetpacs-automation--validate-static-arg
        args :level (concat path ".level") allow-expressions
        (lambda (value) (and (integerp value) (<= 0 value 255)))
        "must be an integer from 0 to 255"))
      ("emacs.theme.load"
       (jetpacs-automation--action-args-shape
        args '(":theme") '(:theme) path)
       (jetpacs-automation--validate-static-arg
        args :theme (concat path ".theme") allow-expressions stringp*
        "must be a string"))
      (_ (jetpacs-automation--schema-error path
                                            "action has no closed args schema"))))
  args)

(defun jetpacs-automation--normalize-step (step path ids &optional depth)
  "Normalize STEP at PATH, recording every id in hash table IDS."
  (setq depth (or depth 0))
  (when (> depth jetpacs-automation-max-step-depth)
    (jetpacs-automation--schema-error path "conditional nesting is too deep"))
  (unless (jetpacs-automation--plist-p step)
    (jetpacs-automation--schema-error path "must be a plist"))
  (let* ((kind-raw (jetpacs-automation--required step :kind path))
         (kind-name (and (symbolp kind-raw) (symbol-name kind-raw)))
         (kind (pcase kind-name (":action" :action) (":if" :if) (_ nil))))
    (unless kind
      (jetpacs-automation--schema-error (concat path ".kind")
                                         "must be :action or :if"))
    (jetpacs-automation--require-object
     step path (if (eq kind :action)
                   '(":id" ":kind" ":action" ":args")
                 '(":id" ":kind" ":condition" ":then" ":else")))
    (let ((id (jetpacs-automation--identifier
               (jetpacs-automation--required step :id path)
               (concat path ".id"))))
      (when (gethash id ids)
        (jetpacs-automation--schema-error path "step id is duplicated"))
      (puthash id t ids)
      (when (> (hash-table-count ids) jetpacs-automation-max-steps)
        (jetpacs-automation--schema-error "recipe.steps" "has too many steps"))
      (if (eq kind :action)
          (let ((action (jetpacs-automation--identifier
                         (jetpacs-automation--required step :action path)
                         (concat path ".action")))
                (args (jetpacs-automation--required step :args path)))
            (unless (jetpacs-automation-action-descriptor action)
              (jetpacs-automation--schema-error
               (concat path ".action") "action is not registered"))
            (let ((normalized-args
                   (jetpacs-automation--normalize-args
                    args (concat path ".args"))))
              (jetpacs-automation-validate-action-args
               action normalized-args t (concat path ".args"))
              (list :id id :kind :action :action action
                    :args normalized-args)))
        (let ((condition (jetpacs-automation-normalize-expression
                          (jetpacs-automation--required step :condition path)
                          (concat path ".condition")))
              (then (jetpacs-automation--required step :then path))
              (else (jetpacs-automation--required step :else path)))
          (unless (and (vectorp then) (vectorp else))
            (jetpacs-automation--schema-error path
                                               "then and else must be vectors"))
          (list :id id :kind :if :condition condition
                :then (apply #'vector
                             (cl-loop for child across then for index from 0
                                      collect (jetpacs-automation--normalize-step
                                               child
                                               (format "%s.then[%d]" path index)
                                               ids (1+ depth))))
                :else (apply #'vector
                             (cl-loop for child across else for index from 0
                                      collect (jetpacs-automation--normalize-step
                                               child
                                               (format "%s.else[%d]" path index)
                                               ids (1+ depth))))))))))

(defun jetpacs-automation--validate-ref (ref input-names prior path)
  "Validate REF against INPUT-NAMES and lexically PRIOR step ids at PATH."
  (cond
   ((string-prefix-p "input." ref)
    (unless (member (substring ref 6) input-names)
      (jetpacs-automation--schema-error path "references an unknown input")))
   ((string-prefix-p "trigger.data." ref) t)
   ((or (equal ref "trigger.data")
        (equal ref "run")
        (string-prefix-p "run." ref)) t)
   ((string-prefix-p "step." ref)
    (unless (string-match "\\`step\\.\\([A-Za-z0-9._:/-]+\\)\\.output\\(?:\\..*\\)?\\'" ref)
      (jetpacs-automation--schema-error path "has a malformed step reference"))
    (unless (member (match-string 1 ref) prior)
      (jetpacs-automation--schema-error path
                                         "step reference is not lexically prior")))
   (t (jetpacs-automation--schema-error path "reference namespace is unknown"))))

(defun jetpacs-automation--validate-refs-in-args (args input-names prior path)
  "Validate references inside ARGS at PATH."
  (cl-loop for (_key value) on args by #'cddr
           for index from 0
           when (and (jetpacs-automation--plist-p value)
                     (jetpacs-automation--plist-present-p value :expr))
           do (dolist (ref (jetpacs-automation--expression-refs
                            (jetpacs-automation--plist-fetch value :expr)))
                (jetpacs-automation--validate-ref
                 ref input-names prior (format "%s[%d]" path index)))))

(defun jetpacs-automation--validate-step-refs (steps input-names prior path)
  "Validate reference order for STEPS and return ids visible afterward."
  (let ((visible (copy-sequence prior)))
    (cl-loop for step across steps for index from 0
             for here = (format "%s[%d]" path index)
             do (if (eq (plist-get step :kind) :action)
                    (progn
                      (jetpacs-automation--validate-refs-in-args
                       (plist-get step :args) input-names visible here)
                      (setq visible (append visible (list (plist-get step :id)))))
                  (dolist (ref (jetpacs-automation--expression-refs
                                (plist-get step :condition)))
                    (jetpacs-automation--validate-ref
                     ref input-names visible (concat here ".condition")))
                  ;; Branch-local outputs remain branch-local and do not leak
                  ;; into the following outer lexical sequence.
                  (jetpacs-automation--validate-step-refs
                   (plist-get step :then) input-names visible (concat here ".then"))
                  (jetpacs-automation--validate-step-refs
                   (plist-get step :else) input-names visible (concat here ".else"))))
    visible))

(defun jetpacs-automation--field (object key)
  "Return OBJECT's KEY or the private missing sentinel."
  (jetpacs-automation--plist-fetch object key))

(defun jetpacs-automation--integer-field (object key path minimum maximum)
  "Require OBJECT's KEY to be an integer in MINIMUM..MAXIMUM."
  (let ((value (jetpacs-automation--field object key)))
    (unless (and (integerp value) (<= minimum value maximum))
      (jetpacs-automation--schema-error path "must be an in-range integer"))
    value))

(defun jetpacs-automation--enum-field
    (object key path allowed &optional optional)
  "Validate OBJECT's string KEY against ALLOWED at PATH.
When OPTIONAL is non-nil, a missing member is accepted."
  (let ((value (jetpacs-automation--field object key)))
    (cond
     ((and optional (eq value jetpacs-automation--missing)) nil)
     ((and (stringp value) (member value allowed)) value)
     (t (jetpacs-automation--schema-error path "has an unsupported value")))))

(defun jetpacs-automation--optional-string-field (object key path)
  "Validate optional bounded string KEY in OBJECT at PATH."
  (let ((value (jetpacs-automation--field object key)))
    (unless (eq value jetpacs-automation--missing)
      (jetpacs-automation--string value path))
    value))

(defun jetpacs-automation--optional-nonempty-string-field (object key path)
  "Validate optional nonempty bounded string KEY in OBJECT at PATH."
  (let ((value (jetpacs-automation--field object key)))
    (unless (eq value jetpacs-automation--missing)
      (jetpacs-automation--string value path t))
    value))

(defun jetpacs-automation--exactly-one (object keys path)
  "Require exactly one of KEYS in OBJECT at PATH; return the present key."
  (let ((present (seq-filter
                  (lambda (key) (jetpacs-automation--plist-present-p object key))
                  keys)))
    (unless (= (length present) 1)
      (jetpacs-automation--schema-error path "requires exactly one selector"))
    (car present)))

(defun jetpacs-automation--validate-predicate (predicate path allow-time-window)
  "Validate one closed state PREDICATE at PATH.
ALLOW-TIME-WINDOW controls predicate-only civil-time gates."
  (unless (jetpacs-automation--plist-p predicate)
    (jetpacs-automation--schema-error path "must be a plist"))
  (let ((type (jetpacs-automation--field predicate :type)))
    (unless (stringp type)
      (jetpacs-automation--schema-error (concat path ".type")
                                         "must be a string"))
    (pcase type
      ("power"
       (jetpacs-automation--require-object predicate path '(":type" ":state"))
       (jetpacs-automation--enum-field
        predicate :state (concat path ".state")
        '("connected" "disconnected") t))
      ("battery.level"
       (let ((key (jetpacs-automation--exactly-one
                   predicate '(:above :below) path)))
         (jetpacs-automation--require-object
          predicate path (list ":type" (symbol-name key)))
         (jetpacs-automation--integer-field
          predicate key (concat path (symbol-name key)) 0 100)))
      ("screen"
       (jetpacs-automation--require-object predicate path '(":type" ":state"))
       (jetpacs-automation--enum-field
        predicate :state (concat path ".state") '("on" "off" "unlocked") t))
      ("airplane"
       (jetpacs-automation--require-object predicate path '(":type" ":state"))
       (jetpacs-automation--enum-field
        predicate :state (concat path ".state") '("on" "off") t))
      ("network"
       (jetpacs-automation--require-object
        predicate path '(":type" ":transport"))
       (jetpacs-automation--enum-field
        predicate :transport (concat path ".transport")
        '("wifi" "cellular" "ethernet" "vpn" "bluetooth") t))
      ("headset"
       (jetpacs-automation--require-object predicate path '(":type" ":state"))
       (jetpacs-automation--enum-field
        predicate :state (concat path ".state") '("plugged" "unplugged") t))
      ((or "wifi.enabled" "bluetooth.enabled")
       (jetpacs-automation--require-object predicate path '(":type" ":enabled"))
       (let ((enabled (jetpacs-automation--field predicate :enabled)))
         (unless (eq enabled jetpacs-automation--missing)
           (jetpacs-automation--canonical-bool enabled
                                                (concat path ".enabled")))))
      ("calendar.event"
       (jetpacs-automation--require-object
        predicate path '(":type" ":calendar" ":title-contains"))
       (jetpacs-automation--optional-string-field
        predicate :calendar (concat path ".calendar"))
       (jetpacs-automation--optional-nonempty-string-field
        predicate :title-contains (concat path ".title-contains")))
      ("call.state"
       (jetpacs-automation--require-object predicate path '(":type" ":state"))
       (jetpacs-automation--enum-field
        predicate :state (concat path ".state")
        '("ringing" "offhook" "idle") t))
      ("time.window"
       (unless allow-time-window
         (jetpacs-automation--schema-error path "time.window is not valid here"))
       (jetpacs-automation--require-object
        predicate path '(":type" ":after" ":before" ":days"))
       (dolist (key '(:after :before))
         (let ((value (jetpacs-automation--field predicate key)))
           (unless (or (eq value jetpacs-automation--missing)
                       (and (stringp value)
                            (string-match-p
                             "\\`\\(?:[01][0-9]\\|2[0-3]\\):[0-5][0-9]\\'"
                             value)))
             (jetpacs-automation--schema-error
              (concat path (symbol-name key)) "must be HH:MM"))))
       (let ((days (jetpacs-automation--field predicate :days)))
         (unless (eq days jetpacs-automation--missing)
           (unless (and (vectorp days)
                        (= (length days)
                           (length (delete-dups (append days nil))))
                        (seq-every-p
                         (lambda (day)
                           (member day '("mon" "tue" "wed" "thu"
                                         "fri" "sat" "sun")))
                         days))
             (jetpacs-automation--schema-error
              (concat path ".days") "must contain distinct weekdays")))))
      (_ (jetpacs-automation--schema-error (concat path ".type")
                                            "unknown predicate type")))))

(defun jetpacs-automation--validate-predicates
    (predicates path allow-time-window &optional nonempty)
  "Validate PREDICATES vector at PATH."
  (unless (and (vectorp predicates)
               (<= (length predicates) jetpacs-automation-max-variadic-args)
               (or (not nonempty) (> (length predicates) 0)))
    (jetpacs-automation--schema-error path "must be a bounded predicate vector"))
  (cl-loop for predicate across predicates for index from 0
           do (jetpacs-automation--validate-predicate
               predicate (format "%s[%d]" path index) allow-time-window)))

(defun jetpacs-automation--validate-trigger-params (type params path)
  "Validate closed trigger TYPE PARAMS at PATH."
  (pcase type
    ("time"
     (let ((key (jetpacs-automation--exactly-one params '(:at-ms :every-s) path)))
       (jetpacs-automation--require-object params path (list (symbol-name key)))
       (jetpacs-automation--integer-field
        params key (concat path (symbol-name key))
        (if (eq key :every-s) 60 0) jetpacs-automation-max-safe-integer)))
    ("power"
     (jetpacs-automation--require-object params path '(":state"))
     (jetpacs-automation--enum-field
      params :state (concat path ".state") '("connected" "disconnected") t))
    ("battery.level"
     (let ((key (jetpacs-automation--exactly-one params '(:above :below) path)))
       (jetpacs-automation--require-object params path (list (symbol-name key)))
       (jetpacs-automation--integer-field
        params key (concat path (symbol-name key)) 0 100)))
    ("screen"
     (jetpacs-automation--require-object params path '(":state"))
     (jetpacs-automation--enum-field
      params :state (concat path ".state") '("on" "off" "unlocked") t))
    ("headset"
     (jetpacs-automation--require-object params path '(":state"))
     (jetpacs-automation--enum-field
      params :state (concat path ".state") '("plugged" "unplugged") t))
    ("airplane"
     (jetpacs-automation--require-object params path '(":state"))
     (jetpacs-automation--enum-field
      params :state (concat path ".state") '("on" "off") t))
    ((or "boot" "timezone.changed" "manual")
     (jetpacs-automation--require-object params path nil))
    ("package"
     (jetpacs-automation--require-object params path '(":event" ":package"))
     (jetpacs-automation--enum-field
      params :event (concat path ".event") '("added" "removed") t)
     (jetpacs-automation--optional-string-field
      params :package (concat path ".package")))
    ("state.edge"
     (jetpacs-automation--require-object params path '(":when" ":edge"))
     (jetpacs-automation--validate-predicates
      (jetpacs-automation--field params :when) (concat path ".when") nil t)
     (jetpacs-automation--enum-field
      params :edge (concat path ".edge") '("rise" "fall" "change") t))
    ("network"
     (jetpacs-automation--require-object params path '(":event" ":transport"))
     (jetpacs-automation--enum-field
      params :event (concat path ".event") '("available" "lost") t)
     (jetpacs-automation--enum-field
      params :transport (concat path ".transport")
      '("wifi" "cellular" "ethernet" "vpn" "bluetooth") t))
    ((or "wifi.enabled" "bluetooth.enabled")
     (jetpacs-automation--require-object params path '(":enabled"))
     (let ((enabled (jetpacs-automation--field params :enabled)))
       (unless (eq enabled jetpacs-automation--missing)
         (jetpacs-automation--canonical-bool enabled (concat path ".enabled")))))
    ("calendar.event"
     (jetpacs-automation--require-object
      params path '(":event" ":calendar" ":title-contains"))
     (jetpacs-automation--enum-field
      params :event (concat path ".event") '("started" "ended") t)
     (jetpacs-automation--optional-string-field
      params :calendar (concat path ".calendar"))
     (jetpacs-automation--optional-nonempty-string-field
      params :title-contains (concat path ".title-contains")))
    ("sms.received"
     (jetpacs-automation--require-object
      params path '(":from" ":contains" ":include-body"))
     (jetpacs-automation--optional-string-field params :from (concat path ".from"))
     (jetpacs-automation--optional-nonempty-string-field
      params :contains (concat path ".contains"))
     (let ((include (jetpacs-automation--field params :include-body)))
       (unless (eq include jetpacs-automation--missing)
         (jetpacs-automation--canonical-bool include
                                              (concat path ".include-body")))))
    ("call.state"
     (jetpacs-automation--require-object
      params path '(":state" ":number" ":include-number"))
     (jetpacs-automation--enum-field
      params :state (concat path ".state") '("ringing" "offhook" "idle") t)
     (jetpacs-automation--optional-string-field
      params :number (concat path ".number"))
     (let ((include (jetpacs-automation--field params :include-number)))
       (unless (eq include jetpacs-automation--missing)
         (jetpacs-automation--canonical-bool include
                                              (concat path ".include-number")))))
    (_ (jetpacs-automation--schema-error path "unknown trigger type"))))

(defun jetpacs-automation--normalize-trigger (trigger)
  "Normalize a recipe TRIGGER plist."
  (let ((path "recipe.trigger"))
    (jetpacs-automation--require-object
     trigger path '(":type" ":params" ":when" ":policy" ":ttl-s"
                    ":dedupe" ":throttle-s"))
    (let* ((type (jetpacs-automation--identifier
                  (jetpacs-automation--required trigger :type path)
                  (concat path ".type")))
           (params (jetpacs-automation--literal
                    (jetpacs-automation--required trigger :params path)
                    (concat path ".params")))
           (when-data (jetpacs-automation--literal
                       (jetpacs-automation--required trigger :when path)
                       (concat path ".when")))
           (raw-policy (jetpacs-automation--plist-fetch trigger :policy))
           (policy-name (cond
                         ((eq raw-policy jetpacs-automation--missing) ":queue")
                         ((symbolp raw-policy) (symbol-name raw-policy))
                         ((stringp raw-policy) (concat ":" raw-policy))))
           (policy (pcase policy-name
                     (":drop" :drop) (":queue" :queue) (":wake" :wake)
                     (_ nil)))
           (ttl (jetpacs-automation--plist-fetch trigger :ttl-s))
           (dedupe (jetpacs-automation--plist-fetch trigger :dedupe))
           (throttle (jetpacs-automation--plist-fetch trigger :throttle-s)))
      (unless (jetpacs-automation-trigger-descriptor type)
        (jetpacs-automation--schema-error (concat path ".type")
                                           "trigger type is not registered"))
      (unless (jetpacs-automation--plist-p params)
        (jetpacs-automation--schema-error (concat path ".params")
                                           "must be a plist"))
      (unless (vectorp when-data)
        (jetpacs-automation--schema-error (concat path ".when")
                                           "must be a vector"))
      (jetpacs-automation--validate-trigger-params
       type params (concat path ".params"))
      (jetpacs-automation--validate-predicates
       when-data (concat path ".when") t)
      (unless policy
        (jetpacs-automation--schema-error (concat path ".policy")
                                           "must be :drop, :queue, or :wake"))
      (if (eq policy :drop)
          (unless (eq ttl jetpacs-automation--missing)
            (jetpacs-automation--schema-error (concat path ".ttl-s")
                                               "drop policy forbids ttl-s"))
        (when (eq ttl jetpacs-automation--missing)
          (setq ttl jetpacs-automation-default-ttl-s))
        (unless (and (integerp ttl) (<= 1 ttl 604800))
          (jetpacs-automation--schema-error (concat path ".ttl-s")
                                             "must be 1..604800")))
      (unless (eq dedupe jetpacs-automation--missing)
        (setq dedupe (jetpacs-automation--identifier
                      dedupe (concat path ".dedupe")))
        (when (eq policy :drop)
          (jetpacs-automation--schema-error (concat path ".dedupe")
                                             "drop policy forbids dedupe")))
      (unless (eq throttle jetpacs-automation--missing)
        (unless (and (integerp throttle) (<= 1 throttle 604800))
          (jetpacs-automation--schema-error (concat path ".throttle-s")
                                             "must be 1..604800")))
      (append (list :type type :params params :when when-data :policy policy)
              (unless (eq policy :drop) (list :ttl-s ttl))
              (unless (eq dedupe jetpacs-automation--missing)
                (list :dedupe dedupe))
              (unless (eq throttle jetpacs-automation--missing)
                (list :throttle-s throttle))))))

(defun jetpacs-automation-normalize-recipe (recipe)
  "Validate and return canonical version-1 RECIPE.
Every collection, key order, symbol, expression, and optional default is
normalized so GUI and Lisp views have one stable authority."
  (jetpacs-automation--require-object
   recipe "recipe" '(":schema-version" ":id" ":name" ":inputs"
                     ":trigger" ":steps"))
  (let* ((version (jetpacs-automation--required recipe :schema-version "recipe"))
         (id (jetpacs-automation--identifier
              (jetpacs-automation--required recipe :id "recipe") "recipe.id"))
         (name (jetpacs-automation--string
                (jetpacs-automation--required recipe :name "recipe")
                "recipe.name" t))
         (raw-inputs (jetpacs-automation--required recipe :inputs "recipe"))
         (raw-steps (jetpacs-automation--required recipe :steps "recipe"))
         (ids (make-hash-table :test #'equal)))
    (unless (equal version jetpacs-automation-schema-version)
      (jetpacs-automation--schema-error "recipe.schema-version"
                                         "unsupported version"))
    (unless (and (vectorp raw-inputs) (vectorp raw-steps))
      (jetpacs-automation--schema-error "recipe"
                                         "inputs and steps must be vectors"))
    (when (> (length raw-inputs) jetpacs-automation-max-inputs)
      (jetpacs-automation--schema-error "recipe.inputs"
                                         "has too many inputs"))
    (let* ((inputs
            (apply #'vector
                   (cl-loop for input across raw-inputs for index from 0
                            collect (jetpacs-automation--normalize-input input index))))
           (names (mapcar (lambda (input) (plist-get input :name))
                          (append inputs nil)))
           (steps
            (apply #'vector
                   (cl-loop for step across raw-steps for index from 0
                            collect (jetpacs-automation--normalize-step
                                     step (format "recipe.steps[%d]" index) ids))))
           (normalized
            (list :schema-version jetpacs-automation-schema-version
                  :id id :name name :inputs inputs
                  :trigger (jetpacs-automation--normalize-trigger
                            (jetpacs-automation--required recipe :trigger "recipe"))
                  :steps steps)))
      (when (/= (length names) (length (delete-dups (copy-sequence names))))
        (jetpacs-automation--schema-error "recipe.inputs"
                                           "input names must be unique"))
      (jetpacs-automation--validate-step-refs steps names nil "recipe.steps")
      ;; Printing detects any residual circularity and enforces the total cap.
      (jetpacs-authoring-print normalized jetpacs-automation-max-recipe-bytes)
      normalized)))

(defun jetpacs-automation-read-recipe (source)
  "Read, validate, and normalize one inert recipe from SOURCE."
  (jetpacs-automation-normalize-recipe
   (jetpacs-authoring-read-one source jetpacs-automation-max-recipe-bytes)))

(defun jetpacs-automation-print-recipe (recipe)
  "Return RECIPE's canonical Lisp-data projection."
  (jetpacs-authoring-print
   (jetpacs-automation-normalize-recipe recipe)
   jetpacs-automation-max-recipe-bytes))

(defun jetpacs-automation-recipe-digest (recipe)
  "Return the SHA-256 digest of canonical RECIPE, including display metadata."
  (jetpacs-authoring-digest
   (jetpacs-automation-normalize-recipe recipe)
   jetpacs-automation-max-recipe-bytes))

(defun jetpacs-automation-execution-digest (recipe)
  "Return canonical RECIPE's execution-only SHA-256 digest.
The user-facing name is intentionally excluded so renaming is not treated as
an executable revision."
  (let ((normalized (jetpacs-automation-normalize-recipe recipe)))
    (jetpacs-authoring-digest
     (list :schema-version (plist-get normalized :schema-version)
           :id (plist-get normalized :id)
           :inputs (plist-get normalized :inputs)
           :trigger (plist-get normalized :trigger)
           :steps (plist-get normalized :steps))
     jetpacs-automation-max-recipe-bytes)))

;;;; Closed evaluator

(defun jetpacs-automation--value-cost (value)
  "Return bounded structural cost of VALUE, or exceed the result cap."
  (cond
   ((stringp value) (string-bytes value))
   ((or (numberp value) (memq value '(nil t))) 8)
   ((vectorp value) (+ 1 (cl-loop for item across value
                                  sum (jetpacs-automation--value-cost item))))
   ((proper-list-p value) (+ 1 (cl-loop for item in value
                                        sum (jetpacs-automation--value-cost item))))
   (t (1+ jetpacs-automation-max-result-cost))))

(defun jetpacs-automation--number (value operator)
  "Return numeric VALUE or signal an evaluation error for OPERATOR."
  (unless (jetpacs-automation--safe-number-p value)
    (signal 'jetpacs-automation-evaluation-error
            (list (format "%s requires finite numbers" operator))))
  value)

(defun jetpacs-automation--sequence-list (value operator)
  "Return VALUE as a list sequence or signal for OPERATOR."
  (cond ((vectorp value) (append value nil))
        ((proper-list-p value) value)
        (t (signal 'jetpacs-automation-evaluation-error
                   (list (format "%s requires a list" operator))))))

(defun jetpacs-automation-eval-expression (expression environment)
  "Evaluate closed EXPRESSION against immutable ENVIRONMENT.
ENVIRONMENT is an alist from exact reference strings to inert values.  The
normalized operator is dispatched by an explicit `pcase'; arbitrary calls,
mutation, I/O, and Emacs evaluation are impossible."
  (let ((form (jetpacs-automation-normalize-expression expression))
        (spent 0))
    (cl-labels
        ((charge
          (value)
          (cl-incf spent (jetpacs-automation--value-cost value))
          (when (> spent jetpacs-automation-max-result-cost)
            (signal 'jetpacs-automation-evaluation-error
                    '("expression result limit exceeded")))
          value)
         (string-arg
          (value op)
          (unless (stringp value)
            (signal 'jetpacs-automation-evaluation-error
                    (list (format "%s requires strings" op))))
          value)
         (run
          (node)
          (charge
           (if (atom node)
               node
             (let ((op (car node)) (args (cdr node)))
               (pcase op
                 ('ref
                  (let ((cell (assoc (cadr node) environment)))
                    (unless cell
                      (signal 'jetpacs-automation-evaluation-error
                              '("reference is unavailable")))
                    (copy-tree (cdr cell) t)))
                 ('if (if (run (nth 0 args))
                          (run (nth 1 args))
                        (run (nth 2 args))))
                 ('and (cl-loop for arg in args always (run arg)))
                 ('or (cl-loop for arg in args thereis (run arg)))
                 ('not (not (run (car args))))
                 ('present-p
                  (let ((v (run (car args))))
                    (not (or (null v)
                             (and (stringp v) (string-empty-p v))
                             (and (sequencep v) (= (length v) 0))))))
                 ('equal (equal (run (nth 0 args)) (run (nth 1 args))))
                 ('= (= (jetpacs-automation--number (run (nth 0 args)) "=")
                        (jetpacs-automation--number (run (nth 1 args)) "=")))
                 ('< (< (jetpacs-automation--number (run (nth 0 args)) "<")
                        (jetpacs-automation--number (run (nth 1 args)) "<")))
                 ('<= (<= (jetpacs-automation--number (run (nth 0 args)) "<=")
                          (jetpacs-automation--number (run (nth 1 args)) "<=")))
                 ('> (> (jetpacs-automation--number (run (nth 0 args)) ">")
                        (jetpacs-automation--number (run (nth 1 args)) ">")))
                 ('>= (>= (jetpacs-automation--number (run (nth 0 args)) ">=")
                          (jetpacs-automation--number (run (nth 1 args)) ">=")))
                 ('member-p
                  (let ((needle (run (nth 0 args))) (haystack (run (nth 1 args))))
                    (and (member needle
                                 (jetpacs-automation--sequence-list haystack "member-p"))
                         t)))
                 ('contains-p
                  (let ((haystack (run (nth 0 args))) (needle (run (nth 1 args))))
                    (if (stringp haystack)
                        (and (string-search (string-arg needle "contains-p") haystack) t)
                      (and (member needle
                                   (jetpacs-automation--sequence-list
                                    haystack "contains-p")) t))))
                 ('starts-with-p
                  (string-prefix-p
                   (string-arg (run (nth 1 args)) "starts-with-p")
                   (string-arg (run (nth 0 args)) "starts-with-p")))
                 ('ends-with-p
                  (string-suffix-p
                   (string-arg (run (nth 1 args)) "ends-with-p")
                   (string-arg (run (nth 0 args)) "ends-with-p")))
                 ('concat (mapconcat
                           (lambda (arg) (string-arg (run arg) "concat")) args ""))
                 ('string-trim (string-trim
                                (string-arg (run (car args)) "string-trim")))
                 ('downcase (downcase (string-arg (run (car args)) "downcase")))
                 ('upcase (upcase (string-arg (run (car args)) "upcase")))
                 ('replace-literal
                  (string-replace
                   (string-arg (run (nth 0 args)) "replace-literal")
                   (string-arg (run (nth 1 args)) "replace-literal")
                   (string-arg (run (nth 2 args)) "replace-literal")))
                 ('number-to-string
                  (number-to-string
                   (jetpacs-automation--number
                    (run (car args)) "number-to-string")))
                 ('list (apply #'vector (mapcar #'run args)))
                 ('append
                  (apply #'vector
                         (apply #'append
                                (mapcar (lambda (arg)
                                          (jetpacs-automation--sequence-list
                                           (run arg) "append"))
                                        args))))
                 ('nth
                  (let* ((index (run (nth 0 args)))
                         (items (jetpacs-automation--sequence-list
                                 (run (nth 1 args)) "nth")))
                    (unless (and (integerp index) (>= index 0))
                      (signal 'jetpacs-automation-evaluation-error
                              '("nth requires a nonnegative integer")))
                    (if (< index (length items)) (nth index items)
                      (and (= (length args) 3) (run (nth 2 args))))))
                 ('length (length (run (car args))))
                 ('join
                  (mapconcat
                   (lambda (x) (string-arg x "join"))
                   (jetpacs-automation--sequence-list (run (nth 0 args)) "join")
                   (string-arg (run (nth 1 args)) "join")))
                 ((or '+ '- '* 'min 'max)
                  (let ((numbers
                         (mapcar (lambda (arg)
                                   (jetpacs-automation--number (run arg)
                                                                (symbol-name op)))
                                 args)))
                    (pcase op
                      ('+ (apply #'+ numbers))
                      ('- (if (= (length numbers) 1) (- (car numbers))
                            (cl-reduce #'- (cdr numbers)
                                       :initial-value (car numbers))))
                      ('* (apply #'* numbers))
                      ('min (apply #'min numbers))
                      ('max (apply #'max numbers)))))
                 ('clamp
                  (let ((value (jetpacs-automation--number
                                (run (nth 0 args)) "clamp"))
                        (low (jetpacs-automation--number
                              (run (nth 1 args)) "clamp"))
                        (high (jetpacs-automation--number
                               (run (nth 2 args)) "clamp")))
                    (when (> low high)
                      (signal 'jetpacs-automation-evaluation-error
                              '("clamp lower bound exceeds upper bound")))
                    (max low (min high value))))
                 (_ (signal 'jetpacs-automation-evaluation-error
                            '("operator is unavailable")))))))))
      (run form))))

(defun jetpacs-automation--flatten-environment (prefix value)
  "Return PREFIX/VALUE and recursively flattened object entries."
  (append
   (list (cons prefix (copy-tree value t)))
   (when (jetpacs-automation--plist-p value)
     (cl-loop for (key child) on value by #'cddr
              for name = (substring (jetpacs-automation--key-name key) 1)
              append (jetpacs-automation--flatten-environment
                      (concat prefix "." name) child)))))

(defun jetpacs-automation-build-environment
    (recipe &optional trigger-data run step-outputs)
  "Build immutable reference environment for RECIPE.
TRIGGER-DATA and RUN are inert plists.  STEP-OUTPUTS is an alist from step id
to output data."
  (let ((normalized (jetpacs-automation-normalize-recipe recipe))
        environment)
    (seq-doseq (input (plist-get normalized :inputs))
      (push (cons (concat "input." (plist-get input :name))
                  (copy-tree (plist-get input :value) t))
            environment))
    (setq environment
          (append (jetpacs-automation--flatten-environment
                   "trigger.data" (or trigger-data nil))
                  (jetpacs-automation--flatten-environment
                   "run" (or run nil))
                  environment))
    (dolist (entry step-outputs)
      (setq environment
            (append (jetpacs-automation--flatten-environment
                     (format "step.%s.output" (car entry)) (cdr entry))
                    environment)))
    environment))

(defun jetpacs-automation-resolve-args (args environment)
  "Resolve canonical action ARGS against ENVIRONMENT and return inert data."
  (cl-loop for (key value) on args by #'cddr
           append
           (list key
                 (if (and (jetpacs-automation--plist-p value)
                          (jetpacs-automation--plist-present-p value :expr))
                     (jetpacs-automation-eval-expression
                      (jetpacs-automation--plist-fetch value :expr) environment)
                   (copy-tree value t)))))

;;;; Dry run and EBP compilation

(defun jetpacs-automation-dry-run (recipe &optional trigger-data run)
  "Purely interpret RECIPE with sample TRIGGER-DATA and RUN.
Return `(:ok t :digest DIGEST :trace VECTOR)' or a bounded error plist.  No
registered action executor is called."
  (condition-case err
      (let* ((normalized (jetpacs-automation-normalize-recipe recipe))
             (environment (jetpacs-automation-build-environment
                           normalized trigger-data run nil))
             trace outputs)
        (cl-labels
            ((walk
              (steps path)
              (cl-loop for step across steps for index from 0
                       for here = (format "%s[%d]" path index)
                       do (if (eq (plist-get step :kind) :action)
                              (let* ((action (plist-get step :action))
                                     (descriptor
                                      (jetpacs-automation-action-descriptor action))
                                     (args (jetpacs-automation-resolve-args
                                            (plist-get step :args) environment)))
                                (jetpacs-automation-validate-action-args
                                 action args nil (concat here ".args"))
                                (push (list :path here :step-id (plist-get step :id)
                                            :kind :action :action action
                                            :target (plist-get descriptor :executor)
                                            :args args)
                                      trace)
                                ;; Dry-run outputs are explicit nil placeholders;
                                ;; actions with real results cannot fabricate one.
                                (push (cons (plist-get step :id) nil) outputs)
                                (setq environment
                                      (jetpacs-automation-build-environment
                                       normalized trigger-data run outputs)))
                            (let ((choice (jetpacs-automation-eval-expression
                                           (plist-get step :condition)
                                           environment)))
                              (push (list :path here :step-id (plist-get step :id)
                                          :kind :if :result (and choice t))
                                    trace)
                              (walk (if choice (plist-get step :then)
                                      (plist-get step :else))
                                    (concat here (if choice ".then" ".else"))))))))
          (walk (plist-get normalized :steps) "steps"))
        (list :ok t :digest (jetpacs-automation-recipe-digest normalized)
              :trace (vconcat (nreverse trace))))
    (error
     (list :ok nil :error-kind
           (cond ((memq (car err) '(jetpacs-automation-schema-error
                                    jetpacs-authoring-read-error
                                    jetpacs-authoring-limit-error))
                  "invalid-recipe")
                 ((eq (car err) 'jetpacs-automation-evaluation-error)
                  "evaluation-failed")
                 (t "dry-run-failed"))
           :message (truncate-string-to-width
                     (error-message-string err) 240 nil nil t)))))

(defun jetpacs-automation--wire-key (key)
  "Return KEY as a fresh underscore-style wire plist key."
  (jetpacs-automation--canonical-key
   (replace-regexp-in-string "-" "_" (jetpacs-automation--key-name key)
                             t t)))

(defun jetpacs-automation--wire-data (value)
  "Convert canonical inert VALUE's object keys to EBP underscore spelling."
  (cond
   ;; Canonical Lisp uses nil for false.  EBP's encoder requires the explicit
   ;; sentinel; a raw nil in a plist value otherwise becomes an empty object.
   ((null value) :false)
   ((vectorp value) (apply #'vector (mapcar #'jetpacs-automation--wire-data
                                             (append value nil))))
   ((jetpacs-automation--plist-p value)
    (cl-loop for (key item) on value by #'cddr
             append (list (jetpacs-automation--wire-key key)
                          (jetpacs-automation--wire-data item))))
   (t value)))

(defun jetpacs-automation--local-expression (expression input-environment)
  "Compile EXPRESSION to an on_fire scalar, or the missing sentinel.
Only constants, input refs, trigger-data substitutions, and string concat are
portable to EBP's single-pass substitution language."
  (cond
   ((or (stringp expression) (numberp expression) (memq expression '(nil t)))
    expression)
   ((and (consp expression) (eq (car expression) 'ref))
    (let ((path (cadr expression)))
      (cond
       ((string-prefix-p "input." path)
        (if-let* ((cell (assoc path input-environment))) (cdr cell)
          jetpacs-automation--missing))
       ((string-prefix-p "trigger.data." path)
        (format "${data.%s}" (substring path (length "trigger.data."))))
       (t jetpacs-automation--missing))))
   ((and (consp expression) (eq (car expression) 'concat))
    (let ((parts (mapcar (lambda (part)
                           (jetpacs-automation--local-expression
                            part input-environment))
                         (cdr expression))))
      (if (or (memq jetpacs-automation--missing parts)
              (seq-some (lambda (part) (not (stringp part))) parts))
          jetpacs-automation--missing
        (mapconcat #'identity parts ""))))
   (t jetpacs-automation--missing)))

(defun jetpacs-automation--local-args (args input-environment)
  "Compile ARGS for `on_fire', or return the missing sentinel."
  (let (result failed)
    (cl-loop for (key value) on args by #'cddr
             while (not failed)
             do (let ((compiled
                       (if (and (jetpacs-automation--plist-p value)
                                (jetpacs-automation--plist-present-p value :expr))
                           (jetpacs-automation--local-expression
                            (jetpacs-automation--plist-fetch value :expr)
                            input-environment)
                         value)))
                  (if (eq compiled jetpacs-automation--missing)
                      (setq failed t)
                    (setq result
                          (append result
                                  (list key compiled))))))
    (if failed jetpacs-automation--missing result)))

(defun jetpacs-automation--local-response (step input-environment trigger-caps)
  "Compile action STEP to an EBP on_fire response, or nil."
  (let* ((descriptor (jetpacs-automation-action-descriptor
                      (plist-get step :action)))
         (args (jetpacs-automation--local-args
                (plist-get step :args) input-environment))
         (cap (plist-get descriptor :cap)))
    (when (and (eq (plist-get descriptor :executor) 'device)
               (plist-get descriptor :trigger-safe)
               (not (eq args jetpacs-automation--missing))
               (or (plist-get descriptor :notify)
                   (member cap trigger-caps))
               (condition-case nil
                   (progn
                     (jetpacs-automation-validate-action-args
                      (plist-get step :action) args nil "on_fire.args")
                     t)
                 (jetpacs-automation-schema-error nil)))
      (if (plist-get descriptor :notify)
          (let ((text (jetpacs-automation--plist-fetch args :text))
                (title (jetpacs-automation--plist-fetch args :title)))
            (list :notify
                  (append (unless (eq title jetpacs-automation--missing)
                            (list :title title))
                          (list :text text))))
        (append (list :cap cap)
                (when args
                  (list :args (jetpacs-automation--wire-data args))))))))

(defun jetpacs-automation--step-capabilities (steps)
  "Return distinct device capability names recursively required by STEPS."
  (let (caps)
    (cl-labels
        ((walk
          (items)
          (seq-doseq (step items)
            (if (eq (plist-get step :kind) :action)
                (when-let* ((descriptor
                             (jetpacs-automation-action-descriptor
                              (plist-get step :action)))
                            (cap (plist-get descriptor :cap)))
                  (push cap caps))
              (walk (plist-get step :then))
              (walk (plist-get step :else))))))
      (walk steps))
    (delete-dups (nreverse caps))))

(defun jetpacs-automation-compile (recipe &optional device-profile)
  "Compile RECIPE for DEVICE-PROFILE and return a hybrid execution plan.
DEVICE-PROFILE is a plist with lists `:trigger-types', `:state-types',
`:caps', and `:trigger-caps'.  Portable descriptors are preserved when absent;
`:supported' is nil and `:missing' explains why activation must refuse."
  (let* ((normalized (jetpacs-automation-normalize-recipe recipe))
         (trigger (plist-get normalized :trigger))
         (type (plist-get trigger :type))
         (trigger-types (plist-get device-profile :trigger-types))
         (caps (plist-get device-profile :caps))
         (trigger-caps (plist-get device-profile :trigger-caps))
         (state-types (plist-get device-profile :state-types))
         (trackable-state-types
          (plist-get device-profile :trackable-state-types))
         (max-trigger-responses
          (and device-profile
               (plist-get device-profile :max-trigger-responses)))
         (inputs-env (jetpacs-automation-build-environment normalized nil nil nil))
         (steps (plist-get normalized :steps))
         (execution-digest (jetpacs-automation-execution-digest normalized))
         (trigger-id (format "automation.%s.%s"
                             (substring (secure-hash 'sha256
                                                     (plist-get normalized :id))
                                        0 12)
                             (substring execution-digest 0 10)))
         (prefix 0)
         responses
         missing)
    (unless (or (null device-profile) (member type trigger-types))
      (push (format "trigger:%s" type) missing))
    (seq-doseq (predicate (plist-get trigger :when))
      (let ((ptype (jetpacs-automation--plist-fetch predicate :type)))
        (unless (or (equal ptype "time.window")
                    (null device-profile)
                    (member ptype state-types))
          (push (format "state:%s" ptype) missing))))
    (when (equal type "state.edge")
      (seq-doseq
          (predicate (jetpacs-automation--plist-fetch
                      (plist-get trigger :params) :when))
        (let ((ptype (jetpacs-automation--plist-fetch predicate :type)))
          (unless (or (null device-profile)
                      (member ptype trackable-state-types))
            (push (format "trackable-state:%s" ptype) missing)))))
    (catch 'stop
      (seq-doseq (step steps)
        (if (or (not (eq (plist-get step :kind) :action))
                (and (integerp max-trigger-responses)
                     (>= prefix max-trigger-responses)))
            (throw 'stop nil)
          (let* ((descriptor (jetpacs-automation-action-descriptor
                              (plist-get step :action)))
                 (cap (plist-get descriptor :cap))
                 (response (jetpacs-automation--local-response
                            step inputs-env trigger-caps)))
            (when (and (eq (plist-get descriptor :executor) 'device)
                       cap device-profile (not (member cap caps)))
              (push (format "cap:%s" cap) missing))
            (if response
                (progn (push response responses) (cl-incf prefix))
              (throw 'stop nil))))))
    ;; Device actions in the host continuation still require live capability
    ;; support, even if nested in a conditional or not translatable to
    ;; unattended on_fire.
    (dolist (cap (jetpacs-automation--step-capabilities
                  (cl-subseq steps prefix)))
      (when (and device-profile (not (member cap caps)))
        (push (format "cap:%s" cap) missing)))
    (setq missing (delete-dups (nreverse missing)))
    (let ((wire
           (append
            (list :id trigger-id :type type)
            ;; Empty params must use the protocol's absent/default form.  The
            ;; wire false sentinel represents canonical nil field values.
            (when (plist-get trigger :params)
              (list :params (jetpacs-automation--wire-data
                             (plist-get trigger :params))))
            (list :when (jetpacs-automation--wire-data
                         (plist-get trigger :when))
                  :policy (substring (symbol-name (plist-get trigger :policy)) 1)
                  :on_fire (vconcat (nreverse responses)))
            (when-let* ((ttl (plist-get trigger :ttl-s))) (list :ttl_s ttl))
            (when-let* ((dedupe (plist-get trigger :dedupe)))
              (list :dedupe dedupe))
            (when-let* ((throttle (plist-get trigger :throttle-s)))
              (list :throttle_s throttle)))))
      (list :recipe normalized :recipe-id (plist-get normalized :id)
            :recipe-digest (jetpacs-automation-recipe-digest normalized)
            :execution-digest execution-digest :trigger-id trigger-id
            :wire-trigger wire :device-prefix-count prefix
            :host-steps (cl-subseq steps prefix) :supported (null missing)
            :missing missing))))

;;;; Built-in portable catalog

(defun jetpacs-automation-register-builtins ()
  "Replace the built-in portable trigger and action descriptor catalog."
  (clrhash jetpacs-automation-trigger-registry)
  (clrhash jetpacs-automation-action-registry)
  (dolist (entry
           '(("time" "Time") ("power" "Power")
             ("battery.level" "Battery level") ("screen" "Screen")
             ("headset" "Headset") ("airplane" "Airplane mode")
             ("boot" "Device boot") ("timezone.changed" "Time zone changed")
             ("manual" "Manual") ("package" "Package changed")
             ("state.edge" "State edge") ("network" "Network")
             ("wifi.enabled" "Wi-Fi enabled")
             ("bluetooth.enabled" "Bluetooth enabled")
             ("calendar.event" "Calendar event")
             ("sms.received" "SMS received") ("call.state" "Call state")))
    (jetpacs-automation-register-trigger
     (list :type (car entry) :label (cadr entry))))
  (dolist (descriptor
           '((:action "device.notify" :label "Post notification"
              :executor device :notify t :trigger-safe t)
             (:action "device.vibrate" :label "Vibrate"
              :executor device :cap "vibrate" :trigger-safe t)
             (:action "device.volume.set" :label "Set volume"
              :executor device :cap "volume.set" :trigger-safe nil)
             (:action "device.tts.speak" :label "Speak text"
              :executor device :cap "tts.speak" :trigger-safe t)
             (:action "device.ringer.mode" :label "Set ringer mode"
              :executor device :cap "ringer.mode" :trigger-safe t)
             (:action "device.flashlight" :label "Set flashlight"
              :executor device :cap "flashlight" :trigger-safe t)
             (:action "device.media.key" :label "Send media key"
              :executor device :cap "media.key" :trigger-safe t)
             (:action "device.screen.keep_on" :label "Keep screen on"
              :executor device :cap "screen.keep_on" :trigger-safe t)
             (:action "device.brightness.set" :label "Set brightness"
              :executor device :cap "brightness.set" :trigger-safe t)
             (:action "device.dnd.set" :label "Set Do Not Disturb"
              :executor device :cap "dnd.set" :trigger-safe t)
             (:action "emacs.message" :label "Show Emacs message"
              :executor host :host-executor message)
             (:action "emacs.theme.load" :label "Load Emacs theme"
              :executor host :host-executor theme-load)))
    (jetpacs-automation-register-action descriptor)))

(jetpacs-automation-register-builtins)

(provide 'jetpacs-automation-model)
;;; jetpacs-automation-model.el ends here
