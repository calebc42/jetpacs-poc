# EBP POC repositories

This directory contains the generated and LLM-assisted EBP proof-of-concept
repositories. Each child is an independent Git repository with one authority:

| Repository | Authority |
|---|---|
| `ebp/` | Protocol specification, contract projection, goldens, and conformance tools |
| `ebp.el/` | Canonical Emacs implementation of the POC protocol |
| `ebp-kmp/` | Canonical Kotlin Multiplatform implementation of the POC protocol |
| `ebp-compose/` | Compose-neutral renderer model and Foundation implementation |
| `ebp-org/` | Reusable Org integration built on the public `ebp.el` API |

`ebp/` is the only active specification checkout in this POC. The former copy
embedded in Jetpacs was consolidated into it on 2026-08-31. A future hand
rewrite belongs in a new repository outside this POC tree and must not treat
these generated implementations as source authority.
