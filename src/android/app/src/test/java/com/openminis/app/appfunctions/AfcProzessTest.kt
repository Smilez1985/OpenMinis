package com.openminis.app.appfunctions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Die Hülle um `afc`: nur Quoting und Argumentliste sind reine Logik. */
class AfcProzessTest {

    @Test fun quote_umschliesst_und_maskiert_das_einfache_anfuehrungszeichen() {
        assertEquals("'abc'", AfcProzess.quote("abc"))
        assertEquals("'a'\\''b'", AfcProzess.quote("a'b"))
        assertEquals("''", AfcProzess.quote(""))
    }

    @Test fun quote_laesst_shell_metazeichen_wirkungslos() {
        // In einfachen Anfuehrungszeichen sind $, `, ;, | und Zeilenumbruch Text.
        val boese = "x'; rm -rf / #\$(id) `id` | cat\nzweite zeile"
        val q = AfcProzess.quote(boese)
        assertTrue(q.startsWith("'") && q.endsWith("'"))
        // Es darf kein unmaskiertes ' im Inneren stehen.
        assertFalse(q.substring(1, q.length - 1).replace("'\\''", "").contains("'"))
    }

    @Test fun call_argumente_sind_ein_array_ohne_zusammenbau() {
        val a = AfcProzess.callArgumente("p.k", "p.k#f", """{"a":"b c"}""", 30, schreibenErlaubt = false)
        assertEquals(listOf("call", "p.k", "p.k#f", "--args", """{"a":"b c"}""", "--zeit", "30"), a)
    }

    @Test fun schreiben_nur_wenn_erlaubt() {
        assertFalse(AfcProzess.callArgumente("p", "f", "{}", 5, false).contains("--schreiben"))
        assertTrue(AfcProzess.callArgumente("p", "f", "{}", 5, true).contains("--schreiben"))
    }
}

/** `AfcProzess.deute` gegen echte Ausgaben von `afc call` (31.10.), Konto-IDs geschwärzt. */
class AfcProzessDeutungTest {
    private fun roh(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("appfunctions/afcout/$name")!!.use { it.readBytes().toString(Charsets.UTF_8) }

    private fun deute(name: String): AfcProzess.Ergebnis {
        val t = roh(name)
        val exit = Regex("""exit=(\d+)""").find(t)!!.groupValues[1].toInt()
        return AfcProzess.deute(t, exit)
    }

    @Test fun erfolg_traegt_das_json() {
        val e = deute("erfolg.txt")
        assertTrue(e.toString(), e is AfcProzess.Ergebnis.Erfolg)
        assertTrue((e as AfcProzess.Ergebnis.Erfolg).json.startsWith("{\"androidAppfunctionsReturnValue\""))
    }

    @Test fun leer_ist_leer_obwohl_erfolg_im_text_steht() {
        assertEquals(AfcProzess.Ergebnis.Leer, deute("leer.txt"))
    }

    @Test fun bestandene_pruefung_ohne_zustimmung_ist_nicht_erfolg_obwohl_exit_null() {
        assertEquals(AfcProzess.Ergebnis.BrauchtZustimmung, deute("schreiben.txt"))
    }

    @Test fun pruefungsfehler_und_unbekannte_funktion_sind_fehler() {
        val p = deute("pruefung.txt")
        assertTrue(p is AfcProzess.Ergebnis.Fehler && p.text.contains("P1"))
        val n = deute("nofunc.txt")
        assertTrue(n is AfcProzess.Ergebnis.Fehler && n.text.contains("keine Funktion passt"))
    }

    @Test fun erfolgszeile_mit_exit_zwei_ist_kein_erfolg() {
        assertTrue(AfcProzess.deute("ERFOLG: {}\n", 2) is AfcProzess.Ergebnis.Fehler)
    }
}
