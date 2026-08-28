;;; jetpacs-renderer-registry.el --- installed renderer extension state -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later
;; Package-Requires: ((emacs "30.1"))

;;; Commentary:

;; Jetpacs owns this implementation-side registry.  Optional presentation
;; modules install their schemas here; EBP negotiates only opaque extension
;; identifiers and does not know any concrete renderer or design system.

;;; Code:

(defvar jetpacs-renderer-extensions nil
  "Installed renderer ownership as (EXTENSION . NODE-TYPES).")

(defvar jetpacs-renderer-extension-node-schema nil
  "Installed renderer schemas as (TYPE (REQUIRED...) (OPTIONAL...)).")

(defvar jetpacs-renderer-extension-targets nil
  "Installed renderer node sets as (TARGET . NODE-TYPES).")

(provide 'jetpacs-renderer-registry)
;;; jetpacs-renderer-registry.el ends here
