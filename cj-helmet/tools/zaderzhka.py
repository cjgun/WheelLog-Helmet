"""Задержка уведомлений state: 10 пакетов по 0.2 с, печать времени каждого уведомления."""
import asyncio
import struct
import time

from bleak import BleakClient, BleakScanner

D = 'e7af526e-d864-4f2c-a552-34f2f8265c65'
S = 'ca1c4800-cea8-44c2-8e14-3be95eb3af36'
t0 = time.time()


def on(_, d):
    print(f'{time.time() - t0:6.2f} уведомление: режим {d[0]}, пакетов {struct.unpack_from("<H", d, 6)[0]}')


async def main():
    dev = await BleakScanner.find_device_by_name('CJ-Helmet', timeout=15)
    async with BleakClient(dev) as c:
        await c.start_notify(S, on)
        print(f'{time.time() - t0:6.2f} старт')
        for i in range(10):
            await c.write_gatt_char(D, struct.pack('<BBhhh', 1, 1, 200, 0, 0), response=False)
            await asyncio.sleep(0.2)
        print(f'{time.time() - t0:6.2f} конец записи')
        await asyncio.sleep(2)


asyncio.run(main())
