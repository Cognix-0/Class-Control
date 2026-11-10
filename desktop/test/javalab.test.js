// Bridge self-check: fake agents talk over TCP to the real Java server (backend/LabServer.java) through JavaLab.
const test = require('node:test');
const assert = require('node:assert');
const net = require('node:net');
const { JavaLab } = require('../electron/javaLab');

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

test('java lab bridge', { timeout: 60_000 }, async () => {
  const lab = new JavaLab();
  const port = await lab.start('pw', 0); // `java LabServer.java` compiles first, so this can take a second or two
  try {
    const bad = agent(port, 'nope\tX');
    await bad.closed; // wrong password: dropped
    assert.equal(lab.list().length, 0);

    const added = nextEvent(lab, 'added');
    const a = agent(port, 'pw\tPC01');
    const { pc } = await added;
    assert.equal(pc.host, 'PC01');

    assert.equal(lab.send([pc.id], 'MSG', 'hi\nthere'), 1);
    assert.equal(await a.next(), 'MSG\thi there');
    const locked = nextEvent(lab, 'updated');
    lab.send([pc.id], 'LOCK', '');
    assert.equal(await a.next(), 'LOCK\tEyes on the instructor');
    assert.equal((await locked).pc.status, 'locked');
    lab.send([], 'UNLOCK', '');           // empty list = everyone
    assert.equal(await a.next(), 'UNLOCK\t');
    assert.throws(() => lab.send([pc.id], 'RM', '-rf'));
    assert.equal(lab.send(['9.9.9.9:1'], 'MSG', 'x'), 0);

    lab.setLive(true);
    assert.equal(await a.next(), 'THUMB\t');
    lab.setLive(false);
    const upd = nextEvent(lab, 'updated');
    a.write('THUMB\tQUJD\n');
    assert.equal((await upd).pc.thumb, 'QUJD');

    lab.remote(pc.id, 'VIEW', '5');
    assert.equal(await a.next(), 'VIEW\t5');
    const frame = nextEvent(lab, 'frame');
    a.write('FRAME\tRkZG\n');
    assert.deepEqual(await frame, { type: 'frame', id: pc.id, data: 'RkZG' });
    lab.remote(pc.id, 'MOUSE', 'down,0.25,1,1');
    assert.equal(await a.next(), 'MOUSE\tdown,0.25,1,1');
    lab.remote(pc.id, 'TYPE', 'a\nb');
    assert.equal(await a.next(), 'TYPE\ta b');

    lab.whiteboard('beg,ff0000,0.0050,0.1000,0.2000');
    assert.equal(await a.next(), 'WB\tbeg,ff0000,0.0050,0.1000,0.2000');
    lab.send([pc.id], 'ALLOW', 'school.edu');
    assert.equal(await a.next(), 'ALLOW\tschool.edu');

    const removed = nextEvent(lab, 'removed');
    a.destroy();
    assert.equal((await removed).id, pc.id);
    assert.equal(lab.list().length, 0);
  } finally {
    lab.stop();
  }
});

test('closing the app releases the students', { timeout: 60_000 }, async () => {
  const lab = new JavaLab();
  const port = await lab.start('pw', 0, 'Room 1');
  const added = nextEvent(lab, 'added');
  const a = agent(port, 'pw\tPC01');
  await added;
  lab.stop(); // class over: nobody may be left locked, blocked or staring at a whiteboard
  assert.equal(await a.next(), 'WB\toff');
  assert.equal(await a.next(), 'SCREENOFF\t');
  assert.equal(await a.next(), 'UNBLOCK\t');
  assert.equal(await a.next(), 'UNLOCK\t');
  a.destroy();
});
