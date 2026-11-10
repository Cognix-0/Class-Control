import { X } from 'lucide-react';

export default function ActivityLog({ logs, onClear, onClose }) {
  return (
    <aside className="panel" aria-label="Activity">
      <div className="panel-head"><h2>Activity</h2><div className="spacer" />
        <button className="btn sm" onClick={onClear}>Clear</button>
        <button className="btn ghost sm icon-btn" style={{ width: 30 }} onClick={onClose} aria-label="Close"><X size={15} /></button>
      </div>
      <div className="panel-body">
        {logs.length === 0 && <div className="muted">Nothing yet. Connections and commands appear here.</div>}
        {[...logs].reverse().map((l) => (
          <div key={l.id} className={`log-row ${l.level}`}><time>{new Date(l.at).toLocaleTimeString()}</time><span>{l.msg}</span></div>
        ))}
      </div>
    </aside>
  );
}
