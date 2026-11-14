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

    @Test fun needsConfirmation_onlyWhenSwitchOnAndWrite() {
        // Switch off (default): nothing asks, not even a delete.
        assertFalse(AppFunctionsCore.needsConfirmation("p#A_deleteAlarms", confirmWrites = false))
        assertFalse(AppFunctionsCore.needsConfirmation("p#A_findAlarms", confirmWrites = false))
        // Switch on: writes ask, reads do not.
        assertTrue(AppFunctionsCore.needsConfirmation("p#A_deleteAlarms", confirmWrites = true))
        assertTrue(AppFunctionsCore.needsConfirmation("p#A_createAlarm", confirmWrites = true))
        assertFalse(AppFunctionsCore.needsConfirmation("p#A_findAlarms", confirmWrites = true))
    }

    // ------------------------------------------------- declared parameters

    // Unmodified excerpt of `cmd app_function list-app-functions` on a Samsung
    // Android 17 device: Google Messages (declares parameters) and the Samsung
    // clock's createAlarm (declares none).
    private val dump by lazy { String(fixture("list_messaging_clock.json")) }
    private val msg = "com.google.android.apps.messaging"
    private val samsungClock = "com.sec.android.app.clockpackage"

    @Test fun parseListDocument_readsDeclaredParametersAndTypes() {
        val doc = AppFunctionsCore.parseListDocument(dump)
        val draft = doc.entries.single { it.packageName == msg }
        assertEquals(listOf("parameters"), draft.parameters!!.map { it.name })
        assertEquals("DraftMessageParameters", draft.parameters!!.single().type)
        assertTrue(draft.parameters!!.single().required)
        assertTrue(draft.description.startsWith("The user is currently"))
        val text = doc.types.getValue(msg).getValue("DraftMessageParameters").single()
        assertEquals("text", text.name); assertEquals("string", text.type); assertTrue(text.required)
    }

    @Test fun parseListDocument_unpublishedIsNull_notEmpty() {
        val create = AppFunctionsCore.parseListDocument(dump).entries.single { it.packageName == samsungClock }
        assertEquals(null, create.parameters)
        assertEquals("createAlarm", create.schemaName)
    }

    @Test fun validate_rejectsUnknownAndMissingFields() {
        val doc = AppFunctionsCore.parseListDocument(dump)
        val draft = doc.entries.single { it.packageName == msg }
        val types = doc.types.getValue(msg)
        fun v(json: String) = AppFunctionsCore.validate(draft, types, JSONObject(json))
        assertTrue(v("{\"parameters\":{\"text\":\"hi\"}}").isEmpty())
        assertTrue(v("{}").single().contains("missing required field 'parameters'"))
        assertTrue(v("{\"parameters\":{}}").single().contains("missing required field 'parameters.text'"))
        assertTrue(v("{\"parameters\":{\"text\":\"hi\",\"txt\":1}}").single().contains("unknown field 'parameters.txt'"))
        assertTrue(v("{\"draftMessageParams\":{}}").any { it.contains("unknown field 'draftMessageParams'") })
        assertTrue(v("{\"parameters\":\"hi\"}").single().contains("must be an object"))
    }

    @Test fun validate_unpublishedChecksNothing() {
        val doc = AppFunctionsCore.parseListDocument(dump)
        val create = doc.entries.single { it.packageName == samsungClock }
        assertTrue(AppFunctionsCore.validate(create, emptyMap(), JSONObject("{\"anything\":1}")).isEmpty())
    }

    @Test fun validate_checksArrayElements() {
        val e = AppFunctionsCore.FunctionEntry("p", "p#f", null, null, null, null,
            parameters = listOf(AppFunctionsCore.Param("items", "array<It>", true, "")))
        val types = mapOf("It" to listOf(AppFunctionsCore.Param("id", "long", true, "")))
        assertTrue(AppFunctionsCore.validate(e, types, JSONObject("{\"items\":[{\"id\":1}]}")).isEmpty())
        assertTrue(AppFunctionsCore.validate(e, types, JSONObject("{\"items\":[{}]}")).single().contains("items[0].id"))
        assertTrue(AppFunctionsCore.validate(e, types, JSONObject("{\"items\":{}}")).single().contains("must be an array"))
    }

    @Test fun schemaFromList_onlyWhenPublished() {
        val doc = AppFunctionsCore.parseListDocument(dump)
        assertEquals(null, AppFunctionsCore.schemaFromList(doc, samsungClock, null))
        val s = AppFunctionsCore.schemaFromList(doc, msg, "draftMessage")!!
        assertEquals("parameters", s.functions.single().parameters.single().name)
        assertTrue(s.types.containsKey("DraftMessageParameters"))
    }

    @Test fun formatList_showsDeclaredParameters() {
        val out = AppFunctionsCore.formatList(AppFunctionsCore.parseList(dump), null)
        assertTrue(out, out.contains("draftMessage(parameters: DraftMessageParameters)"))
        assertTrue(out, out.contains("AlarmCrossAppActions_createAlarm(? unpublished)"))
    }

    @Test fun schemaXml_doctypeInUtf16IsRefused() {
        val evil = "<?xml version=\"1.0\" encoding=\"UTF-16\"?><!DOCTYPE x [<!ENTITY e \"x\">]><appfunctions/>"
        try { AppFunctionsCore.parseSchemaXml(evil.toByteArray(Charsets.UTF_16LE)); fail("DOCTYPE must be rejected") }
        catch (_: IllegalArgumentException) {}
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

    // [T-appfunctions-confirm-note] Measured on the device (TEST2-LOG.md 3.6): a
    // confirmed write call came back exactly like one without the gate.
    @Test fun successNotes_confirmOnly() {
        val n = AppFunctionsCore.successNotes("com.example.app", confirmed = true, parametersDeclared = true)
        assertEquals(1, n.size)
        assertTrue(n[0].startsWith("this call changes state and the user confirmed it"))
    }

    @Test fun successNotes_noConfirmNoNote() {
        // Without the gate the call runs on the strength of the tool switch; then no
        // line belongs here, or the note would be worthless.
        assertTrue(AppFunctionsCore.successNotes("com.example.app", confirmed = false, parametersDeclared = true).isEmpty())
    }

    @Test fun successNotes_unpublishedKeepsItsNote() {
        val n = AppFunctionsCore.successNotes("com.samsung.clock", confirmed = false, parametersDeclared = false)
        assertEquals(1, n.size)
        assertTrue(n[0].startsWith("com.samsung.clock declares no parameters"))
    }

    @Test fun successNotes_bothNotesInOrder() {
        val n = AppFunctionsCore.successNotes("com.samsung.clock", confirmed = true, parametersDeclared = false)
        assertEquals(2, n.size)
        assertTrue(n[0].startsWith("this call changes state"))
        assertTrue(n[1].startsWith("com.samsung.clock declares no parameters"))
    }

    @Test fun successNotes_readCallGetsNothing() {
        // Read functions are never behind the gate (D6), so there is no
        // confirmation note either. The second part pins that coupling down.
        assertFalse(AppFunctionsCore.needsConfirmation("pkg#getDeviceStateItem", confirmWrites = true))
        assertTrue(AppFunctionsCore.successNotes("pkg", confirmed = false, parametersDeclared = true).isEmpty())
    }

    @Test fun noConstructor_isReportedAsErrorWithTheSignatures() {
        val raw = "ERROR: code=NO_CONSTRUCTOR cat=0 msg=no matching constructor of X; declared: public X(A,B)\n"
        val a = AppFunctionsReply.interpret(raw)
        assertEquals(AppFunctionsReply.Kind.ERROR, a.kind)
        assertEquals("NO_CONSTRUCTOR", a.code)
        val b = a.report()
        assertTrue(b, b.contains("declared: public X(A,B)"))
        assertTrue(b, b.contains("BP2A"))
    }

    @Test fun lockedDevice_codesTellTheUserToUnlock() {
        assertTrue(AppFunctionsReply.explanation("1002")!!.contains("unlock"))
        assertTrue(AppFunctionsReply.explanation("3501")!!.contains("unlock"))
    }
}

/** Flat arguments against a single document parameter, as measured on 13 Nov 2026. */
class AppFunctionsCoreWrapTest {
    private val params = AppFunctionsCore.Param(
        "findBookmarksParams",
        "com.google.android.appfunctions.schema.common.v1.browser.FindBookmarksParams",
        true, "convention",
    )
    private val fields = listOf(
        AppFunctionsCore.Param("maxCount", "long", true, "schema"),
        AppFunctionsCore.Param("query", "string", false, "schema"),
    )
    private val entry = AppFunctionsCore.FunctionEntry(
        packageName = "com.sec.android.app.sbrowser",
        id = "com.sec.android.app.sbrowser.cross_app_action#BrowserFunctions_findBookmarks",
        schemaName = "BrowserFunctions_findBookmarks",
        category = "browser",
        scope = null,
        enabledByDefault = true,
        parameters = listOf(params),
    )
    private val types = mapOf(params.type to fields)

    @Test fun flat_fields_are_wrapped_into_the_declared_parameter() {
        val out = AppFunctionsCore.wrapFlatArguments(entry, types, org.json.JSONObject("""{"maxCount":1}"""))
        assertEquals("""{"findBookmarksParams":{"maxCount":1}}""", out.toString())
    }

    @Test fun an_already_wrapped_argument_stays_untouched() {
        val given = """{"findBookmarksParams":{"maxCount":1}}"""
        assertEquals(given, AppFunctionsCore.wrapFlatArguments(entry, types, org.json.JSONObject(given)).toString())
    }

    @Test fun empty_arguments_stay_empty_so_the_missing_field_is_reported() {
        assertEquals("{}", AppFunctionsCore.wrapFlatArguments(entry, types, org.json.JSONObject("{}")).toString())
    }

    @Test fun an_unknown_field_is_not_wrapped_and_is_reported_later() {
        val given = """{"nonsense":1}"""
        assertEquals(given, AppFunctionsCore.wrapFlatArguments(entry, types, org.json.JSONObject(given)).toString())
    }

    @Test fun the_wrapped_form_validates_against_the_published_schema() {
        val flat = org.json.JSONObject("""{"maxCount":1}""")
        val wrapped = AppFunctionsCore.wrapFlatArguments(entry, types, flat)
        assertTrue(AppFunctionsCore.validate(entry, types, wrapped).isEmpty())
        // The flat form is what the platform refused; against the schema it is wrong.
        assertTrue(AppFunctionsCore.validate(entry, types, flat).isNotEmpty())
    }
}

/** Both shapes must pass the registry check: measured on 14 Nov 2026. */
class AppFunctionsRegistryNestedTest {
    private val registry: List<AppFunctionsRegistry.Function> by lazy {
        val text = javaClass.classLoader!!.getResourceAsStream("appfunctions/registry.txt")!!
            .use { it.readBytes().toString(Charsets.UTF_8) }
        AppFunctionsRegistry.fromText(text)
    }
    private val f by lazy {
        AppFunctionsRegistry.find(registry, "com.sec.android.gallery3d", "AlbumFunctions_createAlbum").getOrThrow()
    }

    @Test fun flat_title_is_accepted() {
        val c = AppFunctionsRegistry.check(f, org.json.JSONObject("""{"title":"x"}"""))
        assertFalse(c.report(), c.hasErrors)
    }

    @Test fun nested_createAlbumParams_is_accepted_too() {
        val c = AppFunctionsRegistry.check(f, org.json.JSONObject("""{"createAlbumParams":{"title":"x"}}"""))
        assertFalse(c.report(), c.hasErrors)
    }

    @Test fun both_shapes_produce_the_same_call_arguments() {
        val flat = AppFunctionsRegistry.check(f, org.json.JSONObject("""{"title":"x"}"""))
        val nested = AppFunctionsRegistry.check(f, org.json.JSONObject("""{"createAlbumParams":{"title":"x"}}"""))
        assertEquals(flat.a3, nested.a3)
        assertEquals(flat.a4, nested.a4)
    }

    @Test fun a_missing_required_field_is_still_reported_in_both_shapes() {
        assertTrue(AppFunctionsRegistry.check(f, org.json.JSONObject("""{"createAlbumParams":{}}""")).hasErrors)
        assertTrue(AppFunctionsRegistry.check(f, org.json.JSONObject("""{}""")).hasErrors)
    }
}
