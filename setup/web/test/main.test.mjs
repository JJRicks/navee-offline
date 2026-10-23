// Exercise the production UI event handlers with a small DOM and transport/session
// doubles. No real browser USB permission, phone command, or owner file is used.
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { runInNewContext } from 'node:vm';
import { webcrypto } from 'node:crypto';
import { build } from 'esbuild';
const entry = fileURLToPath(new URL('../src/main.js', import.meta.url));
const html = await readFile(new URL('../index.html', import.meta.url), 'utf8');
const bundled = await build({ entryPoints: [entry], bundle: true, write: false, format: 'iife', plugins: [{
  name: 'ui-fixture', setup(build) {
    build.onResolve({ filter: /^\.\/(capture|transport)\.js$/ }, args => ({ path: args.path, namespace: 'fixture' }));
    // The transport double shares the bundle's SetupError, so doubles can raise
    // the same typed errors the real transport and session would.
    build.onLoad({ filter: /./, namespace: 'fixture' }, args => ({ resolveDir: dirname(entry), contents: args.path.includes('capture')
      ? 'export const CaptureSession = window.fixture.Session; export const readJournal = () => window.fixture.pending ? {} : null;'
      : "import { SetupError } from './protocol.js'; window.fixture.SetupError = SetupError; export const connectPhone = (...args) => window.fixture.connect(...args);" }));
  }
}] });
function fixture(mode = 'success', legacy = false) {
  const elements = new Map(), phones = [], sessions = [], events = [], windowEvents = {};
  for (const [, id] of html.matchAll(/id="([^"]+)"/g)) elements.set(id, { id, hidden: false, disabled: false, checked: false, textContent: '', listeners: {},
    addEventListener(name, fn) { this.listeners[name] = fn; }, classList: { toggle() {} }, setAttribute() {}, removeAttribute() {} });
  const fixture = { mode, pending: false, nextSerial: 'same-phone',
    async connect(_waiting, disconnect) {
      if (this.mode === 'busy') throw new this.SetupError('usb-busy', 'Fixture busy: run adb kill-server.');
      const phone = { serial: this.nextSerial, name: 'Fixture', disconnect, closed: false,
        async close(forget) { assert.equal(forget, true); this.closed = true; events.push('close'); this.disconnect(); },
        async run() { events.push('save'); if (fixture.mode === 'transfer-failure') throw new Error('Fixture failure'); return 'SAVED'; }
      }; phones.push(phone); return phone;
    },
    Session: class {
      constructor({ serial }) { this.serial = serial; this.safe = false; this.result = false; this.busy = false; sessions.push(this); }
      async begin() {
        this.safe = fixture.mode !== 'unsafe-failure'; this.result = fixture.mode === 'success'; fixture.pending = !this.safe;
        if (!this.result) throw new Error('Fixture capture failure');
      }
      async recover() {
        if (fixture.mode === 'wrong-phone') throw new fixture.SetupError('device', 'Choose the same phone to recover its capture.');
        this.safe = true; fixture.pending = false;
      }
      document() { assert.equal(this.safe, true); assert.equal(this.result, true); return '{"fixture":true}'; }
      forgetResult() { this.result = false; }
      cancel() {}
    }
  };
  const scope = { fixture, console, location: { hostname: legacy ? 'jjricks.com' : 'localhost', pathname: '/pairscooter/' }, crypto: webcrypto, Blob, TextEncoder, isSecureContext: true,
    navigator: { usb: {} }, localStorage: { getItem() { return null; } },
    URL: { createObjectURL() { events.push('download'); return 'blob:fixture'; }, revokeObjectURL() {} },
    setTimeout: () => 0,
    addEventListener: (name, fn) => { windowEvents[name] = fn; },
    document: { getElementById: id => elements.get(id), querySelectorAll: () => [], createElement: () => ({ click() {} }) }
  };
  scope.window = scope; runInNewContext(bundled.outputFiles[0].text, scope);
  const click = id => elements.get(id).listeners.click();
  const connect = async () => { elements.get('usb-consent').checked = true; await click('connect'); };
  const start = async () => { elements.get('capture-consent').checked = true; await click('start'); };
  return { fixture, elements, phones, sessions, events, windowEvents, click, connect, start };
}
test('verified phone save closes permission immediately and preserves an explicit computer backup', async () => {
  const f = fixture(); await f.connect(); await f.start(); await f.click('send');
  assert.deepEqual(f.events, ['save', 'close']);
  assert.equal(f.elements.get('save-panel').hidden, false);
  assert.equal(f.sessions[0].result, true);
  await f.click('download');
  assert.deepEqual(f.events, ['save', 'close', 'download']);
  await f.click('finish'); assert.equal(f.sessions[0].result, false);
});
test('backup request closes USB and saving to the same phone can explicitly reconnect', async () => {
  const f = fixture(); await f.connect(); await f.start(); await f.click('download');
  assert.deepEqual(f.events, ['download', 'close']);
  assert.match(f.elements.get('send').textContent, /Choose phone/);
  await f.click('send');
  assert.equal(f.phones.length, 2); assert.equal(f.phones[1].closed, true);
  assert.deepEqual(f.events, ['download', 'close', 'save', 'close']);
  assert.equal(f.sessions.length, 1);
});
test('reconnection to a different phone is closed before any file write', async () => {
  const f = fixture(); await f.connect(); await f.start(); await f.click('download');
  f.fixture.nextSerial = 'other-phone'; await f.click('send');
  assert.deepEqual(f.events, ['download', 'close', 'close']);
  assert.match(f.elements.get('error').textContent, /same phone/);
  assert.equal(f.sessions[0].result, true);
});
test('verified timeout closes USB and resets consent for a fresh attempt', async () => {
  const f = fixture('safe-failure'); await f.connect(); await f.start();
  assert.equal(f.phones[0].closed, true);
  assert.equal(f.elements.get('connect-panel').hidden, false);
  assert.equal(f.elements.get('usb-consent').checked, false);
  f.fixture.mode = 'success'; await f.connect(); await f.start();
  assert.equal(f.elements.get('save-panel').hidden, false);
});
test('unverified cleanup keeps recovery access, then closes USB after verified recovery', async () => {
  const f = fixture('unsafe-failure'); await f.connect(); await f.start();
  assert.equal(f.phones[0].closed, false);
  assert.equal(f.elements.get('recovery').hidden, false);
  await f.click('recover');
  assert.equal(f.phones[0].closed, true);
  assert.equal(f.elements.get('connect-panel').hidden, false);
  assert.equal(f.fixture.pending, false);
});
test('unverified file transfer closes USB, retains the verified capture and allows retry', async () => {
  const f = fixture(); await f.connect(); await f.start(); f.fixture.mode = 'transfer-failure';
  await f.click('send');
  assert.equal(f.phones[0].closed, true); assert.equal(f.sessions[0].result, true);
  assert.match(f.elements.get('error').textContent, /could not be verified/);
  f.fixture.mode = 'success'; await f.click('send');
  assert.equal(f.phones.length, 2); assert.equal(f.phones[1].closed, true);
});
test('a busy USB interface shows its computer-side fix and opens connection help', async () => {
  const f = fixture('busy'); await f.connect();
  assert.equal(f.phones.length, 0);
  assert.match(f.elements.get('error').textContent, /adb kill-server/);
  assert.equal(f.elements.get('connection-help').open, true);
  assert.equal(f.elements.get('connect-panel').hidden, false);
});
test('recovery releases a different phone so the next attempt can choose again', async () => {
  const f = fixture('wrong-phone'); f.fixture.pending = true;
  await f.click('recover');
  assert.equal(f.phones[0].closed, true);
  assert.match(f.elements.get('error').textContent, /same phone/);
  assert.equal(f.fixture.pending, true);
  f.fixture.mode = 'success'; await f.click('recover');
  assert.equal(f.phones.length, 2); assert.equal(f.phones[1].closed, true);
  assert.equal(f.fixture.pending, false);
});

test('legacy origin blocks new capture but preserves same-browser cleanup recovery', async () => {
  const f = fixture('success', true);
  assert.equal(f.elements.get('legacy-site').hidden, false);
  await f.connect();
  assert.equal(f.phones.length, 0);
  assert.equal(f.elements.get('connect').disabled, true);
  f.fixture.pending = true;
  await f.click('recover');
  assert.equal(f.phones.length, 1);
  assert.equal(f.phones[0].closed, true);
  assert.equal(f.fixture.pending, false);
  assert.equal(f.elements.get('connect').disabled, true);
});
