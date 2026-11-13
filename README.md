# Anduino

An Arduino IDE 2–style development environment for Android 17 (API 37), built with
Jetpack Compose and Material 3 / Material You. It compiles sketches **on the phone** with
the real Arduino toolchains, uploads over USB-OTG, and has a serial monitor and plotter.
Layouts adapt to phones, landscape, tablets and foldables (tabletop and book postures).

> **Status: first version.** The app builds, and the bootloader protocols are unit-tested
> against simulated ESP32 ROM and Optiboot bootloaders, but it has not been run on a
> physical phone yet. Expect rough edges on first install; the Settings screen has a
> Linux shell and a "repair" button for that reason.

## Arduino CLI or PlatformIO?

Anduino uses **arduino-cli** as its build engine.

| | arduino-cli | PlatformIO |
|---|---|---|
| What Arduino IDE 2 runs on | Yes, IDE 2 is a front end for it | No |
| Board Manager / Library Manager indexes | Native (every `package_*_index.json`, all ~7000 Arduino libraries) | Own registry; Arduino libraries mostly mirrored |
| ESP32 Arduino core 3.x | Official, released the same day by Espressif | Official platform stuck on core 2.x; 3.x only via the community "pioarduino" fork |
| Runtime needed on the device | One static Go binary | Python + pip + virtualenv |
| Linux ARM64 toolchains | Published by every major core (the same ones a Raspberry Pi uses) | Mostly published |

For the long term this choice does not limit what you can build: ESP32 Arduino core 3.x
is a layer on top of ESP-IDF 5, so FreeRTOS tasks, ESP-IDF drivers, `esp_*` APIs, Wi-Fi,
BLE, Matter, Zigbee, etc. are all callable from a sketch. The Linux environment inside
the app is ordinary Ubuntu, so a PlatformIO or plain ESP-IDF backend can be added later
without changing the rest of the app.

## How it works

```
┌──────────────────────── Android app (Kotlin, Compose, Material 3) ────────────────────────┐
│ Editor (sora-editor + VS Code C++ grammar) · Boards/Library managers · Sketchbook/Examples │
│ Serial Monitor + Plotter ◄── usb-serial-for-android ──► USB-OTG ──► board                 │
│ Native uploaders (Kotlin): ESP32 ROM bootloader (esptool protocol), AVR STK500v1          │
│                    │  runs, streams output                                                │
│                    ▼                                                                      │
│ libproot.so (Termux proot, shipped in the APK's native-library dir so it may be exec'd)   │
│   └── Ubuntu 24.04 arm64 rootfs (downloaded on first run, lives in the app's files dir)   │
│         └── arduino-cli ─► cores, gcc toolchains, esptool, python3 scripts, libraries     │
└───────────────────────────────────────────────────────────────────────────────────────────┘
```

* **Why proot:** apps targeting Android 10+ may not `exec()` files they downloaded (W^X).
  Files in the APK's native-library directory can be executed, so `proot` and its loader
  are shipped there as `lib*.so`. proot then *loads* the toolchain programs instead of
  exec'ing them, which Android allows. No root is required.
* **Why native uploaders:** Android does not expose `/dev/ttyUSB*` to apps; USB devices
  are only reachable through the `UsbManager` Java API. Instead of esptool/avrdude, the
  app speaks the bootloader protocols itself (`flash/EspLoader.kt` is a port of the parts
  of esptool 5.x used by `write-flash -z --no-stub`).
* **Compilation** is exactly `arduino-cli compile`, so build options, partition schemes,
  `build_opt.h`, libraries and examples behave as on a desktop.
* **Fast native compiler.** proot intercepts every file lookup; ESP32 builds search ~400
  include folders per header, which made each compiler run ~30x slower. So every build
  recipe (compile, preprocess, archive, link, esptool/objcopy, size and the core's
  bash/python hooks) is prefixed, via `platform.local.txt`,
  with a tiny relay (`native/run_client.c`). It hands each command to the app, which
  runs it *outside* proot through glibc's own loader shipped in the APK
  (`libanduino-ld.so`, allowed to exec) plus an exec shim (`native/exec_shim.c`) that
  routes the compiler's sub-processes the same way — the glibc counterpart of
  termux-exec's "system linker exec". Anything that can't run natively falls back to
  proot automatically (non-compiler steps that fail natively are rerun under proot);
  only arduino-cli's own coordination stays in proot. Settings has a switch to turn it
  off.

## Features

| Area | Status |
|---|---|
| Editor: C++ syntax highlighting, line numbers, bracket matching, undo/redo per tab, find & replace, keyword/identifier completion, symbol bar above the keyboard, autosave | Done |
| Multiple tabs (.ino/.h/.cpp), new/rename/delete sketch; open a whole sketch folder from phone storage (keeps secrets.h, src/); add a file from storage as a tab; open a single .ino | Done |
| Verify / Upload with streamed compiler output, cancel | Done |
| Board selector over all installed cores, board options (IDE "Tools" menu: CPU, flash, partition scheme, PSRAM, USB mode...) | Done |
| Boards Manager (install / update / remove / pick version, extra index URLs with presets) | Done |
| Library Manager (search the full Arduino index, install versions with dependencies, remove, add .ZIP) | Done |
| Examples from every installed library and the selected board's core | Done |
| Serial Monitor (baud, line ending, timestamps, autoscroll, DTR/RTS) | Done |
| Serial Plotter (IDE 2 format: `1 2 3`, `a:1,b:2`, up to 16 series, pause, window size, legend toggles) | Done |
| Upload: ESP32, S2, S3, C2, C3, C5, C6, C61, H2, P4 over USB-UART bridges and native USB-Serial/JTAG; 1200 bps touch for TinyUSB boards | Done (ROM loader) |
| Upload: AVR via STK500v1 (Uno, Nano, Pro Mini, Duemilanove) | Done |
| Upload: ESP32 over Wi-Fi (ArduinoOTA via arduino-cli/espota) | Done |
| Export compiled binary (share .merged.bin / .hex / .uf2) | Done |
| Adaptive layout: bottom bar / navigation rail; side-by-side panes in landscape and on tablets; editor above the hinge and monitor below in tabletop posture; panes on either side of a separating hinge | Done |
| Material You dynamic colour, light/dark, foreground service so long installs survive backgrounding | Done |
| Code intelligence (clangd / arduino-language-server, go-to-definition, live errors) | Planned |
| Debugger (OpenOCD + GDB) | Planned |
| Upload for SAMD (bossac), RP2040 (UF2), Leonardo/Micro (AVR109), Mega 2560 (STK500v2), ESP8266, STM32 | Planned — compile works; use "Export compiled binary" meanwhile |
| Flasher stub for faster ESP32 uploads and "Erase all flash" | Planned |

Boards: **compiling** works for any core that publishes Linux ARM64 (aarch64) tools —
the same requirement as running Arduino IDE 2 on a 64-bit Raspberry Pi. Arduino AVR and
SAMD, ESP32, ESP8266, RP2040/RP2350 (Earle Philhower) and STM32duino all do.
**Uploading** from the phone is implemented for the ESP32 family and classic AVR boards
today (see the table).

## Getting the APK

Every push runs the **Android CI** GitHub Actions workflow, which runs the unit tests and
uploads `anduino-apks` (debug and release APK) as a build artifact. Download it from the
workflow run's page, unzip, and install the APK on the phone (allow "install unknown apps").

Builds are signed with the public debug key committed at `app/debug.keystore`, so a new
APK installs over the previous one and keeps the installed environment and cores. (APKs
from before 0.1.1 were signed with a throwaway CI key: uninstall that one once.)

## Building

Requirements: JDK 17+, Android SDK Platform 37 and Build-Tools (Android Studio installs
them), internet access for Gradle.

```bash
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # protocol and parser unit tests
./gradlew assembleRelease        # R8-shrunk, signed with the debug key for side-loading
```

The prebuilt binaries in `app/src/main/jniLibs/` and the editor assets in
`app/src/main/assets/textmate/` are committed. They can be regenerated with
`scripts/fetch-proot.sh` (needs `patchelf`), `scripts/fetch-glibc.sh` (Ubuntu 24.04
glibc 2.39), `scripts/build-native.sh` (needs `gcc` and `gcc-aarch64-linux-gnu`) and
`scripts/fetch-editor-assets.sh`.
Supported device ABIs: `arm64-v8a` (phones, foldables) and `x86_64` (emulator).

## First run

1. Install the APK and open Anduino. Setup downloads Ubuntu base 24.04 (~30 MB),
   python3 and CA certificates via apt, and arduino-cli, then the ESP32 core
   (~1.5 GB on disk; tick or untick it on the setup screen).
2. Pick a board (**Board** chip), plug the board in with a USB-OTG adapter, allow USB
   access, and press **Upload** (→). The first ESP32 build compiles the core and takes a
   few minutes; later builds are incremental.
3. If an upload can't connect, hold **BOOT**, tap **RESET**, release **BOOT** and retry.
4. If compiling fails with `...-g++ is not exist` (cores installed by 0.1.0), accept the
   repair prompt or use **Settings → Repair installed cores**.

## Project layout

```
app/src/main/java/com/l0gic0l/anduino/
  env/       proot runner, rootfs download/extract, first-run bootstrap
  cli/       arduino-cli wrapper and its JSON models
  flash/     ESP ROM-loader flasher, STK500v1, Intel HEX, flash plan parsing
  usb/       USB serial enumeration, permission, transport
  serial/    serial monitor session, line assembly, plotter parser and data
  core/      preferences, console log, job manager, compile/upload orchestration
  service/   foreground service for long jobs
  ui/        Compose screens (code, sketchbook, serial, managers, settings, setup)
app/src/test/  unit tests (simulated ESP ROM bootloader and Optiboot, parsers)
```

## Licences

Anduino's own code is MIT (see `LICENSE`). Bundled third-party components:
proot (GPL-2.0, binaries from the Termux project, source: https://github.com/termux/proot),
glibc (LGPL-2.1, Ubuntu 24.04 `libc6` package, source: the Ubuntu `glibc` source package),
talloc (LGPL-3.0), libandroid-shmem (BSD-3-Clause), sora-editor (LGPL-2.1),
usb-serial-for-android (MIT), VS Code C++ grammar and Dark+/Light+ themes via
tm-grammars/tm-themes (MIT). Downloaded at runtime, not bundled: Ubuntu packages,
arduino-cli (GPL-3.0), board cores and libraries under their own licences.
