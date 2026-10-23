import { CaptureSession, readJournal } from './capture.js';
import { PROPERTY, PACKAGE, SetupError, quote, randomToken } from './protocol.js';
import { connectPhone } from './transport.js';

const $ = id => document.getElementById(id);
let phone = null, session = null, busy = false, closing = false;
const legacySite = ['jjricks.com', 'www.jjricks.com'].includes(location.hostname) && /^\/pairscooter(?:\/|$)/.test(location.pathname);
const storage = {
  getItem: key => localStorage.getItem(key),
  setItem: (key, value) => localStorage.setItem(key, value),
  removeItem: key => localStorage.removeItem(key)
};
function message(id, text) { $(id).textContent = text; $(id).hidden = !text; }
function error(error) {
  message('notice', '');
  // USB, shell and library exceptions can contain phone data. Never render,
  // log, persist or transmit an arbitrary exception's message/stack.
  message('error', error instanceof SetupError ? error.message : 'This step could not finish. Check your USB connection and try again.');
}
function panel(name, step) {
  for (const id of ['connect-panel', 'capture-panel', 'working-panel', 'save-panel', 'recovery']) $(id).hidden = id !== name;
  for (const item of document.querySelectorAll('[data-step]')) {
    const n = Number(item.dataset.step);
    item.classList.toggle('complete', n < step);
    if (n === step) item.setAttribute('aria-current', 'step'); else item.removeAttribute('aria-current');
  }
}
function pendingRecovery() {
  try { return !!readJournal(storage); }
  catch (e) { error(e); return true; }
}
function validateEnvironment({ recovery = false } = {}) {
  const problem = navigator.serviceWorker?.controller
    ? 'A service worker controls this page. For your privacy, capture is disabled. Use a separate regular browser profile (not Incognito or Guest) for this helper or contact the site operator; this page will not remove another app’s service worker.'
    : !window.isSecureContext || !navigator.usb
      ? 'Open this page in desktop Chrome or Edge over HTTPS. USB setup is unavailable in this browser.' : '';
  message('environment', problem);
  $('legacy-site').hidden = !legacySite;
  $('connect').disabled = !!problem || legacySite || !$('usb-consent').checked || busy;
  $('recover').disabled = !!problem || busy;
  return !problem && (!legacySite || recovery);
}
async function connect({ keepResult = false, recovery = false } = {}) {
  if (phone) return phone;
  if (!validateEnvironment({ recovery })) throw new SetupError('environment', 'This browser environment cannot safely run capture.');
  const previous = session;
  if (keepResult) previous.document();
  let connected;
  connected = await connectPhone(name => message('notice', `Unlock ${name} and approve the USB debugging prompt. Leave “Always allow” unchecked.`), () => {
    // A deliberate close detaches this phone first; a late disconnect from it
    // must not discard a saved result or disturb a newly selected phone.
    if (phone !== connected) return;
    phone = null;
    if (!closing) {
      session?.cancel();
      session?.forgetResult();
      message('notice', '');
      error(new SetupError('disconnect', 'USB disconnected. If capture was running, reconnect the same phone to verify cleanup.'));
      if (!busy) panel(pendingRecovery() ? 'recovery' : 'connect-panel', 0);
    }
  });
  phone = connected;
  if (keepResult) {
    if (phone.serial !== previous.serial) {
      await releasePhone({ keepResult: true });
      throw new SetupError('device', 'Choose the same phone that provided this owner setup.');
    }
    session = previous;
    return phone;
  }
  session = new CaptureSession({ run: phone.run, serial: phone.serial, storage, update: (state, text) => {
    $('progress').textContent = text;
    $('working-title').textContent = state === 'capturing' ? 'Open your scooter in NAVEE.' : state === 'cleaning' ? 'Restoring your phone’s settings.' : 'Getting the phone ready.';
    $('cancel').disabled = state === 'cleaning';
  } });
  $('device-name').textContent = phone.name;
  message('notice', '');
  return phone;
}
async function action(work) {
  if (busy) return;
  busy = true; validateEnvironment(); message('error', ''); message('notice', '');
  try { await work(); } catch (e) { error(e); }
  finally { busy = false; validateEnvironment(); }
}
async function releasePhone({ keepResult = false } = {}) {
  closing = true;
  const connected = phone;
  phone = null;
  if (!keepResult) { session?.forgetResult(); session = null; }
  try { await connected?.close(true); }
  catch { message('notice', 'Disconnected. Remove this site’s USB permission in Chrome’s site settings.'); }
  finally { closing = false; }
  $('send').textContent = 'Choose phone and save';
}
async function closePhone() {
  await releasePhone();
  $('usb-consent').checked = false; $('capture-consent').checked = false; $('start').disabled = true;
  panel(pendingRecovery() ? 'recovery' : 'connect-panel', 0);
}
$('usb-consent').addEventListener('change', validateEnvironment);
$('capture-consent').addEventListener('change', () => { $('start').disabled = !$('capture-consent').checked || busy; });
$('connect').addEventListener('click', () => action(async () => {
  if (!$('usb-consent').checked || pendingRecovery()) return;
  await connect(); $('send').textContent = 'Save to phone Downloads'; panel('capture-panel', 1);
}));
$('disconnect').addEventListener('click', () => action(closePhone));
$('start').addEventListener('click', () => action(async () => {
  if (!$('capture-consent').checked || !session || !phone || !validateEnvironment()) return;
  $('capture-consent').checked = false; $('start').disabled = true; $('cancel').disabled = false;
  panel('working-panel', 2);
  try { await session.begin(); panel('save-panel', 3); }
  catch (e) {
    if (session?.safe && !pendingRecovery()) await closePhone();
    else panel(pendingRecovery() ? 'recovery' : phone ? 'capture-panel' : 'connect-panel', 1);
    throw e;
  }
}));
$('cancel').addEventListener('click', () => {
  session?.cancel(); $('cancel').disabled = true; $('progress').textContent = 'Stopping after the current bounded phone step, then restoring settings…';
});
$('recover').addEventListener('click', () => action(async () => {
  await connect({ recovery: true });
  await session.recover();
  await closePhone();
  message('notice', 'Cleanup verified. NAVEE is closed and the original log setting is restored. USB access is closed; choose your phone again for a fresh capture.');
}));
function downloadText(data, name, type) {
  const url = URL.createObjectURL(new Blob([data], { type }));
  const link = document.createElement('a'); link.href = url; link.download = name; link.click();
  setTimeout(() => URL.revokeObjectURL(url), 10_000);
}
$('recovery-details').addEventListener('click', () => action(async () => {
  const journal = readJournal(storage);
  if (!journal) throw new SetupError('journal', 'There is no recovery record to download.');
  let instructions = 'VOLT CAPTURE RECOVERY — no scooter owner identity is included.\n\n'
    + 'First: reconnect the same phone and use Recover cleanup on the website.\n'
    + 'If that fails, these details are for you or a trusted technical helper.\n'
    + 'Keep this browser profile and the phone lock directory intact. Do not start another helper.\n\n'
    + JSON.stringify(journal, null, 2) + '\n\n';
  if (journal.stage !== 'preparing') {
    const shell = command => `adb -s ${quote(journal.serial)} shell ${quote(command)}`;
    instructions += 'Advanced macOS/Linux fallback with Android Platform-Tools:\n'
      + '1. Turn USB debugging off and back on, reconnect, and approve this trusted computer. Close browser USB access first.\n'
      + '2. Wait at least three minutes after capture stopped. If the guard was forcibly killed or wedged, restart the phone first. A reboot ends the old timer; it does not prove the old log setting was restored.\n'
      + '3. Verify the displayed serial matches your selected phone. Restore the exact pre-capture value with these commands (they do not remove the lock):\n\n'
      + shell(`am force-stop --user 0 ${PACKAGE}`) + '\n'
      + shell(`setprop ${PROPERTY} ${quote(journal.original)}`) + '\n'
      + shell(`getprop ${PROPERTY}`) + '\n\n'
      + 'Expected getprop output (JSON string, preserving spaces): ' + JSON.stringify(journal.original) + '\n\n'
      + '4. Return to the same browser and try Recover cleanup to verify and release the session. If the timer ended without acknowledgment or preparation is damaged, ask a trusted developer to compare the lock token/owner/original with this record before removing only this session’s files. Never remove a different or active helper’s lock.\n';
  } else instructions += 'Preparation was not confirmed; this record does NOT contain a verified original value. The page never requested temporary logging in this stage. Do not restore a guessed setting. A trusted helper can inspect the matching random token on the phone and verify that no guard was armed before releasing incomplete preparation.\n';
  downloadText(instructions, 'volt-capture-recovery.txt', 'text/plain');
}));
function newFilename() { return `navee-owner-setup-${new Date().toISOString().replace(/[-:]/g, '').slice(0, 15)}-${randomToken().slice(0, 12)}.json`; }
$('download').addEventListener('click', () => action(async () => {
  const data = session.document();
  downloadText(data, newFilename(), 'application/json');
  message('notice', 'Backup download requested; check your browser’s Downloads and store it privately. USB access is now closed.');
  await releasePhone({ keepResult: true });
}));
$('send').addEventListener('click', () => action(async () => {
  if (!phone) await connect({ keepResult: true });
  const data = session.document(), name = newFilename(), path = `/sdcard/Download/${name}`;
  const length = new TextEncoder().encode(data).length;
  // The identity travels over shell stdin, never a command argument. noclobber
  // opens a new file exclusively. The trap is installed only AFTER that open,
  // so a pre-existing file can never be removed on an unsuccessful transfer.
  const command = `umask 077
set -C
exec 3> ${quote(path)} || exit 70
trap 'rm -f ${quote(path)}' EXIT HUP INT TERM
cat >&3 || exit 71
exec 3>&-
[ "$(wc -c < ${quote(path)})" -eq ${length} ] || exit 72
trap - EXIT HUP INT TERM
printf SAVED`;
  try {
    if (await phone.run(command, { input: data }) !== 'SAVED') throw new Error('Unverified transfer');
    $('saved-file').textContent = `Phone Downloads / ${name}`; $('saved-file').hidden = false;
    message('notice', 'Saved to phone Downloads. USB access is closed. In Volt’s Import your owner file step, tap Choose owner file and select the new file.');
    await releasePhone({ keepResult: true });
  } catch {
    await releasePhone({ keepResult: true });
    throw new SetupError('transfer', 'The file transfer could not be verified. Check phone Downloads before retrying; another attempt creates a different filename.');
  }
}));
$('finish').addEventListener('click', () => action(async () => {
  await closePhone();
  message('notice', 'Disconnected and the browser’s temporary key was discarded. Turn off USB debugging on your phone. If you selected “Always allow,” use Developer options → Revoke USB debugging authorizations (this also removes other trusted computers).');
}));
window.addEventListener('beforeunload', event => {
  if (session?.busy || pendingRecovery()) { event.preventDefault(); event.returnValue = ''; }
});
window.addEventListener('pagehide', () => {
  session?.cancel(); session?.forgetResult();
  // Unload promises are not reliable. The already-armed phone watchdog is the
  // fallback; the next visit must still explicitly verify cleanup.
  if (!session?.busy) phone?.close(true).catch(() => {});
});
navigator.serviceWorker?.addEventListener('controllerchange', () => { session?.cancel(); session?.forgetResult(); validateEnvironment(); });
validateEnvironment();
if (pendingRecovery()) panel('recovery', 0);
