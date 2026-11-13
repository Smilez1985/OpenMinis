package com.openminis.app.appfunctions

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The expectations are fixed here and come from the run of the reference
 * implementation over these cases (31 Oct 2026). If a rule changes, this test
 * turns red; that is its purpose.
 */
class AppFunctionsParityTest {

    private val registry: List<AppFunctionsRegistry.Function> by lazy {
        val text = javaClass.classLoader!!.getResourceAsStream("appfunctions/registry.txt")!!
            .use { it.readBytes().toString(Charsets.UTF_8) }
        AppFunctionsRegistry.fromText(text)
    }

    /** "ERROR P1,P3 | P4" or "OK a3=… | P4": rule IDs only, like `afc check`. */
    private fun verdict(packageName: String, name: String, args: String): String {
        val f = AppFunctionsRegistry.find(registry, packageName, name).getOrThrow()
        val c = AppFunctionsRegistry.check(f, JSONObject(args))
        val errors = c.findings.filter { it.isError }.map { it.rule }.toSortedSet()
        val warnings = c.findings.filter { !it.isError }.map { it.rule }.toSortedSet()
        return if (errors.isNotEmpty()) "ERROR ${errors.joinToString(",")} | ${warnings.joinToString(",")}"
        else "OK ${c.a3} | ${warnings.joinToString(",")}"
    }

    private val contacts = "com.samsung.android.app.contacts"

    @Test fun rules_p0_to_p5_match_rust() {
        assertEquals("OK findPersonsParams | ", verdict(contacts, "findPersons", """{"query":"e","maxCount":3}"""))
        assertEquals("ERROR P1 | ", verdict(contacts, "findPersons", """{"query":"e"}"""))
        assertEquals("ERROR P3 | ", verdict(contacts, "findPersons", """{"query":"e","maxCount":"3"}"""))
        assertEquals("ERROR P5 | ", verdict(contacts, "findPersons", """{"query":"","maxCount":3}"""))
        assertEquals("ERROR P2 | ", verdict(contacts, "findPersons", """{"query":"e","maxCount":3,"foo":1}"""))
        assertEquals("ERROR P1 | ", verdict(contacts, "findPersons", "{}"))
        assertEquals("OK findPersonsParams | P4", verdict(contacts, "findPersons", """{"query":"e","maxCount":99}"""))
        assertEquals("OK findPersonsParams | P4", verdict(contacts, "findPersons", """{"query":"e","maxCount":0}"""))
        assertEquals("OK findPersonsParams | P4", verdict(contacts, "findPersons", """{"query":"e","maxCount":-1}"""))
    }

    @Test fun top_level_parameters_match_rust() {
        assertEquals("OK @ | ", verdict(contacts, "getPersons", """{"personIds":["73"]}"""))
        assertEquals("ERROR P3 | ", verdict(contacts, "getPersons", """{"personIds":"73"}"""))
        assertEquals("ERROR P2 | ", verdict(contacts, "getPersons", """{"personIds":["73"],"x":1}"""))
        // If the parameter with which a call once succeeded is missing: warning P1, not an error (Rust: validate.rs)
        assertEquals("OK @ | P1", verdict(contacts, "getPersons", "{}"))
    }

    @Test fun function_without_declared_parameters() {
        assertEquals("ERROR P2 | ", verdict("com.google.android.gms", "getDeviceStateMetadata", """{"a":1}"""))
    }

    @Test fun empty_call_carries_only_the_type() {
        val f = AppFunctionsRegistry.find(registry, "com.google.android.gms", "getDeviceStateMetadata").getOrThrow()
        val c = AppFunctionsRegistry.check(f, JSONObject("{}"))
        assertEquals("getDeviceStateMetadataParams", c.a3)
        assertTrue(c.a4.contains("GetDeviceStateMetadataParams"))
        assertTrue(c.a4, c.a4.contains("\"_type\""))
    }

    private fun kind(raw: String) = AppFunctionsReply.interpret(raw).kind

    @Test fun reply_interpretation_matches_rust() {
        assertEquals(AppFunctionsReply.Kind.SUCCESS, kind("JSON: {\"androidAppfunctionsReturnValue\":[{\"id\":\"1\"}]}\nSUCCESS: ok\nDONE\n"))
        assertEquals(AppFunctionsReply.Kind.EMPTY, kind("JSON: {\"androidAppfunctionsReturnValue\":[]}\nSUCCESS: ok\nDONE\n"))
        assertEquals(AppFunctionsReply.Kind.EMPTY, kind("SUCCESS: null\nDONE\n"))
        assertEquals(AppFunctionsReply.Kind.EMPTY, kind("JSON: {}\nSUCCESS: ok\n"))
        assertEquals(AppFunctionsReply.Kind.EMPTY, kind("JSON: [ ]\nSUCCESS: ok\n"))
        // Rust: "SUCCESS: null" is EMPTY even if JSON came before.
        assertEquals(AppFunctionsReply.Kind.EMPTY, kind("JSON: {\"a\":1}\nSUCCESS: null\nDONE\n"))
        assertEquals(AppFunctionsReply.Kind.ERROR, kind("ERROR: code=1001 cat=APP_ERROR msg=Invalid Argument\nDONE\n"))
        assertEquals(AppFunctionsReply.Kind.TIMEOUT, kind("TIMEOUT\n"))
        assertEquals(AppFunctionsReply.Kind.UNCLEAR, kind("java.lang.NoSuchMethodException: x\n"))
        assertEquals(AppFunctionsReply.Kind.UNCLEAR, kind(""))
        assertEquals(AppFunctionsReply.Kind.SUCCESS, kind("SUCCESS: something\nDONE\n"))
    }

    @Test fun error_code_and_message_are_read() {
        val r = AppFunctionsReply.interpret("ERROR: code=NO_CONSTRUCTOR cat=X msg=ctor missing\n")
        assertEquals("NO_CONSTRUCTOR", r.code)
        assertEquals("X", r.category)
        assertEquals("ctor missing", r.message)
    }
}
