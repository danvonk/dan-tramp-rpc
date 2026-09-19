;;; test-config.el --- Disposable Java test deployment -*- lexical-binding: t; -*-
(setq tramp-rpc-deploy-remote-directory
      (or (getenv "DAN_TRAMP_TEST_DEPLOY_DIR") "/tmp/dan-tramp-rpc-test")
      tramp-rpc-deploy-java-command (or (getenv "DAN_TRAMP_TEST_JAVA") "java")
      tramp-rpc-test-temp-dir "/tmp/dan-tramp-rpc-ert")
