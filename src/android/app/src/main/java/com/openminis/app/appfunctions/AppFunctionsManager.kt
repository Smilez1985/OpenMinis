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
     * The function list. The device dump comes first (`cmd app_function
     * list-app-functions`); it is the fresh state. If the command does not
     * exist (on this device: "No shell command implementation"), the shipped
     * registry supplies the data, including parameters and required fields.
     */
    fun listDocument(context: Context): Result<AppFunctionsCore.ListDocument> {
        val dump = runCatching { dumpListDocument() }
        if (dump.isSuccess) return dump
        val reason = dump.exceptionOrNull()?.message
        return runCatching {
            val functions = AppFunctionsRegistry.load(context)
            check(functions.isNotEmpty()) { "the shipped registry is empty (device dump: $reason)" }
            Log.i(TAG, "device dump not possible ($reason); registry supplies the list (${functions.size} functions)")
            AppFunctionsCore.ListDocument(
                entries = functions.filter { it.status != "off" }.map { AppFunctionsRegistry.asEntry(it) },
                types = emptyMap(),
            )
        }
    }

    /** The dump from the device, if it has the command. */
    private fun dumpListDocument(): AppFunctionsCore.ListDocument {
        check(ShizukuManager.isReady()) { "dump needs Shizuku (state ${ShizukuManager.snapshot.value.state})" }
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
            Path.SHIZUKU -> viaBinder(context, packageName, functionId, parametersJson, timeoutSeconds)
            Path.NONE -> AppFunctionsCore.ExecOutcome.FunctionError("NO_PATH: ${decision.reason}")
        }
    }

    /**
     * The binder path: check first, then call, then interpret the reply.
     *
     * `cmd app_function` does not exist on this device (measured: "No shell
     * command implementation"), and the direct permission is privileged. So
     * the call goes out as uid 2000 through [AppFunctionsBinder], the same
     * chain `deps/afc` verified on the command line against 13 functions.
     *
     * The check runs against the shipped registry: the platform silently drops
     * unknown fields and accepts a number as text without complaint. What does
     * not fit shows up here.
     */
    private fun viaBinder(
        context: Context,
        packageName: String,
        functionId: String,
        parametersJson: String,
        timeoutSeconds: Int,
    ): AppFunctionsCore.ExecOutcome {
        if (AfcProcess.available(context)) return viaAfc(context, packageName, functionId, parametersJson, timeoutSeconds)
        val functions = AppFunctionsRegistry.load(context)
        val f = AppFunctionsRegistry.find(functions, packageName, functionId).getOrElse {
            return AppFunctionsCore.ExecOutcome.FunctionError("1003: ${it.message}")
        }
        val args = runCatching { JSONObject(parametersJson) }.getOrElse {
            return AppFunctionsCore.ExecOutcome.FunctionError("1001: arguments are not a JSON object (${it.message})")
        }
        val c = AppFunctionsRegistry.check(f, args)
        if (c.hasErrors) {
            Log.i(TAG, "check failed: ${c.findings.count { x -> x.isError }} rule(s)")
            return AppFunctionsCore.ExecOutcome.FunctionError(c.report())
        }
        val a4 = AppFunctionsRegistry.apply(c.a4)
        val raw = AppFunctionsBinder.run(
            context,
            AppFunctionsBinder.Call(f.packageName, f.id, c.a3, a4, timeoutSeconds),
        )
        val reply = AppFunctionsReply.interpret(raw.text)
        Log.i(TAG, "reply: ${reply.kind} (${f.shortName}, a3=${c.a3})")
        return when (reply.kind) {
            AppFunctionsReply.Kind.SUCCESS -> AppFunctionsCore.ExecOutcome.Success(reply.json ?: "{}")
            AppFunctionsReply.Kind.EMPTY -> AppFunctionsCore.ExecOutcome.Empty
            else -> AppFunctionsCore.ExecOutcome.FunctionError(reply.report())
        }
    }

    /**
     * The path through the Rust core (DESIGN D9). `afc` checks, calls and
     * interprets; Kotlin only reads the first word of the result line. Writing
     * functions have already received their consent here (caller in
     * AppFunctionsTool), which is why `--write` is set.
     */
    private fun viaAfc(
        context: Context,
        packageName: String,
        functionId: String,
        parametersJson: String,
        timeoutSeconds: Int,
    ): AppFunctionsCore.ExecOutcome {
        val run = AfcProcess.run(
            context,
            AfcProcess.callArguments(packageName, functionId, parametersJson, writeAllowed = true),
            timeoutSeconds,
        )
        val e = AfcProcess.interpret(run.text, run.exitCode)
        Log.i(TAG, "afc: ${e::class.simpleName} (exit ${run.exitCode})")
        return when (e) {
            is AfcProcess.Outcome.Success -> AppFunctionsCore.ExecOutcome.Success(e.json)
            AfcProcess.Outcome.Empty -> AppFunctionsCore.ExecOutcome.Empty
            AfcProcess.Outcome.NeedsConfirmation ->
                AppFunctionsCore.ExecOutcome.FunctionError("NEEDS_CONFIRMATION: the function changes something; consent is missing")
            is AfcProcess.Outcome.Failure -> AppFunctionsCore.ExecOutcome.FunctionError(e.text)
        }
    }
}
