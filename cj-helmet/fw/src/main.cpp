// CJ-Helmet: подсветка шлема и колеса по данным Sherman L.
// Телефон (форк WheelLog) пишет пакеты данных по BLE; шлем пересылает режим колесу по ESP-NOW;
// кнопки поворотника шлют команды по ESP-NOW. Плата сама решает режим и рисует ленту
// по сегментам: у каждого куска ленты своя роль (бок, стоп-полоса, ходовой огонь, педаль...).
#include <Arduino.h>
#include <NimBLEDevice.h>
#include <Adafruit_NeoPixel.h>
#include <Preferences.h>
#include <WiFi.h>
#include <esp_now.h>
#include <esp_wifi.h>
#include <ArduinoOTA.h>
#include <WiFiUdp.h>
#include <WiFiClientSecure.h>
#include <HTTPClient.h>
#include <Update.h>
#include "protokol.h"
#if __has_include("sekrety.h")
#include "sekrety.h"  // OTA_PASS для обновления с MiniGUN; создаётся на MiniGUN, в git нет
#endif
// Сеть и пароль Wi-Fi в прошивке не хранятся: их передаёт приложение (characteristic net), лежат в NVS.

// Выводы платы - менять только здесь
#if defined(BOARD_C3)
#define PIN_LENTA   10     // ESP32-C3 Super Mini: данные ленты через 74AHCT125
#define PIN_PLATA   8      // синий светодиод на плате, горит по LOW
#define PIN_RIM     7      // ключ обода: HIGH - обод погашен (BC547 закрывает MOSFET)
#define HOST_NAME   "cj-wheel"
#define BOARD_ID    "c3-mini"
#define PIN_WHEEL_RX 3     // провод RXD модуля Bluetooth колеса (только приём)
#else
#define PIN_LENTA   18     // nanoESP32-C6
#define PIN_PLATA   8      // RGB-светодиод на плате
#define PIN_RIM     -1
#define HOST_NAME   "cj-helmet"
#define BOARD_ID    "nano-c6"
#define PIN_WHEEL_RX 20    // провод RXD модуля Bluetooth колеса (только приём)
#endif
#define MAX_LEDS    300
#define TIMEOUT_MS  1000   // нет пакетов дольше - режим «нет связи»
#define HOLD_MS     300    // стоп и разгон держатся не меньше, против дребезга на кочках
#define PARK_MS     5000   // стоит дольше - стоянка
#define RIM_PARK_MS 30000  // обод гаснет на стоянке через
#define TEST_MS     5000   // проверка из приложения длится
#define HILITE_MS   10000  // подсветка сегмента из приложения
#define FRAME_MS    200    // кадр ленты в приложение, 5 раз в секунду
#define ESPNOW_CH   1
#define UPD_BASE    "https://cjgun.ru/assets/proshivka/"  // <плата>.json: {"md5": "...", "url": "..."}

// Корень цепочки cjgun.ru (Let's Encrypt): ISRG Root X1, действует до 2035 года
static const char ROOT_CA[] = R"(-----BEGIN CERTIFICATE-----
MIIFazCCA1OgAwIBAgIRAIIQz7DSQONZRGPgu2OCiwAwDQYJKoZIhvcNAQELBQAw
TzELMAkGA1UEBhMCVVMxKTAnBgNVBAoTIEludGVybmV0IFNlY3VyaXR5IFJlc2Vh
cmNoIEdyb3VwMRUwEwYDVQQDEwxJU1JHIFJvb3QgWDEwHhcNMTUwNjA0MTEwNDM4
WhcNMzUwNjA0MTEwNDM4WjBPMQswCQYDVQQGEwJVUzEpMCcGA1UEChMgSW50ZXJu
ZXQgU2VjdXJpdHkgUmVzZWFyY2ggR3JvdXAxFTATBgNVBAMTDElTUkcgUm9vdCBY
MTCCAiIwDQYJKoZIhvcNAQEBBQADggIPADCCAgoCggIBAK3oJHP0FDfzm54rVygc
h77ct984kIxuPOZXoHj3dcKi/vVqbvYATyjb3miGbESTtrFj/RQSa78f0uoxmyF+
0TM8ukj13Xnfs7j/EvEhmkvBioZxaUpmZmyPfjxwv60pIgbz5MDmgK7iS4+3mX6U
A5/TR5d8mUgjU+g4rk8Kb4Mu0UlXjIB0ttov0DiNewNwIRt18jA8+o+u3dpjq+sW
T8KOEUt+zwvo/7V3LvSye0rgTBIlDHCNAymg4VMk7BPZ7hm/ELNKjD+Jo2FR3qyH
B5T0Y3HsLuJvW5iB4YlcNHlsdu87kGJ55tukmi8mxdAQ4Q7e2RCOFvu396j3x+UC
B5iPNgiV5+I3lg02dZ77DnKxHZu8A/lJBdiB3QW0KtZB6awBdpUKD9jf1b0SHzUv
KBds0pjBqAlkd25HN7rOrFleaJ1/ctaJxQZBKT5ZPt0m9STJEadao0xAH0ahmbWn
OlFuhjuefXKnEgV4We0+UXgVCwOPjdAvBbI+e0ocS3MFEvzG6uBQE3xDk3SzynTn
jh8BCNAw1FtxNrQHusEwMFxIt4I7mKZ9YIqioymCzLq9gwQbooMDQaHWBfEbwrbw
qHyGO0aoSCqI3Haadr8faqU9GY/rOPNk3sgrDQoo//fb4hVC1CLQJ13hef4Y53CI
rU7m2Ys6xt0nUW7/vGT1M0NPAgMBAAGjQjBAMA4GA1UdDwEB/wQEAwIBBjAPBgNV
HRMBAf8EBTADAQH/MB0GA1UdDgQWBBR5tFnme7bl5AFzgAiIyBpY9umbbjANBgkq
hkiG9w0BAQsFAAOCAgEAVR9YqbyyqFDQDLHYGmkgJykIrGF1XIpu+ILlaS/V9lZL
ubhzEFnTIZd+50xx+7LSYK05qAvqFyFWhfFQDlnrzuBZ6brJFe+GnY+EgPbk6ZGQ
3BebYhtF8GaV0nxvwuo77x/Py9auJ/GpsMiu/X1+mvoiBOv/2X/qkSsisRcOj/KK
NFtY2PwByVS5uCbMiogziUwthDyC3+6WVwW6LLv3xLfHTjuCvjHIInNzktHCgKQ5
ORAzI4JMPJ+GslWYHb4phowim57iaztXOoJwTdwJx4nLCgdNbOhdjsnvzqvHu7Ur
TkXWStAmzOVyyghqpZXjFaH3pO3JLF+l+/+sKAIuvtd7u+Nxe5AW0wdeRlN8NwdC
jNPElpzVmbUq4JUagEiuTDkHzsxHpFKVK7q4+63SM1N95R1NbdWhscdCb+ZAJzVc
oyi3B43njTOQ5yOf+1CceWxG1bQVs5ZufpsMljq4Ui0/1lvh+wjChP4kqKOJ2qxq
4RgqsahDYVvTH9w7jXbyLeiNdd8XM2w9U/t7y0Ff/9yi0GE44Za4rF2LN9d11TPA
mRGunUHBcnWEvgJBQl9nJEiU0Zsnvgc/ubhPgXRR4Xq37Z0j4r7g1SgEEzwxA57d
emyPxgcYxn/eR44/KJ4EBs+lVDR3veyJm+kXQ99b21/+jh5Xos1AnX5iItreGCc=
-----END CERTIFICATE-----
)";

static Config cfg;
static const Config CFG_DEFAULT = {
  CONFIG_VER, 40, 255, CFG_FRONT_WHITE,
  250,    // smooth_ms
  50,     // brake_decel: 5 км/ч в секунду (по логам 3-5 стопов в минуту езды)
  0,      // brake_current: WheelLog отдаёт ток по модулю, по току не тормозим
  40,     // accel: 4 км/ч в секунду
  30,     // min_speed: 3 км/ч
  135,    // num_leds
  15, 55, 80, 120,
  {0, 200, 160},  // разгон - бирюзовый
  15,     // turn_sec
  2000,   // limit_ma
  250,    // brake_tilt: 2.5 градуса назад сверх среднего
  8,      // tilt_avg_s
  0,      // strip_type: GRB
  0,      // kind: шлем
  5,      // nseg: шаблон интеграла, точка 0 справа у подбородка
  {{15, R_FRONT_R, 0}, {40, R_SIDE_R, 0}, {25, R_BACK, 0}, {40, R_SIDE_L, SEG_REV}, {15, R_FRONT_L, SEG_REV},
   {0, R_OFF, 0}, {0, R_OFF, 0}, {0, R_OFF, 0}},
  {255, 90, 0},   // педали - оранжевый
  85,     // warn_pwm
  RIM_FLASH_WARN,
  RELAY_SEND,
};
static const neoPixelType STRIP_TYPES[] = {NEO_GRB, NEO_RGB, NEO_BRG, NEO_GRBW, NEO_RGBW};
#define STRIP_TYPES_N (sizeof(STRIP_TYPES) / sizeof(STRIP_TYPES[0]))

static Adafruit_NeoPixel lenta(MAX_LEDS, PIN_LENTA, NEO_GRB + NEO_KHZ800);
static uint8_t stripType = 0;
static uint8_t fb[MAX_LEDS][3];   // кадр в RGB до ограничения тока
static uint16_t fbK = 256;        // множитель ограничения тока, /256
static Preferences nvs;
static portMUX_TYPE mux = portMUX_INITIALIZER_UNLOCKED;

// Общее состояние: пишут колбэки BLE и ESP-NOW, читает цикл отрисовки
static volatile bool connected = false;
static volatile uint32_t lastPkt = 0;       // последний пакет от телефона
static volatile uint8_t wheelFlags = 0;
static volatile int16_t speed = 0, current = 0;
static volatile uint8_t pwm = 0, battery = 0, alarms = 0;
static volatile float accelF = 0;           // сглаженное ускорение, 0.1 км/ч в секунду
static volatile uint16_t packets = 0, bad = 0;
static volatile uint8_t turn = T_OFF;
static volatile uint32_t turnUntil = 0, turnStart = 0;
static volatile int16_t pitch = 0;
static volatile float tiltAvg = 0;          // медленное среднее наклона, 0.01 градуса
static volatile uint32_t lastRelay = 0;     // колесо: последний пакет от шлема
static volatile uint32_t wLast = 0;         // последний пакет колеса по проводу
static volatile uint8_t relayMode = M_NOSIGNAL;
static volatile bool relayNow = false;      // шлем: переслать сейчас
static int16_t prevSpeed = 0;
static uint32_t prevT = 0;

static Mode mode = M_NOSIGNAL;
static uint32_t modeSince = 0, stillSince = 0;
static uint16_t lastMa = 0;

// Проверка и подсветка сегмента из приложения
static volatile uint8_t testWhat = 0;
static volatile uint32_t testUntil = 0, hiliteUntil = 0;
static volatile uint8_t hiliteSeg = 0xFF;
static volatile bool frameSub = false;

static NimBLECharacteristic *chrState, *chrConfig, *chrFrame;
static const uint8_t BCAST[6] = {0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF};

// ---------- настройки ----------
static uint16_t segSum(const Config &c) {
  uint16_t s = 0;
  for (int i = 0; i < c.nseg && i < MAX_SEG; i++) s += c.seg[i].count;
  return s;
}

static bool cfgValid(const Config &c) {
  if (c.nseg < 1 || c.nseg > MAX_SEG) return false;
  for (int i = 0; i < c.nseg; i++) if (c.seg[i].role >= R_COUNT) return false;
  uint16_t sum = segSum(c);
  return c.ver == CONFIG_VER && sum >= 1 && sum <= MAX_LEDS && c.num_leds == sum &&
         c.smooth_ms >= 20 && c.smooth_ms <= 2000 &&
         c.turn_sec >= 1 && c.turn_sec <= 120 && c.limit_ma >= 100 && c.limit_ma <= 10000 &&
         c.tilt_avg_s >= 1 && c.tilt_avg_s <= 60 && c.strip_type < STRIP_TYPES_N && c.kind <= 1 &&
         c.warn_pwm <= 100;
}

// Зоны v1-v3 -> пять сегментов шлема
static void zonesToSegs(Config &c) {
  uint16_t n = c.num_leds;
  uint16_t z[4] = {c.z_right, c.z_back, c.z_left, c.z_front_left};
  for (int i = 0; i < 4; i++) z[i] = min(z[i], n);
  memset(c.seg, 0, sizeof c.seg);
  c.nseg = 5;
  c.seg[0] = {z[0], R_FRONT_R, 0};
  c.seg[1] = {(uint16_t)(z[1] - min(z[0], z[1])), R_SIDE_R, 0};
  c.seg[2] = {(uint16_t)(z[2] - min(z[1], z[2])), R_BACK, 0};
  c.seg[3] = {(uint16_t)(z[3] - min(z[2], z[3])), R_SIDE_L, SEG_REV};
  c.seg[4] = {(uint16_t)(n - min(z[3], n)), R_FRONT_L, SEG_REV};
  c.num_leds = segSum(c);
}

// Сохранённые настройки старой версии: значения по умолчанию, поверх старые поля
static void cfgLoad() {
  uint8_t raw[sizeof(Config)];
  nvs.begin("helmet", false);
  size_t n = nvs.getBytes("cfg", raw, sizeof raw);
  Config c = CFG_DEFAULT;
  if (n >= 1 && raw[0] >= 1 && raw[0] <= CONFIG_VER && n == CONFIG_SIZE[raw[0]]) {
    memcpy(&c, raw, n);
    if (raw[0] < 4) zonesToSegs(c);
    c.ver = CONFIG_VER;
  }
  cfg = cfgValid(c) ? c : CFG_DEFAULT;
  if (n != sizeof cfg || raw[0] != CONFIG_VER) nvs.putBytes("cfg", &cfg, sizeof cfg);
}

static void cfgSave() { nvs.putBytes("cfg", &cfg, sizeof cfg); }

// ---------- приём данных ----------
static void takeData(const DataV2 &p, uint32_t now) {
  int16_t v = abs(p.speed);  // по модулю: знак скорости не важен
  portENTER_CRITICAL(&mux);
  uint32_t dt = now - prevT;
  if (prevT == 0 || dt > 500) {
    accelF = 0;  // после перерыва ускорение не считаем по старой скорости
    tiltAvg = p.pitch;
  } else if (dt > 0) {
    // Медианы нет: колесо шлёт 5 пакетов в секунду, скорость в логах гладкая
    float a = (v - prevSpeed) * 1000.0f / dt;
    float k = (float)dt / (cfg.smooth_ms + dt);
    accelF = accelF + k * (a - accelF);
    tiltAvg += (float)dt / (cfg.tilt_avg_s * 1000.0f + dt) * (p.pitch - tiltAvg);
  }
  prevSpeed = v;
  prevT = now;
  pitch = p.pitch;
  speed = p.speed;
  current = p.current;
  wheelFlags = p.flags;
  pwm = p.pwm; battery = p.battery; alarms = p.alarms;
  portEXIT_CRITICAL(&mux);
}

static void onData(const uint8_t *d, size_t n) {
  DataV2 p = {};
  if (n == sizeof(DataV1) && d[0] == 1) memcpy(&p, d, sizeof(DataV1));
  else if (n == sizeof(DataV2) && d[0] == 2) memcpy(&p, d, sizeof(DataV2));
  else { bad++; return; }
  uint32_t now = millis();
  takeData(p, now);
  lastPkt = now;
  packets++;
  relayNow = true;
}

static void setTurn(uint8_t t) {
  portENTER_CRITICAL(&mux);
  if (t == T_OFF || t == turn) turn = T_OFF;  // повторное нажатие гасит
  else { turn = t; turnStart = millis(); turnUntil = turnStart + cfg.turn_sec * 1000UL; }
  portEXIT_CRITICAL(&mux);
  relayNow = true;
}

static void onEspNow(const esp_now_recv_info_t *info, const uint8_t *d, int n) {
  if (n == sizeof(TurnCmd) && d[0] == 'C' && d[1] == 'J' && d[2] == 1 && d[3] <= T_BOTH) { setTurn(d[3]); return; }
  if (n == sizeof(Relay) && d[0] == 'C' && d[1] == 'J' && d[2] == 2 && (cfg.relay & RELAY_RECV)) {
    Relay r;
    memcpy(&r, d, sizeof r);
    uint32_t now = millis();
    if (lastPkt && now - lastPkt < TIMEOUT_MS) return;  // телефон пишет сам - он главнее
    takeData(r.d, now);
    relayMode = r.mode;
    if (r.turn != turn) { turn = r.turn; turnStart = now; turnUntil = now + cfg.turn_sec * 1000UL; }
    lastRelay = now;
    packets++;
    return;
  }
  bad++;
}

class SrvCb : public NimBLEServerCallbacks {
  void onConnect(NimBLEServer *s, NimBLEConnInfo &ci) override {
    connected = true;
    s->updateConnParams(ci.getConnHandle(), 12, 24, 0, 300);  // 15-30 мс
    Serial.printf("BLE: подключён %s\n", ci.getAddress().toString().c_str());
  }
  void onDisconnect(NimBLEServer *s, NimBLEConnInfo &ci, int reason) override {
    connected = false;
    frameSub = false;
    lastPkt = 0;
    Serial.printf("%lu BLE: отключён, причина %d\n", (unsigned long)millis(), reason);
  }
};

class DataCb : public NimBLECharacteristicCallbacks {
  void onWrite(NimBLECharacteristic *c, NimBLEConnInfo &ci) override {
    NimBLEAttValue v = c->getValue();
    onData(v.data(), v.size());
  }
};

class CfgCb : public NimBLECharacteristicCallbacks {
  void onWrite(NimBLECharacteristic *c, NimBLEConnInfo &ci) override {
    NimBLEAttValue v = c->getValue();
    Config nc;
    if (v.size() == sizeof nc) {
      memcpy(&nc, v.data(), sizeof nc);
      nc.num_leds = segSum(nc);
      if (cfgValid(nc)) {
        cfg = nc;
        cfgSave();
        c->setValue((uint8_t *)&cfg, sizeof cfg);
        Serial.println("config: записан");
        return;
      }
    }
    bad++;
    Serial.printf("config: отброшен, %u байт\n", (unsigned)v.size());
    c->setValue((uint8_t *)&cfg, sizeof cfg);  // вернуть действующие
  }
};

class CmdCb : public NimBLECharacteristicCallbacks {
  void onWrite(NimBLECharacteristic *c, NimBLEConnInfo &ci) override {
    NimBLEAttValue v = c->getValue();
    if (v.size() < 2) { bad++; return; }
    uint8_t op = v.data()[0], arg = v.data()[1];
    uint32_t now = millis();
    Serial.printf("%lu команда %u %u\n", (unsigned long)now, op, arg);
    if (op == C_TEST) {
      // проверка не включается на ходу: скорость выше 3 км/ч
      if (arg != 0 && abs(speed) > 30 && lastPkt && now - lastPkt < TIMEOUT_MS) return;
      testWhat = arg;
      testUntil = now + TEST_MS;
      if (arg >= 4 && arg <= 6) { turn = arg == 4 ? T_LEFT : arg == 5 ? T_RIGHT : T_BOTH; turnStart = now; turnUntil = now + TEST_MS; }
      if (arg == 0) { testUntil = now; if (turn != T_OFF) turn = T_OFF; }
    } else if (op == C_HILITE) {
      hiliteSeg = arg;
      hiliteUntil = now + HILITE_MS;
    } else if (op == C_TURN && arg <= T_BOTH) {
      setTurn(arg);
    } else bad++;
  }
};

class FrameCb : public NimBLECharacteristicCallbacks {
  void onSubscribe(NimBLECharacteristic *c, NimBLEConnInfo &ci, uint16_t sub) override { frameSub = sub & 1; }
};

// ---------- режим ----------
static void updateMode(uint32_t now) {
  int16_t spd, cur; float a, tilt; uint8_t fl, pw, al; uint32_t lp, lr;
  portENTER_CRITICAL(&mux);
  spd = abs(speed); cur = current; a = accelF; fl = wheelFlags; lp = lastPkt; lr = lastRelay;
  pw = pwm; al = alarms;
  tilt = pitch - tiltAvg;  // в WheelLog наклон назад (торможение) положительный
  bool sgn = speed < 0;
  portEXIT_CRITICAL(&mux);

  bool phone = connected && lp && now - lp <= TIMEOUT_MS;
  bool relay = !phone && lr && now - lr <= TIMEOUT_MS && relayMode != 255;
  bool wire = !phone && wLast && now - wLast <= TIMEOUT_MS;  // данные по проводу от колеса
  if (wire) { phone = true; fl = 1; }
  Mode m;
  if (relay) {
    m = (Mode)relayMode;  // колесо показывает то же, что шлем
  } else if (!phone || !(fl & 1)) {
    m = M_NOSIGNAL;  // старый стоп-сигнал не держим
  } else {
    int regen = sgn ? cur : -cur;  // рекуперация: ток против движения; знак проверить, флаг инвертирует
    if (cfg.flags & CFG_CURRENT_INVERT) regen = -regen;
    bool byCur = cfg.brake_current && spd >= cfg.min_speed;
    bool keep = (mode == M_BRAKE);  // гистерезис: выход на 60% порога
    float thrA = keep ? cfg.brake_decel * 0.6f : cfg.brake_decel;
    int thrI = keep ? cfg.brake_current * 6 / 10 : cfg.brake_current;
    float thrT = keep ? cfg.brake_tilt * 0.6f : cfg.brake_tilt;
    bool byTilt = cfg.brake_tilt && spd >= 50 && tilt >= thrT;  // наклон только на ходу, от 5 км/ч
    bool brake = (cfg.brake_decel && a <= -thrA) || (byCur && regen >= thrI) || byTilt;
    float thrAcc = mode == M_ACCEL ? cfg.accel * 0.6f : cfg.accel;
    bool acc = cfg.accel && spd >= cfg.min_speed && a >= thrAcc;
    bool warn = (cfg.warn_pwm && pw >= cfg.warn_pwm) || (al & 1);
    if (spd >= 10) stillSince = now;
    bool park = now - stillSince >= PARK_MS;
    m = warn ? M_WARN : brake ? M_BRAKE : acc ? M_ACCEL : park ? M_PARK : M_NORM;
    // не выходить из стопа и разгона раньше HOLD_MS - кочка не моргает
    if ((mode == M_BRAKE || mode == M_ACCEL) && m != mode && m != M_BRAKE && m != M_WARN && now - modeSince < HOLD_MS) m = mode;
  }
  if (m != mode) {
    mode = m;
    modeSince = now;
    relayNow = true;
    Serial.printf("%lu режим %d, a=%.1f v=%d\n", (unsigned long)now, m, a / 10, spd);
  }
  if (turn != T_OFF && (int32_t)(now - turnUntil) >= 0) { turn = T_OFF; relayNow = true; }
}

// Шлем пересылает колесу режим, поворот и данные - колесо горит синхронно без своей связи с телефоном
static void relayTick(uint32_t now) {
  static uint32_t last = 0;
  if (!(cfg.relay & RELAY_SEND) || cfg.kind != 0) return;
  if (!relayNow && now - last < 200) return;
  if (!(connected && lastPkt && now - lastPkt <= TIMEOUT_MS)) return;
  relayNow = false;
  last = now;
  Relay r = {'C', 'J', 2, (uint8_t)mode, turn, {}};
  portENTER_CRITICAL(&mux);
  r.d = {2, wheelFlags, speed, current, pitch, pwm, battery, alarms};
  portEXIT_CRITICAL(&mux);
  esp_now_send(BCAST, (uint8_t *)&r, sizeof r);
}

// ---------- отрисовка ----------
static inline void px(int i, uint8_t r, uint8_t g, uint8_t b) {
  if (i >= 0 && i < cfg.num_leds) { fb[i][0] = r; fb[i][1] = g; fb[i][2] = b; }
}
static inline void pxs(int i, uint8_t r, uint8_t g, uint8_t b, uint8_t br) {
  px(i, r * br / 255, g * br / 255, b * br / 255);
}

static bool isRight(uint8_t role) {
  return role == R_FRONT_R || role == R_SIDE_R || role == R_PEAK_R || role == R_DRL_R || role == R_TAIL_R || role == R_PEDAL_R;
}
static bool isLeft(uint8_t role) {
  return role == R_FRONT_L || role == R_SIDE_L || role == R_PEAK_L || role == R_DRL_L || role == R_TAIL_L || role == R_PEDAL_L;
}
static bool isWave(uint8_t role) {  // поворот бегущей волной; остальные мигают целиком
  return (role <= R_FRONT_L && role != R_BACK) || role == R_DRL_R || role == R_DRL_L || role == R_TAIL_R || role == R_TAIL_L;
}
static int rank(uint8_t role) {  // порядок волны поворота: спереди назад
  switch (role) {
    case R_FRONT_R: case R_FRONT_L: case R_DRL_R: case R_DRL_L: return 0;
    case R_SIDE_R: case R_SIDE_L: return 1;
    default: return 2;
  }
}
static uint16_t segStart(int s) {
  uint16_t a = 0;
  for (int i = 0; i < s; i++) a += cfg.seg[i].count;
  return a;
}

// Поворот одной стороны: волна по всем волновым сегментам стороны спереди назад,
// 500 мс заполнение и 300 мс пауза; козырёк и педали мигают в такт
static void drawTurn(bool left, uint32_t now) {
  uint32_t ph = (now - turnStart) % 800;
  bool on = ph < 500;
  uint8_t br = cfg.bright_brake;
  int total = 0;
  for (int s = 0; s < cfg.nseg; s++) {
    uint8_t ro = cfg.seg[s].role;
    if (!(left ? isLeft(ro) : isRight(ro))) continue;
    uint16_t a = segStart(s), n = cfg.seg[s].count;
    for (int j = 0; j < n; j++) px(a + j, 0, 0, 0);
    if (isWave(ro)) total += n;
    else if (on) for (int j = 0; j < n; j++) pxs(a + j, 255, 140, 0, br);
  }
  if (!on || total == 0) return;
  int k = total * (ph + 50) / 500, done = 0;
  for (int r = 0; r <= 2; r++)
    for (int s = 0; s < cfg.nseg; s++) {
      uint8_t ro = cfg.seg[s].role;
      if (!(left ? isLeft(ro) : isRight(ro)) || !isWave(ro) || rank(ro) != r) continue;
      uint16_t a = segStart(s), n = cfg.seg[s].count;
      bool rev = cfg.seg[s].flags & SEG_REV;
      for (int j = 0; j < n && done < k; j++, done++) pxs(rev ? a + n - 1 - j : a + j, 255, 140, 0, br);
    }
}

static void drawCalibrate() {
  for (int i = 0; i < cfg.num_leds; i++) {
    if (i % 10 == 0) pxs(i, 255, 255, 255, 60);
    if (i % 50 == 0) pxs(i, 0, 0, 255, 120);
  }
  for (int s = 0; s < cfg.nseg; s++) if (cfg.seg[s].count) pxs(segStart(s), 255, 0, 255, 120);
  pxs(0, 0, 255, 0, 120);
}

// Расчётный ток: 20 мА на канал на полной яркости плюс 1 мА покоя на точку; выше предела - притушить
static void output() {
  uint32_t sum = 0;
  int n = cfg.num_leds;
  for (int i = 0; i < n; i++) sum += fb[i][0] + fb[i][1] + fb[i][2];
  uint32_t idle = n, ma = sum * 20 / 255 + idle, k = 256;
  if (ma > cfg.limit_ma && sum) {
    k = (cfg.limit_ma - min(idle, (uint32_t)cfg.limit_ma)) * 255 * 256 / (sum * 20);
    ma = cfg.limit_ma;
  }
  fbK = k;
  lastMa = ma;
  for (int i = 0; i < n; i++) lenta.setPixelColor(i, fb[i][0] * k >> 8, fb[i][1] * k >> 8, fb[i][2] * k >> 8);
  lenta.show();
}

static void draw(uint32_t now) {
  int n = cfg.num_leds;
  memset(fb, 0, n * 3);
  uint8_t t = turn;
  Mode m = mode;
  bool test = (int32_t)(testUntil - now) > 0;
  if (test) {
    static const Mode TM[] = {M_NORM, M_NORM, M_BRAKE, M_ACCEL, M_NORM, M_NORM, M_NORM, M_WARN, M_PARK, M_NORM};
    m = testWhat < 10 ? TM[testWhat] : M_NORM;
  }
  bool calib = (cfg.flags & CFG_CALIBRATE) || (test && testWhat == 9);
  if (calib) {
    drawCalibrate();
  } else {
    uint8_t tb = cfg.bright_tail, bb = cfg.bright_brake;
    bool brake = m == M_BRAKE, accel = m == M_ACCEL, park = m == M_PARK;
    bool warnOn = m == M_WARN && (now / 125) & 1;
    uint32_t since = now - modeSince;
    for (int s = 0; s < cfg.nseg; s++) {
      uint8_t ro = cfg.seg[s].role;
      uint16_t a = segStart(s), cnt = cfg.seg[s].count;
      for (int j = 0; j < cnt; j++) {
        int i = a + j;
        switch (ro) {
          case R_FRONT_R: case R_FRONT_L:
            if (cfg.flags & CFG_FRONT_WHITE) pxs(i, 255, 255, 255, tb); break;
          case R_DRL_R: case R_DRL_L:
            pxs(i, 255, 255, 255, max<uint8_t>(tb, 80)); break;
          case R_PEAK_R: case R_PEAK_L:
            pxs(i, 255, 255, 255, tb); break;
          case R_SIDE_R: case R_SIDE_L:
            pxs(i, 255, 0, 0, brake ? bb : tb); break;
          case R_TAIL_R: case R_TAIL_L:
            pxs(i, 255, 0, 0, brake && (cfg.flags & CFG_TAIL_BRAKE) ? bb : tb); break;
          case R_BACK:
            if (brake) pxs(i, 255, 0, 0, bb);
            else if (accel) pxs(i, cfg.accel_rgb[0], cfg.accel_rgb[1], cfg.accel_rgb[2], bb);
            else pxs(i, 255, 0, 0, tb);
            break;
          case R_STOP: {
            // стоп растёт от центра к краям за 150 мс
            float mid = (cnt - 1) / 2.0f, grow = min(1.0f, since / 150.0f);
            if (brake && fabsf(j - mid) <= mid * grow + 0.5f) pxs(i, 255, 0, 0, bb);
            else if (accel) pxs(i, cfg.accel_rgb[0], cfg.accel_rgb[1], cfg.accel_rgb[2], bb);
            else pxs(i, 255, 0, 0, tb);
            break;
          }
          case R_PEDAL_R: case R_PEDAL_L:
            if (park) {  // на стоянке - заряд колеса зелёной шкалой
              bool lit = j * 100 < (int)battery * cnt;
              if (lit) pxs(i, 0, 255, 40, tb); else pxs(i, 60, 0, 0, tb);
            } else pxs(i, cfg.pedal_rgb[0], cfg.pedal_rgb[1], cfg.pedal_rgb[2], tb);
            break;
          default: break;
        }
        // предупреждение колеса: всё, кроме белого спереди, мигает красным и жёлтым
        if (m == M_WARN && ro != R_FRONT_R && ro != R_FRONT_L && ro != R_DRL_R && ro != R_DRL_L && ro != R_OFF)
          pxs(i, 255, warnOn ? 150 : 30, 0, bb);
      }
    }
    // поворот поверх; в проверке поворот задан командой
    if (t == T_RIGHT || t == T_BOTH) drawTurn(false, now);
    if (t == T_LEFT || t == T_BOTH) drawTurn(true, now);
  }
  // подсветка сегмента из редактора: мигает сиреневым
  if ((int32_t)(hiliteUntil - now) > 0 && hiliteSeg < cfg.nseg && (now / 250) & 1) {
    uint16_t a = segStart(hiliteSeg);
    for (int j = 0; j < cfg.seg[hiliteSeg].count; j++) pxs(a + j, 255, 0, 255, 200);
  }
  output();

  // Обод колеса: ключ в минусе драйвера индукции, HIGH - погасить
#if PIN_RIM >= 0
  bool rimOff = false;
  bool ph = (now / 200) & 1;
  if ((cfg.rim & RIM_FLASH_WARN) && m == M_WARN && ph) rimOff = true;
  if ((cfg.rim & RIM_FLASH_BRAKE) && m == M_BRAKE && ph) rimOff = true;
  if ((cfg.rim & RIM_OFF_PARK) && m == M_PARK && now - stillSince > RIM_PARK_MS) rimOff = true;
  digitalWrite(PIN_RIM, rimOff ? HIGH : LOW);
#endif

  // Светодиод на плате
  bool blink = (now / 500) & 1;
#if defined(BOARD_C3)
  bool led = calib || m == M_BRAKE || m == M_WARN || (t != T_OFF && ((now - turnStart) % 800) < 500) ||
             ((m == M_NORM || m == M_ACCEL || m == M_PARK) && (now % 2000) < 60) || (m == M_NOSIGNAL && blink);
  digitalWrite(PIN_PLATA, led ? LOW : HIGH);
#else
  uint8_t r = 0, g = 0, b = 0;
  if (calib) { r = g = b = 20; }
  else if (t != T_OFF && ((now - turnStart) % 800) < 500) { r = 60; g = 25; }
  else if (m == M_BRAKE || m == M_WARN) r = 80;
  else if (m == M_ACCEL) { r = cfg.accel_rgb[0] / 3; g = cfg.accel_rgb[1] / 3; b = cfg.accel_rgb[2] / 3; }
  else if (m == M_NORM || m == M_PARK) r = 6;
  else r = blink ? 6 : 0;  // нет связи - медленно мигает
  rgbLedWrite(PIN_PLATA, r, g, b);
#endif
}

// Кадр ленты в приложение кусками по FRAME_CHUNK точек, после ограничения тока
static void sendFrame(uint32_t now) {
  static uint32_t last = 0;
  if (!frameSub || !connected || now - last < FRAME_MS) return;
  last = now;
  uint8_t buf[3 + FRAME_CHUNK * 3];
  for (int off = 0; off < cfg.num_leds; off += FRAME_CHUNK) {
    int cnt = min(FRAME_CHUNK, cfg.num_leds - off);
    buf[0] = off & 0xFF; buf[1] = off >> 8; buf[2] = cnt;
    for (int j = 0; j < cnt; j++)
      for (int c = 0; c < 3; c++) buf[3 + j * 3 + c] = fb[off + j][c] * fbK >> 8;
    chrFrame->notify(buf, 3 + cnt * 3);
  }
}

// ---------- колесо по проводу ----------
// Плата слушает провод RXD Bluetooth-модуля колеса (колесо -> модуль): те же пакеты Veteran, что
// получает телефон. Только приём, вывод TX не настроен - в колесо ничего не уходит.
// Пакет: DC 5A 5C, длина, данные big-endian; скорость s16 @6 (0.1 км/ч), напряжение u16 @4 (0.01 В),
// фазный ток s16 @16 (0.1 А), наклон s16 @32 (0.01 град). Длина > 38 - в конце CRC32.
static const uint32_t BAUDS[] = {57600, 115200, 9600, 38400, 19200};
static uint8_t baudIdx = 0;
static uint32_t baudSince = 0, wFrames = 0, wBad = 0, wBytes = 0;
static int16_t wSpeed = 0, wPitch = 0, wCur = 0; static uint16_t wVolt = 0; static uint8_t wPwm = 0, wBat = 0;
static uint8_t wbuf[256]; static int wlen = 0;
static WiFiUDP udp;

static uint32_t crc32(const uint8_t *d, int n) {
  uint32_t c = 0xFFFFFFFF;
  for (int i = 0; i < n; i++) { c ^= d[i]; for (int k = 0; k < 8; k++) c = (c >> 1) ^ (0xEDB88320 & -(c & 1)); }
  return ~c;
}
static inline int16_t s16be(const uint8_t *p) { return (int16_t)((p[0] << 8) | p[1]); }

static void wheelFrame(const uint8_t *f, int n) {
  // n - всё с заголовка; данные после 4 байт заголовка+длины
  if (n > 42) {  // длина > 38 - CRC32 в конце
    uint32_t want = ((uint32_t)f[n - 4] << 24) | (f[n - 3] << 16) | (f[n - 2] << 8) | f[n - 1];
    if (crc32(f, n - 4) != want) { wBad++; return; }
  }
  if (n < 36) { wBad++; return; }
  wVolt = (f[4] << 8) | f[5]; wSpeed = s16be(f + 6); wCur = s16be(f + 16); wPitch = s16be(f + 32);
  // PWM колеса @34: 0.01 %, как hwPwm в WheelLog (10000 = 100 %)
  wPwm = min(100, ((f[34] << 8) | f[35]) / 100);
  // заряд по напряжению, как у WheelLog для Sherman L (0.01 В): 119.02 В = 0 %, 148.05 В = 100 %
  wBat = wVolt <= 11902 ? 0 : wVolt >= 14805 ? 100 : (wVolt - 11902) * 100 / 2903;
  wFrames++; wLast = millis();
  // если телефон сейчас не пишет - провод становится источником данных
  if (!(lastPkt && millis() - lastPkt < TIMEOUT_MS && connected)) {
    DataV2 p = {2, 1, wSpeed, wCur, wPitch, wPwm, wBat, 0};
    takeData(p, millis());
    lastRelay = millis(); relayMode = 255;  // помечаем: данные есть, режим считаем сами
  }
}

static void wheelTick(uint32_t now) {
  if (baudSince == 0) { Serial1.begin(BAUDS[0], SERIAL_8N1, PIN_WHEEL_RX, -1); baudSince = now; }
  while (Serial1.available()) {
    uint8_t b = Serial1.read(); wBytes++;
    if (wlen < 3) {  // ищем DC 5A 5C
      static const uint8_t H[3] = {0xDC, 0x5A, 0x5C};
      if (b == H[wlen]) wbuf[wlen++] = b; else wlen = (b == 0xDC) ? (wbuf[0] = b, 1) : 0;
      continue;
    }
    wbuf[wlen++] = b;
    if (wlen >= 4) {
      int total = 4 + wbuf[3];
      if (total > (int)sizeof wbuf) { wlen = 0; wBad++; continue; }
      if (wlen >= total) { wheelFrame(wbuf, total); wlen = 0; }
    }
  }
  // за 3 с ни одного пакета - следующая скорость
  if (wFrames == 0 && now - baudSince > 3000) {
    baudIdx = (baudIdx + 1) % (sizeof BAUDS / sizeof BAUDS[0]);
    Serial1.updateBaudRate(BAUDS[baudIdx]); baudSince = now; wlen = 0;
  }
  static uint32_t lastRep = 0;
  if (now - lastRep >= 1000) {
    lastRep = now;
    char m[200];
    snprintf(m, sizeof m, "провод: %lu бод, байт %lu, пакетов %lu, битых %lu, v=%.1f км/ч U=%.2f В I=%.1f А наклон=%.2f PWM=%u%% заряд=%u%%, режим %d",
             (unsigned long)BAUDS[baudIdx], (unsigned long)wBytes, (unsigned long)wFrames, (unsigned long)wBad,
             wSpeed / 10.0, wVolt / 100.0, wCur / 10.0, wPitch / 100.0, wPwm, wBat, mode);
    Serial.println(m);
    if (WiFi.isConnected()) { udp.beginPacket(IPAddress(255, 255, 255, 255), 47047); udp.write((const uint8_t *)m, strlen(m)); udp.endPacket(); }
  }
}

// ---------- имя, Wi-Fi и обновление по воздуху ----------
// Wi-Fi выключен, пока приложение не включит: радио одно, а ESP-NOW со шлемом живёт на своём канале.
// Сеть и пароль приходят из приложения и лежат в NVS. «upd» - плата сама берёт прошивку с cjgun.ru
// (TLS с проверкой сертификата, MD5 файла из описания), пишет во второй раздел и перезапускается.
// Настройки ленты в NVS при обновлении сохраняются.
static char bleName[NAME_MAX + 1];
static String wifiSsid, wifiPass;
static bool wifiOn = false, otaReady = false;
static volatile bool gotIp = false;
static volatile bool updWant = false, updBusy = false;
static char updState[64] = "нет";

static void netLoad() {
  Preferences p;
  p.begin("net", true);
  String n = p.getString("name", "");
  wifiSsid = p.getString("ssid", "");
  wifiPass = p.getString("pass", "");
  p.end();
  if (n.length()) snprintf(bleName, sizeof bleName, "%s", n.c_str());
  else {
    uint8_t m[6];
    WiFi.macAddress(m);
    snprintf(bleName, sizeof bleName, "%s-%02X%02X", cfg.kind == 1 ? NAME_WHEEL : NAME_HELMET, m[4], m[5]);
  }
}

static void setWifi(bool on) {
  if (on == wifiOn) return;
  if (on) {
    if (!wifiSsid.length()) { snprintf(updState, sizeof updState, "сеть не задана"); return; }
    WiFi.begin(wifiSsid.c_str(), wifiPass.c_str());
    Serial.printf("Wi-Fi: подключаюсь к %s\n", wifiSsid.c_str());
  } else {
    WiFi.disconnect(false, false);
    esp_wifi_set_channel(ESPNOW_CH, WIFI_SECOND_CHAN_NONE);  // ESP-NOW обратно на свой канал
    Serial.println("Wi-Fi: выключен");
  }
  wifiOn = on;
}

static String netStatus() {
  String s = "name=" + String(bleName) + "\nssid=" + wifiSsid + "\nwifi=";
  s += !wifiOn ? "off" : WiFi.isConnected() ? WiFi.localIP().toString() : "подключение";
  s += "\nupd=" + String(updState) + "\nver=" __DATE__ " " __TIME__ "\nboard=" BOARD_ID "\n";
  return s;
}

static void netCmd(const String &c) {
  Serial.printf("сеть: команда %s\n", c.startsWith("wifi=") ? "wifi=..." : c.c_str());
  if (c.startsWith("name=")) {
    String n = c.substring(5); n.trim();
    if (n.length() > NAME_MAX) n = n.substring(0, NAME_MAX);
    Preferences p; p.begin("net", false); p.putString("name", n); p.end();
    snprintf(updState, sizeof updState, "имя сохранено, будет после перезапуска");
  } else if (c.startsWith("wifi=")) {
    int t = c.indexOf('\t');
    if (t < 6) { snprintf(updState, sizeof updState, "нужно: сеть, табуляция, пароль"); return; }
    wifiSsid = c.substring(5, t); wifiPass = c.substring(t + 1);
    Preferences p; p.begin("net", false); p.putString("ssid", wifiSsid); p.putString("pass", wifiPass); p.end();
    snprintf(updState, sizeof updState, "сеть сохранена");
    if (wifiOn) { setWifi(false); setWifi(true); }
  } else if (c == "wifi on") setWifi(true);
  else if (c == "wifi off") { if (!updBusy) setWifi(false); }
  else if (c == "upd") { if (!updBusy) { updWant = true; setWifi(true); } }
  else if (c == "reboot") { delay(200); ESP.restart(); }
}

class NetCb : public NimBLECharacteristicCallbacks {
  void onRead(NimBLECharacteristic *c, NimBLEConnInfo &ci) override { c->setValue(netStatus()); }
  void onWrite(NimBLECharacteristic *c, NimBLEConnInfo &ci) override {
    NimBLEAttValue v = c->getValue();
    netCmd(String((const char *)v.data(), v.size()));
  }
};

// Обновление идёт в своей задаче: лента и Bluetooth живут, приложение читает ход в upd=.
// Вся работа - в updRun: TLS-клиент (~40 КБ) освобождается при выходе из неё, до vTaskDelete.
static void updRun() {
  uint32_t t0 = millis();
  // адрес от роутера, а не просто связь: сразу после включения isConnected бывает раньше DHCP
  while (!(WiFi.isConnected() && WiFi.localIP() != IPAddress(0, 0, 0, 0)) && millis() - t0 < 20000) delay(200);
  delay(500);
  if (!WiFi.isConnected()) { snprintf(updState, sizeof updState, "ошибка: нет Wi-Fi %s", wifiSsid.c_str()); return; }
  WiFiClientSecure cl;
  cl.setCACert(ROOT_CA);
  HTTPClient http;
  snprintf(updState, sizeof updState, "проверяю");
  int code = 0;
  String js;
  for (int i = 0; i < 3 && code != 200; i++) {  // три попытки: первая бывает до готовности сети
    if (i) delay(1500);
    http.begin(cl, UPD_BASE BOARD_ID ".json");
    code = http.GET();
    js = code == 200 ? http.getString() : "";
    http.end();
  }
  // описание короткое и своё: {"md5": "...", "url": "..."} - без библиотеки JSON
  auto field = [&](const char *k) {
    int i = js.indexOf(String("\"") + k + "\"");
    if (i < 0) return String();
    i = js.indexOf('"', js.indexOf(':', i) + 1);
    int j = js.indexOf('"', i + 1);
    return i < 0 || j < 0 ? String() : js.substring(i + 1, j);
  };
  String md5 = field("md5"), url = field("url");
  if (code != 200 || md5.length() != 32 || !url.startsWith("https://")) {
    snprintf(updState, sizeof updState, "ошибка: описание %d", code);
  } else if (md5 == ESP.getSketchMD5()) {
    snprintf(updState, sizeof updState, "уже последняя");
  } else {
    http.begin(cl, url);
    code = http.GET();
    int len = http.getSize();
    if (code != 200 || len <= 0 || !Update.begin(len)) {
      snprintf(updState, sizeof updState, "ошибка: файл %d", code);
    } else {
      Update.setMD5(md5.c_str());
      WiFiClient *st = http.getStreamPtr();
      uint8_t buf[1024];
      int got = 0;
      uint32_t last = millis();
      while (got < len && millis() - last < 15000) {
        int n = st->readBytes(buf, min((int)sizeof buf, len - got));
        if (n <= 0) continue;
        if (Update.write(buf, n) != (size_t)n) break;
        got += n; last = millis();
        snprintf(updState, sizeof updState, "загрузка %d%%", got * 100 / len);
      }
      if (got == len && Update.end()) {
        snprintf(updState, sizeof updState, "готово, перезапуск");
        Serial.println("обновление: готово, перезапуск");
        http.end();
        delay(1500);  // приложение успевает прочитать «готово»
        ESP.restart();
      }
      Update.abort();
      snprintf(updState, sizeof updState, "ошибка: %s", got == len ? Update.errorString() : "обрыв загрузки");
    }
    http.end();
  }
}

static void updTask(void *) {
  updBusy = true;
  updRun();
  Serial.printf("обновление: %s, свободно %u байт\n", updState, (unsigned)ESP.getFreeHeap());
  setWifi(false);
  updBusy = false;
  vTaskDelete(NULL);
}

static void wifiTick(uint32_t now) {
  if (updWant) {
    updWant = false;
    snprintf(updState, sizeof updState, "подключаюсь к Wi-Fi");
    xTaskCreate(updTask, "upd", 12288, NULL, 1, NULL);
  }
#ifdef OTA_PASS
  if (gotIp && !otaReady) {  // своя сборка: пока Wi-Fi включён, можно залить и с MiniGUN
    ArduinoOTA.setHostname(HOST_NAME);
    ArduinoOTA.setPassword(OTA_PASS);
    ArduinoOTA.onStart([]() { lenta.clear(); lenta.show(); Serial.println("OTA: начало"); });
    ArduinoOTA.onEnd([]() { Serial.println("OTA: готово, перезапуск"); });
    ArduinoOTA.onError([](ota_error_t e) { Serial.printf("OTA: ошибка %u\n", e); });
    ArduinoOTA.begin();
    otaReady = true;
  }
  if (otaReady && wifiOn) ArduinoOTA.handle();
#endif
  if (gotIp) {
    gotIp = false;
    Serial.printf("Wi-Fi: %s\n", WiFi.localIP().toString().c_str());
  }
}

// ---------- состояние ----------
static void sendState(uint32_t now) {
  static uint32_t last = 0;
  static State prev = {};
  State s;
  s.mode = mode; s.turn = turn; s.accel = (int16_t)accelF; s.speed = speed;
  s.packets = packets; s.bad = bad; s.ma = lastMa;
  bool changed = s.mode != prev.mode || s.turn != prev.turn;
  if (!changed && now - last < 1000) return;
  last = now;
  prev = s;
  chrState->setValue((uint8_t *)&s, sizeof s);
  if (connected) chrState->notify();
}

// Команды по USB: l/r/b/o - поворот, c - разметка вкл/выкл, d - настройки по умолчанию, s - состояние,
// w - Wi-Fi вкл/выкл (сеть из приложения), u - обновиться с cjgun.ru, n - имя, сеть, обновление
static void serialCmd() {
  while (Serial.available()) {
    char ch = Serial.read();
    if (ch == 'l') setTurn(T_LEFT);
    else if (ch == 'r') setTurn(T_RIGHT);
    else if (ch == 'b') setTurn(T_BOTH);
    else if (ch == 'o') setTurn(T_OFF);
    else if (ch == 'c') { cfg.flags ^= CFG_CALIBRATE; cfgSave(); chrConfig->setValue((uint8_t *)&cfg, sizeof cfg); }
    else if (ch == 'd') { cfg = CFG_DEFAULT; cfgSave(); chrConfig->setValue((uint8_t *)&cfg, sizeof cfg); }
    else if (ch == 'w') netCmd(wifiOn ? "wifi off" : "wifi on");
    else if (ch == 'u') netCmd("upd");
    else if (ch == 'n') Serial.print(netStatus());
    else if (ch == 's') {
      Serial.printf("режим %d поворот %d a=%.1f v=%d pwm %u заряд %u пакетов %u отброшено %u ток %u мА, сегментов %u, точек %u, Wi-Fi %s, прошивка %s\n",
                    mode, turn, accelF / 10, speed, pwm, battery, packets, bad, lastMa, cfg.nseg, cfg.num_leds,
                    WiFi.isConnected() ? WiFi.localIP().toString().c_str() : "нет", __DATE__ " " __TIME__);
      for (int i = 0; i < cfg.nseg; i++) Serial.printf("  сегмент %d: %u точек, роль %u, флаги %u\n", i, cfg.seg[i].count, cfg.seg[i].role, cfg.seg[i].flags);
    }
  }
}

void setup() {
  Serial.begin(115200);
  cfgLoad();
  stripType = cfg.strip_type;
  lenta.updateType(STRIP_TYPES[stripType] + NEO_KHZ800);
  lenta.begin();
  lenta.updateLength(cfg.num_leds);
#if PIN_RIM >= 0
  pinMode(PIN_RIM, OUTPUT);
  digitalWrite(PIN_RIM, LOW);  // обод горит, пока плата не решит иначе
#endif
#if defined(BOARD_C3)
  pinMode(PIN_PLATA, OUTPUT);
  digitalWrite(PIN_PLATA, HIGH);
#endif

  WiFi.mode(WIFI_STA);
  WiFi.setHostname(HOST_NAME);
  WiFi.onEvent([](arduino_event_id_t, arduino_event_info_t) { gotIp = true; }, ARDUINO_EVENT_WIFI_STA_GOT_IP);
  esp_wifi_set_channel(ESPNOW_CH, WIFI_SECOND_CHAN_NONE);
  if (esp_now_init() == ESP_OK) {
    esp_now_register_recv_cb(onEspNow);
    esp_now_peer_info_t peer = {};
    memcpy(peer.peer_addr, BCAST, 6);
    peer.channel = 0;  // текущий канал
    peer.ifidx = WIFI_IF_STA;
    esp_now_add_peer(&peer);
  }

  netLoad();
  const char *name = bleName;
  NimBLEDevice::init(name);
  NimBLEDevice::setMTU(247);
  NimBLEServer *srv = NimBLEDevice::createServer();
  srv->setCallbacks(new SrvCb());
  srv->advertiseOnDisconnect(true);
  NimBLEService *svc = srv->createService(UUID_SERVICE);
  svc->createCharacteristic(UUID_DATA, NIMBLE_PROPERTY::WRITE_NR | NIMBLE_PROPERTY::WRITE)->setCallbacks(new DataCb());
  chrConfig = svc->createCharacteristic(UUID_CONFIG, NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::WRITE);
  chrConfig->setCallbacks(new CfgCb());
  chrConfig->setValue((uint8_t *)&cfg, sizeof cfg);
  chrState = svc->createCharacteristic(UUID_STATE, NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::NOTIFY);
  svc->createCharacteristic(UUID_CMD, NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_NR)->setCallbacks(new CmdCb());
  chrFrame = svc->createCharacteristic(UUID_FRAME, NIMBLE_PROPERTY::NOTIFY);
  chrFrame->setCallbacks(new FrameCb());
  svc->createCharacteristic(UUID_NET, NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::WRITE)->setCallbacks(new NetCb());
  svc->start();
  // в рекламе сервис (16 + 2 байта), имя - в ответе на скан: иначе длинное имя не влезает в 31 байт
  NimBLEAdvertising *adv = NimBLEDevice::getAdvertising();
  NimBLEAdvertisementData ad, sr;
  ad.setFlags(BLE_HS_ADV_F_DISC_GEN | BLE_HS_ADV_F_BREDR_UNSUP);
  ad.addServiceUUID(UUID_SERVICE);
  sr.setName(name);
  adv->setAdvertisementData(ad);
  adv->setScanResponseData(sr);
  adv->start();

  Serial.printf("%s: лента GPIO%d, %u точек, %u сегментов; BLE %s; ESP-NOW канал %d, MAC %s\n",
                name, PIN_LENTA, cfg.num_leds, cfg.nseg, NimBLEDevice::getAddress().toString().c_str(),
                ESPNOW_CH, WiFi.macAddress().c_str());
}

void loop() {
  static uint32_t lastDraw = 0;
  uint32_t now = millis();
  serialCmd();
  wifiTick(now);
  relayTick(now);
  wheelTick(now);
  if (now - lastDraw >= 20) {  // 50 кадров в секунду
    lastDraw = now;
    if (stripType != cfg.strip_type) {
      stripType = cfg.strip_type;
      lenta.updateType(STRIP_TYPES[stripType] + NEO_KHZ800);
      lenta.updateLength(cfg.num_leds);
    }
    if (lenta.numPixels() != cfg.num_leds) lenta.updateLength(cfg.num_leds);
    updateMode(now);
    draw(now);
    sendState(now);
    sendFrame(now);
  }
  delay(2);
}
