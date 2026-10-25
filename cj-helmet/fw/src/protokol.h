// Протокол CJ-Helmet (шлем и колесо): BLE-сервис, пакеты данных, настройки, команды.
// Всё little-endian. Описание для приложения - в README.md.
#pragma once
#include <stdint.h>

#define NAME_HELMET        "CJ-Helmet"
#define NAME_WHEEL         "CJ-Wheel"
#define UUID_SERVICE       "e5c7e8c9-d675-4a18-81ca-fe8e20469649"
#define UUID_DATA          "e7af526e-d864-4f2c-a552-34f2f8265c65"  // write without response
#define UUID_CONFIG        "0754273b-03f6-4547-ac98-f6652dd7cb00"  // read/write
#define UUID_STATE         "ca1c4800-cea8-44c2-8e14-3be95eb3af36"  // notify/read
#define UUID_CMD           "4f1d2a6e-8c3b-4e57-9a10-6b2d7c9e0f31"  // write: проверка, подсветка сегмента
#define UUID_FRAME         "9b7e3c21-5d4a-4f68-b2e1-0c8a6f4d2e95"  // notify: кадр ленты кусками
#define UUID_NET           "6d3e1f40-2b7a-4c55-9e10-3a8f5c2d7b61"  // read/write: имя, Wi-Fi, обновление (текст)

// Сеть и обновление (characteristic net), текст UTF-8.
// Запись, одна команда за раз:
//   name=<имя>            имя в Bluetooth, до 20 байт; пусто - CJ-Helmet-XXXX / CJ-Wheel-XXXX по MAC; после перезапуска
//   wifi=<сеть>\t<пароль>  запомнить сеть (пароль не читается обратно)
//   wifi on | wifi off    включить или выключить Wi-Fi (после перезапуска выключен)
//   upd                   включить Wi-Fi, скачать прошивку с cjgun.ru, проверить MD5, перезапуститься
//   reboot                перезапуск
// Чтение - строки key=value: name, ssid, wifi (off / подключение / адрес), upd (состояние), ver, board.
#define NAME_MAX 20

// Пакет данных v1 от телефона, 8 байт
struct __attribute__((packed)) DataV1 {
  uint8_t ver;        // = 1
  uint8_t flags;      // бит 0 - колесо на связи
  int16_t speed;      // 0.1 км/ч
  int16_t current;    // 0.1 А, фазный ток
  int16_t pitch;      // 0.01 градуса
};
static_assert(sizeof(DataV1) == 8, "DataV1");

// Пакет данных v2: v1 + PWM, заряд, тревоги колеса, 11 байт
struct __attribute__((packed)) DataV2 {
  uint8_t ver;        // = 2
  uint8_t flags;      // бит 0 - колесо на связи
  int16_t speed;
  int16_t current;
  int16_t pitch;
  uint8_t pwm;        // %, 0-100
  uint8_t battery;    // %, 0-100
  uint8_t alarms;     // бит 0 - тревога колеса (скорость, ток, температура)
};
static_assert(sizeof(DataV2) == 11, "DataV2");

// Роли сегментов. Правые и левые различаются для поворотов.
enum Role : uint8_t {
  R_FRONT_R = 0, R_SIDE_R = 1, R_BACK = 2, R_SIDE_L = 3, R_FRONT_L = 4,  // шлем по кругу
  R_STOP = 5,                                                            // стоп-полоса: стоп от центра
  R_PEAK_R = 6, R_PEAK_L = 7,                                            // козырёк: белый, жёлтое мигание
  R_DRL_R = 8, R_DRL_L = 9,                                              // колесо спереди: ходовой огонь
  R_TAIL_R = 10, R_TAIL_L = 11,                                          // колесо сзади: габарит
  R_PEDAL_R = 12, R_PEDAL_L = 13,                                        // педали: цвет сцены, заряд
  R_OFF = 14,                                                            // не светит
  R_COUNT
};
#define SEG_REV 0x01      // бег поворота в обратную сторону
#define MAX_SEG 8
struct __attribute__((packed)) Seg {
  uint16_t count;     // точек
  uint8_t role;       // Role
  uint8_t flags;      // SEG_REV
};

// Настройки, хранятся в NVS. Запись в characteristic config - вся структура целиком.
#define CONFIG_VER 4
struct __attribute__((packed)) Config {
  uint8_t ver;            // = CONFIG_VER
  uint8_t bright_tail;    // габарит, 0-255
  uint8_t bright_brake;   // стоп и поворот, 0-255
  uint8_t flags;          // CFG_*
  uint16_t smooth_ms;     // постоянная сглаживания ускорения
  uint16_t brake_decel;   // торможение: замедление, 0.1 км/ч в секунду
  uint16_t brake_current; // торможение: ток рекуперации, 0.1 А (0 - не учитывать)
  uint16_t accel;         // разгон: ускорение, 0.1 км/ч в секунду (0 - режим выключен)
  uint16_t min_speed;     // ниже этой скорости разгон и торможение по току не считаются, 0.1 км/ч
  uint16_t num_leds;      // точек в ленте (при сегментах - их сумма)
  // v1-v3: границы четырёх зон. С v4 действуют сегменты; зоны только для переноса старых настроек.
  uint16_t z_right, z_back, z_left, z_front_left;
  uint8_t accel_rgb[3];   // цвет разгона
  uint8_t turn_sec;       // поворот гаснет сам через, с
  uint16_t limit_ma;      // предел тока ленты, мА
  // v2: торможение по наклону
  uint16_t brake_tilt;    // наклон назад сверх среднего, 0.01 градуса (0 - выкл)
  uint8_t tilt_avg_s;     // среднее наклона за, с
  // v3
  uint8_t strip_type;     // порядок цветов: 0 GRB, 1 RGB, 2 BRG, 3 GRBW, 4 RGBW
  // v4: сегменты, колесо, предупреждения
  uint8_t kind;           // 0 шлем, 1 колесо (имя в эфире, поведение по умолчанию)
  uint8_t nseg;           // сегментов, 1..MAX_SEG
  Seg seg[MAX_SEG];
  uint8_t pedal_rgb[3];   // цвет педалей
  uint8_t warn_pwm;       // предупреждение от PWM, %, 0 - выкл
  uint8_t rim;            // обод (колесо): RIM_*
  uint8_t relay;          // ESP-NOW: RELAY_*
};

static_assert(sizeof(Config) == 74, "Config");
// Размер config прошлых версий: новые поля только дописываются в конец
static const uint8_t CONFIG_SIZE[] = {0, 30, 33, 34, 74};

#define CFG_CURRENT_INVERT 0x01
#define CFG_FRONT_WHITE    0x02
#define CFG_CALIBRATE      0x04
#define CFG_TAIL_BRAKE     0x08   // габариты колеса вспыхивают вместе со стопом

#define RIM_FLASH_WARN     0x01   // обод мигает при предупреждении
#define RIM_FLASH_BRAKE    0x02   // обод мигает при торможении
#define RIM_OFF_PARK       0x04   // обод гаснет на стоянке дольше 30 с

#define RELAY_SEND         0x01   // шлем: пересылать режим и данные колесу
#define RELAY_RECV         0x02   // колесо: брать режим у шлема, если телефон не пишет сам

// Состояние, 12 байт
enum Mode : uint8_t { M_NOSIGNAL = 0, M_NORM = 1, M_BRAKE = 2, M_ACCEL = 3, M_WARN = 4, M_PARK = 5 };
enum Turn : uint8_t { T_OFF = 0, T_LEFT = 1, T_RIGHT = 2, T_BOTH = 3 };
struct __attribute__((packed)) State {
  uint8_t mode;       // Mode
  uint8_t turn;       // Turn
  int16_t accel;      // сглаженное ускорение, 0.1 км/ч в секунду
  int16_t speed;      // последняя скорость, 0.1 км/ч
  uint16_t packets;   // принято пакетов данных
  uint16_t bad;       // отброшено пакетов
  uint16_t ma;        // расчётный ток ленты, мА
};

// Команды (characteristic cmd)
enum Cmd : uint8_t {
  C_TEST = 1,         // [1, что]: 0 стоп проверки, 1 габарит, 2 стоп, 3 разгон, 4 поворот влево,
                      //           5 вправо, 6 аварийка, 7 предупреждение, 8 стоянка, 9 разметка; 5 с
  C_HILITE = 2,       // [2, номер сегмента]: мигать сегментом 10 с, 0xFF - выкл
  C_TURN = 3,         // [3, Turn]: поворот, как кнопка на руле
};

// Кадр ленты для экрана: [смещение lo, hi, сколько] + RGB по 3 байта, до FRAME_CHUNK точек в куске
#define FRAME_CHUNK 60

// ESP-NOW от кнопок поворотника: 'C','J', ver=1, команда (Turn), 4 байта
struct __attribute__((packed)) TurnCmd {
  uint8_t m0, m1, ver, cmd;
};
// ESP-NOW шлем -> колесо: 'C','J', ver=2, режим, поворот, данные v2 (11 байт), 16 байт
struct __attribute__((packed)) Relay {
  uint8_t m0, m1, ver, mode, turn;
  DataV2 d;
};
static_assert(sizeof(Relay) == 16, "Relay");
