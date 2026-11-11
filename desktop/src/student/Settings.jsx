import { useState } from 'react';
import { Eye, EyeOff, RotateCcw, Save } from 'lucide-react';

// Instructor address, port and class password. Saved on this PC so students don't type them every time.
export default function Settings({ value, defaults, onSave, onClose }) {
  const [host, setHost] = useState(value.host ?? '');
  const [port, setPort] = useState(String(value.port ?? 5757));
  const [token, setToken] = useState(value.token ?? '');
  const [show, setShow] = useState(false);
  const [error, setError] = useState('');

  async function save(e) {
    e.preventDefault();
    const err = await onSave({ host: host.trim(), port: Number(port), token });
    if (err) setError(err);
  }
  const reset = () => { if (defaults) { setHost(defaults.host ?? ''); setPort(String(defaults.port ?? 5757)); setToken(defaults.token ?? ''); } };

  return (
    <div className="backdrop" onMouseDown={onClose}>
      <form className="modal" onMouseDown={(e) => e.stopPropagation()} onSubmit={save} onKeyDown={(e) => e.key === 'Escape' && onClose()}>
        <h3>Connection settings</h3>
        <p>Ask your instructor if these change. You only set them once.</p>
        <div className="field">
          <label htmlFor="host">Instructor IP address</label>
          <input id="host" className="input mono" value={host} onChange={(e) => setHost(e.target.value.replace(/\s/g, ''))} placeholder="Leave empty to find automatically" />
        </div>
        <div className="field">
          <label htmlFor="port">Port</label>
          <input id="port" className="input mono" inputMode="numeric" value={port} onChange={(e) => setPort(e.target.value.replace(/\D/g, ''))} />
        </div>
        <div className="field">
          <label htmlFor="token">Class password <span className="muted">(only needed with an IP)</span></label>
          <div className="input-wrap">
            <input id="token" className="input" type={show ? 'text' : 'password'} value={token} onChange={(e) => setToken(e.target.value)} placeholder={host ? '' : 'Found automatically'} />
            <button type="button" className="btn ghost icon-btn" onClick={() => setShow(!show)} aria-label={show ? 'Hide password' : 'Show password'}>
              {show ? <EyeOff size={16} /> : <Eye size={16} />}
            </button>
          </div>
        </div>
        {error && <div className="error" role="alert">{error}</div>}
        <div className="row">
          <button type="button" className="btn ghost" onClick={reset} style={{ marginRight: 'auto' }}><RotateCcw size={14} /> Defaults</button>
          <button type="button" className="btn" onClick={onClose}>Cancel</button>
          <button className="btn primary" disabled={!port}><Save size={14} /> Save</button>
        </div>
      </form>
    </div>
  );
}
