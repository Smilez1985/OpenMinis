# Roadmap — native tool `app_functions`

What is still missing relative to [DESIGN.md](DESIGN.md), by priority.

## Next

- [ ] **Green CI build of the branch** — debug APK as artifact.
- [ ] **Device test, Shizuku path** (D8) — install side by side, `list`,
      `schema`, a read `execute`, then one write function with confirmation.
- [ ] **Shizuku permission level** (D2 step 3) — consult the existing
      `OffloadPermissionManager` level for Shizuku before choosing the path.
- [ ] **Probe call for the direct path** (D2 step 2) — a granted permission
      alone must not select it; drop the result on code 1000.

## Then

- [ ] **Cache per package and `versionCode`** (D4) — list and schemas.
- [ ] **Schema-driven argument conversion** (D5) — types, nested objects and
      arrays from the target's schema on the direct path.
- [ ] **Discovery on the direct path** (D4) — `searchAppFunctions` from API 37;
      needs compileSdk 37.
- [ ] **Path and reason in diagnostics** (D2).
- [ ] **Settings texts in every shipped locale** — today `en` and `de`.
- [ ] **Device test, direct path** (D8) — on a device that grants
      `EXECUTE_APP_FUNCTIONS`.

## Later

- [ ] **Read functions as individual tools** (D3, later stage).
- [ ] **Shizuku binder path** instead of the shell — `ShizukuBinderWrapper` on
      the `app_function` service.
- [ ] **Exposure decision** (D3) — native tool or sandbox CLI
      `android-app-functions`.
- [ ] **`scope=activity` functions** — decide whether to expose them at all.
