# Glossary — old names in historical logs

The measurement logs ([TEST-LOG.md](TEST-LOG.md) to [TEST6-LOG.md](TEST6-LOG.md))
and the raw transcripts in [messungen/token_test_roh/](messungen/token_test_roh/)
record commands and output as they were typed and printed at the time. The
project has since been renamed to English throughout. This table maps the old
names to the current ones. Measurements and their meaning are unchanged; only
the words changed.

## Protocol words (shim output, `afc` result text)

| Old | Current |
|---|---|
| `ERFOLG` | `SUCCESS` |
| `FEHLER` | `ERROR` |
| `FERTIG` | `DONE` |
| `ZEITUEBERSCHREITUNG` | `TIMEOUT` |
| `UNKLAR` | `UNCLEAR` |
| `kat=` | `cat=` |
| `ursache=` | `cause=` |
| `_typ` (document key) | `_type` |
| `_fehler` (document key) | `_error` |
| `<N Byte>` (byte array in JSON) | `<N bytes>` |
| `AF_TAUSCH` (environment variable of `Toggle`) | `AF_SWAP` |

Unchanged: `JSON:`, `EMPTY_RESULT`, `NEEDS_CONFIRMATION`, `NO_CONSTRUCTOR`,
`NO_PATH`, `NO_AFC`, `NO_SHIPPED_DEX`, `TOO_LONG`, and the numeric error codes.

## The shim (the small dex that calls the platform binder)

| Old | Current |
|---|---|
| `deps/afc/aus/` | `deps/shim/` |
| `aus.jar` | `shim.jar` |
| `att/` (stub sources) | `stubs/` |
| `bauen.sh` | `build.sh` |
| class `Ausfuehren` (`app_process / Ausfuehren`) | `Execute` |
| `Schalter`, `SchalterCallback` | `Toggle`, `ToggleCallback` |
| `Konstruktoren` | `Constructors` |
| `Callback` | `Callback` (unchanged) |

## `afc` command line

| Old | Current |
|---|---|
| `proben` | `probes` |
| `--hilfe` | `--help` |
| `--alles` | `--all` |
| `--schreiben` | `--write` |
| `--trocken` | `--dry-run` |
| `--zeit` | `--timeout` |
| `--nach` | `--out` |

Unchanged: `list`, `show`, `check`, `call`, `refresh`, `codes`, `help`, `--json`,
`--args`, `--jar`, `--registry`, `--dump`, `$AFC_JAR`, `$AFC_REGISTRY`.

## Registry format

| Old | Current |
|---|---|
| `an` / `aus` / `standard-an` / `standard-aus` | `on` / `off` / `default-on` / `default-off` |
| `ja` / `nein` | `yes` / `no` |
| `oberste-ebene` | `top-level` |
| `konvention` / `vermutet` / `aufruf-ok` / `fehlermeldung` | `convention` / `assumed` / `call-ok` / `error-message` |
| `geprueft` | `verified` |
| record kind `E geraet` | `E device` |

Unchanged: record kinds `F`, `P`, `D`, `B`, `E`, and the origin words `dump` and `schema`.

## Labels in the `afc` output

| Old | Current |
|---|---|
| `Dokument:` | `Document:` |
| `Aufruf:` | `Call:` |

## Rust (`deps/afc`)

| Old | Current |
|---|---|
| `auffrischen.rs` | `refresh.rs` |
| `fehler.rs` | `errors.rs` |
| `pruefen.rs` | `validate.rs` |
| `proben.rs` | `probes.rs` |
| `weg.rs` | `route.rs` |
| `deute` | `interpret` |
| `ausfuehren` | `execute` |
| `anwenden` | `apply` |
| `finde` | `find` |
| `frische` | `refresh` |
| `pruefe` | `check` |
| `Antwort` | `Reply` |
| `Aufruf` | `Call` |
| `Schwere` | `Severity` |
| `Identitaet` | `Identity` |
| `Wert` (`json.rs`) | `Value` |
| `jar_lokal`, `jar_waehlen` | `jar_local`, `jar_choose` |
| `identitaet_von`, `startzeile` | `identity_of`, `launch_line` |
| `BEFEHL_GRENZE` | `COMMAND_LIMIT` |
| `werkzeuge/` | `tools/` |
| `registry_zu_daten.py` | `registry_to_data.py` |
| `geraetetest.sh`, `test_geraetetest.sh` | `devicetest.sh`, `test_devicetest.sh` |

## Kotlin (app)

| Old | Current |
|---|---|
| `AppFunctionsAntwort` | `AppFunctionsReply` |
| `AfcProzess` | `AfcProcess` |
| `Base64Kodierer` | `Base64Encoder` |
| `AppFunctionsParitaetTest` | `AppFunctionsParityTest` |
| `deute` | `interpret` |
| `ausfuehren` | `run` |
| `pruefen` | `check` |
| `ueberBinder`, `ueberAfc` | `viaBinder`, `viaAfc` |
| `jarBereitstellen`, `dexBereitstellen` | `ensureShim`, `ensureShimOnDevice` |
| `ablageBefehle`, `fertigBefehl`, `zuLang` | `stageCommands`, `finishCommand`, `tooLong` |
| `BEFEHL_STUECK`, `BEFEHL_GRENZE` | `CHUNK_SIZE`, `COMMAND_LIMIT` |
| `Ergebnis` | `Result` / `Outcome` |
| `Lauf` | `Run` |
| `Befund` | `Finding` |
| `Pruefung` | `Check` |

## Files

| Old | Current |
|---|---|
| `messungen/token_test.json` keys `ergebnisse`, `aufgabe`, `weg`, `aufrufe`, `befehl_bytes`, `ausgabe_bytes`, `tok`, `sek`, `ergebnis`, `anleitung_bytes`, `hilfe_bytes` | `results`, `task`, `path`, `calls`, `command_bytes`, `output_bytes`, `tokens`, `seconds`, `outcome`, `instructions_bytes`, `help_bytes` |
| `catalogue/*.json` keys such as `datum`, `funktionen`, `pakete`, `funktion`, `parameter`, `pflicht`, `typ`, `quelle` | `date`, `functions`, `packages`, `function`, `parameters`, `required`, `type`, `source` |

## Not renamed on purpose

The plain skill `appfunktionen` (a separate tool outside this repository) keeps
its own subcommands `liste`, `rufen` and `schema`; the transcripts in
`messungen/token_test_roh/` show them. Its message `list-app-functions lieferte
nichts` means "list-app-functions returned nothing".
