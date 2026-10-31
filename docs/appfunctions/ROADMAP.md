# Roadmap — native tool `app_functions`

What is still missing relative to [DESIGN.md](DESIGN.md), by priority.

## Next

- [x] **`afc` — Rust tool for registry, checking and calling** (`deps/afc`,
      30.10., commit `075abf5`). The measured registry is
      embedded (`data/registry.txt`, generated from
      `appfunctions-registry.xml`), rules P0–P7 check the arguments before a
      call, error codes are translated, and the call goes through the binder via
      `aus.jar` (uid 2000). 27 tests, three counter-checks that turn red when a
      rule is disabled; device test on 30.10.: 10 calls, 8 with content, 2
      without (as measured on 29.10.), 0 errors. One finding: `findTabs`
      answers 1001 "Invalid Argument" with `maxCount` alone — not confirmed.
      This fixes the data and rule layer that the Kotlin port takes over.
- [x] **Binder path in the app, and the registry as a fallback source** (30.10.,
      committed on this branch as a backup, no CI run yet). `AppFunctionsBinder.kt` ships
      `aus.jar` as an asset, puts it on the device and calls
      `app_process … Ausfuehren` as uid 2000 through the existing Shizuku
      integration; `AppFunctionsRegistry.kt` carries the measured registry
      (assets/appfunctions/registry.txt) with rules P0–P7 and doubles as the
      list source when `cmd app_function` is missing; `AppFunctionsAntwort.kt`
      reads the reply and names `EMPTY_RESULT`. Measured from inside the app via
      `debug.shizuku.exec`: the chain returns real contacts — see
      [TEST4-LOG.md](TEST4-LOG.md). Not compiled yet: this install's sandbox has
      no working JVM, so CI and the device still have to verify it.
- [x] **`afc` takes the location of `aus.jar` from `--jar` or `$AFC_JAR`** instead of a fixed private path.
- [x] **Token cost of `afc` against the plain skill** (30.10.) — measured, see
      [TEST5-LOG.md](TEST5-LOG.md): the plain skill does not run on a device
      without `cmd app_function`; where both answer (parameters of a function)
      `afc` needs about half the tokens. Open: repeat on a device where the
      skill runs.
- [x] **Compatibility matrix per device and ROM** (31.10.) — see
      [COMPATIBILITY.md](COMPATIBILITY.md): what runs where, what is missing and
      whether it can be brought along.
- [x] **Binder call hardened against class signatures that differ between
      builds** (31.10.) — `aus.jar` picks the `ExecuteAppFunctionAidlRequest`
      constructor by reflection and reports `NO_CONSTRUCTOR` with the declared
      signatures. Measured: 4 arguments on one Android 16 build, 5 on another;
      both run. The source lives in `deps/afc/aus/`, its build is bit-identical
      to the asset. The Kotlin port carries the new code and the same message.
      Not measured: the swapped order of the two `long` values.
- [x] **`afc` shows why a Shizuku call failed** (31.10.) — last output line when
      the error field is empty.
- [x] **Plain skill `appfunktionen` 0.1.2** (31.10.) — appends `--package` only
      when `help` names it, filters itself otherwise, and says so when the
      command does not exist. 21 tests, 20 mutations all red.
- [x] **Device test: "package not installed" as its own class** (31.10.) —
      `deps/afc/werkzeuge/geraetetest.sh` counts 1003 as "not present", not as an
      error; if every call is 1003 it exits 4 ("says nothing"). Test:
      `deps/afc/tests/test_geraetetest.sh` with a fake `afc`; counter-check:
      removing the 1003 branch turns two of three checks red. Device B:
      8 / 2 / 0 / 0 (content / empty / not present / error) as before.
- [x] **The registry is refreshable** (31.10.) — `afc refresh` reads
      `dumpsys app_function` (or `--dump FILE`) and writes a registry for this
      device (`/var/minis/shared/afc/registry.txt`, atomic); `afc` prefers it
      over the embedded one (`--registry`, `$AFC_REGISTRY`). What the embedded
      registry knows (fields, examples) is kept, status comes from the dump,
      `geprueft` is reset, functions only the dump knows carry the dump's
      parameter name and type with unknown fields. Checked against the dump of
      device B (61 of 61 identical) and a reduced dump (19 functions, 42 named as
      missing). Not done: the Kotlin port does not refresh yet; fields of new
      functions stay unknown (need the probe).
- [x] **Green CI build of the branch** — debug APK as artifact (run
      `40255763109`, `b2cb2ab`; sha256 `ada12f8b…caf77`, package
      `com.openminis.app.appfn`).
- [x] **Device test, Shizuku path** (D8) — done, see
      [TEST2-LOG.md](TEST2-LOG.md): `list`, `schema`, a read `execute`, a write
      with and without the confirmation switch, and the refusals.
- [ ] **`afc` as the shipped core (D9)** — NDK build `aarch64-linux-android`,
      `libafc.so` in the APK, Kotlin reduced to starting it; replaces about 600
      lines of Kotlin. Needs a maintainer build; the start from `lib/arm64` is
      unmeasured. Shizuku-only identity measured (uid 2000, context shell): 11
      calls, 0 errors; a device with no root at all is not.
- [x] **Device test from the device's own registry** (31.10.) — `afc proben`
      derives the calls from the registry of this device (read-only, switched on,
      only `query`/`maxCount` as required fields; unknown fields only with a
      confirmed empty call); `geraetetest.sh` runs them. No fixed Samsung list. A
      form that never ran on the test device counts as "deviation", not red.
      44 Rust tests, 5 device-test checks, 5 mutations all red. Measured:
      embedded registry 11 calls (9 content, 2 empty, 0 errors); a registry of
      only Google functions 2 calls, 2 with content. New facts from it:
      `findTabs`, `getHistories` and the dialer's `findPersons` answer with
      `query` + `maxCount`; `findMediaItems` is empty with `query:"a"` (it had
      content with `maxCount` alone).
- [x] **Slim tool definition** (31.10.) — strings of `definition()` 1737 B (~482
      tokens) down to 730 B (~203); the how-to text moved into the answer of
      `list`. **Not compiled**, not run; the effect on how well models use the
      tool is not measured.
- [ ] **Shizuku permission level** (D2 step 3) — consult the existing
      `OffloadPermissionManager` level for Shizuku before choosing the path.
- [ ] **Probe call for the direct path** (D2 step 2) — a granted permission
      alone must not select it; drop the result on code 1000.

- [x] **Device check of the confirmation fix** (`8a0922d`) — done, see
      [TEST3-LOG.md](TEST3-LOG.md): an approved write now says in its result
      that the user confirmed it, and a read of the same target does not. Two
      things surfaced on the way, both in that log: Samsung apps that declare
      no parameters reach the confirmation with **nothing** validated (the
      gate is then the only guard), and the gallery offers `createAlbum`
      without any matching delete — a write test there cannot be undone
      through the same interface.
- [ ] **Testing the parallel install** — the fork's APK installs next to a
      signed Minis as a self-contained second setup, not as a second view of
      the same one: its sandbox rootfs is a fresh Alpine, and `skills`,
      `shared` and `memory` are a **copy** (in the measured case from a restored
      backup, identifiable by one timestamp across every folder), so writes
      only land in the install that makes them. The rootfs ships a small base
      set (`age`, `curl`, `busybox`, `apk-tools`) — everything else, e.g.
      `git`, `python3`, `rg`, `gh`, is handwork per install and is **not** part
      of a backup. Details and the measurements: [TEST3-LOG.md](TEST3-LOG.md) 2.
      Also measured: the restored copy does not carry the executable bit, so
      dozens of files show up as mode-only changes (`100755` → `100644`) in the
      working tree — they must never be committed, add files explicitly.
- [ ] **Catalogue for predefined schemas** — most Samsung apps declare no
      parameters (e.g. `clock/createAlarm@1`), the gallery does. Measured
      2026-10-26: the *parameter* names and types can be had from the runtime
      metadata (`dumpsys app_function`) and confirmed by a read-only probe; the
      **fields inside** the parameter object cannot — the shipped schema
      classes carry renamed fields and no Kotlin metadata. A dated catalogue of
      what is measurable is in place
      ([SAMSUNG-PARAMETERS.md](SAMSUNG-PARAMETERS.md),
      [catalogue/](catalogue/)). Still open: the inner field names, and
      validating against them.
- [ ] **Read-back after write** — compare the stored state with the request.
- [ ] **Type checks of leaf values** (int vs. string) in the validation.
- [x] **Device check of the confirmation switch** — off: a write runs without
      a sheet; on: reject once, expect `NEEDS_CONFIRMATION`. The gap it exposed
      (an approved call looked like an ungated one) is fixed.

## Then

- [ ] **Cache per package and `versionCode`** (D4) — list and schemas.
- [ ] **Schema-driven argument conversion** (D5) — types, nested objects and
      arrays from the target's schema on the direct path.
- [ ] **Discovery on the direct path** (D4) — `searchAppFunctions` from API 37;
      needs compileSdk 37.
- [ ] **Path and reason in diagnostics** (D2).
- [ ] **Settings texts in every shipped locale** — today `en` and `de`.
- [ ] **Device test, direct path** (D8) — on a device that grants
      `EXECUTE_APP_FUNCTIONS`. Measured: on a stock device the permission is
      privileged and cannot be handed to a sideloaded app, so this needs a
      privileged install (TEST2-LOG.md 4.).

## Later

- [ ] **Read functions as individual tools** (D3, later stage).
- [ ] **Exposure decision** (D3) — native tool or sandbox CLI
      `android-app-functions`.
- [ ] **`scope=activity` functions** — decide whether to expose them at all.
      Measured: through Shizuku they answer `SCOPE_ACTIVITY … (code 1003)`,
      because the caller has no activity to name (TEST2-LOG.md 2.1).

## Done, not yet released

Moves to [CHANGELOG.md](CHANGELOG.md) with the release. One line per change.

### Added

- `app_functions`: the tool definition shrinks to about 40 percent; the usage text comes with the answer to `list`.
- `afc refresh`: renew the registry from `dumpsys app_function`; `afc --registry` / `$AFC_REGISTRY` use it.
- Device test counts "package not installed" (1003) as its own class and derives its calls from the device's registry (`afc proben`).
- `afc` runs directly as uid 0 or 2000 on the device (`su 2000` only under root); `afc refresh` works there too.
- `docs/appfunctions/TEST5-LOG.md` section 5: standing cost of the tool definition against the skill entry.

- `deps/afc/aus/`: source and build of `aus.jar`; the constructor of `ExecuteAppFunctionAidlRequest` is chosen by reflection, with `NO_CONSTRUCTOR` and the declared signatures on failure.
- `docs/appfunctions/COMPATIBILITY.md`: what runs on which device and ROM, what is missing, whether it can be brought along.
- `docs/appfunctions/TEST5-LOG.md`: token cost of `afc` against the plain skill.
- `afc` shows the reason of a failed Shizuku call; the codes 1002 and 3501 mention the lock screen.
- `deps/afc`: Rust tool with the measured registry, argument checks before a call and error-code translation.
- `afc --jar` and `$AFC_JAR` choose the location of `aus.jar`.
- `app_functions` executes through the binder (uid 2000 via Shizuku) when `cmd app_function` is missing.
- The function list falls back to a registry shipped with the app when the device cannot be asked.
- A reply that reports success without content is shown as `EMPTY_RESULT`.
- Native agent tool `app_functions` to list, inspect and execute the AppFunctions of other installed apps (Android 16+).
- Execution through the platform API when Minis holds `EXECUTE_APP_FUNCTIONS`, otherwise through Shizuku.
- Parameter schemas read directly from the target app's package, in both the full and the minimal form.
- Short function names are accepted when unique within the app.
- Setting "Confirm actions that change something" (off by default): when on, functions that change something run only after the user approves them in the existing confirmation dialog.
- An empty result is reported as unknown instead of as success.
- `list` and `schema` show the parameters, descriptions and data types each app declares in the system's function registry.
- `execute` refuses arguments with unknown or missing required fields before anything runs or is shown for confirmation.
- Results of functions without declared parameters carry a note to read the outcome back.
- Settings switch "App Functions" under Agent Tools, off by default, with English and German texts.
- Manifest declares `EXECUTE_APP_FUNCTIONS` and package visibility for apps that expose AppFunctions.
- CI workflow on the fork that builds a debug APK installable next to an existing Minis.
- The CI build skips pushes that only change documentation.
- Optional `applicationIdSuffix` for debug builds (`-PminisIdSuffix`).
- Design notes, roadmap and changelog for the feature.
- Measured note on where the parameters of Samsung apps come from, with a dated catalogue of the parameter names and types that are reachable on the device ([SAMSUNG-PARAMETERS.md](SAMSUNG-PARAMETERS.md)).
- Field names of the predefined schema classes, recovered from a second source and checked against the installed copy by field count and type sequence (141 of 142 classes agree).
- `docs/appfunctions/TEST-LOG.md`: field notes from the first on-device test.
- `docs/appfunctions/TEST2-LOG.md`: field notes from the second device round.

### Fixed

- A write call the user approved says so in its result.
- Reading an app's schema file no longer fails on Android.
