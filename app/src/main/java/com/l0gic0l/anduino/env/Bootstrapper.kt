package com.l0gic0l.anduino.env

import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * First-run installer: Ubuntu base rootfs -> python3 & CA certificates via apt ->
 * arduino-cli -> package indexes. Everything is downloaded on the device, so the APK
 * stays small and the toolchains always match what the Arduino ecosystem ships for
 * 64-bit Linux (the same builds a Raspberry Pi uses).
 */
class Bootstrapper(
    private val env: LinuxEnv,
    private val cacheDir: File,
) {
    fun interface Listener {
        /** [fraction] is null for indeterminate steps. */
        fun onProgress(step: String, fraction: Float?, line: OutputLine?)
    }

    private val linuxArch: String = when {
        Build.SUPPORTED_ABIS.firstOrNull() == "x86_64" -> "amd64"
        else -> "arm64"
    }

    private val arduinoCliAsset: String = if (linuxArch == "amd64") "Linux_64bit" else "Linux_ARM64"

    suspend fun install(
        dnsServers: List<String>,
        boardManagerUrls: List<String>,
        listener: Listener,
    ) = withContext(Dispatchers.IO) {
        if (!env.proot.canExecute()) {
            throw IOException("proot binary missing from the APK (${env.proot}). Was the app built for this CPU ABI?")
        }
        env.clearInstalledMarker()

        // 1. Ubuntu base
        if (!File(env.rootfs, "bin/bash").exists() && !File(env.rootfs, "usr/bin/bash").exists()) {
            val url = resolveUbuntuBaseUrl()
            val archive = File(cacheDir, url.substringAfterLast('/'))
            if (!archive.exists()) {
                listener.onProgress("Downloading Ubuntu base", 0f, OutputLine(url, false))
                Downloader.download(url, archive) { done, total ->
                    listener.onProgress("Downloading Ubuntu base", fraction(done, total), null)
                }
            }
            listener.onProgress("Extracting Ubuntu base", null, null)
            val staging = File(env.base, "rootfs.staging")
            staging.deleteRecursively()
            TarGz.extract(archive, staging, onEntry = { listener.onProgress("Extracting Ubuntu base", null, OutputLine(it, false, true)) })
            env.rootfs.deleteRecursively()
            if (!staging.renameTo(env.rootfs)) throw IOException("Could not move rootfs into place")
            archive.delete()
        }
        env.configureRootfs(dnsServers)

        // 2. Packages arduino cores expect on Linux (python3 runs gen_esp32part.py, espota.py, ...)
        if (!File(env.rootfs, "usr/bin/python3").exists() || !File(env.rootfs, "etc/ssl/certs/ca-certificates.crt").exists()) {
            runChecked(
                "Installing python3 and certificates (apt)",
                listOf(
                    "/bin/bash", "-c",
                    "apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends " +
                        "ca-certificates python3 && apt-get clean",
                ),
                listener,
            )
        }

        // 3. arduino-cli
        val cliUrl = "https://downloads.arduino.cc/arduino-cli/arduino-cli_latest_$arduinoCliAsset.tar.gz"
        val cliArchive = File(cacheDir, "arduino-cli.tar.gz")
        listener.onProgress("Downloading arduino-cli", 0f, OutputLine(cliUrl, false))
        Downloader.download(cliUrl, cliArchive) { done, total ->
            listener.onProgress("Downloading arduino-cli", fraction(done, total), null)
        }
        TarGz.extract(cliArchive, env.hostPath("/usr/local/bin")) { name ->
            if (name == "arduino-cli") name else null
        }
        cliArchive.delete()

        // 4. Configuration and indexes
        val cli = LinuxEnv.ARDUINO_CLI
        runChecked("Configuring arduino-cli", listOf(cli, "config", "init", "--overwrite"), listener)
        runChecked("Configuring arduino-cli", listOf(cli, "config", "set", "library.enable_unsafe_install", "true"), listener)
        runChecked("Configuring arduino-cli", listOf(cli, "config", "set", "network.connection_timeout", "300s"), listener)
        applyBoardManagerUrls(boardManagerUrls, listener)
        runChecked("Downloading board index", listOf(cli, "core", "update-index"), listener)
        runChecked("Downloading library index", listOf(cli, "lib", "update-index"), listener)
        runChecked("Checking arduino-cli", listOf(cli, "version"), listener)

        env.markInstalled("1")
        listener.onProgress("Ready", 1f, OutputLine("Linux environment installed.", false))
    }

    suspend fun applyBoardManagerUrls(urls: List<String>, listener: Listener) {
        val clean = urls.map { it.trim() }.filter { it.isNotEmpty() }
        val argv = if (clean.isEmpty()) {
            listOf(LinuxEnv.ARDUINO_CLI, "config", "delete", "board_manager.additional_urls")
        } else {
            listOf(LinuxEnv.ARDUINO_CLI, "config", "set", "board_manager.additional_urls") + clean
        }
        runChecked("Setting board manager URLs", argv, listener)
    }

    private suspend fun runChecked(step: String, argv: List<String>, listener: Listener) {
        listener.onProgress(step, null, OutputLine("$ ${argv.joinToString(" ")}", false))
        val code = env.stream(argv) { listener.onProgress(step, null, it) }
        if (code != 0) throw IOException("$step failed (exit code $code)")
    }

    /** Picks the newest 24.04 point release listed on cdimage.ubuntu.com. */
    private suspend fun resolveUbuntuBaseUrl(): String {
        val dir = "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/"
        return try {
            val html = Downloader.fetchText(dir)
            val name = Regex("ubuntu-base-24\\.04(?:\\.(\\d+))?-base-$linuxArch\\.tar\\.gz")
                .findAll(html)
                .maxByOrNull { it.groupValues[1].toIntOrNull() ?: 0 }
                ?.value
            dir + (name ?: "ubuntu-base-24.04.3-base-$linuxArch.tar.gz")
        } catch (e: IOException) {
            dir + "ubuntu-base-24.04.3-base-$linuxArch.tar.gz"
        }
    }

    private fun fraction(done: Long, total: Long): Float? =
        if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null

    companion object {
        const val ESP32_INDEX_URL = "https://espressif.github.io/arduino-esp32/package_esp32_index.json"
        const val RP2040_INDEX_URL = "https://github.com/earlephilhower/arduino-pico/releases/download/global/package_rp2040_index.json"
        const val STM32_INDEX_URL = "https://github.com/stm32duino/BoardManagerFiles/raw/main/package_stmicroelectronics_index.json"
        const val ESP8266_INDEX_URL = "https://arduino.esp8266.com/stable/package_esp8266com_index.json"

        val DEFAULT_URLS = listOf(ESP32_INDEX_URL)
        val PRESET_URLS = linkedMapOf(
            "Espressif ESP32" to ESP32_INDEX_URL,
            "ESP8266" to ESP8266_INDEX_URL,
            "Raspberry Pi Pico / RP2040 / RP2350" to RP2040_INDEX_URL,
            "STM32 (STMicroelectronics)" to STM32_INDEX_URL,
        )
    }
}
