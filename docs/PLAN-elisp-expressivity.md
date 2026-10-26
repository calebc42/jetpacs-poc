# Plan: what Jetpacs needs so an Org or CRUD app is Elisp alone

Status: plan, 2026-10-26. Source: the three parity applets (Bloom Native,
Orgzly Native, Harp Native) read against the foundation and the receiver,
after the app-descent audit (`AUDIT-app-descent-2026-10-26.md`). The owner's
goal: anyone can author any CRUD or Org app, pixel-faithful to a native
design, without touching Kotlin.

The test for every item below is the same. Bloom matched a Compose app to a
pixel or two per screen; where it needed a measured magic number, a box
drawn by hand, a style list split across scopes or a parser ported line by
line, the foundation lacks a word. Each item names the evidence in the app,
what the general and app-neutral word is, and which apps would use it.
Items marked *verified* were read in source for this plan; items marked
*corroborated* rest on the app's own comments and parity notes.

## 1. Where the apps spend their lines

Bloom's `bloom-design.el` is 1500 lines, more than any of its screens; most
of it restores what the receiver takes away or rebuilds what a slot cannot
express. Orgzly and Harp each carry a 300-line design file that is the same
file with different colors. Every app pages its own lists, snapshots its own
"did the shape change" witness, and parses Org for its own parity view.
Those are the costs to remove, in this order:

1. Design-runtime words that make a measured number unnecessary (§2, A1–A4,
   A11, A14). These account for nearly every literal `0.4`, `1 dp`,
   `padding_top` and hand-drawn switch in Bloom.
2. Foundation helpers the three apps rewrite (§3, B1–B6, B10, B11).
3. Protocol words that let a template or a span say what the app means
   (§4, C1, C4, C5).

## 2. Receiver and design-runtime gaps

A1. **Line-height trim is not controllable** (*corroborated*: `ELISP.md`
"Pixel parity", `bloom-design.el` `bloom-design--untrimmed-leading`). The
receiver trims the extra leading above a first line and below a last; the
app's Compose does not. Bloom marks roles `trimmed` or restores leading with
`min_height` plus half the extra as `padding_top`/`padding_bottom`. Word: a
style property `line_height_trim` with values `none`, `both`, `first`,
`last` (Compose `LineHeightStyle.Trim`). Users: Bloom; any app with a type
scale.

A2. **Line boxes are not rounded as the native app's are** (*corroborated*:
`bloom-agenda.el` "to whole pixels: 0.4 dp more", `bloom-settings.el`
"0.2 dp"). Bloom carries measured slack per row. Word: measure text the way
Compose `Text` does, or a `line_box` property with `pixel` and `exact`.
Users: Bloom.

A3. **A bordered box insets its content by the border width**
(*corroborated*: `bloom-settings--group`, `bloom-design.el` "The receiver
insets content by the border"). Bloom makes group rows functions of their
edge trim. Word: `border_inset` boolean; false draws the border over the
padding. Users: Bloom; Orgzly and Harp rows once bordered.

A4. **`border_width` 0 still draws a hairline on the base card**
(*corroborated*: `bloom-design.el` "keeps a hairline even at zero width").
Word: zero width means no border. Users: Bloom.

A5. **Negative letter spacing is rejected** (*verified*:
`jetpacs-design-dimension` is non-negative; `PARITY.md` "Agenda title
spacing -0.21 sp"). Word: a signed kind for `letter_spacing` in the design
vocabulary and the receiver. Users: Bloom.

A6. **No shadow color** (*corroborated*: `PARITY.md` "Tinted shadows").
Word: `shadow_color` on surfaces and the card slots. Users: Bloom.

A7. **No navigation-bar scrim** (*corroborated*: `PARITY.md`). Word: a
scaffold member or `chrome.navigation-bar` slot. Users: Bloom.

A8. **Monospace spans use the system face, not the profile's mono family**
(*corroborated*: `PARITY.md` "Monospace spans"). Word: resolve `mono` spans
through the `text.mono` slot. Users: Bloom, Orgzly.

A9. **Icons are Material names only; the canvas draws polylines**
(*corroborated*: `bloom-read.el` "draws polylines only"; glyphs drawn as
text and carets as canvas). Word: `icon` accepting a vector path behind a
feature, and `rounded_rect` and `arc` canvas ops. Users: Bloom.

A10. **The icon button is a fixed 48 dp target and System Back is bound to a
leading `arrow_back`** (*corroborated*: `bloom-navigation.el`,
`ChromeBack.kt`). Word: `icon-button.container` honoring `width` and
`height`; a scaffold `back` descriptor instead of icon-name sniffing. Users:
Bloom, Orgzly.

A11. **No top-app-bar design slot** (*verified*: the slot enum has
`chrome.drawer`, `chrome.snackbar`, `chrome.tab-*`, `chrome.rail-*` and
nothing for the top bar; Orgzly recolors the built bar's children by hand).
Word: `chrome.top-bar`, `chrome.top-bar-title`, `chrome.top-bar-action`.
Users: all three.

A12. **The scaffold bottom bar is wrapped in a padded band**
(*corroborated*: `bloom-search.el` ends the body with its Filters bar
instead). Word: `chrome.bottom-bar` slot or a `bottom_bar_padding` member.
Users: Bloom.

A13. **A text field is at least 48 dp tall with its text at the top**
(*corroborated*: `bloom-design.el` "10 dp above the text puts it on the
box's centre"). Word: `text-field.*` slots honoring `min_height` and a
vertical alignment. Users: Bloom.

A14. **The switch cannot express state-dependent thumb size, offset or an
off-state outline, and always injects its value** (*corroborated*:
`bloom-settings--switch` "Drawn from boxes so its geometry is Material's
exactly"; commit 3f6aec2). Word: honor `width`, `height` and `border_*` in
the `switch.thumb` and `switch.track` selected rules; let a switch decline
value injection (see C5). Users: Bloom, Harp.

A15. **An editor's top padding cannot scroll away with its content**
(*corroborated*: `PARITY.md` "Scroll-aware insets"). Word:
`editor.content_padding` applied inside the scroll. Users: Bloom.

A16. **The widget profile advertises no component nodes** (*verified*:
`GlanceRendererContribution` lists fifteen core types), so agenda rows
degrade to the canonical composition. Word: Glance renderings of
`jetpacs.list_item`, `badge` and `section_header`, advertised in the widget
profile. Users: any widget app.

A17. **256 styles in effect, profile and scope merged** (*verified*:
`DesignModel.compileScope` merges the parent's styles into the child's and
caps the merged map at 256; the Elisp schema caps each local map at 256).
Nesting therefore adds no room: Bloom moved 42 screen-only text roles into
per-screen scopes to get its profile to 188. Word: budget scope styles
separately from the profile's, or raise the merged cap (the byte cap of
256 KiB already bounds the configuration). Users: any parity app.

A18. **A theme-role color cannot carry alpha** (*corroborated*:
`bloom-design.el` hairlines pass the role unscaled). Word: a color modifier
(`primary@0.4`). Users: Bloom.

## 3. Foundation gaps (Elisp)

B1. **Fitting to a node budget and paging** (*verified*:
`bloom-widgets--agenda-document` rebuilds the widget with fewer rows until
`jetpacs-shell-node-count` fits `jetpacs-shell-target-limit`; Orgzly and Harp
each write a page window and a Previous/Next pager). Word:
`(jetpacs-shell-fit surface builder &key from to)` that sheds through a
caller-supplied limit, and `(jetpacs-page items page size &key action)`
returning rows and a pager node. Users: all three.

B2. **Nested scopes had to re-declare the parent's tokens** (*verified and
fixed in this audit*: `jetpacs-design-scope :inherit`). A screen-local scope
now validates against the profile it will nest under and sends no copied
tokens. Follow-up: `(jetpacs-design-screen-scope profile styles children)`
as the one-call form Bloom's helper already is.

B3. **A per-role text helper per module** (*corroborated*: six
`(role ink text &rest attrs)` wrappers in Bloom and a role-table compiler in
`bloom-design.el`). Word: `jetpacs-design-text-roles`, a table of (name
family size weight color tracking line-height trim) compiled to styles once,
and `(jetpacs-design-text role text &key ink)`. Users: Bloom first; the
others as they adopt text roles.

B4. **Profile boilerplate and the "does the active profile carry my styles"
gate** (*corroborated*: `orgzly-design.el` and `harp-design.el` are
structurally one file; `bloom-design-parity-available-p` repeated at every
screen). Word: `(jetpacs-design-simple-profile id label palette &key dark)`
and `(jetpacs-design-active-profile-for app-id required-styles)`. Users: all
three.

B5. **The patch-or-push shape witness** (*verified*: Bloom's planning, Orgzly's
fold and Harp's filter each snapshot the screen's shape before a change and
choose `defer-patch`/`data-sync` or a whole push after). Word:
`(jetpacs-app-present owner &key shape-fn data-ops)` that does the compare.
Users: all three.

B6. **Keyed conditional text** (*corroborated*: the `visible_when non_empty`
plus `:key` wrapper in Orgzly, Harp and Bloom). Word:
`(jetpacs-text-when member text &rest options)`. Users: all three.

B7. **The `item` injection fallback** `(or :id :item)` in every templated
handler (*corroborated*: Orgzly and Harp). Word: `(jetpacs-args-ref args)` or
a `:ref-arg` on `jetpacs-defaction`. Users: Orgzly, Harp.

B8. **Org structure as data** (*verified*: `bloom-library-parse-subtree` ports
the Android app's parser; Orgzly and Harp regex their prefaces and
properties; the foundation's Org helpers emit nodes, not data). Word:
`(jetpacs-org-structure region)` returning headings, planning, drawers,
blocks, lists and checkboxes as plists over `org-element`, and
`(jetpacs-org-preface file)`. The app keeps its layout; the parse stops
being product code. Users: all three.

B9. **Inline Org markup to spans** (*verified*: Bloom has two tokenizers,
read and edit; Orgzly builds spans by hand; `jetpacs-org-inline-spans`
exists but is bound to buffer positions and the shared budget). Word: a
string form, `(jetpacs-org-spans text &key colors link-action)`. Users:
Bloom, Orgzly.

B10. **Settings wire shapes and a skinnable page builder** (*verified*:
`PLAN-app-descent.md` §1c; Bloom prints values with `%S` and reads them
back because `jetpacs-settings-apply-wire` decodes choice labels, not
values; Orgzly has no settings screen). Word: a decoder accepting choice
values and JSON booleans, `(jetpacs-settings-change-action sym value)`, and
`(jetpacs-settings-pages spec :row-fn)` so an app styles the rows and keeps
the semantics. Users: all three.

B11. **An actionable snackbar** (*corroborated*: `PARITY.md` "Undo
snackbar"; the shell queues `:snackbar` text only, while SPEC 18.2.1
`snackbar.show` carries an action label and a result). Word:
`(jetpacs-snackbar message &key action-label on-action)`. Users: all three.

B12. **Editor session management** (*verified*: `bloom-edit.el` owns the
dirty flag, autosave timer, stale-revision guard and reattach around a
private subtree buffer with `ebp-sync-fontify-function`). Word:
`jetpacs-editor-session` over a buffer or subtree, with the app supplying
only the fontify function and the save. Users: Bloom; anyone adopting
`editor.sync`.

B13. **The scaffold injects an app-default FAB unless told `:fab nil`**
(*corroborated*: `bloom-agenda.el`). Minor: no implicit FAB.

## 4. Protocol gaps (EBP)

C1. **RichSpan has no strike-through** (*corroborated*: `bloom-read.el` maps
`+struck+` away; design styles have `strike_through`, spans do not). Word:
`strike` on RichSpan. Users: Bloom, Orgzly.

C2. **Spans take literal or theme colors, not design styles**
(*corroborated*: `bloom-design.el` flattens translucent fills for spans).
Word: `style` identifier on RichSpan. Users: Bloom, Orgzly.

C3. **Two color grammars** (*verified*: SPEC 16.6 `#rrggbbaa`; the design
vocabulary `#AARRGGBB`). Word: one grammar. Users: all.

C4. **A template's shape cannot vary per row** (*verified*: Orgzly's row has
one action per slot because a descriptor's members cannot bind; the
preview is dropped). Word: bindable `action` and `args` on descriptors inside
templates. Users: Orgzly, Harp.

C5. **Stateful nodes are barred from templates and conditionals, and a
switch always publishes `state.changed` with `value`** (*corroborated*:
SPEC 13.8.5/13.8.6, 17.4; Bloom draws box switches to own the argument
shape). Word: a bound `checked` inside a template with `item`, or
`publish_state: false` on `switch`. Users: Bloom, Harp.

C6. **Fifteen closed editor syntax roles** (*verified*: `bloom-edit.el` maps
bold to `constant`, italic to `type`, strike to `operator`, code to
`string`). Word: `editor.syntax.custom-1..8` slots, or open role
identifiers the design slots can bind. Users: Bloom; Orgzly for `:syntax
"org"`.

C7. **No Companion-to-Emacs file transfer** (*corroborated*: `harp-write.el`
enters `:FILES:` as text). Word: `file.offer` or a `file.pick` capability
with byte limits. Users: Harp; Bloom attachments later.

C8. **`max_reminders` is pairing-wide, not per owner** (*verified*: Orgzly
caps itself at 64 under the shared 256). Word: `max_reminders_per_owner`.
Users: Orgzly, Bloom.

## 5. What stays in the apps

Monogram palettes and note counts, query-language stances and Quick Start
cards, agenda placement rules, Material fallback builders, palettes and type
scales, autosave delays and page sizes. These are the product.

## 6. Order

1. A11 top-bar slot, B1 fit and paging, B4 profile boilerplate, B5 shape
   witness, B10 settings wire, B11 snackbar: the items three apps share.
2. A1 trim, A2 pixel rounding, A3 border inset, A4 zero border: the items
   behind almost every measured number.
3. B8 Org structure data and B9 string spans: the items that turn a ported
   parser into a layout.
4. A14 and C5 together (the switch), then A17 (the style budget), then the
   rest of §2 and §4 by consumer count.

Each item lands as: SPEC amendment where §4 applies, the design or contract
vocabulary regenerated, receiver and Elisp in the same change, and one of
the three apps switched to the new word in the same step, so the gap closes
on a real screen rather than in a catalog.
