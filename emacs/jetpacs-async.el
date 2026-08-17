;;; jetpacs-async.el --- Declarative async loading for Jetpacs views -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later
;; Package-Requires: ((emacs "30.1"))

;;; Commentary:

;; A keyed loader state machine, so a view stops hand-rolling the three
;; display states of every fetch -- kick a request off in a handler, stash
;; the result in a defvar, re-push, branch on pending/ready/error by hand.
;;
;; `jetpacs-async' is called from *inside* a view builder (like React's
;; use-async): it returns the current (STATUS . PAYLOAD) for a KEY, starting
;; the loader once on first sight and caching the result.  The builder stays
;; a pure function of the cache; the one controlled impurity is the
;; idempotent first-call start.  A loader's completion schedules a single
;; coalesced re-push, so the view re-renders and reads the ready value on
;; the next build.
;;
;; Eviction rides the push cycle.  Each build stamps every KEY it asks for
;; with its presentation target's current generation;
;; `jetpacs-shell-after-push-hook' then sweeps only stale entries on the
;; surface that just pushed (running any cancel thunk) and advances that
;; target's generation -- the precise mirror of a component unmount.  A push
;; elsewhere cannot unmount this view.  App teardown additionally drops every
;; entry scoped to that owner.
;;
;; Rung JC-0 of docs/PLAN-jetpacs-consumers.md (spec: docs/SPEC-JC-0-floor.md),
;; ported near-verbatim from poc-v1 `jetpacs-async.el'.  It adds nothing to
;; the wire vocabulary and never touches the endpoint: the only outward edge
;; is the re-push seam below.
;;
;; TARGETED PUSH (spec decision D1 plus sanctioned guest screens).  Ordinarily
;; each owner presents on `app:<owner>', so a completion re-pushes the owner
;; that asked rather than one global surface.  A sanctioned guest is the one
;; exception: its cache and teardown still belong to the guest owner, while
;; the rendered screen occupies its host's surface.  OWNER and PUSH-TARGET are
;; therefore recorded separately.  The latter defaults to OWNER, preserving
;; the ordinary path; a guest names the actual host surface explicitly.
;;
;; This file is required by `jetpacs-shell', so it must not require it back;
;; the shell registers the sweep on its own hook when it loads.

;;; Code:

(require 'cl-lib)

;; Resolved at runtime from `jetpacs-surfaces'/`jetpacs-shell'; forward-declared
;; so this file byte-compiles clean and can load before them.
(defvar jetpacs-current-owner)                 ; jetpacs-surfaces.el
(defvar jetpacs-shell-pushed-surface)           ; jetpacs-shell.el
(declare-function jetpacs-shell-push "jetpacs-shell" (&optional owner))
(declare-function jetpacs-error-label "jetpacs-surfaces" (err))

(cl-defstruct (jetpacs-async--entry (:constructor jetpacs-async--entry-make)
                                    (:copier nil))
  "One cached async load.
STATUS is `pending', `ready', or `error'; VALUE is the resolved value or
the error message string; GEN is the push-generation stamp driving the
sweep; OWNER scopes the entry to an app for teardown; PUSH-TARGET is the
owner or explicit surface its completion re-pushes; CANCEL is an optional
thunk the loader registered to abort itself (kill a process, cancel a timer)."
  status value gen owner push-target cancel)

(defvar jetpacs-async--cache (make-hash-table :test 'equal)
  "Map of KEY -> `jetpacs-async--entry'.  KEY is compared `equal'.")

(defvar jetpacs-async--generation 0
  "Total successful-push generation, retained for diagnostics and tests.
Eviction itself is per presentation target in
`jetpacs-async--target-generations': a push of an unrelated surface must not
cancel a loader whose screen remains mounted elsewhere.")

(defvar jetpacs-async--target-generations (make-hash-table :test 'equal)
  "Canonical presentation target -> successful-push generation.
An entry is stamped only from its own target's counter.  This prevents a
clipboard, agenda, or launcher push from evicting an in-flight Files scan.")

(defvar jetpacs-async--push-timer nil
  "Debounce timer coalescing the completion pushes of one tick into one.")

(defvar jetpacs-async--pending-repushes nil
  "Pending (OWNER PUSH-TARGET) records since the last flush.
The owner keeps teardown attribution while the target names the surface
that actually contains the view.  The debounced flush re-pushes each target
exactly once.  Accumulated by `jetpacs-async--settle', drained by
`jetpacs-async--flush-push'.")

;; --- Completion push (debounced, per presentation target) ------------------

(defun jetpacs-async--canonical-target (target)
  "Canonical surface identity for owner-or-surface TARGET.
Keep the module free of a shell dependency while mirroring D1's one mapping:
a bare owner presents on app:<owner>; an explicit surface is already final."
  (if (and (stringp target) (not (string-search ":" target)))
      (concat "app:" target)
    target))

(defun jetpacs-async--target-generation (target)
  "Current eviction generation for TARGET, or the ownerless generation."
  (if target
      (gethash (jetpacs-async--canonical-target target)
               jetpacs-async--target-generations 0)
    jetpacs-async--generation))

(defun jetpacs-async--schedule-push ()
  "Schedule one shell push after a completion, coalescing a burst.
Deferred through a zero-delay timer, not called inline: a loader that
resolves synchronously does so *while a build is running*, and pushing from
within a build would recurse."
  (unless (timerp jetpacs-async--push-timer)
    (setq jetpacs-async--push-timer
          (run-at-time 0 nil #'jetpacs-async--flush-push))))

(defun jetpacs-async--flush-push ()
  "Run the pending coalesced pushes now (the debounce timer's target).
Re-pushes each distinct target recorded in `jetpacs-async--pending-repushes'.
An entry created outside any `with-jetpacs-owner' has no teardown owner,
which is a programming error in the caller even if it named a target, so it
is reported rather than silently becoming an immortal cache entry."
  (when (timerp jetpacs-async--push-timer)
    (cancel-timer jetpacs-async--push-timer))
  (setq jetpacs-async--push-timer nil)
  (let ((repushes (nreverse jetpacs-async--pending-repushes))
        (pushed nil))
    (setq jetpacs-async--pending-repushes nil)
    (when (fboundp 'jetpacs-shell-push)
      (pcase-dolist (`(,owner ,target) repushes)
        (cond
         ((null owner)
          (display-warning
           'jetpacs-async
           "completion outside `with-jetpacs-owner': no owned cache lifecycle"
           :warning))
         ((null target)
          (display-warning
           'jetpacs-async "completion has no surface to re-render" :warning))
         ((member target pushed) nil)
         (t
          (push target pushed)
          ;; The shell no-ops for a target with no live root.  Isolated per
          ;; target (the drain-discipline of `jetpacs-shell--on-ready'): one
          ;; target's gate failure must not starve the rest.
          (condition-case err
              (jetpacs-shell-push target)
            (error (message "jetpacs-async: repush of %s failed: %s"
                            target (jetpacs-error-label err))))))))))

;; --- The loader ------------------------------------------------------------

(defun jetpacs-async--message (err)
  "Normalize ERR (a message string or an error object) to a message string."
  (cond ((stringp err) err)
        ((and (consp err) (symbolp (car err))) (error-message-string err))
        (t (format "%s" err))))

(defun jetpacs-async--settle (key entry status value)
  "Set ENTRY to STATUS with VALUE and schedule the coalesced re-render.
No-op unless ENTRY is still the live cache entry for KEY *and* still
pending: a completion for a swept entry must not push -- on a
full-tree-resend wire each ghost push costs a complete rebuild,
reserialize, and radio wake -- and only the first of competing
resolve/reject calls wins."
  (when (and (eq entry (gethash key jetpacs-async--cache))
             (eq (jetpacs-async--entry-status entry) 'pending))
    (setf (jetpacs-async--entry-status entry) status
          (jetpacs-async--entry-value entry) value)
    (cl-pushnew (list (jetpacs-async--entry-owner entry)
                      (jetpacs-async--entry-push-target entry))
                jetpacs-async--pending-repushes :test #'equal)
    (jetpacs-async--schedule-push)))

(defun jetpacs-async--start (key entry loader)
  "Start LOADER for KEY's ENTRY, catching a synchronous throw.
LOADER is (lambda (resolve reject) ...): it calls RESOLVE with the value or
REJECT with an error string, and may return a cleanup thunk stored as the
entry's cancel."
  (let ((resolve (lambda (value) (jetpacs-async--settle key entry 'ready value)))
        (reject  (lambda (err)
                   (jetpacs-async--settle key entry 'error
                                          (jetpacs-async--message err)))))
    (condition-case err
        (let ((cleanup (funcall loader resolve reject)))
          (when (functionp cleanup)
            (setf (jetpacs-async--entry-cancel entry) cleanup)))
      (error (jetpacs-async--settle key entry 'error
                                    (error-message-string err))))))

(defun jetpacs-async--read (entry)
  "The (STATUS . PAYLOAD) pair a caller reads from ENTRY."
  (pcase (jetpacs-async--entry-status entry)
    ('ready (cons 'ready (jetpacs-async--entry-value entry)))
    ('error (cons 'error (jetpacs-async--entry-value entry)))
    (_      '(pending))))

(cl-defun jetpacs-async (key loader &key owner push-target)
  "Return the async state for KEY as (STATUS . PAYLOAD).
STATUS is `pending', `ready', or `error'.

Call this from inside a view builder.  On the first call for a fresh KEY
\(compared `equal') start LOADER once and return `(pending)'.  LOADER is a
function (lambda (RESOLVE REJECT) ...): call RESOLVE with the value or
REJECT with an error string; either stores the result and schedules a
single coalesced re-push of the owning surface, so the view re-renders and
a later call returns `(ready . VALUE)' / `(error . MESSAGE)' from cache.  A
LOADER that throws synchronously is caught and becomes `(error . MESSAGE)'
-- it never takes down the push.  LOADER may return a cleanup thunk (a
function), run when the entry is swept, to abort itself (kill a process,
cancel a timer).

The first call always reports `(pending)', even for a loader that resolves
synchronously: the value it produced surfaces on the next build (via the
push its completion scheduled), keeping one code path for sync and async
sources alike.

Eviction: a KEY not asked for in a given push is swept after that push, so
a view that stops asking for data stops paying for it.  A completion that
arrives after its entry was swept is a no-op -- no cache write, no push --
as is a second resolve/reject after the first.

OWNER scopes the entry to an app for teardown, defaulting to the current
`with-jetpacs-owner'.  PUSH-TARGET names the owner or explicit surface to
re-render and defaults to OWNER.  Keep the defaults for an owner's native
surface; a sanctioned guest screen supplies its host surface while retaining
its own OWNER.  Both values are captured once, at first sight of KEY, and
never revised: a KEY first requested under app A and later shared by app B
stays owned by A and keeps A's original target.  Calling this outside any
owner leaves the entry unowned -- it still caches, but its completion is
reported instead of scheduling an immortal re-render.

Usage:

  (pcase (jetpacs-async (list \\='stock product-id)
                        (lambda (resolve reject)
                          (grocy--fetch-stock product-id resolve reject)))
    (`(pending . ,_) (jetpacs-progress))
    (`(error   . ,e) (jetpacs-empty-state :title \"Couldn't load\" :caption e))
    (`(ready   . ,d) (stock-card d)))"
  (let ((entry (gethash key jetpacs-async--cache)))
    (if entry
      ;; Seen before: mark it live for this generation, read the cache.
      (progn
          (setf (jetpacs-async--entry-gen entry)
                (jetpacs-async--target-generation
                 (jetpacs-async--entry-push-target entry)))
          (jetpacs-async--read entry))
      ;; Fresh: register a pending entry, start the loader once, report pending.
      (let ((captured-owner
             (or owner (bound-and-true-p jetpacs-current-owner))))
        (setq entry (jetpacs-async--entry-make
                     :status 'pending
                     :gen (jetpacs-async--target-generation
                           (or push-target captured-owner))
                     :owner captured-owner
                     :push-target (or push-target captured-owner))))
      (puthash key entry jetpacs-async--cache)
      (jetpacs-async--start key entry loader)
      '(pending))))

;; --- Eviction --------------------------------------------------------------

(defun jetpacs-async--run-cancel (entry)
  "Run ENTRY's registered cancel thunk once, swallowing its errors."
  (let ((cancel (jetpacs-async--entry-cancel entry)))
    (when cancel
      (setf (jetpacs-async--entry-cancel entry) nil)
      (condition-case err
          (funcall cancel)
        (error (message "jetpacs-async: cancel failed: %s"
                        (error-message-string err)))))))

(defun jetpacs-async--after-push (&optional target)
  "Sweep stale entries for the surface just pushed, then advance its clock.
TARGET is a test seam; production reads the dynamically bound
`jetpacs-shell-pushed-surface'.  An entry stamped with this target's current
generation was read by the build that just pushed and survives; an older one
belongs to a view on THIS target that stopped asking, so its cancel runs and
the entry is dropped.  Entries on every other target are untouched.

Ownerless entries retain the legacy global sweep so a malformed caller does
not leak forever.  Registered on `jetpacs-shell-after-push-hook' by
`jetpacs-shell'."
  (let* ((target (or target
                     (bound-and-true-p jetpacs-shell-pushed-surface)))
         (canonical (jetpacs-async--canonical-target target))
         (gen (and canonical
                   (gethash canonical jetpacs-async--target-generations 0)))
         (ownerless-gen jetpacs-async--generation))
    (maphash (lambda (key entry)
               (let ((entry-target
                      (jetpacs-async--canonical-target
                       (jetpacs-async--entry-push-target entry))))
                 (when (or (and (null entry-target)
                                (< (jetpacs-async--entry-gen entry)
                                   ownerless-gen))
                           (and canonical
                                (equal entry-target canonical)
                                (< (jetpacs-async--entry-gen entry) gen)))
                   (jetpacs-async--run-cancel entry)
                   (remhash key jetpacs-async--cache))))
             jetpacs-async--cache)
    (when canonical
      (puthash canonical (1+ gen) jetpacs-async--target-generations))
    (cl-incf jetpacs-async--generation)))

(defun jetpacs-async-clear-owner (owner)
  "Drop every async entry scoped to OWNER (an app id), running its cancels.
Called on app teardown, so a torn-down app leaks no loads."
  (maphash (lambda (key entry)
             (when (equal (jetpacs-async--entry-owner entry) owner)
               (jetpacs-async--run-cancel entry)
               (remhash key jetpacs-async--cache)))
           jetpacs-async--cache)
  (setq jetpacs-async--pending-repushes
        (cl-delete owner jetpacs-async--pending-repushes
                   :key #'car :test #'equal)))

(defun jetpacs-async-reset ()
  "Drop all async state, running every cancel thunk.  For teardown and tests."
  (maphash (lambda (_key entry) (jetpacs-async--run-cancel entry))
           jetpacs-async--cache)
  (clrhash jetpacs-async--cache)
  (setq jetpacs-async--generation 0)
  (clrhash jetpacs-async--target-generations)
  (setq jetpacs-async--pending-repushes nil)
  (when (timerp jetpacs-async--push-timer)
    (cancel-timer jetpacs-async--push-timer))
  (setq jetpacs-async--push-timer nil))

;; The floor's reset seam.  Registered here rather than named by
;; `jetpacs-test-reset-state', so this module's reset survives a rename;
;; `add-hook' on a not-yet-defined hook is deliberate — this file loads
;; BEFORE `jetpacs-surfaces' (see the forward declarations above) and the
;; `defvar' there leaves an already-populated value alone.
(add-hook 'jetpacs-reset-functions #'jetpacs-async-reset)

(provide 'jetpacs-async)
;;; jetpacs-async.el ends here
