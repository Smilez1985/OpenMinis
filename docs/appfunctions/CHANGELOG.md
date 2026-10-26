# Changelog — native tool `app_functions`

All notable changes to this feature branch. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); the branch is based
on Minis release 1.15 and has no release of its own.

## [Unreleased]

### Fixed

- A write call the user approved says so in its result; previously it looked exactly like a call with the confirmation switch off, so nothing in a transcript showed that a confirmation had been given.
- Reading an app's schema file no longer fails on Android (unsupported XML parser feature); DOCTYPE declarations are still refused.

### Added

- Native agent tool `app_functions` to list, inspect and execute the AppFunctions of other installed apps (Android 16+).
- Execution through the platform API when Minis holds `EXECUTE_APP_FUNCTIONS`, otherwise through Shizuku.
- Parameter schemas read directly from the target app's package, in both the full and the minimal form.
- Short function names are accepted when unique within the app.
- Setting "Confirm actions that change something" (off by default): when on, functions that change something run only after the user approves them in the existing confirmation dialog.
- An empty result is reported as unknown instead of as success.
- `list` and `schema` show the parameters, descriptions and data types each app declares in the system's function registry.
- `execute` refuses arguments with unknown or missing required fields before anything runs or is shown for confirmation.
- Results of functions without declared parameters carry a note to read the outcome back.
- Settings switch "App Functions" under Agent Tools, off by default, with English and German texts.
- Manifest declares `EXECUTE_APP_FUNCTIONS` and package visibility for apps that expose AppFunctions.
- CI workflow on the fork that builds a debug APK installable next to an existing Minis.
- The CI build skips pushes that only change documentation.
- Optional `applicationIdSuffix` for debug builds (`-PminisIdSuffix`).
- Design notes, roadmap and changelog for the feature.
- Measured note on where the parameters of Samsung apps come from, with a dated catalogue of the parameter names and types that are reachable on the device ([SAMSUNG-PARAMETERS.md](SAMSUNG-PARAMETERS.md)).
- Field names of the predefined schema classes, recovered from a second source and checked against the installed copy by field count and type sequence (141 of 142 classes agree).
- `docs/appfunctions/TEST-LOG.md`: field notes from the first on-device test, with timestamps, findings, the official discovery API and the plan that replaces guessing with a published contract.
- `docs/appfunctions/TEST2-LOG.md`: field notes from the second round on the device (`b2cb2ab`) — what the parameter reading and the validation do in reality, the limits of the Shizuku path (`scope: activity`, the privileged permission), why the direct path is out of reach on a stock device, and the finding that closed the confirmation gap.
