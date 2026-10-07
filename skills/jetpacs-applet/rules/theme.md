# Theme, colors and extensions

Colors are theme roles the Companion resolves for its own light or dark scheme; extension nodes travel only where the profile advertises them.

## Contents

- Color with a theme role, never a hex literal.
- Never author per-mode colors; the role adapts to dark mode.
- Derived tokens are not EBP names; use a role, not a Material or design token.
- An extension node needs its extension installed and advertised by the target profile; fall back when it is not.

---

## Color with a theme role, never a hex literal

**Incorrect:**

```elisp
(jetpacs-text "Overdue" :color "#ff0000")
```

**Correct:**

```elisp
(jetpacs-text "Overdue" :color "error")
```

The thirteen roles (`primary`, `on_primary`, `secondary`, `on_secondary`, `error`, `on_error`, `background`, `on_background`, `surface`, `on_surface`, `outline`, `success`, `warning`) follow the device's scheme and `theme.set`; a literal ignores both (SPEC 16.6, 18.4).

## Never author per-mode colors; the role adapts to dark mode

**Incorrect:**

```elisp
(jetpacs-text label :color (if (notes--dark-p) "#ffffff" "#000000"))
```

**Correct:**

```elisp
(jetpacs-text label :color "on_background")
```

Omitting `dark` in `theme.set` follows the system; the Companion derives a legible on-color when one is missing. Emacs never knows which mode is showing.

## Derived tokens are not EBP names; use a role, not a Material or design token

**Incorrect:**

```elisp
(jetpacs-text label :color "on_primary_container")
```

**Correct:**

```elisp
(jetpacs-text label :color "on_primary")
```

SPEC 18.4: derived tokens MUST NOT be accepted as `colors` members. Design tokens live in a `jetpacs.design_scope`, which is an extension node, not a color.

## An extension node needs its extension installed and advertised by the target profile; fall back when it is not

**Incorrect:**

```elisp
(jetpacs-design-profile-scope profile children)
```

**Correct:**

```elisp
(if (jetpacs-extension-advertised-p "jetpacs.design" :app)
    (jetpacs-design-profile-scope profile children)
  (jetpacs-column children))
```

A node travels only to a target whose profile lists both the node type and the extension (SPEC 16.2.1); `jetpacs-defapp :requires-extensions` declares the hard requirement and the Apps screen gates on it.
