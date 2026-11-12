# test2-log — AppFunctions on the device, second round (test app)

Command names in transcripts are the names used at the time (see [GLOSSARY.md](GLOSSARY.md)).

Subject under test: `com.openminis.app.appfn`, versionName 1.15-beta.6, installed
2026-10-26 08:39. **Hash of the installed `base.apk` =
`ada12f8bbf4f3dffb166bf6d8340cfd884e495220ec714c908ed2418db3caf77`** — equal to
the artifact of CI run `40255763109` (branch `feature/appfunctions-caller`,
tip `b2cb2ab`). So the build that was reviewed is the build that is tested.

Device: the test device, Android 17 (SDK 37), Samsung. Call path during the whole round:
**Shizuku** (uid 2000/shell).

Switches according to `shared_prefs/agent_settings.xml`:
`agent.tools.appfunctions.enabled = true`,
`agent.tools.appfunctions.confirmWrites` = `false`, later `true` (see 3.6).

---

## 1. What works (measured)

| # | Check | Result |
|---|---|---|
| 1.1 | `list` counts and filters | 109 functions in total; `google` → 34 in 5 apps, `clock` → 18, `messaging` → 1, `reminder` → 11. The path used is shown in the output: `(path: shizuku)` |
| 1.2 | Declared parameters are readable | `draftMessage(parameters: DraftMessageParameters)`, `updatePreviewImage(imageUri: string)`, `ringDevice(deviceId: string, makeItRing: boolean)`. Without a declaration: `(? unpublished)` |
| 1.3 | `schema` resolves nested types | Messages: `parameters → DraftMessageParameters.text` (string, required) with its description, plus `returns: DraftedMessage`. These details were missing entirely before — so the purpose of commit `b2cb2ab` is proven on the device |
| 1.4 | Check **before** execution | `{"parameters":{"bogus":"x"}}` → `INVALID_ARGUMENTS … unknown field 'parameters.bogus' (allowed: text); missing required field 'parameters.text' (string). Nothing was executed.` No call to the target app |
| 1.5 | The check doubles as information | `setDeviceStateItem` with a wrong field names the allowed ones: `allowed: key, itemizationKeys, value, requestInitiatedWhileUnlocked`. Useful because the Play services `schema` arrives truncated in the chat |
| 1.6 | Reading with a result | gms `getDeviceStateMetadata({})` → complete device-state metadata. `getDeviceStateItem(key=call_casting)` → `jsonValue "true"` |
| 1.7 | Writing with a result | gms `setDeviceStateItem(key=call_casting, value="true")` → `isSuccessful true`, `currentValue "true"`. The current value was deliberately written back so the device state stays unchanged |
| 1.8 | A missing schema does not block | Samsung clock `createTimer` (no declaration): the call goes through, the **target app** answers `Parameter [createTimerParams] cannot be null (code 1001)`. The check invents nothing and blocks nothing |
| 1.9 | Required fields can be elicited without a schema | Clock `createTimer` with `{}` → `createTimerParams` is missing; with an empty container → `Input time is not within permissible limits.` So the app names the outermost container, not the fields inside it |

## 2. Limits

### 2.1 `scope: activity` is not reachable via Shizuku
Messages `draftMessage` declares `scope: activity` and ends with
`SCOPE_ACTIVITY functions must be targeted with an AppFunctionActivityId (code 1003)`.
The call has no activity and cannot supply the identifier. The planned
Messages test is **not feasible** on this path; that is not a malfunction of
the tool but the limit of the caller.

### 2.2 Samsung does not publish parameters
The clock's `assets/app_function.xml` (9,392 B) contains, per function, only
`function_id`, `schema_category`, `schema_name`, `schema_version`,
`enabled_by_default`, `restrict_callers_with_execute_app_functions`,
`display_name_string_res` — **no parameters**. The field names cannot be
obtained from the package. The catalog has to be built from the behaviour / the
tooling of the target app.

## 3. Direct path: why it does not work on this device

### 3.1 The app does not hold the permission
`dumpsys package com.openminis.app.appfn`: `EXECUTE_APP_FUNCTIONS` is listed under
`requested permissions`, **not** under `install permissions` or
`runtime permissions`. No `granted=true` entry.

### 3.2 It cannot be granted
`pm grant com.openminis.app.appfn android.permission.EXECUTE_APP_FUNCTIONS` →
`SecurityException: … is not a changeable permission type` (rc 255). Shell rights
are not enough. The earlier assumption "register it once with Shizuku, after that it works
without" is therefore **refuted**.

### 3.3 It is privileged
The only place it appears in the system:
`/system/etc/permissions/privapp-permissions-platform.xml`. So it is only for apps from
the system image. The public reference names protection level `normal`, but
AOSP switches the variant by feature flag
(`android.permission.flags.app_function_access_api_enabled`); without the flag
`internal|privileged` applies. On this device the privileged variant applies.

### 3.4 An allowlist entry alone changes nothing
`cmd allowlist add-packages 2 com.openminis.app.appfn` was set
(`certificateDigest=null`), likewise for ID 1, followed by
`cmd app_function purge-allowlist-cache`. Result: the call still reports
`(path: shizuku)`. **Not separated:** whether the entry has no effect or the
app-internal probe state is cached — indistinguishable without an app restart.

AOSP says on this: both conditions must come together — permission **and**
allowlist (`CallerValidatorImpl`, branch `android17-release`). The second half
alone cannot achieve anything. `ALLOWLIST_ID_APP_FUNCTION = 2` is the right
list; the entry in ID 2 was therefore **kept**, ID 1 (meaning unknown,
only an experiment) was removed:
`cmd allowlist clear-shell-allowlist 1`.

### 3.5 No official path for third parties
Google's FAQ (developer.android.com/ai/appfunctions#faqs, as of 2026-10-22):
"only a limited number of apps and system agents can access the entire
pipeline"; access is through an early-access program, and signing up
"does NOT automatically obtain access". The package overview: functions are
"made available for execution by trusted, system-privileged applications".

Extra levers from newer Android branches (`set-additional-allowlisted-agents`,
`list-valid-agents`) are missing on this build; `cmd app_function` knows only
`help`, `list-app-functions`, `execute-app-function`, `set-enabled`, `is-enabled`,
`purge-allowlist-cache`.

### 3.6 Confirmation gate: tested, works — with one gap

Switch `confirmWrites=true`. Flow played through completely:

| Case | Result |
|---|---|
| Write call with the gate on | A confirmation request reached the user (confirmed by the user) |
| **Confirmed** | `isSuccessful true`, `currentValue "true"` — the call went through |
| **Declined** | `NEEDS_CONFIRMATION: the user did not approve setDeviceStateItem (verb 'set' changes something). Do not retry without asking.` |
| Control check for the decline | Write attempt to the **opposite** value (`call_casting=false`), declined, then verified by reading back: `jsonValue "true"` — **nothing written** |

**Gap:** In the *confirmed* case the tool result says nothing about a
confirmation having been required and having been given — it looks like a call
with no gate at all. The declined case, by contrast, is unambiguous. For the
evidence "who approved what, and when" the result lacks a note
(obvious candidate: "confirmed by the user" as an additional line).

## 4. System app on a rooted device (outlook)

On an unmodified device the direct path is unreachable: Shizuku has no
root rights, `/system` is mounted read-only as `erofs`, and privileged
apps must be in the system image. System-app status cannot be granted by an
installer; it requires the APK under `/system/priv-app/…` **and** an
entry in a `privapp-permissions-*.xml`.

In the app the call path already prefers the direct route, with Shizuku only as fallback
(decided on 2026-10-26) — nothing has to be built in the app for this, only the
privileged delivery is missing. Two further devices run their own system image on
Android 16 and have root; the setup can be demonstrated with them.

Rule for such interventions (decided on 2026-10-26): **every root intervention needs
a rescue path and must be able to undo itself in the worst case — proven by
a control check.** Plan and rollback:
`vorhaben/direktweg-system-app.md`.

## 5. Open items

- [ ] Add a result note for the confirmed case (3.6) — the only measured
      defect of this round.
- [ ] Successful write with a visible effect (clock timer) — needs
      the catalog for the Samsung field names.
- [ ] `scope=activity`: decide whether to leave it out or solve it through a caller with an
      activity (2.1).
- [ ] Demonstrate the direct path on a device with a privileged installation (4.).
