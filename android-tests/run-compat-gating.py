#!/usr/bin/env python3
"""Simulated-model compatibility gating checks; requires a disposable emulator and never accepts a phone serial.

Each case is widthxheight:theme:system-font-scale[:app-text-scale]. The runner replaces the
emulator's Volt data, keeps Bluetooth off, uses the developer simulator only (no frames) and
restores the window size, density and font scale afterwards.
"""
import argparse
import hashlib
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
parser.add_argument('--mode', default='gating', choices=('gating', 'ut5-screens', 'import-name', 'controls'))
parser.add_argument('--case', action='append', help='widthxheight:theme:system-font-scale[:app-text-scale]')
args = parser.parse_args()
if not re.fullmatch(r'emulator-\d+', args.serial):
    parser.error('An explicit emulator serial is required; physical devices are refused')
prefix = [args.adb, '-s', args.serial]
def run(*values, check=True, timeout=240):
    result = subprocess.run(prefix + list(values), text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=timeout)
    if check and result.returncode:
        raise RuntimeError(result.stdout)
    return result.stdout
if run('shell', 'getprop', 'ro.kernel.qemu').strip() != '1' or run('shell', 'getprop', 'sys.boot_completed').strip() != '1':
    raise SystemExit('A booted disposable emulator is required')
args.out.mkdir(parents=True, exist_ok=True)
(args.out / 'inputs.json').write_text(json.dumps({str(p.resolve()): hashlib.sha256(p.read_bytes()).hexdigest()
                                               for p in (args.app, args.runner)}, indent=2) + '\n')
for package in ('local.navee.offlinelock.tests', 'local.navee.offlinelock'):
    run('uninstall', package, check=False)
run('install', '--no-incremental', str(args.app.resolve()))
run('install', '--no-incremental', str(args.runner.resolve()))
run('shell', 'cmd', 'bluetooth_manager', 'disable')
runner = {'gating': 'CompatibilityGatingValidation', 'ut5-screens': 'Ut5SimulationScreens',
          'import-name': 'CompatibilityGatingValidation', 'controls': 'CompatControlsValidation'}[args.mode]
results = []
# A fixed status-bar clock keeps screenshots from two builds comparable pixel by pixel.
run('shell', 'settings', 'put', 'global', 'sysui_demo_allowed', '1', check=False)
for command in (['-e', 'command', 'enter'], ['-e', 'command', 'clock', '-e', 'hhmm', '1200'],
                ['-e', 'command', 'battery', '-e', 'level', '100', '-e', 'plugged', 'false'],
                ['-e', 'command', 'notifications', '-e', 'visible', 'false']):
    run('shell', 'am', 'broadcast', '-a', 'com.android.systemui.demo', *command, check=False)
try:
    for case in args.case or ['344x882:dark:1', '725x604:light:1.4']:
        fields = case.split(':')
        width, height = map(int, fields[0].split('x')); theme, font = fields[1:3]
        text = fields[3] if len(fields) > 3 else '1'
        if theme not in ('dark', 'light') or not 280 <= width <= 1400 or not 280 <= height <= 1600 \
                or not 1 <= float(font) <= 2 or not 1 <= float(text) <= 2:
            raise ValueError('Unsupported test case')
        run('shell', 'wm', 'density', '480'); run('shell', 'wm', 'size', f'{width * 3}x{height * 3}')
        run('shell', 'settings', 'put', 'system', 'font_scale', font)
        run('shell', 'pm', 'clear', 'local.navee.offlinelock')
        for permission in ('BLUETOOTH_SCAN', 'BLUETOOTH_CONNECT', 'POST_NOTIFICATIONS'):
            run('shell', 'pm', 'grant', 'local.navee.offlinelock', 'android.permission.' + permission, check=False)
        variant = f'{width}x{height}-{theme}-font{font}-text{text}'
        output = run('shell', 'am', 'instrument', '-w', '-e', 'theme', theme, '-e', 'text_scale', text,
                     '-e', 'variant', variant, '-e', 'mode', args.mode,
                     'local.navee.offlinelock.tests/local.navee.offlinelock.tests.' + runner, timeout=600)
        destination = args.out / case.replace(':', '-'); destination.mkdir(exist_ok=True)
        (destination / 'result.txt').write_text(output)
        run('pull', '/sdcard/Android/data/local.navee.offlinelock/files/compatibility-validation', str(destination.resolve()), check=False)
        passed = 'result=PASS' in output and 'result=FAIL' not in output
        results.append(dict(case=case, mode=args.mode, passed=passed)); print(f'{case}: {"PASS" if passed else "FAIL"}', flush=True)
        if not passed: print(output, flush=True)
finally:
    run('shell', 'am', 'broadcast', '-a', 'com.android.systemui.demo', '-e', 'command', 'exit', check=False)
    run('shell', 'wm', 'size', 'reset', check=False); run('shell', 'wm', 'density', 'reset', check=False)
    run('shell', 'settings', 'put', 'system', 'font_scale', '1.0', check=False)
    (args.out / 'results.json').write_text(json.dumps(results, indent=2) + '\n')
if not all(result['passed'] for result in results):
    raise SystemExit(1)
