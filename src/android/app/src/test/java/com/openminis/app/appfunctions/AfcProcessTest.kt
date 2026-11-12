package com.openminis.app.appfunctions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The wrapper around `afc`: only quoting and the argument list are pure logic. */
class AfcProcessTest {

    @Test fun quote_wraps_and_escapes_the_single_quote() {
        assertEquals("'abc'", AfcProcess.quote("abc"))
        assertEquals("'a'\\''b'", AfcProcess.quote("a'b"))
        assertEquals("''", AfcProcess.quote(""))
    }

    @Test fun quote_neutralises_shell_metacharacters() {
        // Inside single quotes $, `, ;, | and newline are plain text.
        val hostile = "x'; rm -rf / #\$(id) `id` | cat\nsecond line"
        val q = AfcProcess.quote(hostile)
        assertTrue(q.startsWith("'") && q.endsWith("'"))
        // There must be no unescaped ' inside.
        assertFalse(q.substring(1, q.length - 1).replace("'\\''", "").contains("'"))
    }

    @Test fun call_arguments_are_a_list_without_assembly() {
        val a = AfcProcess.callArguments("p.k", "p.k#f", """{"a":"b c"}""", writeAllowed = false)
        assertEquals(listOf("call", "p.k", "p.k#f", "--args", """{"a":"b c"}"""), a)
        // afc has no timeout flag; sending one would be silently ignored.
        assertFalse(a.contains("--timeout"))
    }

    @Test fun write_flag_only_when_allowed() {
        assertFalse(AfcProcess.callArguments("p", "f", "{}", false).contains("--write"))
        assertTrue(AfcProcess.callArguments("p", "f", "{}", true).contains("--write"))
    }
}

/**
 * `AfcProcess.interpret` against real output of `afc call` (31 Oct 2026), account IDs redacted.
 * The fixtures keep the device data; only the afc label words were translated
 * to English (SUCCESS, ERROR, Document, Call) when the labels were renamed.
 */
class AfcProcessInterpretTest {
    private fun raw(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("appfunctions/afcout/$name")!!.use { it.readBytes().toString(Charsets.UTF_8) }

    private fun interpret(name: String): AfcProcess.Outcome {
        val t = raw(name)
        val exit = Regex("""exit=(\d+)""").find(t)!!.groupValues[1].toInt()
        return AfcProcess.interpret(t, exit)
    }

    @Test fun success_carries_the_json() {
        val e = interpret("success.txt")
        assertTrue(e.toString(), e is AfcProcess.Outcome.Success)
        assertTrue((e as AfcProcess.Outcome.Success).json.startsWith("{\"androidAppfunctionsReturnValue\""))
    }

    @Test fun empty_is_empty_although_success_appears_in_the_text() {
        assertEquals(AfcProcess.Outcome.Empty, interpret("empty.txt"))
    }

    @Test fun passed_check_without_consent_is_not_success_despite_exit_zero() {
        assertEquals(AfcProcess.Outcome.NeedsConfirmation, interpret("write.txt"))
    }

    @Test fun check_failure_and_unknown_function_are_failures() {
        val p = interpret("check.txt")
        assertTrue(p is AfcProcess.Outcome.Failure && p.text.contains("P1"))
        val n = interpret("nofunc.txt")
        assertTrue(n is AfcProcess.Outcome.Failure && n.text.contains("no function matches"))
    }

    @Test fun success_line_with_exit_two_is_not_success() {
        assertTrue(AfcProcess.interpret("SUCCESS: {}\n", 2) is AfcProcess.Outcome.Failure)
    }
}
