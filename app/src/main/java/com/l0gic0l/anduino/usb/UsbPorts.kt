package com.l0gic0l.anduino.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.Ch34xSerialDriver
import com.hoho.android.usbserial.driver.Cp21xxSerialDriver
import com.hoho.android.usbserial.driver.FtdiSerialDriver
import com.hoho.android.usbserial.driver.ProlificSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.l0gic0l.anduino.flash.SerialTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

data class UsbPortInfo(
    /** Stable for as long as the device stays plugged in, e.g. /dev/bus/usb/001/004. */
    val deviceName: String,
    val vendorId: Int,
    val productId: Int,
    val label: String,
    val detail: String,
    val hasPermission: Boolean,
)

/** Enumerates USB serial adapters and opens them (asking for permission when needed). */
class UsbPorts(private val context: Context) {
    private val usbManager = context.getSystemService(UsbManager::class.java)
    private val prober = UsbSerialProber.getDefaultProber()

    private val _ports = MutableStateFlow<List<UsbPortInfo>>(emptyList())
    val ports: StateFlow<List<UsbPortInfo>> = _ports.asStateFlow()

    private val pendingPermission = HashMap<String, CompletableDeferred<Boolean>>()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                ACTION_PERMISSION -> {
                    val device = intent.usbDevice()
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    device?.let { synchronized(pendingPermission) { pendingPermission.remove(it.deviceName) } }
                        ?.complete(granted)
                    refresh()
                }
                else -> refresh()
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(ACTION_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        refresh()
    }

    fun refresh() {
        _ports.value = prober.findAllDrivers(usbManager).map { driver ->
            val d = driver.device
            UsbPortInfo(
                deviceName = d.deviceName,
                vendorId = d.vendorId,
                productId = d.productId,
                label = friendlyName(driver),
                detail = "%04X:%04X · %s".format(d.vendorId, d.productId, d.deviceName.substringAfter("/dev/bus/usb/")),
                hasPermission = usbManager.hasPermission(d),
            )
        }
    }

    private fun friendlyName(driver: UsbSerialDriver): String {
        val d = driver.device
        val product = runCatching { d.productName }.getOrNull()?.takeIf { it.isNotBlank() }
        val known = when {
            d.vendorId == 0x303A && d.productId == 0x1001 -> "ESP32 USB-Serial/JTAG"
            d.vendorId == 0x303A && d.productId == 0x0002 -> "ESP32 USB download mode"
            d.vendorId == 0x303A -> "Espressif native USB"
            driver is Cp21xxSerialDriver -> "CP210x"
            driver is Ch34xSerialDriver -> "CH34x"
            driver is FtdiSerialDriver -> "FTDI"
            driver is ProlificSerialDriver -> "PL2303"
            d.vendorId == 0x2341 || d.vendorId == 0x2A03 -> "Arduino"
            else -> null
        }
        return listOfNotNull(product, known?.takeIf { product == null || !product.contains(it, true) })
            .joinToString(" · ").ifEmpty { "USB serial" }
    }

    private fun findDevice(deviceName: String): UsbDevice? = usbManager.deviceList[deviceName]

    /** Suspends until the user answers the system USB permission dialog. */
    suspend fun requestPermission(device: UsbDevice): Boolean {
        if (usbManager.hasPermission(device)) return true
        val deferred = CompletableDeferred<Boolean>()
        synchronized(pendingPermission) { pendingPermission[device.deviceName] = deferred }
        val intent = Intent(ACTION_PERMISSION).setPackage(context.packageName)
        val flags = PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val pi = PendingIntent.getBroadcast(context, device.deviceId, intent, flags)
        usbManager.requestPermission(device, pi)
        return withTimeoutOrNull(120_000) { deferred.await() } ?: false
    }

    /** Opens [deviceName] at [baud], 8N1. */
    suspend fun open(deviceName: String, baud: Int): UsbSerialTransport {
        val device = findDevice(deviceName) ?: throw IOException("USB device is no longer connected")
        if (!requestPermission(device)) throw IOException("USB permission denied")
        val driver = prober.probeDevice(device) ?: throw IOException("Not a supported USB serial device")
        val connection = usbManager.openDevice(device) ?: throw IOException("Could not open USB device")
        val port = driver.ports.first()
        try {
            port.open(connection)
            port.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        } catch (e: Exception) {
            runCatching { port.close() }
            throw IOException("Could not open serial port: ${e.message}", e)
        }
        refresh()
        return UsbSerialTransport(port)
    }

    /**
     * Waits for a USB serial device that is not in [before] to appear (used after a
     * 1200 bps "touch" makes the board re-enumerate in bootloader mode).
     */
    suspend fun awaitNewPort(before: Set<String>, timeoutMs: Long = 10_000): UsbPortInfo? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            refresh()
            ports.value.firstOrNull { it.deviceName !in before }?.let { return it }
            delay(250)
        }
        return null
    }

    private fun Intent.usbDevice(): UsbDevice? =
        if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        else @Suppress("DEPRECATION") getParcelableExtra(UsbManager.EXTRA_DEVICE)

    companion object {
        private const val ACTION_PERMISSION = "com.l0gic0l.anduino.USB_PERMISSION"
    }
}

/** [SerialTransport] over usb-serial-for-android. */
class UsbSerialTransport(val port: UsbSerialPort) : SerialTransport {
    override val usbVendorId: Int = port.device.vendorId
    override val usbProductId: Int = port.device.productId
    private val scratch = ByteArray(maxOf(port.readEndpoint?.maxPacketSize ?: 64, 64) * 8)

    override fun read(buf: ByteArray, timeoutMs: Int): Int =
        port.read(buf, timeoutMs.coerceAtLeast(1))

    override fun write(data: ByteArray) = port.write(data, WRITE_TIMEOUT_MS)

    override fun setDtr(value: Boolean) = port.setDTR(value)

    override fun setRts(value: Boolean) = port.setRTS(value)

    override fun setBaudRate(baud: Int) =
        port.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)

    override fun purgeInput() {
        try {
            port.purgeHwBuffers(false, true)
        } catch (_: UnsupportedOperationException) {
        } catch (_: IOException) {
        }
        // Not every chip can purge in hardware; drain what is already queued.
        for (round in 0 until 50) {
            if (port.read(scratch, 5) <= 0) break
        }
    }

    fun close() {
        runCatching { port.close() }
    }

    companion object {
        const val WRITE_TIMEOUT_MS = 2000
    }
}
