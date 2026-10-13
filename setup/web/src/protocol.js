// Deliberately independent of browser APIs so the trust boundary can be tested.
export const MAX_CAPTURE_BYTES = 1024 * 1024;
export const PACKAGE = 'com.navee.ucaret';
export const PROPERTY = `log.tag.${PACKAGE}`;
export class SetupError extends Error {
  constructor(code, message) { super(message); this.name = 'SetupError'; this.code = code; }
}
export function parseAuthLine(line) {
  const at = line.indexOf('Send authData:');
  if (at < 0) return null;
  let candidate = line.slice(at + 14).trim();
  if (candidate.startsWith('[')) {
    const end = candidate.indexOf(']');
    if (end < 0) return null;
    candidate = candidate.slice(1, end).trim();
  }
  if (!/^[\da-f]{2}(?:[\s:-]*[\da-f]{2}){16}$/i.test(candidate)) return null;
  const bytes = candidate.replace(/[\s:-]/g, '').match(/../g).map(x => parseInt(x, 16));
  if (bytes.slice(0, 5).join() !== '85,170,0,48,9' || bytes[5] > 4 || bytes[6] > 1 ||
      bytes[13] !== 0 || bytes[15] !== 254 || bytes[16] !== 253 ||
      bytes.slice(0, 14).reduce((a, b) => a + b, 0) % 256 !== bytes[14]) return null;
  return { identity: bytes.slice(7, 13).map(x => x.toString(16).padStart(2, '0')).join('').toUpperCase(), role: bytes[6] };
}
export function parseCapture(text, since, previous = null) {
  if (new TextEncoder().encode(text).length > MAX_CAPTURE_BYTES)
    throw new SetupError('oversize', 'The filtered capture was unexpectedly large. No setup was saved.');
  let found = previous;
  for (const line of text.split('\n')) {
    const stamp = /^\s*(\d{9,}(?:\.\d+)?)\s/.exec(line);
    if (!stamp || Number(stamp[1]) < since) continue;
    const parsed = parseAuthLine(line);
    if (!parsed) continue;
    if (found && (found.identity !== parsed.identity || found.role !== parsed.role))
      throw new SetupError('conflict', 'More than one owner identity or role arrived. Use one NAVEE account and one scooter, then try again.');
    found = parsed;
  }
  return found;
}
export function ownerDocument(result, now = new Date()) {
  if (!result || !/^[A-F0-9]{12}$/.test(result.identity) || ![0, 1].includes(result.role))
    throw new SetupError('invalid', 'No verified owner setup is ready to save.');
  return JSON.stringify({
    format: 'navee-offline-owner-v1', owner_identity: result.identity, role: result.role,
    model_name: 'NAVEE scooter', captured_at_utc: now.toISOString(),
    note: 'Private Bluetooth owner identity; not a NAVEE password or cloud login token.'
  }, null, 2) + '\n';
}
export const quote = value => "'" + String(value).replaceAll("'", "'\\''") + "'";
export function tokenValid(token) { return typeof token === 'string' && /^[a-f0-9]{64}$/.test(token); }
export function randomToken() {
  return Array.from(crypto.getRandomValues(new Uint8Array(32)), x => x.toString(16).padStart(2, '0')).join('');
}
export function parseAndroidUsers(text) {
  if (new TextEncoder().encode(text).length > 8192) throw new SetupError('profile', 'Android profiles could not be checked safely. No capture was started.');
  const lines = text.trim().split(/\r?\n/);
  if (lines.shift() !== 'Users:' || lines.length < 1 || lines.length > 32)
    throw new SetupError('profile', 'Android profiles could not be checked safely. No capture was started.');
  const ids = new Set();
  for (const line of lines) {
    // User-visible names are discarded, never displayed, persisted or commands.
    const match = /^\s*UserInfo\{(\d{1,5}):[^\r\n]*:[a-f\d]+\}(?: running)?\s*$/i.exec(line);
    if (!match || Number(match[1]) > 21474 || ids.has(Number(match[1])))
      throw new SetupError('profile', 'Android profiles could not be checked safely. No capture was started.');
    ids.add(Number(match[1]));
  }
  if (!ids.has(0)) throw new SetupError('profile', 'The main Android profile could not be identified.');
  return [...ids];
}
export function hasNaveePackage(text) {
  if (!text.trim()) return false;
  let found = false;
  for (const line of text.split(/\r?\n/)) {
    const match = /^package:([a-zA-Z\d_.]+) uid:(\d{1,10})$/.exec(line);
    if (!match) throw new SetupError('profile', 'Apps in another Android profile could not be checked safely.');
    if (match[1] === PACKAGE) found = true;
  }
  return found;
}
export function validLauncher(component) {
  return typeof component === 'string' && component.length <= 256 &&
    /^com\.navee\.ucaret\/(?:\.?[A-Za-z_$][\w$]*)(?:\.[A-Za-z_$][\w$]*)*$/.test(component);
}
