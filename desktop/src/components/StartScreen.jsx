import { useEffect, useState } from 'react';
import { Copy, Eye, EyeOff, FlaskConical, KeyRound, LogIn, Play, RefreshCw, Users } from 'lucide-react';

const read = (k, d) => { try { return localStorage.getItem(k) ?? d; } catch { return d; } };
const write = (k, v) => { try { localStorage.setItem(k, v); } catch { /* storage blocked: just don't remember */ } };

export default function StartScreen({ onStarted }) {
  const [password, setPassword] = useState('');  // never remembered: it is what lets a PC join the class
  const [port, setPort] = useState(() => read('cc.port', '5757'));
  const [show, setShow] = useState(false);
  const [share, setShare] = useState(() => read('cc.share', '1') === '1');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [classes, setClasses] = useState(null); // classes already running on this network (null = still looking)
  const [waiting, setWaiting] = useState(null);  // name of the class we asked to join
  const [split, setSplit] = useState(false);     // "a class is already running here" warning shown
  const [joinIp, setJoinIp] = useState('');
  const [joinKey, setJoinKey] = useState('');    // the other instructor's key: we pin it before joining their class
  const [key, setKey] = useState(null);          // { exists, fingerprint } of this PC's instructor key
  const [master, setMaster] = useState('');
  const [master2, setMaster2] = useState('');    // first run only: typed twice
  const [showMaster, setShowMaster] = useState(false);
  const [newKey, setNewKey] = useState(null);    // the key this PC just created: show it before entering the class

  const scan = async () => {
    setClasses(null);
    const r = await window.lab.classes();
    setClasses(r.ok ? r.value : []);
  };
  useEffect(() => {
    scan();
    window.lab.identity().then((r) => setKey(r.ok ? r.value : { exists: false, fingerprint: '' }));
  }, []);

  const [copied, setCopied] = useState('');
  const copy = (text, what) => { navigator.clipboard?.writeText(text); setCopied(what); };

  async function start(e, force = false) {
    e?.preventDefault();
    if (classes?.length && !force) return setSplit(true); // two classes on one network split the students between them
    if (master.length < 8) return setError('Your master password must be at least 8 characters');
    if (!key?.exists && master !== master2) return setError('The two master passwords are different');
    setBusy(true);
    setError('');
    const first = !key?.exists;
    const r = await window.lab.start(password, Number(port), share, master);
    setBusy(false);
    if (!r.ok) return setError(r.error);
    write('cc.port', String(port));
    write('cc.share', share ? '1' : '0');
    const id = await window.lab.identity();
    if (first && id.ok && id.value.fingerprint) { // tell the lecturer the key before they go on: students need it
      setKey(id.value);
      setNewKey({ fingerprint: id.value.fingerprint, port: r.value });
      return;
    }
    onStarted({ port: r.value, password });
  }

  async function join(host, p, name) {
    if (joinKey.replace(/[^0-9a-fA-F]/g, '').length < 16) return setError('Ask the other instructor for their instructor key, then join their class');
    setBusy(true);
    setError('');
    setSplit(false);
    setWaiting(name || host);
    const r = await window.lab.join(host, Number(p), joinKey);
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

  if (newKey) return ( // first class on this PC: the key was just created, students have to pin it
    <div className="start">
      <div className="start-card">
        <h1 className="brand-xl">Class<span>Control</span></h1>
        <div className="sub-mono">YOUR INSTRUCTOR KEY</div>
        <p className="muted" style={{ fontSize: 13 }}>
          Give this key to your students once. Their app only joins a class run by this computer, so nobody else can
          take over your lab. It stays the same for every class; your master password unlocks it.
        </p>
        <div className="fp-row">
          <code className="mono fp">{newKey.fingerprint}</code>
          <button type="button" className="btn sm" onClick={() => copy(newKey.fingerprint, 'key')}><Copy size={14} /> {copied === 'key' ? 'Copied' : 'Copy'}</button>
        </div>
        <button className="btn primary" style={{ width: '100%', height: 44, justifyContent: 'center', marginTop: 14 }}
          onClick={() => onStarted({ port: newKey.port, password })}>Continue to the class</button>
      </div>
    </div>
  );

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
        <div className="field">
          <label htmlFor="joinkey">Their instructor key <span className="muted">(only to join a class above)</span></label>
          <input id="joinkey" className="input mono" value={joinKey} onChange={(e) => setJoinKey(e.target.value)} placeholder="Paste the key from the other instructor's screen" />
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
          <label htmlFor="master">{key?.exists ? 'Your master password' : 'Create your master password'}</label>
          <div className="input-wrap">
            <input id="master" className="input" type={showMaster ? 'text' : 'password'} value={master} onChange={(e) => setMaster(e.target.value)}
              placeholder={key?.exists ? 'Unlocks this PC\'s instructor key' : 'At least 8 characters'} autoComplete="off" />
            <button type="button" className="btn ghost icon-btn" onClick={() => setShowMaster(!showMaster)} aria-label={showMaster ? 'Hide master password' : 'Show master password'}>
              {showMaster ? <EyeOff size={16} /> : <Eye size={16} />}
            </button>
          </div>
          <small className="muted">Only you know this. It unlocks the key that tells student PCs this class is really yours.</small>
        </div>
        {!key?.exists && (
          <div className="field">
            <label htmlFor="master2">Master password again</label>
            <input id="master2" className="input" type={showMaster ? 'text' : 'password'} value={master2} onChange={(e) => setMaster2(e.target.value)} autoComplete="off" />
          </div>
        )}
        {key?.fingerprint && (
          <div className="fp-row">
            <div>
              <div className="muted" style={{ fontSize: 11 }}><KeyRound size={12} /> INSTRUCTOR KEY (students enter this once)</div>
              <code className="mono fp">{key.fingerprint}</code>
              <small className="muted">Give students the whole key. A shortened key still works but is easier to fake.</small>
            </div>
            <button type="button" className="btn sm" onClick={() => copy(key.fingerprint, 'key')}><Copy size={14} /> {copied === 'key' ? 'Copied' : 'Copy'}</button>
          </div>
        )}
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
          <button className="btn primary" style={{ width: '100%', height: 44, justifyContent: 'center' }} disabled={busy || !password.trim() || master.length < 8}>
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
