package com.openminis.app.appfunctions

/**
 * Reads the reply of the binder path and explains error codes.
 *
 * `Execute` prints lines: `SUCCESS: …`, `JSON: …`, `ERROR: code=… cat=… msg=…`,
 * and finally `DONE` or `TIMEOUT`. `deps/afc` evaluates the same lines in
 * `src/errors.rs`; the table here is its counterpart.
 *
 * Two platform quirks that get a name here:
 *  - **SUCCESS without content** is not a hit. Measured: `findCallRecords`
 *    returns "SUCCESS" with an empty list although nothing was found. That is
 *    called `EMPTY_RESULT`, not success.
 *  - **1001 does not name the field.** The message is often just "null" or
 *    "Invalid Argument"; that is why the arguments are checked against the
 *    registry *before* the call (see `AppFunctionsRegistry`).
 */
object AppFunctionsReply {

    enum class Kind { SUCCESS, EMPTY, ERROR, TIMEOUT, UNCLEAR }

    data class Reply(
        val kind: Kind,
        val json: String?,
        val code: String?,
        val category: String?,
        val message: String?,
        val rawLines: List<String>,
    ) {
        val isSuccess: Boolean get() = kind == Kind.SUCCESS

        /** One line a human can read; matches `afc`. */
        fun report(): String = when (kind) {
            Kind.SUCCESS -> "SUCCESS: ${json ?: ""}"
            Kind.EMPTY -> "EMPTY_RESULT: SUCCESS, but no content. That does not mean " +
                "'nothing there', it means 'not proven' - check the parameters against the registry."
            Kind.TIMEOUT -> "TIMEOUT: the target app did not answer."
            Kind.ERROR -> buildString {
                append("ERROR ").append(code ?: "?").append(": ").append(message.orEmpty())
                category?.let { append(" (cat=").append(it).append(')') }
                explanation(code)?.let { append("\n  ").append(it) }
            }
            Kind.UNCLEAR -> "UNCLEAR: the reply could not be interpreted:\n  " + rawLines.joinToString("\n  ")
        }
    }

    fun interpret(raw: String): Reply {
        var kind = Kind.UNCLEAR
        var json: String? = null
        var code: String? = null
        var category: String? = null
        var message: String? = null
        val lines = ArrayList<String>()

        for (line in raw.lineSequence()) {
            val l = line.trim()
            if (l.isEmpty()) continue
            lines.add(l)
            when {
                l.startsWith("JSON: ") -> {
                    json = l.removePrefix("JSON: ")
                    kind = Kind.SUCCESS
                }
                l.startsWith("ERROR:") -> {
                    kind = Kind.ERROR
                    for (part in l.removePrefix("ERROR:").trim().split(" ")) {
                        when {
                            part.startsWith("code=") -> code = part.removePrefix("code=")
                            part.startsWith("cat=") -> category = part.removePrefix("cat=")
                        }
                    }
                    val i = l.indexOf("msg=")
                    if (i >= 0) message = l.substring(i + 4).trim()
                }
                l.startsWith("TIMEOUT") -> kind = Kind.TIMEOUT
                l.startsWith("SUCCESS:") -> {
                    val rest = l.removePrefix("SUCCESS:").trim()
                    // Same as `deps/afc/src/errors.rs`: "SUCCESS: null" or empty is EMPTY
                    // whatever came before; otherwise SUCCESS if nothing is settled yet.
                    if (rest == "null" || rest.isEmpty()) kind = Kind.EMPTY
                    else if (kind == Kind.UNCLEAR) kind = Kind.SUCCESS
                }
            }
        }

        // SUCCESS with an empty return list is not a hit.
        if (kind == Kind.SUCCESS) {
            val t = (json ?: "").filterNot { it.isWhitespace() }
            if (t == "{}" || t == "[]" || t == "null" ||
                t.contains("\"androidAppfunctionsReturnValue\":[]")
            ) {
                kind = Kind.EMPTY
            }
        }
        return Reply(kind, json, code, category, message, lines)
    }

    /**
     * What each code meant on this device (measured 29/30 Oct 2026).
     * Where there is only a suspicion, the text says so.
     */
    val CODES: List<Pair<String, String>> = listOf(
        "1000" to "Caller does not match: wrong uid, package name does not match the uid, or no permission. Measured: Minis (uid 10278) gets 1000; uid 0 has no package; uid 2000 with package com.android.shell works.",
        "1001" to "Parameter wrong or missing. The message is often just 'null'. Measured: missing maxCount or -1 for photos; empty query for findPersons.",
        "1002" to "Function is disabled (enabled=2 in the runtime metadata). Also when the device is locked (\"Device is locked\"): unlock first, then try again.",
        "1003" to "Function not found: wrong ID or wrong package. Functions with scope=activity answer like this too.",
        "1500" to "Measured for rejectCall without a running call (assumed, not proven).",
        "2000" to "System error of the target app.",
        "2001" to "Call cancelled.",
        "2002" to "Enterprise policy.",
        "3000" to "Error in the target app itself. Measured: getHistories without timeZone in the DateTime.",
        "3501" to "Feature not available. Measured on enabled gms functions that the platform rejects anyway. Also when the screen is locked (\"called while screen is locked\"): unlock first, then try again.",
        "NO_PATH" to "No path carries (no binder at hand).",
        "NO_CONSTRUCTOR" to "The platform of this device declares no constructor for ExecuteAppFunctionAidlRequest that the shim knows (Request, UserHandle, String, then only long). The message names the declared signatures; the selection in Execute has to be extended. Measured: 4 arguments on BP2A, 5 on BP4A.",
        "NO_SHIPPED_DEX" to "The dex of the shim is not on the device.",
        "NEEDS_CONFIRMATION" to "Writing function without the user's consent.",
        "EMPTY_RESULT" to "SUCCESS, but no content — the result is unknown, not empty.",
    )

    fun explanation(code: String?): String? = CODES.firstOrNull { it.first == code }?.second
}
