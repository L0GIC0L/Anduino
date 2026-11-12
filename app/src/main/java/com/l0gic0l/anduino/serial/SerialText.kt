package com.l0gic0l.anduino.serial

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/**
 * Turns a stream of byte chunks into complete text lines. UTF-8 sequences split
 * across USB packets are decoded correctly; `\r\n`, `\n` and lone `\r` all end a line.
 */
class LineAssembler {
    private val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    private var pendingBytes = ByteArray(0)
    private val partial = StringBuilder()
    private var lastWasCr = false

    /** Feeds bytes and returns the lines completed by them. */
    fun feed(data: ByteArray): List<String> {
        val input = ByteBuffer.wrap(if (pendingBytes.isEmpty()) data else pendingBytes + data)
        val out = CharBuffer.allocate(input.remaining() + 1)
        decoder.decode(input, out, false)
        pendingBytes = ByteArray(input.remaining()).also { input.get(it) }
        out.flip()
        val lines = ArrayList<String>()
        for (c in out) {
            when (c) {
                '\n' -> {
                    if (!lastWasCr) lines += partial.toString()
                    partial.clear()
                    lastWasCr = false
                }
                '\r' -> {
                    lines += partial.toString()
                    partial.clear()
                    lastWasCr = true
                }
                else -> {
                    partial.append(c)
                    lastWasCr = false
                }
            }
        }
        return lines
    }

    /** Text received after the last line break. */
    val pending: String get() = partial.toString()

    fun reset() {
        pendingBytes = ByteArray(0)
        partial.clear()
        lastWasCr = false
        decoder.reset()
    }
}

/**
 * Parses one line of serial plotter input with the same rules as the Arduino IDE 2
 * plotter: values separated by commas, tabs or spaces; optional `label:value`.
 * Unlabelled values are named "value 1", "value 2", ...
 * Returns an empty list for lines with no numbers (e.g. boot messages).
 */
object PlotterParser {
    private val separators = Regex("[,\\t ]+")

    fun parse(line: String): List<Pair<String, Float>> {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return emptyList()
        // If the line uses commas or tabs, spaces may belong to labels ("Temp C:21.5").
        val tokens = if (trimmed.contains(',') || trimmed.contains('\t')) {
            trimmed.split(Regex("[,\\t]+"))
        } else {
            trimmed.split(separators)
        }
        val out = ArrayList<Pair<String, Float>>()
        var unnamed = 0
        for (raw in tokens) {
            val t = raw.trim()
            if (t.isEmpty()) continue
            val colon = t.lastIndexOf(':')
            if (colon >= 0) {
                val label = t.substring(0, colon).trim()
                val value = t.substring(colon + 1).trim().toFloatOrNull() ?: continue
                if (!value.isFinite()) continue
                out += (label.ifEmpty { "value ${++unnamed}" }) to value
            } else {
                val value = t.toFloatOrNull() ?: continue
                if (!value.isFinite()) continue
                out += "value ${++unnamed}" to value
            }
        }
        return out
    }
}

/**
 * Fixed-window store of plotter samples. Each [add] is one x-step; series missing
 * from a line get NaN for that step so lines break instead of interpolating.
 */
class PlotterData(capacity: Int = 500) {
    var capacity: Int = capacity
        set(value) {
            field = value.coerceIn(10, 10_000)
            trim()
        }

    private val _labels = ArrayList<String>()
    val labels: List<String> get() = _labels

    /** Ring of rows; row[i] belongs to labels[i]. */
    private val rows = ArrayDeque<FloatArray>()
    var totalSamples: Long = 0
        private set

    val size: Int get() = rows.size

    fun add(values: List<Pair<String, Float>>) {
        if (values.isEmpty()) return
        for ((label, _) in values) {
            if (label !in _labels && _labels.size < MAX_SERIES) _labels += label
        }
        val row = FloatArray(_labels.size) { Float.NaN }
        for ((label, v) in values) {
            val idx = _labels.indexOf(label)
            if (idx >= 0) row[idx] = v
        }
        rows.addLast(row)
        totalSamples++
        trim()
    }

    fun value(row: Int, series: Int): Float {
        val r = rows[row]
        return if (series < r.size) r[series] else Float.NaN
    }

    /** Min/max over visible series, or null if there is no finite data. */
    fun range(visible: (Int) -> Boolean): Pair<Float, Float>? {
        var lo = Float.POSITIVE_INFINITY
        var hi = Float.NEGATIVE_INFINITY
        for (r in rows) for (s in r.indices) {
            if (!visible(s)) continue
            val v = r[s]
            if (v.isNaN()) continue
            if (v < lo) lo = v
            if (v > hi) hi = v
        }
        return if (lo <= hi) lo to hi else null
    }

    fun clear() {
        rows.clear()
        _labels.clear()
        totalSamples = 0
    }

    /** Immutable copy for drawing (rows are never mutated after being added). */
    fun snapshot(): PlotSnapshot = PlotSnapshot(_labels.toList(), rows.toList(), capacity)

    private fun trim() {
        while (rows.size > capacity) rows.removeFirst()
    }

    companion object {
        const val MAX_SERIES = 16
    }
}

class PlotSnapshot(val labels: List<String>, val rows: List<FloatArray>, val capacity: Int) {
    fun range(visible: (Int) -> Boolean): Pair<Float, Float>? {
        var lo = Float.POSITIVE_INFINITY
        var hi = Float.NEGATIVE_INFINITY
        for (r in rows) for (s in r.indices) {
            if (!visible(s)) continue
            val v = r[s]
            if (v.isNaN()) continue
            if (v < lo) lo = v
            if (v > hi) hi = v
        }
        return if (lo <= hi) lo to hi else null
    }

    fun latest(series: Int): Float? {
        for (i in rows.indices.reversed()) {
            val r = rows[i]
            if (series < r.size && !r[series].isNaN()) return r[series]
        }
        return null
    }
}
