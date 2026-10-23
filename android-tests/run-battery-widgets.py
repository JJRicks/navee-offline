#!/usr/bin/env python3
"""Run battery presentation fixtures on a disposable, explicitly selected emulator."""
import argparse
import json
from pathlib import Path
import re
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
for name in ('adb', 'serial', 'app', 'runner', 'out'):
    parser.add_argument('--' + name, required=True)
parser.add_argument('--case', action='append', help='WIDTHxHEIGHT:TEXT_SCALE:THEME:MOTION_SCALE')
args = parser.parse_args()
if not re.fullmatch(r'emulator-\d+', args.serial):
    parser.error('Only explicit emulator serials are accepted; this replaces emulator app data')
prefix = [args.adb, '-s', args.serial]

def run(*words, check=True):
    done = subprocess.run(prefix + list(words), text=True, stdout=subprocess.PIPE,
                          stderr=subprocess.STDOUT, timeout=180)
    if check and done.returncode:
        raise RuntimeError(done.stdout)
    return done.stdout

if run('shell', 'getprop', 'ro.kernel.qemu').strip() != '1':
    raise SystemExit('Refusing a physical device')
out = Path(args.out).resolve()
out.mkdir(parents=True, exist_ok=True)
for package in ('com.jjricks.volt.tests', 'com.jjricks.volt'):
    run('uninstall', package, check=False)
run('install', str(Path(args.app).resolve()))
run('install', str(Path(args.runner).resolve()))
run('shell', 'cmd', 'bluetooth_manager', 'disable')
results = []
try:
    for case in args.case or ['344x882:1:dark:1', '725x604:1:dark:1', '360x800:1.4:light:1', '344x882:1:dark:0']:
        dimensions, scale, theme, motion = case.split(':')
        width, height = map(int, dimensions.split('x'))
        if not (280 <= width <= 1400 and 280 <= height <= 1600 and 1 <= float(scale) <= 2 and theme in ('dark', 'light') and motion in ('0', '1')):
            raise ValueError('Invalid case')
        dest = out / case.replace(':', '-')
        dest.mkdir(exist_ok=True)
        run('shell', 'wm', 'density', '480')
        run('shell', 'wm', 'size', f'{width * 3}x{height * 3}')
        run('shell', 'settings', 'put', 'global', 'animator_duration_scale', motion)
        run('shell', 'pm', 'clear', 'com.jjricks.volt')
        for permission in ('BLUETOOTH_SCAN', 'BLUETOOTH_CONNECT'):
            run('shell', 'pm', 'grant', 'com.jjricks.volt', 'android.permission.' + permission)
        result = run('shell', 'am', 'instrument', '-w', '-e', 'text_scale', scale, '-e', 'theme', theme,
                     'com.jjricks.volt.tests/com.jjricks.volt.tests.BatteryWidgetValidation')
        (dest / 'result.txt').write_text(result)
        run('pull', '/sdcard/Android/data/com.jjricks.volt/files/battery-widget-validation', str(dest), check=False)
        passed = 'result=PASS' in result and 'result=FAIL' not in result
        results.append({'case': case, 'passed': passed})
        print(case, 'PASS' if passed else result, flush=True)
        if not passed:
            break
    if results and all(r['passed'] for r in results):
        result = run('shell', 'am', 'instrument', '-w', 'com.jjricks.volt.tests/com.jjricks.volt.tests.DashboardSlotsValidation')
        (out / 'slots.txt').write_text(result)
        passed = 'result=PASS' in result and 'result=FAIL' not in result
        results.append({'case': 'saved-slots', 'passed': passed})
        print('saved-slots', 'PASS' if passed else result, flush=True)
finally:
    run('shell', 'wm', 'size', 'reset', check=False)
    run('shell', 'wm', 'density', 'reset', check=False)
    run('shell', 'settings', 'put', 'global', 'animator_duration_scale', '1', check=False)
    (out / 'results.json').write_text(json.dumps(results, indent=2) + '\n')
if not results or not all(r['passed'] for r in results):
    raise SystemExit(1)
