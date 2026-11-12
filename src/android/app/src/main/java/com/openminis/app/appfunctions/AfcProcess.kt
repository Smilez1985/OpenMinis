package com.openminis.app.appfunctions

import android.content.Context
import android.util.Log
import com.openminis.app.offload.ShizukuManager
import java.io.File

/**
 * The Rust core `afc` as a child process (DESIGN D9).
 *
 * `libafc.so` sits in `jniLibs/arm64-v8a` and the installer unpacks it to
 * `nativeLibraryDir`, the only place an app may execute anything from. Run
 * from the app itself it would not get as far as the binder: there the caller
 * is the app (uid 10xxx) and the platform sees 1000. So Kotlin starts it
 * **through Shizuku**, as shell (uid 2000, or uid 0 with `su 2000`; `afc`
 * decides that itself from its uid).
 *
 * Kotlin talks to Shizuku itself because Shizuku is a separate app with its
 * own binder and its own permission dialog. `afc` only sees the finished
 * command line.
 *
 * Rules and tables live in `deps/afc` (Rust) and only there; this path
 * contains none. If the binary is missing (build without
 * `deps/build_afc_android.sh`), [available] returns false and the caller
 * takes the old path.
 */
object AfcProcess {

    private const val TAG = "AfcProcess"
    private const val REMOTE_DIR = "/data/local/tmp/af"

    /** What `afc` printed and how it ended. */
    data class Run(val exitCode: Int, val text: String)

    fun binary(context: Context): File = File(context.applicationInfo.nativeLibraryDir, "libafc.so")

    fun available(context: Context): Boolean = binary(context).let { it.isFile && it.length() > 0 }

    /**
     * Stages `shim.jar` (once, by checksum) and runs `afc` with the arguments.
     * Arguments arrive as a list and are quoted one by one, never as freely
     * assembled text.
     */
    fun run(context: Context, arguments: List<String>, timeoutSeconds: Int = 30): Run {
        val bin = binary(context)
        if (!bin.isFile) return Run(-1, "ERROR: afc-code=NO_AFC msg=${bin.path} is missing")
        val jar = AppFunctionsBinder.ensureShim(context)
        if (jar != null) return Run(-1, "ERROR: afc-code=NO_SHIPPED_DEX msg=$jar")

        val line = commandLine(bin.path, arguments)
        AppFunctionsBinder.tooLong(line)?.let { return Run(-1, "ERROR: afc-code=TOO_LONG msg=$it") }
        val r = ShizukuManager.runProcess(AppFunctionsBinder.shell(line), timeoutMs = (timeoutSeconds + 20) * 1000L)
        val text = listOfNotNull(r.stdout, r.stderr).filter { it.isNotBlank() }.joinToString("\n")
        Log.i(TAG, "afc ${arguments.firstOrNull()} — exit ${r.exitCode}")
        return Run(r.exitCode, text)
    }

    /** The line that runs on the device: `AFC_JAR=… 'afc' 'arg' …`. */
    fun commandLine(binary: String, arguments: List<String>): String = buildString {
        append("AFC_JAR=").append("$REMOTE_DIR/shim.jar").append(' ')
        append(quote(binary))
        for (a in arguments) append(' ').append(quote(a))
    }

    /** POSIX quoting: wrap in single quotes, `'` becomes `'\''`. */
    fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /**
     * The arguments for `afc call`. `--write` only when the user confirmed;
     * Kotlin passes the word through but does not decide about it. There is no
     * timeout argument: the shim waits a fixed time, and the process deadline
     * is set by [run].
     */
    fun callArguments(
        packageName: String,
        functionId: String,
        argumentsJson: String,
        writeAllowed: Boolean,
    ): List<String> = buildList {
        add("call"); add(packageName); add(functionId)
        add("--args"); add(argumentsJson)
        if (writeAllowed) add("--write")
    }

    /** What an `afc call` came to. Only the first word of the result line is read. */
    sealed interface Outcome {
        data class Success(val json: String) : Outcome
        object Empty : Outcome
        object NeedsConfirmation : Outcome
        data class Failure(val text: String) : Outcome
    }

    /**
     * Interprets the output of `afc call`. Measured (31 Oct 2026): `SUCCESS: <json>`
     * with exit 0; `EMPTY_RESULT` with exit 2; `NEEDS_CONFIRMATION` with exit **0**
     * (the check passed, nothing was called); `ERROR <code>` and rule
     * violations with exit 2. That is why the text counts, not the exit code.
     */
    fun interpret(text: String, exitCode: Int): Outcome {
        val lines = text.lines()
        lines.firstOrNull { it.startsWith("NEEDS_CONFIRMATION") }?.let { return Outcome.NeedsConfirmation }
        if (lines.any { it.startsWith("EMPTY_RESULT") }) return Outcome.Empty
        val success = lines.firstOrNull { it.startsWith("SUCCESS: ") }
        if (success != null && exitCode == 0) return Outcome.Success(success.removePrefix("SUCCESS: ").ifBlank { "{}" })
        return Outcome.Failure(text.trim().ifBlank { "afc exit $exitCode" })
    }
}
