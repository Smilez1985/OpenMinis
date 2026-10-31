package com.openminis.app.appfunctions

import android.content.Context
import android.util.Log
import com.openminis.app.offload.ShizukuManager
import java.security.MessageDigest

/**
 * Der Binder-Weg für AppFunctions.
 *
 * Warum nicht `cmd app_function`: den Befehl gibt es auf diesem Gerät nicht
 * (manche Android-16-Geraete antworten „No shell command implementation"), und die
 * Direktberechtigung `EXECUTE_APP_FUNCTIONS` ist `internal|privileged`, also für
 * eine installierte App nicht zu bekommen. Bleibt der Binder, aufgerufen als
 * **uid 2000 mit Paket `com.android.shell`** — gemessen am 29./30.10.: uid 0
 * scheitert (kein Paket zur uid 0), die App selbst scheitert mit 1000, uid 2000
 * geht.
 *
 * Die Zwischenschicht wählt den Konstruktor von `ExecuteAppFunctionAidlRequest` selbst
 * (nach Typen, per Reflection), weil er je Build verschieden ist: vier Argumente
 * auf BP2A, fünf auf BP4A. Findet sie keinen, meldet sie `NO_CONSTRUCTOR` samt den
 * deklarierten Signaturen; der Aufrufer reicht diese Zeile unverändert weiter.
 * Dieselbe Jar läuft bei `deps/afc`.
 *
 * Der Binder selbst wird nicht nachgebaut. `aus.jar` (6,9 KB, dex) baut die
 * Anfrage per Reflection und ruft `IAppFunctionManager.executeAppFunction`. Das
 * ist auf dem Gerät gegen 13 Funktionen gemessen; das Parcel-Format selbst
 * nachzubauen wäre ungeprüft. Dieselbe Kette fährt `deps/afc` (Rust) auf der
 * Kommandozeile — dort sind die Regeln und Fehlercodes mit Tests belegt.
 *
 * Diese Klasse macht nur den Transport: dex bereitlegen, Befehl absetzen,
 * Antwort einsammeln. Was geprüft wird, entscheidet [AppFunctionsRegistry];
 * wie die Antwort zu lesen ist, [AppFunctionsAntwort].
 *
 * Gemessen am 30.10. über den Debug-Server der laufenden App
 * (`debug.shizuku.exec`): die Kette läuft aus der App heraus und liefert echte
 * Daten (Kontakte, drei Treffer zu „e").
 */
object AppFunctionsBinder {

    private const val TAG = "AppFunctionsBinder"

    /** Wohin das dex auf dem Gerät gelegt wird. Weltweit lesbar, wie in `af.sh`. */
    private const val FERN_VERZEICHNIS = "/data/local/tmp/af"
    private const val FERN_DEX = "$FERN_VERZEICHNIS/aus.jar"

    /** Aufrufer, den die Plattform prüft: uid 2000 und dieses Paket. */
    const val AUFRUFER = "com.android.shell"

    const val DEX_ASSET = "appfunctions/aus.jar"

    /**
     * Ein Aufruf. `parameter` ist `-a3-` für `Ausfuehren`: der Name des
     * Sammelparameters oder `@`, wenn die Werte auf der obersten Ebene liegen.
     * `nutzlastJson` ist `-a4-`: das Parameterobjekt selbst, mit `_typ`.
     */
    data class Aufruf(
        val paket: String,
        val funktionId: String,
        val parameter: String,
        val nutzlastJson: String,
        val zeitSekunden: Int = 30,
    )

    /** Was `Ausfuehren` gedruckt hat, roh. */
    data class Roh(val exitCode: Int, val text: String)

    /**
     * Legt das dex bereit und ruft auf. Wirft nicht: jeder Fehler kommt als
     * [Roh] mit Text zurück, damit der Aufrufer ihn wie eine Antwort der
     * Plattform behandeln kann.
     */
    fun ausfuehren(context: Context, aufruf: Aufruf): Roh {
        val dex = dexBereitstellen(context)
        if (dex is Ergebnis.Fehler) return Roh(-1, "FEHLER: afc-code=NO_SHIPPED_DEX msg=${dex.grund}")

        val skript = skript(aufruf)
        val r = ShizukuManager.runProcess(arrayOf("exec", skript), timeoutMs = (aufruf.zeitSekunden + 20) * 1000L)
        val text = listOfNotNull(r.stdout, r.stderr).filter { it.isNotBlank() }.joinToString("\n")
        Log.i(TAG, "execute ${aufruf.paket}/${aufruf.funktionId.substringAfter('#')} — exit ${r.exitCode}")
        return Roh(r.exitCode, text)
    }

    /**
     * Das Skript, das auf dem Gerät läuft. Es entspricht dem Aufruf, den
     * `deps/afc` (`src/weg.rs`) baut, und ist damit auf beiden Wegen derselbe:
     * dex legen, als uid 2000 aufrufen, beide Ausgabeströme mitschicken.
     */
    fun skript(aufruf: Aufruf): String {
        require('\'' !in aufruf.paket && '\'' !in aufruf.funktionId) {
            "Paket oder Funktions-ID enthält ein Anführungszeichen"
        }
        val nutzlast = Base64Kodierer.encode(aufruf.nutzlastJson.toByteArray(Charsets.UTF_8))
        return buildString {
            append("mkdir -p $FERN_VERZEICHNIS\n")
            append("su 2000 -c \"CLASSPATH=$FERN_DEX app_process / Ausfuehren")
            append(" '").append(aufruf.paket).append("'")
            append(" '").append(aufruf.funktionId).append("'")
            append(" ").append(AUFRUFER)
            append(" '").append(aufruf.parameter).append("'")
            append(" b64:").append(nutzlast)
            append("\" 2>&1\n")
        }
    }

    // ------------------------------------------------------------- dex bereitstellen

    private sealed interface Ergebnis {
        object Da : Ergebnis
        data class Fehler(val grund: String) : Ergebnis
    }

    /**
     * Schreibt das dex nach /data/local/tmp/af. Der kürzeste gangbare Weg: den
     * Inhalt als base64 durch die Shell schicken. Das Umkopieren aus dem
     * App-Verzeichnis ginge nur, wenn der Aufrufer die App-Dateien lesen darf —
     * als uid 2000 darf er das nicht, als root schon. base64 funktioniert in
     * beiden Fällen und braucht kein Verzeichnis, das es vielleicht nicht gibt.
     *
     * Vorher wird die Prüfsumme verglichen; stimmt sie, entfällt das Schreiben.
     */
    /** Für [AfcProzess]: legt `aus.jar` bereit. `null` = liegt da, sonst der Grund. */
    internal fun jarBereitstellen(context: Context): String? =
        (dexBereitstellen(context) as? Ergebnis.Fehler)?.grund

    private fun dexBereitstellen(context: Context): Ergebnis {
        val dex = try {
            context.assets.open(DEX_ASSET).use { it.readBytes() }
        } catch (e: Exception) {
            return Ergebnis.Fehler("Asset $DEX_ASSET nicht lesbar: ${e.message}")
        }
        if (dex.isEmpty()) return Ergebnis.Fehler("Asset $DEX_ASSET ist leer")
        val soll = sha256(dex)
        val liegt = ShizukuManager.runProcess(
            arrayOf("exec", "sha256sum $FERN_DEX 2>/dev/null | cut -d' ' -f1"),
            timeoutMs = 15_000L,
        ).stdout.trim()
        if (liegt == soll) return Ergebnis.Da

        val b64 = Base64Kodierer.encode(dex)
        Log.i(TAG, "lege dex ab ($FERN_DEX, ${dex.size} Byte, sha256 ${soll.take(12)}…)")
        val schreiben = "mkdir -p $FERN_VERZEICHNIS && echo $b64 | base64 -d > $FERN_DEX && chmod 755 $FERN_DEX && sha256sum $FERN_DEX | cut -d' ' -f1"
        val r = ShizukuManager.runProcess(arrayOf("exec", schreiben), timeoutMs = 60_000L)
        val ist = r.stdout.trim().lines().lastOrNull()?.trim().orEmpty()
        return if (ist == soll) {
            Ergebnis.Da
        } else {
            Ergebnis.Fehler("dex konnte nicht abgelegt werden (exit ${r.exitCode}, sha ${ist.take(12)}… statt ${soll.take(12)}…)")
        }
    }

    private fun sha256(daten: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(daten)
            .joinToString("") { "%02x".format(it) }
}

/** base64 ohne Fremdbibliothek — dieselbe Kodierung, die `Base64.getEncoder()` liefert. */
internal object Base64Kodierer {
    private const val T = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun encode(daten: ByteArray): String {
        val s = StringBuilder((daten.size + 2) / 3 * 4)
        var i = 0
        while (i < daten.size) {
            val b0 = daten[i].toInt() and 0xff
            val b1 = if (i + 1 < daten.size) daten[i + 1].toInt() and 0xff else 0
            val b2 = if (i + 2 < daten.size) daten[i + 2].toInt() and 0xff else 0
            val n = (b0 shl 16) or (b1 shl 8) or b2
            s.append(T[(n ushr 18) and 63])
            s.append(T[(n ushr 12) and 63])
            s.append(if (i + 1 < daten.size) T[(n ushr 6) and 63] else '=')
            s.append(if (i + 2 < daten.size) T[n and 63] else '=')
            i += 3
        }
        return s.toString()
    }
}
