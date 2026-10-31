# aus.jar — Quelle der Zwischenschicht

Die kleine Dex-Datei, die auf dem Geraet `IAppFunctionManager.executeAppFunction`
ueber Reflection aufruft (als uid 2000, Paket `com.android.shell`, gestartet mit
`app_process`). `afc` und der Kotlin-Binder rufen dieselbe Datei auf; die App
liefert sie als Asset (`assets/appfunctions/aus.jar`) aus.

- `src/Ausfuehren.java` — der Aufruf. Waehlt den Konstruktor von
  `ExecuteAppFunctionAidlRequest` nach Typen (Request, UserHandle, String, danach
  nur `long`), weil er je Build verschieden ist: 4 Argumente auf BP2A, 5 auf BP4A.
  Passt keiner: `FEHLER: code=NO_CONSTRUCTOR …` mit den deklarierten Signaturen.
- `src/Konstruktoren.java` — Sonde, druckt Konstruktoren und Methoden der
  beteiligten Klassen (nur lesend). Nicht in der Jar.
- `bauen.sh` — baut `aus.jar` und gibt die Pruefsumme aus. Der Bau aus dieser
  Quelle ist bitgleich mit dem Asset der App (sha256 `3e6f7b74…85a3`, gemessen
  31.10.); wer die Quelle aendert, ersetzt das Asset.

Die zwei `long`-Werte des 5-Argument-Konstruktors sind **angenommen** (erster
`elapsedRealtime`, zweiter Wanduhr, nur aus dem Methodennamen geschlossen).
Welche Staende wo gemessen wurden: [COMPATIBILITY.md](../../../docs/appfunctions/COMPATIBILITY.md).
