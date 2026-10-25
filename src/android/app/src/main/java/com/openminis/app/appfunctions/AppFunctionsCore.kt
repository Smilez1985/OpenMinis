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

    // ------------------------------------------------------------------ list

    /** Values in the dump are wrapped in single-element arrays. */
    private fun first(o: JSONObject, key: String): Any? {
        val v = o.opt(key) ?: return null
        return if (v is JSONArray) (if (v.length() > 0) v.opt(0) else null) else v
    }

    fun parseList(json: String): List<FunctionEntry> {
        require(json.isNotBlank()) { "list-app-functions returned nothing" }
        val root = JSONObject(json)
        val out = mutableListOf<FunctionEntry>()
        for (pkg in root.keys()) {
            val entries = root.optJSONArray(pkg) ?: continue
            for (i in 0 until entries.length()) {
                val e = entries.optJSONObject(i) ?: continue
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
                )
            }
        }
        return out
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
        val f = DocumentBuilderFactory.newInstance().apply {
            // No DTDs / external entities: the file comes from another app.
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
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
                f.category?.let { sb.append("  [").append(it).append(']') }
                if (f.enabledByDefault == false) sb.append("  (disabled)")
                sb.append('\n')
            }
        }
        sb.append("Total ${filtered.size} in ${filtered.map { it.packageName }.toSet().size} apps. " +
            "write = needs the user's approval.")
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
