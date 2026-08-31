;;; jetpacs-component-catalog-actions.el --- Safe catalog action projection -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later
;; Package-Requires: ((emacs "30.1"))

;;; Commentary:

;; Catalog-private policy for projecting authored ActionDescriptors into a
;; live specimen.  An unarmed specimen receives only `jpcatalog.trace'
;; descriptors.  Arming is deliberately fail-closed: the current READY client,
;; action registry, surface ownership, target profile, and document-local
;; references must all prove that the authored descriptor can dispatch safely.
;; The authored descriptor is returned alongside the projection and never
;; embedded in trace arguments.

;;; Code:

(require 'cl-lib)
(require 'seq)
(require 'subr-x)
(require 'jetpacs-vocabulary)
(require 'jetpacs-widgets)
(require 'jetpacs-surfaces)

(defconst jetpacs-component-catalog-actions-trace-action "jpcatalog.trace"
  "Drop-only action used by unarmed component specimens.")

(defconst jetpacs-component-catalog-actions--semantic-hook :on_action
  "ActionDescriptor member used by custom accessibility actions.")

(defconst jetpacs-component-catalog-actions--opaque-members
  '(:args :meta :value)
  "Application-data members the action walker must never interpret.")

(defconst jetpacs-component-catalog-actions--trace-max-path-bytes 384)
(defconst jetpacs-component-catalog-actions--trace-max-name-bytes 128)
(defconst jetpacs-component-catalog-actions--trace-max-values 16)

(defun jetpacs-component-catalog-actions--wire-member-name (key)
  "Return KEY's wire member spelling, without its leading colon.
KEY may be a keyword or a colon-prefixed symbol from a private obarray.  This
function deliberately does not intern either spelling."
  (and (symbolp key)
       (let ((name (symbol-name key)))
         (and (string-prefix-p ":" name)
              (substring name 1)))))

(defun jetpacs-component-catalog-actions--wire-key-equal-p (left right)
  "Whether colon-prefixed symbol keys LEFT and RIGHT have the same name."
  (let ((left-name
         (jetpacs-component-catalog-actions--wire-member-name left))
        (right-name
         (jetpacs-component-catalog-actions--wire-member-name right)))
    (and left-name right-name (equal left-name right-name))))

(defun jetpacs-component-catalog-actions--wire-key-member-p (key keys)
  "Whether wire KEY is name-equivalent to one of KEYS."
  (seq-some
   (lambda (candidate)
     (jetpacs-component-catalog-actions--wire-key-equal-p key candidate))
   keys))

(defun jetpacs-component-catalog-actions--opaque-member-p (key)
  "Whether wire KEY names an application-data member."
  (jetpacs-component-catalog-actions--wire-key-member-p
   key jetpacs-component-catalog-actions--opaque-members))

(defun jetpacs-component-catalog-actions--hook-p (key)
  "Return non-nil when wire KEY is an ActionDescriptor hook."
  (let ((name (jetpacs-component-catalog-actions--wire-member-name key)))
    (and name
         (or (equal
              name
              (jetpacs-component-catalog-actions--wire-member-name
               jetpacs-component-catalog-actions--semantic-hook))
             (member name jetpacs-action-hook-keys)))))

(defun jetpacs-component-catalog-actions--plist-p (value)
  "Whether VALUE is a well-formed colon-prefixed-symbol plist."
  (and (consp value)
       (let ((n (proper-list-p value)))
         (and n
              (cl-evenp n)
              (cl-loop for (key _member) on value by #'cddr
                       always
                       (jetpacs-component-catalog-actions--wire-member-name
                        key))))))

(defun jetpacs-component-catalog-actions--sorted-hash-keys (table)
  "Return TABLE's keys in a stable diagnostic traversal order."
  (sort (hash-table-keys table)
        (lambda (left right)
          (string< (format "%S" left) (format "%S" right)))))

(defun jetpacs-component-catalog-actions-descriptors (document)
  "Return every ActionDescriptor in DOCUMENT, in stable tree order.

Each result is `(:path PATH :hook HOOK :node-type TYPE :descriptor COPY)'.
PATH is a list of keyword members, sequence indexes, and hash keys.  The walk
uses the generated action-hook vocabulary plus Semantics' `on_action', and it
does not descend into opaque application `args', `meta', or `value' objects."
  (let (records)
    (cl-labels
        ((walk
          (value path)
          (cond
           ((vectorp value)
            (dotimes (index (length value))
              (walk (aref value index) (append path (list index)))))
           ((hash-table-p value)
            (dolist (key
                     (jetpacs-component-catalog-actions--sorted-hash-keys
                      value))
              (walk (gethash key value) (append path (list key)))))
           ((jetpacs-component-catalog-actions--plist-p value)
            (let ((node-type (and (stringp (plist-get value :t))
                                  (plist-get value :t))))
              (cl-loop for (key child) on value by #'cddr
                       for child-path = (append path (list key))
                       do (cond
                           ((and child
                                 (jetpacs-component-catalog-actions--hook-p
                                  key))
                            (push (list :path child-path
                                        :hook key
                                        :node-type node-type
                                        :descriptor (copy-tree child))
                                  records))
                           ((not
                             (jetpacs-component-catalog-actions--opaque-member-p
                              key))
                            (walk child child-path))))))
           ((proper-list-p value)
            (cl-loop for child in value
                     for index from 0
                     do (walk child (append path (list index)))))
           ((consp value)
            ;; Tolerate an alist/dotted pair around a node.  Canonical EBP
            ;; uses hash tables for JSON objects, but callers may inspect an
            ;; intermediate representation before that final normalization.
            (walk (car value) (append path '(:car)))
            (walk (cdr value) (append path '(:cdr)))))))
      (walk document nil))
    (nreverse records)))

(defun jetpacs-component-catalog-actions--walk-tree (value fn)
  "Call FN for structural plists in VALUE, ignoring descriptors and data."
  (cond
   ((vectorp value)
    (mapc (lambda (child)
            (jetpacs-component-catalog-actions--walk-tree child fn))
          value))
   ((hash-table-p value)
    (maphash
     (lambda (_key child)
       (jetpacs-component-catalog-actions--walk-tree child fn))
     value))
   ((jetpacs-component-catalog-actions--plist-p value)
    (funcall fn value)
    (cl-loop for (key child) on value by #'cddr
             unless (or (jetpacs-component-catalog-actions--opaque-member-p
                         key)
                        (jetpacs-component-catalog-actions--hook-p key))
             do (jetpacs-component-catalog-actions--walk-tree child fn)))
   ((proper-list-p value)
    (mapc (lambda (child)
            (jetpacs-component-catalog-actions--walk-tree child fn))
          value))
   ((consp value)
    (jetpacs-component-catalog-actions--walk-tree (car value) fn)
    (jetpacs-component-catalog-actions--walk-tree (cdr value) fn))))

(defun jetpacs-component-catalog-actions--path-string (path)
  "Return a bounded, readable trace spelling of structural PATH."
  (let ((text
         (mapconcat
          (lambda (part)
            (cond
             ((jetpacs-component-catalog-actions--wire-member-name part))
             ((integerp part) (format "[%d]" part))
             ((stringp part) part)
             (t (format "%S" part))))
          path "/")))
    (jetpacs-component-catalog-actions--truncate-bytes
     text jetpacs-component-catalog-actions--trace-max-path-bytes)))

(defun jetpacs-component-catalog-actions--truncate-bytes (text maximum)
  "Return string TEXT shortened to at most MAXIMUM UTF-8 bytes."
  (let ((result (if (stringp text) text (format "%s" text))))
    (while (> (string-bytes result) maximum)
      (setq result (substring result 0 (max 0 (1- (length result))))))
    result))

(defun jetpacs-component-catalog-actions--descriptor-kind-name (descriptor)
  "Return (KIND . NAME) for DESCRIPTOR without trusting its shape."
  (cond
   ((and (jetpacs-component-catalog-actions--plist-p descriptor)
         (stringp (plist-get descriptor :action)))
    (cons "remote" (plist-get descriptor :action)))
   ((and (jetpacs-component-catalog-actions--plist-p descriptor)
         (stringp (plist-get descriptor :builtin)))
    (cons "builtin" (plist-get descriptor :builtin)))
   (t (cons "invalid" "invalid"))))

(defun jetpacs-component-catalog-actions--descriptor-fingerprint (descriptor)
  "Return a fixed-size, non-reversible fingerprint for DESCRIPTOR."
  (let ((print-circle nil)
        (print-level nil)
        (print-length nil)
        (print-quoted t))
    (secure-hash 'sha256 (prin1-to-string descriptor))))

(defun jetpacs-component-catalog-actions--plist-keys (plist)
  "Return PLIST's wire keys, including duplicates."
  (cl-loop for (key _value) on plist by #'cddr collect key))

(defun jetpacs-component-catalog-actions--issue (record reason message)
  "Build one REASON and MESSAGE compatibility issue for RECORD."
  (list :path (copy-tree (plist-get record :path))
        :reason reason
        :message message))

(defun jetpacs-component-catalog-actions--shape-issue (record)
  "Return a closed-schema issue for RECORD, or nil."
  (let ((descriptor (plist-get record :descriptor)))
    (cond
     ((not (jetpacs-component-catalog-actions--plist-p descriptor))
      (jetpacs-component-catalog-actions--issue
       record 'invalid-descriptor "Action hook value is not a keyword plist."))
     ((let* ((keys
              (jetpacs-component-catalog-actions--plist-keys descriptor))
             (names
              (mapcar #'jetpacs-component-catalog-actions--wire-member-name
                      keys))
             (distinct (delete-dups (copy-sequence names))))
        (/= (length names) (length distinct)))
      (jetpacs-component-catalog-actions--issue
       record 'invalid-descriptor "ActionDescriptor has a duplicate member."))
     ((eq (and (plist-member descriptor :action) t)
          (and (plist-member descriptor :builtin) t))
      (jetpacs-component-catalog-actions--issue
       record 'invalid-descriptor
       "ActionDescriptor must contain exactly one of action or builtin."))
     (t
      (let* ((remote (plist-member descriptor :action))
             (kind (if remote "remote" (plist-get descriptor :builtin)))
             (schema (and (stringp kind)
                          (assoc kind jetpacs-action-descriptor-schema))))
        (if (null schema)
            (jetpacs-component-catalog-actions--issue
             record 'invalid-descriptor
             "ActionDescriptor names no known kind.")
          (let* ((required (plist-get (cdr schema) :required))
                 (optional (plist-get (cdr schema) :optional))
                 (allowed (append required optional))
                 (members
                  (mapcar
                   #'jetpacs-component-catalog-actions--wire-member-name
                   (jetpacs-component-catalog-actions--plist-keys
                    descriptor))))
            (cond
             ((seq-some
               (lambda (required-member)
                 (not (member required-member members)))
               required)
              (jetpacs-component-catalog-actions--issue
               record 'invalid-descriptor
               "ActionDescriptor is missing a required member."))
             ((seq-some
               (lambda (member) (not (member member allowed))) members)
              (jetpacs-component-catalog-actions--issue
               record 'invalid-descriptor
               "ActionDescriptor contains a member outside its closed schema."))))))))))

(defun jetpacs-component-catalog-actions--identifier-p (value)
  "Whether VALUE is an EBP identifier."
  (and (stringp value) (jetpacs-identifier-p value)))

(defun jetpacs-component-catalog-actions--identifier-array-p (value)
  "Whether VALUE is an array/list of distinct EBP identifiers."
  (and (or (vectorp value) (proper-list-p value))
       (let ((items (append value nil)))
         (and (cl-every
               #'jetpacs-component-catalog-actions--identifier-p items)
              (= (length items)
                 (length (delete-dups (copy-sequence items))))))))

(defun jetpacs-component-catalog-actions--confirm-p (value)
  "Whether VALUE is a structurally valid confirmation face."
  (or (and (stringp value) (not (string-empty-p value)))
      (and
       (jetpacs-component-catalog-actions--plist-p value)
       (let* ((keys
               (jetpacs-component-catalog-actions--plist-keys value))
              (allowed '(:text :title :icon :confirm_label :dismiss_label))
              (text (plist-get value :text)))
         (and (= (length keys)
                 (length (delete-dups (copy-sequence keys))))
              (cl-every (lambda (key) (memq key allowed)) keys)
              (stringp text)
              (not (string-empty-p text))
              (cl-every
               (lambda (key)
                 (or (not (plist-member value key))
                     (stringp (plist-get value key))))
               '(:title :confirm_label :dismiss_label))
              (or (not (plist-member value :icon))
                  (jetpacs-component-catalog-actions--identifier-p
                   (plist-get value :icon))))))))

(defun jetpacs-component-catalog-actions--remote-type-issue (record)
  "Return a static remote-descriptor issue for RECORD, or nil."
  (let* ((descriptor (plist-get record :descriptor))
         (name (plist-get descriptor :action))
         (policy (if (plist-member descriptor :when_offline)
                     (plist-get descriptor :when_offline)
                   jetpacs-action-offline-default))
         (hook (plist-get record :hook))
         (hook-name
          (jetpacs-component-catalog-actions--wire-member-name hook))
         (injected
          (and hook-name
               (cdr (assoc hook-name jetpacs-action-injections))))
         (arg-members
          (and (jetpacs-component-catalog-actions--plist-p
                (plist-get descriptor :args))
               (mapcar
                #'jetpacs-component-catalog-actions--wire-member-name
                (jetpacs-component-catalog-actions--plist-keys
                 (plist-get descriptor :args))))))
    (cond
     ((not (and (jetpacs-component-catalog-actions--identifier-p name)
                (string-search "." name)))
      (jetpacs-component-catalog-actions--issue
       record 'invalid-descriptor
       "Remote action name is not a namespaced EBP identifier."))
     ((not (member policy jetpacs-action-offline-policies))
      (jetpacs-component-catalog-actions--issue
       record 'invalid-descriptor "Remote offline policy is invalid."))
     ((not (equal policy "drop"))
      (jetpacs-component-catalog-actions--issue
       record 'durable-policy
       "Queue and wake descriptors are always trace-only in the catalog."))
     ((or (plist-member descriptor :ttl_s)
          (plist-member descriptor :dedupe))
      (jetpacs-component-catalog-actions--issue
       record 'invalid-descriptor
       "Drop descriptors cannot contain ttl_s or dedupe."))
     ((and (plist-member descriptor :args)
           (let ((args (plist-get descriptor :args)))
             (not (or (null args)
                      (jetpacs-component-catalog-actions--plist-p args)))))
      (jetpacs-component-catalog-actions--issue
       record 'invalid-descriptor "Remote args is not an object."))
     ((seq-some (lambda (member) (member member arg-members)) injected)
      (jetpacs-component-catalog-actions--issue
       record 'invalid-descriptor
       "Remote args conflicts with a value injected by this hook."))
     ((and (plist-member descriptor :confirm)
           (not (jetpacs-component-catalog-actions--confirm-p
                 (plist-get descriptor :confirm))))
      (jetpacs-component-catalog-actions--issue
       record 'invalid-descriptor "Remote confirmation face is invalid."))
     ((and (plist-member descriptor :capture_fields)
           (not (jetpacs-component-catalog-actions--identifier-array-p
                 (plist-get descriptor :capture_fields))))
      (jetpacs-component-catalog-actions--issue
       record 'invalid-descriptor "capture_fields is not a distinct ID array."))
     ((and (plist-member descriptor :open_surface)
           (let ((surface (plist-get descriptor :open_surface)))
             (not (and
                   (jetpacs-component-catalog-actions--identifier-p surface)
                   (string-match-p "\\`app:[A-Za-z0-9]" surface)))))
      (jetpacs-component-catalog-actions--issue
       record 'invalid-descriptor "open_surface is not an app Surface ID.")))))

(defun jetpacs-component-catalog-actions--builtin-type-issue (record)
  "Return a static builtin parameter issue for RECORD, or nil."
  (let* ((descriptor (plist-get record :descriptor))
         (builtin (plist-get descriptor :builtin)))
    (pcase builtin
      ((or "view.switch" "trigger.fire")
       (unless (jetpacs-component-catalog-actions--identifier-p
                (plist-get descriptor
                           (if (equal builtin "view.switch") :view :id)))
         (jetpacs-component-catalog-actions--issue
          record 'invalid-descriptor "Builtin reference is not an identifier.")))
      ("variant.switch"
       (unless (and
                (jetpacs-component-catalog-actions--identifier-p
                 (plist-get descriptor :id))
                (or (not (plist-member descriptor :value))
                    (jetpacs-component-catalog-actions--identifier-p
                     (plist-get descriptor :value))))
         (jetpacs-component-catalog-actions--issue
          record 'invalid-descriptor
          "variant.switch id/value is not an identifier.")))
      ("surface.open"
       (unless (let ((surface (plist-get descriptor :surface)))
                 (and
                  (jetpacs-component-catalog-actions--identifier-p surface)
                  (string-match-p "\\`app:[A-Za-z0-9]" surface)))
         (jetpacs-component-catalog-actions--issue
          record 'invalid-descriptor
          "surface.open target is not an app Surface ID.")))
      ((or "clipboard.copy" "share.send")
       (unless (and (stringp (plist-get descriptor :text))
                    (or (not (plist-member descriptor :title))
                        (stringp (plist-get descriptor :title))))
         (jetpacs-component-catalog-actions--issue
          record 'invalid-descriptor "Builtin text/title parameter is invalid.")))
      ("dialog.submit"
       (when (and (plist-member descriptor :capture_fields)
                  (not (jetpacs-component-catalog-actions--identifier-array-p
                        (plist-get descriptor :capture_fields))))
         (jetpacs-component-catalog-actions--issue
          record 'invalid-descriptor
          "dialog.submit capture_fields is not a distinct ID array.")))
      ((or "companion.settings.open" "dialog.dismiss") nil)
      (_
       (jetpacs-component-catalog-actions--issue
        record 'invalid-descriptor "Builtin is not part of the closed catalog.")))))

(defun jetpacs-component-catalog-actions--stateful-node-p (node)
  "Whether NODE has a captureable state address in this catalog."
  (or (jetpacs-stateful-node-p node)
      ;; The optional renderer vocabulary is outside the EBP core's generated
      ;; stateful set.  Its Choice renderer explicitly publishes `checked'.
      (equal (plist-get node :t) "jetpacs.choice")))

(defun jetpacs-component-catalog-actions--node-records-with-id (document id)
  "Return path/node records in DOCUMENT whose authored ID equals ID."
  (let (found)
    (cl-labels
        ((walk
          (value path)
          (cond
           ((vectorp value)
            (dotimes (index (length value))
              (walk (aref value index) (append path (list index)))))
           ((hash-table-p value)
            (dolist (key
                     (jetpacs-component-catalog-actions--sorted-hash-keys
                      value))
              (walk (gethash key value) (append path (list key)))))
           ((jetpacs-component-catalog-actions--plist-p value)
            (when (and (stringp (plist-get value :t))
                       (equal (plist-get value :id) id))
              (push (list :path (copy-tree path) :node value) found))
            (cl-loop for (key child) on value by #'cddr
                     unless
                     (or
                      (jetpacs-component-catalog-actions--opaque-member-p key)
                      (jetpacs-component-catalog-actions--hook-p key))
                     do (walk child (append path (list key)))))
           ((proper-list-p value)
            (cl-loop for child in value
                     for index from 0
                     do (walk child (append path (list index)))))
           ((consp value)
            (walk (car value) (append path '(:car)))
            (walk (cdr value) (append path '(:cdr)))))))
      (walk document nil))
    (nreverse found)))

(defun jetpacs-component-catalog-actions--path-equal-p (left right)
  "Whether structural paths LEFT and RIGHT name the same wire location."
  (and (= (length left) (length right))
       (cl-every
        (lambda (pair)
          (let* ((left-part (car pair))
                 (right-part (cdr pair))
                 (left-name
                  (jetpacs-component-catalog-actions--wire-member-name
                   left-part))
                 (right-name
                  (jetpacs-component-catalog-actions--wire-member-name
                   right-part)))
            (if (and left-name right-name)
                (equal left-name right-name)
              (equal left-part right-part))))
        (cl-mapcar #'cons left right))))

(defun jetpacs-component-catalog-actions--own-password-submit-p
    (record node-record)
  "Whether RECORD is the exact `on_submit' hook of NODE-RECORD."
  (and (equal
        (jetpacs-component-catalog-actions--wire-member-name
         (plist-get record :hook))
        "on_submit")
       (jetpacs-component-catalog-actions--path-equal-p
        (plist-get record :path)
        (append (plist-get node-record :path) '(:on_submit)))))

(defun jetpacs-component-catalog-actions--password-capture-legal-p
    (record node-record context tracep)
  "Whether RECORD may capture password NODE-RECORD.
CONTEXT proves an outstanding dialog for authored `dialog.submit' builtins.
When TRACEP is non-nil, RECORD is being projected to a remote trace action, so
only the password node's exact `on_submit' hook can carry its value."
  (or (jetpacs-component-catalog-actions--own-password-submit-p
       record node-record)
      (and (not tracep)
           (equal (plist-get (plist-get record :descriptor) :builtin)
                  "dialog.submit")
           (eq (or (plist-get context :target) :app) :dialog)
           (plist-get context :dialog-context))))

(defun jetpacs-component-catalog-actions--capture-issue
    (record document client &optional context tracep)
  "Return RECORD's capture issue in DOCUMENT for CLIENT, or nil.
CONTEXT proves authored dialog scope.  TRACEP applies the stricter remote
trace policy after instrumentation has changed the descriptor's execution."
  (let* ((descriptor (plist-get record :descriptor))
         (present (plist-member descriptor :capture_fields))
         (fields (and present (plist-get descriptor :capture_fields)))
         (items (and (or (vectorp fields) (proper-list-p fields))
                     (append fields nil)))
         (limit (and client
                     (plist-get (ebp-client-limits client)
                                :max_capture_fields)))
         matches-by-id)
    (cond
     ((not present) nil)
     ((not (jetpacs-component-catalog-actions--identifier-array-p fields))
      (jetpacs-component-catalog-actions--issue
       record 'capture-invalid "capture_fields is not a distinct ID array."))
     ((and (integerp limit) (> (length items) limit))
      (jetpacs-component-catalog-actions--issue
       record 'capture-invalid
       "capture_fields exceeds this session's advertised limit."))
     ((progn
        (setq matches-by-id
              (mapcar
               (lambda (id)
                 (cons
                  id
                  (jetpacs-component-catalog-actions--node-records-with-id
                   document id)))
               items))
        (seq-some
         (lambda (entry)
           (let ((matches (cdr entry)))
             (not
              (and
               (= (length matches) 1)
               (jetpacs-component-catalog-actions--stateful-node-p
                (plist-get (car matches) :node))))))
         matches-by-id))
      (jetpacs-component-catalog-actions--issue
       record 'capture-invalid
       "A captured ID does not resolve exactly once to a stateful node."))
     ((seq-some
       (lambda (entry)
         (let* ((node-record (car (cdr entry)))
                (node (plist-get node-record :node)))
           (and
            (equal (plist-get node :t) "text_input")
            (eq (plist-get node :password) t)
            (not
             (jetpacs-component-catalog-actions--password-capture-legal-p
              record node-record context tracep)))))
       matches-by-id)
      (jetpacs-component-catalog-actions--issue
       record 'capture-invalid
       "A password capture is not legal for this exact hook and node.")))))

(defun jetpacs-component-catalog-actions--profile (client target)
  "Return CLIENT's authoritative TARGET profile, or nil."
  (and client (plist-get (ebp-client-profiles client) target)))

(defun jetpacs-component-catalog-actions--ready-issue (record client)
  "Return a RECORD issue unless CLIENT is READY."
  (unless (and client (eq (ebp-client-state client) 'ready))
    (jetpacs-component-catalog-actions--issue
     record 'not-ready
     "A READY device session is required before actions can be armed.")))

(defun jetpacs-component-catalog-actions--remote-runtime-issue
    (record document context client profile)
  "Return remote RECORD's first DOCUMENT runtime issue.
CONTEXT supplies the surface/target, CLIENT the session, and PROFILE its
applicable advertised surface profile."
  (let* ((descriptor (plist-get record :descriptor))
         (name (plist-get descriptor :action))
         (surface (or (plist-get context :surface) "app:jpcatalog"))
         (target (or (plist-get context :target) :app))
         (schema (and (stringp name) (jetpacs-action-schema name)))
         (owner (and (stringp name) (jetpacs--owner-of "action" name))))
    (cond
     ((null schema)
      (jetpacs-component-catalog-actions--issue
       record 'unregistered-action
       "Remote action has no registered Emacs handler."))
     ((not (and client (gethash name (ebp-client-actions client))))
      (jetpacs-component-catalog-actions--issue
       record 'not-live-allowlisted
       "Remote action is absent from the live client's allowlist."))
     ((and owner
           (not (plist-get schema :any-surface))
           (not (jetpacs-owned-surface-p surface owner))
           (not
            (and jetpacs-guest-delegation-function
                 (condition-case nil
                     (funcall jetpacs-guest-delegation-function owner surface)
                   (error nil)))))
      (jetpacs-component-catalog-actions--issue
       record 'foreign-surface
       "Remote action ownership would reject this catalog surface."))
     ((and (plist-member descriptor :open_surface)
           (not (eq target :app)))
      (jetpacs-component-catalog-actions--issue
       record 'builtin-context
       "open_surface is valid only for an app target."))
     ((and (plist-member descriptor :open_surface)
           (not (and profile
                     (member jetpacs-action-open-surface-feature
                             (append (plist-get profile :features) nil)))))
      (jetpacs-component-catalog-actions--issue
       record 'feature-unadvertised
       "The app profile does not advertise action.open_surface."))
     ((and (plist-member descriptor :capture_fields)
           (not (memq target '(:app :dialog))))
      (jetpacs-component-catalog-actions--issue
       record 'capture-invalid
       "capture_fields requires a containing app surface or dialog."))
     ((jetpacs-component-catalog-actions--capture-issue
       record document client context)))))

(defun jetpacs-component-catalog-actions--view-ids (document context)
  "Return view IDs proven by DOCUMENT or explicit CONTEXT."
  (if (plist-member context :view-ids)
      (append (plist-get context :view-ids) nil)
    (let (ids)
      (jetpacs-component-catalog-actions--walk-tree
       document
       (lambda (value)
         (when-let* ((views (and (plist-member value :views)
                                 (plist-get value :views))))
           (cond
            ((hash-table-p views)
             (setq ids (append (hash-table-keys views) ids)))
            ((proper-list-p views)
             (dolist (entry views)
               (when (and (consp entry) (stringp (car entry)))
                 (push (car entry) ids))))))))
      (delete-dups ids))))

(defun jetpacs-component-catalog-actions--variant-hosts (document id)
  "Return variant_host nodes in DOCUMENT named ID."
  (let (hosts)
    (jetpacs-component-catalog-actions--walk-tree
     document
     (lambda (value)
       (when (and (equal (plist-get value :t) "variant_host")
                  (equal (plist-get value :id) id))
         (push value hosts))))
    hosts))

(defun jetpacs-component-catalog-actions--value-producing-hook-p (hook)
  "Whether HOOK receives one or more injected action arguments."
  (when-let* ((name
               (jetpacs-component-catalog-actions--wire-member-name hook)))
    (assoc name jetpacs-action-injections)))

(defun jetpacs-component-catalog-actions--builtin-context-issue
    (record document context client)
  "Return builtin RECORD's first DOCUMENT reference issue under CONTEXT.
CLIENT supplies grants and capture limits."
  (let* ((descriptor (plist-get record :descriptor))
         (builtin (plist-get descriptor :builtin))
         (hook (plist-get record :hook))
         (target (or (plist-get context :target) :app)))
    (cond
     ((and (jetpacs-component-catalog-actions--value-producing-hook-p hook)
           (not (and (equal builtin "dialog.submit")
                     (equal
                      (jetpacs-component-catalog-actions--wire-member-name hook)
                      "on_submit")
                     (eq target :dialog)
                     (plist-get context :dialog-context))))
      (jetpacs-component-catalog-actions--issue
       record 'value-hook-builtin
       "This value-producing hook requires a remote action descriptor."))
     ((equal builtin "view.switch")
      (unless (and (eq target :app)
                   (member (plist-get descriptor :view)
                           (jetpacs-component-catalog-actions--view-ids
                            document context)))
        (jetpacs-component-catalog-actions--issue
         record 'reference-invalid
         "view.switch does not resolve to a view in this app document.")))
     ((equal builtin "variant.switch")
      (let* ((hosts
              (jetpacs-component-catalog-actions--variant-hosts
               document (plist-get descriptor :id)))
             (host (and (= (length hosts) 1) (car hosts)))
             (values
              (and host
                   (mapcar (lambda (variant) (plist-get variant :value))
                           (append (plist-get host :variants) nil)))))
        (unless (and (eq target :app)
                     host
                     (or (not (plist-member descriptor :value))
                         (member (plist-get descriptor :value) values)))
          (jetpacs-component-catalog-actions--issue
           record 'reference-invalid
           "variant.switch does not resolve uniquely in this app document."))))
     ((equal builtin "surface.open")
      (unless (eq target :app)
        (jetpacs-component-catalog-actions--issue
         record 'builtin-context "surface.open is valid only for app targets.")))
     ((equal builtin "trigger.fire")
      (cond
       ((not (jetpacs-granted-p "triggers" client))
        (jetpacs-component-catalog-actions--issue
         record 'builtin-context
         "trigger.fire requires the session's triggers grant."))
       (t
        ;; `ebp-client-triggers-set' reports only a count.  There is no
        ;; authoritative read API for the accepted manual trigger IDs, so the
        ;; catalog cannot prove the reference demanded by SPEC 14.2.
        (jetpacs-component-catalog-actions--issue
         record 'trigger-unverifiable
         "The registered manual trigger target cannot be proven by current APIs."))))
     ((member builtin '("dialog.submit" "dialog.dismiss"))
      (or
       (unless (and (eq target :dialog)
                    (plist-get context :dialog-context))
         (jetpacs-component-catalog-actions--issue
          record 'builtin-context
          "Dialog completion builtins require a containing outstanding dialog."))
       (jetpacs-component-catalog-actions--capture-issue
        record document client context)))
     ((jetpacs-component-catalog-actions--capture-issue
       record document client context)))))

(defun jetpacs-component-catalog-actions--builtin-runtime-issue
    (record document context client profile)
  "Return builtin RECORD's first DOCUMENT runtime issue.
CONTEXT supplies its target, CLIENT the session, and PROFILE the applicable
advertised vocabulary."
  (let ((builtin (plist-get (plist-get record :descriptor) :builtin)))
    (cond
     ((null profile)
      (jetpacs-component-catalog-actions--issue
       record 'profile-unavailable
       "The live client supplied no applicable surface profile."))
     ((not (member builtin (append (plist-get profile :builtins) nil)))
      (jetpacs-component-catalog-actions--issue
       record 'builtin-unadvertised
       "Builtin is absent from the applicable live surface profile."))
     ((jetpacs-component-catalog-actions--builtin-context-issue
       record document context client)))))

(defun jetpacs-component-catalog-actions--record-issue
    (record document context client profile)
  "Return RECORD's first DOCUMENT arming incompatibility, or nil.
CONTEXT, CLIENT, and PROFILE supply the live execution environment."
  (or (jetpacs-component-catalog-actions--shape-issue record)
      (let* ((descriptor (plist-get record :descriptor))
             (remote (plist-member descriptor :action)))
        (or (if remote
                (jetpacs-component-catalog-actions--remote-type-issue record)
              (jetpacs-component-catalog-actions--builtin-type-issue record))
            (jetpacs-component-catalog-actions--ready-issue record client)
            (if remote
                (jetpacs-component-catalog-actions--remote-runtime-issue
                 record document context client profile)
              (jetpacs-component-catalog-actions--builtin-runtime-issue
               record document context client profile))))))

(defun jetpacs-component-catalog-actions-compatibility
    (document &optional context)
  "Return fail-closed arming compatibility for DOCUMENT.

The result is `(:compatible BOOL :descriptors RECORDS :issues ISSUES)'.
CONTEXT is an optional plist with `:surface' (default `app:jpcatalog'),
`:target' (default `:app'), `:client' (default `jetpacs-client'), optional
`:view-ids' supplied by a known outer multi-view compositor, and
`:dialog-context' for a caller that owns an outstanding dialog."
  (let* ((records (jetpacs-component-catalog-actions-descriptors document))
         (client (if (plist-member context :client)
                     (plist-get context :client)
                   (jetpacs-client)))
         (target (or (plist-get context :target) :app))
         (profile (jetpacs-component-catalog-actions--profile client target))
         issues)
    (dolist (record records)
      (when-let* ((issue
                   (jetpacs-component-catalog-actions--record-issue
                    record document context client profile)))
        (push issue issues)))
    (setq issues (nreverse issues))
    (list :compatible (null issues)
          :descriptors records
          :issues issues)))

(defun jetpacs-component-catalog-actions-compatible-p
    (document &optional context)
  "Whether every descriptor in DOCUMENT is safe to arm under CONTEXT."
  (plist-get
   (jetpacs-component-catalog-actions-compatibility document context)
   :compatible))

(defun jetpacs-component-catalog-actions--trace-descriptor
    (record document client)
  "Return a safe trace descriptor for authored RECORD in DOCUMENT.
CLIENT supplies the current capture-field limit when available."
  (let* ((authored (plist-get record :descriptor))
         (kind-name
          (jetpacs-component-catalog-actions--descriptor-kind-name authored))
         (kind (car kind-name))
         (name
          (jetpacs-component-catalog-actions--truncate-bytes
           (cdr kind-name)
           jetpacs-component-catalog-actions--trace-max-name-bytes))
         (args
          (list
           :catalog_path
           (jetpacs-component-catalog-actions--path-string
            (plist-get record :path))
           :catalog_kind kind
           :catalog_name name
           :catalog_fingerprint
           (jetpacs-component-catalog-actions--descriptor-fingerprint
            authored)))
         (trace
          (list :action jetpacs-component-catalog-actions-trace-action
                :args args
                :when_offline "drop")))
    ;; Confirmation remains a local presentation guard, but never enters the
    ;; trace payload.  Capture is retained only when it resolves safely; the
    ;; trace summarizer below records shapes/lengths and discards every value.
    (when (and (jetpacs-component-catalog-actions--plist-p authored)
               (plist-member authored :confirm)
               (jetpacs-component-catalog-actions--confirm-p
                (plist-get authored :confirm)))
      (setq trace
            (append trace
                    (list :confirm (copy-tree (plist-get authored :confirm))))))
    (when (and (jetpacs-component-catalog-actions--plist-p authored)
               (plist-member authored :capture_fields)
               (null (jetpacs-component-catalog-actions--capture-issue
                      record document client nil t)))
      (setq trace
            (append
             trace
             (list :capture_fields
                   (vconcat (append (plist-get authored :capture_fields)
                                    nil))))))
    trace))

(defun jetpacs-component-catalog-actions--rewrite
    (value path document client)
  "Return an unarmed copy of VALUE below PATH in DOCUMENT.
Rewrite action hooks recursively; CLIENT supplies live capture limits."
  (cond
   ((vectorp value)
    (let ((result (make-vector (length value) nil)))
      (dotimes (index (length value))
        (aset result index
              (jetpacs-component-catalog-actions--rewrite
               (aref value index) (append path (list index)) document client)))
      result))
   ((hash-table-p value)
    (let ((result (copy-hash-table value)))
      (maphash
       (lambda (key child)
         (puthash
          key
          (jetpacs-component-catalog-actions--rewrite
           child (append path (list key)) document client)
          result))
       value)
      result))
   ((jetpacs-component-catalog-actions--plist-p value)
    (let ((node-type (and (stringp (plist-get value :t))
                          (plist-get value :t)))
          result)
      (cl-loop for (key child) on value by #'cddr
               for child-path = (append path (list key))
               do
               (setq
                result
                (append
                 result
                 (list
                  key
                  (cond
                   ((and child
                         (jetpacs-component-catalog-actions--hook-p key))
                    (jetpacs-component-catalog-actions--trace-descriptor
                     (list :path child-path
                           :hook key
                           :node-type node-type
                           :descriptor (copy-tree child))
                     document client))
                   ((jetpacs-component-catalog-actions--opaque-member-p key)
                    (copy-tree child))
                   (t
                    (jetpacs-component-catalog-actions--rewrite
                     child child-path document client)))))))
      result))
   ((proper-list-p value)
    (cl-loop for child in value
             for index from 0
             collect
             (jetpacs-component-catalog-actions--rewrite
              child (append path (list index)) document client)))
   ((consp value)
    (cons
     (jetpacs-component-catalog-actions--rewrite
      (car value) (append path '(:car)) document client)
     (jetpacs-component-catalog-actions--rewrite
      (cdr value) (append path '(:cdr)) document client)))
   (t value)))

(defun jetpacs-component-catalog-actions-instrument
    (document armed &optional context)
  "Project DOCUMENT for preview according to requested ARMED state.

The result is `(:document COPY :authored RECORDS :armed ACTUAL
:compatibility RESULT)'.  A requested arm succeeds only when RESULT is fully
compatible under CONTEXT; otherwise ACTUAL is nil and the returned document
remains safely instrumented with trace descriptors.  Authored descriptors are
independent copies and are never stored in trace args."
  (let* ((compatibility
          (jetpacs-component-catalog-actions-compatibility document context))
         (actual (and armed (plist-get compatibility :compatible)))
         (client (if (plist-member context :client)
                     (plist-get context :client)
                   (jetpacs-client))))
    (list
     :document
     (if actual
         (copy-tree document)
       (jetpacs-component-catalog-actions--rewrite
        document nil document client))
     :authored (plist-get compatibility :descriptors)
     :armed (and actual t)
     :compatibility compatibility)))

(defun jetpacs-component-catalog-actions--safe-shape (value)
  "Return bounded type/size metadata for VALUE, never VALUE itself."
  (cond
   ((stringp value)
    (list :type "string" :length (length value) :bytes (string-bytes value)))
   ((numberp value) (list :type "number"))
   ((memq value '(t :json-false :false)) (list :type "boolean"))
   ((null value) (list :type "null"))
   ((vectorp value) (list :type "array" :length (length value)))
   ((hash-table-p value)
    (list :type "object" :members (hash-table-count value)))
   ((jetpacs-component-catalog-actions--plist-p value)
    (list :type "object" :members (/ (length value) 2)))
   ((consp value)
    (list :type "array" :length (or (proper-list-p value) 0)))
   (t (list :type "unknown"))))

(defun jetpacs-component-catalog-actions--object-entries (value)
  "Return (COUNT . first bounded KEY/VALUE cells) from object VALUE."
  (let ((count 0) entries)
    (cond
     ((hash-table-p value)
      (dolist (key
               (jetpacs-component-catalog-actions--sorted-hash-keys value))
        (cl-incf count)
        (when (<= count
                  jetpacs-component-catalog-actions--trace-max-values)
          (push (cons key (gethash key value)) entries))))
     ((jetpacs-component-catalog-actions--plist-p value)
      (cl-loop for (key child) on value by #'cddr
               do (progn
                    (cl-incf count)
                    (when (<= count
                              jetpacs-component-catalog-actions--trace-max-values)
                      (push (cons key child) entries))))))
    (cons count (nreverse entries))))

(defun jetpacs-component-catalog-actions--safe-trace-string
    (value maximum &optional predicate)
  "Return string VALUE bounded by MAXIMUM bytes.
When PREDICATE is non-nil, require it to accept VALUE first."
  (and (stringp value)
       (or (null predicate) (funcall predicate value))
       (jetpacs-component-catalog-actions--truncate-bytes value maximum)))

(defun jetpacs-component-catalog-actions-trace-summary (args params)
  "Return a bounded, redaction-safe summary of one trace occurrence.

ARGS is the handler's injected argument object and PARAMS the complete
`event.action' params.  Only catalog-authored path/kind/name/fingerprint
metadata is retained.  Receiver-injected args and captured fields contribute
only type and length/count shapes; no value or captured field ID is returned."
  (let* ((catalog-keys
          '(:catalog_path :catalog_kind :catalog_name :catalog_fingerprint))
         (arg-object
          (jetpacs-component-catalog-actions--object-entries args))
         (field-object
          (jetpacs-component-catalog-actions--object-entries
           (plist-get params :fields)))
         injected field-shapes)
    (dolist (entry (cdr arg-object))
      (unless (jetpacs-component-catalog-actions--wire-key-member-p
               (car entry) catalog-keys)
        (push (jetpacs-component-catalog-actions--safe-shape (cdr entry))
              injected)))
    (dolist (entry (cdr field-object))
      ;; Deliberately discard the field ID as well as its value.  Password and
      ;; ordinary captures therefore share the same redaction rule.
      (push (jetpacs-component-catalog-actions--safe-shape (cdr entry))
            field-shapes))
    (list
     :path
     (jetpacs-component-catalog-actions--safe-trace-string
      (plist-get args :catalog_path)
      jetpacs-component-catalog-actions--trace-max-path-bytes)
     :kind
     (jetpacs-component-catalog-actions--safe-trace-string
      (plist-get args :catalog_kind) 8
      (lambda (value) (member value '("remote" "builtin" "invalid"))))
     :name
     (jetpacs-component-catalog-actions--safe-trace-string
      (plist-get args :catalog_name)
      jetpacs-component-catalog-actions--trace-max-name-bytes)
     :fingerprint
     (jetpacs-component-catalog-actions--safe-trace-string
      (plist-get args :catalog_fingerprint) 64
      (lambda (value)
        (string-match-p "\\`[0-9a-f]\\{64\\}\\'" value)))
     :injected_count
     (max 0 (- (car arg-object)
               (cl-count-if
                (lambda (catalog-key)
                  (seq-some
                   (lambda (entry)
                     (jetpacs-component-catalog-actions--wire-key-equal-p
                      (car entry) catalog-key))
                   (cdr arg-object)))
                catalog-keys)))
     :injected (vconcat (nreverse injected))
     :field_count (car field-object)
     :fields (vconcat (nreverse field-shapes)))))

(provide 'jetpacs-component-catalog-actions)
;;; jetpacs-component-catalog-actions.el ends here
