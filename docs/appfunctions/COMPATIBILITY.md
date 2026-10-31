# Compatibility — what runs where

"Android 16" is not enough to say whether AppFunctions can be called. What
decides it is the ROM: the platform service is part of the framework, but the
shell command, the class signatures and the registered apps differ between
builds. This page records what was **measured per device**; empty cells say
"not measured", never a guess.

The device names, ROM names and models below are given on purpose here, by the
maintainer's decision, because the matrix is useless without them.

## 1. Devices

| | Device A | Device B | Device C |
|---|---|---|---|
| Model | Galaxy S25 Ultra | SM-G780G (Galaxy S20 FE, `r8q`) | SM-N975F (Galaxy Note 10+) |
| ROM | Samsung stock | UN1CA 3.2.1 (custom, port) | EvolutionX (custom) |
| Android | 17 / SDK 37 ("Cinnamon Bun") | 16 / SDK 36 | 16 / SDK 36 |
| Build | not recorded | `BP2A.250605.031.A3.S901EXXSEGZH4`, patch 2026-08-05 | `BP4A.251205.006` |
| Root | none (Shizuku as uid 2000) | Magisk 31.1, Shizuku as uid 0 | Magisk, Shizuku as uid 0 |
| Notes | — | System image is a port of firmware `SM-S901E` (`r0q`); the vendor part is still the device's own Android 11 (`r8q`, `G780GXXSHEYJ1`) | 11.7 GB RAM, SELinux enforcing |

## 2. What was measured

| Check | A: S25 Ultra | B: S20 FE / UN1CA | C: Note 10+ / EvolutionX |
|---|---|---|---|
| Service `app_function` registered | yes | yes (`service list`) | yes |
| `cmd app_function` exists | yes | **no** — "No shell command implementation." (also as uid 2000) | yes, **but only `list-app-functions [--user]`** |
| `list-app-functions --package P` | yes (skill uses it) | — (no command) | **no** — "Unknown option: --package", rc 255 |
| `dumpsys app_function` | not recorded | yes: 61 functions in 10 apps | not recorded |
| `EXECUTE_APP_FUNCTIONS` | `internal\|privileged\|knownSigner`; not grantable to a normal app | `internal\|privileged`; granted to `com.android.shell`, not to a normal app | not recorded |
| `ExecuteAppFunctionAidlRequest(Request, UserHandle, String, long)` | not measured | **present** (`konstruktoren_BP2A.txt`) | **absent** — only the 5-argument form `(…, long, long)` exists (`NoSuchMethodException` for the 4-argument one) |
| Binder path (`aus.jar` as uid 2000, package `com.android.shell`) | not measured | **works**: 13 confirmed calls, 10 of 10 in the device test | **fails** with the 4-argument jar (rc 137, see above); **works** with the reflection jar (row below) |
| Plain skill `appfunktionen` | works (25.10.) | **fails** at its first step | **fails** (`--package` not accepted) |
| Device test with the hardened `aus.jar` (constructor chosen by reflection) | not measured | **8 with content, 2 empty, 0 errors** with the screen unlocked; with the screen **locked**: 5 / 2 / 3 errors | **2 with content, 0 empty, 8 errors**, unlocked and screen on. Both Google functions answer; the 8 others fail with 1003 "App function not found" because none of the five Samsung packages is installed. That is the correct answer, not a fault of the jar. |
| Registered apps | 114 functions in 15 packages, incl. Samsung apps | 61 functions in 10 apps, incl. Samsung contacts, gallery, dialer | 33 functions in 5 packages (gms 20, wellbeing 4, maestro.companion 5, googlequicksearchbox 3, permissioncontroller 1); no Samsung apps |

Sources: [TEST-LOG.md](TEST-LOG.md) and [TEST2-LOG.md](TEST2-LOG.md) (device A),
[TEST4-LOG.md](TEST4-LOG.md) and [TEST5-LOG.md](TEST5-LOG.md) (device B),
a read-only run of the test package on device C (31.10.). Device C's
constructor list was read with a read-only probe (31.10.): the only constructor
takes five arguments, `(ExecuteAppFunctionRequest, UserHandle, String, long, long)`;
the request class also gained a constructor with `AppFunctionAttribution`.

## 3. What is missing, and whether we can bring it ourselves

| Missing | Where | Can it be brought? | Status |
|---|---|---|---|
| Shell command `cmd app_function` | B | **Yes, without the ROM:** call the service through the binder (`aus.jar`, `app_process` as uid 2000). This is the path of the native tool. | measured on B |
| `--package` on `list-app-functions` | C | Yes: do not pass it, filter the full list ourselves. For the skill this is a small change. | proposed, not built |
| The 4-argument constructor of `ExecuteAppFunctionAidlRequest` | C | **Yes, measured.** Device C declares `(ExecuteAppFunctionRequest, UserHandle, String, long, long)`. `aus.jar` now picks the constructor by reflection (types, then only `long`) and prints the declared signatures when none fits; with it the calls on C succeed. | measured on C |
| Samsung's own AppFunctions (contacts, gallery, dialer, files, browser) | C | **No.** They come with Samsung's apps, which this ROM does not register. | measured on C |
| `EXECUTE_APP_FUNCTIONS` for a normal app | A, B | **No.** It is `privileged`; only the shell identity (uid 2000, via Shizuku) holds it. A device without Shizuku cannot call. | measured on A, B |
| Functions switched off by the platform (`enabled=2`) | B | Partly: `setAppFunctionEnabled` works only as the owner uid of the target app. | measured on B |

### Screen lock

Several functions refuse to run while the device is locked: the platform
answers 1002 "Device is locked" (`findBookmarks`) or 3501 "called while screen
is locked" (the two Google Play services functions). Measured on device B: the
same ten calls give 5 / 2 / 3 errors locked and 8 / 2 / 0 unlocked, with the
same `aus.jar`. A failed test on a locked device says nothing about the build.

## 4. Consequences for the native tool

- The tool must not assume one class signature. Select the constructor by
  reflection and report what the class declares when none fits; otherwise a
  second Android 16 build breaks it, as device C shows.
- The tool should tell the model that a refusal 1002/3501 may be the lock
  screen, not a missing function.
- The tool must not depend on `cmd app_function`; on device B it does not
  exist.
- The reply to the model should carry the reason of a failure (rc, exception
  line). Today the Rust tool prints an empty message when the Shizuku call
  fails.
- The function list is per device. A registry measured on one device is not the
  list of another (device C has none of the Samsung apps).

## 5. Open

- The two `long` values of the 5-argument constructor are **assumed** (first
  `elapsedRealtime`, second wall clock, guessed from the method name
  `getRequestWallTime`). The service accepts them; whether it rejects the
  swapped order was not tested (read-only).
- The device test counts "package not installed" (1003) as an error; it should
  be its own class so a device without the Samsung apps does not turn red.
- The plain skill still passes `--package` and fails on device C; not changed.
- Build and build number of device A are not recorded.
- Whether the binder path works on device A (stock, no root) is not measured;
  without root `aus.jar` could be started directly as uid 2000.
- The ROM version of EvolutionX on device C is not recorded.
