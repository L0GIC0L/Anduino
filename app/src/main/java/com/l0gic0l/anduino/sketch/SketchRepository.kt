package com.l0gic0l.anduino.sketch

import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

data class SketchInfo(val name: String, val dir: File, val lastModified: Long)

/**
 * The sketchbook lives inside the Linux environment (/root/Arduino), so arduino-cli
 * sees exactly the files the editor writes, and libraries installed into
 * /root/Arduino/libraries are visible to both.
 */
class SketchRepository(val sketchbook: File) {

    fun list(): List<SketchInfo> =
        sketchbook.listFiles()
            ?.filter { it.isDirectory && File(it, "${it.name}.ino").isFile }
            ?.map { SketchInfo(it.name, it, newestFile(it)) }
            ?.sortedByDescending { it.lastModified }
            ?: emptyList()

    private fun newestFile(dir: File): Long =
        dir.listFiles()?.maxOfOrNull { it.lastModified() } ?: dir.lastModified()

    /** Source files of a sketch, main .ino first, as shown in the editor tabs. */
    fun files(sketchDir: File): List<File> {
        val main = File(sketchDir, "${sketchDir.name}.ino")
        val others = sketchDir.listFiles()
            ?.filter { it.isFile && it != main && it.extension.lowercase() in SOURCE_EXTENSIONS }
            ?.sortedWith(compareBy<File>({ it.extension != "ino" }, { it.name.lowercase() }))
            ?: emptyList()
        return listOf(main) + others
    }

    fun create(name: String): File {
        validateName(name)
        val dir = File(sketchbook, name)
        if (dir.exists()) throw IOException("A sketch named \"$name\" already exists")
        dir.mkdirs()
        File(dir, "$name.ino").writeText(TEMPLATE)
        return dir
    }

    /** Copies an example (or any sketch folder) into the sketchbook, renaming its main file. */
    fun importFolder(source: File, preferredName: String = source.name): File {
        val name = uniqueName(sanitize(preferredName))
        val dest = File(sketchbook, name)
        source.copyRecursively(dest, overwrite = false)
        val oldMain = File(dest, "${source.name}.ino")
        if (oldMain.exists() && source.name != name) oldMain.renameTo(File(dest, "$name.ino"))
        return dest
    }

    /** Creates a sketch from a single file's text (e.g. an .ino opened from another app). */
    fun importText(fileName: String, text: String): File {
        val base = sanitize(fileName.substringBeforeLast('.'))
        val name = uniqueName(base)
        val dir = File(sketchbook, name)
        dir.mkdirs()
        File(dir, "$name.ino").writeText(text)
        return dir
    }

    fun addFile(sketchDir: File, fileName: String): File {
        require(fileName.matches(Regex("[A-Za-z0-9_][A-Za-z0-9_.-]*\\.(ino|h|hpp|c|cpp|S|tpp|ipp)"))) {
            "Use a name like helpers.h, motor.cpp or config.h"
        }
        val f = File(sketchDir, fileName)
        if (f.exists()) throw IOException("$fileName already exists")
        f.writeText(if (f.extension == "h" || f.extension == "hpp") "#pragma once\n\n" else "")
        return f
    }

    fun deleteFile(sketchDir: File, file: File) {
        require(file != File(sketchDir, "${sketchDir.name}.ino")) { "The main sketch file can't be deleted" }
        file.delete()
    }

    fun delete(sketchDir: File) {
        if (sketchDir.parentFile == sketchbook) sketchDir.deleteRecursively()
    }

    fun rename(sketchDir: File, newName: String): File {
        validateName(newName)
        val dest = File(sketchbook, newName)
        if (dest.exists()) throw IOException("A sketch named \"$newName\" already exists")
        if (!sketchDir.renameTo(dest)) throw IOException("Rename failed")
        File(dest, "${sketchDir.name}.ino").renameTo(File(dest, "$newName.ino"))
        return dest
    }

    /** IDE-style default name: sketch_nov12a, sketch_nov12b, ... */
    fun nextDefaultName(): String {
        val today = LocalDate.now()
        val month = today.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH).lowercase()
        val prefix = "sketch_$month${today.dayOfMonth}"
        for (c in 'a'..'z') {
            val name = "$prefix$c"
            if (!File(sketchbook, name).exists()) return name
        }
        return uniqueName(prefix)
    }

    fun uniqueName(base: String): String {
        if (!File(sketchbook, base).exists()) return base
        var i = 2
        while (File(sketchbook, "${base}_$i").exists()) i++
        return "${base}_$i"
    }

    companion object {
        val SOURCE_EXTENSIONS = setOf("ino", "pde", "h", "hpp", "hh", "c", "cpp", "cc", "cxx", "s", "tpp", "ipp")

        const val TEMPLATE = """void setup() {
  // put your setup code here, to run once:

}

void loop() {
  // put your main code here, to run repeatedly:

}
"""

        private val NAME_RULE = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,62}")

        fun validateName(name: String) {
            require(NAME_RULE.matches(name)) {
                "Sketch names start with a letter or number and may contain letters, numbers, _ . - (max 63)"
            }
        }

        fun sanitize(name: String): String {
            val s = name.replace(Regex("[^A-Za-z0-9_.-]"), "_").trimStart('_', '.', '-').take(63)
            return s.ifEmpty { "sketch" }
        }
    }
}
