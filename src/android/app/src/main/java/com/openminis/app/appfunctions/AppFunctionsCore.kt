package com.openminis.app.appfunctions

import org.json.JSONArray
import org.json.JSONObject
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * [T-appfunctions-caller] Pure core of the `app_functions` tool: everything
 * that does not need an Android Context, so it runs in JVM unit tests.
 *
 * - [parseList]: `cmd app_function list-app-functions` JSON -> [FunctionEntry]
 * - [parseSchemaXml]: a target app's schema file (assets, name contains
 *   "app_function", ends in .xml) -> [FunctionSchema]
 *   (two shapes exist in the wild: full Google schemas with typed parameters,
 *   and minimal ones that only carry schema_name/schema_category)
 * - [resolve]: short name -> full function id, unique or a named error
 * - [isReadOnly]: verb classification by NAME (a heuristic, documented as such)
 * - [parseExecuteOutput]: `execute-app-function` stdout/stderr -> [ExecOutcome]
 */
object AppFunctionsCore {

    data class FunctionEntry(
        val packageName: String,
        val id: String,
        val schemaName: String?,
        val category: String?,
        val scope: String?,
        val enabledByDefault: Boolean?,
        val description: String = "",
        /** Declared parameters; null = the app publishes none (Samsung apps). */
        val parameters: List<Param>? = null,
        val returns: String? = null,
    ) {
        /** Part after '#', e.g. `AlarmCrossAppActions_findAlarms`. */
        val shortName: String get() = id.substringAfter('#')
    }

    data class Param(val name: String, val type: String, val required: Boolean, val description: String)

    data class FunctionSchema(
        val id: String?,
        val description: String,
        val scope: String?,
        val schemaName: String?,
        val category: String?,
        val parameters: List<Param>,
        val returns: String,
    )

    data class SchemaDocument(val functions: List<FunctionSchema>, val types: Map<String, List<Param>>)

    /** The discovery dump: functions plus, per package, the declared data types (simple name -> fields). */
    data class ListDocument(val entries: List<FunctionEntry>, val types: Map<String, Map<String, List<Param>>>)

    sealed class ExecOutcome {
        data class Success(val json: String) : ExecOutcome()
        /** The target function answered with an AppFunctionException. */
        data class FunctionError(val message: String) : ExecOutcome()
        /** rc 0 with nothing on stdout: unknown result, never reported as success. */
        object Empty : ExecOutcome()
    }

    class ResolveException(message: String) : Exception(message)

    /**
     * Verbs that only read. Matched against the last `_`-separated part of the
     * short name. A name is not proof of no side effects, so callers state this
     * classification in the tool description. `show…` opens the target app's UI
     * and is therefore NOT read-only.
     */
    val READ_VERBS = listOf("find", "get", "search", "list", "is", "query", "read", "lookup", "fetch")

    /** Type codes of androidx.appfunctions AppFunctionDataTypeMetadata.TYPE_*. */
    private val TYPE_NAMES = mapOf(
        0 to "unit", 1 to "boolean", 2 to "bytes", 3 to "object", 4 to "double", 5 to "float",
        6 to "long", 7 to "int", 8 to "string", 10 to "array", 11 to "ref", 12 to "allOf",
        13 to "parcelable", 14 to "oneOf",
    )

    fun verbOf(functionId: String): String {
        val last = functionId.substringAfter('#').substringAfterLast('_')
        return Regex("^[a-z]+").find(last)?.value ?: ""
    }

    fun isReadOnly(functionId: String): Boolean = verbOf(functionId) in READ_VERBS

    /**
     * Ask the user before this call? Only when the user turned confirmation
     * on AND the function is not read-only. Off by default: the tool switch
     * itself is the consent (D6).
     */
    fun needsConfirmation(functionId: String, confirmWrites: Boolean): Boolean =
        confirmWrites && !isReadOnly(functionId)

    // ------------------------------------------------------------------ list

    /** Values in the dump are wrapped in single-element arrays. */
    private fun first(o: JSONObject, key: String): Any? {
        val v = o.opt(key) ?: return null
        return if (v is JSONArray) (if (v.length() > 0) v.opt(0) else null) else v
    }

    private fun firstObj(o: JSONObject?, key: String): JSONObject? = o?.let { first(it, key) as? JSONObject }
    private fun firstStr(o: JSONObject?, key: String): String? = o?.let { first(it, key) as? String }
    private fun objects(o: JSONObject?, key: String): List<JSONObject> =
        o?.optJSONArray(key)?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } } ?: emptyList()

    /** Type of a dump `dataTypeMetadata` object, same notation as [typeOf] for XML. */
    private fun jsonTypeOf(dt: JSONObject?): String {
        if (dt == null) return "?"
        firstStr(dt, "dataTypeReference")?.let { return it.substringAfterLast('.') }
        val code = (first(dt, "type") as? Number)?.toInt()
        val name = code?.let { TYPE_NAMES[it] ?: "type$it" } ?: "?"
        if (name == "array") return "array<${jsonTypeOf(firstObj(dt, "itemType"))}>"
        return name
    }

    private fun jsonParam(p: JSONObject, required: Boolean?): Param {
        val dt = firstObj(p, "dataTypeMetadata")
        return Param(
            firstStr(p, "name") ?: "?", jsonTypeOf(dt),
            required ?: (first(p, "isRequired") as? Boolean ?: false),
            firstStr(p, "description") ?: firstStr(dt, "description").orEmpty(),
        )
    }

    fun parseList(json: String): List<FunctionEntry> = parseListDocument(json).entries

    /**
     * The whole dump: per function its declared `parameters`, `description` and
     * `response`; per package the data types of its component document. A
     * function without a `parameters` key publishes none (null), which is not
     * the same as taking none (empty list).
     */
    fun parseListDocument(json: String): ListDocument {
        require(json.isNotBlank()) { "list-app-functions returned nothing" }
        val root = JSONObject(json)
        val out = mutableListOf<FunctionEntry>()
        val types = linkedMapOf<String, Map<String, List<Param>>>()
        for (pkg in root.keys()) {
            val entries = root.optJSONArray(pkg) ?: continue
            val pkgTypes = linkedMapOf<String, List<Param>>()
            for (i in 0 until entries.length()) {
                val e = entries.optJSONObject(i) ?: continue
                val compKey = e.keys().asSequence().firstOrNull { it.startsWith("AppFunctionComponentMetadataDocument") }
                if (compKey != null) {
                    for (dts in objects(e.optJSONObject(compKey), "dataTypes")) {
                        val name = firstStr(dts, "name") ?: continue
                        val dt = firstObj(dts, "dataTypeMetadata") ?: continue
                        val req = dt.optJSONArray("required")
                            ?.let { a -> (0 until a.length()).map { a.optString(it) } }?.toSet() ?: emptySet()
                        pkgTypes[name.substringAfterLast('.')] = objects(dt, "properties").map { p ->
                            jsonParam(p, (firstStr(p, "name") ?: "") in req)
                        }
                    }
                }
                val staticKey = e.keys().asSequence().firstOrNull { it.startsWith("AppFunctionStaticMetadata") }
                    ?: continue
                val st = e.optJSONObject(staticKey) ?: continue
                val id = first(st, "functionId") as? String ?: continue
                out += FunctionEntry(
                    packageName = pkg,
                    id = id,
                    schemaName = first(st, "schemaName") as? String,
                    category = first(st, "schemaCategory") as? String,
                    scope = first(st, "scope") as? String,
                    enabledByDefault = first(st, "enabledByDefault") as? Boolean,
                    description = firstStr(st, "description").orEmpty(),
                    parameters = if (st.has("parameters")) objects(st, "parameters").map { jsonParam(it, null) } else null,
                    returns = firstObj(firstObj(st, "response"), "valueType")?.let { jsonTypeOf(it) },
                )
            }
            if (pkgTypes.isNotEmpty()) types[pkg] = pkgTypes
        }
        return ListDocument(out, types)
    }

    /**
     * Schema answer from the dump for [packageName] (optionally one [function]).
     * Null when none of the matching functions publishes parameters: then only
     * the app's own XML can say more.
     */
    fun schemaFromList(doc: ListDocument, packageName: String, function: String?): SchemaDocument? {
        val hits = doc.entries.filter { it.packageName == packageName }.filter { e ->
            function.isNullOrEmpty() || e.id == function || e.shortName.endsWith(function)
        }
        if (hits.none { it.parameters != null }) return null
        return SchemaDocument(
            hits.map { e ->
                FunctionSchema(e.id, e.description, e.scope, e.schemaName, e.category,
                    e.parameters ?: emptyList(), e.returns ?: "?")
            },
            doc.types[packageName].orEmpty(),
        )
    }

    // --------------------------------------------------------------- validate

    /**
     * Checks [arguments] against what [entry] declares, before anything runs or
     * is shown for confirmation. Unknown fields and missing required fields are
     * errors: the platform drops unknown fields silently and fills defaults (a
     * guessed `hour` became 00:00 on the device). Returns the problems; empty =
     * valid. [Entry.parameters] null = nothing published, nothing to check.
     */
    fun validate(entry: FunctionEntry, types: Map<String, List<Param>>, arguments: JSONObject): List<String> {
        val params = entry.parameters ?: return emptyList()
        val out = mutableListOf<String>()
        checkObject("", params, arguments, types, out, 0)
        return out
    }

    private fun checkObject(
        path: String, params: List<Param>, obj: JSONObject,
        types: Map<String, List<Param>>, out: MutableList<String>, depth: Int,
    ) {
        val known = params.map { it.name }.toSet()
        for (k in obj.keys()) if (k !in known) {
            out += "unknown field '$path$k' (allowed: ${params.joinToString(", ") { it.name }.ifEmpty { "none" }})"
        }
        for (p in params) {
            if (!obj.has(p.name) || obj.isNull(p.name)) {
                if (p.required) out += "missing required field '$path${p.name}' (${p.type})"
                continue
            }
            if (depth >= 6) continue
            val v = obj.opt(p.name)
            val sub = types[p.type]
            if (sub != null) {
                if (v is JSONObject) checkObject("$path${p.name}.", sub, v, types, out, depth + 1)
                else out += "field '$path${p.name}' must be an object of type ${p.type}"
                continue
            }
            val item = p.type.removePrefix("array<").removeSuffix(">")
            val itemFields = if (p.type.startsWith("array<")) types[item] else null
            if (itemFields != null) {
                if (v !is JSONArray) { out += "field '$path${p.name}' must be an array of $item"; continue }
                for (i in 0 until v.length()) {
                    val el = v.opt(i)
                    if (el is JSONObject) checkObject("$path${p.name}[$i].", itemFields, el, types, out, depth + 1)
                    else out += "field '$path${p.name}[$i]' must be an object of type $item"
                }
            }
        }
    }

    /** Short name (unique within the package) or full id -> full id. */
    fun resolve(entries: List<FunctionEntry>, packageName: String, function: String): String {
        if ('#' in function) return function
        val inPkg = entries.filter { it.packageName == packageName }
        if (inPkg.isEmpty()) throw ResolveException("package $packageName exposes no AppFunctions")
        val hits = inPkg.filter { it.shortName == function || it.shortName.endsWith("_$function") }.map { it.id }
        return when (hits.size) {
            1 -> hits[0]
            0 -> throw ResolveException(
                "no function '$function' in $packageName; available: " +
                    inPkg.map { it.shortName }.sorted().joinToString(", "),
            )
            else -> throw ResolveException("short name '$function' is ambiguous: ${hits.joinToString(", ")}")
        }
    }

    // ---------------------------------------------------------------- schema

    private fun Element.child(name: String): Element? {
        val nl = childNodes
        for (i in 0 until nl.length) {
            val n = nl.item(i)
            if (n.nodeType == Node.ELEMENT_NODE && n.nodeName == name) return n as Element
        }
        return null
    }

    private fun Element.children(name: String): List<Element> {
        val nl = childNodes
        return (0 until nl.length).map { nl.item(it) }
            .filter { it.nodeType == Node.ELEMENT_NODE && it.nodeName == name }
            .map { it as Element }
    }

    private fun Element.text(name: String): String? = child(name)?.textContent?.trim()

    private fun typeOf(dt: Element?): String {
        if (dt == null) return "?"
        dt.text("dataTypeReference")?.let { return it.substringAfterLast('.') }
        val code = dt.text("type")?.toIntOrNull()
        val name = code?.let { TYPE_NAMES[it] ?: "type$it" } ?: "?"
        if (name == "array") return "array<${typeOf(dt.child("itemType"))}>"
        return name
    }

    private fun allElements(root: Element, name: String): List<Element> {
        val nl = root.getElementsByTagName(name)
        return (0 until nl.length).map { nl.item(it) as Element }
    }

    fun parseSchemaXml(xml: ByteArray): SchemaDocument {
        // No DTDs / external entities: the file comes from another app. Refused
        // here, before parsing, because Android's parser does not support the
        // Xerces feature `disallow-doctype-decl` (setFeature throws on the device).
        // Zero bytes are dropped first so a UTF-16 file cannot hide the marker.
        val ascii = String(xml.filter { it != 0.toByte() }.toByteArray(), Charsets.ISO_8859_1)
        require(!ascii.contains("<!DOCTYPE", ignoreCase = true)) { "schema file declares a DOCTYPE; refused" }
        val f = DocumentBuilderFactory.newInstance().apply {
            isExpandEntityReferences = false
            isNamespaceAware = false
        }
        val root = f.newDocumentBuilder().parse(ByteArrayInputStream(xml)).documentElement

        val types = linkedMapOf<String, List<Param>>()
        for (dts in allElements(root, "dataTypes")) {
            val name = dts.text("name") ?: continue
            val dt = dts.child("dataTypeMetadata") ?: continue
            val required = dt.children("required").map { it.textContent.trim() }.toSet()
            types[name.substringAfterLast('.')] = dt.children("properties").map { p ->
                val pn = p.text("name") ?: "?"
                Param(pn, typeOf(p.child("dataTypeMetadata")), pn in required,
                    p.child("dataTypeMetadata")?.text("description").orEmpty())
            }
        }

        val functions = allElements(root, "appfunction").map { af ->
            FunctionSchema(
                id = af.text("functionId") ?: af.text("function_id") ?: af.text("id"),
                description = af.text("description").orEmpty(),
                scope = af.text("scope"),
                schemaName = af.text("schema_name"),
                category = af.text("schema_category"),
                parameters = af.children("parameters").map { p ->
                    Param(p.text("name") ?: "?", typeOf(p.child("dataTypeMetadata")),
                        p.text("isRequired").equals("true", ignoreCase = true),
                        p.text("description").orEmpty())
                },
                returns = typeOf(af.child("response")?.child("valueType")),
            )
        }
        return SchemaDocument(functions, types)
    }

    // --------------------------------------------------------------- execute

    /** Builds the argv for the shell path. Never `sh -c`: args stay separate. */
    fun executeArgv(packageName: String, functionId: String, parametersJson: String, timeoutSeconds: Int): Array<String> {
        JSONObject(parametersJson) // validate before anything leaves the app
        return arrayOf(
            "cmd", "app_function", "execute-app-function",
            "--package", packageName,
            "--function", functionId,
            "--parameters", parametersJson,
            "--timeout-duration", timeoutSeconds.coerceIn(1, 120).toString(),
        )
    }

    private val EXCEPTION_RE = Regex("AppFunctionException: (.*?)(?: See logcat.*)?$", RegexOption.DOT_MATCHES_ALL)

    fun parseExecuteOutput(exitCode: Int, stdout: String, stderr: String): ExecOutcome {
        val text = (stdout + stderr).trim()
        if (exitCode != 0 || text.startsWith("Error")) {
            val m = EXCEPTION_RE.find(text)
            return ExecOutcome.FunctionError((m?.groupValues?.get(1) ?: text).trim().take(500))
        }
        if (text.isEmpty()) return ExecOutcome.Empty
        return ExecOutcome.Success(text)
    }

    // ---------------------------------------------------------------- output

    /** Compact listing for the model: per package, short names with r/w. */
    fun formatList(entries: List<FunctionEntry>, query: String?): String {
        val q = query?.trim()?.lowercase().orEmpty()
        val filtered = if (q.isEmpty()) entries else entries.filter {
            it.packageName.lowercase().contains(q) || it.shortName.lowercase().contains(q) ||
                (it.category ?: "").lowercase().contains(q)
        }
        if (filtered.isEmpty()) return if (q.isEmpty()) "No AppFunctions registered on this device."
        else "No AppFunctions match '$query' (${entries.size} in total)."
        val sb = StringBuilder()
        filtered.groupBy { it.packageName }.toSortedMap().forEach { (pkg, fs) ->
            sb.append(pkg).append(" (").append(fs.size).append(")\n")
            fs.sortedBy { it.shortName }.forEach { f ->
                sb.append("  ").append(if (isReadOnly(f.id)) "read " else "write").append("  ").append(f.shortName)
                sb.append(when (val ps = f.parameters) {
                    null -> "(? unpublished)"
                    else -> ps.joinToString(", ", "(", ")") { it.name + ": " + it.type + if (it.required) "" else "?" }
                })
                f.category?.let { sb.append("  [").append(it).append(']') }
                if (f.enabledByDefault == false) sb.append("  (disabled)")
                sb.append('\n')
            }
        }
        sb.append("Total ${filtered.size} in ${filtered.map { it.packageName }.toSet().size} apps. " +
            "write = changes something. (…) = declared parameters, '?' after a type = optional; " +
            "'(? unpublished)' = the app declares none, use 'schema' and read errors.")
        return sb.toString()
    }

    fun schemaToJson(doc: SchemaDocument): JSONObject {
        fun params(ps: List<Param>) = JSONArray().apply {
            ps.forEach { p ->
                put(JSONObject().put("name", p.name).put("type", p.type).put("required", p.required)
                    .apply { if (p.description.isNotEmpty()) put("description", p.description) })
            }
        }
        return JSONObject().apply {
            put("functions", JSONArray().apply {
                doc.functions.forEach { f ->
                    put(JSONObject().apply {
                        put("id", f.id)
                        if (f.description.isNotEmpty()) put("description", f.description)
                        f.scope?.let { put("scope", it) }
                        f.schemaName?.let { put("schema", it) }
                        f.category?.let { put("category", it) }
                        put("parameters", params(f.parameters))
                        put("returns", f.returns)
                    })
                }
            })
            if (doc.types.isNotEmpty()) put("types", JSONObject().apply {
                doc.types.forEach { (k, v) -> put(k, params(v)) }
            })
        }
    }
}
