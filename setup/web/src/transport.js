import { Adb, AdbDaemonTransport } from '@yume-chan/adb';
import { AdbDaemonWebUsbDeviceManager } from '@yume-chan/adb-daemon-webusb';
import { MAX_CAPTURE_BYTES, SetupError } from './protocol.js';

// No IndexedDB credential store: the browser key exists only for this page.
export class MemoryCredentialStore {
  key = null;
  async generateKey() {
    const pair = await crypto.subtle.generateKey({ name: 'RSASSA-PKCS1-v1_5', modulusLength: 2048,
      publicExponent: new Uint8Array([1, 0, 1]), hash: 'SHA-1' }, true, ['sign', 'verify']);
    this.key = { buffer: new Uint8Array(await crypto.subtle.exportKey('pkcs8', pair.privateKey)), name: 'Volt-one-time-setup' };
    return this.key;
  }
  *iterateKeys() { if (this.key) yield this.key; }
  clear() { this.key?.buffer.fill(0); this.key = null; }
}
export async function connectPhone(onWaiting, onDisconnect) {
  const manager = AdbDaemonWebUsbDeviceManager.BROWSER;
  if (!manager || !globalThis.isSecureContext) throw new SetupError('browser', 'Use desktop Chrome or Edge over HTTPS (or localhost). Safari and Firefox do not support this USB setup.');
  const device = await manager.requestDevice();
  if (!device) throw new SetupError('cancelled', 'No phone selected.');
  const credentials = new MemoryCredentialStore();
  let adb;
  try {
    const connection = await device.connect();
    onWaiting(device.name || 'Android phone');
    const auth = AdbDaemonTransport.authenticate({ serial: device.serial, connection, credentialStore: credentials });
    let timer;
    const transport = await Promise.race([auth, new Promise((_, reject) => {
      timer = setTimeout(() => reject(new SetupError('authorize', 'Phone approval timed out. Unlock the phone and try Connect again.')), 60_000);
    })]).finally(() => clearTimeout(timer));
    adb = new Adb(transport);
    if (!adb.subprocess.shellProtocol) throw new SetupError('shell', 'This phone does not support the verified shell protocol required for capture.');
    // Attach both paths: USB transport failures must not become unhandled logs.
    adb.disconnected.then(onDisconnect, onDisconnect);
    return {
      serial: device.serial, name: device.name || 'Android phone',
      run: (command, options) => runCommand(adb, command, options),
      close: (forgetUsb = false) => closeConnection(adb, device.raw, credentials, forgetUsb)
    };
  } catch (error) {
    await closeConnection(adb, device.raw, credentials, true).catch(() => {});
    throw error instanceof SetupError ? error : new SetupError('usb', 'Could not connect. Unlock the phone, approve USB debugging, and close other ADB tools or browser tabs. On Windows, an Android USB driver may be needed.');
  }
}
// Begin permission removal synchronously, before awaiting transport shutdown.
// In particular, pagehide cannot reliably resume after an awaited adb.close().
export async function closeConnection(adb, raw, credentials, forgetUsb = false) {
  const attempt = work => { try { return Promise.resolve(work()); } catch (error) { return Promise.reject(error); } };
  const forgotten = forgetUsb && raw.forget ? attempt(() => raw.forget()) : Promise.resolve();
  credentials.clear();
  const closed = attempt(() => adb?.close()).catch(() => {});
  const rawClosed = closed.then(() => attempt(() => raw.close()).catch(() => {}));
  // Install a rejection handler immediately on both operations, including when
  // forget fails while transport shutdown is still pending.
  await Promise.all([forgotten, rawClosed]);
}
export async function runCommand(adb, command, { input, timeout = 12_000, maxBytes = 16_384 } = {}) {
  const abort = new AbortController();
  let timer, process;
  const work = async () => {
    // Array form preserves this complete, internally generated shell program;
    // Tango otherwise tokenizes a string command before joining it again.
    process = await adb.subprocess.shellProtocol.spawn([command], abort.signal);
    let total = 0;
    async function read(stream, keep) {
      const reader = stream.getReader();
      const chunks = [];
      try {
        for (;;) {
          const { value, done } = await reader.read();
          if (done) break;
          total += value.byteLength;
          if (total > Math.min(maxBytes, MAX_CAPTURE_BYTES)) throw new SetupError('oversize', 'The phone response exceeded the capture limit.');
          if (keep) chunks.push(value);
        }
      } finally { reader.releaseLock(); }
      const bytes = new Uint8Array(chunks.reduce((n, x) => n + x.byteLength, 0));
      let at = 0; for (const chunk of chunks) { bytes.set(chunk, at); at += chunk.byteLength; }
      return new TextDecoder().decode(bytes);
    }
    const write = async () => {
      const writer = process.stdin.getWriter();
      try { if (input !== undefined) await writer.write(new TextEncoder().encode(input)); await writer.close(); }
      finally { writer.releaseLock(); }
    };
    const [stdout, , exit] = await Promise.all([read(process.stdout, true), read(process.stderr, false), process.exited, write()]);
    if (exit !== 0) throw new SetupError(exit === 23 ? 'lock-busy' : 'command', exit === 23 ? 'Another setup owns the phone capture lock. Finish its cleanup before trying again.' : exit === 22 ? 'Install and sign into the official NAVEE app on this phone first.' : exit === 26 ? 'Use the phone’s main personal Android user. Work profiles and secondary users are not supported.' : 'The phone could not complete this step. Check permission and try recovery if offered.');
    return stdout.replace(/\r?\n$/, '');
  };
  try {
    return await Promise.race([work(), new Promise((_, reject) => {
      timer = setTimeout(() => reject(new SetupError('timeout', 'The phone did not respond in time.')), timeout);
    })]);
  } finally {
    clearTimeout(timer); abort.abort();
    // Never wait indefinitely for an unresponsive transport during teardown.
    try { Promise.resolve(process?.kill()).catch(() => {}); } catch { /* closed */ }
  }
}
