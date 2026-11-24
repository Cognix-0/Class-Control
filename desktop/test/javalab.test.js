// Bridge self-check: fake agents talk over TLS 1.3 to the real Java server (backend/LabServer.java) through JavaLab.
// They pin the server's certificate, exactly as Agent.java does, so the test fails if the class is ever plaintext.
const test = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { JavaLab } = require('../electron/javaLab');
const { connectPinned } = require('../electron/pinnedTls');

// One keystore for the whole file: keytool only runs for the first class that uses it.
const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'cc-key-'));
const KEY = { file: path.join(dir, 'instructor.p12'), pw: 'lecturerpw' };
process.on('exit', () => { try { fs.rmSync(dir, { recursive: true, force: true }); } catch { /* gone already */ } });

/** Starts a class with that keystore and resolves { port, pin }. */
async function startClass(lab, token, port, name = '', roster = null, share = false) {
  const key = new Promise((r) => lab.on('event', (e) => e.type === 'key' && r(e.fingerprint)));
  const p = await lab.start(token, port, name, roster, share, KEY.file, KEY.pw);
  return { port: p, pin: await key };
}

function agent(port, pin, hello) {
  const s = connectPinned({ host: '127.0.0.1', port, pin });
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
  s.once('pinned', () => s.write(hello + '\n')); // nothing goes out before the certificate matched
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
  const { port, pin } = await startClass(lab, 'pw', 0); // `java LabServer.java` compiles first, so this can take a second or two
  try {
    const bad = agent(port, pin, 'nope\tX');
    await bad.closed; // wrong password: dropped
    assert.equal(lab.list().length, 0);

    const added = nextEvent(lab, 'added');
    const a = agent(port, pin, 'pw\tPC01');
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
  const { port, pin } = await startClass(lab, 'pw', 0, 'Room 1');
  const added = nextEvent(lab, 'added');
  const a = agent(port, pin, 'pw\tPC01');
  await added;
  lab.stop(); // class over: nobody may be left locked, blocked or staring at a whiteboard
  assert.equal(await a.next(), 'WB\toff');
  assert.equal(await a.next(), 'SCREENOFF\t');
  assert.equal(await a.next(), 'UNBLOCK\t');
  assert.equal(await a.next(), 'UNLOCK\t');
  a.destroy();
});

test('students: login, chat, share request, presenting', { timeout: 60_000 }, async () => {
  const roster = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'cc-')), 'students.properties');
  const lab = new JavaLab();
  const { port, pin } = await startClass(lab, 'pw', 0, 'Room 1', roster);
  try {
    const added = nextEvent(lab, 'added');
    const s = agent(port, pin, 'pw\tPC05');
    const { pc } = await added;
    const viewer = agent(port, pin, 'pw\tPC06');
    await nextEvent(lab, 'added');

    const student = nextEvent(lab, 'updated');
    s.write('LOGIN\tIT23001\tsecret\n');
    assert.equal(await s.next(), 'LOGIN\tok');
    assert.equal((await student).pc.student, 'IT23001');
    assert.match(fs.readFileSync(roster, 'utf8'), /^IT23001=/m); // saved for the next class

    const chat = nextEvent(lab, 'chat');
    s.write('CHAT\thelp me\n');
    assert.equal((await chat).text, 'help me');

    const req = nextEvent(lab, 'sharereq');
    s.write('SHARE\treq\n');
    assert.equal((await req).id, pc.id);
    assert.equal(lab.list().find((p) => p.id === pc.id).shareRequest, true);

    const presenter = nextEvent(lab, 'presenter');
    lab.present(pc.id, '1');
    assert.equal(await s.next(), 'PRESENT\t1');
    assert.equal((await presenter).id, pc.id);
    assert.equal(lab.list().find((p) => p.id === pc.id).presenting, true);
    s.write('PFRAME\tQUJD\n');
    assert.equal(await viewer.next(), 'SCREEN\tQUJD');
    const stopped = nextEvent(lab, 'presenter');
    lab.present('', '0');
    assert.equal(await s.next(), 'PRESENT\t0');
    assert.equal(await viewer.next(), 'SCREENOFF\t');
    assert.equal((await stopped).id, null);
    assert.throws(() => lab.present(pc.id, 'yes'));
    s.destroy();
    viewer.destroy();
  } finally {
    lab.stop();
  }
});

test('a second instructor joins the same class and sees the same students', { timeout: 60_000 }, async () => {
  const host = new JavaLab();
  const { port, pin } = await startClass(host, 'pw', 0);
  const co = new JavaLab();
  try {
    const added = nextEvent(host, 'added');
    const a = agent(port, pin, 'pw\tPC01');
    const { pc } = await added;

    // declined request
    const no = new JavaLab();
    const ask1 = nextEvent(host, 'cojoin');
    const denied = no.join('127.0.0.1', port, 'Mr Silva', pin);
    host.coaccept((await ask1).id, false);
    await assert.rejects(denied, /declined/);

    // accepted request: the co-instructor gets the class as it is now
    const ask2 = nextEvent(host, 'cojoin');
    const seen = nextEvent(co, 'added');
    const joined = co.join('127.0.0.1', port, 'Ms Perera', pin);
    const req = await ask2;
    assert.equal(req.name, 'Ms Perera');
    host.coaccept(req.id, true);
    assert.equal(await joined, port);
    assert.equal((await seen).pc.id, pc.id);
    assert.equal(co.role(), 'co');

    assert.equal(co.send([pc.id], 'MSG', 'hi from co'), 1);   // co-instructor's command reaches the student
    assert.equal(await a.next(), 'MSG\thi from co');
    const locked = nextEvent(co, 'updated');
    host.send([pc.id], 'LOCK', '');                             // and the host's actions show up for the co-instructor
    assert.equal(await a.next(), 'LOCK\tEyes on the instructor');
    assert.equal((await locked).pc.locked, true);

    const ended = nextEvent(co, 'ended');
    host.stop();                                                // host ends the class: the co-instructor is told
    await ended;
    a.destroy();
  } finally {
    co.stop();
    host.stop();
  }
});

// The certificate is the lecturer's identity: a client with the wrong pin never gets in, a wrong master
// password stops the class instead of starting an unsafe one, and a co-instructor needs the host's pin too.
test('TLS: wrong pin refused, wrong master password refuses to start', { timeout: 60_000 }, async () => {
  const lab = new JavaLab();
  const { port, pin } = await startClass(lab, 'pw', 0, 'Room 1', null, true);
  try {
    assert.match(pin, /^[0-9a-f]{64}$/);

    const other = 'f'.repeat(64);                       // somebody else's certificate
    const wrong = agent(port, other, 'pw\tPC30');
    const failed = new Promise((r) => wrong.once('error', r));
    await wrong.closed;
    assert.match((await failed).message, /its key does not match/);
    assert.equal(lab.list().length, 0);                 // it never became a PC in the class

    const good = agent(port, pin, 'pw\tPC31');         // and the class is still running
    const added = await nextEvent(lab, 'added');
    assert.equal(added.pc.host, 'PC31');
    assert.equal(good.getProtocol(), 'TLSv1.3');
    good.destroy();
  } finally {
    lab.stop();
  }

  const bad = new JavaLab();
  await assert.rejects(bad.start('pw', 0, 'Room 1', null, true, KEY.file, 'notmypassword'), /Wrong master password/);
  bad.stop();

  const none = new JavaLab();
  await assert.rejects(none.start('pw', 0, 'Room 1'), /instructor certificate/); // never serve a class in the clear
  none.stop();
});
