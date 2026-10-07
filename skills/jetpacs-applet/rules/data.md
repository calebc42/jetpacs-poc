# Data document, templates and conditional nodes

Values live in the surface data document and nodes bind to them; a handler patches the document and the surface is re-pushed only when its shape changes.

## Contents

- Bind a value with `jetpacs-ref` and patch it; do not bake it into the node and re-push.
- Expand a list with `jetpacs-template` over a bound array; key every row from the item.
- Hide a node with `:visible_when` on a keyed child; keep siblings keyed and nothing stateful beneath it.
- Patch with `jetpacs-app-defer-patch` or `-sync`; refresh only when the shape changed.

---

## Bind a value with `jetpacs-ref` and patch it; do not bake it into the node and re-push

**Incorrect:**

```elisp
(jetpacs-text (notes--title))
;; later, on every change:
(jetpacs-app-defer-refresh params)
```

**Correct:**

```elisp
(jetpacs-text (jetpacs-ref "/title"))
;; later, in the handler:
(jetpacs-app-defer-patch (list (jetpacs-data-set "/title" new-title)) params)
```

A patch is a few hundred bytes and keeps scroll and drafts; a re-push resends the whole tree (SPEC 13.8.3-13.8.4).

## Expand a list with `jetpacs-template` over a bound array; key every row from the item

**Incorrect:**

```elisp
(apply #'jetpacs-lazy-column
       (mapcar (lambda (row) (jetpacs-text (plist-get row :title))) rows))
```

**Correct:**

```elisp
(jetpacs-lazy-column
 (jetpacs-template "/rows"
   (jetpacs-with-attrs (jetpacs-text (jetpacs-ref "title"))
                       :key (jetpacs-ref "id"))))
```

Inside the template a `jetpacs-ref` without a leading slash resolves from the item; the row must bind `:key` and may not author `:key` or `:id`. A Companion without `data.template` receives the expansion from Emacs (SPEC 13.8.5).

## Hide a node with `:visible_when` on a keyed child; keep siblings keyed and nothing stateful beneath it

**Incorrect:**

```elisp
(jetpacs-column
 (jetpacs-text "Settings")
 (jetpacs-with-attrs (jetpacs-text-input "name")
                     :visible_when (vector (jetpacs-when "/editing" "eq" t))))
```

**Correct:**

```elisp
(jetpacs-column
 (jetpacs-with-attrs (jetpacs-text "Settings") :key "title")
 (jetpacs-with-attrs (jetpacs-text "Unsaved changes" :color "error")
                     :key "warning"
                     :visible_when (vector (jetpacs-when "/dirty" "eq" t))))
```

`visible_when` is a flat AND of bounded predicates evaluated before bindings; the children array holding it is keyed throughout, and an input, editor, `variant_host` or `id` beneath it would lose state when it flips (SPEC 13.8.6, feature `data.visible`).

## Patch with `jetpacs-app-defer-patch` or `-sync`; refresh only when the shape changed

**Incorrect:**

```elisp
(defun notes--on-toggle (args params)
  (notes--toggle (plist-get args :id))
  (jetpacs-app-defer-refresh params)
  'accepted)
```

**Correct:**

```elisp
(defun notes--on-toggle (args params)
  (notes--toggle (plist-get args :id))
  (jetpacs-app-defer-sync "/rows" (notes--rows) params)
  'accepted)
```

`jetpacs-app-defer-sync` diffs a keyed array into `insert`/`set`/`remove` ops; a refresh is for a new screen, not a changed value.
