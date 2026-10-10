# dan-tramp-rpc

A Java 11 port of [emacs-tramp-rpc](https://github.com/ArthurHeymans/emacs-tramp-rpc).
The remote server is entirely Java; Emacs still uses the original Emacs Lisp
TRAMP backend. There is no Rust, Cargo, Maven, native compilation, or external
Java dependency. A ready-to-run JAR is included in `dist/dan-tramp-rpc.jar`.

The server runs as your SSH user over stdin/stdout, without a listening port.
Directory listings, attributes, project detection and commands travel over the
same persistent MessagePack connection. The `/rpc:` method, `tramp-rpc-*`
settings and existing Projectile/Magit integration remain compatible.

## Requirements

- Local Emacs **29.4 or newer** and **TRAMP 2.8.1.4 or newer**. Emacs 30.2's bundled
  TRAMP is too old; install a newer TRAMP package and load it before this backend.
  Emacs 29.4 with separately installed TRAMP 2.8.2.3 is supported.
- Linux remote host with OpenJDK 11 or newer, SSH, and standard Unix utilities
  (`sh`, `id`, `getent`, `kill`, `setsid`).
- Interactive terminal processes additionally use util-linux **`script`** at
  `/usr/bin/script` and `stty`. Ordinary file access and pipe processes do not
  require `script`.
- Git and any other commands you run must be installed remotely, as with TRAMP.

`msgpack.el` is bundled under `vendor/`; no MELPA access is needed for it.
The Java server needs no access to Maven Central or Artifactory, even to build.

## Use it

Copy this directory to your work machine. Ensure your newer TRAMP package is
on Emacs's load path, then add:

```elisp
(add-to-list 'load-path "/path/to/dan-tramp-rpc/lisp")
(require 'dan-tramp-rpc)
;; Optional when java is not on the remote PATH:
;; (setq tramp-rpc-deploy-java-command "/path/to/jdk-11/bin/java")
```

Open a remote file or directory with `C-x C-f`:

```text
/rpc:dan@hostname:/path/to/project/
```

The first connection copies the local JAR and a small shell launcher to
`~/.cache/emacs/dan-tramp-rpc/<content-id>/` using standard TRAMP. Subsequent
connections start it directly. The content ID includes the JAR and Java path,
so a new build is deployed separately from existing sessions. There are no
remote downloads or compiler invocations. Normal SSH configuration and the
inherited SSH jump-host/sudo support remain available; the latter were not
exercised in the single-host test run.

Do not load the original `emacs-tramp-rpc` and this fork in the same Emacs:
they deliberately provide the same Lisp features and `/rpc:` method.

For Projectile, visit the remote project first, then use its normal commands.
The inherited integration uses remote commands to enumerate files instead of
walking the tree with individual SSH calls. You can explicitly select:

```elisp
(setq projectile-indexing-method 'alien)
```

### Doom Emacs, including Emacs 29.4

Copy `lisp/`, `vendor/`, and `dist/` together under
`~/.config/doom/dan-tramp-rpc/`, preserving that directory structure. Add to
`~/.config/doom/config.el`:

```elisp
(add-to-list 'load-path
             (expand-file-name "dan-tramp-rpc/lisp" doom-user-dir))
(require 'dan-tramp-rpc)
(after! projectile
  (setq projectile-indexing-method 'alien))
```

If `M-x tramp-version` already reports 2.8.2.3, keep that installation; no TRAMP
or Emacs upgrade is needed. Restart Emacs after copying the files. When updating
an earlier copy of this fork for Emacs 29.4, replace `lisp/tramp-rpc.el`,
`lisp/tramp-rpc-advice.el`, and `dist/dan-tramp-rpc.jar`, and remove stale
`lisp/*.elc` files compiled on another Emacs/TRAMP version. The updated JAR also
preserves existing directory permissions and the correct return value when
creating a directory with parents. Deployment settings are unchanged.

To install the server manually, copy the JAR to the remote host and create an
executable launcher beside it:

```sh
#!/bin/sh
exec java --add-opens=java.base/sun.nio.fs=ALL-UNNAMED \
  -jar "$(dirname "$0")/server.jar" "$@"
```

Then set:

```elisp
(setq tramp-rpc-deploy-never-deploy t
      tramp-rpc-deploy-remote-binary-path "/absolute/path/to/launcher")
```

## Build

With a JDK 11 or newer, run:

```sh
./scripts/build.sh
```

This invokes `javac --release 11` and `jar`, producing the self-contained JAR.
You can build on one architecture and run on another. The included artifact
was tested on x86-64 and on aarch64 Java 11. `scripts/server.sh` starts it locally;
it speaks binary RPC, so it is not an interactive shell command.

Lisp byte-compilation is optional. If you compile, **use the same supported
TRAMP version that you will run**: TRAMP macros are embedded in the bytecode.
`scripts/test-emacs.sh` checks the version before compiling. Delete stale
`lisp/*.elc` files after upgrading TRAMP.

## Implemented

All 45 upstream RPC methods are routed by the Java server:

- File metadata, canonical paths, binary reads/writes, append and offset writes,
  zlib reads, copies, renames, deletion, permissions, timestamps, ownership,
  symbolic links and hard links.
- Directory listing with attributes and hidden entries, recursive creation,
  copying and deletion.
- Synchronous commands, binary stdin/stdout/stderr, environment overrides,
  background processes, polling, signals and stdin closure.
- PTY sessions, terminal sizing, input/output, signaling and cleanup.
- System information, groups, environment, tilde expansion and filesystem space.
- Batched calls, parallel command execution, ancestor-marker scans, and the
  project/dir-locals queries used by the Lisp integration.
- Recursive directory watches and asynchronous cache-invalidation events.

Implementation is intentionally small and direct. See `server/src/dan/tramp/rpc/`:
`Server`, `MessagePack`, `FileOps`, `Processes`, `Watches`, `Queries`, and `Rpc`.

## Port-specific behavior

This first port targets **Linux/OpenJDK**, rather than all Unix platforms
supported by the Rust implementation. OpenJDK's Unix path support is opened by
one JVM flag to preserve non-UTF-8 filenames; file operations have been tested
with raw `0xff` filenames. Standard Java APIs handle ordinary UTF-8 paths.

Java WatchService reports renames as deletion plus creation. Modification
notifications also emit an attribute-change notification because WatchService
does not distinguish the two. Overflow requests a client rescan. This is
sufficient for cache invalidation but is not identical to native inotify event
cookies or move pairing.

Requests run concurrently with separate worker pools for process control and
writes. Batch entries run in order; `commands.run_parallel` uses a shared
16-worker pool. Reads are capped at 16 MiB per chunk; process output is capped
at 32 MiB per stream. Request queues and retained parameters are bounded; an
exhausted queue returns an RPC error instead of waiting indefinitely. These
limits and scheduling differ from the Rust implementation.

No-overwrite renames use the Java filesystem provider's existence checks; they
do not provide Linux `renameat2(RENAME_NOREPLACE)` atomicity against competing
writers. Test your usual workflow before relying on this first port for
concurrent writers to the same destination.

## Tests

All tests use disposable `emacs -Q --batch` sessions, never `emacsclient`.
The runner isolates its D-Bus address from the desktop; see TESTING.md.
Deployment and fixtures are placed under `/tmp`, separate from normal installs.

```sh
TRAMP_LISP=/path/to/new/tramp \
EMACS=/path/to/emacs \
TRAMP_RPC_TEST_HOST=pi TRAMP_RPC_TEST_USER=dan \
DAN_TRAMP_TEST_JAVA=/path/to/remote/java \
  ./scripts/test-emacs.sh
```

The runner builds the JAR, byte-compiles the Lisp with warnings treated as
errors, and runs the inherited remote ERT suite plus Java-specific integration
tests. A second remote is optional (`TRAMP_RPC_TEST_HOST_2`).

Additional black-box protocol tests need Python 3 and Python's `msgpack`
package **on the test machine only**:

```sh
python3 test/protocol_test.py --java /path/to/java11
scp dist/dan-tramp-rpc.jar dan@pi:/tmp/dan-tramp-rpc-protocol.jar
python3 test/protocol_test.py --ssh dan@pi --java /path/to/remote/java11 \
  --jar /tmp/dan-tramp-rpc-protocol.jar
```

The full protocol suite exercises PTYs, so its target must have `script`.
For the optional Projectile ERT test, put Projectile and its dependencies on
Emacs's load path and set `DAN_TRAMP_PROJECTILE_ROOT` to a prepared `/rpc:` Git
repository and `DAN_TRAMP_PROJECTILE_COUNT` to its tracked file count.
See [TESTING.md](TESTING.md) for the recorded results and exact environment.

## License and provenance

GPL-3.0-or-later; see [LICENSE](LICENSE) and [NOTICE.md](NOTICE.md).
The original checkout next to this directory has been left unchanged.
