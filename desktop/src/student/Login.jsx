import { useState } from 'react';
import { Eye, EyeOff, LogIn, Settings as Gear } from 'lucide-react';

const ERRORS = new Set(['badlogin', 'rejected', 'fatal']);
const WAITING = new Set(['searching', 'connecting', 'offline']);

export default function Login({ regNo, conn, settings, onLogin, onCancel, onSettings }) {
  const [reg, setReg] = useState(regNo);
  const [password, setPassword] = useState('');
  const [show, setShow] = useState(false);
  const [error, setError] = useState('');
  const waiting = WAITING.has(conn.state);
  const problem = error || (ERRORS.has(conn.state) ? conn.detail : '');
  const target = settings?.host ? `${settings.host} : ${settings.port}` : 'finds your instructor on this network';

  async function submit(e) {
    e.preventDefault();
    setError('');
    const err = await onLogin(reg.trim(), password);
    if (err) setError(err);
  }

  return (
    <div className="start">
      <form className="start-card student-card" onSubmit={submit}>
        <button type="button" className="btn ghost icon-btn gear" onClick={onSettings} aria-label="Connection settings" title="Connection settings"><Gear size={17} /></button>
        <h1 className="brand-xl">Class<span>Control</span></h1>
        <div className="sub-mono">STUDENT</div>
        <div className="field">
          <label htmlFor="reg">Registration number</label>
          <input id="reg" className="input mono" value={reg} autoFocus={!reg} disabled={waiting} placeholder="e.g. IT23123456"
            onChange={(e) => setReg(e.target.value.toUpperCase())} autoComplete="username" />
        </div>
        <div className="field">
          <label htmlFor="pw">Password</label>
          <div className="input-wrap">
            <input id="pw" className="input" type={show ? 'text' : 'password'} value={password} autoFocus={!!reg} disabled={waiting}
              onChange={(e) => setPassword(e.target.value)} autoComplete="current-password" />
            <button type="button" className="btn ghost icon-btn" onClick={() => setShow(!show)} aria-label={show ? 'Hide password' : 'Show password'}>
              {show ? <EyeOff size={16} /> : <Eye size={16} />}
            </button>
          </div>
        </div>
        {problem && !waiting && <div className="error" role="alert">{problem}</div>}
        {waiting
          ? (
            <>
              <div className="connecting" role="status"><span className="pulse" />{conn.detail || 'Connecting…'}</div>
              <button type="button" className="btn" style={{ width: '100%', height: 40, justifyContent: 'center' }} onClick={onCancel}>Cancel</button>
            </>
          )
          : (
            <button className="btn primary" style={{ width: '100%', height: 44, justifyContent: 'center' }} disabled={!reg.trim() || !password}>
              <LogIn size={16} /> Sign in
            </button>
          )}
        <div className="hint">First time? The password you sign in with becomes your password for this class. Connects to: <span className="mono">{target}</span></div>
      </form>
    </div>
  );
}
