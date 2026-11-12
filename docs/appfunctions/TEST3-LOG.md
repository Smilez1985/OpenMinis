# test3-log — AppFunctions on the device, third round (gate, fix `8a0922d`)

Command names in transcripts are the names used at the time (see [GLOSSARY.md](GLOSSARY.md)).

Subject under test: `com.openminis.app.appfn`, installed as a parallel install next to
the signed build. Artifact of CI run `40306973616` (branch
`feature/appfunctions-caller`, tip `8a0922d`),
sha256 `e05f5bfe52b303966955b51acecd33f68b5889df7f213ae590b6e8320789f7d4`.

Device: the test device, Android 17 (SDK 37), Samsung. Call path: **Shizuku**
(uid 2000/shell) — the direct path is not possible on this device (2.1–2.2 of the
second round).

Switches: tool `App Functions` **on**, `Confirm actions that change something` **on**
(default off, deliberately switched on here — the goal of this round).

**Purpose of the round:** close the single gap measured in round 2 and
re-measure — a *confirmed* write call looked in the result like one without a
gate. The fix (`successNotes`) was meant to change that.

---

## 1. What was measured

| # | Check | Result |
|---|---|---|
| 1.1 | `list` counts and filters | 109 functions in 15 apps; `com` → all, `clock` → 18, `reminder` → 11, `calendar` → 6, `messaging` → 1. The path is shown in the output: `(path: shizuku)` |
| 1.2 | `schema` resolves nested types | Gallery `createAlbum(createAlbumParams: CreateAlbumParams)` → `CreateAlbumParams.title` (string, required, "The title of the album."), returns `Album`. For Samsung apps, by contrast, `(? unpublished)` |
| 1.3 | Without a declaration there is nothing to check | Reminders `TaskFunctions_createTask` with `{}`: the app declares no parameters, so the check does not trigger — the call reaches **the gate**. There it is the only guard, not the field check |
| 1.4 | **The gate holds** | The same call, not confirmed → `NEEDS_CONFIRMATION: the user did not approve TaskFunctions_createTask (verb 'create' changes something). Do not retry without asking.` No call to the target app |
| 1.5 | **The fix is effective on the device** | Gallery `createAlbum {createAlbumParams:{title:"APK-Test 8a0922d"}}`, **confirmed** → real return value (`Album` with `id 1268752007`, `dateCreated` 2026-10-26 15:07:30) **and** the new line: `(note: this call changes state and the user confirmed it before it ran)` |
| 1.6 | Control check: reading does not carry the line | `findAlbums {query:"APK-Test", maxCount:5}` → the same album, **without** the note line. So the addition hangs on the confirmation, not on the call path |
| 1.7 | What was written is there | The album read back carries the same `id 1268752007` and the same title — the call really created something |

Side finding (not a defect of the tool): `createAlbum` returns `dateCreated`
correctly, but `findAlbums` for the same album returns `1970-01-21 19:03:43` — the
gallery emits a placeholder value when searching. Whoever reads a date from the search
reads nonsense; from the creation it is usable.

## 2. Side finding about the test environment

The parallel install (`-PminisIdSuffix=.appfn`) is a **separate** setup,
not a second view of the same one:

| Layer | Relationship of the two installs |
|---|---|
| Sandbox root (`…/files/alpine-rootfs`) | **separate** — a fresh Alpine per install |
| `skills`, `shared`, `memory` | **separate** — what is there comes from a restored backup (the folders all carry the same timestamp of the copy operation), not from live access to the same files |
| Writes | take effect **only** in the install in which they happen |

Re-measured: the sandbox binding points to the data folder **of its own**
install (package id `com.openminis.app.appfn`), not to that of the
main install.

On the root: it comes with a **basic set** (`age`, `curl`, `busybox`,
`apk-tools`, `alpine-*`). Everything else — `git`, `python3`, `rg`, `gh` and whatever
else repo work needs — is manual work in exactly this install
and **not part of a backup**. Every new or restored
install is therefore a different working environment, even if the data looks
the same.

Immediate consequence for the working tree: dozens of files show up with a
**pure mode change** (`100755` → `100644`), because the restored storage
does not carry the executable bit. These changes **never** belong in a
commit; only a targeted `git add <file>`.

## 3. Open

- **No way to delete albums.** The gallery offers `createAlbum`, `findAlbums`,
  `updateMediaItem` — but no `deleteAlbum`. The test album has to be removed by hand in
  the gallery. For a write test this is the unpleasant side:
  writing without a way back.
- **Catalog for Samsung field names** remains the precondition for the
  write tests on clock, reminders and calendar (round 2, 2.2). Newly proven:
  the gate at least applies there (1.3).
- The switch `Confirm actions that change something` belongs **on** for such tests;
  the default stays off.
