# Anduino

## SmartDial

ESP32-S3 desk dial: AS5600 magnetic encoder, 3 buttons, 2.13" e-ink (GDEY0213B74),
USB HID (hi-res wheel, keyboard, media keys) and Home Assistant light/sensor control.

1. Copy `SmartDial/secrets.h.example` to `SmartDial/secrets.h` and fill it in.
2. Install libraries: **GxEPD2** (pulls in Adafruit GFX + BusIO).
3. Board settings are listed at the top of `SmartDial/SmartDial.ino`.
