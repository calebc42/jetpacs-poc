# Renderer-extension manifests

This directory owns downstream renderer vocabulary that EBP transports but
does not define. Each JSON manifest is the sole source for its Kotlin receiver
projection and Elisp authoring projection; the adjacent golden file supplies
accepted semantic witnesses for that extension.

The current implementations are independent:

| Extension | Owner | Targets |
|---|---|---|
| `glasspane.material3` | Glasspane's Material library and catalog | app, dialog |
| `jetpacs.components` | Jetpacs' Material-free component library and catalog | app only |

An extension node is usable only when its node type and its owning extension
are both advertised for the target, and the app declares the extension in
`:requires-extensions`. Namespaces do not imply support. EBP itself treats the
identifiers as opaque negotiated data.

## Projection

The generator retains no-argument compatibility for the established
Glasspane manifest:

```sh
python3 tools/gen-renderer-extension-vocabulary.py --check
```

Every other manifest supplies explicit destinations. For Jetpacs Components:

```sh
python3 tools/gen-renderer-extension-vocabulary.py \
  renderer-extensions/jetpacs-components.json \
  --kotlin-output companion/renderer/jetpacs/src/main/kotlin/com/calebc42/jetpacs/renderer/jetpacs/JetpacsComponentsVocabulary.kt \
  --kotlin-package com.calebc42.jetpacs.renderer.jetpacs \
  --elisp-output emacs/apps/jetpacs-components/jetpacs-components-vocabulary.el \
  --check
```

Omit `--check` only when intentionally regenerating after changing the
manifest. Never edit either generated vocabulary file by hand.
