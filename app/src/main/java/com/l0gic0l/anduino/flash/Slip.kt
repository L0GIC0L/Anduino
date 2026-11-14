package com.l0gic0l.anduino.flash

import java.io.ByteArrayOutputStream

/** SLIP framing (RFC 1055) as used by the Espressif ROM bootloader. */
object Slip {
    const val END: Int = 0xC0
    const val ESC: Int = 0xDB
    const val ESC_END: Int = 0xDC
    const val ESC_ESC: Int = 0xDD

    fun encode(packet: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(packet.size + 8)
        out.write(END)
        for (b in packet) {
            when (b.toInt() and 0xFF) {
                END -> { out.write(ESC); out.write(ESC_END) }
                ESC -> { out.write(ESC); out.write(ESC_ESC) }
                else -> out.write(b.toInt())
            }
        }
        out.write(END)
        return out.toByteArray()
    }
}

/**
 * Incremental SLIP decoder. Bytes outside of a frame (boot messages, noise) are
 * ignored, exactly like esptool's slip_reader.
 */
class SlipDecoder {
    private val current = ByteArrayOutputStream()
    private var inFrame = false
    private var escaping = false
    private val ready = ArrayDeque<ByteArray>()

    fun feed(data: ByteArray, length: Int = data.size) {
        for (i in 0 until length) {
            val b = data[i].toInt() and 0xFF
            if (!inFrame) {
                if (b == Slip.END) {
                    inFrame = true
                    current.reset()
                }
                continue
            }
            if (escaping) {
                escaping = false
                when (b) {
                    Slip.ESC_END -> current.write(Slip.END)
                    Slip.ESC_ESC -> current.write(Slip.ESC)
                    else -> { // invalid escape: drop the frame
                        inFrame = false
                        current.reset()
                    }
                }
                continue
            }
            when (b) {
                Slip.ESC -> escaping = true
                Slip.END -> {
                    if (current.size() > 0) {
                        ready.addLast(current.toByteArray())
                        inFrame = false
                    }
                    // An END right after END is a frame start; stay in frame.
                    current.reset()
                }
                else -> current.write(b)
            }
        }
    }

    fun poll(): ByteArray? = ready.removeFirstOrNull()

    fun reset() {
        ready.clear()
        current.reset()
        inFrame = false
        escaping = false
    }
}
