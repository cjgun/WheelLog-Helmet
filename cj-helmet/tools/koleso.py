"""Чтение Sherman L по BLE с MiniGUN: сервисы и пакеты FFE1. Колесу ничего не пишет,
только включает уведомления (CCCD). Разбор по VeteranAdapter.java из WheelLog.

Запуск: /opt/helmet/venv/bin/python /opt/helmet/tools/koleso.py [секунд]
"""
import asyncio
import struct
import sys
import time

from bleak import BleakClient, BleakScanner

FFE1 = '0000ffe1-0000-1000-8000-00805f9b34fb'
buf = bytearray()
kadry = []


def razobrat(p):
    u16 = lambda o: struct.unpack_from('>H', p, o)[0]
    s16 = lambda o: struct.unpack_from('>h', p, o)[0]
    d = {'len': p[3]}
    if len(p) >= 34:
        d.update(volt=u16(4) / 100, speed=s16(6) / 10,
                 trip=((u16(10) << 16) | u16(8)) / 1000, cur=s16(16) / 10,
                 temp=s16(18) / 100, ver=u16(28), pitch=s16(32) / 100)
    return d


def on_data(_, data):
    global buf
    buf += data
    while True:
        i = buf.find(b'\xdc\x5a\x5c')
        if i < 0:
            buf = buf[-2:]
            return
        buf = buf[i:]
        if len(buf) < 4 or len(buf) < buf[3] + 4:
            return
        n = buf[3] + 4
        p, buf = bytes(buf[:n]), buf[n:]
        kadry.append((time.time(), p))


async def main(sek):
    dev = await BleakScanner.find_device_by_name('LK13378', timeout=20)
    if not dev:
        print('колесо не найдено (занято телефоном или выключено)')
        sys.exit(2)
    async with BleakClient(dev) as c:
        for s in c.services:
            print('сервис', s.uuid)
            for ch in s.characteristics:
                print('   ', ch.uuid, ','.join(ch.properties))
        await c.start_notify(FFE1, on_data)
        await asyncio.sleep(sek)
    if not kadry:
        print('пакетов DC 5A 5C нет')
        return
    dt = kadry[-1][0] - kadry[0][0]
    print(f'кадров {len(kadry)} за {dt:.1f} с, длины {sorted(set(len(p) for _, p in kadry))}')
    for t, p in kadry[:2] + kadry[-2:]:
        print(p.hex(' '))
    for t, p in kadry[::max(1, len(kadry) // 15)]:
        print(time.strftime('%H:%M:%S', time.localtime(t)), razobrat(p))


asyncio.run(main(float(sys.argv[1]) if len(sys.argv) > 1 else 10))
