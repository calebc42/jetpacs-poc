;;; jetpacs-vocabulary.el --- the contract node schema -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later
;; Package-Requires: ((emacs "30.1"))

;;; Commentary:

;; GENERATED from ebp/contract.json (format 12, spec
;; 3.1.0-draft) by tools/gen-jetpacs-vocabulary.py -- DO NOT EDIT.
;; `jetpacs-widgets/catalog-node-schema' re-reads the contract and fails on
;; any disagreement, exactly as the other `catalog-*' mirrors do.
;;
;; The sibling of the Companion's generated Vocabulary.kt, and for the same
;; reason: an amendment that adds a member must not have to be remembered in
;; three places.  Emacs cannot read contract.json at run time -- the device
;; load-path holds only the .el files device/install.sh pushes -- so the
;; contract is compiled to Elisp here and drift-tested off-device.
;;
;; This is what lets the container constructors REFUSE an unknown trailing
;; option instead of silently dropping it, which is how `:padding 8' on a
;; row used to vanish (it is a SPEC 16.5 universal attribute and belongs on
;; `jetpacs-with-attrs').

;;; Code:

(defconst jetpacs-contract-format 12
  "The `contract_format' this vocabulary was generated from.")

(defconst jetpacs-protocol-version 3
  "The EBP wire major this vocabulary implements.")

(defconst jetpacs-contract-spec-version "3.1.0-draft"
  "The SPEC version this vocabulary was generated from.")

(defconst jetpacs-universal-attributes
  '(:key :id :scroll_here :padding :pad :width :height :min_width :max_width :min_height :max_height :fill_fraction :aspect_ratio :weight :bg :corner :border :alpha :clip :align_self :semantics)
  "Contract-projected universal member keywords accepted on every Node.")

(defconst jetpacs-semantics-schema
  '(:required ()
    :optional ("name" "description" "state_description" "error" "pane_title" "heading_level" "live_region" "collection" "collection_item" "traversal_group" "traversal_index" "actions")
    :field-types (("name" . "non-empty-plain-string") ("description" . "non-empty-plain-string") ("state_description" . "non-empty-plain-string") ("error" . "non-empty-plain-string") ("pane_title" . "non-empty-plain-string") ("heading_level" . "integer-1-6") ("live_region" . "live-region-enum") ("collection" . "semantic-collection-object") ("collection_item" . "semantic-collection-item-object") ("traversal_group" . "boolean") ("traversal_index" . "finite-number") ("actions" . "semantic-action-array")))
  "Contract-projected outer Semantics object schema.")

(defconst jetpacs-semantic-object-schema
  '(
    ("collection" (:required ("row_count" "column_count") :optional () :field-types (("row_count" . "non-negative-integer") ("column_count" . "positive-integer"))))
    ("collection_item" (:required ("row_index" "row_span" "column_index" "column_span") :optional () :field-types (("row_index" . "non-negative-integer") ("row_span" . "positive-integer") ("column_index" . "non-negative-integer") ("column_span" . "positive-integer"))))
    ("action" (:required ("label" "on_action") :optional () :field-types (("label" . "non-empty-plain-string") ("on_action" . "action-descriptor")))))
  "Contract-projected schemas for nested Semantics objects.")

(defconst jetpacs-semantic-members
  '(:name :description :state_description :error :pane_title :heading_level :live_region :collection :collection_item :traversal_group :traversal_index :actions)
  "Known Semantics member keywords; authoring helpers reject all others.")

(defconst jetpacs-semantic-live-regions
  '("polite" "assertive")
  "The contract-projected Semantics live-region enum.")

(defconst jetpacs-semantic-roles
  '("button" "image" "text_input" "checkbox" "switch" "selection_group" "tab_group" "dropdown" "slider" "progress")
  "The receiver-derived semantic role vocabulary; not an author override.")

(defconst jetpacs-max-semantic-actions-per-node
  8
  "The fixed maximum number of authored custom actions on one Node.")

(defconst jetpacs-renderer-profile-contract
  '(:required ("node_types" "builtins" "features" "extensions") :optional ("members" "limits") :members (:required ("universal" "nodes" "semantics" "surface") :optional () :field_types (:universal "identifier-array" :nodes "node-member-map" :semantics "identifier-array" :surface "identifier-array")) :limits (:required () :optional ("max_nodes" "max_lazy_items" "max_node_depth" "max_size_variants" "max_remote_views_bytes") :field_types (:max_nodes "positive-integer" :max_lazy_items "positive-integer" :max_node_depth "positive-integer" :max_size_variants "positive-integer" :max_remote_views_bytes "positive-integer")))
  "Contract-projected target profile and exact member-support shape.")

(defconst jetpacs-widget-surface-contract
  '(:required ("title" "body") :optional ("empty" "header_action" "size_variants") :node_body t :multi_view :json-false)
  "Contract-projected `widget:*' SurfaceSpec wrapper.")

(defconst jetpacs-widget-size-variant-contract
  '(:required ("min_width" "min_height" "body") :optional () :field_types (:min_width "dp" :min_height "dp" :body "node") :selection "first-authored-eligible")
  "Contract-projected adaptive widget body shape.")

(defconst jetpacs-swipe-contract
  '(:legacy (:required ("label" "on_trigger") :optional ("icon" "color")) :rich (:required ("actions") :optional ("commit") :min_actions 1 :max_actions_limit "max_swipe_actions_per_side" :commit_max_actions 2) :action (:required ("label" "on_trigger") :optional ("icon" "color")))
  "Contract-projected legacy and reveal-first rich swipe shapes.")

(defconst jetpacs-max-widget-nodes
  128)
(defconst jetpacs-max-widget-lazy-items
  40)
(defconst jetpacs-max-widget-node-depth
  12)
(defconst jetpacs-max-widget-size-variants
  8)
(defconst jetpacs-max-widget-remote-views-bytes
  716800)
(defconst jetpacs-max-swipe-actions-per-side
  4)

(defconst jetpacs-accessible-name-precedence
  '("semantics.name" "content_description" "label" "icon" "t" "node")
  "Accessible-name sources in normative first-present order.")

(defconst jetpacs-default-node-semantics
  '(
    ("section_header" . (:heading_level 2))
    ("progress" . (:role "progress" :progress_value_member "value" :progress_min 0 :progress_max 1 :indeterminate_when_value_absent t))
    ("image" . (:role "image"))
    ("button" . (:role "button" :enabled_member "enabled" :checked_member "checked"))
    ("icon_button" . (:role "button" :enabled_member "enabled" :checked_member "checked"))
    ("chip" . (:role "button" :role_condition_member "on_tap" :enabled_member "enabled" :selected_member "selected" :selected_default :json-false))
    ("empty_state" . (:role "button" :role_condition_member "on_tap"))
    ("date_button" . (:role "button" :enabled_member "enabled"))
    ("time_button" . (:role "button" :enabled_member "enabled"))
    ("text_input" . (:role "text_input" :enabled_member "enabled"))
    ("editor" . (:role "text_input" :enabled_member "enabled" :read_only_member "read_only"))
    ("search_bar" . (:role "text_input" :enabled_member "enabled"))
    ("dropdown" . (:role "dropdown" :enabled_member "enabled" :read_only_member "editable" :read_only_inverted t))
    ("checkbox" . (:role "checkbox" :enabled_member "enabled" :checked_member "checked" :toggle_state_member "state" :checked_default :json-false))
    ("switch" . (:role "switch" :enabled_member "enabled" :checked_member "checked" :checked_default :json-false))
    ("enum_list" . (:role "selection_group" :enabled_member "enabled" :selection_member "value"))
    ("tabs" . (:role "tab_group" :selection_member "initial" :selection_default_index 0))
    ("tab_selector" . (:role "tab_group" :selection_member "selected"))
    ("segmented_button" . (:role "tab_group" :enabled_member "enabled" :selection_member "value"))
    ("slider" . (:role "slider" :enabled_member "enabled" :progress_value_member "value" :progress_min_member "min" :progress_max_member "max" :progress_min 0 :progress_max 1 :progress_value_defaults_to_min t))
    ("card" . (:role "button" :role_condition_member "on_tap" :custom_actions_from ("swipe_start" "swipe_end")))
    ("collapsible" . (:expanded_member "collapsed" :expanded_inverted t :custom_actions_from ("swipe_start" "swipe_end"))))
  "Contract-projected roles and state derivations keyed by Node type.")

(defconst jetpacs-contract-enums
  '(
    ("text.style" . ("body" "title" "headline" "caption" "label" "mono"))
    ("rich_text.style" . ("body" "title" "headline" "caption" "label" "mono"))
    ("progress.variant" . ("circular" "linear" "linear_wavy" "circular_wavy" "loading" "contained_loading"))
    ("button.variant" . ("filled" "tonal" "elevated" "outlined" "text"))
    ("image.content_scale" . ("fit" "crop" "fill"))
    ("row.align" . ("top" "center" "bottom" "baseline"))
    ("column.align" . ("start" "center" "end"))
    ("flow_row.align" . ("top" "center" "bottom"))
    ("arrange" . ("start" "center" "end" "space_between" "space_around" "space_evenly"))
    ("box.alignment" . ("top_start" "top_center" "top_end" "center_start" "center" "center_end" "bottom_start" "bottom_center" "bottom_end"))
    ("surface.shape" . ("rounded" "rounded_small" "circle" "square" "slanted" "arch" "fan" "arrow" "semi_circle" "oval" "pill" "triangle" "diamond" "clam_shell" "pentagon" "gem" "sunny" "very_sunny" "cookie_4_sided" "cookie_6_sided" "cookie_7_sided" "cookie_9_sided" "cookie_12_sided" "ghostish" "clover_4_leaf" "clover_8_leaf" "burst" "soft_burst" "boom" "soft_boom" "flower" "puffy" "puffy_diamond" "pixel_circle" "pixel_triangle" "bun" "heart"))
    ("chart.kind" . ("line" "bar" "area" "sparkline"))
    ("canvas.op" . ("line" "rect" "circle" "path" "text"))
    ("text_input.keyboard" . ("text" "number" "decimal" "email" "phone" "uri"))
    ("align_self" . ("start" "center" "end" "stretch"))
    ("dialog.style" . ("dialog" "sheet" "sheet_full"))
    ("notification.priority" . ("min" "low" "default" "high" "max"))
    ("diagnostic.severity" . ("error" "warning" "info" "hint"))
    ("toolbar.placement" . ("cursor" "line-start" "block"))
    ("button.size" . ("xsmall" "small" "medium" "large" "xlarge"))
    ("button.shape" . ("round" "square"))
    ("icon_button.variant" . ("filled" "tonal" "outlined"))
    ("card.variant" . ("filled" "elevated" "outlined"))
    ("chip.variant" . ("flat" "elevated" "input"))
    ("assist_chip.variant" . ("flat" "elevated" "suggestion" "elevated_suggestion"))
    ("text_input.variant" . ("outlined" "filled"))
    ("slider.track" . ("default" "centered"))
    ("slider.orientation" . ("horizontal" "vertical"))
    ("checkbox.state" . ("off" "on" "indeterminate"))
    ("text_input.filter" . ("digits" "alnum"))
    ("menu.initial_scroll" . ("start" "end"))
    ("date_button.mode" . ("calendar" "input"))
    ("time_button.display_mode" . ("picker" "input" "switchable"))
    ("tooltip.position" . ("above" "below" "left" "right" "start" "end"))
    ("icon_button.size" . ("xsmall" "small" "medium" "large"))
    ("icon_button.shape" . ("round" "square"))
    ("icon_button.width_mode" . ("narrow" "uniform" "wide"))
    ("split_button.variant" . ("filled" "tonal" "elevated" "outlined"))
    ("split_button.size" . ("xsmall" "small" "medium" "large" "xlarge"))
    ("scaffold.top_bar_style" . ("small" "center" "medium" "large" "medium_flexible" "large_flexible" "two_rows"))
    ("scaffold.scroll_behavior" . ("pinned" "enter_always" "exit_until_collapsed"))
    ("scaffold.refresh_indicator" . ("default" "loading" "none"))
    ("scaffold.snackbar_duration" . ("short" "long" "indefinite"))
    ("enum_list.variant" . ("chips" "radio"))
    ("scaffold.sheet_state" . ("hidden" "partial" "expanded"))
    ("tabs.style" . ("primary" "secondary"))
    ("tab_selector.style" . ("primary" "secondary"))
    ("tab_item.icon_position" . ("above" "leading"))
    ("scaffold.floating_toolbar_orientation" . ("horizontal" "vertical"))
    ("scaffold.floating_toolbar_placement" . ("bottom_center" "bottom_start" "bottom_end" "center_start" "center_end"))
    ("scaffold.floating_toolbar_exit_direction" . ("bottom" "top" "start" "end"))
    ("pane_scaffold.variant" . ("list_detail" "supporting"))
    ("navigation_rail.variant" . ("standard" "wide" "modal"))
    ("scaffold.drawer_variant" . ("modal" "dismissible" "permanent"))
    ("scaffold.bottom_bar_behavior" . ("pinned" "exit_always"))
    ("scaffold.fab_position" . ("end" "end_overlay" "center"))
    ("button.checked_shape" . ("round" "square"))
    ("navigation_rail.arrangement" . ("top" "center" "bottom"))
    ("carousel.strategy" . ("multi_browse" "uncontained" "centered_hero"))
    ("search_bar.variant" . ("full_screen" "docked"))
    ("button.shape_role" . ("leading" "middle" "trailing" "top" "bottom"))
    ("theme.layout_direction" . ("ltr" "rtl"))
    ("window.size_class" . ("compact" "medium" "expanded"))
    ("snackbar.result" . ("dismissed" "action")))
  "Contract-projected enum values keyed by their qualified field name.")

(defconst jetpacs-field-types
  '(
    ("text" . "string")
    ("label" . "string")
    ("title" . "string")
    ("caption" . "string")
    ("hint" . "string")
    ("content_description" . "string")
    ("annotation" . "string")
    ("summary" . "string")
    ("action_label" . "string")
    ("snackbar" . "string")
    ("url" . "string")
    ("name" . "string")
    ("icon" . "identifier")
    ("syntax" . "identifier")
    ("id" . "identifier")
    ("key" . "identifier")
    ("semantics" . "semantics-object")
    ("document" . "identifier")
    ("keyboard" . "enum")
    ("children" . "node-array")
    ("content" . "node")
    ("variants" . "variant-array")
    ("items" . "varies-per-node")
    ("rows" . "table-row-array")
    ("spans" . "rich-span-array")
    ("series" . "chart-series-array")
    ("ops" . "canvas-op-array")
    ("options" . "enum-option-array")
    ("header" . "node")
    ("trailing" . "node")
    ("top_bar" . "node")
    ("body" . "node")
    ("bottom_bar" . "node")
    ("fab" . "node")
    ("floating_toolbar" . "node")
    ("drawer" . "node")
    ("color" . "color")
    ("bg" . "color")
    ("selectable" . "boolean")
    ("selected" . "varies-per-node")
    ("scroll" . "boolean")
    ("fill" . "varies-per-node")
    ("collapsed" . "boolean")
    ("scrollable" . "boolean")
    ("pager_only" . "boolean")
    ("single_line" . "boolean")
    ("monospace" . "boolean")
    ("password" . "boolean")
    ("autofocus" . "boolean")
    ("clear_on_submit" . "boolean")
    ("read_only" . "boolean")
    ("line_numbers" . "boolean")
    ("complete" . "boolean")
    ("chromeless" . "boolean")
    ("publish_state" . "boolean")
    ("checked" . "boolean")
    ("multi_select" . "boolean")
    ("allow_add" . "boolean")
    ("enabled" . "boolean")
    ("italic" . "boolean")
    ("underline" . "boolean")
    ("mono" . "boolean")
    ("clip" . "boolean")
    ("scroll_here" . "boolean")
    ("size" . "varies-per-node")
    ("spacing" . "dp")
    ("run_spacing" . "dp")
    ("content_padding" . "dp")
    ("elevation" . "dp")
    ("thickness" . "dp")
    ("padding" . "dp")
    ("min_lines" . "positive-integer")
    ("max_lines" . "positive-integer")
    ("initial" . "non-negative-integer")
    ("day" . "integer-1-31")
    ("month_index" . "integer-1-12")
    ("year" . "non-negative-integer")
    ("value" . "varies-per-node")
    ("min" . "number")
    ("max" . "number")
    ("values" . "number-array")
    ("height" . "number")
    ("width" . "number")
    ("y_range" . "two-number-array")
    ("month" . "yyyy-mm")
    ("min_month" . "yyyy-mm")
    ("max_month" . "yyyy-mm")
    ("font_weight" . "font-weight")
    ("toolbar" . "toolbar-id-or-item-array")
    ("snackbar_action" . "label-on-tap-object")
    ("swipe_start" . "swipe-object")
    ("swipe_end" . "swipe-object")
    ("badge" . "string-or-number")
    ("marks" . "date-marks-object")
    ("day_styles" . "date-style-object")
    ("aligns" . "align-array")
    ("time" . "string")
    ("fg" . "color")
    ("animate_shape" . "boolean")
    ("trailing_icon" . "identifier")
    ("shadow_elevation" . "dp")
    ("supporting_text" . "string")
    ("prefix" . "string")
    ("suffix" . "string")
    ("leading_icon" . "identifier")
    ("is_error" . "boolean")
    ("max_length" . "positive-integer")
    ("track" . "string")
    ("thumb_icon" . "identifier")
    ("initial_scroll" . "string")
    ("reverse_scroll" . "boolean")
    ("mode" . "string")
    ("display_mode" . "string")
    ("checked_icon" . "identifier")
    ("caret" . "boolean")
    ("rich" . "boolean")
    ("shown" . "boolean")
    ("position" . "string")
    ("width_mode" . "string")
    ("trailing_label" . "string")
    ("trailing_description" . "string")
    ("top_bar_subtitle" . "string")
    ("scroll_behavior" . "string")
    ("icon_position" . "string")
    ("floating_toolbar_orientation" . "string")
    ("floating_toolbar_expanded" . "boolean")
    ("floating_toolbar_placement" . "string")
    ("floating_toolbar_scroll" . "boolean")
    ("floating_toolbar_exit_direction" . "string")
    ("list" . "node")
    ("detail" . "node")
    ("extra" . "node")
    ("arrangement" . "string")
    ("value_end" . "number")
    ("orientation" . "string")
    ("value_label" . "boolean")
    ("color_end" . "color")
    ("track_icon_start" . "identifier")
    ("track_icon_end" . "identifier")
    ("state" . "string")
    ("stroke" . "stroke-object")
    ("selection" . "two-number-array")
    ("hide_keyboard_on_submit" . "boolean")
    ("mask" . "string")
    ("filter" . "string")
    ("variant" . "string")
    ("caret_width" . "dp")
    ("caret_height" . "dp")
    ("tooltip" . "string")
    ("refresh_indicator" . "string")
    ("is_refreshing" . "boolean")
    ("snackbar_duration" . "string")
    ("snackbar_dismiss" . "boolean")
    ("snackbar_max_lines" . "positive-integer")
    ("editable" . "boolean")
    ("overflow_icon" . "identifier")
    ("max_items" . "positive-integer")
    ("strategy" . "string")
    ("item_width" . "dp")
    ("item_spacing" . "dp")
    ("item_corner" . "dp")
    ("close_icon" . "identifier")
    ("columns" . "positive-integer")
    ("min_item_width" . "dp")
    ("reverse" . "boolean")
    ("sheet" . "node")
    ("sheet_peek_height" . "dp")
    ("sheet_state" . "string")
    ("avatar" . "identifier")
    ("content_spacing" . "dp")
    ("expanded" . "boolean-or-auto")
    ("hide_on_collapse" . "boolean")
    ("drawer_variant" . "string")
    ("bottom_bar_behavior" . "string")
    ("fab_position" . "string")
    ("checked_shape" . "string")
    ("fab_hide_on_scroll" . "boolean")
    ("min_date" . "yyyy-mm-dd")
    ("max_date" . "yyyy-mm-dd")
    ("range_start" . "yyyy-mm-dd")
    ("range_end" . "yyyy-mm-dd")
    ("disabled_weekdays" . "weekday-array")
    ("groups" . "menu-group-array")
    ("footer" . "node")
    ("top_bar_expanded" . "node")
    ("top_bar_collapsed_height" . "dp")
    ("top_bar_expanded_height" . "dp")
    ("top_bar_centered" . "boolean")
    ("overlap" . "dp")
    ("shape_role" . "enum")
    ("indicator" . "tab-indicator-object")
    ("snackbar_content" . "node")
    ("rail" . "node")
    ("report_caret" . "boolean"))
  "Contract-projected wire field names and their declared value types.")

(defconst jetpacs-action-hook-keys
  '("on_tap" "on_change" "on_submit" "on_save" "on_enter" "on_pick" "on_reorder" "on_refresh" "on_sheet_change" "on_expand_change" "on_long_tap" "on_add_row" "on_add_col" "on_day_tap" "on_month_change" "on_point_tap" "on_trigger" "header_action")
  "Contract-projected Node members which accept an ActionDescriptor.")

(defconst jetpacs-action-descriptor-fields
  '("action" "builtin" "args" "when_offline" "dedupe" "ttl_s" "confirm" "capture_fields" "open_surface")
  "Contract-projected fields named by the ActionDescriptor envelope.")

(defconst jetpacs-action-offline-policies
  '("drop" "queue" "wake")
  "Contract-projected remote-action offline policy vocabulary.")

(defconst jetpacs-action-offline-default
  "drop"
  "Contract-projected offline policy used when the member is absent.")

(defconst jetpacs-action-descriptor-schema
  '(
    ("remote" :required ("action") :optional ("args" "when_offline" "dedupe" "ttl_s" "confirm" "capture_fields" "open_surface"))
    ("view.switch" :required ("builtin" "view") :optional ())
    ("variant.switch" :required ("builtin" "id") :optional ("value"))
    ("surface.open" :required ("builtin" "surface") :optional ())
    ("clipboard.copy" :required ("builtin" "text") :optional ())
    ("share.send" :required ("builtin" "text") :optional ("title"))
    ("companion.settings.open" :required ("builtin") :optional ())
    ("trigger.fire" :required ("builtin" "id") :optional ())
    ("dialog.submit" :required ("builtin") :optional ("value" "capture_fields"))
    ("dialog.dismiss" :required ("builtin") :optional ()))
  "Contract-projected closed schemas for remote and builtin actions.
Each row is (KIND :required (MEMBER...) :optional (MEMBER...)).")

(defconst jetpacs-action-injections
  '(
    ("on_change" . ("value"))
    ("on_submit" . ("value"))
    ("on_save" . ("value"))
    ("on_enter" . ("value"))
    ("on_pick" . ("value"))
    ("on_reorder" . ("from" "to" "order"))
    ("on_trigger" . ("direction"))
    ("on_add_row" . ("index"))
    ("on_add_col" . ("index"))
    ("on_day_tap" . ("value"))
    ("on_month_change" . ("value"))
    ("on_point_tap" . ("value" "index")))
  "Contract-projected receiver-injected arguments keyed by action hook.")

(defconst jetpacs-action-open-surface-feature
  "action.open_surface"
  "Feature required by the ActionDescriptor `open_surface' member.")

(defconst jetpacs-toolbar-contract
  '(:ops ("snippet" "on_tap" "menu" "command" "line") :line_ops ("promote" "demote" "move-up" "move-down") :placements ("cursor" "line-start" "block") :snippet_placeholders ("${selection}" "${cursor}" "${input:Prompt}" "${date}" "${time}"))
  "Contract-projected Editor toolbar operation and placeholder vocabulary.")

(defconst jetpacs-text-input-contract
  '(:line_counts (:type "positive-integer" :order "min<=max" :text_input_default_min 1 :text_input_default_max "min_lines" :single_line_value 1 :single_line_forbids "U+000A") :password (:authored_value "absent-or-empty" :on_change "absent" :clear_on_submit "absent-or-false" :submit_capture "self" :capture_allowed_from ("own-on_submit" "dialog.submit") :remote_policy "drop" :forbidden_descriptor_members ("dedupe" "ttl_s") :requires_session_state "READY" :storage "volatile" :deadline_ms 30000) :clear_on_submit (:requires "remote-on_submit" :forbidden_when_on_submit "builtin") :selection (:type "two-number-array" :unit "unicode-scalar" :minimum 0 :order "start<=end" :upper_bound "authored-value" :lifecycle "new-presentation-seed" :when_retained_draft_wins "ignore") :max_length (:type "positive-integer" :unit "unicode-scalar" :behavior "truncate-before-commit" :authored_value_must_fit t :retained_draft_must_fit t) :transform_order ("single_line" "filter" "max_length") :filter_character_sets (:digits "ascii-digit" :alnum "ascii-alphanumeric") :filter_unknown "none" :filter_authored_value_must_match t :filter_retained_draft_must_match t :mask (:slot "#" :minimum_slots 1 :unit "unicode-scalar" :overflow "unformatted" :incompatible_with ("password" "syntax")) :content_padding (:type "non-negative-dp" :applies_to "all-interior-sides") :variant_default "outlined" :variant_unknown "outlined" :hide_keyboard_on_submit_requires "on_submit" :error_description_precedence ("semantics.error" "supporting_text") :logical_value_excludes ("prefix" "suffix" "mask-literals"))
  "Contract-projected §17.4 text-input constraint envelope.")

(defconst jetpacs-node-schema
  '(
    ("text" ("text") ("color" "font_weight" "max_lines" "selectable" "style" "syntax"))
    ("rich_text" ("spans") ("style"))
    ("icon" ("name") ("badge" "color" "content_description" "size"))
    ("image" ("url") ("content_description" "content_scale"))
    ("date_stamp" () ("day" "month" "month_index" "time" "year"))
    ("section_header" ("title") ("trailing"))
    ("empty_state" () ("action_label" "caption" "icon" "on_tap" "title"))
    ("progress" () ("value" "variant"))
    ("badge" ("label") ("children" "color" "icon"))
    ("row" ("children") ("align" "arrange" "content_padding" "fill" "overlap" "scroll" "spacing"))
    ("column" ("children") ("align" "arrange" "fill" "overlap" "reverse_scroll" "scroll" "spacing"))
    ("flow_row" ("children") ("align" "arrange" "run_spacing" "spacing"))
    ("box" ("children") ("alignment" "on_long_tap" "on_tap"))
    ("surface" ("children") ("color" "elevation" "shadow_elevation" "shape"))
    ("lazy_column" ("children") ("content_padding" "spacing"))
    ("variant_host" ("id" "value" "variants") ())
    ("spacer" () ())
    ("divider" () ("color" "thickness"))
    ("card" ("children") ("on_long_tap" "on_tap" "swipe_end" "swipe_start" "variant"))
    ("collapsible" ("children" "header" "id") ("collapsed" "on_long_tap" "swipe_end" "swipe_start"))
    ("reorderable_list" ("items") ("on_reorder"))
    ("tabs" ("children" "items") ("id" "indicator" "initial" "on_change" "pager_only" "scrollable" "style"))
    ("tab_selector" ("items" "on_change" "selected") ("id" "indicator" "scrollable" "style"))
    ("table" ("rows") ("aligns" "on_add_col" "on_add_row"))
    ("button" ("label" "on_tap") ("animate_shape" "checked" "checked_icon" "checked_shape" "color" "enabled" "expanded" "icon" "on_change" "shape" "shape_role" "size" "variant"))
    ("icon_button" ("icon" "on_tap") ("badge" "checked" "checked_icon" "color" "content_description" "enabled" "on_change" "shape" "size" "variant" "width_mode"))
    ("chip" ("label") ("avatar" "content_spacing" "enabled" "icon" "on_tap" "selected" "trailing_icon" "variant"))
    ("menu" () ("enabled" "footer" "groups" "icon" "initial_scroll" "items"))
    ("text_input" ("id") ("autofocus" "clear_on_submit" "content_padding" "enabled" "filter" "hide_keyboard_on_submit" "hint" "is_error" "keyboard" "label" "leading_icon" "mask" "max_length" "max_lines" "min_lines" "monospace" "on_change" "on_submit" "password" "prefix" "selection" "single_line" "suffix" "supporting_text" "syntax" "trailing_icon" "value" "variant"))
    ("editor" ("id") ("autofocus" "chromeless" "complete" "document" "enabled" "line_numbers" "max_lines" "min_lines" "on_enter" "on_save" "publish_state" "read_only" "single_line" "syntax" "toolbar" "value"))
    ("checkbox" ("id") ("checked" "enabled" "label" "on_change" "state" "stroke"))
    ("switch" ("id") ("checked" "enabled" "label" "on_change" "thumb_icon"))
    ("enum_list" ("id" "options") ("allow_add" "children" "enabled" "multi_select" "on_change" "value" "variant"))
    ("date_button" ("label" "on_pick") ("disabled_weekdays" "enabled" "max_date" "min_date" "mode" "value"))
    ("time_button" ("label" "on_pick") ("display_mode" "enabled" "value"))
    ("slider" ("id" "on_change") ("color" "color_end" "enabled" "max" "min" "orientation" "thumb_icon" "track" "track_icon_end" "track_icon_start" "value" "value_end" "value_label" "values"))
    ("chart" ("series") ("children" "height" "kind" "on_point_tap" "summary" "y_range"))
    ("canvas" ("height" "ops" "width") ("children"))
    ("month_grid" ("month") ("children" "day_styles" "disabled_weekdays" "marks" "max_date" "max_month" "min_date" "min_month" "on_day_tap" "on_month_change" "range_end" "range_start" "selected"))
    ("scaffold" () ("body" "bottom_bar" "bottom_bar_behavior" "drawer" "drawer_variant" "fab" "fab_hide_on_scroll" "fab_position" "floating_toolbar" "floating_toolbar_exit_direction" "floating_toolbar_expanded" "floating_toolbar_fab" "floating_toolbar_orientation" "floating_toolbar_placement" "floating_toolbar_scroll" "is_refreshing" "on_refresh" "on_sheet_change" "rail" "refresh_indicator" "scroll_behavior" "sheet" "sheet_peek_height" "sheet_state" "snackbar" "snackbar_action" "snackbar_content" "snackbar_dismiss" "snackbar_duration" "snackbar_max_lines" "top_bar" "top_bar_centered" "top_bar_collapsed_height" "top_bar_expanded" "top_bar_expanded_height" "top_bar_style" "top_bar_subtitle"))
    ("tooltip" ("children" "text") ("action_label" "caret" "caret_height" "caret_width" "on_action" "position" "rich" "shown" "title"))
    ("pane_scaffold" ("detail" "list") ("extra" "variant"))
    ("navigation_rail" ("items") ("arrangement" "expanded" "header" "hide_on_collapse" "on_expand_change" "variant"))
    ("search_bar" ("id") ("children" "enabled" "hint" "leading_icon" "on_change" "on_search" "trailing_icon" "value" "variant"))
    ("dropdown" ("id" "options") ("editable" "enabled" "hint" "label" "on_change" "report_caret" "value"))
    ("segmented_button" ("id" "options") ("enabled" "multi_select" "on_change" "value"))
    ("carousel" ("children") ("content_padding" "item_corner" "item_spacing" "item_width" "strategy"))
    ("button_group" ("items") ("overflow_icon"))
    ("lazy_grid" ("children") ("columns" "content_padding" "min_item_width" "reverse" "spacing")))
  "Contract members per node type: (TYPE (REQUIRED...) (OPTIONAL...)).
WIRE names, so the table compares directly against contract.json.  The
constructors spell a multi-word member with a hyphen (`:content-padding'
for `content_padding'); `jetpacs--wire-name' is the map between them.")

(provide 'jetpacs-vocabulary)
;;; jetpacs-vocabulary.el ends here
