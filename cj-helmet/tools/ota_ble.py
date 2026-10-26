"""Прошивка платы CJ по Bluetooth с MiniGUN - тот же протокол, что в приложении (EspLink.otaFromSite).
Запуск: /opt/helmet/venv/bin/python tools/ota_ble.py <адрес BLE> <firmware.bin>
Куски [смещение u32][данные] без подтверждения, каждые 32 куска - чтение принятого, продолжение с него."""
import asyncio
import hashlib
import struct
import sys
import time

from bleak import BleakClient

NET = "6d3e1f40-2b7a-4c55-9e10-3a8f5c2d7b61"
OTA = "8a1f3c52-6e4b-4d7a-b9c3-2f5e7d1a0c64"


def field(s, k):
    for line in s.splitlines():
        if line.startswith(k + "="):
            return line[len(k) + 1:]
    return ""


async def main(addr, path):
    bin_ = open(path, "rb").read()
    md5 = hashlib.md5(bin_).hexdigest()
    async with BleakClient(addr, timeout=20) as c:
        try:
            await c._backend._acquire_mtu()  # BlueZ: без этого MTU 23 и куски по 16 байт
        except Exception:
            pass
        net = (await c.read_gatt_char(NET)).decode()
        print("было:", field(net, "ver"), field(net, "board"), "MTU", c.mtu_size)
        await c.write_gatt_char(NET, f"ota {len(bin_)} {md5}".encode(), response=True)
        await asyncio.sleep(0.3)
        chunk = min(240, c.mtu_size - 3 - 4)
        off, t0, stuck = 0, time.time(), 0
        while off < len(bin_):
            o = off
            for _ in range(32):
                if o >= len(bin_):
                    break
                n = min(chunk, len(bin_) - o)
                await c.write_gatt_char(OTA, struct.pack("<I", o) + bin_[o:o + n], response=False)
                o += n
            got = struct.unpack("<I", bytes(await c.read_gatt_char(OTA))[:4])[0]
            stuck = stuck + 1 if got == off else 0
            if stuck > 5:
                print("плата не принимает:", field((await c.read_gatt_char(NET)).decode(), "upd"))
                return
            off = got
            if off // 65536 != (off - chunk * 32) // 65536 or off == len(bin_):
                print(f"отправлено {off * 100 // len(bin_)}%, {time.time() - t0:.0f} с")
        for _ in range(20):
            await asyncio.sleep(0.5)
            try:
                u = field((await c.read_gatt_char(NET)).decode(), "upd")
            except Exception:
                u = "связь закрыта (перезапуск)"
            if not u.startswith("приём"):
                print("плата:", u, f"за {time.time() - t0:.0f} с")
                return


asyncio.run(main(sys.argv[1], sys.argv[2]))
