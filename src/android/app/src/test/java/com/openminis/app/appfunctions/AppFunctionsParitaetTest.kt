package com.openminis.app.appfunctions

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kotlin und `deps/afc` (Rust) müssen dasselbe sagen. Die Erwartungen stehen
 * hier fest und stammen aus einem Lauf von `afc check` und `Antwort::deute`
 * über dieselben Fälle (31.10.). Ändert eine Seite eine Regel, wird dieser
 * Test rot, bis die andere nachzieht — das ist der Zweck.
 */
class AppFunctionsParitaetTest {

    private val registry: List<AppFunctionsRegistry.Funktion> by lazy {
        val text = javaClass.classLoader!!.getResourceAsStream("appfunctions/registry.txt")!!
            .use { it.readBytes().toString(Charsets.UTF_8) }
        AppFunctionsRegistry.ausText(text)
    }

    /** "FEHLER P1,P3 | P4" bzw. "OK a3=… | P4": nur Regelkennungen, wie `afc check`. */
    private fun urteil(paket: String, name: String, args: String): String {
        val f = AppFunctionsRegistry.finde(registry, paket, name).getOrThrow()
        val p = AppFunctionsRegistry.pruefen(f, JSONObject(args))
        val fehler = p.befunde.filter { it.fehler }.map { it.regel }.toSortedSet()
        val hinweis = p.befunde.filter { !it.fehler }.map { it.regel }.toSortedSet()
        return if (fehler.isNotEmpty()) "FEHLER ${fehler.joinToString(",")} | ${hinweis.joinToString(",")}"
        else "OK ${p.a3} | ${hinweis.joinToString(",")}"
    }

    private val kontakte = "com.samsung.android.app.contacts"

    @Test fun p0_bis_p5_wie_in_rust() {
        assertEquals("OK findPersonsParams | ", urteil(kontakte, "findPersons", """{"query":"e","maxCount":3}"""))
        assertEquals("FEHLER P1 | ", urteil(kontakte, "findPersons", """{"query":"e"}"""))
        assertEquals("FEHLER P3 | ", urteil(kontakte, "findPersons", """{"query":"e","maxCount":"3"}"""))
        assertEquals("FEHLER P5 | ", urteil(kontakte, "findPersons", """{"query":"","maxCount":3}"""))
        assertEquals("FEHLER P2 | ", urteil(kontakte, "findPersons", """{"query":"e","maxCount":3,"foo":1}"""))
        assertEquals("FEHLER P1 | ", urteil(kontakte, "findPersons", "{}"))
        assertEquals("OK findPersonsParams | P4", urteil(kontakte, "findPersons", """{"query":"e","maxCount":99}"""))
        assertEquals("OK findPersonsParams | P4", urteil(kontakte, "findPersons", """{"query":"e","maxCount":0}"""))
        assertEquals("OK findPersonsParams | P4", urteil(kontakte, "findPersons", """{"query":"e","maxCount":-1}"""))
    }

    @Test fun parameter_der_obersten_ebene_wie_in_rust() {
        assertEquals("OK @ | ", urteil(kontakte, "getPersons", """{"personIds":["73"]}"""))
        assertEquals("FEHLER P3 | ", urteil(kontakte, "getPersons", """{"personIds":"73"}"""))
        assertEquals("FEHLER P2 | ", urteil(kontakte, "getPersons", """{"personIds":["73"],"x":1}"""))
        // fehlt der Parameter, mit dem ein Aufruf gelang: Hinweis P1, kein Fehler (Rust: pruefen.rs)
        assertEquals("OK @ | P1", urteil(kontakte, "getPersons", "{}"))
    }

    @Test fun funktion_ohne_parameterangabe() {
        assertEquals("FEHLER P2 | ", urteil("com.google.android.gms", "getDeviceStateMetadata", """{"a":1}"""))
    }

    @Test fun unbestaetigter_leerer_aufruf_hat_nur_den_typ() {
        val f = AppFunctionsRegistry.finde(registry, "com.google.android.gms", "getDeviceStateMetadata").getOrThrow()
        val p = AppFunctionsRegistry.pruefen(f, JSONObject("{}"))
        assertEquals("getDeviceStateMetadataParams", p.a3)
        assertTrue(p.a4.contains("GetDeviceStateMetadataParams"))
    }

    private fun art(roh: String) = AppFunctionsAntwort.deute(roh).art

    @Test fun antwortdeutung_wie_in_rust() {
        assertEquals(AppFunctionsAntwort.Art.ERFOLG, art("JSON: {\"androidAppfunctionsReturnValue\":[{\"id\":\"1\"}]}\nERFOLG: ok\nFERTIG\n"))
        assertEquals(AppFunctionsAntwort.Art.LEER, art("JSON: {\"androidAppfunctionsReturnValue\":[]}\nERFOLG: ok\nFERTIG\n"))
        assertEquals(AppFunctionsAntwort.Art.LEER, art("ERFOLG: null\nFERTIG\n"))
        assertEquals(AppFunctionsAntwort.Art.LEER, art("JSON: {}\nERFOLG: ok\n"))
        assertEquals(AppFunctionsAntwort.Art.LEER, art("JSON: [ ]\nERFOLG: ok\n"))
        // Rust: "ERFOLG: null" ist LEER, auch wenn vorher JSON kam.
        assertEquals(AppFunctionsAntwort.Art.LEER, art("JSON: {\"a\":1}\nERFOLG: null\nFERTIG\n"))
        assertEquals(AppFunctionsAntwort.Art.FEHLER, art("FEHLER: code=1001 kat=APP_ERROR msg=Invalid Argument\nFERTIG\n"))
        assertEquals(AppFunctionsAntwort.Art.ZEITUEBERSCHREITUNG, art("ZEITUEBERSCHREITUNG\n"))
        assertEquals(AppFunctionsAntwort.Art.UNKLAR, art("java.lang.NoSuchMethodException: x\n"))
        assertEquals(AppFunctionsAntwort.Art.UNKLAR, art(""))
        assertEquals(AppFunctionsAntwort.Art.ERFOLG, art("ERFOLG: irgendwas\nFERTIG\n"))
    }

    @Test fun fehlercode_und_meldung_werden_gelesen() {
        val a = AppFunctionsAntwort.deute("FEHLER: code=NO_CONSTRUCTOR kat=X msg=ctor weg\n")
        assertEquals("NO_CONSTRUCTOR", a.code)
        assertEquals("X", a.kategorie)
        assertEquals("ctor weg", a.meldung)
    }
}
