package com.openminis.app.appfunctions

import android.annotation.SuppressLint
import android.app.appfunctions.AppFunctionException
import android.app.appfunctions.AppFunctionManager
import android.app.appfunctions.ExecuteAppFunctionRequest
import android.app.appfunctions.ExecuteAppFunctionResponse
import android.app.appsearch.GenericDocument
import android.content.Context
import android.os.CancellationSignal
import android.os.OutcomeReceiver
import androidx.annotation.RequiresApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * [T-appfunctions-caller] Direct path: `AppFunctionManager.executeAppFunction`
 * (API 36). Only used when this app holds `EXECUTE_APP_FUNCTIONS`
 * ([AppFunctionsManager.decidePath]); the platform additionally enforces its
 * agent allowlist and reports a refusal as an [AppFunctionException].
 *
 * JSON <-> GenericDocument: arguments are mapped generically by JSON value
 * type (string, long, double, boolean, nested object, array of those). The
 * target validates against its own schema and names a missing or mistyped
 * field in its error, which is passed through. Typed conversion from the
 * schema (as in the official android/appfunctions sample) is a follow-up.
 *
 * UNTESTED on a device: no device at hand grants the permission to a
 * third-party app (see AppFunctionsManager). Compiles against compileSdk 36.
 */
@RequiresApi(36)
object PlatformExecutor {
    /** Platform key of the return value ("androidAppfunctionsReturnValue"). */
    private val RETURN_KEY = ExecuteAppFunctionResponse.PROPERTY_RETURN_VALUE

    @SuppressLint("MissingPermission") // gated by AppFunctionsManager.decidePath
    suspend fun execute(
        context: Context,
        packageName: String,
        functionId: String,
        parametersJson: String,
        timeoutSeconds: Int,
    ): AppFunctionsCore.ExecOutcome {
        val manager = context.getSystemService(AppFunctionManager::class.java)
            ?: return AppFunctionsCore.ExecOutcome.FunctionError("AppFunctionManager not available")
        val params = try {
            toDocument(JSONObject(parametersJson))
        } catch (e: Exception) {
            return AppFunctionsCore.ExecOutcome.FunctionError("arguments not convertible: ${e.message}")
        }
        val request = ExecuteAppFunctionRequest.Builder(packageName, functionId).setParameters(params).build()
        val cancel = CancellationSignal()
        val outcome = withTimeoutOrNull(timeoutSeconds * 1000L) {
            suspendCancellableCoroutine<AppFunctionsCore.ExecOutcome> { cont ->
                cont.invokeOnCancellation { cancel.cancel() }
                manager.executeAppFunction(request, context.mainExecutor, cancel,
                    object : OutcomeReceiver<ExecuteAppFunctionResponse, AppFunctionException> {
                        override fun onResult(result: ExecuteAppFunctionResponse) {
                            val json = JSONObject().put(RETURN_KEY, fromDocumentValue(result.resultDocument, RETURN_KEY))
                            cont.resume(AppFunctionsCore.ExecOutcome.Success(json.toString(1)))
                        }

                        override fun onError(error: AppFunctionException) {
                            cont.resume(AppFunctionsCore.ExecOutcome.FunctionError(
                                "${error.errorMessage ?: error.javaClass.simpleName} (code ${error.errorCode})"))
                        }
                    })
            }
        }
        if (outcome == null) cancel.cancel()
        return outcome ?: AppFunctionsCore.ExecOutcome.FunctionError("timed out after $timeoutSeconds s")
    }

    // ------------------------------------------------------------ JSON -> doc

    internal fun toDocument(o: JSONObject): GenericDocument {
        val b = GenericDocument.Builder<GenericDocument.Builder<*>>("", "", "")
        for (key in o.keys()) {
            when (val v = o.get(key)) {
                is String -> b.setPropertyString(key, v)
                is Boolean -> b.setPropertyBoolean(key, v)
                is Int, is Long -> b.setPropertyLong(key, (v as Number).toLong())
                is Number -> b.setPropertyDouble(key, v.toDouble())
                is JSONObject -> b.setPropertyDocument(key, toDocument(v))
                is JSONArray -> setArray(b, key, v)
                JSONObject.NULL -> Unit
                else -> throw IllegalArgumentException("unsupported value for '$key'")
            }
        }
        return b.build()
    }

    private fun setArray(b: GenericDocument.Builder<*>, key: String, a: JSONArray) {
        if (a.length() == 0) { b.setPropertyString(key, *emptyArray<String>()); return }
        val items = (0 until a.length()).map { a.get(it) }
        when {
            items.all { it is String } -> b.setPropertyString(key, *items.map { it as String }.toTypedArray())
            items.all { it is Boolean } -> b.setPropertyBoolean(key, *items.map { it as Boolean }.toBooleanArray())
            items.all { it is Int || it is Long } -> b.setPropertyLong(key, *items.map { (it as Number).toLong() }.toLongArray())
            items.all { it is Number } -> b.setPropertyDouble(key, *items.map { (it as Number).toDouble() }.toDoubleArray())
            items.all { it is JSONObject } -> b.setPropertyDocument(key, *items.map { toDocument(it as JSONObject) }.toTypedArray())
            else -> throw IllegalArgumentException("array '$key' mixes value types")
        }
    }

    // ------------------------------------------------------------ doc -> JSON

    private fun fromDocumentValue(doc: GenericDocument, key: String): Any? {
        val raw = doc.getProperty(key) ?: return JSONObject.NULL
        return valueToJson(raw)
    }

    private fun valueToJson(v: Any): Any = when (v) {
        is Array<*> -> JSONArray().apply { v.forEach { put(if (it == null) JSONObject.NULL else valueToJson(it)) } }
        is LongArray -> JSONArray().apply { v.forEach { put(it) } }
        is DoubleArray -> JSONArray().apply { v.forEach { put(it) } }
        is BooleanArray -> JSONArray().apply { v.forEach { put(it) } }
        is GenericDocument -> JSONObject().apply {
            for (name in v.propertyNames) put(name, v.getProperty(name)?.let { valueToJson(it) } ?: JSONObject.NULL)
        }
        is ByteArray -> android.util.Base64.encodeToString(v, android.util.Base64.NO_WRAP)
        else -> v
    }
}
