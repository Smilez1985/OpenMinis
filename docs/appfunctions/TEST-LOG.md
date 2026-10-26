# AppFunctions on-device test log

Field notes from testing the `app_functions` tool against real apps on a Samsung
S25 Ultra (Android 17 / SDK 37, "Cinnamon Bun"). Every line here was observed on
the device; nothing is inferred unless it says so.

- **Build under test:** fork `Smilez1985/OpenMinis`, branch `feature/appfunctions-caller`,
  commit `8ad6f8a`, APK installed as `com.openminis.app.appfn` (side by side with
  the signed 1.15).
- **Execution path:** `SHIZUKU` — `android.permission.EXECUTE_APP_FUNCTIONS` is not
  granted (on this device it is `internal|privileged|knownSigner`), so every call
  goes through `cmd app_function` as the shell user (uid 2000).
- **Test session:** 2026-10-26, 01:42–02:15 CET. Times below are device wall clock.
- **Logger tag:** `AppFunctions` (path decisions) and `ToolChain[VM]` (tool turns).

---

## 1. Chronological log

| Time (CET) | Action | Result |
|---|---|---|
| 01:42:36 | `list` (filter `clock`) | 18 functions in `com.sec.android.app.clockpackage`, path `shizuku` |
| 01:42:40 | `schema` for `createAlarm` | **failed**: `http://apache.org/xml/features/disallow-doctype-decl` |
| 01:42:49 | `execute createAlarm {}` | error: `Parameter [createAlarmParams] cannot be null (code 1001)` |
| 01:42:57 | `execute createAlarm {"createAlarmParams":{}}` | error: `Parameter specified as non-null is null: … CreateAlarmParams.<init>, parameter time (code 3000)` |
| 01:43:18 | `execute createAlarm {"createAlarmParams":{"time":{"hour":7,"minute":30}}}` | **success — but the alarm landed at 00:00** (returned `time.hours=0, minutes=0`, day 27.10.) |
| 01:43:25 | `findAlarms {}` / `{"query":""}` | `[]` — although the alarm from 01:43:18 exists |
| 01:43:28 | `getAlarms {}` | error: `Parameter [alarmIds] cannot be null` |
| 01:46:57 | `deleteAlarms {"deleteAlarmsParams":{}}` | error: `Parameter [alarmIds] cannot be null` — note: the parameter is **top level**, not nested |
| 01:48:17–19 | `deleteAlarms {"deleteAlarmsParams":{"alarmIds":[999999999]}}` | error `alarmIds cannot be null`; the platform log shows the document carrying `deleteAlarmsParams` but no `alarmIds` |
| 01:44–02:15 | schema research (see §3) | the platform does publish parameters — we were reading the wrong place |

Screenshot 01:47 CET confirms the side effect: the alarm list contains
`00:00, Di 27. Oktober` (on) next to `12:30, Mo 26. Oktober` (off).

---

## 2. Findings

### F1 — The schema reader is broken on Android, and the unit tests cannot see it
`AppFunctionsCore.parseSchemaXml` calls
`DocumentBuilderFactory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)`.
That is a Xerces feature name; Android's built-in parser does not know it and
throws. On the JVM (where the unit tests run) it works, so 18/18 tests stayed green
while the tool failed on the first real device.

**Lesson:** a green JVM test says nothing about an Android system API. Any code
that touches `javax.xml`, `java.time` or platform classes needs a device test or an
androidTest twin.

### F2 — Parameters are dropped silently, and that is dangerous
`hour`/`minute` are not the field names (they are `hours`/`minutes`). Instead of
rejecting the unknown keys, the call succeeded and created an alarm at **00:00**.
A tool that silently turns a wrong request into a plausible-but-wrong action is
worse than one that refuses: the model gets `success` and reports it.

**Rule for the tool:** never map or drop silently. Unknown keys, wrong types and
missing required fields must produce an error, always.

### F3 — `findAlarms` reports "nothing" while an alarm exists
Same root cause: the parameter document is not what Samsung expects, the app
returns an empty list, and the tool reports an empty result as an answer. An empty
list from a filter the caller invented is not evidence of absence.

### F4 — Withdrawn: the confirmation gate did ask
First read: `executeTool START 01:48:17.300` → app executed at `01:48:19.582`,
too fast for a sheet, so the gate looked bypassed.

Corrected: the gate asked and the tester approved it (confirmed by the tester).
2.3 s is enough for one tap. The code agrees: `deleteAlarms` classifies as a
write (verb `delete`), `runExecute` calls `confirm` before `execute`, the gate
suspends until `userApprove`/`userReject`/120 s timeout, and the only bypass
(`skipConfirmation`) belongs to config-audit reverts, not to this tool.

The real defect is upstream of the gate: the sheet showed arguments the model
had **guessed** (F2/F6), so the user approved a wrong call in good faith. A
confirmation is only as good as what it shows. Consequences:
- validate arguments against the declared parameters **before** asking (S2), so
  an invalid call never reaches the sheet;
- show the resolved, validated arguments in the sheet, not the raw model input.

Still owed: a rejection check on the device (reject once, expect
`NEEDS_CONFIRMATION`, nothing executed).

### F5 — There is no schema getter in the API level we compile against
`javap` on `platform-36_r02/android.jar`:
`android.app.appfunctions.AppFunctionManager` has exactly
`executeAppFunction`, `isAppFunctionEnabled`, `setAppFunctionEnabled`.
That is what led to the note "the caller gets no schema". **That conclusion was
wrong** — see §3. The methods exist, they are just newer than SDK 36, and no
SDK 37 platform package is published yet (`platform-37_r0*.zip` → 404 on
dl.google.com, measured 2026-10-26).

### F6 — What the discovery dump already contains (and we threw away)
`cmd app_function list-app-functions` returns, per function, the static metadata
document. Field sets measured:

| App | Fields in the metadata document |
|---|---|
| `com.google.android.googlequicksearchbox` (GMS) | 12, **including `parameters` and `response`** |
| `com.sec.android.app.clockpackage` (Samsung Clock) | 12, **no `parameters`** |
| `com.samsung.android.app.reminder` | 12, **no `parameters`** |

GMS example (real output):

```json
"parameters": [{
  "isRequired": [true],
  "name": ["textQueryIntelligentAssistParams"],
  "dataTypeMetadata": [{
    "dataTypeReference": ["com.android.personalcontext.appfunctions.schemas.TextQueryIntelligentAssistParams"],
    "type": [11], "isNullable": [false]
  }]
}]
```

`type: 11` is a reference to a data type that the same app declares in its XML
asset (`<dataTypes>`). So for GMS the full command set **is** machine-readable
today, without any new API. Our `FunctionEntry` model
(`packageName, id, schemaName, category, scope, enabledByDefault`) simply never
read the field. **This is the main defect behind the trial-and-error.**

### F7 — Samsung publishes no parameters at all
`assets/app_function.xml` inside Samsung Clock: 9392 bytes, 18 entries, each with
only `function_id`, `schema_category`, `schema_name`, `schema_version`,
`enabled_by_default`, `restrict_callers_with_execute_app_functions`,
`display_name_string_res`. No `<parameters>`, no `<dataTypes>`.
Samsung Calendar: the same shape, 3116 bytes.

These functions implement a **predefined schema**: `schemaCategory=clock`,
`schemaName=createAlarm`, `schemaVersion=1`. The contract lives in the schema, not
in the app. The parameter class the runtime named is
`com.google.android.appfunctions.schema.common.v1.clock.CreateAlarmParams` — a
Google library bundled inside Samsung's app, not published anywhere reachable.

### F8 — Parameter naming is inconsistent across functions
- `createAlarm` expects one top-level parameter `createAlarmParams` (a document).
- `deleteAlarms` expects a top-level `alarmIds`.
- `getAlarms` expects a top-level `alarmIds`.
- `findAlarms` accepts `findAlarmsParams` and returns `[]`.

There is no rule; only the schema says which. Which is the whole point.

### F9 — The Clock database is not readable, so a wrong write cannot be undone remotely
`/data/data/com.sec.android.app.clockpackage` is not accessible to shell,
`run-as` is refused (not debuggable), the Scloud provider needs
`com.samsung.android.scloud.backup.lib.read`, and `deleteAlarms` requires IDs that
nothing hands out. A wrong alarm has to be removed by hand in the UI.

---

## 3. What the official documentation says

Researched 2026-10-26, 02:00–02:20 CET. Sources are quoted, not paraphrased from
memory.

### 3.1 Declaring a function (app side)

`android.app.appfunctions` package summary (developer.android.com):

> "Declaring Function Metadata — All app functions, regardless of implementation,
> must be declared in an XML asset file. This file defines the AppFunctionMetadata
> for each function."
>
> ```xml
> <appfunctions><appfunction><id>createNote</id>
>   <enabledByDefault>true</enabledByDefault>
>   <parameters>...</parameters><returnType>...</returnType>
> </appfunction></appfunctions>
> ```

So the parameter schema belongs in the app's XML asset — exactly the file
Samsung ships without parameters.

### 3.2 Discovering a function (agent side) — this is the official answer

Same page, section "The Agent: Discovering and Executing Functions":

> "1. Discovering App Functions — Agents can find available functions using
> `AppFunctionManager.searchAppFunctions`. This method returns the
> `AppFunctionMetadata` for functions that match the given `AppFunctionSearchSpec`."
>
> "2. Retrieving Runtime State — Agents can query the current `AppFunctionState`
> using `AppFunctionManager.getAppFunctionStates`."
>
> "3. Executing App Functions — Once an agent has the necessary metadata and
> confirms the function is enabled, it can execute the function using
> `AppFunctionManager.executeAppFunction`."

`AppFunctionMetadata` (developer.android.com), API level 37:

> "Contains an app function's metadata, essential for its invocation and discovery,
> retrieved using `AppFunctionManager.searchAppFunctions`. This metadata is defined
> in an XML asset file and does not change at runtime. […] All properties in the
> XML (including unknown properties) are made available through the
> `getMetadataDocument` method."

Public methods: `getMetadataDocument()`, `getName()`, `getSchemaMetadata()`,
`getPackageMetadata()`, `getAccessLevel()`, `getScope()`.

`AppFunctionSchemaMetadata`:

> "Contains identifying metadata for a predefined schema, which can describe
> well-known function signatures. Returned from `AppFunctionMetadata.getSchemaMetadata`."

### 3.3 Consequence for us

The API we need is `searchAppFunctions` + `getMetadataDocument` — API level 37.
Our build is `compileSdk 36`, and no SDK 37 platform package is downloadable yet.
So:

1. **Today, without new APIs:** the `parameters`/`response` field is already in the
   metadata document that `list-app-functions` dumps (F6). Parsing it is a pure
   code fix, no permission and no new API level.
2. **On Android 17 devices:** call `searchAppFunctions` reflectively
   (`Build.VERSION.SDK_INT >= 37`) to get `AppFunctionMetadata` including the
   metadata document, and `getSchemaMetadata()` for the predefined schema identity.
   Reflection keeps `compileSdk 36` working and needs no build-tools upgrade.
3. **What no API can give:** the parameters of a *predefined* schema that the app
   does not declare (F7). Neither the platform nor the app publishes
   `clock/createAlarm@1`. That gap has to be filled by us.

---

## 4. Design: from a guess to a published contract

The target is what MCP does for tools: a machine-readable `inputSchema` in front of
every call, so an agent never has to try. Mapping:

| MCP | AppFunctions equivalent |
|---|---|
| `tools/list` | `searchAppFunctions` / metadata document |
| `inputSchema` (JSON Schema) | `<parameters>` + `<dataTypes>` in the app's XML asset |
| `tools/call` with validation | `executeAppFunction` with a typed `GenericDocument` |
| schema errors | `AppFunctionException` code 1001/3000/1003 |

Steps, in the order they pay off:

**S1 — Parse what is already there (fixes GMS completely).**
Extend `FunctionEntry` with `parameters` and `response`: for each parameter
`name`, `isRequired`, `dataTypeMetadata.type`, `dataTypeReference`, `isNullable`.
Resolve `type: 10` (array) and `type: 11` (ref) against the app's `<dataTypes>`.
Then `list` can print a real signature instead of a name.

**S2 — Validate before executing.**
Build the parameter document from the schema, not from the model's JSON:
check unknown keys (error), required keys (error), types (error). This alone
prevents F2 and F3. Error text must name the expected field and its type.

**S3 — A schema catalogue for predefined schemas.**
For functions with `schemaCategory`/`schemaName`/`schemaVersion` and no declared
parameters, ship a versioned catalogue in the app:

```
schemas/clock/createAlarm/1.json   { "createAlarmParams": { "hours": "int", "minutes": "int", … } }
```

Rules: only entries verified on a device; each entry carries the date and device
of the measurement; `schemaVersion` is part of the key, so a bump does not silently
reuse old knowledge; the tool says which source it used (declared / catalogue /
none) in its output.

**S4 — Prefer the official API where it exists.**
On SDK ≥ 37, `searchAppFunctions` reflectively; fall back to the CLI dump. The
dump has proven sufficient for GMS, so this is an optimisation, not a blocker.

**S5 — Never trust a write.**
After a write, read the state back where the category allows it (clock: `getAlarms`
/ `findAlarms`), compare with the request and fail loudly on a mismatch. This turns
F2 ("created at 00:00, said success") into a reported error.

**S6 — Expose it as a real tool surface.**
`app_functions` should have two verbs the model can rely on:
`describe(package, function)` → the signature, and
`call(package, function, arguments)` → validated execution.
The model should never have to guess a field name, and the tool should never invent
one.

---

## 5. To improve — ordered

1. **Validate before confirming (F4, corrected).** The gate works; it showed
   guessed arguments. Strict validation (S2) must run before the sheet. Device
   check: reject once, expect `NEEDS_CONFIRMATION`.
Status after commit "declared parameters": items 2, 3 and 4 are built and
unit-tested (fixture = unmodified excerpt of the device dump); a device test
of them is still owed.

2. **Schema parser (F1).** Replace `setFeature` with `runCatching` plus
   `isExpandEntityReferences = false`, or parse with Android's `XmlPullParser`.
   Add a device test — a JVM test cannot cover this.
3. **Parse `parameters` (F6/S1).** Biggest single win against trial and error.
4. **Strict validation (S2).** No silent defaults, ever.
5. **Catalogue for predefined schemas (S3).** The only way Samsung apps become
   usable, and it must be labelled as knowledge, not measurement.
6. **Read-back after write (S5).**
7. **Reflective `searchAppFunctions` (S4)** once someone runs Android 17 in a
   build with tools for it.
8. **Package the sandbox additions** (see §6) so `python3`/`ripgrep` are not a
   manual post-install on every device.

---

## 6. Side findings, not AppFunctions

- **The sandbox image has no `python3` and no `ripgrep`** after a fresh install
  with a restored backup. The system prompt instructs the agent to use `ripgrep`
  for every scan, so its absence is an app-level gap, not a user problem.
  `scripts/prepare_android_sandbox.sh` downloads the plain Alpine minirootfs.
  Measured install sizes: `python3` 25 MiB (≈10–12 MiB compressed), `ripgrep`
  3 MiB (≈1.5 MiB) — the APK would grow from 72 MB to roughly 82 MB.
  Two routes: bake into the image at build time (offline-safe), or install on
  first start the way `OnDemandBash` already does for `bash` (no APK growth,
  needs network once).
- **CI:** run `40235800913` did not abort, it hit the 90-minute job limit. Cause:
  24 `AnthropicProviderTest` failures in the public mirror
  (`ANTHROPIC_OAUTH_IDENTIFIER_PROMPT` is empty by design, `ClaudeOAuthManager.kt:56`),
  then 75 minutes of silence. Fixed in `8ad6f8a` by splitting the steps:
  `*AppFunctions*` gates (20 min), the full upstream suite runs with a CI
  placeholder prompt (25 min, `continue-on-error`). Run `40243166053`: green,
  full suite 6197 tests / 28 failed / 23 skipped, all upstream, no hang.

---

## 7. Open, unverified

- **F4 rejection path** on the device (approval path is confirmed).
- **Does `searchAppFunctions` return parameters for Samsung's predefined-schema
  functions?** Unknown. The platform reads the same XML, so probably not — but that
  is an assumption. Test: small APK with `compileSdk 37` (or a reflective call)
  on this device, print `getMetadataDocument()` for `AlarmCrossAppActions_createAlarm`.
- **Where predefined schemas are published.** `AppFunctionSchemaMetadata` speaks of
  "well-known function signatures"; no public list was found. If Google publishes
  one (AOSP `appfunctions-common`, a Jetpack artifact, or per-category XSD), S3
  becomes parsing instead of measuring. Worth another look before hand-writing a
  catalogue.
- **The 00:00 alarm** created at 01:43:18 is still on the device; removed by hand.

---

## 8. Reproducing

```sh
# list what the device knows (shell user, no app permission needed)
adb shell cmd app_function list-app-functions

# full signature of one entry, including parameters (GMS example)
adb shell cmd app_function list-app-functions | jq '."com.google.android.googlequicksearchbox"'

# execute (function ids are "<package>.<module>#<Class>_<method>")
adb shell cmd app_function execute-app-function \
  --package com.sec.android.app.clockpackage \
  --function "com.sec.android.app.clockpackage.crossappactions#AlarmCrossAppActions_findAlarms" \
  --parameters '{"findAlarmsParams":{}}'

# the schema an app declares
adb shell pm path com.sec.android.app.clockpackage
adb shell unzip -l <base.apk> | grep -i app_function
adb shell unzip -p <base.apk> assets/app_function.xml
```
