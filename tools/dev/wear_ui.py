"""Tiny UI driver for the watch over ADB (uiautomator dump + input taps).

Usage:
  tools/dev/wear_ui.py texts            # list visible texts
  tools/dev/wear_ui.py tap "<text>"     # tap the element with exactly this text
  tools/dev/wear_ui.py scroll|scrollup  # swipe the list

Notes: the round 450x450 screen clips items near y>420 / y<30 — scroll first.
Right after launching the app the first swipe is sometimes ignored; scroll twice.
"""
import re, subprocess, sys, time
import os
ADB = os.path.expanduser("~/Android/Sdk/platform-tools/adb")
def sh(*a): return subprocess.run([ADB, "shell", *a], capture_output=True, text=True).stdout
def dump():
    sh("uiautomator", "dump", "/sdcard/ui.xml")
    return sh("cat", "/sdcard/ui.xml")
def nodes(xml):
    for m in re.finditer(r'text="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
        t, *b = m.groups()
        if t: yield t, list(map(int, b))
if len(sys.argv) < 2:
    sys.exit(__doc__)
cmd = sys.argv[1]
if cmd == "texts":
    print(" | ".join(t for t, _ in nodes(dump())))
elif cmd == "tap":
    for t, (x1, y1, x2, y2) in nodes(dump()):
        if t == sys.argv[2]:
            x, y = (x1 + x2) // 2, (y1 + y2) // 2
            if y > 420 or y < 30:
                sys.exit(f"'{t}' at y={y} is at the screen edge; scroll first")
            sh("input", "tap", str(x), str(y)); time.sleep(1.5); print(f"tapped '{t}' @ {x},{y}"); break
    else:
        sys.exit(f"not found: {sys.argv[2]}")
elif cmd == "scroll":
    sh("input", "swipe", "225", "400", "225", "120", "700"); time.sleep(1)
elif cmd == "scrollup":
    sh("input", "swipe", "225", "120", "225", "400", "700"); time.sleep(1)
