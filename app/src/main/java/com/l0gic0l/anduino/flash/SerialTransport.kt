package com.l0gic0l.anduino.flash

import java.io.IOException

/**
 * Minimal blocking serial port abstraction used by the uploaders. The real
 * implementation wraps usb-serial-for-android; unit tests use a fake device.
 */
interface SerialTransport {
    /** USB vendor/product id of the adapter, if known. */
    val usbVendorId: Int?
    val usbProductId: Int?

    /** Reads up to [buf].size bytes. Returns 0 if nothing arrived within [timeoutMs]. */
    @Throws(IOException::class)
    fun read(buf: ByteArray, timeoutMs: Int): Int

    @Throws(IOException::class)
    fun write(data: ByteArray)

    @Throws(IOException::class)
    fun setDtr(value: Boolean)

    @Throws(IOException::class)
    fun setRts(value: Boolean)

    @Throws(IOException::class)
    fun setBaudRate(baud: Int)

    /** Discards anything already received. */
    @Throws(IOException::class)
    fun purgeInput()
}

/** Thrown when the user cancels a running upload. */
class UploadCancelledException : IOException("Upload cancelled")

class UploadException(message: String, cause: Throwable? = null) : IOException(message, cause)
