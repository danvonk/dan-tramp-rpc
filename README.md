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

## Install the server manually

On the **remote host**, create a directory such as `~/.local/lib/dan-tramp-rpc/`
and copy `dist/dan-tramp-rpc.jar` into it as `server.jar`. Create a file named
`dan-tramp-rpc-server` beside the JAR with these contents:

```sh
#!/bin/sh
exec java --add-opens=java.base/sun.nio.fs=ALL-UNNAMED \
  -jar "$(dirname "$0")/server.jar" "$@"
```

If necessary, replace `java` with the absolute path to the remote Java 11+
executable. Make the launcher executable on that host:

```sh
chmod 700 ~/.local/lib/dan-tramp-rpc/dan-tramp-rpc-server
```

Emacs starts this launcher over SSH when needed. You do not need to start a
server daemon. Emacs never copies, installs, updates, or downloads server files.
After replacing the remote JAR yourself, restart Emacs or use
`M-x tramp-cleanup-all-connections` so the next connection starts the new server.

## Configure stock Emacs

Copy only `lisp/` and `vendor/` into `~/.emacs.d/dan-tramp-rpc/`, preserving their
relative layout. The local Emacs machine does not need a JAR or Java runtime.
Add to `~/.emacs.d/init.el`, after your existing package setup:

```elisp
;; Omit these two lines if your init already initializes installed packages.
(require 'package)
(package-initialize)

(add-to-list 'load-path
             (expand-file-name "dan-tramp-rpc/lisp" user-emacs-directory))
(setq tramp-rpc-server-command "~/.local/lib/dan-tramp-rpc/dan-tramp-rpc-server")
(require 'dan-tramp-rpc)

;; Optional, if you use Projectile:
(with-eval-after-load 'projectile
  (setq projectile-indexing-method 'alien))
```

Keep your TRAMP 2.8.2.3 installation if you already have it. Restart Emacs and
open a remote project with `C-x C-f`:

```text
/rpc:dan@hostname:/path/to/project/
```

`tramp-rpc-server-command` accepts an absolute path, a `~/` path, or a command
name on the remote SSH PATH (default: `dan-tramp-rpc-server`). It names a single
executable, not a command string with Java arguments. Use the launcher for those.
When updating an existing installation, replace the `lisp/` directory entirely
to remove obsolete modules and compiled files. Remove old `tramp-rpc-deploy-*`
settings from your init file; `tramp-rpc-server-command` is the only server
installation setting. Restart Emacs after updating.

Do not load the original `emacs-tramp-rpc` and this fork in the same Emacs:
they provide the same Lisp features and `/rpc:` method. Normal SSH configuration
and inherited jump-host/sudo support remain available; install the launcher at a
path accessible to the user who will run it (including the sudo target user).

### Doom Emacs

Copy `lisp/` and `vendor/` under `~/.config/doom/dan-tramp-rpc/`. In Doom's
`config.el`, use the same launcher setting and require form, but construct the
load path relative to `doom-user-dir` instead of `user-emacs-directory`.
Doom manages package initialization itself.

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
Install a test launcher manually under `/tmp`, separate from normal installs.

```sh
TRAMP_LISP=/path/to/new/tramp \
EMACS=/path/to/emacs \
TRAMP_RPC_TEST_HOST=pi TRAMP_RPC_TEST_USER=dan \
DAN_TRAMP_TEST_SERVER=/path/to/remote/dan-tramp-rpc-server \
  ./scripts/test-emacs.sh
```

The runner uses your already installed server, byte-compiles the Lisp with
warnings treated as errors, and runs the inherited remote ERT suite plus Java-specific integration
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
