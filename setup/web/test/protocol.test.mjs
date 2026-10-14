import test from 'node:test';
import assert from 'node:assert/strict';
import { parseAuthLine, parseCapture, ownerDocument, quote, MAX_CAPTURE_BYTES } from '../src/protocol.js';
import { frame, row } from './fixtures.mjs';
test('strict six-byte identity with supported roles and signed checksum', () => {
  assert.deepEqual(parseAuthLine(row('ABCDEF123456', 1)), { identity: 'ABCDEF123456', role: 1 });
  assert.deepEqual(parseAuthLine(`Send authData: [${frame()}]`), { identity: '010203040506', role: 0 });
  assert.equal(parseAuthLine(row(undefined, 2)), null);
  assert.equal(parseAuthLine(row().replace('55 aa', '55 ab')), null);
  assert.equal(parseAuthLine(row() + ' 00'), null);
  assert.equal(parseAuthLine('password=irrelevant'), null);
});
test('only fresh timestamped auth; repeated identical values okay; conflicting role or identity rejected', () => {
  assert.equal(parseCapture(row(undefined, 0, '1799999999.999'), 1800000000), null);
  assert.equal(parseCapture(`Send authData: ${frame()}`, 1800000000), null);
  assert.equal(parseCapture(`${row()}\n${row()}`, 1800000000).identity, '010203040506');
  assert.throws(() => parseCapture(`${row()}\n${row('010203040507')}`, 1800000000), { code: 'conflict' });
  assert.throws(() => parseCapture(row(undefined, 1), 1800000000, { identity: '010203040506', role: 0 }), { code: 'conflict' });
});
test('byte bound rejects multibyte and oversized input', () => {
  assert.throws(() => parseCapture('x'.repeat(MAX_CAPTURE_BYTES + 1), 0), { code: 'oversize' });
  assert.throws(() => parseCapture('é'.repeat(MAX_CAPTURE_BYTES / 2 + 1), 0), { code: 'oversize' });
});
test('export format interoperates with the native owner importer and contains no phone serial', () => {
  const out = JSON.parse(ownerDocument({ identity: '010203040506', role: 0 }, new Date(0)));
  assert.equal(out.format, 'navee-offline-owner-v1'); assert.equal(out.owner_identity, '010203040506');
  assert.equal(out.captured_at_utc, '1970-01-01T00:00:00.000Z'); assert.equal(out.serial, undefined);
  assert.throws(() => ownerDocument({ identity: 'wrong', role: 0 }));
});
test('shell quoting treats quotes and metacharacters as data', () => {
  assert.equal(quote("a'b;$x"), "'a'\\''b;$x'");
});
