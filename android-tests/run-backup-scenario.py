#!/usr/bin/env python3
"""Prepare a disposable emulator for reviewing Back up & restore by hand; never a physical phone.

Seeds a synthetic "source" phone, keeps its scenario-*.voltbackup files (complete, single
categories, truncated, corrupted; password "synthetic scenario password"), clears the app,
seeds a "target" phone (another scooter, 12 charges) and copies the files plus the legacy
.voltrides and dashboard layout fixtures to Download/volt-scenario/ for the system picker.
Afterwards `--inspect` prints what a restore left behind. `--render` instead renders the restore
screens (bad file, categories, destinations, merge review) at each --case in-process, because
FLAG_SECURE keeps them out of screenshots.
"""
import argparse
import pathlib
import re
import subprocess

PACKAGE = 'com.jjricks.volt'
RUNNER = PACKAGE + '.tests/' + PACKAGE + '.tests.BackupScenarioValidation'
ROOT = pathlib.Path(__file__).resolve().parent.parent
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--adb', required=True)
parser.add_argument('--serial', required=True)
parser.add_argument('--app', type=pathlib.Path)
parser.add_argument('--runner', type=pathlib.Path)
parser.add_argument('--out', type=pathlib.Path, required=True)
parser.add_argument('--inspect', action='store_true', help='only report the current app state')
parser.add_argument('--render', action='store_true', help='render the restore screens at each --case')
parser.add_argument('--render-mode', default='render', choices=('render', 'render-damaged', 'render-scooters'),
                    help='with --render: also corrupt local profiles first, or check Scooters-only destinations')
parser.add_argument('--case', action='append', help='widthxheight:theme:system-font-scale')
args = parser.parse_args()
if not re.fullmatch(r'emulator-\d+', args.serial):
    parser.error('An explicit emulator serial is required; physical devices are refused')
prefix = [args.adb, '-s', args.serial]
def run(*values, check=True):
    result = subprocess.run(prefix + list(values), text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=300)
    if check and result.returncode:
        raise RuntimeError(result.stdout)
    return result.stdout
def instrument(mode):
    output = run('shell', 'am', 'instrument', '-w', '-e', 'mode', mode, RUNNER, check=False)
    (args.out / (mode + '.txt')).write_text(output)
    if 'result=PASS' not in output:
        raise SystemExit(output)
    return output
if run('shell', 'getprop', 'ro.kernel.qemu').strip() != '1' or run('shell', 'getprop', 'sys.boot_completed').strip() != '1':
    raise SystemExit('A booted disposable emulator is required')
args.out.mkdir(parents=True, exist_ok=True)
if args.inspect:
    print(instrument('inspect'), end='')
    raise SystemExit(0)
if not args.app or not args.runner:
    parser.error('--app and --runner are required to seed')
external = '/sdcard/Android/data/' + PACKAGE + '/files/'
download = '/sdcard/Download/volt-scenario/'
for package in (PACKAGE + '.tests', PACKAGE):
    run('uninstall', package, check=False)
run('install', str(args.app.resolve())); run('install', str(args.runner.resolve()))
run('shell', 'cmd', 'bluetooth_manager', 'disable')
run('shell', 'pm', 'clear', PACKAGE); run('shell', 'mkdir', '-p', external)
instrument('source')
for name in ('complete', 'corrupted'):
    run('pull', external + 'scenario-' + name + '.voltbackup', str(args.out / ('scenario-' + name + '.voltbackup')))
if args.render:
    results = []
    try:
        for case in args.case or ['344x882:dark:1', '725x604:light:1.4']:
            size, theme, font = case.split(':'); width, height = map(int, size.split('x'))
            if theme not in ('dark', 'light') or not 280 <= width <= 1400 or not 280 <= height <= 1600 or not 1 <= float(font) <= 2:
                raise ValueError('Unsupported case')
            run('shell', 'wm', 'density', '480'); run('shell', 'wm', 'size', f'{width * 3}x{height * 3}')
            run('shell', 'settings', 'put', 'system', 'font_scale', font)
            run('shell', 'pm', 'clear', PACKAGE); run('shell', 'mkdir', '-p', external)
            for permission in ('BLUETOOTH_SCAN', 'BLUETOOTH_CONNECT', 'POST_NOTIFICATIONS'):
                run('shell', 'pm', 'grant', PACKAGE, 'android.permission.' + permission)
            for name in ('complete', 'corrupted'):
                run('push', str(args.out / ('scenario-' + name + '.voltbackup')), external + 'scenario-' + name + '.voltbackup')
            output = run('shell', 'am', 'instrument', '-w', '-e', 'mode', args.render_mode, '-e', 'theme', theme, RUNNER, check=False)
            folder = args.out / case.replace(':', '-'); folder.mkdir(exist_ok=True); (folder / 'render.txt').write_text(output)
            for image in ('restore-bad-file', 'restore-categories', 'restore-destinations', 'restore-review'):
                run('pull', external + image + '.png', str(folder / (image + '.png')), check=False)
            passed = 'result=PASS' in output; results.append(passed)
            print(f'{case}: {"PASS" if passed else "FAIL"}', flush=True)
            if not passed: print(output, flush=True)
    finally:
        run('shell', 'wm', 'size', 'reset', check=False); run('shell', 'wm', 'density', 'reset', check=False)
        run('shell', 'settings', 'put', 'system', 'font_scale', '1.0', check=False)
    raise SystemExit(0 if all(results) else 1)
run('shell', 'rm', '-rf', download); run('shell', 'mkdir', '-p', download)
for name in ('complete', 'rides', 'preferences', 'dashboards', 'charges', 'truncated', 'corrupted'):
    run('shell', 'cp', external + 'scenario-' + name + '.voltbackup', download)
run('push', str(ROOT / 'tests/fixtures/legacy/rides-v1.voltrides'), download + 'legacy-rides.voltrides')
run('push', str(ROOT / 'tests/fixtures/legacy/dashboards-v1.json'), download + 'legacy-layout.json')
run('push', str(ROOT / 'tests/fixtures/legacy/full-v1.voltbackup'), download + 'legacy-full-v1.voltbackup')
run('shell', 'pm', 'clear', PACKAGE); run('shell', 'mkdir', '-p', external)
instrument('target')
# Let the system picker see the copied files.
run('shell', 'content', 'call', '--uri', 'content://media/external/file', '--method', 'scan_volume', '--arg', 'external_primary', check=False)
print(run('shell', 'ls', '-l', download), end='')
print(instrument('inspect'), end='')
