package com.l0gic0l.anduino.core

import com.l0gic0l.anduino.cli.ArduinoCli
import com.l0gic0l.anduino.cli.CliException
import com.l0gic0l.anduino.env.LinuxEnv
import com.l0gic0l.anduino.flash.BuildProperties
import com.l0gic0l.anduino.flash.EspChip
import com.l0gic0l.anduino.flash.EspFlashPlan
import com.l0gic0l.anduino.flash.EspLoader
import com.l0gic0l.anduino.flash.FlashPlanParser
import com.l0gic0l.anduino.flash.IntelHex
import com.l0gic0l.anduino.flash.Stk500v1
import com.l0gic0l.anduino.flash.UploadCancelledException
import com.l0gic0l.anduino.flash.UploadException
import com.l0gic0l.anduino.serial.SerialMonitor
import com.l0gic0l.anduino.usb.UsbPorts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/** Compile and upload, the two buttons of the IDE toolbar. */
class BuildActions(
    private val env: LinuxEnv,
    private val cli: ArduinoCli,
    private val usbPorts: UsbPorts,
    private val monitor: SerialMonitor,
    private val console: ConsoleLog,
    private val prefs: Prefs,
) {
    /** Compiles [sketchDir]; returns the guest build path on success, null on failure. */
    suspend fun compile(job: JobScope, sketchDir: File, fqbn: String): String? {
        val guestSketch = env.guestPath(sketchDir) ?: throw CliException("Sketch is outside the sketchbook")
        val buildPath = cli.buildPathFor(sketchDir.name, fqbn)
        job.step("Compiling ${sketchDir.name}")
        console.info("Compiling ${sketchDir.name} for $fqbn")
        val started = System.currentTimeMillis()
        val code = cli.compile(guestSketch, fqbn, buildPath, prefs.warnings.value, prefs.verboseCompile.value) {
            console.append(it)
            if (it.transient) job.step(it.text)
        }
        val seconds = (System.currentTimeMillis() - started) / 1000.0
        return if (code == 0) {
            console.success("Compilation finished in %.1f s.".format(seconds))
            buildPath
        } else {
            console.error("Compilation failed (exit code $code).")
            null
        }
    }

    suspend fun upload(job: JobScope, sketchDir: File, fqbn: String, port: String) {
        val buildPath = compile(job, sketchDir, fqbn) ?: return
        val guestSketch = env.guestPath(sketchDir)!!

        if (port.startsWith(OTA_PREFIX)) {
            val host = port.removePrefix(OTA_PREFIX)
            job.step("Uploading over the network to $host")
            val code = cli.uploadNetwork(guestSketch, fqbn, buildPath, host, prefs.otaPassword.value) { console.append(it) }
            if (code == 0) console.success("Upload complete.") else console.error("Network upload failed (exit code $code).")
            return
        }

        job.step("Reading board upload settings")
        val props = cli.buildProperties(guestSketch, fqbn, buildPath)
        val tool = BuildProperties.uploadTool(props) ?: ""
        val protocol = props["upload.protocol"] ?: ""
        monitor.withPortReleased {
            when {
                tool.startsWith("esptool") -> uploadEsp(job, props, buildPath, port)
                tool == "avrdude" && protocol in setOf("arduino", "stk500", "stk500v1") ->
                    uploadAvr(job, props, buildPath, port)
                else -> throw UploadException(
                    "This board uploads with \"$tool\" ($protocol), which has no Android implementation yet. " +
                        "The compiled files are in ${env.hostPath(buildPath)}; use Export binary to share them.",
                )
            }
        }
    }

    // ------------------------------------------------------------------ ESP32

    private fun espPlan(props: Map<String, String>, buildPath: String): EspFlashPlan {
        val mcu = props["build.mcu"] ?: throw UploadException("Board has no build.mcu property")
        val flashArgs = env.hostPath("$buildPath/flash_args")
        var (segments, size) = if (flashArgs.isFile) {
            FlashPlanParser.parseFlashArgs(flashArgs.readText(), buildPath)
        } else {
            emptyList<com.l0gic0l.anduino.flash.FlashSegment>() to null
        }
        var erase = props["upload.erase_cmd"]?.trim() == "-e"
        if (segments.isEmpty()) {
            val pattern = props["tools.esptool_py.upload.pattern_args"] ?: props["upload.pattern_args"] ?: ""
            val parsed = FlashPlanParser.parseEsptoolArgs(pattern)
            segments = parsed.first
            size = parsed.second
            erase = erase || parsed.third
        }
        if (segments.isEmpty()) throw UploadException("Could not determine what to flash (no flash_args in build output)")
        return EspFlashPlan(
            mcu = mcu,
            segments = segments,
            flashSizeBytes = size ?: FlashPlanParser.parseSize(props["build.flash_size"]),
            eraseAll = erase,
            uploadBaud = props["upload.speed"]?.toIntOrNull() ?: 921600,
        )
    }

    private suspend fun uploadEsp(job: JobScope, props: Map<String, String>, buildPath: String, port: String) {
        val plan = espPlan(props, buildPath)
        val expected = EspChip.fromMcu(plan.mcu)
        if (expected == null || expected == EspChip.ESP8266) {
            throw UploadException("Native upload for ${plan.mcu} is not supported yet; ESP32-family chips are.")
        }
        val images = plan.segments.map { seg ->
            val f = env.hostPath(seg.path)
            if (!f.isFile) throw UploadException("Missing build output ${seg.path}")
            EspLoader.Image(seg.address, f.readBytes(), f.name)
        }

        var device = port
        var resetMode = EspLoader.ResetMode.DEFAULT
        if (props["upload.use_1200bps_touch"] == "true") {
            // TinyUSB CDC boards re-enumerate in ROM download mode after a 1200 bps touch.
            job.step("Resetting board into bootloader (1200 bps touch)")
            val before = usbPorts.ports.value.map { it.deviceName }.toSet() - port
            runCatching { usbPorts.open(port, 1200).apply { setDtr(false) }.close() }
            delay(500)
            if (props["upload.wait_for_upload_port"] == "true") {
                val newPort = usbPorts.awaitNewPort(before)
                    ?: throw UploadException("Board did not reappear in bootloader mode. Hold BOOT and tap RESET, then retry.")
                device = newPort.deviceName
            }
            resetMode = EspLoader.ResetMode.NO_RESET
        }

        job.step("Connecting to ${expected.displayName}", null)
        val transport = usbPorts.open(device, EspLoader.ROM_BAUD)
        val coroutineJob = coroutineContext[Job]
        try {
            withContext(Dispatchers.IO) {
                val loader = EspLoader(
                    transport,
                    log = { console.info(it) },
                    checkCancelled = { if (coroutineJob?.isActive == false) throw UploadCancelledException() },
                )
                loader.connect(resetMode)
                val chip = loader.detectChip()
                console.info("Chip is ${chip.displayName}")
                if (chip != expected) {
                    throw UploadException(
                        "The connected chip is ${chip.displayName} but the sketch was compiled for ${expected.displayName}. " +
                            "Select the matching board.",
                    )
                }
                loader.spiAttach(chip)
                loader.setFlashParameters(plan.flashSizeBytes ?: (4L * 1024 * 1024))
                val nativeUsb = transport.usbVendorId == EspLoader.ESPRESSIF_VID
                if (!nativeUsb && plan.uploadBaud != EspLoader.ROM_BAUD) loader.changeBaud(plan.uploadBaud)
                if (plan.eraseAll) console.info("Note: \"Erase all flash\" needs the flasher stub; only the written regions are erased.")
                loader.writeFlash(chip, images) { p -> job.step("Writing flash", p) }
                if (transport.usbProductId == 0x0002 && nativeUsb) {
                    console.info("Board is in USB download mode: press RESET to start the new firmware.")
                } else {
                    loader.hardReset(usesUsb = nativeUsb)
                }
            }
            console.success("Upload complete.")
        } finally {
            transport.close()
        }
    }

    // ------------------------------------------------------------------ AVR

    private suspend fun uploadAvr(job: JobScope, props: Map<String, String>, buildPath: String, port: String) {
        val project = props["build.project_name"] ?: throw UploadException("Missing build.project_name")
        val hex = env.hostPath("$buildPath/$project.hex")
        if (!hex.isFile) throw UploadException("Missing $project.hex")
        val image = IntelHex.parse(hex.readText())
        val baud = props["upload.speed"]?.toIntOrNull() ?: 115200
        val pageSize = if (props["build.mcu"] == "atmega8") 64 else 128
        job.step("Uploading ${image.size} bytes")
        val transport = usbPorts.open(port, baud)
        val coroutineJob = coroutineContext[Job]
        try {
            withContext(Dispatchers.IO) {
                Stk500v1(
                    transport,
                    log = { console.info(it) },
                    checkCancelled = { if (coroutineJob?.isActive == false) throw UploadCancelledException() },
                ).upload(image, pageSize) { p -> job.step("Writing flash", p) }
            }
            console.success("Upload complete.")
        } finally {
            transport.close()
        }
    }

    companion object {
        const val OTA_PREFIX = "ota:"
    }
}
