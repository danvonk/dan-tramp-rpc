# Validation record — 2026-09-19

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
