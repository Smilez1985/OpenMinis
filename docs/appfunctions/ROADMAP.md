# Roadmap — native tool `app_functions`

What is still missing relative to [DESIGN.md](DESIGN.md), by priority.

## Next

- [x] **Green CI build of the branch** — debug APK as artifact (run
      `40255763109`, `b2cb2ab`; sha256 `ada12f8b…caf77`, package
      `com.openminis.app.appfn`).
- [x] **Device test, Shizuku path** (D8) — done, see
      [TEST2-LOG.md](TEST2-LOG.md): `list`, `schema`, a read `execute`, a write
      with and without the confirmation switch, and the refusals.
- [ ] **Shizuku permission level** (D2 step 3) — consult the existing
      `OffloadPermissionManager` level for Shizuku before choosing the path.
- [ ] **Probe call for the direct path** (D2 step 2) — a granted permission
      alone must not select it; drop the result on code 1000.

- [x] **Device check of the confirmation fix** (`8a0922d`) — done, see
      [TEST3-LOG.md](TEST3-LOG.md): an approved write now says in its result
      that the user confirmed it, and a read of the same target does not. Two
      things surfaced on the way, both in that log: Samsung apps that declare
      no parameters reach the confirmation with **nothing** validated (the
      gate is then the only guard), and the gallery offers `createAlbum`
      without any matching delete — a write test there cannot be undone
      through the same interface.
- [ ] **Testing the parallel install** — the fork's APK installs next to a
      signed Minis as a self-contained second setup, not as a second view of
      the same one: its sandbox rootfs is a fresh Alpine, and `skills`,
      `shared` and `memory` are a **copy** (in the measured case from a restored
      backup, identifiable by one timestamp across every folder), so writes
      only land in the install that makes them. The rootfs ships a small base
      set (`age`, `curl`, `busybox`, `apk-tools`) — everything else, e.g.
      `git`, `python3`, `rg`, `gh`, is handwork per install and is **not** part
      of a backup. Details and the measurements: [TEST3-LOG.md](TEST3-LOG.md) 2.
      Also measured: the restored copy does not carry the executable bit, so
      dozens of files show up as mode-only changes (`100755` → `100644`) in the
      working tree — they must never be committed, add files explicitly.
- [ ] **Catalogue for predefined schemas** — Samsung apps declare no
      parameters (e.g. `clock/createAlarm@1`); a dated, labelled catalogue fills
      the gap so their calls can be validated too.
- [ ] **Read-back after write** — compare the stored state with the request.
- [ ] **Type checks of leaf values** (int vs. string) in the validation.
- [x] **Device check of the confirmation switch** — off: a write runs without
      a sheet; on: reject once, expect `NEEDS_CONFIRMATION`. The gap it exposed
      (an approved call looked like an ungated one) is fixed.

## Then

- [ ] **Cache per package and `versionCode`** (D4) — list and schemas.
- [ ] **Schema-driven argument conversion** (D5) — types, nested objects and
      arrays from the target's schema on the direct path.
- [ ] **Discovery on the direct path** (D4) — `searchAppFunctions` from API 37;
      needs compileSdk 37.
- [ ] **Path and reason in diagnostics** (D2).
- [ ] **Settings texts in every shipped locale** — today `en` and `de`.
- [ ] **Device test, direct path** (D8) — on a device that grants
      `EXECUTE_APP_FUNCTIONS`. Measured: on a stock device the permission is
      privileged and cannot be handed to a sideloaded app, so this needs a
      privileged install (TEST2-LOG.md 4.).

## Later

- [ ] **Read functions as individual tools** (D3, later stage).
- [ ] **Shizuku binder path** instead of the shell — `ShizukuBinderWrapper` on
      the `app_function` service.
- [ ] **Exposure decision** (D3) — native tool or sandbox CLI
      `android-app-functions`.
- [ ] **`scope=activity` functions** — decide whether to expose them at all.
      Measured: through Shizuku they answer `SCOPE_ACTIVITY … (code 1003)`,
      because the caller has no activity to name (TEST2-LOG.md 2.1).
