# test3-log — AppFunctions am Gerät, dritte Runde (Schranke, Fix `8a0922d`)

Prüfling: `com.openminis.app.appfn`, installiert als Parallelinstallation neben
der signierten Fassung. Artefakt des CI-Laufs `40306973616` (Branch
`feature/appfunctions-caller`, Spitze `8a0922d`),
sha256 `e05f5bfe52b303966955b51acecd33f68b5889df7f213ae590b6e8320789f7d4`.

Gerät: das Testgerät, Android 17 (SDK 37), Samsung. Aufrufweg: **Shizuku**
(uid 2000/shell) — der Direktweg ist auf diesem Gerät nicht möglich (2.1–2.2 der
zweiten Runde).

Schalter: Werkzeug `App-Funktionen` **an**, `Ändernde Aktionen bestätigen` **an**
(Vorgabe aus, hier bewusst eingeschaltet — Ziel dieser Runde).

**Zweck der Runde:** die einzige in Runde 2 gemessene Lücke schließen und
nachmessen — ein *bestätigter* Schreibaufruf sah im Ergebnis aus wie einer ohne
Schranke. Der Fix (`successNotes`) sollte das ändern.

---

## 1. Was gemessen wurde

| # | Prüfung | Ergebnis |
|---|---|---|
| 1.1 | `list` zählt und filtert | 109 Funktionen in 15 Apps; `com` → alle, `clock` → 18, `reminder` → 11, `calendar` → 6, `messaging` → 1. Der Weg steht in der Ausgabe: `(path: shizuku)` |
| 1.2 | `schema` löst verschachtelte Typen auf | Galerie `createAlbum(createAlbumParams: CreateAlbumParams)` → `CreateAlbumParams.title` (string, required, „The title of the album."), Rücksprung `Album`. Bei Samsung-Apps dagegen `(? unpublished)` |
| 1.3 | Ohne Deklaration gibt es nichts zu prüfen | Erinnerungen `TaskFunctions_createTask` mit `{}`: die App deklariert keine Parameter, also schlägt die Prüfung nicht an — der Aufruf erreicht **die Schranke**. Sie ist dort die einzige Wache, nicht die Feldprüfung |
| 1.4 | **Die Schranke hält** | Derselbe Aufruf, nicht bestätigt → `NEEDS_CONFIRMATION: the user did not approve TaskFunctions_createTask (verb 'create' changes something). Do not retry without asking.` Kein Aufruf an die Ziel-App |
| 1.5 | **Der Fix ist am Gerät wirksam** | Galerie `createAlbum {createAlbumParams:{title:"APK-Test 8a0922d"}}`, **bestätigt** → echter Rückgabewert (`Album` mit `id 1268752007`, `dateCreated` 2026-10-26 15:07:30) **und** die neue Zeile: `(note: this call changes state and the user confirmed it before it ran)` |
| 1.6 | Gegenprobe: Lesen trägt die Zeile nicht | `findAlbums {query:"APK-Test", maxCount:5}` → dasselbe Album, **ohne** Hinweiszeile. Der Zusatz hängt also an der Bestätigung, nicht am Aufrufweg |
| 1.7 | Was geschrieben wurde, ist da | Das zurückgelesene Album trägt dieselbe `id 1268752007` und denselben Titel — der Aufruf hat wirklich etwas angelegt |

Nebenbefund (kein Fehler des Werkzeugs): `createAlbum` liefert `dateCreated`
korrekt, `findAlbums` für dasselbe Album dagegen `1970-01-21 19:03:43` — die
Galerie gibt beim Suchen einen Ersatzwert aus. Wer aus der Suche ein Datum liest,
liest Unsinn; aus dem Anlegen ist es brauchbar.

## 2. Offen

- **Kein Löschweg für Alben.** Die Galerie bietet `createAlbum`, `findAlbums`,
  `updateMediaItem` — aber kein `deleteAlbum`. Das Testalbum muss von Hand in
  der Galerie entfernt werden. Für einen Schreibtest ist das die unangenehme
  Seite: Schreiben ohne Rücknahme.
- **Katalog für Samsung-Feldnamen** bleibt die Voraussetzung für die
  Schreibteste bei Uhr, Erinnerungen und Kalender (Runde 2, 2.2). Neu belegt:
  Hier greift wenigstens die Schranke (1.3).
- Der Schalter `Ändernde Aktionen bestätigen` gehört für solche Teste **an**;
  die Vorgabe bleibt aus.
