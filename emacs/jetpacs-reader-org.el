;;; jetpacs-reader-org.el --- Org adapter for the Jetpacs reader -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later
;; Package-Requires: ((emacs "30.1"))

;;; Commentary:

;; The Orgro-shaped Org reader, implemented by renting built-in Org:
;; `org-cycle-overview'/`org-cycle-content' for visibility cycling,
;; `org-occur' for text and regexp sparse views,
;; `org-match-sparse-tree' for tags/properties/TODO filtering,
;; Org fontification and pretty entities for reader mode, org-crypt for
;; symmetric encrypted entries, and `jetpacs-org-render' for the wire
;; representation.  Tables, images, LaTeX, links, citations,
;; attachments, footnotes, checkbox/timestamp actions, drawers, and
;; blocks already flow through that renderer and Org's own keymaps.
;; Link meaning likewise stays in Org.  After `org-open-at-point' resolves
;; a destination, this adapter presents file-backed results through the
;; existing Files document host; it never parses or rewrites link syntax.
;;
;; Transclusion is intentionally absent: it is not built into Org and
;; therefore does not belong in this built-in-backed adapter.

;;; Code:

(require 'cl-lib)
(require 'subr-x)
(require 'org)
(require 'org-fold)
(require 'org-crypt)
(require 'ebp-org)
(require 'jetpacs-reader)
(require 'jetpacs-org-render)
(require 'jetpacs-widgets)
(require 'jetpacs-shell)

(defcustom jetpacs-reader-org-max-query-chars 500
  "Maximum search or sparse-tree query accepted from a reader."
  :type 'integer :group 'jetpacs-org)

(defvar jetpacs-reader-org--registered nil
  "Non-nil while the Org reader adapter and actions are registered.")

(defcustom jetpacs-reader-org-prewarm-idle-seconds 0.25
  "Idle delay before authoring the next canonical Org visibility tree.
Nil disables prewarming.  The timer only runs Emacs-owned rendering work; it
never publishes a surface or changes the reader's selected visibility."
  :type '(choice (const :tag "Disabled" nil) number)
  :group 'jetpacs-org)

(defvar jetpacs-reader-org--visibility-cache nil
  "Bounded cache of Emacs-authored canonical Org visibility trees.
There is at most one entry per document and visibility value.  Each entry also
captures the exact document-build side effects needed to reuse its tree without
minting authority or replenishing a SurfaceSpec budget.")

(defvar jetpacs-reader-org--retained nil
  "The currently retained Org variant host, as an internal plist.
Files owns one edit destination, so at most one document can have a live local
variant subscription.  Search, custom folding, edits, narrowing, or a profile
downgrade revoke this record before another screen is authored.")

(defvar jetpacs-reader-org--prewarm-timer nil
  "Idle timer preparing the next canonical visibility tree, or nil.")

(defconst jetpacs-reader-org--visibility-values '(overview contents all)
  "Closed Emacs-side order authored into an Org `variant_host'.")

(defun jetpacs-reader-org--surface ()
  "Return the Files app Surface ID that owns retained Org presentation."
  (concat "app:" jetpacs-files-owner))

(defun jetpacs-reader-org--variant-id-base (path)
  "Return PATH's stable base identifier for retained visibility state."
  (jetpacs-wire-id "orgvis" (or (jetpacs-reader--key path) path)))

(defun jetpacs-reader-org--hash-snapshot (table)
  "Return a deterministic, copy-safe snapshot of hash TABLE.
Nil TABLE produces nil.  The representation is deliberately plain Elisp so it
can participate in cache equality without treating a mutable hash table as an
immutable build artifact."
  (when (hash-table-p table)
    (let (entries)
      (maphash (lambda (key value)
                 (push (cons (copy-tree key) (copy-tree value)) entries))
               table)
      (sort entries
            (lambda (left right)
              (string< (prin1-to-string (car left))
                       (prin1-to-string (car right))))))))

(defun jetpacs-reader-org--restore-hash-snapshot (table snapshot)
  "Replace hash TABLE with deterministic SNAPSHOT and return TABLE."
  (clrhash table)
  (dolist (entry snapshot)
    (puthash (copy-tree (car entry)) (copy-tree (cdr entry)) table))
  table)

(defun jetpacs-reader-org--deep-copy-hash (table)
  "Return a recursive copy of hash TABLE, including nested hash values."
  (let ((copy (make-hash-table :test (hash-table-test table)
                               :size (max 1 (hash-table-size table)))))
    (maphash (lambda (key value)
               (puthash (copy-tree key)
                        (if (hash-table-p value)
                            (jetpacs-reader-org--deep-copy-hash value)
                          (copy-tree value))
                        copy))
             table)
    copy))

(defun jetpacs-reader-org--restore-deep-hash (table snapshot)
  "Replace hash TABLE from recursive hash SNAPSHOT and return TABLE."
  (clrhash table)
  (maphash (lambda (key value)
             (puthash (copy-tree key)
                      (if (hash-table-p value)
                          (jetpacs-reader-org--deep-copy-hash value)
                        (copy-tree value))
                      table))
           snapshot)
  table)

(defun jetpacs-reader-org--effect-snapshot ()
  "Snapshot mutable SurfaceSpec build state touched by an Org render."
  (list :budget-p (consp jetpacs-buffer-budget)
        :budget (and (consp jetpacs-buffer-budget)
                     (cons (car jetpacs-buffer-budget)
                           (cdr jetpacs-buffer-budget)))
        :extra-p (consp jetpacs-buffer-extra-budget)
        :extra (copy-tree jetpacs-buffer-extra-budget)
        :ids-p (hash-table-p jetpacs-node-id-claims)
        :ids (jetpacs-reader-org--hash-snapshot jetpacs-node-id-claims)
        :exposure-document-p
        (hash-table-p jetpacs-buffer--exposure-document)
        :exposure-document
        (jetpacs-reader-org--hash-snapshot
         jetpacs-buffer--exposure-document)))

(defun jetpacs-reader-org--restore-effect-snapshot (snapshot)
  "Restore SurfaceSpec build state from SNAPSHOT."
  (if (plist-get snapshot :budget-p)
      (let ((saved (plist-get snapshot :budget)))
        (if (consp jetpacs-buffer-budget)
            (setcar jetpacs-buffer-budget (car saved))
          (setq jetpacs-buffer-budget (cons (car saved) (cdr saved))))
        (setcdr jetpacs-buffer-budget (cdr saved)))
    (setq jetpacs-buffer-budget nil))
  (setq jetpacs-buffer-extra-budget
        (and (plist-get snapshot :extra-p)
             (copy-tree (plist-get snapshot :extra))))
  (if (plist-get snapshot :ids-p)
      (progn
        (unless (hash-table-p jetpacs-node-id-claims)
          (setq jetpacs-node-id-claims (make-hash-table :test #'equal)))
        (jetpacs-reader-org--restore-hash-snapshot
         jetpacs-node-id-claims (plist-get snapshot :ids)))
    (setq jetpacs-node-id-claims nil))
  (if (plist-get snapshot :exposure-document-p)
      (progn
        (unless (hash-table-p jetpacs-buffer--exposure-document)
          (setq jetpacs-buffer--exposure-document
                (make-hash-table :test #'equal)))
        (jetpacs-reader-org--restore-hash-snapshot
         jetpacs-buffer--exposure-document
         (plist-get snapshot :exposure-document)))
    (setq jetpacs-buffer--exposure-document nil))
  snapshot)

(defun jetpacs-reader-org--capture-render (function)
  "Call zero-argument FUNCTION and capture its build effects atomically.
The result is a plist containing FUNCTION's value, before/after snapshots, and
the exact exposure operations emitted by the rendered tree."
  (let ((before (jetpacs-reader-org--effect-snapshot))
        (outer-capture jetpacs-buffer--exposure-capture)
        (local-capture (list nil))
        value)
    (let ((jetpacs-buffer--exposure-capture local-capture))
      (setq value (funcall function)))
    ;; Preserve the capture convention used by jetpacs-buffer-expose: newest
    ;; operations are at the front of the one-cell list.
    (when (consp outer-capture)
      (setcar outer-capture
              (append (car local-capture) (car outer-capture))))
    (list :value value
          :before before
          :after (jetpacs-reader-org--effect-snapshot)
          :exposures (copy-tree (car local-capture)))))

(defun jetpacs-reader-org--replay-capture (capture)
  "Replay cached build CAPTURE when its starting context still matches.
Return non-nil on success.  Replaying spends the original budget and restores
the original authority operations; it never grants a fresh allowance."
  (when (equal (jetpacs-reader-org--effect-snapshot)
               (plist-get capture :before))
    (when-let* ((exposures (plist-get capture :exposures)))
      (jetpacs-buffer-restore-exposures exposures t))
    (jetpacs-reader-org--restore-effect-snapshot
     (plist-get capture :after))
    t))

(defun jetpacs-reader-org--fingerprint (path buffer mark-pos)
  "Return the presentation fingerprint for PATH in BUFFER at MARK-POS."
  (list (jetpacs-reader--key path)
        (with-current-buffer buffer
          (list (buffer-chars-modified-tick) (buffer-size)))
        jetpacs-org-render--cache-generation
        (jetpacs-reader-org--reader-mode-p path)
        mark-pos))

(defun jetpacs-reader-org--cache-entry
    (path visibility fingerprint before)
  "Find PATH VISIBILITY cache entry for FINGERPRINT and BEFORE state."
  (cl-find-if
   (lambda (entry)
     (and (equal (plist-get entry :path) (jetpacs-reader--key path))
          (eq (plist-get entry :visibility) visibility)
          (equal (plist-get entry :fingerprint) fingerprint)
          (equal (plist-get (plist-get entry :capture) :before) before)))
   jetpacs-reader-org--visibility-cache))

(defun jetpacs-reader-org--cache-store
    (path visibility fingerprint capture)
  "Store PATH VISIBILITY FINGERPRINT and render CAPTURE in the bounded cache."
  (let ((key (jetpacs-reader--key path)))
    (setq jetpacs-reader-org--visibility-cache
          (cons (list :path key :visibility visibility
                      :fingerprint fingerprint :capture capture)
                (cl-remove-if
                 (lambda (entry)
                   (and (equal (plist-get entry :path) key)
                        (eq (plist-get entry :visibility) visibility)))
                 jetpacs-reader-org--visibility-cache))))
  capture)

(defun jetpacs-reader-org--cancel-prewarm ()
  "Cancel the pending Org visibility prewarm, if any."
  (when (timerp jetpacs-reader-org--prewarm-timer)
    (cancel-timer jetpacs-reader-org--prewarm-timer))
  (setq jetpacs-reader-org--prewarm-timer nil))

(defun jetpacs-reader-org--revoke-retained (&optional path)
  "Revoke retained presentation for PATH, or the active document when nil."
  (when (and jetpacs-reader-org--retained
             (or (null path)
                 (equal (plist-get jetpacs-reader-org--retained :path)
                        (jetpacs-reader--key path))))
    (remhash (cons (plist-get jetpacs-reader-org--retained :surface)
                   (plist-get jetpacs-reader-org--retained :id))
             jetpacs--state-handlers)
    (setq jetpacs-reader-org--retained nil)))

(defun jetpacs-reader-org--invalidate-path (path)
  "Invalidate canonical caches and local selection for Org PATH."
  (let ((key (jetpacs-reader--key path)))
    (setq jetpacs-reader-org--visibility-cache
          (cl-remove-if (lambda (entry)
                          (equal (plist-get entry :path) key))
                        jetpacs-reader-org--visibility-cache))
    (when key
      (jetpacs-reader-state-set path :org-canonical nil))
    (jetpacs-reader-org--revoke-retained path)
    (jetpacs-reader-org--cancel-prewarm)))

(defun jetpacs-reader-org-reset-cache ()
  "Clear retained/canonical Org presentation state and pending idle work."
  (jetpacs-reader-org--revoke-retained)
  (jetpacs-reader-org--cancel-prewarm)
  (setq jetpacs-reader-org--visibility-cache nil)
  t)

(defun jetpacs-reader-org--after-local-fold ()
  "Revoke retained variants after a user-selected local fold in this buffer."
  (when buffer-file-name
    (jetpacs-reader-org--invalidate-path buffer-file-name)))

(defun jetpacs-reader-org--after-change (_begin _end _old-length)
  "Revoke retained variants after text changes in the current Org buffer."
  (when buffer-file-name
    (jetpacs-reader-org--invalidate-path buffer-file-name)))

(defun jetpacs-reader-org-path-p (path)
  "Whether PATH names an Org document."
  (and (stringp path)
       (let ((case-fold-search t))
         (string-match-p "\\.org\\(_archive\\)?\\'" path))))

(defun jetpacs-reader-org--buffer (path)
  "Visit PATH and return its Org buffer."
  (let ((buf (find-file-noselect path t)))
    (with-current-buffer buf
      (unless (derived-mode-p 'org-mode) (org-mode))
      (add-hook 'jetpacs-buffer-after-fold-hook
                #'jetpacs-reader-org--after-local-fold nil t)
      (add-hook 'after-change-functions
                #'jetpacs-reader-org--after-change nil t))
    buf))

(defun jetpacs-reader-org--present-followed-destination
    (source destination position surface)
  "Present Org's DESTINATION at POSITION for SOURCE on SURFACE.
Return non-nil only when SOURCE is the document currently owned by the
Files edit host.  A destination in that document retargets the current
view without changing its screen identity.  A different file goes
through `jetpacs-files-open-path', retaining Files' root policy and
ordinary `edit' chrome.  Non-file buffers return nil for the generic
navigation fallback.

Once Files owns a file-backed destination this function always returns
non-nil, including after a refusal or error.  That rule is a security
boundary: a rejected path must not fall through to an unrestricted
generic buffer drill."
  (let* ((context (jetpacs-files-current-edit-context))
         (current-path (plist-get context :path))
         (source-path (and (buffer-live-p source)
                           (buffer-local-value 'buffer-file-name source)))
         (destination-path
          (and (buffer-live-p destination)
               (buffer-local-value 'buffer-file-name destination)))
         (current-key (jetpacs-reader--key current-path)))
    (when (and current-key
               (equal current-key (jetpacs-reader--key source-path)))
      (cond
       ((and destination-path
             (equal current-key (jetpacs-reader--key destination-path)))
        ;; A synchronous state-only mutation: the current `edit' stack entry
        ;; and live SPEC 19 editor identity remain untouched.
        (or (jetpacs-files-retarget-current-edit
             destination-path position surface)
            ;; The source was Files-owned when this presentation started.
            ;; Treat an unexpected retarget race as an owned refusal rather
            ;; than escaping through the generic drill host.
            (progn
              (jetpacs-shell-notify "Document changed before link opened"
                                    surface)
              t)))
       (destination-path
        (condition-case err
            (progn
              (jetpacs-files-open-path destination-path surface position)
              t)
          (error
           (message "jetpacs-reader-org: linked file failed: %s"
                    (jetpacs-error-label err))
           (jetpacs-shell-notify
            (format "Link failed: %s" (jetpacs-error-label err)) surface)
           t)))
       (t nil)))))

(defun jetpacs-reader-org--reader-mode-p (path)
  "Whether PATH has Orgro-style reader typography enabled."
  (jetpacs-reader-state-get path :org-reader-mode t))

(defun jetpacs-reader-org--visibility (path)
  "PATH's global visibility state."
  (jetpacs-reader-state-get path :org-visibility 'overview))

(defun jetpacs-reader-org--apply-reader-mode (path buffer)
  "Apply PATH's reader typography to BUFFER through Org variables."
  (let ((enabled (jetpacs-reader-org--reader-mode-p path)))
    (with-current-buffer buffer
      (unless (and (eq org-hide-emphasis-markers enabled)
                   (eq org-pretty-entities enabled)
                   (eq org-hide-leading-stars enabled))
        (setq-local org-hide-emphasis-markers enabled)
        (setq-local org-pretty-entities enabled)
        (setq-local org-hide-leading-stars enabled)
        (font-lock-flush (point-min) (point-max)))
      (font-lock-ensure (point-min) (point-max)))))

(defun jetpacs-reader-org--apply-visibility (path buffer)
  "Apply PATH's selected global visibility state in BUFFER."
  (with-current-buffer buffer
    (pcase (jetpacs-reader-org--visibility path)
      ('overview (org-cycle-overview))
      ('contents (org-cycle-content))
      ('all (org-fold-show-all)))
    (jetpacs-reader-state-set
     path :org-canonical
     (list (jetpacs-reader-org--visibility path)
           (buffer-chars-modified-tick)))))

(defun jetpacs-reader-org--mark-canonical (path buffer)
  "Record that BUFFER exactly presents PATH's selected global visibility."
  (with-current-buffer buffer
    (jetpacs-reader-state-set
     path :org-canonical
     (list (jetpacs-reader-org--visibility path)
           (buffer-chars-modified-tick)))))

(defun jetpacs-reader-org--canonical-p (path buffer)
  "Whether BUFFER is a complete canonical global presentation of PATH."
  (and (buffer-live-p buffer)
       (not (jetpacs-reader-state-get path :org-search-open nil))
       (string-empty-p
        (or (jetpacs-reader-state-get path :org-query "") ""))
       (with-current-buffer buffer
         (and (not (buffer-narrowed-p))
              (equal (jetpacs-reader-state-get path :org-canonical nil)
                     (list (jetpacs-reader-org--visibility path)
                           (buffer-chars-modified-tick)))))))

(defun jetpacs-reader-org--ensure-initial-visibility (path buffer)
  "Give PATH Orgro's overview first paint exactly once."
  (unless (jetpacs-reader-state-get path :org-initialized nil)
    (jetpacs-reader-org--apply-visibility path buffer)
    ;; Keep the invariant visible even when a test or host substitutes the
    ;; Org operation at the function seam.
    (jetpacs-reader-org--mark-canonical path buffer)
    (jetpacs-reader-state-set path :org-initialized t)))

(defun jetpacs-reader-org--search-mode (path)
  "Return PATH's search mode: `plain', `regexp', or `sparse'."
  (jetpacs-reader-state-get path :org-search-mode 'plain))

(defun jetpacs-reader-org--clear-search-in-buffer (path buffer)
  "Remove search overlays in BUFFER and restore PATH visibility."
  (let ((active
         (or (not (string-empty-p
                   (or (jetpacs-reader-state-get path :org-query "") "")))
             (jetpacs-reader-state-get path :org-hits nil)
             (with-current-buffer buffer
               (or org-occur-highlights org-occur-parameters)))))
    (when active
      (with-current-buffer buffer
        (org-remove-occur-highlights)
        (org-fold-show-all))))
  (jetpacs-reader-org--apply-visibility path buffer)
  (jetpacs-reader-state-set path :org-query "")
  (jetpacs-reader-state-set path :org-hits nil))

(defun jetpacs-reader-org--apply-search (path query)
  "Apply PATH search QUERY and return its match count, or `sparse'.
Signals `user-error' for invalid or excessive input."
  (when (> (length query) jetpacs-reader-org-max-query-chars)
    (user-error "Query is too long"))
  (when (string-empty-p (string-trim query))
    (user-error "Query cannot be empty"))
  (jetpacs-reader-org--invalidate-path path)
  (let ((buffer (jetpacs-reader-org--buffer path)))
    (with-current-buffer buffer
      (org-remove-occur-highlights)
      (org-fold-show-all)
      (pcase (jetpacs-reader-org--search-mode path)
        ('plain (org-occur (regexp-quote query)))
        ('regexp
         (condition-case nil
             (progn (string-match-p query "") (org-occur query))
           (invalid-regexp (user-error "Invalid regular expression"))))
        ('sparse
         (condition-case nil
             (progn (org-match-sparse-tree nil query) 'sparse)
           (error (user-error "Invalid sparse-tree query"))))))))

(defun jetpacs-reader-org--search-controls (path)
  "Return the expanded search controls for PATH, or nil."
  (when (jetpacs-reader-state-get path :org-search-open nil)
    (let* ((mode (jetpacs-reader-org--search-mode path))
           (query (jetpacs-reader-state-get path :org-query ""))
           (hits (jetpacs-reader-state-get path :org-hits nil))
           (mode-label (pcase mode
                         ('plain "Plain") ('regexp "Regexp")
                         ('sparse "Sparse tree"))))
      (jetpacs-column
       (jetpacs-text-input
        (jetpacs-wire-id "org-find" path)
        :value query
        :hint (pcase mode
                ('plain "Find text")
                ('regexp "Find regular expression")
                ('sparse "Tags, properties, TODO query"))
        :single-line t :autofocus t
        :on-submit (jetpacs-action "jetpacs.reader.org.search"
                                   :args (list :path path)))
       (jetpacs-row
        (jetpacs-button
         mode-label
         (jetpacs-action "jetpacs.reader.org.search-mode"
                         :args (list :path path))
         :variant "tonal")
        (jetpacs-button
         "Clear"
         (jetpacs-action "jetpacs.reader.org.search-clear"
                         :args (list :path path))
         :variant "text")
        :spacing 8)
       (when hits
         (jetpacs-text
          (if (eq hits 'sparse)
              "Sparse-tree filter applied"
            (format "%d match%s" hits (if (= hits 1) "" "es")))
          :style "caption"))
       :spacing 8))))

(defun jetpacs-reader-org--mark-position (path)
  "Return Files' current scroll target for PATH, or nil."
  (let ((context jetpacs-files-editor-context))
    (and (equal (jetpacs-reader--key path)
                (jetpacs-reader--key (plist-get context :path)))
         (plist-get context :mark-pos))))

(defun jetpacs-reader-org--render-live-tree (path buffer mark-pos)
  "Render one live PATH tree from BUFFER with optional MARK-POS."
  (jetpacs-reader-org--apply-reader-mode path buffer)
  (let ((jetpacs-org-render-proportional-prose
         (jetpacs-reader-org--reader-mode-p path))
        (jetpacs-org-render-reader-typography t)
        (jetpacs-buffer-scroll-position mark-pos))
    (apply #'jetpacs-lazy-column
           (delq nil
                 (append
                  (list (jetpacs-reader-org--search-controls path))
                  (jetpacs-org-render buffer))))))

(defun jetpacs-reader-org--render-canonical-tree
    (path buffer mark-pos reuse)
  "Render canonical PATH in BUFFER at MARK-POS; REUSE permits cache replay."
  (let* ((visibility (jetpacs-reader-org--visibility path))
         (fingerprint
          (jetpacs-reader-org--fingerprint path buffer mark-pos))
         (before (jetpacs-reader-org--effect-snapshot))
         (entry (jetpacs-reader-org--cache-entry
                 path visibility fingerprint before)))
    (if (and reuse entry
             (jetpacs-reader-org--replay-capture
              (plist-get entry :capture)))
        (plist-get (plist-get entry :capture) :value)
      (let ((capture
             (jetpacs-reader-org--capture-render
              (lambda ()
                (jetpacs-reader-org--render-live-tree
                 path buffer mark-pos)))))
        ;; A normal refresh deliberately does not overwrite an existing
        ;; canonical snapshot: it may reflect a local fold whose hook has not
        ;; yet run.  An explicit cache request (including prewarm/variant
        ;; authoring) replaces stale input, while first paint seeds the cache.
        (when (and (jetpacs-reader-org--canonical-p path buffer)
                   (or reuse (null entry)))
          (jetpacs-reader-org--cache-store
           path visibility fingerprint capture))
        (plist-get capture :value)))))

(defun jetpacs-reader-org--next-visibility (visibility)
  "Return the authored successor of VISIBILITY, wrapping at `all'."
  (pcase visibility
    ('overview 'contents)
    ('contents 'all)
    (_ 'overview)))

(defun jetpacs-reader-org--prewarm (path)
  "Author PATH's next canonical visibility without changing live state."
  (setq jetpacs-reader-org--prewarm-timer nil)
  (when-let* ((buffer (and (jetpacs-reader-org-path-p path)
                           (jetpacs-reader-org--buffer path))))
    (when (jetpacs-reader-org--canonical-p path buffer)
      (let* ((selected (jetpacs-reader-org--visibility path))
             (next (jetpacs-reader-org--next-visibility selected))
             (mark-pos (jetpacs-reader-org--mark-position path))
             (effects (jetpacs-reader-org--effect-snapshot))
             (exposed (jetpacs-reader-org--deep-copy-hash
                       jetpacs-buffer-exposed)))
        (unwind-protect
            (progn
              (jetpacs-reader-state-set path :org-visibility next)
              (jetpacs-reader-org--apply-visibility path buffer)
              (jetpacs-reader-org--mark-canonical path buffer)
              (jetpacs-reader-org--render-canonical-tree
               path buffer mark-pos t))
          ;; Prewarming is computation, not publication.  Restore both the
          ;; selected Org state and every sender budget/authority side effect;
          ;; the cache keeps the captured operations for a later real build.
          (jetpacs-reader-state-set path :org-visibility selected)
          (jetpacs-reader-org--apply-visibility path buffer)
          (jetpacs-reader-org--mark-canonical path buffer)
          (jetpacs-reader-org--restore-effect-snapshot effects)
          (jetpacs-reader-org--restore-deep-hash
           jetpacs-buffer-exposed exposed))))))

(defun jetpacs-reader-org--schedule-prewarm (path)
  "Schedule bounded idle prewarming for PATH when configured."
  (jetpacs-reader-org--cancel-prewarm)
  (when (and (numberp jetpacs-reader-org-prewarm-idle-seconds)
             (>= jetpacs-reader-org-prewarm-idle-seconds 0))
    (setq jetpacs-reader-org--prewarm-timer
          (run-with-idle-timer
           jetpacs-reader-org-prewarm-idle-seconds nil
           #'jetpacs-reader-org--prewarm path))))

(defun jetpacs-reader-org--preview-claimed-id (base)
  "Return the ID `jetpacs-claim-node-id' would emit for BASE, without mutation."
  (if (hash-table-p jetpacs-node-id-claims)
      (let ((jetpacs-node-id-claims
             (copy-hash-table jetpacs-node-id-claims)))
        (jetpacs-claim-node-id base))
    base))

(defun jetpacs-reader-org--retained-capable-p (path buffer)
  "Whether PATH in BUFFER may use the optional retained-variant profile."
  (and (jetpacs-client)
       (jetpacs-reader-current-path-p path)
       (jetpacs-node-advertised-p "variant_host" :app)
       (jetpacs-builtin-advertised-p "variant.switch" :app)
       (jetpacs-reader-org--canonical-p path buffer)))

(defun jetpacs-reader-org--retained-select (path id value)
  "Reconcile retained host ID for PATH to authored string VALUE."
  (let ((record jetpacs-reader-org--retained)
        (symbol (and (stringp value) (intern-soft value))))
    (when (and record
               (equal (plist-get record :path) (jetpacs-reader--key path))
               (equal (plist-get record :id) id)
               (memq symbol jetpacs-reader-org--visibility-values))
      (let ((buffer (jetpacs-reader-org--buffer path)))
        ;; The callback exists only while the authored snapshot remains
        ;; current, but re-check the immutable inputs before applying Org as
        ;; defense in depth against a queued callback racing invalidation.
        (when (and (equal (plist-get record :fingerprint)
                          (jetpacs-reader-org--fingerprint
                           path buffer (jetpacs-reader-org--mark-position path)))
                   (not (buffer-local-value 'buffer-read-only buffer)))
          (jetpacs-reader-state-set path :org-visibility symbol)
          (jetpacs-reader-org--apply-visibility path buffer)
          (jetpacs-reader-org--mark-canonical path buffer)
          (setq jetpacs-reader-org--retained
                (plist-put record :value symbol)))))))

(defun jetpacs-reader-org--install-retained-handler (path id)
  "Subscribe PATH host ID to reconciled local state."
  (let ((surface (jetpacs-reader-org--surface)))
    (jetpacs-on-state-change
     id (lambda (value)
          (jetpacs-reader-org--retained-select path id value))
     surface)
    (setq jetpacs-reader-org--retained
          (plist-put jetpacs-reader-org--retained :surface surface))))

(defun jetpacs-reader-org--retained-host (path buffer mark-pos)
  "Return PATH's complete retained host from BUFFER at MARK-POS."
  (let* ((surface (jetpacs-reader-org--surface))
         (base (jetpacs-reader-org--variant-id-base path))
         (preview-id (jetpacs-reader-org--preview-claimed-id base))
         (client (jetpacs-client))
         (input (and client
                     (ebp-client-input-value client surface preview-id)))
         (input-symbol (and (stringp input) (intern-soft input)))
         (selected (if (memq input-symbol
                             jetpacs-reader-org--visibility-values)
                       input-symbol
                     (jetpacs-reader-org--visibility path))))
    (unless (memq selected jetpacs-reader-org--visibility-values)
      (setq selected 'overview))
    (unless (eq selected (jetpacs-reader-org--visibility path))
      (jetpacs-reader-state-set path :org-visibility selected)
      (jetpacs-reader-org--apply-visibility path buffer)
      (jetpacs-reader-org--mark-canonical path buffer))
    (let* ((fingerprint
            (jetpacs-reader-org--fingerprint path buffer mark-pos))
           (before (jetpacs-reader-org--effect-snapshot))
           (record jetpacs-reader-org--retained))
      (if (and record
               (equal (plist-get record :path) (jetpacs-reader--key path))
               (equal (plist-get record :id) preview-id)
               (equal (plist-get record :fingerprint) fingerprint)
               (equal (plist-get (plist-get record :capture) :before)
                      before)
               (jetpacs-reader-org--replay-capture
                (plist-get record :capture)))
          (let ((host (copy-sequence (plist-get record :host))))
            (setq jetpacs-reader-org--retained
                  (plist-put record :value selected))
            (jetpacs-reader-org--install-retained-handler path preview-id)
            (plist-put host :value (symbol-name selected)))
        (jetpacs-reader-org--revoke-retained)
        (let (id host)
          (let ((capture
                 (jetpacs-reader-org--capture-render
                  (lambda ()
                    (setq id (jetpacs-claim-node-id base))
                    (let ((original selected)
                          variants)
                      (unwind-protect
                          (dolist (visibility
                                   jetpacs-reader-org--visibility-values)
                            (jetpacs-reader-state-set
                             path :org-visibility visibility)
                            (jetpacs-reader-org--apply-visibility path buffer)
                            (jetpacs-reader-org--mark-canonical path buffer)
                            (push
                             (jetpacs-variant
                              (symbol-name visibility)
                              (jetpacs-reader-org--render-canonical-tree
                               path buffer mark-pos t))
                             variants))
                        (jetpacs-reader-state-set
                         path :org-visibility original)
                        (jetpacs-reader-org--apply-visibility path buffer)
                        (jetpacs-reader-org--mark-canonical path buffer))
                      (setq host
                            (jetpacs-variant-host
                             id (symbol-name original) (nreverse variants))))
                    host))))
            (unless (equal id preview-id)
              (error "jetpacs-reader-org: retained host identity changed during build"))
            (setq jetpacs-reader-org--retained
                  (list :path (jetpacs-reader--key path)
                        :surface surface
                        :id id
                        :value selected
                        :fingerprint fingerprint
                        :capture capture
                        :host host))
            (jetpacs-reader-org--install-retained-handler path id)
            host))))))

(defun jetpacs-reader-org--render (path)
  "Render Org PATH through the shared Org rendering engine.
When the matching Files builder supplies a `:mark-pos' in
`jetpacs-files-editor-context', bind it as the rendered scroll target.  A
capable live Files surface receives all three canonical Org presentations in
one bounded `variant_host'; every fallback remains the remote refresh path."
  (let* ((buffer (jetpacs-reader-org--buffer path))
         (mark-pos (jetpacs-reader-org--mark-position path)))
    (jetpacs-reader-org--ensure-initial-visibility path buffer)
    ;; An explicit cache request is the remote fallback's declaration that it
    ;; just selected a canonical global state.  Apply that Org operation before
    ;; looking up the prepared tree; a bare state write must never make cached
    ;; presentation authoritative by itself.
    (let ((requested
           (jetpacs-reader-state-get
            path :org-visibility-cache-request nil)))
      (when (and requested
                 (not (jetpacs-reader-state-get
                       path :org-search-open nil))
                 (with-current-buffer buffer (not (buffer-narrowed-p)))
                 (not (jetpacs-reader-org--canonical-p path buffer)))
        (jetpacs-reader-org--apply-visibility path buffer)
        (jetpacs-reader-org--mark-canonical path buffer)))
    (if (jetpacs-reader-org--retained-capable-p path buffer)
        (progn
          (jetpacs-reader-org--cancel-prewarm)
          (jetpacs-reader-org--retained-host path buffer mark-pos))
      (jetpacs-reader-org--revoke-retained path)
      (let* ((reuse
              (jetpacs-reader-state-get
               path :org-visibility-cache-request nil))
             (tree
              (if (jetpacs-reader-org--canonical-p path buffer)
                  (jetpacs-reader-org--render-canonical-tree
                   path buffer mark-pos reuse)
                (jetpacs-reader-org--render-live-tree
                 path buffer mark-pos))))
        (jetpacs-reader-state-set
         path :org-visibility-cache-request nil)
        (when (jetpacs-reader-org--canonical-p path buffer)
          (jetpacs-reader-org--schedule-prewarm path))
        tree))))

(defun jetpacs-reader-org--encrypted-p (path)
  "Whether PATH visibly contains an Org Crypt entry."
  (with-current-buffer (jetpacs-reader-org--buffer path)
    (save-excursion
      (goto-char (point-min))
      (re-search-forward "-----BEGIN PGP MESSAGE-----" nil t))))

(defun jetpacs-reader-org--actions (path)
  "Reader-only Org actions for PATH."
  (when (jetpacs-reader-active-p path)
    (let* ((record jetpacs-reader-org--retained)
           (retained-id
            (and record
                 (equal (plist-get record :path)
                        (jetpacs-reader--key path))
                 (plist-get record :id))))
      (delq
       nil
       (list
        (jetpacs-icon-button
         "chrome_reader_mode"
         (jetpacs-action "jetpacs.reader.org.reader-mode"
                         :args (list :path path))
         :content-description
         (if (jetpacs-reader-org--reader-mode-p path)
             "Show Org markup" "Enable reader mode"))
        (jetpacs-icon-button
         "unfold_more"
         (if retained-id
             (jetpacs-variant-switch retained-id)
           (jetpacs-action "jetpacs.reader.org.visibility"
                           :args (list :path path)))
         :content-description
         (format "Visibility: %s" (jetpacs-reader-org--visibility path)))
        (jetpacs-icon-button
         "search"
         (jetpacs-action "jetpacs.reader.org.search-toggle"
                         :args (list :path path))
         :content-description "Search and sparse tree")
        nil)))))

(defun jetpacs-reader-org--transition (path presentation)
  "Prepare PATH for PRESENTATION without changing Org's restriction.
A narrowed subtree cannot use SPEC 19's whole-document coordinates, so
its editor is deliberately plain and section-scoped."
  (when (eq presentation 'editor)
    (jetpacs-reader-org--invalidate-path path)
    (let ((buffer (jetpacs-reader-org--buffer path)))
      (when (with-current-buffer buffer (buffer-narrowed-p))
        (unless (jetpacs-files-downgrade-current-editor path)
          (error "The current Files edit session changed"))))))

(defun jetpacs-reader-org--action-path (args params)
  "Validated Org path from ARGS and PARAMS, or a status symbol."
  (let ((path (plist-get args :path)))
    (cond
     ((not (and (jetpacs-reader-org-path-p path)
                (jetpacs-reader-adapter-for path)))
      'rejected)
     ((jetpacs-event-stale-p params) 'stale)
     ((not (jetpacs-reader-current-path-p path)) 'stale)
     (t path))))

(defun jetpacs-reader-org--reader-mode-action (args params)
  "Toggle Orgro-style reader typography."
  (let ((path (jetpacs-reader-org--action-path args params)))
    (if (symbolp path) path
      (jetpacs-reader-org--invalidate-path path)
      (jetpacs-reader-state-set
       path :org-reader-mode
       (not (jetpacs-reader-org--reader-mode-p path)))
      (let ((buffer (jetpacs-reader-org--buffer path)))
        (jetpacs-reader-org--apply-reader-mode path buffer)
        (when (and (not (jetpacs-reader-state-get
                         path :org-search-open nil))
                   (with-current-buffer buffer (not (buffer-narrowed-p))))
          (jetpacs-reader-org--mark-canonical path buffer)))
      (jetpacs-reader-refresh params)
      'accepted)))

(defun jetpacs-reader-org--visibility-action (args params)
  "Cycle overview, contents, and show-all visibility."
  (let ((path (jetpacs-reader-org--action-path args params)))
    (if (symbolp path) path
      (jetpacs-reader-org--revoke-retained path)
      (jetpacs-reader-state-set
       path :org-visibility
       (pcase (jetpacs-reader-org--visibility path)
         ('overview 'contents) ('contents 'all) (_ 'overview)))
      (jetpacs-reader-org--clear-search-in-buffer
       path (jetpacs-reader-org--buffer path))
      (jetpacs-reader-refresh params)
      'accepted)))

(defun jetpacs-reader-org--search-toggle-action (args params)
  "Show or hide PATH's search controls."
  (let ((path (jetpacs-reader-org--action-path args params)))
    (if (symbolp path) path
      (jetpacs-reader-org--invalidate-path path)
      (jetpacs-reader-state-set
       path :org-search-open
       (not (jetpacs-reader-state-get path :org-search-open nil)))
      (jetpacs-reader-refresh params)
      'accepted)))

(defun jetpacs-reader-org--search-mode-action (args params)
  "Cycle plain, regexp, and sparse-tree search modes."
  (let ((path (jetpacs-reader-org--action-path args params)))
    (if (symbolp path) path
      (jetpacs-reader-org--invalidate-path path)
      (jetpacs-reader-state-set
       path :org-search-mode
       (pcase (jetpacs-reader-org--search-mode path)
         ('plain 'regexp) ('regexp 'sparse) (_ 'plain)))
      (jetpacs-reader-org--clear-search-in-buffer
       path (jetpacs-reader-org--buffer path))
      (jetpacs-reader-refresh params)
      'accepted)))

(defun jetpacs-reader-org--search-action (args params)
  "Apply a submitted text, regexp, or sparse-tree query."
  (let ((path (jetpacs-reader-org--action-path args params))
        (query (plist-get args :value)))
    (cond
     ((symbolp path) path)
     ((not (stringp query)) 'rejected)
     (t
      (condition-case err
          (let ((hits (jetpacs-reader-org--apply-search path query)))
            (jetpacs-reader-state-set path :org-query query)
            (jetpacs-reader-state-set path :org-hits hits)
            (jetpacs-reader-refresh params)
            'accepted)
        (user-error
         (jetpacs-shell-notify (error-message-string err)
                               (plist-get params :surface))
         'rejected))))))

(defun jetpacs-reader-org--search-clear-action (args params)
  "Clear PATH's active search/filter and highlights."
  (let ((path (jetpacs-reader-org--action-path args params)))
    (if (symbolp path) path
      (jetpacs-reader-org--invalidate-path path)
      (jetpacs-reader-org--clear-search-in-buffer
       path (jetpacs-reader-org--buffer path))
      (jetpacs-reader-refresh params)
      'accepted)))

(defun jetpacs-reader-org--decrypt-action (args params)
  "Decrypt PATH's Org Crypt entries through the prompt bridge."
  (let ((path (jetpacs-reader-org--action-path args params)))
    (if (symbolp path) path
      (jetpacs-reader-org--invalidate-path path)
      (jetpacs-flow-continue
       (lambda ()
         (condition-case err
             (with-current-buffer (jetpacs-reader-org--buffer path)
               (org-decrypt-entries)
               (ebp-org-cache-invalidate)
               (jetpacs-reader-refresh params))
           (error
            (jetpacs-shell-notify
             (format "Decrypt failed: %s" (error-message-string err))
             (plist-get params :surface))))))
      'accepted)))

(defun jetpacs-reader-org-register ()
  "Register the Org reader adapter and its actions, idempotently."
  ;; Always reassert the adapter.  Another app may deliberately replace
  ;; the stable `org' slot while it is loaded, then call this registrar
  ;; during teardown to restore the foundation implementation.  Keeping
  ;; this inside the one-time action block would make that restoration a
  ;; no-op merely because the stock actions were still registered.
  (jetpacs-reader-register
   'org :predicate #'jetpacs-reader-org-path-p
   :render #'jetpacs-reader-org--render
   :actions #'jetpacs-reader-org--actions
   :transition #'jetpacs-reader-org--transition)
  ;; The renderer remains host-neutral.  This adapter supplies Files policy
  ;; only when the link source is the document Files currently presents.
  (setq jetpacs-org-render-follow-destination-function
        #'jetpacs-reader-org--present-followed-destination)
  (unless jetpacs-reader-org--registered
    (setq jetpacs-reader-org--registered t)
    (jetpacs-defaction "jetpacs.reader.org.reader-mode"
                       #'jetpacs-reader-org--reader-mode-action)
    (jetpacs-defaction "jetpacs.reader.org.visibility"
                       #'jetpacs-reader-org--visibility-action)
    (jetpacs-defaction "jetpacs.reader.org.search-toggle"
                       #'jetpacs-reader-org--search-toggle-action)
    (jetpacs-defaction "jetpacs.reader.org.search-mode"
                       #'jetpacs-reader-org--search-mode-action)
    (jetpacs-defaction "jetpacs.reader.org.search"
                       #'jetpacs-reader-org--search-action)
    (jetpacs-defaction "jetpacs.reader.org.search-clear"
                       #'jetpacs-reader-org--search-clear-action)
    (jetpacs-defaction "jetpacs.reader.org.decrypt"
                       #'jetpacs-reader-org--decrypt-action))
  t)

(defun jetpacs-reader-org-unregister ()
  "Unregister the Org reader adapter and its actions."
  (jetpacs-reader-org-reset-cache)
  (when (eq jetpacs-org-render-follow-destination-function
            #'jetpacs-reader-org--present-followed-destination)
    (setq jetpacs-org-render-follow-destination-function nil))
  (when jetpacs-reader-org--registered
    (setq jetpacs-reader-org--registered nil)
    (jetpacs-reader-unregister 'org)
    (dolist (action '("jetpacs.reader.org.reader-mode"
                      "jetpacs.reader.org.visibility"
                      "jetpacs.reader.org.search-toggle"
                      "jetpacs.reader.org.search-mode"
                      "jetpacs.reader.org.search"
                      "jetpacs.reader.org.search-clear"
                      "jetpacs.reader.org.decrypt"))
      (jetpacs-undefaction action)))
  t)

(defun jetpacs-reader-org-unload-function ()
  "Unload hygiene for the Org reader adapter."
  (jetpacs-reader-org-unregister)
  (remove-hook 'jetpacs-reset-functions #'jetpacs-reader-org-reset-cache)
  nil)

(add-hook 'jetpacs-reset-functions #'jetpacs-reader-org-reset-cache)

(provide 'jetpacs-reader-org)
;;; jetpacs-reader-org.el ends here
