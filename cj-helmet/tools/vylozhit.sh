#!/bin/sh
# Выложить собранную прошивку на cjgun.ru: платы забирают её по команде «upd» из приложения.
# Запуск на NL после сборки на MiniGUN: tools/vylozhit.sh [nano-c6] [c3-mini]
# Кладёт <плата>.bin и <плата>.json {"md5", "url", "ver"} в /var/www/cjgun/assets/proshivka/.
set -e
DST=/var/www/cjgun/assets/proshivka
SSH="ssh -p 2201 -i /root/.ssh/id_ed25519_debian13_login root@127.0.0.1"
mkdir -p "$DST"
boards="$*"; [ -n "$boards" ] || boards="nano-c6 c3-mini"
for e in $boards; do
  {
    $SSH "cat /opt/helmet/fw/.pio/build/$e/firmware.bin" > "$DST/$e.bin.new"
    md5=$(md5sum "$DST/$e.bin.new" | cut -d' ' -f1)
    ver=$(strings "$DST/$e.bin.new" | grep -m1 -oE '[A-Z][a-z]{2} [ 0-9]{2} 20[0-9]{2} [0-9:]{8}' || true)
    mv "$DST/$e.bin.new" "$DST/$e.bin"
    printf '{"md5": "%s", "url": "https://cjgun.ru/assets/proshivka/%s.bin?%s", "ver": "%s"}\n' "$md5" "$e" "$md5" "$ver" > "$DST/$e.json"
    echo "$e: $md5 $ver"
  }
done
chown -R cjgun:www-data "$DST"
