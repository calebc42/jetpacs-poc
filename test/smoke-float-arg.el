;;; smoke-float-arg.el --- RF-2b: a float-authored action arg echoes intact -*- lexical-binding: t; -*-

;; The SPEC 17.4 echo fix, on hardware. Pre-swap the Companion deep-copied
;; an action descriptor's `args` through `JSONObject(x.toString())`, and
;; org.json re-spelled an integral double on the way: an authored 2.0 came
;; back as 2. RF-2b's R4 deleted those copies (immutable trees share), so
;; the authored spelling now reaches the handler verbatim.
;;
;; Asserts BOTH directions, because "everything is a float now" would be an
;; equal and opposite regression:
;;   :amount 2.0 must arrive as a FLOAT   (the bug that was fixed)
;;   :count  2   must arrive as an INTEGER (no spurious float-ification)
;;
;; With the app open and 127.0.0.1:8765 forwarded:
;;   emacs -Q --batch -L emacs -l test/smoke-float-arg.el
;; then tap "Echo 2.0" on the device. Exit 0 iff both spellings survive.

(require 'ebp)

(let* ((seen nil)
       (client
        (ebp-connect
         "127.0.0.1" 8765
         :client-name "wsl-emacs" :client-version "30.1"
         :pairing-id "101112131415161718191a1b1c1d1e1f"
         :token (ebp-decode-pairing-token "AAECAwQFBgcICQoLDA0ODw")
         :wants '("theme")
         :ready-function
         (lambda (c)
           (ebp-client-surface-update
            c "app:main"
            '(:t "column" :padding 24
              :children [(:t "text" :style "headline"
                          :text "SPEC 17.4 — float echo")
                         (:t "spacer" :height 16)
                         (:t "button" :label "Echo 2.0"
                          :on_tap (:action "demo.echo"
                                   :args (:amount 2.0 :count 2)))
                         (:t "spacer" :height 16)
                         (:t "text" :style "caption"
                          :text "authored 2.0 must arrive as 2.0, authored 2 as 2")]))))))
  (ebp-client-register-action
   client "demo.echo"
   (lambda (_c params)
     (setq seen (plist-get params :args))
     'accepted))
  (cl-loop repeat 900 until seen do (accept-process-output nil 0.1))
  (let* ((amount (plist-get seen :amount))
         (count (plist-get seen :count))
         (ok (and (floatp amount) (= amount 2.0)
                  (integerp count) (= count 2))))
    (message "float-arg: args=%S | amount=%S floatp=%s | count=%S integerp=%s => %s"
             seen amount (floatp amount) count (integerp count)
             (if ok "PASS" "FAIL"))
    (kill-emacs (if ok 0 1))))

;;; smoke-float-arg.el ends here
