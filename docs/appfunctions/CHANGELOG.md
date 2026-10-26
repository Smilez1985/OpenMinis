# Changelog — native tool `app_functions`

All notable changes to this feature branch. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); the branch is based
on Minis release 1.15 and has no release of its own.

## [Unreleased]

### Added

- Native agent tool `app_functions` to list, inspect and execute the AppFunctions of other installed apps (Android 16+).
- Execution through the platform API when Minis holds `EXECUTE_APP_FUNCTIONS`, otherwise through Shizuku.
- Parameter schemas read directly from the target app's package, in both the full and the minimal form.
- Short function names are accepted when unique within the app.
- Setting "Confirm actions that change something" (off by default): when on, functions that change something run only after the user approves them in the existing confirmation dialog.
- An empty result is reported as unknown instead of as success.
- Settings switch "App Functions" under Agent Tools, off by default, with English and German texts.
- Manifest declares `EXECUTE_APP_FUNCTIONS` and package visibility for apps that expose AppFunctions.
- CI workflow on the fork that builds a debug APK installable next to an existing Minis.
- The CI build skips pushes that only change documentation.
- Optional `applicationIdSuffix` for debug builds (`-PminisIdSuffix`).
- Design notes, roadmap and changelog for the feature.
- `docs/appfunctions/TEST-LOG.md`: field notes from the first on-device test, with timestamps, findings, the official discovery API and the plan that replaces guessing with a published contract.
