# Natives Tool `app_functions` — Blaupause

Stand: Entwurf, Branch `feature/appfunctions-caller` auf Basis von Release 1.15.
Nichts davon ist gebaut; Gemessenes und Ungeprüftes sind getrennt benannt.

## Ziel

OpenMinis ruft die **AppFunctions anderer Apps** (Android-Plattform-API ab
Android 16, „Android MCP“) als **natives Tool** auf: Das Modell sieht die
Funktionen als Tool-Use, ohne Umweg über Bildschirm, Bedienungshilfe oder
Linux-Sandbox. Beispiele auf einem Testgerät: Alarme/Timer der Uhr-App,
Erinnerungen, Kalender, Kontakte, Gerätestatus der Play-Dienste.

Nicht Ziel: OpenMinis als **Anbieter** eigener AppFunctions (eigenes Thema,
braucht KSP und den AppFunctions-Compiler).

## Befund, der die Architektur bestimmt

| Weg | Voraussetzung | Stand auf dem Testgerät (Android 17, SDK 37) |
|---|---|---|
| **Direkt** (`android.app.appfunctions.AppFunctionManager`) | Berechtigung `EXECUTE_APP_FUNCTIONS` **und** Eintrag in der Agent-Allowlist **und** Nutzerfreigabe | **nicht erreichbar**: Berechtigung gemessen als `prot=internal|privileged|knownSigner` — eine nachinstallierte App bekommt sie nicht. Die Doku nennt `normal`; das gilt offenbar nicht auf jedem Gerät. |
| **Über Shizuku** (Shell, uid 2000) | Shizuku läuft | **geht**: Shell hält `EXECUTE_APP_FUNCTIONS_SYSTEM`; `cmd app_function list-app-functions` und `execute-app-function` liefern echte Daten (gemessen). Laut Plattform-Code braucht diese Berechtigung keine Allowlist-Prüfung. |

Folge: **Ein Kern, zwei Ausführungswege.** Der direkte Weg wird gebaut und
zur Laufzeit gemessen; trägt er nicht, nimmt der Kern den Shizuku-Weg. Das
Modell merkt davon nichts — das Tool ist dasselbe.

Weitere Messungen (Testgerät, nur gelesen):
- 109–114 registrierte Funktionen in 15 Paketen (Zahl schwankt mit App-Updates).
- Allowlist-Dienst vorhanden (`cmd allowlist`); Liste 2 (AppFunctions) per
  Shell bearbeitbar, zurzeit leer. Hilft nicht, solange die Berechtigung fehlt.
- `targetSdk` wird bei der Aufruferprüfung laut Plattform-Quelltext nicht
  ausgewertet — targetSdk 35 kann bleiben.

## Architektur

```
Modell ──tool_use "app_functions"──▶ ChatViewModel.executeTool
                                         │  (Schalter AgentToolSwitch.APP_FUNCTIONS,
                                         │   Bestätigung für Schreibfunktionen)
                                         ▼
                                AppFunctionsTool  (Schema, Argumente, Ausgabe)
                                         │
                                         ▼
                                AppFunctionsManager  (Kern, einmal vorhanden)
                                   ├── Katalog: Funktionen + Schemas (Cache)
                                   ├── Weg messen: direkt | shizuku | keiner
                                   ├── PlatformBackend  ── AppFunctionManager.executeAppFunction
                                   └── ShizukuBackend   ── cmd app_function … (Argument-Array)
```

### Einhängestellen in 1.15 (Pfade relativ zu `src/android/app/src/main/java/com/openminis/app/`)

1. **`tools/AgentTools.kt` — `makeAgentTools()` (Z. 53–70)**: neues Gate
   `appFunctions: Boolean`, Eintrag `AppFunctionsTool.definition`.
   Datenmodell `data/model/AgentToolDefinition.kt` bleibt unverändert; die
   Provider-Umwandlung (`toAnthropicJson`/`toGeminiJson`/`toOpenAIJson`) gilt
   automatisch.
2. **`ui/chat/ChatViewModel.kt` — Getter `agentTools` (Z. 2499–2523)** reicht
   das Gate durch; **`executeTool()` (Z. 14111–14269)** bekommt den Zweig
   `"app_functions" -> AppFunctionsTool.run(args, ctx)`, Muster wie
   `browser_use` (inkl. `toolDisabledResult`).
3. **`tools/AgentToolSwitch.kt`** + Einstellungsseite der Agent-Tools:
   Schalter `APP_FUNCTIONS`, Vorgabe **aus**.
4. **Neu: `appfunctions/`** (Paket) mit `AppFunctionsManager`,
   `PlatformBackend`, `ShizukuBackend`, `SchemaConverter`, `Katalog`.
   Shizuku-Zugang über die vorhandene `offload/ShizukuManager.kt` /
   `ShizukuBackend.kt` (dort nur `newProcess` — reicht für Stufe 1).
5. **`AndroidManifest.xml`**: `<uses-permission
   android:name="android.permission.EXECUTE_APP_FUNCTIONS"/>` — schadet nicht,
   wo sie nicht erteilt wird, und schaltet den direkten Weg frei, wo sie es wird.
6. **System-Prompt**: kein Handblock nötig; das Tool beschreibt sich selbst.

### Tool-Schema (ein Tool, drei Aktionen)

Dynamische Tool-Registrierung gibt es in OpenMinis nicht (Liste fest
codiert, Befund aus dem Code). Darum **ein** Tool mit `action`, statt je
App-Funktion ein eigenes Tool — das hält auch die Tool-Liste klein:

```json
{
  "name": "app_functions",
  "description": "Call functions that other installed Android apps expose (Android AppFunctions): alarms, timers, reminders, calendar, contacts … Use 'list' to discover, 'schema' for parameters, 'execute' to run. Write actions need user confirmation.",
  "parameters": {
    "action": {"type": "string", "enum": ["list", "schema", "execute"]},
    "package": {"type": "string", "description": "Target app package (schema/execute)"},
    "function": {"type": "string", "description": "Function id or unique short name"},
    "arguments": {"type": "object", "description": "Function parameters as JSON (execute)"},
    "query": {"type": "string", "description": "Optional filter for list (app, category, verb)"}
  },
  "required": ["action"]
}
```

Ausgaben (knapp, damit der Kontext nicht vollläuft):
- `list` → je Paket die Kurznamen + Kategorie + `read|write`.
- `schema` → Parameter mit Typ, Pflicht, Beschreibung (aufgelöste Referenzen).
- `execute` → Rückgabewert als JSON, Fehler mit Code (siehe unten).

Stufe 2 (später, optional): beim Turn-Start die **lesenden** Funktionen der
häufigsten Apps als eigene Tools anbieten (Muster: offizielles Beispiel
`android/appfunctions`, `agent/…/GeminiToolConverter.kt`, Apache 2.0).

### Katalog und Schemas

- **Liste**: Shizuku-Weg `cmd app_function list-app-functions` (JSON, gemessen).
  Direkter Weg: ab API 37 `searchAppFunctions`; auf API 36 über AppSearch
  `GlobalSearchSession` (Datenbank `apps-db`, Namespace `app_functions`) —
  **ungeprüft**, ob eine Dritt-App diese Dokumente lesen darf.
- **Schemas**: aus der APK der Ziel-App, `assets/*app_function*.xml`
  (gemessen: Google-Apps liefern volle Parameter, Samsung-Apps nur
  `schema_name`/`schema_category`). In Kotlin: `PackageManager` →
  `ApplicationInfo.sourceDir` → `ZipFile` — kein Shizuku nötig, APKs sind
  lesbar. Typcodes nach `androidx.appfunctions` (3 Objekt, 8 String,
  10 Array, 11 Referenz … — 3/8/11 auf dem Gerät gesehen).
- Wo kein Parameterschema vorliegt: Fehlermeldung der Funktion
  durchreichen (nennt das Pflichtfeld, gemessen:
  `Parameter [findAlarmsParams] cannot be null (code 1001)`).
- Cache je Paket mit `versionCode`, invalidiert bei Paket-Update.

### Ausführung

- **PlatformBackend**: `AppFunctionManager.executeAppFunction(
  ExecuteAppFunctionRequest.Builder(pkg, fid).setParameters(GenericDocument)…)`.
  JSON → `GenericDocument` nach Schema (Vorlage
  `ConvertInputToAppFunctionDataUseCase.kt` im Google-Beispiel).
  compileSdk 36 reicht für `executeAppFunction`; keine Jetpack-Bibliothek
  nötig (ab `androidx.appfunctions` alpha09 wären compileSdk 37 + AGP 9.1
  Pflicht).
- **ShizukuBackend**: `cmd app_function execute-app-function --package …
  --function … --parameters <json> --timeout-duration <s>` als
  **Argument-Array**, nicht über `sh -c` (Quoting; im bestehenden
  `ShizukuOffloadHandler` landen Optionen mit im `sh -c`-String, Befund).
  Parameter vorher als JSON validieren.
- **Weg messen** (einmal je Prozess, Ergebnis in Diagnose sichtbar):
  `checkSelfPermission(EXECUTE_APP_FUNCTIONS) == GRANTED` und Probeaufruf
  einer Lesefunktion → `direkt`; sonst Shizuku verbunden → `shizuku`;
  sonst `keiner` mit Grund. Nicht an Gerätenamen festmachen.

### Sicherheit

- Schalter **aus** per Vorgabe; Einstellungstext nennt beide Wege.
- **Lesen** (`find|get|search|list|is|query|read|lookup|fetch` am
  Kurznamen) ohne Rückfrage; **alles andere** (create/delete/update/set/
  start/show/draft …) nur nach Bestätigung im Chat — vorhandenes Muster:
  `ConfigConfirmationGate` (minis-config). Die Einordnung folgt dem Namen,
  nicht einem Beleg; das steht so in der Tool-Beschreibung.
- Shizuku-Weg nur, wenn der Nutzer Shizuku für OpenMinis freigegeben hat
  (bestehende `OffloadPermissionManager`-Stufen; heute Vorgabe NOT_ALLOWED).
- Keine Parameter in Logs auf Release-Builds (können Kontakte/Termine enthalten).

### Fehlercodes nach außen

| Code | Herkunft | Bedeutung |
|---|---|---|
| 1000–1003 | Plattform | Anfrage abgelehnt / ungültige Argumente (1001 = Pflichtfeld fehlt) |
| 15xx | Plattform | Element nicht gefunden u. ä. |
| 2000–2002 | Plattform | Systemfehler |
| 3000/3500/3501 | Ziel-App | App-Fehler |
| `NO_PATH` | OpenMinis | weder direkter Weg noch Shizuku |
| `NEEDS_CONFIRMATION` | OpenMinis | Schreibfunktion ohne Bestätigung |
| `EMPTY_RESULT` | OpenMinis | rc 0, aber leere Antwort — nicht als Erfolg werten |

## Teststufen

1. **Ohne Build (erledigt, Testgerät)**: Shizuku-Weg mit einem lokalen
   Skript — Liste = Rohzahl, Schemas aus APK, echte Lesezugriffe mit Daten,
   Schreibsperre, Fehlerpfade; Attrappentests mit Gegenprobe.
2. **Unit-Tests (JVM, ohne Gerät)**: `SchemaConverter` gegen die echten
   XML-Dateien (Google-Form, Samsung-Form), Verb-Einordnung, Argument-Array
   statt `sh -c`, JSON → `GenericDocument`.
3. **Gerät, Shizuku-Weg**: APK aus CI, Tool an, `list`/`schema`/`execute`
   (lesend), dann eine Schreibfunktion mit Bestätigung.
4. **Gerät, direkter Weg**: auf einem Gerät, auf dem
   `EXECUTE_APP_FUNCTIONS` `normal` ist (laut Doku ab Android 16 QPR2/17),
   plus Allowlist-Eintrag per adb für Entwicklertests:
   `cmd allowlist add-package-multimap 2 <pkg>:<sha256> '*'`,
   `cmd app_function flush-allowlist-changes`. **Ungeprüft**: ob der
   Eintrag einen Neustart überlebt; ob die Nutzerfreigabe
   (`createRequestAccessIntent`) auf Seriengeräten erscheint.

Bauen: Auf dem Entwicklungsgerät gibt es kein Android Studio / Gradle-Build.
Vorschlag: GitHub-Actions-Workflow im Fork (`assembleDebug`, APK als Artefakt).

## Offen / ungeprüft

- Wer in der `knownSigner`-Liste der Berechtigung steht (Gerät/Hersteller),
  ob andere Geräte `normal` melden.
- AppSearch-Lesbarkeit der Metadaten für Dritt-Apps (API 36).
- Binder-Weg über `ShizukuBinderWrapper` (statt Shell) — eleganter, aber
  in OpenMinis gibt es dafür noch keine Vorlage.
- Funktionen mit `scope=activity` (z. B. Nachrichtenentwurf) wirken nur bei
  passender App im Vordergrund.

## Quellen

- developer.android.com/ai/appfunctions; Referenz `Manifest.permission.EXECUTE_APP_FUNCTIONS`
  („allowlist checks … enforced at runtime“)
- github.com/android/appfunctions (Beispiel-Agent, Schema-Konverter)
- AOSP `frameworks/base/services/appfunctions/` (Tags 16.0.0_r1/_r4, 17.0.0_r1)
- Messungen am Testgerät, 25.10.2026 (nur lesend)
