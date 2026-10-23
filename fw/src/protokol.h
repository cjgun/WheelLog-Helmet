// Протокол шлема CJ-Helmet: BLE-сервис, пакеты данных и настроек.
// Всё little-endian. Описание для приложения - в README.md.
#pragma once
#include <stdint.h>

#define HELMET_NAME        "CJ-Helmet"
#define UUID_SERVICE       "e5c7e8c9-d675-4a18-81ca-fe8e20469649"
#define UUID_DATA          "e7af526e-d864-4f2c-a552-34f2f8265c65"  // write without response
#define UUID_CONFIG        "0754273b-03f6-4547-ac98-f6652dd7cb00"  // read/write
#define UUID_STATE         "ca1c4800-cea8-44c2-8e14-3be95eb3af36"  // notify/read

// Пакет данных v1 от телефона, 8 байт
struct __attribute__((packed)) DataV1 {
  uint8_t ver;        // = 1
  uint8_t flags;      // бит 0 - колесо на связи
  int16_t speed;      // 0.1 км/ч
  int16_t current;    // 0.1 А, фазный ток
  int16_t pitch;      // 0.01 градуса
};
static_assert(sizeof(DataV1) == 8, "DataV1");

// Настройки, хранятся в NVS. Запись в characteristic config - вся структура целиком.
#define CONFIG_VER 3
struct __attribute__((packed)) Config {
  uint8_t ver;            // = CONFIG_VER
  uint8_t bright_tail;    // габарит, 0-255
  uint8_t bright_brake;   // стоп и поворот, 0-255
  uint8_t flags;          // бит 0 - ток с обратным знаком, бит 1 - белый спереди, бит 2 - режим разметки
  uint16_t smooth_ms;     // постоянная сглаживания ускорения
  uint16_t brake_decel;   // торможение: замедление, 0.1 км/ч в секунду
  uint16_t brake_current; // торможение: ток рекуперации, 0.1 А (0 - не учитывать)
  uint16_t accel;         // разгон: ускорение, 0.1 км/ч в секунду (0 - режим выключен)
  uint16_t min_speed;     // ниже этой скорости разгон и торможение по току не считаются, 0.1 км/ч
  uint16_t num_leds;      // точек в ленте
  // Границы зон по номерам точек; точка 0 - справа спереди у подбородка.
  // [0, z_right) - перед справа, [z_right, z_back) - правый бок,
  // [z_back, z_left) - дуга сзади, [z_left, z_front_left) - левый бок, дальше - перед слева
  uint16_t z_right, z_back, z_left, z_front_left;
  uint8_t accel_rgb[3];   // цвет разгона
  uint8_t turn_sec;       // поворот гаснет сам через, с
  uint16_t limit_ma;      // предел тока ленты, мА
  // v2: торможение по наклону. Ездок откидывается назад раньше, чем падает скорость
  // (логи WheelLog 20 и 22.10: наклон опережает замедление на ~0.4 с).
  uint16_t brake_tilt;    // наклон назад сверх среднего, 0.01 градуса (0 - выкл)
  uint8_t tilt_avg_s;     // среднее наклона за, с (убирает уклон дороги)
  // v3
  uint8_t strip_type;     // порядок цветов: 0 GRB (WS2812), 1 RGB, 2 BRG, 3 GRBW (SK6812 RGBW), 4 RGBW
};

static_assert(sizeof(Config) == 34, "Config");
// Размер config прошлых версий: новые поля только дописываются в конец,
// старые настройки переносятся при обновлении прошивки
static const uint8_t CONFIG_SIZE[] = {0, 30, 33, 34};

#define CFG_CURRENT_INVERT 0x01
#define CFG_FRONT_WHITE    0x02
#define CFG_CALIBRATE      0x04

// Состояние для отладки, 12 байт
enum Mode : uint8_t { M_NOSIGNAL = 0, M_NORM = 1, M_BRAKE = 2, M_ACCEL = 3 };
enum Turn : uint8_t { T_OFF = 0, T_LEFT = 1, T_RIGHT = 2, T_BOTH = 3 };
struct __attribute__((packed)) State {
  uint8_t mode;       // Mode
  uint8_t turn;       // Turn
  int16_t accel;      // сглаженное ускорение, 0.1 км/ч в секунду
  int16_t speed;      // последняя скорость, 0.1 км/ч
  uint16_t packets;   // принято пакетов данных
  uint16_t bad;       // отброшено пакетов (длина, версия)
  uint16_t ma;        // расчётный ток ленты, мА
};

// ESP-NOW от кнопок поворотника: 'C','J', ver=1, команда (Turn), 4 байта
struct __attribute__((packed)) TurnCmd {
  uint8_t m0, m1, ver, cmd;
};
