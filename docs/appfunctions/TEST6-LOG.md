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
