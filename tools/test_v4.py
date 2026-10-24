"""Проверка прошивки v4 по BLE с MiniGUN: config 74 байта, команда проверки, кадры, пакет v2 с PWM."""
import asyncio, struct, time
from bleak import BleakClient, BleakScanner

SVC = "e5c7e8c9-d675-4a18-81ca-fe8e20469649"
DATA = "e7af526e-d864-4f2c-a552-34f2f8265c65"
CONFIG = "0754273b-03f6-4547-ac98-f6652dd7cb00"
STATE = "ca1c4800-cea8-44c2-8e14-3be95eb3af36"
CMD = "4f1d2a6e-8c3b-4e57-9a10-6b2d7c9e0f31"
FRAME = "9b7e3c21-5d4a-4f68-b2e1-0c8a6f4d2e95"
MODES = {0: "нет связи", 1: "норма", 2: "стоп", 3: "разгон", 4: "предупреждение", 5: "стоянка"}
ok = []

def check(name, cond, extra=""):
    ok.append(cond); print(("OK  " if cond else "НЕТ ") + name, extra)

async def main():
    dev = await BleakScanner.find_device_by_filter(lambda d, a: SVC in [u.lower() for u in a.service_uuids], timeout=15)
    if not dev: print("плата не найдена"); return
    async with BleakClient(dev) as c:
        print("плата", dev.name, dev.address, "MTU", c.mtu_size)
        cfg = await c.read_gatt_char(CONFIG)
        check("config 74 байта, версия 4", len(cfg) == 74 and cfg[0] == 4, f"({len(cfg)} байт, v{cfg[0]})")
        nseg = cfg[35]
        segs = [struct.unpack_from("<HBB", cfg, 36 + 4 * i) for i in range(nseg)]
        print("   сегменты:", segs, "точек", struct.unpack_from("<H", cfg, 14)[0])
        states = []
        await c.start_notify(STATE, lambda h, d: states.append(d[0]))
        frames = []
        await c.start_notify(FRAME, lambda h, d: frames.append(bytes(d)))
        await asyncio.sleep(1.2)
        check("кадры ленты приходят", len(frames) > 0, f"({len(frames)} кусков, первый {len(frames[0]) if frames else 0} байт)")
        if frames:
            n = sum(f[2] for f in frames[-3:]) if len(frames) >= 3 else frames[0][2]
            red = sum(1 for f in frames[-3:] for j in range(f[2]) if f[3 + 3 * j] > 0)
            print("   точек в последних кусках", n, "с красным", red)
        # проверка: стоп 5 с
        frames.clear()
        await c.write_gatt_char(CMD, bytes([1, 3]), response=True)
        await asyncio.sleep(0.6)
        acc = [f for f in frames if f[0] == 0 and f[1] == 0]
        back0 = sum(c[0] for c in segs[:[s[1] for s in segs].index(2)]) if 2 in [s[1] for s in segs] else 0
        px = tuple(acc[-1][3 + 3 * back0: 6 + 3 * back0]) if acc else ()
        check("проверка «разгон»: зад цветом разгона", bool(px) and px[1] > 0, f"(точка {back0}: {px}, цвет разгона {tuple(cfg[24:27])})")
        await c.write_gatt_char(CMD, bytes([1, 0]), response=True)
        # пакеты v2: едем 20 км/ч, PWM 90 -> предупреждение
        for i in range(12):
            await c.write_gatt_char(DATA, struct.pack("<BBhhhBBB", 2, 1, 200, 0, 0, 90, 70, 0), response=False)
            await asyncio.sleep(0.2)
        st = await c.read_gatt_char(STATE)
        check("пакет v2 с PWM 90%: режим «предупреждение»", st[0] == 4, f"(режим {MODES.get(st[0], st[0])})")
        # стоим 6 с -> стоянка
        states.clear()
        for i in range(36):
            await c.write_gatt_char(DATA, struct.pack("<BBhhhBBB", 2, 1, 0, 0, 0, 0, 70, 0), response=False)
            await asyncio.sleep(0.2)
        check("стоим 6 с: режим «стоянка»", 5 in states, f"(режимы по уведомлениям {[MODES.get(x, x) for x in states]})")
        # старый пакет v1 ещё понимается
        for i in range(5):
            await c.write_gatt_char(DATA, struct.pack("<BBhhh", 1, 1, 150, 0, 0), response=False)
            await asyncio.sleep(0.2)
        await asyncio.sleep(1.1)
        st = await c.read_gatt_char(STATE)
        check("пакет v1 принимается", st[0] in (1, 3), f"(режим {MODES.get(st[0], st[0])})")
        # неверный config отклоняется, действующий возвращается
        badcfg = bytearray(cfg); badcfg[35] = 0
        await c.write_gatt_char(CONFIG, bytes(badcfg), response=True)
        back = await c.read_gatt_char(CONFIG)
        check("неверный config (0 сегментов) отклонён", back == cfg)
        # подсветка сегмента
        await c.write_gatt_char(CMD, bytes([2, 1]), response=True)
        frames.clear(); await asyncio.sleep(1.5)
        mag = sum(1 for f in frames for j in range(f[2]) if f[3 + 3 * j] > 10 and f[5 + 3 * j] > 10 and f[4 + 3 * j] * 2 < min(f[3 + 3 * j], f[5 + 3 * j]))
        sample = [tuple(f[33:36]) for f in frames if f[0] == 0 and f[1] == 0]
        check("подсветка сегмента 2 сиреневым", mag > 0, f"({mag} сиреневых точек в кадрах; точка 10 по кадрам {sample})")
        await c.write_gatt_char(CMD, bytes([2, 255]), response=True)
    print("ИТОГ:", sum(ok), "из", len(ok))

asyncio.run(main())
