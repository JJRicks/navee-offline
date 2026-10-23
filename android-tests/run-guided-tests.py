#!/usr/bin/env python3
"""Guided setting test checks over an in-process synthetic link; requires a disposable emulator and never accepts a phone serial.

Each case is widthxheight:theme:system-font-scale[:app-text-scale]. Per case the runner replaces
the emulator's Volt data, keeps Bluetooth off, runs GuidedTestValidation in guided mode, then the
process-death pair (interrupt-start is killed between target and restore; interrupt-resume runs on
the same data), then Experimental controls on a synthetic N65i over the same link. The synthetic link needs reflective access to the framework's GATT internals, so
the emulator's hidden_api_policy is relaxed for the run and deleted afterwards, together with the
window size, density and font scale. No frame ever reaches a radio.
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
parser.add_argument('--mode', default='all', choices=('all', 'guided', 'interrupt', 'queue', 'recovery-navigation', 'experimental'))
parser.add_argument('--case', action='append', help='widthxheight:theme:system-font-scale[:app-text-scale]')
args = parser.parse_args()
if not re.fullmatch(r'emulator-\d+', args.serial):
    parser.error('An explicit emulator serial is required; physical devices are refused')
prefix = [args.adb, '-s', args.serial]
PACKAGE = 'com.jjricks.volt'
RUNNER = 'com.jjricks.volt.tests/com.jjricks.volt.GuidedTestValidation'
REMOTE = f'/sdcard/Android/data/{PACKAGE}/files/guided-validation'
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
for package in ('com.jjricks.volt.tests', PACKAGE):
    run('uninstall', package, check=False)
run('install', '--no-incremental', str(args.app.resolve()))
run('install', '--no-incremental', str(args.runner.resolve()))
run('shell', 'cmd', 'bluetooth_manager', 'disable')
previous_policy = run('shell', 'settings', 'get', 'global', 'hidden_api_policy').strip()
run('shell', 'settings', 'put', 'global', 'hidden_api_policy', '1')
run('shell', 'settings', 'put', 'global', 'sysui_demo_allowed', '1', check=False)
for command in (['-e', 'command', 'enter'], ['-e', 'command', 'clock', '-e', 'hhmm', '1200'],
                ['-e', 'command', 'battery', '-e', 'level', '100', '-e', 'plugged', 'false'],
                ['-e', 'command', 'notifications', '-e', 'visible', 'false']):
    run('shell', 'am', 'broadcast', '-a', 'com.android.systemui.demo', *command, check=False)

def fresh():
    run('shell', 'pm', 'clear', PACKAGE)
    for permission in ('BLUETOOTH_SCAN', 'BLUETOOTH_CONNECT', 'POST_NOTIFICATIONS'):
        run('shell', 'pm', 'grant', PACKAGE, 'android.permission.' + permission, check=False)

def instrument(mode, theme, text, variant):
    return run('shell', 'am', 'instrument', '-w', '-e', 'theme', theme, '-e', 'text_scale', text,
               '-e', 'variant', variant, '-e', 'mode', mode, RUNNER, timeout=900, check=False)

results = []
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
        variant = f'{width}x{height}-{theme}-font{font}-text{text}'
        destination = args.out / case.replace(':', '-'); destination.mkdir(exist_ok=True)
        passed = {}
        if args.mode in ('all', 'guided', 'queue'):
            fresh()
            output = instrument('queue' if args.mode == 'queue' else 'guided', theme, text, variant)
            (destination / 'guided-result.txt').write_text(output)
            run('pull', REMOTE, str(destination.resolve()), check=False)
            passed['guided'] = 'result=PASS' in output and 'result=FAIL' not in output
            if not passed['guided']: print(output, flush=True)
        if args.mode in ('all', 'recovery-navigation'):
            fresh()
            output = instrument('recovery-navigation', theme, text, variant)
            (destination / 'recovery-navigation-result.txt').write_text(output)
            passed['recovery_navigation'] = 'result=PASS' in output and 'result=FAIL' not in output
            if not passed['recovery_navigation']: print(output, flush=True)
        if args.mode in ('all', 'experimental'):
            fresh()
            output = instrument('experimental', theme, text, variant)
            (destination / 'experimental-result.txt').write_text(output)
            run('pull', REMOTE, str(destination.resolve()), check=False)
            passed['experimental'] = 'result=PASS' in output and 'result=FAIL' not in output
            if not passed['experimental']: print(output, flush=True)
        if args.mode in ('all', 'interrupt'):
            fresh()
            output = instrument('interrupt-start', theme, text, variant)
            (destination / 'interrupt-start-result.txt').write_text(output)
            run('pull', REMOTE, str(destination.resolve()), check=False)
            marker = destination / 'guided-validation' / 'interrupt-marker.json'
            # The start phase ends by killing its own process: there is no PASS line, only the marker.
            killed = marker.is_file() and 'result=PASS' not in output and 'result=FAIL' not in output
            pid = run('shell', 'pidof', PACKAGE, check=False).strip()
            output = instrument('interrupt-resume', theme, text, variant)
            (destination / 'interrupt-resume-result.txt').write_text(f'app pid before resume: {pid or "none"}\n' + output)
            run('pull', REMOTE, str(destination.resolve()), check=False)
            passed['interrupt'] = killed and 'result=PASS' in output and 'result=FAIL' not in output
            if not passed['interrupt']: print(output, flush=True)
        ok = all(passed.values())
        results.append(dict(case=case, passed=ok, parts=passed)); print(f'{case}: {"PASS" if ok else "FAIL"} {passed}', flush=True)
finally:
    run('shell', 'am', 'broadcast', '-a', 'com.android.systemui.demo', '-e', 'command', 'exit', check=False)
    run('shell', 'wm', 'size', 'reset', check=False); run('shell', 'wm', 'density', 'reset', check=False)
    run('shell', 'settings', 'put', 'system', 'font_scale', '1.0', check=False)
    if previous_policy in ('', 'null'):
        run('shell', 'settings', 'delete', 'global', 'hidden_api_policy', check=False)
    else:
        run('shell', 'settings', 'put', 'global', 'hidden_api_policy', previous_policy, check=False)
    (args.out / 'results.json').write_text(json.dumps(results, indent=2) + '\n')
if not all(result['passed'] for result in results):
    raise SystemExit(1)
