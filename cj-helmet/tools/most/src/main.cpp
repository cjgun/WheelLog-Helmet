// Мост USB <-> UART: всё, что пришло по USB, уходит в модуль, и обратно.
// Строка "#BAUD 9600" по USB меняет скорость UART (сама в модуль не уходит).
#include <Arduino.h>
static uint32_t baud = 115200;
#ifndef MOST_RX
#define MOST_RX 20
#define MOST_TX 19
#endif
static String line;
void setup() {
  Serial.begin(115200);
#ifndef MOST_RX
#define MOST_RX 20
#define MOST_TX 19
#endif
  Serial1.begin(baud, SERIAL_8N1, MOST_RX, MOST_TX);
}
void loop() {
  while (Serial.available()) {
    char c = Serial.read();
    line += c;
    if (line.startsWith("#")) {
      if (c == '\n') {
        if (line.startsWith("#LVL")) {  // уровни на выводах: свободная линия UART в покое = 1
          Serial1.end(); pinMode(19, INPUT); pinMode(20, INPUT); delay(5);
          int h19 = 0, h20 = 0; for (int i = 0; i < 200; i++) { h19 += digitalRead(19); h20 += digitalRead(20); delayMicroseconds(500); }
          Serial.printf("[уровни: вывод19 высокий %d/200, вывод20 высокий %d/200]\n", h19, h20);
          Serial1.begin(baud, SERIAL_8N1, 19, 20);
        }
        if (line.startsWith("#SWAP")) { static bool sw = false; sw = !sw; Serial1.end();
          Serial1.begin(baud, SERIAL_8N1, sw ? MOST_TX : MOST_RX, sw ? MOST_RX : MOST_TX); Serial.printf("[мост: выводы %s]\n", sw ? "наоборот" : "как были"); }
        if (line.startsWith("#BAUD ")) { baud = line.substring(6).toInt(); Serial1.updateBaudRate(baud); Serial.printf("[мост: %lu бод]\n", (unsigned long)baud); }
        line = "";
      }
      continue;
    }
    Serial1.write(c);
    line = "";
  }
  while (Serial1.available()) Serial.write(Serial1.read());
}
