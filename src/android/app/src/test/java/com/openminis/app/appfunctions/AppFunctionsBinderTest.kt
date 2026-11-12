package com.openminis.app.appfunctions

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * Two bugs that only showed up on the device (31 Oct 2026):
 *
 * 1. The Shizuku path silently truncates a command line at 8192 characters
 *    (exit 0). The 7575-byte dex is 10100 characters long as base64.
 * 2. `arrayOf("exec", x)` starts no process at all: `exec` is a subcommand of
 *    the CLI, not a program. The right form is `sh -c`.
 *
 * The tests call the implementation instead of recomputing it.
 */
class AppFunctionsBinderTest {

    private val dir = "/data/local/tmp/af"
    private val file = "$dir/shim.jar"

    /** What `sh` would make of the commands: the chunks one after another. */
    private fun payload(command: String): String =
        Regex("""printf '%s' '([^']*)'""").find(command)!!.groupValues[1]

    // ------------------------------------------------------------ sh -c

    @Test fun shell_is_sh_dash_c_and_never_exec() {
        val a = AppFunctionsBinder.shell("echo hello")
        assertArrayEquals(arrayOf("sh", "-c", "echo hello"), a)
        assertFalse("exec is not a program", a[0] == "exec")
    }

    @Test fun shell_passes_the_command_through_unchanged() {
        val cmd = "a | b > c; d && e 'x' \"y\" \$z `w`"
        assertEquals(cmd, AppFunctionsBinder.shell(cmd)[2])
        assertEquals(3, AppFunctionsBinder.shell(cmd).size)
    }

    // ------------------------------------------------------------ limit

    @Test fun limit_is_below_the_measured_8192() {
        assertTrue(AppFunctionsBinder.COMMAND_LIMIT < 8192)
        assertTrue("8168 was intact", AppFunctionsBinder.COMMAND_LIMIT <= 8168)
    }

    @Test fun too_long_commands_are_rejected_with_a_reason() {
        assertNull(AppFunctionsBinder.tooLong("x".repeat(AppFunctionsBinder.COMMAND_LIMIT)))
        val reason = AppFunctionsBinder.tooLong("x".repeat(AppFunctionsBinder.COMMAND_LIMIT + 1))
        assertNotNull(reason)
        assertTrue(reason!!, reason.contains("${AppFunctionsBinder.COMMAND_LIMIT + 1}"))
    }

    @Test fun the_measured_failing_command_would_have_been_rejected() {
        // 10279 characters: the length of the command with the whole dex (measured).
        assertNotNull(AppFunctionsBinder.tooLong("x".repeat(10279)))
    }

    // ------------------------------------------------------------ chunking

    @Test fun the_real_dex_is_split_into_chunks_below_the_limit() {
        val dex = ByteArray(7575) { (it * 31 + 7).toByte() } // same size as shim.jar
        val b64 = Base64Encoder.encode(dex)
        assertEquals(10100, b64.length)
        val commands = AppFunctionsBinder.stageCommands(b64, dir, file)
        assertEquals(3, commands.size)
        for (b in commands) {
            assertNull("chunk too long: ${b.length}", AppFunctionsBinder.tooLong(b))
        }
    }

    @Test fun chunks_together_give_exactly_the_payload() {
        val b64 = Base64Encoder.encode(ByteArray(7575) { (it % 251).toByte() })
        val commands = AppFunctionsBinder.stageCommands(b64, dir, file)
        assertEquals(b64, commands.joinToString("") { payload(it) })
        assertEquals(listOf(4000, 4000, 2100), commands.map { payload(it).length })
    }

    @Test fun first_chunk_overwrites_all_later_ones_append() {
        val commands = AppFunctionsBinder.stageCommands("A".repeat(9000), dir, file)
        assertEquals(3, commands.size)
        assertTrue(commands[0], commands[0].endsWith(" > $file.b64"))
        assertTrue(commands[1], commands[1].endsWith(" >> $file.b64"))
        assertTrue(commands[2], commands[2].endsWith(" >> $file.b64"))
    }

    @Test fun directory_is_created_only_in_the_first_command() {
        val commands = AppFunctionsBinder.stageCommands("A".repeat(9000), dir, file)
        assertTrue(commands[0].startsWith("mkdir -p $dir && "))
        assertFalse(commands[1].contains("mkdir"))
    }

    @Test fun short_payload_is_a_single_command() {
        val commands = AppFunctionsBinder.stageCommands("QUJD", dir, file)
        assertEquals(1, commands.size)
        assertTrue(commands[0].contains("> $file.b64") && !commands[0].contains(">>"))
    }

    @Test fun negative_check_a_single_command_as_before_would_be_too_long() {
        // Old behaviour: everything in one line. With the real dex it is over the limit.
        val b64 = Base64Encoder.encode(ByteArray(7575) { it.toByte() })
        val old = "mkdir -p $dir && echo $b64 | base64 -d > $file && chmod 755 $file && sha256sum $file | cut -d' ' -f1"
        assertNotNull("the old behaviour must turn red", AppFunctionsBinder.tooLong(old))
        assertTrue(old.length > 8192)
    }

    @Test fun finish_command_decodes_deletes_and_prints_the_checksum() {
        val f = AppFunctionsBinder.finishCommand(file)
        assertTrue(f, f.startsWith("base64 -d $file.b64 > $file"))
        assertTrue(f.contains("rm -f $file.b64"))
        assertTrue(f.contains("chmod 755 $file"))
        assertTrue(f.endsWith("sha256sum $file | cut -d' ' -f1"))
        assertNull(AppFunctionsBinder.tooLong(f))
    }

    // ------------------------------------------------------------ Base64

    @Test fun base64_matches_java_for_every_remainder_length() {
        for (n in 0..40) {
            val d = ByteArray(n) { (it * 17 + 3).toByte() }
            assertEquals(
                "length $n",
                java.util.Base64.getEncoder().encodeToString(d),
                Base64Encoder.encode(d),
            )
        }
    }

    @Test fun chunked_dex_decodes_to_the_same_checksum() {
        val dex = ByteArray(7575) { (it * 13 + 5).toByte() }
        val expected = MessageDigest.getInstance("SHA-256").digest(dex).joinToString("") { "%02x".format(it) }
        val commands = AppFunctionsBinder.stageCommands(Base64Encoder.encode(dex), dir, file)
        val decoded = java.util.Base64.getDecoder().decode(commands.joinToString("") { payload(it) })
        val actual = MessageDigest.getInstance("SHA-256").digest(decoded).joinToString("") { "%02x".format(it) }
        assertEquals(expected, actual)
    }
}

/** The line `AfcProcess` runs on the device. */
class AfcProcessCommandLineTest {

    @Test fun line_carries_jar_binary_and_individually_quoted_arguments() {
        val line = AfcProcess.commandLine("/data/app/x/lib/arm64/libafc.so", listOf("call", "p.k", "p.k#f", "--args", "{\"a\":1}"))
        assertTrue(line, line.startsWith("AFC_JAR="))
        assertTrue(line.contains("'/data/app/x/lib/arm64/libafc.so'"))
        assertTrue(line.contains("'call' 'p.k' 'p.k#f' '--args' '{\"a\":1}'"))
    }

    @Test fun a_quote_in_an_argument_does_not_break_the_line_open() {
        val line = AfcProcess.commandLine("/b", listOf("a'b; id"))
        assertTrue(line, line.endsWith("'a'\\''b; id'"))
    }

    @Test fun normal_call_stays_far_below_the_limit() {
        val line = AfcProcess.commandLine("/data/app/~~x/com.openminis.app-y/lib/arm64/libafc.so", AfcProcess.callArguments("com.google.android.gms", "com.google.android.gms#getUncategorizedDeviceState", "{}", false))
        assertNull(AppFunctionsBinder.tooLong(line))
    }

    @Test fun oversized_arguments_are_rejected_instead_of_truncated() {
        val line = AfcProcess.commandLine("/b", listOf("call", "p", "f", "--args", "x".repeat(9000)))
        assertNotNull(AppFunctionsBinder.tooLong(line))
    }
}
