# shim.jar — source of the intermediate layer

The small dex file that calls `IAppFunctionManager.executeAppFunction` on the device
via reflection (as uid 2000, package `com.android.shell`, started with
`app_process`). `afc` and the Kotlin binder call the same file; the app
ships it as an asset (`assets/appfunctions/shim.jar`).

- `src/Execute.java` — the call. Selects the constructor of
  `ExecuteAppFunctionAidlRequest` by types (Request, UserHandle, String, then
  only `long`), because it differs per build: 4 arguments on BP2A, 5 on BP4A.
  If none matches: `ERROR: code=NO_CONSTRUCTOR …` with the declared signatures.
- `src/Toggle.java`, `src/ToggleCallback.java` — enables or disables a function
  (`setAppFunctionEnabled`). Setting `AF_SWAP` in the environment swaps the
  order of the function-id and caller arguments.
- `src/Constructors.java` — probe, prints constructors and methods of the
  classes involved (read-only). Not part of the jar.
- `build.sh` — builds `shim.jar` and prints the checksum. After a rebuild from
  changed source, replace the app asset with the new jar.

The two `long` values of the 5-argument constructor are **assumed** (the first
`elapsedRealtime`, the second wall clock, inferred only from the method name).
Which builds were measured where: [COMPATIBILITY.md](../../../docs/appfunctions/COMPATIBILITY.md).
