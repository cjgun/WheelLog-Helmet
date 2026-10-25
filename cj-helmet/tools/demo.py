"""Показ режимов на живом шлеме: BLE-данные с MiniGUN, повороты командами по USB.

Запуск на MiniGUN: /opt/helmet/venv/bin/python /opt/helmet/tools/demo.py [кругов]
"""
import asyncio
import struct
import sys
import time

import serial
from bleak import BleakClient, BleakScanner

U_DATA = 'e7af526e-d864-4f2c-a552-34f2f8265c65'
usb = serial.Serial('/dev/ttyACM0', 115200, timeout=0.2)


def paket(v):
    return struct.pack('<BBhhh', 1, 1, round(v * 10), 0, 0)


async def ezda(c, v0, v1, sek, hz=20):
    n = int(sek * hz)
    for i in range(n + 1):
        await c.write_gatt_char(U_DATA, paket(v0 + (v1 - v0) * i / n), response=False)
        await asyncio.sleep(1 / hz)


def shag(t):
    print(time.strftime('%H:%M:%S'), t, flush=True)


async def main(krugov):
    dev = await BleakScanner.find_device_by_name('CJ-Helmet', timeout=15)
    async with BleakClient(dev) as c:
        for k in range(krugov):
            shag(f'круг {k + 1}: норма (габарит)')
            await ezda(c, 25, 25, 4)
            shag('торможение')
            await ezda(c, 25, 5, 3)
            await ezda(c, 5, 5, 1)
            shag('норма')
            await ezda(c, 5, 5, 2)
            shag('разгон')
            await ezda(c, 5, 25, 3)
            await ezda(c, 25, 25, 2)
            shag('поворот вправо')
            usb.write(b'r')
            await ezda(c, 25, 25, 5)
            shag('поворот влево')
            usb.write(b'l')
            await ezda(c, 25, 25, 5)
            shag('аварийка (оба)')
            usb.write(b'b')
            await ezda(c, 25, 25, 5)
            shag('поворот вправо + торможение')
            usb.write(b'r')
            await ezda(c, 25, 5, 3)
            usb.write(b'o')
            await ezda(c, 5, 25, 1)
            await ezda(c, 25, 25, 2)
    shag('отключился: через 1 с нет связи (ровный габарит)')
    await asyncio.sleep(2)


asyncio.run(main(int(sys.argv[1]) if len(sys.argv) > 1 else 3))
