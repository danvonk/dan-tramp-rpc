# Provenance

The Emacs Lisp client and `test/tramp-rpc-tests.el` are derived from
Arthur Heymans' emacs-tramp-rpc, commit
`948e42a76a97947fb2a00e2815f958b7e7b40534`:
https://github.com/ArthurHeymans/emacs-tramp-rpc

Original copyright and GPL notices are retained. The Java server is a new
implementation of that repository's protocol. Java deployment replaces the
Rust build/release downloader. The Lisp transport additionally quotes the
remote launcher path. Process handling preserves stderr during relay shutdown
and exposes process completion only after the local relay exits. One upstream
PTY test now waits for its asynchronous sentinel. `dan-tramp-rpc.el` is the new entry point.

`vendor/msgpack.el` version 0.1.1 is by Xu Chunyang, GPL-3.0-or-later,
retrieved from https://github.com/xuchunyang/msgpack.el on 2026-09-19.
Its copyright and license notice are retained in the file.
