# jetpacs-automations

Downstream GUI-over-Lisp automation applet for Jetpacs. The module owns
the inert automation model, durable runtime integration, app surfaces, and
their ERT suites. It consumes `ebp.el`, `jetpacs-authoring`, and the public
Jetpacs host API.

```sh
test/run-tests.sh
```

This directory is a module of `jetpacs-poc`; it needs no separate Git remote.
Run the commands above from this directory. External EBP dependencies remain
sibling repositories of `jetpacs-poc`; `JETPACS_REPOSITORIES_ROOT` can select
another checkout collection. Runtime feature and extension names are unchanged.
