package com.openminis.app.appfunctions

import android.content.Context
import android.util.Log
import com.openminis.app.offload.ShizukuManager
import java.security.MessageDigest

/**
 * The binder path for AppFunctions.
 *
 * Why not `cmd app_function`: the command does not exist on this device
 * (some Android 16 devices answer "No shell command implementation"), and the
 * direct permission `EXECUTE_APP_FUNCTIONS` is `internal|privileged`, so an
 * installed app cannot get it. What remains is the binder, called as
 * **uid 2000 with package `com.android.shell`**. Measured on 29/30 Oct 2026:
 * uid 0 fails (no package for uid 0), the app itself fails with 1000, uid 2000
 * works.
 *
 * The shim picks the constructor of `ExecuteAppFunctionAidlRequest` itself
 * (by types, via reflection) because it differs per build: four arguments on
 * BP2A, five on BP4A. If it finds none it reports `NO_CONSTRUCTOR` together
 * with the declared signatures; the caller passes that line on unchanged.
 *
 * The binder itself is not re-implemented. `shim.jar` (7.5 KB, dex) builds
 * the request via reflection and calls `IAppFunctionManager.executeAppFunction`.
 * That is measured on the device against 13 functions; rebuilding the Parcel
 * format by hand would be unverified. The chain is the same one verified on the command line
 * on the command line, where the rules and error codes are backed by tests.
 *
 * This class only does the transport: stage the dex, send the command,
 * collect the reply. What gets checked is decided by [AppFunctionsRegistry];
 * how the reply is read, by [AppFunctionsReply].
 *
 * Measured on 30 Oct 2026 through the debug server of the running app
 * (`debug.shizuku.exec`): the chain runs from inside the app and returns real
 * data (contacts, three hits for "e").
 */
object AppFunctionsBinder {

    private const val TAG = "AppFunctionsBinder"

    /** Where the dex is placed on the device. World-readable, as in `af.sh`. */
    private const val REMOTE_DIR = "/data/local/tmp/af"
    private const val REMOTE_SHIM = "$REMOTE_DIR/shim.jar"

    /** The caller the platform checks: uid 2000 and this package. */
    const val CALLER = "com.android.shell"

    const val SHIM_ASSET = "appfunctions/shim.jar"

    /** Characters per command when staging; far below the 8192 limit of the Shizuku path. */
    internal const val CHUNK_SIZE = 4000

    /**
     * Longest command line the Shizuku path passes through intact. Measured:
     * 8168 characters intact, 8198 truncated, and a truncated command ends
     * silently with exit 0. Calculated with a safety margin.
     */
    internal const val COMMAND_LIMIT = 8000

    /**
     * Arguments for `ShizukuManager.runProcess`: a free-form command line goes
     * through `sh -c`. `exec` is a subcommand of the CLI, not a program; as the
     * first argument Shizuku then starts no process at all.
     */
    internal fun shell(command: String): Array<String> = arrayOf("sh", "-c", command)

    /** `null` = fits through the Shizuku path, otherwise the reason. */
    internal fun tooLong(command: String): String? =
        if (command.length <= COMMAND_LIMIT) null
        else "command has ${command.length} characters, the Shizuku path silently truncates beyond $COMMAND_LIMIT"

    /**
     * The commands that write [b64] in chunks to `<file>.b64`: the first with
     * `>`, all later ones with `>>`, each carrying at most [CHUNK_SIZE]
     * characters of payload.
     */
    internal fun stageCommands(b64: String, directory: String, file: String): List<String> =
        b64.chunked(CHUNK_SIZE).mapIndexed { i, chunk ->
            val op = if (i == 0) ">" else ">>"
            val prefix = if (i == 0) "mkdir -p $directory && " else ""
            "${prefix}printf '%s' '$chunk' $op $file.b64"
        }

    /** Decode, set permissions, print the checksum; the chunk file is deleted. */
    internal fun finishCommand(file: String): String =
        "base64 -d $file.b64 > $file && rm -f $file.b64 && chmod 755 $file && sha256sum $file | cut -d' ' -f1"

    /**
     * One call. `parameter` is `-a3-` for `Execute`: the name of the composite
     * parameter, or `@` when the values sit at top level. `payloadJson` is
     * `-a4-`: the parameter object itself, with `_type`.
     */
    data class Call(
        val packageName: String,
        val functionId: String,
        val parameter: String,
        val payloadJson: String,
        val timeoutSeconds: Int = 30,
    )

    /** What `Execute` printed, raw. */
    data class Raw(val exitCode: Int, val text: String)

    /**
     * Stages the dex and calls. Does not throw: every failure comes back as a
     * [Raw] with text, so the caller can treat it like a platform reply.
     */
    fun run(context: Context, call: Call): Raw {
        val shim = ensureShimOnDevice(context)
        if (shim is Outcome.Failed) return Raw(-1, "ERROR: code=NO_SHIPPED_DEX msg=${shim.reason}")

        val script = script(call)
        tooLong(script)?.let { return Raw(-1, "ERROR: code=TOO_LONG msg=$it") }
        val r = ShizukuManager.runProcess(shell(script), timeoutMs = (call.timeoutSeconds + 20) * 1000L)
        val text = listOfNotNull(r.stdout, r.stderr).filter { it.isNotBlank() }.joinToString("\n")
        Log.i(TAG, "execute ${call.packageName}/${call.functionId.substringAfter('#')} — exit ${r.exitCode}")
        return Raw(r.exitCode, text)
    }

    /**
     * The script that runs on the device. It is the call that was
     * (`src/route.rs`) builds, so both paths are the same: stage the dex, call
     * as uid 2000, capture both output streams.
     */
    fun script(call: Call): String {
        require('\'' !in call.packageName && '\'' !in call.functionId) {
            "package or function ID contains a quote character"
        }
        val payload = Base64Encoder.encode(call.payloadJson.toByteArray(Charsets.UTF_8))
        return buildString {
            append("mkdir -p $REMOTE_DIR\n")
            append("su 2000 -c \"CLASSPATH=$REMOTE_SHIM app_process / Execute")
            append(" '").append(call.packageName).append("'")
            append(" '").append(call.functionId).append("'")
            append(" ").append(CALLER)
            append(" '").append(call.parameter).append("'")
            append(" b64:").append(payload)
            append("\" 2>&1\n")
        }
    }

    // ------------------------------------------------------------- staging the shim

    private sealed interface Outcome {
        object Present : Outcome
        data class Failed(val reason: String) : Outcome
    }

    /** Stages `shim.jar`. `null` = it is there, otherwise the reason. */
    internal fun ensureShim(context: Context): String? =
        (ensureShimOnDevice(context) as? Outcome.Failed)?.reason

    /**
     * Writes the dex to /data/local/tmp/af. The shortest workable way: send
     * the content through the shell as base64. Copying from the app directory
     * would only work if the caller may read app files; as uid 2000 it may
     * not, as root it may. base64 works in both cases and needs no directory
     * that might not exist.
     *
     * The checksum is compared first; if it matches, writing is skipped.
     */
    private fun ensureShimOnDevice(context: Context): Outcome {
        val dex = try {
            context.assets.open(SHIM_ASSET).use { it.readBytes() }
        } catch (e: Exception) {
            return Outcome.Failed("asset $SHIM_ASSET not readable: ${e.message}")
        }
        if (dex.isEmpty()) return Outcome.Failed("asset $SHIM_ASSET is empty")
        val expected = sha256(dex)
        val present = ShizukuManager.runProcess(
            shell("sha256sum $REMOTE_SHIM 2>/dev/null | cut -d' ' -f1"),
            timeoutMs = 15_000L,
        ).stdout.trim()
        if (present == expected) return Outcome.Present

        val b64 = Base64Encoder.encode(dex)
        Log.i(TAG, "staging dex ($REMOTE_SHIM, ${dex.size} bytes, sha256 ${expected.take(12)}…)")
        // The Shizuku path truncates a command line at 8192 characters, and a
        // truncated command ends silently with exit 0 (measured: 8168
        // characters intact, 8198 truncated). So write in chunks; the checksum
        // below stays the guard, it catches every silent abort.
        stageCommands(b64, REMOTE_DIR, REMOTE_SHIM).forEachIndexed { i, command ->
            val r = ShizukuManager.runProcess(shell(command), timeoutMs = 30_000L)
            if (r.exitCode != 0) {
                return Outcome.Failed("dex chunk ${i + 1} not written (exit ${r.exitCode})")
            }
        }
        val r = ShizukuManager.runProcess(shell(finishCommand(REMOTE_SHIM)), timeoutMs = 60_000L)
        val actual = r.stdout.trim().lines().lastOrNull()?.trim().orEmpty()
        return if (actual == expected) {
            Outcome.Present
        } else {
            Outcome.Failed("dex could not be staged (exit ${r.exitCode}, sha ${actual.take(12)}… instead of ${expected.take(12)}…)")
        }
    }

    private fun sha256(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data)
            .joinToString("") { "%02x".format(it) }
}

/** base64 without a third-party library; the same encoding `Base64.getEncoder()` produces. */
internal object Base64Encoder {
    private const val T = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun encode(data: ByteArray): String {
        val s = StringBuilder((data.size + 2) / 3 * 4)
        var i = 0
        while (i < data.size) {
            val b0 = data[i].toInt() and 0xff
            val b1 = if (i + 1 < data.size) data[i + 1].toInt() and 0xff else 0
            val b2 = if (i + 2 < data.size) data[i + 2].toInt() and 0xff else 0
            val n = (b0 shl 16) or (b1 shl 8) or b2
            s.append(T[(n ushr 18) and 63])
            s.append(T[(n ushr 12) and 63])
            s.append(if (i + 1 < data.size) T[(n ushr 6) and 63] else '=')
            s.append(if (i + 2 < data.size) T[n and 63] else '=')
            i += 3
        }
        return s.toString()
    }
}
