#!/usr/bin/env python3
"""Run the actual safety guard against local command fixtures, never a phone."""
import json
import os
import pathlib
import signal
import subprocess
import tempfile
import time
import unittest

SOURCE = pathlib.Path(__file__).resolve().parents[1] / 'setup' / 'owner-capture-guard.sh'
TOKEN = 'a' * 64
FIXTURE = r'''#!/usr/bin/env python3
import json, os, pathlib, subprocess, sys, time
name = pathlib.Path(sys.argv[0]).name
state = pathlib.Path(os.environ['GUARD_STATE'])
if name == 'timeout':
    try: sys.exit(subprocess.run(sys.argv[2:], timeout=float(sys.argv[1])).returncode)
    except subprocess.TimeoutExpired: sys.exit(124)
if name == 'setprop':
    if sys.argv[2] == 'VERBOSE': time.sleep(float(os.environ.get('GUARD_SET_DELAY', '0')))
    state.joinpath('property').write_text(sys.argv[2])
if name == 'getprop':
    if os.environ.get('GUARD_FAIL_READ') == '1': sys.exit(1)
    print(state.joinpath('property').read_text())
with state.joinpath('events').open('a') as out: out.write(json.dumps([name] + sys.argv[1:]) + '\n')
if name == 'am' and os.environ.get('GUARD_FAIL_STOP') == '1': sys.exit(1)
'''

class GuardTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='volt-guard-test-')
        self.root = pathlib.Path(self.temp.name)
        self.lock = self.root / 'lock'
        self.lock.mkdir(mode=0o700)
        for name, value in {'token': TOKEN, 'owner': 'shizuku', 'original': '  INFO  ', 'started': '1800000000.123'}.items():
            self.lock.joinpath(name).write_text(value)
        self.root.joinpath('property').write_text('  INFO  ')
        self.root.joinpath('events').touch()
        self.bin = self.root / 'bin'; self.bin.mkdir()
        for name in ('setprop', 'getprop', 'am', 'monkey', 'timeout'):
            path = self.bin / name; path.write_text(FIXTURE); path.chmod(0o700)
        self.script = self.root / 'guard.sh'
        # Only the sandbox path and watchdog duration differ from the shipping script.
        source = SOURCE.read_text().replace('/data/local/tmp/volt-owner-capture.lock', str(self.lock))
        source = source.replace('while [ "$i" -lt 120 ]; do', 'while [ "$i" -lt 2 ]; do', 1)
        self.script.write_text(source)
        self.env = dict(os.environ, PATH=str(self.bin) + os.pathsep + os.environ['PATH'], GUARD_STATE=str(self.root))
        self.children = []
    def tearDown(self):
        for child in self.children:
            if child.poll() is None: child.terminate()
            try: child.wait(timeout=3)
            except subprocess.TimeoutExpired: child.kill(); child.wait()
        self.temp.cleanup()
    def call(self, action, token=TOKEN, **env):
        return subprocess.run(['/bin/sh', str(self.script), action, token, 'com.navee.ucaret/.MainActivity'], env=dict(self.env, **env),
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=12)
    def spawn(self, action, **env):
        child = subprocess.Popen(['/bin/sh', str(self.script), action, TOKEN, 'com.navee.ucaret/.MainActivity'], env=dict(self.env, **env),
                                 stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        self.children.append(child); return child
    def wait_file(self, name):
        deadline = time.monotonic() + 3
        while time.monotonic() < deadline:
            if self.lock.joinpath(name).exists(): return
            time.sleep(.025)
        self.fail('Missing ' + name)
    def events(self): return [json.loads(line) for line in self.root.joinpath('events').read_text().splitlines()]
    def test_start_needs_armed_guard(self):
        self.assertNotEqual(0, self.call('start').returncode)
        self.assertEqual('  INFO  ', self.root.joinpath('property').read_text())
    def test_capture_and_exact_restore(self):
        guard = self.spawn('watch'); self.wait_file('ready')
        self.assertEqual(0, self.call('start').returncode)
        self.assertEqual('VERBOSE', self.root.joinpath('property').read_text())
        self.assertEqual(0, self.call('clean').returncode)
        guard.wait(timeout=3)
        self.assertEqual('done', self.lock.joinpath('guard_done').read_text())
        self.assertEqual('  INFO  ', self.root.joinpath('property').read_text())
        self.assertFalse(self.lock.joinpath('cleaning').exists())
    def test_stopped_or_expired_guard_blocks_late_start(self):
        guard = self.spawn('watch'); self.wait_file('ready'); guard.wait(timeout=5)
        self.assertNotEqual(0, self.call('start').returncode)
        self.assertEqual('  INFO  ', self.root.joinpath('property').read_text())
    def test_wrong_owner_or_token_never_changes_property(self):
        self.assertNotEqual(0, self.call('clean', 'b' * 64).returncode)
        self.lock.joinpath('owner').write_text('webadb')
        self.assertNotEqual(0, self.call('clean').returncode)
        self.assertEqual([], self.events())
    def test_cancel_during_slow_start_serializes_cleanup(self):
        guard = self.spawn('watch'); self.wait_file('ready')
        start = self.spawn('start', GUARD_SET_DELAY='.6'); self.wait_file('cleaning')
        time.sleep(.1)
        start.send_signal(signal.SIGTERM)
        self.assertEqual(0, self.call('clean').returncode)
        start.wait(timeout=3); guard.wait(timeout=3)
        self.assertNotEqual(0, start.returncode)
        self.assertEqual('  INFO  ', self.root.joinpath('property').read_text())
        self.assertFalse(any(event[:2] == ['am', 'start'] for event in self.events()))
        self.assertEqual(['setprop', 'log.tag.com.navee.ucaret', '  INFO  '],
                         [event for event in self.events() if event[0] == 'setprop'][-1])
    def test_force_stop_failure_is_not_cleanup_success(self):
        self.lock.joinpath('ready').write_text('ready')
        before = time.monotonic()
        self.assertNotEqual(0, self.call('clean', GUARD_FAIL_STOP='1').returncode)
        self.assertFalse(self.lock.joinpath('status').exists())
        self.assertLess(time.monotonic() - before, 3)
    def test_failed_watchdog_can_be_recovered(self):
        guard = self.spawn('watch', GUARD_FAIL_STOP='1'); self.wait_file('ready')
        self.assertEqual(0, self.call('start').returncode)
        guard.wait(timeout=5)
        self.assertEqual('stopped', self.lock.joinpath('guard_done').read_text())
        self.assertFalse(self.lock.joinpath('status').exists())
        self.assertEqual(0, self.call('clean').returncode)
        self.assertEqual('done', self.lock.joinpath('status').read_text())
        self.assertEqual('  INFO  ', self.root.joinpath('property').read_text())
    def test_empty_property_failed_readback_is_not_success(self):
        self.lock.joinpath('original').write_text('')
        self.assertNotEqual(0, self.call('clean', GUARD_FAIL_READ='1').returncode)
        self.assertFalse(self.lock.joinpath('status').exists())
    def test_watchdog_survives_missing_client(self):
        guard = self.spawn('watch'); self.wait_file('ready')
        self.assertEqual(0, self.call('start').returncode)
        guard.wait(timeout=5)
        self.assertEqual('done', self.lock.joinpath('status').read_text())
        self.assertEqual('  INFO  ', self.root.joinpath('property').read_text())

if __name__ == '__main__': unittest.main()
