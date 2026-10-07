# IR and builders

A builder returns the plist/vector screen IR and nothing else: the same datum is inspected, gated and serialized. No parallel AST, no codegen, no effects on the way.

## Contents

- Builders are pure readers of explicit state and return the screen IR; effects belong in actions.
- Build nodes with the constructors, never as hand-written wire plists.
- Children are passed as arguments to a container constructor; the IR is data, not text.
- Node ids are unique per document and sibling keys per parent; identity is authored, never generated per render.

---

## Builders are pure readers of explicit state and return the screen IR; effects belong in actions

**Incorrect:**

```elisp
(defun notes--view ()
  (notes--reload-from-disk)          ; an effect inside a builder
  (jetpacs-text (notes--title)))
```

**Correct:**

```elisp
(defun notes--view ()
  (jetpacs-text (notes--title)))

(jetpacs-defaction "notes.reload" #'notes--on-reload
                   :doc "Reload the notes from disk")
```

A builder may run on any push, replay or cache check; an effect inside it runs that often too and is never acknowledged.

## Build nodes with the constructors, never as hand-written wire plists

**Incorrect:**

```elisp
(list :t "button" :label "Go" :on_tap (list :action "app.go"))
```

**Correct:**

```elisp
(jetpacs-button "Go" (jetpacs-action "app.go"))
```

The constructors check members, enum domains and identifiers against the contract; a literal plist skips every check and drifts with the next amendment.

## Children are passed as arguments to a container constructor; the IR is data, not text

**Incorrect:**

```elisp
(format "{\"t\":\"column\",\"children\":[%s]}" rows)
```

**Correct:**

```elisp
(jetpacs-column (jetpacs-text "A") (jetpacs-text "B") :spacing 8)
```

Serialization belongs to the shell. DETERMINISM.md forbids a parallel AST or a code generator; the builder's plist is the one IR.

## Node ids are unique per document and sibling keys per parent; identity is authored, never generated per render

**Incorrect:**

```elisp
(jetpacs-with-attrs row :id (format "row-%d" (random 1000)))
```

**Correct:**

```elisp
(jetpacs-with-attrs row :key (plist-get item :id))
```

A generated identity changes on every build, so the Companion sees a new node each time: focus, scroll and drafts are lost and diffs degrade to replacements (SPEC 16.1).
