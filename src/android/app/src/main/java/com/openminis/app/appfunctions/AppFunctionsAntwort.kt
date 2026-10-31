package com.openminis.app.appfunctions

/**
 * Die Antwort des Binder-Wegs lesen und Fehlercodes deuten.
 *
 * `Ausfuehren` druckt Zeilen: `ERFOLG: …`, `JSON: …`, `FEHLER: code=… kat=… msg=…`,
 * zuletzt `FERTIG` oder `ZEITUEBERSCHREITUNG`. Dieselben Zeilen wertet
 * `deps/afc` in `src/fehler.rs` aus; die Tabelle hier ist deren Übersetzung.
 *
 * Zwei Eigenheiten der Plattform, die hier ihren Namen bekommen:
 *  - **ERFOLG ohne Inhalt** ist kein Treffer. Gemessen liefert `findCallRecords`
 *    „ERFOLG" mit leerer Liste, obwohl nichts gefunden wurde. Das wird
 *    `EMPTY_RESULT` genannt, nicht Erfolg.
 *  - **1001 nennt das Feld nicht.** Die Meldung ist oft nur „null" oder
 *    „Invalid Argument"; deshalb werden die Argumente *vor* dem Aufruf gegen die
 *    Registry geprüft (siehe `AppFunctionsRegistry`).
 */
object AppFunctionsAntwort {

    enum class Art { ERFOLG, LEER, FEHLER, ZEITUEBERSCHREITUNG, UNKLAR }

    data class Antwort(
        val art: Art,
        val json: String?,
        val code: String?,
        val kategorie: String?,
        val meldung: String?,
        val rohzeilen: List<String>,
    ) {
        val istErfolg: Boolean get() = art == Art.ERFOLG

        /** Eine Zeile, die ein Mensch lesen kann — wie in `afc`. */
        fun bericht(): String = when (art) {
            Art.ERFOLG -> "ERFOLG: ${json ?: ""}"
            Art.LEER -> "EMPTY_RESULT: ERFOLG, aber kein Inhalt. Das heisst nicht " +
                "\u201Enichts da\u201C, sondern \u201Enicht nachgewiesen\u201C — " +
                "Parameter gegen die Registry prüfen."
            Art.ZEITUEBERSCHREITUNG -> "ZEITUEBERSCHREITUNG: die Ziel-App hat nicht geantwortet."
            Art.FEHLER -> buildString {
                append("FEHLER ").append(code ?: "?").append(": ").append(meldung.orEmpty())
                kategorie?.let { append(" (kat=").append(it).append(')') }
                erklaerung(code)?.let { append("\n  ").append(it) }
            }
            Art.UNKLAR -> "UNKLAR: die Antwort war nicht zu deuten:\n  " + rohzeilen.joinToString("\n  ")
        }
    }

    fun deute(roh: String): Antwort {
        var art = Art.UNKLAR
        var json: String? = null
        var code: String? = null
        var kategorie: String? = null
        var meldung: String? = null
        val zeilen = ArrayList<String>()

        for (zeile in roh.lineSequence()) {
            val z = zeile.trim()
            if (z.isEmpty()) continue
            zeilen.add(z)
            when {
                z.startsWith("JSON: ") -> {
                    json = z.removePrefix("JSON: ")
                    art = Art.ERFOLG
                }
                z.startsWith("FEHLER:") -> {
                    art = Art.FEHLER
                    for (teil in z.removePrefix("FEHLER:").trim().split(" ")) {
                        when {
                            teil.startsWith("code=") -> code = teil.removePrefix("code=")
                            teil.startsWith("kat=") -> kategorie = teil.removePrefix("kat=")
                        }
                    }
                    val i = z.indexOf("msg=")
                    if (i >= 0) meldung = z.substring(i + 4).trim()
                }
                z.startsWith("ZEITUEBERSCHREITUNG") -> art = Art.ZEITUEBERSCHREITUNG
                z.startsWith("ERFOLG:") -> {
                    val rest = z.removePrefix("ERFOLG:").trim()
                    // Wie `deps/afc/src/fehler.rs`: "ERFOLG: null" oder leer ist LEER,
                    // egal was vorher kam; sonst ERFOLG, wenn noch nichts feststeht.
                    if (rest == "null" || rest.isEmpty()) art = Art.LEER
                    else if (art == Art.UNKLAR) art = Art.ERFOLG
                }
            }
        }

        // ERFOLG mit leerer Rückgabeliste ist kein Treffer.
        if (art == Art.ERFOLG) {
            val t = (json ?: "").filterNot { it.isWhitespace() }
            if (t == "{}" || t == "[]" || t == "null" ||
                t.contains("\"androidAppfunctionsReturnValue\":[]")
            ) {
                art = Art.LEER
            }
        }
        return Antwort(art, json, code, kategorie, meldung, zeilen)
    }

    /**
     * Je Code, was er auf diesem Gerät geheissen hat (gemessen 29./30.10.).
     * Wo nur ein Verdacht vorliegt, steht das dabei.
     */
    val CODES: List<Pair<String, String>> = listOf(
        "1000" to "Aufrufer passt nicht: falsche uid, Paketname passt nicht zur uid, oder keine Berechtigung. Gemessen: Minis (uid 10278) bekommt 1000; uid 0 hat kein Paket; uid 2000 mit Paket com.android.shell geht.",
        "1001" to "Parameter falsch oder fehlt. Die Meldung ist oft nur 'null'. Gemessen: fehlendes maxCount oder -1 bei Fotos; leerer query bei findPersons.",
        "1002" to "Funktion ist deaktiviert (enabled=2 in den Laufzeit-Metadaten). Auch bei gesperrtem Gerät (\"Device is locked\"): erst entsperren, dann erneut versuchen.",
        "1003" to "Funktion nicht gefunden: falsche ID oder falsches Paket. Auch scope=activity-Funktionen antworten so.",
        "1500" to "Gemessen bei rejectCall ohne laufenden Anruf (vermutet, nicht belegt).",
        "2000" to "Systemfehler der Ziel-App.",
        "2001" to "Aufruf abgebrochen.",
        "2002" to "Unternehmensrichtlinie (enterprise policy).",
        "3000" to "Fehler in der Ziel-App selbst. Gemessen: getHistories ohne timeZone im DateTime.",
        "3501" to "Feature nicht verfügbar. Gemessen an eingeschalteten gms-Funktionen, die die Plattform trotzdem ablehnt. Auch bei gesperrtem Bildschirm (\"called while screen is locked\"): erst entsperren, dann erneut versuchen.",
        "NO_PATH" to "Kein Weg trägt (kein Binder zur Hand).",
        "NO_CONSTRUCTOR" to "Die Plattform dieses Geräts deklariert für ExecuteAppFunctionAidlRequest keinen Konstruktor, den die Zwischenschicht kennt (Request, UserHandle, String, danach nur long). Die Meldung nennt die deklarierten Signaturen; die Wahl in Ausfuehren muss erweitert werden. Gemessen: 4 Argumente auf BP2A, 5 auf BP4A.",
        "NO_SHIPPED_DEX" to "Das dex der Zwischenschicht liegt nicht auf dem Gerät.",
        "NEEDS_CONFIRMATION" to "Schreibende Funktion ohne Zustimmung des Nutzers.",
        "EMPTY_RESULT" to "ERFOLG, aber kein Inhalt — das Ergebnis ist unbekannt, nicht leer.",
    )

    fun erklaerung(code: String?): String? = CODES.firstOrNull { it.first == code }?.second
}
