package com.openminis.app.appfunctions

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [T-appfunctions-caller] JVM tests for the pure core. Fixtures under
 * src/test/resources/appfunctions/ are the unmodified schema files read from
 * two installed APKs on an Android 17 device: a full Google schema (typed
 * parameters, descriptions, data types) and a minimal Samsung one
 * (schema_name/schema_category only).
 */
class AppFunctionsCoreTest {

    private fun fixture(name: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("appfunctions/$name")!!.use { it.readBytes() }

    // `cmd app_function list-app-functions` shape: values wrapped in arrays.
    private fun entryJson(pkg: String, fid: String, enabled: Boolean = true) = JSONObject()
        .put("AppFunctionStaticMetadata-$pkg", JSONObject()
            .put("functionId", JSONArray().put(fid))
            .put("schemaName", JSONArray().put(fid.substringAfterLast('_')))
            .put("schemaCategory", JSONArray().put("clock"))
            .put("scope", JSONArray().put("global"))
            .put("enabledByDefault", JSONArray().put(enabled)))
        .put("AppFunctionRuntimeMetadata-$pkg", JSONObject().put("functionId", JSONArray().put(fid)))

    private val clock = "com.example.clock"
    private val listJson = JSONObject()
        .put(clock, JSONArray()
            .put(entryJson(clock, "$clock.x#Alarm_findAlarms"))
            .put(entryJson(clock, "$clock.x#Alarm_deleteAlarms", enabled = false)))
        .put("com.example.other", JSONArray().put(entryJson("com.example.other", "com.example.other#X_getX")))
        .toString()

    // ------------------------------------------------------------------ list

    @Test fun parseList_flattensAndCountsEveryFunction() {
        val e = AppFunctionsCore.parseList(listJson)
        assertEquals(3, e.size)
        val find = e.first { it.shortName == "Alarm_findAlarms" }
        assertEquals(clock, find.packageName)
        assertEquals("clock", find.category)
        assertEquals("global", find.scope)
        assertEquals(false, e.first { it.shortName == "Alarm_deleteAlarms" }.enabledByDefault)
    }

    @Test fun parseList_emptyIsAnError_notZeroFunctions() {
        try { AppFunctionsCore.parseList("  "); fail("expected failure") } catch (_: IllegalArgumentException) {}
    }

    @Test fun formatList_marksReadAndWrite_andTotals() {
        val out = AppFunctionsCore.formatList(AppFunctionsCore.parseList(listJson), null)
        assertTrue(out, out.contains("read   Alarm_findAlarms"))
        assertTrue(out, out.contains("write  Alarm_deleteAlarms"))
        assertTrue(out, out.contains("(disabled)"))
        assertTrue(out, out.contains("Total 3 in 2 apps"))
    }

    @Test fun formatList_filtersByQuery() {
        val out = AppFunctionsCore.formatList(AppFunctionsCore.parseList(listJson), "other")
        assertTrue(out.contains("X_getX"))
        assertFalse(out.contains("Alarm_findAlarms"))
        assertTrue(AppFunctionsCore.formatList(AppFunctionsCore.parseList(listJson), "zzz").startsWith("No AppFunctions match"))
    }

    // --------------------------------------------------------------- resolve

    @Test fun resolve_shortNameToFullId() {
        val e = AppFunctionsCore.parseList(listJson)
        assertEquals("$clock.x#Alarm_findAlarms", AppFunctionsCore.resolve(e, clock, "findAlarms"))
        assertEquals("$clock.x#Alarm_findAlarms", AppFunctionsCore.resolve(e, clock, "Alarm_findAlarms"))
        assertEquals("p#full", AppFunctionsCore.resolve(e, clock, "p#full"))
    }

    @Test fun resolve_unknownNamesTheAlternatives() {
        try {
            AppFunctionsCore.resolve(AppFunctionsCore.parseList(listJson), clock, "nope"); fail()
        } catch (ex: AppFunctionsCore.ResolveException) {
            assertTrue(ex.message!!, ex.message!!.contains("available: Alarm_deleteAlarms, Alarm_findAlarms"))
        }
    }

    @Test fun resolve_ambiguousIsAnError() {
        val two = JSONObject().put(clock, JSONArray()
            .put(entryJson(clock, "$clock.a#Timer_getTimers"))
            .put(entryJson(clock, "$clock.b#Uhr_getTimers"))).toString()
        try {
            AppFunctionsCore.resolve(AppFunctionsCore.parseList(two), clock, "getTimers"); fail()
        } catch (ex: AppFunctionsCore.ResolveException) {
            assertTrue(ex.message!!.contains("ambiguous"))
        }
    }

    // ------------------------------------------------------------------ verb

    @Test fun isReadOnly_byVerb() {
        for (id in listOf("p#A_findAlarms", "p#getDeviceStateItem", "p#X_searchEmails", "p#isEnabled", "p#listThings"))
            assertTrue(id, AppFunctionsCore.isReadOnly(id))
        for (id in listOf("p#A_createAlarm", "p#A_deleteAlarms", "p#updateTask", "p#setDeviceStateItem",
            "p#draftMessage", "p#showAlarm", "p#Stopwatch_startStopwatch"))
            assertFalse(id, AppFunctionsCore.isReadOnly(id))
    }

    // ---------------------------------------------------------------- schema

    @Test fun schema_googleShape_typedParametersAndDataTypes() {
        val doc = AppFunctionsCore.parseSchemaXml(fixture("google_messages_draft.xml"))
        assertEquals(1, doc.functions.size)
        val f = doc.functions[0]
        assertTrue(f.id!!.endsWith("#draftMessage"))
        assertEquals("activity", f.scope)
        assertTrue(f.description.isNotEmpty())
        assertEquals(1, f.parameters.size)
        assertEquals("DraftMessageParameters", f.parameters[0].type)
        assertTrue(f.parameters[0].required)
        assertEquals("DraftedMessage", f.returns)
        val text = doc.types.getValue("DraftMessageParameters").single { it.name == "text" }
        assertEquals("string", text.type)
        assertTrue(text.required)
        assertTrue(text.description.isNotEmpty())
    }

    @Test fun schema_samsungShape_namesWithoutParameters() {
        val doc = AppFunctionsCore.parseSchemaXml(fixture("samsung_clock_app_function.xml"))
        assertEquals(18, doc.functions.size)
        val find = doc.functions.single { it.id!!.endsWith("_findAlarms") }
        assertEquals("findAlarms", find.schemaName)
        assertEquals("clock", find.category)
        assertTrue(find.parameters.isEmpty())
    }

    @Test fun schema_arrayTypeKeepsItemType() {
        val xml = """<appfunctions><AppFunctionComponentMetadataDocument><dataTypes>
            <dataTypeMetadata>
              <properties><dataTypeMetadata><type>10</type><itemType><type>8</type></itemType></dataTypeMetadata><name>tags</name></properties>
              <type>3</type></dataTypeMetadata><name>a.b.P</name></dataTypes></AppFunctionComponentMetadataDocument></appfunctions>"""
        val p = AppFunctionsCore.parseSchemaXml(xml.toByteArray()).types.getValue("P").single()
        assertEquals("array<string>", p.type)
        assertFalse(p.required)
    }

    @Test fun schema_rejectsDoctype() {
        val evil = "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><appfunctions>&e;</appfunctions>"
        try { AppFunctionsCore.parseSchemaXml(evil.toByteArray()); fail("DOCTYPE must be rejected") } catch (_: Exception) {}
    }

    // --------------------------------------------------------------- execute

    @Test fun executeArgv_keepsArgumentsSeparate_noShell() {
        val params = """{"text":"a \"b\" 'c' ${'$'}HOME `id`; rm -rf /"}"""
        val argv = AppFunctionsCore.executeArgv("p", "p#f", params, 30)
        assertArrayEquals(arrayOf("cmd", "app_function", "execute-app-function", "--package", "p",
            "--function", "p#f", "--parameters", params, "--timeout-duration", "30"), argv)
        assertFalse(argv.contains("sh"))
        assertFalse(argv.contains("-c"))
    }

    @Test fun executeArgv_rejectsNonJson_andClampsTimeout() {
        try { AppFunctionsCore.executeArgv("p", "p#f", "{broken", 30); fail() } catch (_: Exception) {}
        assertEquals("120", AppFunctionsCore.executeArgv("p", "p#f", "{}", 999).last())
        assertEquals("1", AppFunctionsCore.executeArgv("p", "p#f", "{}", 0).last())
    }

    @Test fun parseExecuteOutput_success() {
        val r = AppFunctionsCore.parseExecuteOutput(0, "{\n  \"androidAppfunctionsReturnValue\": []\n}", "")
        assertTrue(r is AppFunctionsCore.ExecOutcome.Success)
    }

    @Test fun parseExecuteOutput_functionErrorWithoutLogcatHint() {
        val r = AppFunctionsCore.parseExecuteOutput(255, "",
            "Error executing app function: android.app.appfunctions.AppFunctionException: " +
                "Parameter [findAlarmsParams] cannot be null (code 1001). See logcat for more details.")
        r as AppFunctionsCore.ExecOutcome.FunctionError
        assertEquals("Parameter [findAlarmsParams] cannot be null (code 1001).", r.message)
    }

    @Test fun parseExecuteOutput_emptyIsNotSuccess() {
        assertEquals(AppFunctionsCore.ExecOutcome.Empty, AppFunctionsCore.parseExecuteOutput(0, "  \n", ""))
    }

    @Test fun parseExecuteOutput_errorTextWithRcZeroIsAnError() {
        val r = AppFunctionsCore.parseExecuteOutput(0, "Error executing app function: something", "")
        assertTrue(r is AppFunctionsCore.ExecOutcome.FunctionError)
    }
}
