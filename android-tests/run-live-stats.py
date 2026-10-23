#!/usr/bin/env python3
"""Live Stats UI checks; requires a disposable emulator and never accepts a phone serial."""
import argparse
import json
from pathlib import Path
import re
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--adb', required=True)
parser.add_argument('--serial', required=True)
parser.add_argument('--app', type=Path, required=True)
parser.add_argument('--runner', type=Path, required=True)
parser.add_argument('--out', type=Path, required=True)
parser.add_argument('--case', action='append', help='widthxheight:theme:system-font-scale[:reduced]')
args = parser.parse_args()
if not re.fullmatch(r'emulator-\d+', args.serial):
    parser.error('An explicit emulator serial is required; physical devices are refused')
prefix = [args.adb, '-s', args.serial]
def run(*values, check=True):
    result = subprocess.run(prefix + list(values), text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=180)
    if check and result.returncode:
        raise RuntimeError(result.stdout)
    return result.stdout
if run('shell', 'getprop', 'ro.kernel.qemu').strip() != '1' or run('shell', 'getprop', 'sys.boot_completed').strip() != '1':
    raise SystemExit('A booted disposable emulator is required')
args.out.mkdir(parents=True, exist_ok=True)
for package in ('com.jjricks.volt.tests', 'com.jjricks.volt'):
    run('uninstall', package, check=False)
run('install', str(args.app.resolve()))
run('install', str(args.runner.resolve()))
run('shell', 'cmd', 'bluetooth_manager', 'disable')
results = []
try:
    for case in args.case or ['344x882:dark:1', '725x604:dark:1', '360x800:light:1.4', '650x850:light:1.3', '344x882:dark:1:reduced']:
        fields = case.split(':')
        width, height = map(int, fields[0].split('x')); theme, font = fields[1:3]
        if theme not in ('dark', 'light') or not 280 <= width <= 1400 or not 280 <= height <= 1600 or not 1 <= float(font) <= 2:
            raise ValueError('Unsupported test case')
        reduced = len(fields) == 4 and fields[3] == 'reduced'
        run('shell', 'wm', 'density', '480'); run('shell', 'wm', 'size', f'{width * 3}x{height * 3}')
        run('shell', 'settings', 'put', 'system', 'font_scale', font)
        run('shell', 'settings', 'put', 'global', 'animator_duration_scale', '0' if reduced else '1')
        run('shell', 'pm', 'clear', 'com.jjricks.volt')
        for permission in ('BLUETOOTH_SCAN', 'BLUETOOTH_CONNECT'):
            run('shell', 'pm', 'grant', 'com.jjricks.volt', 'android.permission.' + permission)
        output = run('shell', 'am', 'instrument', '-w', '-e', 'theme', theme,
                     'com.jjricks.volt.tests/com.jjricks.volt.tests.LiveStatsValidation')
        destination = args.out / case.replace(':', '-'); destination.mkdir(exist_ok=True)
        (destination / 'result.txt').write_text(output)
        run('pull', '/sdcard/Android/data/com.jjricks.volt/files/stats-validation', str(destination.resolve()), check=False)
        passed = 'result=PASS' in output and 'result=FAIL' not in output
        results.append(dict(case=case, passed=passed)); print(f'{case}: {"PASS" if passed else "FAIL"}', flush=True)
        if not passed: print(output, flush=True)
finally:
    run('shell', 'wm', 'size', 'reset', check=False); run('shell', 'wm', 'density', 'reset', check=False)
    run('shell', 'settings', 'put', 'system', 'font_scale', '1.0', check=False)
    run('shell', 'settings', 'put', 'global', 'animator_duration_scale', '1', check=False)
    (args.out / 'results.json').write_text(json.dumps(results, indent=2) + '\n')
if not all(result['passed'] for result in results):
    raise SystemExit(1)
