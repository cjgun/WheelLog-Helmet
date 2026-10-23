"""Проверка шлема CJ-Helmet по BLE с MiniGUN (bleak): режимы, нет связи, неверные пакеты.

Запуск на MiniGUN: /opt/helmet/venv/bin/python /opt/helmet/tools/test_ble.py
"""
import asyncio
import struct
import sys
import time

from bleak import BleakClient, BleakScanner

NAME = 'CJ-Helmet'
U_DATA = 'e7af526e-d864-4f2c-a552-34f2f8265c65'
U_CONFIG = '0754273b-03f6-4547-ac98-f6652dd7cb00'
U_STATE = 'ca1c4800-cea8-44c2-8e14-3be95eb3af36'
REZHIM = {0: 'нет связи', 1: 'норма', 2: 'торможение', 3: 'разгон'}

state = {}
oshibki = []


def on_state(_, d):
    m, t, a, v, pk, bad, ma = struct.unpack('<BBhhHHH', d)
    state.update(mode=m, turn=t, a=a, v=v, packets=pk, bad=bad, ma=ma, t=time.time())


def paket(v_kmh, cur_a=0.0, flags=1, ver=1):
    return struct.pack('<BBhhh', ver, flags, round(v_kmh * 10), round(cur_a * 10), 0)


async def ezda(c, v0, v1, sek, hz=20):
    """Плавно менять скорость от v0 до v1 за sek секунд, hz пакетов в секунду."""
    n = int(sek * hz)
    for i in range(n + 1):
        await c.write_gatt_char(U_DATA, paket(v0 + (v1 - v0) * i / n), response=False)
        await asyncio.sleep(1 / hz)


def proverka(imya, ok, podrobno=''):
    print(f"{'OK  ' if ok else 'FAIL'} {imya} {podrobno}")
    if not ok:
        oshibki.append(imya)


async def main():
    dev = await BleakScanner.find_device_by_name(NAME, timeout=15)
    if not dev:
        print('шлем не найден')
        sys.exit(2)
    print('найден', dev.address)
    async with BleakClient(dev) as c:
        await c.start_notify(U_STATE, on_state)
        cfg = await c.read_gatt_char(U_CONFIG)
        print('config', len(cfg), 'байт:', cfg.hex())

        await ezda(c, 20, 20, 1.5)
        proverka('ровно 20 км/ч -> норма', state.get('mode') == 1, REZHIM.get(state.get('mode')))

        await ezda(c, 20, 10, 1.0)  # 10 км/ч в секунду
        proverka('замедление 10 км/ч/с -> торможение', state.get('mode') == 2, f"{REZHIM.get(state.get('mode'))} a={state.get('a')}")

        await ezda(c, 10, 10, 1.5)
        proverka('снова ровно -> норма', state.get('mode') == 1, REZHIM.get(state.get('mode')))

        await ezda(c, 10, 20, 1.0)
        proverka('ускорение 10 км/ч/с -> разгон', state.get('mode') == 3, f"{REZHIM.get(state.get('mode'))} a={state.get('a')}")

        await ezda(c, 20, 20, 1.5)
        # одиночная кочка: скачок скорости на один пакет не должен давать стоп
        await c.write_gatt_char(U_DATA, paket(17), response=False)
        await asyncio.sleep(0.05)
        await ezda(c, 20, 20, 0.5)
        proverka('кочка (-3 км/ч на один пакет) -> не стоп', state.get('mode') == 1, REZHIM.get(state.get('mode')))

        # колесо отвалилось у телефона (flags=0)
        for _ in range(5):
            await c.write_gatt_char(U_DATA, paket(20, flags=0), response=False)
            await asyncio.sleep(0.05)
        await asyncio.sleep(0.3)
        proverka('flags=0 -> нет связи', state.get('mode') == 0, REZHIM.get(state.get('mode')))

        # торможение, потом пакеты пропали: стоп не держится
        await ezda(c, 20, 20, 1.0)
        await ezda(c, 20, 10, 1.0)
        b0 = state.get('mode')
        t0 = time.time()
        while state.get('mode') != 0 and time.time() - t0 < 3:
            await asyncio.sleep(0.05)
        proverka('стоп и тишина -> нет связи за ~1 с', b0 == 2 and state.get('mode') == 0,
                 f'было {REZHIM.get(b0)}, через {time.time() - t0:.2f} с {REZHIM.get(state.get("mode"))}')

        # неверные пакеты
        bad0 = state.get('bad', 0)
        await ezda(c, 20, 20, 1.0)
        for d in (paket(20, ver=2), b'\x01\x01\x10', paket(20) + b'\x00', b''):
            try:
                await c.write_gatt_char(U_DATA, d, response=False)
            except Exception as e:
                print('  запись', d.hex(), 'не ушла:', e)
            await asyncio.sleep(0.05)
        await ezda(c, 20, 20, 1.2)
        proverka('неверные пакеты отброшены, шлем жив', state.get('bad', 0) >= bad0 + 3 and state.get('mode') == 1,
                 f"bad {bad0} -> {state.get('bad')}, режим {REZHIM.get(state.get('mode'))}")

        # неверный config не применяется
        plokhoj = bytearray(cfg)
        plokhoj[0] = 99
        try:
            await c.write_gatt_char(U_CONFIG, bytes(plokhoj), response=True)
        except Exception as e:
            print('  запись config:', e)
        cfg2 = await c.read_gatt_char(U_CONFIG)
        proverka('неверный config не применён', cfg2 == cfg)

        # верный config: порог торможения 20 км/ч/с - замедление 10 км/ч/с уже не стоп
        nov = bytearray(cfg)
        struct.pack_into('<H', nov, 6, 200)
        await c.write_gatt_char(U_CONFIG, bytes(nov), response=True)
        cfg3 = await c.read_gatt_char(U_CONFIG)
        await ezda(c, 20, 20, 1.5)
        await ezda(c, 20, 10, 1.0)
        proverka('порог из config работает', cfg3 == nov and state.get('mode') == 1, REZHIM.get(state.get('mode')))
        await c.write_gatt_char(U_CONFIG, bytes(cfg), response=True)

        await ezda(c, 20, 20, 0.5)
    t0 = time.time()
    print('отключился, ждём')
    await asyncio.sleep(1.5)
    print('итог:', 'всё OK' if not oshibki else 'ошибки: ' + ', '.join(oshibki))
    sys.exit(1 if oshibki else 0)


asyncio.run(main())
