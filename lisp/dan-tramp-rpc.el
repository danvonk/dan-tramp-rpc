;;; dan-tramp-rpc.el --- TRAMP RPC with a Java 11 server -*- lexical-binding: t; -*-
;; SPDX-License-Identifier: GPL-3.0-or-later
;;; Commentary:
;; Load this entry point to use /rpc:user@host:/path with the Java server.
;;; Code:
(let ((vendor (expand-file-name "../vendor" (file-name-directory (or load-file-name buffer-file-name)))))
  (add-to-list 'load-path vendor))
(require 'tramp-rpc)
(provide 'dan-tramp-rpc)
;;; dan-tramp-rpc.el ends here
