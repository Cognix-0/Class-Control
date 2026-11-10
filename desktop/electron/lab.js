// Lab server: same line protocol as Teacher.java / RealLabService.java.
// Agents connect in, send "token\tcomputerName", then receive "COMMAND\targ" lines.
// Agents may reply "THUMB\t<b64 jpeg>" (live view) and "FRAME\t<b64 jpeg>" (remote view).
const net = require('node:net');
const { EventEmitter } = require('node:events');

const BROADCAST = new Set(['MSG', 'URL', 'LOCK', 'UNLOCK', 'BLOCK', 'UNBLOCK', 'SCREEN', 'SCREENOFF']);
const MAX_LINE = 8 * 1024 * 1024; // trust boundary: a client can't make us buffer forever
const B64 = /^[A-Za-z0-9+/=]+$/;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

class Lab extends EventEmitter {
  constructor({ grabFrame } = {}) {
    super();
    this.grabFrame = grabFrame; // async () => base64 jpeg of the teacher screen
    this.pcs = new Map();       // id -> { id, host, ip, status, lastSeen, thumb, locked }
    this.socks = new Map();     // id -> net.Socket
    this.sharing = new Set();
    this.server = null;
    this.liveTimer = null;
    this.token = '';
  }

  start(token, port) {
    this.token = String(token);
    if (this.server) return Promise.resolve(this.server.address().port);
    return new Promise((resolve, reject) => {
      const srv = net.createServer((s) => this.#serve(s));
      srv.once('error', reject);
      srv.listen(port, () => {
        srv.off('error', reject);
        srv.on('error', (e) => this.#log(`Server error: ${e.message}`, 'error'));
        this.server = srv;
        this.#log(`Class started on port ${srv.address().port}. Waiting for student PCs…`);
        resolve(srv.address().port);
      });
    });
  }

  stop() {
    clearInterval(this.liveTimer);
    this.sharing.clear();
    for (const s of this.socks.values()) s.destroy();
    this.server?.close();
    this.server = null;
  }

  list() { return [...this.pcs.values()]; }

  #serve(s) {
    // Remote ip:port is unique per connection, so two PCs with the same name don't collide.
    const ip = (s.remoteAddress || '').replace(/^::ffff:/, '');
    const id = `${ip}:${s.remotePort}`;
    let buf = '';
    let joined = false;
    s.setEncoding('utf8');
    s.setNoDelay(true);
    s.setTimeout(10_000, () => { if (!joined) s.destroy(); });
    s.on('data', (chunk) => {
      buf += chunk;
      if (buf.length > MAX_LINE) { s.destroy(); return; }
      let i;
      while ((i = buf.indexOf('\n')) >= 0) {
        const line = buf.slice(0, i).replace(/\r$/, '');
        buf = buf.slice(i + 1);
        if (joined) { this.#onLine(id, line); continue; }
        const [tok, name] = line.split('\t', 2);
        if (name === undefined || tok !== this.token) {
          this.#log(`Rejected ${ip} (wrong password)`, 'warn');
          s.destroy();
          return;
        }
        joined = true;
        s.setTimeout(0);
        const pc = { id, host: name.trim() || 'student', ip, status: 'online', lastSeen: Date.now(), thumb: null, locked: false };
        this.pcs.set(id, pc);
        this.socks.set(id, s);
        this.emit('event', { type: 'added', pc });
        this.#log(`${pc.host} connected`);
      }
    });
    s.on('error', () => {});
    s.on('close', () => {
      if (!joined) return;
      const pc = this.pcs.get(id);
      this.pcs.delete(id);
      this.socks.delete(id);
      this.sharing.delete(id);
      this.emit('event', { type: 'removed', id });
      this.#log(`${pc?.host ?? id} disconnected`);
    });
  }

  #onLine(id, line) {
    const pc = this.pcs.get(id);
    if (!pc) return;
    const tab = line.indexOf('\t');
    const kind = tab < 0 ? line : line.slice(0, tab);
    const data = tab < 0 ? '' : line.slice(tab + 1);
    if (kind === 'FRAME') {
      if (B64.test(data)) this.emit('event', { type: 'frame', id, data });
      return;
    }
    const next = { ...pc, lastSeen: Date.now(), status: pc.locked ? 'locked' : 'online' };
    if (kind === 'THUMB' && data && B64.test(data)) next.thumb = data;
    this.#update(next);
  }

  #update(pc) {
    this.pcs.set(pc.id, pc);
    this.emit('event', { type: 'updated', pc });
  }

  #write(id, line) {
    const s = this.socks.get(id);
    if (!s || s.destroyed) return false;
    s.write(line + '\n');
    return true;
  }

  #targets(ids) {
    const all = ids && ids.length ? ids : [...this.socks.keys()];
    return all.filter((id) => this.socks.has(id));
  }

  /** Broadcast-style commands to the selected PCs (empty list = everyone). */
  send(ids, cmd, arg) {
    if (!BROADCAST.has(cmd)) throw new Error(`Unknown command ${cmd}`);
    const to = this.#targets(ids);
    if (!to.length) return 0;
    let a = String(arg ?? '').trim().replace(/[\r\n]+/g, ' ');
    const n = `${to.length} PC${to.length === 1 ? '' : 's'}`;
    if (cmd === 'SCREEN') { to.forEach((id) => this.sharing.add(id)); this.#shareLoop(); this.#emitShare(); this.#log(`Sharing your screen with ${n}`); return to.length; }
    if (cmd === 'SCREENOFF') { to.forEach((id) => { this.sharing.delete(id); this.#write(id, 'SCREENOFF\t'); }); this.#emitShare(); this.#log(`Stopped screen share on ${n}`); return to.length; }
    if (cmd === 'LOCK' && !a) a = 'Eyes on the instructor';
    for (const id of to) {
      this.#write(id, `${cmd}\t${a}`);
      const pc = this.pcs.get(id);
      if (pc && (cmd === 'LOCK' || cmd === 'UNLOCK')) {
        const locked = cmd === 'LOCK';
        this.#update({ ...pc, locked, status: locked ? 'locked' : 'online' });
      }
    }
    this.#log(`${cmd}${a ? ` "${a}"` : ''} sent to ${n}`);
    return to.length;
  }

  sharingIds() { return [...this.sharing]; }

  #emitShare() { this.emit('event', { type: 'share', ids: this.sharingIds() }); }

  async #shareLoop() {
    if (this.shareRunning || !this.grabFrame) return;
    this.shareRunning = true;
    try {
      while (this.sharing.size) {
        const frame = await this.grabFrame();
        for (const id of this.sharing) {
          const s = this.socks.get(id);
          if (s && s.writableLength < 4 * 1024 * 1024) s.write(`SCREEN\t${frame}\n`); // skip frames on slow links
        }
        await sleep(500);
      }
    } catch (e) {
      this.#log(`Screen share stopped: ${e.message}`, 'error');
      this.sharing.clear();
    } finally {
      this.shareRunning = false;
      this.#emitShare();
    }
  }

  /** Remote view / control of one PC. Validates everything that goes onto the wire. */
  remote(id, cmd, arg = '') {
    const a = String(arg);
    const ok = {
      VIEW: /^\d{1,2}$/,
      VIEWOFF: /^$/,
      MOUSE: /^(move|down|up|wheel),(0(\.\d+)?|1(\.0+)?),(0(\.\d+)?|1(\.0+)?),-?\d{1,3}$/,
      KEY: /^(down|up),\d{1,5}$/,
    }[cmd];
    if (cmd === 'TYPE') return this.#write(id, `TYPE\t${a.replace(/[\r\n]+/g, ' ').slice(0, 2000)}`);
    if (!ok || !ok.test(a)) throw new Error(`Bad remote command ${cmd} ${a}`);
    if (cmd === 'VIEW') this.#log(`Remote view opened on ${this.pcs.get(id)?.host ?? id}`);
    return this.#write(id, `${cmd}\t${a}`);
  }

  setLive(on, ms = 2000) {
    clearInterval(this.liveTimer);
    this.liveTimer = null;
    this.#log(on ? 'Live view on' : 'Live view off');
    if (!on) return;
    const tick = () => {
      const now = Date.now();
      for (const id of this.socks.keys()) {
        this.#write(id, 'THUMB\t');
        const pc = this.pcs.get(id);
        // No reply for 3 rounds: connected but stuck (or an old agent without THUMB).
        if (pc && !pc.locked && pc.status === 'online' && now - pc.lastSeen > 3 * ms) this.#update({ ...pc, status: 'stuck' });
      }
    };
    tick();
    this.liveTimer = setInterval(tick, ms);
  }

  #log(msg, level = 'info') {
    this.emit('event', { type: 'log', msg, level, at: Date.now() });
  }
}

module.exports = { Lab };
