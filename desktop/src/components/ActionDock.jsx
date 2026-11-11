import { useEffect, useRef, useState } from 'react';
import { Ban, CheckSquare, Link2, ListChecks, Lock, MessageSquare, MonitorUp, MonitorX, MousePointer2, ShieldCheck, Unlock, XSquare } from 'lucide-react';
import ConfirmModal from './ConfirmModal.jsx';

// Commands that take text, with presets and input validation.
const INPUTS = {
  MSG: { title: 'Send a message', placeholder: 'Type a message for the students', chips: ['Please save your work', '5 minutes left', 'Eyes on the projector', 'Class is over — log off'], ok: (v) => v.trim().length > 0 },
  URL: { title: 'Open a link on their screens', placeholder: 'https://…', chips: ['https://classroom.google.com', 'https://www.w3schools.com'], ok: (v) => /^https?:\/\/\S+$/i.test(v.trim()), bad: 'Links must start with http:// or https://' },
  BLOCK: { title: 'Block websites', placeholder: 'youtube.com, facebook.com', chips: ['youtube.com', 'facebook.com', 'tiktok.com', 'instagram.com'], ok: (v) => /^[a-z0-9.-]+(\s*,\s*[a-z0-9.-]+)*$/i.test(v.trim()), bad: 'Use domain names separated by commas', append: true },
  ALLOW: { title: 'Only allow these websites', placeholder: 'school.edu, wikipedia.org', chips: ['wikipedia.org', 'classroom.google.com', 'khanacademy.org', 'w3schools.com'], ok: (v) => /^[a-z0-9.-]+(\s*,\s*[a-z0-9.-]+)*$/i.test(v.trim()), bad: 'Use domain names separated by commas', append: true },
};
const DANGER = new Set(['LOCK', 'BLOCK', 'ALLOW']);
const VERB = { LOCK: 'Lock screens', BLOCK: 'Block websites', ALLOW: 'Allow only these sites' };

export default function ActionDock({ count, total, sharingAll, onSend, onRemote, onSelectAll, onClear, request }) {
  const [pop, setPop] = useState(null); // command whose input popover is open
  const [val, setVal] = useState('');
  const [confirm, setConfirm] = useState(null);
  const input = useRef(null);
  const none = count === 0;

  useEffect(() => { if (request) open(request.cmd); }, [request]); // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => { if (pop) input.current?.focus(); }, [pop]);

  function open(cmd) {
    if (INPUTS[cmd]) { setPop(cmd); setVal(''); } else go(cmd, '');
  }
  function go(cmd, arg) {
    setPop(null);
    if (DANGER.has(cmd) && count > 1) setConfirm({ cmd, arg });
    else onSend(cmd, arg);
  }
  const spec = pop && INPUTS[pop];
  const valid = spec && spec.ok(val);

  const A = ({ cmd, icon: I, label, danger, on, disabled, onClick }) => (
    <button className={`btn act ${danger ? 'danger' : ''} ${on ? 'on' : ''}`} disabled={disabled ?? none} onClick={onClick ?? (() => open(cmd))} title={label}>
      <I size={19} />{label}
    </button>
  );

  return (
    <div className="dock" onPointerDown={(e) => e.stopPropagation()}>
      <div className="count">{none ? <span className="muted">Select PCs to act</span> : <><b>{count}</b> PC{count === 1 ? '' : 's'} selected</>}</div>
      <button className="btn ghost sm icon-btn" title="Select all (Ctrl+A)" onClick={onSelectAll} disabled={!total}><CheckSquare size={16} /></button>
      <button className="btn ghost sm icon-btn" title="Clear selection (Esc)" onClick={onClear} disabled={none}><XSquare size={16} /></button>
      <div className="sep" />
      <A cmd="MSG" icon={MessageSquare} label="Message" />
      <A cmd="URL" icon={Link2} label="Open link" />
      <div className="sep" />
      <A cmd="LOCK" icon={Lock} label="Lock" danger />
      <A cmd="UNLOCK" icon={Unlock} label="Unlock" />
      {sharingAll
        ? <A cmd="SCREENOFF" icon={MonitorX} label="Stop share" on />
        : <A cmd="SCREEN" icon={MonitorUp} label="Share screen" />}
      <div className="sep" />
      <A cmd="BLOCK" icon={Ban} label="Block sites" danger />
      <A cmd="ALLOW" icon={ListChecks} label="Only allow" danger />
      <A cmd="UNBLOCK" icon={ShieldCheck} label="Unblock" />
      <div className="sep" />
      <A icon={MousePointer2} label="Control" disabled={count !== 1} onClick={onRemote} />

      {spec && (
        <form className="pop" onSubmit={(e) => { e.preventDefault(); if (valid) go(pop, val.trim()); }}>
          <h4>{spec.title} <span className="muted" style={{ fontWeight: 400 }}>· {count} PC{count === 1 ? '' : 's'}</span></h4>
          <div className="row">
            <input ref={input} className="input" value={val} placeholder={spec.placeholder} onChange={(e) => setVal(e.target.value)}
              onKeyDown={(e) => e.key === 'Escape' && setPop(null)} aria-label={spec.title} />
            <button className="btn primary" disabled={!valid}>Send</button>
          </div>
          {val && !valid && spec.bad && <div className="error" style={{ margin: '8px 0 0' }}>{spec.bad}</div>}
          <div className="chips">
            {spec.chips.map((c) => (
              <button type="button" key={c} className="chip" onClick={() => setVal(spec.append && val.trim() ? `${val.replace(/[,\s]+$/, '')}, ${c}` : c)}>{c}</button>
            ))}
          </div>
        </form>
      )}
      {confirm && (
        <ConfirmModal title={`${VERB[confirm.cmd]} on ${count} PCs?`}
          body={confirm.cmd === 'LOCK' ? 'Students will see a full-screen lock until you unlock them.' : confirm.cmd === 'ALLOW' ? `Students will only be able to open: ${confirm.arg}` : `Students won't be able to open: ${confirm.arg}`}
          confirm={VERB[confirm.cmd]} onNo={() => setConfirm(null)} onYes={() => { onSend(confirm.cmd, confirm.arg); setConfirm(null); }} />
      )}
    </div>
  );
}
