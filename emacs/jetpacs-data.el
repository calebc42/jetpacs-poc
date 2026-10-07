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
;; from the Companion and never affects a usable member value.  The same
;; holds for an empty-plist template item (SPEC 13.8.5): build items as
;; hash tables or non-empty plists.

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

(defun jetpacs-data-templated-p (spec)
  "Non-nil when any node of SPEC carries `:template' (SPEC 13.8.5)."
  (catch 'templated
    (jetpacs-data--walk-nodes
     spec (lambda (node _path) (when (plist-member node :template) (throw 'templated t))))
    nil))

(defun jetpacs-data--walk-nodes (value fn &optional path)
  "Call FN with each node plist of VALUE and its SPEC 13.8 path.
A `:template' member is walked as the node it is; `:bind' and the opaque
members are not."
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
Checks the bindable-member rule, the pointer grammar (relative inside a
template item scope, SPEC 13.8.5), the authored-and-bound conflict,
`jetpacs-max-bindings-per-snapshot', and every template's structural
rules; a document is not consulted, so a required member whose pointer
reaches nothing is the Companion's to refuse.  Returns the binding count."
  (let ((count 0))
    (cl-labels
        ((check-node (node path in-scope template-root)
           (let ((type (plist-get node :t))
                 (bind (plist-get node :bind))
                 (templated (plist-member node :template)))
             (when templated
               (jetpacs--check-templated-node node path)
               ;; Nested templates are scanned by their enclosing one.
               (unless in-scope
                 (jetpacs-check-template (plist-get node :template)
                                         (concat path ".template") 1)))
             (when bind
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
                     (when-let* ((reason (jetpacs-unbindable-reason
                                          type name template-root templated)))
                       (signal 'jetpacs-data-error (list bpath reason)))
                     (when (plist-member node k)
                       (signal 'jetpacs-data-error
                               (list bpath (format "%s is both authored and bound" name))))
                     (jetpacs-pointer-tokens pointer bpath in-scope)))))))
         (walk (value path in-scope template-root)
           (cond
            ((vectorp value)
             (let ((i -1))
               (mapc (lambda (child)
                       (walk child (format "%s[%d]" path (cl-incf i)) in-scope nil))
                     value)))
            ((hash-table-p value)
             (maphash (lambda (k child) (walk child (format "%s.%s" path k) in-scope nil))
                      value))
            ((and (consp value) (keywordp (car value)))
             (let ((node-p (plist-member value :t)))
               (when node-p (check-node value path in-scope template-root))
               (let ((p value))
                 (while p
                   (let ((k (pop p)) (child (pop p)))
                     (cond
                      ((and node-p (memq k jetpacs--opaque-members)))
                      ((and node-p (eq k :template))
                       (walk child (concat path ".template") t t))
                      (t (walk child (format "%s.%s" path (jetpacs--wire-name k))
                               in-scope nil)))))))))))
      (walk spec "spec" nil nil))
    count))

(defconst jetpacs-data--probe (make-symbol "probe")
  "The scope of the one check a template gets when its array is empty.")

(defconst jetpacs-data--hook-keywords
  (mapcar (lambda (n) (intern (concat ":" n))) jetpacs-action-hook-keys)
  "The contract's hook members as keywords.")

(defun jetpacs-data--inject-item (node item)
  "A copy of NODE whose remote descriptors carry `:item' ITEM in `:args'.
SPEC 14.3 (amendment #195): the Companion injects the enclosing item's key
on every hook dispatched from a templated node; when Emacs expands the
template itself it writes the same member, so a handler reads
`(plist-get args :item)' either way."
  (cl-labels ((inject (d)
                (if (and (jetpacs-node-p d) (plist-member d :action))
                    (plist-put (copy-sequence d) :args
                               (plist-put (copy-sequence (plist-get d :args)) :item item))
                  d)))
    (let ((out (copy-sequence node)))
      (dolist (hook jetpacs-data--hook-keywords)
        (when (plist-member out hook)
          (setq out (plist-put out hook (inject (plist-get out hook))))))
      (dolist (side '(:swipe_start :swipe_end))
        (when-let* ((swipe (plist-get out side)))
          (let ((copy (copy-sequence swipe)))
            (when (plist-member copy :on_trigger)
              (setq copy (plist-put copy :on_trigger (inject (plist-get copy :on_trigger)))))
            (when (vectorp (plist-get copy :actions))
              (setq copy (plist-put copy :actions
                                    (vconcat (mapcar (lambda (a)
                                                       (if (plist-member a :on_trigger)
                                                           (plist-put (copy-sequence a) :on_trigger
                                                                      (inject (plist-get a :on_trigger)))
                                                         a))
                                                     (plist-get copy :actions))))))
            (setq out (plist-put out side copy)))))
      out)))

(cl-defun jetpacs-data-materialize (spec data &key strip)
  "Resolve every `:bind' of SPEC against DATA (SPEC 13.8.3); return a copy.
The reference materializer: bound members take the value their pointer
reaches, or are omitted when it reaches nothing; a required member left
absent, an unbindable or unknown member, a member both authored and
bound, a malformed pointer, or a resolved value of the wrong type signals
`jetpacs-data-error' with (PATH REASON) at `<node>.bind.<member>', in
walk order.  A `:template' (SPEC 13.8.5) is expanded once per element of
the array its `:children' pointer reaches, each copy in an item scope
where a relative pointer resolves from the element; a structural
violation is reported at the authored template's path.  With STRIP
non-nil the `:bind' and `:template' members are removed and every remote
descriptor inside an expanded item receives the item's key as `:item' in
its `:args', as a snapshot bound for a Companion without `data.model' or
without the `data.template' feature needs."
  (jetpacs-data-check-document data)
  (let ((count 0))
    (cl-labels
        ((walk (value path scope depth item)
           (cond
            ((vectorp value)
             (let ((i -1))
               (vconcat (mapcar (lambda (child)
                                  (walk child (format "%s[%d]" path (cl-incf i)) scope depth item))
                                value))))
            ((hash-table-p value)
             (let ((out (make-hash-table :test (hash-table-test value)
                                         :size (hash-table-count value))))
               (maphash (lambda (k child)
                          (puthash k (walk child (format "%s.%s" path k) scope depth item) out))
                        value)
               out))
            ((and (consp value) (keywordp (car value)))
             (if (plist-member value :t)
                 (walk-node value path scope depth nil item)
               (walk-plist value path scope depth item)))
            (t value)))
         (walk-plist (plist path scope depth item)
           (let (out)
             (while plist
               (let ((k (pop plist)) (v (pop plist)))
                 (push k out)
                 (push (walk v (format "%s.%s" path (jetpacs--wire-name k)) scope depth item)
                       out)))
             (nreverse out)))
         (walk-node (node path scope depth template-root item)
           (let* ((type (plist-get node :t))
                  (bind (plist-get node :bind))
                  (templated (plist-member node :template))
                  (expansion 'none)
                  resolved out)
             ;; SPEC 13.8.5: the structural rules once, at the authored
             ;; path; copies and nested templates were covered by that scan.
             (when (and templated (null scope))
               (jetpacs--check-templated-node node path)
               (jetpacs-check-template (plist-get node :template)
                                       (concat path ".template") (1+ depth)))
             (when bind
               (pcase-let ((`(,r . ,e) (resolve node type bind path scope template-root)))
                 (setq resolved r expansion e)))
             (when template-root
               (setq item (and (not (eq scope jetpacs-data--probe))
                               (plist-get resolved :key))))
             (when (and strip item) (setq node (jetpacs-data--inject-item node item)))
             (let ((p node))
               (while p
                 (let ((k (pop p)) (v (pop p)))
                   (cond
                    ((and (memq k '(:bind :template)) strip))
                    ((memq k '(:bind :template)) (push k out) (push v out))
                    ((memq k jetpacs--opaque-members) (push k out) (push v out))
                    (t (push k out)
                       (push (walk v (format "%s.%s" path (jetpacs--wire-name k))
                                   scope depth item)
                             out))))))
             (setq out (nreverse out))
             (while resolved
               (let ((k (pop resolved)) (v (pop resolved)))
                 (setq out (plist-put out k v))))
             (unless (eq expansion 'none)
               (let ((template (plist-get node :template)))
                 (if (zerop (length expansion))
                     (progn
                       (walk-node template (concat path ".template")
                                  jetpacs-data--probe (1+ depth) t nil)
                       (setq out (plist-put out :children [])))
                   (let ((keys (make-hash-table :test #'equal)) (i -1))
                     (setq out
                           (plist-put out :children
                                      (vconcat
                                       (mapcar (lambda (element)
                                                 (let* ((cpath (format "%s.children[%d]" path (cl-incf i)))
                                                        (copy (walk-node template cpath element
                                                                         (1+ depth) t nil))
                                                        (key (plist-get copy :key)))
                                                   ;; SPEC 16.1 after expansion: the
                                                   ;; bound keys are the copies' identity.
                                                   (when (gethash key keys)
                                                     (signal 'jetpacs-data-error
                                                             (list (concat cpath ".key")
                                                                   "duplicate sibling key")))
                                                   (puthash key t keys)
                                                   copy))
                                               expansion))))))))
             out))
         (resolve (node type bind path scope template-root)
           (unless (and (consp bind) (keywordp (car bind)))
             (signal 'jetpacs-data-error (list (concat path ".bind") "bind must be an object")))
           (let ((row (jetpacs-node-schema-row type))
                 (templated (plist-member node :template))
                 (b bind) (expansion 'none) out)
             (while b
               (let* ((k (pop b)) (pointer (pop b))
                      (name (jetpacs--wire-name k))
                      (bpath (format "%s.bind.%s" path name)))
                 (when (> (cl-incf count) jetpacs-max-bindings-per-snapshot)
                   (signal 'jetpacs-data-error
                           (list bpath "exceeds max_bindings_per_snapshot")))
                 (when-let* ((reason (jetpacs-unbindable-reason
                                      type name template-root templated)))
                   (signal 'jetpacs-data-error (list bpath reason)))
                 (when (plist-member node k)
                   (signal 'jetpacs-data-error
                           (list bpath (format "%s is both authored and bound" name))))
                 (pcase-let ((`(,absolute . ,tokens)
                              (jetpacs-pointer-parse pointer bpath (and scope t))))
                   (unless (and (not absolute) (eq scope jetpacs-data--probe))
                     (let ((hit (jetpacs-data-resolve (if absolute data scope) tokens bpath)))
                       (cond
                        ((and (eq k :children) templated)
                         (let ((elements (if (car hit) (cdr hit) [])))
                           (unless (vectorp elements)
                             (signal 'jetpacs-data-error
                                     (list bpath "a template's children pointer must reach an array (SPEC 13.8.5)")))
                           (when (> (length elements) jetpacs-max-template-items)
                             (signal 'jetpacs-data-error
                                     (list bpath "exceeds max_template_items")))
                           (setq expansion elements)))
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
                                 (list bpath (format "required %s reaches nothing" name))))))))))
             (cons (nreverse out) expansion))))
      (walk spec "spec" nil 0 nil))))

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

(defun jetpacs-data-insert (path value)
  "An `insert' DataOp: add VALUE to an array before PATH's final index.
SPEC 13.8.4 (amendment #196): later elements shift; an index equal to the
length appends.  Send it only to a target advertising `data.insert'."
  (when (string-empty-p path)
    (signal 'jetpacs-data-error (list "insert" "insert requires an array element pointer")))
  (jetpacs-pointer-tokens path "insert")
  (list :op "insert" :path path :value value))

(defun jetpacs-data-check-op (op)
  "Signal unless OP is a well-formed DataOp plist; return OP."
  (let ((kind (plist-get op :op)))
    (unless (member kind '("set" "remove" "insert"))
      (signal 'jetpacs-data-error (list "op" "must be set, remove or insert")))
    (let ((tokens (jetpacs-pointer-tokens (plist-get op :path) kind)))
      (when (and (member kind '("set" "insert")) (not (plist-member op :value)))
        (signal 'jetpacs-data-error (list kind (format "%s requires value" kind))))
      (when (and (equal kind "insert") (null tokens))
        (signal 'jetpacs-data-error (list "path" "insert requires an array element pointer")))
      (when (and (null tokens)
                 (not (and (equal kind "set")
                           (jetpacs-data--object-p (plist-get op :value)))))
        (signal 'jetpacs-data-error
                (list "path" "the empty pointer is admitted only for a set whose value is an object"))))
    op))

(defconst jetpacs-data-insert-feature
  (plist-get (plist-get jetpacs-data-contract :ops) :insert_feature)
  "The SPEC 22.4 feature a target advertises when it admits `insert'.")

;;;; Applying DataOps (SPEC 13.8.4)

(defun jetpacs-data--object-member (object name)
  "Return (FOUND . VALUE) for member NAME of OBJECT (plist or hash table)."
  (if (hash-table-p object)
      (let ((v (gethash name object jetpacs-data--absent)))
        (if (eq v jetpacs-data--absent) (cons nil nil) (cons t v)))
    (let ((cell (plist-member object (intern (concat ":" name)))))
      (if cell (cons t (cadr cell)) (cons nil nil)))))

(defun jetpacs-data--object-put (object name value)
  "A copy of OBJECT with member NAME set to VALUE."
  (if (hash-table-p object)
      (let ((copy (copy-hash-table object))) (puthash name value copy) copy)
    (let ((key (intern (concat ":" name))))
      (if (plist-member object key)
          (plist-put (copy-sequence object) key value)
        (append object (list key value))))))

(defun jetpacs-data--object-delete (object name)
  "A copy of OBJECT without member NAME."
  (if (hash-table-p object)
      (let ((copy (copy-hash-table object))) (remhash name copy) copy)
    (let ((key (intern (concat ":" name))) (p object) out)
      (while p
        (let ((k (pop p)) (v (pop p)))
          (unless (eq k key) (push k out) (push v out))))
      (nreverse out))))

(defun jetpacs-data--index (token what limit)
  "TOKEN as an array index no greater than LIMIT, or signal at WHAT."
  (unless (string-match-p "\\`\\(0\\|[1-9][0-9]*\\)\\'" token)
    (signal 'jetpacs-data-error
            (list what "array index must be a decimal without leading zeros")))
  (let ((index (string-to-number token)))
    (when (> index limit)
      (signal 'jetpacs-data-error (list what "array index exceeds the length")))
    index))

(defun jetpacs-data--apply-in (container tokens kind value what)
  "Apply KIND with VALUE at TOKENS beneath CONTAINER; return the new container."
  (let ((token (car tokens)) (last (null (cdr tokens))))
    (cond
     ((vectorp container)
      (let ((length (length container)))
        (if (not last)
            (let ((index (jetpacs-data--index token what length)))
              (when (= index length)
                (signal 'jetpacs-data-error (list what "parent container does not exist")))
              (let ((copy (copy-sequence container)))
                (aset copy index (jetpacs-data--apply-in
                                  (aref container index) (cdr tokens) kind value what))
                copy))
          (let ((index (jetpacs-data--index token what length)))
            (pcase kind
              ("set" (if (= index length) (vconcat container (vector value))
                       (let ((copy (copy-sequence container))) (aset copy index value) copy)))
              ("insert" (vconcat (substring container 0 index) (vector value)
                                 (substring container index)))
              (_ (when (= index length)
                   (signal 'jetpacs-data-error (list what "nothing to remove at the pointer")))
                 (vconcat (substring container 0 index) (substring container (1+ index)))))))))
     ((jetpacs-data--object-p container)
      (let ((hit (jetpacs-data--object-member container token)))
        (cond
         ((not last)
          (unless (car hit)
            (signal 'jetpacs-data-error (list what "parent container does not exist")))
          (jetpacs-data--object-put
           container token (jetpacs-data--apply-in (cdr hit) (cdr tokens) kind value what)))
         ((equal kind "insert")
          (signal 'jetpacs-data-error (list what "insert requires an array parent")))
         ((equal kind "set") (jetpacs-data--object-put container token value))
         (t (unless (car hit)
              (signal 'jetpacs-data-error (list what "nothing to remove at the pointer")))
            (jetpacs-data--object-delete container token)))))
     ((null container)
      ;; The empty plist is the empty object.
      (cond ((not last) (signal 'jetpacs-data-error (list what "parent container does not exist")))
            ((equal kind "set") (list (intern (concat ":" token)) value))
            ((equal kind "insert")
             (signal 'jetpacs-data-error (list what "insert requires an array parent")))
            (t (signal 'jetpacs-data-error (list what "nothing to remove at the pointer")))))
     (t (signal 'jetpacs-data-error (list what "parent container does not exist"))))))

(defun jetpacs-data-apply-ops (document ops &optional prefix)
  "Return DOCUMENT with OPS applied in order (SPEC 13.8.4); DOCUMENT is unchanged.
The Elisp reference applier — set, remove and insert — replayed against
ebp/goldens/data-ops.golden.  Signals `jetpacs-data-error' with (PATH
REASON) at `PREFIX[i].path', `PREFIX[i].op' or `PREFIX[i]', PREFIX
defaulting to \"ops\", as the Companion reports them."
  (let ((i -1) (prefix (or prefix "ops")))
    (seq-doseq (op ops)
      (let* ((opath (format "%s[%d]" prefix (cl-incf i)))
             (kind (plist-get op :op))
             (allowed (pcase kind
                        ((or "set" "insert") '(:op :path :value))
                        ("remove" '(:op :path))
                        (_ (signal 'jetpacs-data-error
                                   (list (concat opath ".op") "must be set, remove or insert")))))
             (p op))
        (while p
          (let ((k (pop p)))
            (pop p)
            (unless (memq k allowed)
              (signal 'jetpacs-data-error
                      (list (format "%s.%s" opath (substring (symbol-name k) 1))
                            "unknown DataOp member")))))
        (when (and (memq :value allowed) (not (plist-member op :value)))
          (signal 'jetpacs-data-error (list opath (format "%s requires value" kind))))
        (let* ((what (concat opath ".path"))
               (tokens (jetpacs-pointer-tokens (plist-get op :path) what))
               (value (plist-get op :value)))
          (setq document
                (if tokens
                    (jetpacs-data--apply-in document tokens kind value what)
                  (if (and (equal kind "set") (jetpacs-data--object-p value))
                      value
                    (signal 'jetpacs-data-error
                            (list what "the empty pointer is admitted only for a set whose value is an object"))))))))
    document))

;;;; Keyed array diffs

(defun jetpacs-data-pointer-escape (token)
  "TOKEN escaped as one RFC 6901 reference token."
  (replace-regexp-in-string "/" "~1" (replace-regexp-in-string "~" "~0" token t t) t t))

(defun jetpacs-data--member-names (object)
  "The member names of OBJECT (plist or hash table), in order."
  (if (hash-table-p object) (hash-table-keys object)
    (let ((p object) out)
      (while p (push (substring (symbol-name (pop p)) 1) out) (pop p))
      (nreverse out))))

(defun jetpacs-data--same-p (a b)
  "Non-nil when A and B are the same JSON value."
  (equal (jetpacs-node->canonical-json (vector a))
         (jetpacs-node->canonical-json (vector b))))

(defun jetpacs-data--lcs (old-keys new-keys)
  "Index pairs (I . J) of a longest common subsequence of two key vectors."
  (let* ((n (length old-keys)) (m (length new-keys))
         (table (make-vector (1+ n) nil)))
    (dotimes (i (1+ n)) (aset table i (make-vector (1+ m) 0)))
    (cl-loop for i from (1- n) downto 0 do
             (cl-loop for j from (1- m) downto 0 do
                      (aset (aref table i) j
                            (if (equal (aref old-keys i) (aref new-keys j))
                                (1+ (aref (aref table (1+ i)) (1+ j)))
                              (max (aref (aref table (1+ i)) j)
                                   (aref (aref table i) (1+ j)))))))
    (let ((i 0) (j 0) pairs)
      (while (and (< i n) (< j m))
        (cond ((equal (aref old-keys i) (aref new-keys j))
               (push (cons i j) pairs) (cl-incf i) (cl-incf j))
              ((>= (aref (aref table (1+ i)) j) (aref (aref table i) (1+ j))) (cl-incf i))
              (t (cl-incf j))))
      (nreverse pairs))))

(defconst jetpacs-data-diff-max-elements 1000
  "Arrays longer than this are patched whole rather than diffed.")

(cl-defun jetpacs-data-array-ops (path old new &key (key "id") no-insert)
  "Return DataOps turning array OLD at PATH into NEW, or `:whole'.
Elements are identified by their member KEY (a string; elements are hash
tables or plists).  The ops remove every element NEW lacks (highest index
first), insert every element OLD lacks at its final index (SPEC 13.8.4,
amendment #196), then `set' or `remove' each changed member of the
elements both keep.  A reordered element is removed and re-inserted.
Returns nil when nothing changed, and `:whole' — meaning \"send `set'
PATH NEW instead\" — when an element has no or a duplicate key, either
array exceeds `jetpacs-data-diff-max-elements', or NO-INSERT is non-nil
and an element must enter anywhere but the end."
  (let* ((old (vconcat old)) (new (vconcat new))
         (keyof (lambda (e) (and (jetpacs-data--object-p e)
                                 (car (jetpacs-data--object-member e key))
                                 (cdr (jetpacs-data--object-member e key)))))
         (old-keys (vconcat (mapcar keyof old)))
         (new-keys (vconcat (mapcar keyof new))))
    (if (or (> (length old) jetpacs-data-diff-max-elements)
            (> (length new) jetpacs-data-diff-max-elements)
            (seq-contains-p old-keys nil) (seq-contains-p new-keys nil)
            (/= (length old-keys) (length (seq-uniq old-keys)))
            (/= (length new-keys) (length (seq-uniq new-keys))))
        :whole
      (catch 'whole
        (let* ((pairs (jetpacs-data--lcs old-keys new-keys))
               (kept-old (mapcar #'car pairs)) (kept-new (mapcar #'cdr pairs))
               (length (length old)) ops)
          (cl-loop for i from (1- (length old)) downto 0
                   unless (memq i kept-old)
                   do (push (jetpacs-data-remove (format "%s/%d" path i)) ops)
                   (cl-decf length))
          (dotimes (j (length new))
            (unless (memq j kept-new)
              (let ((at (format "%s/%d" path j)))
                (push (cond ((not no-insert) (jetpacs-data-insert at (aref new j)))
                            ((= j length) (jetpacs-data-set at (aref new j)))
                            (t (throw 'whole :whole)))
                      ops)
                (cl-incf length))))
          (pcase-dolist (`(,i . ,j) pairs)
            (let ((before (aref old i)) (after (aref new j))
                  (at (format "%s/%d" path j)))
              (unless (jetpacs-data--same-p before after)
                (dolist (name (jetpacs-data--member-names after))
                  (let ((was (jetpacs-data--object-member before name))
                        (now (cdr (jetpacs-data--object-member after name))))
                    (unless (and (car was) (jetpacs-data--same-p (cdr was) now))
                      (push (jetpacs-data-set
                             (concat at "/" (jetpacs-data-pointer-escape name)) now)
                            ops))))
                (dolist (name (jetpacs-data--member-names before))
                  (unless (car (jetpacs-data--object-member after name))
                    (push (jetpacs-data-remove
                           (concat at "/" (jetpacs-data-pointer-escape name)))
                          ops))))))
          (nreverse ops))))))

(provide 'jetpacs-data)
;;; jetpacs-data.el ends here
