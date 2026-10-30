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
- [ ] **The registry must be refreshable** — today it is a measured snapshot for
      one device; `dumpsys app_function` and the probe can renew it.
- [x] **Green CI build of the branch** — debug APK as artifact (run
      `40255763109`, `b2cb2ab`; sha256 `ada12f8b…caf77`, package
      `com.openminis.app.appfn`).
- [x] **Device test, Shizuku path** (D8) — done, see
      [TEST2-LOG.md](TEST2-LOG.md): `list`, `schema`, a read `execute`, a write
      with and without the confirmation switch, and the refusals.
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

- `deps/afc`: Rust tool with the measured registry, argument checks before a call and error-code translation.
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
