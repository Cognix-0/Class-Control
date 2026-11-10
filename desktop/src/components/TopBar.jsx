import { Box, LayoutGrid, LocateFixed, Radio } from 'lucide-react';

export default function TopBar({ online, total, live, onLive, view, onView, onRecenter, panel, onPanel, address, sharing }) {
  const tab = (id, label) => (
    <button className={panel === id ? 'active' : ''} onClick={() => onPanel(panel === id ? null : id)}>{label}</button>
  );
  return (
    <header className="topbar">
      <div className="brand">Class<span>Control</span></div>
      <nav className="nav">
        <button className={panel === null ? 'active' : ''} onClick={() => onPanel(null)}>Lab</button>
        {tab('layout', 'Layout')}
        {tab('activity', 'Activity')}
      </nav>
      <div className="spacer" />
      {sharing > 0 && <span className="live-badge"><Radio size={12} /> SHARING TO {sharing}</span>}
      <span className="pill mono" title="Students connect to this address">{address}</span>
      <span className="pill"><span className={`dot ${online ? 'online' : ''}`} />{online} of {total} online</span>
      <button className={`switch ${live ? 'on' : ''}`} onClick={() => onLive(!live)} role="switch" aria-checked={live}>
        <span className="track" /> Live screens
      </button>
      {view === '3d' && <button className="btn sm" onClick={onRecenter} title="Back to the whole-room view"><LocateFixed size={15} /> Recenter</button>}
      <div className="seg" role="group" aria-label="View">
        <button className={view === '3d' ? 'active' : ''} onClick={() => onView('3d')}><Box size={15} /> 3D</button>
        <button className={view === '2d' ? 'active' : ''} onClick={() => onView('2d')}><LayoutGrid size={15} /> 2D</button>
      </div>
    </header>
  );
}
