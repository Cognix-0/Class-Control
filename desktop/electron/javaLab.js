// Lab server written in Java (backend/LabServer.java), run as a child process.
// Same interface and events as lab.js, so main.js and the UI don't care which one is behind them.
// Control lines go to its stdin and events come back on stdout, both tab separated (see LabServer.java).
const { spawn } = require('node:child_process');
const { EventEmitter } = require('node:events');
const path = require('node:path');
const readline = require('node:readline');

// Inside a packaged app the .java file is unpacked next to app.asar so `java` can read it.
const SERVER = path.join(__dirname, '..', 'backend', 'LabServer.java').replace('app.asar', 'app.asar.unpacked');
const BROADCAST = new Set(['MSG', 'URL', 'LOCK', 'UNLOCK', 'BLOCK', 'ALLOW', 'UNBLOCK', 'SCREEN', 'SCREENOFF']);
const oneLine = (s) => String(s ?? '').replace(/[\r\n]+/g, ' ');

class JavaLab extends EventEmitter {
  constructor() {
    super();
    this.pcs = new Map(); // id -> { id, host, ip, status, lastSeen, thumb, locked }
    this.sharing = new Set();
    this.proc = null;
    this.started = null;
  }

  /** `name` is announced to student PCs on the LAN so they can find this class on their own. */
  start(token, port, name = '') {
    if (this.started) return this.started;
    if (/[\t\r\n]/.test(token)) return Promise.reject(new Error('The password cannot contain tabs or line breaks'));
    this.started = new Promise((resolve, reject) => {
      let err = '';
      let up = false;
      const proc = spawn('java', ['-Dapple.awt.UIElement=true', SERVER], { stdio: ['pipe', 'pipe', 'pipe'] });
      this.proc = proc;
      proc.stdin.on('error', () => {});
      proc.stderr.setEncoding('utf8');
      proc.stderr.on('data', (d) => { err += d; });
      proc.once('error', (e) => reject(new Error(e.code === 'ENOENT'
        ? 'Java was not found. Install Java 17 or newer, then restart the app.' : e.message)));
      proc.once('exit', () => {
        if (!up) reject(new Error(err.trim().split('\n')[0] || 'The Java backend stopped before it started'));
        else if (!this.stopping) { this.#log('The Java backend stopped unexpectedly', 'error'); this.#clear(); }
        if (this.proc === proc) { this.proc = null; this.started = null; }
      });
      readline.createInterface({ input: proc.stdout }).on('line', (l) => {
        const p = l.split('\t');
        if (p[0] === 'STARTED') { up = true; resolve(Number(p[1])); }
        else if (p[0] === 'STARTFAIL') { reject(new Error(p[1])); proc.kill(); }
        else this.#onEvent(p);
      });
      this.stopping = false;
      this.#write(`START\t${token}\t${port}\t${oneLine(name).replace(/\t/g, ' ')}`);
    });
    this.started.catch(() => { this.started = null; });
    return this.started;
  }

  stop() {
    const proc = this.proc;
    this.stopping = true;
    this.#write('STOP'); // the server releases every student (unlock, unblock, board off) before it exits
    proc?.stdin.end();
    if (proc) setTimeout(() => proc.kill(), 3000).unref();
    this.proc = null;
    this.started = null;
    this.#clear();
  }

  list() { return [...this.pcs.values()]; }

  sharingIds() { return [...this.sharing]; }

  send(ids, cmd, arg) {
    if (!BROADCAST.has(cmd)) throw new Error(`Unknown command ${cmd}`);
    const known = ids?.length ? ids.filter((id) => this.pcs.has(id)) : [...this.pcs.keys()];
    if (!known.length) return 0;
    this.#write(`SEND\t${known.join(',')}\t${cmd}\t${oneLine(arg)}`);
    return known.length;
  }

  remote(id, cmd, arg = '') {
    this.#write(`REMOTE\t${id}\t${cmd}\t${oneLine(arg)}`); // the Java side validates and logs bad commands
    return this.pcs.has(id);
  }

  /** One whiteboard stroke / command for every student; the Java server validates it. */
  whiteboard(arg) {
    this.#write(`WB\t${oneLine(arg)}`);
    return true;
  }

  setLive(on) { this.#write(`LIVE\t${on ? 1 : 0}`); }

  #write(line) {
    if (this.proc && !this.proc.stdin.destroyed) this.proc.stdin.write(line + '\n');
  }

  #clear() {
    for (const id of [...this.pcs.keys()]) this.emit('event', { type: 'removed', id });
    this.pcs.clear();
    this.sharing.clear();
  }

  #onEvent(p) {
    const pc = this.pcs.get(p[1]);
    switch (p[0]) {
      case 'ADDED': {
        const added = { id: p[1], host: p[2], ip: p[3], status: 'online', lastSeen: Date.now(), thumb: null, locked: false };
        this.pcs.set(added.id, added);
        this.emit('event', { type: 'added', pc: added });
        break;
      }
      case 'REMOVED':
        this.pcs.delete(p[1]);
        this.emit('event', { type: 'removed', id: p[1] });
        break;
      case 'THUMB': // always followed by STATUS, which publishes it
        if (pc) this.pcs.set(pc.id, { ...pc, thumb: p[2] });
        break;
      case 'STATUS':
        if (pc) {
          const next = { ...this.pcs.get(pc.id), status: p[2], locked: p[3] === '1', lastSeen: Number(p[4]) };
          this.pcs.set(next.id, next);
          this.emit('event', { type: 'updated', pc: next });
        }
        break;
      case 'FRAME':
        this.emit('event', { type: 'frame', id: p[1], data: p[2] });
        break;
      case 'SHARE':
        this.sharing = new Set(p[1] ? p[1].split(',') : []);
        this.emit('event', { type: 'share', ids: this.sharingIds() });
        break;
      case 'LOG':
        this.#log(p[2], p[1]);
        break;
    }
  }

  #log(msg, level = 'info') {
    this.emit('event', { type: 'log', msg, level, at: Date.now() });
  }
}

module.exports = { JavaLab };
