// CJ-Helmet: индикация шлема по данным колеса Sherman L.
// Телефон (форк WheelLog) пишет пакеты DataV1 по BLE, кнопки поворотника шлют
// команды по ESP-NOW. Шлем сам решает режим и рисует ленту WS2812.
#include <Arduino.h>
#include <NimBLEDevice.h>
#include <Adafruit_NeoPixel.h>
#include <Preferences.h>
#include <WiFi.h>
#include <esp_now.h>
#include <esp_wifi.h>
#include "protokol.h"

#define PIN_LENTA   18     // вывод данных ленты - менять только здесь
#define PIN_PLATA   8      // RGB-светодиод на плате nanoESP32-C6
#define MAX_LEDS    300
#define TIMEOUT_MS  1000   // нет пакетов дольше - режим «нет связи»
#define HOLD_MS     300    // стоп и разгон держатся не меньше, против дребезга на кочках
#define ESPNOW_CH   1

static Config cfg;
static const Config CFG_DEFAULT = {
  CONFIG_VER, 40, 255, 0,
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
};

static Adafruit_NeoPixel lenta(MAX_LEDS, PIN_LENTA, NEO_GRB + NEO_KHZ800);
static Preferences nvs;
static portMUX_TYPE mux = portMUX_INITIALIZER_UNLOCKED;

// Общее состояние: пишут колбэки BLE и ESP-NOW, читает цикл отрисовки
static volatile bool connected = false;
static volatile uint32_t lastPkt = 0;
static volatile uint8_t wheelFlags = 0;
static volatile int16_t speed = 0, current = 0;
static volatile float accelF = 0;       // сглаженное ускорение, 0.1 км/ч в секунду
static volatile uint16_t packets = 0, bad = 0;
static volatile uint8_t turn = T_OFF;
static volatile uint32_t turnUntil = 0, turnStart = 0;
static volatile int16_t pitch = 0;
static volatile float tiltAvg = 0;      // медленное среднее наклона, 0.01 градуса
static int16_t prevSpeed = 0;
static uint32_t prevT = 0;

static Mode mode = M_NOSIGNAL;
static uint32_t modeSince = 0;
static uint16_t lastMa = 0;

static NimBLECharacteristic *chrState, *chrConfig;

// ---------- настройки ----------
static bool cfgValid(const Config &c) {
  return c.ver == CONFIG_VER && c.num_leds >= 1 && c.num_leds <= MAX_LEDS &&
         c.z_right <= c.z_back && c.z_back <= c.z_left && c.z_left <= c.z_front_left &&
         c.z_front_left <= c.num_leds && c.smooth_ms >= 20 && c.smooth_ms <= 2000 &&
         c.turn_sec >= 1 && c.turn_sec <= 120 && c.limit_ma >= 100 && c.limit_ma <= 10000 &&
         c.tilt_avg_s >= 1 && c.tilt_avg_s <= 60;
}

static void cfgLoad() {
  Config c;
  nvs.begin("helmet", false);
  if (nvs.getBytes("cfg", &c, sizeof c) == sizeof c && cfgValid(c)) cfg = c;
  else cfg = CFG_DEFAULT;
}

static void cfgSave() { nvs.putBytes("cfg", &cfg, sizeof cfg); }

// ---------- приём данных ----------
static void onData(const uint8_t *d, size_t n) {
  if (n != sizeof(DataV1) || d[0] != 1) { bad++; return; }
  DataV1 p;
  memcpy(&p, d, sizeof p);
  uint32_t now = millis();
  int16_t v = abs(p.speed);  // по модулю: знак скорости не важен
  portENTER_CRITICAL(&mux);
  uint32_t dt = now - prevT;
  if (prevT == 0 || dt > 500) {
    accelF = 0;  // после перерыва ускорение не считаем по старой скорости
    tiltAvg = p.pitch;
  } else if (dt > 0) {
    // Медианы нет: колесо шлёт 5 пакетов в секунду, скорость в логах гладкая,
    // а медиана добавляла бы ещё пакет задержки
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
  lastPkt = now;
  packets++;
  portEXIT_CRITICAL(&mux);
}

static void setTurn(uint8_t t) {
  portENTER_CRITICAL(&mux);
  if (t == T_OFF || t == turn) turn = T_OFF;  // повторное нажатие гасит
  else { turn = t; turnStart = millis(); turnUntil = turnStart + cfg.turn_sec * 1000UL; }
  portEXIT_CRITICAL(&mux);
}

static void onEspNow(const esp_now_recv_info_t *info, const uint8_t *d, int n) {
  if (n != sizeof(TurnCmd) || d[0] != 'C' || d[1] != 'J' || d[2] != 1 || d[3] > T_BOTH) { bad++; return; }
  setTurn(d[3]);
}

class SrvCb : public NimBLEServerCallbacks {
  void onConnect(NimBLEServer *s, NimBLEConnInfo &ci) override {
    connected = true;
    s->updateConnParams(ci.getConnHandle(), 12, 24, 0, 300);  // 15-30 мс
    Serial.printf("BLE: подключён %s\n", ci.getAddress().toString().c_str());
  }
  void onDisconnect(NimBLEServer *s, NimBLEConnInfo &ci, int reason) override {
    connected = false;
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
      if (cfgValid(nc)) {
        cfg = nc;
        cfgSave();
        Serial.println("config: записан");
        return;
      }
    }
    bad++;
    Serial.printf("config: отброшен, %u байт\n", (unsigned)v.size());
    c->setValue((uint8_t *)&cfg, sizeof cfg);  // вернуть действующие
  }
};

// ---------- режим ----------
static void updateMode(uint32_t now) {
  int16_t spd, cur; float a, tilt; uint8_t fl; uint32_t lp;
  portENTER_CRITICAL(&mux);
  spd = abs(speed); cur = current; a = accelF; fl = wheelFlags; lp = lastPkt;
  tilt = pitch - tiltAvg;  // в WheelLog наклон назад (торможение) положительный
  bool sgn = speed < 0;
  portEXIT_CRITICAL(&mux);

  Mode m;
  if (!connected || lp == 0 || now - lp > TIMEOUT_MS || !(fl & 1)) {
    m = M_NOSIGNAL;  // старый стоп-сигнал не держим
  } else {
    // Рекуперация: ток против направления движения. Знак на Sherman L проверить, флаг инвертирует.
    int regen = sgn ? cur : -cur;
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
    m = brake ? M_BRAKE : acc ? M_ACCEL : M_NORM;
    // не выходить из стопа и разгона раньше HOLD_MS - кочка не моргает
    if ((mode == M_BRAKE || mode == M_ACCEL) && m != mode && m != M_BRAKE && now - modeSince < HOLD_MS) m = mode;
  }
  if (m != mode) {
    mode = m;
    modeSince = now;
    Serial.printf("%lu режим %d, a=%.1f v=%d\n", (unsigned long)now, m, a / 10, spd);
  }
  if (turn != T_OFF && (int32_t)(now - turnUntil) >= 0) turn = T_OFF;
}

// ---------- отрисовка ----------
static uint32_t col(uint8_t r, uint8_t g, uint8_t b, uint8_t br) {
  return Adafruit_NeoPixel::Color(r * br / 255, g * br / 255, b * br / 255);
}

static void fill(int a, int b, uint32_t c) {
  for (int i = a; i < b; i++) lenta.setPixelColor(i, c);
}

// Поворот: бегущее заполнение от подбородка назад, 500 мс заполнение, 300 мс пауза
static void drawTurn(bool left, uint32_t now) {
  uint32_t ph = (now - turnStart) % 800;
  if (ph >= 500) return;
  int a = left ? cfg.z_left : 0, b = left ? cfg.num_leds : cfg.z_back;
  int len = b - a, k = len * (ph + 50) / 500;
  uint32_t c = col(255, 140, 0, cfg.bright_brake);
  for (int j = 0; j < k && j < len; j++) lenta.setPixelColor(left ? b - 1 - j : a + j, c);
}

static void drawCalibrate() {
  for (int i = 0; i < cfg.num_leds; i++) {
    uint32_t c = 0;
    if (i % 10 == 0) c = col(255, 255, 255, 60);
    if (i % 50 == 0) c = col(0, 0, 255, 120);
    if (i == cfg.z_right || i == cfg.z_back || i == cfg.z_left || i == cfg.z_front_left) c = col(255, 0, 255, 120);
    if (i == 0) c = col(0, 255, 0, 120);
    lenta.setPixelColor(i, c);
  }
}

// Расчётный ток: 20 мА на канал на полной яркости плюс 1 мА покоя на точку; выше предела - притушить
static void limitCurrent() {
  uint32_t sum = 0;
  uint8_t *px = lenta.getPixels();
  for (int i = 0; i < cfg.num_leds * 3; i++) sum += px[i];
  uint32_t idle = cfg.num_leds, ma = sum * 20 / 255 + idle;
  if (ma > cfg.limit_ma && sum) {
    uint32_t k = (cfg.limit_ma - min(idle, (uint32_t)cfg.limit_ma)) * 255 * 256 / (sum * 20);
    for (int i = 0; i < cfg.num_leds * 3; i++) px[i] = px[i] * k >> 8;
    ma = cfg.limit_ma;
  }
  lastMa = ma;
}

static void draw(uint32_t now) {
  lenta.clear();
  uint8_t t = turn;
  if (cfg.flags & CFG_CALIBRATE) {
    drawCalibrate();
  } else {
    uint32_t tail = col(255, 0, 0, cfg.bright_tail);
    uint32_t stop = col(255, 0, 0, cfg.bright_brake);
    uint32_t sides = mode == M_BRAKE ? stop : tail;
    uint32_t back = mode == M_BRAKE ? stop
                  : mode == M_ACCEL ? col(cfg.accel_rgb[0], cfg.accel_rgb[1], cfg.accel_rgb[2], cfg.bright_brake)
                  : tail;
    fill(cfg.z_right, cfg.z_back, sides);
    fill(cfg.z_back, cfg.z_left, back);
    fill(cfg.z_left, cfg.z_front_left, sides);
    if (cfg.flags & CFG_FRONT_WHITE) {
      uint32_t w = col(255, 255, 255, cfg.bright_tail);
      fill(0, cfg.z_right, w);
      fill(cfg.z_front_left, cfg.num_leds, w);
    }
    // поворот рисуется поверх боков, дуга сзади остаётся стопом или габаритом
    if (t == T_RIGHT || t == T_BOTH) { fill(0, cfg.z_back, 0); drawTurn(false, now); }
    if (t == T_LEFT || t == T_BOTH) { fill(cfg.z_left, cfg.num_leds, 0); drawTurn(true, now); }
  }
  limitCurrent();
  lenta.show();

  // Светодиод на плате: то же, пока ленты нет
  uint8_t r = 0, g = 0, b = 0;
  bool blink = (now / 500) & 1;
  if (cfg.flags & CFG_CALIBRATE) { r = g = b = 20; }
  else if (t != T_OFF && ((now - turnStart) % 800) < 500) { r = 60; g = 25; }
  else if (mode == M_BRAKE) r = 80;
  else if (mode == M_ACCEL) { r = cfg.accel_rgb[0] / 3; g = cfg.accel_rgb[1] / 3; b = cfg.accel_rgb[2] / 3; }
  else if (mode == M_NORM) r = 6;
  else r = blink ? 6 : 0;  // нет связи - медленно мигает
  rgbLedWrite(PIN_PLATA, r, g, b);
}

// ---------- отладка ----------
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

// Команды по USB: l/r/b/o - поворот, c - разметка вкл/выкл, d - настройки по умолчанию, s - состояние
static void serialCmd() {
  while (Serial.available()) {
    char ch = Serial.read();
    if (ch == 'l') setTurn(T_LEFT);
    else if (ch == 'r') setTurn(T_RIGHT);
    else if (ch == 'b') setTurn(T_BOTH);
    else if (ch == 'o') setTurn(T_OFF);
    else if (ch == 'c') { cfg.flags ^= CFG_CALIBRATE; cfgSave(); chrConfig->setValue((uint8_t *)&cfg, sizeof cfg); }
    else if (ch == 'd') { cfg = CFG_DEFAULT; cfgSave(); chrConfig->setValue((uint8_t *)&cfg, sizeof cfg); }
    else if (ch == 's') Serial.printf("режим %d поворот %d a=%.1f v=%d пакетов %u отброшено %u ток %u мА\n",
                                      mode, turn, accelF / 10, speed, packets, bad, lastMa);
  }
}

void setup() {
  Serial.begin(115200);
  cfgLoad();
  lenta.begin();
  lenta.updateLength(cfg.num_leds);

  WiFi.mode(WIFI_STA);
  esp_wifi_set_channel(ESPNOW_CH, WIFI_SECOND_CHAN_NONE);
  if (esp_now_init() == ESP_OK) esp_now_register_recv_cb(onEspNow);

  NimBLEDevice::init(HELMET_NAME);
  NimBLEServer *srv = NimBLEDevice::createServer();
  srv->setCallbacks(new SrvCb());
  srv->advertiseOnDisconnect(true);
  NimBLEService *svc = srv->createService(UUID_SERVICE);
  svc->createCharacteristic(UUID_DATA, NIMBLE_PROPERTY::WRITE_NR | NIMBLE_PROPERTY::WRITE)->setCallbacks(new DataCb());
  chrConfig = svc->createCharacteristic(UUID_CONFIG, NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::WRITE);
  chrConfig->setCallbacks(new CfgCb());
  chrConfig->setValue((uint8_t *)&cfg, sizeof cfg);
  chrState = svc->createCharacteristic(UUID_STATE, NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::NOTIFY);
  svc->start();
  NimBLEAdvertising *adv = NimBLEDevice::getAdvertising();
  adv->setName(HELMET_NAME);
  adv->addServiceUUID(UUID_SERVICE);
  adv->enableScanResponse(true);
  adv->start();

  Serial.printf("CJ-Helmet: лента GPIO%d, %u точек; BLE %s; ESP-NOW канал %d, MAC %s\n",
                PIN_LENTA, cfg.num_leds, NimBLEDevice::getAddress().toString().c_str(),
                ESPNOW_CH, WiFi.macAddress().c_str());
}

void loop() {
  static uint32_t lastDraw = 0;
  uint32_t now = millis();
  serialCmd();
  if (now - lastDraw >= 20) {  // 50 кадров в секунду
    lastDraw = now;
    if (lenta.numPixels() != cfg.num_leds) lenta.updateLength(cfg.num_leds);
    updateMode(now);
    draw(now);
    sendState(now);
  }
  delay(2);
}
