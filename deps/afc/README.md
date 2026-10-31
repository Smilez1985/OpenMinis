# afc — AppFunctions aufrufen (Rust)

Kommandozeilenwerkzeug und Bibliothek für die AppFunctions des Geräts:
Registry, Prüfung **vor** dem Aufruf, Fehlerübersetzung, Binder-Weg. Gebaut aus
den Messungen vom 29./30.10.2026 auf einem Testgeraet (Android 16).

Dies ist die Vorstufe des Kotlin-Kerns aus
[`docs/appfunctions/DESIGN.md`](../../../docs/appfunctions/DESIGN.md): die
Regeln stehen hier zuerst in einer Form, die sich auf dem Gerät und in der CI
ohne Android-Stack prüfen lässt. Der Kotlin-Port übernimmt danach Daten und
Regeln unverändert.

## Ort der aus.jar

Reihenfolge: `--jar PFAD`, dann Umgebungsvariable `AFC_JAR`, dann
`/var/minis/shared/afc/aus.jar`. Der Pfad muss unter `/var/minis/` liegen,
weil das Geraet die Datei ueber den Datenordner der App findet.

## Warum Rust und Java getrennt

Der Binder wird nicht nachgebaut. `aus.jar` spricht
`IAppFunctionManager.executeAppFunction` und ist auf diesem Gerät gemessen
(13 Aufrufe im Erfolg); das Parcel-Format selbst nachzubauen wäre ungeprüft und
fragil. `afc` macht alles andere: die Argumente bauen, prüfen, den Aufruf
ansetzen, die Antwort deuten.

Aufrufer ist **uid 2000 mit Paket `com.android.shell`** über die Shizuku-Kette
(root → `su 2000`). Gemessen: uid 0 scheitert (kein Paket zur uid 0), die Minis-App
selbst scheitert mit 1000, weil `EXECUTE_APP_FUNCTIONS` `internal|privileged` ist.

## Aufrufe

```sh
cargo build --release          # TMPDIR=/tmp, RUSTFLAGS="-C target-feature=+crt-static"
target/release/afc list [muster] [--alles]
target/release/afc show findBookmarks
target/release/afc check com.sec.android.app.sbrowser findBookmarks --args '{"query":"a","maxCount":3}'
target/release/afc call  com.sec.android.app.sbrowser findBookmarks --args '{"query":"a","maxCount":3}'
target/release/afc codes
target/release/afc proben   # lesende Proben dieses Geraets (TSV: Paket, Name, Args, bestaetigt)
target/release/afc refresh [--dump DATEI] [--nach DATEI]   # Registry dieses Geraets aus dumpsys
```

`afc` nimmt `/var/minis/shared/afc/registry.txt`, wenn sie existiert (oder `--registry` / `$AFC_REGISTRY`), sonst die eingebettete Registry. `refresh` behaelt, was die eingebettete Registry ueber eine Funktion weiss (Felder, Beispiele), nimmt Status und Funktionsliste aus dem Dump und setzt `geprueft` zurueck.

* `--args` sind die Felder, wie ein Mensch sie schreiben würde. Ob sie auf der
  obersten Ebene liegen oder in einem Sammelparameter, entscheidet die Registry
  — nicht der Aufrufer.
* Schreibende Funktionen laufen **nur** mit `--schreiben`.
* `--trocken` zeigt das Skript, ohne es auszuführen.

## Prüfregeln (jede mit Kennung)

| Regel | Was | Warum |
|---|---|---|
| P0 | Funktion ohne Parameterangabe: es wird nichts geprüft, das steht im Ergebnis | „Ein Ergebnis ohne Prüfung ist kein Beweis" |
| P1 | Pflichtfeld fehlt (mit Name, Typ und Herkunft der Angabe) | gemessen: fehlendes `maxCount` → Fehler 1001 mit der Meldung „null" |
| P2 | unbekanntes Feld, Feld doppelt belegt | die Plattform verwirft unbekannte Felder **still** |
| P3 | falscher Typ | gemessen: `maxCount` als Text `"3"` → **leere Liste, kein Fehler** |
| P4 | `maxCount` über 30 → gekappt, `0` und `-1` gemeldet | gemessen: Obergrenze 30; `-1` nur bei Kontakten „alle" |
| P5 | leerer Suchtext bei `findPersons` | gemessen: leeres `query` → Fehler 1001 |
| P7 | ERFOLG ohne Inhalt ⇒ `EMPTY_RESULT` | gemessen: `findCallRecords` meldet ERFOLG mit leerer Liste |

Jede Regel hat einen Test; zu jeder Regel gibt es eine Gegenprobe (Regel
ausschalten → Test wird rot, gefahren am 30.10.).

## Fehlercodes

`afc codes` gibt die Tabelle aus. Sie stammt aus den Messungen, nicht aus der
Dokumentation; wo nur ein Verdacht vorliegt, steht das dabei (z. B. 1500).

## Gerätetest

```sh
sh werkzeuge/geraetetest.sh
```

Ruft die in der Registry bestätigten **lesenden** Funktionen auf und vergleicht
das Ergebnis mit der Messung vom 29./30.10. Letzter Lauf am 30.10.:
**10 Aufrufe, 8 mit Inhalt, 2 ohne Inhalt (so gemessen), 0 Fehler.**

`BrowserFunctions_findTabs` fällt auf: mit `maxCount` allein antwortet die
Ziel-App mit 1001 „Invalid Argument". Die Funktion ist in der Registry nicht
bestätigt und wird im Gerätetest nicht mehr geführt.

## Grenzen

* Die Registry ist ein Stand vom **29./30.10.2026 für ein Testgeraet**. Sie ist keine
  Geräteabfrage; `dumpsys app_function` und die Sonde liefern den frischen Stand
  (Werkzeuge liegen beim Maintainer der Messungen).
* Feldnamen **innerhalb** der Parameterobjekte sind nur dort bekannt, wo sie aus
  dem Schema der Ziel-APK stammen (`quelle=schema`). Bei `konvention` oder
  `vermutet` prüft `afc` den Typ, aber nicht die inneren Felder.
* Nur ein Gerät, nur ein Android-16-Stand: die Fehlercodes sind gemessen, aber
  nicht über mehrere Modelle belegt.
