import test from 'node:test';
import assert from 'node:assert/strict';
import { CaptureSession, JOURNAL_KEY, readJournal } from '../src/capture.js';
import { SetupError } from '../src/protocol.js';
import { row } from './fixtures.mjs';

const token = 'a'.repeat(64);
function store() {
  const data = new Map();
  return { getItem: k => data.get(k) ?? null, setItem: (k, v) => data.set(k, v), removeItem: k => data.delete(k) };
}
function fixture({ fail = '', original = '', logs = row(), clockStep = 0, after = () => {}, storage = store(), serial = 'test-phone', user = '0', userList = 'Users:\n\tUserInfo{0:Owner:13} running', otherPackage = '', launcher = 'com.navee.ucaret/.MainActivity', uidRow = 'package:com.navee.ucaret uid:10123' } = {}) {
  const calls = [], phases = []; let locked = false, restored = false, time = 0, captured = false;
  const run = async (command, opts = {}) => {
    let op;
    if (command === 'am get-current-user') op = 'user';
    else if (command === 'cmd user list --all') op = 'users';
    else if (command.startsWith('cmd package resolve-activity')) op = 'launcher';
    else if (command.startsWith('pm list') && !command.includes('--user 0 ')) op = 'other-user';
    else if (command.includes('printf RECOVERABLE')) op = 'reacquire';
    else if (command.startsWith('pm list')) op = 'uid';
    else if (command.startsWith('umask')) op = 'prepare';
    else if (command.includes('nohup sh')) op = 'arm';
    else if (command.includes('printf STARTED')) op = 'start';
    else if (command.startsWith('logcat')) op = 'logs';
    else if (command === 'sh -s clean') op = 'cleanup';
    else if (command.startsWith('getprop')) op = 'readback';
    else if (command.includes('printf RELEASED')) op = 'release';
    else if (command.includes('printf ABSENT')) op = 'exists';
    else if (command.includes('printf PENDING')) op = 'inspect';
    else throw new Error('Unexpected command');
    calls.push({ op, command, opts });
    if (op === 'prepare') locked = true;
    if (fail === op) throw new SetupError('injected', 'Injected test failure');
    after(op);
    switch (op) {
      case 'uid': return uidRow;
      case 'user': return user;
      case 'users': return userList;
      case 'other-user': return otherPackage;
      case 'launcher': return launcher;
      case 'reacquire': assert(!locked); locked = true; return 'RECOVERABLE';
      case 'prepare': return `1800000000.000\n${btoa(original)}`;
      case 'arm': assert.equal(readJournal(storage).stage, 'armed'); return 'READY';
      case 'start': assert(calls.some(x => x.op === 'arm')); return 'STARTED';
      case 'logs': captured = true; time += clockStep; return logs;
      case 'cleanup': restored = true; return '';
      case 'readback': return original;
      case 'release': assert(restored); locked = false; return 'RELEASED';
      case 'inspect': if (!locked) throw new Error('Missing lock'); return `${btoa(original)}\nPENDING`;
      case 'exists': return locked ? 'PRESENT' : 'ABSENT';
    }
  };
  const session = new CaptureSession({ run, serial, storage, token: () => token, update: s => phases.push(s), now: () => time, delay: async ms => { time += ms; } });
  return { session, calls, phases, storage, captured: () => captured };
}
test('successful capture cannot export until exact restoration and lock release', async () => {
  const f = fixture({ original: '  DEBUG  ' });
  assert.throws(() => f.session.document(), { code: 'unsafe-export' });
  await f.session.begin();
  assert.equal(JSON.parse(f.session.document()).owner_identity, '010203040506');
  assert.equal(f.storage.getItem(JOURNAL_KEY), null);
  assert.deepEqual(f.calls.map(x => x.op), ['user', 'users', 'uid', 'launcher', 'prepare', 'arm', 'start', 'logs', 'exists', 'inspect', 'cleanup', 'readback', 'release']);
  const capture = f.calls.find(x => x.op === 'logs').command;
  assert.match(capture, /--uid=10123/); assert.match(capture, /-e 'Send authData:'/); assert.match(capture, /com.navee.ucaret:D '\*:S'/);
});
for (const fail of ['uid', 'prepare', 'arm', 'start', 'logs']) test(`failure at ${fail} still performs cleanup and never exports`, async () => {
  const f = fixture({ fail });
  await assert.rejects(f.session.begin());
  assert.throws(() => f.session.document(), { code: 'unsafe-export' });
  if (fail !== 'uid') assert(f.calls.some(x => x.op === 'cleanup'));
});
for (const fail of ['inspect', 'cleanup', 'readback', 'release']) test(`unverified ${fail} retains recovery metadata without owner identity`, async () => {
  const f = fixture({ fail }); await assert.rejects(f.session.begin(), { code: 'cleanup' });
  const persisted = f.storage.getItem(JOURNAL_KEY);
  assert(persisted); assert(!persisted.includes('010203040506')); assert(!persisted.includes('owner_identity'));
  assert.throws(() => f.session.document(), { code: 'unsafe-export' });
});
test('cancellation after possible mutation restores and discards a captured result', async () => {
  let f; f = fixture({ after: op => { if (op === 'start') f.session.cancel(); } });
  await assert.rejects(f.session.begin(), { code: 'cancelled' });
  assert(f.calls.some(x => x.op === 'cleanup')); assert(!f.captured()); assert.equal(f.session.result, null);
});
test('no fresh frame times out at 90 seconds and cleans up', async () => {
  const f = fixture({ logs: '', clockStep: 30_000 }); await assert.rejects(f.session.begin(), { code: 'empty' });
  assert.equal(f.calls.filter(x => x.op === 'logs').length, 3); assert(f.calls.some(x => x.op === 'cleanup'));
});
test('conflicting owner data cannot be exported even when cleanup succeeds', async () => {
  const f = fixture({ logs: `${row()}\n${row('020304050607')}` });
  await assert.rejects(f.session.begin(), { code: 'conflict' }); assert.equal(f.session.result, null);
});
test('blocked persistent storage fails before any phone operation', async () => {
  const storage = store(); storage.setItem = () => { throw new Error('storage denied'); };
  const f = fixture({ storage }); await assert.rejects(f.session.begin()); assert.equal(f.calls.length, 0);
});
test('wrong Android package / ambiguous package UID fails before changing logging', async () => {
  for (const uidRow of ['package:com.navee.ucaret.fake uid:10123', 'package:com.navee.ucaret uid:0', 'package:com.navee.ucaret uid:1010123', 'package:com.navee.ucaret uid:10123\npackage:other uid:10124']) {
    const f = fixture({ uidRow }); await assert.rejects(f.session.begin(), { code: 'package' });
    assert(!f.calls.some(x => x.op === 'prepare'));
  }
});
test('secondary Android users are rejected before any logging change', async () => {
  const f = fixture({ user: '10' }); await assert.rejects(f.session.begin(), { code: 'profile' });
  assert(!f.calls.some(x => x.op === 'prepare'));
});
test('NAVEE in another user is rejected, without mutating or retaining profile names', async () => {
  const f = fixture({ userList: 'Users:\n UserInfo{0:Owner:13} running\n UserInfo{10:Private name:10}', otherPackage: 'package:com.navee.ucaret uid:1010123' });
  await assert.rejects(f.session.begin(), { code: 'profile' });
  assert(!f.calls.some(x => x.op === 'prepare')); assert.equal(f.storage.getItem(JOURNAL_KEY), null);
});
test('another user without NAVEE is allowed, but malformed enumeration fails closed', async () => {
  const f = fixture({ userList: 'Users:\n UserInfo{0:Owner:13} running\n UserInfo{10:Work:10}' });
  await f.session.begin(); assert(f.session.document());
  for (const userList of ['Unknown output', 'Users:\n UserInfo{0:A:13}\n UserInfo{0:B:10}', 'Users:\n UserInfo{10:A:10}', 'Users:\n UserInfo{0:A:13}\n junk']) {
    const broken = fixture({ userList }); await assert.rejects(broken.session.begin(), { code: 'profile' });
    assert(!broken.calls.some(x => x.op === 'prepare'));
  }
});
test('a wrong package or injected launcher is rejected before mutation', async () => {
  for (const launcher of ['other.package/.Activity', 'com.navee.ucaret/.Main; touch /tmp/pwn', 'No activity found', 'com.navee.ucaret/.Main\nextra']) {
    const f = fixture({ launcher }); await assert.rejects(f.session.begin(), { code: 'launcher' });
    assert(!f.calls.some(x => x.op === 'prepare'));
  }
});
test('damaged journal and different phone never receive restore commands', async () => {
  const f = fixture(); f.storage.setItem(JOURNAL_KEY, '{damaged');
  await assert.rejects(f.session.recover(), { code: 'journal' }); assert.equal(f.calls.length, 0);
  f.storage.setItem(JOURNAL_KEY, JSON.stringify({ version: 1, token, serial: 'other-phone', original: '', stage: 'armed' }));
  await assert.rejects(f.session.recover(), { code: 'device' }); assert.equal(f.calls.length, 0);
});
test('pending journal blocks a second capture and export from another tab', async () => {
  const f = fixture(); await f.session.begin();
  f.storage.setItem(JOURNAL_KEY, JSON.stringify({ version: 1, token, serial: 'test-phone', original: '', stage: 'armed' }));
  assert.throws(() => f.session.document(), { code: 'unsafe-export' });
  await assert.rejects(f.session.begin(), { code: 'recovery' });
});
test('a cancellation arriving during cleanup still discards the candidate', async () => {
  let f; f = fixture({ after: op => { if (op === 'cleanup') f.session.cancel(); } });
  await assert.rejects(f.session.begin(), { code: 'cancelled' });
  assert.equal(f.storage.getItem(JOURNAL_KEY), null);
  assert.throws(() => f.session.document(), { code: 'unsafe-export' });
});
test('lost final release reply reacquires exclusively and repeats verified restoration', async () => {
  const f = fixture({ original: 'WARN' }), normal = f.session.run;
  let lost = false;
  f.session.run = async (...args) => {
    const response = await normal(...args);
    if (response === 'RELEASED' && !lost) { lost = true; throw new Error('USB reply lost'); }
    return response;
  };
  await assert.rejects(f.session.begin(), { code: 'cleanup' });
  assert(f.storage.getItem(JOURNAL_KEY));
  await f.session.recover();
  assert(f.calls.some(x => x.op === 'reacquire'));
  assert.equal(f.calls.filter(x => x.op === 'cleanup').length, 2);
  assert.equal(f.storage.getItem(JOURNAL_KEY), null);
  assert.throws(() => f.session.document(), { code: 'unsafe-export' });
});
