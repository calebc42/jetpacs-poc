# Actions and handlers

An action is a dotted identifier the Companion dispatches back; the handler schedules a durable effect and answers immediately.

## Contents

- Action names are dotted EBP identifiers, registered with `jetpacs-defaction` and emitted with `jetpacs-action`; args are plain data.
- A handler takes `(ARGS PARAMS)`, schedules its effect and returns `accepted`, `stale` or `rejected`.
- No blocking call and no prompt inside a dispatch; confirmation belongs on the descriptor.
- Validate with `jetpacs_validate_applet` and the shell gates; never guess a member the catalog does not list.

---

## Action names are dotted EBP identifiers, registered with `jetpacs-defaction` and emitted with `jetpacs-action`; args are plain data

**Incorrect:**

```elisp
(jetpacs-button "Save" (jetpacs-action "save"))
```

**Correct:**

```elisp
(jetpacs-defaction "notes.save" #'notes--on-save
                   :doc "Save the open note"
                   :args '(:id "the note's id"))

(jetpacs-button "Save" (jetpacs-action "notes.save" :args (list :id id)))
```

The validator refuses an emitted name without a provider and a name without a namespace dot; `:doc` and `:args` are what the catalog and the agent read.

## A handler takes `(ARGS PARAMS)`, schedules its effect and returns `accepted`, `stale` or `rejected`

**Incorrect:**

```elisp
(defun notes--on-save (args)
  (notes--write-to-disk args)
  t)
```

**Correct:**

```elisp
(defun notes--on-save (args params)
  (jetpacs-flow-continue
   (lambda () (notes--write-to-disk args)))
  'accepted)
```

`accepted` means the effect is durable or owned by a durable work item; anything else is a protocol lie (SPEC 14.4). The return value is a symbol, not `t`.

## No blocking call and no prompt inside a dispatch; confirmation belongs on the descriptor

**Incorrect:**

```elisp
(defun notes--on-delete (args _params)
  (when (y-or-n-p "Delete?")
    (url-retrieve-synchronously (notes--url args)))
  'accepted)
```

**Correct:**

```elisp
(jetpacs-button "Delete"
                (jetpacs-action "notes.delete" :args (list :id id)
                                :confirm "Delete this note?"))

(defun notes--on-delete (args _params)
  (jetpacs-flow-continue (lambda () (notes--delete args)))
  'accepted)
```

A prompt inside a dispatch blocks the JSON-RPC reader; the validator marks `action.prompt-in-dispatch` an error and `action.blocking-call` a warning. `jetpacs-flow-continue` runs outside the dispatch with the flow identity kept.

## Validate with `jetpacs_validate_applet` and the shell gates; never guess a member the catalog does not list

**Incorrect:**

```elisp
(jetpacs-button "Go" (jetpacs-action "app.go") :tooltip "Go now")
```

**Correct:**

```elisp
(jetpacs-button "Go" (jetpacs-action "app.go") :icon "arrow_forward")
```

A member outside the contract is `1201 content-invalid` on the wire; `jetpacs_describe_node` and `jetpacs_node_examples` show what exists.
