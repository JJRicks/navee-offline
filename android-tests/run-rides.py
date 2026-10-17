#!/usr/bin/env python3
"""Run the ride-analysis fixture on an explicitly selected disposable emulator."""
import argparse
import pathlib
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--adb", required=True)
parser.add_argument("--serial", required=True)
parser.add_argument("--app", required=True)
parser.add_argument("--runner", required=True)
parser.add_argument("--out", required=True)
parser.add_argument("--case", action="append", help="widthxheight dp:font scale:dark|light")
parser.add_argument("--start-only", action="store_true", help="Only validate immediate start, automatic naming, and rename")
parser.add_argument("--text-scale", default="1", choices=("1", "1.4"), help="App text scaling, independent of Android font scale")
args = parser.parse_args()
if not args.serial.startswith("emulator-"):
    parser.error("This destructive fixture refuses physical phones; use a disposable emulator.")
output = pathlib.Path(args.out).resolve()
output.mkdir(parents=True, exist_ok=True)

def adb(*command, optional=False):
    result = subprocess.run([args.adb, "-s", args.serial, *command], capture_output=True, text=True)
    text = result.stdout + result.stderr
    if result.returncode and not optional:
        raise RuntimeError(text)
    return text

if adb("shell", "getprop", "ro.kernel.qemu").strip() != "1":
    parser.error("Selected device did not identify itself as an emulator.")

cases = args.case or ["344x882:1:dark", "725x604:1:dark", "360x800:1.4:light"]
original_policy = adb("shell", "settings", "get", "global", "hidden_api_policy").strip()
original_ime = adb("shell", "settings", "get", "secure", "show_ime_with_hard_keyboard").strip()
parsed = []
for case in cases:
    size, font, theme = case.split(":")
    width, height = map(int, size.split("x"))
    if not (280 <= width <= 1600 and 280 <= height <= 1800 and .8 <= float(font) <= 2 and theme in ("dark", "light")):
        parser.error("Unreasonable viewport/font/theme fixture")
    parsed.append((width, height, font, theme))
try:
    # A synthetic ready-state GATT tests Record without connecting to any radio.
    adb("shell", "settings", "put", "global", "hidden_api_policy", "1")
    adb("shell", "svc", "bluetooth", "disable")
    adb("shell", "settings", "put", "secure", "show_ime_with_hard_keyboard", "1")
    adb("shell", "settings", "put", "system", "screen_off_timeout", "2147483647")
    adb("shell", "input", "keyevent", "82")
    for width, height, font, theme in parsed:
        variant = f"{width}x{height}-{font}-{theme}"
        print(variant, flush=True)
        adb("uninstall", "local.navee.offlinelock.tests", optional=True)
        adb("uninstall", "local.navee.offlinelock", optional=True)
        adb("shell", "wm", "density", "480")
        adb("shell", "wm", "size", f"{width * 3}x{height * 3}")
        adb("shell", "settings", "put", "system", "font_scale", font)
        adb("install", args.app)
        adb("install", args.runner)
        for permission in ("BLUETOOTH_SCAN", "BLUETOOTH_CONNECT", "POST_NOTIFICATIONS"):
            adb("shell", "pm", "grant", "local.navee.offlinelock", f"android.permission.{permission}")
        result = adb("shell", "am", "instrument", "-w", "-r", "-e", "variant", variant, "-e", "theme", theme, "-e", "text_scale", args.text_scale, "-e", "start_only", str(args.start_only).lower(),
                     "local.navee.offlinelock.tests/local.navee.offlinelock.tests.RidesAnalysisValidation")
        (output / f"{variant}.txt").write_text(result)
        adb("pull", "/sdcard/Android/data/local.navee.offlinelock/files/rides-validation", str(output), optional=True)
        print(result, flush=True)
        if "result=PASS" not in result:
            raise RuntimeError(f"Ride validation failed: {variant}")
finally:
    if original_policy == "null":
        adb("shell", "settings", "delete", "global", "hidden_api_policy", optional=True)
    else:
        adb("shell", "settings", "put", "global", "hidden_api_policy", original_policy, optional=True)
    if original_ime == "null":
        adb("shell", "settings", "delete", "secure", "show_ime_with_hard_keyboard", optional=True)
    else:
        adb("shell", "settings", "put", "secure", "show_ime_with_hard_keyboard", original_ime, optional=True)
    adb("shell", "wm", "size", "reset", optional=True)
    adb("shell", "wm", "density", "reset", optional=True)
    adb("shell", "settings", "put", "system", "font_scale", "1.0", optional=True)
