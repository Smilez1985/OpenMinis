package com.openminis.app.appfunctions

import android.content.Context
import android.util.Log
import com.openminis.app.offload.ShizukuManager
import java.io.File

/**
 * Der Rust-Kern `afc` als Kindprozess (DESIGN D9).
 *
 * `libafc.so` liegt in `jniLibs/arm64-v8a` und wird vom Installer nach
 * `nativeLibraryDir` entpackt, dem einzigen Ort, von dem eine App etwas
 * ausführen darf. Aus der App heraus läuft es nicht bis zum Binder: dort ist
 * der Aufrufer die App (uid 10xxx) und die Plattform sagt 1000. Deshalb lässt
 * Kotlin es **über Shizuku** starten, als Shell (uid 2000, oder uid 0 mit
 * `su 2000` — das entscheidet `afc` selbst an seiner uid).
 *
 * Kotlin spricht Shizuku selbst an, weil Shizuku eine eigene App mit eigenem
 * Binder und eigenem Berechtigungsdialog ist. `afc` sieht nur die fertige
 * Aufrufzeile.
 *
 * Regeln und Tabellen stehen in `deps/afc` (Rust) und nur dort; dieser Weg
 * enthält keine. Fehlt das Binary (Build ohne `deps/build_afc_android.sh`),
 * gibt [verfuegbar] false zurück und der Aufrufer nimmt den alten Weg.
 */
object AfcProzess {

    private const val TAG = "AfcProzess"
    private const val FERN = "/data/local/tmp/af"

    /** Was `afc` gedruckt hat und wie es endete. */
    data class Lauf(val exitCode: Int, val text: String)

    fun binary(context: Context): File = File(context.applicationInfo.nativeLibraryDir, "libafc.so")

    fun verfuegbar(context: Context): Boolean = binary(context).let { it.isFile && it.length() > 0 }

    /**
     * Legt `aus.jar` bereit (einmal, per Prüfsumme) und führt `afc` mit den
     * Argumenten aus. Argumente kommen als Array und werden einzeln in
     * Anführungszeichen gesetzt; nie als frei zusammengesetzter Text.
     */
    fun ausfuehren(context: Context, argumente: List<String>, zeitSekunden: Int = 30): Lauf {
        val bin = binary(context)
        if (!bin.isFile) return Lauf(-1, "FEHLER: afc-code=NO_AFC msg=${bin.path} fehlt")
        val jar = AppFunctionsBinder.jarBereitstellen(context)
        if (jar != null) return Lauf(-1, "FEHLER: afc-code=NO_SHIPPED_DEX msg=$jar")

        val zeile = buildString {
            append("AFC_JAR=").append("$FERN/aus.jar").append(' ')
            append(quote(bin.path))
            for (a in argumente) append(' ').append(quote(a))
        }
        val r = ShizukuManager.runProcess(arrayOf("exec", zeile), timeoutMs = (zeitSekunden + 20) * 1000L)
        val text = listOfNotNull(r.stdout, r.stderr).filter { it.isNotBlank() }.joinToString("\n")
        Log.i(TAG, "afc ${argumente.firstOrNull()} — exit ${r.exitCode}")
        return Lauf(r.exitCode, text)
    }

    /** POSIX-Anführung: in einfache Anführungszeichen, `'` wird zu `'\''`. */
    fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /**
     * Die Argumente für `afc call`. `--schreiben` nur, wenn der Nutzer bestätigt
     * hat; Kotlin reicht das Wort durch, entscheidet aber nicht darüber.
     */
    fun callArgumente(
        paket: String,
        funktionId: String,
        argumenteJson: String,
        zeitSekunden: Int,
        schreibenErlaubt: Boolean,
    ): List<String> = buildList {
        add("call"); add(paket); add(funktionId)
        add("--args"); add(argumenteJson)
        add("--zeit"); add(zeitSekunden.toString())
        if (schreibenErlaubt) add("--schreiben")
    }

    /** Was ein `afc call` ergab. Gelesen wird nur das erste Wort der Ergebniszeile. */
    sealed interface Ergebnis {
        data class Erfolg(val json: String) : Ergebnis
        object Leer : Ergebnis
        object BrauchtZustimmung : Ergebnis
        data class Fehler(val text: String) : Ergebnis
    }

    /**
     * Deutet die Ausgabe von `afc call`. Gemessen (31.10.): `ERFOLG: <json>` mit
     * Exit 0; `EMPTY_RESULT` mit Exit 2; `NEEDS_CONFIRMATION` mit Exit **0**
     * (die Prüfung bestand, aufgerufen wurde nichts); `FEHLER <code>` und
     * Regelverstöße mit Exit 2. Deshalb zählt der Text, nicht der Exit-Code.
     */
    fun deute(text: String, exitCode: Int): Ergebnis {
        val zeilen = text.lines()
        zeilen.firstOrNull { it.startsWith("NEEDS_CONFIRMATION") }?.let { return Ergebnis.BrauchtZustimmung }
        if (zeilen.any { it.startsWith("EMPTY_RESULT") }) return Ergebnis.Leer
        val erfolg = zeilen.firstOrNull { it.startsWith("ERFOLG: ") }
        if (erfolg != null && exitCode == 0) return Ergebnis.Erfolg(erfolg.removePrefix("ERFOLG: ").ifBlank { "{}" })
        return Ergebnis.Fehler(text.trim().ifBlank { "afc exit $exitCode" })
    }
}
