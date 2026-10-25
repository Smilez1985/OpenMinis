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
- Schema XML comes from another app: parsed with DOCTYPE and external entities
  disabled.

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

- The tool switch is **off** by default.
- **Read** functions — verb of the short name in `find|get|search|list|is|query|
  read|lookup|fetch` — run directly. **Everything else** (create, delete,
  update, set, start, show, draft …) runs only after the user approved it
  through the existing confirmation gate (`ConfigConfirmationGate`: one dialog
  queue, timeout, notification when backgrounded). Reject and timeout do not
  proceed.
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
