package com.openminis.app.appfunctions

import android.content.Context
import org.json.JSONObject
import java.util.Locale

/**
 * The measured registry, shipped as part of the app.
 *
 * On this device there is no `cmd app_function`: the listing and the
 * parameter information the platform would otherwise provide are missing.
 * This registry brings exactly that: which apps offer which functions, which
 * parameters they expect, which fields in them are required, and where each
 * piece of information comes from (`dump`, `schema`, `call-ok`,
 * `error-message`, `convention`, `assumed`).
 *
 * Rules P0–P7 check the arguments **before** the call. Reason: the platform is
 * lenient. Unknown fields are dropped silently, missing required fields are
 * replaced by defaults, and a number passed as text (`"3"`) returns an empty
 * list without an error. A guessed call then looks like a hit without a
 * result; it must not.
 *
 * The data comes from `assets/appfunctions/registry.txt`, generated from the
 * measured registry (`appfunctions-registry.xml`). The same data and the same
 * rules live in `deps/afc` (Rust), with tests and a device test.
 */
object AppFunctionsRegistry {

    const val ASSET = "appfunctions/registry.txt"

    /** Upper bound of the target apps: 50, 500, 100000 returned at most 30 hits (measured 29 Oct 2026). */
    const val MAXCOUNT_LIMIT = 30L

    data class Field(val name: String, val dataType: String, val required: Boolean, val source: String)

    data class Parameter(
        val name: String,
        val type: String,
        val level: String,
        val source: String,
        val fields: List<Field> = emptyList(),
    )

    data class Function(
        val packageName: String,
        val id: String,
        val name: String,
        val category: String,
        val status: String,
        val verified: Boolean,
        val parameters: List<Parameter> = emptyList(),
        val example: String? = null,
    ) {
        val shortName: String get() = id.substringAfterLast('#')

        /** The composite parameter: its name is passed as `-a3-`, its fields as the payload. */
        val composite: Parameter?
            get() = parameters.firstOrNull {
                it.level != TOP_LEVEL && !it.type.contains("text") && it.type.contains('.')
            }

        val flat: List<Parameter>
            get() = parameters.filter { it.level == TOP_LEVEL || it.type == "text" || it.type == LIST_OF_TEXT }
    }

    data class Finding(val rule: String, val isError: Boolean, val text: String)

    /**
     * Result of the check. `a3`/`a4` are the two arguments `Execute` expects
     * over the binder: parameter name (or `@`) and payload.
     */
    data class Check(
        val findings: List<Finding>,
        val a3: String,
        val a4: String,
        val source: String,
    ) {
        val hasErrors: Boolean get() = findings.any { it.isError }
        fun report(): String = findings.joinToString("\n") {
            (if (it.isError) "ERROR " else "WARNING ") + it.rule + ": " + it.text
        }
    }

    private const val TOP_LEVEL = "top-level"
    private const val LIST_OF_TEXT = "list-of-text"

    // ------------------------------------------------------------------- load

    /** Reads the shipped registry. Call once per process and keep the result. */
    fun load(context: Context): List<Function> =
        fromText(context.assets.open(ASSET).use { it.readBytes().toString(Charsets.UTF_8) })

    /** The same from a string; separate so the JVM tests run without a Context. */
    fun fromText(text: String): List<Function> {
        val functions = ArrayList<Function>()
        for (line in text.lineSequence()) {
            if (line.isBlank() || line.startsWith("#")) continue
            val t = line.split('\t')
            when (t.getOrNull(0)) {
                "F" -> functions += Function(
                    packageName = t.getOrElse(1) { "" },
                    id = t.getOrElse(2) { "" },
                    name = t.getOrElse(3) { "" },
                    category = t.getOrElse(4) { "" },
                    status = t.getOrElse(5) { "" },
                    verified = t.getOrElse(6) { "" } == "yes",
                )
                "P" -> functions.lastOrNull()?.let { f ->
                    val p = Parameter(
                        name = t.getOrElse(2) { "" },
                        type = t.getOrElse(3) { "" },
                        level = t.getOrElse(4) { "" },
                        source = t.getOrElse(5) { "" },
                    )
                    replaceLast(functions, f.copy(parameters = f.parameters + p))
                }
                "D" -> functions.lastOrNull()?.let { f ->
                    val parameterName = t.getOrElse(2) { "" }
                    val updated = f.parameters.map { p ->
                        if (p.name == parameterName) p.copy(
                            fields = p.fields + Field(
                                name = t.getOrElse(3) { "" },
                                dataType = t.getOrElse(4) { "" },
                                required = t.getOrElse(5) { "" } == "yes",
                                source = t.getOrElse(6) { "" },
                            )
                        ) else p
                    }
                    replaceLast(functions, f.copy(parameters = updated))
                }
                "B" -> functions.lastOrNull()?.let { f ->
                    replaceLast(functions, f.copy(example = "${t.getOrElse(4) { "" }} (args: ${t.getOrElse(2) { "" }}, source: ${t.getOrElse(3) { "" }})"))
                }
            }
        }
        return functions
    }

    private fun replaceLast(list: MutableList<Function>, replacement: Function) {
        if (list.isNotEmpty()) list[list.size - 1] = replacement
    }

    /**
     * Finds a function by full path, part of it, or short name.
     * Ambiguity is an error with suggestions; nothing is guessed.
     */
    fun find(functions: List<Function>, packageName: String?, key: String): Result<Function> {
        val k = key.lowercase(Locale.ROOT)
        val candidates = functions.filter { f ->
            (packageName == null || f.packageName == packageName) && (
                f.id.lowercase(Locale.ROOT) == k ||
                    f.id.lowercase(Locale.ROOT).endsWith(key) ||
                    f.shortName.lowercase(Locale.ROOT) == k ||
                    f.id.lowercase(Locale.ROOT).contains(k)
                )
        }
        return when {
            candidates.isEmpty() -> Result.failure(NoSuchElementException("no function matches '$key'"))
            candidates.size == 1 -> Result.success(candidates[0])
            else -> {
                val exact = candidates.filter { it.shortName.lowercase(Locale.ROOT) == k }
                if (exact.size == 1) Result.success(exact[0])
                else Result.failure(IllegalArgumentException(
                    "'$key' is ambiguous:\n" + candidates.joinToString("\n") { "  ${it.id} (${it.packageName})" }
                ))
            }
        }
    }

    // ------------------------------------------------------------------ check

    /**
     * Checks the arguments and builds the payload.
     *
     * `args` are the fields as a human would write them, without knowing
     * whether they sit at top level or inside a composite parameter. The
     * registry decides that, not the caller.
     */
    fun check(f: Function, args: JSONObject): Check {
        val findings = ArrayList<Finding>()
        val outer = LinkedHashMap<String, Any?>()
        val inner = LinkedHashMap<String, Any?>()

        if (f.parameters.isEmpty()) {
            val names = args.keys().asSequence().toList()
            if (names.isEmpty()) {
                findings += Finding("P0", false,
                    "'${f.shortName}' declares no parameters, so nothing is checked. " +
                        "Read the actual state back after the call.")
            } else {
                findings += Finding("P0", true,
                    "'${f.shortName}' declares no parameters; the given fields cannot be assigned: " +
                        names.joinToString(", "))
            }
            return Check(findings, "@", "{}", "not stated")
        }

        val composite = f.composite
        val flat = f.flat
        for (name in args.keys()) {
            val value = args.get(name)
            val fitsOuter = flat.any { it.name == name }
            val field = composite?.fields?.firstOrNull { it.name == name }
            when {
                fitsOuter && field != null -> findings += Finding("P2", true,
                    "'$name' exists as a top-level parameter AND as a field in " +
                        "'${composite?.name}'. The registry lists it twice; the call is not guessed.")
                fitsOuter -> {
                    val p = flat.first { it.name == name }
                    if (p.type.isNotEmpty() && !matches(value, p.type)) {
                        findings += Finding("P3", true,
                            "'$name' is declared as ${p.type}, but ${kindOf(value)} was given ($value). " +
                                "The target app silently accepts the wrong type and then returns an empty list.")
                    }
                    outer[name] = value
                }
                field != null -> {
                    if (field.dataType.isNotEmpty() && !matches(value, field.dataType)) {
                        findings += Finding("P3", true,
                            "'$name' is declared as ${field.dataType}, but ${kindOf(value)} was given ($value). " +
                                "The target app silently accepts the wrong type and then returns an empty list.")
                    }
                    if (value is String && value.isEmpty() && f.name.equals("findPersons", true)) {
                        findings += Finding("P5", true,
                            "'$name' is empty; measured at this place: error 1001 \"is null or empty\"")
                    }
                    if (name == "maxCount") findings += maxCount(value)
                    inner[name] = value
                }
                else -> {
                    val possible = (flat.map { it.name } + (composite?.fields?.map { it.name } ?: emptyList())).sorted()
                    findings += Finding("P2", true,
                        "'${f.shortName}' does not know '$name'. The platform would drop it silently. " +
                            "Possible: ${if (possible.isEmpty()) "nothing known" else possible.joinToString(", ")}")
                }
            }
        }

        // A top-level parameter with which a call already succeeded: if it is missing,
        // that is a warning (like P1 in `deps/afc/src/validate.rs`), not an error.
        for (p in flat) {
            if (!outer.containsKey(p.name) && p.source == "call-ok") {
                findings += Finding("P1", false,
                    "Parameter '${p.name}' is missing; this function was measured being called with it.")
            }
        }

        for (field in composite?.fields.orEmpty().filter { it.required }) {
            if (!inner.containsKey(field.name)) {
                findings += Finding("P1", true, "Required field '${field.name}' (${field.dataType}) is missing. Origin of the information: ${field.source}")
            }
        }

        val (a3, a4) = if (composite != null) {
            val obj = JSONObject()
            obj.put("_type", composite.type)
            for ((k, v) in inner) obj.put(k, v)
            composite.name to obj.toString()
        } else {
            val obj = JSONObject()
            for ((k, v) in outer) obj.put(k, v)
            "@" to obj.toString()
        }
        return Check(findings, a3, a4, if (composite != null) composite.source else TOP_LEVEL)
    }

    private fun maxCount(value: Any?): List<Finding> {
        val n = (value as? Number)?.toLong() ?: return emptyList()
        return when {
            n > MAXCOUNT_LIMIT -> listOf(Finding("P4", false,
                "maxCount=$n is above the limit $MAXCOUNT_LIMIT; the target apps return at most " +
                    "$MAXCOUNT_LIMIT. The value is set to $MAXCOUNT_LIMIT."))
            n == 0L -> listOf(Finding("P4", false,
                "maxCount=0 returns an empty list, so a hit cannot be proven this way."))
            n < 0 -> listOf(Finding("P4", false,
                "maxCount=-1 means 'all' for contacts, but is error 1001 for photos, bookmarks and files."))
            else -> emptyList()
        }
    }

    /** Clamps `maxCount` in the finished payload to the measured limit. */
    fun apply(a4: String): String = runCatching {
        val o = JSONObject(a4)
        val n = o.optLong("maxCount", Long.MIN_VALUE)
        if (n != Long.MIN_VALUE && n > MAXCOUNT_LIMIT) {
            o.put("maxCount", MAXCOUNT_LIMIT)
            o.toString()
        } else a4
    }.getOrDefault(a4)

    private fun matches(value: Any?, dataType: String): Boolean = when (dataType) {
        "long" -> value is Long || value is Int
        "double" -> value is Double || value is Float || value is Long || value is Int
        "boolean" -> value is Boolean
        "string" -> value is String
        LIST_OF_TEXT -> value is org.json.JSONArray && (0 until value.length()).all { value.get(it) is String }
        "document" -> value is JSONObject
        else -> true // type unknown to the registry: do not check against it
    }

    private fun kindOf(value: Any?): String = when (value) {
        null -> "null"
        is Boolean -> "boolean"
        is Long, is Int -> "integer"
        is Double, is Float -> "decimal number"
        is String -> "text"
        is org.json.JSONArray -> "list"
        is JSONObject -> "object"
        else -> value.javaClass.simpleName
    }

    // ---------------------------------------------------- prepared for the core

    /** As an entry for `list`, without asking the device. */
    fun asEntry(f: Function): AppFunctionsCore.FunctionEntry = AppFunctionsCore.FunctionEntry(
        packageName = f.packageName,
        id = f.id,
        schemaName = f.shortName,
        category = f.category.ifEmpty { null },
        scope = null,
        enabledByDefault = f.status != "off",
        description = f.example ?: "",
        parameters = f.parameters.map { p ->
            AppFunctionsCore.Param(p.name, p.type, p.fields.any { it.required }, p.source)
        },
        returns = null,
    )
}
