;;; tramp-rpc-deploy.el --- Java deployment for dan-tramp-rpc -*- lexical-binding: t; -*-
;; SPDX-License-Identifier: GPL-3.0-or-later
;; Bootstrap route handling derived from Arthur Heymans' tramp-rpc.
;;; Commentary:
;; Copy the self-contained Java 11 JAR and a launcher over standard TRAMP.
;; No downloads or compilation occur on remote machines.
;;; Code:
(require 'tramp)
(require 'tramp-sh)
(require 'tramp-rpc-hops)
(defgroup tramp-rpc-deploy nil "Java server deployment." :group 'tramp)
(defconst tramp-rpc-deploy-version "0.1.0")
(defconst tramp-rpc-deploy-binary-name "dan-tramp-rpc-server")
(defcustom tramp-rpc-deploy-source-directory
  (expand-file-name "../" (file-name-directory (or load-file-name buffer-file-name)))
  "Root of the dan-tramp-rpc checkout." :type 'directory)
(defcustom tramp-rpc-deploy-local-jar nil
  "JAR to deploy, or nil for dist/dan-tramp-rpc.jar in the checkout." :type '(choice (const nil) file))
(defcustom tramp-rpc-deploy-java-command "java"
  "Java 11 or newer executable on the remote host." :type 'string)
(defcustom tramp-rpc-deploy-remote-directory "~/.cache/emacs/dan-tramp-rpc"
  "Remote directory for versioned Java deployments." :type 'string)
(defcustom tramp-rpc-deploy-remote-binary-path nil
  "Explicit remote launcher path, used when deployment is disabled." :type '(choice (const nil) string))
(defcustom tramp-rpc-deploy-never-deploy nil
  "Use an already installed launcher without deploying." :type 'boolean)
(defcustom tramp-rpc-deploy-auto-deploy t
  "Automatically copy the local JAR when needed." :type 'boolean)
(defcustom tramp-rpc-deploy-bootstrap-method "scpx"
  "Standard TRAMP method used to transfer the JAR." :type 'string)
(defun tramp-rpc-deploy--jar ()
  "Return the local Java server JAR."
  (or tramp-rpc-deploy-local-jar
      (expand-file-name "dist/dan-tramp-rpc.jar" tramp-rpc-deploy-source-directory)))
(defun tramp-rpc-deploy--id ()
  "Return a content identifier for this JAR and Java executable."
  (with-temp-buffer
    (set-buffer-multibyte nil)
    (insert-file-contents-literally (tramp-rpc-deploy--jar))
    (insert tramp-rpc-deploy-java-command)
    (substring (secure-hash 'sha256 (current-buffer)) 0 16)))
(defun tramp-rpc-deploy-expected-binary-localname ()
  "Return the expected remote Java launcher path."
  (if tramp-rpc-deploy-never-deploy
      (or tramp-rpc-deploy-remote-binary-path tramp-rpc-deploy-binary-name)
    (concat (file-name-as-directory tramp-rpc-deploy-remote-directory)
            (tramp-rpc-deploy--id) "/" tramp-rpc-deploy-binary-name)))
(defun tramp-rpc-deploy--normalize-hops (hop-string)
  "Convert \"rpc:\" method references in HOP-STRING to \"ssh:\" for bootstrap.
The bootstrap vec uses standard TRAMP methods (sshx) which need ssh-compatible
hop methods for their own multi-hop traversal.
Preserves the trailing \"|\" that TRAMP uses in canonical hop format."
  (when hop-string
    (concat
     (mapconcat
      (lambda (hop-str)
        (replace-regexp-in-string
         (rx bos "rpc" (literal tramp-postfix-method-format))
	 (concat "ssh" tramp-postfix-method-format) hop-str))
      (split-string hop-string tramp-postfix-hop-regexp 'omit)
      tramp-postfix-hop-format)
     tramp-postfix-hop-format)))

(defun tramp-rpc-deploy--bootstrap-vec (vec)
  "Convert VEC to use the bootstrap method for deployment operations.
This converts the rpc method to a standard TRAMP method for deployment.
The method used is controlled by `tramp-rpc-deploy-bootstrap-method'.
Methods like \"scp\" and \"rsync\" use out-of-band transfer for `copy-file',
while \"ssh\" and \"sshx\" use inline encoding (base64 through the shell).
Any \"rpc:\" hops in the hop chain are normalized to \"ssh:\" so that
standard TRAMP can traverse them."
  (let ((method (tramp-file-name-method vec))
        (sudo-hop (and (fboundp 'tramp-rpc--sudo-rpc-hop-vec)
                       (tramp-rpc--sudo-rpc-hop-vec vec))))
    (if (and (not sudo-hop)
             (member method '("ssh" "sshx" "scp" "scpx" "rsync")))
        vec  ; Already a TRAMP method that supports shell commands and file transfer
      ;; Convert to bootstrap method - create a new tramp-file-name struct.
      ;; For /rpc:user@host|sudo:root@host: paths, deployment must still happen
      ;; over the SSH user from the rpc hop.  The root RPC server is started via
      ;; sudo later; using root here makes TRAMP try an unrelated scpx root
      ;; login and fails before sudo authentication can help.
      (make-tramp-file-name
       :method tramp-rpc-deploy-bootstrap-method
       :user (or (and sudo-hop
                      (or (tramp-file-name-user sudo-hop) (user-login-name)))
                 (tramp-file-name-user vec))
       :domain (tramp-file-name-domain vec)
       :host (tramp-file-name-host vec)
       :port (if sudo-hop
                 (tramp-file-name-port sudo-hop)
               (tramp-file-name-port vec))
       :localname (tramp-file-name-localname vec)
       :hop (tramp-rpc-deploy--normalize-hops
             (if sudo-hop
                 (tramp-rpc--proxy-hop-string vec)
               (tramp-file-name-hop vec)))))))


(defun tramp-rpc-deploy-ensure-binary (vec)
  "Ensure the Java server is installed for VEC; return its launcher path."
  (let ((localname (tramp-rpc-deploy-expected-binary-localname)))
    (unless tramp-rpc-deploy-never-deploy
      (let* ((bootstrap (tramp-rpc-deploy--bootstrap-vec vec))
             (remote (tramp-make-tramp-file-name bootstrap localname))
             (directory (file-name-directory remote)))
        (unless (file-executable-p remote)
          (unless tramp-rpc-deploy-auto-deploy
            (error "Java server missing and automatic deployment is disabled"))
          (unless (file-readable-p (tramp-rpc-deploy--jar))
            (error "Build the Java JAR first with scripts/build.sh"))
          (make-directory directory t)
          (let ((temporary (concat directory "server.jar.upload")))
            (copy-file (tramp-rpc-deploy--jar) temporary t)
            (rename-file temporary (concat directory "server.jar") t))
          (with-temp-buffer
            (insert "#!/bin/sh\nexec "
                    (shell-quote-argument tramp-rpc-deploy-java-command)
                    " --add-opens=java.base/sun.nio.fs=ALL-UNNAMED -jar \"$(dirname \"$0\")/server.jar\" \"$@\"\n")
            (write-region (point-min) (point-max) remote nil 'silent))
          (set-file-modes remote #o700))))
    localname))
(defun tramp-rpc-deploy-install-binary (vec &optional _force)
  "Install the Java server on VEC."
  (interactive (list (tramp-dissect-file-name (read-file-name "Remote directory: "))))
  (when tramp-rpc-deploy-never-deploy (user-error "Deployment is disabled"))
  (let ((tramp-rpc-deploy-auto-deploy t)) (tramp-rpc-deploy-ensure-binary vec)))
(provide 'tramp-rpc-deploy)
;;; tramp-rpc-deploy.el ends here
