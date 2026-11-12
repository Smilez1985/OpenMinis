# afc — call AppFunctions (Rust)

`afc` is a command line tool and library for the AppFunctions of an Android
device: a registry of known functions, validation **before** the call, error
translation, and the binder route. It was built from measurements taken on
29/30 Oct 2026 on a test device (Android 16).

In the app it ships as `libafc.so` (see "Building for Android"). The Kotlin
side of the app starts it as a child process and reads its output; the design
is described in [`docs/appfunctions/DESIGN.md`](../../docs/appfunctions/DESIGN.md).

## Why a Rust core

The rules (registry, validation, error table) are plain data and plain logic.
In Rust they can be tested on a device or in CI without the Android stack, and
the same binary runs as a command line tool and inside the app. The Kotlin code
reads the same registry file and implements the same rules; a parity test keeps
the two in line.

`afc` has no dependencies: it must build without network access (no crates.io
in the sandbox) and run as a static binary.

## Why Rust and Java are separate

`afc` does not talk binder itself. A small Java program, `shim.jar` (source in
[`shim/`](shim/)), calls `IAppFunctionManager.executeAppFunction`. It is
measured on the test device (13 calls that succeeded), whereas rebuilding the
parcel format in Rust would be unverified and fragile. `afc` does everything
else: build the arguments, validate them, assemble the command line, and
interpret the reply.

The caller is **uid 2000 with package `com.android.shell`**, reached through the
Shizuku chain (root → `su 2000`). Measured: uid 0 fails (there is no package
for uid 0), and the Minis app itself fails with 1000 because
`EXECUTE_APP_FUNCTIONS` is `internal|privileged`.

The command line the shim receives is:

```
CLASSPATH=/data/local/tmp/af/shim.jar app_process / Execute '<package>' '<function-id>' <caller> '<param-name>' b64:<base64-json>
```

## Where `shim.jar` comes from

The app ships the jar as `assets/appfunctions/shim.jar` and places it at
`/var/minis/shared/afc/shim.jar`. From there `afc` copies it to
`/data/local/tmp/af/shim.jar`, which is readable for uid 2000. Order of lookup:
`--jar PATH`, then the environment variable `AFC_JAR`, then
`/var/minis/shared/afc/shim.jar`. The path must be under `/var/minis/`, because
the device finds the file through the data folder of the app. When `afc` runs
as uid 0 or 2000 itself, it uses the chosen path if it exists and otherwise
`/data/local/tmp/af/shim.jar`.

## Commands

```sh
cargo build --release          # TMPDIR=/tmp, RUSTFLAGS="-C target-feature=+crt-static"
target/release/afc list [pattern] [--all] [--json]
target/release/afc show findBookmarks
target/release/afc check com.sec.android.app.sbrowser findBookmarks --args '{"query":"a","maxCount":3}'
target/release/afc call  com.sec.android.app.sbrowser findBookmarks --args '{"query":"a","maxCount":3}'
target/release/afc codes
target/release/afc probes      # read-only probes for this device (TSV: package, function, args, confirmed yes|no)
target/release/afc refresh [--dump FILE] [--out FILE]   # renew this device's registry from dumpsys
target/release/afc help
```

| Command | What it does |
|---|---|
| `list [pattern]` | functions of the registry, grouped by package; switched-off ones only with `--all` |
| `show <name\|id>` | one function with its parameters and where each piece of information comes from |
| `check <package\|-> <name>` | validate the arguments, call nothing |
| `call <package\|-> <name>` | validate, then call; `-` means "any package" and fails if the name is ambiguous |
| `probes` | read-only probes derived from the registry, used by `tools/devicetest.sh` |
| `codes` | the error code table |
| `refresh` | write a registry for this device from `dumpsys app_function` |
| `help`, `--help`, `-h` | usage |

### Flags

| Flag | Meaning |
|---|---|
| `--args JSON` | the fields as a human would write them. Whether they sit at the top level or inside a composite parameter is decided by the registry, not by the caller |
| `--write` | really call a writing function. Without it a writing function stops with `NEEDS_CONFIRMATION` |
| `--dry-run` | prepare the call and print the script, but do not execute it |
| `--all` | `list` also shows functions that are switched off |
| `--json` | `list` prints JSON |
| `--jar PATH` | location of `shim.jar` (see above) |
| `--registry FILE` | use this registry instead of the embedded one (also `$AFC_REGISTRY`; otherwise `/var/minis/shared/afc/registry.txt` if it exists) |
| `--dump FILE` | `refresh` reads this dump instead of running `dumpsys app_function` |
| `--out FILE` | `refresh` writes the new registry here (default `/var/minis/shared/afc/registry.txt`) |

`afc` takes `/var/minis/shared/afc/registry.txt` if it exists (or `--registry` /
`$AFC_REGISTRY`), otherwise the embedded registry. A registry file that is named
but unreadable is an error, not a silent fallback. `refresh` keeps what the
embedded registry knows about a function (fields, examples), takes the function
list and the status from the dump, and resets `verified` to `no`.

### Exit codes and output

* `0`: the validation passed and, for `call`, the target app answered with
  content; or the command needed no call.
* `2`: usage error, unknown function or subcommand, failed validation
  (`afc: check failed`), or a call without success (`afc: call without success`).
  Errors are printed to stderr as `afc: <message>`.
* `3`: `tools/devicetest.sh` only: `afc` is missing or `probes` failed.
* `4`: `tools/devicetest.sh` only: nothing was tested.

Result text of `call` (the Kotlin app reads exactly these words):

| Line | Meaning |
|---|---|
| `SUCCESS: <json>` | the target app answered with content |
| `EMPTY_RESULT: SUCCESS, but no content. …` | success without content: that does not mean "nothing there", it means "not proven" |
| `TIMEOUT: the target app did not answer.` | the shim got no callback within its time limit |
| `ERROR <code>: <message> (cat=<category>)` + explanation | the platform or the target app refused; the explanation comes from the code table |
| `UNCLEAR: the reply could not be interpreted:` + raw lines | nothing recognised |
| `NEEDS_CONFIRMATION: '<function>' changes something. Ask first, then repeat with --write.` | a writing function without `--write` |

`afc codes` prints the code table: `1000 1001 1002 1003 1500 2000 2001 2002 3000
3501` from the platform, and `NO_CONSTRUCTOR NO_PATH NEEDS_CONFIRMATION
EMPTY_RESULT` from `afc` and the shim. `TOO_LONG` comes from `afc` and the app;
the app adds `NO_AFC` and `NO_SHIPPED_DEX`. The table comes from measurements, not from documentation;
where only a suspicion exists, it says so (for example 1500).

## Validation rules

| Rule | What | Why |
|---|---|---|
| P0 | function without parameter information: nothing is validated, and the result says so | "A result without validation is no proof" |
| P1 | required field missing (with name, type and origin of the information) | measured: missing `maxCount` → error 1001 with the message "null" |
| P2 | unknown field, or a field defined twice | the platform drops unknown fields **silently** |
| P3 | wrong type | measured: `maxCount` as text `"3"` → **empty list, no error** |
| P4 | `maxCount` above 30 is capped; `0` and `-1` are reported | measured: upper limit 30; `-1` means "all" only for contacts |
| P5 | empty search text for `findPersons` | measured: empty `query` → error 1001 |

Every rule has a test, and every rule has a counter-check (switch the rule
off and the test turns red; run on 30 Oct 2026). A success without content is
reported as `EMPTY_RESULT` (measured: `findCallRecords` returns success with an
empty list).

## Registry format

`data/registry.txt` is the master; it is embedded at build time. The Kotlin
asset and the Kotlin test resource are byte-identical copies, so after every
change copy it to `src/android/app/src/main/assets/appfunctions/registry.txt`
and `src/android/app/src/test/resources/appfunctions/registry.txt`.

`tools/registry_to_data.py` generates the file from the XML of the measured
registry. Tab-separated lines, `#` starts a comment:

| Kind | Columns |
|---|---|
| `F` function | package, function id, short name, category, status (`on`, `off`, `default-on`, `default-off`), verified (`yes`/`no`) |
| `P` parameter | function id, parameter name, type, level (`top-level` or empty), source |
| `D` field | function id, parameter name, field name, data type, required (`yes`/`no`), source |
| `B` example | function id, arguments, source, note |
| `E` meta | `device`, device name, `registry`, version |

Every entry carries its source: `dump` (runtime metadata of the device),
`schema` (asset/app_function.xml of the target APK), `call-ok` (a call went
through with these values), `error-message` (the target app named the field),
`convention` or `assumed` (guessed, and shown as such).

## Device test

```sh
sh tools/devicetest.sh
```

Calls the **read-only** functions that `afc probes` derives from the registry of
the device and prints one line per function (`SUCCESS`, `EMPTY`, `MISSING`,
`DEVIATION` or `ERROR`) plus a summary. It never calls anything that writes. A
`1003` counts as "not present" (the right answer for an app that is not
installed), a failure of a shape that never ran on the measuring device counts
as a deviation, and only a failure of a confirmed shape is red.
`tests/test_devicetest.sh` checks this classification with a fake `afc`.

Last run on 30 Oct 2026: **10 calls, 8 with content, 2 without content (as
measured), 0 errors.** `BrowserFunctions_findTabs` stands out: with `maxCount`
alone the target app answers 1001 "Invalid Argument". The function is not
confirmed in the registry and is no longer part of the device test.

## Tests

```sh
CARGO_TARGET_DIR=/tmp/afc-target cargo test   # unit tests plus tests/cli.rs, which runs the real binary
sh tests/test_devicetest.sh
```

## Building for Android

`deps/build_afc_android.sh` builds `afc` for `aarch64-linux-android` with the
NDK and installs it as `src/android/app/src/main/jniLibs/arm64-v8a/libafc.so`.
A native library is the only place an app may execute a file from (the same
reason as `libproot.so`).

```sh
export ANDROID_NDK_HOME=/path/to/ndk-r27+
./deps/build_afc_android.sh          # build and install
./deps/build_afc_android.sh test     # run the host tests first
```

Prerequisites: Rust (rustup) with the target and an NDK r27 or newer. The
binary is dynamic against Bionic (API 26 = minSdk), PIE, and 16 KB aligned. The
app starts it as a child process; it never loads into the app.

## Limits

* The registry is a snapshot of 29/30 Oct 2026 for one test device. It is not a
  device query; `dumpsys app_function` (via `afc refresh`) gives the current state.
* Field names **inside** the parameter objects are known only where they come
  from the schema of the target APK (`source=schema`). For `convention` or
  `assumed` entries `afc` validates the type but not the inner fields.
* The command line the Shizuku route passes through is limited. Measured: 8168
  characters arrive intact, 8198 are truncated, and a truncated command ends
  silently with exit 0. `afc` therefore refuses commands over 8000 characters
  with `TOO_LONG` instead of sending them.
* One device and one Android 16 build: the error codes are measured, but not
  confirmed across several models.
* The shim waits a fixed time for the target app (see `shim/`); `afc` has no
  timeout flag.
