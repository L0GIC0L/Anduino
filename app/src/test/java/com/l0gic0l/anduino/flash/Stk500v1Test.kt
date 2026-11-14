package com.l0gic0l.anduino.flash

import org.junit.Assert.assertArrayEquals
import org.junit.Test

/** Minimal Optiboot simulation. */
private class FakeOptiboot : SerialTransport {
    override val usbVendorId: Int? = 0x2341
    override val usbProductId: Int? = 0x0043
    val flash = ByteArray(32 * 1024) { 0xFF.toByte() }
    private val input = ArrayList<Byte>()
    private val output = ArrayDeque<Byte>()
    private var address = 0

    override fun read(buf: ByteArray, timeoutMs: Int): Int {
        var n = 0
        while (n < buf.size && output.isNotEmpty()) buf[n++] = output.removeFirst()
        return n
    }

    override fun write(data: ByteArray) {
        input.addAll(data.toList())
        process()
    }

    private fun ok(vararg data: Byte) {
        output.addLast(0x14)
        data.forEach { output.addLast(it) }
        output.addLast(0x10)
    }

    private fun process() {
        while (input.isNotEmpty()) {
            val cmd = input[0].toInt() and 0xFF
            val total = when (cmd) {
                0x55 -> 4
                0x64 -> if (input.size >= 3) 5 + (((input[1].toInt() and 0xFF) shl 8) or (input[2].toInt() and 0xFF)) else return
                0x74 -> 5
                else -> 2
            }
            if (input.size < total) return
            val pkt = input.subList(0, total).toByteArray()
            repeat(total) { input.removeAt(0) }
            when (cmd) {
                0x30, 0x50, 0x51 -> ok()
                0x75 -> ok(0x1E, 0x95.toByte(), 0x0F)
                0x55 -> { address = ((pkt[1].toInt() and 0xFF) or ((pkt[2].toInt() and 0xFF) shl 8)) * 2; ok() }
                0x64 -> {
                    val len = ((pkt[1].toInt() and 0xFF) shl 8) or (pkt[2].toInt() and 0xFF)
                    System.arraycopy(pkt, 4, flash, address, len)
                    ok()
                }
                0x74 -> {
                    val len = ((pkt[1].toInt() and 0xFF) shl 8) or (pkt[2].toInt() and 0xFF)
                    ok(*flash.copyOfRange(address, address + len))
                }
                else -> output.addLast(0x15)
            }
        }
    }

    override fun setDtr(value: Boolean) {}
    override fun setRts(value: Boolean) {}
    override fun setBaudRate(baud: Int) {}
    override fun purgeInput() { output.clear() }
}

class Stk500v1Test {
    @Test
    fun uploadsAndVerifiesImage() {
        val dev = FakeOptiboot()
        val image = ByteArray(1000) { (it * 7).toByte() }
        Stk500v1(dev, sleep = {}).upload(image) {}
        assertArrayEquals(image, dev.flash.copyOfRange(0, image.size))
    }
}
