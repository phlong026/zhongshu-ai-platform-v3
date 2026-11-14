const test = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const { sha256Hex } = require('../miniprogram/api/finance');
test('upload digest hashes ArrayBuffer bytes, byte views and legacy binary strings identically', () => {
  const vectors = [Buffer.alloc(0), Buffer.from('abc'), Buffer.alloc(4), Buffer.from([0, 255, 128, 1]),
    Buffer.from('abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq'), Buffer.alloc(1000000, 97)];
  for (const bytes of vectors) {
    const expected = crypto.createHash('sha256').update(bytes).digest('hex');
    const padded = Buffer.concat([Buffer.from([99]), bytes, Buffer.from([100])]);
    assert.equal(sha256Hex(bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength)), expected);
    assert.equal(sha256Hex(new Uint8Array(padded.buffer, padded.byteOffset + 1, bytes.byteLength)), expected);
    assert.equal(sha256Hex(bytes.toString('binary')), expected);
  }
});
