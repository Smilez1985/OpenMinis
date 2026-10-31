# Native tool `app_functions` — design

Blueprint for calling other apps' Android AppFunctions from Minis. It
describes the **target state**. What is still missing is in
[ROADMAP.md](ROADMAP.md), what has been built in [CHANGELOG.md](CHANGELOG.md).
Whoever changes something described here changes this file first.

This lives on a fork as a reference for a feature proposal; the upstream
repository does not take pull requests.

## Goal

Minis calls the **AppFunctions of other installed apps** (Android platform API
since Android 16, "Android MCP") as a **native agent tool**: the model uses
them through tool use, without the screen, the accessibility service or the
Linux sandbox. Typical targets: clock alarms/timers/stopwatch, reminders,
calendar, contacts, call log, gallery, browser bookmarks, device-state
settings of Play services.

Out of scope: Minis as a **provider** of its own AppFunctions. That is a
separate topic (KSP, the AppFunctions compiler, and from
`androidx.appfunctions` alpha09 on compileSdk 37 + AGP 9.1).

## Rule D1 — One core, two execution paths

| Path | Requires |
|---|---|
| **Direct** — `android.app.appfunctions.AppFunctionManager` | permission `EXECUTE_APP_FUNCTIONS`, an agent-allowlist entry, user consent |
| **Shizuku** — `cmd app_function` as the shell (uid 2000) | Shizuku running and granted to Minis |

Both paths sit behind one core; the model sees one tool and never learns
which path ran.

**Why:** the reference docs list `EXECUTE_APP_FUNCTIONS` as protection level
`normal`, but devices exist where it is `internal|privileged|knownSigner` and
therefore not grantable to an installed app. The shell holds
`EXECUTE_APP_FUNCTIONS_SYSTEM`, which the platform does not check against the
agent allowlist. A design that relies on the direct path alone would be dead on
those devices; one that relies on Shizuku alone would ignore the path Google
intends.

> **Amended by D9 (31.10.):** the shell path no longer runs `cmd app_function`
> (missing on some builds) but the binary `afc` through the binder; the direct
> path is only for a privileged install. Read D9 first.

## Rule D2 — The path is measured, never assumed

The path is decided per request, in this order:

1. API < 36 → **none**, reason named.
2. `EXECUTE_APP_FUNCTIONS` granted **and** a read-only probe call passes the
   platform's allowlist → **direct**. The probe result is cached for the
   process lifetime and dropped when a direct call is denied (code 1000).
3. Shizuku ready **and** the Shizuku integration permitted for Minis by the
   user (existing `OffloadPermissionManager` level for Shizuku) → **shizuku**.
4. Otherwise **none**, with the reason.

Never by device model or manufacturer. The decided path and its reason are
visible in diagnostics.

**Why:** a granted permission alone does not prove the allowlist passes; an
allowlisted agent without the permission does not exist. Only a real call
answers both.

## Rule D3 — One tool, three actions

Minis has no dynamic tool registration, and a device exposes 100+ functions.
So there is **one** tool `app_functions` with an `action`:

| Parameter | Use |
|---|---|
| `action` | `list` \| `schema` \| `execute` |
| `package` | target app (schema, execute) |
| `function` | full id, or the short name after `#` if unique in the package |
| `arguments` | execute: parameters as a JSON object (a JSON string is accepted) |
| `query` | list: filter on package, function name or category |
| `timeout_s` | execute: seconds, default 30, max 120 |

Output stays compact so the context does not fill up:
- `list` → per package: short names, `read`/`write`, category, `(disabled)`.
- `schema` → parameters with type, required, description; referenced types.
- `execute` → the return value as JSON; errors with their code.

A later stage may additionally expose the **read** functions of frequently
used apps as individual tools at turn start, with schemas generated from the
metadata (pattern: official sample `android/appfunctions`,
`GeminiToolConverter.kt`, Apache-2.0). The single tool stays the fallback.

The same core can alternatively be exposed as a sandbox CLI
`android-app-functions`, consistent with the other `android-*` commands. The
exposure is a product decision; the core does not change with it.

## Rule D4 — Discovery and schemas without privilege where possible

- **List**: `cmd app_function list-app-functions` via Shizuku (API 36 and 37).
  On the direct path from API 37: `AppFunctionManager.searchAppFunctions`.
- **Declared parameters come first from the list.** The registry dump carries,
  per function, `parameters`, `description` and `response`, and per package a
  component document with the data types. `list` and `schema` show them; the
  APK files are only the fallback for apps that declare nothing there.
- **Validation before execution** (and before any confirmation): unknown
  fields and missing required fields are an error, nested objects and array
  elements are checked against the declared data types. Reason: the platform
  drops unknown fields silently and fills defaults — a guessed `hour` became
  00:00 on the device. A function that declares no parameters cannot be
  validated; its result says so and asks for a read-back.
- **Schemas**: read from the target APK — asset files whose name contains
  `app_function` and ends in `.xml` — via `PackageManager` →
  `ApplicationInfo.sourceDir` + split APKs → `ZipFile`. No privilege needed.
  Two shapes exist: full schemas (typed parameters, descriptions, data types)
  and minimal ones (`schema_name` / `schema_category` only). Type codes follow
  `androidx.appfunctions` `AppFunctionDataTypeMetadata.TYPE_*`.
- Where no parameter schema exists, the target's error message names the
  missing field; it is passed through unchanged. That is the intended way, not
  guessing.
- List and schemas are cached per package and `versionCode`; a package update
  invalidates its entries.
- Schema XML comes from another app: a DOCTYPE is refused before parsing
  (Android's parser does not support the Xerces feature for it), and entity
  references are not expanded.

## Rule D5 — Execution

- **Direct**: `executeAppFunction(ExecuteAppFunctionRequest.Builder(pkg, id)
  .setParameters(GenericDocument), executor, cancellation, receiver)`.
  JSON → `GenericDocument` is converted **along the target's schema** when one
  exists (types, nested objects, arrays); without a schema, generically by JSON
  value type. The result document's `PROPERTY_RETURN_VALUE` is returned as JSON.
  compileSdk 36 suffices; no Jetpack dependency.
- **Shizuku**: `cmd app_function execute-app-function --package … --function …
  --parameters <json> --timeout-duration <s>` as an **argument array**, never
  through `sh -c`. Parameters are validated as JSON first; the timeout is
  clamped to 1–120 s.

## Rule D6 — Safety

- The tool switch is **off** by default. Turning it on is the consent: like
  shell or Shizuku, the tool then acts without asking per call.
- A second switch, **"Confirm actions that change something"**, also **off** by
  default and only usable while the tool is on, adds a per-call question.
  Reason: a confirmation sheet is only as good as what it shows — in the first
  device test the user approved arguments the model had guessed. Correct calls
  come from validation (D4/D5), not from a dialog; the dialog stays available
  for users who want it.
- With that switch on, **read** functions — verb of the short name in
  `find|get|search|list|is|query|read|lookup|fetch` — still run directly.
  **Everything else** (create, delete, update, set, start, show, draft …) runs
  only after the user approved it through the existing confirmation gate
  (`ConfigConfirmationGate`: one dialog queue, timeout, notification when
  backgrounded). Reject and timeout do not proceed.
- The classification is by **name**, not by evidence of side effects; the tool
  description says so. `show…` opens the target's UI and counts as write.
- An empty result with success status is reported as `EMPTY_RESULT`, never as
  success.
- Not offered to helper (sub-) agents: write calls need the user mid-run.
- Arguments and results are never logged; only package, short name and path.

## Rule D7 — Error codes

| Code | Origin | Meaning |
|---|---|---|
| 1000 / 1001 / 1002 / 1003 | platform | denied / invalid argument (names the field) / disabled / not found |
| 2000 / 2001 / 2002 | platform | system error / cancelled / enterprise policy |
| 3000 | target app | app error |
| `NO_PATH` | Minis | no path carries (D2), with reason |
| `NEEDS_CONFIRMATION` | Minis | write function not approved |
| `EMPTY_RESULT` | Minis | success status but no content — outcome unknown |

## Integration points (release 1.15)

Paths relative to `src/android/app/src/main/java/com/openminis/app/`.

| Where | What |
|---|---|
| `appfunctions/AppFunctionsCore.kt` | context-free core: list parsing, short-name resolution, schema XML, read/write classification, shell argv, output interpretation and formatting |
| `appfunctions/AppFunctionsManager.kt` | path decision (D2), discovery, schemas, cache, dispatch |
| `appfunctions/PlatformExecutor.kt` | direct path, JSON ↔ `GenericDocument` |
| `tools/AppFunctionsTool.kt` | the tool (D3) |
| `tools/AgentTools.kt` | `makeAgentTools(appFunctionsExposed)` |
| `ui/chat/ChatViewModel.kt` | gate = switch on **and** a path carries; branch in `executeTool()`; confirmation; display name |
| `tools/AgentToolSwitch.kt` + `ui/settings/AgentToolsSettingsScreen.kt` | switch `APP_FUNCTIONS` |
| `AndroidManifest.xml` | `EXECUTE_APP_FUNCTIONS`; `<queries>` for `android.app.appfunctions.AppFunctionService` |
| `res/values*/strings.xml` | settings texts in every shipped locale |

No system-prompt block: the tool describes itself.

## Rule D8 — Verification

- The core is tested on the JVM against **unmodified** schema files taken from
  real APKs (both shapes), not against hand-written samples only.
- Every regression test has a counter-check: deliberately break the core and
  the test must turn red.
- Both paths are tested on a device before the feature is called done: the
  Shizuku path on any Android 16+ device with Shizuku, the direct path on a
  device that grants the permission, with a developer allowlist entry
  (`cmd allowlist add-package-multimap 2 <pkg>:<sha256> '*'`,
  `cmd app_function flush-allowlist-changes`).
- CI builds a side-by-side installable debug APK (`applicationIdSuffix`), so
  testing never touches an existing install.

## Sources

- https://developer.android.com/ai/appfunctions ; reference of
  `Manifest.permission.EXECUTE_APP_FUNCTIONS`
- https://github.com/android/appfunctions (sample agent)
- AOSP `frameworks/base/core/api/current.txt` (android-16.0.0_r1),
  `packages/modules/AppSearch/framework/api/current.txt`,
  `frameworks/base/services/appfunctions/`

## Rule D9 — The core is a Rust binary; Kotlin is the shell around it

Decided by the maintainer of the fork, 31.10. Reason: the route that works is
Shizuku or root (the documented caller route is closed to a normal app, see
below), and everything on that route costs the model tokens and the device time.
So it is built once, small and fast, and wired into the APK.

**Split of work**

| Part | Does | Does not |
|---|---|---|
| `afc` (Rust, `deps/afc`) | registry, argument checks P0–P7, error codes, building the binder call, reading the reply, `refresh` | talk to Shizuku, hold any permission, know about the UI |
| Kotlin (thin) | decides the identity, starts `afc` as a child process, passes the result to the tool, owns the settings switch and the confirmation dialog | contain rules, registry or code tables |

Kotlin must address Shizuku itself, because Shizuku is a separate app with its
own binder and permission dialog. The Rust binary only ever sees the finished
call line. This is by design and not a gap.

**Identity and path, chosen by uid and measured, never by model or maker**

1. The process runs as uid 0 (root): `su 2000`, then `app_process`.
2. The process runs as uid 2000 (Shizuku without root): `app_process` directly.
3. Anything else: Kotlin has Shizuku run the call line; `afc` builds it
   (`--trocken`). Root first, Shizuku-only as the fallback, as in 1 and 2.
4. Neither is available: the tool says so with the reason; the direct path is
   tried only on a privileged install.

`weg.rs` implements 1 to 3 (`identitaet_von`, `startzeile`, 38 tests).

**Shipping**

- `afc` is built for `aarch64-linux-android` (NDK) and ships as
  `lib/arm64/libafc.so` with `extractNativeLibs=true`. The sandbox build for
  musl is a test build, not the shipped one.
- Measured on the test device: a static binary of 592 KB runs as uid 2000, and
  the directory `lib/arm64` of the installed app is world-readable and
  executable (mode 755). Measured (TEST6-LOG.md): the Android build of `afc`
  started from there as the app, and as the shell, works. Not measured: a
  Gradle build that packs it into the APK.
- `aus.jar` stays an asset. Without root the app directory is closed to uid
  2000, so the jar is placed once in `/data/local/tmp/af` (world-readable).

**What replaces what.** `AppFunctionsBinder.kt`, `AppFunctionsRegistry.kt`,
`AppFunctionsAntwort.kt` (about 600 lines of Kotlin) become one process call and
a text reply. Rules and tables then exist once, with tests, not twice.

**Contract between the two** (so the maintainer can wire it without reading Rust)

| | |
|---|---|
| Input | `afc <list|show|check|call|refresh> ...`, arguments as in `afc --help` |
| Output | text on stdout; the first token of a result line is one of `ERFOLG`, `EMPTY_RESULT`, `FEHLER <code>`, `ZEITUEBERSCHREITUNG`, `UNKLAR`, `NEEDS_CONFIRMATION` |
| Exit | 0 success; 2 refused or failed; 4 device test says nothing |
| Writes | only with `--schreiben`; Kotlin passes it only after the user's confirmation |
| Registry | `--registry` / `$AFC_REGISTRY` / `/var/minis/shared/afc/registry.txt`, else embedded |

**Open, said plainly:** no APK with the binary has been built; the Shizuku-only
identity was measured, from an empty state and with nothing as root after the
staging, on a rooted device with uid 2000 and SELinux context
`u:r:shell:s0` (see below), not yet on a device that has no root at all; the packaging through Gradle and the CI step for
`deps/build_afc_android.sh` were not done; `android-shizuku-cli` does not exist inside
the app, so the Shizuku call stays in Kotlin.

**Shizuku-only, measured (31.10.).** Run as `su -Z u:r:shell:s0 2000`, i.e.
`uid=2000 gid=2000 groups=2000 context=u:r:shell:s0`, the identity Shizuku has
without root: `app_process` direct, no `su` inside; one call answers with
content; `afc refresh` reads `dumpsys app_function` itself (61 of 61); `afc
proben` derives 11 probes; the device test gives 11 calls, 9 with content, 2
empty, 0 errors. Still different from a real device without root: the parent
process is the root-owned Shizuku server, and the process has only the group
`shell`, not the extra groups an `adb` shell carries. What is **not** covered:
placing `aus.jar` and the binary without root (Kotlin writes the jar through
Shizuku in base64; the binary comes from `lib/arm64`), and any ROM that blocks
`app_process` for the shell.

**Why the direct route is not the main one:** `EXECUTE_APP_FUNCTIONS` is
`internal|privileged` on the test devices and cannot be granted to an installed
app (COMPATIBILITY.md); the platform pages describe the Jetpack route for apps
that are allowed to call. We are not, so Shizuku or root is the path.

## Open decision: tools as name and one line, detail on demand

The app sends the full definition of every enabled tool with each request.
A provider API is stateless, so the name must be sent each time, but the long
description and parameter text could be fetched when the model asks for it, as
other agents do. This reaches every tool of the app, so it goes to the maintainer as an issue
that points at this fork, not as a pull request; `app_functions` alone can
shrink its own text without it.

## What the platform documentation says (read 31.10.)

Source: developer.android.com/ai/appfunctions and the API reference of
`android.app.appfunctions.AppFunctionManager`. AppFunctions is an Android
platform API (API 36, Android 16) with a Jetpack library, not a vendor feature.
Callers need `EXECUTE_APP_FUNCTIONS`. The documented test path is
`adb shell cmd app_function ...`, and the page itself says that only a limited
number of apps and system agents reach the whole pipeline in this experimental
phase. The manager offers `searchAppFunctions`, `getAppFunctionStates`,
`observeAppFunctions` and, from the 37.x line, `executeAppFunction` with an
`AppInteractionSession` and the property
`PROPERTY_SUPPORT_APP_FUNCTION_USER_CONTROL` (runtime user control). Device
tests must therefore rest on the platform and the Google Play services
functions; vendor apps are optional (see COMPATIBILITY.md section 0).

**Consequence for the path (smilez, 31.10.):** the documented caller route is
closed to this app. `EXECUTE_APP_FUNCTIONS` is `internal|privileged` and cannot
be handed to a sideloaded or normal app (measured, TEST2-LOG.md 4.). Shizuku or
root, with the shell identity (uid 2000, package `com.android.shell`), is the
only route that works, so the Shizuku/binder path is the main path, not a
fallback. The direct path stays in the code for a privileged install only.
