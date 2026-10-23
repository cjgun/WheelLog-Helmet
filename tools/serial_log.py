"""USB-лог шлема в файл с метками времени; аргумент - сколько секунд писать."""
import sys, time, serial
s = serial.Serial("/dev/ttyACM0", 115200, timeout=0.2)
s.write(b"s")
konec = time.time() + float(sys.argv[1])
with open("/opt/helmet/serial.log", "a") as f:
    while time.time() < konec:
        line = s.readline()
        if line:
            f.write(time.strftime("%H:%M:%S ") + line.decode("utf-8", "replace"))
            f.flush()
