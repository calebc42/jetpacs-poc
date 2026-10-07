# Adding applets from a registry

An applet registry is an index of installable applets with declared requirements; the tools fetch, verify and preview, and only the App Store's consent gate runs code.

## Contents

- Registry must be explicit: ask which registry when the user does not say; never default to one.
- Resolve and dry-run before any install; read the diff of every file that would be overwritten.
- Never pass --overwrite, or confirm an overwrite on the device, without the user's explicit approval.
- Never fetch or load registry Elisp yourself; the tools verify sha256 and read with a throwaway obarray, and only the App Store's consent gate loads.

---

## Registry must be explicit: ask which registry when the user does not say; never default to one

**Incorrect:**

```elisp
;; "Add a login applet"
(jetpacs_registry_install_command :items ["login"])   ; silently @jetpacs
```

**Correct:**

```elisp
;; "Add a login applet"
;; -> ask: from which registry? (jetpacs_registry_namespaces lists them)
(jetpacs_registry_search :query "login" :registry "@acme")
```

A bare name resolves in the default namespace; the user, not the agent, decides where code comes from.

## Resolve and dry-run before any install; read the diff of every file that would be overwritten

**Incorrect:**

```elisp
sh tools/jetpacs-registry.sh install @acme/login --overwrite
```

**Correct:**

```elisp
sh tools/jetpacs-registry.sh dry-run @acme/login
sh tools/jetpacs-registry.sh diff @acme/login
sh tools/jetpacs-registry.sh install @acme/login
```

`dry-run` classifies every file as create, overwrite or skip and checks requirements; `diff` shows what an overwrite would change. Install refuses an overwrite it was not told about.

## Never pass --overwrite, or confirm an overwrite on the device, without the user's explicit approval

**Incorrect:**

```elisp
;; the user said "update the login applet"
sh tools/jetpacs-registry.sh install @acme/login --overwrite
```

**Correct:**

```elisp
;; show the diff, then ask: replace your local changes to login.el?
;; only on a yes:
sh tools/jetpacs-registry.sh install @acme/login --overwrite
```

A local change is the user's work; the registry's copy is not automatically better. The Apps screen enforces the same rule: an overwrite needs a Preview first.

## Never fetch or load registry Elisp yourself; the tools verify sha256 and read with a throwaway obarray, and only the App Store's consent gate loads

**Incorrect:**

```elisp
(load (url-retrieve-synchronously "https://acme.test/r/login.json"))
```

**Correct:**

```elisp
(jetpacs_registry_view :items ["@acme/login"])   ; sha-verified, for reading
;; then the dry-run / install commands above
```

Installing is running code. The registry path keeps every step static until the user's consent on the device; a manual fetch skips the sha pin, the safe read and the gate.
