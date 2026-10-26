# test2-log — AppFunctions am Gerät, zweite Runde (Test-App)

Prüfling: `com.openminis.app.appfn`, versionName 1.15-beta.6, installiert
26.10.2026 08:39. **Hash der installierten `base.apk` =
`ada12f8bbf4f3dffb166bf6d8340cfd884e495220ec714c908ed2418db3caf77`** — gleich
dem Artefakt des CI-Laufs `40255763109` (Branch `feature/appfunctions-caller`,
Spitze `b2cb2ab`). Es wird also wirklich der geprüfte Build getestet.

Gerät: das Testgerät, Android 17 (SDK 37), Samsung. Aufrufweg während der ganzen Runde:
**Shizuku** (uid 2000/shell).

Schalter laut `shared_prefs/agent_settings.xml`:
`agent.tools.appfunctions.enabled = true`,
`agent.tools.appfunctions.confirmWrites` = `false`, später `true` (siehe 3.6).

---

## 1. Was funktioniert (gemessen)

| # | Prüfung | Ergebnis |
|---|---|---|
| 1.1 | `list` zählt und filtert | 109 Funktionen gesamt; `google` → 34 in 5 Apps, `clock` → 18, `messaging` → 1, `reminder` → 11. Der benutzte Weg steht in der Ausgabe: `(path: shizuku)` |
| 1.2 | Deklarierte Parameter lesbar | `draftMessage(parameters: DraftMessageParameters)`, `updatePreviewImage(imageUri: string)`, `ringDevice(deviceId: string, makeItRing: boolean)`. Ohne Deklaration: `(? unpublished)` |
| 1.3 | `schema` löst verschachtelte Typen auf | Messages: `parameters → DraftMessageParameters.text` (string, required) samt Beschreibung, dazu `returns: DraftedMessage`. Früher fehlten diese Angaben ganz — der Zweck des Commits `b2cb2ab` ist damit am Gerät belegt |
| 1.4 | Prüfung **vor** der Ausführung | `{"parameters":{"bogus":"x"}}` → `INVALID_ARGUMENTS … unknown field 'parameters.bogus' (allowed: text); missing required field 'parameters.text' (string). Nothing was executed.` Kein Aufruf an die Ziel-App |
| 1.5 | Die Prüfung ist zugleich Auskunft | `setDeviceStateItem` mit falschem Feld nennt die erlaubten: `allowed: key, itemizationKeys, value, requestInitiatedWhileUnlocked`. Brauchbar, weil das `schema` der Play-Dienste im Chat abgeschnitten ankommt |
| 1.6 | Lesen mit Ergebnis | gms `getDeviceStateMetadata({})` → vollständige Gerätezustand-Metadaten. `getDeviceStateItem(key=call_casting)` → `jsonValue "true"` |
| 1.7 | Schreiben mit Ergebnis | gms `setDeviceStateItem(key=call_casting, value="true")` → `isSuccessful true`, `currentValue "true"`. Bewusst der Ist-Wert zurückgeschrieben, damit der Gerätezustand unverändert bleibt |
| 1.8 | Fehlendes Schema blockiert nicht | Samsung-Uhr `createTimer` (keine Deklaration): der Aufruf geht durch, die **Ziel-App** antwortet `Parameter [createTimerParams] cannot be null (code 1001)`. Die Prüfung erfindet nichts und versperrt nichts |
| 1.9 | Pflichtfelder ohne Schema erfragbar | Uhr `createTimer` mit `{}` → `createTimerParams` fehlt; mit leerem Behälter → `Input time is not within permissible limits.` Die App nennt also den obersten Behälter, nicht die Felder darin |

## 2. Grenzen

### 2.1 `scope: activity` ist über Shizuku nicht erreichbar
Messages `draftMessage` deklariert `scope: activity` und endet mit
`SCOPE_ACTIVITY functions must be targeted with an AppFunctionActivityId (code 1003)`.
Der Aufruf besitzt keine Activity und kann die Kennung nicht liefern. Der
geplante Messages-Test ist über diesen Weg **nicht machbar**; das ist keine
Fehlfunktion des Werkzeugs, sondern die Grenze des Aufrufers.

### 2.2 Samsung veröffentlicht keine Parameter
`assets/app_function.xml` der Uhr (9 392 B) enthält je Funktion nur
`function_id`, `schema_category`, `schema_name`, `schema_version`,
`enabled_by_default`, `restrict_callers_with_execute_app_functions`,
`display_name_string_res` — **keine Parameter**. Die Feldnamen sind aus dem
Paket nicht zu gewinnen. Der Katalog muss aus dem Verhalten/aus dem Werkzeug der
Ziel-App gebaut werden.

## 3. Direktweg: warum er auf diesem Gerät nicht geht

### 3.1 Die App hält die Berechtigung nicht
`dumpsys package com.openminis.app.appfn`: `EXECUTE_APP_FUNCTIONS` steht unter
`requested permissions`, **nicht** unter `install permissions` oder
`runtime permissions`. Kein Eintrag `granted=true`.

### 3.2 Sie ist nicht vergebbar
`pm grant com.openminis.app.appfn android.permission.EXECUTE_APP_FUNCTIONS` →
`SecurityException: … is not a changeable permission type` (rc 255). Shell-Rechte
genügen nicht. Die frühere Annahme „einmal mit Shizuku eintragen, danach läuft es
ohne" ist damit **widerlegt**.

### 3.3 Sie ist privilegiert
Einzige Fundstelle im System:
`/system/etc/permissions/privapp-permissions-platform.xml`. Also nur für Apps aus
dem Systemabbild. Die öffentliche Referenz nennt zwar Schutzstufe `normal`, AOSP
schaltet die Fassung aber per Feature-Flag
(`android.permission.flags.app_function_access_api_enabled`) um; ohne Flag gilt
`internal|privileged`. Auf diesem Gerät gilt die privilegierte Fassung.

### 3.4 Allowlist-Eintrag allein ändert nichts
`cmd allowlist add-packages 2 com.openminis.app.appfn` gesetzt
(`certificateDigest=null`), ebenso für ID 1, danach
`cmd app_function purge-allowlist-cache`. Ergebnis: der Aufruf meldet weiter
`(path: shizuku)`. **Nicht getrennt:** ob der Eintrag wirkungslos ist oder der
App-interne Probe-Zustand zwischengespeichert wird — ohne App-Neustart nicht
unterscheidbar.

AOSP sagt dazu: beide Bedingungen müssen zusammenkommen — Berechtigung **und**
Allowlist (`CallerValidatorImpl`, Branch `android17-release`). Die zweite Hälfte
allein kann nichts bewirken. `ALLOWLIST_ID_APP_FUNCTION = 2` ist die richtige
Liste; der Eintrag in ID 2 ist deshalb **geblieben**, ID 1 (Bedeutung unbekannt,
nur ein Versuch) wurde entfernt:
`cmd allowlist clear-shell-allowlist 1`.

### 3.5 Kein offizieller Weg für Drittanbieter
Googles FAQ (developer.android.com/ai/appfunctions#faqs, Stand 22.10.2026):
„only a limited number of apps and system agents can access the entire
pipeline"; Zugang über ein Early-Access-Programm, wobei die Anmeldung
„does NOT automatically obtain access". Die Paketübersicht: Funktionen sind
„made available for execution by trusted, system-privileged applications".

Zusatzhebel aus neueren Android-Zweigen (`set-additional-allowlisted-agents`,
`list-valid-agents`) fehlen auf diesem Build; `cmd app_function` kennt nur
`help`, `list-app-functions`, `execute-app-function`, `set-enabled`, `is-enabled`,
`purge-allowlist-cache`.

### 3.6 Bestätigungsschranke: geprüft, funktioniert — mit einer Lücke

Schalter `confirmWrites=true`. Ablauf vollständig durchgespielt:

| Fall | Ergebnis |
|---|---|
| Schreibaufruf mit Schranke an | Eine Bestätigungsanfrage erreichte den Nutzer (von ihm bestätigt) |
| **Bestätigt** | `isSuccessful true`, `currentValue "true"` — der Aufruf lief durch |
| **Abgelehnt** | `NEEDS_CONFIRMATION: the user did not approve setDeviceStateItem (verb 'set' changes something). Do not retry without asking.` |
| Gegenprobe zur Ablehnung | Schreibversuch auf den **entgegengesetzten** Wert (`call_casting=false`), abgelehnt, danach per Rücklesen geprüft: `jsonValue "true"` — **nichts geschrieben** |

**Lücke:** Im *bestätigten* Fall sagt das Werkzeugergebnis nichts davon, dass eine
Bestätigung nötig war und dass sie erteilt wurde — es sieht aus wie ein Aufruf
ganz ohne Schranke. Der abgelehnte Fall ist dagegen eindeutig. Für den
Nachweis „wer hat wann was freigegeben" fehlt im Ergebnis ein Vermerk
(naheliegend: „vom Nutzer bestätigt" als Zusatzzeile).

## 4. System-App auf einem Root-Gerät (Ausblick)

Auf einem unveränderten Gerät ist der Direktweg unerreichbar: Shizuku hat keine
Root-Rechte, `/system` liegt als `erofs` schreibgeschützt, und privilegierte
Apps müssen im Systemabbild liegen. Ein System-App-Status lässt sich nicht per
Installer vergeben; er verlangt APK unter `/system/priv-app/…` **und** einen
Eintrag in einer `privapp-permissions-*.xml`.

Der Aufrufweg ist in der App bereits bevorzugt direkt, Shizuku nur Rückfall
(so entschieden am 26.10.) — in der App ist dafür nichts zu bauen, es fehlt allein die
privilegierte Auslieferung. zwei weitere Geräte laufen mit eigenem Systemabbild auf
Android 16 und haben Root; damit ist der Aufbau zeigbar.

Regel für solche Eingriffe (so entschieden am 26.10.): **jeder Root-Eingriff braucht einen
Rettungsweg und muss sich im schlimmsten Fall selbst zurücknehmen — belegt durch
eine Gegenprobe.** Plan und Rücknahme:
`vorhaben/direktweg-system-app.md`.

## 5. Offene Punkte

- [ ] Ergebnisvermerk im bestätigten Fall ergänzen (3.6) — der einzige
      gemessene Fehler dieser Runde.
- [ ] Erfolgreicher Schreibvorgang mit sichtbarer Wirkung (Uhr-Timer) — braucht
      den Katalog für die Samsung-Feldnamen.
- [ ] `scope=activity`: Entscheidung, ob ausgelassen oder über einen Aufrufer mit
      Activity gelöst (2.1).
- [ ] Direktweg auf einem Gerät mit privilegierter Installation zeigen (4.).
