#!/bin/sh
# Прошить шлем по Wi-Fi с MiniGUN. Аргумент - адрес шлема; без него ищется по mDNS через Avahi
# (системный резолвер MiniGUN отдаёт на cj-helmet.local 127.0.0.200).
# Шлем ждёт обновление, когда телефон не подключён дольше 30 с и домашняя сеть видна.
# Пароль из /opt/helmet/sekrety.txt; строку Options (в ней пароль) espota из вывода убираем.
export PLATFORMIO_CORE_DIR=/opt/helmet/.platformio
IP=${1:-$(avahi-resolve -4 -n cj-helmet.local 2>/dev/null | cut -f2)}
[ -n "$IP" ] || { echo "шлем не найден по mDNS, укажите адрес"; exit 2; }
P=$(sed -n 's/^ota=//p' /opt/helmet/sekrety.txt)
export PLATFORMIO_UPLOAD_FLAGS="--auth=$P"
cd /opt/helmet/fw && /opt/helmet/venv/bin/pio run -e nano-c6-ota -t upload --upload-port "$IP" 2>&1 | grep -v "Options: Namespace"
