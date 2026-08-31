# Jetpacs Automations

Jetpacs Automations is the GUI-over-Lisp workflow editor.  A saved automation
is ordinary, versioned Lisp data; the Inspector, Tree, and Canonical Lisp
screens are projections of the same normalized recipe rather than independent
representations.  Canonical Lisp is data, never code: it is read as exactly one
bounded form, schema-checked, and interpreted by a closed expression evaluator.

Recipes are saved through Customize in `jetpacs-automation-recipes`.
Activation is deliberately separate in `jetpacs-automation-enabled-recipes`,
so Save never arms a trigger.  A draft must pass Dry Run at its current digest
before it can be saved.  Structured edits regenerate canonical Lisp; a valid
Lisp edit replaces the structured draft, while malformed or unsupported data
remains an isolated editor error and cannot replace the last valid recipe.

## Recipe shape

The version-1 envelope is:

```elisp
(:schema-version 1
 :id "user.morning"
 :name "Morning"
 :inputs [(:name "greeting" :type "text" :value "Good morning")]
 :trigger (:type "time"
           :params (:every-s 3600)
           :when []
           :policy :queue
           :ttl-s 86400)
 :steps [(:id "notify"
          :kind :action
          :action "device.notify"
          :args (:text (:expr
                        (concat (ref "input.greeting") "!"))))])
```

Steps are ordered action nodes or nested `if`/`else` nodes.  Expressions may
read immutable recipe inputs, trigger data, run metadata, and outputs from
lexically prior steps.  They cannot mutate variables, call arbitrary Emacs
functions, perform I/O, or evaluate source.

## Execution boundary

The compiler marks the maximal leading sequence supported by EBP `on_fire` as
**On device**.  The Companion durably admits a queue-policy occurrence and runs
that prefix while Emacs is disconnected.  The replayed `trigger.fired` event is
then admitted to an independent Emacs work inbox before Jetpacs returns
`accepted`; remaining steps run **After reconnect** against the frozen recipe
revision that fired.  Device-local effects are reported as `dispatched`, not
as proven successes, because EBP deliberately isolates their platform
failures.

The editor is capability-aware.  It preserves portable trigger and action
descriptors that the current Companion does not advertise, labels them
unavailable, and refuses activation rather than weakening the recipe.
`jetpacs-defaction` registrations are not automatically headless-safe;
application packages must opt into the separate automation action registry.

## Safety and lifecycle

- New recipes are disabled until explicitly enabled.
- Dry Run is pure and cannot invoke an action executor.
- Execution-relevant edits disable before replacing an armed definition.
- Disabling while disconnected is persisted but remains pending on the device
  until the next successful full-set reconciliation.
- Runs are FIFO per recipe, while different recipes may progress separately.
- Ordinary failures stop the run.  Session-scoped capability calls with an
  indeterminate outcome are never retried automatically.
- Durable history retains only bounded paths, timestamps, statuses, and error
  kinds.  Raw trigger data and outputs are operational work state and are
  erased when work completes.

The app uses existing canonical EBP controls and requires no new renderer
extension or Android UI vocabulary.
