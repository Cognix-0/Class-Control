import { useEffect, useState } from 'react';
import { Eye, EyeOff, FlaskConical, LogIn, Play, RefreshCw, Users } from 'lucide-react';

const read = (k, d) => { try { return localStorage.getItem(k) ?? d; } catch { return d; } };
const write = (k, v) => { try { localStorage.setItem(k, v); } catch { /* storage blocked: just don't remember */ } };

export default function StartScreen({ onStarted }) {
  const [password, setPassword] = useState(() => read('cc.password', ''));
  const [port, setPort] = useState(() => read('cc.port', '5757'));
  const [show, setShow] = useState(false);
  const [share, setShare] = useState(() => read('cc.share', '1') === '1');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [classes, setClasses] = useState(null); // classes already running on this network (null = still looking)
  const [waiting, setWaiting] = useState(null);  // name of the class we asked to join
  const [split, setSplit] = useState(false);     // "a class is already running here" warning shown
  const [joinIp, setJoinIp] = useState('');

  const scan = async () => {
    setClasses(null);
    const r = await window.lab.classes();
    setClasses(r.ok ? r.value : []);
  };
  useEffect(() => { scan(); }, []);

  async function start(e, force = false) {
    e?.preventDefault();
    if (classes?.length && !force) return setSplit(true); // two classes on one network split the students between them
    setBusy(true);
    setError('');
    const r = await window.lab.start(password, Number(port), share);
    setBusy(false);
    if (!r.ok) return setError(r.error);
    write('cc.password', password);
    write('cc.port', String(port));
    write('cc.share', share ? '1' : '0');
    onStarted({ port: r.value, password });
  }

  async function join(host, p, name) {
    setBusy(true);
    setError('');
    setSplit(false);
    setWaiting(name || host);
    const r = await window.lab.join(host, Number(p));
    setBusy(false);
    setWaiting(null);
    if (!r.ok) return setError(r.error);
    onStarted({ port: r.value, coHost: host });
  }

  async function demo() {
    setBusy(true);
    const r = await window.lab.demo();
    setBusy(false);
    if (!r.ok) return setError(r.error);
    onStarted({ port: r.value, password: 'demo', demo: true });
  }

  return (
    <div className="start">
      <form className="start-card" onSubmit={start}>
        <h1 className="brand-xl">Class<span>Control</span></h1>
        <div className="sub-mono">INSTRUCTOR CONSOLE</div>

        <div className="classes">
          <div className="classes-head">
            <Users size={15} /> Classes on this network
            <button type="button" className="btn ghost sm icon-btn" onClick={scan} disabled={busy || classes === null} aria-label="Look again"><RefreshCw size={14} /></button>
          </div>
          {classes === null && <div className="muted classes-empty">Looking…</div>}
          {classes?.length === 0 && <div className="muted classes-empty">None running. Start one below.</div>}
          {classes?.map((c) => (
            <div key={`${c.host}:${c.port}`} className="class-row">
              <div><b>{c.name}</b><div className="mono muted" style={{ fontSize: 11 }}>{c.host} : {c.port}</div></div>
              <button type="button" className="btn sm primary" disabled={busy} onClick={() => join(c.host, c.port, c.name)}><LogIn size={14} /> Join</button>
            </div>
          ))}
        </div>
        {waiting && <div className="hint" role="status" style={{ margin: '0 0 14px' }}>Waiting for the instructor of <b>{waiting}</b> to accept you…</div>}

        <div className="field">
          <label htmlFor="pw">Class password</label>
          <div className="input-wrap">
            <input id="pw" className="input" type={show ? 'text' : 'password'} value={password} autoFocus
              onChange={(e) => setPassword(e.target.value)} placeholder="Students enter the same password" />
            <button type="button" className="btn ghost icon-btn" onClick={() => setShow(!show)} aria-label={show ? 'Hide password' : 'Show password'}>
              {show ? <EyeOff size={16} /> : <Eye size={16} />}
            </button>
          </div>
        </div>
        <div className="field">
          <label htmlFor="port">Port</label>
          <input id="port" className="input mono" inputMode="numeric" value={port} onChange={(e) => setPort(e.target.value.replace(/\D/g, ''))} />
        </div>
        <label className="check">
          <input type="checkbox" checked={share} onChange={(e) => setShare(e.target.checked)} />
          <span>Student apps on this network join on their own<br /><small className="muted">They find this PC and the class password automatically; students only enter their reg no.</small></span>
        </label>
        {error && <div className="error" role="alert">{error}</div>}
        {split ? (
          <div className="split-warn" role="alert">
            <b>{classes[0].name}</b> is already running on this network. If you start another class, each student PC joins only one of
            them. To teach the same students together, join it instead.
            <div className="row">
              <button type="button" className="btn sm primary" onClick={() => join(classes[0].host, classes[0].port, classes[0].name)}><LogIn size={14} /> Join {classes[0].name}</button>
              <button type="button" className="btn sm" onClick={() => { setSplit(false); start(null, true); }}>Start a separate class</button>
            </div>
          </div>
        ) : (
          <button className="btn primary" style={{ width: '100%', height: 44, justifyContent: 'center' }} disabled={busy || !password.trim()}>
            <Play size={16} /> {busy && !waiting ? 'Starting…' : 'Start class'}
          </button>
        )}
        <button type="button" className="btn ghost" style={{ width: '100%', height: 40, justifyContent: 'center', marginTop: 10 }} disabled={busy} onClick={demo}>
          <FlaskConical size={16} /> Try demo with 18 simulated PCs
        </button>
        <details className="join-ip">
          <summary>Join another instructor's class by IP</summary>
          <div className="row">
            <input className="input mono" value={joinIp} onChange={(e) => setJoinIp(e.target.value.trim())} placeholder="Their IP, e.g. 192.168.1.20" aria-label="Instructor IP" />
            <button type="button" className="btn" disabled={busy || !joinIp} onClick={() => join(joinIp, port, joinIp)}><LogIn size={14} /> Join</button>
          </div>
          <small className="muted">Uses the port above. The other instructor has to accept you.</small>
        </details>
        <div className="hint">Student PCs on the same network find this class automatically. They only need the agent and the password above.</div>
      </form>
    </div>
  );
}
