package com.l0gic0l.anduino.core

import android.content.Context
import android.content.SharedPreferences
import com.l0gic0l.anduino.env.Bootstrapper
import com.l0gic0l.anduino.serial.LineEnding
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** A preference exposed as a [StateFlow]; [set] writes through to SharedPreferences. */
class Pref<T>(
    private val sp: SharedPreferences,
    private val key: String,
    default: T,
    private val read: SharedPreferences.(String, T) -> T,
    private val write: SharedPreferences.Editor.(String, T) -> Unit,
) {
    private val state = MutableStateFlow(sp.read(key, default))
    val flow: StateFlow<T> = state.asStateFlow()
    val value: T get() = state.value

    fun set(value: T) {
        state.value = value
        sp.edit().apply { write(key, value) }.apply()
    }
}

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("anduino", Context.MODE_PRIVATE)

    private fun string(key: String, default: String) =
        Pref(sp, key, default, { k, d -> getString(k, d) ?: d }, { k, v -> putString(k, v) })

    private fun nullableString(key: String) =
        Pref<String?>(sp, key, null, { k, d -> getString(k, d) }, { k, v -> putString(k, v) })

    private fun int(key: String, default: Int) =
        Pref(sp, key, default, { k, d -> getInt(k, d) }, { k, v -> putInt(k, v) })

    private fun float(key: String, default: Float) =
        Pref(sp, key, default, { k, d -> getFloat(k, d) }, { k, v -> putFloat(k, v) })

    private fun bool(key: String, default: Boolean) =
        Pref(sp, key, default, { k, d -> getBoolean(k, d) }, { k, v -> putBoolean(k, v) })

    private inline fun <reified E : Enum<E>> enum(key: String, default: E) =
        Pref(
            sp, key, default,
            { k, d -> runCatching { enumValueOf<E>(getString(k, d.name)!!) }.getOrDefault(d) },
            { k, v -> putString(k, v.name) },
        )

    // Board / port
    val fqbn = nullableString("fqbn")
    val boardName = nullableString("board_name")
    /** USB device name, or "ota:<host>" for network uploads. */
    val port = nullableString("port")
    val otaPassword = string("ota_password", "")

    // Sketch
    val lastSketch = nullableString("last_sketch")

    // Serial
    val baud = int("baud", 115200)
    val lineEnding = enum("line_ending", LineEnding.LF)
    val showTimestamps = bool("timestamps", false)
    val autoScroll = bool("autoscroll", true)
    val plotterWindow = int("plotter_window", 500)

    // Appearance / editor
    val themeMode = enum("theme_mode", ThemeMode.SYSTEM)
    val dynamicColor = bool("dynamic_color", true)
    val editorFontSize = float("editor_font_size", 14f)
    val tabSize = int("tab_size", 2)
    val wordWrap = bool("word_wrap", false)

    // Build
    val warnings = string("warnings", "default")
    val verboseCompile = bool("verbose_compile", false)
    val boardManagerUrls = string("board_urls", Bootstrapper.DEFAULT_URLS.joinToString("\n"))
    val disableSeccomp = bool("disable_seccomp", false)

    fun boardUrlList(): List<String> = boardManagerUrls.value.lines().map { it.trim() }.filter { it.isNotEmpty() }
}
