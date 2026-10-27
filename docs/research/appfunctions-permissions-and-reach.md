# AppFunctions: who may call, and what is actually reachable

_Measured on a Samsung device (Android 17 / SDK 37) on 2026-10-27, with
Shizuku running as uid 2000. Sources are named per claim._

## Summary

**Serving** an AppFunction needs no special permission — any app that declares
one gets it indexed. **Calling** one is gated: the caller needs
`android.permission.EXECUTE_APP_FUNCTIONS`, which a third-party app cannot
obtain. On top of that, the end-to-end pipeline is currently closed by Google.
The practical route to calling on a real device is a privileged shell
(here: Shizuku), which holds the system variant of that permission.

## 1. The caller side is permission-gated

developer.android.com/ai/appfunctions (read 2026-10-27):

> This lets you expose your app's capabilities as orchestratable “tools” that
> **authorized apps (callers)** can discover and execute to fulfill user
> intents. **Callers must have the `EXECUTE_APP_FUNCTIONS` permission** to
> discover and execute AppFunctions, and can include agents, apps, and AI
> assistants like Gemini.

And, on the finer-grained check:

> callers can verify whether a specific function is enabled within a target app
> using `isAppFunctionEnabled(packageName, functionId)`. **Querying the status
> of functions in other packages requires the
> `android.permission.EXECUTE_APP_FUNCTIONS` permission.**

So the asymmetry is deliberate and documented: **declaring is open, calling is
privileged.**

## 2. What that permission looks like on a device

Measured with `dumpsys package` and `pm`:

- `android.permission.EXECUTE_APP_FUNCTIONS` — protection level
  `internal|privileged|knownSigner`. A third-party app is outside all three.
- `pm grant <app> android.permission.EXECUTE_APP_FUNCTIONS` →
  `SecurityException`. It cannot be granted, not even by the user.
- The **shell** (uid 2000) holds `EXECUTE_APP_FUNCTIONS_SYSTEM`. Through it,
  `cmd app_function execute-app-function` works, and on this device the call is
  subject to **no allowlist check** — `cmd app_function allowlist` lists two
  entries and is otherwise empty and writable by the shell.

Consequence: a third-party app can call AppFunctions **only by going through a
privileged shell**. Without Shizuku (or root), reading another package's
function state is not available to it at all.

## 3. Reach: the pipeline is closed today

- **Early Access Program** — the registration form named in the documentation
  (`forms.gle/GN5ybjQFhzHRCguM7`) resolves to a closed form: *“The Early Access
  Program is currently at capacity.”* Checked 2026-10-27. A feedback form
  remains open; the EAP form does not accept entries.
- **Gemini** — “As of May 2026, AppFunctions integration with Gemini is in a
  private preview with trusted testers.”
- **The documentation's own FAQ** — “I have implemented AppFunctions in my app.
  Why can my system agent not access them? … **only a limited number of apps
  and system agents can access the entire pipeline.**”

So a newly published app that declares AppFunctions can be indexed locally, but
no system agent will reach it until Google opens the pipeline.

## 4. What apps on a real device actually expose

The registry was read with `cmd app_function list-app-functions`:

**16 packages, 114 functions** (2026-10-27).

For a set of them, the APK was extracted and inspected for three things: does it
ship `app_function` XML, does it contain `androidx/appfunctions` classes, and
does it contain the string `AppFunctionDeclaration`.

| Package | Functions | ships `app_function` XML | `androidx/appfunctions` | `AppFunctionDeclaration` |
|---|---|---|---|---|
| `com.sec.android.app.clockpackage` | 18 | yes (1 file) | no | no |
| `com.google.android.apps.adm` | 12 | **no** | no | no |
| `com.samsung.android.app.reminder` | 11 | yes (1 file) | no | no |
| `com.sec.android.gallery3d` | 9 | yes (3 files) | no | no |
| `com.google.android.googlequicksearchbox` | 3 | no | no | no |
| `com.google.android.apps.messaging` | 2 | no | no | no |

Two conclusions, both measured rather than inferred:

1. **No inspected app carries the annotation**, and none carries the Jetpack
   library at all. These apps declare through the **platform API** directly.
   Parsing DEX annotations would therefore return nothing on this device.
2. **`adm` exposes 12 functions while shipping neither XML nor the library** —
   a case where the registry knows more than the APK does. This is the argument
   for reading the registry first: it is built by the OS and covers declarations
   the APK does not carry.

Details on the XML format itself, the naming history and retention are in
[appfunctions-formats.md](appfunctions-formats.md).

## 5. What follows for a caller

- Read the **registry** first (`list-app-functions` / `dumpsys app_function`);
  fall back to the APK's XML for parameter detail where the registry is silent.
- Budget for a **privileged shell**. Without one, the feature is unreachable for
  a third-party app, regardless of API level.
- Do not expect system agents to reach an app that declares functions. Verify
  with `cmd app_function list-app-functions` that a declaration was indexed
  locally, and treat “an assistant can call it” as unproven until Google opens
  the pipeline.

## 6. Not verified

- Whether a **non-privileged** caller can execute a function in a package whose
  function is explicitly enabled for it. Not measured; the documentation gates
  the *query*, and no such caller was available to test.
- Whether the missing allowlist check on the shell path is intentional or a
  preview-phase gap. Observed state only.
