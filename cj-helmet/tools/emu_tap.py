"""Нажать в эмуляторе элемент по тексту (uiautomator). tap.py "текст" [ещё...]; список: tap.py --list"""
import re, subprocess, sys, time
A = "/opt/helmet/android/sdk/platform-tools/adb"
def dump():
    subprocess.run([A, "shell", "rm", "-f", "/sdcard/ui.xml"]); r = subprocess.run([A, "shell", "uiautomator", "dump", "/sdcard/ui.xml"], capture_output=True, text=True); print("dump:", r.stdout.strip()[-60:], r.stderr.strip()[-80:])
    return subprocess.run([A, "exec-out", "cat", "/sdcard/ui.xml"], capture_output=True, text=True).stdout
args = sys.argv[1:]
if args == ["--list"]:
    for t, d, b in re.findall(r"text=\"([^\"]*)\"[^>]*content-desc=\"([^\"]*)\"[^>]*bounds=\"([^\"]*)\"", dump()):
        if t or d: print(repr(t), repr(d), b)
    sys.exit()
for want in args:
    x = dump(); hit = None
    nodes = re.findall(r"text=\"([^\"]*)\"[^>]*content-desc=\"([^\"]*)\"[^>]*bounds=\"([^\"]*)\"", x)
    for t, d, b in nodes:  # сначала точное совпадение
        if want.lower() in (t.lower(), d.lower()):
            hit = b; break
    for t, d, b in ([] if hit else nodes):
        if want.lower() in (t + " " + d).lower():
            hit = b; break
    if not hit: print("нет:", want); continue
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", hit))
    subprocess.run([A, "shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2)])
    print("нажал:", want, hit); time.sleep(2)
