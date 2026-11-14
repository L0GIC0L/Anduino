package com.l0gic0l.anduino.ui.code

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.eclipse.tm4e.core.grammar.IGrammar
import org.eclipse.tm4e.core.grammar.IStateStack
import org.eclipse.tm4e.core.internal.oniguruma.Oniguruma
import org.eclipse.tm4e.core.registry.IGrammarSource
import org.eclipse.tm4e.core.registry.IRegistryOptions
import org.eclipse.tm4e.core.registry.Registry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The editor runs TextMate grammars on joni, which silently disables patterns it can't
 * compile (scripts/fix-grammar-for-joni.py). These tests run the real tokenizer.
 */
class GrammarTest {
    private val dir = File("src/main/assets/textmate")

    private fun patterns(e: JsonElement, out: MutableList<Pair<String, String>>) {
        when (e) {
            is JsonObject -> for ((k, v) in e) {
                if (k in setOf("match", "begin", "end", "while") && v is JsonPrimitive && v.isString) out += k to v.content
                else patterns(v, out)
            }
            is JsonArray -> e.forEach { patterns(it, out) }
            else -> Unit
        }
    }

    @Test
    fun joniAcceptsEveryPattern() {
        for (name in listOf("cpp.tmLanguage.json", "cpp-macro.tmLanguage.json")) {
            val all = mutableListOf<Pair<String, String>>()
            patterns(Json.parseToJsonElement(File(dir, name).readText()), all)
            val rejected = all.filter { (key, p) ->
                // Back-references in end/while are filled in at run time.
                val compiled = if (key == "end" || key == "while") p.replace(Regex("\\\\(\\d+)"), "x") else p
                runCatching { Oniguruma.newRegex(compiled) }.isFailure
            }
            assertEquals("$name: patterns joni rejects: ${rejected.map { it.second.take(80) }}", 0, rejected.size)
        }
    }

    private fun grammar(): IGrammar {
        val registry = Registry(object : IRegistryOptions {
            override fun getGrammarSource(scopeName: String): IGrammarSource? = when (scopeName) {
                "source.cpp" -> IGrammarSource.fromFile(File(dir, "cpp.tmLanguage.json"))
                "source.cpp.embedded.macro" -> IGrammarSource.fromFile(File(dir, "cpp-macro.tmLanguage.json"))
                else -> null
            }
        })
        return registry.loadGrammar("source.cpp")!!
    }

    /** Tokenizes like sora-editor (each line with its newline) and returns, per line, the scopes of a substring. */
    private fun scopesOf(code: List<String>, newline: String, probe: (Int) -> String): List<List<String>> {
        val g = grammar()
        var state: IStateStack? = null
        return code.mapIndexed { i, line ->
            val r = g.tokenizeLine(line + newline, state, null)
            state = r.ruleStack
            val at = line.indexOf(probe(i)).coerceAtLeast(0)
            r.tokens.first { at >= it.startIndex && at < it.endIndex }.scopes
        }
    }

    @Test
    fun lineCommentsEndAtTheEndOfTheLine() {
        val code = listOf(
            "// WiFi credentials",
            "#include \"secrets.h\"",
            "#define LED 2",
            "int x = 1; // note",
            "void setup() { pinMode(LED, OUTPUT); }",
        )
        val probes = listOf("WiFi", "#", "#", "int", "pinMode")
        for (nl in listOf("\n", "\r\n")) {
            val s = scopesOf(code, nl) { probes[it] }
            assertTrue(s[0].any { it.startsWith("comment.line") })
            for (i in 1..4) assertFalse("line ${i + 1} ($nl) is still a comment: ${s[i]}", s[i].any { it.startsWith("comment") })
            assertTrue("include: ${s[1]}", s[1].any { it.startsWith("meta.preprocessor.include") })
            assertTrue("define: ${s[2]}", s[2].any { it.startsWith("meta.preprocessor.macro") })
            assertTrue("function call: ${s[4]}", s[4].any { it.startsWith("entity.name.function") })
        }
    }

    @Test
    fun blockCommentsSpanLinesAndEnd() {
        val code = listOf("/* multi", "   line */ int y;")
        val s = scopesOf(code, "\n") { listOf("multi", "int")[it] }
        assertTrue(s[0].any { it.startsWith("comment.block") })
        assertFalse(s[1].any { it.startsWith("comment") })
    }
}
