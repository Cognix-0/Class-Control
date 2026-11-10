import { useEffect, useRef, useState } from 'react';
import { ArrowLeft, Keyboard, Lock, Maximize2, MessageSquare, MousePointer2, Send, Unlock, Eye } from 'lucide-react';
import { javaKey } from '../keymap.js';

const clamp = (n) => Math.min(1, Math.max(0, n)).toFixed(4);
const BUTTON = { 0: 1, 1: 2, 2: 3 }; // DOM button -> Java button number

export default function RemoteView({ pc, onClose, onSend }) {
  const [frame, setFrame] = useState(null);
  const [control, setControl] = useState(false);
  const [fps, setFps] = useState(5);
  const [slow, setSlow] = useState(false);
  const [text, setText] = useState(null); // null = closed, string = text box open
  const [mode, setMode] = useState('type'); // 'type' = paste on PC, 'msg' = popup message
  const img = useRef(null);
  const root = useRef(null);
  const pressed = useRef(new Set());
  const lastMove = useRef(0);
  const id = pc.id;
  const remote = (cmd, arg) => window.lab.remote(id, cmd, arg);

  // Start / restart the stream; stop it when leaving.
  useEffect(() => {
    remote('VIEW', String(fps));
    const off = window.lab.onEvent((e) => { if (e.type === 'frame' && e.id === id) setFrame(e.data); });
    return () => { off(); remote('VIEWOFF', ''); };
  }, [id, fps]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    if (frame) return setSlow(false);
    const t = setTimeout(() => setSlow(true), 5000);
    return () => clearTimeout(t);
  }, [frame]);

  const releaseKeys = () => { for (const vk of pressed.current) remote('KEY', `up,${vk}`); pressed.current.clear(); };

  // While in control, every key goes to the student PC (Esc included). Stop control with the button.
  useEffect(() => {
    if (!control) return undefined;
    const handle = (type) => (e) => {
      if (e.target.closest?.('input')) return;
      const vk = javaKey(e.code);
      if (vk === undefined) return;
      e.preventDefault();
      if (type === 'down') pressed.current.add(vk); else pressed.current.delete(vk);
      remote('KEY', `${type},${vk}`);
    };
    const kd = handle('down'), ku = handle('up');
    window.addEventListener('keydown', kd);
    window.addEventListener('keyup', ku);
    window.addEventListener('blur', releaseKeys);
    return () => { window.removeEventListener('keydown', kd); window.removeEventListener('keyup', ku); window.removeEventListener('blur', releaseKeys); releaseKeys(); };
  }, [control]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    if (control) return undefined;
    const f = (e) => { if (e.key === 'Escape' && text === null) onClose(); };
    window.addEventListener('keydown', f);
    return () => window.removeEventListener('keydown', f);
  }, [control, text, onClose]);

  function pos(e) {
    const r = img.current.getBoundingClientRect();
    return `${clamp((e.clientX - r.left) / r.width)},${clamp((e.clientY - r.top) / r.height)}`;
  }
  const mouse = control ? {
    onMouseMove: (e) => { const now = performance.now(); if (now - lastMove.current < 30) return; lastMove.current = now; remote('MOUSE', `move,${pos(e)},0`); },
    onMouseDown: (e) => { e.preventDefault(); remote('MOUSE', `down,${pos(e)},${BUTTON[e.button] ?? 1}`); },
    onMouseUp: (e) => remote('MOUSE', `up,${pos(e)},${BUTTON[e.button] ?? 1}`),
    onWheel: (e) => remote('MOUSE', `wheel,${pos(e)},${Math.sign(e.deltaY) * Math.min(5, Math.max(1, Math.round(Math.abs(e.deltaY) / 60)))}`),
    onContextMenu: (e) => e.preventDefault(),
    onDragStart: (e) => e.preventDefault(),
  } : {};

  return (
    <div className="remote" ref={root} role="dialog" aria-label={`Remote view of ${pc.host}`}>
      <div className="remote-bar">
        <button className="btn ghost" onClick={onClose}><ArrowLeft size={16} /> Back to lab</button>
        <div className="title">{pc.host} <span className="mono">{pc.ip}</span></div>
        {control && <span className="live-badge">● CONTROLLING</span>}
        <div className="spacer" />
        <div className="seg" role="group" aria-label="Mode">
          <button className={!control ? 'active' : ''} onClick={() => setControl(false)}><Eye size={15} /> View only</button>
          <button className={control ? 'active' : ''} onClick={() => setControl(true)} disabled={!frame}><MousePointer2 size={15} /> Take control</button>
        </div>
        <button className="btn sm" onClick={() => { setMode('type'); setText(''); }}><Keyboard size={14} /> Send text</button>
        <button className="btn sm" onClick={() => { setMode('msg'); setText(''); }}><MessageSquare size={14} /> Message</button>
        {pc.locked
          ? <button className="btn sm" onClick={() => onSend('UNLOCK', '')}><Unlock size={14} /> Unlock</button>
          : <button className="btn sm danger" onClick={() => onSend('LOCK', '')}><Lock size={14} /> Lock</button>}
        <select className="btn sm" value={fps} onChange={(e) => setFps(Number(e.target.value))} aria-label="Frame rate">
          <option value={2}>Smooth network · 2 fps</option>
          <option value={5}>Balanced · 5 fps</option>
          <option value={10}>Fast · 10 fps</option>
        </select>
        <button className="btn sm icon-btn" onClick={() => root.current.requestFullscreen?.()} aria-label="Full screen"><Maximize2 size={15} /></button>
      </div>
      <div className={`remote-screen ${control ? 'control' : ''}`}>
        {frame
          ? <img ref={img} alt={`${pc.host} screen`} src={`data:image/jpeg;base64,${frame}`} draggable={false} {...mouse} />
          : (
            <div className="remote-wait">
              <div className="pulse" style={{ margin: '0 auto 14px' }} />
              {slow
                ? <>No picture from <b>{pc.host}</b> yet. Make sure it runs the latest Agent, and on macOS that Java has Screen Recording and Accessibility permission.</>
                : <>Connecting to {pc.host}…</>}
            </div>
          )}
        {control && <div className="control-hint">Your mouse and keyboard now control {pc.host}. Click “View only” to stop.</div>}
      </div>
      {text !== null && (
        <div className="backdrop" onMouseDown={() => setText(null)}>
          <form className="modal" onMouseDown={(e) => e.stopPropagation()} onSubmit={(e) => { e.preventDefault(); if (text.trim()) { if (mode === 'msg') onSend('MSG', text.trim()); else remote('TYPE', text); } setText(null); }}>
            <h3>{mode === 'msg' ? `Message ${pc.host}` : `Type text on ${pc.host}`}</h3>
            <p>{mode === 'msg' ? 'Shows as a pop-up on the student PC.' : 'Pasted where the cursor is on the student PC.'}</p>
            <input className="input" autoFocus value={text} onChange={(e) => setText(e.target.value)} onKeyDown={(e) => e.key === 'Escape' && setText(null)} />
            <div className="row" style={{ marginTop: 14 }}>
              <button type="button" className="btn" onClick={() => setText(null)}>Cancel</button>
              <button className="btn primary" disabled={!text.trim()}><Send size={14} /> Send</button>
            </div>
          </form>
        </div>
      )}
    </div>
  );
}
