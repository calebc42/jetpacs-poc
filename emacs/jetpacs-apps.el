;;; jetpacs-apps.el --- App identity over dock-as-data chrome -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later

;; Groups owned surfaces into named apps, AppSheet-style, over the
;; chrome's dock-as-data seam (design: PLAN-poc1-parity, "The
;; app-identity design").  An app claims its surfaces and contributes
;; dock destinations in the exact `jetpacs-chrome-dock-items-function'
;; item shape; this module composes the seam, it never defines a second
;; vocabulary.
;;
;; The single-app contract (POC 1's, kept): with zero registered apps
;; the composed dock is byte-identical to the host-seeded core items;
;; with one app its destinations merge after core and nothing else
;; appears.  The Apps grid is reached from the composed drawer and
;; never consumes a persistent navigation slot.
;;
;; THIS IS THE ENTRY POINT for a Tier 1 app:
;;
;;   1. Register your surfaces under (with-jetpacs-owner "<appid>" ...).
;;   2. Finish with `jetpacs-defapp' claiming them and declaring your
;;      dock destinations.
;;
;; One broken app costs its own destinations, never the dock: each
;; app's item builder runs under its own condition-case — the same
;; isolation the session-hook blanking bug taught.

;;; Code:

(require 'cl-lib)
(require 'jetpacs-widgets)
(require 'jetpacs-surfaces)
(require 'jetpacs-shell)
(require 'jetpacs-chrome)

(defconst jetpacs-apps-surface "jetpacs.apps"
  "The Apps grid's root surface (owner and surface name).")

(defvar jetpacs-apps-core-dock-items nil
  "The host's own dock destinations: a function (SURFACE) -> items.
Seeded by the device init (which used to set the chrome seam
directly); these render in EVERY app — the dock-as-data restatement of
POC 1's \"views not claimed by any app show everywhere\".  An item may
carry a stable string `:key' for selective relocation by an
APP-PRIMARY app; chrome itself ignores that metadata.")

(defvar jetpacs-apps--registry nil
  "Ordered alist of APP-ID -> plist
\(:label :icon :surfaces :dock :destinations :home-route :fab :chrome :dock-core
 :drawer-core :requires-extensions :order).
:dock is a list of dock item plists or a function (SURFACE) -> items;
:destinations is the S1 route registry; :home-route optionally names the
destination represented by the app's root surface; :fab is the app-default
FAB; `:dock-core' and `:drawer-core' parameterize the APP-PRIMARY pole; and
:chrome is the integration pole — see `jetpacs-defapp'.")

(defvar jetpacs-apps--current nil
  "The current app's id, or nil before `app.open' or an explicit seed.")

(defvar jetpacs-apps--current-route nil
  "The current app's last-opened destination key, or nil.
Written by `app.open', `jetpacs-apps-seed-current', and
`jetpacs-apps-note-route' — set by a routed open or an app's own
navigation, cleared by a plain/reset open — so the app-primary
navigation-bar entries can indicate the selected place (the M3
navigation-bar contract).")

(defvar jetpacs-apps--unavailable nil
  "The last unavailable app selection as (APP-ID . MISSING-EXTENSIONS).
The Apps surface consumes this to explain why it refused to render an app
instead of letting its builders emit extension nodes into an incompatible
receiver profile.")

;;;; App-surface refresh

(defun jetpacs-app-defer-refresh (&optional params)
  "Defer a re-push of PARAMS' or the current flow's app surface.
PARAMS is an `event.action' plist and may be nil.  An explicit
`:surface' wins; otherwise `jetpacs-flow-surface' supplies the
device-flow origin.  If neither exists, the deferred call preserves
the flow's owner and `jetpacs-shell-push' applies its normal D1
default.

The push is deliberately outside the dispatch extent (D2), and a
render refusal is presentation failure after the handler's effect —
it must not escape a timer and rewrite an already-returned status."
  (let ((surface (or (plist-get params :surface)
                     (jetpacs-flow-surface))))
    (jetpacs-flow-continue
     (lambda ()
       (ignore-errors (jetpacs-shell-push surface))))))

;;;; Registry

(defun jetpacs-apps--check-destination-list (dests)
  "Signal unless DESTS is a proper list of valid destinations; return it.
Each destination is a plist with string `:key' (a §4.4 identifier — it
rides `app.open''s wire args), string `:label', a DOTTED namespaced
`:verb' (the `jetpacs-action' rule: a dotless verb can never have a
registered handler, so every tap would silently degrade to the home
push), optional string `:icon'/`:subtitle'/`:open-surface'; the latter is
the app Surface the receiver must present when the destination deliberately
publishes outside the app's home Surface.  Keys are distinct within the app.
`proper-list-p' first: the reader trusts this checker to make a
resolved value safe to MAP, and a function or circular cell is not."
  (unless (proper-list-p dests)
    (error "jetpacs-defapp: :destinations must be a proper list, got %S"
           dests))
  (let (keys)
    (dolist (d dests)
      (unless (and (listp d) (keywordp (car-safe d)))
        (error "jetpacs-defapp: destination must be a plist, got %S" d))
      (jetpacs-check-identifier (plist-get d :key) "destination :key")
      (jetpacs-require-string (plist-get d :label) "destination :label")
      (let ((verb (plist-get d :verb)))
        (unless (and (jetpacs-identifier-p verb) (string-search "." verb))
          (error "jetpacs-defapp: destination :verb %S must be a §4.4 namespaced identifier containing a dot"
                 verb)))
      (when-let* ((icon (plist-get d :icon)))
        (jetpacs-check-identifier icon "destination :icon"))
      (when-let* ((sub (plist-get d :subtitle)))
        (jetpacs-require-string sub "destination :subtitle"))
      (when-let* ((surface (plist-get d :open-surface)))
        ;; Reuse the wire constructor as the single app-Surface validator.
        ;; It returns a node that is intentionally discarded here.
        (jetpacs-surface-open surface))
      (when (plist-member d :badge)
        (let ((badge (plist-get d :badge)))
          (unless (or (null badge) (stringp badge) (functionp badge))
            (error "jetpacs-defapp: destination :badge %S must be a string or nullary function"
                   badge))))
      (when (and (plist-member d :bar)
                 (not (memq (plist-get d :bar) '(nil t))))
        (error "jetpacs-defapp: destination :bar must be t or nil, got %S"
               (plist-get d :bar)))
      (when (member (plist-get d :key) keys)
        (error "jetpacs-defapp: duplicate destination key %S"
               (plist-get d :key)))
      (push (plist-get d :key) keys)))
  dests)

(defun jetpacs-apps--check-destinations (dests)
  "Signal unless DESTS is a valid `:destinations' value; return it.
A function is deferred trust (its RESULT is checked per read,
isolated, by `jetpacs-apps-destinations'); a list is checked NOW — the
build-time-validation house rule."
  (if (functionp dests)
      dests
    (jetpacs-apps--check-destination-list dests)))

(defun jetpacs-apps--check-home-route (route destinations)
  "Signal unless ROUTE is a valid home key for DESTINATIONS; return ROUTE.
Nil means the app has no destination corresponding to its root surface.  A
literal destination table is checked eagerly.  A function table keeps the
same deferred-trust contract as `jetpacs-apps-destinations'; its home key is
checked against each resolved result before it is used."
  (when route
    (jetpacs-check-identifier route ":home-route")
    (when (and (not (functionp destinations))
               (not (cl-find route destinations
                             :key (lambda (dest) (plist-get dest :key))
                             :test #'equal)))
      (error "jetpacs-defapp: :home-route %S is not a destination key"
             route)))
  route)

(defun jetpacs-apps--check-drawer-core (keys)
  "Signal unless KEYS is a proper list of distinct core identifiers.
Return KEYS.  The identifiers name stable `:key' metadata on items
returned by `jetpacs-apps-core-dock-items'."
  (unless (proper-list-p keys)
    (error "jetpacs-defapp: :drawer-core must be a proper list, got %S"
           keys))
  (let (seen)
    (dolist (key keys)
      (jetpacs-check-identifier key ":drawer-core entry")
      (when (member key seen)
        (error "jetpacs-defapp: duplicate :drawer-core key %S" key))
      (push key seen)))
  keys)

(defun jetpacs-apps--check-required-extensions (extensions)
  "Validate and return app renderer EXTENSIONS.
The value is a proper list of distinct, namespaced §4.4 identifiers."
  (unless (proper-list-p extensions)
    (error "jetpacs-defapp: :requires-extensions must be a proper list, got %S"
           extensions))
  (let (seen)
    (dolist (extension extensions)
      (jetpacs-check-identifier extension ":requires-extensions entry")
      (unless (string-search "." extension)
        (error "jetpacs-defapp: renderer extension %S must be namespaced"
               extension))
      (when (member extension seen)
        (error "jetpacs-defapp: duplicate renderer extension %S" extension))
      (push extension seen)))
  extensions)

(cl-defun jetpacs-defapp (id &key label icon surfaces dock destinations
                             home-route fab
                             chrome (dock-core t) drawer-core
                             requires-extensions (order 100))
  "Register (or replace) app ID.
LABEL and ICON draw its Apps-grid card; SURFACES is the list of surface
names it claims (the first is its home); DOCK is its destinations —
item plists in the chrome seam's shape, or a function of the surface.

DESTINATIONS is the S1 route registry (CHROME-VOCABULARY v4, the
build-within pole; poc-1's `:views' restored onto chrome screens): a
list of plists
  (:key :label :verb [:icon :subtitle :badge :bar :open-surface])
— or
a function of no arguments returning one — naming the screens the app
offers the HOST.  A destination's optional BADGE is a string or a
nullary function returning a string/nil; it is resolved when the bar
is built.  Optional BAR is boolean and defaults to t; nil keeps the
destination in the drawer/deep-link registry but excludes it from the
persistent bar.  Each destination is opened via the global `app.open'
with `:route KEY', which re-dispatches the destination's VERB on the
app's own home surface — so the verb stays owner-scoped and no
`:any-surface' declaration is ever needed for a host-side row.

HOME-ROUTE optionally names the destination represented by the app's home
surface.  Jetpacs uses it only when a stale, missing, refusing, or signaling
deep link falls back to that surface, so persistent chrome describes the
screen actually shown.  The app owns this policy; nil preserves an unselected
root.  Literal destination tables validate membership now, while function
tables validate it when resolved.

FAB is a typed node, or a function (SURFACE) returning one, used as
the app's default creation action on its own screens.  A screen's
authored `:fab' wins.  The result is resolved per screen through
`jetpacs-apps-default-fab', isolated, and never crosses onto a surface
the app does not claim.

CHROME is the app's integration pole (CHROME-VOCABULARY v4):
nil (default) composes into the shell as today; `primary' makes the
dock APP-PRIMARY while this app is current.  DOCK-CORE defaults to t;
nil lets the app's DESTINATIONS own all five M3 bar slots.  DRAWER-CORE
is a list of stable host-core item keys to relocate into the composed
drawer while core is suppressed.  These two options are valid only
for the `primary' pole, and DRAWER-CORE is meaningful only when
DOCK-CORE is nil.  Apps always remains in the drawer;
`standalone' withdraws the core dock items and the global-actions
injection for the app's OWN surfaces and keeps the app's items off
foreign ones — the app authors its chrome whole.

REQUIRES-EXTENSIONS is a list of namespaced renderer-extension identifiers.
A live Companion must advertise every requirement in its app profile before
Jetpacs will enter or build the app; otherwise the Apps surface explains the
missing renderer contract.  Offline builders retain the richer-form behavior.
Returns ID."
  (unless (and (stringp id) (not (string-empty-p id)))
    (error "jetpacs-defapp: id must be a non-empty string"))
  (unless (memq chrome '(nil standalone primary))
    (error "jetpacs-defapp: :chrome must be nil, standalone, or primary, got %S"
           chrome))
  (unless (memq dock-core '(nil t))
    (error "jetpacs-defapp: :dock-core must be t or nil, got %S"
           dock-core))
  (jetpacs-apps--check-drawer-core drawer-core)
  (jetpacs-apps--check-required-extensions requires-extensions)
  (when (and (null dock-core) (not (eq chrome 'primary)))
    (error "jetpacs-defapp: :dock-core nil requires :chrome 'primary"))
  (when (and drawer-core
             (not (and (eq chrome 'primary) (null dock-core))))
    (error "jetpacs-defapp: :drawer-core requires :chrome 'primary and :dock-core nil"))
  (when destinations (jetpacs-apps--check-destinations destinations))
  (jetpacs-apps--check-home-route home-route destinations)
  (when (and fab (not (functionp fab))
             (not (jetpacs-root-node-p fab)))
    (error "jetpacs-defapp: :fab must be a typed node or function, got %S"
           fab))
  (setf (alist-get id jetpacs-apps--registry nil nil #'equal)
        (list :label (or label id) :icon (or icon "apps")
              :surfaces surfaces :dock dock
              :destinations destinations :home-route home-route :fab fab
              :chrome chrome :dock-core dock-core
              :drawer-core drawer-core
              :requires-extensions requires-extensions :order order))
  (setq jetpacs-apps--registry
        (sort jetpacs-apps--registry
              (lambda (a b) (< (plist-get (cdr a) :order)
                               (plist-get (cdr b) :order)))))
  id)

(defun jetpacs-apps-required-extensions (id)
  "Return app ID's declared renderer-extension requirements.
The returned list is a copy so callers cannot mutate the registry."
  (copy-sequence
   (plist-get (cdr (assoc id jetpacs-apps--registry))
              :requires-extensions)))

(defun jetpacs-apps-missing-extensions (id &optional target)
  "Return app ID's renderer extensions absent from TARGET's live profile.
TARGET defaults to `:app'.  With no attached client the ordinary offline
richer-form convention makes the result nil."
  (cl-remove-if (lambda (extension)
                  (jetpacs-extension-advertised-p extension target))
                (jetpacs-apps-required-extensions id)))

(defun jetpacs-apps-available-p (id &optional target)
  "Non-nil when app ID's renderer requirements are available for TARGET."
  (null (jetpacs-apps-missing-extensions id target)))

(defun jetpacs-apps-destinations (id)
  "App ID's destination list, resolved and isolated.
The function form is called here and its RESULT goes through the
LIST-ONLY checker — deferred trust is still checked trust, and a
function returning another function (or an improper list) must not
slip past a `functionp' fast path into a caller's `mapcar'.  A signal
or malformed value costs this app's destinations only, never a
caller."
  (when-let* ((entry (assoc id jetpacs-apps--registry)))
    (let ((dests (plist-get (cdr entry) :destinations)))
      (condition-case nil
          (jetpacs-apps--check-destination-list
           (if (functionp dests) (funcall dests) dests))
        (error nil)))))

(defun jetpacs-apps--resolved-home-route (entry destinations)
  "Return ENTRY's home route when it exists in resolved DESTINATIONS.
Dynamic destination providers are isolated by `jetpacs-apps-destinations'; a
provider that drops or malforms its declared home leaves the root unselected."
  (let ((route (plist-get (cdr entry) :home-route)))
    (and route
         (cl-find route destinations
                  :key (lambda (dest) (plist-get dest :key))
                  :test #'equal)
         route)))

(defun jetpacs-apps-unregister (id)
  "Remove app ID; the current app falls back to none."
  (setf (alist-get id jetpacs-apps--registry nil 'remove #'equal) nil)
  (when (equal jetpacs-apps--current id)
    (setq jetpacs-apps--current nil
          jetpacs-apps--current-route nil)))

(defun jetpacs-apps-current ()
  "The current app's registry entry (ID . PLIST), or nil.
Defaults to the sole registered app when only one exists."
  (or (and jetpacs-apps--current
           (assoc jetpacs-apps--current jetpacs-apps--registry))
      (and (= (length jetpacs-apps--registry) 1)
           (car jetpacs-apps--registry))))

(defun jetpacs-apps-seed-current (id &optional route)
  "Seed current app ID and optional destination ROUTE without navigating.
This is the READY-time counterpart of `app.open': it establishes
honest APP-PRIMARY selection before the first device event but neither
dispatches a destination verb nor pushes a surface.  ID and ROUTE must
already exist in the registry; configuration mistakes fail eagerly.
Return ID."
  (unless (and (stringp id) (assoc id jetpacs-apps--registry))
    (error "jetpacs-apps-seed-current: unknown app %S" id))
  (when (and route
             (not (and (stringp route)
                       (cl-find route (jetpacs-apps-destinations id)
                                :key (lambda (dest)
                                       (plist-get dest :key))
                                :test #'equal))))
    (error "jetpacs-apps-seed-current: unknown route %S for app %S"
           route id))
  (setq jetpacs-apps--current id
        jetpacs-apps--current-route route)
  id)

(defun jetpacs-apps-open-seeded ()
  "Open the explicitly seeded app and route; non-nil when one was scheduled.
Only `jetpacs-apps--current' counts: the sole-app fallback returned by
`jetpacs-apps-current' is discovery convenience, not a request to replace the
Jetpacs ready landing.  The normal `app.open' path performs the navigation, so
route dispatch keeps its D1 handoff, refusal fallback, and D2 deferral.  A
dynamic destination which vanished after seeding still counts as handled:
`app.open' schedules the app's stable home and returns `stale'."
  (when (and (stringp jetpacs-apps--current)
             (assoc jetpacs-apps--current jetpacs-apps--registry))
    (let ((status
           (jetpacs-apps--action-open
            (append (list :app jetpacs-apps--current)
                    (and jetpacs-apps--current-route
                         (list :route jetpacs-apps--current-route)))
            nil)))
      (memq status '(accepted stale)))))

(defun jetpacs-apps-note-route (id route)
  "Record app ID's current destination ROUTE without navigating.
ROUTE is a registered destination key, or nil to clear the selection
when ID resets to a routeless root.  This is the runtime counterpart
of `jetpacs-apps-seed-current': app-owned verbs and companion-local
Back handlers use it when navigation did not enter through `app.open'.

ID and a non-nil ROUTE must already exist in the registry.  Return
non-nil only when the selected app/route pair actually changed, so a
caller can bound any presentation refresh it schedules."
  (unless (and (stringp id) (assoc id jetpacs-apps--registry))
    (error "jetpacs-apps-note-route: unknown app %S" id))
  (when (and route
             (not (and (stringp route)
                       (cl-find route (jetpacs-apps-destinations id)
                                :key (lambda (dest)
                                       (plist-get dest :key))
                                :test #'equal))))
    (error "jetpacs-apps-note-route: unknown route %S for app %S"
           route id))
  (let ((changed (not (and (equal jetpacs-apps--current id)
                           (equal jetpacs-apps--current-route route)))))
    (setq jetpacs-apps--current id
          jetpacs-apps--current-route route)
    changed))

(defun jetpacs-apps--home-surface (entry)
  (car (plist-get (cdr entry) :surfaces)))

(defun jetpacs-apps--home-surface-id (entry)
  "Return ENTRY's home as a fully-qualified app Surface ID."
  (when-let* ((home (jetpacs-apps--home-surface entry)))
    (if (string-search ":" home) home
      (jetpacs-shell-surface-for home))))

(defun jetpacs-apps--app-open-action (id &optional route)
  "Build the global app-open action for ID and optional ROUTE.
On a Companion advertising `action.open_surface', the same explicit gesture
also presents the destination's declared `:open-surface', or the app's cached
home by default, through the receiver-local Nav3 shell.  The remote action
still updates Emacs app/route state and refreshes content."
  (let* ((entry (assoc id jetpacs-apps--registry))
         (home (and entry (jetpacs-apps--home-surface-id entry)))
         (destination
          (and route entry
               (cl-find route (jetpacs-apps-destinations id)
                        :key (lambda (dest) (plist-get dest :key))
                        :test #'equal)))
         (target (or (plist-get destination :open-surface) home)))
    (jetpacs-action
     "app.open"
     :args (append (list :app id) (and route (list :route route)))
     :open-surface (and target
                        (jetpacs-feature-advertised-p
                         "action.open_surface" :app)
                        target)
     :when-offline "drop")))

(defun jetpacs-apps--with-home-open (item home)
  "Copy ITEM and attach HOME navigation to its remote tap when supported.
An app-authored `:open_surface' is left intact; builtin taps remain local and
unchanged.  This is the generic seam that makes an app's contributed dock work
even while its rail is being rendered on the Apps or another host surface."
  (let ((tap (plist-get item :on-tap)))
    (if (and home
             (consp tap)
             (plist-member tap :action)
             (not (plist-member tap :open_surface))
             (jetpacs-feature-advertised-p "action.open_surface" :app))
        (let ((copy (copy-sequence item))
              (tap-copy (copy-sequence tap)))
          (setq tap-copy (plist-put tap-copy :open_surface home))
          (plist-put copy :on-tap tap-copy))
      item)))

(defun jetpacs-apps--entry-owns-surface-p (entry surface)
  "Non-nil when SURFACE (a full id) is one of ENTRY's claimed surfaces.
Colon-aware on both sides, mirroring the flow resolver."
  (cl-some (lambda (owner)
             (equal surface
                    (if (string-search ":" owner) owner
                      (jetpacs-shell-surface-for owner))))
           (plist-get (cdr entry) :surfaces)))

(defun jetpacs-apps-default-fab (screen-owner surface)
  "SCREEN-OWNER's registered default FAB for SURFACE, or nil.
The app id is its owner id.  Both identities must agree: the screen
must belong to the app, and SURFACE must be one of that app's declared
surfaces.  Consequently an app screen presented as an S4 guest on a
foreign surface gets no default, and a foreign guest on the app's own
surface can never inherit the host app's FAB.

A function-valued `:fab' receives SURFACE.  Signals and malformed
results cost only the default; a screen's own node remains renderable."
  (when-let* ((entry (and (stringp screen-owner)
                          (assoc screen-owner jetpacs-apps--registry)))
              ((jetpacs-apps--entry-owns-surface-p entry surface))
              (fab (plist-get (cdr entry) :fab)))
    (condition-case nil
        (let ((node (if (functionp fab) (funcall fab surface) fab)))
          (and (jetpacs-root-node-p node) node))
      (error nil))))

(defun jetpacs-apps-for-surface (surface)
  "The registry entry (ID . PLIST) of the app claiming SURFACE, or nil.
THE public identity read: the launcher takes a claimed surface's
label and icon from here — one enumeration, the registry — instead
of keeping a second vocabulary for surfaces that already have an
app.  Platform surfaces (not apps by owner decision) return nil and
keep their module-seeded launcher identity."
  (cl-loop for entry in jetpacs-apps--registry
           when (jetpacs-apps--entry-owns-surface-p entry surface)
           return entry))

(defun jetpacs-apps-home-for-surface (surface)
  "The registry entry (ID . PLIST) whose HOME surface is SURFACE, or nil.
The launcher's identity read: an entry names the APP, not the
surface, so only the app's home wears its label and icon — a claimed
SECONDARY surface (Org Mode claims the Files surface) keeps its own
platform identity.  Colon-aware like the ownership check."
  (cl-loop for entry in jetpacs-apps--registry
           for home = (jetpacs-apps--home-surface entry)
           ;; A registry SCAN, not a filter of the first ownership hit:
           ;; app A claiming SURFACE as a secondary must not shadow app
           ;; B whose home it is (registry order is :order, not claims).
           when (and home
                     (equal surface (if (string-search ":" home) home
                                      (jetpacs-shell-surface-for home))))
           return entry))

(defun jetpacs-apps--surface-chrome (surface)
  "The integration pole of the app owning SURFACE, or nil.
nil for host surfaces and for build-within apps alike — only a
declared pole changes composition."
  (plist-get (cdr (jetpacs-apps-for-surface surface)) :chrome))

;;;; The composed dock

(defun jetpacs-apps--core-items (surface)
  "The host core items for SURFACE, resolved and isolated.
A signalling or malformed core builder costs the core contribution,
never the rest of the app navigation."
  (when jetpacs-apps-core-dock-items
    (condition-case nil
        (let ((items (funcall jetpacs-apps-core-dock-items surface)))
          (and (proper-list-p items)
               (cl-every (lambda (item)
                           (and (listp item) (plist-get item :label)))
                         items)
               items))
      (error nil))))

(defun jetpacs-apps--app-items (entry surface)
  "ENTRY's dock destinations for SURFACE, isolated: a signal or a
malformed result costs this app's items only."
  (condition-case nil
      (let* ((dock (plist-get (cdr entry) :dock))
             (items (if (functionp dock) (funcall dock surface) dock))
             (home (jetpacs-apps--home-surface-id entry)))
        (and (listp items)
             (cl-every (lambda (i) (and (listp i) (plist-get i :label)))
                       items)
             (mapcar (lambda (item)
                       (jetpacs-apps--with-home-open item home))
                     items)))
    (error nil)))

(defun jetpacs-apps--destination-bar-items (entry &optional limit)
  "ENTRY's destinations as navigation-bar items — the S2 primary form.
Only destinations whose optional `:bar' is not nil participate;
drawer-only destinations remain in the S1 registry.  Each item
deep-links through the global `app.open' `:route' (the S1 mechanism
powering S2), capped at LIMIT (five by default) to respect the M3
budget; `:selected' follows the route this verb last opened."
  (pcase-let ((`(,id . ,_plist) entry))
    (mapcar (lambda (d)
              (list :label (plist-get d :label)
                    :icon (or (plist-get d :icon) "circle")
                    :badge (let ((badge (plist-get d :badge)))
                             (condition-case nil
                                 (let ((value (if (functionp badge)
                                                  (funcall badge)
                                                badge)))
                                   (and (stringp value) value))
                               (error nil)))
                    :on-tap (jetpacs-apps--app-open-action
                             id (plist-get d :key))
                    :selected (and (equal id jetpacs-apps--current)
                                   (equal (plist-get d :key)
                                          jetpacs-apps--current-route))))
            (seq-take
             (cl-remove-if
              (lambda (d)
                (and (plist-member d :bar) (null (plist-get d :bar))))
              (jetpacs-apps-destinations id))
             (or limit 5)))))

(defun jetpacs-apps-dock-items (surface)
  "THE `jetpacs-chrome-dock-items-function', by integration pole.
Default (build-within, no declared pole): core + current app, with the
Apps switcher living only in the drawer.  With fewer than two
registered apps this is the original single-app contract.  STANDALONE:
on the app's own surfaces only its authored `:dock' items ship (none
authored: no dock at all — the app's chrome is its own); on foreign
surfaces a standalone current app contributes NOTHING.  PRIMARY while
current: core remains in the bar by default, or `:dock-core nil' lets
the app's `:bar'-eligible destinations fill all five slots; Apps stays
in the drawer."
  (let ((surface-pole (jetpacs-apps--surface-chrome surface))
        (entry (jetpacs-apps-current)))
    (cond
     ;; A standalone app's OWN surface: the app authors its chrome
     ;; whole (CHROME-VOCABULARY v3, the ratified withdrawal).
     ((eq surface-pole 'standalone)
      (and entry (jetpacs-apps--app-items entry surface)))
     ;; The current app is PRIMARY: retain core by default, or let a
     ;; full-bar declaration suppress it.  App destinations fill the
     ;; remaining slots in Material's five-item budget.
     ((and entry (eq (plist-get (cdr entry) :chrome) 'primary))
      (let* ((core (and (plist-get (cdr entry) :dock-core)
                        (jetpacs-apps--core-items surface)))
             (room (max 0 (- 5 (length core)))))
        (append core (jetpacs-apps--destination-bar-items entry room))))
     ;; Build-within default.
     (t
      (append
       (jetpacs-apps--core-items surface)
       ;; A standalone CURRENT app keeps its items off foreign
       ;; surfaces — they are its own chrome, not a contribution.
       (when (and entry
                  (not (eq (plist-get (cdr entry) :chrome) 'standalone)))
         (jetpacs-apps--app-items entry surface)))))))

;;;; The global-actions wrapper (S3, standalone-aware)

(defvar jetpacs-apps-core-global-actions nil
  "The host's shell-global top-bar actions: a function (SURFACE) -> nodes.
Seeded by the device init (M-x, canonically); rendered into every
screen through `jetpacs-chrome-global-actions-function' — except on a
standalone app's surfaces, where the ratified withdrawal applies.")

(defun jetpacs-apps-global-actions (surface)
  "THE `jetpacs-chrome-global-actions-function': the host seed, minus
standalone surfaces (CHROME-VOCABULARY v3: `:chrome' `standalone'
withdraws the global-actions injection for the app's own surfaces)."
  (unless (eq (jetpacs-apps--surface-chrome surface) 'standalone)
    (when jetpacs-apps-core-global-actions
      (condition-case nil
          (funcall jetpacs-apps-core-global-actions surface)
        (error nil)))))

;;;; The global-items wrapper (S10, standalone-aware)

(defvar jetpacs-apps-core-global-items nil
  "The host's shell globals as DATA: a function (SURFACE) -> item plists.
Seeded by the device init with the same M-x the node seam seeds, in
the shape `jetpacs-chrome-global-items-function' can re-author per
`jetpacs-chrome-global-actions-placement' — which is why the device
carries both seeds and the data one wins.")

(defun jetpacs-apps-global-items (surface)
  "THE `jetpacs-chrome-global-items-function': the host seed, minus
standalone surfaces.  The S3 withdrawal is about WHETHER a standalone
app wears the shell's globals, and placement only moves WHERE they
ride — so the ratified rule applies here unchanged."
  (unless (eq (jetpacs-apps--surface-chrome surface) 'standalone)
    (when jetpacs-apps-core-global-items
      (condition-case nil
          (funcall jetpacs-apps-core-global-items surface)
        (error nil)))))

;;;; The composed drawer (S8, standalone-aware)

(defvar jetpacs-apps-core-drawer-rows nil
  "The host's own drawer rows: a function (SURFACE) -> a list of nodes.
Seeded by the device init (the Tools nest, the divider, and the
Settings nest, canonically — the pass-4 IA tail); composed BELOW the
app-identity head — the Apps row and the destination nests — by
`jetpacs-apps-drawer'.  Isolated: a signal or a non-list costs the
host rows, never the drawer.")

(defun jetpacs-apps--relocated-core-rows (surface)
  "Core rows the current APP-PRIMARY app selects for the drawer.
Selection is by stable `:key' on the host's dock-item data.  Requested
keys retain app-declared order; an unknown key is ignored so a host
upgrade cannot take down the drawer.  This is generic composition:
the app owns the placement opinion, while Jetpacs knows only keys."
  (when-let* ((entry (jetpacs-apps-current))
              (plist (cdr entry))
              ((eq (plist-get plist :chrome) 'primary))
              ((null (plist-get plist :dock-core)))
              (keys (plist-get plist :drawer-core))
              (items (jetpacs-apps--core-items surface)))
    (delq
     nil
     (mapcar
      (lambda (key)
        (when-let* ((item (cl-find key items
                                   :key (lambda (candidate)
                                          (plist-get candidate :key))
                                   :test #'equal)))
          (jetpacs-chrome-row
           (plist-get item :label)
           :icon (plist-get item :icon)
           :on-tap (plist-get item :on-tap)
           :key (jetpacs-wire-id "core" key))))
      keys))))

(defun jetpacs-apps-drawer (surface)
  "THE `jetpacs-chrome-drawer-function': the composed host drawer.
The head is the app-identity half — the Apps entry and one S1
destination nest per registered app — followed by any core rows the
current full-bar APP-PRIMARY app selected with `:drawer-core'.  The
tail is the host's own rows (`jetpacs-apps-core-drawer-rows').  On the
hub this composes exactly the drawer its root used to author by hand;
on every other build-within root it is the SAME drawer, which is the
point — the canonical navigation list no longer depends on which
screen the user is standing on.  STANDALONE withdraws whole: the app
authors its chrome, drawer included (CHROME-VOCABULARY v4)."
  (unless (eq (jetpacs-apps--surface-chrome surface) 'standalone)
    (apply #'jetpacs-lazy-column
           (append
            (list (jetpacs-apps-drawer-row))
            (jetpacs-apps-destination-rows)
            (jetpacs-apps--relocated-core-rows surface)
            (when jetpacs-apps-core-drawer-rows
              (condition-case nil
                  (let ((rows (funcall jetpacs-apps-core-drawer-rows
                                       surface)))
                    (and (listp rows) (cl-every #'jetpacs-node-p rows)
                         rows))
                (error nil)))
            (list :spacing 8)))))

;;;; The Apps grid

(defun jetpacs-apps--card (entry)
  (pcase-let ((`(,id . ,plist) entry))
    (let ((missing (jetpacs-apps-missing-extensions id :app)))
      (jetpacs-chrome-row
       (plist-get plist :label)
       :subtitle (if missing
                     (format "Unavailable: requires %s"
                             (string-join missing ", "))
                   (jetpacs-apps--home-surface entry))
       :icon (plist-get plist :icon)
       :trailing (cond
                  (missing (jetpacs-icon "warning" :color "warning"))
                  ((equal id (car (jetpacs-apps-current)))
                   (jetpacs-icon "check_circle" :color "primary"))
                  (t (jetpacs-icon "chevron_right")))
       :on-tap (jetpacs-apps--app-open-action id)
       :key (jetpacs-wire-id "ap" id)))))

(defun jetpacs-apps-unavailable-view ()
  "Return an explanatory screen for `jetpacs-apps--unavailable', or nil."
  (when jetpacs-apps--unavailable
    (pcase-let* ((`(,id . ,missing) jetpacs-apps--unavailable)
                 (entry (assoc id jetpacs-apps--registry))
                 (label (or (plist-get (cdr entry) :label) id)))
      (jetpacs-chrome-screen
       "App unavailable"
       (jetpacs-column
        (jetpacs-empty-state
         :icon "warning"
         :title (format "%s cannot run on this renderer" label)
         :caption (format "Missing renderer extension%s: %s"
                          (if (= (length missing) 1) "" "s")
                          (string-join missing ", ")))
        (jetpacs-button
         "Back to Apps"
         (jetpacs-action "app.unavailable.dismiss")))))))

(defun jetpacs-apps--view ()
  (jetpacs-chrome-screen
   "Apps"
   (apply #'jetpacs-lazy-column
          (if (null jetpacs-apps--registry)
              (list (jetpacs-empty-state
                     :icon "apps" :title "No apps registered"
                     :caption "Apps appear here as their bundles load."))
            (mapcar #'jetpacs-apps--card jetpacs-apps--registry)))))

;;;; The drawer's Apps entry

(defun jetpacs-apps-drawer-row ()
  "The drawer's single Apps entry (owner decision 2026-08-06 pass 2):
one plain row opening the combined Apps view, where installing,
removing, editing, and launching all live.  An App is a Tier 1 elisp
package built ON jetpacs — platform surfaces are not apps and are not
listed there."
  (jetpacs-chrome-row "Apps"
                      :subtitle "Install, manage, and launch"
                      :icon "apps"
                      :on-tap (jetpacs-shell-open-surface-action
                               "app:jetpacs.app-store")
                      :key "drawer-apps"))

(defun jetpacs-apps--destination-row (id dest)
  "One host-drawer row for app ID's destination DEST.
The tap is the global `app.open' with the route — never the
destination's own verb, which is owner-scoped and would be refused on
the host surface (the S1 point)."
  (jetpacs-chrome-row (plist-get dest :label)
                      :subtitle (plist-get dest :subtitle)
                      :icon (or (plist-get dest :icon) "chevron_right")
                      :on-tap (jetpacs-apps--app-open-action
                               id (plist-get dest :key))
                      :key (jetpacs-wire-id
                            "apd" (concat id "/" (plist-get dest :key)))))

(defun jetpacs-apps-destination-rows ()
  "Every registered app's destinations as host-drawer nests.
The S1 consumption seam (CHROME-VOCABULARY v3, build-within): the HOST
composes what apps CONTRIBUTE — poc-1's claimed-views drawer restored.
One collapsible per app that declares destinations (collapsed by
default, the settings-nest precedent — a plain row header so the whole
line is the expand target); apps without them cost nothing, and a
broken destination list costs that app's nest alone
\(`jetpacs-apps-destinations' isolates)."
  (delq nil
        (mapcar
         (lambda (entry)
           (pcase-let ((`(,id . ,plist) entry))
             (when-let* ((dests (jetpacs-apps-destinations id)))
               (apply #'jetpacs-collapsible
                      (jetpacs-wire-id "apn" id)
                      (jetpacs-row
                       (jetpacs-icon (plist-get plist :icon))
                       (jetpacs-with-attrs
                        (jetpacs-text (plist-get plist :label))
                        :weight 1))
                      (append
                       (mapcar (lambda (d)
                                 (jetpacs-apps--destination-row id d))
                               dests)
                       ;; Collapsed by default — the settings/tools
                       ;; nest precedent, EXPLICIT: collapsible's own
                       ;; default is expanded.
                       (list :collapsed t))))))
         jetpacs-apps--registry)))

;;;; Actions

(defun jetpacs-apps--action-grid (_args _params)
  ;; The drawer's Apps row lands on the combined Apps view (the
  ;; app-store surface) — the old grid folded into it (pass 2).
  (setq jetpacs-apps--unavailable nil)
  (jetpacs-flow-continue
   (lambda ()
     (ignore-errors (jetpacs-shell-push "jetpacs.app-store"))))
  'accepted)

(defun jetpacs-apps--action-dismiss-unavailable (_args _params)
  "Clear the extension refusal and return to the ordinary Apps view."
  (setq jetpacs-apps--unavailable nil)
  (jetpacs-flow-continue
   (lambda () (ignore-errors (jetpacs-shell-push "jetpacs.app-store"))))
  'accepted)

(defun jetpacs-apps--action-open (args _params)
  "Open app `:app'; with `:route', open one of its DESTINATIONS.
The S1 deep link: the tapping row lives on a HOST surface the app does
not own, but this verb is ownerless (gate-exempt), and the route's
verb is re-dispatched through `jetpacs--dispatch' with the app's OWN
home surface as the event surface — the D1 gate passes because the
context is genuinely the app's, which is what retires the
`:any-surface' workaround for host-side rows.  A vanished route is
`stale' (the row outlived the registry it was rendered from) but still
opens the app's plain home, as does a route whose handler refuses — a
dead deep link must never strand an obsolete host screen."
  (let* ((id (plist-get args :app))
         (entry (and (stringp id) (assoc id jetpacs-apps--registry)))
         (route (plist-get args :route)))
    (cond
     ((null entry) 'rejected)
     ((and route (not (stringp route))) 'rejected)
     ((when-let* ((missing (jetpacs-apps-missing-extensions id :app)))
        (setq jetpacs-apps--unavailable (cons id missing))
        (jetpacs-flow-continue
         (lambda ()
           (ignore-errors (jetpacs-shell-push "jetpacs.app-store"))))
        t)
      'accepted)
     (t
      (let* ((destinations (jetpacs-apps-destinations id))
             (dest (and route
                        (cl-find route destinations
                                 :key (lambda (d) (plist-get d :key))
                                 :test #'equal)))
             (home (jetpacs-apps--home-surface entry))
             (home-route
              (jetpacs-apps--resolved-home-route entry destinations))
             (fallback
              (lambda ()
                ;; A deferred open can be overtaken by a later app switch.
                ;; In that case its push still belongs to this intent, but it
                ;; must not rewrite the newer app's selection state.
                (when (equal jetpacs-apps--current id)
                  (setq jetpacs-apps--current-route home-route))
                (ignore-errors
                  (jetpacs-shell-push
                   (or home "jetpacs.app-store"))))))
        (if (and route (null dest))
            (progn
              ;; Preserve the stale receipt — the tapped row really did
              ;; outlive its registry — while satisfying S1's navigation
              ;; contract: the app still opens at its stable home.  This
              ;; must be deferred for the same D2 reason as every other
              ;; push in this handler.
              (setq jetpacs-apps--current id
                    jetpacs-apps--current-route home-route)
              (jetpacs-flow-continue
               fallback)
              'stale)
          (setq jetpacs-apps--current id
                jetpacs-apps--current-route (and dest route))
          (jetpacs-flow-continue
           (lambda ()
             (if-let* ((verb (and dest (plist-get dest :verb)))
                       (handler (gethash verb jetpacs-action-handlers))
                       ;; Colon-aware, mirroring the flow resolver: a
                       ;; `:surfaces' entry is normally an owner name,
                       ;; but a full surface id must not grow a second
                       ;; prefix.
                       (surface (and home
                                     (if (string-search ":" home) home
                                       (jetpacs-shell-surface-for home)))))
                 ;; The full dispatch discipline — owner binding, the
                 ;; D1 gate, prompt pinning — AND the flow identity:
                 ;; the continuation inherited the HOST's device flow,
                 ;; and `jetpacs-flow-surface' prefers the flow over
                 ;; dispatch params, so without this rebinding a
                 ;; destination verb written against the navigate/flow
                 ;; patterns would drill onto the host surface — the
                 ;; exact foreign act the gate exists to refuse, done
                 ;; with its approval.  A direct `let', not
                 ;; `jetpacs--call-with-flow': that helper refuses a
                 ;; nested different-surface flow BY DESIGN, and this
                 ;; is the one sanctioned hand-off from the host's
                 ;; flow to the app's.  Client nil — a local
                 ;; re-dispatch, no wire reply.  The condition-case is
                 ;; load-bearing: `jetpacs--dispatch' deliberately
                 ;; RE-SIGNALS typed jsonrpc errors (the wire-reply
                 ;; path's contract), but here there is no wire — an
                 ;; escaping `jetpacs-retry-later' or 1500 would die
                 ;; in the timer with the fallback skipped and the
                 ;; intent silently lost.
                 (unless (eq (condition-case nil
                                 (let ((jetpacs--device-flow
                                        (list :surface surface
                                              :owner (jetpacs--owner-of
                                                      "action" verb))))
                                   (jetpacs--dispatch
                                    nil (list :action verb :surface surface)
                                    handler))
                               (error 'rejected))
                             'accepted)
                   (funcall fallback))
               (funcall fallback))))
          'accepted))))))

;; No root of its own (pass 2): the grid folded into the combined Apps
;; view on the app-store surface; `jetpacs-apps--card' renders there.
(jetpacs-defaction "app.grid" #'jetpacs-apps--action-grid)
(jetpacs-defaction "app.open" #'jetpacs-apps--action-open)
(jetpacs-defaction "app.unavailable.dismiss"
                   #'jetpacs-apps--action-dismiss-unavailable)

;; Install on the chrome seam.  The host seeds
;; `jetpacs-apps-core-dock-items' with what it used to put here
;; directly; anything else already on the seam is adopted as the core
;; builder rather than clobbered.
(when (and jetpacs-chrome-dock-items-function
           (not (eq jetpacs-chrome-dock-items-function
                    #'jetpacs-apps-dock-items))
           (null jetpacs-apps-core-dock-items))
  (setq jetpacs-apps-core-dock-items jetpacs-chrome-dock-items-function))
(setq jetpacs-chrome-dock-items-function #'jetpacs-apps-dock-items)
;; The S3 seam installs the same way: adopt any pre-existing raw
;; function as the core seed, then wrap it standalone-aware.
(when (and jetpacs-chrome-global-actions-function
           (not (eq jetpacs-chrome-global-actions-function
                    #'jetpacs-apps-global-actions))
           (null jetpacs-apps-core-global-actions))
  (setq jetpacs-apps-core-global-actions
        jetpacs-chrome-global-actions-function))
(setq jetpacs-chrome-global-actions-function #'jetpacs-apps-global-actions)
;; The S10 data seam installs identically — it is the placement-bearing
;; half of the same globals, so it withdraws on the same surfaces.
(when (and jetpacs-chrome-global-items-function
           (not (eq jetpacs-chrome-global-items-function
                    #'jetpacs-apps-global-items))
           (null jetpacs-apps-core-global-items))
  (setq jetpacs-apps-core-global-items
        jetpacs-chrome-global-items-function))
(setq jetpacs-chrome-global-items-function #'jetpacs-apps-global-items)
;; The S8 seam installs plainly: it is born alongside this composition,
;; so unlike the two above there is no pre-existing direct setter to
;; adopt — the host seeds `jetpacs-apps-core-drawer-rows' instead
;; (defvar-before-load, the core-global-actions pattern).
(setq jetpacs-chrome-drawer-function #'jetpacs-apps-drawer)
;; The GR-7b app-default FAB seam.  Chrome supplies the owner of EACH
;; screen (not merely the surface owner); the resolver above enforces
;; the app's declared-surface boundary before returning a node.
(setq jetpacs-chrome-app-fab-function #'jetpacs-apps-default-fab)

(provide 'jetpacs-apps)
;;; jetpacs-apps.el ends here
