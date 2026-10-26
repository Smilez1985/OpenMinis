# Samsung apps: where their function parameters come from

Measured on a Samsung device, Android 17 (SDK 37), 2026-10-26, with the
`schema` and `execute` paths of the native tool. This note exists because the
roadmap item says *"Samsung apps declare no parameters"* — that is true for
most of them, but not for all, and the real gap is narrower and further down
than the sentence suggests.

## 1. What each app declares

Read from `assets/app_function.xml` inside each package:

| Package | Functions | with parameters in the XML |
|---|---|---|
| `com.sec.android.gallery3d` | 16 | **8** (including their own `dataType` definitions) |
| `com.samsung.android.app.contacts` | 6 | 0 |
| `com.samsung.android.app.reminder` | 11 | 0 |
| `com.samsung.android.calendar` | 6 | 0 |
| `com.sec.android.app.sbrowser` | 9 | 0 |
| `com.sec.android.app.myfiles` | 3 | 0 |
| `com.sec.android.app.clockpackage` | 18 | 0 |

So the statement holds per app, not for Samsung as a whole: the gallery
declares parameters and types like any app that used the compiler.

## 2. The runtime metadata has more than the list command

`cmd app_function list-app-functions` — the route the tool uses — returns
identifiers only. **`dumpsys app_function`** additionally carries, per
function:

```
"parameters": [{
  "isRequired": [true]
  "name": ["findAlbumsParams"]
  "dataTypeMetadata": [{ "dataTypeReference": ["com.google.android.appfunctions.schema.photos.v1.FindAlbumsParams"]
                         "type": [11], "isNullable": [false] }] }]
```

109 functions in 15 packages, 13 of them with parameter entries (gallery 5,
Play services 5, Google search 2, Messages 1). Example: gallery `findAlbums`
takes one required parameter `findAlbumsParams` of type
`…schema.photos.v1.FindAlbumsParams`.

## 3. A probe that costs nothing

For the apps that declare nothing, calling a **read-only** function without
arguments makes the failing side name its own parameter:

- a required field is missing → `Parameter [findAlarmsParams] cannot be null`
- right name, wrong type → `Find the property under [findAlarmsParams]. But the
  data type does not matched with the request`
- wrong name → back to "cannot be null", so the name stays unconfirmed

34 read-only functions probed, all without side effects; **20 parameter names**
were confirmed this way across clock, contacts, phone, calendar, reminders,
files and browser. The naming rule that shows up: `find<X>` → `<x>Params`,
`get<X>` → `<x>Ids`. The measured set is in
[catalogue/samsung-2026-10-26.json](catalogue/samsung-2026-10-26.json).

## 4. The fields *inside* the parameter object are not on the device

The parameter types are Google's schema classes,
`com.google.android.appfunctions.schema.**` — **285 of them ship in the clock
package alone** (clock, photos, browser, contacts, calendar, messaging …), from
the library `com.google.android.libraries.llm.appfunctions.appfunctions_schema`.
In the shipped build they are unusable as a source, and that was measured, not
assumed:

1. **Fields are renamed.** `FindAlarmsParams` carries the fields `a` … `i`
   (types in order: String, String, DateTime, DateTime, String, String,
   DayPattern, int, String) — the original names are gone.
2. **Kotlin metadata is stripped.** `…clock.FindAlarmsParams` has
   `annotations_off = 0`, i.e. no annotation at all; verified in the clock
   *and* the calendar package. The DEX metadata reader built for this does
   work — in the same files it recovers the original names for 2143 other
   classes (control: `androidx.annotation.InspectableProperty$ValueType` gives
   `k=1`, `mv=[1,9,0]`, names readable).
3. **No type checking inside the object.** `{"findAlarmsParams":{"maxCount":"x"}}`,
   `{"…":{"label":5}}` and invented fields all pass without an error, so there
   is no oracle there either.
4. **The call trace in the dump does not help**: it echoes the keys that were
   *sent* (test with `{"label":"test","xyz":1}` → both appear, values masked),
   not the ones accepted.
5. **No key names in the DEX**: no DDL strings, no AppSearch schema with field
   names.

The document envelope around a call does show a few real names —
`namespace`, `id`, `score`, `schemaType`, `creationTimestampMillis`,
`timeToLiveMillis`.

## 5. What this means for validation

A catalogue can check a function's **parameters**: name, required, type
reference — and for the apps that declare them (e.g. the gallery) their fields
too. For clock, contacts, reminders, calendar, browser and files it can check
the parameter object's name and type, and nothing inside it. That limit is
documented rather than guessed.

## 6. Open

- Inner field names can only come from the schema library itself: either a
  second source that still carries the Kotlin metadata (to be cross-checked
  against our own copy by field count, types and order), or a returned object
  with real data.
- The roadmap sentence should be sharpened: the gap is the *inner* fields and
  the apps that declare nothing, not Samsung as a vendor.
