# Proposal — native agent tool `app_functions`

This file is the proposal that goes with this branch. It is the same text that was
sent as an issue; keeping it here means the reasoning travels with the code.

Two branches carry this feature and differ only in where the rules live:

| | branch | rules in |
|---|---|---|
| simpler, no native code | `feature/appfunctions-caller-native` | Kotlin |
| one core for Android and iOS | `feature/appfunctions-caller` | Rust (`afc`) |

Both are built, translated, tested and measured; the same gaps are open in both
(see "How it was tested").

---

## What this is

Android 16 ships **AppFunctions**, a platform API by which an app publishes typed
functions that an assistant may call ("Android MCP"). Minis cannot use them as an
agent tool today. This is a working implementation: a native tool `app_functions`
through which the model reads and writes other apps' functions via tool use —
without the screen, without the accessibility service and without the Linux
sandbox.

Typical targets: clock alarms and timers, reminders, calendar, contacts, call log,
gallery, browser bookmarks, device-state settings of Play services.

Out of scope here: Minis as a *provider* of its own AppFunctions (KSP and the
AppFunctions compiler, from `androidx.appfunctions` alpha09 on with compileSdk 37).

## Why

1. **The skill route does not work everywhere.** The existing skill calls
   `cmd app_function`, which is missing on some builds ("No shell command
   implementation"). A native tool does not depend on it and can use the Binder
   path instead.
2. **Token cost.** `AgentTools` rebuilds the definition of every enabled tool with
   each request. This tool's definition measured 1690 bytes (~469 tokens) and was
   cut to 689 bytes (~191); the detail moved into the answer of `list`, which is
   sent once per use instead of per request.

## Two variants — pick one

This is an **offer**, not a demand. Both branches are built, translated, tested and
measured on a device, and they answer the same in every case measured; the same
gaps are open in both (see the end of this text). They differ in **where the rules
live**.

| | `feature/appfunctions-caller-native` | `feature/appfunctions-caller` |
|---|---|---|
| Rules, registry, error codes | Kotlin (`AppFunctionsRegistry`, `AppFunctionsCore`, `AppFunctionsReply`) | Rust (`afc`, `deps/afc`) |
| Binder call | `deps/shim` → `shim.jar` | same shim |
| Native library in the APK | none | `lib/libafc.so` |
| Build needs | nothing extra | Rust toolchain + NDK step |
| Size of the diff | 50 files, ~80 lines added, ~3600 removed | 87 files |
| Read the logic in | Kotlin | Rust |

Apart from `deps/afc` and the call site in `AppFunctionsManager`, the two branches
are identical: `deps/shim`, the assets, the tool, the design and the logs.
`DESIGN.md` rule D9 describes both and the trade-off.

### What speaks for the Kotlin variant (the default I suggest)

- Nothing native: no `.so` per ABI, no NDK, no cargo in the build, the APK stays small.
- The whole diff stays inside the app module; a maintainer reviews Kotlin in Kotlin.

### What speaks for the Rust variant

- **One core for both platforms.** The rules — registry, argument checks, error
  codes, reply interpretation — are the platform-independent part. The same core
  can read Apple's **App Intents** on the iOS side, so a rule is written and tested
  once for Android and iOS instead of twice.
- **Small and predictable.** ~420 KB static binary, no GC pauses, no allocations
  that the JVM decides to make; the calling process is short-lived and exits.
- **Also usable outside the app.** `afc` runs from a shell, which is how the
  measurements in `docs/appfunctions/` were taken, and it can refresh the registry
  from a device dump.
- Cost: a native library in the APK, a Rust build step in the build.

## What this branch is, and what it is not

It is the **proof**: AppFunctions of other apps can be reached from Minis, read
and written, measured on a device. It is **not** the form to keep.

Google's guide names the documented route — `androidx.appfunctions:appfunctions:1.0.0-beta01`
with the KSP compiler, `compileSdk 37` and AGP 9.1.0 or newer. This module is on
`compileSdk 36` / AGP 8.7.3, and the build file already records what moving that
costs. So the branch uses what is available on 36: the framework classes plus a
small dex that reflects over the hidden AIDL.

**The proper integration is the maintainer's step**, and `DESIGN.md` rule D10 lists
what changes with it: `AppFunctionManager` from Jetpack instead of reflection,
`setAppFunctionEnabled` instead of the reflected AIDL, compileSdk 37 and AGP 9.1
with the KSP plugin. The rules, the registry, the argument checks, the
confirmation gate and every measurement stay as they are — they describe the
behaviour, not the transport.

If Minis ever *offers* its own AppFunctions, the same guide applies in full and
compileSdk 37 is a hard requirement.

## Where to look

Fork `Smilez1985/OpenMinis`. Start with the branch you are reading now (`feature/appfunctions-caller-native`, the
simpler one); the other is one branch away.

- `docs/appfunctions/DESIGN.md` — the design and its rules (D1…D9), target state.
- `docs/appfunctions/ROADMAP.md` — done but unreleased, and what is open.
- `docs/appfunctions/COMPATIBILITY.md` — what was measured on which Android build.
- `docs/appfunctions/TEST-LOG.md` … `TEST6-LOG.md` — field notes, one file per round.
- `docs/appfunctions/GLOSSARY.md` — old and new names, for the historical logs.
- `deps/shim/` — the small dex that reaches the platform Binder by reflection.
- `src/android/app/src/main/java/com/openminis/app/appfunctions/` — the tool itself.

Measured state: 61 functions in the registry; `list` reported 48 of them on the
test device. Read calls run end to end from inside the app — the app stages the
shim itself and the call ends with exit 0 and real device data.

## Three observations about upstream code

Found while building; each is small, and worth a look independently of this feature:

1. **Tool definitions are sent in full with every request.** `AgentTools` rebuilds
   every enabled tool's definition per request. Sending name plus one line, with
   the details fetched on demand, would cut that for every tool, not just this one.
2. **`ShizukuBackend.runProcess` loses the reason for a failure.**
   `AppLogger.warning(TAG, "newProcess reflection failed: ${it.message}")` prints an
   empty string for a `NullPointerException`. `it.stackTraceToString()` or at least
   the exception class would have saved a measurement round.
3. **The schema namespace of the platform changes between builds.** Returned
   documents carried `com.google.android.appfunctions.schema…` in an earlier round
   and `com.android.appfunctions.schema…` later. Nothing in Minis matches on it
   today, but anything that does will break silently.

## How it was tested

Rust: 53 unit tests and 9 CLI tests. Kotlin: 54 tests in the native variant, 67 in
the Rust one. A device test script, plus negative checks: the old protocol words,
`exec` instead of `sh -c`, and a single long command each make tests fail. Every
measurement in `docs/appfunctions/` names the build it came from.

Reading **and** writing were measured from inside the running app, in both
variants: a read call returns real device data, and a changing call created an
album in the gallery, which the target app returned as a document. The album was
removed again afterwards.

No CI, on purpose: GitHub disabled Actions on this fork ("Workflows are not
currently being run on this fork due to the scale of GitHub Actions usage"), so
the workflow cannot run there. A Gradle build of these branches therefore comes
from your side, not from mine.

Also not measured: `list` through the device command (the `cmd app_function`
dump is missing on the test build), and any second device or Android version.
