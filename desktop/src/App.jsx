import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Copy } from 'lucide-react';
import StartScreen from './components/StartScreen.jsx';
import TopBar from './components/TopBar.jsx';
import SearchBox from './components/SearchBox.jsx';
import Lab3D from './components/Lab3D.jsx';
import LabFlat from './components/LabFlat.jsx';
import ActionDock from './components/ActionDock.jsx';
import InfoCard from './components/InfoCard.jsx';
import RemoteView from './components/RemoteView.jsx';
import ActivityLog from './components/ActivityLog.jsx';
import Whiteboard from './components/Whiteboard.jsx';
import LayoutEditor from './components/LayoutEditor.jsx';
import Toasts from './components/Toasts.jsx';
import Inbox from './components/Inbox.jsx';
import PresenterPip from './components/PresenterPip.jsx';
import { threadKey, who } from './names.js';
import { allSeats, assignSeats } from './seating.js';

let seq = 0;

export default function App() {
  const [session, setSession] = useState(null); // { port, password }
  const [info, setInfo] = useState({ ips: [] });
  const [pcs, setPcs] = useState({});
  const [layout, setLayout] = useState(null);
  const [selected, setSelected] = useState(new Set());
  const [focusId, setFocusId] = useState(null);
  const [flyTo, setFlyTo] = useState(null);
  const [view, setView] = useState('3d');
  const [panel, setPanel] = useState(null);
  const [live, setLive] = useState(false);
  const [sharing, setSharing] = useState(new Set());
  const [logs, setLogs] = useState([]);
  const [toasts, setToasts] = useState([]);
  const [remoteId, setRemoteId] = useState(null);
  const [dockRequest, setDockRequest] = useState(null);
  const [boardLive, setBoardLive] = useState(false); // students are seeing the whiteboard
  const [boardOpen, setBoardOpen] = useState(false); // the instructor's whiteboard editor is on screen
  const [chats, setChats] = useState([]); // { id, pcId, who, from: 'student'|'me', text, at }
  const [unread, setUnread] = useState(0);
  const [presenter, setPresenter] = useState(null);
  const saveTimer = useRef(null);
  const pcsRef = useRef(pcs);
  pcsRef.current = pcs;
  const panelRef = useRef(panel);
  panelRef.current = panel;

  const toast = useCallback((msg, level = 'ok') => {
    const id = ++seq;
    setToasts((t) => [...t.slice(-3), { id, msg, level }]);
    setTimeout(() => setToasts((t) => t.filter((x) => x.id !== id)), level === 'error' ? 6000 : 3500);
  }, []);
  const call = useCallback(async (p) => { const r = await p; if (!r.ok) toast(r.error, 'error'); return r; }, [toast]);

  // Lab events from the backend.
  useEffect(() => window.lab.onEvent((e) => {
    if (e.type === 'added' || e.type === 'updated') setPcs((p) => ({ ...p, [e.pc.id]: e.pc }));
    else if (e.type === 'removed') {
      setPcs((p) => { const n = { ...p }; delete n[e.id]; return n; });
      setSelected((s) => { if (!s.has(e.id)) return s; const n = new Set(s); n.delete(e.id); return n; });
      setRemoteId((r) => (r === e.id ? null : r));
    } else if (e.type === 'share') setSharing(new Set(e.ids));
    else if (e.type === 'presenter') setPresenter(e.id);
    else if (e.type === 'chat') {
      const pc = pcsRef.current[e.id];
      const name = pc ? who(pc) : e.id;
      setChats((c) => [...c.slice(-500), { id: ++seq, key: pc ? threadKey(pc) : e.id, who: name, from: 'student', text: e.text, at: e.at }]);
      if (panelRef.current !== 'messages') { setUnread((u) => u + 1); toast(`${name}: ${e.text}`, 'msg'); }
    } else if (e.type === 'sharereq') {
      const pc = pcsRef.current[e.id];
      if (panelRef.current !== 'messages') setUnread((u) => u + 1);
      toast(`${pc ? who(pc) : 'A student'} asks to share their screen. Open Messages to allow it.`, 'warn');
    }
    else if (e.type === 'log') {
      setLogs((l) => [...l.slice(-400), { ...e, id: ++seq }]);
      if (e.level !== 'info') toast(e.msg, e.level);
    }
  }), [toast]);

  async function started(s) {
    setSession(s);
    const [l, i, list] = await Promise.all([call(window.lab.getLayout()), call(window.lab.info()), call(window.lab.list())]);
    if (l.ok) setLayout(l.value);
    if (i.ok) { setInfo(i.value); setPresenter(i.value.presenter); }
    if (list.ok) setPcs(Object.fromEntries(list.value.map((p) => [p.id, p])));
  }

  const seats = useMemo(() => (layout ? allSeats(layout) : []), [layout]);
  const pcList = useMemo(() => Object.values(pcs), [pcs]);
  const { bySeat, seatOf, overflow } = useMemo(() => (layout ? assignSeats(layout, pcList, seats) : { bySeat: new Map(), seatOf: new Map(), overflow: [] }), [layout, pcList, seats]);
  const seatByKey = useMemo(() => new Map(seats.map((s) => [s.key, s])), [seats]);
  const where = useCallback((id) => { const s = seatByKey.get(seatOf.get(id)); return s ? `Block ${s.block} · Row ${s.row} · Seat ${s.col}` : 'No free seat'; }, [seatByKey, seatOf]);

  const changeLayout = useCallback((l) => {
    setLayout(l);
    clearTimeout(saveTimer.current);
    saveTimer.current = setTimeout(() => call(window.lab.saveLayout(l)), 400);
  }, [call]);

  // ---- selection ----
  const selectOnly = (ids) => setSelected(new Set(ids));
  const onSeatClick = (id, e) => {
    if (e.shiftKey || e.ctrlKey || e.metaKey) {
      setSelected((s) => { const n = new Set(s); n.has(id) ? n.delete(id) : n.add(id); return n; });
    } else { selectOnly([id]); setFocusId(id); }
  };
  const onBlockClick = (bi, add) => {
    const ids = seats.filter((s) => s.bi === bi).map((s) => bySeat.get(s.key)?.id).filter(Boolean);
    if (!ids.length) return toast(`No PCs in block ${layout.blocks[bi].name} yet`, 'warn');
    setSelected((s) => new Set(add ? [...s, ...ids] : ids));
  };
  const clear = () => { setSelected(new Set()); setFocusId(null); };
  const pick = (id) => {
    if (!id) { setFocusId(null); setFlyTo({ key: null, n: ++seq }); return; }
    selectOnly([id]); setFocusId(id); setFlyTo({ key: seatOf.get(id), n: ++seq });
  };

  useEffect(() => {
    const f = (e) => {
      if (remoteId || boardOpen || e.target.closest('input, textarea, select')) return;
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'a') { e.preventDefault(); selectOnly(pcList.map((p) => p.id)); }
      else if (e.key === 'Escape') clear();
    };
    window.addEventListener('keydown', f);
    return () => window.removeEventListener('keydown', f);
  }, [pcList, remoteId, boardOpen]);

  // ---- actions ----
  const send = async (cmd, arg, ids = [...selected]) => {
    if (!ids.length) return;
    const r = await call(window.lab.send(ids, cmd, arg));
    const n = `${ids.length} PC${ids.length === 1 ? '' : 's'}`;
    const done = { MSG: `Message sent to ${n}`, URL: `Link opened on ${n}`, LOCK: `Locked ${n}`, UNLOCK: `Unlocked ${n}`, SCREEN: `Sharing your screen with ${n}`, SCREENOFF: `Stopped sharing on ${n}`, BLOCK: `Blocked sites on ${n}`, ALLOW: `${n} can now only open the sites you listed`, UNBLOCK: `Unblocked sites on ${n}` };
    if (r.ok) toast(done[cmd]);
  };
  const present = async (id, mode) => {
    const r = await call(window.lab.present(id, mode));
    if (r.ok && mode === '1') toast(`${who(pcs[id])} is now on every screen`);
  };
  const reply = async (pcId, text) => {
    const r = await call(window.lab.send([pcId], 'MSG', text));
    if (r.ok) setChats((c) => [...c, { id: ++seq, key: threadKey(pcs[pcId]), who: who(pcs[pcId]), from: 'me', text, at: Date.now() }]);
  };
  const openPanel = (p) => { setPanel(p); if (p === 'messages') setUnread(0); };
  const cardAction = async (id, cmd) => {
    if (cmd === 'MSG') { selectOnly([id]); setDockRequest({ cmd, n: ++seq }); }
    else if (cmd === 'PRESENT') present(id, '1');
    else if (cmd === 'STOPPRESENT') present(id, '0');
    else if (cmd === 'DENY') present(id, 'deny');
    else if (cmd === 'FORGET') { const reg = pcs[id]?.student; const r = reg && await call(window.lab.forget(reg)); if (r?.ok) toast(`${reg}'s password is reset. Their next login sets a new one.`); }
    else send(cmd, '', [id]);
  };
  const toggleLive = async (on) => { const r = await call(window.lab.setLive(on)); if (r.ok) setLive(on); };

  const moveSeat = (host, key) => {
    if (!host || !layout) return;
    const pc = pcList.find((p) => p.host === host);
    if (!pc) return;
    const occupant = bySeat.get(key);
    const seatsMap = { ...layout.seats, [host]: key };
    const from = seatOf.get(pc.id);
    if (occupant && occupant.host !== host) { if (from) seatsMap[occupant.host] = from; else delete seatsMap[occupant.host]; }
    changeLayout({ ...layout, seats: seatsMap });
    const s = seatByKey.get(key);
    toast(`${host} moved to Block ${s.block} · Row ${s.row} · Seat ${s.col}`);
  };

  if (!session) return <StartScreen onStarted={started} />;
  if (!layout) return <div className="start"><div className="pulse" /></div>;

  const online = pcList.filter((p) => p.status !== 'stuck').length;
  const address = `${info.ips[0] ?? 'this-pc'} : ${session.port}`;
  const sel = [...selected];
  const sharingAll = sel.length > 0 && sel.every((id) => sharing.has(id));
  const focusPc = focusId && pcs[focusId];
  const remotePc = remoteId && pcs[remoteId];
  const presenterPc = presenter && pcs[presenter];

  const common = { layout, seats, bySeat, selected, focusId, onSeatClick, onSeatDouble: setRemoteId, onBlockClick, onEmpty: clear };

  return (
    <div className="app">
      <TopBar online={online} total={pcList.length} live={live} onLive={toggleLive} view={view} onView={setView} onRecenter={() => pick(null)}
        panel={panel} onPanel={openPanel} address={address} sharing={sharing.size} unread={unread}
        boardLive={boardLive} onBoard={() => { setBoardLive(true); setBoardOpen(true); }} />
      <div className="stage-area">
        <div className="map">
          {view === '3d'
            ? <Lab3D {...common} seatOfPc={seatOf} flyTo={flyTo} sharing={sharing.size > 0} onRemote={setRemoteId} onAction={cardAction} onCloseInfo={() => setFocusId(null)} />
            : <LabFlat {...common} onBox={(ids, add) => setSelected((s) => new Set(add ? [...s, ...ids] : ids))} onMove={moveSeat} />}
          <SearchBox pcs={pcList} where={where} onPick={pick} />
          {view === '2d' && focusPc && (
            <div className="side-info">
              <InfoCard pc={focusPc} seat={seatByKey.get(seatOf.get(focusId))} onRemote={() => setRemoteId(focusId)} onAction={(cmd) => cardAction(focusId, cmd)} onClose={() => setFocusId(null)} />
            </div>
          )}
          {pcList.length === 0 && (
            <div className="waiting">
              <div className="pulse" />
              <div>
                <div style={{ fontWeight: 600 }}>Waiting for student PCs…</div>
                <div className="muted" style={{ fontSize: 13, margin: '4px 0 8px' }}>Student PCs on this network join by themselves: run the agent as Administrator and enter the class password once. Or connect by hand:</div>
                <code>java Agent.java {info.ips[0] ?? '<this PC IP>'} {'<password>'} {session.port}</code>
              </div>
              <button className="btn sm" onClick={() => { navigator.clipboard?.writeText(`java Agent.java ${info.ips[0] ?? ''} ${session.password} ${session.port}`); toast('Command copied (includes the password)'); }}>
                <Copy size={14} /> Copy
              </button>
            </div>
          )}
          {presenterPc && <PresenterPip pc={presenterPc} onStop={() => present('', '0')} />}
          {overflow.length > 0 && <div className="waiting" style={{ bottom: 112 }}><b>{overflow.length} PC{overflow.length === 1 ? '' : 's'}</b>&nbsp;have no seat. Add rows or blocks in Layout.</div>}
          <ActionDock count={selected.size} total={pcList.length} sharingAll={sharingAll} onSend={send} request={dockRequest}
            onRemote={() => setRemoteId(sel[0])} onSelectAll={() => selectOnly(pcList.map((p) => p.id))} onClear={clear} />
        </div>
        {panel === 'messages' && <Inbox chats={chats} pcs={pcList} onReply={reply} onPresent={present} onLocate={(id) => { setView('3d'); pick(id); }} onClose={() => setPanel(null)} />}
        {panel === 'activity' && <ActivityLog logs={logs} onClear={() => setLogs([])} onClose={() => setPanel(null)} />}
        {panel === 'layout' && <LayoutEditor layout={layout} onChange={changeLayout} onClose={() => setPanel(null)} seatCount={seats.length} pcCount={pcList.length} />}
      </div>
      {boardLive && <Whiteboard visible={boardOpen} onMinimise={() => setBoardOpen(false)} onStop={() => { setBoardLive(false); setBoardOpen(false); }} />}
      {remotePc && <RemoteView pc={remotePc} onClose={() => setRemoteId(null)} onSend={(cmd, arg) => send(cmd, arg, [remotePc.id])} />}
      <Toasts items={toasts} />
    </div>
  );
}
