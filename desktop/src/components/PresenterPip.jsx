import { useEffect, useState } from 'react';
import { MonitorUp, Square } from 'lucide-react';
import { who } from '../names.js';

// Small preview of the screen a student is showing to the class, with Stop.
export default function PresenterPip({ pc, onStop }) {
  const [frame, setFrame] = useState(null);
  useEffect(() => window.lab.onEvent((e) => { if (e.type === 'frame' && e.id === pc.id) setFrame(e.data); }), [pc.id]);
  return (
    <div className="pip" role="region" aria-label="Student presenting">
      <div className="pip-head">
        <MonitorUp size={14} /><span>{who(pc)} is presenting to the class</span>
        <div className="spacer" />
        <button className="btn sm danger" onClick={onStop}><Square size={12} /> Stop</button>
      </div>
      {frame ? <img alt={`${pc.host} screen`} src={`data:image/jpeg;base64,${frame}`} /> : <div className="pip-wait muted">Waiting for the first picture…</div>}
    </div>
  );
}
