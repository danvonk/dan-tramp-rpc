;;; java-integration-tests.el --- Java server integration -*- lexical-binding: t; -*-
;; SPDX-License-Identifier: GPL-3.0-or-later
(require 'ert)
(require 'tramp-rpc-tests)
(require 'filenotify)

(ert-deftest dan-tramp-java-mkdir-parents-semantics ()
  "Parent creation preserves return values and errors for RPC and local paths."
  (should (tramp-rpc-test-enabled))
  (let ((local-root (make-temp-file "dan-tramp-mkdir-" t)))
    (unwind-protect
        (tramp-rpc-test--with-temp-dir remote-root
          (dolist (root (list local-root remote-root))
            (let* ((default-directory (file-name-as-directory root))
                   (directory "nested/path")
                   (file "blocking-file"))
              (should-not (make-directory directory t))
              (set-file-modes directory #o700)
              (should (make-directory directory t))
              (should (= #o700 (logand #o777 (file-modes directory))))
              (should-error (make-directory directory) :type 'file-already-exists)
              (write-region "not a directory" nil file)
              (should-error (make-directory (concat file "/child") t)
                            :type 'file-error)
              (should (file-regular-p file)))))
      (delete-directory local-root t))))

(ert-deftest dan-tramp-java-trash-connection-local-directory ()
  "Trashing a remote file honors its connection profile on Emacs 29 and 30."
  (skip-unless (not (fboundp 'system-move-file-to-trash)))
  (should (tramp-rpc-test-enabled))
  (let ((local-trash (make-temp-file "dan-tramp-trash-" t)))
    (unwind-protect
        (tramp-rpc-test--with-temp-dir dir
          (let* ((default-directory (file-name-as-directory dir))
                 (trash-directory nil)
                 (connection-local-profile-alist
                  `((dan-tramp-test-trash . ((trash-directory . ,local-trash)))))
                 (connection-local-criteria-alist
                  '(((:application tramp :protocol "rpc") dan-tramp-test-trash)))
                 (file (expand-file-name "trash-me.txt" dir))
                 (destination (expand-file-name "trash-me.txt" local-trash)))
            (write-region "preserved in trash" nil file)
            (move-file-to-trash file)
            (should-not (file-exists-p file))
            (should (equal "preserved in trash"
                           (with-temp-buffer
                             (insert-file-contents destination)
                             (buffer-string))))))
      (delete-directory local-trash t))))

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
