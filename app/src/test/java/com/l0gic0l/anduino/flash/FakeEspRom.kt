package com.l0gic0l.anduino.flash

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.Inflater

/**
 * Simulates an Espressif ROM bootloader on the other end of a serial line,
 * closely enough to exercise [EspLoader] end to end.
 */
class FakeEspRom(private val chip: EspChip) : SerialTransport {
    override val usbVendorId: Int? = 0x10C4 // CP2102
    override val usbProductId: Int? = 0xEA60

    val flash = HashMap<Long, Byte>()
    val commandLog = mutableListOf<Int>()
    val beginParamSizes = mutableListOf<Int>()
    var baud = 115200
    var spiAttached = false
    var flashSizeSet: Long? = null
    var resetPulses = 0

    private val decoder = SlipDecoder()
    private val outgoing = ArrayDeque<Byte>()
    private var rts = false

    private var deflBuffer = ByteArrayOutputStream()
    private var deflOffset = 0L
    private var deflExpectedSeq = 0

    override fun read(buf: ByteArray, timeoutMs: Int): Int {
        var n = 0
        while (n < buf.size && outgoing.isNotEmpty()) buf[n++] = outgoing.removeFirst()
        return n
    }

    override fun write(data: ByteArray) {
        decoder.feed(data)
        while (true) handle(decoder.poll() ?: break)
    }

    override fun setDtr(value: Boolean) {}

    override fun setRts(value: Boolean) {
        if (rts && !value) resetPulses++
        rts = value
    }

    override fun setBaudRate(baud: Int) { this.baud = baud }

    override fun purgeInput() { outgoing.clear() }

    private fun handle(p: ByteArray) {
        val bb = ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN)
        require(bb.get().toInt() == 0) { "request direction must be 0" }
        val op = bb.get().toInt() and 0xFF
        val len = bb.short.toInt() and 0xFFFF
        val chk = bb.int
        val data = p.copyOfRange(8, 8 + len)
        commandLog += op
        when (op) {
            EspLoader.OP_SYNC -> repeat(8) { reply(op, 0x20120707, ok()) }
            EspLoader.OP_READ_REG -> {
                val addr = u32(data, 0)
                val v = when {
                    addr == EspLoader.CHIP_DETECT_MAGIC_REG_ADDR -> chip.magicValue ?: 0xDEADBEEF
                    else -> 0L // eFuse reads: default SPI pads
                }
                reply(op, v, ok())
            }
            EspLoader.OP_GET_SECURITY_INFO -> {
                val id = chip.chipId
                if (id == null) {
                    repeat(3) { reply(op, 0, byteArrayOf(1, 5, 0, 0)) } // unsupported -> error, several times
                } else {
                    val info = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
                        .putInt(0).put(ByteArray(8)).putInt(id).putInt(0).array()
                    reply(op, 0, info + ok())
                }
            }
            EspLoader.OP_SPI_ATTACH -> { spiAttached = true; reply(op, 0, ok()) }
            EspLoader.OP_SPI_SET_PARAMS -> { flashSizeSet = u32(data, 4); reply(op, 0, ok()) }
            EspLoader.OP_CHANGE_BAUDRATE -> reply(op, 0, ok())
            EspLoader.OP_FLASH_DEFL_BEGIN -> {
                check(spiAttached) { "flash not attached" }
                beginParamSizes += data.size
                deflOffset = u32(data, 12)
                deflBuffer = ByteArrayOutputStream()
                deflExpectedSeq = 0
                reply(op, 0, ok())
            }
            EspLoader.OP_FLASH_DEFL_DATA -> {
                val size = u32(data, 0).toInt()
                val seq = u32(data, 4).toInt()
                val block = data.copyOfRange(16, 16 + size)
                if (seq != deflExpectedSeq || EspLoader.checksum(block).toInt() != chk) {
                    reply(op, 0, byteArrayOf(1, 7, 0, 0))
                } else {
                    deflExpectedSeq++
                    deflBuffer.write(block)
                    // The real ROM inflates as it goes; doing it on every block is fine here.
                    val inflated = inflate(deflBuffer.toByteArray())
                    inflated.forEachIndexed { i, b -> flash[deflOffset + i] = b }
                    reply(op, 0, ok())
                }
            }
            EspLoader.OP_SPI_FLASH_MD5 -> {
                val addr = u32(data, 0)
                val size = u32(data, 4).toInt()
                val bytes = ByteArray(size) { flash[addr + it] ?: 0xFF.toByte() }
                val hex = MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
                reply(op, 0, hex.toByteArray(Charsets.US_ASCII) + ok())
            }
            else -> reply(op, 0, byteArrayOf(1, 5, 0, 0))
        }
    }

    /** ESP32-family ROMs send 4 status bytes. */
    private fun ok() = byteArrayOf(0, 0, 0, 0)

    private fun reply(op: Int, value: Long, body: ByteArray) {
        val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .put(1).put(op.toByte()).putShort(body.size.toShort()).putInt(value.toInt()).array()
        Slip.encode(header + body).forEach { outgoing.addLast(it) }
    }

    private fun inflate(data: ByteArray): ByteArray {
        val inf = Inflater()
        inf.setInput(data)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (!inf.finished()) {
            val n = inf.inflate(buf)
            if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
            out.write(buf, 0, n)
        }
        inf.end()
        return out.toByteArray()
    }

    private fun u32(b: ByteArray, off: Int) = EspLoader.u32(b, off)
}
