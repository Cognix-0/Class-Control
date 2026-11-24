// The old JavaScript backend has no instructor key, so it must not serve a class at all (see electron/lab.js).
const test = require('node:test');
const assert = require('node:assert');
const { Lab } = require('../electron/lab');

test('the JavaScript backend refuses to start', async () => {
  await assert.rejects(new Lab().start('pw', 0), /cannot prove this PC is the instructor/);
});
