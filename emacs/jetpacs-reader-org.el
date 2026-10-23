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
;; Three exposure-gated editing sessions ride beside the reader: an
;; inline draft of a source block's code and language, an inline draft of
;; a Babel call's arguments, and a read-then-edit footnote dialog.  Org
;; owns their syntax; this adapter owns one draft at a time, the snapshot
;; freshness guards, and the durable save through the Org save policy.
;;
;; Transclusion is intentionally absent: it is not built into Org and
;; therefore does not belong in this built-in-backed adapter.

;;; Code:

(require 'cl-lib)
(require 'subr-x)
(require 'org)
(require 'org-fold)
(require 'org-crypt)
(require 'org-footnote)
(require 'ebp)
(require 'ebp-org)
(require 'jetpacs-reader)
(require 'jetpacs-org-render)
(require 'jetpacs-widgets)
(require 'jetpacs-surfaces)
(require 'jetpacs-buffer)
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

;;;; Inline Babel editors and the footnote editor (built-in Org, exposure-gated)
;;
;; Three small editing sessions over positions a render exposed (SPEC
;; 23.1): an inline draft of a source block's code and language, an
;; inline draft of a Babel call's arguments, and a read-then-edit dialog
;; over a footnote definition.  Org owns the syntax (element bounds, the
;; call's literal components, footnote lookup and the validity of a
;; definition); this module owns one draft at a time, the snapshot
;; freshness guards (source tick, disk modtime, exact original text), and
;; the durable save through the Org save policy.  A downstream reader
;; authors the surrounding presentation (cards, fold headers, prose) and
;; places the editor node this module returns; the verbs here are
;; ownerless, like every other exposure-gated Org verb.

(declare-function jetpacs-editor-org-save-policy "jetpacs-editor-org"
                  (&optional buffer))
(declare-function org-babel-lob-execute-maybe "ob-lob" ())

(defconst jetpacs-reader-org-source-edit-limit 65536
  "Maximum characters retained in the single inline code-edit session.")

(defconst jetpacs-reader-org-call-edit-limit 8192
  "Maximum characters retained in one inline Babel argument draft.")

(defconst jetpacs-reader-org-footnote-limit 16384
  "Maximum editable footnote length, bounded before building the dialog.")

(defvar jetpacs-reader-org--source-edit nil
  "One active inline Babel draft: source identity, original text and save guard.
A source draft has no `:kind'; an argument draft carries `:kind' `call'.")

(defvar jetpacs-reader-org--source-edit-sequence 0
  "Action-owned generation for fresh inline input identities after Cancel.")

(defvar jetpacs-reader-org--footnote-dialog nil
  "The outstanding footnote editor and its source snapshot, or nil.")

(defvar jetpacs-reader-org--footnote-sequence 0
  "Sequence of action-created footnote dialogs; never read by builders.")

(defvar jetpacs-reader-org-footnote-prose-function nil
  "Function rendering a footnote's TEXT as a dialog-profile node, or nil.
Nil renders plain text.  A downstream reader with richer prose (emphasis
spans, say) installs its own renderer here while registered.")

(defun jetpacs-reader-org--save-buffer (buffer)
  "Save BUFFER synchronously through the Org save policy."
  ;; Load at action time to avoid the reader/editor cycle.
  (require 'jetpacs-editor-org)
  (jetpacs-editor-org-save-policy buffer))

(defun jetpacs-reader-org-source-draft ()
  "Return the live inline Babel draft plist, or nil."
  jetpacs-reader-org--source-edit)

(defun jetpacs-reader-org-source-editing-p (name pos scope &optional kind)
  "Whether the live draft edits the block of KIND at POS in buffer NAME.
SCOPE is the explicit reader location the draft was opened from, so a
retained whole-file view and a drilled subtree never share one editor.
KIND is nil for a source block and `call' for a Babel call."
  (let ((session jetpacs-reader-org--source-edit))
    (and session
         (eq (plist-get session :kind) kind)
         (equal name (plist-get session :buffer))
         (eql pos (plist-get session :pos))
         (equal scope (plist-get session :scope)))))

(defun jetpacs-reader-org-source-bounds (element)
  "Return the exact code-body bounds of source block ELEMENT."
  (cons (save-excursion
          (goto-char (org-element-property :post-affiliated element))
          (line-beginning-position 2))
        (save-excursion
          (goto-char (org-element-property :end element))
          (skip-chars-backward " \t\n") (line-beginning-position))))

(defun jetpacs-reader-org-source-editor (session)
  "Render SESSION's inline code field and explicit Save/Cancel actions.
The returned column is the draft's whole editor; the caller places it
inside its own presentation of the block."
  (let* ((id (jetpacs-claim-node-id (plist-get session :id)))
         (language-id (jetpacs-claim-node-id (plist-get session :language-id)))
         (args (list :session (plist-get session :id) :field id
                     :language-field language-id))
         (save (jetpacs-action "jetpacs.org.source.save" :args args
                               :capture-fields (list id language-id))))
    (jetpacs-buffer-expose (plist-get session :buffer) (plist-get session :pos)
                           "jetpacs.org.source.save")
    (jetpacs-column
     (jetpacs-dropdown
      language-id
      (mapcar (lambda (language)
                (jetpacs-enum-option
                 (if (string-empty-p language) "Unspecified" language)
                 language))
              (plist-get session :languages))
      :value (plist-get session :language) :label "Language")
     (jetpacs-text-input id :value (plist-get session :text) :label "Code"
                         :monospace t :min-lines 4 :max-lines 16
                         :max-length jetpacs-reader-org-source-edit-limit)
     (jetpacs-row
      (jetpacs-button "Cancel"
                      (jetpacs-action "jetpacs.org.source.cancel" :args args)
                      :variant "text")
      (jetpacs-button "Save" save :variant "filled")
      :arrange "end" :spacing 8)
     :spacing 8)))

(defun jetpacs-reader-org--on-source-edit (args params)
  "Open an inline source draft from ARGS and event PARAMS without editing Org."
  (let* ((name (plist-get args :buffer)) (pos (plist-get args :pos))
         (tick (plist-get args :tick)) (scope (plist-get args :scope))
         (buffer (and (stringp name) (get-buffer name))))
    (cond
     ((not (and buffer (integerp pos) (integerp tick) (stringp scope)))
      'rejected)
     ((jetpacs-event-stale-p params) 'stale)
     ((not (jetpacs-buffer-exposed-p name pos "jetpacs.org.source.edit"))
      'rejected)
     (jetpacs-reader-org--source-edit
      (jetpacs-shell-notify "Save or cancel the open Babel draft first."
                            (plist-get params :surface))
      'rejected)
     (t
      (with-current-buffer buffer
        (org-with-wide-buffer
         (if (or (/= tick (buffer-chars-modified-tick))
                 (not (<= (point-min) pos (point-max))))
             'stale
           (let ((element (org-element-at-point pos)))
             (if (not (and (eq (org-element-type element) 'src-block)
                           (eql pos (org-element-property
                                     :post-affiliated element))))
                 'stale
               (let* ((bounds (jetpacs-reader-org-source-bounds element))
                      (text (buffer-substring-no-properties
                             (car bounds) (cdr bounds)))
                      (language (or (org-element-property :language element)
                                    ""))
                      (language-bounds
                       (save-excursion
                         (goto-char pos)
                         (let ((case-fold-search t))
                           (re-search-forward "^[ \t]*#\\+begin_src\\b"
                                              (line-end-position)))
                         (unless (string-empty-p language)
                           (skip-chars-forward " \t"))
                         (cons (point) (+ (point) (length language)))))
                      (languages
                       (sort (delete-dups
                              (cons language
                                    (mapcar (lambda (entry)
                                              (symbol-name (car entry)))
                                            org-babel-load-languages)))
                             #'string<)))
                 (if (> (length text) jetpacs-reader-org-source-edit-limit)
                     'rejected
                   (let ((id (jetpacs-wire-id
                              "org-code-edit"
                              (format "%s/%d/%d" name pos
                                      (cl-incf
                                       jetpacs-reader-org--source-edit-sequence)))))
                     (setq jetpacs-reader-org--source-edit
                           (list :buffer name :pos pos :tick tick :scope scope
                                 :surface (plist-get params :surface)
                                 :bounds bounds :text text
                                 :language language
                                 :language-bounds language-bounds
                                 :languages (cons language
                                                  (seq-take
                                                   (remove language languages)
                                                   127))
                                 :language-id (jetpacs-wire-id
                                               "org-code-language" id)
                                 :id id)))
                   (jetpacs-buffer-defer-refresh (plist-get params :surface))
                   'accepted)))))))))))

(defun jetpacs-reader-org--on-source-cancel (args params)
  "Discard the inline session identified by ARGS from PARAMS without writing."
  (if (not (and jetpacs-reader-org--source-edit
                (equal (plist-get args :session)
                       (plist-get jetpacs-reader-org--source-edit :id))
                (equal (plist-get params :surface)
                       (plist-get jetpacs-reader-org--source-edit :surface))))
      'stale
    (setq jetpacs-reader-org--source-edit nil)
    (jetpacs-buffer-defer-refresh (plist-get params :surface))
    'accepted))

(defun jetpacs-reader-org--source-field (fields id)
  "Return captured ID from FIELDS without interning untrusted field names."
  (when (and (stringp id) (proper-list-p fields) (zerop (% (length fields) 2)))
    (cl-loop for (key value) on fields by #'cddr
             when (and (symbolp key)
                       (equal (symbol-name key) (concat ":" id)))
             return value)))

(defun jetpacs-reader-org--source-field-p (field base)
  "Whether FIELD is BASE or its document allocator's numeric suffix."
  (and (stringp field) (stringp base)
       (string-match-p (concat "\\`" (regexp-quote base)
                               "\\(?:-[0-9]+\\)?\\'")
                       field)))

(defun jetpacs-reader-org--on-source-save (args params)
  "Save the code body and language of the inline session in ARGS from PARAMS.
Validate the original source tick, exact body and disk freshness first.
Failed writes roll back the Org buffer and keep the draft open."
  (let* ((session jetpacs-reader-org--source-edit)
         (field (plist-get args :field))
         (language-field (plist-get args :language-field))
         (value (jetpacs-reader-org--source-field (plist-get params :fields)
                                                  field))
         (language (jetpacs-reader-org--source-field (plist-get params :fields)
                                                     language-field))
         (buffer (and session (get-buffer (plist-get session :buffer)))))
    (cond
     ((not (and session buffer
                (equal (plist-get args :session) (plist-get session :id))
                (equal (plist-get params :surface)
                       (plist-get session :surface))))
      'stale)
     ((jetpacs-event-stale-p params) 'stale)
     ((not (and (stringp value)
                (<= (length value) jetpacs-reader-org-source-edit-limit)
                (jetpacs-reader-org--source-field-p field (plist-get session :id))
                (jetpacs-reader-org--source-field-p
                 language-field (plist-get session :language-id))
                (member language (plist-get session :languages))
                (not (string-match-p
                      "^[ \t]*#\\+[Ee][Nn][Dd]_[Ss][Rr][Cc][ \t]*$" value))
                (jetpacs-buffer-exposed-p (plist-get session :buffer)
                                          (plist-get session :pos)
                                          "jetpacs.org.source.save")))
      'rejected)
     (t
      (condition-case err
          (with-current-buffer buffer
            (org-with-wide-buffer
             (let ((beg (car (plist-get session :bounds)))
                   (end (cdr (plist-get session :bounds))))
               (if (or (/= (plist-get session :tick)
                           (buffer-chars-modified-tick))
                       (not (verify-visited-file-modtime buffer))
                       (not (equal (plist-get session :text)
                                   (buffer-substring-no-properties beg end))))
                   (progn
                     (jetpacs-shell-notify
                      "Source changed. Cancel and reopen the code editor."
                      (plist-get params :surface))
                     'stale)
                 (ebp-org--check-file buffer-file-name)
                 (ebp-org--with-clamped-io
                   (atomic-change-group
                     (save-excursion
                       (goto-char beg) (delete-region beg end)
                       (insert value)
                       (unless (or (string-empty-p value)
                                   (string-suffix-p "\n" value))
                         (insert "\n"))
                       (unless (equal language (plist-get session :language))
                         (let ((range (plist-get session :language-bounds)))
                           (goto-char (car range))
                           (delete-region (car range) (cdr range))
                           (when (string-empty-p (plist-get session :language))
                             (insert " "))
                           (insert language))))
                     (jetpacs-reader-org--save-buffer buffer)))
                 (setq jetpacs-reader-org--source-edit nil)
                 (jetpacs-buffer-defer-refresh (plist-get params :surface))
                 'accepted))))
        (error (jetpacs-shell-notify (jetpacs-error-label err)
                                     (plist-get params :surface))
               'rejected))))))

(defun jetpacs-reader-org-call-argument-bounds (element)
  "Return ELEMENT's argument bounds using Org's parsed literal components.
Only return a range when the original parentheses and parsed text agree."
  (save-excursion
    (goto-char (org-element-property :post-affiliated element))
    (when (search-forward ":" (line-end-position) t)
      (skip-chars-forward " \t")
      (forward-char (length (org-element-property :call element)))
      (when-let* ((inside (org-element-property :inside-header element)))
        (forward-char (+ 2 (length inside))))
      (when (eq (char-after) ?\()
        (forward-char)
        (let ((beg (point))
              (arguments (org-element-property :arguments element)))
          (if arguments (forward-char (length arguments))
            (skip-chars-forward " \t"))
          (when (eq (char-after) ?\)) (cons beg (point))))))))

(defun jetpacs-reader-org-call-editor (session)
  "Render SESSION's native argument field and Save/Cancel controls."
  (let* ((id (jetpacs-claim-node-id (plist-get session :id)))
         (args (list :session (plist-get session :id) :field id)))
    (jetpacs-column
     (jetpacs-text-input id :value (plist-get session :text) :label "Arguments"
                         :hint "n=5, other=…" :monospace t :single-line t
                         :max-length jetpacs-reader-org-call-edit-limit)
     (jetpacs-text
      "Use Org arguments, for example n=5. Save, then press Play to run."
      :style "caption" :color "outline")
     (jetpacs-row
      (jetpacs-button "Cancel"
                      (jetpacs-action "jetpacs.org.call.cancel" :args args)
                      :variant "text")
      (jetpacs-button "Save"
                      (jetpacs-action "jetpacs.org.call.save" :args args
                                      :capture-fields (list id))
                      :variant "filled")
      :spacing 8)
     :spacing 8)))

(defun jetpacs-reader-org--on-call-edit (args params)
  "Open an argument draft identified by ARGS and PARAMS without evaluating it."
  (let* ((name (plist-get args :buffer)) (pos (plist-get args :pos))
         (tick (plist-get args :tick)) (scope (plist-get args :scope))
         (buffer (and (stringp name) (get-buffer name))))
    (cond
     ((not (and buffer (integerp pos) (integerp tick) (stringp scope)))
      'rejected)
     ((jetpacs-event-stale-p params) 'stale)
     ((not (jetpacs-buffer-exposed-p name pos "jetpacs.org.call.edit"))
      'rejected)
     (jetpacs-reader-org--source-edit
      (jetpacs-shell-notify "Save or cancel the open Babel draft first."
                            (plist-get params :surface))
      'rejected)
     (t
      (condition-case err
          (with-current-buffer buffer
            (org-with-wide-buffer
             (if (or (/= tick (buffer-chars-modified-tick))
                     (not (<= (point-min) pos (point-max))))
                 'stale
               (let* ((element (org-element-at-point pos))
                      (bounds (and (eq (org-element-type element) 'babel-call)
                                   (eql pos (org-element-property
                                             :post-affiliated element))
                                   (jetpacs-reader-org-call-argument-bounds
                                    element))))
                 (if (not (and bounds
                               (<= (- (cdr bounds) (car bounds))
                                   jetpacs-reader-org-call-edit-limit)))
                     'rejected
                   (setq jetpacs-reader-org--source-edit
                         (list :kind 'call :buffer name :pos pos :tick tick
                               :scope scope
                               :surface (plist-get params :surface)
                               :bounds bounds
                               :text (buffer-substring-no-properties
                                      (car bounds) (cdr bounds))
                               :id (jetpacs-wire-id
                                    "org-call-edit"
                                    (format "%s/%d/%d" name pos
                                            (cl-incf
                                             jetpacs-reader-org--source-edit-sequence)))))
                   (jetpacs-buffer-defer-refresh (plist-get params :surface))
                   'accepted)))))
        (error (jetpacs-shell-notify (jetpacs-error-label err)
                                     (plist-get params :surface))
               'rejected))))))

(defun jetpacs-reader-org--call-valid-arguments-p (value prefix suffix)
  "Whether VALUE remains entirely within the arguments between PREFIX and SUFFIX.
Use Org's parser without resolving any Babel expressions."
  (and (stringp value) (<= (length value) jetpacs-reader-org-call-edit-limit)
       (not (string-match-p "[\n\r]" value))
       (with-temp-buffer
         (insert prefix value suffix)
         (delay-mode-hooks (org-mode))
         (let* ((element (org-element-at-point (point-min)))
                (bounds (and (eq (org-element-type element) 'babel-call)
                             (jetpacs-reader-org-call-argument-bounds element))))
           (and bounds (= (car bounds) (1+ (length prefix)))
                (= (- (cdr bounds) (car bounds)) (length value)))))))

(defun jetpacs-reader-org--on-call-save (args params)
  "Save captured argument ARGS from PARAMS without executing the Babel call.
Require the original source and disk revision; preserve everything outside
its parentheses.  Failed writes roll back the buffer and retain the draft."
  (let* ((session jetpacs-reader-org--source-edit)
         (field (plist-get args :field))
         (value (jetpacs-reader-org--source-field (plist-get params :fields)
                                                  field))
         (buffer (and session (get-buffer (plist-get session :buffer)))))
    (cond
     ((not (and buffer (eq (plist-get session :kind) 'call)
                (equal (plist-get args :session) (plist-get session :id))
                (equal (plist-get params :surface)
                       (plist-get session :surface))))
      'stale)
     ((jetpacs-event-stale-p params) 'stale)
     ((not (and (stringp value)
                (<= (length value) jetpacs-reader-org-call-edit-limit)
                (jetpacs-reader-org--source-field-p field (plist-get session :id))
                (jetpacs-buffer-exposed-p (plist-get session :buffer)
                                          (plist-get session :pos)
                                          "jetpacs.org.call.save")))
      'rejected)
     (t
      (condition-case err
          (with-current-buffer buffer
            (org-with-wide-buffer
             (let ((beg (car (plist-get session :bounds)))
                   (end (cdr (plist-get session :bounds))))
               (cond
                ((or (/= (plist-get session :tick) (buffer-chars-modified-tick))
                     (not (verify-visited-file-modtime buffer)))
                 (jetpacs-shell-notify
                  "Call changed. Cancel and reopen its arguments."
                  (plist-get params :surface))
                 'stale)
                ((not (jetpacs-reader-org--call-valid-arguments-p
                       value
                       (buffer-substring-no-properties (plist-get session :pos)
                                                       beg)
                       (save-excursion
                         (goto-char end)
                         (buffer-substring-no-properties
                          end (line-end-position)))))
                 (jetpacs-shell-notify
                  "Keep the arguments on one line with balanced parentheses."
                  (plist-get params :surface))
                 'rejected)
                (t
                 (ebp-org--check-file buffer-file-name)
                 (ebp-org--with-clamped-io
                   (atomic-change-group
                     (save-excursion
                       (goto-char beg) (delete-region beg end) (insert value))
                     (jetpacs-reader-org--save-buffer buffer)))
                 (setq jetpacs-reader-org--source-edit nil)
                 (jetpacs-buffer-defer-refresh (plist-get params :surface))
                 'accepted)))))
        (error (jetpacs-shell-notify (jetpacs-error-label err)
                                     (plist-get params :surface))
               'rejected))))))

(defun jetpacs-reader-org--on-call-execute (args params)
  "Execute the exposed Babel call in ARGS from PARAMS and save its results.
Revalidate the source tick, call identity and disk before resolving arguments.
The explicit Play gesture uses the same Babel confirmation policy as the
source-block Play control.  No evaluation happens in any builder."
  (let* ((name (plist-get args :buffer))
         (pos (plist-get args :pos))
         (tick (plist-get args :tick))
         (buffer (and (stringp name) (get-buffer name))))
    (cond
     ((not (and buffer (integerp pos) (integerp tick))) 'rejected)
     ((jetpacs-event-stale-p params) 'stale)
     ((not (jetpacs-buffer-exposed-p name pos "jetpacs.org.call.execute"))
      'rejected)
     (t
      (condition-case err
          (with-current-buffer buffer
            (org-with-wide-buffer
             (if (or (not (derived-mode-p 'org-mode))
                     (/= tick (buffer-chars-modified-tick))
                     (not (<= (point-min) pos (point-max)))
                     (not (verify-visited-file-modtime buffer)))
                 'stale
               (save-excursion
                 (goto-char pos)
                 (let ((element (org-element-at-point)))
                   (if (not (and (eq (org-element-type element) 'babel-call)
                                 (eql pos (org-element-property
                                           :post-affiliated element))))
                       'stale
                     (ebp-org--check-file buffer-file-name)
                     (require 'ob-lob)
                     (ebp-org--with-clamped-io
                       ;; Play is the explicit execution request, as for
                       ;; source blocks.
                       (let ((org-confirm-babel-evaluate nil))
                         (atomic-change-group
                           (unless (org-babel-lob-execute-maybe)
                             (user-error
                              "Babel call target could not be resolved"))
                           (jetpacs-reader-org--save-buffer buffer))))
                     (jetpacs-buffer-defer-refresh (plist-get params :surface))
                     'accepted))))))
        (error (jetpacs-shell-notify (jetpacs-error-label err)
                                     (plist-get params :surface))
               'rejected))))))

(defun jetpacs-reader-org-footnote-record (pos)
  "Resolve the reference at POS to an editable definition in the current buffer.
Org owns normal, named inline and anonymous reference syntax and lookup.
Return nil for an undefined reference.  Preserve separators outside the body."
  (save-excursion
    (goto-char pos)
    (let* ((reference (org-element-context))
           (label (org-element-property :label reference)))
      (when (and (eq (org-element-type reference) 'footnote-reference)
                 (eql pos (org-element-property :begin reference)))
        (let ((element
               (if (eq (org-element-property :type reference) 'inline)
                   reference
                 (when-let* ((definition (org-footnote-get-definition label)))
                   (goto-char (nth 1 definition))
                   (org-element-context)))))
          (when (memq (org-element-type element)
                      '(footnote-definition footnote-reference))
            (let* ((start (org-element-property :begin element))
                   (finish (min (point-max) (org-element-property :end element)))
                   (inline (eq (org-element-type element) 'footnote-reference))
                   (beg (or (org-element-property :contents-begin element)
                            (save-excursion
                              (goto-char start)
                              (search-forward "]" finish)
                              (point))))
                   (end (if inline
                            (org-element-property :contents-end element)
                          (save-excursion
                            (goto-char (or (org-element-property
                                            :contents-end element)
                                           beg))
                            (skip-chars-backward " \t\r\n" beg)
                            (point)))))
              (when (<= (- finish start) jetpacs-reader-org-footnote-limit)
                (list :buffer (buffer-name) :pos pos
                      :tick (buffer-chars-modified-tick)
                      :label label :inline inline
                      :start start :finish finish :beg beg :end end
                      :text (buffer-substring-no-properties beg end)
                      :original (buffer-substring-no-properties start finish)
                      :prefix (buffer-substring-no-properties start beg)
                      :suffix (buffer-substring-no-properties end finish))))))))))

(defun jetpacs-reader-org--footnote-close ()
  "Retire the current footnote dialog without changing its source."
  (let ((session jetpacs-reader-org--footnote-dialog))
    (setq jetpacs-reader-org--footnote-dialog nil)
    (when-let* ((client (jetpacs-client))
                (request (plist-get session :request-id)))
      (ignore-errors (ebp-client-abandon client request)))))

(defun jetpacs-reader-org--footnote-spec (session)
  "Build the reading and editing dialog from the explicit SESSION snapshot."
  (let ((id (plist-get session :id))
        (field (plist-get session :field))
        (text (plist-get session :text))
        (editing (plist-get session :editing)))
    (jetpacs-column
     (jetpacs-text (if-let* ((label (plist-get session :label)))
                       (concat "Footnote · " label)
                     "Footnote")
                   :style "title")
     (cond
      (editing
       (jetpacs-text-input field :value text :label "Footnote text"
                           :single-line nil :min-lines 3 :max-lines 4
                           :max-length jetpacs-reader-org-footnote-limit))
      ((string-empty-p text)
       (jetpacs-text "Empty footnote" :color "outline"))
      ((functionp jetpacs-reader-org-footnote-prose-function)
       (funcall jetpacs-reader-org-footnote-prose-function text))
      (t (jetpacs-text text)))
     (jetpacs-row
      (jetpacs-spacer :weight 1)
      (jetpacs-button (if editing "Cancel" "Close") (jetpacs-dialog-dismiss)
                      :variant "text")
      (if editing
          (jetpacs-button "Save"
                          (jetpacs-action "jetpacs.org.footnote.save"
                                          :args (list :session id)
                                          :capture-fields (list field)))
        (jetpacs-button "Edit"
                        (jetpacs-action "jetpacs.org.footnote.edit"
                                        :args (list :session id))))
      :spacing 8)
     :spacing 12)))

(defun jetpacs-reader-org--show-footnote (session)
  "Show SESSION outside action dispatch, keeping its origin for refresh.
The remote Save action commits synchronously; dismissal never writes."
  (jetpacs-reader-org--footnote-close)
  (when-let* ((client (jetpacs-client)))
    (setq session (plist-put session :request-id nil))
    (setq jetpacs-reader-org--footnote-dialog session)
    (condition-case err
        (let ((request
               (ebp-client-dialog-show
                client (plist-get session :id)
                (jetpacs-reader-org--footnote-spec session)
                :callback (lambda (_status _result _error)
                            (when (eq jetpacs-reader-org--footnote-dialog
                                      session)
                              (setq jetpacs-reader-org--footnote-dialog nil))))))
          (if request (setf (plist-get session :request-id) request)
            (setq jetpacs-reader-org--footnote-dialog nil)))
      (error (setq jetpacs-reader-org--footnote-dialog nil)
             (jetpacs-shell-notify (jetpacs-error-label err)
                                   (plist-get session :surface))))))

(defun jetpacs-reader-org--on-footnote-open (args params)
  "Open ARGS' exposed footnote in a dialog originating from PARAMS.
Reject arbitrary buffers/positions, and resolve definitions only in the
allowlisted source file.  Opening this transient UI has no document effect."
  (let* ((name (plist-get args :buffer))
         (pos (plist-get args :pos))
         (tick (plist-get args :tick))
         (buffer (and (stringp name) (get-buffer name))))
    (cond
     ((not (and buffer (integerp pos) (integerp tick)
                (jetpacs-buffer-exposed-p name pos "jetpacs.org.footnote.open")))
      'rejected)
     ((jetpacs-event-stale-p params) 'stale)
     ((not (and (jetpacs-client) (jetpacs-granted-p "surfaces.dialog")))
      'rejected)
     (t
      (condition-case err
          (with-current-buffer buffer
            (org-with-wide-buffer
             (ebp-org--check-file buffer-file-name)
             (cond
              ((or (/= tick (buffer-chars-modified-tick))
                   (not (<= (point-min) pos (point-max))))
               'stale)
              (t
               (let ((session (jetpacs-reader-org-footnote-record pos)))
                 (if (not session)
                     (progn
                       (jetpacs-shell-notify
                        "Footnote missing or too large to edit here."
                        (plist-get params :surface))
                       'rejected)
                   (let ((id (jetpacs-wire-id
                              "org-footnote"
                              (format "%s/%d/%d" name pos
                                      (cl-incf
                                       jetpacs-reader-org--footnote-sequence)))))
                     (setq session
                           (append session
                                   (list :id id :field "footnote-text"
                                         :surface (plist-get params :surface))))
                     (jetpacs-flow-continue
                      (lambda () (jetpacs-reader-org--show-footnote session)))
                     'accepted)))))))
        (error (jetpacs-shell-notify (jetpacs-error-label err)
                                     (plist-get params :surface))
               'rejected))))))

(defun jetpacs-reader-org--footnote-valid-p (session value)
  "Whether VALUE stays one complete Org definition inside SESSION's delimiters.
Parsing a temporary copy rejects structural escapes, extra definitions and
unbalanced inline brackets without executing content or touching the source."
  (with-temp-buffer
    (insert (plist-get session :prefix) value (plist-get session :suffix))
    (let ((org-inhibit-startup t)) (delay-mode-hooks (org-mode)))
    (goto-char (point-min))
    (let ((element (org-element-context)))
      (and (eq (org-element-type element)
               (if (plist-get session :inline)
                   'footnote-reference
                 'footnote-definition))
           (= (org-element-property :end element) (point-max))
           (or (not (plist-get session :inline))
               (= (org-element-property :contents-end element)
                  (+ 1 (length (plist-get session :prefix)) (length value))))))))

(defun jetpacs-reader-org--on-footnote-edit (args params)
  "Switch ARGS' reading dialog from PARAMS to a compact footnote editor.
Use a fresh dialog identity so the retired preview cannot save or dismiss
the new editor.  The original source snapshot continues to guard Save."
  (let ((session jetpacs-reader-org--footnote-dialog))
    (if (not (and session (plist-get session :request-id)
                  (not (plist-get session :editing))
                  (equal (plist-get args :session) (plist-get session :id))
                  (equal (plist-get params :dialog_id) (plist-get session :id))))
        'stale
      (let ((editor (copy-sequence session)))
        (setq editor (plist-put editor :editing t))
        (setf (plist-get editor :id)
              (jetpacs-wire-id "org-footnote-edit"
                               (format "%s/%d" (plist-get session :id)
                                       (cl-incf
                                        jetpacs-reader-org--footnote-sequence))))
        (jetpacs-flow-continue
         (lambda () (jetpacs-reader-org--show-footnote editor)))
        'accepted))))

(defun jetpacs-reader-org--on-footnote-save (args params)
  "Commit captured footnote text from ARGS and PARAMS before accepting Save.
Only the outstanding dialog can write.  Source/disk drift rejects stale
drafts, and failed writes roll back the buffer while leaving the dialog open."
  (let* ((session jetpacs-reader-org--footnote-dialog)
         (buffer (and session (get-buffer (plist-get session :buffer))))
         (value (jetpacs-reader-org--source-field (plist-get params :fields)
                                                  (plist-get session :field)))
         (surface (plist-get session :surface)))
    (cond
     ((not (and session buffer (plist-get session :editing)
                (plist-get session :request-id)
                (equal (plist-get args :session) (plist-get session :id))
                (equal (plist-get params :dialog_id) (plist-get session :id))))
      'stale)
     ((not (and (stringp value)
                (<= (length value) jetpacs-reader-org-footnote-limit)))
      'rejected)
     (t
      (condition-case err
          (with-current-buffer buffer
            (org-with-wide-buffer
             (cond
              ((or (/= (plist-get session :tick) (buffer-chars-modified-tick))
                   (not (verify-visited-file-modtime buffer))
                   (not (equal (plist-get session :original)
                               (buffer-substring-no-properties
                                (plist-get session :start)
                                (plist-get session :finish)))))
               (jetpacs-shell-notify
                "Footnote source changed. Cancel and reopen it." surface)
               'stale)
              (t
               ;; An originally empty definition may have no separator after ].
               (when (and (not (plist-get session :inline))
                          (string-suffix-p "]" (plist-get session :prefix))
                          (not (string-empty-p value)))
                 (setq value (concat " " value)))
               (if (not (jetpacs-reader-org--footnote-valid-p session value))
                   (progn
                     (jetpacs-shell-notify
                      "Keep the text inside one Org footnote definition."
                      surface)
                     'rejected)
                 (ebp-org--check-file buffer-file-name)
                 (ebp-org--with-clamped-io
                   (atomic-change-group
                     (save-excursion
                       (goto-char (plist-get session :beg))
                       (delete-region (plist-get session :beg)
                                      (plist-get session :end))
                       (insert value))
                     (jetpacs-reader-org--save-buffer buffer)))
                 (jetpacs-reader-org--footnote-close)
                 (jetpacs-buffer-defer-refresh surface)
                 'accepted)))))
        (error (jetpacs-shell-notify (jetpacs-error-label err) surface)
               'rejected))))))

(defconst jetpacs-reader-org--editor-verbs
  '("jetpacs.org.source.edit" "jetpacs.org.source.save"
    "jetpacs.org.source.cancel"
    "jetpacs.org.call.edit" "jetpacs.org.call.save" "jetpacs.org.call.cancel"
    "jetpacs.org.call.execute"
    "jetpacs.org.footnote.open" "jetpacs.org.footnote.edit"
    "jetpacs.org.footnote.save")
  "The inline editor and footnote verbs, for the register/unregister sweep.")

(defun jetpacs-reader-org--register-editor-verbs ()
  "Register the inline Babel editor and footnote verbs (ownerless)."
  (jetpacs-defaction "jetpacs.org.source.edit"
                     #'jetpacs-reader-org--on-source-edit
                     :doc "Open an inline draft of this source block's code and language")
  (jetpacs-defaction "jetpacs.org.source.save"
                     #'jetpacs-reader-org--on-source-save
                     :doc "Save the captured code and language after revalidating their source")
  (jetpacs-defaction "jetpacs.org.source.cancel"
                     #'jetpacs-reader-org--on-source-cancel
                     :doc "Discard the inline code draft without changing Org")
  (jetpacs-defaction "jetpacs.org.call.edit"
                     #'jetpacs-reader-org--on-call-edit
                     :doc "Open an inline argument draft for this Babel call")
  (jetpacs-defaction "jetpacs.org.call.save"
                     #'jetpacs-reader-org--on-call-save
                     :doc "Save call arguments without executing the call")
  (jetpacs-defaction "jetpacs.org.call.cancel"
                     #'jetpacs-reader-org--on-source-cancel
                     :doc "Discard the inline Babel argument draft")
  (jetpacs-defaction "jetpacs.org.call.execute"
                     #'jetpacs-reader-org--on-call-execute
                     :doc "Execute this Babel call through Org and save its results")
  (jetpacs-defaction "jetpacs.org.footnote.open"
                     #'jetpacs-reader-org--on-footnote-open
                     :doc "Read and edit the definition of this exposed Org footnote")
  (jetpacs-defaction "jetpacs.org.footnote.edit"
                     #'jetpacs-reader-org--on-footnote-edit
                     :doc "Switch this footnote dialog from reading to editing")
  (jetpacs-defaction "jetpacs.org.footnote.save"
                     #'jetpacs-reader-org--on-footnote-save
                     :doc "Save this dialog's footnote text after checking source and disk freshness"))

(defun jetpacs-reader-org-reset-editors ()
  "Drop the inline Babel draft and retire the footnote dialog."
  (setq jetpacs-reader-org--source-edit nil)
  (jetpacs-reader-org--footnote-close))

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
                       #'jetpacs-reader-org--decrypt-action)
    (jetpacs-reader-org--register-editor-verbs))
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
    (dolist (action (append '("jetpacs.reader.org.reader-mode"
                              "jetpacs.reader.org.visibility"
                              "jetpacs.reader.org.search-toggle"
                              "jetpacs.reader.org.search-mode"
                              "jetpacs.reader.org.search"
                              "jetpacs.reader.org.search-clear"
                              "jetpacs.reader.org.decrypt")
                            jetpacs-reader-org--editor-verbs))
      (jetpacs-undefaction action))
    (jetpacs-reader-org-reset-editors))
  t)

(defun jetpacs-reader-org-unload-function ()
  "Unload hygiene for the Org reader adapter."
  (jetpacs-reader-org-unregister)
  (remove-hook 'jetpacs-reset-functions #'jetpacs-reader-org-reset-cache)
  (remove-hook 'jetpacs-reset-functions #'jetpacs-reader-org-reset-editors)
  nil)

(add-hook 'jetpacs-reset-functions #'jetpacs-reader-org-reset-cache)
(add-hook 'jetpacs-reset-functions #'jetpacs-reader-org-reset-editors)

(provide 'jetpacs-reader-org)
;;; jetpacs-reader-org.el ends here
