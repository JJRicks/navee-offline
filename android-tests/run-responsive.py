#!/usr/bin/env python3
"""Run layout-only native fixtures on a disposable emulator; never selects a phone.

Build the app and android-tests/build.sh with the same key first. Start a read-only
AVD yourself, then supply its explicit emulator serial, SDK adb, both APKs and an
evidence directory. Existing emulator app data is deliberately replaced.
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
parser.add_argument('--baseline', action='store_true', help='Only the two original reported regressions')
parser.add_argument('--case', action='append', help='Target widthxheight:app-text-scale:mode[:system-font-scale] (repeatable; example restores saved All widgets slot)')
args = parser.parse_args()
if not re.fullmatch(r'emulator-\d+', args.serial):
    parser.error('An explicit emulator serial is required; physical phones are refused')
prefix = [args.adb, '-s', args.serial]
def run(*values, timeout=180, check=True):
    result = subprocess.run(prefix + list(values), text=True, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, timeout=timeout)
    if check and result.returncode:
        raise RuntimeError(result.stdout)
    return result.stdout
if run('shell', 'getprop', 'ro.kernel.qemu').strip() != '1':
    raise SystemExit('Refusing a non-emulator device')
if run('shell', 'getprop', 'sys.boot_completed').strip() != '1':
    raise SystemExit('Wait for the emulator to finish booting')
# Deliberately vary width, aspect ratio, navigation breakpoint, and text size.
# 344x882dp models the Fold5 cover; 725x604dp models its open landscape panel.
matrix = [(344, 882, 1.0, 'cards', 1.0), (725, 604, 1.0, 'fold', 1.0)] if args.baseline else [
    (320, 720, 1.0, 'cards', 1.0), (336, 800, 1.0, 'cards', 1.0),
    (337, 800, 1.0, 'cards', 1.0), (344, 882, 1.0, 'all', 1.0),
    (344, 882, 1.0, 'constraints', 1.3), (360, 800, 1.4, 'cards', 1.0),
    (411, 840, 1.0, 'cards', 1.0), (600, 850, 1.0, 'all', 1.0),
    (649, 850, 1.0, 'cards', 1.0), (650, 850, 1.0, 'cards', 1.0),
    (686, 725, 1.4, 'all', 1.0), (725, 604, 1.0, 'all', 1.0),
    (852, 690, 1.0, 'all', 1.0), (344, 882, 1.0, 'constraints', 1.0),
    (360, 800, 1.4, 'constraints', 1.0), (650, 850, 1.0, 'constraints', 1.0),
    (344, 882, 1.0, 'edit', 1.0), (725, 604, 1.0, 'edit', 1.0),
    (344, 882, 1.0, 'editor', 1.0), (725, 604, 1.0, 'editor', 1.0),
    (344, 882, 1.0, 'example', 1.0), (448, 960, 1.0, 'example', 1.0),
    (725, 604, 1.0, 'example', 1.0), (344, 882, 1.4, 'example', 1.0),
    (448, 960, 1.4, 'example', 1.0), (725, 604, 1.4, 'example', 1.0),
    (320, 720, 1.0, 'narrow', 1.0), (344, 882, 1.0, 'narrow', 1.0),
    (360, 800, 1.0, 'narrow', 1.0), (390, 844, 1.0, 'narrow', 1.0)]
if args.case:
    matrix = []
    for selected in args.case:
        fields = selected.split(':')
        if len(fields) not in (3, 4):
            parser.error('Each case is widthxheight:app-text-scale:mode[:system-font-scale]')
        size, scale, mode = fields[:3]
        system_scale = float(fields[3]) if len(fields) > 3 else 1.0
        width, height = map(int, size.split('x'))
        if not 280 <= width <= 1400 or not 280 <= height <= 1600 or not 1 <= float(scale) <= 2 or not 1 <= system_scale <= 2:
            parser.error('Cases must stay within reasonable emulator dimensions/text sizes')
        if mode not in ('all', 'cards', 'fold', 'battery', 'constraints', 'edit', 'editor', 'gauge', 'motion', 'quick', 'followup', 'chart', 'example', 'narrow'):
            parser.error('Unknown validation mode')
        matrix.append((width, height, float(scale), mode, system_scale))
args.out.mkdir(parents=True, exist_ok=True)
(args.out / 'inputs.json').write_text(json.dumps({str(p.resolve()): hashlib.sha256(p.read_bytes()).hexdigest()
                                               for p in (args.app, args.runner)}, indent=2) + '\n')
for package in ('local.navee.offlinelock.tests', 'local.navee.offlinelock'):
    run('uninstall', package, check=False)
run('install', str(args.app.resolve()))
run('install', str(args.runner.resolve()))
run('shell', 'cmd', 'bluetooth_manager', 'disable')
results = []
try:
    for width, height, scale, mode, system_scale in matrix:
        variant = f'{width}x{height}-text{scale}-font{system_scale}-{mode}'
        run('shell', 'wm', 'density', '480')
        run('shell', 'wm', 'size', f'{width * 3}x{height * 3}')
        run('shell', 'settings', 'put', 'system', 'font_scale', str(system_scale))
        run('shell', 'pm', 'clear', 'local.navee.offlinelock')
        for permission in ('BLUETOOTH_SCAN', 'BLUETOOTH_CONNECT'):
            run('shell', 'pm', 'grant', 'local.navee.offlinelock', 'android.permission.' + permission)
        output = run('shell', 'am', 'instrument', '-w', '-e', 'variant', variant,
                     '-e', 'text_scale', str(scale), '-e', 'mode', mode,
                     'local.navee.offlinelock.tests/local.navee.offlinelock.tests.ResponsiveDashboardValidation')
        (args.out / (variant + '.txt')).write_text(output)
        passed = 'result=PASS' in output and 'result=FAIL' not in output
        results.append(dict(width_dp=width, height_dp=height, text_scale=scale, system_font_scale=system_scale, mode=mode, passed=passed))
        dest = args.out / variant
        dest.mkdir(exist_ok=True)
        run('pull', '/sdcard/Android/data/local.navee.offlinelock/files/responsive-validation', str(dest.resolve()), check=False)
        print(f'{variant}: {"PASS" if passed else "FAIL"}', flush=True)
        if not passed:
            print(output, flush=True)
finally:
    run('shell', 'wm', 'size', 'reset', check=False)
    run('shell', 'wm', 'density', 'reset', check=False)
    run('shell', 'settings', 'put', 'system', 'font_scale', '1.0', check=False)
    (args.out / 'results.json').write_text(json.dumps(results, indent=2) + '\n')
if not all(result['passed'] for result in results):
    raise SystemExit(1)
