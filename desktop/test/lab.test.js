// Protocol self-check: fake agents talk to the real Lab server over TCP.
const test = require('node:test');
const assert = require('node:assert');
const net = require('node:net');
const { Lab } = require('../electron/lab');

function agent(port, hello) {
  const s = net.connect(port, '127.0.0.1');
  s.setEncoding('utf8');
  const lines = [];
  const waiters = [];
  let buf = '';
  s.on('data', (d) => {
    buf += d;
    let i;
    while ((i = buf.indexOf('\n')) >= 0) {
      lines.push(buf.slice(0, i)); buf = buf.slice(i + 1);
      while (waiters.length && lines.length) waiters.shift()(lines.shift());
    }
  });
  s.write(hello + '\n');
  s.next = () => new Promise((r) => (lines.length ? r(lines.shift()) : waiters.push(r)));
  s.closed = new Promise((r) => s.on('close', r));
  return s;
}
const nextEvent = (lab, type) => new Promise((r) => {
  const f = (e) => { if (e.type === type) { lab.off('event', f); r(e); } };
  lab.on('event', f);
});

test('lab protocol', async () => {
  const lab = new Lab({ grabFrame: async () => 'QUFB' });
  const port = await lab.start('pw', 0);

  const bad = agent(port, 'nope\tX');
  await bad.closed; // wrong password: dropped
  assert.equal(lab.list().length, 0);

  const added = nextEvent(lab, 'added');
  const a = agent(port, 'pw\tPC01');
  const { pc } = await added;
  assert.equal(pc.host, 'PC01');

  lab.send([pc.id], 'MSG', 'hi\nthere');
  assert.equal(await a.next(), 'MSG\thi there');
  lab.send([pc.id], 'LOCK', '');
  assert.equal(await a.next(), 'LOCK\tEyes on the instructor');
  assert.equal(lab.list()[0].status, 'locked');
  lab.send([], 'UNLOCK', '');           // empty list = everyone
  assert.equal(await a.next(), 'UNLOCK\t');
  assert.throws(() => lab.send([pc.id], 'RM', '-rf'));

  lab.setLive(true, 5000);
  assert.equal(await a.next(), 'THUMB\t');
  lab.setLive(false);
  const upd = nextEvent(lab, 'updated');
  a.write('THUMB\tQUJD\n');
  assert.equal((await upd).pc.thumb, 'QUJD');

  lab.send([pc.id], 'SCREEN');
  assert.equal(await a.next(), 'SCREEN\tQUFB');
  lab.send([pc.id], 'SCREENOFF');
  assert.equal(await a.next(), 'SCREENOFF\t');

  lab.remote(pc.id, 'VIEW', '5');
  assert.equal(await a.next(), 'VIEW\t5');
  const frame = nextEvent(lab, 'frame');
  a.write('FRAME\tRkZG\n');
  assert.deepEqual(await frame, { type: 'frame', id: pc.id, data: 'RkZG' });
  lab.remote(pc.id, 'MOUSE', 'down,0.25,1,1');
  assert.equal(await a.next(), 'MOUSE\tdown,0.25,1,1');
  lab.remote(pc.id, 'KEY', 'up,65');
  assert.equal(await a.next(), 'KEY\tup,65');
  lab.remote(pc.id, 'TYPE', 'a\nb');
  assert.equal(await a.next(), 'TYPE\ta b');
  assert.throws(() => lab.remote(pc.id, 'MOUSE', 'down,2,0,1\nLOCK'));
  assert.throws(() => lab.remote(pc.id, 'KEY', 'up,x'));

  const removed = nextEvent(lab, 'removed');
  a.destroy();
  assert.equal((await removed).id, pc.id);
  assert.equal(lab.list().length, 0);
  lab.stop();
});
