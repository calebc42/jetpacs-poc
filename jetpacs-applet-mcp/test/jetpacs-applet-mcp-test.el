;;; jetpacs-applet-mcp-test.el --- Protocol tests for Jetpacs Applet MCP -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later

(require 'ert)
(require 'json)

(setq jetpacs-applet-mcp-suppress-auto-start t)
(let* ((test-dir (file-name-directory (or load-file-name buffer-file-name)))
       (root (expand-file-name "../.." test-dir)))
  (setq jetpacs-applet-tooling-workspace-root root)
  (load (expand-file-name "jetpacs-applet-mcp/jetpacs-applet-mcp.el" root) nil 'nomessage))

(defun jetpacs-applet-mcp-test--json (line)
  (json-parse-string line :object-type 'hash-table :array-type 'array
                     :null-object :json-null :false-object :json-false))

(defun jetpacs-applet-mcp-test--initialize ()
  (setq jetpacs-applet-mcp--initialized nil
        jetpacs-applet-mcp--initialize-seen nil)
  (jetpacs-applet-mcp-handle-line
   "{\"jsonrpc\":\"2.0\",\"id\":\"init\",\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},\"clientInfo\":{\"name\":\"ert\",\"version\":\"1\"}}}")
  (jetpacs-applet-mcp-handle-line
   "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"))

(ert-deftest jetpacs-applet-mcp-test-initialize-preserves-string-id ()
  (setq jetpacs-applet-mcp--initialized nil
        jetpacs-applet-mcp--initialize-seen nil)
  (let* ((response
          (jetpacs-applet-mcp-test--json
           (jetpacs-applet-mcp-handle-line
            "{\"jsonrpc\":\"2.0\",\"id\":\"request-a\",\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},\"clientInfo\":{\"name\":\"ert\",\"version\":\"1\"}}}")))
         (result (gethash "result" response))
         (server-info (gethash "serverInfo" result)))
    (should (equal (gethash "id" response) "request-a"))
    (should (equal (gethash "protocolVersion" result) "2025-11-25"))
    (should (equal (gethash "name" server-info) "jetpacs-applet-mcp"))
    (should (equal (gethash "title" server-info) "Jetpacs Applet MCP"))
    (should (string-match-p
             "jetpacs://applets/implementation-guide"
             (gethash "instructions" result)))
    (should (hash-table-p (gethash "tools" (gethash "capabilities" result))))))

(ert-deftest jetpacs-applet-mcp-test-requires-initialized-notification ()
  (setq jetpacs-applet-mcp--initialized nil
        jetpacs-applet-mcp--initialize-seen nil)
  (let* ((response
          (jetpacs-applet-mcp-test--json
           (jetpacs-applet-mcp-handle-line
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}")))
         (error (gethash "error" response)))
    (should (= (gethash "code" error) -32002))))

(ert-deftest jetpacs-applet-mcp-test-initialized-cannot-bypass-initialize ()
  (setq jetpacs-applet-mcp--initialized nil
        jetpacs-applet-mcp--initialize-seen nil)
  (jetpacs-applet-mcp-handle-line
   "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")
  (let* ((response
          (jetpacs-applet-mcp-test--json
           (jetpacs-applet-mcp-handle-line
            "{\"jsonrpc\":\"2.0\",\"id\":21,\"method\":\"tools/list\"}")))
         (error (gethash "error" response)))
    (should (= (gethash "code" error) -32002))))

(ert-deftest jetpacs-applet-mcp-test-initialize-validates-required-client-fields ()
  (setq jetpacs-applet-mcp--initialized nil
        jetpacs-applet-mcp--initialize-seen nil)
  (let* ((response
          (jetpacs-applet-mcp-test--json
           (jetpacs-applet-mcp-handle-line
            "{\"jsonrpc\":\"2.0\",\"id\":22,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{}}}")))
         (error (gethash "error" response)))
    (should (= (gethash "code" error) -32602))))

(ert-deftest jetpacs-applet-mcp-test-tool-list-is-read-only-and-has-no-evaluator ()
  (jetpacs-applet-mcp-test--initialize)
  (let* ((response
          (jetpacs-applet-mcp-test--json
           (jetpacs-applet-mcp-handle-line
            "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/list\"}")))
         (tools (gethash "tools" (gethash "result" response)))
         (names (mapcar (lambda (tool) (gethash "name" tool)) tools))
         (manifest (seq-find
                    (lambda (tool)
                      (equal (gethash "name" tool)
                             "jetpacs_applet_manifest"))
                    tools)))
    (should (member "jetpacs_validate_applet" names))
    (should (member "jetpacs_applet_manifest" names))
    (should (member "jetpacs_describe_node" names))
    (should (member "jetpacs_describe_actions" names))
    (should-not (member "eval_elisp" names))
    (should-not (member "macroexpand" names))
    (let ((properties (gethash "properties"
                               (gethash "inputSchema" manifest))))
      (dolist (name '("section" "offset" "limit"))
        (should (hash-table-p (gethash name properties)))))
    (dolist (tool (append tools nil))
      (should (> (length (gethash "description" tool)) 40))
      (should (not (string-empty-p (gethash "title" tool))))
      (should (eq (gethash "additionalProperties"
                           (gethash "inputSchema" tool))
                  :json-false))
      (should (eq (gethash "readOnlyHint" (gethash "annotations" tool)) t)))))

(ert-deftest jetpacs-applet-mcp-test-action-model-tool-is-source-derived ()
  (jetpacs-applet-mcp-test--initialize)
  (let* ((response
          (jetpacs-applet-mcp-test--json
           (jetpacs-applet-mcp-handle-line
            "{\"jsonrpc\":\"2.0\",\"id\":31,\"method\":\"tools/call\",\"params\":{\"name\":\"jetpacs_describe_actions\",\"arguments\":{}}}")))
         (result (gethash "result" response))
         (text (gethash "text" (aref (gethash "content" result) 0))))
    (should (string-match-p "Remote semantic actions are open-world" text))
    (should (string-match-p "Native builtins" text))
    (should (string-match-p "jetpacs-surface-open" text))))

(ert-deftest jetpacs-applet-mcp-test-tool-errors-are-visible-to-the-model ()
  (jetpacs-applet-mcp-test--initialize)
  (let* ((response
          (jetpacs-applet-mcp-test--json
           (jetpacs-applet-mcp-handle-line
            "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"jetpacs_describe_node\",\"arguments\":{\"nodeType\":\"not_a_node\"}}}")))
         (result (gethash "result" response)))
    (should (eq (gethash "isError" result) t))
    (should (string-match-p
             "unknown EBP node type"
             (gethash "text" (aref (gethash "content" result) 0))))))

(ert-deftest jetpacs-applet-mcp-test-unknown-tool-is-a-protocol-error ()
  (jetpacs-applet-mcp-test--initialize)
  (let* ((response
          (jetpacs-applet-mcp-test--json
           (jetpacs-applet-mcp-handle-line
            "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\",\"params\":{\"name\":\"missing\",\"arguments\":{}}}")))
         (error (gethash "error" response)))
    (should (= (gethash "code" error) -32602))))

(ert-deftest jetpacs-applet-mcp-test-resources-and-prompts-are-discoverable ()
  (jetpacs-applet-mcp-test--initialize)
  (let* ((resources
          (jetpacs-applet-mcp-test--json
           (jetpacs-applet-mcp-handle-line
            "{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"resources/list\"}")))
         (prompts
          (jetpacs-applet-mcp-test--json
           (jetpacs-applet-mcp-handle-line
            "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"prompts/list\"}"))))
    (should (= (length (gethash "resources" (gethash "result" resources))) 8))
    (should (= (length (gethash "prompts" (gethash "result" prompts))) 3))))

(ert-deftest jetpacs-applet-mcp-test-resources-and-prompts-are-readable ()
  (jetpacs-applet-mcp-test--initialize)
  (let* ((resource
          (jetpacs-applet-mcp-test--json
           (jetpacs-applet-mcp-handle-line
            "{\"jsonrpc\":\"2.0\",\"id\":23,\"method\":\"resources/read\",\"params\":{\"uri\":\"jetpacs://applets/guide\"}}")))
         (contents (gethash "contents" (gethash "result" resource)))
         (determinism
          (jetpacs-applet-mcp-test--json
           (jetpacs-applet-mcp-handle-line
            "{\"jsonrpc\":\"2.0\",\"id\":25,\"method\":\"resources/read\",\"params\":{\"uri\":\"jetpacs://applets/determinism\"}}")))
         (determinism-contents
          (gethash "contents" (gethash "result" determinism)))
         (implementation
          (jetpacs-applet-mcp-test--json
           (jetpacs-applet-mcp-handle-line
            "{\"jsonrpc\":\"2.0\",\"id\":26,\"method\":\"resources/read\",\"params\":{\"uri\":\"jetpacs://applets/implementation-guide\"}}")))
         (implementation-text
          (gethash "text"
                   (aref (gethash "contents" (gethash "result" implementation))
                         0)))
         (skins
          (jetpacs-applet-mcp-test--json
           (jetpacs-applet-mcp-handle-line
            "{\"jsonrpc\":\"2.0\",\"id\":27,\"method\":\"resources/read\",\"params\":{\"uri\":\"jetpacs://applets/package-skins\"}}")))
         (skins-text
          (gethash "text"
                   (aref (gethash "contents" (gethash "result" skins)) 0)))
         (prompt
          (jetpacs-applet-mcp-test--json
           (jetpacs-applet-mcp-handle-line
            "{\"jsonrpc\":\"2.0\",\"id\":24,\"method\":\"prompts/get\",\"params\":{\"name\":\"create_jetpacs_applet\",\"arguments\":{\"id\":\"notes\",\"goal\":\"Capture a note\"}}}")))
         (messages (gethash "messages" (gethash "result" prompt))))
    (should (string-match-p "Building a Jetpacs applet"
                            (gethash "text" (aref contents 0))))
    (should (string-match-p "one screen representation"
                            (gethash "text"
                                     (aref determinism-contents 0))))
    (should (< (string-match "Jetpacs implementation guide" implementation-text)
               (string-match "Applet-tooling implementation guide"
                             implementation-text)))
    (should (string-match-p "existing package" skins-text))
    (should (string-match-p "Do not rearchitect the MCP" skins-text))
    (should (string-match-p "Capture a note"
                            (gethash "text" (gethash "content" (aref messages 0)))))))

(ert-deftest jetpacs-applet-mcp-test-package-skin-prompt-is-specific ()
  (jetpacs-applet-mcp-test--initialize)
  (let* ((response
          (jetpacs-applet-mcp-test--json
           (jetpacs-applet-mcp-handle-line
            "{\"jsonrpc\":\"2.0\",\"id\":28,\"method\":\"prompts/get\",\"params\":{\"name\":\"skin_emacs_package\",\"arguments\":{\"id\":\"git\",\"package\":\"Magit\",\"goal\":\"Review and stage changes\"}}}")))
         (messages (gethash "messages" (gethash "result" response)))
         (text (gethash "text" (gethash "content" (aref messages 0)))))
    (should (string-match-p "Magit" text))
    (should (string-match-p "Review and stage changes" text))
    (should (string-match-p "package-skins" text))))

(ert-deftest jetpacs-applet-mcp-test-invalid-id-uses-null-response-id ()
  (let* ((response
          (jetpacs-applet-mcp-test--json
           (jetpacs-applet-mcp-handle-line
            "{\"jsonrpc\":\"2.0\",\"id\":true,\"method\":\"ping\"}")))
         (error (gethash "error" response)))
    (should (eq (gethash "id" response) :json-null))
    (should (= (gethash "code" error) -32600))))

(ert-deftest jetpacs-applet-mcp-test-parse-error-uses-null-id ()
  (let* ((response (jetpacs-applet-mcp-test--json
                    (jetpacs-applet-mcp-handle-line "{")))
         (error (gethash "error" response)))
    (should (eq (gethash "id" response) :json-null))
    (should (= (gethash "code" error) -32700))))

(provide 'jetpacs-applet-mcp-test)
;;; jetpacs-applet-mcp-test.el ends here

(ert-deftest jetpacs-applet-mcp-test-registry-tools-search-resolve-and-command ()
  "The registry tools list, search, view and resolve the checkout's own registry.
Fetching tools are open-world and still read-only; nothing writes."
  (jetpacs-applet-mcp-test--initialize)
  (let* ((tools (gethash "tools"
                         (gethash "result"
                                  (jetpacs-applet-mcp-test--json
                                   (jetpacs-applet-mcp-handle-line
                                    "{\"jsonrpc\":\"2.0\",\"id\":50,\"method\":\"tools/list\"}")))))
         (tool (lambda (name) (seq-find (lambda (tool) (equal (gethash "name" tool) name)) tools))))
    (dolist (name '("jetpacs_registry_namespaces" "jetpacs_registry_list" "jetpacs_registry_search"
                    "jetpacs_registry_view" "jetpacs_registry_resolve" "jetpacs_registry_validate"
                    "jetpacs_registry_install_command"))
      (should (funcall tool name))
      (should (eq t (gethash "readOnlyHint" (gethash "annotations" (funcall tool name))))))
    (should (eq t (gethash "openWorldHint" (gethash "annotations" (funcall tool "jetpacs_registry_search")))))
    (should (eq :json-false
                (gethash "openWorldHint" (gethash "annotations" (funcall tool "jetpacs_registry_install_command"))))))
  (cl-flet ((call (id name arguments)
              (let ((response (jetpacs-applet-mcp-test--json
                               (jetpacs-applet-mcp-handle-line
                                (format "{\"jsonrpc\":\"2.0\",\"id\":%d,\"method\":\"tools/call\",\"params\":{\"name\":%S,\"arguments\":%s}}"
                                        id name arguments)))))
                (gethash "text" (aref (gethash "content" (gethash "result" response)) 0)))))
    (should (string-match-p "^- @jetpacs: file://" (call 51 "jetpacs_registry_namespaces" "{}")))
    (should (string-match-p "^- @jetpacs/hello 0.1.0" (call 52 "jetpacs_registry_list" "{}")))
    (should (string-match-p "@jetpacs/hello" (call 53 "jetpacs_registry_search" "{\"query\":\"minimal applet\"}")))
    (should (string-match-p "^No item matches" (call 54 "jetpacs_registry_search" "{\"query\":\"xyzzy\"}")))
    (should (string-match-p "^--- hello.el (sha256 " (call 55 "jetpacs_registry_view" "{\"items\":[\"hello\"]}")))
    (let ((dir (make-temp-file "jetpacs-mcp-registry" t)))
      (unwind-protect
          (let ((text (call 56 "jetpacs_registry_resolve"
                            (format "{\"items\":[\"hello\"],\"installDir\":%S}" dir))))
            (should (string-match-p "^create +.*hello.el" text))
            (should (string-match-p "Installable" text))
            (should-not (file-exists-p (expand-file-name "hello.el" dir))))
        (delete-directory dir t)))
    (should (string-match-p "^Status: PASS"
                            (call 57 "jetpacs_registry_validate"
                                  "{\"registryFile\":\"jetpacs-applet-mcp/registry/registry.json\"}")))
    (should (string-match-p "dry-run hello\n.*diff hello\n.*install hello"
                            (call 58 "jetpacs_registry_install_command" "{\"items\":[\"hello\"]}")))))

(ert-deftest jetpacs-applet-mcp-test-catalog-tool-and-resources ()
  "The catalog is a read-only tool and two readable resources."
  (jetpacs-applet-mcp-test--initialize)
  (let* ((tools (jetpacs-applet-mcp-test--json
                 (jetpacs-applet-mcp-handle-line
                  "{\"jsonrpc\":\"2.0\",\"id\":40,\"method\":\"tools/list\"}")))
         (catalog (seq-find (lambda (tool) (equal (gethash "name" tool) "jetpacs_catalog"))
                            (gethash "tools" (gethash "result" tools)))))
    (should catalog)
    (should (eq t (gethash "readOnlyHint" (gethash "annotations" catalog)))))
  (let* ((call (jetpacs-applet-mcp-test--json
                (jetpacs-applet-mcp-handle-line
                 "{\"jsonrpc\":\"2.0\",\"id\":41,\"method\":\"tools/call\",\"params\":{\"name\":\"jetpacs_catalog\",\"arguments\":{\"section\":\"nodes\"}}}")))
         (text (gethash "text" (aref (gethash "content" (gethash "result" call)) 0))))
    (should (string-match-p "^- material3.assist_chip: {" text))
    (should-not (string-match-p "^## Rules" text)))
  (let* ((markdown (jetpacs-applet-mcp-test--json
                    (jetpacs-applet-mcp-handle-line
                     "{\"jsonrpc\":\"2.0\",\"id\":42,\"method\":\"resources/read\",\"params\":{\"uri\":\"jetpacs://applets/catalog\"}}")))
         (json (jetpacs-applet-mcp-test--json
                (jetpacs-applet-mcp-handle-line
                 "{\"jsonrpc\":\"2.0\",\"id\":43,\"method\":\"resources/read\",\"params\":{\"uri\":\"jetpacs://applets/catalog.json\"}}"))))
    (should (string-prefix-p "# Jetpacs catalog"
                             (gethash "text" (aref (gethash "contents" (gethash "result" markdown)) 0))))
    (should (equal "application/json"
                   (gethash "mimeType" (aref (gethash "contents" (gethash "result" json)) 0))))
    (should (string-prefix-p "{\"catalog_format\":2,"
                             (gethash "text" (aref (gethash "contents" (gethash "result" json)) 0))))))

