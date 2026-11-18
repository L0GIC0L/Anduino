// ============================================================================
// ESP32-S3 SMART DIAL
//   AS5600 encoder + 3 buttons + 2.13" e-ink (GDEY0213B74) + USB HID
//   (hi-res mouse wheel, keyboard, consumer control) + USB serial host link
//
// Arduino IDE settings:
//   USB Mode          = "USB-OTG (TinyUSB)"
//   USB CDC On Boot   = "Enabled"
//   Partition Scheme  = "Huge APP (3MB No OTA/1MB SPIFFS)"  (needs 4MB+ flash)
//   Core Debug Level  = "None"
//   Plug into the native USB port (not the UART port).
//
// NOTE: HA_HOST in secrets.h must be plain HTTP, e.g. "http://192.168.1.50:8123"
//       (this sketch uses a tiny raw HTTP client instead of HTTPClient/TLS to
//        save program space).
//
// Pages:  SCROLL | MEDIA | LIGHTS | TEMPS | NAVIGATE | MACROS | FOCUS TIMER |
//         SYSTEM | SETTINGS
// Buttons: SW1 short = next page, long = previous page
//          SW2 short = page sub-action, long = lock/unlock dial
//          SW3 short = page primary action (middle-click on SCROLL)
// ============================================================================
#include <Arduino.h>
#include <Wire.h>
#include <SPI.h>
#include <Preferences.h>
#include <WiFi.h>
#include "secrets.h"      // WIFI_SSID, WIFI_PASS, HA_HOST, HA_TOKEN
#include "USB.h"
#include "USBHID.h"
#include "USBHIDKeyboard.h"
#include "USBHIDConsumerControl.h"

#include <GxEPD2_BW.h>
#include <Fonts/FreeMonoBold9pt7b.h>

// ============================================================================
// 1. PINS
// ============================================================================
#define SW1_PAGE_PIN  4
#define SW2_SUB_PIN   5
#define SW3_ACT_PIN   6

#define SDA_PIN       8
#define SCL_PIN       18
#define AS5600_ADDR   0x36

#define EPD_BUSY      9
#define EPD_CS        10
#define EPD_MOSI      11
#define EPD_SCK       12
#define EPD_DC        13
#define EPD_RST       14

// ============================================================================
// 2. DISPLAY
// ============================================================================
GxEPD2_BW<GxEPD2_213_GDEY0213B74, GxEPD2_213_GDEY0213B74::HEIGHT> display(
  GxEPD2_213_GDEY0213B74(EPD_CS, EPD_DC, EPD_RST, EPD_BUSY)
);

// ============================================================================
// 3. USB HID  (custom hi-res mouse + stock keyboard + consumer control)
//    Custom report IDs are 0x20/0x21 so they never collide with the
//    keyboard (1) / consumer (3) IDs used by the Arduino core.
// ============================================================================
#define MOUSE_RID       0x20
#define MOUSE_FEAT_RID  0x21

USBHID HID;

static const uint8_t hires_mouse_descriptor[] = {
  0x05, 0x01, 0x09, 0x02, 0xA1, 0x01,            // Mouse, Application
  0x09, 0x01, 0xA1, 0x00,                        //   Pointer, Physical
  0x85, MOUSE_RID,                               //   Report ID
  0x05, 0x09, 0x19, 0x01, 0x29, 0x03,            //   3 buttons
  0x15, 0x00, 0x25, 0x01, 0x95, 0x03, 0x75, 0x01, 0x81, 0x02,
  0x95, 0x01, 0x75, 0x05, 0x81, 0x03,            //   padding
  0x05, 0x01, 0x09, 0x30, 0x09, 0x31,            //   X, Y
  0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x02, 0x81, 0x06,
  0xA1, 0x02,                                    //   Logical collection
  0x85, MOUSE_FEAT_RID,                          //     Feature report ID
  0x09, 0x48,                                    //     Resolution Multiplier
  0x15, 0x00, 0x25, 0x01, 0x35, 0x01, 0x45, 0x10,
  0x75, 0x08, 0x95, 0x01, 0xB1, 0x02,
  0x85, MOUSE_RID,                               //     back to input report
  0x09, 0x38,                                    //     Wheel
  0x15, 0x81, 0x25, 0x7F, 0x35, 0x81, 0x45, 0x7F,
  0x75, 0x08, 0x95, 0x01, 0x81, 0x06,
  0xC0,                                          //   End logical
  0xC0,                                          // End physical
  0xC0                                           // End application
};

class CustomHiResMouse : public USBHIDDevice {
public:
  uint8_t buttonMask = 0x00;
  bool hiRes = false;          // set by host via feature report
  int resRemainder = 0;

  CustomHiResMouse() {
    static bool initialized = false;
    if (!initialized) {
      initialized = true;
      HID.addDevice(this, sizeof(hires_mouse_descriptor));
    }
  }
  void begin() { HID.begin(); }

  uint16_t _onGetDescriptor(uint8_t* buffer) {
    memcpy(buffer, hires_mouse_descriptor, sizeof(hires_mouse_descriptor));
    return sizeof(hires_mouse_descriptor);
  }
  uint16_t _onGetFeature(uint8_t report_id, uint8_t* buffer, uint16_t len) {
    if (report_id == MOUSE_FEAT_RID && len >= 1) { buffer[0] = hiRes ? 1 : 0; return 1; }
    return 0;
  }
  void _onSetFeature(uint8_t report_id, const uint8_t* buffer, uint16_t len) {
    if (report_id == MOUSE_FEAT_RID && len >= 1) hiRes = (buffer[0] & 1);
  }

  void sendWheel(int8_t w) {
    uint8_t report[4] = { buttonMask, 0x00, 0x00, (uint8_t)w };
    HID.SendReport(MOUSE_RID, report, 4);
  }
  // units = 1/16 of a wheel notch
  void scroll(int units) {
    if (hiRes) {
      while (units) { int c = constrain(units, -127, 127); sendWheel((int8_t)c); units -= c; }
    } else {                                   // host not hi-res: whole notches only
      resRemainder += units;
      int n = resRemainder / 16;
      resRemainder -= n * 16;
      while (n) { int c = constrain(n, -127, 127); sendWheel((int8_t)c); n -= c; }
    }
  }
  void setButtons(uint8_t mask) {
    buttonMask = mask;
    uint8_t report[4] = { buttonMask, 0x00, 0x00, 0x00 };
    HID.SendReport(MOUSE_RID, report, 4);
  }
};

CustomHiResMouse Dial;
USBHIDKeyboard Keyboard;
USBHIDConsumerControl ConsumerControl;

// ============================================================================
// 4. SETTINGS (persisted in flash)
// ============================================================================
struct Settings {
  uint8_t sensIndex = 2;
  bool    invert    = false;
  uint8_t deadzone  = 1;      // raw encoder counts (0..4)
  bool    accel     = true;   // velocity-based scroll acceleration
  bool    hostMac   = false;  // false = Windows/Linux, true = macOS
  uint8_t focusMin  = 25;     // pomodoro length
};
Settings cfg;
Preferences prefs;
bool settingsDirty = false;
uint32_t settingsDirtyAt = 0;

const int sensPresets[4] = { 8, 16, 32, 64 };
const char* const sensNames[4] = { "ULTRA", "FINE", "STD", "COARSE" };

void loadSettings() {
  prefs.begin("dial", true);
  cfg.sensIndex = constrain((int)prefs.getUChar("sens", 2), 0, 3);
  cfg.invert    = prefs.getBool("inv", false);
  cfg.deadzone  = constrain((int)prefs.getUChar("dz", 1), 0, 4);
  cfg.accel     = prefs.getBool("acc", true);
  cfg.hostMac   = prefs.getBool("mac", false);
  cfg.focusMin  = constrain((int)prefs.getUChar("focus", 25), 5, 90);
  prefs.end();
}
void saveSettings() {
  prefs.begin("dial", false);
  prefs.putUChar("sens", cfg.sensIndex);
  prefs.putBool("inv", cfg.invert);
  prefs.putUChar("dz", cfg.deadzone);
  prefs.putBool("acc", cfg.accel);
  prefs.putBool("mac", cfg.hostMac);
  prefs.putUChar("focus", cfg.focusMin);
  prefs.end();
}

// ============================================================================
// 5. GLOBAL STATE
// ============================================================================
enum Page : uint8_t {
  PAGE_SCROLL = 0, PAGE_MEDIA, PAGE_LIGHTS, PAGE_TEMPS, PAGE_NAV, PAGE_MACROS,
  PAGE_TIMER, PAGE_SYSTEM, PAGE_SETTINGS, PAGE_COUNT
};
const char* const pageTitles[PAGE_COUNT] = {
  "SCROLL", "MEDIA", "LIGHTS", "TEMPS", "NAVIGATE", "MACROS", "FOCUS TIMER", "SYSTEM", "SETTINGS"
};

volatile uint8_t currentPage = PAGE_SCROLL;
volatile bool screenNeedsUpdate = true;
volatile bool dialLocked = false;

enum { MAG_NONE = 0, MAG_OK, MAG_WEAK, MAG_STRONG };
const char* const magNames[4] = { "NONE", "OK", "WEAK", "STRONG" };
volatile uint8_t magnetState = MAG_NONE;

char lastAction[24] = "-";
void setAction(const char* s) { strlcpy(lastAction, s, sizeof(lastAction)); screenNeedsUpdate = true; }

// Dial engine
const uint32_t SAMPLE_US = 2000;          // 500 Hz
uint32_t lastSampleUs = 0;
int  lastAngle = -1;
long acc = 0;                             // accumulator in 1/16 raw counts
float speedEma = 0;
#define RAWU(x) ((long)(x) * 16)          // raw counts -> accumulator units

// Media
int volEst = 50;                          // local estimate only

// Navigate
enum { NAV_TABS = 0, NAV_SCRUB, NAV_ZOOM, NAV_UNDO, NAV_COUNT };
const char* const navNames[NAV_COUNT] = { "TABS", "SCRUB", "ZOOM", "UNDO" };
const char* const navDesc[NAV_COUNT]  = { "CTRL+TAB / +SHIFT", "LEFT / RIGHT ARROW", "CTRL + WHEEL", "UNDO / REDO" };
const char* const navHints[NAV_COUNT] = { "[1]MODE [3]NEW TAB", "[1]MODE [3]SPACE", "[1]MODE [3]RESET", "[1]MODE [3]SAVE" };
uint8_t navMode = NAV_TABS;
bool zoomHeld = false;
uint32_t zoomReleaseAt = 0;

// Settings page
const int SETTINGS_ITEMS = 5;
int settingsSel = 0;

// Macros page
int macroSel = 0;

// Timer
enum TimerState : uint8_t { T_IDLE, T_RUNNING, T_PAUSED };
TimerState timerState = T_IDLE;
bool timerBreak = false;
uint32_t timerEndMs = 0, timerRemainMs = 0, timerTotalMs = 1;
uint8_t sessions = 0;
int lastShownMin = -1;

// Host stats (from serial)
int hostCpu = 0, hostMem = 0;
char hostTime[8] = "--:--";
uint32_t lastHostMs = 0;
char rxBuf[48];
uint8_t rxLen = 0;

// Buttons
const uint8_t switchPins[3] = { SW1_PAGE_PIN, SW2_SUB_PIN, SW3_ACT_PIN };
bool rawLast[3]      = { false, false, false };
bool btnHeld[3]      = { false, false, false };
bool btnConsumed[3]  = { false, false, false };
uint32_t lastChange[3] = { 0, 0, 0 };
uint32_t btnDownAt[3]  = { 0, 0, 0 };
const uint32_t DEBOUNCE_MS   = 25;
const uint32_t LONG_PRESS_MS = 600;
const uint32_t MIN_REFRESH_MS = 250;

uint32_t lastMagPoll = 0;

// ============================================================================
// 6. KEYBOARD / CONSUMER HELPERS
// ============================================================================
#define MOD_CTRL     0x01
#define MOD_SHIFT    0x02
#define MOD_ALT      0x04
#define MOD_GUI      0x08
#define MOD_PRIMARY  0x10   // Ctrl on Windows/Linux, Cmd on macOS

void pressMods(uint8_t m) {
  if (m & MOD_PRIMARY) m |= cfg.hostMac ? MOD_GUI : MOD_CTRL;
  if (m & MOD_CTRL)  Keyboard.press(KEY_LEFT_CTRL);
  if (m & MOD_SHIFT) Keyboard.press(KEY_LEFT_SHIFT);
  if (m & MOD_ALT)   Keyboard.press(KEY_LEFT_ALT);
  if (m & MOD_GUI)   Keyboard.press(KEY_LEFT_GUI);
}
void combo(uint8_t mods, uint8_t key) {
  pressMods(mods);
  if (key) Keyboard.press(key);
  delay(10);
  Keyboard.releaseAll();
}
void tapConsumer(uint16_t code, int n = 1) {
  n = constrain(n, 1, 8);
  for (int i = 0; i < n; i++) {
    ConsumerControl.press(code);
    ConsumerControl.release();
    if (n > 1) delay(2);
  }
}
void zoomHold() {
  if (!zoomHeld) { pressMods(MOD_PRIMARY); zoomHeld = true; }
  zoomReleaseAt = millis() + 350;
}
void zoomRelease() {
  if (zoomHeld) { Keyboard.releaseAll(); zoomHeld = false; }
}

// ============================================================================
// 7. MACROS  (edit this table to add your own)
// ============================================================================
enum MacroType : uint8_t { MT_COMBO, MT_CONSUMER, MT_TEXT, MT_FUNC };
struct Macro {
  const char* name;
  MacroType   type;
  uint8_t     mods;
  uint8_t     key;
  uint16_t    consumer;
  const char* text;
  void      (*fn)();
};

void macroScreenshot() { if (cfg.hostMac) combo(MOD_GUI | MOD_SHIFT, '4'); else combo(MOD_GUI | MOD_SHIFT, 's'); }
void macroLock()       { if (cfg.hostMac) combo(MOD_CTRL | MOD_GUI, 'q');  else combo(MOD_GUI, 'l'); }
void macroTaskMgr()    { if (cfg.hostMac) combo(MOD_GUI | MOD_ALT, KEY_ESC); else combo(MOD_CTRL | MOD_SHIFT, KEY_ESC); }

const Macro macros[] = {
  { "Copy",            MT_COMBO,    MOD_PRIMARY,             'c', 0,      nullptr,   nullptr },
  { "Paste",           MT_COMBO,    MOD_PRIMARY,             'v', 0,      nullptr,   nullptr },
  { "Cut",             MT_COMBO,    MOD_PRIMARY,             'x', 0,      nullptr,   nullptr },
  { "Undo",            MT_COMBO,    MOD_PRIMARY,             'z', 0,      nullptr,   nullptr },
  { "Redo",            MT_COMBO,    MOD_PRIMARY | MOD_SHIFT, 'z', 0,      nullptr,   nullptr },
  { "Select All",      MT_COMBO,    MOD_PRIMARY,             'a', 0,      nullptr,   nullptr },
  { "New Tab",         MT_COMBO,    MOD_PRIMARY,             't', 0,      nullptr,   nullptr },
  { "Screenshot",      MT_FUNC,     0, 0, 0,      nullptr,   macroScreenshot },
  { "Lock Computer",   MT_FUNC,     0, 0, 0,      nullptr,   macroLock },
  { "Task Manager",    MT_FUNC,     0, 0, 0,      nullptr,   macroTaskMgr },
  { "Open Calculator", MT_CONSUMER, 0, 0, 0x0192, nullptr,   nullptr },
  { "Type: Thanks!",   MT_TEXT,     0, 0, 0,      "Thanks!", nullptr },
};
const int MACRO_COUNT = sizeof(macros) / sizeof(macros[0]);

void runMacro(int idx) {
  if (idx < 0 || idx >= MACRO_COUNT) return;
  const Macro& m = macros[idx];
  switch (m.type) {
    case MT_COMBO:    combo(m.mods, m.key); break;
    case MT_CONSUMER: tapConsumer(m.consumer); break;
    case MT_TEXT:     Keyboard.print(m.text); break;
    case MT_FUNC:     if (m.fn) m.fn(); break;
  }
  char b[24];
  snprintf(b, sizeof(b), "RAN: %s", m.name);
  setAction(b);
}

// ============================================================================
// 7b. HOME ASSISTANT  (state shared between the dial loop and the HA task)
//     Edit the lights[] table with your own entity IDs.
//     Use "all" as the entity to target every light.
// ============================================================================
struct HALight { const char* name; const char* entity; };
const HALight lights[] = {
  { "Desk Lamp",       "light.third_reality_inc_3rcb01057z_light_3" },
  { "Nightstand Lamp", "light.third_reality_inc_3rcb01057z_light" },
  { "Printer Lamp",    "light.third_reality_inc_3rcb01057z_light_2" },
  { "Kitchen Light",   "light.third_reality_inc_3rcb01057z_light_4" },
  { "All Lights",      "all" },
};
const int LIGHT_COUNT = sizeof(lights) / sizeof(lights[0]);
const int HA_PCT_PER_DETENT = 3;          // brightness % per dial detent
const int HA_KELVIN_STEP    = 200;        // color temp per detent while SW3 held

volatile uint8_t haSel = 0;               // selected light
int8_t  haOn[16];                         // -1 unknown, 0 off, 1 on
uint8_t haBright[16];                     // 0..100 (last known / optimistic)
volatile int haKelvin = 3000;
volatile bool haOnline = false;
volatile int  haLastCode = 0;

// Pending commands (dial thread adds, HA task consumes atomically)
volatile int  haPendBright = 0;           // accumulated brightness_step_pct
volatile int  haPendCt = 0;               // flag: send haKelvin
volatile int  haPendToggle = 0;
volatile bool haPendRefresh = false;

// Temperature / humidity sensors (read-only, shown on the TEMPS page)
struct HASensor { const char* label; const char* entity; };
const HASensor sensors[] = {
  { "BEDROOM",  "sensor.bedroom_temperature_temperature" },
  { "HUMIDITY", "sensor.bedroom_temperature_humidity" },
  { "NOELLE",   "sensor.sonoff_snzb_02ld" },
};
const int SENSOR_COUNT = sizeof(sensors) / sizeof(sensors[0]);
float haSensVal[8];
bool  haSensOk[8];
char  haSensUnit[8];                      // last char of the unit, e.g. 'F', 'C', '%'
volatile bool haPendSens = false;

// ============================================================================
// 8. PAGE NAVIGATION, SETTINGS, TIMER
// ============================================================================
void gotoPage(uint8_t p) {
  zoomRelease();
  acc = 0;
  currentPage = p % PAGE_COUNT;
  if (currentPage == PAGE_LIGHTS) haPendRefresh = true;
  if (currentPage == PAGE_TEMPS)  haPendSens = true;
  screenNeedsUpdate = true;
}
void settingsChanged() {
  settingsDirty = true;
  settingsDirtyAt = millis();
  screenNeedsUpdate = true;
}
void changeSetting(int dir) {
  switch (settingsSel) {
    case 0: cfg.sensIndex = (cfg.sensIndex + 4 + dir) % 4; break;
    case 1: cfg.invert = !cfg.invert; break;
    case 2: cfg.deadzone = constrain((int)cfg.deadzone + dir, 0, 4); break;
    case 3: cfg.accel = !cfg.accel; break;
    case 4: cfg.hostMac = !cfg.hostMac; break;
  }
  settingsChanged();
}

void timerStart(uint32_t ms) {
  timerTotalMs = ms ? ms : 1;
  timerEndMs = millis() + ms;
  timerState = T_RUNNING;
  lastShownMin = -1;
}
uint32_t timerRemaining() {
  if (timerState == T_RUNNING) {
    int32_t r = (int32_t)(timerEndMs - millis());
    return r > 0 ? (uint32_t)r : 0;
  }
  if (timerState == T_PAUSED) return timerRemainMs;
  return (uint32_t)cfg.focusMin * 60000UL;
}
void timerToggle() {
  if (timerState == T_IDLE) {
    timerBreak = false;
    timerStart((uint32_t)cfg.focusMin * 60000UL);
  } else if (timerState == T_RUNNING) {
    timerRemainMs = timerRemaining();
    timerState = T_PAUSED;
  } else {
    timerEndMs = millis() + timerRemainMs;
    timerState = T_RUNNING;
  }
  screenNeedsUpdate = true;
}
void timerReset() {
  timerState = T_IDLE;
  timerBreak = false;
  screenNeedsUpdate = true;
}
void timerTick(uint32_t now) {
  if (timerState != T_RUNNING) return;
  if ((int32_t)(now - timerEndMs) >= 0) {
    if (!timerBreak) {
      sessions++;
      timerBreak = true;
      timerStart((uint32_t)max(5, cfg.focusMin / 5) * 60000UL);
    } else {
      timerState = T_IDLE;
      timerBreak = false;
    }
    lastShownMin = -1;
    gotoPage(PAGE_TIMER);                // pull the user's attention
    return;
  }
  int m = (int)((timerEndMs - now + 59999) / 60000);
  if (m != lastShownMin) {
    lastShownMin = m;
    if (currentPage == PAGE_TIMER) screenNeedsUpdate = true;
  }
}

// ============================================================================
// 9. BUTTON ACTIONS
// ============================================================================
void sw2Action() {
  switch (currentPage) {
    case PAGE_SCROLL:   cfg.invert = !cfg.invert; settingsChanged(); break;
    case PAGE_MEDIA:    tapConsumer(CONSUMER_CONTROL_MUTE); setAction("MUTE"); break;
    case PAGE_LIGHTS:   haSel = (haSel + 1) % LIGHT_COUNT; haPendRefresh = true; screenNeedsUpdate = true; break;
    case PAGE_TEMPS:    haPendSens = true; break;
    case PAGE_NAV:      zoomRelease(); navMode = (navMode + 1) % NAV_COUNT; acc = 0; screenNeedsUpdate = true; break;
    case PAGE_MACROS:   macroSel = 0; screenNeedsUpdate = true; break;
    case PAGE_TIMER:    timerReset(); break;
    case PAGE_SYSTEM:   screenNeedsUpdate = true; break;       // manual refresh
    case PAGE_SETTINGS: changeSetting(-1); break;
  }
}
void sw3Action() {
  switch (currentPage) {
    case PAGE_MEDIA:
      tapConsumer(CONSUMER_CONTROL_PLAY_PAUSE);
      setAction("PLAY/PAUSE");
      break;
    case PAGE_LIGHTS:
      haOn[haSel] = (haOn[haSel] == 1) ? 0 : 1;           // optimistic UI
      __atomic_add_fetch(&haPendToggle, 1, __ATOMIC_SEQ_CST);
      screenNeedsUpdate = true;
      break;
    case PAGE_NAV:
      switch (navMode) {
        case NAV_TABS:  combo(MOD_PRIMARY, 't'); setAction("NEW TAB");    break;
        case NAV_SCRUB: Keyboard.write(' ');      setAction("SPACE");      break;
        case NAV_ZOOM:  combo(MOD_PRIMARY, '0'); setAction("ZOOM RESET"); break;
        case NAV_UNDO:  combo(MOD_PRIMARY, 's'); setAction("SAVE");       break;
      }
      break;
    case PAGE_MACROS:   runMacro(macroSel); break;
    case PAGE_TIMER:    timerToggle(); break;
    case PAGE_SETTINGS: changeSetting(+1); break;
    default: break;
  }
}

void onPress(int i) {
  if (i == 2 && currentPage == PAGE_SCROLL) Dial.setButtons(0x04);   // middle click
}
void onRelease(int i) {
  if (i == 2 && Dial.buttonMask) Dial.setButtons(0x00);
  if (btnConsumed[i]) return;
  if (i == 0) gotoPage(currentPage + 1);
  else if (i == 1) sw2Action();
  else if (i == 2) sw3Action();
}
void onLong(int i) {
  if (i == 0) {
    gotoPage((currentPage + PAGE_COUNT - 1) % PAGE_COUNT);
  } else if (i == 1) {
    dialLocked = !dialLocked;
    screenNeedsUpdate = true;
  }
}

void pollButtons(uint32_t now) {
  for (int i = 0; i < 3; i++) {
    bool pressed = (digitalRead(switchPins[i]) == LOW);
    if (pressed != rawLast[i]) { rawLast[i] = pressed; lastChange[i] = now; }
    if ((now - lastChange[i]) >= DEBOUNCE_MS && pressed != btnHeld[i]) {
      btnHeld[i] = pressed;
      if (pressed) { btnDownAt[i] = now; btnConsumed[i] = false; onPress(i); }
      else         { onRelease(i); }
    }
    if (btnHeld[i] && !btnConsumed[i] && i != 2 && (now - btnDownAt[i]) >= LONG_PRESS_MS) {
      btnConsumed[i] = true;
      onLong(i);
    }
  }
}

// ============================================================================
// 10. DIAL ENGINE
// ============================================================================
int detentSteps(long du, long detentU) {
  acc += du;
  long s = acc / detentU;
  acc -= s * detentU;
  return (int)s;
}

void scrollInput(long du) {
  float gain = cfg.accel ? 1.0f + min(3.0f, speedEma / 3.0f) : 1.0f;
  acc += (long)(du * gain);
  long sens = sensPresets[cfg.sensIndex];
  long n = acc / sens;
  if (n) { acc -= n * sens; Dial.scroll((int)n); }
}

void dialInput(long du) {
  switch (currentPage) {
    case PAGE_SCROLL:
    case PAGE_SYSTEM:
      scrollInput(du);
      break;

    case PAGE_MEDIA: {
      bool skip = btnHeld[2];                       // hold SW3 + turn = skip track
      int s = detentSteps(du, RAWU(skip ? 192 : 64));
      if (!s) break;
      if (skip) {
        btnConsumed[2] = true;
        tapConsumer(s > 0 ? CONSUMER_CONTROL_SCAN_NEXT : CONSUMER_CONTROL_SCAN_PREVIOUS);
        setAction(s > 0 ? "NEXT TRACK" : "PREV TRACK");
      } else {
        tapConsumer(s > 0 ? CONSUMER_CONTROL_VOLUME_INCREMENT : CONSUMER_CONTROL_VOLUME_DECREMENT, abs(s));
        volEst = constrain(volEst + s * 2, 0, 100);
        setAction(s > 0 ? "VOL +" : "VOL -");
      }
      break;
    }

    case PAGE_LIGHTS: {
      bool ct = btnHeld[2];                          // hold SW3 + turn = color temp
      int s = detentSteps(du, RAWU(ct ? 96 : 64));
      if (!s) break;
      if (ct) {
        btnConsumed[2] = true;
        haKelvin = constrain((int)haKelvin + s * HA_KELVIN_STEP, 2000, 6500);
        __atomic_store_n(&haPendCt, 1, __ATOMIC_SEQ_CST);
      } else {
        int pct = s * HA_PCT_PER_DETENT;
        __atomic_add_fetch(&haPendBright, pct, __ATOMIC_SEQ_CST);
        haBright[haSel] = constrain((int)haBright[haSel] + pct, 0, 100);   // optimistic
        if (pct > 0) haOn[haSel] = 1;
      }
      screenNeedsUpdate = true;
      break;
    }

    case PAGE_NAV: {
      switch (navMode) {
        case NAV_TABS: {
          int s = detentSteps(du, RAWU(192));
          for (int k = 0; k < min(abs(s), 4); k++) combo(MOD_CTRL | (s < 0 ? MOD_SHIFT : 0), KEY_TAB);
          if (s) setAction(s > 0 ? "NEXT TAB" : "PREV TAB");
          break;
        }
        case NAV_SCRUB: {
          int s = detentSteps(du, RAWU(48));
          for (int k = 0; k < min(abs(s), 6); k++) Keyboard.write(s > 0 ? KEY_RIGHT_ARROW : KEY_LEFT_ARROW);
          if (s) setAction(s > 0 ? "FORWARD" : "BACK");
          break;
        }
        case NAV_ZOOM:
          zoomHold();
          scrollInput(du);
          break;
        case NAV_UNDO: {
          int s = detentSteps(du, RAWU(128));
          for (int k = 0; k < min(abs(s), 4); k++) combo(MOD_PRIMARY | (s > 0 ? MOD_SHIFT : 0), 'z');
          if (s) setAction(s > 0 ? "REDO" : "UNDO");
          break;
        }
      }
      break;
    }

    case PAGE_MACROS: {
      int s = detentSteps(du, RAWU(128));
      if (s) {
        macroSel = ((macroSel + s) % MACRO_COUNT + MACRO_COUNT) % MACRO_COUNT;
        screenNeedsUpdate = true;
      }
      break;
    }

    case PAGE_TIMER:
      if (timerState == T_IDLE) {
        int s = detentSteps(du, RAWU(128));
        if (s) {
          cfg.focusMin = constrain((int)cfg.focusMin + s * 5, 5, 90);
          settingsChanged();
        }
      }
      break;

    case PAGE_SETTINGS: {
      int s = detentSteps(du, RAWU(160));
      if (s) {
        settingsSel = ((settingsSel + s) % SETTINGS_ITEMS + SETTINGS_ITEMS) % SETTINGS_ITEMS;
        screenNeedsUpdate = true;
      }
      break;
    }
  }
}

// ---- AS5600 -----------------------------------------------------------------
int readAngleRaw() {                      // 0..4095, -1 on I2C error
  Wire.beginTransmission(AS5600_ADDR);
  Wire.write(0x0C);
  if (Wire.endTransmission() != 0) return -1;
  if (Wire.requestFrom((uint8_t)AS5600_ADDR, (uint8_t)2) != 2) return -1;
  uint8_t hi = Wire.read();
  uint8_t lo = Wire.read();
  return ((hi & 0x0F) << 8) | lo;
}
int readMagnetStatus() {
  Wire.beginTransmission(AS5600_ADDR);
  Wire.write(0x0B);
  if (Wire.endTransmission() != 0) return -1;
  if (Wire.requestFrom((uint8_t)AS5600_ADDR, (uint8_t)1) != 1) return -1;
  return Wire.read();
}
void pollMagnet() {
  int st = readMagnetStatus();
  uint8_t ns = MAG_NONE;
  if (st >= 0) {
    if (st & 0x08)      ns = MAG_STRONG;   // MH
    else if (st & 0x10) ns = MAG_WEAK;     // ML
    else if (st & 0x20) ns = MAG_OK;       // MD
  }
  if (ns != magnetState) {
    if (magnetState == MAG_NONE) lastAngle = -1;   // resync on (re)detect
    magnetState = ns;
    screenNeedsUpdate = true;
  }
}

void pollDial(uint32_t nowUs) {
  if (magnetState == MAG_NONE) { delay(2); return; }
  if ((nowUs - lastSampleUs) < SAMPLE_US) return;
  lastSampleUs = nowUs;

  int a = readAngleRaw();
  if (a < 0) return;
  if (lastAngle < 0) { lastAngle = a; return; }

  int d = a - lastAngle;                  // proper 12-bit wraparound
  if (d > 2048) d -= 4096;
  else if (d < -2048) d += 4096;

  speedEma = speedEma * 0.95f + abs(d) * 0.05f;

  // Deadzone with hysteresis: small moves accumulate until they exceed it.
  if (d == 0 || abs(d) < cfg.deadzone) return;
  lastAngle = a;

  if (dialLocked) return;
  long du = (long)d * 16 * (cfg.invert ? -1 : 1);
  dialInput(du);
}

// ============================================================================
// 11. HOST SERIAL LINK
//     "S,<cpu>,<mem>,<HH:MM>\n"  -> system stats
//     "M,<index>\n"              -> run macro <index>
// ============================================================================
void parseLine(char* s) {
  if (s[0] == 'S' && s[1] == ',') {
    char* p;
    long cpu = strtol(s + 2, &p, 10);  if (*p != ',') return;
    long mem = strtol(p + 1, &p, 10);  if (*p != ',') return;
    long h   = strtol(p + 1, &p, 10);  if (*p != ':') return;
    long m   = strtol(p + 1, &p, 10);
    hostCpu = constrain((int)cpu, 0, 100);
    hostMem = constrain((int)mem, 0, 100);
    snprintf(hostTime, sizeof(hostTime), "%02d:%02d", (int)(h % 24), (int)(m % 60));
    lastHostMs = millis();
    if (lastHostMs == 0) lastHostMs = 1;
  } else if (s[0] == 'M' && s[1] == ',') {
    runMacro(atoi(s + 2));
  }
}
void pollSerial() {
  while (Serial.available()) {
    char c = Serial.read();
    if (c == '\n' || c == '\r') {
      if (rxLen) { rxBuf[rxLen] = 0; parseLine(rxBuf); rxLen = 0; }
    } else if (rxLen < sizeof(rxBuf) - 1) {
      rxBuf[rxLen++] = c;
    }
  }
}

void housekeeping(uint32_t now) {
  if (zoomHeld && (int32_t)(now - zoomReleaseAt) >= 0) zoomRelease();
  if (settingsDirty && (now - settingsDirtyAt) > 3000) { saveSettings(); settingsDirty = false; }
  timerTick(now);
  if (now - lastMagPoll >= 250) { lastMagPoll = now; pollMagnet(); }
}

// ============================================================================
// 12. E-INK RENDERER (Core 0)
// ============================================================================
void drawHeader(uint8_t pg) {
  display.fillRect(0, 0, display.width(), 24, GxEPD_BLACK);
  display.setTextColor(GxEPD_WHITE);
  display.setCursor(8, 17);
  display.print(pageTitles[pg]);
  if (dialLocked) { display.setCursor(138, 17); display.print("LK"); }
  for (int i = 0; i < PAGE_COUNT; i++) {
    int cx = display.width() - 12 - (PAGE_COUNT - 1 - i) * 9;
    if (i == pg) display.fillCircle(cx, 12, 3, GxEPD_WHITE);
    else         display.drawCircle(cx, 12, 3, GxEPD_WHITE);
  }
  display.setTextColor(GxEPD_BLACK);
}
void drawRow(int i, const char* s) {
  display.setCursor(8, 42 + i * 17);
  display.print(s);
}
void drawHint(const char* s) {
  display.drawFastHLine(0, 100, display.width(), GxEPD_BLACK);
  display.setCursor(8, 116);
  display.print(s);
}
void drawBar(int x, int y, int w, int h, int pct) {
  pct = constrain(pct, 0, 100);
  display.drawRect(x, y, w, h, GxEPD_BLACK);
  int fw = (w - 4) * pct / 100;
  if (fw > 0) display.fillRect(x + 2, y + 2, fw, h - 4, GxEPD_BLACK);
}

void renderPage(uint8_t pg) {
  char b[48];
  switch (pg) {
    case PAGE_SCROLL:
      snprintf(b, sizeof(b), "SENS : %s", sensNames[cfg.sensIndex]); drawRow(0, b);
      drawRow(1, cfg.invert ? "DIR  : INVERT" : "DIR  : NORMAL");
      drawRow(2, dialLocked ? "STATE: LOCKED" : "STATE: ACTIVE");
      snprintf(b, sizeof(b), "MAG:%s ACCEL:%s", magNames[magnetState], cfg.accel ? "ON" : "OFF"); drawRow(3, b);
      drawHint("[1]FLIP [3]MID-CLICK");
      break;

    case PAGE_MEDIA:
      drawRow(0, "VOLUME (est.)");
      drawBar(8, 48, 190, 12, volEst);
      snprintf(b, sizeof(b), "%d", volEst);
      display.setCursor(206, 59); display.print(b);
      snprintf(b, sizeof(b), "LAST: %.15s", lastAction); drawRow(2, b);
      drawRow(3, "HOLD [3]+TURN: SKIP");
      drawHint("[1]MUTE [3]PLAY/PAUSE");
      break;

    case PAGE_LIGHTS: {
      snprintf(b, sizeof(b), "%.20s", lights[haSel].name); drawRow(0, b);
      if (!haOnline) {
        drawRow(1, WiFi.status() == WL_CONNECTED ? "HA: NO RESPONSE" : "WIFI: CONNECTING");
        snprintf(b, sizeof(b), "CODE: %d", (int)haLastCode); drawRow(2, b);
      } else {
        int pct = (haOn[haSel] == 0) ? 0 : haBright[haSel];
        drawBar(8, 48, 190, 12, pct);
        snprintf(b, sizeof(b), "%d%%", pct);
        display.setCursor(206, 59); display.print(b);
        snprintf(b, sizeof(b), "%s  %dK", haOn[haSel] == 1 ? "ON" : haOn[haSel] == 0 ? "OFF" : "?", (int)haKelvin);
        drawRow(2, b);
        drawRow(3, "HOLD [3]+TURN: WARMTH");
      }
      drawHint("[1]NEXT [3]TOGGLE");
      break;
    }

    case PAGE_TEMPS:
      for (int i = 0; i < SENSOR_COUNT; i++) {
        if (haSensOk[i]) snprintf(b, sizeof(b), "%-9s%.1f%c", sensors[i].label, haSensVal[i], haSensUnit[i]);
        else             snprintf(b, sizeof(b), "%-9s--", sensors[i].label);
        drawRow(i, b);
      }
      drawHint(haOnline ? "[1]REFRESH" : "[1]REFRESH  (HA OFFLINE)");
      break;

    case PAGE_NAV:
      snprintf(b, sizeof(b), "MODE: %s", navNames[navMode]); drawRow(0, b);
      drawRow(1, navDesc[navMode]);
      snprintf(b, sizeof(b), "LAST: %.15s", lastAction); drawRow(2, b);
      drawHint(navHints[navMode]);
      break;

    case PAGE_MACROS: {
      int first = constrain(macroSel - 1, 0, max(0, MACRO_COUNT - 4));
      for (int r = 0; r < 4 && first + r < MACRO_COUNT; r++) {
        int idx = first + r;
        snprintf(b, sizeof(b), "%c %.19s", idx == macroSel ? '>' : ' ', macros[idx].name);
        drawRow(r, b);
      }
      snprintf(b, sizeof(b), "[3]RUN [1]TOP  %d/%d", macroSel + 1, MACRO_COUNT);
      drawHint(b);
      break;
    }

    case PAGE_TIMER: {
      uint32_t rem = timerRemaining();
      int mins = (int)((rem + 59999) / 60000);
      display.setTextSize(3);
      snprintf(b, sizeof(b), "%dm", mins);
      display.setCursor(8, 66); display.print(b);
      display.setTextSize(1);
      const char* st = timerState == T_IDLE ? "READY" : timerState == T_PAUSED ? "PAUSED" : (timerBreak ? "BREAK" : "FOCUS");
      snprintf(b, sizeof(b), "%s  DONE:%d", st, sessions);
      display.setCursor(8, 86); display.print(b);
      int pct = (timerState == T_IDLE) ? 0 : 100 - (int)((uint64_t)rem * 100 / timerTotalMs);
      drawBar(8, 92, 234, 6, pct);
      drawHint(timerState == T_RUNNING ? "[1]RESET [3]PAUSE" : timerState == T_PAUSED ? "[1]RESET [3]RESUME" : "[1]RESET [3]START");
      break;
    }

    case PAGE_SYSTEM: {
      bool linked = lastHostMs && (millis() - lastHostMs < 10000);
      if (linked) snprintf(b, sizeof(b), "HOST: %s", hostTime);
      else        snprintf(b, sizeof(b), "HOST: NOT LINKED");
      drawRow(0, b);
      if (linked) snprintf(b, sizeof(b), "CPU %3d%%", hostCpu); else snprintf(b, sizeof(b), "CPU  --");
      drawRow(1, b);
      if (linked) drawBar(110, 49, 130, 11, hostCpu);
      if (linked) snprintf(b, sizeof(b), "MEM %3d%%", hostMem); else snprintf(b, sizeof(b), "MEM  --");
      drawRow(2, b);
      if (linked) drawBar(110, 66, 130, 11, hostMem);
      uint32_t up = millis() / 1000;
      snprintf(b, sizeof(b), "UP %luh%02lum FREE %uKB", (unsigned long)(up / 3600), (unsigned long)((up / 60) % 60), (unsigned)(ESP.getFreeHeap() / 1024));
      drawRow(3, b);
      break;
    }

    case PAGE_SETTINGS: {
      const char* labels[SETTINGS_ITEMS] = { "SENS", "DIR", "DEADZONE", "ACCEL", "HOST OS" };
      char v[12];
      for (int i = 0; i < SETTINGS_ITEMS; i++) {
        switch (i) {
          case 0: strlcpy(v, sensNames[cfg.sensIndex], sizeof(v)); break;
          case 1: strlcpy(v, cfg.invert ? "INVERT" : "NORMAL", sizeof(v)); break;
          case 2: snprintf(v, sizeof(v), "%d", cfg.deadzone); break;
          case 3: strlcpy(v, cfg.accel ? "ON" : "OFF", sizeof(v)); break;
          default: strlcpy(v, cfg.hostMac ? "MAC" : "WIN", sizeof(v)); break;
        }
        snprintf(b, sizeof(b), "%c %-8s %s", i == settingsSel ? '>' : ' ', labels[i], v);
        drawRow(i, b);
      }
      break;
    }
  }
}

void eInkDisplayTask(void* parameter) {
  SPI.begin(EPD_SCK, -1, EPD_MOSI, EPD_CS);
  display.epd2.selectSPI(SPI, SPISettings(4000000, MSBFIRST, SPI_MODE0));
  display.init(0, true, 2, false);
  display.setRotation(1);                 // 250x122 landscape
  display.setFont(&FreeMonoBold9pt7b);

  bool firstBoot = true;
  uint32_t lastSysRefresh = 0, lastDraw = 0;
  int partialCount = 0;
  uint8_t lastPage = 255;

  for (;;) {
    uint32_t now = millis();
    if (currentPage == PAGE_SYSTEM && (now - lastSysRefresh > 5000)) {
      lastSysRefresh = now;
      screenNeedsUpdate = true;
    }

    if (screenNeedsUpdate && (now - lastDraw) >= MIN_REFRESH_MS) {
      screenNeedsUpdate = false;
      lastDraw = now;
      uint8_t pg = currentPage;           // snapshot

      // Full refresh on boot, or when the user changes page and ghosting has built up.
      bool full = firstBoot || (partialCount >= 30 && pg != lastPage) || partialCount >= 150;
      if (full) { display.setFullWindow(); partialCount = 0; firstBoot = false; }
      else      { display.setPartialWindow(0, 0, display.width(), display.height()); partialCount++; }
      lastPage = pg;

      display.firstPage();
      do {
        display.fillScreen(GxEPD_WHITE);
        display.setTextSize(1);
        drawHeader(pg);
        renderPage(pg);
      } while (display.nextPage());

      display.hibernate();
    }
    vTaskDelay(pdMS_TO_TICKS(40));
  }
}

// ============================================================================
// 12b. HOME ASSISTANT TASK (Core 0): plain-HTTP client, no HTTPClient/TLS.
//      Never blocks the dial.
// ============================================================================
static char haBuf[4096];                   // only touched from haTask

// Minimal HTTP/1.0 request. Returns status code, or -1 on failure.
// Response (headers + body) is left NUL-terminated in haBuf.
static int haRequest(const char* method, const char* path, const char* body, size_t cap) {
  static char host[40];
  static uint16_t port = 0;
  if (!port) {
    const char* h = HA_HOST;
    if (!strncmp(h, "http://", 7)) h += 7;
    strlcpy(host, h, sizeof(host));
    char* s = strchr(host, '/'); if (s) *s = 0;
    char* c = strchr(host, ':');
    port = 80;
    if (c) { *c = 0; port = (uint16_t)atoi(c + 1); if (!port) port = 80; }
  }

  WiFiClient cl;
  if (!cl.connect(host, port, 2500)) return -1;
  cl.setNoDelay(true);

  cl.printf("%s %s HTTP/1.0\r\nHost: %s\r\nAuthorization: Bearer %s\r\nConnection: close\r\n",
            method, path, host, HA_TOKEN);
  if (body) cl.printf("Content-Type: application/json\r\nContent-Length: %u\r\n", (unsigned)strlen(body));
  cl.print("\r\n");
  if (body) cl.print(body);

  size_t n = 0;
  uint32_t deadline = millis() + 3000;
  while (millis() < deadline && n < cap - 1) {
    if (cl.available()) {
      int r = cl.read((uint8_t*)haBuf + n, cap - 1 - n);
      if (r > 0) n += r;
    } else if (!cl.connected()) break;
    else delay(2);
  }
  cl.stop();
  haBuf[n] = 0;
  if (n < 12 || strncmp(haBuf, "HTTP/", 5) != 0) return -1;
  return atoi(haBuf + 9);
}

static const char* haBody() {
  const char* b = strstr(haBuf, "\r\n\r\n");
  return b ? b + 4 : haBuf + strlen(haBuf);
}

bool haPost(const char* service, const char* body) {
  char path[48];
  snprintf(path, sizeof(path), "/api/services/light/%s", service);
  int code = haRequest("POST", path, body, 96);      // only need the status line
  haLastCode = code;
  bool ok = (code >= 200 && code < 300);
  if (ok != haOnline) { haOnline = ok; screenNeedsUpdate = true; }
  return ok;
}

void haPoll(int idx) {
  if (!strcmp(lights[idx].entity, "all")) return;     // no single state for "all"
  char path[80];
  snprintf(path, sizeof(path), "/api/states/%s", lights[idx].entity);
  int code = haRequest("GET", path, nullptr, sizeof(haBuf));
  haLastCode = code;
  if (code == 200) {
    const char* p = haBody();
    if (strstr(p, "\"state\":\"on\""))       haOn[idx] = 1;
    else if (strstr(p, "\"state\":\"off\"")) haOn[idx] = 0;
    else                                     haOn[idx] = -1;
    const char* q = strstr(p, "\"brightness\":");
    if (q) {
      int v = atoi(q + 13);                           // 0..255, null -> 0
      haBright[idx] = constrain((v * 100 + 127) / 255, 0, 100);
    }
    q = strstr(p, "\"color_temp_kelvin\":");
    if (q) {
      int k = atoi(q + 20);
      if (k > 0) haKelvin = k;
    }
  }
  haOnline = (code == 200);
  screenNeedsUpdate = true;
}

void haPollSensors() {
  for (int i = 0; i < SENSOR_COUNT; i++) {
    char path[80];
    snprintf(path, sizeof(path), "/api/states/%s", sensors[i].entity);
    int code = haRequest("GET", path, nullptr, sizeof(haBuf));
    haLastCode = code;
    haSensOk[i] = false;
    if (code == 200) {
      const char* p = haBody();
      const char* s = strstr(p, "\"state\":\"");
      if (s) {
        s += 9;
        char* end;
        float f = strtof(s, &end);
        if (end != s) { haSensVal[i] = f; haSensOk[i] = true; }   // "unavailable" -> not ok
      }
      const char* u = strstr(p, "\"unit_of_measurement\":\"");
      if (u) {
        u += 23;
        const char* e = strchr(u, '"');
        if (e && e > u) haSensUnit[i] = e[-1];
      }
    }
  }
  screenNeedsUpdate = true;
}

void haTask(void* parameter) {
  WiFi.mode(WIFI_STA);
  WiFi.setAutoReconnect(true);
  WiFi.begin(WIFI_SSID, WIFI_PASS);

  uint32_t lastSend = 0, lastPoll = 0, pollAt = 0, lastSensPoll = 0;
  bool firstPoll = true;

  for (;;) {
    if (WiFi.status() != WL_CONNECTED) {
      if (haOnline) { haOnline = false; screenNeedsUpdate = true; }
      vTaskDelay(pdMS_TO_TICKS(500));
      continue;
    }
    uint32_t now = millis();
    if (firstPoll) { firstPoll = false; pollAt = now + 50; }

    // Coalesce dial movement: send at most one batch every ~120 ms.
    if ((now - lastSend) >= 120) {
      int  tog = __atomic_exchange_n(&haPendToggle, 0, __ATOMIC_SEQ_CST);
      int  br  = __atomic_exchange_n(&haPendBright, 0, __ATOMIC_SEQ_CST);
      int  ct  = __atomic_exchange_n(&haPendCt, 0, __ATOMIC_SEQ_CST);
      bool ref = haPendRefresh; haPendRefresh = false;
      const char* ent = lights[haSel].entity;
      char body[128];
      bool sent = false;

      if (tog) {
        snprintf(body, sizeof(body), "{\"entity_id\":\"%s\"}", ent);
        haPost("toggle", body); sent = true;
      }
      if (br) {
        snprintf(body, sizeof(body), "{\"entity_id\":\"%s\",\"brightness_step_pct\":%d}", ent, br);
        haPost("turn_on", body); sent = true;
      }
      if (ct) {
        snprintf(body, sizeof(body), "{\"entity_id\":\"%s\",\"color_temp_kelvin\":%d}", ent, (int)haKelvin);
        haPost("turn_on", body); sent = true;
      }
      if (sent) { lastSend = millis(); pollAt = lastSend + 600; }   // confirm real state
      if (ref)  pollAt = millis() + 50;
    }

    if (pollAt && (int32_t)(millis() - pollAt) >= 0) {
      pollAt = 0; lastPoll = millis();
      haPoll(haSel);
    } else if (currentPage == PAGE_LIGHTS && (millis() - lastPoll) > 10000) {
      lastPoll = millis();
      haPoll(haSel);
    }

    // Temperature sensors: on page entry / SW2, and every 20 s while the page is open.
    if (haPendSens || (currentPage == PAGE_TEMPS && (millis() - lastSensPoll) > 20000)) {
      haPendSens = false;
      lastSensPoll = millis();
      haPollSensors();
    }
    vTaskDelay(pdMS_TO_TICKS(20));
  }
}

// ============================================================================
// 13. SETUP / LOOP
// ============================================================================
void setup() {
  Serial.begin(115200);
  loadSettings();

  for (int i = 0; i < 3; i++) pinMode(switchPins[i], INPUT_PULLUP);

  Wire.begin(SDA_PIN, SCL_PIN);
  Wire.setClock(400000L);

  Keyboard.begin();
  ConsumerControl.begin();
  Dial.begin();
  USB.productName("ESP32 Dial");
  USB.manufacturerName("DIY");
  USB.begin();

  pollMagnet();
  screenNeedsUpdate = true;

  for (int i = 0; i < 16; i++) { haOn[i] = -1; haBright[i] = 50; }

  xTaskCreatePinnedToCore(eInkDisplayTask, "eInkTask", 8192, NULL, 1, NULL, 0);
  xTaskCreatePinnedToCore(haTask, "haTask", 10240, NULL, 1, NULL, 0);
}

void loop() {
  uint32_t nowMs = millis();
  pollButtons(nowMs);
  pollSerial();
  housekeeping(nowMs);
  pollDial(micros());
}
