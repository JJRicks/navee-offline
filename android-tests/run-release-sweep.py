#!/usr/bin/env python3
"""Every emulator validation a release needs, on one disposable emulator; never accepts a phone serial.

Build the app and android-tests/build.sh with the same key first, start a read-only AVD yourself,
then pass its explicit emulator serial, SDK adb, both APKs and an evidence directory. Existing
emulator app data is deliberately replaced. Before anything runs, every <instrumentation> in
android-tests/AndroidManifest.xml must be covered by a step below or EXCLUDED with a reason, so a
new validation can't be left out of a release by accident. The guard works per class, not per
mode: when a validation or runner gains a mode with checks of its own, add a step for it here, or
list it in SUBSUMED with the mode whose run already includes it. --only reruns named steps;
--list prints them. Each direct step's on-device files (screenshots) are copied to its evidence
directory before the app is removed.
"""
import argparse
import json
from pathlib import Path
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ElementTree

HERE = Path(__file__).resolve().parent
PKG = 'com.jjricks.volt'
ALL = ('BLUETOOTH_SCAN', 'BLUETOOTH_CONNECT', 'POST_NOTIFICATIONS', 'ACCESS_FINE_LOCATION', 'ACCESS_COARSE_LOCATION')
NEARBY = ALL[:3]
PHONE, WIDE = ('344x882', 'dark', '1'), ('725x604', 'light', '1.4')
# Registered but deliberately not run here, with the reason; everything else must have a step.
EXCLUDED = {'ParkedValidation': 'physical parked-scooter checks that send real lock, PIN and clock commands; run only under an explicit physical test plan'}
# Modes not run on their own because a step's mode already runs every check they make.
SUBSUMED = {'ResponsiveDashboardValidation battery, capacity and fold': 'all (in the default responsive matrix)',
            'DeveloperSettingsValidation preferences': 'all (developer-settings steps)',
            'run-backup-scenario --inspect': 'its default prepare run, which ends with inspect'}
ANDROID = '{http://schemas.android.com/apk/res/android}'


def runner(name, script, covers, *extra):
    return dict(name=name, kind='runner', script=script, covers=covers, extra=list(extra))


def inst(name, cls, *extra, grant=ALL, size=None, hidden_api=False):
    return dict(name=name, kind='inst', cls=cls, covers=[cls.rsplit('.', 1)[1]], extra=list(extra), grant=grant, size=size, hidden_api=hidden_api)


T = PKG + '.tests.'
STEPS = [
    runner('backups', 'run-backups.py', ['AppDataBackupValidation', 'AppDataMaintenanceValidation', 'BackupInventoryValidation',
                                         'BackupUiValidation', 'DashboardTransferValidation', 'FullBackupJourneyValidation']),
    runner('backup-scenario', 'run-backup-scenario.py', ['BackupScenarioValidation']),
    runner('backup-render', 'run-backup-scenario.py', [], '--render'),
    runner('backup-render-damaged', 'run-backup-scenario.py', [], '--render', '--render-mode', 'render-damaged'),
    runner('backup-render-scooters', 'run-backup-scenario.py', [], '--render', '--render-mode', 'render-scooters'),
    runner('guided', 'run-guided-tests.py', ['GuidedTestValidation']),
    runner('guided-queue', 'run-guided-tests.py', [], '--mode', 'queue'),
    runner('compat-gating', 'run-compat-gating.py', ['CompatibilityGatingValidation']),
    runner('compat-controls', 'run-compat-gating.py', ['CompatControlsValidation'], '--mode', 'controls'),
    runner('compat-ut5-screens', 'run-compat-gating.py', ['Ut5SimulationScreens'], '--mode', 'ut5-screens'),
    runner('compat-import-name', 'run-compat-gating.py', [], '--mode', 'import-name'),
    runner('compat-owner-profiles', 'run-compat-gating.py', [], '--mode', 'owner-profiles'),
    runner('charge-capture', 'run-charge-capture.py', ['ChargeCaptureValidation']),
    runner('charge-estimates', 'run-charge-capture.py', ['ChargeEstimateValidation'], '--suite', 'estimates'),
    runner('battery-widgets', 'run-battery-widgets.py', ['BatteryWidgetValidation']),
    runner('learned-range', 'run-learned-range.py', ['LearnedRangeValidation']),
    runner('ride-auto-stop', 'run-ride-auto-stop.py', ['RideAutoStopValidation']),
    runner('ride-backups', 'run-ride-backups.py', ['RideBackupValidation']),
    runner('rides', 'run-rides.py', ['RidesAnalysisValidation']),
    runner('live-stats', 'run-live-stats.py', ['LiveStatsValidation']),
    runner('responsive', 'run-responsive.py', ['ResponsiveDashboardValidation']),
    # The default matrix leaves out the modes whose checks run nowhere else.
    runner('responsive-modes', 'run-responsive.py', [], *[arg for mode in ('chart', 'followup', 'gauge', 'quick', 'motion')
                                                          for size in ('344x882', '725x604') for arg in ('--case', f'{size}:1.0:{mode}')]),
    runner('motion-polish', 'run-motion-polish.py', ['MotionPolishValidation']),
    runner('haptics', 'run-haptics.py', ['QuickHapticsValidation', 'ConnectionHapticsValidation']),
]
for size, theme, text in (PHONE, WIDE):
    w = size.split('x')[0]
    STEPS += [inst(f'charge-schedule-{w}', T + 'ChargeScheduleValidation', '-e', 'theme', theme, '-e', 'text_scale', text, size=size),
              inst(f'my-scooters-{w}', T + 'MyScootersValidation', '-e', 'theme', theme, '-e', 'text_scale', text, size=size),
              inst(f'developer-settings-{w}', T + 'DeveloperSettingsValidation', '-e', 'theme', theme, size=size),
              inst(f'dashboard-offline-lock-{w}', T + 'DashboardValidation', '-e', 'mode', 'offline-lock', '-e', 'theme', theme, size=size)]
STEPS += [
    inst('dashboard', T + 'DashboardValidation'),
    inst('dashboard-layout-slots', T + 'DashboardValidation', '-e', 'mode', 'layout-slots'),
    inst('dashboard-home-widgets', T + 'DashboardValidation', '-e', 'mode', 'home-widgets'),
    inst('dashboard-slots', T + 'DashboardSlotsValidation'),
    inst('capacity-widget', T + 'CapacityWidgetValidation'),
    inst('charge-schedule-wire', PKG + '.ChargeScheduleWireValidation', hidden_api=True),
    inst('first-connection', T + 'FirstConnectionValidation', hidden_api=True),
    inst('launch-denied', T + 'LaunchValidation', '-e', 'mode', 'denied', grant=()),
    inst('launch-granted', T + 'LaunchValidation', '-e', 'mode', 'granted'),
    inst('phone-ride-denied', T + 'PhoneRideValidation', '-e', 'mode', 'denied', grant=NEARBY),
    inst('phone-ride-granted', T + 'PhoneRideValidation', '-e', 'mode', 'granted'),
]
for theme in ('dark', 'light'):
    STEPS += [inst(f'report-invite-{theme}', T + 'ReportInviteValidation', '-e', 'theme', theme),
              inst(f'report-shortcuts-{theme}', T + 'ReportShortcutsValidation', '-e', 'theme', theme),
              inst(f'owner-model-{theme}', T + 'OwnerModelValidation', '-e', 'theme', theme),
              inst(f'model-home-{theme}', T + 'ModelHomeValidation', '-e', 'theme', theme)]
STEPS += [inst('more-menu-phone', T + 'MoreMenuValidation', '-e', 'wide', 'false', '-e', 'theme', 'dark', size='344x882'),
          inst('more-menu-wide', T + 'MoreMenuValidation', '-e', 'wide', 'true', '-e', 'theme', 'light', size='850x880')]
STEPS += [inst(name, cls) for name, cls in (
    ('map-tiles', PKG + '.MapTilesValidation'), ('history', T + 'HistoryValidation'),
    ('route-gesture', T + 'RouteGestureValidation'), ('search-focus', T + 'SearchFocusValidation'),
    ('ui-organization', T + 'UiOrganizationValidation'), ('update-check', T + 'UpdateCheckValidation'))]


def registered():
    root = ElementTree.parse(HERE / 'AndroidManifest.xml').getroot()
    return sorted(element.get(ANDROID + 'name').rsplit('.', 1)[1] for element in root.iter('instrumentation'))


def uncovered():
    covered = {c for step in STEPS for c in step['covers']}
    return [name for name in registered() if name not in covered and name not in EXCLUDED]


parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--adb')
parser.add_argument('--serial')
parser.add_argument('--app', type=Path)
parser.add_argument('--runner', type=Path)
parser.add_argument('--out', type=Path)
parser.add_argument('--only', action='append', help='step name (repeatable)')
parser.add_argument('--list', action='store_true', help='print the steps and exit')
args = parser.parse_args()
missing = uncovered()
if missing:
    raise SystemExit('Not covered by the release sweep: ' + ', '.join(missing) + '. Add a step for each.')
if args.list:
    for step in STEPS: print(step['name'])
    for name, why in EXCLUDED.items(): print(f'(excluded) {name}: {why}')
    for name, where in SUBSUMED.items(): print(f'(subsumed) {name}: by {where}')
    raise SystemExit(0)
for flag in ('adb', 'serial', 'app', 'runner', 'out'):
    if getattr(args, flag) is None: parser.error(f'--{flag} is required')
if not re.fullmatch(r'emulator-\d+', args.serial):
    parser.error('An explicit emulator serial is required; physical devices are refused')
names = {step['name'] for step in STEPS}
for name in args.only or []:
    if name not in names: parser.error(f'Unknown step {name}; see --list')
prefix = [args.adb, '-s', args.serial]


def adb(*values, check=True, timeout=240):
    result = subprocess.run(prefix + list(values), text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=timeout)
    if check and result.returncode: raise RuntimeError(result.stdout)
    return result.stdout


if adb('shell', 'getprop', 'ro.kernel.qemu').strip() != '1' or adb('shell', 'getprop', 'sys.boot_completed').strip() != '1':
    raise SystemExit('A booted disposable emulator is required')
args.out.mkdir(parents=True, exist_ok=True)


def fresh(grant):
    for package in (PKG + '.tests', PKG): adb('uninstall', package, check=False)
    adb('install', '--no-incremental', str(args.app.resolve())); adb('install', '--no-incremental', str(args.runner.resolve()))
    for permission in grant: adb('shell', 'pm', 'grant', PKG, 'android.permission.' + permission, check=False)


def run_step(step):
    if step['kind'] == 'runner':
        command = [sys.executable, '-I', str(HERE / step['script']), '--adb', args.adb, '--serial', args.serial,
                   '--app', str(args.app), '--runner', str(args.runner), '--out', str(args.out / step['name'])] + step['extra']
        result = subprocess.run(command, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=7200)
        return result.returncode == 0, result.stdout
    if step['size']:
        w, h = map(int, step['size'].split('x'))
        adb('shell', 'wm', 'density', '480'); adb('shell', 'wm', 'size', f'{w * 3}x{h * 3}')
    if step['hidden_api']: adb('shell', 'settings', 'put', 'global', 'hidden_api_policy', '1')
    try:
        fresh(step['grant'])
        output = adb('shell', 'am', 'instrument', '-w', '-r', *step['extra'], f'{PKG}.tests/{step["cls"]}', check=False, timeout=1800)
    finally:
        # Screenshots live in the app's files; keep them (failures too) before the next install removes them.
        destination = args.out / step['name']; destination.mkdir(parents=True, exist_ok=True)
        adb('pull', f'/sdcard/Android/data/{PKG}/files', str(destination), check=False, timeout=600)
        if step['hidden_api']: adb('shell', 'settings', 'delete', 'global', 'hidden_api_policy', check=False)
        if step['size']: adb('shell', 'wm', 'size', 'reset', check=False); adb('shell', 'wm', 'density', 'reset', check=False)
    passed = 'result=PASS' in output and 'result=FAIL' not in output and 'INSTRUMENTATION_FAILED' not in output and 'Process crashed' not in output
    return passed, output


adb('shell', 'cmd', 'bluetooth_manager', 'disable')
adb('shell', 'settings', 'delete', 'global', 'hidden_api_policy', check=False)
results = []
try:
    for step in STEPS:
        if args.only and step['name'] not in args.only: continue
        started = time.monotonic()
        try: passed, output = run_step(step)
        except Exception as error: passed, output = False, f'{type(error).__name__}: {error}'
        (args.out / f'{step["name"]}.txt').write_text(output)
        detail = ' '.join(re.findall(r'(?:assertions|checks|check_count)=\d+', output)[:4])
        error = re.search(r'error=(.{0,240})', output)
        results.append(dict(step=step['name'], passed=passed, seconds=round(time.monotonic() - started)))
        print(f'{step["name"]}: {"PASS" if passed else "FAIL"} {detail}' + ('' if passed or not error else f' :: {error.group(1)}'), flush=True)
finally:
    for package in (PKG + '.tests', PKG): adb('uninstall', package, check=False)
    adb('shell', 'wm', 'size', 'reset', check=False); adb('shell', 'wm', 'density', 'reset', check=False)
    adb('shell', 'settings', 'put', 'system', 'font_scale', '1.0', check=False)
    (args.out / 'summary.json').write_text(json.dumps(results, indent=2) + '\n')
failed = [r['step'] for r in results if not r['passed']]
print(f'{len(results) - len(failed)}/{len(results)} steps passed' + (': failed ' + ', '.join(failed) if failed else ''))
raise SystemExit(1 if failed else 0)
