#!/usr/bin/env python3
"""Run haptic regressions on a disposable API 34+ emulator; clears its Volt app data."""
import argparse
import json
from pathlib import Path
import re
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--adb', required=True, help='Android SDK adb executable')
parser.add_argument('--serial', required=True, help='Explicit disposable emulator serial')
parser.add_argument('--app', type=Path, required=True, help='Test-signed app APK')
parser.add_argument('--runner', type=Path, required=True, help='Same-key parked-validation APK')
parser.add_argument('--out', type=Path, required=True)
parser.add_argument('--case', action='append', choices=('quick', 'connection'),
                    help='Run one fixture (repeatable); defaults to both')
args = parser.parse_args()
if not re.fullmatch(r'emulator-\d+', args.serial):
    parser.error('Physical devices are refused; supply an explicit disposable emulator serial')
if not args.app.is_file() or not args.runner.is_file():
    parser.error('Both APKs must exist before installing anything')

prefix = [args.adb, '-s', args.serial]
app_package = 'local.navee.offlinelock'
runner_package = app_package + '.tests'


def run(*values, check=True):
    completed = subprocess.run(prefix + list(values), text=True, stdout=subprocess.PIPE,
                               stderr=subprocess.STDOUT, timeout=180)
    if check and completed.returncode:
        raise RuntimeError(completed.stdout)
    return completed.stdout


# All checks precede install, uninstall, clear, settings writes or Bluetooth changes.
if run('shell', 'getprop', 'ro.kernel.qemu').strip() != '1':
    raise SystemExit('A genuine disposable emulator is required')
if run('shell', 'getprop', 'sys.boot_completed').strip() != '1':
    raise SystemExit('Wait until the emulator has finished booting')
if int(run('shell', 'getprop', 'ro.build.version.sdk').strip()) < 34:
    raise SystemExit('API 34+ is required for directional toggle diagnostics')

old_haptics = run('shell', 'settings', 'get', 'system', 'haptic_feedback_enabled').strip()
old_bluetooth = run('shell', 'settings', 'get', 'global', 'bluetooth_on').strip()
if old_haptics not in ('null', '0', '1') or old_bluetooth not in ('null', '0', '1'):
    raise SystemExit('Unrecognized emulator settings; refusing to change them')
args.out.mkdir(parents=True, exist_ok=True)
results = []
errors = []
cases = list(dict.fromkeys(args.case or ('quick', 'connection')))

try:
    for package in (runner_package, app_package):
        run('uninstall', package, check=False)
    run('install', str(args.app.resolve()))
    run('install', str(args.runner.resolve()))
    run('shell', 'cmd', 'bluetooth_manager', 'disable')
    for case in cases:
        name = case.capitalize() + 'HapticsValidation'
        run('shell', 'am', 'force-stop', app_package)
        run('shell', 'pm', 'clear', app_package)
        for permission in ('BLUETOOTH_SCAN', 'BLUETOOTH_CONNECT', 'POST_NOTIFICATIONS'):
            run('shell', 'pm', 'grant', app_package, 'android.permission.' + permission)
        output = run('shell', 'am', 'instrument', '-w',
                     runner_package + '/' + runner_package + '.' + name)
        destination = args.out / name
        destination.mkdir(exist_ok=True)
        (destination / 'result.txt').write_text(output)
        # Pull before clear: evidence survives failed assertions but stays out of the app APK.
        run('pull', '/sdcard/Android/data/' + app_package + '/files/haptics-validation',
            str(destination.resolve()), check=False)
        assertions = re.search(r'INSTRUMENTATION_RESULT: assertions=(\d+)', output)
        passed = ('INSTRUMENTATION_RESULT: result=PASS' in output
                  and 'INSTRUMENTATION_RESULT: result=FAIL' not in output
                  and 'INSTRUMENTATION_CODE: 0' in output
                  and assertions is not None and int(assertions.group(1)) > 0)
        results.append(dict(case=name, passed=passed,
                            assertions=int(assertions.group(1)) if assertions else 0))
        print(f'{name}: {"PASS" if passed else "FAIL"}', flush=True)
        if not passed:
            print(output, flush=True)
except Exception as error:
    errors.append(str(error))
finally:
    # Also runs when install, launch, instrumentation or evidence collection fails. The
    # instrumentation has its own system-setting cleanup; this handles process crashes too.
    def cleanup(*values):
        try:
            return run(*values)
        except Exception as error:
            errors.append('Cleanup: ' + str(error))
            return ''

    cleanup('shell', 'am', 'force-stop', app_package)
    cleanup('shell', 'pm', 'clear', app_package)
    if old_haptics == 'null':
        cleanup('shell', 'settings', 'delete', 'system', 'haptic_feedback_enabled')
    else:
        cleanup('shell', 'settings', 'put', 'system', 'haptic_feedback_enabled', old_haptics)
    cleanup('shell', 'cmd', 'bluetooth_manager', 'enable' if old_bluetooth == '1' else 'disable')
    if old_bluetooth == 'null':
        cleanup('shell', 'settings', 'delete', 'global', 'bluetooth_on')
    if cleanup('shell', 'settings', 'get', 'system', 'haptic_feedback_enabled').strip() != old_haptics:
        errors.append('Cleanup: haptic preference was not restored')
    (args.out / 'results.json').write_text(json.dumps(dict(results=results, errors=errors), indent=2) + '\n')

for error in errors:
    print(error, flush=True)
if errors or len(results) != len(cases) or not all(result['passed'] for result in results):
    raise SystemExit(1)
