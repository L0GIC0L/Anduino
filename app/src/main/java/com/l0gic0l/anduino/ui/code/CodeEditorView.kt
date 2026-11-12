package com.l0gic0l.anduino.ui.code

import android.content.Context
import android.graphics.Typeface
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import io.github.rosemoe.sora.widget.subscribeAlways
import org.eclipse.tm4e.core.registry.IThemeSource

/** One-time TextMate registry setup (grammars and the two VS Code themes from assets). */
object EditorSetup {
    private var initialized = false

    @Synchronized
    fun ensureInitialized(context: Context) {
        if (initialized) return
        val files = FileProviderRegistry.getInstance()
        files.addFileProvider(AssetsFileResolver(context.applicationContext.assets))
        val themes = ThemeRegistry.getInstance()
        for ((name, dark) in listOf("dark-plus" to true, "light-plus" to false)) {
            val path = "textmate/$name.json"
            val model = ThemeModel(IThemeSource.fromInputStream(files.tryGetInputStream(path), path, null), name)
            model.isDark = dark
            themes.loadTheme(model, false)
        }
        // A current theme must exist before a TextMateLanguage is created; applyColors() picks the right one.
        themes.setTheme("light-plus")
        GrammarRegistry.getInstance().loadGrammars("textmate/languages.json")
        initialized = true
    }

    fun newLanguage(): TextMateLanguage =
        TextMateLanguage.create("source.cpp", true).apply {
            setCompleterKeywords(ARDUINO_KEYWORDS)
            tabSize = 2
        }

    /** VS Code token colours, Material You chrome. */
    fun applyColors(editor: CodeEditor, dark: Boolean, c: ColorScheme) {
        val key = dark to c.surface
        if (editor.tag == key) return
        editor.tag = key
        ThemeRegistry.getInstance().setTheme(if (dark) "dark-plus" else "light-plus")
        val scheme = (editor.colorScheme as? TextMateColorScheme)
            ?: TextMateColorScheme.create(ThemeRegistry.getInstance()).also { editor.colorScheme = it }
        fun set(id: Int, color: androidx.compose.ui.graphics.Color) = scheme.setColor(id, color.toArgb())
        set(EditorColorScheme.WHOLE_BACKGROUND, c.surface)
        set(EditorColorScheme.LINE_NUMBER_BACKGROUND, c.surface)
        set(EditorColorScheme.LINE_NUMBER, c.onSurfaceVariant.copy(alpha = 0.55f))
        set(EditorColorScheme.LINE_NUMBER_CURRENT, c.primary)
        set(EditorColorScheme.LINE_DIVIDER, c.outlineVariant.copy(alpha = 0.4f))
        set(EditorColorScheme.CURRENT_LINE, c.onSurface.copy(alpha = 0.05f))
        set(EditorColorScheme.SELECTION_INSERT, c.primary)
        set(EditorColorScheme.SELECTION_HANDLE, c.primary)
        set(EditorColorScheme.SELECTED_TEXT_BACKGROUND, c.primary.copy(alpha = 0.28f))
        set(EditorColorScheme.MATCHED_TEXT_BACKGROUND, c.tertiaryContainer)
        set(EditorColorScheme.BLOCK_LINE, c.outlineVariant.copy(alpha = 0.5f))
        set(EditorColorScheme.BLOCK_LINE_CURRENT, c.outline)
        set(EditorColorScheme.SCROLL_BAR_THUMB, c.onSurface.copy(alpha = 0.25f))
        set(EditorColorScheme.SCROLL_BAR_THUMB_PRESSED, c.primary)
        set(EditorColorScheme.COMPLETION_WND_BACKGROUND, c.surfaceContainerHigh)
        set(EditorColorScheme.COMPLETION_WND_CORNER, c.outlineVariant)
        set(EditorColorScheme.COMPLETION_WND_TEXT_PRIMARY, c.onSurface)
        set(EditorColorScheme.COMPLETION_WND_TEXT_SECONDARY, c.onSurfaceVariant)
        set(EditorColorScheme.COMPLETION_WND_ITEM_CURRENT, c.secondaryContainer)
        set(EditorColorScheme.TEXT_ACTION_WINDOW_BACKGROUND, c.surfaceContainerHigh)
        set(EditorColorScheme.TEXT_ACTION_WINDOW_ICON_COLOR, c.onSurface)
        editor.invalidate()
    }

    private val ARDUINO_KEYWORDS = arrayOf(
        // Arduino core API
        "setup", "loop", "pinMode", "digitalWrite", "digitalRead", "analogRead", "analogWrite",
        "analogReadResolution", "analogWriteResolution", "analogReference", "delay", "delayMicroseconds",
        "millis", "micros", "tone", "noTone", "pulseIn", "pulseInLong", "shiftIn", "shiftOut",
        "attachInterrupt", "detachInterrupt", "digitalPinToInterrupt", "interrupts", "noInterrupts",
        "random", "randomSeed", "map", "constrain", "min", "max", "abs", "sq", "sqrt", "pow",
        "bitRead", "bitWrite", "bitSet", "bitClear", "bit", "highByte", "lowByte",
        "HIGH", "LOW", "INPUT", "OUTPUT", "INPUT_PULLUP", "INPUT_PULLDOWN", "LED_BUILTIN",
        "CHANGE", "RISING", "FALLING", "Serial", "Serial1", "Serial2", "Wire", "SPI",
        "begin", "end", "available", "read", "write", "print", "println", "printf", "flush",
        "readString", "readStringUntil", "parseInt", "parseFloat", "setTimeout",
        "String", "byte", "word", "boolean", "uint8_t", "uint16_t", "uint32_t", "uint64_t",
        "int8_t", "int16_t", "int32_t", "int64_t", "size_t", "PROGMEM", "F",
        // ESP32
        "WiFi", "WiFiClient", "WiFiServer", "WiFiUDP", "WiFiClientSecure", "HTTPClient", "WebServer",
        "Preferences", "esp_sleep_enable_timer_wakeup", "esp_deep_sleep_start", "ESP", "ledcAttach",
        "ledcWrite", "touchRead", "xTaskCreate", "xTaskCreatePinnedToCore", "vTaskDelay", "pdMS_TO_TICKS",
        "portTICK_PERIOD_MS", "TaskHandle_t", "SemaphoreHandle_t", "QueueHandle_t",
        // C++
        "void", "int", "long", "short", "char", "float", "double", "bool", "unsigned", "signed",
        "const", "static", "volatile", "constexpr", "auto", "struct", "class", "enum", "union",
        "public", "private", "protected", "virtual", "override", "template", "typename", "namespace",
        "using", "return", "if", "else", "for", "while", "do", "switch", "case", "default", "break",
        "continue", "goto", "sizeof", "new", "delete", "nullptr", "true", "false", "this",
        "#include", "#define", "#ifdef", "#ifndef", "#endif", "#pragma",
    )
}

/**
 * The sora-editor [CodeEditor] hosted in Compose. Each file has its own [Content]
 * instance (owned by the ViewModel), which the editor swaps in without losing undo.
 */
@Composable
fun CodeEditorView(
    content: Content?,
    fontSize: Float,
    tabSize: Int,
    wordWrap: Boolean,
    dark: Boolean,
    onEdited: () -> Unit,
    onEditorReady: (CodeEditor?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val latestOnEdited by rememberUpdatedState(onEdited)
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            EditorSetup.ensureInitialized(ctx)
            CodeEditor(ctx).apply {
                typefaceText = Typeface.MONOSPACE
                typefaceLineNumber = Typeface.MONOSPACE
                setEditorLanguage(EditorSetup.newLanguage())
                isLineNumberEnabled = true
                setPinLineNumber(true)
                setHighlightCurrentLine(true)
                isHighlightBracketPair = true
                isBlockLineEnabled = true
                props.symbolPairAutoCompletion = true
                subscribeAlways<ContentChangeEvent> { e ->
                    if (e.action != ContentChangeEvent.ACTION_SET_NEW_TEXT) latestOnEdited()
                }
                onEditorReady(this)
            }
        },
        update = { editor ->
            if (content != null && editor.text !== content) editor.setText(content, true, null)
            editor.setTextSize(fontSize)
            editor.tabWidth = tabSize
            (editor.editorLanguage as? TextMateLanguage)?.tabSize = tabSize
            if (editor.isWordwrap != wordWrap) editor.isWordwrap = wordWrap
            EditorSetup.applyColors(editor, dark, colors)
        },
        onRelease = { editor ->
            onEditorReady(null)
            editor.release()
        },
    )
}
