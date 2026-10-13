import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, writeFile, readFile, rm, access, readdir } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawn } from 'node:child_process';
import { LOCK, armCommand, cleanupCommand, guardScript, prepareCommand, releaseCommand, startCommand as makeStart } from '../src/guard.js';

const token = 'a'.repeat(64), other = 'b'.repeat(64);
const startCommand = value => makeStart(value, 'com.navee.ucaret/.FixtureActivity');
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
async function fixture(original = '') {
  const root = await mkdtemp(join(tmpdir(), 'volt-guard-test-')), bin = join(root, 'bin'), lock = join(root, 'lock');
  await mkdir(bin);
  await writeFile(join(root, 'property'), original);
  const scripts = {
    id: 'printf 2000',
    pm: 'printf "package:/fake/navee.apk\\n"',
    date: 'printf "1800000000.125\\n"',
    getprop: '[ ! -f "$FIXTURE/fail-readback" ] || exit 1; cat "$FIXTURE/property"; printf "\\n"',
    setprop: 'printf "%s\\n" "setprop:$2" >> "$FIXTURE/events"; [ ! -f "$FIXTURE/fail-restore" ] || [ "$2" = VERBOSE ] || exit 1; printf %s "$2" > "$FIXTURE/property"',
    am: 'if [ "$1" = get-current-user ]; then printf 0; exit; fi; if [ "$1" = start ]; then printf "launch\\n" >> "$FIXTURE/events"; else printf "am\\n" >> "$FIXTURE/events"; fi; [ ! -f "$FIXTURE/slow-am" ] || /bin/sleep 0.6; [ ! -f "$FIXTURE/fail-am" ]',
    monkey: 'printf "monkey\\n" >> "$FIXTURE/events"',
    rm: 'if [ -f "$FIXTURE/fail-retired-delete" ]; then case "$*" in *retired-*) exit 1 ;; esac; fi; exec /bin/rm "$@"',
    timeout: 'shift; exec "$@"'
  };
  for (const [name, script] of Object.entries(scripts)) await writeFile(join(bin, name), `#!/bin/sh\n${script}\n`, { mode: 0o700 });
  const env = { ...process.env, PATH: `${bin}:${process.env.PATH}`, FIXTURE: root };
  const adapt = program => program.replaceAll(LOCK, lock).replaceAll('/data/local/tmp/volt-owner-retired-', `${root}/retired-`).replace('sleep 1\n', 'sleep 0.01\n');
  function execute(command, input, args = []) {
    const child = spawn('/bin/sh', [...args, '-c', adapt(command)], { env, stdio: ['pipe', 'pipe', 'pipe'] });
    const stdout = [], stderr = [];
    child.stdout.on('data', data => stdout.push(data)); child.stderr.on('data', data => stderr.push(data));
    child.stdin.on('error', () => {}); child.stdin.end(input ? adapt(input) : undefined);
    const done = new Promise(resolve => child.on('close', (code, signal) => resolve({ code, signal, stdout: Buffer.concat(stdout).toString(), stderr: Buffer.concat(stderr).toString() })));
    return { child, done };
  }
  const run = (command, input) => execute(command, input).done;
  async function prepare() { const result = await run(prepareCommand(token)); assert.equal(result.code, 0, result.stderr); }
  async function arm(quick = false) {
    // Deterministically fire the watchdog as soon as start owns its gate;
    // fixed millisecond sleeps otherwise make the race flaky on fast CI hosts.
    const script = quick ? guardScript(token).replace('while [ "$i" -lt 120 ]; do',
      'while [ "$i" -lt 120 ] && [ ! -d "$d/cleaning" ]; do') : guardScript(token);
    const result = await run(armCommand(token), script); assert.equal(result.code, 0, result.stderr); assert.equal(result.stdout, 'READY');
  }
  return { root, lock, run, execute, prepare, arm,
    property: () => readFile(join(root, 'property'), 'utf8'),
    events: () => readFile(join(root, 'events'), 'utf8'),
    dispose: () => rm(root, { recursive: true, force: true }) };
}
test('real shell commands preserve an unusual printable original and exclude concurrent helpers', async () => {
  const f = await fixture("  INFO';$HOME  ");
  try {
    await f.prepare();
    const second = await f.run(prepareCommand(other)); assert.equal(second.code, 23);
    assert.equal(await readFile(join(f.lock, 'original'), 'utf8'), "  INFO';$HOME  ");
    await f.arm();
    assert.equal((await f.run(startCommand(token))).code, 0);
    assert.equal(await f.property(), 'VERBOSE');
    assert.equal((await f.run('sh -s clean', cleanupCommand(token))).code, 0);
    assert.equal(await f.property(), "  INFO';$HOME  ");
    assert.equal((await f.run(releaseCommand(token))).code, 0);
    await assert.rejects(access(f.lock));
  } finally { await f.dispose(); }
});
test('detached watchdog cleans up without the browser sending any cleanup request', async () => {
  const f = await fixture('WARN');
  try {
    await f.prepare(); await f.arm(); await f.run(startCommand(token));
    for (let i = 0; i < 80 && await f.property() !== 'WARN'; i++) await delay(40);
    assert.equal(await f.property(), 'WARN');
    for (let i = 0; i < 30; i++) { try { await access(join(f.lock, 'guard_done')); break; } catch { await delay(20); } }
    assert.match(await readFile(join(f.lock, 'guard_done'), 'utf8'), /^(done|stopped)$/);
    assert.equal((await f.run(releaseCommand(token))).code, 0);
  } finally { await f.dispose(); }
});
test('watchdog racing an in-flight start restores only after the last possible launch', async () => {
  const f = await fixture('INFO');
  try {
    await f.prepare(); await writeFile(join(f.root, 'slow-am'), '1');
    // Speed the watch loop while start's am takes 600ms.
    await f.arm(true);
    const started = await f.run(startCommand(token));
    assert.equal(started.code, 0);
    for (let i = 0; i < 30 && await f.property() !== 'INFO'; i++) await delay(40);
    assert.equal(await f.property(), 'INFO');
    const events = await f.events();
    assert(events.lastIndexOf('setprop:INFO') > events.lastIndexOf('launch'));
    assert.equal((await f.run(startCommand(token))).code, 40);
    assert.equal((await f.run(releaseCommand(token))).code, 0);
  } finally { await f.dispose(); }
});
test('a signal during a bounded child ends start without launching later commands', async () => {
  const f = await fixture('ERROR');
  try {
    await f.prepare(); await f.arm(); await writeFile(join(f.root, 'slow-am'), '1');
    const { child, done } = f.execute(startCommand(token));
    for (let i = 0; i < 40; i++) { if ((await f.events().catch(() => '')).includes('am')) break; await delay(5); }
    child.kill('SIGHUP');
    const restored = f.run('sh -s clean', cleanupCommand(token));
    assert.notEqual((await done).code, 0); assert.equal((await restored).code, 0);
    assert(!((await f.events()).includes('launch'))); assert.equal(await f.property(), 'ERROR');
    assert.equal((await f.run(releaseCommand(token))).code, 0);
  } finally { await f.dispose(); }
});
test('stale token cannot start, restore or remove another session', async () => {
  const f = await fixture('DEBUG');
  try {
    await f.prepare(); await f.arm();
    assert.notEqual((await f.run(startCommand(other))).code, 0);
    assert.notEqual((await f.run('sh -s clean', cleanupCommand(other))).code, 0);
    assert.notEqual((await f.run(releaseCommand(other))).code, 0);
    assert.equal(await f.property(), 'DEBUG');
    assert.equal((await f.run('sh -s clean', cleanupCommand(token))).code, 0);
    assert.equal((await f.run(releaseCommand(token))).code, 0);
  } finally { await f.dispose(); }
});
test('failed force-stop or restore never writes a successful cleanup marker', async () => {
  for (const failure of ['fail-am', 'fail-restore']) {
    const f = await fixture('INFO');
    try {
      await f.prepare(); await f.arm(); await f.run(startCommand(token));
      await writeFile(join(f.root, failure), '1');
      assert.notEqual((await f.run('sh -s clean', cleanupCommand(token))).code, 0);
      await assert.rejects(access(join(f.lock, 'status')));
      assert.notEqual((await f.run(releaseCommand(token))).code, 0);
      await rm(join(f.root, failure));
      assert.equal((await f.run('sh -s clean', cleanupCommand(token))).code, 0);
      assert.equal((await f.run(releaseCommand(token))).code, 0);
    } finally { await f.dispose(); }
  }
});
test('failed readback with an empty original cannot falsely confirm restoration', async () => {
  const f = await fixture('');
  try {
    await f.prepare(); await f.arm(); await f.run(startCommand(token));
    await writeFile(join(f.root, 'fail-readback'), '1');
    assert.notEqual((await f.run('sh -s clean', cleanupCommand(token))).code, 0);
    await assert.rejects(access(join(f.lock, 'status')));
    await rm(join(f.root, 'fail-readback'));
    assert.equal((await f.run('sh -s clean', cleanupCommand(token))).code, 0);
    assert.equal((await f.run(releaseCommand(token))).code, 0);
  } finally { await f.dispose(); }
});
test('unexpected lock files block release without deleting the recovery keys', async () => {
  const f = await fixture('WARN');
  try {
    await f.prepare(); await f.arm(); await f.run('sh -s clean', cleanupCommand(token));
    await writeFile(join(f.lock, 'unknown'), 'preserve');
    assert.equal((await f.run(releaseCommand(token))).code, 63);
    assert.equal(await readFile(join(f.lock, 'token'), 'utf8'), token);
    assert.equal(await readFile(join(f.lock, 'original'), 'utf8'), 'WARN');
    await rm(join(f.lock, 'unknown')); assert.equal((await f.run(releaseCommand(token))).code, 0);
  } finally { await f.dispose(); }
});
test('interrupted retired-file deletion cannot leave an unidentifiable global lock', async () => {
  const f = await fixture('INFO');
  try {
    await f.prepare(); await f.arm(); await f.run('sh -s clean', cleanupCommand(token));
    await writeFile(join(f.root, 'fail-retired-delete'), '1');
    assert.equal((await f.run(releaseCommand(token))).code, 65);
    await assert.rejects(access(f.lock));
    const retired = (await readdir(f.root)).find(name => name.startsWith('retired-'));
    assert(retired);
    assert.equal(await readFile(join(f.root, retired, 'session', 'original'), 'utf8'), 'INFO');
  } finally { await f.dispose(); }
});
