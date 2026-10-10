# Validation record

## Manual server installation — 2026-10-10

Automatic deployment has been removed. The historical deployment instructions
and results below describe earlier versions, not the current client.
The deployment module and legacy variable alias have also been deleted;
`tramp-rpc-server-command` is defined directly in the transport module.

The current client was byte-compiled with warnings treated as errors using
Emacs 29.4 and TRAMP 2.8.2.3, then tested over SSH to `dan@pi` with a manually
installed JAR and launcher at `/tmp/dan-tramp-rpc-manual-test/`.
**107 tests passed, zero failed, four skipped** (the optional Projectile test
and three tests requiring a second host). The new regression checks both
successful and failed startup, forbids installation writes, and verifies that
a missing server is not retried through a deployment path.

Reproduce after installing your server manually:

```sh
EMACS=/path/to/emacs-29.4 TRAMP_LISP=/path/to/tramp-2.8.2.3 \
DAN_TRAMP_TEST_SERVER=/tmp/dan-tramp-rpc-manual-test/dan-tramp-rpc-server \
  ./scripts/test-emacs.sh
```

[Manual-server test output](test/results/emacs-29.4-manual-server.log).

## Emacs 29.4 compatibility — 2026-10-10

Tested with an isolated GNU Emacs 29.4 built from the release tarball, and
TRAMP **2.8.2.3** from emacs-straight/tramp commit
`793be9a9ff439026b80c3c284588245ef79fb717`. The existing system Emacs and Doom
configuration were not modified. Remote operations used `ssh dan@pi`, now
running OpenJDK 21.0.12.1. The updated JAR is still built with `--release 11`;
this compatibility run did not repeat the earlier Java 11 runtime validation.

Both Emacs **29.4** and **30.2** passed strict byte-compilation and the SSH ERT
suite: **106 passed, zero failed, four skipped**. Three skips require a second
remote host; the fourth is the optional Projectile fixture, tested separately.
The new regressions cover connection-local trash destinations, recursive mkdir
return values, unchanged permissions of existing directories, blocking files,
and local-path behavior. The inherited test still requires recursive RPC mkdir
to use only one request.

A separate Emacs 29.4 Projectile run enumerated **12,000 tracked files in
1.216 seconds**. Projectile commit:
`7cb1f64c1d68dd01acda8b7e3459ed11eb4fb2a8`; fixture:
`/rpc:dan@pi:/tmp/dan-rpc-emacs29-projectile/`. This is a synthetic fixture, not
the user's work repository or a test of the user's running Doom session.
The rebuilt server also passed **35 protocol checks over SSH**. The local
protocol run could not exercise PTYs because `/usr/bin/script` was absent;
the Pi provides it and the full SSH run passed.

Finally, after removing all generated `lisp/*.elc`, the combined source-only
Emacs 29.4 run (including Projectile) passed **107 tests, zero failures, three
multi-host skips**. This checks the `.el` files intended for manual copying.
[Source-only run](test/results/emacs-29.4-source-ssh.log).

Reproduce the compilation and ERT checks with:

```sh
EMACS=/path/to/emacs-29.4 TRAMP_LISP=/path/to/tramp-2.8.2.3 \
  ./scripts/test-emacs.sh
```

Logs: [Emacs 29.4](test/results/emacs-29.4-ssh.log),
[Emacs 30.2](test/results/emacs-30.2-compat-ssh.log),
[Projectile on 29.4](test/results/projectile-29.4-ssh.log),
[protocol](test/results/protocol-compat-ssh.log).

Updated artifact SHA-256:

```text
0bc61edc03c079a2f9e335f80c8cbfdf09a962a41f59a3a3eb18e4344f735b15
```

## Original port validation — 2026-09-19

The final server was compiled by `javac 11.0.32` with `--release 11`, all Java
lint warnings treated as errors. Every class in the JAR has class-file version
55 (Java 11). There are no external JARs or native libraries in the artifact.

Artifact SHA-256:

```text
7526934bead75555244376b802c9256cb64ebcd95deff20a1ad11021c13109e5
```

## Actual SSH integration

Local Emacs: 30.2, Linux x86-64. Remote: `dan@pi`, Linux aarch64, Temurin
OpenJDK JRE 11.0.32.1+1. The Pi had no Java installed; a runtime was extracted
under `/tmp/dan-tramp-rpc-java11`, without sudo or system configuration changes.
The actual work-machine JDK and repositories were not available for testing.

TRAMP 2.8.2.2 was loaded from a separate checkout, commit
`7b178fb8b8616797596ffa51debe5b0ab10c8a5a` of emacs-straight/tramp.
The original Emacs-bundled TRAMP 2.7.3 does not meet this client's requirement.
All Lisp modules byte-compiled with `byte-compile-error-on-warn` enabled against
the tested TRAMP. Compiled Lisp is not shipped, to avoid embedding the wrong
TRAMP macros for the user's installation.

The combined ERT run completed **108 tests: 105 passed, zero failed, three
skipped**, in 12.66 seconds. The skips are the inherited copy/rename tests
requiring a second remote host; only the requested `pi` host was used.

This exercises automatic JAR deployment, the real `/rpc:` file-name handler,
file/directory operations, permissions, symlinks, coding and Unicode, recursive
copies, process execution, async processes and stderr relays, Python comint,
project-root/dir-locals integration and file notifications. The added slow-stderr
regression verifies that a normal stdout exit cannot discard stderr still in
flight. A test waiting for a PTY's sentinel was updated to wait for the
asynchronous notification rather than assuming status observation dispatches it.

[Complete ERT output](test/results/emacs-ssh.log)

The black-box MessagePack suite passed **35 checks over SSH**, including raw
non-UTF-8 filenames, zlib/chunked binary reads, write offsets, hard/symbolic
links, errors and batch isolation, recursive-copy rejection through symlinks,
concurrent requests, stdin/stdout/stderr, cleared environments, command
timeouts, real RPC PTYs and resize, recursive watch notifications, malformed
message recovery, and child termination when stdin closes.

[Protocol output](test/results/protocol-ssh.log)

## Large-project check

A disposable Git repository on the Pi contained 12,000 tracked Java files in
120 directories. Projectile discovered its root and enumerated all 12,000
files through `/rpc:dan@pi:`. The final warmed-connection measurement was
**0.607 seconds**; an earlier run including connection setup took 1.793 seconds.
These are fixture measurements, not a benchmark against the user's work repo
or a claim of a specific speedup over ordinary TRAMP.

Projectile checkout: `f902073c1d10bb125875470c9dcbdcd7c9a1eefb`.
Compat checkout: `90880f81419577e1d3f68424d2a3adf31e6d663e`.
Neither package is required by the Java server or bundled with this port.

## Reproduce the combined run

From the repository root, after building and preparing the remote fixture:

```sh
DBUS_SESSION_BUS_ADDRESS=unix:path=/tmp/dan-no-session-bus \
DAN_TRAMP_PROJECTILE_ROOT=/rpc:dan@pi:/tmp/dan-tramp-rpc-projectile/ \
DAN_TRAMP_TEST_JAVA=/tmp/dan-tramp-rpc-java11/bin/java \
TRAMP_RPC_TEST_HOST=pi TRAMP_RPC_TEST_USER=dan \
emacs -Q --batch \
  -L /path/to/tramp -L /path/to/compat -L /path/to/projectile \
  -L vendor -L lisp -L test \
  -l test/test-config.el -l java-integration-tests \
  --eval '(ert-run-tests-batch-and-exit "^\\(tramp-rpc-test\\|dan-tramp-java\\)")'
```

The regular `scripts/test-emacs.sh` runner does not require Projectile and
skips that optional test when the fixture/package is absent.

## Batch process-test isolation

On the local test machine, batch Emacs connected to the inherited desktop
D-Bus session intermittently missed child-process sentinels after GVFS loaded.
This was reproduced with ordinary local `cat` processes and **without this
project loaded**. Disabling adaptive buffering did not fix it. Isolating the
D-Bus address let the same native test complete 1,000 process pairs, and the
combined RPC tests then passed. `scripts/test-emacs.sh` applies this isolation
only to its test subprocesses; the product does not change desktop settings.

## Test locations and coverage limits

Deployment: `/tmp/dan-tramp-rpc-test/<content-id>/` on `pi`.
Protocol JAR: `/tmp/dan-tramp-rpc-protocol.jar`.
ERT fixtures: `/tmp/dan-tramp-rpc-ert/` (individual tests remove their fixtures).
Projectile fixture: `/tmp/dan-tramp-rpc-projectile/`.

These temporary deployment/runtime/benchmark directories remain available for
reproduction. Normal remote installations and the user's running Emacs were
not used. Multi-host copy, jump hosts, sudo, Magit's interactive UI and non-Linux
hosts were not exercised. See README's port-specific behavior for WatchService,
resource limits and rename atomicity differences from the Rust server.
