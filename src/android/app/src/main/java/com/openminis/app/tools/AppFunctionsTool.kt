package com.openminis.app.tools

import android.content.Context
import com.openminis.app.appfunctions.AppFunctionsCore
import com.openminis.app.appfunctions.AppFunctionsManager
import com.openminis.app.appfunctions.AppFunctionsSettings
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * [T-appfunctions-caller] Native tool `app_functions`: lets the model call the
 * Android AppFunctions other installed apps expose ("Android MCP"), without
 * screen automation.
 *
 * ONE tool with an `action` instead of one tool per function: OpenMinis has no
 * dynamic tool registration, and a device lists 100+ functions.
 *
 * Safety: the tool switch (off by default) is the consent. When the user also
 * turned on [AppFunctionsSettings.confirmWrites], every function that is not
 * read-only (see [AppFunctionsCore.needsConfirmation]) runs only after
 * [confirm] returns true. The caller supplies [confirm] so the dialog stays in
 * the UI layer.
 */
object AppFunctionsTool {
    const val NAME = "app_functions"
    private const val DEFAULT_TIMEOUT_S = 30
    private const val MAX_OUTPUT = 12_000

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Call functions that other installed Android apps expose through Android AppFunctions " +
            "(on-device 'Android MCP'): e.g. alarms, timers and stopwatch in the clock app, reminders, " +
            "calendar events, contacts, call log, gallery, browser bookmarks, device settings state. " +
            "Prefer this over UI automation for these apps. Requires Android 16+.\n\n" +
            "Steps: 'list' to discover (optionally filter with 'query'), 'schema' with 'package' (and " +
            "optionally 'function') to read parameters, then 'execute' with 'package', 'function' and " +
            "'arguments'. 'function' may be the short name after '#' (e.g. 'findAlarms') when unique.\n\n" +
            "Functions other than reads (find/get/search/list/is/query/read/lookup/fetch) change " +
            "something; if the user enabled confirmation in settings they ask first, otherwise they run " +
            "directly — so get the arguments right before calling. The classification is by name only. " +
            "Some apps publish no parameter schema: then call once with '{}' and read the error, " +
            "it names the missing field. A result with no content is reported as empty, not as success.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of this call, shown to the user. Use the same language as the user."),
            "action" to AgentToolParam("string", "list | schema | execute", enumValues = listOf("list", "schema", "execute")),
            "package" to AgentToolParam("string", "Target app package name (schema, execute)."),
            "function" to AgentToolParam("string", "Function id, or its unique short name (schema: optional filter; execute: required)."),
            "arguments" to AgentToolParam("string", "execute: the function's parameters as a JSON object string, e.g. {\"findAlarmsParams\":{}}. Default {}."),
            "query" to AgentToolParam("string", "list: optional filter on package, function name or category (e.g. 'clock', 'calendar', 'find')."),
            "timeout_s" to AgentToolParam("integer", "execute: seconds to wait for the target app (default 30, max 120)."),
        ),
        required = listOf("tool_title", "action"),
        propertyOrdering = listOf("tool_title", "action", "package", "function", "arguments", "query", "timeout_s"),
    )

    /**
     * @param confirm asked for every non-read-only execute while
     *   [AppFunctionsSettings.confirmWrites] is on; receives package,
     *   full function id and the arguments JSON. Return true to proceed.
     */
    suspend fun execute(
        context: Context,
        argsJson: String,
        confirm: suspend (packageName: String, functionId: String, arguments: String) -> Boolean,
    ): ToolExecutionResult = withContext(Dispatchers.IO) {
        val args = try { JSONObject(argsJson) } catch (e: Exception) {
            return@withContext fail("arguments are not valid JSON: ${e.message}")
        }
        val title = args.optString("tool_title", NAME)
        val decision = AppFunctionsManager.decidePath(context)
        when (args.optString("action", "").trim().lowercase()) {
            "list" -> {
                if (decision.path == AppFunctionsManager.Path.NONE) return@withContext fail("unavailable: ${decision.reason}", title)
                AppFunctionsManager.list().fold(
                    onSuccess = { ok(AppFunctionsCore.formatList(it, args.optString("query").ifBlank { null }) + "\n(path: ${decision.path.name.lowercase()})", title) },
                    onFailure = { fail("list failed: ${it.message}", title) },
                )
            }
            "schema" -> {
                val pkg = args.optString("package").trim()
                if (pkg.isEmpty()) return@withContext fail("schema needs 'package'", title)
                // Declared parameters first: the discovery dump carries them.
                val listDoc = AppFunctionsManager.listDocument().getOrNull()
                AppFunctionsCore.schemaFromList(listDoc ?: AppFunctionsCore.ListDocument(emptyList(), emptyMap()),
                    pkg, args.optString("function").trim().ifEmpty { null })?.let {
                    return@withContext ok(AppFunctionsCore.schemaToJson(it).toString(1), title)
                }
                AppFunctionsManager.schema(context, pkg).fold(
                    onSuccess = { doc ->
                        val fn = args.optString("function").trim()
                        val filtered = if (fn.isEmpty()) doc else doc.copy(functions = doc.functions.filter {
                            val id = it.id.orEmpty()
                            id == fn || id.substringAfter('#').endsWith(fn)
                        })
                        if (fn.isNotEmpty() && filtered.functions.isEmpty()) fail("function '$fn' not in the schema of $pkg", title)
                        else ok(AppFunctionsCore.schemaToJson(filtered).toString(1) +
                            "\n(note: $pkg declares no parameters for these functions; the names above come from " +
                            "its package only. Call once with '{}' and read the error, it names the missing field.)", title)
                    },
                    onFailure = { fail("schema failed: ${it.message}", title) },
                )
            }
            "execute" -> runExecute(context, args, title, confirm)
            else -> fail("unknown action '${args.optString("action")}'; use list, schema or execute", title)
        }
    }

    private suspend fun runExecute(
        context: Context,
        args: JSONObject,
        title: String,
        confirm: suspend (String, String, String) -> Boolean,
    ): ToolExecutionResult {
        val pkg = args.optString("package").trim()
        val fn = args.optString("function").trim()
        if (pkg.isEmpty() || fn.isEmpty()) return fail("execute needs 'package' and 'function'", title)
        // Accept an object as well as a JSON string: some providers send either.
        val arguments = when (val a = args.opt("arguments")) {
            null, JSONObject.NULL -> "{}"
            is JSONObject -> a.toString()
            else -> a.toString().ifBlank { "{}" }
        }
        try { JSONObject(arguments) } catch (e: Exception) {
            return fail("'arguments' is not a JSON object: ${e.message}", title)
        }
        val decision = AppFunctionsManager.decidePath(context)
        if (decision.path == AppFunctionsManager.Path.NONE) return fail("unavailable: ${decision.reason}", title)

        // The list resolves short names AND carries the declared parameters to
        // validate against, so it is read for every execute.
        val doc = AppFunctionsManager.listDocument().getOrElse { return fail("cannot read the function list: ${it.message}", title) }
        val fid = try { AppFunctionsCore.resolve(doc.entries, pkg, fn) } catch (e: AppFunctionsCore.ResolveException) {
            return fail(e.message ?: "unresolved", title)
        }
        val entry = doc.entries.firstOrNull { it.packageName == pkg && it.id == fid }
            ?: return fail("function $fid is not registered for $pkg", title)
        val problems = AppFunctionsCore.validate(entry, doc.types[pkg].orEmpty(), JSONObject(arguments))
        if (problems.isNotEmpty()) {
            return fail("INVALID_ARGUMENTS for ${entry.shortName}: ${problems.joinToString("; ")}. Nothing was executed; " +
                "use 'schema' for the declared fields.", title)
        }
        val unvalidated = entry.parameters == null
        val needsConfirmation = AppFunctionsCore.needsConfirmation(fid, AppFunctionsSettings.confirmWrites(context))
        if (needsConfirmation && !confirm(pkg, fid, arguments)) {
            return fail("NEEDS_CONFIRMATION: the user did not approve ${fid.substringAfter('#')} " +
                "(verb '${AppFunctionsCore.verbOf(fid)}' changes something). Do not retry without asking.", title)
        }
        // Reaching this point with the gate on means the user approved it --
        // the result has to say so, see [AppFunctionsCore.successNotes].
        val confirmed = needsConfirmation
        val timeout = args.optInt("timeout_s", DEFAULT_TIMEOUT_S).coerceIn(1, 120)
        return when (val r = AppFunctionsManager.execute(context, pkg, fid, arguments, timeout)) {
            is AppFunctionsCore.ExecOutcome.Success -> ok(
                buildString {
                    append(r.json)
                    for (note in AppFunctionsCore.successNotes(pkg, confirmed, !unvalidated)) {
                        append("\n(note: ").append(note).append(')')
                    }
                },
                title)
            is AppFunctionsCore.ExecOutcome.FunctionError -> fail("${fid.substringAfter('#')}: ${r.message}", title)
            AppFunctionsCore.ExecOutcome.Empty -> fail("EMPTY_RESULT: ${fid.substringAfter('#')} returned nothing; the outcome is unknown, do not treat it as done.", title)
        }
    }

    private fun ok(text: String, title: String = NAME) =
        ToolExecutionResult(if (text.length > MAX_OUTPUT) text.take(MAX_OUTPUT) + "\n… (truncated)" else text, true, toolTitle = title)

    private fun fail(text: String, title: String = NAME) = ToolExecutionResult(text, false, toolTitle = title)
}
