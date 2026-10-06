;;; jetpacs-data.el --- The surface data document (SPEC 13.8) -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later

;;; Commentary:

;; Amendment #194 gives a surface a JSON data document beside its tree:
;; a node member may be bound to an RFC 6901 pointer into it (`jetpacs-ref'
;; in a constructor, `:bind' on the wire), and `surface.data' replaces
;; values at pointers without resending the tree.  The Companion resolves
;; the bindings before it validates ("materialization", SPEC 13.8.3).
;;
;; This file is the Elisp reference materializer, the same algorithm the
;; Companion and ebp/validate.py run, replayed against ebp/goldens/data.golden
;; by test/jetpacs-data-test.el.  The shell uses it to keep an applet
;; portable: on a Companion that did not grant `data.model' the bindings are
;; resolved here, stripped, and an ordinary snapshot goes out (SPEC 12 rule 3).
;;
;; A document is a plist with keyword keys (an empty plist is the empty
;; object), a hash table with string keys, or a vector; arrays are vectors.
;; An Elisp plist cannot tell a JSON null member from an absent one, so a
;; null in the document reaches nothing here, which is the only difference
;; from the Companion and never affects a usable member value.

;;; Code:

(require 'cl-lib)
(require 'jetpacs-widgets)

(defconst jetpacs-data--absent (make-symbol "absent")
  "Marker for a hash-table lookup that reached nothing.")

(defun jetpacs-data-check-document (data)
  "Signal unless DATA is a document within SPEC 13.8.1's bounds.
Returns DATA.  The path reported is `data', as the Companion reports it."
  (unless (or (null data) (hash-table-p data)
              (and (consp data) (keywordp (car data))))
    (signal 'jetpacs-data-error (list "data" "data document must be an object")))
  (when (> (jetpacs-data--depth data) jetpacs-max-data-depth)
    (signal 'jetpacs-data-error (list "data" "exceeds max_data_depth")))
  (when (and data (> (jetpacs-node-wire-bytes data) jetpacs-max-data-bytes))
    (signal 'jetpacs-data-error (list "data" "exceeds max_data_bytes")))
  data)

(defun jetpacs-data--depth (value)
  "Containers nested in VALUE."
  (cond
   ((vectorp value)
    (1+ (apply #'max 0 (mapcar #'jetpacs-data--depth value))))
   ((hash-table-p value)
    (let ((deepest 0))
      (maphash (lambda (_k v) (setq deepest (max deepest (jetpacs-data--depth v))))
               value)
      (1+ deepest)))
   ((and (consp value) (keywordp (car value)))
    (let ((deepest 0) (p value))
      (while p
        (pop p)
        (setq deepest (max deepest (jetpacs-data--depth (pop p)))))
      (1+ deepest)))
   (t 0)))

(defun jetpacs-data-resolve (document tokens what)
  "Return (FOUND . VALUE) for TOKENS in DOCUMENT (SPEC 13.8.2).
One container step per token; an array token must be a decimal without
leading zeros (signaled as `jetpacs-data-error' at WHAT); a token that
reaches nothing, or a null member, yields FOUND nil."
  (let ((current document) (found t))
    (while (and found tokens)
      (let ((token (pop tokens)))
        (cond
         ((vectorp current)
          (unless (string-match-p "\\`\\(0\\|[1-9][0-9]*\\)\\'" token)
            (signal 'jetpacs-data-error
                    (list what "array index must be a decimal without leading zeros")))
          (let ((index (string-to-number token)))
            (if (< index (length current))
                (setq current (aref current index))
              (setq found nil))))
         ((hash-table-p current)
          (let ((v (gethash token current jetpacs-data--absent)))
            (if (eq v jetpacs-data--absent) (setq found nil) (setq current v))))
         ((and (consp current) (keywordp (car current)))
          (let ((cell (plist-member current (intern (concat ":" token)))))
            (if cell (setq current (cadr cell)) (setq found nil))))
         (t (setq found nil)))))
    (if (and found current) (cons t current) (cons nil nil))))

(defun jetpacs-data--object-p (v)
  "Non-nil when V is a JSON object in any of the document representations."
  (or (hash-table-p v) (and (consp v) (keywordp (car v)))))

(defun jetpacs-data--finite-number-p (v)
  (and (numberp v) (jetpacs--finite-number-p v)))

(defun jetpacs-data--matches-tag (tag value type name)
  "Whether a resolved VALUE has the §17.1 TAG of member NAME on TYPE."
  (pcase tag
    ('nil t)
    ((or "string" "yyyy-mm" "yyyy-mm-dd" "hh-mm" "color") (stringp value))
    ("identifier" (and (stringp value) (jetpacs-identifier-p value)))
    ("boolean" (memq value '(t :json-false)))
    ("boolean-or-auto" (or (memq value '(t :json-false)) (equal value "auto")))
    ("number" (jetpacs-data--finite-number-p value))
    ("dp" (and (jetpacs-data--finite-number-p value) (>= value 0)))
    ("positive-integer" (and (integerp value) (> value 0)))
    ("non-negative-integer" (and (integerp value) (>= value 0)))
    ("integer-1-12" (and (integerp value) (<= 1 value 12)))
    ("integer-1-31" (and (integerp value) (<= 1 value 31)))
    ("enum"
     (and (stringp value)
          (member value (or (cdr (assoc (concat type "." name)
                                        jetpacs-contract-enums))
                            (cdr (assoc name jetpacs-contract-enums))))))
    ("font-weight"
     (or (member value '("normal" "bold"))
         (and (integerp value) (<= 100 value 900) (zerop (% value 100)))))
    ((or "string-or-number" "option-value")
     (or (stringp value) (jetpacs-data--finite-number-p value)))
    ("option-value-or-array"
     (or (stringp value) (jetpacs-data--finite-number-p value) (vectorp value)))
    ("number-or-corner-object"
     (or (jetpacs-data--finite-number-p value) (jetpacs-data--object-p value)))
    ("two-number-array"
     (and (vectorp value) (= (length value) 2)
          (cl-every #'jetpacs-data--finite-number-p value)))
    ("number-array"
     (and (vectorp value) (cl-every #'jetpacs-data--finite-number-p value)))
    ((pred (lambda (tag) (string-suffix-p "-object" tag)))
     (jetpacs-data--object-p value))
    ((pred (lambda (tag) (string-suffix-p "-array" tag))) (vectorp value))
    (_ t)))

(defun jetpacs-data-bound-p (spec)
  "Non-nil when any node of SPEC carries `:bind'."
  (catch 'bound
    (jetpacs-data--walk-nodes
     spec (lambda (node _path) (when (plist-member node :bind) (throw 'bound t))))
    nil))

(defun jetpacs-data--walk-nodes (value fn &optional path)
  "Call FN with each node plist of VALUE and its SPEC 13.8 path."
  (let ((path (or path "spec")))
    (cond
     ((vectorp value)
      (let ((i -1))
        (mapc (lambda (child)
                (jetpacs-data--walk-nodes child fn (format "%s[%d]" path (cl-incf i))))
              value)))
     ((hash-table-p value)
      (maphash (lambda (k child)
                 (jetpacs-data--walk-nodes child fn (format "%s.%s" path k)))
               value))
     ((and (consp value) (keywordp (car value)))
      (let ((node-p (plist-member value :t)))
        (when node-p (funcall fn value path))
        (let ((p value))
          (while p
            (let ((k (pop p)) (child (pop p)))
              (unless (and node-p (memq k jetpacs--opaque-members))
                (jetpacs-data--walk-nodes
                 child fn (format "%s.%s" path (jetpacs--wire-name k))))))))))))

(defun jetpacs-data-check-bindings (spec)
  "Signal unless every `:bind' of SPEC may travel as authored (SPEC 13.8.3).
Checks the bindable-member rule, the pointer grammar, the authored-and-
bound conflict, and `jetpacs-max-bindings-per-snapshot'; a document is
not consulted, so a required member whose pointer reaches nothing is the
Companion's to refuse.  Returns the binding count."
  (let ((count 0))
    (jetpacs-data--walk-nodes
     spec
     (lambda (node path)
       (when-let* ((bind (plist-get node :bind)))
         (let ((type (plist-get node :t)))
           (unless (and (consp bind) (keywordp (car bind)))
             (signal 'jetpacs-data-error (list (concat path ".bind") "bind must be an object")))
           (let ((b bind))
             (while b
               (let* ((k (pop b)) (pointer (pop b))
                      (name (jetpacs--wire-name k))
                      (bpath (format "%s.bind.%s" path name)))
                 (when (> (cl-incf count) jetpacs-max-bindings-per-snapshot)
                   (signal 'jetpacs-data-error
                           (list bpath "exceeds max_bindings_per_snapshot")))
                 (when-let* ((reason (jetpacs-unbindable-reason type name)))
                   (signal 'jetpacs-data-error (list bpath reason)))
                 (when (plist-member node k)
                   (signal 'jetpacs-data-error
                           (list bpath (format "%s is both authored and bound" name))))
                 (jetpacs-pointer-tokens pointer bpath))))))))
    count))

(cl-defun jetpacs-data-materialize (spec data &key strip)
  "Resolve every `:bind' of SPEC against DATA (SPEC 13.8.3); return a copy.
The reference materializer: bound members take the value their pointer
reaches, or are omitted when it reaches nothing; a required member left
absent, an unbindable or unknown member, a member both authored and
bound, a malformed pointer, or a resolved value of the wrong type signals
`jetpacs-data-error' with (PATH REASON) at `<node>.bind.<member>', in
walk order.  With STRIP non-nil the `:bind' members are removed, as a
snapshot bound for a Companion without `data.model' needs."
  (jetpacs-data-check-document data)
  (let ((count 0))
    (cl-labels
        ((walk (value path)
           (cond
            ((vectorp value)
             (let ((i -1))
               (vconcat (mapcar (lambda (child)
                                  (walk child (format "%s[%d]" path (cl-incf i))))
                                value))))
            ((hash-table-p value)
             (let ((out (make-hash-table :test (hash-table-test value)
                                         :size (hash-table-count value))))
               (maphash (lambda (k child)
                          (puthash k (walk child (format "%s.%s" path k)) out))
                        value)
               out))
            ((and (consp value) (keywordp (car value)))
             (if (plist-member value :t) (walk-node value path) (walk-plist value path)))
            (t value)))
         (walk-plist (plist path)
           (let (out)
             (while plist
               (let ((k (pop plist)) (v (pop plist)))
                 (push k out)
                 (push (walk v (format "%s.%s" path (jetpacs--wire-name k))) out)))
             (nreverse out)))
         (walk-node (node path)
           (let* ((type (plist-get node :t))
                  (bind (plist-get node :bind))
                  (resolved (and bind (resolve node type bind path)))
                  (p node)
                  out)
             (while p
               (let ((k (pop p)) (v (pop p)))
                 (cond
                  ((and (eq k :bind) strip))
                  ((memq k jetpacs--opaque-members) (push k out) (push v out))
                  (t (push k out)
                     (push (walk v (format "%s.%s" path (jetpacs--wire-name k))) out)))))
             (setq out (nreverse out))
             (while resolved
               (let ((k (pop resolved)) (v (pop resolved)))
                 (setq out (plist-put out k v))))
             out))
         (resolve (node type bind path)
           (unless (and (consp bind) (keywordp (car bind)))
             (signal 'jetpacs-data-error (list (concat path ".bind") "bind must be an object")))
           (let ((row (jetpacs-node-schema-row type)) (b bind) out)
             (while b
               (let* ((k (pop b)) (pointer (pop b))
                      (name (jetpacs--wire-name k))
                      (bpath (format "%s.bind.%s" path name)))
                 (when (> (cl-incf count) jetpacs-max-bindings-per-snapshot)
                   (signal 'jetpacs-data-error
                           (list bpath "exceeds max_bindings_per_snapshot")))
                 (when-let* ((reason (jetpacs-unbindable-reason type name)))
                   (signal 'jetpacs-data-error (list bpath reason)))
                 (when (plist-member node k)
                   (signal 'jetpacs-data-error
                           (list bpath (format "%s is both authored and bound" name))))
                 (let* ((tokens (jetpacs-pointer-tokens pointer bpath))
                        (hit (jetpacs-data-resolve data tokens bpath)))
                   (cond
                    ((car hit)
                     (let ((tag (jetpacs-member-tag type name)))
                       (unless (jetpacs-data--matches-tag tag (cdr hit) type name)
                         (signal 'jetpacs-data-error
                                 (list bpath (format "resolved value is not %s"
                                                     (or tag "typed")))))
                       (push k out)
                       (push (cdr hit) out)))
                    ((and row (member name (nth 1 row)))
                     (signal 'jetpacs-data-error
                             (list bpath (format "required %s reaches nothing" name))))))))
             (nreverse out))))
      (walk spec "spec"))))

;;;; DataOps (SPEC 13.8.4)

(defun jetpacs-data-set (path value)
  "A `set' DataOp: replace or create the VALUE PATH reaches (SPEC 13.8.4)."
  (jetpacs-pointer-tokens path "set")
  (list :op "set" :path path :value value))

(defun jetpacs-data-remove (path)
  "A `remove' DataOp: delete the value PATH reaches, which must exist."
  (when (string-empty-p path)
    (signal 'jetpacs-data-error (list "remove" "the whole document cannot be removed")))
  (jetpacs-pointer-tokens path "remove")
  (list :op "remove" :path path))

(defun jetpacs-data-check-op (op)
  "Signal unless OP is a well-formed DataOp plist; return OP."
  (let ((kind (plist-get op :op)))
    (unless (member kind '("set" "remove"))
      (signal 'jetpacs-data-error (list "op" "must be set or remove")))
    (let ((tokens (jetpacs-pointer-tokens (plist-get op :path) kind)))
      (when (and (equal kind "set") (not (plist-member op :value)))
        (signal 'jetpacs-data-error (list "set" "set requires value")))
      (when (and (null tokens)
                 (not (and (equal kind "set")
                           (jetpacs-data--object-p (plist-get op :value)))))
        (signal 'jetpacs-data-error
                (list "path" "the empty pointer is admitted only for a set whose value is an object"))))
    op))

(provide 'jetpacs-data)
;;; jetpacs-data.el ends here
