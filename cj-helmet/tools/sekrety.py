"""Создать fw/src/sekrety.h на MiniGUN: Wi-Fi из секретов ESPHome, пароль обновления из
/opt/helmet/sekrety.txt (создаётся при первом запуске). Значения не печатает."""
import os
import re
import secrets

y = open('/opt/esphome/config/secrets.yaml').read()


def val(k):
    v = re.search(r'^' + k + r':\s*(.+?)\s*$', y, re.M).group(1)
    return v[1:-1] if v[:1] in '"\'' else v


os.umask(0o077)
p = '/opt/helmet/sekrety.txt'
ota = None
if os.path.exists(p):
    for line in open(p):
        if line.startswith('ota='):
            ota = line[4:].strip()
if not ota:  # создаём пароль один раз, остальные строки (apk= и др.) не трогаем
    ota = secrets.token_hex(12)
    open(p, 'a').write('ota=' + ota + '\n')
c = lambda s: s.replace('\\', '\\\\').replace('"', '\\"')
open('/opt/helmet/fw/src/sekrety.h', 'w').write(
    '// Создаётся на MiniGUN из /opt/esphome/config/secrets.yaml и /opt/helmet/sekrety.txt, в git не кладётся\n'
    '#pragma once\n#define WIFI_SSID "%s"\n#define WIFI_PASS "%s"\n#define OTA_PASS "%s"\n'
    % (c(val('wifi_ssid')), c(val('wifi_password')), ota))
print('sekrety.h готов')
