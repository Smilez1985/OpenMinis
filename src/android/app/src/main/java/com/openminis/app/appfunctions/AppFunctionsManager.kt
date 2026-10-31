package com.openminis.app.appfunctions

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import org.json.JSONObject
import com.openminis.app.offload.ShizukuManager
import java.util.zip.ZipFile

/**
 * [T-appfunctions-caller] Discovers and executes other apps' AppFunctions
 * (Android 16+, `android.app.appfunctions`).
 *
 * One core, two execution paths, chosen by MEASUREMENT, never by device model:
 *
 * - [Path.DIRECT]: this app holds `EXECUTE_APP_FUNCTIONS` (declared in the
 *   manifest). The docs list it as protection level `normal` (16 QPR2 / 17)
 *   plus a runtime agent allowlist; measured on a Samsung Android 17 device it
 *   is `internal|privileged|knownSigner`, i.e. not granted to a sideloaded app.
 *   Where it IS granted, [PlatformExecutor] calls the platform API directly.
 * - [Path.SHIZUKU]: the shell identity (uid 2000) holds
 *   `EXECUTE_APP_FUNCTIONS_SYSTEM`, which the platform does not check against
 *   the agent allowlist. `cmd app_function` over the existing Shizuku
 *   integration lists and executes (measured on the same device).
 *
 * Discovery always goes through `cmd app_function list-app-functions` when
 * Shizuku is ready (works on API 36 and 37); the direct path's own discovery
 * (`searchAppFunctions`) needs API 37 / compileSdk 37 and is not wired yet.
 * Schemas are read from the target APKs, which needs no privilege at all.
 */
object AppFunctionsManager {
    private const val TAG = "AppFunctions"
    const val PERMISSION = "android.permission.EXECUTE_APP_FUNCTIONS"
    /** Service action target apps declare (measured: Samsung Clock, Play services). */
    const val SERVICE_ACTION = "android.app.appfunctions.AppFunctionService"
    private const val LIST_TIMEOUT_MS = 20_000L

    enum class Path { DIRECT, SHIZUKU, NONE }

    data class PathDecision(val path: Path, val reason: String)

    fun isSupportedOs(): Boolean = Build.VERSION.SDK_INT >= 36

    /** Which execution path carries, and why. Cheap; call per request. */
    fun decidePath(context: Context): PathDecision {
        if (!isSupportedOs()) return PathDecision(Path.NONE, "needs Android 16 (API 36), device has ${Build.VERSION.SDK_INT}")
        val granted = context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED
        if (granted) return PathDecision(Path.DIRECT, "$PERMISSION granted")
        if (ShizukuManager.isReady()) return PathDecision(Path.SHIZUKU, "$PERMISSION not granted; using Shizuku shell")
        return PathDecision(Path.NONE,
            "$PERMISSION not granted on this device and Shizuku is not ready (${ShizukuManager.snapshot.value.state})")
    }

    // ------------------------------------------------------------- discovery

    fun list(context: Context): Result<List<AppFunctionsCore.FunctionEntry>> =
        listDocument(context).map { it.entries }

    /**
     * Die Funktionsliste. Zuerst der Geräteabzug (`cmd app_function
     * list-app-functions`) — er ist der frische Stand. Gibt es den Befehl nicht
     * (auf diesem Gerät: „No shell command implementation"), trägt die
     * mitgelieferte Registry die Angaben, samt Parametern und Pflichtfeldern.
     */
    fun listDocument(context: Context): Result<AppFunctionsCore.ListDocument> {
        val dump = runCatching { dumpListDocument() }
        if (dump.isSuccess) return dump
        val grund = dump.exceptionOrNull()?.message
        return runCatching {
            val funktionen = AppFunctionsRegistry.laden(context)
            check(funktionen.isNotEmpty()) { "die mitgelieferte Registry ist leer (Geräteabzug: $grund)" }
            Log.i(TAG, "Geraeteabzug nicht moeglich ($grund) — Registry traegt die Liste (${funktionen.size} Funktionen)")
            AppFunctionsCore.ListDocument(
                entries = funktionen.filter { it.status != "aus" }.map { AppFunctionsRegistry.alsEintrag(it) },
                types = emptyMap(),
            )
        }
    }

    /** Der Abzug vom Gerät, sofern es den Befehl gibt. */
    private fun dumpListDocument(): AppFunctionsCore.ListDocument {
        check(ShizukuManager.isReady()) { "Dump braucht Shizuku (Zustand ${ShizukuManager.snapshot.value.state})" }
        val r = ShizukuManager.runProcess(arrayOf("cmd", "app_function", "list-app-functions"), timeoutMs = LIST_TIMEOUT_MS)
        check(r.exitCode == 0) { "list-app-functions rc ${r.exitCode}: ${r.stderr.take(300)}" }
        return AppFunctionsCore.parseListDocument(r.stdout)
    }

    /**
     * Packages that declare an AppFunction service. Needs the `<queries>`
     * entry for [SERVICE_ACTION] in the manifest (package visibility).
     */
    fun providerPackages(context: Context): Set<String> =
        context.packageManager.queryIntentServices(Intent(SERVICE_ACTION), 0)
            .mapNotNull { it.serviceInfo?.packageName }.toSet()

    // ---------------------------------------------------------------- schema

    /**
     * Reads `assets/…app_function….xml` from the target APK (base + splits).
     * No privilege needed: installed APKs are world-readable.
     */
    fun schema(context: Context, packageName: String): Result<AppFunctionsCore.SchemaDocument> = runCatching {
        val ai = context.packageManager.getApplicationInfo(packageName, 0)
        val apks = listOfNotNull(ai.sourceDir) + (ai.splitSourceDirs?.toList() ?: emptyList())
        val functions = mutableListOf<AppFunctionsCore.FunctionSchema>()
        val types = linkedMapOf<String, List<AppFunctionsCore.Param>>()
        var found = 0
        for (apk in apks) {
            ZipFile(apk).use { zip ->
                zip.entries().asSequence()
                    .filter { e -> e.name.startsWith("assets/") && e.name.endsWith(".xml") && SCHEMA_FILE.matches(e.name.substringAfterLast('/')) }
                    .forEach { e ->
                        found++
                        val doc = AppFunctionsCore.parseSchemaXml(zip.getInputStream(e).use { it.readBytes() })
                        functions += doc.functions
                        types += doc.types
                    }
            }
        }
        check(found > 0) { "no AppFunction schema file in the APK of $packageName" }
        AppFunctionsCore.SchemaDocument(functions, types)
    }

    /** `app_function.xml`, `gms_appfunctions.xml`, `draft_message_app_functions.xml` … */
    private val SCHEMA_FILE = Regex(".*app_?functions?.*\\.xml")

    // --------------------------------------------------------------- execute

    suspend fun execute(
        context: Context,
        packageName: String,
        functionId: String,
        parametersJson: String,
        timeoutSeconds: Int,
    ): AppFunctionsCore.ExecOutcome {
        val decision = decidePath(context)
        Log.i(TAG, "execute $packageName/${functionId.substringAfter('#')} via ${decision.path} (${decision.reason})")
        return when (decision.path) {
            Path.DIRECT -> PlatformExecutor.execute(context, packageName, functionId, parametersJson, timeoutSeconds)
            Path.SHIZUKU -> ueberBinder(context, packageName, functionId, parametersJson, timeoutSeconds)
            Path.NONE -> AppFunctionsCore.ExecOutcome.FunctionError("NO_PATH: ${decision.reason}")
        }
    }

    /**
     * Der Binder-Weg: erst prüfen, dann aufrufen, dann die Antwort deuten.
     *
     * `cmd app_function` gibt es auf diesem Gerät nicht (gemessen: „No shell
     * command implementation"), die Direktberechtigung ist privilegiert. Also
     * geht der Aufruf als uid 2000 über [AppFunctionsBinder] — dieselbe Kette,
     * die `deps/afc` auf der Kommandozeile gegen 13 Funktionen geprüft hat.
     *
     * Geprüft wird gegen die mitgelieferte Registry: die Plattform verwirft
     * unbekannte Felder still und nimmt eine Zahl als Text an, ohne sich zu
     * beschweren. Was nicht passt, fällt hier auf.
     */
    private fun ueberBinder(
        context: Context,
        packageName: String,
        functionId: String,
        parametersJson: String,
        timeoutSeconds: Int,
    ): AppFunctionsCore.ExecOutcome {
        if (AfcProzess.verfuegbar(context)) return ueberAfc(context, packageName, functionId, parametersJson, timeoutSeconds)
        val funktionen = AppFunctionsRegistry.laden(context)
        val f = AppFunctionsRegistry.finde(funktionen, packageName, functionId).getOrElse {
            return AppFunctionsCore.ExecOutcome.FunctionError("1003: ${it.message}")
        }
        val args = runCatching { JSONObject(parametersJson) }.getOrElse {
            return AppFunctionsCore.ExecOutcome.FunctionError("1001: Argumente sind kein JSON-Objekt (${it.message})")
        }
        val p = AppFunctionsRegistry.pruefen(f, args)
        if (p.hatFehler) {
            Log.i(TAG, "Pruefung nicht bestanden: ${p.befunde.count { b -> b.fehler }} Regel(n)")
            return AppFunctionsCore.ExecOutcome.FunctionError(p.bericht())
        }
        val a4 = AppFunctionsRegistry.anwenden(p.a4)
        val roh = AppFunctionsBinder.ausfuehren(
            context,
            AppFunctionsBinder.Aufruf(f.paket, f.id, p.a3, a4, timeoutSeconds),
        )
        val antwort = AppFunctionsAntwort.deute(roh.text)
        Log.i(TAG, "Antwort: ${antwort.art} (${f.kurzname}, a3=${p.a3})")
        return when (antwort.art) {
            AppFunctionsAntwort.Art.ERFOLG -> AppFunctionsCore.ExecOutcome.Success(antwort.json ?: "{}")
            AppFunctionsAntwort.Art.LEER -> AppFunctionsCore.ExecOutcome.Empty
            else -> AppFunctionsCore.ExecOutcome.FunctionError(antwort.bericht())
        }
    }

    /**
     * Der Weg über den Rust-Kern (DESIGN D9). `afc` prüft, ruft auf und deutet;
     * Kotlin liest nur das erste Wort der Ergebniszeile. Schreibende Funktionen
     * haben hier ihre Zustimmung schon bekommen (Aufrufer in AppFunctionsTool),
     * deshalb wird `--schreiben` gesetzt.
     */
    private fun ueberAfc(
        context: Context,
        packageName: String,
        functionId: String,
        parametersJson: String,
        timeoutSeconds: Int,
    ): AppFunctionsCore.ExecOutcome {
        val lauf = AfcProzess.ausfuehren(
            context,
            AfcProzess.callArgumente(packageName, functionId, parametersJson, timeoutSeconds, schreibenErlaubt = true),
            timeoutSeconds,
        )
        val e = AfcProzess.deute(lauf.text, lauf.exitCode)
        Log.i(TAG, "afc: ${e::class.simpleName} (exit ${lauf.exitCode})")
        return when (e) {
            is AfcProzess.Ergebnis.Erfolg -> AppFunctionsCore.ExecOutcome.Success(e.json)
            AfcProzess.Ergebnis.Leer -> AppFunctionsCore.ExecOutcome.Empty
            AfcProzess.Ergebnis.BrauchtZustimmung ->
                AppFunctionsCore.ExecOutcome.FunctionError("NEEDS_CONFIRMATION: die Funktion aendert etwas; Zustimmung fehlt")
            is AfcProzess.Ergebnis.Fehler -> AppFunctionsCore.ExecOutcome.FunctionError(e.text)
        }
    }
}
