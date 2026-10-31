# Test log 6 — the Kotlin side, compiled and compared with Rust (31.10.2026)

Until now the Kotlin was "not compiled". There is no Gradle or SDK here, but
`kotlinc` 2.1.20 and the platform `android.jar` (API 36) can be fetched, and
that is enough to compile and run everything that does not need a device.

## What was done

- Compiled with `kotlinc`: `appfunctions/*.kt`, `tools/AppFunctionsTool.kt`,
  `ToolExecutionResult.kt`, `AgentToolDefinition.kt` (the real files), against
  `android.jar` 36, `kotlinx-coroutines` 1.9 and `androidx.annotation`. Two
  app classes were replaced by stubs with the same signature
  (`ShizukuManager`, `AgentToolSwitch`): they pull in Shizuku and the settings
  store. Result: 57 classes, **no errors**.
- Ran the existing JVM tests `AppFunctionsCoreTest` (JUnit 4.13.2, real
  `org.json`): **34 of 34 pass**.
- Compared Kotlin with Rust on **23 argument cases** (`AppFunctionsRegistry.pruefen`
  against `afc check`: verdict, rule ids, `a3`, `a4`) and **14 reply cases**
  (`AppFunctionsAntwort.deute` against `Antwort::deute`: kind, code, category,
  message, JSON).

## What the comparison found

Two real differences, both fixed on the Kotlin side:

1. A missing parameter that a call once succeeded with: Rust gives hint P1,
   Kotlin gave nothing (case `getPersons {}`).
2. `ERFOLG: null` after a JSON line: Rust says empty, Kotlin said success.

After the fixes: 23 of 23 argument cases and 14 of 14 reply cases are equal.

## What stays in the repo

`AppFunctionsParitaetTest` (6 tests) holds the verdicts as fixed expectations,
so a rule changed on one side turns it red until the other side follows.
Counter-check: with either old behaviour put back, exactly one test turns red.
`src/test/resources/appfunctions/registry.txt` is a copy of the asset and is
byte-identical to `deps/afc/data/registry.txt`.

## What this is not

It is not the Gradle build: no R8, no resources, no Compose, no lint, no
instrumentation. The two stubs hide the real `ShizukuManager` and the real
settings switch. What the maintainer still has to do is `./gradlew
testDebugUnitTest assembleDebug`; the risk left is in the parts that touch
those two classes and in the Gradle configuration, not in the logic.

## The Android build of `afc` and its start from `lib/arm64` (31.10.)

Measured on the test device with an installed debuggable app (`extractNativeLibs=true`):

- **Android binary:** `afc` built for `aarch64-linux-android` (Bionic, API 26,
  PIE, 16 KB aligned, `panic=abort`), 419 KB stripped. Built here with
  `-Zbuild-std`, `lld` and the NDK r27d sysroot, because the NDK compiler is an
  x86 binary and this device is aarch64. `unwind_stub.o` stands in for
  `libunwind` (with `panic=abort` nothing unwinds; `std` only needs the
  symbols to link).
- **Started from `lib/arm64/libafc.so` in three roles:**
  1. as the app itself (context `runas_app`): `codes`, `list` and
     `call --trocken` work; `refresh` fails as designed (the app may not run
     `dumpsys`) with a readable message;
  2. as the shell (context `shell`): a real call answers with content;
     `refresh` reads 61 of 61 functions;
  3. the full device test as the shell: 11 calls, 9 with content, 2 empty,
     0 errors.
- The probe file was removed from the app's directory after each run.

**Not the shipped path:** `deps/build_afc_android.sh` is the script for the
maintainer's NDK (clang from the NDK as linker). It was **not run** as written:
the NDK clang is x86-64 only. The binary above came from the `lld` route, so
the script is untested; the target, flags and output path are the same.
**Not measured:** a build through Gradle that packs `libafc.so` into the APK
(`jniLibs/arm64-v8a`, picked up by `useLegacyPackaging`/`extractNativeLibs`),
and the CI step that runs the script before Gradle.

## The Kotlin shell around `afc` (31.10.)

`AfcProzess.kt` starts `libafc.so` from `nativeLibraryDir` through Shizuku and
reads the first word of its result line. `AppFunctionsManager.ueberBinder`
uses it when the file exists and keeps the old Kotlin path otherwise.

- Compiled with `kotlinc`; 49 JVM tests pass (34 core, 6 parity, 4 shell,
  5 reading real `afc` output).
- `AfcProzess.quote` was run against a real `sh` with `'; echo PWNED #$(...)
  \`...\` | cat` and newline: the text arrives as text, nothing runs.
- `AfcProzess.deute` was written against recorded `afc call` output. It found
  that `NEEDS_CONFIRMATION` ends with **exit 0** (the check passed, nothing was
  called), so the exit code cannot decide; the text does. Counter-check: with
  that branch removed, one test turns red.
- The fixtures are real outputs; an account id inside one of them was
  replaced by `X` of the same length, and its lists were cut to two entries.

**Not measured:** the Shizuku call from inside the running app
(`ShizukuManager.runProcess` with the `AFC_JAR=… libafc.so …` line), because
that needs an APK containing both. The line itself was run by hand as the
shell with the same binary and succeeded. `--schreiben` is passed by the
manager because the confirmation already happened in `AppFunctionsTool`; that
hand-off is read, not exercised.
