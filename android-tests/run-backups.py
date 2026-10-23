#!/usr/bin/env python3
"""Unified backup journeys on an explicitly selected disposable emulator; never a physical phone.

Each journey clears the app with pm clear first. Run android-tests/run-ride-backups.py separately
for the ride-file restore and cold-reopen range check at several window sizes.
"""
import argparse
import json
import pathlib
import re
import subprocess

PACKAGE = 'local.navee.offlinelock'
RUNNER = PACKAGE + '.tests/' + PACKAGE + '.tests.'
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--adb', required=True)
parser.add_argument('--serial', required=True)
parser.add_argument('--app', type=pathlib.Path, required=True)
parser.add_argument('--runner', type=pathlib.Path, required=True)
parser.add_argument('--fixture', type=pathlib.Path, default=pathlib.Path(__file__).resolve().parent.parent / 'tests/fixtures/legacy/full-v1.voltbackup')
parser.add_argument('--out', type=pathlib.Path, required=True)
args = parser.parse_args()
if not re.fullmatch(r'emulator-\d+', args.serial):
    parser.error('An explicit emulator serial is required; physical devices are refused')
prefix = [args.adb, '-s', args.serial]
def run(*values, check=True):
    result = subprocess.run(prefix + list(values), text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=300)
    if check and result.returncode:
        raise RuntimeError(result.stdout)
    return result.stdout
if run('shell', 'getprop', 'ro.kernel.qemu').strip() != '1' or run('shell', 'getprop', 'sys.boot_completed').strip() != '1':
    raise SystemExit('A booted disposable emulator is required')
args.out.mkdir(parents=True, exist_ok=True)
external = '/sdcard/Android/data/' + PACKAGE + '/files/'
for package in (PACKAGE + '.tests', PACKAGE):
    run('uninstall', package, check=False)
run('install', str(args.app.resolve()))
run('install', str(args.runner.resolve()))
run('shell', 'cmd', 'bluetooth_manager', 'disable')
results = []
def instrument(name, runner, *extra):
    output = run('shell', 'am', 'instrument', '-w', *extra, RUNNER + runner, check=False)
    (args.out / (name + '.txt')).write_text(output)
    passed = ('result=PASS' in output or 'status=PASS' in output) and 'result=FAIL' not in output and 'status=FAIL' not in output
    results.append(dict(journey=name, passed=passed)); print(f'{name}: {"PASS" if passed else "FAIL"}', flush=True)
    if not passed: print(output, flush=True)
    return passed
def clear():
    run('shell', 'pm', 'clear', PACKAGE)
    run('shell', 'mkdir', '-p', external)
try:
    # Complete export, host copy, Clear storage, push back, Replace everything, compare everything.
    clear()
    if instrument('full-export', 'FullBackupJourneyValidation', '-e', 'mode', 'export'):
        host = args.out / 'backup-journey.voltbackup'
        run('pull', external + 'backup-journey.voltbackup', str(host))
        clear(); run('push', str(host), external + 'backup-journey.voltbackup')
        instrument('full-restore-check', 'FullBackupJourneyValidation', '-e', 'mode', 'restore-check')
        host.unlink()
    # The committed Volt 1.3.1 file restores after Clear storage.
    clear(); run('push', str(args.fixture.resolve()), external + 'legacy-v1.voltbackup')
    instrument('legacy-v1-restore', 'FullBackupJourneyValidation', '-e', 'mode', 'legacy-v1')
    clear(); instrument('owner-only-merge', 'FullBackupJourneyValidation', '-e', 'mode', 'owner-only')
    clear(); instrument('owner-existing-profile', 'FullBackupJourneyValidation', '-e', 'mode', 'owner-existing')
    clear(); instrument('owner-another-profile', 'FullBackupJourneyValidation', '-e', 'mode', 'owner-another')
    clear(); instrument('adapter', 'AppDataBackupValidation')
    clear(); instrument('dashboard-transfer', 'DashboardTransferValidation')
    for case in ('344x882:dark:1', '725x604:light:1.4'):
        width, height = map(int, case.split(':')[0].split('x')); theme, font = case.split(':')[1:]
        run('shell', 'wm', 'density', '480'); run('shell', 'wm', 'size', f'{width * 3}x{height * 3}')
        run('shell', 'settings', 'put', 'system', 'font_scale', font)
        clear()
        if theme == 'light': run('shell', 'cmd', 'uimode', 'night', 'no')
        else: run('shell', 'cmd', 'uimode', 'night', 'yes')
        # A system "isn't responding" dialog keeps window focus from every app and fails focus checks.
        if 'Not Responding' in run('shell', 'dumpsys', 'window', 'displays', check=False).split('mCurrentFocus', 1)[-1].split('\n', 1)[0]:
            raise SystemExit('A system "isn\'t responding" dialog has focus on ' + args.serial + '; dismiss it and run again')
        name = 'ui-' + case.replace(':', '-')
        instrument(name, 'BackupUiValidation', '-e', 'theme', theme)
        run('pull', external + 'backup-categories.png', str(args.out / (name + '.png')), check=False)
    clear()
    # The maintenance journey binds the real service; Bluetooth itself stays disabled.
    for permission in ('BLUETOOTH_SCAN', 'BLUETOOTH_CONNECT', 'POST_NOTIFICATIONS'):
        run('shell', 'pm', 'grant', PACKAGE, 'android.permission.' + permission)
    instrument('maintenance', 'AppDataMaintenanceValidation')
    # Read-only: run last, after the journeys above left real app data in place.
    instrument('inventory', 'BackupInventoryValidation')
finally:
    run('shell', 'wm', 'size', 'reset', check=False); run('shell', 'wm', 'density', 'reset', check=False)
    run('shell', 'settings', 'put', 'system', 'font_scale', '1.0', check=False)
    (args.out / 'results.json').write_text(json.dumps(results, indent=2) + '\n')
if not all(result['passed'] for result in results):
    raise SystemExit(1)
