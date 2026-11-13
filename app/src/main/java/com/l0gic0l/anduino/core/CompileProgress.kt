package com.l0gic0l.anduino.core

import com.l0gic0l.anduino.env.OutputLine

/**
 * Turns `arduino-cli compile -v` output into progress a person can follow. Without
 * `-v` arduino-cli prints nothing until the build ends, which on a phone can be many
 * minutes for a first ESP32 build, so the app always asks for verbose output and shows
 * the build phases plus a running file count. Full command lines are shown only when
 * the user turned on verbose output.
 */
class CompileProgress(private val showCommands: Boolean) {
    data class Decision(val show: Boolean, val step: String?)

    var phase: String = "Preparing"
        private set
    var compiled = 0
        private set
    var reused = 0
        private set
    /** Files preprocessed while detecting libraries (the slow part of a first build). */
    var checked = 0
        private set
    var cachedChecks = 0
        private set

    fun accept(line: OutputLine): Decision {
        val text = line.text.trim()
        if (line.isError) return Decision(true, null) // compiler warnings and errors
        PHASES.firstOrNull { text.startsWith(it) }?.let {
            phase = it
            return Decision(true, "$it…")
        }
        PREPROCESS_COMMAND.find(text)?.let { m ->
            checked++
            val file = m.groupValues[1].substringAfterLast('/')
            return Decision(showCommands, "$phase: $file (${checked + cachedChecks} files checked)")
        }
        if (text.startsWith("Using cached library dependencies")) {
            cachedChecks++
            return Decision(showCommands, "$phase: ${checked + cachedChecks} files checked ($cachedChecks from cache)")
        }
        COMPILE_COMMAND.find(text)?.let { m ->
            compiled++
            val file = m.groupValues[1].substringAfterLast('/')
            return Decision(showCommands, "$phase: $file (${compiled + reused} files)")
        }
        if (text.startsWith("Using previously compiled file")) {
            reused++
            return Decision(showCommands, "$phase: ${compiled + reused} files ($reused already built)")
        }
        if (text.startsWith("Using precompiled core")) return Decision(true, "Using the core built earlier")
        if (looksLikeCommand(text) || CHATTER.any { text.startsWith(it) }) return Decision(showCommands, null)
        return Decision(true, null) // sizes, used libraries, anything unexpected
    }

    private fun looksLikeCommand(text: String): Boolean =
        text.startsWith("/") || text.startsWith("\"/") || text.contains(" -o /dev/null") ||
            (text.contains("/.arduino15/packages/") && text.length > 160)

    companion object {
        private val PHASES = listOf(
            "Detecting libraries used",
            "Generating function prototypes",
            "Compiling sketch",
            "Compiling libraries",
            "Compiling core",
            "Linking everything together",
        )

        private val CHATTER = listOf(
            "FQBN:", "Using board", "Using core", "Using library", "Using cached library",
            "Alternatives for", "ResolveLibrary", "Archiving built core",
        )

        /** Library detection preprocesses each file: `... -E ... source.cpp -o /dev/null`. */
        private val PREPROCESS_COMMAND =
            Regex("""(\S+?\.(?:cpp|cc|cxx|c|S|s|ino))"?\s+-o\s+"?/dev/null"?(?:\s|$)""")

        /** `... source.cpp -o object.cpp.o`, with or without quotes. */
        private val COMPILE_COMMAND =
            Regex("""(\S+?\.(?:cpp|cc|cxx|c|S|s|ino))"?\s+-o\s+"?\S+?\.o"?(?:\s|$)""")
    }
}
