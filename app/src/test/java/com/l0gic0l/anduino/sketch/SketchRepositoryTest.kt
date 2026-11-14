package com.l0gic0l.anduino.sketch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SketchRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun createListAndFilesOrder() {
        val repo = SketchRepository(tmp.newFolder("Arduino"))
        val dir = repo.create("Blink")
        assertTrue(File(dir, "Blink.ino").readText().contains("void setup()"))
        repo.addFile(dir, "pins.h")
        repo.addFile(dir, "motor.cpp")
        repo.addFile(dir, "extra.ino")
        assertEquals(listOf("Blink.ino", "extra.ino", "motor.cpp", "pins.h"), repo.files(dir).map { it.name })
        assertEquals(listOf("Blink"), repo.list().map { it.name })
    }

    @Test
    fun importFolderRenamesMainFileWhenNameTaken() {
        val book = tmp.newFolder("Arduino")
        val repo = SketchRepository(book)
        repo.create("WiFiScan")
        val example = tmp.newFolder("examples", "WiFiScan").apply { File(this, "WiFiScan.ino").writeText("// scan") }
        val copy = repo.importFolder(example)
        assertEquals("WiFiScan_2", copy.name)
        assertEquals("// scan", File(copy, "WiFiScan_2.ino").readText())
    }

    @Test
    fun renameMovesMainFile() {
        val repo = SketchRepository(tmp.newFolder("Arduino"))
        val dir = repo.create("old_name")
        val renamed = repo.rename(dir, "new-name")
        assertTrue(File(renamed, "new-name.ino").isFile)
    }

    @Test
    fun importsWholeSketchFolderIncludingSecretsAndSrc() {
        val repo = SketchRepository(tmp.newFolder("Arduino"))
        val picked = tmp.newFolder("picked", "WeatherStation").apply {
            File(this, "WeatherStation.ino").writeText("#include \"secrets.h\"")
            File(this, "secrets.h").writeText("#define WIFI_SSID \"home\"")
            File(this, "src/sensors").mkdirs()
            File(this, "src/sensors/bme.cpp").writeText("// bme")
        }
        val dir = repo.importSketchFolder(picked)
        assertEquals("WeatherStation", dir.name)
        assertEquals(listOf("WeatherStation.ino", "secrets.h"), repo.files(dir).map { it.name })
        assertEquals("// bme", File(dir, "src/sensors/bme.cpp").readText())
    }

    @Test
    fun importedFolderIsNamedAfterItsInoWhenTheFolderNameDiffers() {
        val repo = SketchRepository(tmp.newFolder("Arduino"))
        val picked = tmp.newFolder("Download copy").apply {
            File(this, "blinky.ino").writeText("void setup(){}")
            File(this, "secrets.h").writeText("")
        }
        val dir = repo.importSketchFolder(picked)
        assertEquals("blinky", dir.name)
        assertTrue(File(dir, "blinky.ino").isFile)
    }

    @Test(expected = IllegalArgumentException::class)
    fun folderWithoutInoIsRejected() {
        SketchRepository(tmp.newFolder("Arduino")).importSketchFolder(tmp.newFolder("notes"))
    }

    @Test
    fun addsAFileFromDeviceAsATabAndRefusesToOverwrite() {
        val repo = SketchRepository(tmp.newFolder("Arduino"))
        val dir = repo.create("Blink")
        repo.importFileInto(dir, "secrets.h", "#define PASS \"x\"".byteInputStream())
        assertEquals(listOf("Blink.ino", "secrets.h"), repo.files(dir).map { it.name })
        val again = runCatching { repo.importFileInto(dir, "secrets.h", "".byteInputStream()) }
        assertTrue(again.exceptionOrNull() is java.io.IOException)
        assertEquals("#define PASS \"x\"", File(dir, "secrets.h").readText())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidNames() {
        SketchRepository(tmp.newFolder("Arduino")).create("my sketch")
    }

    @Test
    fun defaultNameLooksLikeTheIde() {
        val name = SketchRepository(tmp.newFolder("Arduino")).nextDefaultName()
        assertTrue(name, Regex("sketch_[a-z]{3}\\d{1,2}a").matches(name))
        assertEquals("My_Sketch_v1.2", SketchRepository.sanitize("My Sketch v1.2"))
    }
}
