import { Check, Hand, KeyRound, Lock, MessageSquare, Monitor, MonitorUp, MousePointer2, Square, Unlock, UserRound, X } from 'lucide-react';

const STATUS = { online: 'Online', locked: 'Locked', stuck: 'Not responding' };

export default function InfoCard({ pc, seat, onRemote, onAction, onClose }) {
  return (
    <div className="info" onPointerDown={(e) => e.stopPropagation()}>
      <div className="info-head">
        <div className="ico"><Monitor size={18} /></div>
        <div style={{ minWidth: 0 }}>
          <div className="info-name">{pc.host}</div>
          <div className="info-ip">{pc.ip}</div>
        </div>
        <button className="btn ghost sm icon-btn info-x" style={{ width: 28 }} onClick={onClose} aria-label="Close"><X size={14} /></button>
      </div>
      <div className="info-student">
        <UserRound size={15} />
        {pc.student ? <><b>{pc.student}</b><div className="spacer" />
          <button className="btn ghost sm" onClick={() => onAction('FORGET')} title="The student's next login sets a new password"><KeyRound size={13} /> Reset password</button></>
          : <span className="muted">No student logged in</span>}
      </div>
      {pc.shareRequest && (
        <div className="share-req">
          <Hand size={16} /><div style={{ flex: 1 }}>Wants to share their screen</div>
          <button className="btn sm primary" onClick={() => onAction('PRESENT')}><Check size={14} /> Allow</button>
          <button className="btn sm" onClick={() => onAction('DENY')}>Deny</button>
        </div>
      )}
      <div className="info-grid">
        <div><div className="k">Block</div><div className="v block">{seat?.block ?? '–'}</div></div>
        <div><div className="k">Row</div><div className="v">{seat?.row ?? '–'}</div></div>
        <div><div className="k">Seat</div><div className="v">{seat?.col ?? '–'}</div></div>
      </div>
      <div className="info-meta">
        <span style={{ display: 'flex', alignItems: 'center', gap: 6 }}><span className={`dot ${pc.status}`} />{STATUS[pc.status]}</span>
        <span className="mono">seen {new Date(pc.lastSeen).toLocaleTimeString()}</span>
      </div>
      <div className="info-actions">
        <button className="btn primary wide" onClick={onRemote}><MousePointer2 size={15} /> Remote control</button>
        <button className="btn sm" onClick={() => onAction('MSG')}><MessageSquare size={14} /> Message</button>
        {pc.locked
          ? <button className="btn sm" onClick={() => onAction('UNLOCK')}><Unlock size={14} /> Unlock</button>
          : <button className="btn sm danger" onClick={() => onAction('LOCK')}><Lock size={14} /> Lock</button>}
        {pc.presenting
          ? <button className="btn sm danger wide" onClick={() => onAction('STOPPRESENT')}><Square size={13} /> Stop showing to class</button>
          : !pc.shareRequest && <button className="btn sm wide" onClick={() => onAction('PRESENT')} title="Show this PC's screen on every student screen"><MonitorUp size={14} /> Show to class</button>}
      </div>
    </div>
  );
}
