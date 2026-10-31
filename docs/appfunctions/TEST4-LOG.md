# Test log 4 — the binder path, tested from inside the app (30.10.2026)

Where: the test device (Android 16) that runs
`com.openminis.app.appfn` — the branch's debug APK installed next to the signed
Minis (`com.openminis.app`, uid 10278). Shizuku runs; its context measured as
`u:r:magisk:s0`, i.e. the calls reach root.

## 1. `cmd app_function` does not exist on this device

The branch's Shizuku path (D5) runs `cmd app_function list-app-functions` /
`execute-app-function`. On this ROM:

```
$ cmd app_function list-app-functions
No shell command implementation.
```

So the branch's Shizuku path is dead here — the reason the middleware exists.

## 2. The binder path carries — measured from inside the app

The app's own Shizuku integration was driven through the debug server
(`DEV_TOOLS` build, `127.0.0.1:5321`, method `debug.shizuku.exec`), which invokes
`ShizukuOffloadHandler` with the given argv — the same handler the agent's
`android-shizuku-cli` uses. Two runs:

| Command | Result |
|---|---|
| `exec "id; getprop ro.build.version.sdk"` | `uid=0(root) context=u:r:magisk:s0`, SDK 36 |
| `exec "<script built by deps/afc>"` | `ERFOLG: {…Person _id=73…}` — three real contacts for `query=e`, `maxCount=3` |

The second command is the chain the middleware uses:

```sh
mkdir -p /data/local/tmp/af
cp <minis-global>/shared/afc/aus.jar /data/local/tmp/af/aus.jar
su 2000 -c "CLASSPATH=/data/local/tmp/af/aus.jar app_process / Ausfuehren \
  'com.samsung.android.app.contacts' \
  'com.samsung.android.contacts.person#PersonFunctions_findPersons' \
  com.android.shell 'findPersonsParams' b64:<params json>"
```

Repeated in the shape the new Kotlin builds (dex already on the device, no
search loop over `minis-global`) — same result. Both runs used
`com.android.shell` with uid 2000, i.e. the caller identity that works.

Measured earlier and still valid (see `MESSUNGEN.md`): uid 0 fails (no package
for uid 0), this app fails with 1000 (`EXECUTE_APP_FUNCTIONS` is
`internal|privileged`), uid 2000 works.

## 3. What the middleware brings that the app did not have

- A **registry** of 61 functions in 10 packages with parameters, field names,
  required flags, and the source of every statement (13 functions confirmed by a
  real call). On this device the platform cannot supply it — no `cmd
  app_function`, and the Samsung schemas are not in any dump.
- **Checking before the call** (P0–P7): unknown fields, missing required
  fields, wrong types, the `maxCount` limit of 30, an empty search term where
  the target app demands content.
- **Error translation**: 1000 / 1001 / 1002 / 1003 / 1500 / 2000 / 2001 / 2002 /
  3000 / 3501 with the meaning measured on this device, plus `EMPTY_RESULT` for
  "success but no content" — which is *not* a hit.

## 4. What is built but NOT yet verified

`AppFunctionsBinder.kt`, `AppFunctionsRegistry.kt` and `AppFunctionsAntwort.kt`
(Kotlin, plus `assets/appfunctions/{aus.jar,registry.txt}`) are written and wire
`AppFunctionsManager.execute` to the binder, with the registry as the fallback
list source. **They have not been compiled**: the sandbox of this install has no
working JVM (`java -version` → "Failed to mark memory page as executable"), so
there is no Kotlin/Gradle here. Verification has to come from CI and the device.

`deps/afc` (Rust) is the same logic, compiled and tested locally: 27 tests,
three counter-checks, and a device run over the confirmed functions (10 calls, 8
with content, 2 without as measured on 29.10., 0 errors).
