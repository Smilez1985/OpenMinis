package com.openminis.app.appfunctions

import android.content.Context
import org.json.JSONObject
import java.util.Locale

/**
 * Die gemessene Registry — als Teil der App.
 *
 * Auf diesem Gerät gibt es kein `cmd app_function`: die Auflistung und die
 * Parameterangaben, die die Plattform sonst liefert, fehlen. Genau die bringt
 * diese Registry mit: welche Apps welche Funktionen anbieten, welcher
 * Parameter sie erwarten, welche Felder darin Pflicht sind und woher jede
 * Angabe stammt (`dump`, `schema`, `aufruf-ok`, `fehlermeldung`, `konvention`,
 * `vermutet`).
 *
 * Die Regeln P0–P7 prüfen die Argumente **vor** dem Aufruf. Grund: die
 * Plattform ist nachsichtig — unbekannte Felder werden still verworfen,
 * fehlende Pflichtfelder durch Vorgaben ersetzt, und eine Zahl als Text (`"3"`)
 * liefert eine leere Liste ohne Fehler. Ein geratener Aufruf sieht danach aus
 * wie ein Treffer ohne Ergebnis; das soll er nicht.
 *
 * Quelle der Daten ist `assets/appfunctions/registry.txt`, erzeugt aus
 * `shared/appfunktionen-bau/appfunctions-registry.xml`. Dieselben Daten und
 * dieselben Regeln stehen in `deps/afc` (Rust) mit Tests und Gerätetest.
 */
object AppFunctionsRegistry {

    const val ASSET = "appfunctions/registry.txt"

    /** Obergrenze der Ziel-Apps: 50, 500, 100000 lieferten höchstens 30 Treffer (gemessen 29.10.). */
    const val MAXCOUNT_OBERGRENZE = 30L

    data class Feld(val name: String, val datentyp: String, val pflicht: Boolean, val quelle: String)

    data class Parameter(
        val name: String,
        val typ: String,
        val ebene: String,
        val quelle: String,
        val felder: List<Feld> = emptyList(),
    )

    data class Funktion(
        val paket: String,
        val id: String,
        val name: String,
        val kategorie: String,
        val status: String,
        val geprueft: Boolean,
        val parameter: List<Parameter> = emptyList(),
        val beispiel: String? = null,
    ) {
        val kurzname: String get() = id.substringAfterLast('#')

        /** Der Sammelparameter: sein Name steht als `-a3-`, seine Felder als Nutzlast. */
        val composite: Parameter?
            get() = parameter.firstOrNull {
                it.ebene != "oberste-ebene" && !it.typ.contains("text") && it.typ.contains('.')
            }

        val flach: List<Parameter>
            get() = parameter.filter { it.ebene == "oberste-ebene" || it.typ == "text" || it.typ == "liste-von-text" }
    }

    data class Befund(val regel: String, val fehler: Boolean, val text: String)

    /**
     * Ergebnis der Prüfung. `a3`/`a4` sind die beiden Argumente, die
     * `Ausfuehren` über den Binder erwartet: Parametername (oder `@`) und
     * Nutzlast.
     */
    data class Pruefung(
        val befunde: List<Befund>,
        val a3: String,
        val a4: String,
        val quelle: String,
    ) {
        val hatFehler: Boolean get() = befunde.any { it.fehler }
        fun bericht(): String = befunde.joinToString("\n") {
            (if (it.fehler) "FEHLER " else "Hinweis ") + it.regel + ": " + it.text
        }
    }

    // ------------------------------------------------------------------ laden

    /** Liest die mitgelieferte Registry. Einmal je Prozess aufrufen und behalten. */
    fun laden(context: Context): List<Funktion> {
        val text = context.assets.open(ASSET).use { it.readBytes().toString(Charsets.UTF_8) }
        val funktionen = ArrayList<Funktion>()
        for (zeile in text.lineSequence()) {
            if (zeile.isBlank() || zeile.startsWith("#")) continue
            val t = zeile.split('\t')
            when (t.getOrNull(0)) {
                "F" -> funktionen += Funktion(
                    paket = t.getOrElse(1) { "" },
                    id = t.getOrElse(2) { "" },
                    name = t.getOrElse(3) { "" },
                    kategorie = t.getOrElse(4) { "" },
                    status = t.getOrElse(5) { "" },
                    geprueft = t.getOrElse(6) { "" } == "ja",
                )
                "P" -> funktionen.lastOrNull()?.let { f ->
                    val p = Parameter(
                        name = t.getOrElse(2) { "" },
                        typ = t.getOrElse(3) { "" },
                        ebene = t.getOrElse(4) { "" },
                        quelle = t.getOrElse(5) { "" },
                    )
                    ersetze(funktionen, f.copy(parameter = f.parameter + p))
                }
                "D" -> funktionen.lastOrNull()?.let { f ->
                    val pname = t.getOrElse(2) { "" }
                    val neue = f.parameter.map { p ->
                        if (p.name == pname) p.copy(
                            felder = p.felder + Feld(
                                name = t.getOrElse(3) { "" },
                                datentyp = t.getOrElse(4) { "" },
                                pflicht = t.getOrElse(5) { "" } == "ja",
                                quelle = t.getOrElse(6) { "" },
                            )
                        ) else p
                    }
                    ersetze(funktionen, f.copy(parameter = neue))
                }
                "B" -> funktionen.lastOrNull()?.let { f ->
                    ersetze(funktionen, f.copy(beispiel = "${t.getOrElse(4) { "" }} (args: ${t.getOrElse(2) { "" }}, quelle: ${t.getOrElse(3) { "" }})"))
                }
            }
        }
        return funktionen
    }

    private fun ersetze(liste: MutableList<Funktion>, neu: Funktion) {
        if (liste.isNotEmpty()) liste[liste.size - 1] = neu
    }

    /**
     * Sucht eine Funktion über vollen Pfad, Teil davon oder Kurznamen.
     * Mehrdeutigkeit ist ein Fehler mit Vorschlägen — geraten wird nicht.
     */
    fun finde(funktionen: List<Funktion>, paket: String?, schluessel: String): Result<Funktion> {
        val k = schluessel.lowercase(Locale.ROOT)
        val kandidaten = funktionen.filter { f ->
            (paket == null || f.paket == paket) && (
                f.id.lowercase(Locale.ROOT) == k ||
                    f.id.lowercase(Locale.ROOT).endsWith(schluessel) ||
                    f.kurzname.lowercase(Locale.ROOT) == k ||
                    f.id.lowercase(Locale.ROOT).contains(k)
                )
        }
        return when {
            kandidaten.isEmpty() -> Result.failure(NoSuchElementException("keine Funktion passt auf '$schluessel'"))
            kandidaten.size == 1 -> Result.success(kandidaten[0])
            else -> {
                val genau = kandidaten.filter { it.kurzname.lowercase(Locale.ROOT) == k }
                if (genau.size == 1) Result.success(genau[0])
                else Result.failure(IllegalArgumentException(
                    "'$schluessel' ist mehrdeutig:\n" + kandidaten.joinToString("\n") { "  ${it.id} (${it.paket})" }
                ))
            }
        }
    }

    // ----------------------------------------------------------------- prüfen

    /**
     * Prüft die Argumente und baut die Nutzlast.
     *
     * `args` sind die Felder, wie ein Mensch sie schreiben würde — ohne zu
     * wissen, ob sie auf der obersten Ebene liegen oder in einem
     * Sammelparameter. Das entscheidet die Registry, nicht der Aufrufer.
     */
    fun pruefen(f: Funktion, args: JSONObject): Pruefung {
        val befunde = ArrayList<Befund>()
        val aussen = LinkedHashMap<String, Any?>()
        val innen = LinkedHashMap<String, Any?>()

        if (f.parameter.isEmpty()) {
            val namen = args.keys().asSequence().toList()
            if (namen.isEmpty()) {
                befunde += Befund("P0", false,
                    "'${f.kurzname}' gibt keine Parameter an — es wird nichts geprüft. " +
                        "Nach dem Aufruf den Ist-Zustand gegenlesen.")
            } else {
                befunde += Befund("P0", true,
                    "'${f.kurzname}' gibt keine Parameter an; angegebene Felder können nicht zugeordnet werden: " +
                        namen.joinToString(", "))
            }
            return Pruefung(befunde, "@", "{}", "keine Angabe")
        }

        val composite = f.composite
        val flach = f.flach
        for (name in args.keys()) {
            val wert = args.get(name)
            val passtAussen = flach.any { it.name == name }
            val feld = composite?.felder?.firstOrNull { it.name == name }
            when {
                passtAussen && feld != null -> befunde += Befund("P2", true,
                    "'$name' gibt es als Parameter auf der obersten Ebene UND als Feld in " +
                        "'${composite?.name}'. Das ist in der Registry doppelt; der Aufruf wird nicht geraten.")
                passtAussen -> {
                    val p = flach.first { it.name == name }
                    if (p.typ.isNotEmpty() && !passt(wert, p.typ)) {
                        befunde += Befund("P3", true,
                            "'$name' ist als ${p.typ} angegeben, angegeben wurde ${art(wert)} ($wert). " +
                                "Die Ziel-App nimmt den falschen Typ stillschweigend an und liefert dann eine leere Liste.")
                    }
                    aussen[name] = wert
                }
                feld != null -> {
                    if (feld.datentyp.isNotEmpty() && !passt(wert, feld.datentyp)) {
                        befunde += Befund("P3", true,
                            "'$name' ist als ${feld.datentyp} angegeben, angegeben wurde ${art(wert)} ($wert). " +
                                "Die Ziel-App nimmt den falschen Typ stillschweigend an und liefert dann eine leere Liste.")
                    }
                    if (wert is String && wert.isEmpty() && f.name.equals("findPersons", true)) {
                        befunde += Befund("P5", true,
                            "'$name' ist leer; gemessen an dieser Stelle: Fehler 1001 \"is null or empty\"")
                    }
                    if (name == "maxCount") befunde += maxcount(wert)
                    innen[name] = wert
                }
                else -> {
                    val moeglich = (flach.map { it.name } + (composite?.felder?.map { it.name } ?: emptyList())).sorted()
                    befunde += Befund("P2", true,
                        "'${f.kurzname}' kennt '$name' nicht. Die Plattform würde es still verwerfen. " +
                            "Möglich sind: ${if (moeglich.isEmpty()) "nichts Bekanntes" else moeglich.joinToString(", ")}")
                }
            }
        }

        for (feld in composite?.felder.orEmpty().filter { it.pflicht }) {
            if (!innen.containsKey(feld.name)) {
                befunde += Befund("P1", true, "Pflichtfeld '${feld.name}' (${feld.datentyp}) fehlt. Herkunft der Angabe: ${feld.quelle}")
            }
        }

        val (a3, a4) = if (composite != null) {
            val objekt = JSONObject()
            objekt.put("_typ", composite.typ)
            for ((k, v) in innen) objekt.put(k, v)
            composite.name to objekt.toString()
        } else {
            val objekt = JSONObject()
            for ((k, v) in aussen) objekt.put(k, v)
            "@" to objekt.toString()
        }
        return Pruefung(befunde, a3, a4, if (composite != null) composite.quelle else "oberste-ebene")
    }

    private fun maxcount(wert: Any?): List<Befund> {
        val n = (wert as? Number)?.toLong() ?: return emptyList()
        return when {
            n > MAXCOUNT_OBERGRENZE -> listOf(Befund("P4", false,
                "maxCount=$n liegt über der Obergrenze $MAXCOUNT_OBERGRENZE; die Ziel-Apps liefern höchstens " +
                    "$MAXCOUNT_OBERGRENZE. Der Wert wird auf $MAXCOUNT_OBERGRENZE gesetzt."))
            n == 0L -> listOf(Befund("P4", false,
                "maxCount=0 liefert eine leere Liste — ein Treffer ist so nicht nachweisbar."))
            n < 0 -> listOf(Befund("P4", false,
                "maxCount=-1 heisst bei Kontakten 'alle', bei Fotos, Lesezeichen und Dateien aber Fehler 1001."))
            else -> emptyList()
        }
    }

    /** Kappt `maxCount` in der fertigen Nutzlast auf die gemessene Obergrenze. */
    fun anwenden(a4: String): String = runCatching {
        val o = JSONObject(a4)
        val n = o.optLong("maxCount", Long.MIN_VALUE)
        if (n != Long.MIN_VALUE && n > MAXCOUNT_OBERGRENZE) {
            o.put("maxCount", MAXCOUNT_OBERGRENZE)
            o.toString()
        } else a4
    }.getOrDefault(a4)

    private fun passt(wert: Any?, datentyp: String): Boolean = when (datentyp) {
        "long" -> wert is Long || wert is Int
        "double" -> wert is Double || wert is Float || wert is Long || wert is Int
        "boolean" -> wert is Boolean
        "string" -> wert is String
        "liste-von-text" -> wert is org.json.JSONArray && (0 until wert.length()).all { wert.get(it) is String }
        "document" -> wert is JSONObject
        else -> true // unbekannter Typ der Registry: nicht dagegen prüfen
    }

    private fun art(wert: Any?): String = when (wert) {
        null -> "null"
        is Boolean -> "boolean"
        is Long, is Int -> "ganze Zahl"
        is Double, is Float -> "Kommazahl"
        is String -> "Text"
        is org.json.JSONArray -> "Liste"
        is JSONObject -> "Objekt"
        else -> wert.javaClass.simpleName
    }

    // ------------------------------------------------- aufbereitet für den Kern

    /** Als Eintrag für `list`, ohne das Gerät zu fragen. */
    fun alsEintrag(f: Funktion): AppFunctionsCore.FunctionEntry = AppFunctionsCore.FunctionEntry(
        packageName = f.paket,
        id = f.id,
        schemaName = f.kurzname,
        category = f.kategorie.ifEmpty { null },
        scope = null,
        enabledByDefault = f.status != "aus",
        description = f.beispiel ?: "",
        parameters = f.parameter.map { p ->
            AppFunctionsCore.Param(p.name, p.typ, p.felder.any { it.pflicht }, p.quelle)
        },
        returns = null,
    )
}
