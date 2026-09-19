;;; java-integration-tests.el --- Java server integration -*- lexical-binding: t; -*-
;; SPDX-License-Identifier: GPL-3.0-or-later
(require 'ert)
(require 'tramp-rpc-tests)
(require 'filenotify)

(ert-deftest dan-tramp-java-file-notifications ()
  "An external writer invalidates metadata and delivers a file notification."
  (should (tramp-rpc-test-enabled))
  (tramp-rpc-test--with-temp-dir dir
    (let* ((file (concat dir "/watched.txt"))
           (events nil)
           descriptor)
      (write-region "before" nil file)
      (unwind-protect
          (progn
            (setq descriptor
                  (file-notify-add-watch dir '(change attribute-change)
                                         (lambda (event) (push event events))))
            (let ((default-directory (file-name-as-directory dir)))
              (should (= 0 (process-file "sh" nil nil nil "-c"
                                        "printf after-after > watched.txt"))))
            (let ((deadline (+ (float-time) 5)))
              (while (and (< (float-time) deadline) (not events))
                (accept-process-output nil 0.05)))
            (should events)
            (should (cl-some (lambda (event) (memq (cadr event) '(changed attribute-changed))) events))
            (should (= 11 (file-attribute-size (file-attributes file)))))
        (when descriptor (file-notify-rm-watch descriptor))))))

(ert-deftest dan-tramp-java-large-projectile ()
  "Enumerate a prepared large Git repository over the actual RPC handler."
  (skip-unless (and (getenv "DAN_TRAMP_PROJECTILE_ROOT")
                    (require 'projectile nil t)))
  (let* ((default-directory (getenv "DAN_TRAMP_PROJECTILE_ROOT"))
         (projectile-enable-caching nil)
         (projectile-indexing-method 'alien)
         (_ (file-attributes default-directory))
         (start (float-time))
         (root (projectile-project-root))
         (files (projectile-project-files root)))
    (should root)
    (should (= (string-to-number (or (getenv "DAN_TRAMP_PROJECTILE_COUNT") "12000"))
               (length files)))
    (message "Projectile: %d files in %.3fs over RPC" (length files) (- (float-time) start))))


(ert-deftest dan-tramp-java-stderr-drains-after-stdout-exit ()
  "A slow stderr relay survives the normal stdout exit sentinel."
  (let* ((default-directory temporary-file-directory)
         (connection-type nil)
         (buffer (generate-new-buffer " *dan-tramp-stderr*"))
         (stdout (make-process :name "dan-tramp-stdout" :command '("cat")
                               :connection-type 'pipe :noquery t))
         (stderr (make-process :name "dan-tramp-stderr" :buffer buffer
                               :command '("sh" "-c" "sleep 0.2; cat")
                               :connection-type 'pipe :noquery t)))
    (unwind-protect
        (progn
          (puthash stdout (list :stderr-process stderr) tramp-rpc--async-processes)
          (process-put stdout :tramp-rpc-exited t)
          (process-put stdout :tramp-rpc-exit-code 0)
          (process-put stdout :tramp-rpc-pid 12345)
          ;; A remote exit must not expose a finished process while its local
          ;; relay still needs to deliver output and dispatch its sentinel.
          (should (eq (tramp-rpc-handle-process-status stdout) 'run))
          (set-process-sentinel stdout #'tramp-rpc--pipe-process-sentinel)
          (process-send-string stderr "final stderr bytes")
          (process-send-eof stderr)
          (process-send-eof stdout)
          (tramp-rpc-test--wait-for
           (lambda () (not (gethash stdout tramp-rpc--async-processes)))
           "stdout sentinel" stdout)
          (tramp-rpc-test--wait-for
           (lambda () (with-current-buffer buffer
                        (string-match-p "final stderr bytes" (buffer-string))))
           "stderr drain" stderr))
      (remhash stdout tramp-rpc--async-processes)
      (ignore-errors (delete-process stdout))
      (ignore-errors (delete-process stderr))
      (kill-buffer buffer))))

(provide 'java-integration-tests)
