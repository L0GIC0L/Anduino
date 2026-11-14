package com.l0gic0l.anduino.flash

/**
 * Espressif chips the ROM-bootloader flasher knows about. Values come from
 * esptool 5.x (the per-chip files in esptool/targets): chips with [magicValue] are identified by reading
 * CHIP_DETECT_MAGIC_REG, newer ones report [chipId] via GET_SECURITY_INFO.
 */
enum class EspChip(
    val displayName: String,
    /** The `build.mcu` value used by the Arduino ESP32 core. */
    val mcu: String,
    val chipId: Int?,
    val magicValue: Long?,
) {
    ESP32("ESP32", "esp32", null, 0x00F01D83L),
    ESP32_S2("ESP32-S2", "esp32s2", null, 0x000007C6L),
    ESP32_S3("ESP32-S3", "esp32s3", 9, null),
    ESP32_C3("ESP32-C3", "esp32c3", 5, null),
    ESP32_C2("ESP32-C2", "esp32c2", 12, null),
    ESP32_C5("ESP32-C5", "esp32c5", 23, null),
    ESP32_C6("ESP32-C6", "esp32c6", 13, null),
    ESP32_C61("ESP32-C61", "esp32c61", 20, null),
    ESP32_H2("ESP32-H2", "esp32h2", 16, null),
    ESP32_H21("ESP32-H21", "esp32h21", 25, null),
    ESP32_H4("ESP32-H4", "esp32h4", 28, null),
    ESP32_P4("ESP32-P4", "esp32p4", 18, null),
    ESP8266("ESP8266", "esp8266", null, 0xFFF0C101L);

    /** ESP32 and ESP8266 ROMs take the short (16 byte) FLASH_BEGIN parameter block. */
    val romSupportsEncryptedFlag: Boolean get() = this != ESP32 && this != ESP8266

    companion object {
        fun fromMcu(mcu: String?): EspChip? =
            entries.firstOrNull { it.mcu.equals(mcu?.trim(), ignoreCase = true) }

        fun fromChipId(id: Int): EspChip? = entries.firstOrNull { it.chipId == id }

        fun fromMagic(value: Long): EspChip? = entries.firstOrNull { it.magicValue == value }
    }
}
