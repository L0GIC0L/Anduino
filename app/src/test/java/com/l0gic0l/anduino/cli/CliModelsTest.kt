package com.l0gic0l.anduino.cli

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JSON shapes follow arduino-cli 1.6 internal/cli/feedback/result/rpc.go. */
class CliModelsTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true; explicitNulls = false }

    @Test
    fun parsesCoreSearch() {
        val text = """
            {"platforms":[
              {"id":"esp32:esp32","maintainer":"Espressif Systems","website":"https://github.com/espressif/arduino-esp32",
               "email":"hristo@espressif.com","indexed":true,
               "releases":{
                 "3.2.1":{"name":"esp32","version":"3.2.1","types":["Contributed"],"boards":[{"name":"ESP32 Dev Module"}],"compatible":true},
                 "3.3.0":{"name":"esp32","version":"3.3.0","types":["Contributed"],"installed":true,
                          "boards":[{"name":"ESP32 Dev Module"},{"name":"ESP32S3 Dev Module"}],"help":{"online":"x"},"compatible":true}
               },
               "installed_version":"3.3.0","latest_version":"3.3.0"},
              {"id":"arduino:avr","maintainer":"Arduino","releases":{"1.8.6":{"name":"Arduino AVR Boards","version":"1.8.6","compatible":true}},
               "installed_version":"","latest_version":"1.8.6"}
            ]}
        """.trimIndent()
        val r = json.decodeFromString<CoreSearchResult>(text)
        assertEquals(2, r.platforms.size)
        val esp = r.platforms[0]
        assertEquals("esp32", esp.name)
        assertEquals("3.3.0", esp.installed)
        assertFalse(esp.updatable)
        assertEquals(2, esp.releases["3.3.0"]!!.boards.size)
        val avr = r.platforms[1]
        assertEquals(null, avr.installed) // "" means not installed
        assertEquals("Arduino AVR Boards", avr.name)
    }

    @Test
    fun parsesBoardDetailsOptions() {
        val text = """
            {"fqbn":"esp32:esp32:esp32s3","name":"ESP32S3 Dev Module","version":"3.3.0","properties_id":"esp32s3",
             "official":false,"package":{"maintainer":"Espressif"},
             "config_options":[
               {"option":"CDCOnBoot","option_label":"USB CDC On Boot","values":[
                 {"value":"default","value_label":"Disabled","selected":true},{"value":"cdc","value_label":"Enabled"}]},
               {"option":"PartitionScheme","option_label":"Partition Scheme","values":[
                 {"value":"default","value_label":"Default 4MB with spiffs (1.2MB APP/1.5MB SPIFFS)","selected":true}]}
             ],
             "programmers":[{"platform":"esp32","id":"esptool","name":"Esptool"}]}
        """.trimIndent()
        val d = json.decodeFromString<BoardDetails>(text)
        assertEquals("ESP32S3 Dev Module", d.name)
        assertEquals(2, d.configOptions.size)
        assertEquals("cdc", d.configOptions[0].values[1].value)
        assertTrue(d.configOptions[0].values[0].selected)
    }

    @Test
    fun parsesBoardListAllLibSearchListAndExamples() {
        val boards = json.decodeFromString<BoardListAllResult>(
            """{"boards":[{"name":"Arduino Uno","fqbn":"arduino:avr:uno","platform":{"metadata":{"id":"arduino:avr"},"release":{"name":"Arduino AVR Boards","version":"1.8.6"}}},
                         {"name":"Hidden","fqbn":"x:y:z","is_hidden":true}]}""",
        )
        assertEquals("arduino:avr", boards.boards[0].platform?.metadata?.id)
        assertTrue(boards.boards[1].isHidden)

        val libs = json.decodeFromString<LibSearchResult>(
            """{"libraries":[{"name":"Adafruit NeoPixel","latest":{"author":"Adafruit","version":"1.12.3","sentence":"Arduino library for controlling single-wire-based LED pixels and strip.",
                "resources":{"url":"https://x"}},"available_versions":["1.0.0","1.12.3"]}],"status":"LIBRARY_SEARCH_STATUS_SUCCESS"}""",
        )
        assertEquals("1.12.3", libs.libraries[0].latest?.version)

        val installed = json.decodeFromString<LibListResult>(
            """{"installed_libraries":[{"library":{"name":"WiFi","author":"Hristo Gochkov","version":"3.3.0","location":"platform",
                "install_dir":"/root/.arduino15/packages/esp32/hardware/esp32/3.3.0/libraries/WiFi","container_platform":"esp32:esp32",
                "properties":{"name":"WiFi"},"compatible_with":{"esp32:esp32:esp32":true}}}]}""",
        )
        assertEquals("platform", installed.installedLibraries[0].library?.location)

        val examples = json.decodeFromString<LibExamplesResult>(
            """{"examples":[{"library":{"name":"WiFi","install_dir":"/lib/WiFi"},"examples":["/lib/WiFi/examples/WiFiScan"]}]}""",
        )
        assertEquals("/lib/WiFi/examples/WiFiScan", examples.examples[0].examples[0])
    }

    @Test
    fun fqbnRoundTrip() {
        val f = Fqbn.parse("esp32:esp32:esp32s3:CDCOnBoot=cdc,PSRAM=opi")
        assertEquals("esp32:esp32:esp32s3", f.base)
        assertEquals(mapOf("CDCOnBoot" to "cdc", "PSRAM" to "opi"), f.options)
        assertEquals("esp32", f.architecture)
        val updated = f.copy(options = f.options + ("PSRAM" to "disabled"))
        assertEquals("esp32:esp32:esp32s3:CDCOnBoot=cdc,PSRAM=disabled", updated.toString())
        assertEquals("arduino:avr:uno", Fqbn.parse("arduino:avr:uno").toString())
    }
}
