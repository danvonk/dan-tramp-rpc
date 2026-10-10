;;; test-config.el --- Manually installed Java test server -*- lexical-binding: t; -*-
(setq tramp-rpc-server-command
      (or (getenv "DAN_TRAMP_TEST_SERVER") "/tmp/dan-tramp-rpc-test/dan-tramp-rpc-server")
      tramp-rpc-test-temp-dir "/tmp/dan-tramp-rpc-ert")
