import test from 'node:test';
import assert from 'node:assert/strict';
import { closeConnection, runCommand } from '../src/transport.js';

const deferred = () => { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; };
function processFixture(exit = 0) {
  const stream = () => new ReadableStream({ start(controller) { controller.close(); } });
  return { stdout: stream(), stderr: stream(), stdin: new WritableStream(), exited: Promise.resolve(exit), kill() {} };
}
test('prepare exit 23 retains a distinct lock-busy error code', async () => {
  const adb = { subprocess: { shellProtocol: { spawn: async () => processFixture(23) } } };
  await assert.rejects(runCommand(adb, 'trusted fixture'), { code: 'lock-busy' });
});
test('permission removal and key clearing start before a slow transport close settles', async () => {
  const pending = deferred(), events = [];
  const closing = closeConnection({ close: () => { events.push('adb'); return pending.promise; } },
    { forget: () => { events.push('forget'); }, close: () => { events.push('raw'); } },
    { clear: () => { events.push('clear'); } }, true);
  assert.deepEqual(events, ['forget', 'clear', 'adb']);
  pending.resolve(); await closing;
  assert.deepEqual(events, ['forget', 'clear', 'adb', 'raw']);
});
test('a rejected forget still closes the transport and discards the key', async () => {
  const events = [];
  await assert.rejects(closeConnection({ close: async () => { events.push('adb'); } },
    { forget: async () => { throw new Error('permission removal failed'); }, close: async () => { events.push('raw'); } },
    { clear: () => { events.push('clear'); } }, true));
  await Promise.resolve(); await Promise.resolve();
  assert.deepEqual(events, ['clear', 'adb', 'raw']);
});
test('a timed-out spawn may reject later without an unhandled rejection', async () => {
  // Promise.race attaches both fulfillment and rejection handlers to work().
  // A later rejection is already consumed; an extra catch is unnecessary.
  const pending = deferred(); let signal;
  const adb = { subprocess: { shellProtocol: { spawn: (_command, abort) => { signal = abort; return pending.promise; } } } };
  await assert.rejects(runCommand(adb, 'trusted fixture', { timeout: 5 }), { code: 'timeout' });
  assert.equal(signal.aborted, true);
  pending.reject(new Error('Late transport failure'));
  await new Promise(resolve => setTimeout(resolve, 10));
});
test('a timed-out command may reject its output after abort without an unhandled rejection', async () => {
  const pending = deferred(), output = deferred();
  const process = processFixture();
  process.stdout = new ReadableStream({ async pull() { await output.promise; } });
  process.exited = pending.promise;
  let kills = 0; process.kill = () => { kills++; };
  const adb = { subprocess: { shellProtocol: { spawn: async () => process } } };
  await assert.rejects(runCommand(adb, 'trusted fixture', { timeout: 5 }), { code: 'timeout' });
  assert.equal(kills, 1);
  output.reject(new Error('Closed output'));
  pending.reject(new Error('Closed command'));
  await new Promise(resolve => setTimeout(resolve, 10));
});
