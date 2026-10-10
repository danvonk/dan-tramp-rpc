#!/bin/sh
# Run in a disposable Emacs, never emacsclient or the user's live session.
set -eu
cd "$(dirname "$0")/.."
: "${TRAMP_LISP:?Set TRAMP_LISP to the directory containing TRAMP >= 2.8.1.4}"
: "${EMACS:=emacs}"
# Batch tests must not inherit the desktop D-Bus session. On the test host,
# loading GVFS against that session loses native Emacs child notifications.
export DBUS_SESSION_BUS_ADDRESS="unix:path=/tmp/dan-tramp-rpc-no-bus-$$"
export TRAMP_RPC_TEST_HOST="${TRAMP_RPC_TEST_HOST:-pi}"
export TRAMP_RPC_TEST_USER="${TRAMP_RPC_TEST_USER:-dan}"
# Install the server manually before running this suite.
export DAN_TRAMP_TEST_SERVER="${DAN_TRAMP_TEST_SERVER:-/tmp/dan-tramp-rpc-test/dan-tramp-rpc-server}"
"$EMACS" -Q --batch -L "$TRAMP_LISP" -L vendor -L lisp \
  --eval '(setq load-prefer-newer t)' \
  --eval '(require (quote tramp-rpc))' \
  --eval '(tramp-rpc--check-tramp-version)' \
  --eval '(setq byte-compile-error-on-warn t)' \
  -f batch-byte-compile lisp/*.el
"$EMACS" -Q --batch -L "$TRAMP_LISP" -L vendor -L lisp -L test \
  -l test/test-config.el -l java-integration-tests \
  --eval '(unless (tramp-rpc-test-enabled) (error "Remote test host is unavailable"))' \
  --eval '(ert-run-tests-batch-and-exit "^\\(tramp-rpc-test\\|dan-tramp-java\\)")'
