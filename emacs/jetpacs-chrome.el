;;; jetpacs-chrome.el --- Chrome kit + per-surface screen stack -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later
;; Package-Requires: ((emacs "30.1"))

;;; Commentary:

;; JA-2 of docs/PLAN-jetpacs-apps.md (B6, reduced): composition over the
;; smoke-verified scaffold/multi_view/view.switched machinery — not a
;; framework.  `jetpacs-chrome-screen' is a titled scaffold with an
;; optional back arrow; `jetpacs-chrome-row' is the hub list-row the poc
;; screens all shared; the per-surface SCREEN STACK renders as ONE
;; multi_view surface whose views are the stacked screens (decision:
;; representation A — back is then the `view.switch' BUILTIN,
;; companion-local, zero-latency, works offline, REQUIRED in every app
;; profile; and SPEC 13.4's preserve-current-view rule applies for
;; free: background refreshes omit `current_view' and never yank the
;; user; only push/pop/reset name one).
;;
;; Stack↔device sync rides `jetpacs-shell-view-change-functions' — the
;; shell's own view.switched registration; the kit must NEVER
;; `jetpacs-defaction' that name (it would REPLACE the shell's global
;; handler).  A back-arrow tap truncates the Emacs stack silently: the
;; device already shows the right screen, dead upper views leave the
;; snapshot at the next natural push.  Offline back drift is BENIGN by
;; design — do not "fix" it by forcing current_view on reconnect.
;;
;; Snackbar note (H10, RESOLVED 2026-08-02 by 1ad6bde and stale here
;; until the S6 sweep): `jetpacs-shell--inject-snackbar' reaches one
;; level down into the CURRENT VIEW's scaffold, so `jetpacs-shell-notify'
;; injects on multi-view chrome surfaces too — the toast degrade is
;; only for surfaces with no scaffold anywhere.

;;; Code:

(require 'cl-lib)
(require 'jetpacs-widgets)
(require 'jetpacs-surfaces)
(require 'jetpacs-buffer)
(require 'jetpacs-shell)

(defcustom jetpacs-chrome-max-screens 3
  "Screens one surface's stack renders (SPEC 22.3 bounds module work).
Every push re-renders EVERY screen — font-lock and a full render per
rendered-buffer screen — so an unbounded stack makes each refresh
O(depth) heavy work before one frame leaves.  The ROOT is pinned (it is
the registered fallback); eviction removes the screen ABOVE it, and the
lowest surviving screen back-targets the root.

Default 3, NOT the audit's 8, for a measured reason: `--build' renders
bottom-first so the ROOT spends the shared SPEC 4.5 budget first, and
the TOP screen — the one the user is looking at — gets the leftovers.
A two-pass top-first render would fix that but inverts the claim order
`jetpacs-claim-node-id' depends on (the first claimant keeps the stable
id, and today that is the root's literal, which later mints route
around).  Until that lands, a small bound is what keeps the starvation
bounded too."
  :type 'natnum :group 'jetpacs)

(defvar jetpacs-chrome--stacks (make-hash-table :test #'equal)
  "SURFACE id -> screen stack, a list of (ID . BUILDER), TOP FIRST.
BUILDER takes one argument BACK — a `view.switch' descriptor, or nil at
the stack bottom — and returns a root Node.")

(defvar jetpacs-chrome--view-cache (make-hash-table :test #'equal)
  "SURFACE id -> last successfully composed stack views and budget states.
The cache is consulted only under an explicit current-view refresh.  Every
ordinary push still rebuilds the whole stack, so unrelated model changes
cannot leave a hidden screen stale.")

(defvar jetpacs-chrome--target-refresh-view nil
  "Dynamically bound view id whose builder alone needs to run this push.")

(defvar jetpacs-chrome-dock-function nil
  "Function (SURFACE) -> Node or nil: a DOCKED bottom bar for SURFACE.
The docs/CHROME-VOCABULARY.md view switcher is chrome that persists —
authored on one root screen it vanishes on every drill and every other
surface.  When this is non-nil, `jetpacs-chrome--build' calls it once
per build and injects the returned node as the `bottom_bar' of every
stacked scaffold screen that does not author its own (a screen's own
bar always wins).  Returning nil docks nothing for that surface; a
signal or a non-node degrades the same way and never fails the build.
Every descriptor the dock ships must be a GLOBAL VERB or scoped to the
surfaces it appears on — it renders on every chrome surface.")

(defvar jetpacs-chrome-dock-items-function nil
  "Function (SURFACE) -> the dock's destinations as DATA, or nil.
The window-class-adaptive alternative to `jetpacs-chrome-dock-function\':
each item is a plist (:label STR :icon STR :on-tap DESCRIPTOR
\[:selected BOOL] [:badge STR-OR-NUM]) — the badge rides the icon in
the bar form and the rail item in the rail form (gap #5's one-key
thread-through; an empty string is the bare attention dot) — and
chrome wears the SAME destinations as a real
M3 navigation bar on a compact window (either axis) and as a
`jetpacs-navigation-rail\' in the scaffold\'s start-edge rail slot on
medium and expanded ones (SPEC 20.1.1) — the
NavigationSuiteScaffold swap, driven by data instead of two authorings.
`jetpacs-chrome-dock-function\' (a finished node, always the bottom
bar) WINS when both are set — it is the raw-node override.  Degrades
like the node dock: a signal or malformed items cost the dock, never
the surface.")

(defvar jetpacs-chrome-drawer-function nil
  "Function (SURFACE) -> Node or nil: SURFACE's navigation drawer.
The S8 seam, the dock's missing sibling: the drawer was the one
chrome element with no persistence seam — authored by hand on a root
it existed nowhere else, and only two roots in the tree ever authored
one.  When this is non-nil, `jetpacs-chrome--build' calls it once per
build and injects the returned node as the `drawer' of the
stack-BOTTOM scaffold ONLY: the root wears the hamburger and a
drilled screen wears the back arrow (the M3 top-level-destination
rule); a GUEST screen — never the bottom — can never wear the host's
drawer; and the drawer's literal row ids stay in ONE view of the
document (SPEC 16.1 scopes id uniqueness to the whole update — the
constraint that forced every hand author root-only).  Single-slot
authored-wins: a screen's own `:drawer' is never clobbered.
Returning nil hangs no drawer on that surface; a signal or a
non-node degrades the same way and never fails the build.  Every
descriptor the drawer ships must be a GLOBAL VERB or scoped to the
surfaces it appears on — it renders on every chrome surface's root.")

(defvar jetpacs-chrome-present-function nil
  "Function that presents a bare scaffold screen, or nil.

Called with the scaffold a screen builder returned, before chrome composes
into it; the value is what the shell pushes.  A downstream design runtime
installs its active-profile wrapper here, so every `jetpacs-chrome-screen'
wears the platform's profile without the foundation naming that runtime.
It is only ever offered a bare scaffold: a screen an app already presents
inside its own wrapper keeps that presentation.  A signal or a non-node
result costs the presentation only, never the screen.")

(defvar jetpacs-chrome-app-fab-function nil
  "Function (SCREEN-OWNER SURFACE) -> app-default FAB node, or nil.
The GR-7b app seam.  Chrome resolves SCREEN-OWNER per stacked screen:
a sanctioned S4 guest carries its recorded foreign owner, while a
native screen carries the surface owner.  This distinction prevents a
host app's creation action from leaking onto a guest settings screen.

The result joins every scaffold that does not author `:fab' itself,
before shell globals are placed, so an app's primary creation action
outranks a global requesting the same slot.  A signal, malformed node,
or live-profile refusal costs the default only, never the screen.")

;;;; Composition

(cl-defun jetpacs-chrome-screen (title body &key back actions fab drawer
                                       bottom-bar on-refresh floating-toolbar
                                       scaffold)
  "A titled scaffold screen.  BACK, when given, is the tap descriptor
of a leading arrow_back button (canonically `jetpacs-view-switch' of
the screen below).  The weight-1 title is what keeps trailing ACTIONS
at intrinsic width — the poc flex-trap lesson.  Validation rides the
builders: bad TITLE signals in `jetpacs-text', bad slots in
`jetpacs-scaffold', a bad BACK in `jetpacs-icon-button'.

SCAFFOLD is a plist appended verbatim to the `jetpacs-scaffold' call, for
the §17.6 members this function does not name individually — top-bar
styling, floating-toolbar styling, and whatever §17.6 grows next.
`jetpacs-scaffold' validates it, so an unknown member is an error there
rather than a silently dropped keyword here.

The optional slots follow docs/CHROME-VOCABULARY.md: DRAWER holds
app-level destinations (the Companion adds the opening hamburger on
the left by itself); BOTTOM-BAR is canonically a view switcher —
three to five sibling places, never document actions."
  (jetpacs-with-semantics
   (apply
    #'jetpacs-scaffold
    :top-bar (apply #'jetpacs-row
                    (append
                     (when back
                       (list (jetpacs-icon-button "arrow_back" back
                                                  :content-description "Back")))
                     (list (jetpacs-with-attrs
                            (jetpacs-text title :style "title")
                            :weight 1))
                     actions
                     (list :align "center" :spacing 4)))
    :body body :fab fab :drawer drawer :bottom-bar bottom-bar
    :on-refresh on-refresh :floating-toolbar floating-toolbar
    ;; The M3-catalog sprint proved the styled bars end to end, so chrome
    ;; wears the REAL M3 small top bar by default now — proper insets,
    ;; the drawer hamburger as its navigationIcon, and a scroll behavior
    ;; one :scaffold keyword away.  cl-defun keeps the FIRST duplicate
    ;; keyword, so an explicit :top-bar-style in SCAFFOLD still wins.
    (append scaffold (list :top-bar-style "small")))
   :pane-title title))

(declare-function jetpacs-components-list-item "jetpacs-components")

(cl-defun jetpacs-chrome-row (title &key subtitle icon leading trailing
                                    on-tap on-long-tap key)
  "The hub list-row: the best list item the live profile can draw.
TITLE/SUBTITLE are strings; ICON is a convenience when LEADING is nil;
TRAILING is one node or a list.  KEY (a SPEC 4.4 identifier) rides
`jetpacs-with-attrs' — like :weight, it is a universal attr the
container builders silently DROP as a trailing option, the exact poc
bug this port fixes.

With `jetpacs-components' loaded this is `jetpacs-components-list-item':
the flat `jetpacs.list_item' node when the receiver advertises it, styled
by the active design profile's `list-item.*' slots, and the canonical
card composition otherwise.  Without it, the canonical `jetpacs-list-item'
card.  Either way the row announces once, as its title."
  (let ((lead (or leading (and icon (jetpacs-icon icon)))))
    (if (fboundp 'jetpacs-components-list-item)
        (jetpacs-components-list-item
         title :subtitle subtitle :leading lead :trailing trailing
         :on-tap on-tap :on-long-tap on-long-tap :key key)
      (jetpacs-list-item :leading lead :title title :subtitle subtitle
                         :trailing trailing :on-tap on-tap
                         :on-long-tap on-long-tap :key key))))

;;;; The per-surface screen stack (representation A: one multi_view)

(defun jetpacs-chrome--dock (surface)
  "SURFACE's dock node from `jetpacs-chrome-dock-function', or nil.
A signal or a non-node return degrades to nil — a broken dock builder
must cost the dock, never every chrome surface in the process."
  (when jetpacs-chrome-dock-function
    (condition-case err
        (let ((n (funcall jetpacs-chrome-dock-function surface)))
          (and (jetpacs-root-node-p n) n))
      (error (message "jetpacs-chrome: dock builder failed: %s"
                      (jetpacs-error-label err))
             nil))))

(defun jetpacs-chrome--namespace-ids (node view-id)
  "Return NODE with every `:id' below it prefixed for VIEW-ID.
Node ids are document-unique (SPEC 16.1) and one multi_view document
carries every cached view, so a drawer riding more than one view must
not repeat its collapsibles' literal ids.  The root keeps the originals;
each other view wears its own.  Copy-on-write: NODE is not mutated."
  (cl-labels ((walk (value)
                (cond
                 ((vectorp value) (vconcat (mapcar #'walk (append value nil))))
                 ((and (listp value) (keywordp (car value)))
                  (let ((out nil))
                    (cl-loop for (key child) on value by #'cddr
                             do (push key out)
                                (push (if (and (eq key :id) (stringp child)
                                               (stringp (plist-get value :t)))
                                          (jetpacs-wire-id view-id child)
                                        (walk child))
                                      out))
                    (nreverse out)))
                 ((listp value) (mapcar #'walk value))
                 (t value))))
    (walk node)))

(defun jetpacs-chrome--scaffold-arrow-p (scaffold)
  "Non-nil when SCAFFOLD's top bar starts with the chrome's back arrow.
The arrow is the leading `icon_button' named `arrow_back' that
`jetpacs-chrome-screen' authors from BACK — the same spine the
Companion reads for the system gesture."
  (let* ((bar (plist-get scaffold :top_bar))
         (first (car (append (plist-get bar :children) nil))))
    (and (jetpacs-node-p first)
         (equal (plist-get first :t) "icon_button")
         (equal (plist-get first :icon) "arrow_back"))))

(defun jetpacs-chrome--shows-back-p (node)
  "Non-nil when the scaffold reachable through NODE draws a back arrow."
  (let (found)
    (jetpacs-chrome--scaffold-apply
     node
     (lambda (s) (setq found (jetpacs-chrome--scaffold-arrow-p s)) s))
    found))

(defun jetpacs-chrome--drawer (surface)
  "SURFACE's drawer node from `jetpacs-chrome-drawer-function', or nil.
A signal or a non-node return degrades to nil — a broken drawer
builder must cost the drawer, never every chrome surface in the
process."
  (when jetpacs-chrome-drawer-function
    (condition-case err
        (let ((n (funcall jetpacs-chrome-drawer-function surface)))
          (and (jetpacs-root-node-p n) n))
      (error (message "jetpacs-chrome: drawer builder failed: %s"
                      (jetpacs-error-label err))
             nil))))

(defun jetpacs-chrome--dock-tab (item)
  "One bottom-bar destination from a dock ITEM plist.
The REAL M3 NavigationBarItem, in the composition the catalog proved
on device (jetpacs-m3-navigation-bar): the icon above the label, the
64x32 active indicator behind a selected icon.  The wire uses neutral
`secondary', `on_secondary', and `on_surface' roles; the Material renderer
owns any more specific tonal derivation.  Equal weights are the bars\'
EqualWeight default."
  (let ((selected (plist-get item :selected)))
    (jetpacs-with-attrs
     (jetpacs-box
      (jetpacs-column
       (jetpacs-with-attrs
        (jetpacs-box (jetpacs-icon (plist-get item :icon)
                                   :color (if selected
                                              "on_secondary"
                                            "on_surface")
                                   :badge (plist-get item :badge)
                                   :content-description
                                   (plist-get item :label))
                     :alignment "center")
        :width 64 :height 32 :corner 16
        :bg (and selected "secondary"))
       ;; M3 mutes the unselected label with on_surface_variant, a role
       ;; the wire does not carry; both labels wear on_surface.
       (jetpacs-text (plist-get item :label) :style "label"
                     :color "on_surface")
       :spacing 4 :align "center")
      :alignment "center"
      :on-tap (plist-get item :on-tap))
     :weight 1)))

(defvar jetpacs-chrome-global-actions-function nil
  "Function (SURFACE) -> shell-global top-bar action nodes, or nil.
The S3 seam, symmetric to the dock: what it returns is appended to
EVERY stacked scaffold screen's top bar on that surface — the shell
globals (M-x) persisting into apps, CHROME-VOCABULARY v3's
build-within promise.  De-dup by action name: a screen that already
authors a button dispatching the same action keeps its own, so the
three existing M-x authors are not doubled.  The standalone pole opts
out in the FUNCTION (jetpacs-apps wraps the host seed with the
`:chrome' check), keeping this module app-agnostic.  Degrades like the
dock: a signal or non-list costs the globals, never the surface.

Since S10 this is the TOP-BAR-ONLY arm: finished nodes cannot be
re-authored into another slot, so `jetpacs-chrome-global-actions-placement'
does not reach them, and `jetpacs-chrome-global-items-function' — the
data form, which it does reach — supersedes this seam whenever it
yields items.")

(defun jetpacs-chrome--global-actions (surface)
  "SURFACE's shell-global action nodes, isolated; nil without the seam."
  (when jetpacs-chrome-global-actions-function
    (condition-case err
        (let ((nodes (funcall jetpacs-chrome-global-actions-function
                              surface)))
          (and (listp nodes) (cl-every #'jetpacs-node-p nodes) nodes))
      (error (message "jetpacs-chrome: global actions failed: %s"
                      (jetpacs-error-label err))
             nil))))

(defconst jetpacs-chrome-presentation-depth
  jetpacs-shell-presentation-depth
  "How many presentation wrappers chrome descends to reach a scaffold.")

(defalias 'jetpacs-chrome--scaffold-apply #'jetpacs-shell-scaffold-apply
  "Return NODE with FN applied to the scaffold it presents.
The rule lives in `jetpacs-shell-scaffold-apply', which the shell's own
snackbar injection shares; chrome keeps this name for its callers.")

(defun jetpacs-chrome--present (node)
  "Offer bare scaffold NODE to `jetpacs-chrome-present-function'.
Anything else -- an already presented screen, a non-scaffold root -- is
returned as is, and a signal or non-node result keeps NODE."
  (if (and jetpacs-chrome-present-function
           (jetpacs-root-node-p node)
           (equal (plist-get node :t) "scaffold"))
      (condition-case nil
          (let ((presented (funcall jetpacs-chrome-present-function node)))
            (if (jetpacs-root-node-p presented) presented node))
        (error node))
    node))

(defun jetpacs-chrome--join-global-actions (n globals)
  "Append GLOBALS to scaffold N's top-bar row, de-duped by action name.
Nodes whose `:on_tap' action already appears anywhere in the authored
top bar are skipped — the author's own copy wins.  Bar-less scaffolds and
screens presenting no scaffold pass through untouched; `append'/`vconcat'
copy, so the builder's node is never mutated."
  (if (null globals)
      n
    (jetpacs-chrome--scaffold-apply
     n
     (lambda (s)
       (if-let* ((bar (plist-get s :top_bar))
                 (kids (plist-get bar :children)))
           (let* ((authored (format "%S" bar))
                  (missing (cl-remove-if
                            (lambda (g)
                              (when-let* ((action (plist-get
                                                   (plist-get g :on_tap)
                                                   :action)))
                                ;; Printed WITH its quotes so the token is
                                ;; delimited: an authored ...mxyz must not
                                ;; swallow the ...mx global.
                                (string-search (format "%S" action) authored)))
                            globals)))
             (if (null missing)
                 s
               (let ((bar* (plist-put (copy-sequence bar)
                                      :children
                                      (vconcat kids missing))))
                 (plist-put (copy-sequence s) :top_bar bar*))))
         s)))))

(defcustom jetpacs-chrome-global-actions-placement 'top-bar
  "Where the shell globals ride on every chrome screen (S10).
`top-bar' (the default) appends them to every stacked scaffold's top
bar — docs/CHROME-VOCABULARY.md's placement for M-x, and what every
screen has rendered since the S3 seam landed.  `fab' hands them the
`fab' slot instead: ONE global IS the button, several unfold as a
`fab_menu', because the slot holds exactly one node.  `fab-menu' is
the menu unconditionally, a single global included.

Only the DATA seam `jetpacs-chrome-global-items-function' obeys this.
Placement is a RE-AUTHORING, and a raw node
\(`jetpacs-chrome-global-actions-function') is by definition already
authored — the same division the dock pair draws.

This is the one seam member that is a defcustom, and the closed choice
of consts is why: `customize.set' `read's a wire STRING for any type it
cannot decode as a boolean, a number, or a choice of consts
\(`jetpacs-settings--decode'), so a phone-settable FUNCTION value would
hand every later chrome build whatever the wire typed.  Three symbols
cannot carry a payload; a function value can, which is why the seams
themselves stay defvars.  Setting this through Custom re-pushes every
chrome surface, so the placement moves without a navigation.

Two authored-wins consequences to expect on device: a screen that
authors M-x in its own top bar keeps it THERE at every placement
\(the de-dup — the hub, Buffers, and the m3 catalog do), and a screen
whose `fab' slot holds its own button keeps that button, the globals
falling back to its top bar."
  :type '(choice (const :tag "Top app bar (M-x top-right)" top-bar)
                 (const :tag "FAB (a fab menu once there are several)" fab)
                 (const :tag "FAB menu (always)" fab-menu))
  :set (lambda (sym val)
         (set-default sym val)
         ;; Unbound at definition time (`custom-initialize-reset' calls
         ;; this with the standard value), which is exactly when there
         ;; is nothing to re-push — the theme options' guard.
         (when (featurep 'jetpacs-chrome)
           (maphash (lambda (surface _stack)
                      (jetpacs-shell--schedule-repush surface))
                    jetpacs-chrome--stacks)))
  :group 'jetpacs)

(defvar jetpacs-chrome-global-items-function nil
  "Function (SURFACE) -> the shell globals as DATA, or nil.
Each item is a plist (:icon STR :label STR :on-tap DESCRIPTOR); the
label is the accessible name in the top-bar form and the menu row's
text in the fab-menu form, so all three members are required.  This is
the placement-adaptive alternative to
`jetpacs-chrome-global-actions-function', standing to it as
`jetpacs-chrome-dock-items-function' stands to
`jetpacs-chrome-dock-function': only data can be re-authored, so only
data can obey `jetpacs-chrome-global-actions-placement' — a finished
top-bar node dropped into the `fab' slot would be a presentation lie,
not a placement.

Where the dock pair gives the win to the raw node, this pair gives it
to the DATA, and the asymmetry is deliberate: the device seeds BOTH
globals seams, so raw-wins would leave the placement inert on the
default install.  Items, whenever this seam yields any, supersede the
node seam whole — at every placement, `top-bar' included, where the
two would otherwise each contribute their own M-x.  The node seam
remains the top-bar-only override for a host with no items to give.
Degrades like the dock: a signal or malformed items cost the globals,
never the surface.")

(defvar jetpacs-chrome-fab-menu-function nil
  "Optional function (ITEMS) -> one design-layer FAB-menu node.
Jetpacs chrome owns placement and normalized action data, but not a Material
control.  A selected design implementation installs this seam.  Without one,
several global actions fall back to ordinary Compose-shaped icon buttons.")

(defun jetpacs-chrome--global-items (surface)
  "SURFACE's shell-global ITEMS, validated and isolated; nil without the seam.
The check is all-or-nothing like the dock's: an item missing what
every placement needs cannot be re-authored at all, and a partially
honored globals list is a worse answer than none."
  (when jetpacs-chrome-global-items-function
    (condition-case err
        (let ((items (funcall jetpacs-chrome-global-items-function surface)))
          (and (consp items)
               (cl-every (lambda (item)
                           (and (listp item)
                                (stringp (plist-get item :icon))
                                (stringp (plist-get item :label))
                                (plist-get item :on-tap)))
                         items)
               items))
      (error (message "jetpacs-chrome: global items failed: %s"
                      (jetpacs-error-label err))
             nil))))

(defun jetpacs-chrome--global-slot (surface)
  "SURFACE's shell globals as (SLOT . VALUE), or nil.
`:top_bar' carries finished action NODES, authored once per build and
appended to every screen's bar.  `:fab' carries the ITEM PLISTS still
as data: the de-dup against a screen's authored top bar is a
per-screen question and the slot holds exactly ONE node, so the answer
differs per screen and the authoring has to happen there.
The data seam supersedes the node one — see
`jetpacs-chrome-global-items-function'."
  (if-let* ((items (jetpacs-chrome--global-items surface)))
      (if (memq jetpacs-chrome-global-actions-placement '(fab fab-menu))
          (cons :fab items)
        (condition-case err
            (cons :top_bar (jetpacs-chrome--global-item-buttons items))
          (error (message "jetpacs-chrome: global items failed: %s"
                          (jetpacs-error-label err))
                 nil)))
    (when-let* ((nodes (jetpacs-chrome--global-actions surface)))
      (cons :top_bar nodes))))

(defun jetpacs-chrome--global-item-buttons (items)
  "ITEMS authored as top-bar icon buttons — the `top-bar' arm's form.
Shared with the fab arms' FALLBACK for a screen whose `:fab' slot is
already taken: authored-wins costs the globals their slot there, never
their reach."
  (mapcar (lambda (item)
            (jetpacs-icon-button
             (plist-get item :icon)
             (plist-get item :on-tap)
             :content-description (plist-get item :label)))
          items))

(defun jetpacs-chrome--global-fab (items)
  "ITEMS as the ONE node the `fab' slot wears at the current placement.
A single item under `fab' is the plain FAB: the slot takes any node
and an icon button is what the tree already puts there (the buffer
screen's command-palette FAB).  Anything else is M3's
FloatingActionButtonMenu, whose toggle wears the vocabulary's menu
anchor rather than the builder's `add' default — these are the shell's
globals, not a creation act."
  (if (and (null (cdr items))
           (eq jetpacs-chrome-global-actions-placement 'fab))
      (jetpacs-icon-button (plist-get (car items) :icon)
                           (plist-get (car items) :on-tap)
                           :content-description (plist-get (car items) :label))
    (if jetpacs-chrome-fab-menu-function
        (funcall jetpacs-chrome-fab-menu-function items)
      (jetpacs-column
       (mapcar (lambda (item)
                 (jetpacs-icon-button
                  (plist-get item :icon) (plist-get item :on-tap)
                  :content-description (plist-get item :label)))
               items)
       :spacing 8))))

(defun jetpacs-chrome--join-global-fab (surface n items)
  "Give SURFACE's scaffold N the shell globals as its `fab', de-duped.
Authored-wins costs the globals their SLOT, never their REACH: a
screen authoring its own `:fab' keeps it untouched and the globals
fall back to the top-bar join instead — M-x must keep a home on
every screen (the S3 build-within promise; downstream capture FABs, the
buffer screen's palette FAB and the m3 demos all author fabs).
Items whose `:on-tap' action already appears in the authored top bar
are dropped first — the top-bar join's own token-delimited search —
so a screen that authors M-x itself never grows a SECOND M-x in
another slot; nothing left means no fab there.  The authoring runs
per screen (the de-dup answer is per-screen), so it carries its own
isolation: a malformed item costs the fab, never the screen.

The injected fab subtree is run through the per-view gate and the
join DROPPED if it fails: a design-layer menu is outside the Core Node Set,
so a session whose profile lacks it would otherwise turn EVERY
screen of every chrome surface into an error card for as long as the
placement stayed set — a total loss for a presentation preference.
The SUBTREE only: the caller gates the whole screen a line later, so
gating the joined screen here would walk everything twice per build.
The top-bar arm needs no such retry: its `icon_button' is what the
authored bars beside it are already made of."
  ;; GR-7b's per-app FAB registry lands in this slot too and must
  ;; outrank the globals here for the same reason a screen does: the
  ;; FAB's contract is the primary creation act, and a shell global is
  ;; a guest in that slot, welcome only while it stands empty.
  (if (null items)
      n
    (jetpacs-chrome--scaffold-apply
     n
     (lambda (s)
       (cond
        ((plist-member s :fab)
         (jetpacs-chrome--join-global-actions
          s (condition-case err
                (jetpacs-chrome--global-item-buttons items)
              (error (message "jetpacs-chrome: global fab fallback failed: %s"
                              (jetpacs-error-label err))
                     nil))))
        (t
         (let* ((authored (format "%S" (plist-get s :top_bar)))
                (missing (cl-remove-if
                          (lambda (item)
                            (when-let* ((action (plist-get
                                                 (plist-get item :on-tap)
                                                 :action)))
                              ;; Printed WITH its quotes so the token is
                              ;; delimited, as in the top-bar join.
                              (string-search (format "%S" action) authored)))
                          items))
                (fab (and missing
                          (condition-case err
                              (jetpacs-chrome--global-fab missing)
                            (error (message "jetpacs-chrome: global fab \
failed: %s" (jetpacs-error-label err))
                                   nil)))))
           (if (and fab
                    (ignore-errors (jetpacs-chrome--gate-view surface fab) t))
               (append s (list :fab fab))
             s))))))))

(defun jetpacs-chrome--join-globals (surface n globals)
  "Join GLOBALS — `jetpacs-chrome--global-slot''s cons — into screen N.
The placement fan-out: every arm is single-slot authored-wins and
de-duped by action name, so no placement can double an affordance the
screen already carries."
  (pcase globals
    (`(:top_bar . ,nodes) (jetpacs-chrome--join-global-actions n nodes))
    (`(:fab . ,items) (jetpacs-chrome--join-global-fab surface n items))
    (_ n)))

(defun jetpacs-chrome--dock-slot (surface)
  "SURFACE\'s dock as (SLOT . NODE), or nil.
SLOT is `:bottom_bar\' — or `:rail\' when the destinations come from
`jetpacs-chrome-dock-items-function\' and NEITHER window axis is
compact (SPEC 20.1.1): the M3 layout guidance and Compose\'s
NavigationSuiteScaffold both give the bar to compact windows — a phone
in either orientation — and the start-edge rail to medium and expanded
ones.  The raw-node dock stays a bottom bar unconditionally; only the
data form can swap, because only data can be re-authored into a rail."
  (if-let* ((node (jetpacs-chrome--dock surface)))
      (cons :bottom_bar node)
    (when jetpacs-chrome-dock-items-function
      (condition-case err
          (when-let* ((items (funcall jetpacs-chrome-dock-items-function
                                      surface))
                      ((consp items)))
            (if (not (or (equal (jetpacs-window-class :width) "compact")
                         (equal (jetpacs-window-class :height) "compact")))
                (cons :rail
                      (jetpacs-navigation-rail
                       (mapcar (lambda (item)
                                 (jetpacs-rail-item
                                  (plist-get item :label)
                                  (plist-get item :icon)
                                  (plist-get item :on-tap)
                                  :selected (plist-get item :selected)
                                  :badge (plist-get item :badge)))
                               items)
                       :arrangement "center"))
              (cons :bottom_bar
                    ;; The 80dp container height is what the M3
                    ;; NavigationBar composable owns upstream; here it is
                    ;; the universal height attribute on the slot node.
                    (jetpacs-with-attrs
                     (apply #'jetpacs-row
                            (append (mapcar #'jetpacs-chrome--dock-tab items)
                                    (list :align "center" :fill t)))
                     :height 80))))
        (error (message "jetpacs-chrome: dock items failed: %s"
                        (jetpacs-error-label err))
               nil)))))

(defvar jetpacs-chrome--window-classes nil
  "The (WIDTH-CLASS . HEIGHT-CLASS) chrome last authored for.")

(defun jetpacs-chrome--on-window-changed (_client _window)
  "Re-push every chrome surface when the size CLASS flips (SPEC 20.1.1).
Geometry ticks inside one class cost nothing; a flip re-pushes each
chrome surface (debounced by the shell) so the dock swaps forms and
every screen re-authors for the new class."
  (let ((classes (cons (jetpacs-window-class :width)
                       (jetpacs-window-class :height))))
    (unless (equal classes jetpacs-chrome--window-classes)
      (setq jetpacs-chrome--window-classes classes)
      (maphash (lambda (surface _stack)
                 (jetpacs-shell--schedule-repush surface))
               jetpacs-chrome--stacks))))

(defun jetpacs-chrome--on-ready (client)
  "Seed the class memo and attach the window hook.
The welcome mirrors the geometry before ready runs (SPEC 20.1.1), so
seeding here means the first `window.changed\' re-pushes only on a REAL
class flip — not on the notification that merely repeats the welcome.
If surfaces were pushed offline before ready, their initial render used
the default compact class; a flip here re-pushes them for the real geometry."
  (setq jetpacs-chrome--window-classes '("compact" . "compact"))
  (let ((classes (cons (jetpacs-window-class :width)
                       (jetpacs-window-class :height))))
    (unless (equal classes jetpacs-chrome--window-classes)
      (setq jetpacs-chrome--window-classes classes)
      (maphash (lambda (surface _stack)
                 (jetpacs-shell--schedule-repush surface))
               jetpacs-chrome--stacks)))
  (cl-pushnew #'jetpacs-chrome--on-window-changed
              (ebp-client-window-changed-functions client)))

(defun jetpacs-chrome--error-screen (surface id back err)
  "A Core-Node-Set stand-in for screen ID whose builder failed with ERR.
ERR is a signal object or a bare error SYMBOL.  Core Node Set ONLY:
SPEC 16.2 makes `text', `column' and `button' types every `app' profile
MUST carry (SPEC 10.2), and every builder called here is total for
these arguments — the degrade path must not be able to fail the gate it
exists to survive.  Keeps BACK whenever a screen is below: the broken
screen is normally the one just pushed, hence on display, and
`view.switch' is companion-local — the one escape that does not need
the crashed Emacs side to answer.  The finished card is run through the
per-view gate and retried WITHOUT the button if it fails: SPEC.md
requires `view.switch' of every conforming app profile, but GATE 1
checks the LIVE one, and the degrade path must not out-fail the failure
it degrades.  SPEC 23.3: the body is the error SYMBOL, never
`error-message-string' — that embeds the offending datum, and SPEC 13.2
has the Companion PERSIST this text on the device."
  (let ((card (lambda (b)
                (apply #'jetpacs-column
                       (append
                        (list (jetpacs-text
                               (format "Screen %s failed to build" id)
                               :style "title")
                              (jetpacs-text (jetpacs-error-label err)
                                            :style "body"))
                        (when b (list (jetpacs-button "Back" b)))
                        (list :spacing 8))))))
    (or (ignore-errors
          (let ((n (funcall card back)))
            (jetpacs-chrome--gate-view surface n)
            n))
        (funcall card nil))))

(defun jetpacs-chrome--gate-view (surface node &optional analysis)
  "Signal when NODE uses what SURFACE's LIVE session does not allow.
A per-view pre-run of the shell's GATE 1 (node types, builtins,
features) and GATE 4 (the ratified amendments — an ungranted `wake'
descriptor or synchronized editor), so the failure costs its own screen
instead of making the whole surface unpushable for the process
lifetime.  No client (offline render, tests) is a no-op; a missing
profile skips only GATE 1 — `--gate-spec' would signal its own
\='no profile\=' error for every view, strictly worse than one
push-level failure.  The authority remains the shell's gates on the
assembled spec; this is the same check run earlier, per screen.

ANALYSIS, when supplied, is `jetpacs-shell--analyze-spec' output.  The
returned analysis also supplies exact ids to the caller.  Sharing this one
walk matters for long Files and Org views: the old path independently walked
the same tree for profile uses, amendments, and ids before the shell walked
the complete snapshot again."
  (let ((analysis (or analysis (jetpacs-shell--analyze-spec node))))
    (when-let* ((client (jetpacs-client)))
      (when (plist-get (ebp-client-profiles client)
                       (jetpacs-shell--surface-target surface))
        (jetpacs-shell--gate-spec client surface node nil analysis nil))
      (jetpacs-shell--gate-amendments client node analysis))
    ;; Claiming across screens happens separately, but an authored duplicate
    ;; within this screen is already known by the shared analysis.
    (jetpacs-shell--gate-ids node nil analysis nil)
    analysis))

(cl-defun jetpacs-chrome--claim-screen-ids
    (node seen &optional (known-ids nil known-ids-p))
  "Check NODE's ids against SEEN (prior screens) and record them.
KNOWN-IDS may be the exact list captured for an unchanged cached NODE.
Signals `jetpacs-duplicate-node-id' when NODE repeats an id an earlier
screen emitted, or repeats one within itself — SPEC 16.1 scopes
uniqueness to the whole document and the Companion answers a duplicate
with 1201 for the ENTIRE update.  On success the ids are added to SEEN
and SEEDED into `jetpacs-node-id-claims' (as t, never clobbering a
minter's count), so a LATER screen's minted id routes around an earlier
screen's literal.  Minted ids are already unique by construction — the
signal here means a LITERAL authored id collided, and the caller turns
it into that screen's error card.  Returns the ids used."
  (let ((ids (if known-ids-p known-ids
               (jetpacs-collect-node-ids node nil)))
        (mine (make-hash-table :test #'equal)))
    (dolist (id ids)
      (when (or (gethash id seen) (gethash id mine))
        (signal 'jetpacs-duplicate-node-id (list id)))
      (puthash id t mine))
    (dolist (id ids)
      (puthash id t seen)
      (when jetpacs-node-id-claims
        (unless (gethash id jetpacs-node-id-claims)
          (puthash id t jetpacs-node-id-claims))))
    ids))

(defun jetpacs-chrome--gate-signature ()
  "Snapshot the live session facts that make a cached view gate-valid."
  (when-let* ((client (jetpacs-client)))
    (list :client client
          :profiles (copy-tree (ebp-client-profiles client))
          :granted (copy-sequence (ebp-client-granted client))
          :limits (copy-tree (ebp-client-limits client)))))

(defun jetpacs-chrome--budget-snapshot ()
  "Copy the render budgets at the current point in a surface build."
  (list :main (and jetpacs-buffer-budget
                   (cons (car jetpacs-buffer-budget)
                         (cdr jetpacs-buffer-budget)))
        :extra (and jetpacs-buffer-extra-budget
                    (copy-tree jetpacs-buffer-extra-budget))))

(defun jetpacs-chrome--restore-budget (snapshot)
  "Restore render budgets from SNAPSHOT inside the current build."
  (let ((main (plist-get snapshot :main)))
    (when (and main jetpacs-buffer-budget)
      (setcar jetpacs-buffer-budget (car main))
      (setcdr jetpacs-buffer-budget (cdr main))))
  (setq jetpacs-buffer-extra-budget
        (copy-tree (plist-get snapshot :extra))))

(defun jetpacs-chrome--same-stack-p (cached live)
  "Whether CACHED and LIVE contain the identical immutable stack entries."
  (and (= (length cached) (length live))
       (cl-loop for old in cached
                for new in live
                always (eq old new))))

(defun jetpacs-chrome--cache-compatible-p (cache stack target gate-signature)
  "Whether CACHE can supply every STACK view below topmost TARGET.
Budget snapshots are part of compatibility: a changed welcome limit or
different earlier spend forces the ordinary full rebuild."
  (and cache target (equal target (caar stack))
       (equal gate-signature (plist-get cache :gate-signature))
       (jetpacs-chrome--same-stack-p (plist-get cache :stack) stack)
       (let ((screens (plist-get cache :screens))
             (expected (jetpacs-chrome--budget-snapshot))
             (ok t))
         (dolist (entry (reverse stack) ok)
           (unless (equal (car entry) target)
             (let ((record (gethash (car entry) screens)))
               (if (and record
                       (eq entry (plist-get record :entry))
                       (plist-get record :reusable)
                       (plist-get record :analysis)
                       (equal expected (plist-get record :before)))
                   (setq expected (plist-get record :after))
                 (setq ok nil))))))))

(defun jetpacs-chrome--build (surface)
  "Build SURFACE's stack as one complete multi_view snapshot.
Ordinary pushes rebuild every screen.  When
`jetpacs-chrome--target-refresh-view' names the unchanged stack top, reuse
the last lower views and run only that visible screen's builder.  The cached
views remain in the complete snapshot required by SPEC 13.2."
  (let ((stack (gethash surface jetpacs-chrome--stacks)))
    (unless stack
      (error "jetpacs-chrome: no chrome stack for %s" surface))
    (jetpacs-buffer-with-budget
      (let* ((seen (make-hash-table :test #'equal))
             (dock (jetpacs-chrome--dock-slot surface))
             (drawer (jetpacs-chrome--drawer surface))
             (globals (jetpacs-chrome--global-slot surface))
             (gate-signature (jetpacs-chrome--gate-signature))
             (old-cache (gethash surface jetpacs-chrome--view-cache))
             (reuse-cache
              (and (jetpacs-chrome--cache-compatible-p
                    old-cache stack jetpacs-chrome--target-refresh-view
                    gate-signature)
                   old-cache))
             (old-screens (and reuse-cache (plist-get reuse-cache :screens)))
             (new-screens (make-hash-table :test #'equal))
             analyses views prev-id)
        (dolist (entry (reverse stack))
          (let* ((id (car entry))
                 (back (and prev-id (jetpacs-view-switch prev-id)))
                 (before (jetpacs-chrome--budget-snapshot))
                 (budget jetpacs-buffer-budget)
                 (spans (car-safe budget))
                 (bytes (cdr-safe budget))
                 (extra (copy-tree jetpacs-buffer-extra-budget))
                 (prior-record (and old-screens (gethash id old-screens)))
                 (record (and (not (equal id
                                          jetpacs-chrome--target-refresh-view))
                              prior-record))
                 (cached (and record (plist-get record :node)))
                 (exposure-capture (and (not cached) (list nil)))
                 (jetpacs-buffer--exposure-capture exposure-capture)
                 (fail nil)
                 analysis
                 ids
                 node)
            (if cached
                (progn
                  (setq node cached)
                  (setq analysis (plist-get record :analysis))
                  (jetpacs-chrome--restore-budget (plist-get record :after))
                  ;; Restore the exact SPEC 23.1 operations captured while the
                  ;; view was built.  Walking arbitrary action args here was
                  ;; both slower and less exact.
                  (jetpacs-buffer-restore-exposures
                   (plist-get record :exposures))
                  (setq node
                        (condition-case err
                            (handler-bind
                                ((error
                                  (lambda (e)
                                    (jetpacs-shell--note-builder-error
                                     (list :surface surface :screen id) e))))
                              ;; The identical node passed this per-screen gate
                              ;; under the identical welcome signature when it
                              ;; entered the cache.  The shell's final gates
                              ;; still validate the complete outgoing snapshot.
                              (setq ids
                                    (jetpacs-chrome--claim-screen-ids
                                     node seen (plist-get record :ids)))
                              node)
                          (error (setq fail err) nil))))
              (setq node
                    (condition-case err
                        ;; The recorder seam fires HERE, stack intact — the
                        ;; catch below unwinds only this screen's crash.
                        (handler-bind
                            ((error
                              (lambda (e)
                                (jetpacs-shell--note-builder-error
                                 (list :surface surface :screen id) e))))
                          (let ((n (jetpacs-chrome--present
                                    (funcall (cdr entry) back))))
                            ;; The drawer hangs on every screen that draws
                            ;; no back arrow: the root, and a peer
                            ;; destination that declined the stack's back
                            ;; (a Tier-1 place beside the rail).  A drill
                            ;; keeps its arrow and gets no hamburger, as
                            ;; Material's own navigation icon would.
                            ;; Authored slots always win.  Composition
                            ;; reaches the scaffold THROUGH an app's
                            ;; presentation wrapper, so presenting a screen
                            ;; never costs it the host's chrome.
                            (when (and drawer
                                       (not (jetpacs-chrome--shows-back-p n)))
                              (let ((mine (if back
                                              (jetpacs-chrome--namespace-ids
                                               drawer id)
                                            drawer)))
                                (setq n (jetpacs-chrome--scaffold-apply
                                         n
                                         (lambda (s)
                                           (if (plist-member s :drawer)
                                               s
                                             (append s
                                                     (list :drawer mine))))))))
                            ;; Adaptive dock; authored bar/rail opts out.
                            (when dock
                              (setq n (jetpacs-chrome--scaffold-apply
                                       n
                                       (lambda (s)
                                         (if (or (plist-member s :bottom_bar)
                                                 (plist-member s :rail))
                                             s
                                           (append s (list (car dock)
                                                           (cdr dock))))))))
                            (setq n (jetpacs-chrome--join-app-fab surface id n))
                            (setq n (jetpacs-chrome--join-globals
                                     surface n globals))
                            ;; A targeted refresh still runs the visible
                            ;; builder.  When its authored node is structurally
                            ;; unchanged under the same gate signature, reuse
                            ;; its immutable facts instead of allocating a new
                            ;; full-tree analysis merely to rediscover them.
                            (if (and prior-record
                                     (plist-get prior-record :reusable)
                                     (plist-get prior-record :analysis)
                                     (equal n (plist-get prior-record :node)))
                                (progn
                                  (setq analysis
                                        (plist-get prior-record :analysis))
                                  (setq ids
                                        (jetpacs-chrome--claim-screen-ids
                                         n seen
                                         (plist-get prior-record :ids))))
                              (setq analysis
                                    (jetpacs-chrome--gate-view surface n))
                              (setq ids
                                    (jetpacs-chrome--claim-screen-ids
                                     n seen (plist-get analysis :ids))))
                            n))
                      (error (setq fail err) nil))))
            (unless (or fail (jetpacs-root-node-p node))
              (setq fail 'wrong-type-argument)
              (jetpacs-shell--note-builder-error
               (list :surface surface :screen id) fail))
            (when fail
              ;; A dead screen spent budget it never ships.  Restore both
              ;; the main and extra aggregate allowances before degrading it.
              (when (consp budget)
                (setcar budget spans)
                (setcdr budget bytes))
              (setq jetpacs-buffer-extra-budget (copy-tree extra))
              (message "jetpacs-chrome: screen %s failed to build: %s"
                       id (jetpacs-error-label fail))
              (setq node (jetpacs-chrome--error-screen surface id back fail))
              (setq analysis (jetpacs-shell--analyze-spec node)))
            (push analysis analyses)
            (puthash id
                     (list :entry entry :node node :before before
                           :after (jetpacs-chrome--budget-snapshot)
                           :analysis analysis
                           :ids ids
                           :exposures
                           (if cached
                               (plist-get record :exposures)
                             (nreverse (car exposure-capture)))
                           :reusable (null fail))
                     new-screens)
            (push (cons id node) views)
            (setq prev-id id)))
        (let* ((spec (jetpacs-multi-view (nreverse views) (caar stack)))
               (analysis
                (jetpacs-shell--merge-analyses (nreverse analyses))))
          (when (consp jetpacs-shell--analysis-capture)
            (setcar jetpacs-shell--analysis-capture (cons spec analysis)))
          (puthash surface
                   (list :stack (copy-sequence stack)
                         :gate-signature gate-signature
                         :screens new-screens)
                   jetpacs-chrome--view-cache)
          spec)))))

(defun jetpacs-chrome-defer-current-view-refresh (surface)
  "Defer a complete SURFACE push rebuilding only its current stack top.
The stack and budget cache are revalidated inside the eventual build.  If
navigation, builders, or limits changed in between, `jetpacs-chrome--build'
automatically performs an ordinary full rebuild instead."
  (let* ((surface (jetpacs-shell--resolve-surface surface))
         (view (caar (gethash surface jetpacs-chrome--stacks))))
    (if (not view)
        (jetpacs-buffer-defer-refresh surface)
      (run-at-time
       0 nil
       (lambda ()
         (let ((jetpacs-chrome--target-refresh-view view))
           (jetpacs-buffer--refresh surface)))))))

(setq jetpacs-buffer-view-refresh-function
      #'jetpacs-chrome-defer-current-view-refresh)

(cl-defun jetpacs-chrome-define-root (surface-or-owner id builder
                                                       &key required)
  "Define SURFACE's chrome root screen; re-evaluation RESETS the stack.
Call under `with-jetpacs-owner' — the shell records the owner and
re-binds it around every build.  Returns the surface id."
  (let ((surface (jetpacs-shell--resolve-surface surface-or-owner)))
    (jetpacs-check-identifier id "screen id")
    (remhash surface jetpacs-chrome--view-cache)
    (puthash surface (list (cons id builder)) jetpacs-chrome--stacks)
    (jetpacs-shell-define-root surface
                               (lambda () (jetpacs-chrome--build surface))
                               :required required)
    (jetpacs-chrome--claim-drill-host surface)
    surface))

(defun jetpacs-chrome--stack-insert (surface id builder)
  "Validate and insert (ID . BUILDER) at SURFACE's stack top.
An ID already on the stack TRUNCATES to that entry and replaces its
builder — re-entrant navigation; without this `jetpacs-multi-view'
signals a duplicate view id and the surface degrades to the error
screen.  Pure stack mutation: no push.

Returns a nullary UNDO thunk restoring the prior stack.  Two rules make
the undo exact.  (a) The replace branch CONSES a fresh entry instead of
`setcdr'-ing the found one in place: that cons is SHARED with the saved
stack, so an in-place replace would survive any restore of it and a
rolled-back entry would keep poisoning every later build.  (b) The undo
restores only while the stored stack is still `eq' to the one this
insert produced — a deferred rollback must never discard a navigation
that happened in between."
  (let ((stack (gethash surface jetpacs-chrome--stacks)))
    (unless stack
      (error "jetpacs-chrome: no chrome stack for %s" surface))
    (jetpacs-check-identifier id "screen id")
    (let* ((tail (cl-member id stack :key #'car :test #'equal))
           (new (cons (cons id builder) (if tail (cdr tail) stack)))
           ;; The bound applies HERE, before the single puthash, so the
           ;; undo thunk's `eq' guard sees the same object it stored — an
           ;; eviction done as a second write would defeat every rollback.
           (new (if (> (length new) jetpacs-chrome-max-screens)
                    (append (seq-take new (1- jetpacs-chrome-max-screens))
                            (last new))
                  new)))
      (puthash surface new jetpacs-chrome--stacks)
      (lambda ()
        (when (eq new (gethash surface jetpacs-chrome--stacks))
          (puthash surface stack jetpacs-chrome--stacks))))))

(defun jetpacs-chrome--push-or-undo (surface view undo)
  "Push SURFACE forcing VIEW; a signalling push runs UNDO and re-raises.
The transactional half of the kit's design rule: the reply must
describe the MODEL mutation, and an ADDITION is committed only if the
surface stays renderable — `jetpacs-chrome--build' rebuilds the whole
stack on every push, so an entry that failed a gate would otherwise
refuse every later push of the surface for the process lifetime."
  (condition-case err
      (jetpacs-shell-push surface :current-view view)
    (error
     (funcall undo)
     ;; `jetpacs-shell-push' consumed SURFACE's queued repush entry
     ;; (--drop-pending) BEFORE the gate signalled; without this line a
     ;; rolled-back navigation also silently costs an unrelated pending
     ;; re-render.  No-op while disconnected, which is correct.
     (jetpacs-shell--schedule-repush surface)
     (signal (car err) (cdr err)))))

(defun jetpacs-chrome--push-quietly (surface view)
  "Push SURFACE forcing VIEW; a signalling push logs and returns nil.
The commit-unconditionally half of the design rule: a REMOVAL (pop,
reset) can only shrink the stack, so it cannot make the surface less
renderable than it was — the mutation is kept, the presentation loss is
logged, and the requeued push renders the truncated stack."
  (condition-case err
      (jetpacs-shell-push surface :current-view view)
    (error
     (jetpacs-shell--schedule-repush surface)
     ;; VIEW and SURFACE are app-minted wire ids already on the wire —
     ;; naming them is not a SPEC 23.3 exposure, and a bare error label
     ;; alone ("error") locates nothing.
     (message "jetpacs-chrome: push of %s (view %s) failed: %s"
              surface view (jetpacs-error-label err))
     nil)))

(defvar jetpacs-chrome--guests (make-hash-table :test #'equal)
  "SURFACE -> alist of (SCREEN-ID . OWNER): the sanctioned guests (S4).
A row grants nothing by itself — `jetpacs-chrome--guest-delegate-p'
requires the id to still be ON the surface's live stack, so a back
truncation or pop revokes without bookkeeping here; stale rows are
inert and swept when their owner tears down.")

(defun jetpacs-chrome-guest-screen-id (owner id)
  "Return the sanctioned-guest wire id for OWNER's screen ID.
This is the public counterpart of `jetpacs-chrome-push-screen''s S4
prefixing rule, for callers that need to recognize the ordinary
`view.switched' report when their guest becomes visible."
  (jetpacs-check-identifier owner "guest owner")
  (jetpacs-check-identifier id "guest screen id")
  (concat "guest-" owner "-" id))

(defun jetpacs-chrome--screen-owner (surface id)
  "Owner of screen ID on SURFACE, distinguishing sanctioned guests.
Guest ownership is the S4 record minted at push time; native screens
fall back to the owner that registered the surface root."
  (or (alist-get id (gethash surface jetpacs-chrome--guests)
                 nil nil #'equal)
      (jetpacs--owner-of "surface" surface)))

(defun jetpacs-chrome--join-app-fab (surface id node)
  "Inject ID's app-default FAB into scaffold NODE when its slot is free.
Authored-wins is absolute.  The seam receives the screen owner rather
than inferring an app from SURFACE, which is what keeps host defaults
off S4 guests."
  (if (null jetpacs-chrome-app-fab-function)
      node
    (jetpacs-chrome--scaffold-apply
     node
     (lambda (s)
       (if (plist-member s :fab)
           s
         (let ((fab
                (condition-case err
                    (funcall jetpacs-chrome-app-fab-function
                             (jetpacs-chrome--screen-owner surface id)
                             surface)
                  (error
                   (message "jetpacs-chrome: app fab failed: %s"
                            (jetpacs-error-label err))
                   nil))))
           (if (and (jetpacs-root-node-p fab)
                    (ignore-errors (jetpacs-chrome--gate-view surface fab) t))
               (append s (list :fab fab))
             s)))))))

(defun jetpacs-chrome--guest-delegate-p (owner surface)
  "Non-nil when OWNER has a guest screen live on SURFACE's stack.
THE `jetpacs-guest-delegation-function': validity derives from the
stack, never from the table alone."
  (when-let* ((rows (gethash surface jetpacs-chrome--guests))
              (stack (gethash surface jetpacs-chrome--stacks)))
    (cl-some (lambda (row)
               (and (equal (cdr row) owner)
                    (cl-member (car row) stack :key #'car :test #'equal)
                    t))
             rows)))

(defun jetpacs-chrome-sweep-guests (owner)
  "Remove OWNER's guest screens from every foreign stack; repush touched.
The S4 teardown half: without it a stranded guest survives its app's
unload as a rebuilt error card on the HOST's surface.  Rides
`jetpacs-teardown-functions' and `jetpacs-chrome-remove', so both the
session-teardown and the live-unregister paths sweep."
  (maphash
   (lambda (surface rows)
     (let ((mine (cl-remove-if-not (lambda (r) (equal (cdr r) owner))
                                   rows)))
       (when mine
         (puthash surface
                  (cl-set-difference rows mine) jetpacs-chrome--guests)
         (when-let* ((stack (gethash surface jetpacs-chrome--stacks)))
           (let ((kept (cl-remove-if
                        (lambda (entry)
                          (cl-member (car entry) mine
                                     :key #'car :test #'equal))
                        stack)))
             (unless (equal kept stack)
               (puthash surface kept jetpacs-chrome--stacks)
               ;; Offline-safe: the queued repush renders the swept
               ;; stack at the next opportunity, never inline here —
               ;; teardown may run inside a dispatch extent.
               (jetpacs-shell--schedule-repush surface)))))))
   jetpacs-chrome--guests))

(defun jetpacs-chrome--on-guest-teardown (owner)
  "`jetpacs-teardown-functions' member: sweep OWNER's guests."
  (jetpacs-chrome-sweep-guests owner))
(add-hook 'jetpacs-teardown-functions #'jetpacs-chrome--on-guest-teardown)

(setq jetpacs-guest-delegation-function #'jetpacs-chrome--guest-delegate-p)

(defun jetpacs-chrome-push-screen (surface-or-owner id builder)
  "Push screen ID onto SURFACE's stack and navigate to it.
ID is a SPEC 4.4 identifier, validated BEFORE any mutation — mint
dynamic ids from buffer names/paths through `jetpacs-wire-id'.  The
only navigation-forcing push shape (`:current-view').  From an action
handler pass (plist-get params :surface): the wire names the surface the
user actually tapped, which is not necessarily this owner's primary one.
\(`jetpacs--dispatch' binds the registering owner now, so the zero-arg
default is no longer simply wrong — it is merely a different surface.)

TRANSACTIONAL: a push the gates refuse rolls the stack back and
re-signals, so a screen that cannot render never enters the model — an
entry that failed a gate would otherwise refuse EVERY later push of the
surface (`jetpacs-chrome--build' rebuilds the stack each time).  A
deferred caller (`jetpacs-flow-continue') must wrap in `condition-case'
or the re-signal dies in a timer.  Returns the claimed revision, or nil
while disconnected — nil is NOT failure: the mutation is kept and the
next successful push renders it.  Never retry-loop on nil.  (The W10
sender ceiling does NOT yield nil here: `ebp-client--surface-request'
claims and returns the revision before the ceiling can refuse; the
refused frame is retried by the B8 repush.)"
  (let* ((surface (jetpacs-shell--resolve-surface surface-or-owner))
         ;; S4: pushed by an owner onto a stack it does not own, the
         ;; screen is a sanctioned GUEST — its id is prefixed (two
         ;; apps pushing "settings" onto the host must not truncate
         ;; each other's entries) and recorded, which is what admits
         ;; this owner's events from the host surface while the
         ;; screen lives (`jetpacs-chrome--guest-delegate-p').
         (guest-owner (and jetpacs-current-owner
                           (not (jetpacs-owned-surface-p
                                 surface jetpacs-current-owner))
                           jetpacs-current-owner))
         (id (if guest-owner
                 (jetpacs-chrome-guest-screen-id guest-owner id)
               id))
         (undo (jetpacs-chrome--stack-insert surface id builder)))
    (when guest-owner
      (let ((rows (gethash surface jetpacs-chrome--guests)))
        (unless (equal (alist-get id rows nil nil #'equal) guest-owner)
          (puthash surface (cons (cons id guest-owner)
                                 ;; Re-noting replaces; rows whose id
                                 ;; left the stack are inert either way.
                                 (cl-remove id rows
                                            :key #'car :test #'equal))
                   jetpacs-chrome--guests))))
    (if guest-owner
        ;; The presenting push must not CLAIM the host's surface for
        ;; the guest (`jetpacs-shell-push' claims under the bound
        ;; owner): a stolen claim makes `jetpacs-owned-surface-p'
        ;; answer t for the guest from then on — an UNSCOPED D1 grant
        ;; where S4 promises a screen-lifetime one, unprefixed
        ;; re-pushes, and the host's root swept by the guest's
        ;; teardown.  Bind the owner of record (nil leaves an
        ;; unclaimed surface unclaimed; `jetpacs--claim' no-ops).
        (let ((jetpacs-current-owner (jetpacs--owner-of "surface" surface)))
          (jetpacs-chrome--push-or-undo surface id undo))
      (jetpacs-chrome--push-or-undo surface id undo))))

(defun jetpacs-chrome-pop-screen (surface-or-owner)
  "Pop SURFACE's stack and navigate to the screen below (Emacs-side
back — a completed flow returning to its hub; the on-screen arrow never
calls this).  At the root: idempotent no-op returning nil."
  (let* ((surface (jetpacs-shell--resolve-surface surface-or-owner))
         (stack (gethash surface jetpacs-chrome--stacks)))
    (when (cdr stack)
      (puthash surface (cdr stack) jetpacs-chrome--stacks)
      (jetpacs-chrome--push-quietly surface (caar (cdr stack))))))

(defun jetpacs-chrome-reset-screens (surface-or-owner &optional no-push)
  "Truncate SURFACE-OR-OWNER's stack to its root and navigate there.
Keeps the root cons, so the registered builder survives; the push
doubles as a hub refresh.  With NO-PUSH, only truncate the stack; the
caller must arrange presentation, for example by immediately pushing a
peer screen.  This avoids presenting the root during a destination switch.
The truncation remains committed if that subsequent push fails."
  (let* ((surface (jetpacs-shell--resolve-surface surface-or-owner))
         (stack (gethash surface jetpacs-chrome--stacks)))
    (when stack
      (let ((root (last stack)))
        (puthash surface root jetpacs-chrome--stacks)
        (unless no-push
          (jetpacs-chrome--push-quietly surface (caar root)))))))

(defun jetpacs-chrome-stack (surface-or-owner)
  "SURFACE's screen ids, top first, or nil (read-only)."
  (mapcar #'car (gethash (jetpacs-shell--resolve-surface surface-or-owner)
                         jetpacs-chrome--stacks)))

(defun jetpacs-chrome-remove (surface-or-owner)
  "Drop SURFACE's stack and tombstone the surface.
Also sweeps the owner's GUEST screens off foreign stacks (S4): the
live-unregister path arrives here, not through session teardown."
  (let ((surface (jetpacs-shell--resolve-surface surface-or-owner)))
    (remhash surface jetpacs-chrome--stacks)
    (remhash surface jetpacs-chrome--view-cache)
    (remhash surface jetpacs-chrome--guests)
    (when (stringp surface-or-owner)
      (unless (string-search ":" surface-or-owner)
        (jetpacs-chrome-sweep-guests surface-or-owner)))
    (jetpacs-shell-remove-root surface)))

;;;; Stack <-> device sync (the shell's view.switched, subscribed)

(defun jetpacs-chrome--on-view-switched (surface view)
  "Truncate SURFACE's stack to VIEW (a Companion-local back).
Runs INSIDE the dispatch extent under the no-prompts regime, so it is
two when-lets and nothing else — an error here would flip the whole
view.switched reply to rejected.  NO push: the device already shows the
right screen; dead upper views leave the snapshot at the next natural
push."
  (when-let* ((stack (gethash surface jetpacs-chrome--stacks)))
    (when-let* ((tail (cl-member view stack :key #'car :test #'equal)))
      (puthash surface tail jetpacs-chrome--stacks))))

(add-hook 'jetpacs-shell-view-change-functions
          #'jetpacs-chrome--on-view-switched)

;;;; The navigate drill seam (integration C7) and teardown (C8)

(defun jetpacs-chrome--drill (surface builder label)
  "The `jetpacs-navigate-drill-function' implementation.
LABEL is display text (a hostile buffer name) — the wire id is minted
\(B5), so an identical LABEL mints an identical id and the stack-insert
truncate-and-replace gives repeat-drill replace-top semantics for free.
The presenting push is DEFERRED: `jetpacs-shell-push' signals on gate
failure, and a synchronous signal inside a handler after the stack
mutated would answer rejected for an effect that happened."
  (if (null (gethash surface jetpacs-chrome--stacks))
      ;; A stackless surface — torn down, or never chrome's.  This is an
      ;; already-live signal for the PRIMARY surface after any teardown;
      ;; the corrected sweep extends it to secondaries.  The seam
      ;; contract says the host never signals: refuse with nil and let
      ;; the navigator run its documented degrade.
      (progn (message "jetpacs-chrome: no chrome stack for %s; drill \
refused" surface)
             nil)
    (jetpacs-chrome--drill-1 surface builder label)))

(defun jetpacs-chrome--drill-1 (surface builder label)
  "The live half of `jetpacs-chrome--drill' — SURFACE has a stack."
  (let* ((id (jetpacs-wire-id "drill" label))
         (undo (jetpacs-chrome--stack-insert
                surface id
                (lambda (back)
                  ;; No budget wrap HERE: `jetpacs-chrome--build' wraps the
                  ;; whole multi_view once, because SPEC 4.5 counts across
                  ;; the SurfaceSpec and the stack puts N screens in one.
                  (jetpacs-chrome-screen
                   label
                   ;; A drill hosts an arbitrary Emacs buffer, whose rendered
                   ;; height is not bounded by the viewport.  Scrolling is a
                   ;; property of this generic host, not something every
                   ;; Tier-0/Tier-1 renderer should have to remember.
                   (apply #'jetpacs-lazy-column (funcall builder))
                   :back back)))))
    (run-at-time 0 nil
                 (lambda ()
                   (condition-case err
                       (jetpacs-shell-push surface :current-view id)
                     (error
                      ;; Deferring dodged the rejected-flattening; it does
                      ;; NOT dodge the poison — the failed entry would be
                      ;; rebuilt by every later push.  Roll it back (the
                      ;; undo no-ops if navigation moved the stack since)
                      ;; and let the requeued push render the prior state.
                      (funcall undo)
                      (jetpacs-shell--schedule-repush surface)
                      (message "jetpacs-chrome: drill push of %s (view %s) \
failed: %s" surface id (jetpacs-error-label err))))))
    t))

(defvar jetpacs-navigate-drill-function)
(declare-function jetpacs-navigate-register-drill-host "jetpacs-navigate"
                  (surface fn))
(declare-function jetpacs-navigate-drill-host "jetpacs-navigate" (surface))
(defvar jetpacs-navigate--drill-hosts)

(defun jetpacs-chrome--claim-drill-host (surface)
  "Claim SURFACE's drill host for the chrome stack — ONLY when the slot
is free or already chrome's.  Re-evaluating a chrome root (the
documented live-reload path) must not clobber a Tier-1's registered
host: the guard is what makes per-surface registration deterministic
under re-evaluation, not just under require order."
  (when (fboundp 'jetpacs-navigate-register-drill-host)
    (let ((cur (gethash surface jetpacs-navigate--drill-hosts)))
      (when (or (null cur) (eq cur #'jetpacs-chrome--drill))
        (jetpacs-navigate-register-drill-host
         surface #'jetpacs-chrome--drill)))))

(with-eval-after-load 'jetpacs-navigate
  ;; The GLOBAL seam stays as the backfill for chrome roots defined
  ;; before navigate loaded — sentinel-guarded so a Tier-1's own global
  ;; host survives require order.
  (unless jetpacs-navigate-drill-function
    (setq jetpacs-navigate-drill-function #'jetpacs-chrome--drill)))

(defun jetpacs-chrome--on-teardown (_owner)
  "Drop the stack of every surface the teardown swept — stacks ONLY:
`jetpacs-teardown-owner' already tombstones via remove-root, so calling
`jetpacs-chrome-remove' here would double-tombstone.  Reads
`jetpacs-teardown-surfaces', NOT `jetpacs-shell--owner-surfaces': the
owner's claims are gone by now and recomputing sees only the D1
primary, leaking every secondary surface's stack — the leaked stack
pins builder closures and answers a later drill with false success."
  (dolist (surface jetpacs-teardown-surfaces)
    (remhash surface jetpacs-chrome--stacks)
    (remhash surface jetpacs-chrome--view-cache)))

(defun jetpacs-chrome-reset-cache ()
  "Forget every reusable composed view."
  (clrhash jetpacs-chrome--view-cache))

(add-hook 'jetpacs-teardown-functions #'jetpacs-chrome--on-teardown)

(add-hook 'jetpacs-reset-functions #'jetpacs-chrome-reset-cache)

(add-hook 'jetpacs-ready-functions #'jetpacs-chrome--on-ready)

(provide 'jetpacs-chrome)
;;; jetpacs-chrome.el ends here
