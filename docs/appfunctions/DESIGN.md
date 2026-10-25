# Native tool `app_functions` — design notes

Branch `feature/appfunctions-caller`, based on release 1.15. This is a
reference sketch for a feature proposal, not a pull request (the upstream
repository does not take PRs). Measured facts and untested assumptions are
labelled as such.

## Goal

Minis calls the **AppFunctions of other installed apps** (Android platform API
since Android 16, "Android MCP") as a **native agent tool**: the model sees the
functions through tool use, without going through the screen, the
accessibility service or the Linux sandbox. Examples on the test device:
clock alarms/timers/stopwatch, reminders, calendar, contacts, call log,
gallery, browser bookmarks, Play services device-state settings.

Out of scope: Minis as a **provider** of its own AppFunctions (separate topic;
needs KSP and the AppFunctions compiler, and from `androidx.appfunctions`
alpha09 on compileSdk 37 + AGP 9.1).

## Finding that shapes the architecture

| Path | Requires | State on the test device (Samsung, Android 17 / SDK 37) |
|---|---|---|
| **Direct** (`android.app.appfunctions.AppFunctionManager`) | permission `EXECUTE_APP_FUNCTIONS` **and** an agent-allowlist entry **and** user consent | **not reachable**: the permission is `prot=internal\|privileged\|knownSigner`, so a sideloaded app does not get it. The reference docs say `normal`; that evidently does not hold on every device. |
| **Shizuku** (shell, uid 2000) | Shizuku running and granted | **works**: the shell holds `EXECUTE_APP_FUNCTIONS_SYSTEM`; `cmd app_function list-app-functions` and `execute-app-function` return real data (measured). Per the platform source this permission is not checked against the agent allowlist. |

Consequence: **one core, two execution paths.** The direct path is built and
chosen by measurement at runtime; where it does not carry, the core uses the
Shizuku path. The model does not notice — it is the same tool.

Further measurements (test device, read-only):
- ~110 registered functions in 15 packages (the number changes with app updates;
  the raw dump has two documents per function, static + runtime metadata).
- An allowlist service exists (`cmd allowlist`); list 2 (AppFunctions) is
  writable by the shell and currently empty. It does not help while the
  permission itself is not grantable.
- `targetSdk` is not evaluated by the caller check (platform source), so
  targetSdk 35 can stay.

## Architecture

```
model ──tool_use "app_functions"──▶ ChatViewModel.executeTool
                                       │  (switch AgentToolSwitch.APP_FUNCTIONS,
                                       │   confirmation for write functions)
                                       ▼
                              AppFunctionsTool  (schema, arguments, output)
                                       │
                                       ▼
                              AppFunctionsManager
                                 ├── decidePath: direct | shizuku | none
                                 ├── list    ── cmd app_function list-app-functions (Shizuku)
                                 ├── schema  ── assets/…app_function….xml from the target APK
                                 ├── direct  ── PlatformExecutor → AppFunctionManager.executeAppFunction
                                 └── shizuku ── cmd app_function execute-app-function (argv)
                                       │
                              AppFunctionsCore  (pure Kotlin, JVM-tested)
```

### Files

New (`src/android/app/src/main/java/com/openminis/app/`):

| File | Role |
|---|---|
| `appfunctions/AppFunctionsCore.kt` | Context-free core: parse the function list, resolve short names, parse schema XML (both shapes, DOCTYPE disabled), read/write classification, build the shell argv, interpret execution output, format output for the model |
| `appfunctions/AppFunctionsManager.kt` | Path decision, discovery, schema reading (`ZipFile` on `sourceDir` + splits), execution dispatch |
| `appfunctions/PlatformExecutor.kt` | Direct path: `executeAppFunction` with a generic JSON ↔ `GenericDocument` mapping |
| `tools/AppFunctionsTool.kt` | The tool: definition and `list` / `schema` / `execute` |

Hooks in existing files:

1. `tools/AgentTools.kt` — `makeAgentTools()`: parameter `appFunctionsExposed`,
   adds `AppFunctionsTool.definition()` (not for helper agents: write calls need
   the user mid-run).
2. `ui/chat/ChatViewModel.kt` — `agentTools` getter: exposed = switch on **and**
   a path that carries; `executeTool()`: branch `app_functions` (pattern of
   `browser_use`, incl. `toolDisabledResult`); `confirmAppFunctionWrite()`;
   display name "App Functions".
3. `tools/AgentToolSwitch.kt` — `APP_FUNCTIONS`, **off by default**;
   `ui/settings/AgentToolsSettingsScreen.kt` — switch row; strings `en` + `de`.
4. `AndroidManifest.xml` — `<uses-permission
   android:name="android.permission.EXECUTE_APP_FUNCTIONS"/>` (harmless where
   not granted, enables the direct path where it is) and a `<queries>` entry for
   the action `android.app.appfunctions.AppFunctionService` (measured as the
   service action of target apps).

No system-prompt block is needed; the tool describes itself.

### Tool shape (one tool, three actions)

Minis has no dynamic tool registration (the list is hard-coded), and a device
exposes 100+ functions, so this is **one** tool with an `action`:

| Parameter | Use |
|---|---|
| `action` | `list` \| `schema` \| `execute` |
| `package` | target app (schema, execute) |
| `function` | full id or unique short name after `#` (e.g. `findAlarms`) |
| `arguments` | execute: parameters as a JSON object (a JSON string is accepted too) |
| `query` | list: filter on package, function name or category |
| `timeout_s` | execute: seconds, default 30, max 120 |

Output is compact so the context does not fill up:
- `list` → per package the short names, `read`/`write`, category, `(disabled)`.
- `schema` → parameters with type, required, description; referenced data types.
- `execute` → the return value as JSON; errors with the platform code.

Possible later step: expose the **read** functions of frequently used apps as
individual tools at turn start (pattern: official sample `android/appfunctions`,
`agent/…/GeminiToolConverter.kt`, Apache-2.0).

### Discovery and schemas

- **List**: `cmd app_function list-app-functions` via Shizuku (JSON, measured).
  The direct path's own discovery (`searchAppFunctions`) needs API 37 /
  compileSdk 37 and is not wired; on API 36 AppSearch (`apps-db`, namespace
  `app_functions`) would be an option — **untested** whether a third-party app
  may read those documents.
- **Schemas**: from the target APK, asset files whose name contains
  `app_function` and ends in `.xml` (measured: Google apps ship full parameter
  schemas, Samsung apps only `schema_name` / `schema_category`). Read with
  `PackageManager` → `ApplicationInfo.sourceDir` → `ZipFile`; no privilege
  needed. Type codes follow `androidx.appfunctions`
  `AppFunctionDataTypeMetadata.TYPE_*` (0 unit … 8 string, 10 array,
  11 reference, 12 allOf, 13 parcelable, 14 oneOf).
- Where no parameter schema exists, the target's error names the missing
  field (measured: `Parameter [findAlarmsParams] cannot be null (code 1001)`);
  it is passed through to the model.
- **Not implemented**: caching the list/schemas per package `versionCode`.

### Execution

- **Direct** (`PlatformExecutor`): `AppFunctionManager.executeAppFunction(
  ExecuteAppFunctionRequest.Builder(pkg, id).setParameters(GenericDocument), …)`.
  JSON → `GenericDocument` is mapped **generically by JSON value type** (string,
  long, double, boolean, nested object, homogeneous arrays). Schema-driven typed
  conversion (as in the official sample) is a follow-up. compileSdk 36 is
  enough; no Jetpack dependency. Signatures were checked with `javap` against
  `platform-36_r02` `android.jar`. **Not tested on a device** (no device at hand
  grants the permission).
- **Shizuku**: `cmd app_function execute-app-function --package … --function …
  --parameters <json> --timeout-duration <s>` as an **argument array**, never
  `sh -c`; parameters validated as JSON first; timeout clamped to 1–120 s.
- **Path decision** (`decidePath`, per request): API < 36 → none;
  `checkSelfPermission(EXECUTE_APP_FUNCTIONS) == GRANTED` → direct; else Shizuku
  ready → shizuku; else none with the reason. Never by device model.
  **Not implemented**: a read-only probe call to confirm the direct path also
  passes the allowlist (today a granted permission alone selects it).

### Safety

- Switch **off** by default; the settings text names both paths.
- **Read** functions (short name verb `find|get|search|list|is|query|read|
  lookup|fetch`) run directly; **everything else** (create/delete/update/set/
  start/show/draft …) only after confirmation through the existing
  `ConfigConfirmationGate` (same dialog queue as `minis-config`, 120 s timeout,
  notification when backgrounded). Only an explicit approval proceeds. The
  classification is by name, not by evidence of side effects; the tool
  description says so. `show…` opens the target's UI and counts as write.
- An empty result with success status is reported as `EMPTY_RESULT`, never as
  success.
- Not offered to helper (sub-) agents.
- **Open**: the Shizuku path does not consult `OffloadPermissionManager`'s
  per-tool level (only the tool switch and Shizuku's own grant). Whether it
  should, is a product decision.
- Parameters/results are not logged; only package, short name and path.

### Error codes

| Code | Origin | Meaning |
|---|---|---|
| 1000 / 1001 / 1002 / 1003 | platform | denied / invalid argument (missing field) / disabled / function not found |
| 2000 / 2001 / 2002 | platform | system error / cancelled / enterprise policy |
| 3000 | target app | app error |
| `NO_PATH` | Minis | neither direct path nor Shizuku |
| `NEEDS_CONFIRMATION` | Minis | write function not approved |
| `EMPTY_RESULT` | Minis | rc 0 but no content — outcome unknown |

## Test stages

1. **Without a build (done, test device)**: the Shizuku path exercised with a
   local script — list count equals raw count, schemas from APKs, real read
   calls with data, write lock, error paths.
2. **JVM unit tests (done)**: `AppFunctionsCoreTest`, 18 cases, against two
   unmodified schema files taken from real APKs (Google shape, Samsung shape);
   a mutation check (17 deliberate defects in the core) turns every one red.
   The new files compile with kotlinc 2.1.0 against the API 36 `android.jar`.
3. **Device, Shizuku path (pending)**: CI debug APK installed side by side
   (`applicationIdSuffix .appfn`), tool on, `list` / `schema` / read `execute`,
   then one write function with confirmation.
4. **Device, direct path (pending, needs a suitable device)**: where
   `EXECUTE_APP_FUNCTIONS` is `normal`, plus an allowlist entry for developer
   tests: `cmd allowlist add-package-multimap 2 <pkg>:<sha256> '*'`,
   `cmd app_function flush-allowlist-changes`. **Untested**: whether the entry
   survives a reboot; whether the consent intent (`createRequestAccessIntent`,
   API 37) appears on production devices.

Build: `.github/workflows/android-debug.yml` on the fork builds the native
dependencies from source per `BUILDING.md` and a debug APK; the Gradle heap is
raised for CI only.

## Open / untested

- Which devices report `EXECUTE_APP_FUNCTIONS` as `normal`, and who is in the
  `knownSigner` list.
- AppSearch readability of the metadata for third-party apps (API 36).
- A Shizuku binder path (`ShizukuBinderWrapper` on the `app_function` service)
  instead of the shell — cleaner, but Minis has no binder-wrapper precedent yet.
- Functions with `scope=activity` (e.g. drafting a message) only work with the
  target screen in the foreground.
- Native tool vs. a sandbox CLI `android-app-functions` like the other
  `android-*` commands: the core would be the same, only the exposure differs.

## Sources

- https://developer.android.com/ai/appfunctions ;
  `Manifest.permission.EXECUTE_APP_FUNCTIONS` reference ("allowlist checks for
  AppFunctions API access are enforced at runtime")
- https://github.com/android/appfunctions (sample agent, schema converter)
- AOSP `frameworks/base/core/api/current.txt` (android-16.0.0_r1),
  `packages/modules/AppSearch/framework/api/current.txt`,
  `frameworks/base/services/appfunctions/` (tags 16.0.0_r1 / _r4, 17.0.0_r1)
- Measurements on the test device, read-only
