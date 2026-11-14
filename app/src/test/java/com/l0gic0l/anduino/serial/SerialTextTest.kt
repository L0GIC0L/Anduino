package com.l0gic0l.anduino.serial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SerialTextTest {
    @Test
    fun plotterParsesIde2Formats() {
        assertEquals(listOf("value 1" to 1f, "value 2" to 2.5f, "value 3" to -3f), PlotterParser.parse("1 2.5 -3"))
        assertEquals(listOf("value 1" to 1f, "value 2" to 2f), PlotterParser.parse("1,2\r"))
        assertEquals(listOf("temp" to 21.5f, "hum" to 40f), PlotterParser.parse("temp:21.5,hum:40"))
        assertEquals(listOf("Temp C" to 21.5f, "RH %" to 40f), PlotterParser.parse("Temp C:21.5\tRH %:40"))
        assertEquals(listOf("a" to 1f, "b" to 2f), PlotterParser.parse("a:1 b:2"))
        assertTrue(PlotterParser.parse("Booting...").isEmpty())
        assertTrue(PlotterParser.parse("").isEmpty())
    }

    @Test
    fun plotterDataKeepsWindowAndAlignsSeries() {
        val d = PlotterData(capacity = 10)
        repeat(15) { d.add(listOf("a" to it.toFloat())) }
        d.add(listOf("a" to 1f, "b" to 100f))
        assertEquals(10, d.size)
        assertEquals(listOf("a", "b"), d.labels)
        assertTrue(d.value(0, 1).isNaN())
        assertEquals(100f, d.value(9, 1))
        assertEquals(1f to 100f, d.range { true })
        assertEquals(1f to 14f, d.range { it == 0 })
    }

    @Test
    fun lineAssemblerHandlesSplitUtf8AndCrLf() {
        val la = LineAssembler()
        val bytes = "Grüße\r\nok\nlast".toByteArray()
        val lines = ArrayList<String>()
        // Feed one byte at a time to split the multi-byte characters.
        for (b in bytes) lines += la.feed(byteArrayOf(b))
        assertEquals(listOf("Grüße", "ok"), lines)
        assertEquals("last", la.pending)
    }
}
