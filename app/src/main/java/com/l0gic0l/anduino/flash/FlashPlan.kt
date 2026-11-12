package com.l0gic0l.anduino.flash

/** One file to write at a flash address. Paths are as the toolchain (guest) sees them. */
data class FlashSegment(val address: Long, val path: String)

data class EspFlashPlan(
    val mcu: String,
    val segments: List<FlashSegment>,
    val flashSizeBytes: Long?,
    val eraseAll: Boolean,
    val uploadBaud: Int,
)

/**
 * Works out what to flash from the build output of the Arduino ESP32 core.
 * Preferred source is the `flash_args` file the core writes into the build
 * directory; the expanded esptool upload command line is the fallback.
 */
object FlashPlanParser {

    /**
     * Parses `{build.path}/flash_args`:
     * ```
     * --flash-mode dio --flash-freq 80m --flash-size 4MB
     * 0x1000 sketch.ino.bootloader.bin
     * 0x8000 sketch.ino.partitions.bin
     * ```
     * Relative paths are resolved against [buildDir].
     */
    fun parseFlashArgs(text: String, buildDir: String): Pair<List<FlashSegment>, Long?> {
        val tokens = tokenize(text)
        return parseSegmentTokens(tokens) { p -> if (p.startsWith("/")) p else "${buildDir.trimEnd('/')}/$p" }
            .let { (segments, size, _) -> segments to size }
    }

    /**
     * Parses an esptool command line (e.g. `tools.esptool_py.upload.pattern_args`
     * after expansion) and returns the address/file pairs after `write-flash`.
     */
    fun parseEsptoolArgs(commandLine: String): Triple<List<FlashSegment>, Long?, Boolean> {
        val tokens = tokenize(commandLine)
        val start = tokens.indexOfFirst { it == "write-flash" || it == "write_flash" }
        if (start < 0) return Triple(emptyList(), null, false)
        return parseSegmentTokens(tokens.drop(start + 1)) { it }
    }

    private fun parseSegmentTokens(
        tokens: List<String>,
        resolve: (String) -> String,
    ): Triple<List<FlashSegment>, Long?, Boolean> {
        val segments = mutableListOf<FlashSegment>()
        var size: Long? = null
        var eraseAll = false
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            when {
                t == "-e" || t == "--erase-all" -> eraseAll = true
                t == "--flash-size" || t == "--flash_size" || t == "-fs" -> {
                    size = parseSize(tokens.getOrNull(i + 1))
                    i++
                }
                t.startsWith("--flash-size=") || t.startsWith("--flash_size=") -> size = parseSize(t.substringAfter('='))
                t in OPTIONS_WITH_VALUE -> i++
                t.startsWith("-") -> Unit // flag without value (e.g. -z, --compress, --verify)
                isAddress(t) && i + 1 < tokens.size -> {
                    segments += FlashSegment(parseAddress(t), resolve(tokens[i + 1]))
                    i++
                }
            }
            i++
        }
        return Triple(segments, size, eraseAll)
    }

    private val OPTIONS_WITH_VALUE = setOf(
        "--flash-mode", "--flash_mode", "-fm", "--flash-freq", "--flash_freq", "-ff",
        "--spi-connection", "--spi_connection", "--chip", "-c", "--port", "-p", "--baud", "-b",
        "--before", "--after",
    )

    fun isAddress(s: String): Boolean = Regex("^(0x[0-9a-fA-F]+|[0-9]+)$").matches(s)

    fun parseAddress(s: String): Long =
        if (s.startsWith("0x", ignoreCase = true)) s.substring(2).toLong(16) else s.toLong()

    /** "4MB" -> 4194304, "512KB" -> 524288; "keep"/"detect" -> null. */
    fun parseSize(s: String?): Long? {
        val m = Regex("^(\\d+)\\s*([KM])B$", RegexOption.IGNORE_CASE).find(s?.trim() ?: return null) ?: return null
        val n = m.groupValues[1].toLong()
        return if (m.groupValues[2].equals("M", true)) n * 1024 * 1024 else n * 1024
    }

    /** Splits a command line honouring single and double quotes. */
    fun tokenize(s: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var quote: Char? = null
        var hasToken = false
        for (c in s) {
            when {
                quote != null -> if (c == quote) quote = null else cur.append(c)
                c == '"' || c == '\'' -> { quote = c; hasToken = true }
                c.isWhitespace() -> {
                    if (hasToken || cur.isNotEmpty()) out += cur.toString()
                    cur.clear()
                    hasToken = false
                }
                else -> cur.append(c)
            }
        }
        if (hasToken || cur.isNotEmpty()) out += cur.toString()
        return out
    }
}

/** `key=value` lines as printed by `arduino-cli compile --show-properties`. */
object BuildProperties {
    fun parse(text: String): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        for (line in text.lineSequence()) {
            val idx = line.indexOf('=')
            if (idx <= 0 || line.startsWith("#")) continue
            map[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
        }
        return map
    }

    /** The upload tool for serial ports, handling old and pluggable-discovery style keys. */
    fun uploadTool(props: Map<String, String>): String? =
        props["upload.tool.serial"] ?: props["upload.tool.default"] ?: props["upload.tool"]
}
