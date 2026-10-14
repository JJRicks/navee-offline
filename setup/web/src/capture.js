import { MAX_CAPTURE_BYTES, PACKAGE, PROPERTY, SetupError, ownerDocument, parseCapture, quote, randomToken, tokenValid, parseAndroidUsers, hasNaveePackage, validLauncher } from './protocol.js';
import { LOCK, armCommand, cleanupCommand, inspectCommand, prepareCommand, releaseCommand, preparationStateCommand, startCommand, guardScript, reacquireRecoveryCommand } from './guard.js';

export const JOURNAL_KEY = 'volt.owner-capture.recovery.v1';
export function readJournal(storage) {
  const raw = storage.getItem(JOURNAL_KEY);
  if (!raw) return null;
  try {
    const value = JSON.parse(raw);
    if (value.version === 1 && tokenValid(value.token) && typeof value.serial === 'string' &&
        value.serial.length > 0 && value.serial.length <= 256 && typeof value.original === 'string' &&
        /^[\x20-\x7e]{0,91}$/.test(value.original) && ['preparing', 'prepared', 'armed'].includes(value.stage)) return value;
  } catch { /* Don't expose any contents from local storage. */ }
  throw new SetupError('journal', 'The local recovery record is damaged. Keep this browser profile and use the recovery instructions before another capture.');
}
const wait = ms => new Promise(resolve => setTimeout(resolve, ms));
export class CaptureSession {
  constructor({ run, serial, storage, update = () => {}, now = () => performance.now(), delay = wait, token = randomToken }) {
    Object.assign(this, { run, serial, storage, update, now, delay, token });
    this.busy = false; this.result = null; this.cancelled = false; this.journal = null; this.safe = false;
  }
  saveJournal(value) {
    this.storage.setItem(JOURNAL_KEY, JSON.stringify(value));
    if (this.storage.getItem(JOURNAL_KEY) !== JSON.stringify(value)) throw new SetupError('storage', 'Recovery storage is unavailable. Allow this site to store its temporary recovery record, then try again.');
    this.journal = value;
  }
  clearJournal() {
    const current = readJournal(this.storage);
    if (current?.token !== this.journal?.token) throw new SetupError('journal', 'The recovery record changed. Reconnect to verify cleanup.');
    this.storage.removeItem(JOURNAL_KEY);
    this.journal = null;
  }
  cancel() { this.cancelled = true; }
  checkCancelled() { if (this.cancelled) throw new SetupError('cancelled', 'Capture stopped.'); }
  async begin() {
    if (this.busy) throw new SetupError('busy', 'A capture is already running.');
    if (readJournal(this.storage)) throw new SetupError('recovery', 'Finish recovery before starting a new capture.');
    this.busy = true; this.result = null; this.safe = false; this.cancelled = false;
    let candidate = null, failure = null;
    try {
      // The serial and random token, plus the original log property once read,
      // survive a reload. Neither the ADB key nor owner identity is persisted.
      this.saveJournal({ version: 1, token: this.token(), serial: this.serial, original: '', stage: 'preparing' });
      this.update('preparing', 'Preparing a temporary capture');
      this.checkCancelled();
      if (await this.run('am get-current-user') !== '0')
        throw new SetupError('profile', 'Use the phone’s main personal Android user for this first version. Work profiles and secondary users are not supported.');
      const users = parseAndroidUsers(await this.run('cmd user list --all', { maxBytes: 8192 }));
      for (const user of users) {
        if (user !== 0 && hasNaveePackage(await this.run(`pm list packages --user ${user} -U ${PACKAGE}`)))
          throw new SetupError('profile', 'NAVEE is also installed in another Android profile. Capture is unavailable because its temporary log setting is shared across profiles. No logging changes were requested.');
      }
      const packageRow = await this.run(`pm list packages --user 0 -U ${PACKAGE}`);
      const packageMatch = /^package:com\.navee\.ucaret uid:(\d{1,10})$/.exec(packageRow);
      if (!packageMatch || Number(packageMatch[1]) < 10000 || Number(packageMatch[1]) >= 100000)
        throw new SetupError('package', 'The installed NAVEE app could not be identified for this Android user. No capture was started.');
      const uid = packageMatch[1];
      const launcher = await this.run(`cmd package resolve-activity --components --user 0 -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p ${PACKAGE}`);
      if (!validLauncher(launcher)) throw new SetupError('launcher', 'NAVEE’s launcher could not be identified safely. Open NAVEE normally and use a supported installation.');
      const prepared = await this.run(prepareCommand(this.journal.token));
      const [stamp, encoded] = prepared.split('\n');
      if (!/^\d{9,}\.\d{1,9}$/.test(stamp || '') || !/^[A-Za-z0-9+/]*={0,2}$/.test(encoded || ''))
        throw new SetupError('clock', 'The phone did not provide a valid capture time and original log setting.');
      const original = atob(encoded);
      if (!/^[\x20-\x7e]{0,91}$/.test(original)) throw new SetupError('property', 'The existing NAVEE log setting is not supported. No logging changes were requested.');
      this.saveJournal({ ...this.journal, original, stage: 'prepared' });
      this.checkCancelled();
      // A crash/timeout may occur after starting the watchdog. Journal that
      // uncertainty before issuing the command, not after it returns.
      this.saveJournal({ ...this.journal, stage: 'armed' });
      const armed = await this.run(armCommand(this.journal.token), { input: guardScript(this.journal.token) });
      if (armed !== 'READY') throw new SetupError('guard', 'The cleanup timer could not be confirmed. No logging changes were requested.');
      this.checkCancelled();
      await this.run(startCommand(this.journal.token, launcher), { timeout: 20_000 });
      this.update('capturing', 'On your phone, select your scooter in NAVEE and connect');
      const deadline = this.now() + 90_000;
      while (this.now() < deadline) {
        this.checkCancelled();
        const rows = await this.run(`logcat --uid=${uid} -d -v epoch -t ${quote(stamp)} -e 'Send authData:' -s ${PACKAGE}:D '*:S'`,
          { maxBytes: MAX_CAPTURE_BYTES, timeout: Math.min(12_000, Math.max(100, deadline - this.now())) });
        candidate = parseCapture(rows, Number(stamp), candidate);
        if (candidate) break;
        await this.delay(Math.min(600, Math.max(0, deadline - this.now())));
      }
      this.checkCancelled();
      if (!candidate) throw new SetupError('empty', 'No fresh owner setup arrived in 90 seconds. Check NAVEE is signed in, your scooter is nearby, and other scooter apps are closed.');
    } catch (error) {
      failure = error;
      // Exit 23 is emitted only before prepare acquires the reservation. This
      // page changed nothing; do not turn another helper's lock into our journal.
      if (error instanceof SetupError && error.code === 'lock-busy' && this.journal?.stage === 'preparing') {
        try { this.clearJournal(); } catch (journalError) { failure = journalError; }
      }
    }
    finally {
      this.update('cleaning', 'Closing NAVEE and restoring its log setting');
      try {
        if (this.journal) await this.cleanup();
        this.safe = true;
      } catch { failure = new SetupError('cleanup', 'Cleanup could not be verified. Keep the phone connected and choose Recover cleanup. Nothing can be exported yet.'); }
      this.busy = false;
    }
    if (this.cancelled && !failure) failure = new SetupError('cancelled', 'Capture stopped.');
    if (failure) { this.result = null; throw failure instanceof SetupError ? failure : new SetupError('capture', 'Capture could not finish. Check the USB cable and phone permission, then try again.'); }
    this.result = candidate;
    this.update('ready', 'Owner setup found. Cleanup verified.');
  }
  async cleanup() {
    const journal = this.journal;
    if (!journal || journal.serial !== this.serial) throw new SetupError('device', 'Choose the same phone to recover its capture.');
    if (journal.stage === 'preparing') {
      // Logging was never requested. A failed/lost prepare reply can leave no
      // reservation, our reservation, or one owned by another helper. Only our
      // token authorizes inspection/restoration; never adopt a foreign lock.
      const state = await this.run(preparationStateCommand(journal.token));
      if (state === 'ABSENT' || state === 'FOREIGN') { this.clearJournal(); return; }
      if (state !== 'OURS') throw new SetupError('cleanup', 'Capture preparation is incomplete. Keep the recovery record and use the recovery guide.');
      const inspected = await this.run(inspectCommand(journal.token));
      const original = atob(inspected.split('\n')[0]);
      if (!/^[\x20-\x7e]{0,91}$/.test(original)) throw new SetupError('property', 'Cleanup needs manual verification of the original log setting.');
      this.saveJournal({ ...journal, original, stage: 'prepared' });
    } else {
      const state = await this.run(`[ ! -e ${quote(LOCK)} ] && printf ABSENT || printf PRESENT`);
      if (state === 'ABSENT') await this.run(reacquireRecoveryCommand(journal.token, journal.original));
    }
    const inspected = await this.run(inspectCommand(journal.token));
    if (atob(inspected.split('\n')[0]) !== this.journal.original) throw new SetupError('property', 'The saved phone log setting changed; automatic restoration was stopped.');
    await this.run('sh -s clean', { input: cleanupCommand(journal.token), timeout: 35_000 });
    const restored = await this.run(`getprop ${PROPERTY}`);
    // run() removes exactly the final line ending, never surrounding spaces.
    if (restored !== this.journal.original) throw new SetupError('property', 'The original log setting could not be verified.');
    if (await this.run(releaseCommand(journal.token)) !== 'RELEASED') throw new SetupError('cleanup', 'The cleanup timer could not be released.');
    this.clearJournal();
  }
  async recover() {
    if (this.busy) return;
    this.busy = true; this.result = null; this.safe = false;
    try {
      this.journal = readJournal(this.storage);
      if (!this.journal) throw new SetupError('journal', 'No pending cleanup was found in this browser.');
      await this.cleanup(); this.safe = true;
    } catch (error) {
      throw error instanceof SetupError ? error : new SetupError('cleanup', 'Cleanup is still unverified. Reconnect the same phone and try again.');
    } finally { this.busy = false; }
  }
  document() {
    if (this.busy || !this.safe || this.journal || !this.result || readJournal(this.storage))
      throw new SetupError('unsafe-export', 'A verified capture and completed cleanup are required before saving.');
    return ownerDocument(this.result);
  }
  forgetResult() { this.result = null; }
}
