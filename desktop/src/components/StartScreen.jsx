import { useState } from 'react';
import { Eye, EyeOff, FlaskConical, Play } from 'lucide-react';

const read = (k, d) => { try { return localStorage.getItem(k) ?? d; } catch { return d; } };
const write = (k, v) => { try { localStorage.setItem(k, v); } catch { /* storage blocked: just don't remember */ } };

export default function StartScreen({ onStarted }) {
  const [password, setPassword] = useState(() => read('cc.password', ''));
  const [port, setPort] = useState(() => read('cc.port', '5757'));
  const [show, setShow] = useState(false);
  const [share, setShare] = useState(() => read('cc.share', '1') === '1');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  async function start(e) {
    e.preventDefault();
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
        <button className="btn primary" style={{ width: '100%', height: 44, justifyContent: 'center' }} disabled={busy || !password.trim()}>
          <Play size={16} /> {busy ? 'Starting…' : 'Start class'}
        </button>
        <button type="button" className="btn ghost" style={{ width: '100%', height: 40, justifyContent: 'center', marginTop: 10 }} disabled={busy} onClick={demo}>
          <FlaskConical size={16} /> Try demo with 18 simulated PCs
        </button>
        <div className="hint">Student PCs on the same network find this class automatically. They only need the agent and the password above.</div>
      </form>
    </div>
  );
}
